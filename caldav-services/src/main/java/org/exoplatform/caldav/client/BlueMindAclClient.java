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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.exoplatform.caldav.provider.CaldavCredentialsResolver;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Reads the access control list of a BlueMind calendar through BlueMind's own
 * REST API — the one place its sharing state can be read (EXO-90253).
 *
 * <p>
 * BlueMind's DAV server answers a {@code CS:share} with 200 whatever it did
 * and publishes no sharee list over DAV ({@code CS:invite} is a stub:
 * {@code plugins/net.bluemind.dav.server/.../proto/props/calendarserver/Invite.java}),
 * so the only proof that a grant was applied is the container's access list,
 * {@code GET /api/containers/_manage/{containerUid}/_acl}
 * ({@code parent/core/net.bluemind.core.container.api/.../IContainerManagement.java}),
 * which the container's owner may read
 * ({@code ContainerPermissionResolver.java}: the owner holds every verb).
 *
 * <p>
 * The conversation, and what keeps it safe:
 * <ul>
 * <li><b>Same server, never another.</b> The REST root is the scheme, host
 * and port of the endpoint minted from the server registry for the DAV calls,
 * with the {@code /api} root BlueMind serves its REST API under
 * ({@code RestServiceApiDescriptionParser.java} prefixes every path with
 * {@code /api}; {@code bluemind-vhosts.conf} proxies {@code location /api/}).
 * Nothing a caller passes can name a host; redirects are never followed.</li>
 * <li><b>The owner's own credentials, from storage.</b> The login and password
 * are the ones the configured credentials provider produces for the owner's
 * DAV requests, taken from its Basic header; a provider producing anything
 * else is not a login this API accepts, and sharing is not offered.</li>
 * <li><b>A session for one read.</b> {@code POST /api/auth/login?login=…} with
 * the password as the body answers a {@code LoginResponse} whose
 * {@code authKey} travels in the {@code X-BM-ApiKey} header
 * ({@code parent/core/net.bluemind.core.rest/.../base/RestRootHandler.java}
 * reads it, {@code BasicClientProxy.java} sends it); the key is used for the
 * read and {@code POST /api/auth/logout} is always sent after it. The key and
 * the password are never logged, never stored, and never part of an
 * exception message.</li>
 * </ul>
 */
@Component
public class BlueMindAclClient {

  /** The header BlueMind's REST API reads a session key from. */
  public static final String      API_KEY_HEADER   = "X-BM-ApiKey";

  /** What this client names itself as to BlueMind's login, for its logs. */
  static final String             LOGIN_ORIGIN     = "exo-caldav";

  private static final Log        LOG              = ExoLogger.getLogger(BlueMindAclClient.class);

  /** The longest answer read from this API: an access list is a few entries. */
  private static final long       MAX_BODY_BYTES   = 1024L * 1024;

  private static final Duration   REQUEST_TIMEOUT  = Duration.ofSeconds(30);

  /** The characters left as they are in a path segment: RFC 3986 unreserved. */
  private static final String     UNRESERVED       = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

  private final HttpClient                httpClient;

  private final CaldavCredentialsResolver caldavCredentialsResolver;

  private final JsonMapper                mapper   = JsonMapper.builder().build();

  /**
   * One entry of a BlueMind container's access list.
   *
   * @param subject the directory entry uid it applies to — a user's uid is the
   *          segment of their DAV principal
   *          {@code /dav/principals/__uids__/<uid>/}
   * @param verb the BlueMind verb, {@code Read}, {@code Write}, {@code Manage}…
   */
  public record BlueMindAce(String subject, String verb) {
  }

  /**
   * The client Spring builds: no redirects, bounded timeouts.
   *
   * @param caldavCredentialsResolver the seam producing the owner's credentials
   */
  @Autowired
  public BlueMindAclClient(CaldavCredentialsResolver caldavCredentialsResolver) {
    this(HttpClient.newBuilder()
                   .connectTimeout(Duration.ofSeconds(10))
                   .followRedirects(HttpClient.Redirect.NEVER)
                   .build(),
         caldavCredentialsResolver);
  }

  /**
   * The seam the tests use.
   *
   * @param httpClient the transport
   * @param caldavCredentialsResolver the credentials seam
   */
  BlueMindAclClient(HttpClient httpClient, CaldavCredentialsResolver caldavCredentialsResolver) {
    this.httpClient = httpClient;
    this.caldavCredentialsResolver = caldavCredentialsResolver;
  }

