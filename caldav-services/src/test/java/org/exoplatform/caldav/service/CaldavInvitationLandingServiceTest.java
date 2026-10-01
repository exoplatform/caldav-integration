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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.CalendarObjectWriter;
import org.exoplatform.caldav.client.CalendarObjectWriters;
import org.exoplatform.caldav.client.PutResult;
import org.exoplatform.caldav.ics.IcsMerger;
import org.exoplatform.caldav.ics.IcsParser;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.emailConnector.model.InvitationAnswer;
import org.exoplatform.emailConnector.model.InvitationLanding;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * An invitation answered from a mail lands in the user's CalDAV-bound calendar
 * through the sweep's own import, with the answer recorded where every eXo
 * answer is (EXO-90848).
 *
 * <p>
 * What is pinned: the object reaches the server once and only when the server
 * does not hold it; a mail server that filed it already is read, not written;
 * the eXo event comes from the import, never from a second create; the answer
 * goes through agenda and, when the import's own "accepted" would hide it from
 * the listener, is pushed here; and nothing of the sender's is trusted beyond
 * the user's own bindings.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavInvitationLandingServiceTest {

  private static final String        LOGIN    = "john";

  private static final long          USER     = 42L;

  private static final long          SERVER   = 7L;

  private static final long          PAIR     = 11L;

  private static final long          OTHER    = 12L;

  private static final long          EVENT    = 964L;

  private static final long          CALENDAR = 5L;

  private static final String        MAILBOX  = "john@acme.com";

  private static final String        UID      = "weekly-sync@google.com";

  private static final String        HOME     = "/dav/calendars/john/default/";

  private static final String        HREF     = HOME + UID + ".ics";

  private static final String        OTHER_HOME = "/dav/calendars/john/work/";

  private static final String        REQUEST  = "BEGIN:VCALENDAR\r\n"
      + "PRODID:-//Google Inc//Google Calendar 70.9054//EN\r\n"
      + "VERSION:2.0\r\n"
      + "METHOD:REQUEST\r\n"
      + "BEGIN:VTIMEZONE\r\n"
      + "TZID:Europe/Paris\r\n"
      + "BEGIN:STANDARD\r\n"
      + "DTSTART:19701025T030000\r\n"
      + "TZOFFSETFROM:+0200\r\n"
      + "TZOFFSETTO:+0100\r\n"
      + "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"
      + "END:STANDARD\r\n"
      + "BEGIN:DAYLIGHT\r\n"
      + "DTSTART:19700329T020000\r\n"
      + "TZOFFSETFROM:+0100\r\n"
      + "TZOFFSETTO:+0200\r\n"
      + "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n"
      + "END:DAYLIGHT\r\n"
      + "END:VTIMEZONE\r\n"
      + "BEGIN:VEVENT\r\n"
      + "UID:" + UID + "\r\n"
      + "SEQUENCE:2\r\n"
      + "DTSTAMP:20261001T080000Z\r\n"
      + "DTSTART;TZID=Europe/Paris:20261005T100000\r\n"
      + "DTEND;TZID=Europe/Paris:20261005T110000\r\n"
      + "RRULE:FREQ=WEEKLY;BYDAY=MO\r\n"
      + "SUMMARY:Weekly sync\r\n"
      + "ORGANIZER;CN=Olivia:mailto:olivia@partner.example\r\n"
      + "ATTENDEE;CN=John;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:" + MAILBOX + "\r\n"
      + "ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@partner.example\r\n"
      + "END:VEVENT\r\n"
      + "END:VCALENDAR\r\n";

  @Mock
  private IdentityManager            identityManager;

  @Mock
  private CaldavConnectorStorage     caldavConnectorStorage;

  @Mock
  private CaldavServerService        caldavServerService;

  @Mock
  private CaldavSyncStorage          caldavSyncStorage;

  @Mock
  private CalDavClient               calDavClient;

  @Mock
  private CalendarObjectWriters      calendarObjectWriters;

  @Mock
  private CalendarObjectWriter       writer;

  @Mock
  private CalDavEndpoint             endpoint;

  @Spy
  private IcsParser                  icsParser = new IcsParser();

  @Spy
  private IcsMerger                  icsMerger = new IcsMerger();

  @Mock
  private CaldavInboundService       caldavInboundService;

  @Mock
  private CaldavPushService          caldavPushService;

  @Mock
  private AgendaCalendarService      agendaCalendarService;

  @Mock
  private AgendaEventAttendeeService agendaEventAttendeeService;

  @InjectMocks
  private CaldavInvitationLandingService service;

  private CaldavUserSetting          settings;

  private CalendarSync               binding;

  private Calendar                   calendar;

  /**
   * A connected user with one calendar bound on the account, holding nothing
   * of the event yet.
   *
   * @throws Exception never
   */
  @BeforeEach
  public void setUp() throws Exception {
    Identity identity = new Identity(String.valueOf(USER));
    lenient().when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, LOGIN)).thenReturn(identity);
    settings = new CaldavUserSetting();
    settings.setServerId(SERVER);
    settings.setUsername("john@dav.example");
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings);
    lenient().when(caldavServerService.isConnected(settings)).thenReturn(true);
    binding = pair(PAIR, HOME, SyncOrigin.REMOTE, CalendarSyncStatus.ACTIVE, "anchor-1");
    lenient().when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(binding));
    lenient().when(caldavSyncStorage.getMirrorEventIdOnServer(SERVER, UID)).thenReturn(null);
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
    lenient().when(calendarObjectWriters.writer(endpoint)).thenReturn(writer);
    // Answered rather than left unstubbed, so a write the test forbids fails on
    // its verify and not on a null result.
    lenient().when(writer.putObject(any(), anyString(), anyString())).thenReturn(new PutResult(201, "\"w\"", null));
    lenient().when(writer.updateObject(any(), anyString(), anyString(), anyString())).thenReturn(new PutResult(204, "\"w\"", null));
    lenient().when(caldavPushService.addressesNaming(USER, settings)).thenReturn(List.of("john@dav.example"));
    calendar = new Calendar();
    calendar.setId(CALENDAR);
    calendar.setOwnerId(USER);
    calendar.setSyncUid("anchor-1");
    lenient().when(agendaCalendarService.getCalendars(0, Integer.MAX_VALUE, LOGIN)).thenReturn(List.of(calendar));
    lenient().when(agendaEventAttendeeService.getEventResponse(EVENT, null, USER)).thenReturn(EventAttendeeResponse.ACCEPTED);
  }

  /**
   * No connected account, or no calendar bound on it: not this add-on's user.
   * The mirror ledger is not a calendar binding.
   */
  @Test
  public void aUserWithoutABoundCalendarIsNotThisAddonsToLand() {
    when(caldavServerService.isConnected(settings)).thenReturn(false);
    assertFalse(service.land(landing(InvitationAnswer.ACCEPTED)));

    when(caldavServerService.isConnected(settings)).thenReturn(true);
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair(OTHER, "/dav/calendars/john/exo-meetings/",
                                                                           SyncOrigin.MIRROR,
                                                                           CalendarSyncStatus.ACTIVE,
                                                                           null),
                                                                      pair(OTHER + 1, HOME, SyncOrigin.REMOTE,
                                                                           CalendarSyncStatus.PAUSED,
                                                                           "anchor-2")));
    assertFalse(service.land(landing(InvitationAnswer.ACCEPTED)));
    verify(calDavClient, never()).fetchObject(any(), anyString());
    verify(caldavInboundService, never()).importInto(anyLong(), anyString(), any(), any(), any(), any());
  }

  /**
   * A server not holding the event gets it: the message's object with METHOD
   * gone and the user's answer on their line, created at the path the sweep
   * computes; then the sweep's own import creates the eXo event, and the answer
   * is recorded in agenda. "Accepted" is what the import already recorded, so
   * the listener would carry nothing: it is pushed here.
   *
   * @throws Exception never
   */
  @Test
  public void aNewInvitationIsWrittenThenImportedThenAnswered() throws Exception {
    when(calDavClient.fetchObject(endpoint, HREF)).thenReturn(null);
    when(writer.putObject(eq(endpoint), eq(HREF), anyString())).thenReturn(new PutResult(201, "\"e1\"", null));
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(null, mapping(HREF));
    when(caldavPushService.pushAnswer(USER, LOGIN, EVENT, "ACCEPTED")).thenReturn(true);

    assertTrue(service.land(landing(InvitationAnswer.ACCEPTED)));

    ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
    verify(writer).putObject(eq(endpoint), eq(HREF), stored.capture());
    String document = stored.getValue().replace("\r\n ", "");
    assertFalse(document.contains("METHOD:"), document);
    assertTrue(document.contains("TZID:Europe/Paris"), document);
    assertTrue(document.contains("UID:" + UID), document);
    assertTrue(document.contains("RRULE:FREQ=WEEKLY;BYDAY=MO"), document);
    assertTrue(document.contains("PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:" + MAILBOX) || document.contains("RSVP=TRUE;PARTSTAT=ACCEPTED:mailto:" + MAILBOX),
               "the user's line carries the answer: " + document);
    assertFalse(document.contains("NEEDS-ACTION"), document);
    verify(writer, never()).updateObject(any(), anyString(), anyString(), anyString());
    verify(caldavInboundService).importInto(USER,
                                            LOGIN,
                                            binding,
                                            calendar,
                                            Instant.parse("2026-10-04T08:00:00Z"),
                                            Instant.parse("2026-10-06T09:00:00Z"));
    verify(agendaEventAttendeeService).sendEventResponse(EVENT, USER, EventAttendeeResponse.ACCEPTED, false);
    verify(caldavPushService).pushAnswer(USER, LOGIN, EVENT, "ACCEPTED");
  }

  /**
   * The mail server filed the invitation itself: the object is there, nothing
   * is written, the import brings it in. A changed answer is agenda's listener's
   * to carry, so nothing is pushed from here; and a creation refused because the
   * collection holds the UID under another path reads the same way.
   *
   * @throws Exception never
   */
  @Test
  public void anInvitationTheServerAlreadyHoldsIsReadNotWritten() throws Exception {
    when(calDavClient.fetchObject(endpoint, HREF)).thenReturn(new CalendarObject(HREF, "\"e0\"", REQUEST.replace("METHOD:REQUEST\r\n", "")));
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(null, mapping(HREF));

    assertTrue(service.land(landing(InvitationAnswer.DECLINED)));

    verify(writer, never()).putObject(any(), anyString(), anyString());
    verify(caldavInboundService).importInto(eq(USER), eq(LOGIN), eq(binding), eq(calendar), any(), any());
    verify(agendaEventAttendeeService).sendEventResponse(EVENT, USER, EventAttendeeResponse.DECLINED, false);
    verify(caldavPushService, never()).pushAnswer(anyLong(), anyString(), anyLong(), anyString());

    when(calDavClient.fetchObject(endpoint, HREF)).thenReturn(null);
    when(writer.putObject(eq(endpoint), eq(HREF), anyString())).thenReturn(new PutResult(PutResult.PRECONDITION_FAILED, null, null));
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(null, mapping(HREF));
    assertTrue(service.land(landing(InvitationAnswer.DECLINED)));
  }

  /**
   * An event the user already holds is left as the server has it unless the
   * mail carries a strictly newer revision, which is then written over the
   * copy under the version just read; the import and the answer follow either
   * way.
   *
   * @throws Exception never
   */
  @Test
  public void aKnownEventIsRewrittenOnlyByANewerRevision() throws Exception {
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(mapping(HREF));
    when(calDavClient.fetchObject(endpoint, HREF)).thenReturn(new CalendarObject(HREF, "\"e5\"", REQUEST.replace("METHOD:REQUEST\r\n", "")));

    assertTrue(service.land(landing(InvitationAnswer.TENTATIVE)));
    verify(writer, never()).updateObject(any(), anyString(), anyString(), anyString());
    verify(writer, never()).putObject(any(), anyString(), anyString());
    verify(caldavInboundService).importInto(eq(USER), eq(LOGIN), eq(binding), eq(calendar), any(), any());
    verify(agendaEventAttendeeService).sendEventResponse(EVENT, USER, EventAttendeeResponse.TENTATIVE, false);

    when(calDavClient.fetchObject(endpoint, HREF)).thenReturn(new CalendarObject(HREF,
                                                                                 "\"e5\"",
                                                                                 REQUEST.replace("METHOD:REQUEST\r\n", "")
                                                                                        .replace("SEQUENCE:2", "SEQUENCE:1")));
    when(writer.updateObject(eq(endpoint), eq(HREF), anyString(), eq("\"e5\""))).thenReturn(new PutResult(204, "\"e6\"", null));
    assertTrue(service.land(landing(InvitationAnswer.TENTATIVE)));
    ArgumentCaptor<String> rewritten = ArgumentCaptor.forClass(String.class);
    verify(writer).updateObject(eq(endpoint), eq(HREF), rewritten.capture(), eq("\"e5\""));
    assertTrue(rewritten.getValue().contains("SEQUENCE:2"), rewritten.getValue());
    assertTrue(rewritten.getValue().replace("\r\n ", "").contains("PARTSTAT=TENTATIVE"), rewritten.getValue());
  }

  /**
   * Nothing of the sender's is trusted: a message that is not about the event
   * answered, that is about one occurrence only, that cannot be read, or whose
   * UID names a meeting this deployment wrote is refused before anything is
   * written or imported.
   *
   * @throws Exception never
   */
  @Test
  public void theSendersObjectIsCheckedBeforeAnythingIsWritten() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> service.land(landing(InvitationAnswer.ACCEPTED, "another-uid", REQUEST)));
    String occurrenceOnly = REQUEST.replace("RRULE:FREQ=WEEKLY;BYDAY=MO\r\n", "RECURRENCE-ID;TZID=Europe/Paris:20261012T100000\r\n");
    assertThrows(IllegalArgumentException.class, () -> service.land(landing(InvitationAnswer.ACCEPTED, UID, occurrenceOnly)));
    assertThrows(IllegalArgumentException.class, () -> service.land(landing(InvitationAnswer.ACCEPTED, UID, "not a calendar")));

    when(caldavSyncStorage.getMirrorEventIdOnServer(SERVER, UID)).thenReturn(EVENT);
    assertThrows(IllegalArgumentException.class, () -> service.land(landing(InvitationAnswer.ACCEPTED)));

    verify(calDavClient, never()).fetchObject(any(), anyString());
    verify(writer, never()).putObject(any(), anyString(), anyString());
    verify(caldavInboundService, never()).importInto(anyLong(), anyString(), any(), any(), any(), any());
    verify(agendaEventAttendeeService, never()).sendEventResponse(anyLong(), anyLong(), any(), eq(false));
  }

  /**
   * An object on the server the import did not bring in is a failure the user
   * is told of, not a silent "no calendar".
   *
   * @throws Exception never
   */
  @Test
  public void anObjectTheImportDidNotBringInIsAFailure() throws Exception {
    when(calDavClient.fetchObject(endpoint, HREF)).thenReturn(null);
    when(writer.putObject(eq(endpoint), eq(HREF), anyString())).thenReturn(new PutResult(201, "\"e1\"", null));
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(null);

    assertThrows(IllegalStateException.class, () -> service.land(landing(InvitationAnswer.ACCEPTED)));
    verify(agendaEventAttendeeService, never()).sendEventResponse(anyLong(), anyLong(), any(), eq(false));
  }

  /**
   * With several calendars bound, a new invitation goes where the mail server
   * would file it: the account's default calendar; the binding already holding
   * the event wins over that.
   *
   * @throws Exception never
   */
  @Test
  public void aNewInvitationGoesToTheDefaultCalendarAndAKnownOneStaysWhereItIs() throws Exception {
    CalendarSync work = pair(OTHER, OTHER_HOME, SyncOrigin.EXO, CalendarSyncStatus.ACTIVE, "anchor-2");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(work, binding));
    when(calDavClient.discoverDefaultCalendar(endpoint)).thenReturn(HOME);
    when(calDavClient.fetchObject(endpoint, HREF)).thenReturn(null);
    when(writer.putObject(eq(endpoint), eq(HREF), anyString())).thenReturn(new PutResult(201, "\"e1\"", null));
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(null, mapping(HREF));
    when(caldavSyncStorage.getObjectByUid(OTHER, UID)).thenReturn(null);

    assertTrue(service.land(landing(InvitationAnswer.ACCEPTED)));
    verify(writer).putObject(eq(endpoint), eq(HREF), anyString());
    verify(caldavInboundService).importInto(eq(USER), eq(LOGIN), eq(binding), eq(calendar), any(), any());

    Calendar workCalendar = new Calendar();
    workCalendar.setId(CALENDAR + 1);
    workCalendar.setOwnerId(USER);
    workCalendar.setSyncUid("anchor-2");
    when(agendaCalendarService.getCalendars(0, Integer.MAX_VALUE, LOGIN)).thenReturn(List.of(calendar, workCalendar));
    when(caldavSyncStorage.getObjectByUid(OTHER, UID)).thenReturn(mapping(OTHER_HOME + UID + ".ics"));
    when(calDavClient.fetchObject(endpoint, OTHER_HOME + UID + ".ics")).thenReturn(new CalendarObject(OTHER_HOME + UID + ".ics",
                                                                                                     "\"e9\"",
                                                                                                     REQUEST.replace("METHOD:REQUEST\r\n",
                                                                                                                     "")));
    assertTrue(service.land(landing(InvitationAnswer.ACCEPTED)));
    verify(caldavInboundService).importInto(eq(USER), eq(LOGIN), eq(work), eq(workCalendar), any(), any());
  }

  /**
   * The landing of the test's invitation.
   *
   * @param answer the answer given
   * @return the landing
   */
  private static InvitationLanding landing(InvitationAnswer answer) {
    return landing(answer, UID, REQUEST);
  }

  /**
   * A landing.
   *
   * @param answer the answer given
   * @param uid what the reader said the UID is
   * @param icalendar the object
   * @return the landing
   */
  private static InvitationLanding landing(InvitationAnswer answer, String uid, String icalendar) {
    return new InvitationLanding(LOGIN, MAILBOX, uid, null, 2, answer, icalendar);
  }

  /**
   * A binding.
   *
   * @param id its id
   * @param href the collection
   * @param origin who created the collection
   * @param status its status
   * @param anchor the eXo calendar's sync uid
   * @return the binding
   */
  private static CalendarSync pair(long id, String href, SyncOrigin origin, CalendarSyncStatus status, String anchor) {
    CalendarSync pair = new CalendarSync();
    pair.setId(id);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setRemoteHref(href);
    pair.setOrigin(origin);
    pair.setStatus(status);
    pair.setLocalCalendarSyncUid(anchor);
    return pair;
  }

  /**
   * The mapping the import records for the event.
   *
   * @param href where the object is
   * @return the mapping
   */
  private static ObjectSync mapping(String href) {
    ObjectSync mapping = new ObjectSync();
    mapping.setIcsUid(UID);
    mapping.setLocalEventId(EVENT);
    mapping.setRemoteHref(href);
    mapping.setEtag("\"e1\"");
    return mapping;
  }
}
