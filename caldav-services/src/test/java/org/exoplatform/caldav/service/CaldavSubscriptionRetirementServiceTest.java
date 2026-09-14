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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.caldav.LogRecorder;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.service.CaldavSubscriptionRetirementService.Retirement;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

import ch.qos.logback.classic.Level;

/**
 * Retiring a calendar the sweep materialised from a subscription (EXO-90275),
 * on the rig's shape: root's pool vehicle, agenda calendar 16, binding 17.
 *
 * <p>
 * No CalDAV client is wired into the service at all: that nothing reaches the
 * server is a property of its construction, and the pins below say what it
 * does do in eXo.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavSubscriptionRetirementServiceTest {

  private static final long   ROOT     = 1L;

  private static final String LOGIN    = "root";

  private static final String VEHICLE  = "/dav/calendars/__uids__/751E6D1A-7FDB-49B2-B668-B569E9A5A42D/calendar:7E3AE6F3-98DF-43D9-B071-AAB477AC2CD8/";

  private static final String ANCHOR   = "anchor-16";

  private static final long   CALENDAR = 16L;

  private static final long   PAIR     = 17L;

  @Mock
  private CaldavSyncStorage     caldavSyncStorage;

  @Mock
  private AgendaCalendarService agendaCalendarService;

  @Mock
  private AgendaEventService    agendaEventService;

  @Mock
  private CaldavTuningService   caldavTuningService;

  @InjectMocks
  private CaldavSubscriptionRetirementService service;

  @BeforeEach
  public void rig() throws Exception {
    lenient().when(caldavTuningService.getPastDays()).thenReturn(60L);
    lenient().when(caldavTuningService.getFutureDays()).thenReturn(365L);
    lenient().when(agendaCalendarService.getCalendars(0, Integer.MAX_VALUE, LOGIN)).thenReturn(List.of(calendar16()));
  }

  /**
   * A calendar holding nothing but copies of the server's objects is
   * deleted, then its mappings, then its binding — in that order, so a
   * calendar never outlives its binding — and it is said once, at info.
   */
  @Test
  public void aCalendarOfCopiesIsDeletedThenItsMappingsThenItsBinding() throws Exception {
    givenMappings(101L, 102L);
    givenEvents(event(101L, 0L), event(102L, 0L), event(0L, 102L));

    Retirement outcome;
    try (LogRecorder log = new LogRecorder(CaldavSubscriptionRetirementService.class)) {
      outcome = service.retire(ROOT, LOGIN, binding17(), CollectionOwnership.SUBSCRIBED_RESOURCE);
      assertEquals(1,
                   log.events()
                      .stream()
                      .filter(event -> event.getLevel() == Level.INFO && event.getFormattedMessage().contains(VEHICLE))
                      .count());
    }

    assertEquals(Retirement.RETIRED, outcome);
    InOrder order = inOrder(agendaCalendarService, caldavSyncStorage);
    order.verify(agendaCalendarService).deleteCalendarById(CALENDAR);
    order.verify(caldavSyncStorage).deleteObjects(PAIR);
    order.verify(caldavSyncStorage).deletePair(PAIR);
  }

  /**
   * An event no mapping names is one the server never received: the
   * calendar and its events are kept, the binding is paused — persisted — and
   * a warning names the count, once.
   */
  @Test
  public void aCalendarHoldingAnEventTheServerNeverReceivedIsKeptAndItsBindingPaused() throws Exception {
    givenMappings(101L);
    givenEvents(event(101L, 0L), event(205L, 0L));
    CalendarSync binding = binding17();

    Retirement outcome;
    try (LogRecorder log = new LogRecorder(CaldavSubscriptionRetirementService.class)) {
      outcome = service.retire(ROOT, LOGIN, binding, CollectionOwnership.SUBSCRIBED_RESOURCE);
      assertEquals(1,
                   log.events()
                      .stream()
                      .filter(event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains("1 event(s)")
                          && event.getFormattedMessage().contains("[205]"))
                      .count());
    }

    assertEquals(Retirement.PAUSED, outcome);
    assertEquals(CalendarSyncStatus.PAUSED, binding.getStatus());
    verify(caldavSyncStorage).savePair(binding);
    verify(agendaCalendarService, never()).deleteCalendarById(anyLong());
    verify(caldavSyncStorage, never()).deletePair(anyLong());
    verify(caldavSyncStorage, never()).deleteObjects(anyLong());
  }

  /**
   * An event of another calendar is not this calendar's to count.
   */
  @Test
  public void eventsOfOtherCalendarsAreNotCounted() throws Exception {
    givenMappings();
    Event elsewhere = event(300L, 0L);
    elsewhere.setCalendarId(3L);
    givenEvents(elsewhere);

    assertEquals(Retirement.RETIRED, service.retire(ROOT, LOGIN, binding17(), CollectionOwnership.SUBSCRIBED_PERSON));
  }

  /**
   * A calendar agenda will not delete leaves the binding and its mappings as
   * they were, for the next pass.
   */
  @Test
  public void aCalendarThatCannotBeDeletedKeepsItsBinding() throws Exception {
    givenMappings(101L);
    givenEvents(event(101L, 0L));
    doThrow(new IllegalStateException("refused")).when(agendaCalendarService).deleteCalendarById(CALENDAR);

    assertEquals(Retirement.KEPT, service.retire(ROOT, LOGIN, binding17(), CollectionOwnership.SUBSCRIBED_RESOURCE));
    verify(caldavSyncStorage, never()).deleteObjects(anyLong());
    verify(caldavSyncStorage, never()).deletePair(anyLong());
  }

  /**
   * Events that cannot be read retire nothing: a calendar whose contents are
   * unknown is not one to delete.
   */
  @Test
  public void eventsThatCannotBeReadRetireNothing() throws Exception {
    givenMappings(101L);
    when(agendaEventService.getEvents(any(), any(), eq(ROOT))).thenThrow(new IllegalAccessException("refused"));

    assertEquals(Retirement.KEPT, service.retire(ROOT, LOGIN, binding17(), CollectionOwnership.SUBSCRIBED_RESOURCE));
    verify(agendaCalendarService, never()).deleteCalendarById(anyLong());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * Only an ACTIVE REMOTE binding of the user, for a subscription, is
   * retired: a share the server's owner signal revealed, a paused binding, an
   * eXo-made one and another user's are all left alone — which is also what
   * makes a second pass over a paused binding a no-op.
   */
  @Test
  public void onlyAnActiveRemoteBindingOfTheUserForASubscriptionIsRetired() throws Exception {
    assertEquals(Retirement.KEPT, service.retire(ROOT, LOGIN, binding17(), CollectionOwnership.SHARED));
    assertEquals(Retirement.KEPT, service.retire(ROOT, LOGIN, binding17(), CollectionOwnership.COLLEAGUES_EXO_CALENDAR));
    CalendarSync paused = binding17();
    paused.setStatus(CalendarSyncStatus.PAUSED);
    assertEquals(Retirement.KEPT, service.retire(ROOT, LOGIN, paused, CollectionOwnership.SUBSCRIBED_RESOURCE));
    CalendarSync exported = binding17();
    exported.setOrigin(SyncOrigin.EXO);
    assertEquals(Retirement.KEPT, service.retire(ROOT, LOGIN, exported, CollectionOwnership.SUBSCRIBED_RESOURCE));
    CalendarSync anothers = binding17();
    anothers.setUserIdentityId(2L);
    assertEquals(Retirement.KEPT, service.retire(ROOT, LOGIN, anothers, CollectionOwnership.SUBSCRIBED_RESOURCE));

    // Not merely left unchanged: never looked at. An ineligible binding
    // costs agenda no read, so a failure further in cannot pass for a
    // refusal here.
    verify(agendaCalendarService, never()).getCalendars(anyInt(), anyInt(), any());
    verify(caldavSyncStorage, never()).getObjects(anyLong(), anyInt(), anyInt());
    verify(agendaCalendarService, never()).deleteCalendarById(anyLong());
    verify(caldavSyncStorage, never()).savePair(any());
    verify(caldavSyncStorage, never()).deletePair(anyLong());
  }

  /**
   * A binding with no calendar behind it is the orphan pruning's, not this.
   */
  @Test
  public void aBindingWithNoCalendarIsLeftToThePruning() throws Exception {
    when(agendaCalendarService.getCalendars(0, Integer.MAX_VALUE, LOGIN)).thenReturn(List.of());

    assertEquals(Retirement.KEPT, service.retire(ROOT, LOGIN, binding17(), CollectionOwnership.SUBSCRIBED_RESOURCE));
    verify(caldavSyncStorage, never()).deletePair(anyLong());
    verify(agendaCalendarService, never()).deleteCalendarById(anyLong());
  }

  /**
   * @return binding 17 as the sweep made it
   */
  private CalendarSync binding17() {
    CalendarSync pair = new CalendarSync();
    pair.setId(PAIR);
    pair.setUserIdentityId(ROOT);
    pair.setServerId(2L);
    pair.setRemoteHref(VEHICLE);
    pair.setLocalCalendarSyncUid(ANCHOR);
    pair.setOrigin(SyncOrigin.REMOTE);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  /**
   * @return agenda calendar 16, root's, carrying the binding's anchor
   */
  private Calendar calendar16() {
    Calendar calendar = new Calendar();
    calendar.setId(CALENDAR);
    calendar.setOwnerId(ROOT);
    calendar.setSyncUid(ANCHOR);
    return calendar;
  }

  /**
   * @param eventIds the events binding 17's mappings name
   */
  private void givenMappings(Long... eventIds) {
    List<ObjectSync> mappings = java.util.Arrays.stream(eventIds).map(id -> {
      ObjectSync mapping = new ObjectSync();
      mapping.setCalendarSyncId(PAIR);
      mapping.setLocalEventId(id);
      return mapping;
    }).toList();
    lenient().when(caldavSyncStorage.getObjects(PAIR, 0, 200)).thenReturn(new PageImpl<>(mappings, PageRequest.of(0, 200), mappings.size()));
  }

  /**
   * @param events what agenda answers for root's window
   * @throws Exception never, agenda's signature
   */
  private void givenEvents(Event... events) throws Exception {
    when(agendaEventService.getEvents(any(), any(), eq(ROOT))).thenReturn(List.of(events));
  }

  /**
   * @param id the event id, 0 for an occurrence computed from its series
   * @param parentId the series, 0 for none
   * @return an event of calendar 16
   */
  private Event event(long id, long parentId) {
    Event event = new Event();
    event.setId(id);
    event.setParentId(parentId);
    event.setCalendarId(CALENDAR);
    return event;
  }
}
