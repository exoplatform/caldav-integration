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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.model.RemoteProvider;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
import org.exoplatform.agenda.service.AgendaUserSettingsService;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavProviderMissingException;
import org.exoplatform.caldav.exception.ManagedConnectionLockedException;
import org.exoplatform.caldav.model.CaldavManagedRefusal;
import org.exoplatform.caldav.model.CaldavProbeResult;
import org.exoplatform.caldav.model.CaldavRelayRequest;
import org.exoplatform.caldav.model.CaldavRelayedResponse;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.MirrorTargetKind;
import org.exoplatform.caldav.service.CaldavConnectorService;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.provider.CaldavCredentialsResolver;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.connector.credentials.ConnectorTargetRefusedException;
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

  /** The provider the registry row is configured with. */
  private static final String    PROVIDER     = "personal";

  /**
   * The header the provider answers. Deliberately not Basic, and derivable
   * from neither the stored account nor the stored password: an implementation
   * that went back to assembling Basic auth from the setting would produce
   * something else, and the assertion would say so.
   */
  private static final String    PROVIDED_AUTH = "Bearer produced-by-the-provider";

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

  @Mock
  private CaldavCredentialsResolver caldavCredentialsResolver;

  @Mock
  private CaldavConnectorService    caldavConnectorService;

  @Mock
  private AgendaUserSettingsService agendaUserSettingsService;

  @Mock
  private AgendaRemoteEventService  agendaRemoteEventService;

  @Mock
  private CaldavManagedModeService  caldavManagedModeService;

  /**
   * Managed mode governs nobody unless a test says so. Stated, because a mocked
   * {@code Long} answers 0, not null: left alone, every user would be governed by
   * registration 0.
   */
  @BeforeEach
  void managedModeGovernsNobodyByDefault() throws Exception {
    org.mockito.Mockito.lenient().when(caldavManagedModeService.checkUserMayChangeConnection(any(), any())).thenReturn(null);
  }

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

  /** Agenda's own switch for the server's connector, as its connector settings hold it. */
  private void givenAgendaConnector(boolean enabled) {
    when(agendaRemoteEventService.getRemoteProviders())
                                 .thenReturn(List.of(new RemoteProvider(0, "agenda.caldavCalendar." + SERVER_ID, null, null, enabled, false)));
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
                            null, true, null, null, null, null, null, MirrorTargetKind.DEDICATED_CALENDAR, PROVIDER, null, null);
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
    org.mockito.Mockito.lenient().when(caldavCredentialsResolver.authorization(any(), any(), any())).thenReturn(PROVIDED_AUTH);
    // "Connected" is now one definition, in CaldavServerService. This helper declares
    // a typed account, so the mock answers the rule these tests were written against.
    org.mockito.Mockito.lenient().when(caldavServerService.isConnected(any())).thenReturn(true);
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
    assertEquals(Optional.of(PROVIDED_AUTH), request.headers().firstValue("Authorization"));
    // Asked for the eXo login, on that server row's own provider — never for
    // the DAV account, which is the provider's business to derive.
    org.mockito.Mockito.verify(caldavCredentialsResolver).authorization(SERVER_ID, PROVIDER, USERNAME);
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
    // The provider is told once that its material was refused, so a caching one
    // forgets it; the client's half of this is pinned in HttpCalDavClientTest.
    org.mockito.Mockito.verify(caldavCredentialsResolver, org.mockito.Mockito.times(1)).invalidate(SERVER_ID, PROVIDER, USERNAME);
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
   * Typed credentials for a server whose credentials provider is not installed are
   * not sent anywhere: the probe answers that the server is not usable yet, so the
   * drawer stores nothing that would show as connected and never synchronise. The
   * same server probes as before once its provider is installed.
   */
  @Test
  public void probesNothingOnAServerWhoseProviderIsNotInstalled() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavCredentialsResolver.isProviderMissing(PROVIDER)).thenReturn(true);

    assertEquals(CaldavProbeResult.SERVER_NOT_USABLE, caldavRelayService.probeAccount(SERVER_ID, "john", "pw").getResult());
    verifyNoInteractions(httpClient);

    when(caldavCredentialsResolver.isProviderMissing(PROVIDER)).thenReturn(false);
    givenProbeAnswer(207);
    assertEquals(CaldavProbeResult.OK, caldavRelayService.probeAccount(SERVER_ID, "john", "pw").getResult());
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

  private void givenOneClickConnection() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, USERNAME)).thenReturn(identity);
    when(identity.getId()).thenReturn(String.valueOf(IDENTITY_ID));
    when(caldavCredentialsResolver.requiresUserAction(PROVIDER)).thenReturn(false);
    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn("eric@bm.example.org");
    when(caldavCredentialsResolver.authorization(SERVER_ID, PROVIDER, USERNAME)).thenReturn(PROVIDED_AUTH);
    givenAgendaConnector(true);
    givenProbeAnswer(207);
  }

  /** A connection managed mode makes at login is marked as such. */
  @Test
  public void aConnectionManagedModeMakesIsMarked() throws Exception {
    givenOneClickConnection();

    caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME, true, () -> true);

    verify(caldavConnectorStorage).markConnectedByManagedMode(IDENTITY_ID, true);
  }

  /** A one-click connection the user makes clears the mark: it is their own choice. */
  @Test
  public void aOneClickConnectionTheUserMakesClearsTheMark() throws Exception {
    givenOneClickConnection();

    caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME);

    verify(caldavConnectorStorage).markConnectedByManagedMode(IDENTITY_ID, false);
  }

  /** A refused probe records nothing, and marks nothing. */
  @Test
  public void aRefusedConnectionMarksNothing() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavCredentialsResolver.requiresUserAction(PROVIDER)).thenReturn(false);
    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn("eric@bm.example.org");
    when(caldavCredentialsResolver.authorization(SERVER_ID, PROVIDER, USERNAME)).thenReturn(PROVIDED_AUTH);
    givenAgendaConnector(true);
    givenProbeAnswer(403);

    caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME, true, () -> true);

    verify(caldavConnectorStorage, never()).markConnectedByManagedMode(anyLong(), anyBoolean());
  }

  /**
   * EXO-91017. A connection managed mode makes, whose provider says the remote
   * authority refused the account it named for the user, records the refusal against
   * the registration and that account, and still fails as before; nothing is probed.
   */
  @Test
  public void aManagedConnectionWhoseTargetIsRefusedRecordsTheRefusal() throws Exception {
    givenTargetRefusedByTheProvider();

    assertThrows(IllegalStateException.class, () -> caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME, true, () -> true));

    verify(caldavConnectorStorage).saveManagedRefusal(IDENTITY_ID, new CaldavManagedRefusal(SERVER_ID, "eric@bm.example.org"));
    verifyNoInteractions(httpClient);
  }

  /** EXO-91017. The click of a user managed mode governs is a connection managed mode makes. */
  @Test
  public void aGovernedUsersClickWhoseTargetIsRefusedRecordsTheRefusal() throws Exception {
    givenTargetRefusedByTheProvider();
    when(caldavManagedModeService.checkUserMayChangeConnection(USERNAME, SERVER_ID)).thenReturn(SERVER_ID);

    assertThrows(IllegalStateException.class, () -> caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME));

    verify(caldavConnectorStorage).saveManagedRefusal(IDENTITY_ID, new CaldavManagedRefusal(SERVER_ID, "eric@bm.example.org"));
  }

  /** EXO-91017. A connection the user makes outside managed mode records no refusal. */
  @Test
  public void aConnectionOutsideManagedModeWhoseTargetIsRefusedRecordsNothing() throws Exception {
    givenTargetRefusedByTheProvider();

    assertThrows(IllegalStateException.class, () -> caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME));

    verify(caldavConnectorStorage, never()).saveManagedRefusal(anyLong(), any());
  }

  /**
   * EXO-91017. Any other failure to produce credentials - an unreachable authority, a
   * refused technical account - is the connector's, not this user's: nothing recorded.
   */
  @Test
  public void aManagedConnectionWhoseCredentialsCannotBeProducedRecordsNothing() throws Exception {
    givenOneClickTargetOnly();
    when(caldavCredentialsResolver.authorization(SERVER_ID, PROVIDER, USERNAME))
        .thenThrow(new CalDavException("The credentials provider could not produce credentials",
                                       new ConnectorCredentialsException("BlueMind did not answer in time")));

    assertThrows(IllegalStateException.class, () -> caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME, true, () -> true));

    verify(caldavConnectorStorage, never()).saveManagedRefusal(anyLong(), any());
  }

  /**
   * EXO-91017. A provider that names no account for the user, on a connection managed
   * mode makes, records a refusal with no account; outside managed mode, nothing.
   */
  @Test
  public void aProviderNamingNobodyRecordsARefusalOnlyForManagedMode() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, USERNAME)).thenReturn(identity);
    when(identity.getId()).thenReturn(String.valueOf(IDENTITY_ID));
    when(caldavCredentialsResolver.requiresUserAction(PROVIDER)).thenReturn(false);
    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn(" ");

    assertThrows(IllegalArgumentException.class, () -> caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME));
    verify(caldavConnectorStorage, never()).saveManagedRefusal(anyLong(), any());

    assertThrows(IllegalArgumentException.class, () -> caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME, true, () -> true));
    verify(caldavConnectorStorage).saveManagedRefusal(IDENTITY_ID, new CaldavManagedRefusal(SERVER_ID, ""));
  }

  /**
   * EXO-91017. The refusal holds for the designated registration while the provider
   * names the same account for the user; another registration, another account, or
   * none recorded, and the connection is offered again.
   */
  @Test
  public void aManagedRefusalHoldsWhileTheServerAndTheAccountAreTheSame() throws Exception {
    givenOneClickTargetOnly();
    when(caldavConnectorStorage.getManagedRefusal(IDENTITY_ID)).thenReturn(new CaldavManagedRefusal(SERVER_ID, "eric@bm.example.org"));

    assertTrue(caldavRelayService.isManagedRefused(USERNAME, SERVER_ID));
    // Another registration naming the same account: only the registration differs.
    org.mockito.Mockito.lenient().when(caldavServerService.getServerById(SERVER_ID + 1)).thenReturn(server(SERVER_ID + 1, true));
    org.mockito.Mockito.lenient()
                      .when(caldavCredentialsResolver.targetAccount(SERVER_ID + 1, PROVIDER, USERNAME))
                      .thenReturn("eric@bm.example.org");
    assertFalse(caldavRelayService.isManagedRefused(USERNAME, SERVER_ID + 1));

    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn("eric.new@bm.example.org");
    assertFalse(caldavRelayService.isManagedRefused(USERNAME, SERVER_ID));

    when(caldavConnectorStorage.getManagedRefusal(IDENTITY_ID)).thenReturn(null);
    assertFalse(caldavRelayService.isManagedRefused(USERNAME, SERVER_ID));
  }

  /**
   * EXO-91017. A refusal recorded because the provider named nobody holds while it
   * still names nobody, and ends when it names someone.
   */
  @Test
  public void aManagedRefusalForNoAccountHoldsWhileTheProviderNamesNobody() throws Exception {
    givenOneClickTargetOnly();
    when(caldavConnectorStorage.getManagedRefusal(IDENTITY_ID)).thenReturn(new CaldavManagedRefusal(SERVER_ID, ""));

    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn(null);
    assertTrue(caldavRelayService.isManagedRefused(USERNAME, SERVER_ID));

    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn("eric@bm.example.org");
    assertFalse(caldavRelayService.isManagedRefused(USERNAME, SERVER_ID));
  }

  /** EXO-91017. A refusal that cannot be checked offers the connection again. */
  @Test
  public void aManagedRefusalThatCannotBeCheckedIsNone() throws Exception {
    givenOneClickTargetOnly();
    when(caldavConnectorStorage.getManagedRefusal(IDENTITY_ID)).thenReturn(new CaldavManagedRefusal(SERVER_ID, "eric@bm.example.org"));
    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenThrow(new CalDavException("no provider"));

    assertFalse(caldavRelayService.isManagedRefused(USERNAME, SERVER_ID));

    when(caldavServerService.getServerById(SERVER_ID)).thenThrow(new ObjectNotFoundException("gone"));
    assertFalse(caldavRelayService.isManagedRefused(USERNAME, SERVER_ID));
  }

  /** The registration, the user's identity and the account the provider names for them. */
  private void givenOneClickTargetOnly() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    org.mockito.Mockito.lenient().when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, USERNAME)).thenReturn(identity);
    org.mockito.Mockito.lenient().when(identity.getId()).thenReturn(String.valueOf(IDENTITY_ID));
    org.mockito.Mockito.lenient().when(caldavCredentialsResolver.requiresUserAction(PROVIDER)).thenReturn(false);
    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn("eric@bm.example.org");
    org.mockito.Mockito.lenient().when(agendaRemoteEventService.getRemoteProviders())
                      .thenReturn(List.of(new RemoteProvider(0, "agenda.caldavCalendar." + SERVER_ID, null, null, true, false)));
  }

  /** A one-click connection whose provider says the remote authority refused the account it named. */
  private void givenTargetRefusedByTheProvider() throws Exception {
    givenOneClickTargetOnly();
    when(caldavCredentialsResolver.authorization(SERVER_ID, PROVIDER, USERNAME))
        .thenThrow(new CalDavException("The credentials provider could not produce credentials",
                                       new ConnectorTargetRefusedException("BlueMind refused to act as eric@bm.example.org: status Bad, no message",
                                                                           null)));
  }

  /**
   * A disconnection on the platform's initiative removes agenda's record of
   * the connection as well as caldav's, or "My calendars" would still show it.
   */
  @Test
  public void aPlatformDisconnectionRemovesAgendasRecordThenCaldavs() {
    when(caldavConnectorStorage.getCaldavSetting(IDENTITY_ID)).thenReturn(settingOn(SERVER_ID));
    when(caldavServerService.resolveServer(SERVER_ID)).thenReturn(server(SERVER_ID, true));

    caldavRelayService.disconnectForUser(IDENTITY_ID, USERNAME);

    InOrder order = inOrder(agendaUserSettingsService, caldavConnectorService);
    order.verify(agendaUserSettingsService).removeUserConnector("agenda.caldavCalendar." + SERVER_ID, IDENTITY_ID);
    order.verify(caldavConnectorService).deleteCaldavSetting(IDENTITY_ID, USERNAME);
  }

  /**
   * A setting naming a row that no longer exists resolves to the seed
   * registration: agenda's record of another connector is left alone, and caldav's
   * setting still goes.
   */
  @Test
  public void aPlatformDisconnectionNeverRemovesAnotherConnectorsRecord() {
    when(caldavConnectorStorage.getCaldavSetting(IDENTITY_ID)).thenReturn(settingOn(SERVER_ID));
    when(caldavServerService.resolveServer(SERVER_ID)).thenReturn(server(1L, true));

    caldavRelayService.disconnectForUser(IDENTITY_ID, USERNAME);

    verify(agendaUserSettingsService, never()).removeUserConnector(anyString(), anyLong());
    verify(caldavConnectorService).deleteCaldavSetting(IDENTITY_ID, USERNAME);
  }

  /**
   * A setting naming no server was made through the legacy connector, which agenda
   * recorded under the seed row's provider name: that record goes too.
   */
  @Test
  public void aPlatformDisconnectionOfALegacyConnectionRemovesTheSeedRowsRecord() {
    when(caldavConnectorStorage.getCaldavSetting(IDENTITY_ID)).thenReturn(settingOn(null));
    CaldavServer seed = new CaldavServer(1L, CaldavServerService.CALDAV_PROVIDER_NAME, "Stalwart", null, SERVER_URL, true, null,
                                         null, null, null, true, null, null, null, null, null,
                                         MirrorTargetKind.DEDICATED_CALENDAR, PROVIDER, null, null);
    when(caldavServerService.resolveServer(null)).thenReturn(seed);

    caldavRelayService.disconnectForUser(IDENTITY_ID, USERNAME);

    InOrder order = inOrder(agendaUserSettingsService, caldavConnectorService);
    order.verify(agendaUserSettingsService).removeUserConnector(CaldavServerService.CALDAV_PROVIDER_NAME, IDENTITY_ID);
    order.verify(caldavConnectorService).deleteCaldavSetting(IDENTITY_ID, USERNAME);
  }

  /** Agenda refusing to forget the connector does not keep caldav's setting. */
  @Test
  public void aPlatformDisconnectionDeletesCaldavsSettingEvenWhenAgendaFails() {
    when(caldavConnectorStorage.getCaldavSetting(IDENTITY_ID)).thenReturn(settingOn(SERVER_ID));
    when(caldavServerService.resolveServer(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    doThrow(new IllegalStateException("agenda unavailable")).when(agendaUserSettingsService)
                                                            .removeUserConnector(anyString(), anyLong());

    caldavRelayService.disconnectForUser(IDENTITY_ID, USERNAME);

    verify(caldavConnectorService).deleteCaldavSetting(IDENTITY_ID, USERNAME);
  }

  private CaldavUserSetting settingOn(Long serverId) {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("mary@bm.example.org");
    setting.setServerId(serverId);
    return setting;
  }

  /**
   * The one-click path end to end: the server is probed with what the <b>provider</b>
   * produces - never with typed credentials, since there are none - and the connection
   * is recorded against the account the provider named.
   */
  @Test
  public void connectsInOneClickAndRecordsTheAccountTheProviderNamed() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, USERNAME)).thenReturn(identity);
    when(identity.getId()).thenReturn(String.valueOf(IDENTITY_ID));
    when(caldavCredentialsResolver.requiresUserAction(PROVIDER)).thenReturn(false);
    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn("eric@bm.example.org");
    when(caldavCredentialsResolver.authorization(SERVER_ID, PROVIDER, USERNAME)).thenReturn(PROVIDED_AUTH);
    givenAgendaConnector(true);
    givenProbeAnswer(207);

    CaldavProbeResult outcome = caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME);

    assertEquals(CaldavProbeResult.OK, outcome.getResult());
    // Accepted material is the provider's to keep.
    verify(caldavCredentialsResolver, never()).invalidate(any(), any(), any());
    ArgumentCaptor<CaldavUserSetting> recorded = ArgumentCaptor.forClass(CaldavUserSetting.class);
    // Agenda's record too, under the connector's name: without it "My calendars"
    // shows the account as not connected, whatever caldav stored (EXO-89653).
    // And agenda first: it is the write that can still refuse, and a refusal
    // after caldav's write would leave a half-connected account.
    org.mockito.InOrder writes = org.mockito.Mockito.inOrder(agendaUserSettingsService, caldavConnectorService);
    writes.verify(agendaUserSettingsService)
          .saveUserConnector("agenda.caldavCalendar." + SERVER_ID, "eric@bm.example.org", IDENTITY_ID);
    writes.verify(caldavConnectorService).createProviderBackedSetting(recorded.capture(), eq(IDENTITY_ID));
    assertEquals("eric@bm.example.org", recorded.getValue().getUsername());
    assertEquals(SERVER_ID, recorded.getValue().getServerId());
  }

  /**
   * Agenda's connector settings can switch the connector off on their own. The
   * one-click connect then refuses before probing or writing anything: a refusal
   * from agenda after caldav's setting was stored would leave a half-connected
   * account that the login-time attachment's rule 1 never retries.
   */
  @Test
  public void refusesToConnectWhenAgendaHasSwitchedTheConnectorOff() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavCredentialsResolver.requiresUserAction(PROVIDER)).thenReturn(false);
    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn("eric@bm.example.org");
    givenAgendaConnector(false);

    IllegalAccessException refusal = assertThrows(IllegalAccessException.class,
                                                  () -> caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME));

    assertEquals(CaldavRelayService.PROVIDER_DISABLED_MESSAGE, refusal.getMessage());
    org.mockito.Mockito.verifyNoInteractions(httpClient, caldavConnectorService, agendaUserSettingsService);
  }

  /**
   * A refused probe records nothing. A stored connection that does not work is worse
   * than a refused one: only the first looks right on screen, and the user discovers
   * it through an empty calendar. The refused material was the provider's, so the
   * provider is told, once per refused material: a caching provider would hand it
   * out again otherwise. A 401 is probed once more on fresh material, and that
   * refusal is told too; a 403 is the answer.
   *
   * @param status the server's refusal
   */
  @ParameterizedTest
  @ValueSource(ints = { 401, 403 })
  public void recordsNothingWhenTheServerRefusesTheServiceAccount(int status) throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavCredentialsResolver.requiresUserAction(PROVIDER)).thenReturn(false);
    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn("eric@bm.example.org");
    when(caldavCredentialsResolver.authorization(SERVER_ID, PROVIDER, USERNAME)).thenReturn(PROVIDED_AUTH);
    givenAgendaConnector(true);
    givenProbeAnswer(status);

    CaldavProbeResult outcome = caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME);

    assertEquals(CaldavProbeResult.CREDENTIALS, outcome.getResult());
    int refusedMaterials = status == 401 ? 2 : 1;
    verify(caldavCredentialsResolver, org.mockito.Mockito.times(refusedMaterials)).invalidate(SERVER_ID, PROVIDER, USERNAME);
    verify(httpClient, org.mockito.Mockito.times(refusedMaterials)).send(any(), any());
    org.mockito.Mockito.verifyNoInteractions(caldavConnectorService, agendaUserSettingsService);
  }

  /**
   * The server accepted, but the caller no longer wants the connection - the
   * login-time attachment, whose user configured an account during the probe.
   * Nothing is written, and the answer says so rather than claiming success.
   */
  @Test
  public void recordsNothingWhenTheConnectionIsNoLongerWanted() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavCredentialsResolver.requiresUserAction(PROVIDER)).thenReturn(false);
    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn("eric@bm.example.org");
    when(caldavCredentialsResolver.authorization(SERVER_ID, PROVIDER, USERNAME)).thenReturn(PROVIDED_AUTH);
    givenAgendaConnector(true);
    givenProbeAnswer(207);

    CaldavProbeResult outcome = caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME, false, () -> false);

    assertEquals(CaldavProbeResult.SUPERSEDED, outcome.getResult());
    assertEquals(207, outcome.getStatus());
    org.mockito.Mockito.verifyNoInteractions(caldavConnectorService, agendaUserSettingsService);
  }

  /**
   * A connector that does expect typed credentials is refused here, and nothing is
   * sent: connecting it with no credentials at all would record an account nobody
   * proved anything about.
   */
  @Test
  public void refusesToConnectAProviderThatAsksTheUser() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavCredentialsResolver.requiresUserAction(PROVIDER)).thenReturn(true);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME));

    // The message matters: without it this assertion passes on a version that
    // dropped the guard entirely and merely failed later, for another reason.
    assertEquals(CaldavRelayService.PROVIDER_ASKS_MESSAGE, refusal.getMessage());
    org.mockito.Mockito.verifyNoInteractions(httpClient);
    org.mockito.Mockito.verifyNoInteractions(caldavConnectorService);
  }

  /**
   * A server whose credentials provider is not installed is refused before the
   * provider is asked anything or the server probed, and nothing is recorded; the
   * refusal is the typed one a managed login stays quiet about.
   */
  @Test
  public void refusesToConnectAServerWhoseProviderIsNotInstalledBeforeAskingIt() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavCredentialsResolver.isProviderMissing(PROVIDER)).thenReturn(true);

    CalDavProviderMissingException refusal = assertThrows(CalDavProviderMissingException.class,
                                                          () -> caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME));

    assertTrue(refusal.getMessage().contains(PROVIDER), refusal.getMessage());
    verify(caldavCredentialsResolver, never()).requiresUserAction(anyString());
    verify(caldavCredentialsResolver, never()).targetAccount(any(), anyString(), anyString());
    org.mockito.Mockito.verifyNoInteractions(httpClient);
    org.mockito.Mockito.verifyNoInteractions(caldavConnectorService);
  }

  /** A provider that cannot name the account has nothing to connect. */
  @Test
  public void refusesToConnectWhenTheProviderNamesNobody() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavCredentialsResolver.requiresUserAction(PROVIDER)).thenReturn(false);
    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn(null);

    assertThrows(IllegalArgumentException.class, () -> caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME));
    org.mockito.Mockito.verifyNoInteractions(httpClient);
  }

  // ---- EXO-89649: one retry on fresh material after a 401 ----------------------

  /**
   * The relay: an upstream 401 on a refreshable provider's material is retried once on
   * fresh material, and the browser sees the retry's answer.
   */
  @Test
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public void relaysOnceMoreOnFreshMaterialAfterA401() throws Exception {
    givenConnectedUser(SERVER_ID);
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavServerService.resolveServer(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavCredentialsResolver.retriesAfterRefusal(PROVIDER)).thenReturn(true);
    HttpResponse refused = org.mockito.Mockito.mock(HttpResponse.class);
    when(refused.statusCode()).thenReturn(401);
    when(refused.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
    HttpResponse answered = org.mockito.Mockito.mock(HttpResponse.class);
    org.mockito.Mockito.lenient().when(answered.statusCode()).thenReturn(207);
    org.mockito.Mockito.lenient().when(answered.body()).thenReturn(new ByteArrayInputStream("<multistatus/>".getBytes(StandardCharsets.UTF_8)));
    org.mockito.Mockito.lenient().when(answered.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
    when(httpClient.send(any(), any())).thenReturn(refused, answered);

    CaldavRelayedResponse response = caldavRelayService.relay(relayRequest("PROPFIND", "/dav/cal/john/", Map.of()));

    assertEquals(207, response.getStatus());
    org.mockito.Mockito.verify(caldavCredentialsResolver, org.mockito.Mockito.times(1)).invalidate(SERVER_ID, PROVIDER, USERNAME);
    org.mockito.Mockito.verify(httpClient, org.mockito.Mockito.times(2)).send(any(), any());
  }

  /** The relay never retries a provider that carries what the user typed, and tells it the refusal once. */
  @Test
  public void neverRelaysAgainForAProviderThatCannotRefreshItsMaterial() throws Exception {
    givenConnectedUser(SERVER_ID);
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavServerService.resolveServer(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavCredentialsResolver.retriesAfterRefusal(PROVIDER)).thenReturn(false);
    givenUpstreamAnswer(401, Map.of(), new byte[0]);

    assertEquals(403, caldavRelayService.relay(relayRequest("PROPFIND", "/dav/cal/john/", Map.of())).getStatus());

    org.mockito.Mockito.verify(caldavCredentialsResolver, org.mockito.Mockito.times(1)).invalidate(SERVER_ID, PROVIDER, USERNAME);
    org.mockito.Mockito.verify(httpClient, org.mockito.Mockito.times(1)).send(any(), any());
  }

  /**
   * The one-click connect: a probe refused with 401 on material the provider kept is
   * probed once more on fresh material, and connects.
   */
  @Test
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public void probesOnceMoreOnFreshMaterialBeforeConnecting() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, USERNAME)).thenReturn(identity);
    when(identity.getId()).thenReturn(String.valueOf(IDENTITY_ID));
    when(caldavCredentialsResolver.requiresUserAction(PROVIDER)).thenReturn(false);
    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn("eric@bm.example.org");
    when(caldavCredentialsResolver.authorization(SERVER_ID, PROVIDER, USERNAME)).thenReturn(PROVIDED_AUTH);
    givenAgendaConnector(true);
    HttpResponse refused = org.mockito.Mockito.mock(HttpResponse.class);
    when(refused.statusCode()).thenReturn(401);
    HttpResponse accepted = org.mockito.Mockito.mock(HttpResponse.class);
    when(accepted.statusCode()).thenReturn(207);
    when(httpClient.send(any(), any())).thenReturn(refused, accepted);

    assertEquals(CaldavProbeResult.OK, caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME).getResult());

    org.mockito.Mockito.verify(caldavCredentialsResolver, org.mockito.Mockito.times(1)).invalidate(SERVER_ID, PROVIDER, USERNAME);
    org.mockito.Mockito.verify(httpClient, org.mockito.Mockito.times(2)).send(any(), any());
  }

  /**
   * The one-click probe retries on a 401 only - a 403 is the answer. Its material
   * was refused all the same, so the provider is told, once.
   */
  @Test
  @SuppressWarnings({ "rawtypes" })
  public void neverProbesAgainOnA403() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavCredentialsResolver.requiresUserAction(PROVIDER)).thenReturn(false);
    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn("eric@bm.example.org");
    when(caldavCredentialsResolver.authorization(SERVER_ID, PROVIDER, USERNAME)).thenReturn(PROVIDED_AUTH);
    givenAgendaConnector(true);
    givenProbeAnswer(403);

    assertEquals(CaldavProbeResult.CREDENTIALS, caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME).getResult());

    org.mockito.Mockito.verify(caldavCredentialsResolver, org.mockito.Mockito.times(1)).invalidate(SERVER_ID, PROVIDER, USERNAME);
    org.mockito.Mockito.verify(httpClient, org.mockito.Mockito.times(1)).send(any(), any());
  }

  /**
   * EXO-90836. A governed user's one-click connect to another registration is refused
   * before anything is asked of the provider or the server.
   */
  @Test
  public void aGovernedUserCannotConnectAnotherServer() throws Exception {
    when(caldavManagedModeService.checkUserMayChangeConnection(USERNAME, SERVER_ID)).thenThrow(new ManagedConnectionLockedException());

    assertThrows(ManagedConnectionLockedException.class, () -> caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME));

    org.mockito.Mockito.verifyNoInteractions(caldavServerService, caldavCredentialsResolver, httpClient, caldavConnectorService);
  }

  /**
   * EXO-90836. A governed user's own one-click connect to the designated registration is
   * marked as made by managed mode, as the login-time attachment is.
   */
  @Test
  public void aGovernedUsersConnectionToTheDesignatedServerIsMarked() throws Exception {
    when(caldavManagedModeService.checkUserMayChangeConnection(USERNAME, SERVER_ID)).thenReturn(SERVER_ID);
    givenOneClickConnection();

    caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME);

    verify(caldavConnectorStorage).markConnectedByManagedMode(IDENTITY_ID, true);
  }

  /**
   * EXO-90836. A governed user's own one-click connect to the designated registration
   * disconnects the account they have on another server, as the switch does, so agenda
   * is not left with the previous connector's record beside the new one.
   */
  @Test
  public void aGovernedUsersConnectDisconnectsTheAccountOnAnotherServer() throws Exception {
    when(caldavManagedModeService.checkUserMayChangeConnection(USERNAME, SERVER_ID)).thenReturn(SERVER_ID);
    givenOneClickConnection();
    when(caldavConnectorStorage.getCaldavSetting(IDENTITY_ID)).thenReturn(settingOn(99L));
    when(caldavServerService.resolveServer(99L)).thenReturn(server(99L, true));

    caldavRelayService.connectThroughProvider(SERVER_ID, USERNAME);

    InOrder order = inOrder(agendaUserSettingsService, caldavConnectorService, caldavConnectorStorage);
    order.verify(agendaUserSettingsService).removeUserConnector("agenda.caldavCalendar.99", IDENTITY_ID);
    order.verify(caldavConnectorService).deleteCaldavSetting(IDENTITY_ID, USERNAME);
    order.verify(agendaUserSettingsService)
         .saveUserConnector("agenda.caldavCalendar." + SERVER_ID, "eric@bm.example.org", IDENTITY_ID);
    order.verify(caldavConnectorStorage).markConnectedByManagedMode(IDENTITY_ID, true);
  }

  /**
   * EXO-90836. Once the designated server answered, the switch disconnects the previous
   * account as the platform does - agenda's record of it, then caldav's - before the
   * new account is recorded and marked.
   */
  @Test
  public void aSwitchDisconnectsThePreviousAccountOnceTheDesignatedServerAnswered() throws Exception {
    givenOneClickConnection();
    when(caldavConnectorStorage.getCaldavSetting(IDENTITY_ID)).thenReturn(settingOn(99L));
    when(caldavServerService.resolveServer(99L)).thenReturn(server(99L, true));

    CaldavProbeResult outcome = caldavRelayService.switchThroughProvider(SERVER_ID, USERNAME, () -> true);

    assertEquals(CaldavProbeResult.OK, outcome.getResult());
    InOrder order = inOrder(agendaUserSettingsService, caldavConnectorService, caldavConnectorStorage);
    order.verify(agendaUserSettingsService).removeUserConnector("agenda.caldavCalendar.99", IDENTITY_ID);
    order.verify(caldavConnectorService).deleteCaldavSetting(IDENTITY_ID, USERNAME);
    order.verify(agendaUserSettingsService)
         .saveUserConnector("agenda.caldavCalendar." + SERVER_ID, "eric@bm.example.org", IDENTITY_ID);
    order.verify(caldavConnectorService).createProviderBackedSetting(any(), eq(IDENTITY_ID));
    order.verify(caldavConnectorStorage).markConnectedByManagedMode(IDENTITY_ID, true);
  }

  /** EXO-90836. A switch the designated server refuses changes nothing of the previous account. */
  @Test
  public void aRefusedSwitchKeepsThePreviousAccount() throws Exception {
    when(caldavServerService.getServerById(SERVER_ID)).thenReturn(server(SERVER_ID, true));
    when(caldavCredentialsResolver.requiresUserAction(PROVIDER)).thenReturn(false);
    when(caldavCredentialsResolver.targetAccount(SERVER_ID, PROVIDER, USERNAME)).thenReturn("eric@bm.example.org");
    when(caldavCredentialsResolver.authorization(SERVER_ID, PROVIDER, USERNAME)).thenReturn(PROVIDED_AUTH);
    givenAgendaConnector(true);
    givenProbeAnswer(403);

    caldavRelayService.switchThroughProvider(SERVER_ID, USERNAME, () -> true);

    org.mockito.Mockito.verifyNoInteractions(caldavConnectorService, agendaUserSettingsService);
    verify(caldavConnectorStorage, never()).markConnectedByManagedMode(anyLong(), anyBoolean());
  }

  /**
   * EXO-90836. An account already on the registration - the legacy one resolving to it
   * included, as the registry decides - is not disconnected by a switch to it.
   */
  @Test
  public void aSwitchOfAnAccountAlreadyOnTheRegistrationDisconnectsNothing() throws Exception {
    givenOneClickConnection();
    when(caldavConnectorStorage.getCaldavSetting(IDENTITY_ID)).thenReturn(settingOn(null));
    when(caldavServerService.isOnServer(any(CaldavUserSetting.class), eq(SERVER_ID))).thenReturn(true);

    caldavRelayService.switchThroughProvider(SERVER_ID, USERNAME, () -> true);

    verify(caldavConnectorService, never()).deleteCaldavSetting(anyLong(), any());
    verify(agendaUserSettingsService, never()).removeUserConnector(anyString(), anyLong());
    verify(caldavConnectorService).createProviderBackedSetting(any(), eq(IDENTITY_ID));
  }
}
