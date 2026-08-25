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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.model.AgendaConnectorAccount;
import org.exoplatform.agenda.model.AgendaUserSettings;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.EventFilter;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.agenda.service.AgendaUserSettingsService;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * The seed that makes a pending invitation reachable at all (EXO-89681): the
 * copy has to be in the user's calendar before they can answer on it, and
 * nothing used to put it there until they answered in eXo — the one place the
 * feature exists to spare them.
 *
 * <p>
 * The refusals matter as much as the writes: a user who turned copies off has
 * said no to all of this, a date poll is not a meeting, and the user's own
 * calendars belong to their own flow.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavPendingInvitationServiceTest {

  private static final long              USER  = 42L;

  private static final long              SPACE = 900L;

  @Mock
  private AgendaEventService             agendaEventService;

  @Mock
  private AgendaCalendarService          agendaCalendarService;

  @Mock
  private AgendaUserSettingsService      agendaUserSettingsService;

  @Mock
  private CaldavPushService              caldavPushService;

  @Mock
  private CaldavSyncStorage              caldavSyncStorage;

  @InjectMocks
  private CaldavPendingInvitationService service;

  @BeforeEach
  public void aConnectedUserReceivingCopies() {
    ReflectionTestUtils.setField(service, "seedDays", 60);
    ReflectionTestUtils.setField(service, "seedLimit", 200);
    givenCopies(true);
  }

  @Test
  public void aPendingInvitationIsCopiedOut() throws Exception {
    // The whole point: the meeting shows up while the user is deciding, so
    // the calendar on their phone becomes the surface they can answer on.
    givenUpcoming(event(5L, 0, SPACE, EventStatus.CONFIRMED));
    givenCalendar(SPACE, 7L);
    when(caldavPushService.pushAgendaEvent(eq(USER), eq(5L), isNull())).thenReturn(new ObjectSync());

    assertEquals(1, service.pushUpcomingMeetings(USER));
  }

  @Test
  public void aMeetingAlreadyCopiedIsLeftToTheMachineryThatOwnsIt() throws Exception {
    // Seeding maintains nothing: edits belong to the answer flow and drift to
    // the verification pass. Re-pushing here would overwrite an answer the
    // verification pass has not read yet.
    givenUpcoming(event(5L, 0, SPACE, EventStatus.CONFIRMED));
    givenCalendar(SPACE, 7L);
    when(caldavSyncStorage.isEventMapped(5L)).thenReturn(true);

    assertEquals(0, service.pushUpcomingMeetings(USER));
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong(), any());
  }

  @Test
  public void theUsersOwnCalendarsAreNotSeeded() throws Exception {
    // Their events have collections and a flow of their own; filing them
    // among the meeting copies is the mixing the mirror refuses.
    givenUpcoming(event(5L, 0, 800L, EventStatus.CONFIRMED));
    givenCalendar(800L, USER);

    assertEquals(0, service.pushUpcomingMeetings(USER));
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong(), any());
  }

  @Test
  public void aDatePollIsNotAMeeting() throws Exception {
    // eXo spells a date poll TENTATIVE; pushed, it would show as a scheduled
    // meeting nobody has confirmed.
    givenUpcoming(event(5L, 0, SPACE, EventStatus.TENTATIVE));

    assertEquals(0, service.pushUpcomingMeetings(USER));
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong(), any());
  }

  @Test
  public void aSeriesIsSeededOnceThroughItsMaster() throws Exception {
    // The window query expands a series into its occurrences; the copy is one
    // object under the master, so one write covers them all.
    givenUpcoming(event(0L, 5L, SPACE, EventStatus.CONFIRMED),
                  event(0L, 5L, SPACE, EventStatus.CONFIRMED),
                  event(9L, 5L, SPACE, EventStatus.CONFIRMED));
    givenCalendar(SPACE, 7L);
    when(caldavPushService.pushAgendaEvent(eq(USER), eq(5L), isNull())).thenReturn(new ObjectSync());

    assertEquals(1, service.pushUpcomingMeetings(USER));
    verify(caldavPushService).pushAgendaEvent(USER, 5L, null);
  }

  @Test
  public void aUserWhoTurnedCopiesOffIsNotSeeded() throws Exception {
    // The same per-account switch the browser flow honours. Turning copies
    // off now also turns off answering from the calendar, which is what the
    // setting's wording says since EXO-89681.
    givenCopies(false);

    assertEquals(0, service.pushUpcomingMeetings(USER));
    verify(agendaEventService, never()).getEvents(any(), any(), anyLong());
  }

  @Test
  public void oneRefusedMeetingDoesNotStopTheRest() throws Exception {
    givenUpcoming(event(5L, 0, SPACE, EventStatus.CONFIRMED), event(6L, 0, SPACE, EventStatus.CONFIRMED));
    givenCalendar(SPACE, 7L);
    when(caldavPushService.pushAgendaEvent(eq(USER), eq(5L), isNull())).thenThrow(new CaldavPushException("caldav.error.save",
                                                                                                          "refused"));
    when(caldavPushService.pushAgendaEvent(eq(USER), eq(6L), isNull())).thenReturn(new ObjectSync());

    assertEquals(1, service.pushUpcomingMeetings(USER));
  }

  /**
   * @param enabled whether the connected CalDAV account receives copies
   */
  private void givenCopies(boolean enabled) {
    AgendaUserSettings settings = new AgendaUserSettings();
    settings.getConnectedConnectors()
            .add(new AgendaConnectorAccount(CaldavPushService.CONNECTOR_NAME, "john@example.test", enabled));
    lenient().when(agendaUserSettingsService.getAgendaUserSettings(USER)).thenReturn(settings);
  }

  /**
   * @param events what the window query answers
   * @throws IllegalAccessException never — the mock declares it
   */
  private void givenUpcoming(Event... events) throws IllegalAccessException {
    when(agendaEventService.getEvents(any(EventFilter.class), any(), eq(USER))).thenReturn(List.of(events));
  }

  /**
   * @param calendarId the calendar
   * @param ownerId who owns it
   */
  private void givenCalendar(long calendarId, long ownerId) {
    Calendar calendar = new Calendar();
    calendar.setId(calendarId);
    calendar.setOwnerId(ownerId);
    lenient().when(agendaCalendarService.getCalendarById(calendarId)).thenReturn(calendar);
  }

  /**
   * @param id the event identifier, 0 for a computed occurrence
   * @param parentId the series master, 0 for a single event
   * @param calendarId the calendar the event lives in
   * @param status the agenda status
   * @return the event, also resolvable by its master identifier
   */
  private Event event(long id, long parentId, long calendarId, EventStatus status) {
    Event event = new Event();
    event.setId(id);
    event.setParentId(parentId);
    event.setCalendarId(calendarId);
    event.setStatus(status);
    long masterId = parentId > 0 ? parentId : id;
    if (masterId > 0) {
      lenient().when(agendaEventService.getEventById(masterId)).thenReturn(masterOf(event, masterId));
    }
    return event;
  }

  /**
   * @param occurrence the listed event
   * @param masterId the master's identifier
   * @return the master the seed loads for it
   */
  private Event masterOf(Event occurrence, long masterId) {
    Event master = new Event();
    master.setId(masterId);
    master.setCalendarId(occurrence.getCalendarId());
    master.setStatus(occurrence.getStatus());
    return master;
  }
}
