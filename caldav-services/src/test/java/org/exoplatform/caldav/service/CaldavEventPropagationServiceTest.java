/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.caldav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import org.exoplatform.agenda.constant.AgendaEventModificationType;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * What an edit or a cancellation made in eXo does to the calendar copies other
 * people hold.
 *
 * <p>
 * The regression these pin is that it did nothing at all: the only push of an
 * event was the one the editing user's own browser made, so an organiser who
 * moved a meeting moved it on their own phone and on nobody else's.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavEventPropagationServiceTest {

  private static final long                     EVENT     = 8801L;

  private static final long                     ALICE     = 11L;

  private static final long                     BOB       = 22L;

  private static final long                     CAROL     = 33L;

  private static final Set<AgendaEventModificationType> A_REAL_EDIT =
                                                                    EnumSet.of(AgendaEventModificationType.UPDATED,
                                                                               AgendaEventModificationType.START_DATE_UPDATED);

  @Mock
  private CaldavPushService                     caldavPushService;

  @Mock
  private CaldavSyncStorage                     caldavSyncStorage;

  @Mock
  private AgendaEventService                    agendaEventService;

  @InjectMocks
  private CaldavEventPropagationService         service;

  /**
   * By default the edited event is a stand-alone meeting, so the series lookup
   * finds nothing extra to consider.
   */
  @BeforeEach
  public void theEventIsAnOrdinaryMeeting() {
    Event event = new Event();
    event.setId(EVENT);
    event.setParentId(0);
    lenient().when(agendaEventService.getEventById(EVENT)).thenReturn(event);
  }

  /**
   * The defect itself: an attendee who already holds a copy gets the edit.
   * Before this service existed, nothing wrote to anybody's copy but the
   * editor's own.
   */
  @Test
  public void anEditReachesTheCopyAnAttendeeAlreadyHolds() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT, null)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT, null);
  }

  /**
   * Every holder, not the first one. Fifty attendees means fifty accounts, and
   * a loop that stopped at one would look like it worked.
   */
  @Test
  public void anEditReachesEveryHolderOfACopy() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    when(caldavPushService.pushAgendaEvent(anyLong(), eq(EVENT), any())).thenReturn(new ObjectSync());

    assertEquals(2, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT, null);
    verify(caldavPushService).pushAgendaEvent(BOB, EVENT, null);
  }

  /**
   * The guard the task asks for by name. An attendee who has never had a copy
   * of this meeting must not acquire one because somebody edited it — seeding
   * a copy is a different decision, taken elsewhere.
   */
  @Test
  public void anAttendeeWithNoCopyDoesNotAcquireOne() {
    givenNoHolders();

    assertEquals(0, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong(), any());
  }

  /**
   * A mapping with no href is the tombstone a removal leaves, not a copy.
   * Writing to it would re-create on the server the object somebody deleted.
   */
  @Test
  public void aTombstoneMappingIsNotACopyAndIsNotWrittenTo() {
    givenHolders(mapping(1L, 100L, "uid-8801", null));
    // The pair resolves perfectly well: the only thing that must stop this
    // push is the missing href, and a test that let something else stop it
    // would pass against the very bug it pins.
    givenPair(100L, ALICE);

    assertEquals(0, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong(), any());
  }

  /**
   * One unreachable server is an ordinary Tuesday, not a reason the other
   * attendees keep a stale meeting.
   */
  @Test
  public void oneUnreachableServerDoesNotStopTheOthers() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"),
                 mapping(3L, 300L, "uid-8801", "/dav/carol/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    givenPair(300L, CAROL);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT, null)).thenReturn(new ObjectSync());
    when(caldavPushService.pushAgendaEvent(BOB, EVENT, null))
                                                             .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                                "bob's server is down"));
    when(caldavPushService.pushAgendaEvent(CAROL, EVENT, null)).thenReturn(new ObjectSync());

    assertEquals(2, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(CAROL, EVENT, null);
  }

  /**
   * And an error rather than an exception, which is the shape that once escaped
   * a {@code catch (RuntimeException)} here and took a whole sweep with it.
   */
  @Test
  public void aLinkageErrorFromOneServerDoesNotStopTheOthers() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT, null)).thenThrow(new NoSuchMethodError("a half-assembled classpath"));
    when(caldavPushService.pushAgendaEvent(BOB, EVENT, null)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(BOB, EVENT, null);
  }

  /**
   * A change no copy can show buys nobody anything and costs three network
   * round trips per attendee.
   */
  @Test
  public void anEditNoCopyCanShowIsNotCarriedOut() {
    assertEquals(0,
                 service.propagateUpdate(EVENT,
                                         EnumSet.of(AgendaEventModificationType.UPDATED,
                                                    AgendaEventModificationType.COLOR_UPDATED,
                                                    AgendaEventModificationType.AVAILABILITY_UPDATED)));

    verify(caldavSyncStorage, never()).getObjectsByEvent(anyLong(), anyInt(), anyInt());
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong(), any());
  }

  /**
   * The deny-list fails towards carrying, not towards skipping: a modification
   * type nobody has classified yet is pushed, because a modification silently
   * not carried is the defect this whole thing exists to end.
   */
  @Test
  public void aModificationNobodyClassifiedIsCarriedRatherThanSkipped() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT, null)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, EnumSet.of(AgendaEventModificationType.CONFERENCE_ADDED)));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT, null);
  }

  /**
   * Cancelling is an update in agenda, so it takes the ordinary rewrite path —
   * and it must, because that is what puts {@code STATUS:CANCELLED} on the copy
   * rather than making the meeting vanish.
   */
  @Test
  public void aCancellationIsCarriedAsARewriteNotAsARemoval() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT, null)).thenReturn(new ObjectSync());

    assertEquals(1,
                 service.propagateUpdate(EVENT,
                                         EnumSet.of(AgendaEventModificationType.UPDATED,
                                                    AgendaEventModificationType.STATUS_UPDATED)));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT, null);
    verify(caldavPushService, never()).deleteEvent(anyLong(), anyString());
  }

  /**
   * A destroyed meeting is removed from every copy that exists, by the
   * iCalendar identity the mapping row carries — agenda no longer has one to
   * ask.
   */
  @Test
  public void aDeletedMeetingIsRemovedFromEveryCopy() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);

    assertEquals(2, service.propagateDeletion(EVENT));

    verify(caldavPushService).deleteEvent(ALICE, "uid-8801");
    verify(caldavPushService).deleteEvent(BOB, "uid-8801");
  }

  /**
   * A deletion never reads agenda for the event, and cannot: the row is already
   * gone by the time the broadcast arrives.
   */
  @Test
  public void aDeletionDoesNotAskAgendaForAnEventItNoLongerHas() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);

    service.propagateDeletion(EVENT);

    verify(agendaEventService, never()).getEventById(anyLong());
  }

  /**
   * One server refusing the removal leaves the others removed.
   */
  @Test
  public void aRemovalThatFailsForOneHolderStillHappensForTheOthers() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    org.mockito.Mockito.doThrow(new CaldavPushException(CaldavPushService.SAVE, "alice's server is down"))
                       .when(caldavPushService)
                       .deleteEvent(ALICE, "uid-8801");

    assertEquals(1, service.propagateDeletion(EVENT));

    verify(caldavPushService).deleteEvent(BOB, "uid-8801");
  }

  /**
   * An override and its series share one calendar object, written under the
   * series' identity. Editing the override alone would otherwise find no copy
   * at all.
   */
  @Test
  public void editingOneOccurrenceReachesTheCopyWrittenUnderItsSeries() {
    long override = 9902L;
    long series = 9900L;
    Event exceptional = new Event();
    exceptional.setId(override);
    exceptional.setParentId(series);
    when(agendaEventService.getEventById(override)).thenReturn(exceptional);
    givenNoHoldersFor(override);
    givenHoldersFor(series, mapping(1L, 100L, "uid-9900", "/dav/alice/mirror/uid-9900.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, override, null)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(override, A_REAL_EDIT));

    // Pushed under the override's own id, so the merge splices the amended
    // instance into the series object rather than replacing the series.
    verify(caldavPushService).pushAgendaEvent(ALICE, override, null);
  }

  /**
   * One user, two collections, one meeting: the copy is written once. Two
   * writes would be two attempts to settle the same object, the second of them
   * refused by the first's own ETag.
   */
  @Test
  public void aUserMappedInTwoCollectionsIsWrittenToOnce() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 101L, "uid-8801", "/dav/alice/personal/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(101L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT, null)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT, null);
  }

  /**
   * The fan-out is walked in slices, and the second slice is not forgotten.
   */
  @Test
  public void everySliceOfHoldersIsWalked() {
    List<ObjectSync> first = new ArrayList<>();
    for (long i = 0; i < 50; i++) {
      first.add(mapping(i + 1, 1000L + i, "uid-8801", "/dav/u" + i + "/mirror/uid-8801.ics"));
      givenPair(1000L + i, 1000L + i);
    }
    ObjectSync last = mapping(51L, 2000L, "uid-8801", "/dav/last/mirror/uid-8801.ics");
    givenPair(2000L, 2000L);
    when(caldavSyncStorage.getObjectsByEvent(EVENT, 0, 50)).thenReturn(new PageImpl<>(first, PageRequest.of(0, 50), 51));
    when(caldavSyncStorage.getObjectsByEvent(EVENT, 1, 50)).thenReturn(new PageImpl<>(List.of(last),
                                                                                     PageRequest.of(1, 50),
                                                                                     51));
    when(caldavPushService.pushAgendaEvent(anyLong(), eq(EVENT), any())).thenReturn(new ObjectSync());

    assertEquals(51, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(2000L, EVENT, null);
  }

  // ---------------------------------------------------------------- fixtures

  /**
   * Declares the mappings the edited event has, on its own id.
   *
   * @param mappings the mapping rows the storage answers
   */
  private void givenHolders(ObjectSync... mappings) {
    givenHoldersFor(EVENT, mappings);
  }

  /**
   * Declares the mappings one event id has.
   *
   * @param eventId the event the mappings belong to
   * @param mappings the mapping rows the storage answers
   */
  private void givenHoldersFor(long eventId, ObjectSync... mappings) {
    Page<ObjectSync> page = new PageImpl<>(List.of(mappings), PageRequest.of(0, 50), mappings.length);
    lenient().when(caldavSyncStorage.getObjectsByEvent(eventId, 0, 50)).thenReturn(page);
  }

  /**
   * Declares that the edited event has no mapping at all.
   */
  private void givenNoHolders() {
    givenNoHoldersFor(EVENT);
  }

  /**
   * Declares that one event id has no mapping at all.
   *
   * @param eventId the event with no copy anywhere
   */
  private void givenNoHoldersFor(long eventId) {
    lenient().when(caldavSyncStorage.getObjectsByEvent(eq(eventId), anyInt(), anyInt()))
             .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
  }

  /**
   * Declares which user a collection belongs to.
   *
   * @param pairId the collection pair
   * @param userIdentityId its owner
   */
  private void givenPair(long pairId, long userIdentityId) {
    CalendarSync pair = new CalendarSync();
    pair.setId(pairId);
    pair.setUserIdentityId(userIdentityId);
    lenient().when(caldavSyncStorage.getPair(pairId)).thenReturn(pair);
  }

  /**
   * One mapping row.
   *
   * @param id the row's identifier
   * @param pairId the collection it lives in
   * @param icsUid the iCalendar identity of the object
   * @param href where the object sits, null for a tombstone
   * @return the mapping
   */
  private ObjectSync mapping(long id, long pairId, String icsUid, String href) {
    ObjectSync mapping = new ObjectSync();
    mapping.setId(id);
    mapping.setCalendarSyncId(pairId);
    mapping.setLocalEventId(EVENT);
    mapping.setIcsUid(icsUid);
    mapping.setRemoteHref(href);
    mapping.setEtag("\"v1\"");
    mapping.setLastSync(new Date());
    return mapping;
  }
}
