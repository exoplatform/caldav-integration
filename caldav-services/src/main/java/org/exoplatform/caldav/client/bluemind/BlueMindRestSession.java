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
package org.exoplatform.caldav.client.bluemind;

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
import java.util.Base64;
import java.util.Locale;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.provider.CaldavCredentialsResolver;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The one way eXo talks to BlueMind's own REST API: a short session opened
 * with the account's stored login and password, every call carrying the key
 * it answered, and the session always closed afterwards.
 *
 * <p>
 * Extracted from {@code BlueMindAclClient} (EXO-90253) when a second
 * conversation needed it (EXO-90307, the ICS import channel), so that both
 * share one transport, one authentication path and one set of rules:
 * <ul>
 * <li><b>Same server, never another.</b> The REST root is the scheme, host
 * and port of the endpoint minted from the server registry for the DAV calls,
 * with the {@code /api} root BlueMind serves its REST API under
 * ({@code RestServiceApiDescriptionParser.java} prefixes every path with
 * {@code /api}; {@code bluemind-vhosts.conf} proxies {@code location /api/}).
 * Nothing a caller passes can name a host; redirects are never followed.</li>
 * <li><b>The account's own credentials, from storage.</b> The login and
 * password are the ones the configured credentials provider produces for the
 * account's DAV requests, taken from its Basic header; a provider producing
 * anything else is not a login this API accepts.</li>
 * <li><b>A session for one job.</b> {@code POST /api/auth/login?login=…} with
 * the password as a JSON string under exactly
 * {@code Content-Type: application/json} — what BlueMind's own client proxy
 * sends ({@code ClientProxyGenerator.java}, {@code ByMimeTypeCodec.encode});
 * BlueMind picks the body codec by the exact {@code Content-Type} value
 * ({@code DefaultBodyParameterCodecs.java}), so a {@code text/plain} carrying
 * a charset parameter would be read as JSON, refused with a 500 and logged by
 * BlueMind with the password in it — answers a {@code LoginResponse} whose
 * {@code authKey} travels in the {@code X-BM-ApiKey} header
 * ({@code parent/core/net.bluemind.core.rest/.../base/RestRootHandler.java}
 * reads it, {@code BasicClientProxy.java} sends it); the key is used for the
 * calls and {@code POST /api/auth/logout} is always sent after them. The key
 * and the password are never logged, never stored, and never part of an
 * exception message.</li>
 * <li><b>Bounded answers.</b> Every body is read up to a fixed size and
 * refused beyond it: nothing this add-on reads from this API is large.</li>
 * </ul>
 */
@Component
public class BlueMindRestSession {

  /** The header BlueMind's REST API reads a session key from. */
  public static final String      API_KEY_HEADER  = "X-BM-ApiKey";

  /** The media type BlueMind's REST API reads and answers. */
  public static final String      JSON_MEDIA_TYPE = "application/json";

  /** What this add-on names itself as to BlueMind's login, for its logs. */
  static final String             LOGIN_ORIGIN    = "exo-caldav";

  private static final Log        LOG             = ExoLogger.getLogger(BlueMindRestSession.class);

  /** The longest answer read from this API: a list of a few entries. */
  private static final long       MAX_BODY_BYTES  = 1024L * 1024;

  private static final Duration   REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /** The characters left as they are in a path segment: RFC 3986 unreserved. */
  private static final String     UNRESERVED      =
                                             "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

  private final HttpClient                httpClient;

  private final CaldavCredentialsResolver caldavCredentialsResolver;

  private final JsonMapper                mapper  = JsonMapper.builder().build();

  /**
   * An answer: status and body text.
   *
   * @param status the HTTP status
   * @param body the body, decoded as UTF-8
   */
  public record Answer(int status, String body) {
  }

