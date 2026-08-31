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
package org.exoplatform.caldav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.service.CaldavServerService;

/**
 * The protocol, exercised against canned answers. No network anywhere: the
 * transport is a mock, which is the only reason these can assert what the
 * client SENDS — the precondition headers, the registry-resolved target,
 * the refusal to address a foreign host before a socket is opened — as well
 * as what it makes of what comes back.
 */
@ExtendWith(MockitoExtension.class)
public class HttpCalDavClientTest {

  private static final String  SERVER_URL = "http://cal.example.com/dav/cal/{username}/";

  private static final String  BASE_PATH  = "/dav/cal/alice%40stalwart.local/";

  private static final String  USER       = "alice@stalwart.local";

  private static final String  PASSWORD   = "AlicePass123!";

  @Mock
  private CaldavServerService  caldavServerService;

  private HttpClient           transport;

  private HttpCalDavClient     client;

  private List<HttpRequest>    sent;

  @BeforeEach
  void setUp() {
    transport = mock(HttpClient.class);
    client = new HttpCalDavClient(transport, caldavServerService);
    sent = new ArrayList<>();
    lenient().when(caldavServerService.resolveServerUrl(1L)).thenReturn(SERVER_URL);
  }

  // ---- registry-only targeting -------------------------------------------

  @Test
  void endpointsAreMintedFromTheRegistryOnly() {
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertEquals(1L, endpoint.getServerId());
    assertEquals("http://cal.example.com" + BASE_PATH, endpoint.getBaseUri().toString(),
                 "the {username} placeholder is replaced percent-encoded for its path position");
    assertEquals(BASE_PATH, endpoint.getBasePath());
  }

  @Test
  void anEndpointWithoutAnyDeclaredServerIsRefused() {
    when(caldavServerService.resolveServerUrl(null)).thenReturn(null);

    assertThrows(CalDavException.class, () -> client.endpoint(null, USER));
    verifyNoInteractions(transport);
  }

  @Test
  void aUsernameThatCannotLiveInAUrlPathIsRefused() {
    assertThrows(CalDavException.class, () -> client.endpoint(1L, "alice/../../etc"));
    verifyNoInteractions(transport);
  }

  @Test
  void aForeignHostHrefIsRefusedBeforeAnySocketIsOpened() {
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertThrows(CalDavException.class,
                 () -> client.getCtag(endpoint, "https://attacker.example.org/dav/cal/", USER, PASSWORD),
                 "an absolute href naming another host must never be sent the user's credentials");
    verifyNoInteractions(transport);
  }

  @Test
  void aDotSegmentPathIsRefusedBeforeAnySocketIsOpened() {
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertThrows(CalDavException.class, () -> client.getCtag(endpoint, "/dav/../admin/", USER, PASSWORD));
    verifyNoInteractions(transport);
  }

  @Test
  void aSameHostAbsoluteHrefIsFoldedOntoTheEndpointAuthority() throws Exception {
    givenAnswers(collectionAnswer("/dav/cal/alice%40stalwart.local/default/", "Default", "\"c-1\""));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    client.getCtag(endpoint, "http://cal.example.com/dav/cal/alice%40stalwart.local/default/", USER, PASSWORD);

    assertEquals("http://cal.example.com/dav/cal/alice%40stalwart.local/default/", sent.get(0).uri().toString());
  }

  // ---- discovery ---------------------------------------------------------

  @Test
  void discoveryWalksFromThePrincipalToTheCalendarHome() throws Exception {
    givenAnswers("""
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response><D:href>%s</D:href><D:propstat><D:prop>
            <D:current-user-principal><D:href>/dav/pal/alice%%40stalwart.local/</D:href></D:current-user-principal>
          </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>""".formatted(BASE_PATH),
                 """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:A="urn:ietf:params:xml:ns:caldav">
          <D:response><D:href>/dav/pal/alice%40stalwart.local/</D:href><D:propstat><D:prop>
            <A:calendar-home-set><D:href>/dav/cal/alice%40stalwart.local/</D:href></A:calendar-home-set>
          </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>""");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    String home = client.discoverCalendarHome(endpoint, USER, PASSWORD);

    assertEquals("/dav/cal/alice%40stalwart.local/", home);
    assertEquals(2, sent.size(), "discovery is two PROPFINDs: principal, then home");
    assertEquals("PROPFIND", sent.get(0).method());
    assertEquals("http://cal.example.com" + BASE_PATH, sent.get(0).uri().toString(),
                 "discovery starts at the registered base path, never at /.well-known/");
    assertEquals("http://cal.example.com/dav/pal/alice%40stalwart.local/", sent.get(1).uri().toString());
  }

