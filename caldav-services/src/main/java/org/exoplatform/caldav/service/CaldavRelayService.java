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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.model.CaldavProbeResult;
import org.exoplatform.caldav.model.CaldavRelayRequest;
import org.exoplatform.caldav.model.CaldavRelayedResponse;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The server-side DAV relay: the browser addresses declared CalDAV servers
 * through {@code /caldav/rest/dav/{serverId}/**} and this service forwards
 * each request to the one host the registry row names, injecting the
 * connected user's stored credentials. This is the piece that removes every
 * reason for the browser to hold a CalDAV password or to reach a CalDAV
 * origin directly, and it holds the whole security posture of the relay:
 * <ul>
 * <li><b>SSRF boundary</b> — the target host comes only from the
 * administrator-managed registry ({@link CaldavServerService}); the client
 * chooses a {@code serverId} and a path on that host, never a URL. An
 * unknown, inactive or non-registry target is refused, not forwarded, and the
 * refusal happens before any socket is opened.</li>
 * <li><b>Credential custody</b> — Basic credentials are read from the user's
 * stored setting and injected here; any Authorization or Cookie header the
 * browser sends is dropped. Nothing auth-shaped is ever logged.</li>
 * <li><b>Method and header allow-lists</b> — only the DAV verbs and headers
 * the shipped connector actually issues pass through; everything else is
 * attack surface and is refused or dropped.</li>
 * <li><b>Per-server namespace</b> — responses advertise hrefs rooted at the
 * upstream host's own path space (BlueMind roots everything at {@code /dav/});
 * they are rewritten under the relay prefix of that one server, so two
 * servers can both advertise {@code /dav/} without colliding — the collision
 * a dev rig previously papered over with nginx {@code sub_filter}.</li>
 * </ul>
 */
@Service
public class CaldavRelayService {

  /** Message code for a relay request naming a method outside the allow-list. */
  public static final String        METHOD_NOT_ALLOWED_MESSAGE   = "caldav.relay.methodNotAllowed";

  /** Message code for a relay path that is not a plain DAV resource path. */
  public static final String        INVALID_PATH_MESSAGE         = "caldav.relay.invalidPath";

  /** Message code for a user with no stored CalDAV credentials. */
  public static final String        NOT_CONNECTED_MESSAGE        = "caldav.relay.notConnected";

  /** Message code for a target server declared but deactivated. */
  public static final String        SERVER_INACTIVE_MESSAGE      = "caldav.relay.serverInactive";

  /** Message code for a target that is not the server the account is connected to. */
  public static final String        SERVER_MISMATCH_MESSAGE      = "caldav.relay.serverMismatch";

  /** Message code for an upstream response larger than the configured cap. */
  public static final String        RESPONSE_TOO_LARGE_MESSAGE   = "caldav.relay.responseTooLarge";

  /** Message code for a probe request missing its credentials. */
  public static final String        PROBE_CREDENTIALS_MESSAGE    = "caldav.probe.credentialsMandatory";

  /**
   * Response header carrying the relay's own machine-readable outcome code,
   * so the browser can tell a relay refusal (or a translated upstream
   * credential rejection) from the same status genuinely answered by the
   * upstream server, which always arrives without this header.
   */
  public static final String        RELAY_CODE_HEADER            = "x-caldav-relay-code";

  /**
   * The DAV verbs the relay forwards: exactly what the shipped browser
   * connector issues — PROPFIND (discovery, calendar and ETag listings),
   * REPORT (calendar-query/multiget reads), GET (single-object read before a
   * conditional write), PUT (create/update of an ICS object), DELETE (object
   * removal), MKCALENDAR (mirror-calendar creation) — plus OPTIONS and HEAD,
   * read-only capability probes some clients open with. Deliberately absent:
   * PROPPATCH, POST, COPY, MOVE, LOCK, UNLOCK, MKCOL — no current flow sends
   * them, so relaying them would be pure attack surface; the sync engine
   * that may need PROPPATCH later talks to servers directly, not through
   * this relay.
   * <p>
   * Public because it is the single source of truth two gates read: this
   * service's own 405 refusal, and the webapp's Spring Security
   * {@code StrictHttpFirewall}, whose allowed-method list is built as
   * standard-HTTP ∪ this set (CaldavHttpFirewallConfiguration) — the
   * firewall would otherwise reject every DAV verb as a 400 before the
   * dispatcher ever saw it. Sharing the constant means a verb added here is
   * automatically admitted by the firewall, and a verb only the firewall
   * admits (the standard ones the admin REST needs) still meets this
   * service's 405 on the relay path.
   */
  public static final Set<String>   ALLOWED_METHODS              =
                                                    Set.of("OPTIONS", "HEAD", "GET", "PROPFIND", "REPORT", "PUT", "DELETE",
                                                           "MKCALENDAR");

  /**
   * Request headers forwarded to the upstream server: Depth scopes PROPFIND
   * and REPORT; Content-Type distinguishes the XML request bodies from
   * text/calendar PUTs; If-Match and If-None-Match carry the connector's
   * conditional-write discipline (every PUT is conditional, a lost
   * precondition must surface as 412); Accept and Prefer are harmless
   * negotiation. Everything else — Authorization, Cookie, Origin, Referer,
   * the sec-* family — is dropped: the upstream must see the relay's own
   * credentials and nothing of the user's eXo session or browser context.
   */
  private static final Set<String>  FORWARDED_REQUEST_HEADERS    =
                                                              Set.of("depth", "content-type", "if-match", "if-none-match",
                                                                     "accept", "prefer");

  /**
   * Response headers passed back to the browser: Content-Type and ETag drive
   * the connector's parsing and conditional writes; DAV and Allow answer
   * capability probes; Schedule-Tag and Preference-Applied are standard
   * CalDAV answers. Location and Content-Location are also passed, after href
   * rewriting. Never passed: WWW-Authenticate (a Basic challenge on the
   * platform origin would pop the browser's own credentials dialog),
   * Set-Cookie (the upstream must not plant cookies on the eXo origin), and
   * the hop-by-hop family.
   */
  private static final Set<String>  FORWARDED_RESPONSE_HEADERS   =
                                                               Set.of("content-type", "etag", "dav", "allow", "schedule-tag",
                                                                      "preference-applied");

  /** Response headers passed back after being rewritten into relay space. */
  private static final Set<String>  REWRITTEN_RESPONSE_HEADERS   = Set.of("location", "content-location");

  /**
   * One DAV href element with its text content, whatever namespace prefix the
   * server chose ({@code <D:href>}, {@code <d:href>}, {@code <href>}...).
   * Href content is a plain URI reference — never nested markup — which is
   * what makes a textual rewrite exact where general XML rewriting would not
   * be.
   */
  private static final Pattern      HREF_PATTERN                 =
                                                 Pattern.compile("(<(?:[A-Za-z][\\w.-]*:)?href(?:\\s[^>]*)?>)\\s*([^<]*?)\\s*"
                                                     + "(</(?:[A-Za-z][\\w.-]*:)?href\\s*>)", Pattern.CASE_INSENSITIVE);

  /**
   * The characters a raw DAV resource path may carry: the RFC 3986 path
   * character set, percent-encoding included. Anything else — and any
   * dot-segment — is refused before a URI is even built, so no crafted path
   * can smuggle a different authority or confuse the upstream server.
   */
  private static final Pattern      SAFE_PATH_PATTERN            = Pattern.compile("/[A-Za-z0-9._~%!$&'()*+,;=:@/\\-]*");

  /** Legacy-style property naming the connect timeout, in seconds. */
  private static final String       CONNECT_TIMEOUT_PROPERTY     = "exo.agenda.caldav.relay.connectTimeoutSeconds";

  /** Legacy-style property naming the per-request timeout, in seconds. */
  private static final String       REQUEST_TIMEOUT_PROPERTY     = "exo.agenda.caldav.relay.requestTimeoutSeconds";

  /** Property capping request and response body sizes, in bytes. */
  private static final String       MAX_BODY_BYTES_PROPERTY      = "exo.agenda.caldav.relay.maxBodyBytes";

  private static final int          DEFAULT_CONNECT_TIMEOUT      = 10;

  private static final int          DEFAULT_REQUEST_TIMEOUT      = 30;

  private static final long         DEFAULT_MAX_BODY_BYTES       = 20L * 1024 * 1024;

  private static final String       PROBE_BODY                   =
                                               "<?xml version=\"1.0\"?><propfind xmlns=\"DAV:\"><prop><resourcetype/></prop></propfind>";

  private static final Log          LOG                          = ExoLogger.getLogger(CaldavRelayService.class);

  @Autowired
  private CaldavServerService       caldavServerService;

  @Autowired
  private CaldavConnectorStorage    caldavConnectorStorage;

  @Autowired
  private IdentityManager           identityManager;

  /**
   * The JDK's own HTTP client, TLS trust from the platform truststore —
   * exactly the transport email-connector's CardDAV client rides. Redirects
   * are NEVER followed: a registered server must not be able to bounce the
   * platform's credentialed requests to another host, so a 3xx passes back
   * to the browser (rewritten) instead of being chased.
   */
  private HttpClient                httpClient                   =
                                               HttpClient.newBuilder()
                                                         .connectTimeout(Duration.ofSeconds(intProperty(CONNECT_TIMEOUT_PROPERTY,
                                                                                                        DEFAULT_CONNECT_TIMEOUT)))
                                                         .followRedirects(HttpClient.Redirect.NEVER)
                                                         .build();

  /**
   * The size cap applied to request and response bodies alike, so the REST
   * layer can refuse an oversized upload with the same limit this service
   * enforces on downloads.
   *
   * @return the cap in bytes
   */
  public long getMaxBodyBytes() {
    return longProperty(MAX_BODY_BYTES_PROPERTY, DEFAULT_MAX_BODY_BYTES);
  }

  /**
   * Relays one DAV request of a connected user to the declared server it
   * targets. The target host comes exclusively from the registry row —
   * refused before any I/O when the row is unknown ({@link ObjectNotFoundException}),
   * deactivated, or not the server the user's account is connected to
   * ({@link IllegalAccessException}) — and the stored credentials of the user
   * are injected as Basic auth. Upstream statuses pass through untouched so
   * the browser connector's own DAV logic (207 parsing, 412 conflicts,
   * BlueMind's 500-for-absent-object quirk) keeps holding, with one
   * translation: an upstream 401/407 means the <b>stored</b> credentials are
   * stale, so it becomes a 403 carrying {@code caldav.error.credentials} in
   * the {@value #RELAY_CODE_HEADER} header, never a 401 the platform or the
   * browser could read as an eXo authentication failure.
   *
   * @param relayRequest the request to relay, target named by serverId only
   * @return the upstream response, headers allow-listed, hrefs rewritten
   * @throws ObjectNotFoundException when no registration carries the id
   * @throws IllegalAccessException when the server is deactivated or is not
   *           the one the user's account is connected to
   * @throws IllegalStateException when the user has no stored credentials
   * @throws IllegalArgumentException when the method is outside the
   *           allow-list or the path is not a plain DAV resource path
   */
  public CaldavRelayedResponse relay(CaldavRelayRequest relayRequest) throws ObjectNotFoundException, IllegalAccessException {
    String method = StringUtils.upperCase(relayRequest.getMethod(), Locale.ENGLISH);
    if (method == null || !ALLOWED_METHODS.contains(method)) {
      throw new IllegalArgumentException(METHOD_NOT_ALLOWED_MESSAGE);
    }
    String davPath = StringUtils.defaultIfBlank(relayRequest.getDavPath(), "/");
    if (!SAFE_PATH_PATTERN.matcher(davPath).matches() || davPath.contains("..")) {
      throw new IllegalArgumentException(INVALID_PATH_MESSAGE);
    }
    CaldavUserSetting setting = getConnectedSetting(relayRequest.getUsername());
    CaldavServer server = resolveAuthorizedServer(relayRequest.getServerId(), setting);
    URI upstreamBase = serverBaseUri(server);
    URI target = URI.create(upstreamBase.getScheme() + "://" + upstreamBase.getRawAuthority() + davPath
        + (StringUtils.isBlank(relayRequest.getQuery()) ? "" : "?" + relayRequest.getQuery()));
    HttpRequest request = buildUpstreamRequest(target, method, relayRequest.getHeaders(), relayRequest.getBody(),
                                               setting.getUsername(), setting.getPassword());
    return execute(request, upstreamBase, relayRequest.getRelayPrefix());
  }

  /**
   * Probes a CalDAV account server-side, before anything is stored: the
   * connect drawer sends the typed credentials once, here, instead of the
   * browser probing the CalDAV origin directly — which BlueMind's missing
   * CORS headers made impossible without an nginx front. The probe is a
   * Depth:0 PROPFIND against the declared server's own URL and classifies
   * the answer with the same stable codes the browser probe historically
   * produced. It never stores anything and injects nothing from the user's
   * stored setting: only the typed credentials are tried, only against a
   * registry row.
   *
   * @param serverId identifier of the declared server, or null for the
   *          resolution fallback (the seed registration)
   * @param username account username to try
   * @param password account password to try
   * @return the classified outcome, never null
   * @throws ObjectNotFoundException when no registration resolves
   * @throws IllegalAccessException when the resolved server is deactivated
   * @throws IllegalArgumentException when the credentials are blank or the
   *           username cannot be part of a URL path
   */
  public CaldavProbeResult probeAccount(Long serverId, String username, String password) throws ObjectNotFoundException,
                                                                                         IllegalAccessException {
    if (StringUtils.isBlank(username) || StringUtils.isBlank(password)) {
      throw new IllegalArgumentException(PROBE_CREDENTIALS_MESSAGE);
    }
    if (!username.matches("[A-Za-z0-9._%+@\\-]+")) {
      throw new IllegalArgumentException(PROBE_CREDENTIALS_MESSAGE);
    }
    CaldavServer server = serverId == null ? caldavServerService.resolveServer(null)
                                           : caldavServerService.getServerById(serverId);
    if (server == null) {
      throw new ObjectNotFoundException("No CalDAV server is declared to probe against");
    }
    if (!server.isActive()) {
      throw new IllegalAccessException(SERVER_INACTIVE_MESSAGE);
    }
    URI target = URI.create(server.getServerUrl().replace("{username}", username));
    HttpRequest request = HttpRequest.newBuilder(target)
                                     .method("PROPFIND", BodyPublishers.ofString(PROBE_BODY))
                                     .header("Depth", "0")
                                     .header("Content-Type", "application/xml")
                                     .header("Authorization", basicAuth(username, password))
                                     .timeout(Duration.ofSeconds(intProperty(REQUEST_TIMEOUT_PROPERTY,
                                                                             DEFAULT_REQUEST_TIMEOUT)))
                                     .build();
    try {
      HttpResponse<Void> response = httpClient.send(request, BodyHandlers.discarding());
      int status = response.statusCode();
      if (status == 401 || status == 403) {
        return new CaldavProbeResult(CaldavProbeResult.CREDENTIALS, status);
      }
      if (status != 207) {
        return new CaldavProbeResult(CaldavProbeResult.NOT_CALDAV, status);
      }
      return new CaldavProbeResult(CaldavProbeResult.OK, status);
    } catch (IOException e) {
      LOG.debug("CalDAV probe could not reach the server {}", server.getId(), e);
      return new CaldavProbeResult(CaldavProbeResult.CONNECTION, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new CaldavProbeResult(CaldavProbeResult.CONNECTION, null);
    }
  }

  /**
   * The stored CalDAV setting of the user, credentials included — read from
   * the storage on purpose, since the service-level DTO no longer carries
   * the password anywhere.
   *
   * @param username eXo username of the caller
   * @return the stored setting, holding username and decoded password
   * @throws IllegalStateException when no complete credentials are stored
   */
  private CaldavUserSetting getConnectedSetting(String username) {
    CaldavUserSetting setting = caldavConnectorStorage.getCaldavSetting(getUserIdentityId(username));
    if (StringUtils.isBlank(setting.getUsername()) || StringUtils.isBlank(setting.getPassword())) {
      throw new IllegalStateException(NOT_CONNECTED_MESSAGE);
    }
    return setting;
  }

  /**
   * The one registry row a user's relay request may target: the row must
   * exist, be the row the user's account resolves to — otherwise the stored
   * credentials of one server would be injected into requests aimed at
   * another — and be active.
   *
   * @param serverId identifier the request names
   * @param setting the user's stored setting, carrying its server reference
   * @return the authorized registration
   * @throws ObjectNotFoundException when no registration carries the id
   * @throws IllegalAccessException when the row is deactivated or not the
   *           user's connected server
   */
  private CaldavServer resolveAuthorizedServer(long serverId, CaldavUserSetting setting) throws ObjectNotFoundException,
                                                                                         IllegalAccessException {
    CaldavServer requested = caldavServerService.getServerById(serverId);
    CaldavServer effective = caldavServerService.resolveServer(setting.getServerId());
    if (effective == null || effective.getId() != requested.getId()) {
      throw new IllegalAccessException(SERVER_MISMATCH_MESSAGE);
    }
    if (!requested.isActive()) {
      throw new IllegalAccessException(SERVER_INACTIVE_MESSAGE);
    }
    return requested;
  }

  /**
   * The base URI of a declared server, from its registered URL with the
   * {@code {username}} placeholder neutralized — only the scheme and
   * authority are used, since the relay exposes the upstream host's whole
   * path space under the per-server prefix (BlueMind advertises hrefs rooted
   * at {@code /dav/}, wherever its registered URL points).
   *
   * @param server the registration to derive the base from
   * @return the upstream base URI
   */
  private URI serverBaseUri(CaldavServer server) {
    return URI.create(server.getServerUrl().replace("{username}", "u"));
  }

  /**
   * Builds the upstream request: allow-listed headers only, the user's stored
   * credentials as Basic auth — whatever Authorization the browser sent is
   * not in the allow-list, so it is structurally impossible to forward it —
   * and the configured per-request timeout.
   *
   * @param target upstream URI to call
   * @param method allow-listed DAV verb
   * @param headers request headers as the browser sent them, lower-cased
   * @param body request body bytes, possibly empty
   * @param davUsername stored CalDAV username
   * @param davPassword stored CalDAV password
   * @return the request to send
   */
  private HttpRequest buildUpstreamRequest(URI target,
                                           String method,
                                           Map<String, String> headers,
                                           byte[] body,
                                           String davUsername,
                                           String davPassword) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                                             .method(method, BodyPublishers.ofByteArray(body == null ? new byte[0] : body))
                                             .timeout(Duration.ofSeconds(intProperty(REQUEST_TIMEOUT_PROPERTY,
                                                                                     DEFAULT_REQUEST_TIMEOUT)));
    if (headers != null) {
      headers.forEach((name, value) -> {
        if (FORWARDED_REQUEST_HEADERS.contains(StringUtils.lowerCase(name, Locale.ENGLISH)) && StringUtils.isNotBlank(value)) {
          builder.header(name, value);
        }
      });
    }
    builder.header("Authorization", basicAuth(davUsername, davPassword));
    builder.header("User-Agent", "eXo-CalDAV-Relay");
    return builder.build();
  }

  /**
   * Sends the upstream request and shapes the answer for the browser:
   * bounded body read, response-header allow-list, href rewriting for DAV
   * XML bodies and Location-family headers, and the credential-status
   * translation. Transport failures come back as a 502 carrying
   * {@code caldav.error.connection} — the honest "the platform could not
   * reach the CalDAV server", distinct from any eXo failure.
   *
   * @param request the upstream request to send
   * @param upstreamBase the upstream base URI, for absolute-href matching
   * @param relayPrefix the relay prefix hrefs are rewritten onto
   * @return the response to hand the browser
   */
  private CaldavRelayedResponse execute(HttpRequest request, URI upstreamBase, String relayPrefix) {
    try {
      HttpResponse<InputStream> response = httpClient.send(request, BodyHandlers.ofInputStream());
      byte[] body = readBounded(response.body());
      if (body == null) {
        LOG.warn("CalDAV relay response from server exceeded the configured cap of {} bytes", getMaxBodyBytes());
        return refusal(502, RESPONSE_TOO_LARGE_MESSAGE);
      }
      int status = response.statusCode();
      if (status == 401 || status == 407) {
        // The STORED CalDAV credentials are refused: never let this travel
        // as a 401, which the platform and the browser both read as "the eXo
        // user is unauthenticated" (and, with WWW-Authenticate, as an
        // invitation to pop the browser's own Basic dialog).
        return refusal(403, "caldav.error.credentials");
      }
      Map<String, String> headers = new LinkedHashMap<>();
      response.headers().map().forEach((name, values) -> {
        String lower = StringUtils.lowerCase(name, Locale.ENGLISH);
        if (!values.isEmpty()) {
          if (FORWARDED_RESPONSE_HEADERS.contains(lower)) {
            headers.put(lower, values.get(0));
          } else if (REWRITTEN_RESPONSE_HEADERS.contains(lower)) {
            headers.put(lower, rewriteHrefValue(values.get(0), upstreamBase, relayPrefix));
          }
        }
      });
      String contentType = headers.get("content-type");
      if (contentType != null && contentType.toLowerCase(Locale.ENGLISH).contains("xml") && body.length > 0) {
        Charset charset = charsetOf(contentType);
        body = rewriteHrefs(new String(body, charset), upstreamBase, relayPrefix).getBytes(charset);
      }
      return new CaldavRelayedResponse(status, headers, body);
    } catch (IOException e) {
      LOG.debug("CalDAV relay could not reach the upstream server", e);
      return refusal(502, "caldav.error.connection");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return refusal(502, "caldav.error.connection");
    }
  }

  /**
   * A response produced by the relay itself, marked as such: the
   * {@value #RELAY_CODE_HEADER} header is what lets the browser tell a relay
   * verdict from the same status genuinely answered by the upstream server.
   *
   * @param status HTTP status to answer
   * @param code machine-readable relay code
   * @return the relay's own response
   */
  private CaldavRelayedResponse refusal(int status, String code) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(RELAY_CODE_HEADER, code);
    return new CaldavRelayedResponse(status, headers, new byte[0]);
  }

  /**
   * Reads a response body up to the configured cap.
   *
   * @param stream the upstream body stream
   * @return the bytes, or null when the body exceeds the cap
   * @throws IOException when the read itself fails
   */
  private byte[] readBounded(InputStream stream) throws IOException {
    long max = getMaxBodyBytes();
    try (InputStream body = stream) {
      byte[] bytes = body.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE));
      return bytes.length > max ? null : bytes;
    }
  }

  /**
   * Rewrites every DAV href of a multistatus body into relay space, so that
   * the URL space the browser sees is closed under the relay: any href a
   * response advertises — BlueMind's {@code /dav/}-rooted ones included —
   * resolves back under {@code /caldav/rest/dav/{serverId}/} instead of
   * escaping to the platform root, which is the exact bug class that
   * produced "cannot find homeUrl" against BlueMind when hrefs resolved
   * against the wrong base.
   *
   * @param xml the multistatus body
   * @param upstreamBase the upstream base URI, for absolute-href matching
   * @param relayPrefix the relay prefix to root hrefs at
   * @return the body with every rewritable href moved into relay space
   */
  protected String rewriteHrefs(String xml, URI upstreamBase, String relayPrefix) {
    Matcher matcher = HREF_PATTERN.matcher(xml);
    StringBuilder rewritten = new StringBuilder();
    while (matcher.find()) {
      String value = rewriteHrefValue(matcher.group(2), upstreamBase, relayPrefix);
      matcher.appendReplacement(rewritten,
                                Matcher.quoteReplacement(matcher.group(1) + value + matcher.group(3)));
    }
    matcher.appendTail(rewritten);
    return rewritten.toString();
  }

  /**
   * Rewrites one href value into relay space: a path-rooted href is prefixed,
   * an absolute URL naming the upstream host is folded onto the prefix, and
   * anything else — notably an absolute URL naming a foreign host — is left
   * untouched, because rewriting it would turn the relay into an open proxy
   * toward hosts no administrator declared.
   *
   * @param value the href text, possibly percent-encoded
   * @param upstreamBase the upstream base URI
   * @param relayPrefix the relay prefix to root the href at
   * @return the rewritten href, or the value unchanged
   */
  protected String rewriteHrefValue(String value, URI upstreamBase, String relayPrefix) {
    String trimmed = StringUtils.trimToEmpty(value);
    if (trimmed.startsWith("/")) {
      return relayPrefix + trimmed;
    }
    try {
      URI uri = URI.create(trimmed);
      if (uri.isAbsolute() && sameAuthority(uri, upstreamBase)) {
        String path = StringUtils.defaultIfBlank(uri.getRawPath(), "/");
        return relayPrefix + path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
      }
    } catch (IllegalArgumentException e) {
      LOG.debug("Unparseable href left unrewritten by the CalDAV relay", e);
    }
    return value;
  }

  /**
   * Whether two URIs name the same server: same scheme, same host ignoring
   * case, same effective port — the default of the scheme when none is
   * written.
   *
   * @param first one URI
   * @param second the other URI
   * @return true when both name the same authority
   */
  private boolean sameAuthority(URI first, URI second) {
    return StringUtils.equalsIgnoreCase(first.getScheme(), second.getScheme())
        && StringUtils.equalsIgnoreCase(first.getHost(), second.getHost())
        && effectivePort(first) == effectivePort(second);
  }

  /**
   * The port a URI actually addresses: the written one, else the scheme's
   * default.
   *
   * @param uri the URI to read
   * @return the effective port
   */
  private int effectivePort(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return StringUtils.equalsIgnoreCase(uri.getScheme(), "https") ? 443 : 80;
  }

  /**
   * The charset a Content-Type declares, else UTF-8 — what every DAV server
   * in practice emits.
   *
   * @param contentType the Content-Type header value
   * @return the charset to decode and re-encode the body with
   */
  private Charset charsetOf(String contentType) {
    Matcher matcher = Pattern.compile("charset=\"?([A-Za-z0-9._\\-]+)\"?", Pattern.CASE_INSENSITIVE).matcher(contentType);
    if (matcher.find()) {
      try {
        return Charset.forName(matcher.group(1));
      } catch (IllegalArgumentException e) {
        LOG.debug("Unknown charset '{}' in a relayed CalDAV response, using UTF-8", matcher.group(1));
      }
    }
    return StandardCharsets.UTF_8;
  }

  /**
   * The Basic Authorization header value for a credential pair.
   *
   * @param username account username
   * @param password account password
   * @return the header value
   */
  private String basicAuth(String username, String password) {
    return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The social identity id of an eXo user, which is how the per-user settings
   * are keyed.
   *
   * @param username eXo username
   * @return the identity id, or 0 when the identity cannot be resolved
   */
  private long getUserIdentityId(String username) {
    Identity identity = identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, username);
    return identity == null ? 0 : Long.parseLong(identity.getId());
  }

  /**
   * An integer deployment property, else its default.
   *
   * @param name property name
   * @param defaultValue value when unset or unreadable
   * @return the configured value
   */
  private static int intProperty(String name, int defaultValue) {
    try {
      return Integer.parseInt(System.getProperty(name, String.valueOf(defaultValue)));
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /**
   * A long deployment property, else its default.
   *
   * @param name property name
   * @param defaultValue value when unset or unreadable
   * @return the configured value
   */
  private static long longProperty(String name, long defaultValue) {
    try {
      return Long.parseLong(System.getProperty(name, String.valueOf(defaultValue)));
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
