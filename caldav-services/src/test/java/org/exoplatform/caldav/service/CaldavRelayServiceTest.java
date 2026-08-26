/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.caldav.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.model.CaldavProbeResult;
import org.exoplatform.caldav.model.CaldavRelayRequest;
import org.exoplatform.caldav.model.CaldavRelayedResponse;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.MirrorTargetKind;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The relay's security contract, as tests — every refusal is asserted
 * together with the absence of any outbound request, because the SSRF
 * boundary is precisely "a refused target is refused BEFORE a socket is
 * opened":
 * <ul>
 * <li>targets resolve only from the registry, by id — unknown, inactive and
 * mismatched targets are refused with nothing sent;</li>
 * <li>credentials are injected from the stored setting; the browser's own
 * Authorization and Cookie headers never reach the upstream;</li>
 * <li>methods and headers outside the allow-lists do not pass;</li>
 * <li>an upstream 401 travels as a 403 carrying the credentials code, never
 * as an eXo authentication failure;</li>
 * <li>advertised hrefs come back rewritten into the per-server relay
 * namespace — the structural fix for the /dav/ path collision.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
public class CaldavRelayServiceTest {

  private static final String    USERNAME     = "john";

  private static final long      IDENTITY_ID  = 42L;

  private static final long      SERVER_ID    = 5L;

  private static final String    RELAY_PREFIX = "/caldav/rest/dav/" + SERVER_ID;

  private static final String    SERVER_URL   = "http://dav.example.org:8888/dav/cal/{username}/";

  @Mock
  private CaldavServerService    caldavServerService;

  @Mock
  private CaldavConnectorStorage caldavConnectorStorage;

  @Mock
  private IdentityManager        identityManager;

  @Mock
  private HttpClient             httpClient;

  @Mock
  private Identity               identity;

  @InjectMocks
  private CaldavRelayService     caldavRelayService;

  /**
   * Clears every relay property a test may have tuned, so no cap or timeout
   * leaks into the next test.
   */
  @AfterEach
  public void restoreProperties() {
    System.clearProperty("exo.agenda.caldav.relay.maxBodyBytes");
  }

  /**
   * The declared server rows the tests play with.
   *
   * @param id row identifier
   * @param active whether users may connect to it
   * @return the registration
   */
  private CaldavServer server(long id, boolean active) {
    return new CaldavServer(id, "agenda.caldavCalendar." + id, "Server " + id, null, SERVER_URL, active, null, null, null,
                            null, true, null, null, null, null, null, MirrorTargetKind.DEDICATED_CALENDAR, null);
  }