  /**
   * The account's default calendar is asked of its scheduling inbox, in the two
   * hops RFC 6638 defines.
   */
  @Test
  void theDefaultCalendarIsAskedOfTheSchedulingInboxRatherThanGuessed() throws Exception {
    givenAnswers(principalAnswer(), scheduleInboxAnswer("/dav/cal/alice%40stalwart.local/inbox/"),
                 defaultCalendarAnswer("/dav/cal/alice%40stalwart.local/personal/"));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    String defaultCalendar = client.discoverDefaultCalendar(endpoint, USER, PASSWORD);

    assertEquals("/dav/cal/alice%40stalwart.local/personal/", defaultCalendar);
    assertEquals(3, sent.size(), "three PROPFINDs: principal, then its scheduling inbox, then the inbox's default");
    assertEquals("http://cal.example.com" + BASE_PATH, sent.get(0).uri().toString());
    assertEquals("http://cal.example.com/dav/pal/alice%40stalwart.local/", sent.get(1).uri().toString());
    assertEquals("http://cal.example.com/dav/cal/alice%40stalwart.local/inbox/", sent.get(2).uri().toString(),
                 "the second hop addresses the inbox the principal named, not a path this client invented");
  }

  /**
   * A server implementing no scheduling answers "no default calendar" rather
   * than raising.
   */
  @Test
  void aServerImplementingNoSchedulingNamesNoDefaultCalendar() throws Exception {
    // Scheduling is an extension, and plenty of servers implement none of it.
    // That has to be an answer of "none" rather than an exception, because the
    // caller's whole job is to decide what to do without one - and it must not
    // become a guess here.
    givenAnswers(principalAnswer(), emptyMultistatus());
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertNull(client.discoverDefaultCalendar(endpoint, USER, PASSWORD));
    assertEquals(2, sent.size(), "asking the inbox that was never named would address a path this client made up");
  }

  /**
   * An inbox that names no default calendar leaves the answer empty.
   */
  @Test
  void aSchedulingInboxNamingNoDefaultCalendarIsNotFilledInFromTheListing() throws Exception {
    givenAnswers(principalAnswer(), scheduleInboxAnswer("/dav/cal/alice%40stalwart.local/inbox/"), emptyMultistatus());
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertNull(client.discoverDefaultCalendar(endpoint, USER, PASSWORD),
               "an inbox that names no default calendar means the account has none, not that one must be chosen for it");
  }

  @Test
  void everyRequestCarriesBasicCredentialsUnprompted() throws Exception {
    givenAnswers(collectionAnswer(BASE_PATH, "Home", null));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    client.getCtag(endpoint, null, USER, PASSWORD);

    String authorization = sent.get(0).headers().firstValue("Authorization").orElse(null);
    assertNotNull(authorization);
    assertTrue(authorization.startsWith("Basic "),
               "sent unprompted: BlueMind's refusals carry no WWW-Authenticate challenge to react to");
  }

  // ---- propstat discipline: the false-success class ----------------------

  @Test
  void aPropertyOutsideA2xxPropstatDoesNotExist() throws Exception {
    // The exact interleaving Stalwart answers live: calendar-color in a 404
    // propstat NEXT TO the 200 propstat of the same response. A naive read
    // of descendants would happily return properties the server refused.
    givenAnswers("""
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:A="urn:ietf:params:xml:ns:caldav" xmlns:C="http://calendarserver.org/ns/">
          <D:response>
            <D:href>/dav/cal/alice%40stalwart.local/default/</D:href>
            <D:propstat>
              <D:prop><D:displayname>Real name</D:displayname>
                <D:resourcetype><D:collection/><A:calendar/></D:resourcetype>
                <C:getctag>"granted"</C:getctag></D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
            <D:propstat>
              <D:prop><D:sync-token>refused-token</D:sync-token></D:prop>
              <D:status>HTTP/1.1 404 Not Found</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>""");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    CalendarCollection calendar = client.readCalendar(endpoint, "/dav/cal/alice%40stalwart.local/default/", USER, PASSWORD);

    assertNotNull(calendar);
    assertEquals("Real name", calendar.displayName());
    assertEquals("\"granted\"", calendar.ctag());
    assertNull(calendar.syncToken(), "a value inside a failing propstat must never be read as granted");
  }

  @Test
  void a207WhoseOnlyPropstatFailsGrantsNothing() throws Exception {
    givenAnswers("""
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/cal/alice%40stalwart.local/default/</D:href>
            <D:propstat>
              <D:prop><D:displayname>lying-value</D:displayname></D:prop>
              <D:status>HTTP/1.1 403 Forbidden</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>""");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertNull(client.getCtag(endpoint, "/dav/cal/alice%40stalwart.local/default/", USER, PASSWORD));
  }

  // ---- listings ----------------------------------------------------------