  /**
   * A container's access list, read as its owner in one short REST session.
   *
   * @param endpoint the owner's DAV endpoint, minted from the registry
   * @param containerUid the container uid, the last segment of the calendar
   *          collection's path
   * @return the entries, in the order BlueMind lists them
   * @throws UnsupportedOperationException when the configured credentials are
   *           not a login and password
   * @throws CalDavAuthenticationException when BlueMind refuses the login
   * @throws CalDavForbiddenException when the account may not read the list
   * @throws CalDavUnreachableException when the server cannot be reached
   * @throws CalDavException when the answer is anything else
   */
  public List<BlueMindAce> readAcl(CalDavEndpoint endpoint, String containerUid) {
    if (StringUtils.isBlank(containerUid)) {
      throw new IllegalArgumentException("A container uid is required");
    }
    String root = apiRootOf(endpoint);
    String[] account = accountOf(endpoint);
    String key = login(root, account[0], account[1]);
    try {
      URI uri = URI.create(root + "/api/containers/_manage/" + encodeSegment(containerUid) + "/_acl");
      HttpRequest request = HttpRequest.newBuilder(uri)
                                       .timeout(REQUEST_TIMEOUT)
                                       .header(API_KEY_HEADER, key)
                                       .header("Accept", "application/json")
                                       .GET()
                                       .build();
      Answer answer = send(request, uri);
      if (answer.status() == 401) {
        throw new CalDavAuthenticationException("The calendar server refused the session for GET " + uri);
      }
      if (answer.status() == 403) {
        throw new CalDavForbiddenException("The calendar server refused to list the access of " + uri);
      }
      checkGateway(answer.status(), "GET", uri);
      if (answer.status() != 200) {
        throw new CalDavException("The calendar server answered " + answer.status() + " for GET " + uri);
      }
      return acesOf(answer.body(), uri);
    } finally {
      logout(root, key);
    }
  }

  /**
   * The REST root of the declared server: the endpoint's scheme, host and
   * port, and nothing a caller supplies.
   *
   * @param endpoint the endpoint minted from the registry
   * @return {@code scheme://host[:port]}
   */
  static String apiRootOf(CalDavEndpoint endpoint) {
    URI base = endpoint.getBaseUri();
    String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
    if (!("https".equals(scheme) || "http".equals(scheme)) || StringUtils.isBlank(base.getHost())) {
      throw new CalDavException("The declared calendar server has no usable address for its REST API");
    }
    return scheme + "://" + base.getHost() + (base.getPort() == -1 ? "" : ":" + base.getPort());
  }

  /**
   * Whether the owner's configured credentials are a login and password this
   * API can take, without calling the server: what decides whether sharing
   * on BlueMind is offered at all, so that a registration whose provider
   * produces a token never shows an action every click of which would be
   * refused.
   *
   * @param endpoint the owner's endpoint, minted from the registry
   * @return true when the provider produces a Basic login and password
   */
  public boolean acceptsCredentials(CalDavEndpoint endpoint) {
    try {
      return accountOf(endpoint) != null;
    } catch (UnsupportedOperationException e) {
      return false;
    }
  }

