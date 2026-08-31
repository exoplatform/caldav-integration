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
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import org.exoplatform.caldav.service.CaldavServerService;

/**
 * The client against the real containerised Stalwart rig — the same server
 * the interim browser connector runs against — earning its place where a
 * mock cannot: the full discovery walk, a create/read/update/delete round
 * trip whose ETags are the server's own, the sync-token increment across a
 * real change, and MKCALENDAR followed by the confirm-by-listing read-back.
 * <p>
 * Skipped, not failed, when the rig is not answering (CI has no Stalwart):
 * every test assumes the DAV port accepts a TCP connection first. Rig
 * coordinates are overridable — {@code caldav.test.stalwart.url},
 * {@code caldav.test.stalwart.user}, {@code caldav.test.stalwart.password}
 * — and default to the local dev rig.
 * <p>
 * <b>Rig-gated: this class contributes no CI signal.</b> Every test here assumes out when
 * the rig is unreachable, which is always the case in CI — these are the skipped tests in
 * a normal run. The server-quirk behaviour it exercises is pinned for CI by
 * {@link HttpCalDavClientServerQuirksTest} (12 tests); treat that class, not this one, as
 * the regression net.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HttpCalDavClientStalwartTest {

  private static final String    SERVER_URL = System.getProperty("caldav.test.stalwart.url",
                                                                 "http://localhost:8090/dav/cal/{username}/");

  private static final String    USER       = System.getProperty("caldav.test.stalwart.user", "alice@stalwart.local");

  private static final String    PASSWORD   = System.getProperty("caldav.test.stalwart.password", "AlicePass123!");

  private final String           runId      = UUID.randomUUID().toString();

  private final HttpCalDavClient client     = newClient();

  private CalDavEndpoint         endpoint;

  private String                 homeHref;

  private String                 calendarHref;

  private String                 objectHref;

  private String                 createdEtag;

  private String                 syncToken;

  /**
   * A client whose registry answers the rig URL — the registry itself is a
   * mock because this test exercises the protocol, not the storage.
   *
   * @return the client under test
   */
  private HttpCalDavClient newClient() {
    CaldavServerService registry = mock(CaldavServerService.class);
    lenient().when(registry.resolveServerUrl(1L)).thenReturn(SERVER_URL);
    return new HttpCalDavClient(registry);
  }

  /**
   * Skips the whole class when the rig's DAV port is not answering.
   */
  private void assumeRigIsUp() {
    URI base = URI.create(SERVER_URL.replace("{username}", "u"));
    int port = base.getPort() != -1 ? base.getPort() : ("https".equalsIgnoreCase(base.getScheme()) ? 443 : 80);
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(base.getHost(), port), 1500);
    } catch (IOException e) {
      assumeTrue(false, "The Stalwart rig is not answering on " + base.getHost() + ":" + port + " — skipping");
    }
  }

  @Test
  @Order(1)
  void discoveryWalksToTheRealCalendarHome() {
    assumeRigIsUp();
    endpoint = client.endpoint(1L, USER);

    homeHref = client.discoverCalendarHome(endpoint, USER, PASSWORD);

    assertNotNull(homeHref);
    assertTrue(homeHref.startsWith("/"), "hrefs are answered as server-absolute paths");
  }

  @Test
  @Order(2)
  void theHomeListsAtLeastOneWritableCalendar() {
    assumeRigIsUp();
    discoveryWalksToTheRealCalendarHome();

    List<CalendarCollection> calendars = client.listCalendars(endpoint, homeHref, USER, PASSWORD);

    assertFalse(calendars.isEmpty());
    calendarHref = calendars.stream()
                            .filter(CalendarCollection::writable)
                            .findFirst()
                            .orElseThrow()
                            .href();
  }

  @Test
  @Order(3)
  void theRigProbesToTheSyncCollectionTier() {
    assumeRigIsUp();
    theHomeListsAtLeastOneWritableCalendar();

    ServerCapabilities capabilities = client.probeCapabilities(endpoint, calendarHref, USER, PASSWORD);

    assertEquals(ServerCapabilities.SyncTier.SYNC_COLLECTION, capabilities.tier(),
                 "Stalwart advertises sync-collection — if this downgrades, the probe broke, not the rig");
    assertTrue(capabilities.calendarMultiget());
  }

  @Test
  @Order(4)
  void aConditionalRoundTripHoldsAgainstTheRealServer() {
    assumeRigIsUp();
    theHomeListsAtLeastOneWritableCalendar();
    objectHref = calendarHref + "exo-test-" + runId + ".ics";
    String uid = "exo-test-" + runId;
    String ics = icsFor(uid, "Client IT event");
    syncToken = client.syncCollection(endpoint, calendarHref, null, USER, PASSWORD).syncToken();

    // Create insists on creating.
    PutResult created = client.putObject(endpoint, objectHref, ics, USER, PASSWORD);
    assertFalse(created.preconditionFailed());

    // Creating the same object again is refused by the server, not merged.
    PutResult duplicate = client.putObject(endpoint, objectHref, ics, USER, PASSWORD);
    assertTrue(duplicate.preconditionFailed(), "If-None-Match:* means the server itself refuses the second create");

    // The object reads back with the server's own version.
    CalendarObject fetched = client.fetchObject(endpoint, objectHref, USER, PASSWORD);
    assertNotNull(fetched);
    assertNotNull(fetched.etag());
    assertTrue(fetched.calendarData().contains(uid));
    createdEtag = fetched.etag();

    // A stale precondition is refused; the fresh one is accepted.
    assertEquals(PutResult.PRECONDITION_FAILED,
                 client.updateObject(endpoint, objectHref, icsFor(uid, "Renamed"), "\"stale-etag\"", USER, PASSWORD)
                       .status(),
                 "the server protects somebody else's change when the etag is not current");
    PutResult updated = client.updateObject(endpoint, objectHref, icsFor(uid, "Renamed"), createdEtag, USER, PASSWORD);
    assertFalse(updated.preconditionFailed());

    // The listing shows the object with a version; sync-collection reports it.
    Map<String, String> etags = client.listResourceEtags(endpoint, calendarHref, USER, PASSWORD);
    assertTrue(etags.containsKey(objectHref), "the listing names the object this run created");
    SyncCollectionResult changes = client.syncCollection(endpoint, calendarHref, syncToken, USER, PASSWORD);
    assertTrue(changes.tokenValid());
    assertTrue(changes.changed().stream().anyMatch(object -> object.href().equals(objectHref)),
               "the token from before the create must report it as changed");

    // Multiget and ranged query both return the object's data.
    List<CalendarObject> multi = client.multiget(endpoint, calendarHref, List.of(objectHref), USER, PASSWORD);
    assertEquals(1, multi.size());
    List<CalendarObject> queried = client.calendarQuery(endpoint,
                                                        calendarHref,
                                                        Instant.parse("2030-01-01T00:00:00Z"),
                                                        Instant.parse("2030-01-03T00:00:00Z"),
                                                        USER,
                                                        PASSWORD);
    assertTrue(queried.stream().anyMatch(object -> object.href().equals(objectHref)));

    // Conditional delete: gone is gone, and gone again is a fact.
    String currentEtag = client.fetchObject(endpoint, objectHref, USER, PASSWORD).etag();
    int deleteStatus = client.deleteObject(endpoint, objectHref, currentEtag, USER, PASSWORD);
    assertTrue(deleteStatus == 200 || deleteStatus == 204);
    assertNull(client.fetchObject(endpoint, objectHref, USER, PASSWORD));
    int again = client.deleteObject(endpoint, objectHref, null, USER, PASSWORD);
    assertTrue(again == 404 || again == 410, "a retried delete is idempotent, answered as a fact");
  }

  @Test
  @Order(5)
  void mkCalendarConfirmedByListingThenDeleted() {
    assumeRigIsUp();
    discoveryWalksToTheRealCalendarHome();
    String collectionHref = homeHref + "exo-cal-it-" + runId + "/";

    MkCalendarResult result = client.mkCalendar(endpoint, collectionHref, "eXo client IT " + runId, "#FF8800", USER,
                                                PASSWORD);

    assertFalse(result.refused());
    // The read-back discipline: the answer is a claim, the listing is the fact.
    List<CalendarCollection> calendars = client.listCalendars(endpoint, homeHref, USER, PASSWORD);
    assertTrue(calendars.stream().anyMatch(calendar -> calendar.href().equals(collectionHref)),
               "presence in a fresh listing is the only statement of success MKCALENDAR gets credit for");

    int deleteStatus = client.deleteObject(endpoint, collectionHref, null, USER, PASSWORD);
    assertTrue(deleteStatus == 200 || deleteStatus == 204, "the run cleans its own collection up");
  }

  @Test
  @Order(6)
  void wrongCredentialsClassifyAsACredentialRefusal() {
    assumeRigIsUp();
    CalDavEndpoint rigEndpoint = client.endpoint(1L, USER);

    assertThrows(CalDavAuthenticationException.class,
                 () -> client.discoverCalendarHome(rigEndpoint, USER, "definitely-not-the-password"));
  }

  /**
   * A minimal VEVENT for the round trip, timed inside the query window.
   *
   * @param uid the event UID
   * @param summary the summary to store
   * @return the iCalendar text
   */
  private String icsFor(String uid, String summary) {
    return """
        BEGIN:VCALENDAR\r
        VERSION:2.0\r
        PRODID:-//eXo//caldav-client-it//EN\r
        BEGIN:VEVENT\r
        UID:%s\r
        DTSTAMP:20300101T090000Z\r
        DTSTART:20300102T100000Z\r
        DTEND:20300102T110000Z\r
        SUMMARY:%s\r
        END:VEVENT\r
        END:VCALENDAR\r
        """.formatted(uid, summary);
  }
}