  @Test
  void listCalendarsKeepsOnlyCalendarTypedCollections() throws Exception {
    givenAnswers("""
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:A="urn:ietf:params:xml:ns:caldav" xmlns:AP="http://apple.com/ns/ical/">
          <D:response>
            <D:href>%s</D:href>
            <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
              <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response>
            <D:href>%sdefault/</D:href>
            <D:propstat><D:prop>
              <D:displayname>Default</D:displayname>
              <D:resourcetype><D:collection/><A:calendar/></D:resourcetype>
              <D:sync-token>urn:token:1</D:sync-token>
              <AP:calendar-color>#0088FF</AP:calendar-color>
              <D:current-user-privilege-set><D:privilege><D:write/></D:privilege></D:current-user-privilege-set>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
        </D:multistatus>""".formatted(BASE_PATH, BASE_PATH));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    List<CalendarCollection> calendars = client.listCalendars(endpoint, BASE_PATH, USER, PASSWORD);

    assertEquals(1, calendars.size(), "the home itself is a plain collection and is filtered by type, not by path");
    CalendarCollection calendar = calendars.get(0);
    assertEquals(BASE_PATH + "default/", calendar.href());
    assertEquals("Default", calendar.displayName());
    assertEquals("urn:token:1", calendar.syncToken());
    assertEquals("#0088FF", calendar.color());
    assertTrue(calendar.writable());
    assertEquals("1", sent.get(0).headers().firstValue("Depth").orElse(null));
  }

  @Test
  void listCalendarsAsksWhichComponentsEachCollectionHolds() throws Exception {
    // Nothing else on the wire separates a calendar from the task list a
    // CalDAV home publishes beside it, so the property has to be requested —
    // a server answers what it was asked for and nothing more.
    givenAnswers(emptyMultistatus());
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    client.listCalendars(endpoint, BASE_PATH, USER, PASSWORD);

    String body = bodyOf(sent.get(0));
    assertTrue(body.contains("supported-calendar-component-set"), body);
  }

  @Test
  void listCalendarsReadsEveryDeclaredComponentAndUpperCasesIt() throws Exception {
    // Servers disagree on the case they write, and a set compared against
    // "VEVENT" would read a server writing "vevent" as declaring no events at
    // all — turning that server's every calendar into a task list.
    givenAnswers(componentSetAnswer("""
        <A:supported-calendar-component-set>
          <A:comp name="vevent"/>
          <A:comp name=" VTodo "/>
        </A:supported-calendar-component-set>"""));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    CalendarCollection calendar = client.listCalendars(endpoint, BASE_PATH, USER, PASSWORD).get(0);

    assertEquals(java.util.Set.of("VEVENT", "VTODO"), calendar.components());
    assertTrue(calendar.holdsEvents());
  }

  @Test
  void aCollectionDeclaringOnlyTodosIsListedButHoldsNoEvents() throws Exception {
    // Still listed: the filtering is the sync's decision to make, and a client
    // that hid the collection here would leave the sync unable to say why.
    givenAnswers(componentSetAnswer("""
        <A:supported-calendar-component-set>
          <A:comp name="VTODO"/>
        </A:supported-calendar-component-set>"""));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    CalendarCollection calendar = client.listCalendars(endpoint, BASE_PATH, USER, PASSWORD).get(0);

    assertEquals(java.util.Set.of("VTODO"), calendar.components());
    assertFalse(calendar.holdsEvents());
  }

  @Test
  void aCollectionThatDeclaresNoComponentSetComesBackWithAnEmptyOne() throws Exception {
    // RFC 4791 makes the property optional, and the empty set is how the
    // absence travels — read further on as "the server did not say" rather
    // than as "the server said nothing is supported".
    givenAnswers(componentSetAnswer(""));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    CalendarCollection calendar = client.listCalendars(endpoint, BASE_PATH, USER, PASSWORD).get(0);

    assertTrue(calendar.components().isEmpty());
    assertTrue(calendar.holdsEvents());
  }

  @Test
  void aCompWithoutAUsableNameIsIgnoredRatherThanRecordedBlank() throws Exception {
    // A blank entry would make an otherwise-undeclared set non-empty, which
    // reads as an explicit refusal of events — the collection would vanish
    // from the user's agenda because of one malformed element.
    givenAnswers(componentSetAnswer("""
        <A:supported-calendar-component-set>
          <A:comp/>
          <A:comp name=""/>
          <A:comp name="   "/>
        </A:supported-calendar-component-set>"""));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    CalendarCollection calendar = client.listCalendars(endpoint, BASE_PATH, USER, PASSWORD).get(0);

    assertTrue(calendar.components().isEmpty());
    assertTrue(calendar.holdsEvents());
  }

