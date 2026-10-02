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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.agenda.util.EventIcsBuilder;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavForbiddenException;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.CalendarObjectWriter;
import org.exoplatform.caldav.client.CalendarObjectWriters;
import org.exoplatform.caldav.client.PutResult;
import org.exoplatform.caldav.ics.IcsMerger;
import org.exoplatform.caldav.ics.IcsParser;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.LandedMailInvitation;
import org.exoplatform.caldav.model.MailInvitation;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * An invitation answered from a mail lands in the user's CalDAV-bound calendar
 * through the sweep's own import, with the answer written onto the copy and
 * recorded where every eXo answer is (EXO-90848).
 *
 * <p>
 * What is pinned: the collection is read before anything is written, so an
 * object the mail server filed is found by its UID whatever its name; the
 * object reaches the server once, created only, under a name of this service's
 * own; the eXo event comes from the import, never from a second create; the
 * answer is written onto the copy on the version the server holds now, with
 * every address the copy may name the user by, the mailbox first, and then
 * recorded through agenda; a copy the user holds is rewritten by its organiser
 * only, and only by a newer revision; and nothing of the sender's is trusted
 * beyond the user's own bindings.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavInvitationLandingServiceTest {

  private static final String        LOGIN      = "john";

  private static final long          USER       = 42L;

  private static final long          SERVER     = 7L;

  private static final long          PAIR       = 11L;

  private static final long          OTHER      = 12L;

  private static final long          EVENT      = 964L;

  private static final long          CALENDAR   = 5L;

  private static final String        MAILBOX    = "john@acme.com";

  private static final String        ACCOUNT    = "john@dav.example";

  private static final String        ORGANISER  = "olivia@partner.example";

  private static final String        UID        = "weekly-sync@google.com";

  private static final String        HOME       = "/dav/calendars/john/default/";

  private static final String        HREF       = HOME + UID + ".ics";

  private static final String        FILED_HREF = HOME + "51.ics";

  private static final String        OTHER_HOME = "/dav/calendars/john/work/";

  private static final Instant       FROM       = Instant.parse("2026-10-04T08:00:00Z");

  private static final Instant       TO         = Instant.parse("2026-10-06T09:00:00Z");

  private static final String        REQUEST    = "BEGIN:VCALENDAR\r\n"
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
      + "ORGANIZER;CN=Olivia:mailto:" + ORGANISER + "\r\n"
      + "ATTENDEE;CN=John;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:" + MAILBOX + "\r\n"
      + "ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@partner.example\r\n"
      + "END:VEVENT\r\n"
      + "END:VCALENDAR\r\n";

  /** The copy a server holds of the same event, as the mail server filed it. */
  private static final String        COPY       = REQUEST.replace("METHOD:REQUEST\r\n", "");

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
  private AgendaEventService         agendaEventService;

  @Mock
  private CaldavEventPropagationService caldavEventPropagationService;

  @Mock
  private AgendaEventAttendeeService agendaEventAttendeeService;

  @InjectMocks
  private CaldavInvitationLandingService service;

  private CaldavUserSetting          settings;

  private CalendarSync               binding;

  private Calendar                   calendar;

  /**
   * A connected user with one calendar bound on the account, holding nothing
   * of the event yet; the writer answers every write, so a write a test forbids
   * fails on its verify and not on a null result.
   *
   * @throws Exception never
   */
  @BeforeEach
  public void setUp() throws Exception {
    Identity identity = new Identity(String.valueOf(USER));
    lenient().when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, LOGIN)).thenReturn(identity);
    settings = new CaldavUserSetting();
    settings.setServerId(SERVER);
    settings.setUsername(ACCOUNT);
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings);
    lenient().when(caldavServerService.isConnected(settings)).thenReturn(true);
    binding = pair(PAIR, HOME, SyncOrigin.REMOTE, CalendarSyncStatus.ACTIVE, "anchor-1");
    lenient().when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(binding));
    lenient().when(caldavSyncStorage.getMirrorEventIdOnServer(SERVER, UID)).thenReturn(null);
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
    lenient().when(calendarObjectWriters.writer(endpoint)).thenReturn(writer);
    lenient().when(writer.putObject(any(), anyString(), anyString())).thenReturn(new PutResult(201, "\"w\"", null));
    lenient().when(writer.updateObject(any(), anyString(), anyString(), anyString())).thenReturn(new PutResult(204, "\"w\"", null));
    lenient().when(caldavPushService.addressesNaming(USER, settings)).thenReturn(List.of(ACCOUNT));
    lenient().when(caldavPushService.pushAnswerOnto(anyLong(), anyString(), any(), any(), anyString(), anyLong()))
             .thenReturn(CaldavPushService.AnswerOutcome.WRITTEN);
    calendar = new Calendar();
    calendar.setId(CALENDAR);
    calendar.setOwnerId(USER);
    calendar.setSyncUid("anchor-1");
    lenient().when(agendaCalendarService.getCalendars(0, Integer.MAX_VALUE, LOGIN)).thenReturn(List.of(calendar));
  }

  /**
   * No connected account, or no calendar bound on it: not this add-on's user.
   * The mirror ledger is not a calendar binding.
   */
  @Test
  public void aUserWithoutABoundCalendarIsNotThisAddonsToLand() {
    when(caldavServerService.isConnected(settings)).thenReturn(false);
    assertNull(service.land(invitation(EventAttendeeResponse.ACCEPTED)));

    when(caldavServerService.isConnected(settings)).thenReturn(true);
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair(OTHER,
                                                                           "/dav/calendars/john/exo-meetings/",
                                                                           SyncOrigin.MIRROR,
                                                                           CalendarSyncStatus.ACTIVE,
                                                                           null),
                                                                      pair(OTHER + 1,
                                                                           HOME,
                                                                           SyncOrigin.REMOTE,
                                                                           CalendarSyncStatus.PAUSED,
                                                                           "anchor-2")));
    assertNull(service.land(invitation(EventAttendeeResponse.ACCEPTED)));
    verify(caldavInboundService, never()).importInto(anyLong(), anyString(), any(), any(), any(), any());
    verify(writer, never()).putObject(any(), anyString(), anyString());
  }

  /**
   * A server not holding the event: the collection is read first and finds
   * nothing, the message's object is created — METHOD gone, zones and rule
   * kept, the user's answer on their line — read back through the sweep's own
   * import, and the answer written onto the copy with the mailbox first, then
   * recorded in agenda without the "response sent" broadcast.
   *
   * @throws Exception never
   */
  @Test
  public void aNewInvitationIsReadThenWrittenThenImportedThenAnswered() throws Exception {
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(null, null, mapping(HREF));

    assertNotNull(service.land(invitation(EventAttendeeResponse.ACCEPTED)));

    InOrder order = inOrder(caldavInboundService, writer, caldavPushService, agendaEventAttendeeService);
    order.verify(caldavInboundService).importInto(USER, LOGIN, binding, calendar, FROM, TO);
    ArgumentCaptor<String> href = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
    order.verify(writer).putObject(eq(endpoint), href.capture(), stored.capture());
    order.verify(caldavInboundService).importInto(USER, LOGIN, binding, calendar, FROM, TO);
    order.verify(caldavPushService).pushAnswerOnto(USER, LOGIN, mapping(HREF), List.of(MAILBOX, ACCOUNT), "ACCEPTED", EVENT);
    assertTrue(href.getValue().matches(HOME + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.ics"),
               "named by this service, never by the sender: " + href.getValue());
    order.verify(agendaEventAttendeeService).sendEventResponse(EVENT, USER, EventAttendeeResponse.ACCEPTED, false);
    String document = stored.getValue().replace("\r\n ", "");
    assertFalse(document.contains("METHOD:"), document);
    assertTrue(document.contains("TZID:Europe/Paris"), document);
    assertTrue(document.contains("UID:" + UID), document);
    assertTrue(document.contains("RRULE:FREQ=WEEKLY;BYDAY=MO"), document);
    assertTrue(document.contains("PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:" + MAILBOX)
        || document.contains("RSVP=TRUE;PARTSTAT=ACCEPTED:mailto:" + MAILBOX), "the user's line carries the answer: " + document);
    assertFalse(document.contains("NEEDS-ACTION"), document);
    verify(writer, never()).updateObject(any(), anyString(), anyString(), anyString());
    verify(calDavClient, never()).fetchObject(any(), anyString());
  }

  /**
   * The mail server filed the invitation itself, under its own name: the read
   * before the write finds it by its UID, nothing is written, and the copy is
   * answered where it is. The same for a copy mapped before this answer.
   *
   * @throws Exception never
   */
  @Test
  public void anInvitationTheServerAlreadyHoldsIsReadNotWritten() throws Exception {
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(null, mapping(FILED_HREF));

    assertNotNull(service.land(invitation(EventAttendeeResponse.DECLINED)));

    verify(caldavInboundService, times(1)).importInto(USER, LOGIN, binding, calendar, FROM, TO);
    verify(writer, never()).putObject(any(), anyString(), anyString());
    verify(writer, never()).updateObject(any(), anyString(), anyString(), anyString());
    verify(caldavPushService).pushAnswerOnto(USER, LOGIN, mapping(FILED_HREF), List.of(MAILBOX, ACCOUNT), "DECLINED", EVENT);
    verify(agendaEventAttendeeService).sendEventResponse(EVENT, USER, EventAttendeeResponse.DECLINED, false);

    // Mapped before this answer, and moved on the server since the last sweep:
    // the copy is read again through the sweep, and the answer is written on
    // the version the server holds now, not on the one the sweep recorded. A
    // fresh row per read, as storage answers.
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenAnswer(call -> mapping(FILED_HREF));
    when(calDavClient.fetchObject(endpoint, FILED_HREF)).thenReturn(new CalendarObject(FILED_HREF, "\"e5\"", COPY));
    assertNotNull(service.land(invitation(EventAttendeeResponse.TENTATIVE)));
    verify(caldavInboundService, times(2)).importInto(USER, LOGIN, binding, calendar, FROM, TO);
    verify(writer, never()).putObject(any(), anyString(), anyString());
    verify(writer, never()).updateObject(any(), anyString(), anyString(), anyString());
    ArgumentCaptor<ObjectSync> answered = ArgumentCaptor.forClass(ObjectSync.class);
    verify(caldavPushService).pushAnswerOnto(eq(USER), eq(LOGIN), answered.capture(), eq(List.of(MAILBOX, ACCOUNT)), eq("TENTATIVE"), eq(EVENT));
    assertEquals("\"e5\"", answered.getValue().getEtag(), "the version the server holds now");
    assertEquals(FILED_HREF, answered.getValue().getRemoteHref());
    assertEquals(EVENT, answered.getValue().getLocalEventId());
    verify(agendaEventAttendeeService).sendEventResponse(EVENT, USER, EventAttendeeResponse.TENTATIVE, false);

    // The sweep's record and the server agree: the row is answered on as is.
    when(calDavClient.fetchObject(endpoint, FILED_HREF)).thenReturn(new CalendarObject(FILED_HREF, "\"e1\"", COPY));
    assertNotNull(service.land(invitation(EventAttendeeResponse.TENTATIVE)));
    verify(caldavPushService).pushAnswerOnto(USER, LOGIN, mapping(FILED_HREF), List.of(MAILBOX, ACCOUNT), "TENTATIVE", EVENT);

    // The import recorded a version of its own after this call's read — the
    // copy moved in between: the later read wins over the earlier one.
    when(calDavClient.fetchObject(endpoint, FILED_HREF)).thenReturn(new CalendarObject(FILED_HREF, "\"e5\"", COPY));
    ObjectSync recordedByTheImport = mapping(FILED_HREF);
    recordedByTheImport.setEtag("\"e8\"");
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenAnswer(call -> mapping(FILED_HREF))
                                                     .thenAnswer(call -> mapping(FILED_HREF))
                                                     .thenReturn(recordedByTheImport);
    assertNotNull(service.land(invitation(EventAttendeeResponse.TENTATIVE)));
    verify(caldavPushService).pushAnswerOnto(USER, LOGIN, recordedByTheImport, List.of(MAILBOX, ACCOUNT), "TENTATIVE", EVENT);
    assertEquals("\"e8\"", recordedByTheImport.getEtag());
  }

  /**
   * A copy the user holds is rewritten by a strictly newer revision of its own
   * organiser's, under the version just read, and read back; the SEQUENCE
   * compared is the copy's master's, not an override's.
   *
   * @throws Exception never
   */
  @Test
  public void aKnownCopyIsRewrittenOnlyByItsOrganisersNewerRevision() throws Exception {
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenAnswer(call -> mapping(HREF));
    String older = COPY.replace("SEQUENCE:2", "SEQUENCE:1");
    when(calDavClient.fetchObject(endpoint, HREF)).thenReturn(new CalendarObject(HREF, "\"e5\"", older));

    assertNotNull(service.land(invitation(EventAttendeeResponse.TENTATIVE)));

    ArgumentCaptor<String> rewritten = ArgumentCaptor.forClass(String.class);
    verify(writer).updateObject(eq(endpoint), eq(HREF), rewritten.capture(), eq("\"e5\""));
    assertTrue(rewritten.getValue().contains("SEQUENCE:2"), rewritten.getValue());
    assertTrue(rewritten.getValue().replace("\r\n ", "").contains("PARTSTAT=TENTATIVE"), rewritten.getValue());
    verify(caldavInboundService).importInto(USER, LOGIN, binding, calendar, FROM, TO);
    ArgumentCaptor<ObjectSync> answered = ArgumentCaptor.forClass(ObjectSync.class);
    verify(caldavPushService).pushAnswerOnto(eq(USER), eq(LOGIN), answered.capture(), any(), eq("TENTATIVE"), eq(EVENT));
    assertEquals("\"w\"", answered.getValue().getEtag(), "the version the rewrite produced");

    // A server that altered what it stored answers the rewrite with no ETag
    // (RFC 4791 section 5.3.4): the version is read back before the answer.
    when(writer.updateObject(eq(endpoint), eq(HREF), anyString(), eq("\"e5\""))).thenReturn(new PutResult(204, null, null));
    when(calDavClient.fetchObject(endpoint, HREF)).thenReturn(new CalendarObject(HREF, "\"e5\"", older),
                                                              new CalendarObject(HREF, "\"e5b\"", COPY));
    assertNotNull(service.land(invitation(EventAttendeeResponse.TENTATIVE)));
    verify(caldavPushService, times(2)).pushAnswerOnto(eq(USER), eq(LOGIN), answered.capture(), any(), eq("TENTATIVE"), eq(EVENT));
    assertEquals("\"e5b\"", answered.getValue().getEtag(), "read back after a write the server answered without a version");

    // A copy served without a version cannot take a newer revision: the
    // server's shortcoming, told as a failure, never as the sender's fault.
    when(calDavClient.fetchObject(endpoint, HREF)).thenReturn(new CalendarObject(HREF, null, older));
    assertThrows(IllegalStateException.class, () -> service.land(invitation(EventAttendeeResponse.TENTATIVE)));
    verify(writer, never()).updateObject(eq(endpoint), eq(HREF), anyString(), eq((String) null));

    // The master is older but an override of the copy carries a higher
    // revision: the master still decides.
    String overrideNewer = older.replace("END:VEVENT\r\n",
                                         "END:VEVENT\r\nBEGIN:VEVENT\r\nUID:" + UID
                                             + "\r\nSEQUENCE:9\r\nRECURRENCE-ID;TZID=Europe/Paris:20261012T100000\r\n"
                                             + "DTSTAMP:20261001T080000Z\r\nDTSTART;TZID=Europe/Paris:20261012T140000\r\n"
                                             + "DTEND;TZID=Europe/Paris:20261012T150000\r\nSUMMARY:moved\r\nORGANIZER:mailto:"
                                             + ORGANISER + "\r\nEND:VEVENT\r\n");
    when(calDavClient.fetchObject(endpoint, HREF)).thenReturn(new CalendarObject(HREF, "\"e6\"", overrideNewer));
    assertNotNull(service.land(invitation(EventAttendeeResponse.TENTATIVE)));
    verify(writer).updateObject(eq(endpoint), eq(HREF), anyString(), eq("\"e6\""));
    verify(caldavInboundService, times(3)).importInto(USER, LOGIN, binding, calendar, FROM, TO);

    // An equal revision leaves the copy alone.
    when(calDavClient.fetchObject(endpoint, HREF)).thenReturn(new CalendarObject(HREF, "\"e7\"", COPY));
    assertNotNull(service.land(invitation(EventAttendeeResponse.TENTATIVE)));
    verify(writer, never()).updateObject(eq(endpoint), eq(HREF), anyString(), eq("\"e7\""));
    verify(caldavInboundService, times(4)).importInto(USER, LOGIN, binding, calendar, FROM, TO);
  }

  /**
   * Only an event's organiser rewrites it: a message from somebody else about a
   * copy the user holds is refused, and so is any message about a copy the user
   * organises themselves — from their own server, where an invitee learnt the
   * UID. Both before anything is written.
   *
   * @throws Exception never
   */
  @Test
  public void aCopyIsRewrittenByItsOrganiserOnly() throws Exception {
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(mapping(HREF));
    when(calDavClient.fetchObject(endpoint, HREF)).thenReturn(new CalendarObject(HREF,
                                                                                 "\"e5\"",
                                                                                 COPY.replace("SEQUENCE:2", "SEQUENCE:1")
                                                                                     .replace("mailto:" + ORGANISER,
                                                                                              "mailto:somebody@else.example")));
    assertThrows(IllegalArgumentException.class, () -> service.land(invitation(EventAttendeeResponse.ACCEPTED)));

    when(calDavClient.fetchObject(endpoint, HREF)).thenReturn(new CalendarObject(HREF,
                                                                                 "\"e5\"",
                                                                                 COPY.replace("SEQUENCE:2", "SEQUENCE:1")
                                                                                     .replace("mailto:" + ORGANISER,
                                                                                              "mailto:" + ACCOUNT.toUpperCase())));
    assertThrows(IllegalArgumentException.class, () -> service.land(invitation(EventAttendeeResponse.ACCEPTED)));

    verify(writer, never()).updateObject(any(), anyString(), anyString(), anyString());
    verify(writer, never()).putObject(any(), anyString(), anyString());
    verify(caldavInboundService, never()).importInto(anyLong(), anyString(), any(), any(), any(), any());
    verify(caldavPushService, never()).pushAnswerOnto(anyLong(), anyString(), any(), any(), anyString(), anyLong());
    verify(agendaEventAttendeeService, never()).sendEventResponse(anyLong(), anyLong(), any(), eq(false));
  }

  /**
   * Nothing of the sender's is trusted: a message that is not about the event
   * answered, that is about one occurrence only, that names no organiser, that
   * cannot be read, or whose UID names a meeting this deployment wrote is
   * refused before anything is read or written.
   *
   * @throws Exception never
   */
  @Test
  public void theSendersObjectIsCheckedBeforeAnythingIsWritten() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> service.land(invitation(EventAttendeeResponse.ACCEPTED, "another-uid", REQUEST)));
    String occurrenceOnly = REQUEST.replace("RRULE:FREQ=WEEKLY;BYDAY=MO\r\n", "RECURRENCE-ID;TZID=Europe/Paris:20261012T100000\r\n");
    assertThrows(IllegalArgumentException.class, () -> service.land(invitation(EventAttendeeResponse.ACCEPTED, UID, occurrenceOnly)));
    String noOrganiser = REQUEST.replace("ORGANIZER;CN=Olivia:mailto:" + ORGANISER + "\r\n", "");
    assertThrows(IllegalArgumentException.class, () -> service.land(invitation(EventAttendeeResponse.ACCEPTED, UID, noOrganiser)));
    assertThrows(IllegalArgumentException.class, () -> service.land(invitation(EventAttendeeResponse.ACCEPTED, UID, "not a calendar")));

    when(caldavSyncStorage.getMirrorEventIdOnServer(SERVER, UID)).thenReturn(EVENT);
    assertThrows(IllegalArgumentException.class, () -> service.land(invitation(EventAttendeeResponse.ACCEPTED)));

    verify(calDavClient, never()).fetchObject(any(), anyString());
    verify(writer, never()).putObject(any(), anyString(), anyString());
    verify(caldavInboundService, never()).importInto(anyLong(), anyString(), any(), any(), any(), any());
    verify(agendaEventAttendeeService, never()).sendEventResponse(anyLong(), anyLong(), any(), eq(false));
  }

  /**
   * A write the server refuses — a 403, or a 412 on a name this service just
   * minted — and an object the import did not bring in are failures the user is
   * told of, not a silent "no calendar"; agenda is told nothing.
   *
   * @throws Exception never
   */
  @Test
  public void aRefusedWriteAndAnObjectNotImportedAreFailures() throws Exception {
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(null);
    when(writer.putObject(eq(endpoint), anyString(), anyString())).thenThrow(new CalDavForbiddenException("no-uid-conflict"));
    assertThrows(IllegalStateException.class, () -> service.land(invitation(EventAttendeeResponse.ACCEPTED)));

    when(writer.putObject(eq(endpoint), anyString(), anyString())).thenReturn(new PutResult(PutResult.PRECONDITION_FAILED, null, null));
    assertThrows(IllegalStateException.class, () -> service.land(invitation(EventAttendeeResponse.ACCEPTED)));

    when(writer.putObject(eq(endpoint), anyString(), anyString())).thenReturn(new PutResult(201, "\"w\"", null));
    assertThrows(IllegalStateException.class, () -> service.land(invitation(EventAttendeeResponse.ACCEPTED)));
    verify(agendaEventAttendeeService, never()).sendEventResponse(anyLong(), anyLong(), any(), eq(false));
    verify(caldavPushService, never()).pushAnswerOnto(anyLong(), anyString(), any(), any(), anyString(), anyLong());
  }

  /**
   * The sender's UID is content, never a path: whatever it holds — a space, a
   * slash, an encoded parent, a non-ASCII letter — the object is created under
   * a name of this service's own, and the UID travels inside it.
   *
   * @throws Exception never
   */
  @Test
  public void theSendersUidIsNeverAPath() throws Exception {
    for (String uid : List.of("weird uid", "../../x/y", "a%2E%2E%2Fb", "r\u00e9union@partner.example", "hash#frag")) {
      when(caldavSyncStorage.getObjectByUid(PAIR, uid)).thenReturn(null, null, mapping(HOME + "x.ics"));
      lenient().when(caldavSyncStorage.getMirrorEventIdOnServer(SERVER, uid)).thenReturn(null);
      String request = REQUEST.replace("UID:" + UID, "UID:" + uid);

      assertNotNull(service.land(invitation(EventAttendeeResponse.ACCEPTED, uid, request)));
    }
    ArgumentCaptor<String> hrefs = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> documents = ArgumentCaptor.forClass(String.class);
    verify(writer, times(5)).putObject(eq(endpoint), hrefs.capture(), documents.capture());
    for (String href : hrefs.getAllValues()) {
      assertTrue(href.matches(HOME + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.ics"), href);
    }
    assertTrue(documents.getAllValues().get(1).replace("\r\n ", "").contains("UID:../../x/y"), "the UID stays content");
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
    Calendar workCalendar = new Calendar();
    workCalendar.setId(CALENDAR + 1);
    workCalendar.setOwnerId(USER);
    workCalendar.setSyncUid("anchor-2");
    when(agendaCalendarService.getCalendars(0, Integer.MAX_VALUE, LOGIN)).thenReturn(List.of(calendar, workCalendar));
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(work, binding));
    when(calDavClient.discoverDefaultCalendar(endpoint)).thenReturn(HOME);
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(null, null, mapping(HREF));
    when(caldavSyncStorage.getObjectByUid(OTHER, UID)).thenReturn(null);

    assertNotNull(service.land(invitation(EventAttendeeResponse.ACCEPTED)));
    verify(writer).putObject(eq(endpoint), anyString(), anyString());
    verify(caldavInboundService, times(2)).importInto(eq(USER), eq(LOGIN), eq(binding), eq(calendar), any(), any());

    when(caldavSyncStorage.getObjectByUid(OTHER, UID)).thenReturn(mapping(OTHER_HOME + UID + ".ics"));
    when(calDavClient.fetchObject(endpoint, OTHER_HOME + UID + ".ics")).thenReturn(new CalendarObject(OTHER_HOME + UID + ".ics",
                                                                                                     "\"e9\"",
                                                                                                     COPY));
    assertNotNull(service.land(invitation(EventAttendeeResponse.ACCEPTED)));
    verify(caldavInboundService, times(1)).importInto(eq(USER), eq(LOGIN), eq(work), eq(workCalendar), any(), any());
    ArgumentCaptor<ObjectSync> answered = ArgumentCaptor.forClass(ObjectSync.class);
    verify(caldavPushService, times(2)).pushAnswerOnto(eq(USER), eq(LOGIN), answered.capture(), any(), eq("ACCEPTED"), eq(EVENT));
    assertEquals(OTHER_HOME + UID + ".ics", answered.getValue().getRemoteHref());
    assertEquals("\"e9\"", answered.getValue().getEtag());
  }

  /**
   * The reader offers the calendar when the user has a connected account with a
   * calendar bound on it — database reads only.
   */
  @Test
  public void theCalendarIsHeldForAConnectedUserWithABinding() {
    assertTrue(service.holdsCalendarFor(LOGIN));
    verify(calDavClient, never()).endpoint(anyLong(), anyString());

    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of());
    assertFalse(service.holdsCalendarFor(LOGIN));
    when(caldavServerService.isConnected(settings)).thenReturn(false);
    assertFalse(service.holdsCalendarFor(LOGIN));
  }

  /**
   * "Add to my calendar": the event lands as sent — no answer on the copy, none
   * recorded in agenda — and the reader is handed agenda's own link to it. A
   * published event with no organiser lands the same way.
   *
   * @throws Exception never
   */
  @Test
  public void anInvitationAddedWithoutAnAnswerLandsAsSent() throws Exception {
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(null, null, mapping(HREF));
    try (MockedStatic<EventIcsBuilder> agenda = mockStatic(EventIcsBuilder.class)) {
      agenda.when(() -> EventIcsBuilder.eventUrl(EVENT)).thenReturn("https://exo.example/portal/dw/agenda?eventId=964");

      LandedMailInvitation landed = service.land(invitation("REQUEST", null, UID, REQUEST));

      assertEquals(EVENT, landed.eventId());
      assertEquals("https://exo.example/portal/dw/agenda?eventId=964", landed.link());
      assertFalse(landed.removed());
    }
    ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
    verify(writer).putObject(eq(endpoint), anyString(), stored.capture());
    assertTrue(stored.getValue().replace("\r\n ", "").contains("PARTSTAT=NEEDS-ACTION"), "stored as sent: " + stored.getValue());
    verify(caldavPushService, never()).pushAnswerOnto(anyLong(), anyString(), any(), any(), anyString(), anyLong());
    verify(agendaEventAttendeeService, never()).sendEventResponse(anyLong(), anyLong(), any(), eq(false));

    String published = REQUEST.replace("METHOD:REQUEST", "METHOD:PUBLISH").replace("ORGANIZER;CN=Olivia:mailto:" + ORGANISER + "\r\n", "");
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(null, null, mapping(HREF));
    assertNotNull(service.land(invitation("PUBLISH", null, UID, published)));
  }

  /**
   * A decline creates nothing: a meeting the user turned down and never added
   * does not belong in their calendar. The collection is still read first, so
   * a copy the mail server filed takes the decline instead of coming in
   * accepted on the next sweep; on a copy they hold it sets the answer, as
   * answering Decline in agenda does, and the copy stays.
   *
   * @throws Exception never
   */
  @Test
  public void aDeclineAnswersACopyTheUserHoldsAndCreatesNone() throws Exception {
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(null);
    assertNull(service.land(invitation(EventAttendeeResponse.DECLINED)));
    verify(caldavInboundService, times(1)).importInto(USER, LOGIN, binding, calendar, FROM, TO);
    verify(writer, never()).putObject(any(), anyString(), anyString());
    verify(agendaEventAttendeeService, never()).sendEventResponse(anyLong(), anyLong(), any(), eq(false));

    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenAnswer(call -> mapping(FILED_HREF));
    when(calDavClient.fetchObject(endpoint, FILED_HREF)).thenReturn(new CalendarObject(FILED_HREF, "\"e1\"", COPY));
    assertNotNull(service.land(invitation(EventAttendeeResponse.DECLINED)));
    verify(caldavPushService).pushAnswerOnto(eq(USER), eq(LOGIN), any(), eq(List.of(MAILBOX, ACCOUNT)), eq("DECLINED"), eq(EVENT));
    verify(agendaEventAttendeeService).sendEventResponse(EVENT, USER, EventAttendeeResponse.DECLINED, false);
    verify(writer, never()).deleteObject(any(), anyString(), anyString());
    verify(agendaEventService, never()).deleteEventById(anyLong(), anyLong());
  }

  /**
   * The organiser's cancellation removes the copy the user holds — the object
   * on the server under the version just read, the mapping, the eXo event, in
   * that order — and nothing when they hold none. From the organiser only.
   *
   * @throws Exception never
   */
  @Test
  public void aCancellationRemovesTheCopyTheUserHoldsFromItsOrganiserOnly() throws Exception {
    String cancel = REQUEST.replace("METHOD:REQUEST", "METHOD:CANCEL").replace("SEQUENCE:2", "SEQUENCE:3");
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(null);
    assertNull(service.land(invitation("CANCEL", null, UID, cancel)));
    verify(writer, never()).deleteObject(any(), anyString(), anyString());

    ObjectSync held = mapping(FILED_HREF);
    held.setId(5L);
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenReturn(held);
    when(calDavClient.fetchObject(endpoint, FILED_HREF)).thenReturn(new CalendarObject(FILED_HREF, "\"e5\"", COPY));
    when(writer.deleteObject(endpoint, FILED_HREF, "\"e5\"")).thenReturn(204);

    LandedMailInvitation removed = service.land(invitation("CANCEL", null, UID, cancel));

    assertTrue(removed.removed());
    assertEquals(EVENT, removed.eventId());
    assertNull(removed.link());
    InOrder order = inOrder(writer, caldavEventPropagationService, agendaEventService, caldavSyncStorage);
    order.verify(writer).deleteObject(endpoint, FILED_HREF, "\"e5\"");
    order.verify(caldavEventPropagationService).changedOnTheServer(EVENT, 5L);
    order.verify(agendaEventService).deleteEventById(EVENT, USER);
    order.verify(caldavSyncStorage).deleteObject(5L);
    verify(caldavInboundService, never()).importInto(anyLong(), anyString(), any(), any(), any(), any());
    verify(agendaEventAttendeeService, never()).sendEventResponse(anyLong(), anyLong(), any(), eq(false));

    // Already gone from agenda: done, the mapping dropped; agenda refusing:
    // the mapping stays, the user is told; the copy not served: nothing here.
    doThrow(new ObjectNotFoundException("gone")).when(agendaEventService).deleteEventById(EVENT, USER);
    assertTrue(service.land(invitation("CANCEL", null, UID, cancel)).removed());
    verify(caldavEventPropagationService, times(1)).notChangedAfterAll(EVENT);
    verify(caldavSyncStorage, times(2)).deleteObject(5L);
    doThrow(new IllegalAccessException("not yours")).when(agendaEventService).deleteEventById(EVENT, USER);
    assertThrows(IllegalStateException.class, () -> service.land(invitation("CANCEL", null, UID, cancel)));
    verify(caldavEventPropagationService, times(2)).notChangedAfterAll(EVENT);
    verify(caldavSyncStorage, times(2)).deleteObject(5L);
    when(calDavClient.fetchObject(endpoint, FILED_HREF)).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> service.land(invitation("CANCEL", null, UID, cancel)));
    verify(writer, times(3)).deleteObject(eq(endpoint), eq(FILED_HREF), anyString());

    when(calDavClient.fetchObject(endpoint, FILED_HREF)).thenReturn(new CalendarObject(FILED_HREF,
                                                                                       "\"e6\"",
                                                                                       COPY.replace("mailto:" + ORGANISER,
                                                                                                    "mailto:somebody@else.example")));
    assertThrows(IllegalArgumentException.class, () -> service.land(invitation("CANCEL", null, UID, cancel)));
    verify(writer, never()).deleteObject(endpoint, FILED_HREF, "\"e6\"");
    verify(agendaEventService, times(3)).deleteEventById(EVENT, USER);

    when(calDavClient.fetchObject(endpoint, FILED_HREF)).thenReturn(new CalendarObject(FILED_HREF, "\"e7\"", COPY));
    when(writer.deleteObject(endpoint, FILED_HREF, "\"e7\"")).thenReturn(PutResult.PRECONDITION_FAILED);
    assertThrows(IllegalStateException.class, () -> service.land(invitation("CANCEL", null, UID, cancel)));
  }

  /**
   * One of this deployment's own meetings, mailed by agenda with the UID
   * agenda stamps ({@code agenda-event-<id>@<this host>}): it is in agenda
   * already. Adding it hands back agenda's page and writes nothing; an answer
   * or a cancellation carried by such a message is refused, and so is a meeting
   * the user may not read. Another deployment's meeting is an external event.
   *
   * @throws Exception never
   */
  @Test
  public void anOwnAgendaMeetingIsNeverLandedTwice() throws Exception {
    String own = "agenda-event-42@exo.example";
    String published = REQUEST.replace("METHOD:REQUEST", "METHOD:PUBLISH").replace("UID:" + UID, "UID:" + own);
    Event meeting = new Event();
    meeting.setSummary(" weekly SYNC ");
    meeting.setStart(java.time.ZonedDateTime.parse("2026-10-05T10:00:00+02:00[Europe/Paris]"));
    when(agendaEventService.getEventById(42L, null, USER)).thenReturn(meeting);
    try (MockedStatic<CommonsUtils> platform = mockStatic(CommonsUtils.class);
         MockedStatic<EventIcsBuilder> agenda = mockStatic(EventIcsBuilder.class)) {
      platform.when(CommonsUtils::getCurrentDomain).thenReturn("https://exo.example:8443/portal");
      agenda.when(() -> EventIcsBuilder.eventUrl(42L)).thenReturn("https://exo.example/portal/dw/agenda?eventId=42");

      LandedMailInvitation held = service.land(invitation("PUBLISH", null, own, published));

      assertEquals(42L, held.eventId());
      assertEquals("https://exo.example/portal/dw/agenda?eventId=42", held.link());
      assertTrue(held.alreadyHeld(), "nothing was written: it is in agenda already");
      assertFalse(held.removed());
      // The sender's UID alone links nothing: the message must describe the meeting.
      assertThrows(IllegalArgumentException.class,
                   () -> service.land(invitation("PUBLISH", null, own, published.replace("SUMMARY:Weekly sync", "SUMMARY:Lunch"))));
      assertThrows(IllegalArgumentException.class,
                   () -> service.land(invitation("PUBLISH", null, own, published.replace("20261005T100000", "20261012T100000"))));
      // A meeting agenda cancelled is not "already in your calendar".
      meeting.setStatus(org.exoplatform.agenda.constant.EventStatus.CANCELLED);
      assertThrows(IllegalArgumentException.class, () -> service.land(invitation("PUBLISH", null, own, published)));
      meeting.setStatus(org.exoplatform.agenda.constant.EventStatus.CONFIRMED);
      verify(caldavInboundService, never()).importInto(anyLong(), anyString(), any(), any(), any(), any());
      verify(writer, never()).putObject(any(), anyString(), anyString());
      verify(agendaEventAttendeeService, never()).sendEventResponse(anyLong(), anyLong(), any(), eq(false));

      String request = REQUEST.replace("UID:" + UID, "UID:" + own);
      assertThrows(IllegalArgumentException.class,
                   () -> service.land(invitation("REQUEST", EventAttendeeResponse.ACCEPTED, own, request)));
      assertThrows(IllegalArgumentException.class,
                   () -> service.land(invitation("CANCEL", null, own, request.replace("METHOD:REQUEST", "METHOD:CANCEL"))));
      when(agendaEventService.getEventById(42L, null, USER)).thenThrow(new IllegalAccessException("not yours"));
      assertThrows(IllegalArgumentException.class, () -> service.land(invitation("PUBLISH", null, own, published)));
      verify(agendaEventAttendeeService, never()).sendEventResponse(anyLong(), anyLong(), any(), eq(false));

      // Another deployment's meeting is an ordinary external event.
      String elsewhere = published.replace(own, "agenda-event-42@other.example");
      when(caldavSyncStorage.getObjectByUid(PAIR, "agenda-event-42@other.example")).thenReturn(null, null, mapping(HREF));
      lenient().when(caldavSyncStorage.getMirrorEventIdOnServer(SERVER, "agenda-event-42@other.example")).thenReturn(null);
      assertNotNull(service.land(invitation("PUBLISH", null, "agenda-event-42@other.example", elsewhere)));
      verify(writer).putObject(any(), anyString(), anyString());
    }
  }

  /**
   * A published event without organiser, added once, is held as it is on a
   * second add — there is no organiser to take a newer revision from, and
   * adding again is adding nothing; a newer revision of it is refused. Only an
   * invitation, a published event or a cancellation is landed at all.
   *
   * @throws Exception never
   */
  @Test
  public void aPublishedEventIsHeldAsItIsAndOtherMethodsAreRefused() throws Exception {
    String published = REQUEST.replace("METHOD:REQUEST", "METHOD:PUBLISH").replace("ORGANIZER;CN=Olivia:mailto:" + ORGANISER + "\r\n", "");
    when(caldavSyncStorage.getObjectByUid(PAIR, UID)).thenAnswer(call -> mapping(FILED_HREF));
    when(calDavClient.fetchObject(endpoint, FILED_HREF)).thenReturn(new CalendarObject(FILED_HREF, "\"e5\"", published.replace("METHOD:PUBLISH\r\n", "")));

    assertNotNull(service.land(invitation("PUBLISH", null, UID, published)));
    verify(writer, never()).updateObject(any(), anyString(), anyString(), anyString());
    verify(writer, never()).putObject(any(), anyString(), anyString());

    assertThrows(IllegalArgumentException.class,
                 () -> service.land(new MailInvitation(LOGIN, MAILBOX, "PUBLISH", UID, 3, null, published.replace("SEQUENCE:2", "SEQUENCE:3"))));
    verify(writer, never()).updateObject(any(), anyString(), anyString(), anyString());

    // Each under a UID the user holds nothing of, so that only the method can
    // refuse it: landed, it would be read and written like a new event.
    for (String method : List.of("REPLY", "COUNTER", "REFRESH", "ADD", "DECLINECOUNTER")) {
      String uid = method.toLowerCase() + "-" + UID;
      lenient().when(caldavSyncStorage.getObjectByUid(PAIR, uid)).thenReturn(null);
      lenient().when(caldavSyncStorage.getMirrorEventIdOnServer(SERVER, uid)).thenReturn(null);
      String message = REQUEST.replace("METHOD:REQUEST", "METHOD:" + method).replace("UID:" + UID, "UID:" + uid);
      assertThrows(IllegalArgumentException.class, () -> service.land(invitation(method, null, uid, message)), method);
    }
    // The one read the first add made; the refusals above read nothing.
    verify(caldavInboundService, times(1)).importInto(anyLong(), anyString(), any(), any(), any(), any());
    verify(writer, never()).putObject(any(), anyString(), anyString());
  }

  /**
   * The invitation of the test.
   *
   * @param response the answer given
   * @return the invitation
   */
  private static MailInvitation invitation(EventAttendeeResponse response) {
    return invitation(response, UID, REQUEST);
  }

  /**
   * An invitation.
   *
   * @param response the answer given
   * @param uid what the reader said the UID is
   * @param icalendar the object
   * @return the invitation
   */
  private static MailInvitation invitation(EventAttendeeResponse response, String uid, String icalendar) {
    return invitation("REQUEST", response, uid, icalendar);
  }

  /**
   * An invitation of a given kind.
   *
   * @param method the iTIP method
   * @param response the answer given, null to add or remove without one
   * @param uid what the reader said the UID is
   * @param icalendar the object
   * @return the invitation
   */
  private static MailInvitation invitation(String method, EventAttendeeResponse response, String uid, String icalendar) {
    return new MailInvitation(LOGIN, MAILBOX, method, uid, 2, response, icalendar);
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
    mapping.setCalendarSyncId(PAIR);
    mapping.setIcsUid(UID);
    mapping.setLocalEventId(EVENT);
    mapping.setRemoteHref(href);
    mapping.setEtag("\"e1\"");
    return mapping;
  }
}