  /**
   * The transport Spring builds: no redirects, bounded timeouts.
   *
   * @param caldavCredentialsResolver the seam producing the account's
   *          credentials
   */
  @Autowired
  public BlueMindRestSession(CaldavCredentialsResolver caldavCredentialsResolver) {
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
  BlueMindRestSession(HttpClient httpClient, CaldavCredentialsResolver caldavCredentialsResolver) {
    this.httpClient = httpClient;
    this.caldavCredentialsResolver = caldavCredentialsResolver;
  }

  /**
   * Whether the account's configured credentials are a login and password
   * this API can take, without calling the server.
   *
   * @param endpoint the account's endpoint, minted from the registry
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
   * Runs one job inside a REST session opened as the account: log in, hand
   * the open session to the job, log out whatever the job did.
   *
   * @param <T> what the job produces
   * @param endpoint the account's DAV endpoint, minted from the registry
   * @param job the calls to make with the session
   * @return what the job produced
   * @throws UnsupportedOperationException when the configured credentials are
   *           not a login and password
   * @throws CalDavAuthenticationException when BlueMind refuses the login
   * @throws CalDavUnreachableException when the server cannot be reached
   * @throws CalDavException when the login answers anything else
   */
  public <T> T call(CalDavEndpoint endpoint, Function<Session, T> job) {
    String root = apiRootOf(endpoint);
    String[] account = accountOf(endpoint);
    String key = login(root, account[0], account[1]);
    try {
      return job.apply(new Session(root, key));
    } finally {
      logout(root, key);
    }
  }

  /**
   * Parses a JSON answer.
   *
   * @param body the body
   * @param uri the request, for the message
   * @return the tree
   * @throws CalDavException when the body is not JSON
   */
  public JsonNode parse(String body, URI uri) {
    try {
      return mapper.readTree(body);
    } catch (RuntimeException e) {
      throw new CalDavException("The calendar server answered something that is not JSON for " + uri);
    }
  }

  /**
   * One text member of a JSON object.
   *
   * @param node the object
   * @param name the member
   * @return its text, or null when absent, null or not a value
   */
  public static String textOf(JsonNode node, String name) {
    JsonNode member = node == null ? null : node.get(name);
    return member == null || member.isNull() || !member.isValueNode() ? null : StringUtils.trimToNull(member.asText());
  }

  /**
   * One path segment, percent-encoded outside the unreserved set.
   *
   * @param segment the decoded segment
   * @return the encoded segment
   */
  public static String encodeSegment(String segment) {
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
   * The REST root of the declared server: the endpoint's scheme, host and
   * port, and nothing a caller supplies.
   *
   * @param endpoint the endpoint minted from the registry
   * @return {@code scheme://host[:port]}
   * @throws CalDavException when the endpoint names no usable address
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
   * A gateway status becomes the unreachable failure, as on the DAV side.
   *
   * @param status the status
   * @param method the method
   * @param uri the request, without its query
   * @throws CalDavUnreachableException on 502, 503 and 504
   */
  static void checkGateway(int status, String method, URI uri) {
    if (status == 502 || status == 503 || status == 504) {
      throw new CalDavUnreachableException("The calendar server could not be reached (" + status + ") for " + method + " " + uri);
    }
  }

  /**
   * The account's login and password, as the configured provider produces
   * them for the DAV requests.
   *
   * @param endpoint the account's endpoint
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
   * Opens a REST session: {@code IAuthentication.login}. The password is the
   * body, encoded as a JSON string under exactly
   * {@code Content-Type: application/json}; see the class comment for why no
   * other shape is safe.
   *
   * @param root the REST root
   * @param login the login
   * @param password the password
   * @return the session key
   */
  private String login(String root, String login, String password) {
    URI named = URI.create(root + "/api/auth/login");
    URI uri = URI.create(named + "?login=" + URLEncoder.encode(login, StandardCharsets.UTF_8) + "&origin=" + LOGIN_ORIGIN);
    HttpRequest request = HttpRequest.newBuilder(uri)
                                     .timeout(REQUEST_TIMEOUT)
                                     .header("Content-Type", JSON_MEDIA_TYPE)
                                     .header("Accept", JSON_MEDIA_TYPE)
                                     .POST(BodyPublishers.ofString(mapper.writeValueAsString(password), StandardCharsets.UTF_8))
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
   * An open session: the REST root and the key every call carries. Paths are
   * given relative to the root and always start with {@code /api/}; a query
   * may follow, and is never named in a message.
   */
  public final class Session {

    private final String root;

    private final String key;

    /**
     * An open session.
     *
     * @param root the REST root
     * @param key the session key
     */
    private Session(String root, String key) {
      this.root = root;
      this.key = key;
    }

    /**
     * A GET.
     *
     * @param path the path under the root, query included if any
     * @return the answer
     */
    public Answer get(String path) {
      return exchange("GET", path, null, null);
    }

    /**
     * A PUT with a body.
     *
     * @param path the path under the root
     * @param body the body text
     * @param contentType its media type, sent exactly as given
     * @return the answer
     */
    public Answer put(String path, String body, String contentType) {
      return exchange("PUT", path, body, contentType);
    }

    /**
     * A DELETE.
     *
     * @param path the path under the root, query included if any
     * @return the answer
     */
    public Answer delete(String path) {
      return exchange("DELETE", path, null, null);
    }

    /**
     * The URI a message may name for a path: root and path, without the
     * query.
     *
     * @param path the path under the root
     * @return the URI, query stripped
     */
    public URI named(String path) {
      return URI.create(root + StringUtils.substringBefore(path, "?"));
    }

    /**
     * One call of the session, the key on it.
     *
     * @param method the HTTP method
     * @param path the path under the root
     * @param body the body, or null for none
     * @param contentType the body's media type, or null with no body
     * @return the answer
     */
    private Answer exchange(String method, String path, String body, String contentType) {
      URI uri = URI.create(root + path);
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                                               .timeout(REQUEST_TIMEOUT)
                                               .header(API_KEY_HEADER, key)
                                               .header("Accept", JSON_MEDIA_TYPE);
      if (body == null) {
        builder.method(method, BodyPublishers.noBody());
      } else {
        builder.header("Content-Type", contentType).method(method, BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      }
      Answer answer = send(builder.build(), named(path));
      checkGateway(answer.status(), method, named(path));
      return answer;
    }
  }
}
