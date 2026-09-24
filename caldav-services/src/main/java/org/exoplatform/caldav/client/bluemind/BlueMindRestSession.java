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
import org.exoplatform.caldav.client.bluemind.BlueMindSessionCache.Key;
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
 * <li><b>A session, reused for a short while.</b>
 * {@code POST /api/auth/login?login=…} with the password as a JSON string
 * under exactly {@code Content-Type: application/json} — what BlueMind's own
 * client proxy sends ({@code ClientProxyGenerator.java},
 * {@code ByMimeTypeCodec.encode}); BlueMind picks the body codec by the exact
 * {@code Content-Type} value ({@code DefaultBodyParameterCodecs.java}), so a
 * {@code text/plain} carrying a charset parameter would be read as JSON,
 * refused with a 500 and logged by BlueMind with the password in it — answers
 * a {@code LoginResponse} whose {@code authKey} travels in the
 * {@code X-BM-ApiKey} header
 * ({@code parent/core/net.bluemind.core.rest/.../base/RestRootHandler.java}
 * reads it, {@code BasicClientProxy.java} sends it). Since EXO-90397 that key
 * is kept for a few minutes, in <b>this node's own memory</b> and nowhere
 * else ({@link BlueMindSessionCache}), so the next read costs one request
 * rather than three; the rules that follow from it are on {@link #call}. The
 * key and the password are never logged, never written to the database,
 * never handed to a caller — {@link Session} exposes who the session is, not
 * what authenticates it — and never part of an exception message.</li>
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

  private final BlueMindSessionCache    sessions;

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
   * @param sessions the short-lived sessions kept per account
   */
  @Autowired
  public BlueMindRestSession(CaldavCredentialsResolver caldavCredentialsResolver, BlueMindSessionCache sessions) {
    this(HttpClient.newBuilder()
                   .connectTimeout(Duration.ofSeconds(10))
                   .followRedirects(HttpClient.Redirect.NEVER)
                   .build(),
         caldavCredentialsResolver,
         sessions);
  }

  /**
   * The seam the protocol tests use: a session over a handed-in transport
   * that keeps nothing, so each call opens and closes its own session — the
   * shape a call takes when the endpoint cannot be keyed.
   *
   * @param httpClient the transport
   * @param caldavCredentialsResolver the credentials seam
   */
  BlueMindRestSession(HttpClient httpClient, CaldavCredentialsResolver caldavCredentialsResolver) {
    this(httpClient, caldavCredentialsResolver, BlueMindSessionCache.unpooled());
  }

  /**
   * The seam the reuse tests use: a session over a handed-in transport and a
   * handed-in store.
   *
   * @param httpClient the transport
   * @param caldavCredentialsResolver the credentials seam
   * @param sessions the sessions kept per account
   */
  BlueMindRestSession(HttpClient httpClient, CaldavCredentialsResolver caldavCredentialsResolver,
                      BlueMindSessionCache sessions) {
    this.httpClient = httpClient;
    this.caldavCredentialsResolver = caldavCredentialsResolver;
    this.sessions = sessions;
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
   * Runs one job inside a REST session opened as the account: the session
   * kept for that account when there is one, a fresh login otherwise.
   *
   * <p>
   * <b>What is reused, and for whom.</b> The session is looked up by the
   * account it acts as — the declared server, the eXo login the credentials
   * are produced for, and the account those credentials address on that
   * server ({@link BlueMindSessionCache.Key}) — so no session ever serves an
   * account other than the one it was minted for. An endpoint minted from a
   * DAV account name rather than for an eXo user carries no login to key on,
   * and neither does one whose provider cannot name the account it would
   * address: such a call opens its own session and closes it, as every call
   * did before EXO-90397.
   *
   * <p>
   * <b>Never to another address.</b> The kept session records the REST root
   * it was opened at; an endpoint that now resolves elsewhere — an
   * administrator changed the declared server's URL — does not reuse it. The
   * key is a credential of one host and is never sent to another.
   *
   * <p>
   * <b>An expired or refused session re-logs in once.</b> BlueMind expires a
   * session on its own clock, and this add-on does not measure it (EXO-89647
   * is where that measurement lands), so the kept entry's lifetime is set
   * well below any plausible server-side one and the refusal is handled
   * rather than predicted: a 401 on a reused session drops the entry, opens
   * another and sends the request again — once per call, tracked on the
   * session itself, so a server refusing everything costs one extra login and
   * never a loop.
   *
   * <p>
   * <b>Logout.</b> A session nothing keeps is logged out when the job ends,
   * as before — and <i>nothing keeps it</i> covers three cases, not one: the
   * endpoint that cannot be keyed at all, the store that had no room for
   * another account ({@code maxAccounts}), and the session another thread's
   * took the place of. A kept one is not closed: it is closed when eXo drops
   * it on purpose ({@link #forget}), and otherwise expires on BlueMind's own
   * clock when the entry expires here first — the same fallback a failed
   * logout has always relied on. A session refused by BlueMind is already
   * closed on its side and is dropped without one.
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
    Key key = keyOf(endpoint);
    if (key == null) {
      // Nothing can keep it, so this call closes it — the one rule below,
      // reached by the one endpoint that never has an entry at all.
      Session own = new Session(root, endpoint, null, mint(root, endpoint), false, true);
      try {
        return job.apply(own);
      } finally {
        own.closeIfMine();
      }
    }
    Login held = sessions.held(key);
    if (held != null && !root.equals(held.apiRoot())) {
      // Minted for another address: the declared server moved under it. It is
      // not sent anywhere, and the entry goes rather than being believed. It
      // is closed at the address it was opened at, which is the only one that
      // knows it.
      if (sessions.drop(key, held)) {
        logout(held.apiRoot(), held.key());
      }
      held = null;
    }
    boolean reused = held != null;
    Acquired acquired = reused ? new Acquired(held, false) : acquire(key, root, endpoint);
    Session session = new Session(root, endpoint, key, acquired.login(), reused, acquired.mine());
    try {
      return job.apply(session);
    } finally {
      session.closeIfMine();
    }
  }

  /**
   * Drops the session kept for one account and closes it, because eXo itself
   * changed what that account is: new credentials, a reconnection, a
   * disconnection.
   *
   * <p>
   * The logout is best-effort and never fails the caller: a session that
   * could not be closed expires on BlueMind's own clock.
   *
   * @param serverId the declared server registration, zero for the legacy
   *          deployment property
   * @param exoLogin the eXo login whose session goes
   */
  public void forget(long serverId, String exoLogin) {
    if (StringUtils.isBlank(exoLogin)) {
      return;
    }
    for (Login dropped : sessions.forget(serverId, exoLogin)) {
      logout(dropped.apiRoot(), dropped.key());
    }
  }

  /**
   * Drops every kept session, because the declared servers changed under
   * them. The sessions are not closed — the entries carrying them are gone —
   * so they expire on BlueMind's own clock.
   */
  public void forgetAll() {
    sessions.forgetAll();
  }

  /**
   * The account a session is kept for, or null when this endpoint cannot be
   * keyed and must therefore open and close its own session.
   *
   * @param endpoint the account's endpoint
   * @return the key, or null
   */
  private Key keyOf(CalDavEndpoint endpoint) {
    if (!sessions.keeps() || StringUtils.isBlank(endpoint.getExoLogin())) {
      return null;
    }
    String actsAs;
    try {
      actsAs = caldavCredentialsResolver.targetAccount(endpoint.getServerId(),
                                                       endpoint.getAuthProviderName(),
                                                       endpoint.getExoLogin());
    } catch (RuntimeException e) {
      // A provider that cannot say which account it would address cannot have
      // its session kept: the third field of the key is what says the session
      // is this mailbox's and no other. The call still goes through, opening
      // and closing its own session as it did before EXO-90397.
      LOG.debug("The configured provider could not name the account it addresses for {}; its session is not kept", endpoint, e);
      return null;
    }
    if (StringUtils.isBlank(actsAs)) {
      return null;
    }
    return new Key(endpoint.getServerId() == null ? 0L : endpoint.getServerId(), endpoint.getExoLogin(), actsAs);
  }

  /**
   * A session to use, and whether closing it is this call's business.
   *
   * @param login the session
   * @param mine true when this call opened it and nothing kept it, so that
   *          nobody else will ever present it and the job's end is the only
   *          moment it can be closed at
   */
  private record Acquired(Login login, boolean mine) {
  }

  /**
   * Opens a session for an account and keeps it — unless another thread kept
   * one first, in which case that one is used and the redundant session is
   * closed at once rather than left open on the server.
   *
   * <p>
   * The store may also decline to keep it, having no room left
   * ({@code exo.agenda.caldav.bluemind.session.maxAccounts}). That session is
   * used for this call and closed when it ends, exactly as every call did
   * before EXO-90397: a session nothing will reuse must not be left open on
   * the server until BlueMind expires it, and past the bound there would be
   * one of those per call.
   *
   * @param key the account
   * @param root the REST root
   * @param endpoint the account's endpoint
   * @return the session to use, and who closes it
   */
  private Acquired acquire(Key key, String root, CalDavEndpoint endpoint) {
    Login fresh = mint(root, endpoint);
    Login winner = sessions.keep(key, fresh);
    if (winner == null) {
      return new Acquired(fresh, !sessions.holds(key, fresh));
    }
    if (root.equals(winner.apiRoot())) {
      logout(root, fresh.key());
      return new Acquired(winner, false);
    }
    // The entry that won names another address, so it is the one that goes —
    // and it is closed at the address it was opened at, the only one that
    // knows it, rather than left open there for nothing.
    sessions.replace(key, fresh);
    logout(winner.apiRoot(), winner.key());
    return new Acquired(fresh, !sessions.holds(key, fresh));
  }

  /**
   * Opens a session as the account: its configured credentials, then the
   * login.
   *
   * @param root the REST root
   * @param endpoint the account's endpoint
   * @return the session
   */
  private Login mint(String root, CalDavEndpoint endpoint) {
    String[] account = accountOf(endpoint);
    try {
      return login(root, account[0], account[1]);
    } catch (CalDavAuthenticationException e) {
      if (endpoint.getExoLogin() == null || !caldavCredentialsResolver.retriesAfterRefusal(endpoint.getAuthProviderName())) {
        // A provider carrying what the user typed would hand the same password back:
        // a second failed login against the user's account for nothing.
        throw e;
      }
      // The one retry the credentials contract allows (EXO-89649): the login's
      // password is material the provider produced - under bluemind-sudo a kept
      // BlueMind session - and it may have gone stale. The provider is told once and
      // the login tried once more on fresh material; that answer is the answer.
      // Unlike the DAV paths, which retry on a 401 only, this one retries on any login
      // refusal - 401, 403 or a 200 answering status Bad - because a login endpoint
      // states a refused password in any of the three, and which one BlueMind uses for
      // a stale session is not known.
      LOG.debug("BlueMind refused the provider's material for {}; logging in once more with fresh material",
                endpoint.getExoLogin(),
                e);
      caldavCredentialsResolver.invalidate(endpoint.getServerId(), endpoint.getAuthProviderName(), endpoint.getExoLogin());
      String[] fresh = accountOf(endpoint);
      return login(root, fresh[0], fresh[1]);
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
   * What a login answered and a session carries: the address it answered at,
   * the key, and who BlueMind says the session belongs to.
   *
   * <p>
   * Package-private rather than private because it is what
   * {@link BlueMindSessionCache} keeps. Its {@code toString} is overridden
   * so that the key cannot reach a log line or an exception message through a
   * record's generated one — the rule the whole class is written under.
   *
   * @param apiRoot the REST root this session was opened at, which is the
   *          only address its key may ever be sent to
   * @param key the session key, sent in {@link #API_KEY_HEADER}
   * @param userUid the directory entry uid of the authenticated user
   *          ({@code LoginResponse.authUser.uid}), or null when the answer
   *          named none
   * @param domainUid the uid of their domain
   *          ({@code LoginResponse.authUser.domainUid}), or null when the
   *          answer named none
   */
  record Login(String apiRoot, String key, String userUid, String domainUid) {

    /**
     * Names the session without ever naming its key — safe in logs.
     *
     * @return the session described by its address and its authenticated user
     */
    @Override
    public String toString() {
      return "BlueMindSession[apiRoot=" + apiRoot + ", userUid=" + userUid + "]";
    }
  }

  /**
   * Opens a REST session: {@code IAuthentication.login}. The password is the
   * body, encoded as a JSON string under exactly
   * {@code Content-Type: application/json}; see the class comment for why no
   * other shape is safe.
   *
   * <p>
   * Besides the key, the answer names the authenticated user: {@code authUser}
   * carries the directory entry {@code uid} and the {@code domainUid}
   * ({@code parent/authentication/net.bluemind.authentication.api/.../LoginResponse.java},
   * {@code AuthUser.java}). Both are read here and carried on the session,
   * because a call addressed to an account — a subscription edit — needs the
   * domain in its path and must be able to check that the account it is about
   * to edit is the one the session opened (EXO-90277). Their absence is not a
   * refusal: a caller needing them says so itself.
   *
   * @param root the REST root
   * @param login the login
   * @param password the password
   * @return the key and the authenticated user
   */
  private Login login(String root, String login, String password) {
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
    JsonNode authUser = response.get("authUser");
    return new Login(root, key, textOf(authUser, "uid"), textOf(authUser, "domainUid"));
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

    private final String         root;

    private final CalDavEndpoint endpoint;

    /** The account this session is kept for, or null when none keeps it. */
    private final Key            key;

    /** Whether the session came from the store rather than from a login. */
    private final boolean        reused;

    /** Whether this call has already spent its one re-login. */
    private boolean              renewed;

    /**
     * Whether this call opened the session it now holds and nothing kept it,
     * so that closing it is this call's business and nobody else's.
     */
    private boolean              mine;

    private Login                login;

    /**
     * An open session.
     *
     * @param root the REST root
     * @param endpoint the account's endpoint, which a re-login is made from
     * @param key the account this session is kept for, or null
     * @param login the key and the authenticated user
     * @param reused whether the session came from the store
     * @param mine whether this call opened it and nothing kept it
     */
    private Session(String root, CalDavEndpoint endpoint, Key key, Login login, boolean reused, boolean mine) {
      this.root = root;
      this.endpoint = endpoint;
      this.key = key;
      this.login = login;
      this.reused = reused;
      this.mine = mine;
    }

    /**
     * Closes the session when this call is the only one that ever held it:
     * the store had no room for it, or another thread's session took its
     * place. A session the store keeps is left open for the next caller, and
     * a session BlueMind refused is already closed on its side.
     *
     * <p>
     * This is what makes the {@code call} Javadoc's "a session nothing keeps
     * is logged out when the job ends" true of the keyed path too, and not
     * only of the endpoint that cannot be keyed at all: past
     * {@code maxAccounts} every call opens a session nothing will reuse, and
     * leaving each of those open until BlueMind expires it would leak one
     * server-side session per call (EXO-90397, review round 1).
     */
    private void closeIfMine() {
      if (mine) {
        logout(root, login.key());
      }
    }

    /**
     * The directory entry uid BlueMind authenticated this session as, the
     * segment of the account's DAV principal
     * {@code /dav/principals/__uids__/<uid>/}.
     *
     * @return the uid, or null when the login answer named none
     */
    public String userUid() {
      return login.userUid();
    }

    /**
     * The uid of the domain the authenticated account belongs to: the
     * {@code {domainUid}} segment of BlueMind's per-user REST paths.
     *
     * @return the domain uid, or null when the login answer named none
     */
    public String domainUid() {
      return login.domainUid();
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
     * A POST with a body.
     *
     * @param path the path under the root
     * @param body the body text
     * @param contentType its media type, sent exactly as given
     * @return the answer
     */
    public Answer post(String path, String body, String contentType) {
      return exchange("POST", path, body, contentType);
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
     * One call of the session, the key on it — and, when a kept session is
     * refused, the same call again on a session opened afresh.
     *
     * <p>
     * The retry is what makes reuse safe without knowing how long BlueMind
     * keeps a session: a 401 is the server saying this key is no longer one,
     * which cannot be told from an expiry and does not need to be. It is
     * spent at most once per call, and only on a session that came from the
     * store — a session this very call opened and that is refused is a
     * refusal to report, not a staleness to repair.
     *
     * @param method the HTTP method
     * @param path the path under the root
     * @param body the body, or null for none
     * @param contentType the body's media type, or null with no body
     * @return the answer
     */
    private Answer exchange(String method, String path, String body, String contentType) {
      Answer answer = send(request(method, path, body, contentType), named(path));
      if (answer.status() == 401 && key != null && reused && !renewed) {
        renewed = true;
        // Refused means closed on the server's side: the entry goes without a
        // logout, and the key it held is never sent again. Only this account's
        // entry, and only while it is still the one that was refused: another
        // thread that has already put a fresh session there is not chased out
        // of it, and the accounts this user's credentials address elsewhere on
        // this server were not refused and are not dropped.
        sessions.drop(key, login);
        LOG.debug("The REST session kept for {} was refused; another is opened for this call", key);
        Acquired again = acquire(key, root, endpoint);
        login = again.login();
        mine = again.mine();
        answer = send(request(method, path, body, contentType), named(path));
      }
      checkGateway(answer.status(), method, named(path));
      return answer;
    }

    /**
     * The request of one call, carrying the session's current key.
     *
     * @param method the HTTP method
     * @param path the path under the root
     * @param body the body, or null for none
     * @param contentType the body's media type, or null with no body
     * @return the request to send
     */
    private HttpRequest request(String method, String path, String body, String contentType) {
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(root + path))
                                               .timeout(REQUEST_TIMEOUT)
                                               .header(API_KEY_HEADER, login.key())
                                               .header("Accept", JSON_MEDIA_TYPE);
      if (body == null) {
        builder.method(method, BodyPublishers.noBody());
      } else {
        builder.header("Content-Type", contentType).method(method, BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      }
      return builder.build();
    }
  }
}
