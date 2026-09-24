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
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.lang3.StringUtils;
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
 * too loosely offers Apple sharing on a server that only looks like BlueMind,
 * where nothing tells eXo how a change could be read back. An entry
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


  /** Apple's CalendarServer namespace, which the sharing elements live in. */

  private static final String CALENDARSERVER_NS = "http://calendarserver.org/ns/";

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
   * ({@code caldav-webapp/src/test/js/fixtures/bluemind-principal.captured.xml:10}),
   * as its PROPFIND answers carry it. On the deployments observed, BlueMind's
   * {@code OPTIONS} came back as a bare 204 with no {@code DAV} header, answered
   * in front of its DAV server ({@code bluemind-options-bare-204.http}).
   */
  private static final String   BLUEMIND_DAV    =
                                             "1, access-control, calendar-access, calendar-schedule, calendar-auto-schedule, calendar-availability, inbox-availability, calendar-proxy, calendarserver-private-events, calendarserver-sharing, calendarserver-sharing-no-scheduling, calendar-query-extended, calendar-default-alarms, calendarserver-partstat-changes, extended-mkcol, calendarserver-principal-property-search, calendarserver-principal-search, calendarserver-home-sync, addressbook";

  /** Google's DAV header, live (design A.5). */
  private static final String   GOOGLE_DAV      = "1, calendar-access, calendar-schedule, calendar-auto-schedule, calendar-proxy";

  /** FRANCOIS's directory entry uid, the one captured in the BlueMind fixtures. */
  private static final String   BLUEMIND_OWNER  = "9F3C1A20-4D5E-4B7A-8C61-2E0D7A4B9C13";

  /** A calendar collection as BlueMind lays it out. */
  private static final String   BLUEMIND_COLLECTION = "/dav/calendars/__uids__/" + BLUEMIND_OWNER + "/exo-cal-" + ANCHOR + "/";

  /** eric/MEYER's directory entry uid, as in the DERIVED address-set fixture. */
  private static final String   ERIC_UID        = "6B2E4F10-8A3C-4D7E-9B51-0C2D4E6F8A17";

  /** eric/MEYER's BlueMind principal. */
  private static final String   ERIC_PRINCIPAL  = "/dav/principals/__uids__/" + ERIC_UID + "/";

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
                                          null, true, null, null, null, null, null, null, "personal", null, null));
    ConnectorCredentialsService credentials = mock(ConnectorCredentialsService.class);
    // The Personal provider carries what the user typed: it asks for user action, so
    // a refused credential is never retried on "fresh" material (EXO-89649).
    org.mockito.Mockito.lenient().when(credentials.requiresUserAction("personal")).thenReturn(true);
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
   * credentials, whose answer (the rig's live {@code dav} header and a
   * completed {@code allow}, {@code stalwart-options-collection.http}) carries
   * the DAV classes and the
   * methods, so nothing more is asked; it selects the RFC 3744 method, offered.
   */
  @Test
  void stalwartsOptionsSelectTheAclMethodWhichIsOffered() {
    answerFromTranscript("stalwart-options-collection.http");

    DavOptions options = client.capabilities(endpoint, COLLECTION);

    assertEquals(1, sent.size(), "an OPTIONS carrying a DAV header is enough: no PROPFIND");
    assertTrue(options.advertises("calendar-no-timezone"), "the live header is read whole");
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
   * and read without the collection's path it is not offered — even were the
   * ACL method allowed.
   */
  @Test
  void bluemindsCapturedHeaderSelectsCalendarServerSharingWhichIsNotOffered() {
    DavOptions options = DavOptions.of(List.of(BLUEMIND_DAV), List.of(STALWART_ALLOW));

    assertTrue(options.advertises("access-control"), "the capture does advertise RFC 3744: the rule order is what keeps it off");
    assertEquals(SharingMechanism.CALENDARSERVER_SHARE, SharingMechanism.of(options));
    assertFalse(SharingMechanism.of(options).isOffered());
  }

  /**
   * BlueMind is recognised by its header and its collection layout together
   * (EXO-90253): the captured header on {@code /dav/calendars/__uids__/<owner
   * uid>/<container>/} selects BlueMind's sharing, offered. Either alone is
   * not enough — the header on another server's path, Apple CalendarServer's
   * {@code __uids__} layout without BlueMind's {@code /dav} root, a resource
   * under the collection, and the path under a server advertising RFC 3744
   * each keep their own mechanism.
   */
  @Test
  void blueMindIsRecognisedByItsHeaderAndItsCollectionPathTogether() {
    DavOptions bluemind = DavOptions.of(List.of(BLUEMIND_DAV), List.of());
    DavOptions stalwart = DavOptions.of(List.of(STALWART_DAV), List.of(STALWART_ALLOW));

    assertEquals(SharingMechanism.BLUEMIND_SHARE, SharingMechanism.of(bluemind, BLUEMIND_COLLECTION));
    assertTrue(SharingMechanism.of(bluemind, BLUEMIND_COLLECTION).isOffered());
    assertEquals(SharingMechanism.BLUEMIND_SHARE,
                 SharingMechanism.of(DavOptions.of(List.of(BLUEMIND_DAV),
                                                   List.of("ACL, COPY, DELETE, GET, HEAD, LOCK, MKCOL, OPTIONS, PROPFIND, PROPPATCH, PUT, REPORT, UNLOCK")),
                                     BLUEMIND_COLLECTION),
                 "an OPTIONS reaching BlueMind's own OptionsProtocol lists ACL too; calendar-proxy and the vendor sharing rule both keep it BlueMind's");
    assertEquals(SharingMechanism.BLUEMIND_SHARE,
                 SharingMechanism.of(bluemind, "https://bm.example.com" + StringUtils.stripEnd(BLUEMIND_COLLECTION, "/")),
                 "an absolute href and a missing trailing slash are the same collection");

    assertEquals(SharingMechanism.CALENDARSERVER_SHARE, SharingMechanism.of(bluemind, COLLECTION));
    assertEquals(SharingMechanism.CALENDARSERVER_SHARE, SharingMechanism.of(bluemind, "/calendars/__uids__/" + BLUEMIND_OWNER + "/work/"));
    assertEquals(SharingMechanism.CALENDARSERVER_SHARE, SharingMechanism.of(bluemind, BLUEMIND_COLLECTION + "event.ics"));
    assertEquals(SharingMechanism.CALENDARSERVER_SHARE, SharingMechanism.of(bluemind, null));
    assertEquals(SharingMechanism.CALENDARSERVER_SHARE,
                 SharingMechanism.of(DavOptions.of(List.of("1, access-control, resource-sharing"), List.of("ACL")), BLUEMIND_COLLECTION),
                 "another vendor protocol on BlueMind's path is not BlueMind's");
    assertEquals(SharingMechanism.WEBDAV_ACL, SharingMechanism.of(stalwart, BLUEMIND_COLLECTION));
    assertEquals(SharingMechanism.NONE, SharingMechanism.of(null, BLUEMIND_COLLECTION));
    for (SharingMechanism mechanism : SharingMechanism.values()) {
      assertEquals(mechanism == SharingMechanism.WEBDAV_ACL || mechanism == SharingMechanism.BLUEMIND_SHARE, mechanism.isOffered(),
                   mechanism.name());
    }
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

    DavOptions options = client.capabilities(endpoint, COLLECTION);

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
   * BlueMind: the OPTIONS answer is a bare 204, no {@code DAV}
   * and no {@code Allow} header (the rig, 2026-09-14,
   * {@code bluemind-options-bare-204.http}), which alone selects nothing. The
   * classes are then read from the {@code DAV} header of a depth-0 PROPFIND of
   * the same collection, with the same credentials — the header BlueMind's DAV
   * server sets on every PROPFIND answer, here as CAPTURED on its principal
   * (the {@code calendar-home-set} discovery,
   * {@code bluemind-calendar-home.captured.xml}) — and select BlueMind's sharing, offered, with no method taken from the
   * PROPFIND.
   *
   * @throws Exception when a fixture cannot be read
   */
  @Test
  void blueMindsBare204OptionsIsCompletedFromItsPropfindDavHeader() throws Exception {
    answerFromTranscript("bluemind-options-bare-204.http");
    answers.add(response(207,
                         Map.of("dav", List.of(capturedBlueMindPropfindDavHeader()), "server", List.of("nginx")),
                         "<d:multistatus xmlns:d=\"DAV:\"/>"));

    DavOptions capabilities = client.capabilities(endpoint, BLUEMIND_COLLECTION);

    assertEquals(2, sent.size());
    assertEquals("OPTIONS", sent.get(0).method());
    HttpRequest propfind = sent.get(1);
    assertEquals("PROPFIND", propfind.method());
    assertEquals("0", propfind.headers().firstValue("Depth").orElse(null));
    assertEquals("http://cal.example.com" + BLUEMIND_COLLECTION, propfind.uri().toString());
    assertEquals(AUTHORIZATION, propfind.headers().firstValue("Authorization").orElse(null));
    assertTrue(capabilities.advertises("calendarserver-sharing"));
    assertTrue(capabilities.allowedMethods().isEmpty(), "methods come from OPTIONS only");
    assertEquals(SharingMechanism.BLUEMIND_SHARE, SharingMechanism.of(capabilities, BLUEMIND_COLLECTION));
    assertTrue(SharingMechanism.of(capabilities, BLUEMIND_COLLECTION).isOffered());
  }

  /**
   * A PROPFIND answer never supplies methods, even one carrying an
   * {@code Allow} header: the RFC 3744 method is selected only where OPTIONS
   * lists {@code ACL}. A PROPFIND answer without a {@code DAV} header leaves
   * nothing advertised; a refused one is classified like any read.
   */
  @Test
  void methodsAreNeverTakenFromAPropfindAnswer() {
    answerFromTranscript("bluemind-options-bare-204.http");
    answers.add(response(207, Map.of("dav", List.of("1, access-control, calendar-access"), "allow", List.of("PROPFIND, ACL")), ""));
    DavOptions fromPropfind = client.capabilities(endpoint, COLLECTION);
    assertTrue(fromPropfind.advertises("access-control"));
    assertFalse(fromPropfind.allows("ACL"));
    assertEquals(SharingMechanism.NONE, SharingMechanism.of(fromPropfind, COLLECTION));

    answerFromTranscript("bluemind-options-bare-204.http");
    answers.add(response(207, Map.of(), ""));
    DavOptions nothing = client.capabilities(endpoint, BLUEMIND_COLLECTION);
    assertTrue(nothing.davTokens().isEmpty());
    assertEquals(SharingMechanism.NONE, SharingMechanism.of(nothing, BLUEMIND_COLLECTION),
                 "BlueMind's bare 204 with no DAV header anywhere offers nothing: the classes are what selects it");

    answerFromTranscript("bluemind-options-bare-204.http");
    answers.add(response(401, Map.of(), ""));
    assertThrows(CalDavAuthenticationException.class, () -> client.capabilities(endpoint, COLLECTION));
  }

  /**
   * OPTIONS is a read: a 401 is a credential refusal, a 404 a plain failure.
   */
  @Test
  void optionsRefusalsAreClassified() {
    answer(401, Map.of(), "");
    assertThrows(CalDavAuthenticationException.class, () -> client.capabilities(endpoint, COLLECTION));
    answer(404, Map.of(), "");
    assertThrows(CalDavException.class, () -> client.capabilities(endpoint, COLLECTION));
  }

  // ---- BlueMind: the sharee's address and CS:share ------------------------

  /**
   * A sharee's addresses are one PROPFIND of depth 0 on their principal, and
   * come back every href of the set in the server's order — BlueMind's
   * principal path and urn:uuid before the mailto the share needs.
   */
  @Test
  void aPrincipalsCalendarUserAddressesAreReadInOrder() {
    answer(207, Map.of(), transcript("bluemind-propfind-calendar-user-address-set-eric.xml"));

    List<String> addresses = client.readCalendarUserAddresses(endpoint, ERIC_PRINCIPAL);

    HttpRequest request = sent.get(0);
    assertEquals("PROPFIND", request.method());
    assertEquals("0", request.headers().firstValue("Depth").orElse(null));
    assertEquals("http://cal.example.com" + ERIC_PRINCIPAL, request.uri().toString());
    assertTrue(bodyOf(request).contains("calendar-user-address-set"), bodyOf(request));
    assertEquals(List.of(ERIC_PRINCIPAL, "urn:uuid:" + ERIC_UID, "mailto:eric.meyer@bm.example.com"), addresses);
  }

  /**
   * A principal whose set is not found states no address.
   */
  @Test
  void aPrincipalWithoutAnAddressSetStatesNone() {
    answer(207, Map.of(), """
        <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav"><d:response><d:href>%s</d:href>
        <d:propstat><d:prop><cal:calendar-user-address-set/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
        </d:response></d:multistatus>""".formatted(ERIC_PRINCIPAL));

    assertTrue(client.readCalendarUserAddresses(endpoint, ERIC_PRINCIPAL).isEmpty());
  }

  /**
   * A share is one {@code POST} of {@code CS:share} to the collection eXo
   * created, with the owner's credentials, whose elements are exactly those
   * of the DERIVED body BlueMind's handler reads as read-only; a stop is the
   * remove body. The 200 BlueMind answers whatever happened is returned as a
   * status and nothing more.
   *
   * @throws Exception when a body is not XML
   */
  @Test
  void aShareIsOneCsSharePostToTheCollectionEXoCreated() throws Exception {
    answer(200, Map.of(), "");
    answer(200, Map.of(), "");

    assertEquals(200, client.postCalendarServerShare(endpoint, exoPair(), "eric.meyer@bm.example.com", false));
    assertEquals(200, client.postCalendarServerShare(endpoint, exoPair(), "eric.meyer@bm.example.com", true));

    for (HttpRequest request : sent) {
      assertEquals("POST", request.method());
      assertEquals("http://cal.example.com" + COLLECTION, request.uri().toString());
      assertEquals(AUTHORIZATION, request.headers().firstValue("Authorization").orElse(null));
      assertFalse(bodyOf(request).toLowerCase().contains("multiput"), "BlueMind routes a body naming multiput elsewhere");
    }
    assertEquals(shapeOf(parseXml(transcript("bluemind-post-cs-share-set-read.xml"))), shapeOf(parseXml(bodyOf(sent.get(0)))));
    assertEquals(shapeOf(parseXml(transcript("bluemind-post-cs-share-remove.xml"))), shapeOf(parseXml(bodyOf(sent.get(1)))));
  }

  /**
   * A "can edit" share is the same {@code POST} with {@code CS:read-write}
   * (EXO-90378), and a "can view" one is the body above: the element comes
   * from a boolean and can be nothing else, which is what keeps a level added
   * later away from BlueMind's permissive fallback — {@code SharingProtocol}
   * maps <b>anything</b> that is not {@code read} to {@code Verb.Write}.
   * A remove carries no access element at either level, because
   * {@code CS:remove} deletes the sharee's entry whatever verb it held.
   *
   * @throws Exception when a body is not XML
   */
  @Test
  void anEditShareIsTheSamePostWithReadWrite() throws Exception {
    answer(200, Map.of(), "");
    answer(200, Map.of(), "");
    answer(200, Map.of(), "");

    client.postCalendarServerShare(endpoint, exoPair(), "eric.meyer@bm.example.com", false, true);
    client.postCalendarServerShare(endpoint, exoPair(), "eric.meyer@bm.example.com", false, false);
    client.postCalendarServerShare(endpoint, exoPair(), "eric.meyer@bm.example.com", true, true);

    assertEquals(shapeOf(parseXml(transcript("bluemind-post-cs-share-set-read-write.xml"))),
                 shapeOf(parseXml(bodyOf(sent.get(0)))));
    assertEquals(shapeOf(parseXml(transcript("bluemind-post-cs-share-set-read.xml"))),
                 shapeOf(parseXml(bodyOf(sent.get(1)))));
    assertEquals(shapeOf(parseXml(transcript("bluemind-post-cs-share-remove.xml"))),
                 shapeOf(parseXml(bodyOf(sent.get(2)))));
    // Exactly one access element per set body, and never both
    assertEquals(1, parseXml(bodyOf(sent.get(0))).getElementsByTagNameNS(CALENDARSERVER_NS, "read-write").getLength());
    assertEquals(0, parseXml(bodyOf(sent.get(0))).getElementsByTagNameNS(CALENDARSERVER_NS, "read").getLength());
    assertEquals(1, parseXml(bodyOf(sent.get(1))).getElementsByTagNameNS(CALENDARSERVER_NS, "read").getLength());
    assertEquals(0, parseXml(bodyOf(sent.get(1))).getElementsByTagNameNS(CALENDARSERVER_NS, "read-write").getLength());
    assertEquals(0, parseXml(bodyOf(sent.get(2))).getElementsByTagNameNS(CALENDARSERVER_NS, "read-write").getLength());
  }

  /**
   * Nothing but a mail address is ever named, and nothing but a shareable
   * collection is ever addressed: markup smuggled in an address, a value
   * without {@code @}, a hidden share and the meetings mirror are refused
   * before any request.
   */
  @Test
  void aShareNamesOnlyAMailAddressOnAShareableCollection() {
    CalendarSync hidden = importedPair(BLUEMIND_COLLECTION);
    hidden.setStatus(CalendarSyncStatus.HIDDEN_SHARE);
    CalendarSync mirror = importedPair("/dav/calendars/__uids__/" + BLUEMIND_OWNER + "/exo-meetings/");

    assertThrows(IllegalArgumentException.class,
                 () -> client.postCalendarServerShare(endpoint, exoPair(), "eric@bm.example.com</D:href><CS:read-write/>", false));
    assertThrows(IllegalArgumentException.class, () -> client.postCalendarServerShare(endpoint, exoPair(), "eric", false));
    assertThrows(IllegalArgumentException.class, () -> client.postCalendarServerShare(endpoint, exoPair(), null, false));
    assertThrows(IllegalArgumentException.class,
                 () -> client.postCalendarServerShare(endpoint, hidden, "eric.meyer@bm.example.com", false));
    assertThrows(IllegalArgumentException.class,
                 () -> client.postCalendarServerShare(endpoint, mirror, "eric.meyer@bm.example.com", false));
    assertTrue(sent.isEmpty());
  }

  /**
   * A write refused is classified: 401 is the credentials, 403 the server's
   * refusal, anything else a failure.
   */
  @Test
  void aRefusedShareIsClassified() {
    answer(401, Map.of(), "");
    assertThrows(CalDavAuthenticationException.class,
                 () -> client.postCalendarServerShare(endpoint, exoPair(), "eric.meyer@bm.example.com", false));
    answer(403, Map.of(), "");
    assertThrows(CalDavForbiddenException.class,
                 () -> client.postCalendarServerShare(endpoint, exoPair(), "eric.meyer@bm.example.com", false));
    answer(500, Map.of(), "");
    CalDavException failed = assertThrows(CalDavException.class,
                                          () -> client.postCalendarServerShare(endpoint, exoPair(), "eric.meyer@bm.example.com", false));
    assertFalse(failed instanceof CalDavAuthenticationException);
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
   * An edit grant (EXO-90378) goes on the wire as one {@code DAV:ace} granting
   * {@code DAV:read} and {@code DAV:write} to one principal — the shape that
   * tells a grant eXo wrote from a right given through the server's own
   * interface — and reads back as the same entry.
   *
   * @throws Exception when the body cannot be parsed
   */
  @Test
  void anEditGrantWritesReadAndWriteAndReadsBack() throws Exception {
    AccessControlEntry bob = AccessControlEntry.editGrantTo("/dav/pal/bob%40stalwart.local/");
    answer(200, Map.of(), "");

    assertTrue(client.writeAcl(endpoint, exoPair(), List.of(bob)).accepted());

    Document body = parseXml(bodyOf(sent.get(0)));
    assertEquals(1, body.getElementsByTagNameNS("DAV:", "ace").getLength(), "one entry, not one per privilege");
    assertEquals(1, body.getElementsByTagNameNS("DAV:", "grant").getLength());
    assertEquals(0, body.getElementsByTagNameNS("DAV:", "deny").getLength());
    assertEquals(1, body.getElementsByTagNameNS("DAV:", "read").getLength());
    assertEquals(1, body.getElementsByTagNameNS("DAV:", "write").getLength());
    assertEquals(0, body.getElementsByTagNameNS("DAV:", "write-acl").getLength(), "nothing beyond writing events");

    String written = bodyOf(sent.get(0));
    answer(207, Map.of(), aclAnswer(written.substring(written.indexOf("<d:acl")), PRIVILEGES_OWNER));
    CollectionAcl readBack = client.readAcl(endpoint, COLLECTION);

    assertTrue(readBack.understood(), readBack.reason());
    assertEquals(List.of(bob), readBack.entries());
    assertTrue(readBack.entries().get(0).grantsEditOnly(), "and is recognised as an eXo edit grant");
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
   * The write addresses a collection eXo created for the pair's calendar, or an
   * active imported one that is not the meetings mirror — whose ownership the
   * share service confirms — and nothing else. Refused before a request is
   * built: an eXo pair whose href is not the derived slug or has no anchor, a
   * hidden share, a paused import, the meetings mirror as an import (in any
   * spelling of its slug), a mirror
   * pair, an unanchored import, and no pair. Deleting keeps the eXo-created
   * rule: an imported collection is never deleted.
   *
   * @throws Exception never
   */
  @Test
  void onlyACollectionEXoCreatedOrAnActiveImportedOneCanBeWritten() throws Exception {
    CalendarSync elsewhere = exoPair();
    elsewhere.setRemoteHref("/dav/cal/alice%40stalwart.local/default/");
    CalendarSync unanchored = exoPair();
    unanchored.setLocalCalendarSyncUid(null);
    CalendarSync hidden = importedPair("/dav/cal/bob%40stalwart.local/default/");
    hidden.setStatus(CalendarSyncStatus.HIDDEN_SHARE);
    CalendarSync paused = importedPair("/dav/cal/alice%40stalwart.local/default/");
    paused.setStatus(CalendarSyncStatus.PAUSED);
    CalendarSync mirrorImport = importedPair("/dav/cal/alice%40stalwart.local/exo-meetings/");
    CalendarSync mirrorEncoded = importedPair("/dav/cal/alice%40stalwart.local/exo%2Dmeetings/");
    CalendarSync mirror = importedPair("/dav/cal/alice%40stalwart.local/default/");
    mirror.setOrigin(SyncOrigin.MIRROR);
    CalendarSync unanchoredImport = importedPair("/dav/cal/alice%40stalwart.local/default/");
    unanchoredImport.setLocalCalendarSyncUid(null);
    List<AccessControlEntry> grant = List.of(AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/"));

    for (CalendarSync refused : java.util.Arrays.asList(elsewhere, unanchored, hidden, paused, mirrorImport, mirrorEncoded, mirror, unanchoredImport, null)) {
      assertThrows(IllegalArgumentException.class, () -> client.writeAcl(endpoint, refused, grant), String.valueOf(refused));
    }
    verify(transport, never()).send(any(HttpRequest.class), any());

    CalendarSync imported = importedPair("/dav/cal/alice%40stalwart.local/default/");
    answer(200, Map.of(), "");
    assertTrue(client.writeAcl(endpoint, imported, grant).accepted());
    assertEquals("http://cal.example.com/dav/cal/alice%40stalwart.local/default/", sent.get(0).uri().toString());
    assertThrows(IllegalArgumentException.class, () -> client.deleteCollection(endpoint, imported));
    assertEquals(1, sent.size(), "an imported collection is never deleted");
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
   * An active imported pair of alice's.
   *
   * @param href the collection it binds
   * @return a fresh pair
   */
  private static CalendarSync importedPair(String href) {
    CalendarSync pair = exoPair();
    pair.setOrigin(SyncOrigin.REMOTE);
    pair.setRemoteHref(href);
    return pair;
  }

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
   * Queues the answer an {@code .http} transcript records: its status line,
   * its headers and its body, the {@code #} note lines removed.
   *
   * @param name the transcript file name
   */
  private void answerFromTranscript(String name) {
    List<String> lines = transcript(name).lines().filter(line -> !line.startsWith("#")).toList();
    int status = Integer.parseInt(lines.get(0).trim().split("\\s+")[1]);
    Map<String, List<String>> headers = new HashMap<>();
    int index = 1;
    for (; index < lines.size() && !lines.get(index).isBlank(); index++) {
      String line = lines.get(index);
      int colon = line.indexOf(':');
      headers.computeIfAbsent(line.substring(0, colon).trim(), key -> new ArrayList<>()).add(line.substring(colon + 1).trim());
    }
    String body = index + 1 < lines.size() ? String.join("\n", lines.subList(index + 1, lines.size())) : "";
    answers.add(response(status, headers, body));
  }

  /**
   * The {@code dav} header BlueMind's DAV server sent on a PROPFIND of its
   * principal (the {@code calendar-home-set} discovery), from the CAPTURED
   * transcript the webapp's tests read.
   *
   * @return the header value, whole
   * @throws IOException when the capture cannot be read
   */
  private static String capturedBlueMindPropfindDavHeader() throws IOException {
    return Files.readAllLines(Path.of("../caldav-webapp/src/test/js/fixtures/bluemind-calendar-home.captured.xml"), StandardCharsets.UTF_8)
                .stream()
                .filter(line -> line.regionMatches(true, 0, "dav:", 0, 4))
                .map(line -> line.substring(4).trim())
                .findFirst()
                .orElseThrow(() -> new IOException("no dav header in the BlueMind capture"));
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
   * What a SAX handler matching local names sees of a document, as BlueMind's
   * {@code SharingQuerySaxHandler} does: each element's namespace, local name
   * and trimmed own text, in document order; comments and whitespace left out.
   *
   * @param document the document
   * @return one line per element
   */
  private static List<String> shapeOf(Document document) {
    List<String> shape = new ArrayList<>();
    NodeList all = document.getElementsByTagNameNS("*", "*");
    for (int i = 0; i < all.getLength(); i++) {
      org.w3c.dom.Element element = (org.w3c.dom.Element) all.item(i);
      StringBuilder text = new StringBuilder();
      for (org.w3c.dom.Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE) {
          text.append(child.getNodeValue());
        }
      }
      shape.add(element.getNamespaceURI() + "|" + element.getLocalName() + "|" + text.toString().trim());
    }
    return shape;
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