  @Test
  void listResourceEtagsSkipsTheCollectionAndAnythingWithoutAVersion() throws Exception {
    givenAnswers("""
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response><D:href>%sdefault/</D:href>
            <D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
          <D:response><D:href>%sdefault/a.ics</D:href>
            <D:propstat><D:prop><D:getetag>"v1"</D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
          <D:response><D:href>%sdefault/b.ics</D:href>
            <D:propstat><D:prop><D:getetag>"v2"</D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>""".formatted(BASE_PATH, BASE_PATH, BASE_PATH));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    Map<String, String> etags = client.listResourceEtags(endpoint, BASE_PATH + "default/", USER, PASSWORD);

    assertEquals(Map.of(BASE_PATH + "default/a.ics", "\"v1\"", BASE_PATH + "default/b.ics", "\"v2\""), etags);
  }

  @Test
  void aForeignHostHrefInAListingSkipsTheEntryNotTheListing() throws Exception {
    givenAnswers("""
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response><D:href>https://attacker.example.org/dav/x.ics</D:href>
            <D:propstat><D:prop><D:getetag>"evil"</D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
          <D:response><D:href>%sdefault/a.ics</D:href>
            <D:propstat><D:prop><D:getetag>"v1"</D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>""".formatted(BASE_PATH));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    Map<String, String> etags = client.listResourceEtags(endpoint, BASE_PATH + "default/", USER, PASSWORD);

    assertEquals(Map.of(BASE_PATH + "default/a.ics", "\"v1\""), etags,
                 "the foreign entry is ignored — it could never be fetched anyway — and the sync keeps going");
  }

  // ---- REPORTs -----------------------------------------------------------

  @Test
  void calendarQuerySendsTheWindowInUtcBasicFormat() throws Exception {
    givenAnswers(emptyMultistatus());
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    client.calendarQuery(endpoint,
                         BASE_PATH + "default/",
                         Instant.parse("2026-01-01T00:00:00Z"),
                         Instant.parse("2026-02-01T00:00:00Z"),
                         USER,
                         PASSWORD);

    assertEquals("REPORT", sent.get(0).method());
    String body = bodyOf(sent.get(0));
    assertTrue(body.contains("<c:time-range start=\"20260101T000000Z\" end=\"20260201T000000Z\"/>"), body);
  }

  @Test
  void multigetSendsEveryHrefAndReadsDataAndVersionTogether() throws Exception {
    givenAnswers("""
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:A="urn:ietf:params:xml:ns:caldav">
          <D:response><D:href>%sdefault/a.ics</D:href>
            <D:propstat><D:prop><D:getetag>"v1"</D:getetag>
              <A:calendar-data>BEGIN:VCALENDAR
END:VCALENDAR</A:calendar-data></D:prop>
              <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>""".formatted(BASE_PATH));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    List<CalendarObject> objects = client.multiget(endpoint,
                                                   BASE_PATH + "default/",
                                                   List.of(BASE_PATH + "default/a.ics", BASE_PATH + "default/gone.ics"),
                                                   USER,
                                                   PASSWORD);

    assertEquals(1, objects.size(), "an href the server did not answer is simply absent");
    assertEquals("\"v1\"", objects.get(0).etag());
    assertTrue(objects.get(0).calendarData().contains("BEGIN:VCALENDAR"));
    String body = bodyOf(sent.get(0));
    assertTrue(body.contains("<d:href>" + BASE_PATH + "default/a.ics</d:href>"), body);
    assertTrue(body.contains("<d:href>" + BASE_PATH + "default/gone.ics</d:href>"), body);
  }

  @Test
  void anEmptyMultigetCostsNoRequest() {
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertEquals(List.of(), client.multiget(endpoint, BASE_PATH + "default/", List.of(), USER, PASSWORD));
    verifyNoInteractions(transport);
  }

  @Test
  void syncCollectionSeparatesChangesFromDeletionsAndCarriesTheNewToken() throws Exception {
    givenAnswers("""
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response><D:href>%sdefault/changed.ics</D:href>
            <D:propstat><D:prop><D:getetag>"v2"</D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
          <D:response><D:href>%sdefault/gone.ics</D:href>
            <D:status>HTTP/1.1 404 Not Found</D:status></D:response>
          <D:sync-token>urn:token:2</D:sync-token>
        </D:multistatus>""".formatted(BASE_PATH, BASE_PATH));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    SyncCollectionResult result = client.syncCollection(endpoint, BASE_PATH + "default/", "urn:token:1", USER, PASSWORD);

    assertTrue(result.tokenValid());
    assertEquals("urn:token:2", result.syncToken());
    assertEquals(1, result.changed().size());
    assertEquals(BASE_PATH + "default/changed.ics", result.changed().get(0).href());
    assertEquals(List.of(BASE_PATH + "default/gone.ics"), result.deleted());
    assertTrue(bodyOf(sent.get(0)).contains("<d:sync-token>urn:token:1</d:sync-token>"));
  }

