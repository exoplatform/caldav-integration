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
import org.exoplatform.agenda.model.EventAttendee;
import org.exoplatform.agenda.model.EventAttendeeList;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.identity.provider.SpaceIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * What happens to a meeting in eXo — created, edited, cancelled, destroyed —
 * and what each of those does to the calendar copies people hold.
 *
 * <p>
 * Two regressions are pinned here. The edit path did nothing at all: the only
 * push of an event was the one the editing user's own browser made, so an
 * organiser who moved a meeting moved it on their own phone and on nobody
 * else's. And the creation path did not exist (EXO-89754): a new meeting only
 * ever acquired a copy once somebody edited or answered it, or once the
 * background seeding pass happened to find it in its upcoming window.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavEventPropagationServiceTest {

  private static final long                     EVENT     = 8801L;

  private static final long                     ALICE     = 11L;

  private static final long                     BOB       = 22L;

  private static final long                     CAROL     = 33L;

  /**
   * Whoever created the meeting. Never one of the identities a creation test
   * expects a copy for: their own copy is written by their browser, not here.
   */
  private static final long                     AUTHOR    = 44L;

  private static final Set<AgendaEventModificationType> A_REAL_EDIT =
                                                                    EnumSet.of(AgendaEventModificationType.UPDATED,
                                                                               AgendaEventModificationType.START_DATE_UPDATED);

  @Mock
  private CaldavPushService                     caldavPushService;

  @Mock
  private CaldavSyncStorage                     caldavSyncStorage;

  @Mock
  private AgendaEventService                    agendaEventService;

  @Mock
  private AgendaEventAttendeeService            agendaEventAttendeeService;

  @Mock
  private IdentityManager                       identityManager;

  @Mock
  private CaldavPendingInvitationService        caldavPendingInvitationService;

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
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
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
    when(caldavPushService.pushAgendaEvent(anyLong(), eq(EVENT))).thenReturn(new ObjectSync());

    assertEquals(2, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
    verify(caldavPushService).pushAgendaEvent(BOB, EVENT);
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

    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
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

    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
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
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());
    when(caldavPushService.pushAgendaEvent(BOB, EVENT))
                                                             .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                                "bob's server is down"));
    when(caldavPushService.pushAgendaEvent(CAROL, EVENT)).thenReturn(new ObjectSync());

    assertEquals(2, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(CAROL, EVENT);
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
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenThrow(new NoSuchMethodError("a half-assembled classpath"));
    when(caldavPushService.pushAgendaEvent(BOB, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(BOB, EVENT);
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
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
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
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, EnumSet.of(AgendaEventModificationType.CONFERENCE_ADDED)));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
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
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1,
                 service.propagateUpdate(EVENT,
                                         EnumSet.of(AgendaEventModificationType.UPDATED,
                                                    AgendaEventModificationType.STATUS_UPDATED)));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
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
    when(caldavPushService.pushAgendaEvent(ALICE, override)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(override, A_REAL_EDIT));

    // Pushed under the override's own id, so the merge splices the amended
    // instance into the series object rather than replacing the series.
    verify(caldavPushService).pushAgendaEvent(ALICE, override);
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
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
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
    when(caldavPushService.pushAgendaEvent(anyLong(), eq(EVENT))).thenReturn(new ObjectSync());

    assertEquals(51, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(2000L, EVENT);
  }

  /**
   * The defect EXO-89754 is about: a meeting invited somebody, and nothing was
   * ever written to their calendar. Every existing listener reacted to a
   * meeting that already existed, so the copy only appeared once the user
   * edited or answered the event — which is why anyone testing by clicking
   * around saw a feature that worked.
   */
  @Test
  public void aNewMeetingIsCopiedToEverybodyInvitedToIt() {
    givenInvited(user(ALICE), user(BOB));
    when(caldavPendingInvitationService.seedMeeting(ALICE, EVENT)).thenReturn(true);
    when(caldavPendingInvitationService.seedMeeting(BOB, EVENT)).thenReturn(true);

    assertEquals(2, service.propagateCreation(EVENT, AUTHOR));

    verify(caldavPendingInvitationService).seedMeeting(ALICE, EVENT);
    verify(caldavPendingInvitationService).seedMeeting(BOB, EVENT);
  }

  /**
   * The guard against the double push this fix could have introduced.
   *
   * <p>
   * A creation does not reach this service once. Agenda auto-accepts the
   * organiser from inside the very broadcast that says the event was created,
   * which makes it emit {@code responseSaved} too — and this add-on listens to
   * that as well. So the question is not whether a creation triggers one thing
   * or two; it is whether the second one writes. It must not: a second write
   * carrying a fresh {@code DTSTAMP} is exactly the churn EXO-89716 spent a day
   * removing.
   *
   * <p>
   * The whole decision, including "this user already has a copy", is deferred to
   * the seeding service rather than re-implemented here — so this pins that the
   * fan-out asks it and obeys the answer, and writes nothing of its own when it
   * says no.
   */
  @Test
  public void aSecondTriggerOnTheSameCreationWritesNothing() {
    givenInvited(user(ALICE));
    when(caldavPendingInvitationService.seedMeeting(ALICE, EVENT)).thenReturn(true, false);

    assertEquals(1, service.propagateCreation(EVENT, AUTHOR));
    assertEquals(0, service.propagateCreation(EVENT, AUTHOR));

    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  /**
   * A creation is the one moment where handing out a copy is the instruction,
   * so the set of people written to is agenda's attendee list — never the
   * mapping table, which is empty for an event nobody has a copy of yet. Asking
   * the mapping table is what would have made this fix a no-op.
   */
  @Test
  public void aCreationDoesNotAskWhoAlreadyHoldsACopy() {
    givenInvited(user(ALICE));
    when(caldavPendingInvitationService.seedMeeting(ALICE, EVENT)).thenReturn(true);

    service.propagateCreation(EVENT, AUTHOR);

    verify(caldavSyncStorage, never()).getObjectsByEvent(anyLong(), anyInt(), anyInt());
  }

  /**
   * The double push, measured rather than imagined.
   *
   * <p>
   * The author's own copy is written by their <b>browser</b>, on save, from
   * agenda's connector panel — the one path that worked before this method
   * existed. Writing it here as well is not a harmless duplicate: on a rig
   * (2026-08-27) both writers minted the same stable iCalendar UID, both read
   * "no copy yet", both wrote the object, and the second to record its mapping
   * row died on {@code UQ_CALDAV_OBJECT_SYNC_UID} — an ERROR with a stack trace
   * on the ordinary path of every single creation. No "already copied" check
   * can prevent that, because neither writer had written when both looked.
   *
   * <p>
   * So the author is skipped, and every other attendee — the people who got
   * nothing at all, which is the defect — is written to.
   */
  @Test
  public void theAuthorsOwnCopyIsLeftToTheirBrowser() {
    givenInvited(user(AUTHOR), user(ALICE));
    when(caldavPendingInvitationService.seedMeeting(ALICE, EVENT)).thenReturn(true);

    assertEquals(1, service.propagateCreation(EVENT, AUTHOR));

    verify(caldavPendingInvitationService).seedMeeting(ALICE, EVENT);
    verify(caldavPendingInvitationService, never()).seedMeeting(eq(AUTHOR), anyLong());
  }

  /**
   * A broadcast that names no author writes to everybody invited. Skipping the
   * author is a defence against one concurrent writer, not a rule about who
   * deserves a copy, so an unnamed one must not silently cost an attendee
   * theirs.
   */
  @Test
  public void aCreationThatNamesNoAuthorStillReachesEverybody() {
    givenInvited(user(ALICE));
    when(caldavPendingInvitationService.seedMeeting(ALICE, EVENT)).thenReturn(true);

    assertEquals(1, service.propagateCreation(EVENT, 0L));

    verify(caldavPendingInvitationService).seedMeeting(ALICE, EVENT);
  }

  /**
   * A space invited to a meeting is one attendee row standing for its members.
   * Expanding it here would turn one creation into as many settings reads and
   * writes as the space has members — for people the copy does not even name.
   * They are reached by the background seeding pass, which resolves space
   * membership through agenda's own query.
   */
  @Test
  public void aSpaceAmongTheAttendeesIsLeftToTheSeedingPass() {
    givenInvited(user(ALICE), space(CAROL));
    when(caldavPendingInvitationService.seedMeeting(ALICE, EVENT)).thenReturn(true);

    assertEquals(1, service.propagateCreation(EVENT, AUTHOR));

    verify(caldavPendingInvitationService, never()).seedMeeting(eq(CAROL), anyLong());
  }

  /**
   * One account refusing its copy leaves the other attendees theirs. Fifty
   * attendees means as many accounts on as many servers, and one of them being
   * unreachable is an ordinary Tuesday.
   */
  @Test
  public void oneAccountFailingLeavesTheOtherAttendeesTheirCopy() {
    givenInvited(user(ALICE), user(BOB));
    when(caldavPendingInvitationService.seedMeeting(ALICE, EVENT))
                                                                 .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                                    "alice's server is down"));
    when(caldavPendingInvitationService.seedMeeting(BOB, EVENT)).thenReturn(true);

    assertEquals(1, service.propagateCreation(EVENT, AUTHOR));

    verify(caldavPendingInvitationService).seedMeeting(BOB, EVENT);
  }

  /**
   * Agenda answering nothing about the attendees is not a reason to fail the
   * creation, which is already recorded in eXo.
   */
  @Test
  public void aCreationWhoseAttendeesCannotBeListedWritesNothing() {
    when(agendaEventAttendeeService.getEventAttendees(EVENT)).thenThrow(new IllegalStateException("agenda is unhappy"));

    assertEquals(0, service.propagateCreation(EVENT, AUTHOR));

    verify(caldavPendingInvitationService, never()).seedMeeting(anyLong(), anyLong());
  }

  // ---------------------------------------------------------------- fixtures

  /**
   * Declares who agenda says was invited to the created event.
   *
   * @param identityIds the attendee identities, as agenda lists them
   */
  private void givenInvited(long... identityIds) {
    List<EventAttendee> attendees = new ArrayList<>();
    for (long identityId : identityIds) {
      EventAttendee attendee = new EventAttendee();
      attendee.setEventId(EVENT);
      attendee.setIdentityId(identityId);
      attendees.add(attendee);
    }
    when(agendaEventAttendeeService.getEventAttendees(EVENT)).thenReturn(new EventAttendeeList(attendees));
  }

  /**
   * Declares one identity as a person, and answers with it.
   *
   * @param identityId the social identity
   * @return the same identity, so it can be passed straight to
   *         {@link #givenInvited(long...)}
   */
  private long user(long identityId) {
    return givenIdentity(identityId, OrganizationIdentityProvider.NAME);
  }

  /**
   * Declares one identity as a space, and answers with it.
   *
   * @param identityId the social identity
   * @return the same identity, so it can be passed straight to
   *         {@link #givenInvited(long...)}
   */
  private long space(long identityId) {
    return givenIdentity(identityId, SpaceIdentityProvider.NAME);
  }

  /**
   * Declares what one attendee identity is.
   *
   * @param identityId the social identity
   * @param providerId the provider that owns it
   * @return the identity id, unchanged
   */
  private long givenIdentity(long identityId, String providerId) {
    Identity identity = new Identity(String.valueOf(identityId));
    identity.setProviderId(providerId);
    lenient().when(identityManager.getIdentity(String.valueOf(identityId))).thenReturn(identity);
    return identityId;
  }

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
