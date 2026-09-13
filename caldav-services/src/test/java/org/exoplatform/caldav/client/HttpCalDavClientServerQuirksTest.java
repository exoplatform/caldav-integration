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
import static org.mockito.Mockito.doReturn;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.service.CaldavServerService;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.provider.CaldavCredentialsResolver;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.HttpConnectorCredentials;

/**
 * The client against the recorded behaviour of the real servers — the
 * transcripts under {@code caldav/transcripts}, standing in for the PR0
 * golden-file corpus that is not built yet. Provenance, per fixture:
 * <ul>
 * <li>{@code stalwart-home-depth1-full-props.xml} — captured live from the
 * containerised Stalwart rig (2026-08-20), and it happens to prove the
 * propstat discipline on its own: the home's absent calendar-color comes
 * back in a 404 propstat interleaved with the 200 one.</li>
 * <li>{@code stalwart-home-depth1-sharee-listing.xml} — DERIVED from that
 * live capture and from the observation record of EXO-90235 (rig, Stalwart
 * v0.16, 2026-09-13): after alice granted bob {@code DAV:read} on her
 * {@code default} calendar, the Depth:1 listing of <b>bob's</b> home also
 * returned her collection at <b>her</b> path,
 * {@code /dav/cal/alice%40stalwart.local/default/}, with {@code DAV:owner}
 * naming her principal and {@code read, read-current-user-privilege-set} as
 * the whole privilege set. The raw sharee capture was not saved; the shape
 * of the two own collections and of the owner element is the 2026-08-20
 * capture's, with {@code DAV:owner} added as RFC 3744 §5.1 defines it.</li>
 * <li>{@code bluemind-403-refused-auth.http} — captured live from the
 * BlueMind demo (2026-08-20), unauthenticated and with wrong credentials
 * alike: <b>403</b>, text/html, no WWW-Authenticate.</li>
 * <li>{@code bluemind-mkcalendar-201-nothing-created.http} and
 * {@code bluemind-propfind-home-depth1.xml} — captured live from the
 * BlueMind demo (2026-08-20), authenticated: the MKCALENDAR 201 a request
 * without {@code supported-calendar-component-set} gets over a creation
 * that never happened, and the Depth:1 home listing proving the claimed
 * collection absent.</li>
 * <li>{@code bluemind-propfind-home-depth1-with-mirror.xml} — DERIVED from
 * that live listing: the same capture plus the collection an MKCALENDAR
 * declaring the component set had just created, as observed live in the
 * browser the same day.</li>
 * <li>{@code bluemind-mkcalendar-207-failing-propstat.xml} and
 * {@code bluemind-propfind-dav-rooted.xml} — RECONSTRUCTED from the browser
 * connector's documented transcripts (caldavConnector.js:2272-2306 and the
 * relay work); the raw authenticated captures need credentials this session
 * did not have and are what PR0 still owes.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
public class HttpCalDavClientServerQuirksTest {

  private static final String SERVER_URL    = "https://caldav.example.invalid/dav/";

  private static final String USER          = "someone@demo3.livecollab.fr";

  private static final String PASSWORD      = "not-the-real-one";

  private static final String BLUEMIND_HOME =
                                            "/dav/calendars/__uids__/9F3C1A20-4D5E-4B7A-8C61-2E0D7A4B9C13/";

  @Mock
  private CaldavServerService caldavServerService;

  private HttpClient          transport;

  private HttpCalDavClient    client;

  private CalDavEndpoint      endpoint;

  private List<HttpRequest>   sent;

  @BeforeEach
  void setUp() {
    transport = mock(HttpClient.class);
    client = new HttpCalDavClient(transport, caldavServerService, new CaldavCredentialsResolver(credentialsAnswering("Basic stubbed")));
    sent = new ArrayList<>();
    lenient().when(caldavServerService.resolveServer(1L))
             .thenReturn(new CaldavServer(1L, "agenda.caldavCalendar", "BlueMind", null, SERVER_URL, true, null, null, null,
                                          null, true, null, null, null,
                                          null, null, null, "personal", null));
    // The declared URL carries no {username}, so minting never asks the
    // provider for an account — only the requests on this endpoint do.
    endpoint = client.endpoint(1L, USER);
  }

  @Test
  void blueMindRefusesBasicAuthWith403NotA401() throws Exception {
    // Verified live 2026-08-20: /dav/ answers 403 with an HTML body and no
    // WWW-Authenticate, to wrong credentials and to none at all.
    givenAnswer(403, Map.of("Content-Type", "text/html"), fixture("bluemind-403-refused-auth.http"));

    assertThrows(CalDavAuthenticationException.class,
                 () -> client.discoverCalendarHome(endpoint),
                 "a 403 on a read verb IS the BlueMind credential refusal and must classify as one");
  }

  @Test
  void a401ClassifiesAsACredentialRefusalEverywhere() throws Exception {
    givenAnswer(401, Map.of(), "");

    assertThrows(CalDavAuthenticationException.class,
                 () -> client.putObject(endpoint, "/dav/calendars/vevent/x/a.ics", "BEGIN:VCALENDAR"));
  }

  @Test
  void a403OnAWriteStaysARefusalNotACredentialFailure() throws Exception {
    givenAnswer(403, Map.of(), "");

    assertThrows(CalDavException.class,
                 () -> client.putObject(endpoint, "/dav/calendars/vevent/x/a.ics", "BEGIN:VCALENDAR"));
    try {
      givenAnswer(403, Map.of(), "");
      client.putObject(endpoint, "/dav/calendars/vevent/x/a.ics", "BEGIN:VCALENDAR");
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

    assertNull(client.fetchObject(endpoint, "/dav/calendars/vevent/x/missing.ics"),
               "absent, reported as a fact — the conditional create keeps the answer safe");
  }

  @Test
  void aGenuineMkCalendarRefusalIsAnAnswerNotAnException() throws Exception {
    // A server genuinely declining MKCALENDAR (405 here) is an answer the
    // caller maps to its inbound-only degradation. This is NOT the BlueMind
    // behaviour, despite what three rounds of debugging concluded: BlueMind
    // never refuses an MKCALENDAR under the calendar home — see the two
    // tests below for what it really does — so refused() never fires there.
    givenAnswer(405, Map.of(), "");

    MkCalendarResult result = client.mkCalendar(endpoint, "/dav/calendars/vevent/x/exo-cal-1/", "eXo", null);

    assertTrue(result.refused(), "the caller maps this to its inbound-only degradation, it is not an error");
    assertFalse(result.provenCreated());
  }

  @Test
  void blueMindAnswers201OverACreationThatNeverHappened() throws Exception {
    // The trap, proven live 2026-08-20: an MKCALENDAR body without
    // supported-calendar-component-set makes BlueMind's kind derivation
    // fail internally; the failure is swallowed and 201 goes out anyway —
    // the capture replayed here — while the Depth:1 home listing, also
    // captured live, shows the claimed collection absent. This is why
    // provenCreated() is documented as a claim: a naive client reading the
    // 201 concludes "created", pushes into the void, and every later
    // attempt reads as the server "refusing" calendar creation.
    givenSequence(new int[] { 201, 207 },
                  new String[] { fixture("bluemind-mkcalendar-201-nothing-created.http"),
                      fixture("bluemind-propfind-home-depth1.xml") });

    MkCalendarResult result = client.mkCalendar(endpoint, BLUEMIND_HOME + "exo-meetings/", "eXo Meetings", null);
    List<CalendarCollection> calendars = client.listCalendars(endpoint, BLUEMIND_HOME);

    assertTrue(result.provenCreated(), "the server's claim — everything a client trusting the status ever sees");
    assertFalse(result.refused(), "BlueMind never refuses: the failure hides behind a 201, not a 4xx");
    assertTrue(calendars.stream().noneMatch(calendar -> calendar.href().endsWith("/exo-meetings/")),
               "the listing is the fact: nothing was created, whatever the 201 claimed — read back, always");
  }

  @Test
  void blueMindCreationIsRealOnceTheComponentSetIsDeclaredAndTheListingConfirmsIt() throws Exception {
    // The contract, observed live the same day: the same MKCALENDAR plus
    // <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set>
    // answers the same 201 — and this time the collection appears in the
    // next listing. The body assertion is on the XML actually sent, so this
    // test fails the moment the component set is dropped from the client:
    // the 201s would keep coming, the creations would silently stop.
    givenSequence(new int[] { 201, 207 },
                  new String[] { fixture("bluemind-mkcalendar-201-nothing-created.http"),
                      fixture("bluemind-propfind-home-depth1-with-mirror.xml") });

    MkCalendarResult result = client.mkCalendar(endpoint, BLUEMIND_HOME + "exo-meetings/", "eXo Meetings", null);
    List<CalendarCollection> calendars = client.listCalendars(endpoint, BLUEMIND_HOME);

    assertTrue(result.provenCreated());
    String body = bodyOf(sent.get(0));
    assertTrue(body.contains("<c:supported-calendar-component-set><c:comp name=\"VEVENT\"/></c:supported-calendar-component-set>"),
               "the component set must ride EVERY MKCALENDAR, as a nested element — without it BlueMind answers"
                   + " 201 while creating nothing, the bug that read as a refusal three times over: " + body);
    assertTrue(calendars.stream().anyMatch(calendar -> calendar.href().endsWith("/exo-meetings/")),
               "with the component set declared, the creation is one the listing confirms");
  }

  @Test
  void aMkCalendar207WithFailingPropstatsIsNeverASuccess() throws Exception {
    givenAnswer(207, Map.of("Content-Type", "application/xml"), fixture("bluemind-mkcalendar-207-failing-propstat.xml"));

    MkCalendarResult result = client.mkCalendar(endpoint, "/dav/calendars/vevent/x/exo-cal-1/", "eXo", "#FF0000");

    assertFalse(result.provenCreated(),
                "MKCALENDAR is atomic: a 207 reports a rejected property and a collection that was NOT created");
    assertEquals(List.of(403, 424), result.failedPropstatStatuses(),
                 "the failing statuses are surfaced so the caller can name the real reason (the non-424 one)");
  }

  @Test
  void aRenameIsAProppatchOfTheDisplayNameAlone() throws Exception {
    givenAnswer(207, Map.of("Content-Type", "application/xml"), proppatchAnswer(200));

    PropPatchResult result = client.setDisplayName(endpoint, BLUEMIND_HOME + "exo-cal-1/", "Team & <co>");

    assertTrue(result.accepted());
    assertFalse(result.refused());
    HttpRequest request = sent.get(0);
    assertEquals("PROPPATCH", request.method());
    String body = bodyOf(request);
    assertTrue(body.contains("<d:propertyupdate xmlns:d=\"DAV:\">"), body);
    assertTrue(body.contains("<d:set><d:prop><d:displayname>Team &amp; &lt;co&gt;</d:displayname></d:prop></d:set>"),
               "the name is escaped, so a calendar called after an ampersand does not become malformed XML: " + body);
    assertFalse(body.contains("supported-calendar-component-set"),
                "a rename touches the name and nothing else — the collection's kind is not re-declared");
  }

  @Test
  void aProppatch207WithAFailingPropstatIsNotAccepted() throws Exception {
    givenAnswer(207, Map.of("Content-Type", "application/xml"), proppatchAnswer(403));

    PropPatchResult result = client.setDisplayName(endpoint, BLUEMIND_HOME + "exo-cal-1/", "Work");

    assertFalse(result.accepted(), "207 is a 2xx that can carry a refusal; the propstat is what says no");
    assertEquals(List.of(403), result.failedPropstatStatuses());
  }

  @Test
  void aRenameRefusedWith403IsAnAnswerNotACredentialFailure() throws Exception {
    givenAnswer(403, Map.of(), "");

    PropPatchResult result = client.setDisplayName(endpoint, BLUEMIND_HOME + "exo-cal-1/", "Work");

    assertTrue(result.refused(), "the caller logs and lives with it; pausing the account over a rename would be absurd");
    assertFalse(result.accepted());
  }

  @Test
  void aRenameWith401IsACredentialRefusal() throws Exception {
    givenAnswer(401, Map.of(), "");

    assertThrows(CalDavAuthenticationException.class,
                 () -> client.setDisplayName(endpoint, BLUEMIND_HOME + "exo-cal-1/", "Work"));
  }

  /**
   * A PROPPATCH answer in the shape both rigs give (Stalwart verified live
   * 2026-09-04: 207, one propstat, the property echoed empty).
   *
   * @param propstatStatus the status the one propstat carries
   * @return the multistatus body
   */
  private String proppatchAnswer(int propstatStatus) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:"><D:response><D:href>%sexo-cal-1/</D:href>
        <D:propstat><D:prop><D:displayname/></D:prop><D:status>HTTP/1.1 %s</D:status></D:propstat>
        </D:response></D:multistatus>""".formatted(BLUEMIND_HOME, propstatStatus == 200 ? "200 OK" : propstatStatus + " Forbidden");
  }

  @Test
  void blueMindsDavRootedHrefsAreKeptServerAbsolute() throws Exception {
    // BlueMind advertises hrefs rooted at /dav/ whatever path the registered
    // URL carries: the client must keep them addressable as answered, and
    // its propstat reading must survive the 404-propstat interleaving.
    givenAnswer(207, Map.of("Content-Type", "application/xml"), fixture("bluemind-propfind-dav-rooted.xml"));

    List<CalendarCollection> calendars = client.listCalendars(endpoint, "/dav/");

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

    List<CalendarCollection> calendars = client.listCalendars(endpoint, "/dav/cal/alice%40stalwart.local/");

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

    CalendarCollection home = client.readCalendar(endpoint, "/dav/cal/alice%40stalwart.local/");

    assertNull(home, "the home is a plain collection, not a calendar — and its 404-propstat colour was never granted");
  }

  @Test
  void theLiveStalwartAnswerProbesToTheSyncCollectionTier() throws Exception {
    givenAnswer(207, Map.of("Content-Type", "application/xml", "DAV", "1, 2, 3, access-control, calendar-access"),
                fixture("stalwart-home-depth1-full-props.xml"));

    ServerCapabilities capabilities = client.probeCapabilities(endpoint, "/dav/cal/alice%40stalwart.local/");

    assertEquals(ServerCapabilities.SyncTier.SYNC_COLLECTION, capabilities.tier());
    assertTrue(capabilities.calendarMultiget());
    assertTrue(capabilities.calendarQuery());
  }

  // ------------------------------------ a colleague's calendar in the home, EXO-90235

  /** Bob's principal, as Stalwart names it: the login, percent-encoded. */
  private static final String BOB   = "/dav/pal/bob%40stalwart.local/";

  /** Alice's principal, the owner of the one collection she shared with Bob. */
  private static final String ALICE = "/dav/pal/alice%40stalwart.local/";

  /**
   * The listing that caused EXO-90235, read as it should have been: the
   * colleague's collection carries her owner and a read-only privilege set,
   * and the user's own carry his owner and a set with write.
   *
   * <p>
   * Both signals are asserted on their own before the predicate that combines
   * them, so that a parser regression on either one is named for what it is
   * rather than as "the share was not recognised".
   */
  @Test
  void aColleaguesCalendarInTheHomeCarriesHerOwnerAndAReadOnlyPrivilegeSet() throws Exception {
    givenAnswer(207, Map.of("Content-Type", "application/xml"), fixture("stalwart-home-depth1-sharee-listing.xml"));

    List<CalendarCollection> calendars = client.listCalendars(endpoint, "/dav/cal/bob%40stalwart.local/");

    assertTrue(bodyOf(sent.get(0)).contains("<d:owner/>"),
               "the owner has to be asked for, or no server ever answers it: " + bodyOf(sent.get(0)));
    CalendarCollection alices = only(calendars, "/dav/cal/alice%40stalwart.local/default/");
    assertEquals(ALICE, alices.owner(), "the owner is read from the granted propstat and folded to a path");
    assertTrue(alices.privilegesAnswered(), "Stalwart answered a privilege set — a read-only one");
    assertFalse(alices.writable());
    assertEquals("Stalwart Calendar (alice)", alices.displayName());

    CalendarCollection bobs = only(calendars, "/dav/cal/bob%40stalwart.local/default/");
    assertEquals(BOB, bobs.owner());
    assertTrue(bobs.privilegesAnswered());
    assertTrue(bobs.writable());
  }

  /**
   * The classification the engine runs on that listing, with Bob's own
   * principal: her calendar is a share, his are not. This is the one line
   * the sync skips on and the read-through marks read-only on.
   */
  @Test
  void aColleaguesCalendarIsAShareAndTheUsersOwnAreNot() throws Exception {
    givenAnswer(207, Map.of("Content-Type", "application/xml"), fixture("stalwart-home-depth1-sharee-listing.xml"));

    List<CalendarCollection> calendars = client.listCalendars(endpoint, "/dav/cal/bob%40stalwart.local/");

    assertTrue(only(calendars, "/dav/cal/alice%40stalwart.local/default/").isSharedWith(BOB),
               "owned by alice and read-only: either signal alone would do, and both are present");
    assertFalse(only(calendars, "/dav/cal/bob%40stalwart.local/default/").isSharedWith(BOB),
                "the user's own default calendar is not a share of anybody's");
    assertFalse(only(calendars, "/dav/cal/bob%40stalwart.local/exo-meetings/").isSharedWith(BOB));
  }

  /**
   * The owner's own listing, captured before ownership was ever requested:
   * no {@code DAV:owner} in the answer, the full privilege set on every
   * collection. Nothing in it is a share, and the sweep keeps materialising
   * it — the negative half of EXO-90235, on the same server.
   */
  @Test
  void theOwnersOwnStalwartListingHoldsNoShare() throws Exception {
    givenAnswer(207, Map.of("Content-Type", "application/xml"), fixture("stalwart-home-depth1-full-props.xml"));

    List<CalendarCollection> calendars = client.listCalendars(endpoint, "/dav/cal/alice%40stalwart.local/");

    assertFalse(calendars.isEmpty());
    for (CalendarCollection calendar : calendars) {
      assertNull(calendar.owner(), calendar.href() + " was listed before the owner was requested");
      assertTrue(calendar.privilegesAnswered(), calendar.href());
      assertTrue(calendar.writable(), calendar.href());
      assertFalse(calendar.isSharedWith(ALICE), calendar.href() + " is alice's own");
    }
  }

  /**
   * The BlueMind home listing, likewise: no owner answered, write granted.
   * BlueMind names the <em>subscriber</em> as owner of a share and grants
   * them the full set (EXO-90234), so on that server neither signal fires —
   * which is exactly what this pins: nothing BlueMind lists here becomes a
   * share by this rule, and its own calendars keep being materialised.
   */
  @Test
  void theBlueMindListingHoldsNoShareByTheOwnerOrPrivilegeRule() throws Exception {
    givenAnswer(207, Map.of("Content-Type", "application/xml"), fixture("bluemind-propfind-home-depth1.xml"));

    List<CalendarCollection> calendars = client.listCalendars(endpoint, BLUEMIND_HOME);

    assertFalse(calendars.isEmpty());
    for (CalendarCollection calendar : calendars) {
      assertFalse(calendar.isSharedWith("/dav/principals/__uids__/9F3C1A20-4D5E-4B7A-8C61-2E0D7A4B9C13/"),
                  calendar.href() + " is the account's own on BlueMind");
    }
  }

  /**
   * An owner the server names on another host is compared against nothing:
   * the listing does not fail over it, and the collection does not become a
   * share over it. An absolute owner on the declared host is folded to a
   * path and compared like any other.
   */
  @Test
  void anOwnerOnAnotherHostIsLeftUnknownRatherThanFailingTheListingOrMakingAShare() throws Exception {
    givenAnswer(207, Map.of("Content-Type", "application/xml"), """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:A="urn:ietf:params:xml:ns:caldav">
          <D:response><D:href>/dav/cal/bob/own/</D:href><D:propstat><D:prop>
            <D:resourcetype><D:collection/><A:calendar/></D:resourcetype>
            <D:owner><D:href>https://internal-host.invalid/dav/pal/bob/</D:href></D:owner>
            <D:current-user-privilege-set><D:privilege><D:write/></D:privilege></D:current-user-privilege-set>
          </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
          <D:response><D:href>/dav/cal/bob/folded/</D:href><D:propstat><D:prop>
            <D:resourcetype><D:collection/><A:calendar/></D:resourcetype>
            <D:owner><D:href>%s/dav/pal/alice/</D:href></D:owner>
            <D:current-user-privilege-set><D:privilege><D:write/></D:privilege></D:current-user-privilege-set>
          </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>""".formatted(SERVER_URL.replaceAll("/dav/$", "")));

    List<CalendarCollection> calendars = client.listCalendars(endpoint, "/dav/cal/bob/");

    CalendarCollection foreign = only(calendars, "/dav/cal/bob/own/");
    assertNull(foreign.owner(), "an owner on another host cannot be compared, so it is not kept");
    assertFalse(foreign.isSharedWith("/dav/pal/bob/"), "and an unknown owner is not a share");
    CalendarCollection folded = only(calendars, "/dav/cal/bob/folded/");
    assertEquals("/dav/pal/alice/", folded.owner(), "an absolute owner on the declared host is folded to its path");
    assertTrue(folded.isSharedWith("/dav/pal/bob/"));
  }

  /**
   * A 403 on a PUT is the server refusing the write, told apart from every
   * other refusal so the engine can give up on it at once (EXO-90235). It
   * is still not a credential failure: that reading would pause accounts
   * whose password is fine, as the test above this family already pins.
   */
  @Test
  void a403OnAPutIsAForbiddenWrite() throws Exception {
    givenAnswer(403, Map.of(), "");

    CalDavException refusal = assertThrows(CalDavException.class,
                                           () -> client.putObject(endpoint, "/dav/cal/alice%40stalwart.local/default/a.ics",
                                                                  "BEGIN:VCALENDAR"));

    assertTrue(refusal instanceof CalDavForbiddenException, refusal.getClass().getName());
    assertFalse(refusal instanceof CalDavAuthenticationException);
    assertTrue(refusal.getMessage().contains("403"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("/dav/cal/alice%40stalwart.local/default/a.ics"),
               "the message names the object, which is what the abandonment line needs: " + refusal.getMessage());
  }

  /**
   * The same on a DELETE — the other write a read-only share refuses.
   */
  @Test
  void a403OnADeleteIsAForbiddenWrite() throws Exception {
    givenAnswer(403, Map.of(), "");

    assertThrows(CalDavForbiddenException.class,
                 () -> client.deleteObject(endpoint, "/dav/cal/alice%40stalwart.local/default/a.ics", "\"e\""));
  }

  /**
   * Any other refused status on a write stays the plain refusal it was: the
   * forbidden reading is for 403 alone, never widened by accident.
   */
  @Test
  void a405OnAPutStaysAPlainRefusal() throws Exception {
    givenAnswer(405, Map.of(), "");

    CalDavException refusal = assertThrows(CalDavException.class,
                                           () -> client.putObject(endpoint, "/dav/cal/x/a.ics", "BEGIN:VCALENDAR"));

    assertFalse(refusal instanceof CalDavForbiddenException);
  }

  /**
   * The one collection of a listing at a given path.
   *
   * @param calendars the listing
   * @param href the path wanted
   * @return the collection
   */
  private CalendarCollection only(List<CalendarCollection> calendars, String href) {
    List<CalendarCollection> found = calendars.stream().filter(calendar -> href.equals(calendar.href())).toList();
    assertEquals(1, found.size(), href + " should be listed exactly once among " + calendars.stream().map(CalendarCollection::href).toList());
    return found.get(0);
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
    when(transport.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
      sent.add(invocation.getArgument(0));
      return response;
    });
  }

  /**
   * Queues one answer per call, in order — for the replays where a write's
   * answer and the read-back's answer must differ, the very distinction the
   * confirm-by-listing discipline exists for. The last pair keeps serving
   * once the sequence is exhausted.
   *
   * @param statuses the HTTP statuses to answer, one per call
   * @param bodies the bodies to answer, parallel to statuses
   * @throws Exception never — the mock declares it
   */
  @SuppressWarnings("unchecked")
  private void givenSequence(int[] statuses, String[] bodies) throws Exception {
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    when(transport.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
      sent.add(invocation.getArgument(0));
      int index = Math.min(calls.getAndIncrement(), statuses.length - 1);
      HttpResponse<InputStream> response = mock(HttpResponse.class);
      lenient().when(response.statusCode()).thenReturn(statuses[index]);
      lenient().when(response.body())
               .thenReturn(new ByteArrayInputStream(bodies[index].getBytes(StandardCharsets.UTF_8)));
      lenient().when(response.headers())
               .thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("application/xml; charset=\"utf-8\"")),
                                          (a, b) -> true));
      return response;
    });
  }

  /**
   * The body of a sent request, read back from its publisher — the only way
   * to assert on the XML the client actually put on the wire, which is the
   * whole point where a mock accepting any body is what hid the missing
   * component set three times.
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

  /**
   * A credentials provider answering a Basic header, which is what Personal
   * produces. The client is not supposed to know that — it sends whatever it is
   * given — so this exists to let the requests carry something, not to pin the
   * scheme.
   *
   * @return the stubbed service
   */
  private static ConnectorCredentialsService credentialsAnswering(String header) {
    ConnectorCredentialsService service = mock(ConnectorCredentialsService.class);
    try {
      lenient().doReturn(new HttpConnectorCredentials(header, null)).when(service).produce(any());
    } catch (ConnectorCredentialsException e) {
      throw new IllegalStateException(e);
    }
    return service;
  }
}
