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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.EventAttendee;
import org.exoplatform.agenda.model.EventAttendeeList;
import org.exoplatform.agenda.model.RemoteEvent;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.ics.IcsEventMapper;
import org.exoplatform.caldav.ics.IcsParser;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * Bringing a collection's events into the calendar standing for it.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavInboundServiceTest {

  private static final long      USER     = 7L;

  private static final long      SERVER   = 3L;

  private static final long      PAIR     = 11L;

  private static final long      CALENDAR = 42L;

  private static final String    LOGIN    = "john";

  private static final String    HREF     = "/dav/calendars/john/private/";

  /**
   * A weekly series with one occurrence moved and one cancelled — the same
   * shape written into the live test rig.
   */
  private static final String    SERIES   = """
      BEGIN:VCALENDAR
      VERSION:2.0
      PRODID:-//test//EN
      BEGIN:VEVENT
      UID:uid-1@example.test
      DTSTAMP:20260821T200000Z
      SUMMARY:Weekly standup
      DTSTART:20260907T090000Z
      DTEND:20260907T093000Z
      RRULE:FREQ=WEEKLY;COUNT=6
      EXDATE:20260921T090000Z
      END:VEVENT
      BEGIN:VEVENT
      UID:uid-1@example.test
      RECURRENCE-ID:20260914T090000Z
      DTSTAMP:20260821T200000Z
      SUMMARY:Weekly standup (moved)
      DTSTART:20260914T140000Z
      DTEND:20260914T143000Z
      END:VEVENT
      END:VCALENDAR
      """;

  @Mock
  private CalDavClient           calDavClient;

  @Mock
  private CaldavSyncStorage      caldavSyncStorage;

  @Mock
  private CaldavConnectorStorage caldavConnectorStorage;

  @Mock
  private AgendaEventService     agendaEventService;

  @Mock
  private AgendaEventAttendeeService agendaEventAttendeeService;

  @Mock
  private CalDavEndpoint         endpoint;

  @Spy
  private IcsParser              icsParser;

  @Spy
  private IcsEventMapper         icsEventMapper;

  @InjectMocks
  private CaldavInboundService   service;

  /**
   * A connected account, an endpoint, and a slice wide enough that the tests
   * below make one round trip — so what they assert stays about importing
   * rather than about slicing.
   */
  @BeforeEach
  public void connectAnAccount() {
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
    ReflectionTestUtils.setField(service, "sliceDays", 400L);
  }

  /**
   * An object never seen becomes an event.
   */
  @Test
  public void anObjectSeenForTheFirstTimeBecomesAnEvent() throws Exception {
    givenServerObjects(object("o1.ics", "etag-1", ics("uid-1@example.test", "Design review")));
    givenAgendaCreates(501L);

    assertEquals(1, service.importInto(USER, pair(), calendar(), from(), to()));

    ArgumentCaptor<Event> created = ArgumentCaptor.forClass(Event.class);
    verify(agendaEventService).createEvent(created.capture(),
                                           any(),
                                           any(),
                                           any(),
                                           any(),
                                           any(),
                                           anyBoolean(),
                                           eq(USER));
    assertEquals("Design review", created.getValue().getSummary());
    assertEquals(CALENDAR, created.getValue().getCalendarId());
  }

  /**
   * Importing never invites anyone.
   */
  @Test
  public void importingAnEventNeverInvitesItsAttendeesAgain() throws Exception {
    // The most consequential argument in this service. These people were
    // invited by whoever organised the meeting, on a server the user already
    // reads; inviting them again because eXo has just noticed the event would
    // send real mail to real people about something that happened days ago.
    givenServerObjects(object("o1.ics", "etag-1", ics("uid-1@example.test", "Design review")));
    givenAgendaCreates(501L);

    service.importInto(USER, pair(), calendar(), from(), to());

    verify(agendaEventService).createEvent(any(), any(), any(), any(), any(), any(), eq(false), eq(USER));
  }

  /**
   * An imported event remembers what it is called on the server.
   */
  @Test
  public void anImportedEventKeepsTheIdentifierTheServerGaveIt() throws Exception {
    // Without this the event has no remote identity at all, and for as long as
    // nothing pushed from a materialised calendar that cost nothing. Now such
    // a calendar synchronises both ways: asked to write the event back, the
    // push finds no identifier, mints a fresh one and writes a SECOND object
    // beside the one the event was imported from. Renaming an imported meeting
    // in eXo made it appear twice on the account it came from.
    givenServerObjects(object("o1.ics", "etag-1", ics("uid-1@example.test", "Design review")));
    givenAgendaCreates(501L);

    service.importInto(USER, pair(), calendar(), from(), to());

    ArgumentCaptor<RemoteEvent> identity = ArgumentCaptor.forClass(RemoteEvent.class);
    verify(agendaEventService).createEvent(any(),
                                           any(),
                                           any(),
                                           any(),
                                           any(),
                                           identity.capture(),
                                           anyBoolean(),
                                           eq(USER));
    // The server's own UID, so a later push addresses the object this event
    // came from rather than a new one beside it.
    assertEquals("uid-1@example.test", identity.getValue().getRemoteId());
    // Named, or agenda deletes the mapping instead of storing it.
    assertEquals("agenda.caldavCalendar", identity.getValue().getRemoteProviderName());
  }

  /**
   * The imported event names its own owner as an attendee.
   */
  @Test
  public void anImportedEventNamesTheCalendarOwnerAsAttendee() throws Exception {
    // Not a cosmetic detail: agenda's default view inner-joins the attendee
    // table, so an event nobody attends is invisible in the calendar it was
    // just imported into — the whole import reads as having done nothing.
    givenServerObjects(object("o1.ics", "etag-1", ics("uid-1@example.test", "Design review")));
    givenAgendaCreates(501L);

    service.importInto(USER, pair(), calendar(), from(), to());

    ArgumentCaptor<List<EventAttendee>> attendees = ArgumentCaptor.forClass(List.class);
    verify(agendaEventService).createEvent(any(),
                                           attendees.capture(),
                                           any(),
                                           any(),
                                           any(),
                                           any(),
                                           anyBoolean(),
                                           eq(USER));
    assertEquals(1, attendees.getValue().size());
    assertEquals(USER, attendees.getValue().get(0).getIdentityId());
  }

  /**
   * That attendee is accepted, not asked to answer.
   */
  @Test
  public void theOwnerIsNotAskedToAnswerTheirOwnCalendar() throws Exception {
    // NEEDS_ACTION would put a pending invitation in front of the user for a
    // meeting they already accepted on the server this was read from.
    givenServerObjects(object("o1.ics", "etag-1", ics("uid-1@example.test", "Design review")));
    givenAgendaCreates(501L);

    service.importInto(USER, pair(), calendar(), from(), to());

    ArgumentCaptor<List<EventAttendee>> attendees = ArgumentCaptor.forClass(List.class);
    verify(agendaEventService).createEvent(any(), attendees.capture(), any(), any(), any(), any(), anyBoolean(), anyLong());
    assertEquals(EventAttendeeResponse.ACCEPTED, attendees.getValue().get(0).getResponse());
  }

  /**
   * A remote edit keeps the attendees the event already had.
   */
  @Test
  public void aRemoteEditDoesNotStripTheAttendeesOffTheEvent() throws Exception {
    // agenda reads the list it is handed as the whole truth about who
    // attends, so an empty one deletes everybody. Anyone the user added here
    // would disappear the next time the organiser touched the meeting.
    givenServerObjects(object("o1.ics", "etag-2", icsModifiedAt("uid-1@example.test", "Moved", "20261005T120000Z")));
    when(caldavSyncStorage.getObjectByUid(PAIR, "uid-1@example.test")).thenReturn(mapping("etag-1"));
    when(agendaEventService.getEventById(501L)).thenReturn(eventUpdatedAt("2026-10-05T09:00:00Z"));
    givenEventAttendees(501L, attendee(USER), attendee(909L));

    service.importInto(USER, pair(), calendar(), from(), to());

    ArgumentCaptor<List<EventAttendee>> attendees = ArgumentCaptor.forClass(List.class);
    verify(agendaEventService).updateEvent(any(), attendees.capture(), any(), any(), any(), any(), anyBoolean(), anyLong());
    assertEquals(2, attendees.getValue().size());
    assertEquals(List.of(USER, 909L),
                 attendees.getValue().stream().map(EventAttendee::getIdentityId).toList());
  }

  /**
   * An event imported before the owner was recorded gets them on the next edit.
   */
  @Test
  public void aRemoteEditAddsTheOwnerToAnEventImportedWithoutOne() throws Exception {
    // Events imported by an earlier build carry no attendee at all. Without
    // this they stay invisible until someone deletes and re-imports the
    // calendar, which is not a repair anybody would think to attempt.
    givenServerObjects(object("o1.ics", "etag-2", icsModifiedAt("uid-1@example.test", "Moved", "20261005T120000Z")));
    when(caldavSyncStorage.getObjectByUid(PAIR, "uid-1@example.test")).thenReturn(mapping("etag-1"));
    when(agendaEventService.getEventById(501L)).thenReturn(eventUpdatedAt("2026-10-05T09:00:00Z"));
    givenEventAttendees(501L, attendee(909L));

    service.importInto(USER, pair(), calendar(), from(), to());

    ArgumentCaptor<List<EventAttendee>> attendees = ArgumentCaptor.forClass(List.class);
    verify(agendaEventService).updateEvent(any(), attendees.capture(), any(), any(), any(), any(), anyBoolean(), anyLong());
    assertEquals(2, attendees.getValue().size());
    assertFalse(attendees.getValue().stream().noneMatch(a -> a.getIdentityId() == USER));
  }

  /**
   * The binding is written so the object can be recognised next time.
   */
  @Test
  public void theObjectIsRecordedSoItIsNotImportedTwice() throws Exception {
    givenServerObjects(object("o1.ics", "etag-1", ics("uid-1@example.test", "Design review")));
    givenAgendaCreates(501L);

    service.importInto(USER, pair(), calendar(), from(), to());

    ArgumentCaptor<ObjectSync> saved = ArgumentCaptor.forClass(ObjectSync.class);
    verify(caldavSyncStorage).saveObject(saved.capture());
    ObjectSync mapping = saved.getValue();
    assertEquals(PAIR, mapping.getCalendarSyncId());
    assertEquals("uid-1@example.test", mapping.getIcsUid());
    assertEquals(501L, mapping.getLocalEventId());
    assertEquals("etag-1", mapping.getEtag());
    assertNotNull(mapping.getLastSync());
  }

  /**
   * An unchanged object costs nothing.
   */
  @Test
  public void anObjectTheServerSaysIsUnchangedIsLeftAlone() throws Exception {
    // Re-writing the event would bump its modification date and make every
    // sync look like an edit to anything watching agenda.
    givenServerObjects(object("o1.ics", "etag-1", ics("uid-1@example.test", "Design review")));
    when(caldavSyncStorage.getObjectByUid(PAIR, "uid-1@example.test")).thenReturn(mapping("etag-1"));

    assertEquals(0, service.importInto(USER, pair(), calendar(), from(), to()));

    verify(agendaEventService, never()).createEvent(any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    anyBoolean(),
                                                    anyLong());
  }

  /**
   * A changed object is not overwritten on a guess.
   */
  @Test
  public void anObjectChangedSinceItWasImportedIsLeftForTheConflictPass() throws Exception {
    // Deciding which side wins needs both sides' modification times, which
    // this pass does not gather. Overwriting here would silently discard a
    // local edit.
    givenServerObjects(object("o1.ics", "etag-2", ics("uid-1@example.test", "Design review")));
    when(caldavSyncStorage.getObjectByUid(PAIR, "uid-1@example.test")).thenReturn(mapping("etag-1"));

    assertEquals(0, service.importInto(USER, pair(), calendar(), from(), to()));

    verify(agendaEventService, never()).createEvent(any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    anyBoolean(),
                                                    anyLong());
  }

  /**
   * A collection the server refuses does not empty the calendar.
   */
  @Test
  public void aCollectionTheServerRefusesLeavesTheCalendarAsItIs() {
    when(calDavClient.calendarQuery(any(), anyString(), any(), any(), anyString(), anyString()))
                                                                                               .thenThrow(new CalDavException("down"));

    assertEquals(0, service.importInto(USER, pair(), calendar(), from(), to()));

    verify(caldavSyncStorage, never()).saveObject(any());
  }

  /**
   * An object holding only an override waits for the pass that can place it.
   */
  @Test
  public void anObjectHoldingOnlyAnOverrideIsNotImportedAsItsOwnMeeting() throws Exception {
    // Creating it here would show the amendment as a separate meeting beside a
    // series that already covers that day.
    String override = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//test//EN
        BEGIN:VEVENT
        DTSTAMP:20261001T080000Z
        UID:uid-1@example.test
        RECURRENCE-ID:20261012T090000Z
        DTSTART:20261012T100000Z
        DTEND:20261012T110000Z
        SUMMARY:Moved an hour later
        END:VEVENT
        END:VCALENDAR
        """;
    givenServerObjects(object("o1.ics", "etag-1", override));

    assertEquals(0, service.importInto(USER, pair(), calendar(), from(), to()));

    verify(agendaEventService, never()).createEvent(any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    anyBoolean(),
                                                    anyLong());
  }

  /**
   * An unreadable object does not stop the ones behind it.
   */
  @Test
  public void oneUnreadableObjectDoesNotStopTheRest() throws Exception {
    givenServerObjects(object("bad.ics", "etag-0", "this is not a calendar"),
                       object("o1.ics", "etag-1", ics("uid-1@example.test", "Design review")));
    givenAgendaCreates(501L);

    assertEquals(1, service.importInto(USER, pair(), calendar(), from(), to()));
  }

  /**
   * An event agenda refuses does not stop the collection.
   */
  @Test
  public void anEventAgendaRefusesDoesNotStopTheCollection() throws Exception {
    givenServerObjects(object("o1.ics", "etag-1", ics("uid-1@example.test", "Refused")),
                       object("o2.ics", "etag-2", ics("uid-2@example.test", "Accepted")));
    when(agendaEventService.createEvent(any(), any(), any(), any(), any(), any(), anyBoolean(), anyLong()))
                                                                                                          .thenThrow(new IllegalStateException("no"))
                                                                                                          .thenReturn(event(502L));

    assertEquals(1, service.importInto(USER, pair(), calendar(), from(), to()));

    // The refused one is not recorded, so the next run tries it again rather
    // than believing it was imported.
    verify(caldavSyncStorage).saveObject(any());
  }

  /**
   * An account pointed at another server is not read with these credentials.
   */
  @Test
  public void aBindingBelongingToAnotherServerIsNotRead() {
    CaldavUserSetting elsewhere = settings();
    elsewhere.setServerId(SERVER + 1);
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(elsewhere);

    assertEquals(0, service.importInto(USER, pair(), calendar(), from(), to()));

    verify(calDavClient, never()).calendarQuery(any(), anyString(), any(), any(), anyString(), anyString());
  }

  /**
   * With no account there is nothing to read with.
   */
  @Test
  public void anAccountThatIsGoneReadsNothing() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    assertEquals(0, service.importInto(USER, pair(), calendar(), from(), to()));

    verify(calDavClient, never()).calendarQuery(any(), anyString(), any(), any(), anyString(), anyString());
  }

  /**
   * Nothing to import into is not an error.
   */
  @Test
  public void nothingToImportIntoIsNotAnError() {
    assertEquals(0, service.importInto(USER, null, calendar(), from(), to()));
    assertEquals(0, service.importInto(USER, pair(), null, from(), to()));
  }

  @Test
  public void theWindowIsWalkedInSlicesRatherThanAskedForAtOnce() {
    // Observed live: a year asked for in one calendar-query is one enormous
    // response, and it timed out against a real calendar — losing the whole
    // collection for it. Sliced, each round trip is small.
    ReflectionTestUtils.setField(service, "sliceDays", 10L);
    when(calDavClient.calendarQuery(any(), anyString(), any(), any(), anyString(), anyString())).thenReturn(List.of());

    service.importInto(USER, pair(), calendar(), from(), from().plus(Duration.ofDays(30)));

    verify(calDavClient, times(3)).calendarQuery(any(), anyString(), any(), any(), anyString(), anyString());
  }

  @Test
  public void oneSliceTheServerCannotAnswerDoesNotCostTheRestOfTheWindow() throws Exception {
    // The failure that started this: one slow stretch of a calendar must not
    // lose the days on either side of it.
    ReflectionTestUtils.setField(service, "sliceDays", 10L);
    when(calDavClient.calendarQuery(any(), anyString(), any(), any(), anyString(), anyString()))
                                                                                               .thenThrow(new CalDavException("timed out"))
                                                                                               .thenReturn(List.of(object("o1.ics",
                                                                                                                          "etag-1",
                                                                                                                          ics("uid-1@example.test",
                                                                                                                              "Later"))))
                                                                                               .thenReturn(List.of());
    givenAgendaCreates(501L);

    assertEquals(1, service.importInto(USER, pair(), calendar(), from(), from().plus(Duration.ofDays(30))));
  }

  @Test
  public void aRemoteEditIsAppliedWhenItIsTheNewer() throws Exception {
    givenServerObjects(object("o1.ics", "etag-2", icsModifiedAt("uid-1@example.test", "Moved", "20261005T120000Z")));
    when(caldavSyncStorage.getObjectByUid(PAIR, "uid-1@example.test")).thenReturn(mapping("etag-1"));
    when(agendaEventService.getEventById(501L)).thenReturn(eventUpdatedAt("2026-10-05T09:00:00Z"));

    assertEquals(1, service.importInto(USER, pair(), calendar(), from(), to()));

    ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
    verify(agendaEventService).updateEvent(saved.capture(), any(), any(), any(), any(), any(), eq(false), eq(USER));
    assertEquals("Moved", saved.getValue().getSummary());
    // The same event, not a new one beside it.
    assertEquals(501L, saved.getValue().getId());
  }

  @Test
  public void aLocalEditMoreRecentThanTheRemoteOneIsNotOverwritten() throws Exception {
    // The edit is not lost — the outbound half carries it. What matters here
    // is that the remote copy does not silently win.
    givenServerObjects(object("o1.ics", "etag-2", icsModifiedAt("uid-1@example.test", "Stale", "20261005T090000Z")));
    when(caldavSyncStorage.getObjectByUid(PAIR, "uid-1@example.test")).thenReturn(mapping("etag-1"));
    when(agendaEventService.getEventById(501L)).thenReturn(eventUpdatedAt("2026-10-05T12:00:00Z"));

    assertEquals(0, service.importInto(USER, pair(), calendar(), from(), to()));

    verify(agendaEventService, never()).updateEvent(any(), any(), any(), any(), any(), any(), anyBoolean(), anyLong());
  }

  @Test
  public void aRefusedRemoteEditDoesNotRecordTheEtag() throws Exception {
    // Recording it would make the next run believe the two sides agree, and
    // the remote edit would be lost rather than reconsidered.
    givenServerObjects(object("o1.ics", "etag-2", icsModifiedAt("uid-1@example.test", "Stale", "20261005T090000Z")));
    when(caldavSyncStorage.getObjectByUid(PAIR, "uid-1@example.test")).thenReturn(mapping("etag-1"));
    when(agendaEventService.getEventById(501L)).thenReturn(eventUpdatedAt("2026-10-05T12:00:00Z"));

    service.importInto(USER, pair(), calendar(), from(), to());

    verify(caldavSyncStorage, never()).saveObject(any());
  }

  @Test
  public void theServerWinsWhenTheTwoSidesChangedAtTheSameMoment() throws Exception {
    // The tie is unresolvable and one side has to be named in advance: a rule
    // nobody can predict is worse than one that occasionally loses the wrong
    // edit. Remote is the side the user's other clients write to.
    givenServerObjects(object("o1.ics", "etag-2", icsModifiedAt("uid-1@example.test", "Remote", "20261005T090000Z")));
    when(caldavSyncStorage.getObjectByUid(PAIR, "uid-1@example.test")).thenReturn(mapping("etag-1"));
    when(agendaEventService.getEventById(501L)).thenReturn(eventUpdatedAt("2026-10-05T09:00:00Z"));

    assertEquals(1, service.importInto(USER, pair(), calendar(), from(), to()));
  }

  @Test
  public void anObjectThatNeverSaysWhenItChangedIsStillApplied() throws Exception {
    // Refusing a change because the server said nothing about its age would
    // freeze the event here for good.
    givenServerObjects(object("o1.ics", "etag-2", ics("uid-1@example.test", "No timestamp")));
    when(caldavSyncStorage.getObjectByUid(PAIR, "uid-1@example.test")).thenReturn(mapping("etag-1"));
    when(agendaEventService.getEventById(501L)).thenReturn(eventUpdatedAt("2026-10-05T12:00:00Z"));

    assertEquals(1, service.importInto(USER, pair(), calendar(), from(), to()));
  }

  @Test
  public void aMappingWhoseEventIsGoneIsDroppedSoTheObjectComesBack() throws Exception {
    // Otherwise a row describing an event nobody has skips the object for
    // ever, and the user is left with a calendar quietly missing a meeting.
    givenServerObjects(object("o1.ics", "etag-2", ics("uid-1@example.test", "Back again")));
    when(caldavSyncStorage.getObjectByUid(PAIR, "uid-1@example.test")).thenReturn(mapping("etag-1"));
    when(agendaEventService.getEventById(501L)).thenReturn(null);

    service.importInto(USER, pair(), calendar(), from(), to());

    verify(caldavSyncStorage).deleteObject(1L);
  }

  /**
   * @param uid the object's uid
   * @param summary its summary
   * @param lastModified its LAST-MODIFIED stamp
   * @return a single-event calendar object carrying that stamp
   */
  private String icsModifiedAt(String uid, String summary, String lastModified) {
    return """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//test//EN
        BEGIN:VEVENT
        DTSTAMP:20261001T080000Z
        LAST-MODIFIED:%s
        UID:%s
        DTSTART:20261012T090000Z
        DTEND:20261012T100000Z
        SUMMARY:%s
        END:VEVENT
        END:VCALENDAR
        """.formatted(lastModified, uid, summary);
  }

  /**
   * @param updated when agenda last saw it change
   * @return an event agenda would answer with
   */
  private Event eventUpdatedAt(String updated) {
    Event event = new Event();
    event.setId(501L);
    event.setUpdated(java.time.ZonedDateTime.parse(updated));
    return event;
  }

  @Test
  public void anOverrideAmendsTheOccurrenceItNamesRatherThanBecomingAMeetingOfItsOwn() throws Exception {
    // Creating it beside the series would show the amendment as a separate
    // meeting on a day the series already covers.
    givenServerObjects(object("o1.ics", "etag-1", SERIES));
    givenAgendaCreates(501L);
    Event occurrence = new Event();
    occurrence.setId(777L);
    when(agendaEventService.saveEventExceptionalOccurrence(eq(501L), any())).thenReturn(occurrence);

    service.importInto(USER, pair(), calendar(), from(), to());

    ArgumentCaptor<Event> amended = ArgumentCaptor.forClass(Event.class);
    verify(agendaEventService, atLeastOnce()).updateEvent(amended.capture(),
                                                          any(),
                                                          any(),
                                                          any(),
                                                          any(),
                                                          any(),
                                                          eq(false),
                                                          eq(USER));
    Event moved = amended.getAllValues().stream().filter(e -> e.getId() == 777L).findFirst().orElseThrow();
    assertEquals(501L, moved.getParentId());
    assertEquals("Weekly standup (moved)", moved.getSummary());
  }

  @Test
  public void anAmendedOccurrenceNeverCarriesTheSeriesRule() throws Exception {
    // Handing agenda a rule on an override would turn one amended meeting
    // into a second series running beside the first.
    givenServerObjects(object("o1.ics", "etag-1", SERIES));
    givenAgendaCreates(501L);
    Event occurrence = new Event();
    occurrence.setId(777L);
    when(agendaEventService.saveEventExceptionalOccurrence(eq(501L), any())).thenReturn(occurrence);

    service.importInto(USER, pair(), calendar(), from(), to());

    ArgumentCaptor<Event> amended = ArgumentCaptor.forClass(Event.class);
    verify(agendaEventService, atLeastOnce()).updateEvent(amended.capture(),
                                                          any(),
                                                          any(),
                                                          any(),
                                                          any(),
                                                          any(),
                                                          anyBoolean(),
                                                          anyLong());
    Event moved = amended.getAllValues().stream().filter(e -> e.getId() == 777L).findFirst().orElseThrow();
    assertNull(moved.getRecurrence());
  }

  @Test
  public void anExcludedDateCancelsThatOccurrenceRatherThanDeletingIt() throws Exception {
    // Observed live: deleting the exceptional occurrence removes the
    // *exception*, not the date — the series then covers that day again and
    // the cancelled meeting comes back. Agenda's way to empty one date is an
    // exceptional occurrence marked CANCELLED.
    givenServerObjects(object("o1.ics", "etag-1", SERIES));
    givenAgendaCreates(501L);
    Event moved = new Event();
    moved.setId(777L);
    Event cancelled = new Event();
    cancelled.setId(888L);
    when(agendaEventService.saveEventExceptionalOccurrence(eq(501L), any())).thenReturn(moved).thenReturn(cancelled);

    service.importInto(USER, pair(), calendar(), from(), to());

    verify(agendaEventService, never()).deleteEventById(anyLong(), anyLong());
    ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
    verify(agendaEventService, times(2)).updateEvent(saved.capture(),
                                                     any(),
                                                     any(),
                                                     any(),
                                                     any(),
                                                     any(),
                                                     anyBoolean(),
                                                     anyLong());
    Event emptied = saved.getAllValues().stream().filter(e -> e.getId() == 888L).findFirst().orElseThrow();
    assertEquals(EventStatus.CANCELLED, emptied.getStatus());
    // A cancelled occurrence carrying the series rule would be a second
    // series, and agenda warns about exactly that shape.
    assertNull(emptied.getRecurrence());
  }

  @Test
  public void oneOccurrenceThatWillNotTakeDoesNotCostTheSeries() throws Exception {
    // The series is already in place and correct for every other date.
    givenServerObjects(object("o1.ics", "etag-1", SERIES));
    givenAgendaCreates(501L);
    when(agendaEventService.saveEventExceptionalOccurrence(eq(501L), any())).thenThrow(new IllegalStateException("no"));

    assertEquals(1, service.importInto(USER, pair(), calendar(), from(), to()));

    verify(caldavSyncStorage).saveObject(any());
  }

  @Test
  public void aSeriesWithNothingToSayAboutItsOccurrencesCostsNothing() throws Exception {
    givenServerObjects(object("o1.ics", "etag-1", ics("uid-1@example.test", "Plain")));
    givenAgendaCreates(501L);

    service.importInto(USER, pair(), calendar(), from(), to());

    verify(agendaEventService, never()).saveEventExceptionalOccurrence(anyLong(), any());
  }

  /**
   * @param objects what the server answers
   */
  private void givenServerObjects(CalendarObject... objects) {
    when(calDavClient.calendarQuery(any(), anyString(), any(), any(), anyString(), anyString()))
                                                                                               .thenReturn(List.of(objects));
  }

  /**
   * @param eventId the id agenda mints
   * @throws Exception when the stub cannot be set
   */
  /**
   * States the attendees agenda already holds for an event.
   *
   * @param eventId the event
   * @param attendees the attendees it carries
   */
  private void givenEventAttendees(long eventId, EventAttendee... attendees) {
    when(agendaEventAttendeeService.getEventAttendees(eventId)).thenReturn(new EventAttendeeList(List.of(attendees)));
  }

  /**
   * One attendee, accepted.
   *
   * @param identityId identity of the attendee
   * @return the attendee
   */
  private EventAttendee attendee(long identityId) {
    EventAttendee attendee = new EventAttendee();
    attendee.setIdentityId(identityId);
    attendee.setResponse(EventAttendeeResponse.ACCEPTED);
    return attendee;
  }

  private void givenAgendaCreates(long eventId) throws Exception {
    when(agendaEventService.createEvent(any(), any(), any(), any(), any(), any(), anyBoolean(), anyLong()))
                                                                                                          .thenReturn(event(eventId));
  }

  /**
   * @param id the event identifier
   * @return an event agenda would answer with
   */
  private Event event(long id) {
    Event event = new Event();
    event.setId(id);
    return event;
  }

  /**
   * @param href the object path
   * @param etag its entity tag
   * @param data its calendar data
   * @return the object as the server sends it
   */
  private CalendarObject object(String href, String etag, String data) {
    return new CalendarObject(href, etag, data);
  }

  /**
   * @param uid the object's uid
   * @param summary its summary
   * @return a single-event calendar object
   */
  private String ics(String uid, String summary) {
    return """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//test//EN
        BEGIN:VEVENT
        DTSTAMP:20261001T080000Z
        UID:%s
        DTSTART:20261012T090000Z
        DTEND:20261012T100000Z
        SUMMARY:%s
        END:VEVENT
        END:VCALENDAR
        """.formatted(uid, summary);
  }

  /**
   * @param etag the tag recorded at the last import
   * @return an existing object mapping
   */
  private ObjectSync mapping(String etag) {
    ObjectSync mapping = new ObjectSync();
    mapping.setId(1L);
    mapping.setCalendarSyncId(PAIR);
    mapping.setIcsUid("uid-1@example.test");
    mapping.setLocalEventId(501L);
    mapping.setEtag(etag);
    return mapping;
  }

  /**
   * @return the binding being read
   */
  private CalendarSync pair() {
    CalendarSync pair = new CalendarSync();
    pair.setId(PAIR);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setRemoteHref(HREF);
    pair.setOrigin(SyncOrigin.REMOTE);
    return pair;
  }

  /**
   * @return the eXo calendar standing for the collection
   */
  private Calendar calendar() {
    Calendar calendar = new Calendar();
    calendar.setId(CALENDAR);
    return calendar;
  }

  /**
   * @return a connected account
   */
  private CaldavUserSetting settings() {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername(LOGIN);
    setting.setPassword("secret");
    setting.setServerId(SERVER);
    return setting;
  }

  /**
   * @return the window's start
   */
  private Instant from() {
    return Instant.parse("2026-10-01T00:00:00Z");
  }

  /**
   * @return the window's end
   */
  private Instant to() {
    return Instant.parse("2026-11-01T00:00:00Z");
  }
}
