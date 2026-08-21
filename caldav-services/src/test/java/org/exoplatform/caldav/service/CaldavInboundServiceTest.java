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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.Event;
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

  @Mock
  private CalDavClient           calDavClient;

  @Mock
  private CaldavSyncStorage      caldavSyncStorage;

  @Mock
  private CaldavConnectorStorage caldavConnectorStorage;

  @Mock
  private AgendaEventService     agendaEventService;

  @Mock
  private CalDavEndpoint         endpoint;

  @Spy
  private IcsParser              icsParser;

  @Spy
  private IcsEventMapper         icsEventMapper;

  @InjectMocks
  private CaldavInboundService   service;

  /**
   * A connected account and an endpoint, for the tests that get that far.
   */
  @BeforeEach
  public void connectAnAccount() {
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
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