  @Test
  void aRejectedSyncTokenIsARoutineDowngradeNotAFailure() throws Exception {
    givenStatusAnswers(507, "");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    SyncCollectionResult result = client.syncCollection(endpoint, BASE_PATH + "default/", "stale", USER, PASSWORD);

    assertFalse(result.tokenValid(), "the caller falls through to the listing tier for this run");
  }

  @Test
  void aValidSyncTokenPreconditionOn403IsInvalidTokenNotACredentialRefusal() throws Exception {
    givenStatusAnswers(403,
                       "<?xml version=\"1.0\"?><D:error xmlns:D=\"DAV:\"><D:valid-sync-token/></D:error>");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    SyncCollectionResult result = client.syncCollection(endpoint, BASE_PATH + "default/", "stale", USER, PASSWORD);

    assertFalse(result.tokenValid(),
                "a 403 carrying valid-sync-token must not pause the account as a credential failure");
  }

  // ---- capability probe --------------------------------------------------

  @Test
  void theProbeReadsTiersFromTheSupportedReportSet() throws Exception {
    givenAnswersWithHeaders(Map.of("DAV", "1, 2, 3, calendar-access"), """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:A="urn:ietf:params:xml:ns:caldav" xmlns:C="http://calendarserver.org/ns/">
          <D:response><D:href>%sdefault/</D:href>
            <D:propstat><D:prop>
              <C:getctag>"1909"</C:getctag>
              <D:sync-token>urn:token:9</D:sync-token>
              <D:supported-report-set>
                <D:supported-report><D:report><D:sync-collection/></D:report></D:supported-report>
                <D:supported-report><D:report><A:calendar-query/></D:report></D:supported-report>
                <D:supported-report><D:report><A:calendar-multiget/></D:report></D:supported-report>
              </D:supported-report-set>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>""".formatted(BASE_PATH));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    ServerCapabilities capabilities = client.probeCapabilities(endpoint, BASE_PATH + "default/", USER, PASSWORD);

    assertTrue(capabilities.syncCollection());
    assertTrue(capabilities.calendarQuery());
    assertTrue(capabilities.calendarMultiget());
    assertTrue(capabilities.ctag());
    assertEquals("1, 2, 3, calendar-access", capabilities.davHeader());
    assertEquals(ServerCapabilities.SyncTier.SYNC_COLLECTION, capabilities.tier());
  }

  @Test
  void aServerWithoutSyncButWithCtagLandsOnTheCtagTier() throws Exception {
    givenAnswers("""
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:C="http://calendarserver.org/ns/">
          <D:response><D:href>%sdefault/</D:href>
            <D:propstat><D:prop><C:getctag>"1"</C:getctag><D:supported-report-set/></D:prop>
              <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>""".formatted(BASE_PATH));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertEquals(ServerCapabilities.SyncTier.CTAG_ETAG,
                 client.probeCapabilities(endpoint, BASE_PATH + "default/", USER, PASSWORD).tier());
  }

  @Test
  void aServerAnsweringNothingUsefulLandsOnTheEtagListingFloor() throws Exception {
    givenAnswers(emptyMultistatus());
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertEquals(ServerCapabilities.SyncTier.ETAG_LISTING,
                 client.probeCapabilities(endpoint, BASE_PATH + "default/", USER, PASSWORD).tier());
  }

  // ---- writes ------------------------------------------------------------

  @Test
  void aCreateAlwaysInsistsOnCreating() throws Exception {
    givenStatusAnswers(201, "");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    PutResult result = client.putObject(endpoint, BASE_PATH + "default/a.ics", "BEGIN:VCALENDAR", USER, PASSWORD);

    assertEquals(201, result.status());
    assertEquals("*", sent.get(0).headers().firstValue("If-None-Match").orElse(null),
                 "no unconditional create exists: the server itself guarantees the PUT can only CREATE");
    assertEquals("text/calendar; charset=utf-8", sent.get(0).headers().firstValue("Content-Type").orElse(null));
  }

  @Test
  void aRefusedPreconditionIsAnAnswerNotAFault() throws Exception {
    givenStatusAnswers(412, "");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    PutResult result = client.putObject(endpoint, BASE_PATH + "default/a.ics", "BEGIN:VCALENDAR", USER, PASSWORD);

    assertTrue(result.preconditionFailed(), "under If-None-Match:* a 412 means 'already exists' — a fact to consume");
  }

  @Test
  void anUpdateRefusesToGoOutUnconditional() {
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertThrows(IllegalArgumentException.class,
                 () -> client.updateObject(endpoint, BASE_PATH + "default/a.ics", "BEGIN:VCALENDAR", " ", USER, PASSWORD),
                 "an unconditional PUT overwrites silently — the class of loss the doctrine forbids");
    verifyNoInteractions(transport);
  }

