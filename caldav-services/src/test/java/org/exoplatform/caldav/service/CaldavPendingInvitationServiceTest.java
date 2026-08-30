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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Set;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.model.AgendaConnectorAccount;
import org.exoplatform.agenda.model.AgendaUserSettings;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.EventAttendee;
import org.exoplatform.agenda.model.EventAttendeeList;
import org.exoplatform.agenda.model.EventFilter;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.agenda.service.AgendaUserSettingsService;
import org.exoplatform.caldav.LogRecorder;
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
 * said no to all of this, a date poll is not a meeting, and a meeting somebody
 * declined is not put back in front of them.
 *
 * <p>
 * And since EXO-89796 the pass covers the user's own calendars too, because
 * the two paths that copy an event — the creation listener and this backfill —
 * have to answer the same question. They did not: an event a user made for
 * themselves alone carries no attendee row, so no attendee-keyed window could
 * name it, and the pass refused it a second time for living in a calendar the
 * user owns. Created while connected it was copied by the browser; created
 * before, it was copied by nothing at all.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavPendingInvitationServiceTest {

  private static final long              USER  = 42L;

  private static final long              SPACE = 900L;

  private static final long              MINE  = 800L;

  @Mock
  private AgendaEventService             agendaEventService;

  @Mock
  private AgendaCalendarService          agendaCalendarService;

  @Mock
  private AgendaEventAttendeeService     agendaEventAttendeeService;

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
    when(caldavPushService.pushAgendaEvent(eq(USER), eq(5L))).thenReturn(new ObjectSync());

    assertEquals(1, service.pushUpcomingMeetings(USER));
  }

  @Test
  public void aMeetingAlreadyCopiedIsLeftToTheMachineryThatOwnsIt() throws Exception {
    // Seeding maintains nothing: edits belong to the answer flow and drift to
    // the verification pass. Re-pushing here would overwrite an answer the
    // verification pass has not read yet.
    givenUpcoming(event(5L, 0, SPACE, EventStatus.CONFIRMED));
    givenCalendar(SPACE, 7L);
    when(caldavSyncStorage.mappedEventIds(eq(USER), any())).thenReturn(Set.of(5L));

    assertEquals(0, service.pushUpcomingMeetings(USER));
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  /**
   * Another attendee holding a copy must not stop this one getting theirs.
   *
   * A meeting has an attendee list and each of them needs a copy of their
   * own, so the question is always "does THIS user have one". Asked without
   * the user, whichever attendee was copied first answered for all the rest:
   * they were skipped, and the meeting never reached their calendar. The
   * assertion is on the identity the storage is asked about, because that is
   * the whole of the fix.
   */
  @Test
  public void aCopyBelongingToAnotherAttendeeDoesNotBlockThisOne() throws Exception {
    givenUpcoming(event(5L, 0, SPACE, EventStatus.CONFIRMED));
    givenCalendar(SPACE, 7L);
    // This user has none; that another user does is not this question.
    when(caldavSyncStorage.mappedEventIds(eq(USER), any())).thenReturn(Set.of());
    when(caldavPushService.pushAgendaEvent(eq(USER), eq(5L))).thenReturn(new ObjectSync());

    assertEquals(1, service.pushUpcomingMeetings(USER));

    verify(caldavPushService).pushAgendaEvent(USER, 5L);
    verify(caldavSyncStorage, atLeastOnce()).mappedEventIds(eq(USER), any());
    verify(caldavSyncStorage, never()).isEventMapped(anyLong());
  }

  /**
   * The reported defect, EXO-89796: a future event a user made for themselves
   * alone, in their own calendar, before they connected an account.
   *
   * <p>
   * It has no attendee row of any kind — agenda writes those only for the
   * attendees it is given — so the attendee-keyed window cannot name it, and
   * the pass used to refuse it a second time for living in a calendar the user
   * owns. Created after the connection it was copied all the same, by the
   * browser's push on save; created before, it was copied by nothing, for
   * ever. Both halves are what this asserts away: an empty attendee window,
   * and a calendar owned by the user.
   *
   * @throws Exception never — the agenda mocks declare checked exceptions
   */
  @Test
  public void anEventOfTheUsersOwnCalendarWithNoAttendeesIsSeeded() throws Exception {
    givenUpcoming();
    givenInOwnCalendars(event(5L, 0, MINE, EventStatus.CONFIRMED));
    givenCalendar(MINE, USER);
    when(caldavPushService.pushAgendaEvent(eq(USER), eq(5L))).thenReturn(new ObjectSync());

    assertEquals(1, service.pushUpcomingMeetings(USER));
    verify(caldavPushService).pushAgendaEvent(USER, 5L);
  }

  /**
   * Where an own-calendar event goes is not this service's question.
   *
   * <p>
   * {@code CaldavPushService} routes it into the collection paired with that
   * calendar, and answers null when the calendar has none — nothing is filed
   * among the meeting copies, which is the mixing the old refusal was written
   * to prevent and which the routing prevents properly. Null must read as
   * "nothing was pushed" rather than as a write.
   *
   * @throws Exception never — the agenda mocks declare checked exceptions
   */
  @Test
  public void anOwnCalendarWithNoCollectionYieldsNoCopyAndNoMixing() throws Exception {
    givenUpcoming();
    givenInOwnCalendars(event(5L, 0, MINE, EventStatus.CONFIRMED));
    givenCalendar(MINE, USER);
    when(caldavPushService.pushAgendaEvent(eq(USER), eq(5L))).thenReturn(null);

    assertEquals(0, service.pushUpcomingMeetings(USER));
  }

  /**
   * The guard, kept: a meeting the user answered no to stays out.
   *
   * <p>
   * Asserted through the own-calendar window on purpose. The attendee-keyed
   * window filters declined answers out in SQL, so a pin riding on it would
   * pass whatever the service did afterwards; the owner-keyed window has no
   * attendee predicate at all, so this is the path where the refusal has to be
   * made in code — and it is the path the widening opened.
   *
   * @throws Exception never — the agenda mocks declare checked exceptions
   */
  @Test
  public void aDeclinedMeetingIsNotSeeded() throws Exception {
    givenUpcoming();
    givenInOwnCalendars(event(5L, 0, MINE, EventStatus.CONFIRMED));
    givenCalendar(MINE, USER);
    givenDeclined(5L);

    assertEquals(0, service.pushUpcomingMeetings(USER));
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  /**
   * The equivalence this defect was, stated as one assertion over four shapes
   * of event.
   *
   * <p>
   * The creation listener reaches {@link CaldavPendingInvitationService#seedMeeting}
   * and the background pass reaches {@code pushUpcomingMeetings}; an event that
   * one copies must be an event the other copies. Held over: an own-calendar
   * event nobody attends (the reported defect), an ordinary space meeting, a
   * declined meeting, and a date poll. Comparing the two sets rather than
   * asserting each in isolation is what makes this a pin on the agreement
   * instead of two pins that can drift apart.
   *
   * @throws Exception never — the agenda mocks declare checked exceptions
   */
  @Test
  public void theBackfillCopiesExactlyWhatTheCreationPathCopies() throws Exception {
    givenUpcoming(event(6L, 0, SPACE, EventStatus.CONFIRMED), event(7L, 0, SPACE, EventStatus.CONFIRMED));
    givenInOwnCalendars(event(5L, 0, MINE, EventStatus.CONFIRMED), event(8L, 0, MINE, EventStatus.TENTATIVE));
    givenCalendar(MINE, USER);
    givenCalendar(SPACE, 7L);
    givenDeclined(7L);
    when(caldavPushService.pushAgendaEvent(eq(USER), anyLong())).thenReturn(new ObjectSync());

    List<Long> seededOnCreation = new ArrayList<>();
    for (long eventId : List.of(5L, 6L, 7L, 8L)) {
      if (service.seedMeeting(USER, eventId)) {
        seededOnCreation.add(eventId);
      }
    }
    // The own-calendar event and the ordinary meeting; not the declined one,
    // not the date poll.
    assertEquals(List.of(5L, 6L), seededOnCreation);

    assertEquals(seededOnCreation.size(), service.pushUpcomingMeetings(USER));
    for (Long eventId : seededOnCreation) {
      verify(caldavPushService, atLeastOnce()).pushAgendaEvent(USER, eventId);
    }
    verify(caldavPushService, never()).pushAgendaEvent(USER, 7L);
    verify(caldavPushService, never()).pushAgendaEvent(USER, 8L);
  }

  /**
   * The converged account: everything in both windows already has a copy.
   *
   * <p>
   * The failure mode this codebase keeps repeating is logic exercised only on
   * the broken path, so the pass that finds nothing to do is asserted too —
   * and asserted to be cheap, which is a claim about the batch question asked
   * once for the whole window. Nothing is pushed and <b>no event is even
   * loaded</b>: a pass that had to load each event before discovering it had
   * nothing to do would make the cost of a converged account grow with how
   * much had already been done, on a pass that repeats for ever.
   *
   * <p>
   * Both windows answer, so this also covers the one the widening added — the
   * cheapness has to hold for the own-calendar events too, not only for the
   * meetings.
   *
   * @throws Exception never — the agenda mocks declare checked exceptions
   */
  @Test
  public void aConvergedAccountPushesNothing() throws Exception {
    givenUpcoming(event(6L, 0, SPACE, EventStatus.CONFIRMED));
    givenInOwnCalendars(event(5L, 0, MINE, EventStatus.CONFIRMED));
    when(caldavSyncStorage.mappedEventIds(eq(USER), any())).thenReturn(Set.of(5L, 6L));

    assertEquals(0, service.pushUpcomingMeetings(USER));
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
    verify(agendaEventService, never()).getEventById(anyLong());
  }

  @Test
  public void aDatePollIsNotAMeeting() throws Exception {
    // eXo spells a date poll TENTATIVE; pushed, it would show as a scheduled
    // meeting nobody has confirmed.
    givenUpcoming(event(5L, 0, SPACE, EventStatus.TENTATIVE));

    assertEquals(0, service.pushUpcomingMeetings(USER));
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  @Test
  public void aSeriesIsSeededOnceThroughItsMaster() throws Exception {
    // The window query expands a series into its occurrences; the copy is one
    // object under the master, so one write covers them all.
    givenUpcoming(event(0L, 5L, SPACE, EventStatus.CONFIRMED),
                  event(0L, 5L, SPACE, EventStatus.CONFIRMED),
                  event(9L, 5L, SPACE, EventStatus.CONFIRMED));
    givenCalendar(SPACE, 7L);
    when(caldavPushService.pushAgendaEvent(eq(USER), eq(5L))).thenReturn(new ObjectSync());

    assertEquals(1, service.pushUpcomingMeetings(USER));
    verify(caldavPushService).pushAgendaEvent(USER, 5L);
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
    when(caldavPushService.pushAgendaEvent(eq(USER), eq(5L))).thenThrow(new CaldavPushException("caldav.error.save",
                                                                                                          "refused"));
    when(caldavPushService.pushAgendaEvent(eq(USER), eq(6L))).thenReturn(new ObjectSync());

    assertEquals(1, service.pushUpcomingMeetings(USER));
  }

  /**
   * One named meeting, seeded at the moment it is created rather than at the
   * next sweep (EXO-89754). Nothing used to write it: every listener this
   * add-on registered reacted to a meeting that already existed.
   *
   * @throws Exception never — the agenda mocks declare checked exceptions
   */
  @Test
  public void oneNamedMeetingIsSeededOnDemand() throws Exception {
    givenEvent(5L, SPACE, EventStatus.CONFIRMED);
    givenCalendar(SPACE, 7L);
    when(caldavPushService.pushAgendaEvent(USER, 5L)).thenReturn(new ObjectSync());

    assertTrue(service.seedMeeting(USER, 5L));

    verify(caldavPushService).pushAgendaEvent(USER, 5L);
  }

  /**
   * The guard that keeps a creation to one write.
   *
   * <p>
   * A creation reaches this add-on more than once — agenda auto-accepts the
   * organiser from inside the {@code created} broadcast, so
   * {@code responseSaved} follows it — and the two arrive on the same
   * single-threaded listener executor. Whichever runs second must find the
   * meeting already copied and write nothing: a second write carrying a fresh
   * {@code DTSTAMP} is the churn EXO-89716 spent a day removing.
   *
   * @throws Exception never — the agenda mocks declare checked exceptions
   */
  @Test
  public void aMeetingThatAlreadyHasACopyIsNotSeededAgain() throws Exception {
    givenEvent(5L, SPACE, EventStatus.CONFIRMED);
    givenCalendar(SPACE, 7L);
    when(caldavSyncStorage.mappedEventIds(eq(USER), any())).thenReturn(Set.of(5L));

    assertFalse(service.seedMeeting(USER, 5L));

    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  /**
   * Seeding one named meeting refuses everything the sweep refuses. A user who
   * turned copies off has said no to this too, whichever path asks.
   *
   * @throws Exception never — the agenda mocks declare checked exceptions
   */
  @Test
  public void oneNamedMeetingIsNotSeededForAUserWhoTurnedCopiesOff() throws Exception {
    givenCopies(false);

    assertFalse(service.seedMeeting(USER, 5L));

    verify(agendaEventService, never()).getEventById(anyLong());
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  /**
   * A date poll created in eXo is spelled TENTATIVE and is not a scheduled
   * meeting. Agenda broadcasts it under a name of its own, but the refusal is
   * stated here too rather than left to the registration alone.
   *
   * @throws Exception never — the agenda mocks declare checked exceptions
   */
  @Test
  public void oneNamedDatePollIsNotSeeded() throws Exception {
    givenEvent(5L, SPACE, EventStatus.TENTATIVE);

    assertFalse(service.seedMeeting(USER, 5L));

    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  /**
   * The state behind EXO-89798, held to the control flow it always had.
   *
   * <p>
   * An invitee who never connected a CalDAV account is an ordinary state, and
   * the whole change is about how loudly it is recorded — not about what
   * happens. So this pins the behaviour rather than the log: the refusal is
   * absorbed, the caller is told no, and nothing escapes to break the fan-out
   * to the other attendees of the same meeting.
   *
   * @throws Exception never — the agenda mocks declare checked exceptions
   */
  @Test
  public void aUserWithNoConnectedAccountIsRefusedWithoutThrowing() throws Exception {
    givenEvent(5L, SPACE, EventStatus.CONFIRMED);
    givenCalendar(SPACE, 7L);
    when(caldavPushService.pushAgendaEvent(USER, 5L)).thenThrow(new CaldavPushException(CaldavPushService.NOT_CONNECTED,
                                                                                        "User 42 has no connected CalDAV account"));

    assertFalse(assertDoesNotThrow(() -> service.seedMeeting(USER, 5L)));
  }

  /**
   * The one test in this change that reads the log, and the only one that can
   * prove what the change actually claims (EXO-89798).
   *
   * <p>
   * Everywhere else the classification is tested as what it is — a pure
   * function of the code — and each call site is pinned on its behaviour, which
   * is what a reader of those tests should care about. But at every one of
   * those sites the observable difference between a known state and a failure
   * <i>is</i> the log line, so a suite that never looks at one cannot tell a
   * working filter from a branch that quietly does nothing. This looks, once,
   * at the site the incident was reported on.
   *
   * <p>
   * Both halves, and the second is the guard against a silencer: a state is
   * recorded at debug with no trace attached, and a failure is still a warn
   * that carries its trace. A change that quietened both would pass the first
   * assertion on its own.
   *
   * @throws Exception never — the agenda mocks declare checked exceptions
   */
  @Test
  public void aKnownStateIsRecordedQuietlyAndAFailureIsStillAnIncident() throws Exception {
    givenEvent(5L, SPACE, EventStatus.CONFIRMED);
    givenCalendar(SPACE, 7L);

    List<ILoggingEvent> recorded;
    try (LogRecorder log = new LogRecorder(CaldavPendingInvitationService.class)) {
      when(caldavPushService.pushAgendaEvent(USER, 5L)).thenThrow(new CaldavPushException(CaldavPushService.NOT_CONNECTED,
                                                                                          "no account"),
                                                                  new CaldavPushException(CaldavPushService.SAVE,
                                                                                          "the server refused the write"));

      service.seedMeeting(USER, 5L);
      service.seedMeeting(USER, 5L);
      recorded = List.copyOf(log.events());
    }

    assertEquals(2, recorded.size(), "each refusal is recorded exactly once");

    ILoggingEvent state = recorded.get(0);
    assertEquals(Level.DEBUG, state.getLevel(), "a user who never connected an account is not an incident");
    assertNull(state.getThrowableProxy(), "a known state does not need eleven frames to be understood");

    ILoggingEvent failure = recorded.get(1);
    assertEquals(Level.WARN, failure.getLevel(), "a refused write is still a failure and still has to be seen");
    assertNotNull(failure.getThrowableProxy(), "a failure keeps the trace that says where it came from");
  }

  /**
   * The same absorption in the sweep, and the reason the sweep is worth a test
   * of its own: it is the loop the report watched, where one unconnected
   * invitee must not cost the meeting its other copies.
   *
   * @throws Exception never — the agenda mocks declare checked exceptions
   */
  @Test
  public void anUnconnectedInviteeDoesNotStopTheSweep() throws Exception {
    givenUpcoming(event(5L, 0, SPACE, EventStatus.CONFIRMED), event(6L, 0, SPACE, EventStatus.CONFIRMED));
    givenCalendar(SPACE, 7L);
    when(caldavPushService.pushAgendaEvent(eq(USER),
                                           eq(5L))).thenThrow(new CaldavPushException(CaldavPushService.NOT_CONNECTED,
                                                                                      "User 42 has no connected CalDAV account"));
    when(caldavPushService.pushAgendaEvent(eq(USER), eq(6L))).thenReturn(new ObjectSync());

    assertEquals(1, assertDoesNotThrow(() -> service.pushUpcomingMeetings(USER)));
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
   * What the attendee-keyed window answers: the meetings this user is an
   * attendee of.
   *
   * <p>
   * Matched on the filter carrying the user as its attendee, because the pass
   * puts two questions to agenda and they are not interchangeable — this one
   * can only ever name an event the user has an attendee row on.
   *
   * @param events what that window answers
   * @throws IllegalAccessException never — the mock declares it
   */
  private void givenUpcoming(Event... events) throws IllegalAccessException {
    when(agendaEventService.getEvents(argThat(filter -> filter != null && filter.getAttendeeId() == USER),
                                      any(),
                                      eq(USER))).thenReturn(List.of(events));
  }

  /**
   * What the owner-keyed window answers: the events living in a calendar this
   * user owns, whether or not anybody is an attendee of them.
   *
   * @param events what that window answers
   * @throws IllegalAccessException never — the mock declares it
   */
  private void givenInOwnCalendars(Event... events) throws IllegalAccessException {
    when(agendaEventService.getEvents(argThat(filter -> filter != null
        && filter.getOwnerIds() != null
        && filter.getOwnerIds().contains(USER)),
                                      any(),
                                      eq(USER))).thenReturn(List.of(events));
  }

  /**
   * Makes this user a declined attendee of these events, and of no other.
   *
   * @param eventIds the events the user said no to
   */
  private void givenDeclined(long... eventIds) {
    List<Long> declined = new ArrayList<>();
    for (long eventId : eventIds) {
      declined.add(eventId);
    }
    lenient().when(agendaEventAttendeeService.getEventAttendees(anyLong(), eq(EventAttendeeResponse.DECLINED)))
             .thenAnswer(invocation -> declined.contains(invocation.getArgument(0, Long.class))
                                                                                               ? oneAttendee(USER)
                                                                                               : EventAttendeeList.EMPTY_ATTENDEE_LIST);
  }

  /**
   * @param identityId who the single attendee is
   * @return an attendee list holding only them, as a declined answer
   */
  private EventAttendeeList oneAttendee(long identityId) {
    return new EventAttendeeList(List.of(new EventAttendee(1L, identityId, EventAttendeeResponse.DECLINED)));
  }

  /**
   * Declares one event by its identifier, for the callers that name a meeting
   * instead of listing a window.
   *
   * @param eventId the event identifier
   * @param calendarId the calendar the event lives in
   * @param status the agenda status
   */
  private void givenEvent(long eventId, long calendarId, EventStatus status) {
    Event event = new Event();
    event.setId(eventId);
    event.setCalendarId(calendarId);
    event.setStatus(status);
    when(agendaEventService.getEventById(eventId)).thenReturn(event);
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
