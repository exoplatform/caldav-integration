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
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.service.CaldavServerService;

/**
 * The client against the recorded behaviour of the real servers — the
 * transcripts under {@code caldav/transcripts}, standing in for the PR0
 * golden-file corpus that is not built yet. Provenance, per fixture:
 * <ul>
 * <li>{@code stalwart-home-depth1-full-props.xml} — captured live from the
 * containerised Stalwart rig (2026-08-20), and it happens to prove the
 * propstat discipline on its own: the home's absent calendar-color comes
 * back in a 404 propstat interleaved with the 200 one.</li>
 * <li>{@code bluemind-403-refused-auth.http} — captured live from the
 * BlueMind demo (2026-08-20), unauthenticated and with wrong credentials
 * alike: <b>403</b>, text/html, no WWW-Authenticate.</li>
 * <li>{@code bluemind-mkcalendar-207-failing-propstat.xml} and
 * {@code bluemind-propfind-dav-rooted.xml} — RECONSTRUCTED from the browser
 * connector's documented transcripts (caldavConnector.js:2272-2306 and the
 * relay work); the raw authenticated captures need credentials this session
 * did not have and are what PR0 still owes.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
public class HttpCalDavClientServerQuirksTest {

  private static final String SERVER_URL = "https://webmail.demo3.livecollab.fr/dav/";

  private static final String USER       = "someone@demo3.livecollab.fr";

  private static final String PASSWORD   = "not-the-real-one";

  @Mock
  private CaldavServerService caldavServerService;

  private HttpClient          transport;

  private HttpCalDavClient    client;

  private CalDavEndpoint      endpoint;

  @BeforeEach
  void setUp() {
    transport = mock(HttpClient.class);
    client = new HttpCalDavClient(transport, caldavServerService);
    lenient().when(caldavServerService.resolveServerUrl(1L)).thenReturn(SERVER_URL);
    endpoint = client.endpoint(1L, USER);
  }

  @Test
  void blueMindRefusesBasicAuthWith403NotA401() throws Exception {
    // Verified live 2026-08-20: /dav/ answers 403 with an HTML body and no
    // WWW-Authenticate, to wrong credentials and to none at all.
    givenAnswer(403, Map.of("Content-Type", "text/html"), fixture("bluemind-403-refused-auth.http"));

    assertThrows(CalDavAuthenticationException.class,
                 () -> client.discoverCalendarHome(endpoint, USER, PASSWORD),
                 "a 403 on a read verb IS the BlueMind credential refusal and must classify as one");
  }

  @Test
  void a401ClassifiesAsACredentialRefusalEverywhere() throws Exception {
    givenAnswer(401, Map.of(), "");

    assertThrows(CalDavAuthenticationException.class,
                 () -> client.putObject(endpoint, "/dav/calendars/vevent/x/a.ics", "BEGIN:VCALENDAR", USER, PASSWORD));
  }

  @Test
  void a403OnAWriteStaysARefusalNotACredentialFailure() throws Exception {
    givenAnswer(403, Map.of(), "");

    assertThrows(CalDavException.class,
                 () -> client.putObject(endpoint, "/dav/calendars/vevent/x/a.ics", "BEGIN:VCALENDAR", USER, PASSWORD));
    try {
      givenAnswer(403, Map.of(), "");
      client.putObject(endpoint, "/dav/calendars/vevent/x/a.ics", "BEGIN:VCALENDAR", USER, PASSWORD);
    } catch (CalDavException e) {
      assertFalse(e instanceof CalDavAuthenticationException,
                  "classifying a write refusal as bad credentials would pause accounts whose password is fine");
    }
  }

  @Test
  void blueMindAnswers500ForAnObjectThatIsSimplyNotThere() throws Exception {
    // The documented quirk (caldavConnector.js:1196-1213): 500, not 404, for
    // a GET of a missing .ics. Treating it as fatal made every first push
    // fail; treating it as absent is safe because creates keep
    // If-None-Match:* — the worst case is a refused write.
    givenAnswer(500, Map.of("Content-Type", "text/html"), "internal error");

    assertNull(client.fetchObject(endpoint, "/dav/calendars/vevent/x/missing.ics", USER, PASSWORD),
               "absent, reported as a fact — the conditional create keeps the answer safe");
  }

  @Test
  void blueMindRefusingMkCalendarIsAnAnswerNotAnException() throws Exception {
    givenAnswer(405, Map.of(), "");

    MkCalendarResult result = client.mkCalendar(endpoint, "/dav/calendars/vevent/x/exo-cal-1/", "eXo", null, USER, PASSWORD);

    assertTrue(result.refused(), "the caller maps this to its inbound-only degradation, it is not an error");
    assertFalse(result.provenCreated());
  }

  @Test
  void aMkCalendar207WithFailingPropstatsIsNeverASuccess() throws Exception {
    givenAnswer(207, Map.of("Content-Type", "application/xml"), fixture("bluemind-mkcalendar-207-failing-propstat.xml"));

    MkCalendarResult result = client.mkCalendar(endpoint, "/dav/calendars/vevent/x/exo-cal-1/", "eXo", "#FF0000",
                                                USER, PASSWORD);

    assertFalse(result.provenCreated(),
                "MKCALENDAR is atomic: a 207 reports a rejected property and a collection that was NOT created");
    assertEquals(List.of(403, 424), result.failedPropstatStatuses(),
                 "the failing statuses are surfaced so the caller can name the real reason (the non-424 one)");
  }

  @Test
  void blueMindsDavRootedHrefsAreKeptServerAbsolute() throws Exception {
    // BlueMind advertises hrefs rooted at /dav/ whatever path the registered
    // URL carries: the client must keep them addressable as answered, and
    // its propstat reading must survive the 404-propstat interleaving.
    givenAnswer(207, Map.of("Content-Type", "application/xml"), fixture("bluemind-propfind-dav-rooted.xml"));

    List<CalendarCollection> calendars = client.listCalendars(endpoint, "/dav/", USER, PASSWORD);

    assertEquals(1, calendars.size());
    CalendarCollection calendar = calendars.get(0);
    assertEquals("/dav/calendars/vevent/6C452330-6F16-4B76-A6C2-6B24E1E0C4F1/", calendar.href());
    assertEquals("Mon agenda", calendar.displayName());
    assertEquals("\"bm-42\"", calendar.ctag());
    assertNull(calendar.syncToken(), "a property in the 404 propstat does not exist");
  }

  @Test
  void theLiveStalwartListingParsesIntoCalendarsWithTheirFullPropertySet() throws Exception {
    givenAnswer(207, Map.of("Content-Type", "application/xml"), fixture("stalwart-home-depth1-full-props.xml"));

    List<CalendarCollection> calendars = client.listCalendars(endpoint, "/dav/cal/alice%40stalwart.local/", USER, PASSWORD);

    assertTrue(calendars.size() >= 2, "the rig holds at least the default calendar and the eXo mirror");
    assertTrue(calendars.stream().noneMatch(calendar -> "/dav/cal/alice%40stalwart.local/".equals(calendar.href())),
               "the home itself is not a calendar and is filtered by resource type");
    CalendarCollection defaultCalendar = calendars.stream()
                                                  .filter(calendar -> calendar.href().endsWith("/default/"))
                                                  .findFirst()
                                                  .orElse(null);
    assertNotNull(defaultCalendar);
    assertEquals("#0088FF", defaultCalendar.color());
    assertTrue(defaultCalendar.writable());
    assertNotNull(defaultCalendar.ctag());
    assertNotNull(defaultCalendar.syncToken());
  }

  @Test
  void theLiveStalwartAnswerProvesThePropstatDisciplineOnItsOwn() throws Exception {
    // The home response interleaves calendar-color in a 404 propstat with
    // the 200 propstat of everything else: reading it naively would give
    // the home an empty-string colour and, worse, teach the parser that
    // properties live outside their status.
    givenAnswer(207, Map.of("Content-Type", "application/xml"), fixture("stalwart-home-depth1-full-props.xml"));

    CalendarCollection home = client.readCalendar(endpoint, "/dav/cal/alice%40stalwart.local/", USER, PASSWORD);

    assertNull(home, "the home is a plain collection, not a calendar — and its 404-propstat colour was never granted");
  }

  @Test
  void theLiveStalwartAnswerProbesToTheSyncCollectionTier() throws Exception {
    givenAnswer(207, Map.of("Content-Type", "application/xml", "DAV", "1, 2, 3, access-control, calendar-access"),
                fixture("stalwart-home-depth1-full-props.xml"));

    ServerCapabilities capabilities = client.probeCapabilities(endpoint, "/dav/cal/alice%40stalwart.local/", USER,
                                                               PASSWORD);

    assertEquals(ServerCapabilities.SyncTier.SYNC_COLLECTION, capabilities.tier());
    assertTrue(capabilities.calendarMultiget());
    assertTrue(capabilities.calendarQuery());
  }

  /**
   * Reads a transcript fixture. For the {@code .http} capture the headers
   * are part of the record but only the body travels through the client, so
   * everything up to the first blank line is dropped.
   *
   * @param name the fixture file name
   * @return the fixture's body text
   * @throws IOException when the fixture cannot be read
   */
  private String fixture(String name) throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream("caldav/transcripts/" + name)) {
      String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      if (name.endsWith(".http")) {
        int split = content.indexOf("\r\n\r\n");
        if (split < 0) {
          split = content.indexOf("\n\n");
          return split < 0 ? content : content.substring(split + 2);
        }
        return content.substring(split + 4);
      }
      return content;
    }
  }

  /**
   * Queues one canned answer on the mocked transport.
   *
   * @param status the HTTP status to answer
   * @param headers the response headers to answer
   * @param body the body to answer
   * @throws Exception never — the mock declares it
   */
  @SuppressWarnings("unchecked")
  private void givenAnswer(int status, Map<String, String> headers, String body) throws Exception {
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    lenient().when(response.statusCode()).thenReturn(status);
    lenient().when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    Map<String, List<String>> headerMap = new java.util.HashMap<>();
    headers.forEach((headerName, value) -> headerMap.put(headerName, List.of(value)));
    lenient().when(response.headers()).thenReturn(HttpHeaders.of(headerMap, (a, b) -> true));
    when(transport.send(any(HttpRequest.class), any())).thenAnswer(invocation -> response);
  }
}