  @Test
  void anUpdateSendsItsEtagVerbatim() throws Exception {
    givenStatusAnswers(204, "");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    client.updateObject(endpoint, BASE_PATH + "default/a.ics", "BEGIN:VCALENDAR", "\"v1\"", USER, PASSWORD);

    assertEquals("\"v1\"", sent.get(0).headers().firstValue("If-Match").orElse(null),
                 "an etag only means what the server said if it is byte for byte what the server said");
  }

  @Test
  void aDeleteOfAnAlreadyGoneObjectIsAFactNotAFault() throws Exception {
    givenStatusAnswers(404, "");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertEquals(404, client.deleteObject(endpoint, BASE_PATH + "default/a.ics", "\"v1\"", USER, PASSWORD),
                 "absent is absent — what makes a retried delete idempotent");
    assertEquals("\"v1\"", sent.get(0).headers().firstValue("If-Match").orElse(null));
  }

  @Test
  void aDeleteRefusedByTheServerThrows() throws Exception {
    givenStatusAnswers(500, "");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertThrows(CalDavException.class,
                 () -> client.deleteObject(endpoint, BASE_PATH + "default/a.ics", null, USER, PASSWORD));
  }

  @Test
  void aCollectionDeletionAddressesThePairOwnCollection() throws Exception {
    givenStatusAnswers(204, "");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertEquals(204, client.deleteCollection(endpoint, exoPair("cal-1", BASE_PATH + "exo-cal-cal-1"), USER, PASSWORD));

    // The trailing slash matters: a collection is a collection, and some
    // servers answer a 301 to the slashed form rather than deleting.
    assertTrue(sent.get(0).uri().toString().endsWith("/exo-cal-cal-1/"),
               () -> "expected the collection URL, got " + sent.get(0).uri());
    assertEquals("DELETE", sent.get(0).method());
  }

  @Test
  void aCollectionAlreadyGoneIsAFactNotAFault() throws Exception {
    givenStatusAnswers(410, "");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    // Absent is absent — which is what makes a repeated deletion idempotent
    // rather than an error somebody has to interpret.
    assertEquals(410, client.deleteCollection(endpoint, exoPair("cal-1", BASE_PATH + "exo-cal-cal-1"), USER, PASSWORD));
  }

  @Test
  void aCollectionDeletionRefusedByTheServerThrows() throws Exception {
    givenStatusAnswers(500, "");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertThrows(CalDavException.class,
                 () -> client.deleteCollection(endpoint, exoPair("cal-1", BASE_PATH + "exo-cal-cal-1"), USER, PASSWORD));
  }

  @Test
  void rejectedCredentialsOnACollectionDeletionKeepTheirOwnType() throws Exception {
    // A 401 here must not be read as "the server refuses this deletion": the
    // caller's answer to a stale password is not the answer to a refusal.
    givenStatusAnswers(401, "");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertThrows(CalDavAuthenticationException.class,
                 () -> client.deleteCollection(endpoint, exoPair("cal-1", BASE_PATH + "exo-cal-cal-1"), USER, PASSWORD));
  }

  @Test
  void aCollectionEXoDidNotCreateIsNeverAddressedAtAll() throws Exception {
    CalDavEndpoint endpoint = client.endpoint(1L, USER);
    CalendarSync foreign = exoPair("cal-1", BASE_PATH + "exo-cal-cal-1");
    foreign.setOrigin(SyncOrigin.REMOTE);

    assertThrows(IllegalArgumentException.class, () -> client.deleteCollection(endpoint, foreign, USER, PASSWORD));

    // Not merely refused: no socket was opened, because the guard runs before
    // the request exists.
    assertTrue(sent.isEmpty(), "the guard must run before anything is sent");
  }

  /**
   * A binding authorising the deletion of one collection eXo created.
   *
   * @param anchor the calendar's sync uid
   * @param href where its collection lives
   * @return the pair
   */
  private CalendarSync exoPair(String anchor, String href) {
    CalendarSync pair = new CalendarSync();
    pair.setOrigin(SyncOrigin.EXO);
    pair.setLocalCalendarSyncUid(anchor);
    pair.setRemoteHref(href);
    return pair;
  }

  // ---- transport disciplines ---------------------------------------------

  @Test
  void aRedirectIsNeverFollowed() throws Exception {
    givenStatusAnswersWithHeaders(301, Map.of("Location", "https://elsewhere.example.org/dav/"), "");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    CalDavException exception = assertThrows(CalDavException.class,
                                             () -> client.getCtag(endpoint, null, USER, PASSWORD));

    assertTrue(exception.getMessage().contains("redirect"),
               "a registered server must not be able to bounce credentialed requests to another host");
    assertEquals(1, sent.size());
  }