  /**
   * The owner's login and password, as the configured provider produces them
   * for the DAV requests.
   *
   * @param endpoint the owner's endpoint
   * @return login and password
   * @throws UnsupportedOperationException when the provider produces anything
   *           but a Basic login and password
   */
  private String[] accountOf(CalDavEndpoint endpoint) {
    String authorization = caldavCredentialsResolver.authorization(endpoint.getServerId(),
                                                                   endpoint.getAuthProviderName(),
                                                                   endpoint.getExoLogin());
    if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
      throw new UnsupportedOperationException("The configured credentials are not a login and password");
    }
    String decoded;
    try {
      decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new UnsupportedOperationException("The configured credentials are not a login and password");
    }
    int colon = decoded.indexOf(':');
    if (colon < 1 || colon == decoded.length() - 1) {
      throw new UnsupportedOperationException("The configured credentials are not a login and password");
    }
    return new String[] { decoded.substring(0, colon), decoded.substring(colon + 1) };
  }

  /**
   * Opens a REST session: {@code IAuthentication.login}.
   *
   * @param root the REST root
   * @param login the login
   * @param password the password, sent as the body
   * @return the session key
   */
  private String login(String root, String login, String password) {
    URI named = URI.create(root + "/api/auth/login");
    URI uri = URI.create(named + "?login=" + URLEncoder.encode(login, StandardCharsets.UTF_8) + "&origin=" + LOGIN_ORIGIN);
    HttpRequest request = HttpRequest.newBuilder(uri)
                                     .timeout(REQUEST_TIMEOUT)
                                     .header("Content-Type", "text/plain; charset=utf-8")
                                     .header("Accept", "application/json")
                                     .POST(BodyPublishers.ofString(password, StandardCharsets.UTF_8))
                                     .build();
    Answer answer = send(request, named);
    if (answer.status() == 401 || answer.status() == 403) {
      throw new CalDavAuthenticationException("The calendar server refused the login for POST " + named);
    }
    checkGateway(answer.status(), "POST", named);
    if (answer.status() != 200) {
      throw new CalDavException("The calendar server answered " + answer.status() + " for POST " + named);
    }
    JsonNode response = parse(answer.body(), named);
    String status = textOf(response, "status");
    String key = textOf(response, "authKey");
    if (!"Ok".equals(status) || StringUtils.isBlank(key)) {
      throw new CalDavAuthenticationException("The calendar server refused the login (" + StringUtils.defaultString(status, "no status")
          + ") for POST " + named);
    }
    return key;
  }

  /**
   * Closes a REST session: {@code IAuthentication.logout}. A failure is only
   * noted: the session expires on the server's side.
   *
   * @param root the REST root
   * @param key the session key
   */
  private void logout(String root, String key) {
    URI uri = URI.create(root + "/api/auth/logout");
    try {
      send(HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header(API_KEY_HEADER, key).POST(BodyPublishers.noBody()).build(),
           uri);
    } catch (RuntimeException e) {
      LOG.debug("The REST session opened on {} could not be closed; it expires on the server's side", root);
    }
  }

  /**
   * The access list in an answer: a JSON array of {@code subject} and
   * {@code verb}. Anything else is refused rather than read partly, since a
   * grant is confirmed on it.
   *
   * @param body the answer's body
   * @param uri the request, for the message
   * @return the entries
   */
  private List<BlueMindAce> acesOf(String body, URI uri) {
    JsonNode list = parse(body, uri);
    if (!list.isArray()) {
      throw new CalDavException("The calendar server answered no access list for GET " + uri);
    }
    List<BlueMindAce> aces = new ArrayList<>();
    for (JsonNode entry : list) {
      String subject = textOf(entry, "subject");
      String verb = textOf(entry, "verb");
      if (StringUtils.isBlank(subject) || StringUtils.isBlank(verb)) {
        throw new CalDavException("The calendar server answered an access entry without subject or verb for GET " + uri);
      }
      aces.add(new BlueMindAce(subject, verb));
    }
    return aces;
  }

  /**
   * One text member of a JSON object.
   *
   * @param node the object
   * @param name the member
   * @return its text, or null
   */
  private static String textOf(JsonNode node, String name) {
    JsonNode member = node == null ? null : node.get(name);
    return member == null || member.isNull() || !member.isValueNode() ? null : StringUtils.trimToNull(member.asText());
  }

  /**
   * Parses a JSON answer.
   *
   * @param body the body
   * @param uri the request, for the message
   * @return the tree
   */
  private JsonNode parse(String body, URI uri) {
    try {
      return mapper.readTree(body);
    } catch (RuntimeException e) {
      throw new CalDavException("The calendar server answered something that is not JSON for " + uri);
    }
  }

  /**
   * A gateway status becomes the unreachable failure, as on the DAV side.
   *
   * @param status the status
   * @param method the method
   * @param uri the request, without its query
   */
  private static void checkGateway(int status, String method, URI uri) {
    if (status == 502 || status == 503 || status == 504) {
      throw new CalDavUnreachableException("The calendar server could not be reached (" + status + ") for " + method + " " + uri);
    }
  }

  /**
   * Sends a request and reads a bounded body. A redirect is refused, never
   * followed; transport failure is unreachable. Messages name the request
   * without its query, and never a header.
   *
   * @param request the request
   * @param named the URI to name in messages
   * @return status and body
   */
  private Answer send(HttpRequest request, URI named) {
    try {
      HttpResponse<InputStream> response = httpClient.send(request, BodyHandlers.ofInputStream());
      byte[] bytes;
      try (InputStream stream = response.body()) {
        bytes = stream == null ? new byte[0] : stream.readNBytes((int) (MAX_BODY_BYTES + 1));
      }
      if (bytes.length > MAX_BODY_BYTES) {
        throw new CalDavException("The calendar server answered more than " + MAX_BODY_BYTES + " bytes for " + named);
      }
      int status = response.statusCode();
      if (status >= 300 && status < 400) {
        throw new CalDavException("The calendar server answered a redirect (" + status + ") for " + named
            + ", which this client never follows");
      }
      return new Answer(status, new String(bytes, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new CalDavUnreachableException("The calendar server could not be reached at " + named, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CalDavException("Interrupted while talking to " + named, e);
    }
  }

  /**
   * One path segment, percent-encoded outside the unreserved set.
   *
   * @param segment the decoded segment
   * @return the encoded segment
   */
  static String encodeSegment(String segment) {
    StringBuilder encoded = new StringBuilder();
    for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xFF);
      if (b >= 0 && UNRESERVED.indexOf(c) >= 0) {
        encoded.append(c);
      } else {
        encoded.append('%').append(String.format("%02X", b & 0xFF));
      }
    }
    return encoded.toString();
  }

  /**
   * An answer: status and body.
   *
   * @param status the HTTP status
   * @param body the body text
   */
  private record Answer(int status, String body) {
  }
}
