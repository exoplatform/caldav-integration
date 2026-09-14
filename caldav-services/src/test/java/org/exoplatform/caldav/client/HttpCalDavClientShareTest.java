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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import org.exoplatform.caldav.client.AccessControlEntry.AcePrincipal;
import org.exoplatform.caldav.client.AccessControlEntry.AcePrincipal.Kind;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.provider.CaldavCredentialsResolver;
import org.exoplatform.caldav.service.CaldavServerService;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.HttpConnectorCredentials;

/**
 * The three requests sharing a calendar out rests on (EXO-90253), against
 * canned answers: {@code OPTIONS} and the capability it selects, the ACL read,
 * and the {@code ACL} write.
 *
 * <p>
 * What is pinned is what a wrong answer would silently cost. A capability read
 * too loosely offers granting on BlueMind, where it is unverified. An entry
 * the parser skips is an entry the write deletes — somebody's share made
 * outside eXo. A body that loses a namespace, a privilege or an inverted
 * principal on its way out rewrites someone's access. A refusal whose reason
 * is dropped is shown as a generic failure. And a write addressed anywhere but
 * a collection eXo created is the one request this feature must never send.
 *
 * <p>
 * Fixtures marked DERIVED in their header are built from the live
 * observations of 2026-09-13 and RFC 3744, not captured; the header says from
 * what.
 */
public class HttpCalDavClientShareTest {

  private static final String   SERVER_URL      = "http://cal.example.com/dav/cal/{username}/";

  private static final String   USER            = "alice@stalwart.local";

  private static final String   AUTHORIZATION   = "Basic YWxpY2VAc3RhbHdhcnQubG9jYWw6QWxpY2VQYXNzMTIzIQ==";

  private static final String   ANCHOR          = "9f1c2d3e-4b5a-6c7d-8e9f-0a1b2c3d4e5f";

  /** The collection eXo exported alice's calendar to. */
  private static final String   COLLECTION      = "/dav/cal/alice%40stalwart.local/exo-cal-" + ANCHOR + "/";

  /**
   * Stalwart 0.16's DAV header, live on 2026-09-13 (design A.5): access
   * control, no vendor sharing, no proxy.
   */
  private static final String   STALWART_DAV    =
                                             "1, 2, 3, access-control, extended-mkcol, calendar-access, calendar-auto-schedule, calendar-no-timezone, addressbook";

  /**
   * Stalwart's Allow header: DERIVED — the live answer was recorded as
   * "Allow: … MKCALENDAR, … REPORT, ACL" (design A.5); the elided methods are
   * the standard WebDAV set.
   */
  private static final String   STALWART_ALLOW  =
                                               "OPTIONS, GET, HEAD, POST, PUT, DELETE, COPY, MOVE, MKCALENDAR, MKCOL, PROPFIND, PROPPATCH, LOCK, UNLOCK, REPORT, ACL";

  /**
   * BlueMind's DAV header, CAPTURED on 2026-08-20
   * ({@code caldav-webapp/src/test/js/fixtures/bluemind-principal.captured.xml:10}).
   */
  private static final String   BLUEMIND_DAV    =
                                             "1, access-control, calendar-access, calendar-schedule, calendar-auto-schedule, calendar-availability, inbox-availability, calendar-proxy, calendarserver-private-events, calendarserver-sharing, calendarserver-sharing-no-scheduling, calendar-query-extended, calendar-default-alarms, calendarserver-partstat-changes, extended-mkcol, calendarserver-principal-property-search, calendarserver-principal-search, calendarserver-home-sync, addressbook";

  /** Google's DAV header, live (design A.5). */
  private static final String   GOOGLE_DAV      = "1, calendar-access, calendar-schedule, calendar-auto-schedule, calendar-proxy";

  private HttpClient            transport;

  private HttpCalDavClient      client;

  private CalDavEndpoint        endpoint;

  private final List<HttpRequest> sent          = new ArrayList<>();

  private final Deque<HttpResponse<InputStream>> answers = new ArrayDeque<>();