  @Test
  void aBodyOverTheCapIsRefused() throws Exception {
    System.setProperty("exo.agenda.caldav.client.maxBodyBytes", "16");
    try {
      givenAnswers(emptyMultistatus());
      CalDavEndpoint endpoint = client.endpoint(1L, USER);

      assertThrows(CalDavException.class, () -> client.getCtag(endpoint, null, USER, PASSWORD));
    } finally {
      System.clearProperty("exo.agenda.caldav.client.maxBodyBytes");
    }
  }

  @Test
  void noExceptionEverCarriesAuthMaterial() throws Exception {
    givenStatusAnswers(418, "");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    CalDavException exception = assertThrows(CalDavException.class,
                                             () -> client.getCtag(endpoint, null, USER, PASSWORD));

    assertFalse(exception.getMessage().contains(PASSWORD));
    assertFalse(exception.getMessage().contains("Basic"));
    assertFalse(exception.getMessage().contains(java.util.Base64.getEncoder()
                                                                .encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  void anUnreachableServerNamesTheServerNotTheCredentials() throws Exception {
    when(transport.send(any(HttpRequest.class), any())).thenThrow(new IOException("connection refused"));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    CalDavException exception = assertThrows(CalDavException.class,
                                             () -> client.getCtag(endpoint, null, USER, PASSWORD));

    assertTrue(exception.getMessage().contains("could not be reached"));
  }

  @Test
  void aTransportFailureIsToldApartFromAnyOtherFailure() throws Exception {
    // EXO-89806. A caller running several independent steps has to be able to
    // tell "this call did not work" from "the server is not there" — the
    // second is settled after one attempt, and the steps after it would only
    // rediscover it, at the cost of a burst of authenticated requests that
    // earns a persistent ban from a server counting them.
    when(transport.send(any(HttpRequest.class), any())).thenThrow(new IOException("connection refused"));
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertThrows(CalDavUnreachableException.class, () -> client.getCtag(endpoint, null, USER, PASSWORD));
  }

  @Test
  void aGatewaySayingItCannotReachTheServerIsAnUnreachableServer() throws Exception {
    // What the rig actually measured: a proxy in front of a banned Stalwart
    // answered 502 to every request, and each step of the connection read it
    // as its own private failure.
    givenStatusAnswers(502, "");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    assertThrows(CalDavUnreachableException.class, () -> client.getCtag(endpoint, null, USER, PASSWORD));
  }

  @Test
  void aServerErrorFromTheServerItselfIsNotAnAbsentServer() throws Exception {
    // 500 is deliberately outside the gateway set: BlueMind answers it for an
    // absent object, a quirk this add-on accommodates, and reading it as "the
    // server is gone" would abandon passes against a server that is there.
    givenStatusAnswers(500, "");
    CalDavEndpoint endpoint = client.endpoint(1L, USER);

    CalDavException exception = assertThrows(CalDavException.class,
                                             () -> client.getCtag(endpoint, null, USER, PASSWORD));

    assertFalse(exception instanceof CalDavUnreachableException,
                "a 500 from the server itself is a refusal of this call, not an absent server");
  }

  // ---- helpers -----------------------------------------------------------

  /**
   * Queues 207 multistatus answers on the mocked transport, recording every
   * request the client sends.
   *
   * @param bodies the multistatus bodies to answer, in order
   * @throws Exception never — the mock declares it
   */
  private void givenAnswers(String... bodies) throws Exception {
    List<String> queue = new ArrayList<>(List.of(bodies));
    when(transport.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
      sent.add(invocation.getArgument(0));
      String body = queue.isEmpty() ? emptyMultistatus() : queue.remove(0);
      return response(207, Map.of(), body);
    });
  }

  /**
   * Queues one 207 answer carrying response headers.
   *
   * @param headers the response headers to answer
   * @param body the multistatus body
   * @throws Exception never — the mock declares it
   */
  private void givenAnswersWithHeaders(Map<String, String> headers, String body) throws Exception {
    when(transport.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
      sent.add(invocation.getArgument(0));
      return response(207, headers, body);
    });
  }

  /**
   * Queues answers of one fixed status.
   *
   * @param status the HTTP status to answer
   * @param body the body to answer
   * @throws Exception never — the mock declares it
   */
  private void givenStatusAnswers(int status, String body) throws Exception {
    givenStatusAnswersWithHeaders(status, Map.of(), body);
  }

  /**
   * Queues answers of one fixed status with response headers.
   *
   * @param status the HTTP status to answer
   * @param headers the response headers to answer
   * @param body the body to answer
   * @throws Exception never — the mock declares it
   */
  private void givenStatusAnswersWithHeaders(int status, Map<String, String> headers, String body) throws Exception {
    when(transport.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
      sent.add(invocation.getArgument(0));
      return response(status, headers, body);
    });
  }