  /**
   * Wires a user whose stored setting holds complete credentials referencing
   * the given server.
   *
   * @param serverId the referenced registration, or null for a legacy account
   */
  private void givenConnectedUser(Long serverId) {
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, USERNAME)).thenReturn(identity);
    when(identity.getId()).thenReturn(String.valueOf(IDENTITY_ID));
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("dav-john");
    setting.setPassword("dav-secret");
    setting.setServerId(serverId);
    when(caldavConnectorStorage.getCaldavSetting(IDENTITY_ID)).thenReturn(setting);
  }

  /**
   * Scripts the upstream answer the mocked transport serves.
   *
   * @param status upstream status
   * @param headers upstream headers
   * @param body upstream body bytes
   * @throws Exception never, the transport is mocked
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private void givenUpstreamAnswer(int status, Map<String, List<String>> headers, byte[] body) throws Exception {
    HttpResponse response = org.mockito.Mockito.mock(HttpResponse.class);
    // Lenient on purpose: a capped answer never reads the status or the
    // headers, and a credential rejection never reads the headers.
    org.mockito.Mockito.lenient().when(response.statusCode()).thenReturn(status);
    org.mockito.Mockito.lenient().when(response.body()).thenReturn(new ByteArrayInputStream(body));
    org.mockito.Mockito.lenient().when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
    when(httpClient.send(any(), any())).thenReturn(response);
  }

  /**
   * A relay request of the connected user, PROPFIND by default.
   *
   * @param method DAV verb to relay
   * @param davPath resource path on the upstream host
   * @param headers browser headers
   * @return the request
   */
  private CaldavRelayRequest relayRequest(String method, String davPath, Map<String, String> headers) {
    return new CaldavRelayRequest(USERNAME, SERVER_ID, method, davPath, null, headers,
                                  "<propfind/>".getBytes(StandardCharsets.UTF_8), RELAY_PREFIX);
  }

  /**
   * An unknown registration is refused as not found, and — the SSRF
   * assertion — nothing is ever sent anywhere.
   */
  @Test
  public void shouldRefuseUnknownServerWithoutAnyRequest() throws Exception {
    givenConnectedUser(SERVER_ID);
    when(caldavServerService.getServerById(SERVER_ID)).thenThrow(new ObjectNotFoundException("no such row"));

    assertThrows(ObjectNotFoundException.class,
                 () -> caldavRelayService.relay(relayRequest("PROPFIND", "/dav/cal/john/", Map.of())));

    verifyNoInteractions(httpClient);
  }

  /**
   * A declared but deactivated server is refused, nothing sent: deactivation
   * is the administrator's kill switch and the relay must honour it.
   */
  @Test
  public void shouldRefuseInactiveServerWithoutAnyRequest() throws Exception {
    givenConnectedUser(SERVER_ID);
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, false));
    when(caldavServerService.resolveServer(SERVER_ID)).thenReturn(server(SERVER_ID, false));

    IllegalAccessException refusal = assertThrows(IllegalAccessException.class,
                                                  () -> caldavRelayService.relay(relayRequest("PROPFIND", "/dav/cal/john/",
                                                                                              Map.of())));

    assertEquals(CaldavRelayService.SERVER_INACTIVE_MESSAGE, refusal.getMessage());
    verifyNoInteractions(httpClient);
  }

  /**
   * A server that exists but is NOT the one the user's account resolves to
   * is refused: relaying there would inject the stored credentials of one
   * server into requests aimed at another.
   */
  @Test
  public void shouldRefuseServerTheAccountIsNotConnectedTo() throws Exception {
    givenConnectedUser(3L);
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavServerService.resolveServer(3L)).thenReturn(server(3L, true));

    IllegalAccessException refusal = assertThrows(IllegalAccessException.class,
                                                  () -> caldavRelayService.relay(relayRequest("PROPFIND", "/dav/cal/john/",
                                                                                              Map.of())));

    assertEquals(CaldavRelayService.SERVER_MISMATCH_MESSAGE, refusal.getMessage());
    verifyNoInteractions(httpClient);
  }

  /**
   * A user with no stored credentials has nothing the relay could inject:
   * refused as a state, nothing sent.
   */
  @Test
  public void shouldRefuseWhenNoAccountIsConnected() {
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, USERNAME)).thenReturn(identity);
    when(identity.getId()).thenReturn(String.valueOf(IDENTITY_ID));
    when(caldavConnectorStorage.getCaldavSetting(IDENTITY_ID)).thenReturn(new CaldavUserSetting());

    IllegalStateException refusal = assertThrows(IllegalStateException.class,
                                                 () -> caldavRelayService.relay(relayRequest("PROPFIND", "/dav/cal/john/",
                                                                                             Map.of())));

    assertEquals(CaldavRelayService.NOT_CONNECTED_MESSAGE, refusal.getMessage());
    verifyNoInteractions(httpClient);
  }

  /**
   * Verbs outside the allow-list — including DAV ones no shipped flow
   * issues, like PROPPATCH or MOVE — are refused before anything else is
   * even looked at.
   */
  @Test
  public void shouldRefuseMethodsOutsideTheAllowList() {
    for (String method : List.of("MOVE", "PROPPATCH", "POST", "TRACE", "LOCK")) {
      IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                      () -> caldavRelayService.relay(relayRequest(method, "/dav/cal/john/",
                                                                                                  Map.of())));
      assertEquals(CaldavRelayService.METHOD_NOT_ALLOWED_MESSAGE, refusal.getMessage());
    }
    verifyNoInteractions(httpClient, caldavConnectorStorage, caldavServerService);
  }

  /**
   * A path outside the plain DAV character set — dot-segments most of all —
   * is refused before a URI is even built.
   */
  @Test
  public void shouldRefusePathsOutsideThePlainDavShape() {
    for (String path : List.of("/dav/../secrets", "/dav/a b", "/dav/x\\y", "no-leading-slash")) {
      IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                      () -> caldavRelayService.relay(relayRequest("PROPFIND", path,
                                                                                                  Map.of())));
      assertEquals(CaldavRelayService.INVALID_PATH_MESSAGE, refusal.getMessage());
    }
    verifyNoInteractions(httpClient);
  }

  /**
   * The forwarded request: aimed at the registry row's own host plus the
   * asked path, carrying the STORED credentials as Basic auth and the
   * allow-listed headers — while the browser's Authorization, Cookie and
   * Origin never leave the platform.
   */
  @Test
  public void shouldInjectStoredCredentialsAndAllowlistHeaders() throws Exception {
    givenConnectedUser(SERVER_ID);
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavServerService.resolveServer(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    givenUpstreamAnswer(207, Map.of("Content-Type", List.of("application/xml")), "<multistatus/>".getBytes());

    caldavRelayService.relay(relayRequest("PROPFIND", "/dav/cal/john/",
                                          Map.of("depth", "1",
                                                 "content-type", "application/xml",
                                                 "if-none-match", "*",
                                                 "authorization", "Basic ZXZpbDpldmls",
                                                 "cookie", "JSESSIONID=stolen",
                                                 "origin", "https://exo.example.org")));

    ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
    org.mockito.Mockito.verify(httpClient).send(sent.capture(), any());
    HttpRequest request = sent.getValue();
    assertEquals(URI.create("http://dav.example.org:8888/dav/cal/john/"), request.uri());
    assertEquals("PROPFIND", request.method());
    String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("dav-john:dav-secret".getBytes(StandardCharsets.UTF_8));
    assertEquals(Optional.of(expectedAuth), request.headers().firstValue("Authorization"));
    assertEquals(Optional.of("1"), request.headers().firstValue("depth"));
    assertEquals(Optional.of("*"), request.headers().firstValue("if-none-match"));
    assertTrue(request.headers().firstValue("cookie").isEmpty());
    assertTrue(request.headers().firstValue("origin").isEmpty());
  }

  /**
   * An upstream 401 means the STORED CalDAV credentials are stale — it must
   * reach the browser as a 403 carrying the credentials code, never as a 401
   * the platform (or the browser's own Basic dialog) would read as an eXo
   * authentication failure.
   */
  @Test
  public void shouldTranslateUpstreamCredentialRejection() throws Exception {
    givenConnectedUser(SERVER_ID);
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavServerService.resolveServer(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    givenUpstreamAnswer(401, Map.of("WWW-Authenticate", List.of("Basic realm=\"bm.basic.auth.v2\"")), new byte[0]);

    CaldavRelayedResponse response = caldavRelayService.relay(relayRequest("PROPFIND", "/dav/cal/john/", Map.of()));

    assertEquals(403, response.getStatus());
    assertEquals("caldav.error.credentials", response.getHeaders().get(CaldavRelayService.RELAY_CODE_HEADER));
    assertFalse(response.getHeaders().containsKey("www-authenticate"));
  }

  /**
   * Every other upstream status passes through untouched — the browser
   * connector's DAV logic (207 parsing, the 412 conflict discipline,
   * BlueMind's 500 for an absent object) depends on seeing the real one —
   * with ETag forwarded and Set-Cookie structurally dropped.
   */
  @Test
  public void shouldPassUpstreamStatusesAndSafeHeadersThrough() throws Exception {
    givenConnectedUser(SERVER_ID);
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavServerService.resolveServer(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    givenUpstreamAnswer(412,
                        Map.of("ETag", List.of("\"abc\""),
                               "Set-Cookie", List.of("upstream=1"),
                               "Content-Type", List.of("text/plain")),
                        "precondition".getBytes());

    CaldavRelayedResponse response = caldavRelayService.relay(relayRequest("PUT", "/dav/cal/john/x.ics",
                                                                           Map.of("if-match", "\"abc\"")));

    assertEquals(412, response.getStatus());
    assertEquals("\"abc\"", response.getHeaders().get("etag"));
    assertFalse(response.getHeaders().containsKey("set-cookie"));
    assertNull(response.getHeaders().get(CaldavRelayService.RELAY_CODE_HEADER));
    assertArrayEquals("precondition".getBytes(), response.getBody());
  }

  /**
   * Hrefs come back in relay space, whatever shape the server spelled them
   * in: a path-rooted href (BlueMind roots everything at /dav/) is prefixed,
   * an absolute URL on the upstream host is folded onto the prefix, and an
   * absolute URL on a FOREIGN host is left alone — rewriting it would make
   * the relay an open proxy toward hosts no administrator declared.
   */
  @Test
  public void shouldRewriteAdvertisedHrefsIntoRelaySpace() throws Exception {
    givenConnectedUser(SERVER_ID);
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavServerService.resolveServer(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    String multistatus = """
        <d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/dav/cal/john%40x/personal/</d:href></d:response>
          <d:response><D:href xmlns:D="DAV:">http://dav.example.org:8888/dav/cal/john/</D:href></d:response>
          <d:response><href xmlns="DAV:">https://elsewhere.example.net/dav/foreign/</href></d:response>
        </d:multistatus>""";
    givenUpstreamAnswer(207, Map.of("Content-Type", List.of("application/xml; charset=utf-8")),
                        multistatus.getBytes(StandardCharsets.UTF_8));

    CaldavRelayedResponse response = caldavRelayService.relay(relayRequest("PROPFIND", "/dav/cal/john/", Map.of()));

    String body = new String(response.getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("<d:href>" + RELAY_PREFIX + "/dav/cal/john%40x/personal/</d:href>"));
    assertTrue(body.contains(RELAY_PREFIX + "/dav/cal/john/</D:href>"));
    assertTrue(body.contains("<href xmlns=\"DAV:\">https://elsewhere.example.net/dav/foreign/</href>"));
  }

  /**
   * A Location header is rewritten by the same rule: a redirect must stay
   * inside relay space or the browser leaves the platform origin.
   */
  @Test
  public void shouldRewriteLocationHeaders() throws Exception {
    givenConnectedUser(SERVER_ID);
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavServerService.resolveServer(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    givenUpstreamAnswer(301, Map.of("Location", List.of("http://dav.example.org:8888/dav/cal/john/moved/")), new byte[0]);

    CaldavRelayedResponse response = caldavRelayService.relay(relayRequest("GET", "/dav/cal/john/x.ics", Map.of()));

    assertEquals(301, response.getStatus());
    assertEquals(RELAY_PREFIX + "/dav/cal/john/moved/", response.getHeaders().get("location"));
  }

  /**
   * An upstream answer over the configured cap never reaches the page: the
   * relay reports its own 502 with the tooLarge code instead of buffering
   * without bound.
   */
  @Test
  public void shouldCapOversizedUpstreamAnswers() throws Exception {
    System.setProperty("exo.agenda.caldav.relay.maxBodyBytes", "8");
    givenConnectedUser(SERVER_ID);
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavServerService.resolveServer(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    givenUpstreamAnswer(200, Map.of(), "much longer than eight bytes".getBytes());

    CaldavRelayedResponse response = caldavRelayService.relay(relayRequest("GET", "/dav/cal/john/x.ics", Map.of()));

    assertEquals(502, response.getStatus());
    assertEquals(CaldavRelayService.RESPONSE_TOO_LARGE_MESSAGE,
                 response.getHeaders().get(CaldavRelayService.RELAY_CODE_HEADER));
  }

  /**
   * A transport failure toward the upstream is the relay's own 502 carrying
   * the connection code — the honest "the platform could not reach the
   * CalDAV server", never an eXo error.
   */
  @Test
  public void shouldAnswerBadGatewayWhenUpstreamIsUnreachable() throws Exception {
    givenConnectedUser(SERVER_ID);
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavServerService.resolveServer(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(httpClient.send(any(), any())).thenThrow(new IOException("connection refused"));

    CaldavRelayedResponse response = caldavRelayService.relay(relayRequest("PROPFIND", "/dav/cal/john/", Map.of()));

    assertEquals(502, response.getStatus());
    assertEquals("caldav.error.connection", response.getHeaders().get(CaldavRelayService.RELAY_CODE_HEADER));
  }

  /**
   * The PUT body reaches the upstream byte for byte: an ICS mangled in
   * transit is a corrupted meeting on someone's phone.
   */
  @Test
  public void shouldRelayTheRequestBodyByteForByte() throws Exception {
    givenConnectedUser(SERVER_ID);
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavServerService.resolveServer(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    givenUpstreamAnswer(201, Map.of(), new byte[0]);
    byte[] ics = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n".getBytes(StandardCharsets.UTF_8);

    caldavRelayService.relay(new CaldavRelayRequest(USERNAME, SERVER_ID, "PUT", "/dav/cal/john/x.ics", null,
                                                    Map.of("content-type", "text/calendar"), ics, RELAY_PREFIX));

    ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
    org.mockito.Mockito.verify(httpClient).send(sent.capture(), any());
    assertEquals(Optional.of((long) ics.length), sent.getValue().bodyPublisher().map(p -> p.contentLength()));
  }

  /**
   * The connect-time probe classifies the upstream answers with the stable
   * codes the drawer translates: 207 accepted, 401/403 refused credentials,
   * anything else not-a-CalDAV-collection, transport failure unreachable.
   */
  @Test
  public void shouldClassifyProbeOutcomes() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    givenProbeAnswer(207);
    assertEquals(CaldavProbeResult.OK, caldavRelayService.probeAccount(SERVER_ID, "john", "pw").getResult());
    givenProbeAnswer(401);
    assertEquals(CaldavProbeResult.CREDENTIALS, caldavRelayService.probeAccount(SERVER_ID, "john", "pw").getResult());
    givenProbeAnswer(404);
    assertEquals(CaldavProbeResult.NOT_CALDAV, caldavRelayService.probeAccount(SERVER_ID, "john", "pw").getResult());
    when(httpClient.send(any(), any())).thenThrow(new IOException("unreachable"));
    assertEquals(CaldavProbeResult.CONNECTION, caldavRelayService.probeAccount(SERVER_ID, "john", "pw").getResult());
  }

  /**
   * EXO-89806. A gateway status is an unreachable server, not a wrongly
   * declared address: the measured failure was a proxy answering 502 in front
   * of a Stalwart that had banned the platform's source address, and the user
   * was told to have their administrator check the configured server URL —
   * which was correct all along.
   */
  @Test
  public void aGatewayRefusalIsAnUnreachableServerNotAWrongAddress() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    for (int status : new int[] { 502, 503, 504 }) {
      givenProbeAnswer(status);

      CaldavProbeResult outcome = caldavRelayService.probeAccount(SERVER_ID, "john", "pw");

      assertEquals(CaldavProbeResult.CONNECTION, outcome.getResult(), () -> "status " + status);
      // The status travels with the outcome, so the log and the support ticket
      // keep the raw fact the message paraphrases.
      assertEquals(status, outcome.getStatus());
    }
  }

  /**
   * The probe tries the TYPED credentials against the declared server's own
   * URL, username substituted — and only against a registry row.
   */
  @Test
  public void shouldProbeTheDeclaredServerWithTheTypedCredentials() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    givenProbeAnswer(207);

    caldavRelayService.probeAccount(SERVER_ID, "john", "pw");

    ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
    org.mockito.Mockito.verify(httpClient).send(sent.capture(), any());
    assertEquals(URI.create("http://dav.example.org:8888/dav/cal/john/"), sent.getValue().uri());
    String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("john:pw".getBytes(StandardCharsets.UTF_8));
    assertEquals(Optional.of(expectedAuth), sent.getValue().headers().firstValue("Authorization"));
    assertEquals(Optional.of("0"), sent.getValue().headers().firstValue("Depth"));
  }

  /**
   * A probe against a deactivated server is refused, nothing sent — a user
   * must not be able to make the platform knock on a server the
   * administrator switched off.
   */
  @Test
  public void shouldRefuseProbingAnInactiveServer() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, false));

    assertThrows(IllegalAccessException.class, () -> caldavRelayService.probeAccount(SERVER_ID, "john", "pw"));

    verifyNoInteractions(httpClient);
  }

  /**
   * Blank credentials, and a username that cannot be a single path segment
   * (a path-traversing one most of all), are refused before any request.
   */
  @Test
  public void shouldRefuseUnusableProbeCredentials() {
    assertThrows(IllegalArgumentException.class, () -> caldavRelayService.probeAccount(SERVER_ID, " ", "pw"));
    assertThrows(IllegalArgumentException.class, () -> caldavRelayService.probeAccount(SERVER_ID, "john", ""));
    assertThrows(IllegalArgumentException.class, () -> caldavRelayService.probeAccount(SERVER_ID, "../john", "pw"));
    assertThrows(IllegalArgumentException.class, () -> caldavRelayService.probeAccount(SERVER_ID, "a/b", "pw"));
    verifyNoInteractions(httpClient, caldavServerService);
  }

  /**
   * Scripts the status the probe transport answers.
   *
   * @param status upstream status
   * @throws Exception never, the transport is mocked
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private void givenProbeAnswer(int status) throws Exception {
    HttpResponse response = org.mockito.Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(httpClient.send(any(), any())).thenReturn(response);
  }
}