  /**
   * A client over a mocked transport, answering from a queue.
   *
   * @throws Exception never — the mocks declare it
   */
  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    transport = mock(HttpClient.class);
    CaldavServerService registry = mock(CaldavServerService.class);
    lenient().when(registry.resolveServer(1L))
             .thenReturn(new CaldavServer(1L, "agenda.caldavCalendar", "Stalwart", null, SERVER_URL, true, null, null, null,
                                          null, true, null, null, null, null, null, null, "personal", null));
    ConnectorCredentialsService credentials = mock(ConnectorCredentialsService.class);
    lenient().doReturn(new HttpConnectorCredentials(AUTHORIZATION, null)).when(credentials).produce(any());
    lenient().doReturn(USER).when(credentials).resolveTargetIdentity(any());
    client = new HttpCalDavClient(transport, registry, new CaldavCredentialsResolver(credentials));
    lenient().when(transport.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
      sent.add(invocation.getArgument(0));
      return answers.removeFirst();
    });
    endpoint = client.endpoint(1L, USER);
  }

  // ---- OPTIONS and the capability it selects ------------------------------

  /**
   * Stalwart: the request is an OPTIONS on the collection with the account's
   * credentials, and its answer selects the RFC 3744 method, offered.
   */
  @Test
  void stalwartsOptionsSelectTheAclMethodWhichIsOffered() {
    answer(200, Map.of("DAV", STALWART_DAV, "Allow", STALWART_ALLOW), "");

    DavOptions options = client.options(endpoint, COLLECTION);

    HttpRequest request = sent.get(0);
    assertEquals("OPTIONS", request.method());
    assertEquals("http://cal.example.com" + COLLECTION, request.uri().toString());
    assertEquals(AUTHORIZATION, request.headers().firstValue("Authorization").orElse(null));
    assertTrue(options.advertises("ACCESS-CONTROL"), "compliance classes compare ignoring case");
    assertTrue(options.allows("acl"), "methods compare ignoring case");
    assertEquals(SharingMechanism.WEBDAV_ACL, SharingMechanism.of(options));
    assertTrue(SharingMechanism.of(options).isOffered());
  }

  /**
   * BlueMind advertises access control too; the vendor sharing protocol wins,
   * and it is not offered — even were the ACL method allowed.
   */
  @Test
  void bluemindsCapturedHeaderSelectsCalendarServerSharingWhichIsNotOffered() {
    DavOptions options = DavOptions.of(List.of(BLUEMIND_DAV), List.of(STALWART_ALLOW));

    assertTrue(options.advertises("access-control"), "the capture does advertise RFC 3744: the rule order is what keeps it off");
    assertEquals(SharingMechanism.CALENDARSERVER_SHARE, SharingMechanism.of(options));
    assertFalse(SharingMechanism.of(options).isOffered());
  }

  /**
   * A header repeated is one list, and an absent header is an empty one.
   */
  @Test
  void repeatedHeadersAreOneListAndAbsentOnesEmpty() {
    answer(204, Map.of("DAV", "1, 2", "Allow", "ACL"), "");
    // the queue helper stores one value per name; add a second DAV value by hand
    answers.clear();
    answers.add(response(204, Map.of("DAV", List.of("1, 2", "access-control"), "Allow", List.of("PROPFIND", "ACL")), ""));

    DavOptions options = client.options(endpoint, COLLECTION);

    assertEquals(SharingMechanism.WEBDAV_ACL, SharingMechanism.of(options));
    assertEquals(SharingMechanism.NONE, SharingMechanism.of(DavOptions.of(null, null)));
    assertEquals(SharingMechanism.NONE, SharingMechanism.of(null));
  }

  /**
   * Google advertises no access control; a server advertising it without
   * allowing ACL cannot be written; proxy delegation over access control
   * (SOGo) is left alone; draft-pot sharing is a vendor protocol too.
   */
  @Test
  void everyOtherShapeSelectsNothingOffered() {
    assertEquals(SharingMechanism.NONE, SharingMechanism.of(DavOptions.of(List.of(GOOGLE_DAV), List.of("OPTIONS, PROPFIND"))));
    assertEquals(SharingMechanism.NONE,
                 SharingMechanism.of(DavOptions.of(List.of("1, access-control, calendar-access"), List.of("PROPFIND, REPORT"))));
    assertEquals(SharingMechanism.NONE,
                 SharingMechanism.of(DavOptions.of(List.of("1, access-control, calendar-access, calendar-proxy"), List.of("ACL"))));
    assertEquals(SharingMechanism.CALENDARSERVER_SHARE,
                 SharingMechanism.of(DavOptions.of(List.of("1, access-control, resource-sharing"), List.of("ACL"))));
  }

  /**
   * OPTIONS is a read: a 401 is a credential refusal, a 404 a plain failure.
   */
  @Test
  void optionsRefusalsAreClassified() {
    answer(401, Map.of(), "");
    assertThrows(CalDavAuthenticationException.class, () -> client.options(endpoint, COLLECTION));
    answer(404, Map.of(), "");
    assertThrows(CalDavException.class, () -> client.options(endpoint, COLLECTION));
  }

  // ---- reading the ACL -----------------------------------------------------

  /**
   * Stalwart's shape: one grant made outside eXo, read as an href principal
   * granted DAV:read, modifiable; the owner's privileges read beside it.
   */
  @Test
  void aStalwartListIsReadEntryByEntry() {
    answer(207, Map.of(), transcript("stalwart-propfind-acl-exo-cal-shared-with-carol.xml"));

    CollectionAcl acl = client.readAcl(endpoint, COLLECTION);

    HttpRequest request = sent.get(0);
    assertEquals("PROPFIND", request.method());
    assertEquals("0", request.headers().firstValue("Depth").orElse(null));
    assertTrue(acl.readable());
    assertTrue(acl.understood());
    assertEquals(1, acl.entries().size());
    AccessControlEntry carol = acl.entries().get(0);
    assertEquals(AcePrincipal.href("/dav/pal/carol%40stalwart.local/"), carol.principal());
    assertFalse(carol.deny());
    assertFalse(carol.inverted());
    assertEquals(Set.of(AccessControlEntry.READ, "{DAV:}read-current-user-privilege-set"), carol.privileges(),
                 "Stalwart reads a read grant back with read-current-user-privilege-set beside it");
    assertTrue(carol.grantsReadOnly());
    assertTrue(carol.isModifiable());
    assertTrue(carol.appliesTo("/dav/pal/carol@stalwart.local"), "compared in the canonical form a principal is recorded in");
    assertTrue(acl.currentUserPrivileges().contains("{DAV:}write-acl"));
    assertTrue(acl.currentUserPrivileges().contains("{urn:ietf:params:xml:ns:caldav}read-free-busy"));
  }

  /**
   * Every form RFC 3744 §5.5 allows is read, flags and all — the parser that
   * skipped one would have the write delete it.
   */
  @Test
  void everyFormOfTheGrammarIsRead() {
    answer(207, Map.of(), transcript("rfc3744-propfind-acl-every-form.xml"));

    CollectionAcl acl = client.readAcl(endpoint, "/dav/calendars/alice/exo-cal-" + ANCHOR + "/");

    assertTrue(acl.understood(), acl.reason());
    assertEquals(6, acl.entries().size());
    AccessControlEntry owner = acl.entries().get(0);
    assertEquals(AcePrincipal.property("DAV:", "owner"), owner.principal());
    assertTrue(owner.protectedEntry());
    assertFalse(owner.isModifiable());
    AccessControlEntry inheritedDeny = acl.entries().get(2);
    assertEquals(Kind.ALL, inheritedDeny.principal().kind());
    assertTrue(inheritedDeny.deny());
    assertEquals("/dav/calendars/alice/", inheritedDeny.inheritedFrom());
    assertFalse(inheritedDeny.isModifiable());
    AccessControlEntry inverted = acl.entries().get(3);
    assertTrue(inverted.inverted());
    assertEquals(Set.of("{urn:ietf:params:xml:ns:caldav}read-free-busy"), inverted.privileges());
    assertFalse(inverted.appliesTo("/dav/principals/mallory"), "an inverted entry applies to everybody but its principal");
    assertEquals(Kind.AUTHENTICATED, acl.entries().get(4).principal().kind());
    assertTrue(acl.entries().get(4).grantsReadOnly());
    assertEquals(Kind.UNAUTHENTICATED, acl.entries().get(5).principal().kind());
    assertFalse(acl.currentUserPrivileges().contains("{DAV:}read-acl"));
  }

  /**
   * An empty list — the owner's own collection before any share, observed on
   * Stalwart — is readable, understood and empty.
   */
  @Test
  void anEmptyListIsReadableAndEmpty() {
    answer(207, Map.of(), aclAnswer("<D:acl/>", PRIVILEGES_OWNER));

    CollectionAcl acl = client.readAcl(endpoint, COLLECTION);

    assertTrue(acl.readable());
    assertTrue(acl.understood());
    assertTrue(acl.entries().isEmpty());
  }

  /**
   * A list withheld in a 403 propstat is not a list, and the privileges say
   * why.
   */
  @Test
  void aWithheldListIsUnreadable() {
    answer(207, Map.of(), """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:"><D:response><D:href>%s</D:href>
          <D:propstat><D:prop><D:acl/></D:prop><D:status>HTTP/1.1 403 Forbidden</D:status></D:propstat>
          <D:propstat><D:prop><D:current-user-privilege-set><D:privilege><D:read/></D:privilege></D:current-user-privilege-set></D:prop>
            <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
        </D:response></D:multistatus>""".formatted(COLLECTION));

    CollectionAcl acl = client.readAcl(endpoint, COLLECTION);

    assertFalse(acl.readable());
    assertFalse(acl.understood());
    assertEquals(Set.of("{DAV:}read"), acl.currentUserPrivileges());
  }

  /**
   * An entry holding anything outside the grammar — an unknown element, a
   * second principal, text inside a grant, a privilege with content — makes
   * the whole list not understood, and nothing is returned to write back.
   */
  @Test
  void anythingOutsideTheGrammarMakesTheListNotUnderstood() {
    for (String ace : List.of("<D:ace><D:principal><D:href>/p/</D:href></D:principal><D:grant><D:privilege><D:read/></D:privilege></D:grant><X:expires xmlns:X=\"urn:x\">2027</X:expires></D:ace>",
                              "<D:ace><D:principal><D:href>/p/</D:href></D:principal><D:principal><D:all/></D:principal><D:grant><D:privilege><D:read/></D:privilege></D:grant></D:ace>",
                              "<D:ace><D:principal><D:href>/p/</D:href></D:principal><D:grant>read</D:grant></D:ace>",
                              "<D:ace><D:principal><D:href>/p/</D:href></D:principal><D:grant><D:privilege><D:read><D:x/></D:read></D:privilege></D:grant></D:ace>",
                              "<D:ace><D:principal><X:group xmlns:X=\"urn:x\"/></D:principal><D:grant><D:privilege><D:read/></D:privilege></D:grant></D:ace>",
                              "<D:ace><D:grant><D:privilege><D:read/></D:privilege></D:grant></D:ace>",
                              "<D:ace><D:principal><D:href>/p/</D:href></D:principal></D:ace>",
                              "<X:rule xmlns:X=\"urn:x\"/>")) {
      answers.clear();
      answer(207, Map.of(), aclAnswer("<D:acl><D:ace><D:principal><D:href>/dav/pal/carol/</D:href></D:principal>"
          + "<D:grant><D:privilege><D:read/></D:privilege></D:grant></D:ace>" + ace + "</D:acl>", PRIVILEGES_OWNER));

      CollectionAcl acl = client.readAcl(endpoint, COLLECTION);

      assertTrue(acl.readable(), ace);
      assertFalse(acl.understood(), ace);
      assertTrue(acl.entries().isEmpty(), "no partial list is handed to a writer: " + ace);
    }
  }

  // ---- writing the ACL -----------------------------------------------------

  /**
   * The body carries every entry it is given — the one made outside eXo and
   * the one eXo adds — addressed to the pair's own collection with the ACL
   * method.
   *
   * @throws Exception when the body cannot be parsed
   */
  @Test
  void theWriteCarriesEveryEntryToThePairsCollection() throws Exception {
    AccessControlEntry carol = AccessControlEntry.readGrantTo("/dav/pal/carol%40stalwart.local/");
    AccessControlEntry bob = AccessControlEntry.readGrantTo(AccessControlEntry.principalHrefOf("/dav/pal/bob@stalwart.local"));
    answer(200, Map.of(), "");

    AclWriteResult result = client.writeAcl(endpoint, exoPair(), List.of(carol, bob));

    assertTrue(result.accepted());
    HttpRequest request = sent.get(0);
    assertEquals("ACL", request.method());
    assertEquals("http://cal.example.com" + COLLECTION, request.uri().toString());
    assertTrue(request.headers().firstValue("Content-Type").orElse("").startsWith("application/xml"));
    Document body = parseXml(bodyOf(request));
    NodeList hrefs = body.getElementsByTagNameNS("DAV:", "href");
    assertEquals(2, hrefs.getLength());
    assertEquals("/dav/pal/carol%40stalwart.local/", hrefs.item(0).getTextContent());
    assertEquals("/dav/pal/bob%40stalwart.local/", hrefs.item(1).getTextContent(), "Stalwart's own spelling of a principal");
    assertEquals(2, body.getElementsByTagNameNS("DAV:", "read").getLength());
    assertEquals(0, body.getElementsByTagNameNS("DAV:", "protected").getLength());
  }

  /**
   * What is written reads back as the same entries: pseudo-principals, a
   * property principal, an inverted principal, a deny, and a privilege of
   * another namespace all survive the round trip.
   *
   * @throws Exception never
   */
  @Test
  void aWrittenListReadsBackAsTheSameEntries() throws Exception {
    List<AccessControlEntry> entries = List.of(AccessControlEntry.readGrantTo("/dav/pal/carol%40stalwart.local/"),
                                               new AccessControlEntry(AcePrincipal.of(Kind.AUTHENTICATED), false, false,
                                                                      Set.of("{urn:ietf:params:xml:ns:caldav}read-free-busy"), false, null),
                                               new AccessControlEntry(AcePrincipal.href("/dav/pal/mallory/"), true, true,
                                                                      Set.of("{DAV:}write", "{DAV:}write-acl"), false, null),
                                               new AccessControlEntry(AcePrincipal.property("DAV:", "owner"), false, false,
                                                                      Set.of("{urn:x-vendor}manage"), false, null),
                                               new AccessControlEntry(AcePrincipal.of(Kind.ALL), false, false,
                                                                      Set.of("{DAV:}read"), false, null));
    answer(200, Map.of(), "");
    client.writeAcl(endpoint, exoPair(), entries);
    String written = bodyOf(sent.get(0));
    String acl = written.substring(written.indexOf("<d:acl"));

    answer(207, Map.of(), aclAnswer(acl.replaceFirst("<d:acl xmlns:d=\"DAV:\">", "<d:acl xmlns:d=\"DAV:\">"), PRIVILEGES_OWNER));
    CollectionAcl readBack = client.readAcl(endpoint, COLLECTION);

    assertTrue(readBack.understood(), readBack.reason());
    assertEquals(entries, readBack.entries());
  }

  /**
   * A protected or inherited entry is never sent: the server keeps them, and
   * sending one is a conflict. Nothing reaches the transport.
   *
   * @throws Exception never
   */
  @Test
  void aProtectedOrInheritedEntryIsNeverSent() throws Exception {
    AccessControlEntry protectedOwner = new AccessControlEntry(AcePrincipal.property("DAV:", "owner"), false, false,
                                                               Set.of("{DAV:}all"), true, null);
    AccessControlEntry inherited = new AccessControlEntry(AcePrincipal.of(Kind.ALL), false, true, Set.of("{DAV:}write"),
                                                          false, "/dav/calendars/alice/");

    assertThrows(IllegalArgumentException.class, () -> client.writeAcl(endpoint, exoPair(), List.of(protectedOwner)));
    assertThrows(IllegalArgumentException.class, () -> client.writeAcl(endpoint, exoPair(), List.of(inherited)));
    verify(transport, never()).send(any(HttpRequest.class), any());
  }

  /**
   * The write can address a collection eXo created for the pair's calendar
   * and nothing else: a materialised calendar, a pair whose href is not the
   * derived slug, and a pair with no anchor are refused before a request is
   * built.
   *
   * @throws Exception never
   */
  @Test
  void onlyACollectionEXoCreatedCanBeWritten() throws Exception {
    CalendarSync remote = exoPair();
    remote.setOrigin(SyncOrigin.REMOTE);
    CalendarSync elsewhere = exoPair();
    elsewhere.setRemoteHref("/dav/cal/alice%40stalwart.local/default/");
    CalendarSync unanchored = exoPair();
    unanchored.setLocalCalendarSyncUid(null);
    List<AccessControlEntry> grant = List.of(AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/"));

    assertThrows(IllegalArgumentException.class, () -> client.writeAcl(endpoint, remote, grant));
    assertThrows(IllegalArgumentException.class, () -> client.writeAcl(endpoint, elsewhere, grant));
    assertThrows(IllegalArgumentException.class, () -> client.writeAcl(endpoint, unanchored, grant));
    assertThrows(IllegalArgumentException.class, () -> client.writeAcl(endpoint, null, grant));
    verify(transport, never()).send(any(HttpRequest.class), any());
  }

  /**
   * RFC 3744 §7.1.1's refusal is answered, not thrown, with the privilege the
   * server said was missing.
   */
  @Test
  void aNeedPrivilegesRefusalNamesTheMissingPrivilege() {
    answer(403, Map.of(), """
        <?xml version="1.0" encoding="utf-8" ?>
        <D:error xmlns:D="DAV:">
          <D:need-privileges>
            <D:resource>
              <D:href>%s</D:href>
              <D:privilege><D:write-acl/></D:privilege>
            </D:resource>
          </D:need-privileges>
        </D:error>""".formatted(COLLECTION));

    AclWriteResult result = client.writeAcl(endpoint, exoPair(), List.of(AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/")));

    assertFalse(result.accepted());
    assertEquals(403, result.status());
    assertEquals(List.of("need-privileges"), result.preconditions());
    assertEquals(List.of("write-acl"), result.missingPrivileges());
  }

  /**
   * A precondition refusal — an unknown principal, a vendor precondition — is
   * answered with its names; a bare refusal with none.
   */
  @Test
  void preconditionRefusalsAreAnsweredWithTheirNames() {
    answer(409, Map.of(), """
        <?xml version="1.0" encoding="utf-8" ?>
        <D:error xmlns:D="DAV:"><D:recognized-principal/><S:max-shares xmlns:S="urn:stalwart"/></D:error>""");
    AclWriteResult conflict = client.writeAcl(endpoint, exoPair(), List.of(AccessControlEntry.readGrantTo("/dav/pal/nobody/")));
    assertEquals(409, conflict.status());
    assertEquals(List.of("recognized-principal", "{urn:stalwart}max-shares"), conflict.preconditions());
    assertTrue(conflict.missingPrivileges().isEmpty());

    answer(403, Map.of(), "Forbidden");
    AclWriteResult bare = client.writeAcl(endpoint, exoPair(), List.of(AccessControlEntry.readGrantTo("/dav/pal/nobody/")));
    assertEquals(403, bare.status());
    assertTrue(bare.preconditions().isEmpty());
  }

  /**
   * Credentials refused are an authentication failure; a gateway that could
   * not reach the server is unreachable.
   */
  @Test
  void aWriteThatReachesNothingThrows() {
    List<AccessControlEntry> grant = List.of(AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/"));
    answer(401, Map.of(), "");
    assertThrows(CalDavAuthenticationException.class, () -> client.writeAcl(endpoint, exoPair(), grant));
    answer(503, Map.of(), "");
    assertThrows(CalDavUnreachableException.class, () -> client.writeAcl(endpoint, exoPair(), grant));
  }

  /**
   * A principal path is written the way Stalwart spells it, and compared
   * however the server spells it back.
   */
  @Test
  void principalsAreWrittenEncodedAndComparedCanonically() {
    assertEquals("/dav/pal/bob%40stalwart.local/", AccessControlEntry.principalHrefOf("/dav/pal/bob@stalwart.local"));
    assertEquals("/dav/pal/jos%C3%A9/", AccessControlEntry.principalHrefOf("/dav/pal/josé/"));
    assertEquals("/dav/principals/__uids__/9F3C1A20-4D5E/", AccessControlEntry.principalHrefOf("/dav/principals/__uids__/9F3C1A20-4D5E"));
    AccessControlEntry spelledRaw = AccessControlEntry.readGrantTo("/dav/pal/bob@stalwart.local");
    assertTrue(spelledRaw.appliesTo("/dav/pal/bob@stalwart.local"));
    assertFalse(spelledRaw.appliesTo("/dav/pal/bob@stalwart.localhost"));
    assertNull(AccessControlEntry.readGrantTo("/p/").inheritedFrom());
  }

  // ---- helpers -------------------------------------------------------------

  /** The privilege set of an owner, for answers that need one. */
  private static final String PRIVILEGES_OWNER = "<D:current-user-privilege-set><D:privilege><D:all/></D:privilege>"
      + "<D:privilege><D:read-acl/></D:privilege><D:privilege><D:write-acl/></D:privilege></D:current-user-privilege-set>";

  /**
   * The pair of alice's exported calendar.
   *
   * @return a fresh pair
   */
  private static CalendarSync exoPair() {
    CalendarSync pair = new CalendarSync();
    pair.setId(3L);
    pair.setUserIdentityId(5L);
    pair.setServerId(1L);
    pair.setLocalCalendarSyncUid(ANCHOR);
    pair.setRemoteHref(COLLECTION);
    pair.setOrigin(SyncOrigin.EXO);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  /**
   * A multistatus answering the ACL PROPFIND with a granted list.
   *
   * @param acl the acl element's XML, prefix D for DAV
   * @param privileges the privilege set XML
   * @return the body
   */
  private static String aclAnswer(String acl, String privileges) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:"><D:response><D:href>%s</D:href><D:propstat><D:prop>%s%s</D:prop>
        <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>""".formatted(COLLECTION, acl, privileges);
  }

  /**
   * Queues one answer with single-valued headers.
   *
   * @param status the status
   * @param headers the headers
   * @param body the body
   */
  private void answer(int status, Map<String, String> headers, String body) {
    Map<String, List<String>> multi = new HashMap<>();
    headers.forEach((name, value) -> multi.put(name, List.of(value)));
    answers.add(response(status, multi, body));
  }

  /**
   * A canned response.
   *
   * @param status the status
   * @param headers the headers
   * @param body the body
   * @return the mocked response
   */
  @SuppressWarnings("unchecked")
  private static HttpResponse<InputStream> response(int status, Map<String, List<String>> headers, String body) {
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    lenient().when(response.statusCode()).thenReturn(status);
    lenient().when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    lenient().when(response.headers()).thenReturn(HttpHeaders.of(headers, (a, b) -> true));
    return response;
  }

  /**
   * A transcript from the test resources.
   *
   * @param name the file name
   * @return its text
   */
  private static String transcript(String name) {
    try (InputStream stream = HttpCalDavClientShareTest.class.getResourceAsStream("/caldav/transcripts/" + name)) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException | NullPointerException e) {
      throw new IllegalStateException("missing transcript " + name, e);
    }
  }

  /**
   * The body a request was built with, drained from its publisher.
   *
   * @param request the request
   * @return the body text
   */
  private static String bodyOf(HttpRequest request) {
    StringBuilder text = new StringBuilder();
    request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(ByteBuffer item) {
        byte[] bytes = new byte[item.remaining()];
        item.get(bytes);
        text.append(new String(bytes, StandardCharsets.UTF_8));
      }

      @Override
      public void onError(Throwable throwable) {
        throw new IllegalStateException(throwable);
      }

      @Override
      public void onComplete() {
        // drained
      }
    });
    return text.toString();
  }

  /**
   * Parses XML namespace-aware.
   *
   * @param xml the document
   * @return the DOM
   * @throws Exception when it is not XML
   */
  private static Document parseXml(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }
}