  /**
   * A canned HTTP response over an InputStream body, the shape the client's
   * bounded read consumes.
   *
   * @param status the HTTP status
   * @param headers the response headers
   * @param body the body text
   * @return the mocked response
   */
  @SuppressWarnings("unchecked")
  private HttpResponse<InputStream> response(int status, Map<String, String> headers, String body) {
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    lenient().when(response.statusCode()).thenReturn(status);
    lenient().when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    Map<String, List<String>> headerMap = new java.util.HashMap<>();
    headers.forEach((name, value) -> headerMap.put(name, List.of(value)));
    lenient().when(response.headers()).thenReturn(HttpHeaders.of(headerMap, (a, b) -> true));
    return response;
  }

  /**
   * The base path naming the authenticated principal — the first hop of every
   * discovery walk.
   *
   * @return the multistatus body
   */
  private String principalAnswer() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response><D:href>%s</D:href><D:propstat><D:prop>
            <D:current-user-principal><D:href>/dav/pal/alice%%40stalwart.local/</D:href></D:current-user-principal>
          </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>""".formatted(BASE_PATH);
  }

  /**
   * A principal naming its scheduling inbox.
   *
   * @param inboxHref where the principal says its inbox lives
   * @return the multistatus body
   */
  private String scheduleInboxAnswer(String inboxHref) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:A="urn:ietf:params:xml:ns:caldav">
          <D:response><D:href>/dav/pal/alice%%40stalwart.local/</D:href><D:propstat><D:prop>
            <A:schedule-inbox-URL><D:href>%s</D:href></A:schedule-inbox-URL>
          </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>""".formatted(inboxHref);
  }

  /**
   * A scheduling inbox naming the account's default calendar.
   *
   * @param calendarHref the collection the inbox calls the default one
   * @return the multistatus body
   */
  private String defaultCalendarAnswer(String calendarHref) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:A="urn:ietf:params:xml:ns:caldav">
          <D:response><D:href>/dav/cal/alice%%40stalwart.local/inbox/</D:href><D:propstat><D:prop>
            <A:schedule-default-calendar-URL><D:href>%s</D:href></A:schedule-default-calendar-URL>
          </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>""".formatted(calendarHref);
  }

  /**
   * A Depth:0 listing of one calendar collection.
   *
   * @param href the collection href
   * @param displayName its display name
   * @param ctag its ctag, or null to answer without one
   * @return the multistatus body
   */
  private String collectionAnswer(String href, String displayName, String ctag) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:A="urn:ietf:params:xml:ns:caldav" xmlns:C="http://calendarserver.org/ns/">
          <D:response><D:href>%s</D:href><D:propstat><D:prop>
            <D:displayname>%s</D:displayname>
            <D:resourcetype><D:collection/><A:calendar/></D:resourcetype>
            %s
          </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>""".formatted(href, displayName, ctag == null ? "" : "<C:getctag>" + ctag + "</C:getctag>");
  }

  /**
   * A Depth:1 listing of one calendar collection carrying the given component
   * set verbatim, so a test can hand the parser exactly the element a server
   * would have written — including one it wrote badly.
   *
   * @param componentSet the supported-calendar-component-set element, or an
   *          empty string to answer without the property at all
   * @return the multistatus body
   */
  private String componentSetAnswer(String componentSet) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:A="urn:ietf:params:xml:ns:caldav">
          <D:response><D:href>%sdefault/</D:href><D:propstat><D:prop>
            <D:displayname>Default</D:displayname>
            <D:resourcetype><D:collection/><A:calendar/></D:resourcetype>
            %s
          </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>""".formatted(BASE_PATH, componentSet);
  }

  /**
   * An empty multistatus.
   *
   * @return the body
   */
  private String emptyMultistatus() {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><D:multistatus xmlns:D=\"DAV:\"/>";
  }

  /**
   * The body of a sent request, read back from its publisher.
   *
   * @param request the recorded request
   * @return the body text
   */
  private String bodyOf(HttpRequest request) {
    java.util.concurrent.Flow.Publisher<java.nio.ByteBuffer> publisher = request.bodyPublisher().orElseThrow();
    StringBuilder body = new StringBuilder();
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    publisher.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
      /**
       * Asks for the whole body at once.
       *
       * @param subscription the flow subscription
       */
      @Override
      public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      /**
       * Appends one chunk.
       *
       * @param item the body chunk
       */
      @Override
      public void onNext(java.nio.ByteBuffer item) {
        body.append(StandardCharsets.UTF_8.decode(item));
      }

      /**
       * Ends the read on failure.
       *
       * @param throwable the failure
       */
      @Override
      public void onError(Throwable throwable) {
        done.countDown();
      }

      /** Ends the read. */
      @Override
      public void onComplete() {
        done.countDown();
      }
    });
    try {
      done.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return body.toString();
  }
}
