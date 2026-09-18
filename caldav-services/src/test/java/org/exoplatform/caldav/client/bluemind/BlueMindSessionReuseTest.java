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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.TestEndpoints;
import org.exoplatform.caldav.client.bluemind.BlueMindRestSession.Login;
import org.exoplatform.caldav.client.bluemind.BlueMindSessionCache.Key;
import org.exoplatform.caldav.provider.CaldavCredentialsResolver;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsContext;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.HttpConnectorCredentials;

/**
 * The BlueMind REST session kept per account (EXO-90397): what a sequence of
 * reads costs, and the four things reuse must never do.
 *
 * <p>
 * <b>Against the real store, never a mock.</b> The client runs here over a
 * real {@link BlueMindSessionCache} — its own keys, its own lifetime, its own
 * bound — because a mocked store answers a drifted key exactly as it answers
 * the right one, and a key drift on this value is one account served
 * another's session. Only the clock is handed in, so the lifetime is
 * exercised rather than waited out.
 *
 * <p>
 * The requests are counted, not merely inspected: the whole point of the
 * change is a number, and only a count can fail if the login comes back.
 */
public class BlueMindSessionReuseTest {

  private static final String         ROOT_LOGIN  = "francois@bm.example.com";

  private static final String         ERIC_LOGIN  = "eric@bm.example.com";

  private static final String         PASSWORD    = "Fr@n:co\"is\\ Pass1!";

  private static final String         ROOT_KEY    = "bm-session-7f1e9c2a-4d6b-4e1f-9a3c-5b8d2e0f6a41";

  private static final String         SECOND_KEY  = "bm-session-0b4d8e1c-2f37-4a95-8c60-1d3e5f7a9b20";

  private static final String         ERIC_KEY    = "bm-session-5a2c7e90-6b18-4f3d-a472-8e0c1b9d4f63";

  private static final String         ROOT_UID    = "9F3C1A20-4D5E-4B7A-8C61-2E0D7A4B9C13";

  private static final String         ERIC_UID    = "4C60FEDD-0562-4903-A524-E95E1CCBCDE0";

  private static final String         CONTAINER   = "exo-cal-9f1c2d3e-4b5a-6c7d-8e9f-0a1b2c3d4e5f";

  private static final long           SERVER      = 1L;

  private static final int            TTL_SECONDS = 300;

  private static final int            MAX_ACCOUNTS = 3;

  private static final URI            BASE        =
                                           URI.create("https://bm.example.com:8443/dav/calendars/__uids__/" + ROOT_UID + "/");

  private static final URI            MOVED       =
                                           URI.create("https://bm2.example.com:8443/dav/calendars/__uids__/" + ROOT_UID + "/");

  private final List<HttpRequest>     sent        = new ArrayList<>();

  private final Deque<Object>         answers     = new ArrayDeque<>();

  private BlueMindSessionCache        sessions;

  private long                        now         = 1_700_000_000_000L;

  /**
   * The BlueMind account root's credentials address. A deployment changes it
   * by re-pointing the provider — from the eXo username to the mail address,
   * say — and that is a different mailbox, not a different spelling.
   */
  private String                      rootAccount = ROOT_LOGIN;

  private BlueMindRestSession         session;

  private BlueMindAclClient           client;

  private CalDavEndpoint              rootHere;

  private CalDavEndpoint              rootMoved;

  private CalDavEndpoint              eric;

  private CalDavEndpoint              anonymous;

  private Logger                      logger;

  private ListAppender<ILoggingEvent> logged;

  private Level                       previousLevel;

  /**
   * A client over a mocked transport answering from a queue, a real cache
   * behind the session store, and credentials produced per eXo login so that
   * two users are two accounts.
   *
   * @throws Exception never — the mocks declare it
   */
  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    HttpClient transport = mock(HttpClient.class);
    when(transport.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
      sent.add(invocation.getArgument(0));
      Object next = answers.removeFirst();
      if (next instanceof IOException failure) {
        throw failure;
      }
      return next;
    });
    ConnectorCredentialsService credentials = mock(ConnectorCredentialsService.class);
    lenient().doAnswer(invocation -> {
      ConnectorCredentialsContext context = invocation.getArgument(0);
      return new HttpConnectorCredentials(basic(bluemindLoginOf(context.getUsername())), null);
    }).when(credentials).produce(any());
    // The account the configured provider would address, which is the third
    // field of the key: the Personal provider answers the stored DAV login,
    // the technical-account one the target it is about to sudo to.
    lenient().doAnswer(invocation -> bluemindLoginOf(((ConnectorCredentialsContext) invocation.getArgument(0)).getUsername()))
             .when(credentials)
             .resolveTargetIdentity(any());
    sessions = new BlueMindSessionCache(TTL_SECONDS, MAX_ACCOUNTS, () -> now);
    session = new BlueMindRestSession(transport, new CaldavCredentialsResolver(credentials), sessions);
    client = new BlueMindAclClient(session);
    rootHere = TestEndpoints.endpoint(SERVER, BASE, "personal", "root");
    rootMoved = TestEndpoints.endpoint(SERVER, MOVED, "personal", "root");
    eric = TestEndpoints.endpoint(SERVER, BASE, "personal", "eric");
    anonymous = TestEndpoints.endpoint(SERVER, BASE, "personal", null);

    logger = (Logger) LoggerFactory.getLogger(BlueMindRestSession.class);
    previousLevel = logger.getLevel();
    logger.setLevel(Level.TRACE);
    logged = new ListAppender<>();
    logged.start();
    logger.addAppender(logged);
  }

  /**
   * The logger is left as found.
   */
  @AfterEach
  void restoreLogger() {
    logger.detachAppender(logged);
    logger.setLevel(previousLevel);
  }

  /**
   * <b>The number this change is about.</b> Two reads of one account used to
   * cost six requests — a login, the read and a logout, twice. They now cost
   * three: one login and two reads, and no logout at all, because the session
   * is kept for the next caller rather than closed behind this one.
   */
  @Test
  void aSecondReadOfOneAccountCostsOneRequestRatherThanThree() {
    answer(200, loginOk(ROOT_KEY, ROOT_UID));
    answer(200, "[]");
    answer(200, "[]");

    client.readAcl(rootHere, CONTAINER);
    client.readAcl(rootHere, CONTAINER);

    assertEquals(3, sent.size(), "one login and two reads");
    assertEquals(1, countOf("/api/auth/login"), "the second read reuses the session");
    assertEquals(0, countOf("/api/auth/logout"), "a kept session is not closed behind the call that used it");
    assertEquals(ROOT_KEY, sent.get(1).headers().firstValue(BlueMindRestSession.API_KEY_HEADER).orElse(null));
    assertEquals(ROOT_KEY, sent.get(2).headers().firstValue(BlueMindRestSession.API_KEY_HEADER).orElse(null));
  }

  /**
   * <b>The entry has a lifetime, and it is this node's own clock that ends
   * it.</b> A read one second before the entry expires reuses the session; a
   * read one second after opens another. No logout is sent for the one that
   * expired — it is left to BlueMind's own clock, which is what the short
   * lifetime is chosen to stay well inside.
   */
  @Test
  void anEntryIsKeptForItsLifetimeAndNoLonger() {
    answer(200, loginOk(ROOT_KEY, ROOT_UID));
    answer(200, "[]");
    answer(200, "[]");
    answer(200, loginOk(SECOND_KEY, ROOT_UID));
    answer(200, "[]");

    client.readAcl(rootHere, CONTAINER);
    now += TTL_SECONDS * 1000L - 1000L;
    client.readAcl(rootHere, CONTAINER);

    assertEquals(1, countOf("/api/auth/login"), "still inside the lifetime");

    now += 2000L;
    client.readAcl(rootHere, CONTAINER);

    assertEquals(2, countOf("/api/auth/login"), "past it, another session is opened");
    assertEquals(0, countOf("/api/auth/logout"), "an entry that expires here is left to expire there too");
    assertEquals(SECOND_KEY, keyOf(sent.get(4)));
    assertEquals(1, sessions.size(), "the expired entry did not stay behind");
  }

  /**
   * <b>The store is bounded.</b> Past its size an account is served without
   * being kept, so the map cannot grow with the user base of an instance
   * nobody sized it for — and the accounts already kept are not evicted to
   * make room for one that has not been asked for twice.
   */
  @Test
  void thereAreNeverMoreAccountsKeptThanTheBound() {
    for (int i = 0; i <= MAX_ACCOUNTS; i++) {
      answer(200, loginOk(ROOT_KEY + i, ROOT_UID));
      answer(200, "[]");
      client.readAcl(TestEndpoints.endpoint(SERVER + i, BASE, "personal", "root"), CONTAINER);
    }

    assertEquals(MAX_ACCOUNTS, sessions.size());
  }

  /**
   * <b>Two users never share a session.</b> Each account logs in on its own
   * and every read carries the key minted for it; the keys are the two
   * accounts' own, and the store's keys compare by their fields rather than
   * collapsing into one.
   */
  @Test
  void twoUsersOnOneServerNeverShareASession() {
    answer(200, loginOk(ROOT_KEY, ROOT_UID));
    answer(200, "[]");
    answer(200, loginOk(ERIC_KEY, ERIC_UID));
    answer(200, "[]");
    answer(200, "[]");

    client.readAcl(rootHere, CONTAINER);
    client.readAcl(eric, CONTAINER);
    client.readAcl(eric, CONTAINER);

    assertEquals(5, sent.size());
    assertEquals(2, countOf("/api/auth/login"), "one login each, and eric's second read reuses his own");
    assertEquals(ROOT_LOGIN, loginNameOf(sent.get(0)));
    assertEquals(ERIC_LOGIN, loginNameOf(sent.get(2)));
    assertEquals(ROOT_KEY, keyOf(sent.get(1)));
    assertEquals(ERIC_KEY, keyOf(sent.get(3)), "eric is never handed root's session");
    assertEquals(ERIC_KEY, keyOf(sent.get(4)));
    assertNotEquals(new Key(SERVER, "root", ROOT_LOGIN), new Key(SERVER, "eric", ERIC_LOGIN));
    assertNotEquals(new Key(SERVER, "root", ROOT_LOGIN), new Key(2L, "root", ROOT_LOGIN));
    assertNotEquals(new Key(SERVER, "root", ROOT_LOGIN), new Key(SERVER, "root", ERIC_LOGIN),
                    "the account the credentials address is a field of its own: the same eXo user pointed at another"
                        + " mailbox is another entry");
    assertEquals(new Key(SERVER, "root", ROOT_LOGIN), new Key(SERVER, "root", ROOT_LOGIN), "compared by its fields");
  }

  /**
   * <b>The account the credentials address is part of the key.</b> The
   * provider is re-pointed — the technical account now sudoes to the user's
   * mail address rather than their login, which on BlueMind is a different
   * entry — and the session opened for the previous account is not reused for
   * the new one. The same eXo user asking is not the same mailbox answered.
   */
  @Test
  void aSessionIsNotReusedWhenTheProviderNowAddressesAnotherAccount() {
    answer(200, loginOk(ROOT_KEY, ROOT_UID));
    answer(200, "[]");
    answer(200, loginOk(SECOND_KEY, ROOT_UID));
    answer(200, "[]");

    client.readAcl(rootHere, CONTAINER);
    rootAccount = "root@bm.example.com";
    client.readAcl(rootHere, CONTAINER);

    assertEquals(2, countOf("/api/auth/login"), "the new account is logged in to on its own");
    assertEquals(ROOT_LOGIN, loginNameOf(sent.get(0)));
    assertEquals("root@bm.example.com", loginNameOf(sent.get(2)));
    assertEquals(SECOND_KEY, keyOf(sent.get(3)), "the session of the previous account is not presented for this one");
  }

  /**
   * <b>A key is never sent to another address.</b> The declared server's URL
   * changed under the account: the kept session was opened at the old host,
   * so it is dropped rather than presented at the new one, and nothing that
   * leaves for the new host carries the old host's key.
   */
  @Test
  void aKeptSessionIsNeverPresentedAtAnotherAddress() {
    answer(200, loginOk(ROOT_KEY, ROOT_UID));
    answer(200, "[]");
    answer(200, loginOk(SECOND_KEY, ROOT_UID));
    answer(200, "[]");

    client.readAcl(rootHere, CONTAINER);
    client.readAcl(rootMoved, CONTAINER);

    assertEquals(4, sent.size());
    assertEquals(2, countOf("/api/auth/login"), "the new address is logged in to on its own");
    assertEquals("bm2.example.com", sent.get(2).uri().getHost());
    assertEquals(SECOND_KEY, keyOf(sent.get(3)));
    for (HttpRequest request : sent) {
      assertFalse("bm2.example.com".equals(request.uri().getHost()) && ROOT_KEY.equals(keyOf(request)),
                  "the key minted at bm.example.com never travels to bm2.example.com");
    }
  }

  /**
   * <b>An expired session re-logs in once, and does not loop.</b> BlueMind
   * refuses the kept key; the call opens another session, sends its request
   * again and answers normally. Exactly one extra login, and the refused key
   * is not logged out — the server has already closed it.
   */
  @Test
  void aRefusedSessionIsReopenedOnceAndTheCallSucceeds() {
    answer(200, loginOk(ROOT_KEY, ROOT_UID));
    answer(200, "[]");
    answer(401, "");
    answer(200, loginOk(SECOND_KEY, ROOT_UID));
    answer(200, "[]");
    answer(200, "[]");

    client.readAcl(rootHere, CONTAINER);
    client.readAcl(rootHere, CONTAINER);
    client.readAcl(rootHere, CONTAINER);

    assertEquals(6, sent.size());
    assertEquals(2, countOf("/api/auth/login"), "one re-login, not one per read");
    assertEquals(0, countOf("/api/auth/logout"), "a refused key is already closed on the server");
    assertEquals(ROOT_KEY, keyOf(sent.get(2)), "the refused read carried the stale key");
    assertEquals(SECOND_KEY, keyOf(sent.get(4)), "the read is sent again on the new session");
    assertEquals(SECOND_KEY, keyOf(sent.get(5)), "and the new session is the one now kept");
  }

  /**
   * <b>And it does not loop.</b> A server refusing the reopened session too
   * costs one extra login and no more; the refusal is then the caller's,
   * exactly as it was before the session was ever kept.
   */
  @Test
  void aSecondRefusalIsReportedRatherThanChased() {
    answer(200, loginOk(ROOT_KEY, ROOT_UID));
    answer(200, "[]");
    answer(401, "");
    answer(200, loginOk(SECOND_KEY, ROOT_UID));
    answer(401, "");

    client.readAcl(rootHere, CONTAINER);
    assertThrows(CalDavAuthenticationException.class, () -> client.readAcl(rootHere, CONTAINER));

    assertEquals(5, sent.size(), "one login, one read, the refused read, one re-login, one refused read");
    assertEquals(2, countOf("/api/auth/login"));
  }

  /**
   * <b>And a job making several calls spends one re-login between them
   * all.</b> The first call meets the stale key and reopens; the second is
   * refused by the new session too — a genuine refusal, not a staleness — and
   * is answered as it came rather than chased with another login. The
   * one-per-call budget is what makes a refusing server cost one extra
   * request instead of one per call for ever.
   */
  @Test
  void aJobThatIsRefusedTwiceSpendsOneReLoginBetweenAllItsCalls() {
    answer(200, loginOk(ROOT_KEY, ROOT_UID));
    answer(200, "[]");
    answer(401, "");
    answer(200, loginOk(SECOND_KEY, ROOT_UID));
    answer(200, "[]");
    answer(401, "");

    client.readAcl(rootHere, CONTAINER);
    List<Integer> statuses = session.call(rootHere, open -> List.of(open.get("/api/first").status(),
                                                                   open.get("/api/second").status()));

    assertEquals(List.of(200, 401), statuses, "the second refusal is handed to the job, not chased");
    assertEquals(6, sent.size());
    assertEquals(2, countOf("/api/auth/login"), "one re-login for the whole job");
  }

  /**
   * <b>A session this very call opened is not reopened.</b> A 401 on a
   * freshly minted session is BlueMind refusing what it has just handed out —
   * a refusal to report, not a staleness to repair — so no second login is
   * spent on it.
   */
  @Test
  void aSessionOpenedByThisCallIsNotReopenedOnARefusal() {
    answer(200, loginOk(ROOT_KEY, ROOT_UID));
    answer(401, "");

    assertThrows(CalDavAuthenticationException.class, () -> client.readAcl(rootHere, CONTAINER));

    assertEquals(2, sent.size(), "the login and the refused read, and nothing more");
    assertEquals(1, countOf("/api/auth/login"));
  }

  /**
   * <b>Dropping an account closes its session.</b> The account reconnected,
   * disconnected or changed credentials: the kept session is logged out at
   * once rather than left open until it expires, and the next read opens
   * another.
   */
  @Test
  void forgettingAnAccountLogsItsSessionOutAndTheNextReadOpensAnother() {
    answer(200, loginOk(ROOT_KEY, ROOT_UID));
    answer(200, "[]");
    answer(200, "");
    answer(200, loginOk(SECOND_KEY, ROOT_UID));
    answer(200, "[]");

    client.readAcl(rootHere, CONTAINER);
    session.forget(SERVER, "root");
    client.readAcl(rootHere, CONTAINER);

    assertEquals(5, sent.size());
    assertEquals("/api/auth/logout", sent.get(2).uri().getPath());
    assertEquals(ROOT_KEY, keyOf(sent.get(2)), "the session that was dropped is the one closed");
    assertEquals("/api/auth/login", sent.get(3).uri().getPath());
    assertEquals(SECOND_KEY, keyOf(sent.get(4)));
  }

  /**
   * Dropping one account leaves the other's session alone, and dropping every
   * account leaves none: a registration written under them makes them all
   * describe a server that no longer says what it said.
   */
  @Test
  void oneAccountIsDroppedAtATimeAndAllOfThemTogether() {
    answer(200, loginOk(ROOT_KEY, ROOT_UID));
    answer(200, "[]");
    answer(200, loginOk(ERIC_KEY, ERIC_UID));
    answer(200, "[]");
    answer(200, "");
    answer(200, "[]");
    answer(200, loginOk(SECOND_KEY, ROOT_UID));
    answer(200, "[]");
    answer(200, loginOk(ERIC_KEY, ERIC_UID));
    answer(200, "[]");

    client.readAcl(rootHere, CONTAINER);
    client.readAcl(eric, CONTAINER);
    session.forget(SERVER, "root");
    client.readAcl(eric, CONTAINER);

    assertEquals(6, sent.size(), "eric's session survived root's being dropped");
    assertEquals(2, countOf("/api/auth/login"));

    session.forgetAll();
    client.readAcl(rootHere, CONTAINER);
    client.readAcl(eric, CONTAINER);

    assertEquals(4, countOf("/api/auth/login"), "every account logs in again");
    assertEquals(1, countOf("/api/auth/logout"), "dropping them all closes none: they expire on the server's clock");
  }

  /**
   * <b>An endpoint with no eXo login keeps nothing.</b> It cannot be keyed by
   * the account it acts as, so it opens its own session and closes it — what
   * every call did before this change.
   */
  @Test
  void anEndpointMintedWithoutAnExoLoginOpensAndClosesItsOwnSession() {
    answer(200, loginOk(ROOT_KEY, ROOT_UID));
    answer(200, "[]");
    answer(200, "");
    answer(200, loginOk(SECOND_KEY, ROOT_UID));
    answer(200, "[]");
    answer(200, "");

    client.readAcl(anonymous, CONTAINER);
    client.readAcl(anonymous, CONTAINER);

    assertEquals(6, sent.size());
    assertEquals(2, countOf("/api/auth/login"));
    assertEquals(2, countOf("/api/auth/logout"));
  }

  /**
   * <b>The key stays a secret.</b> Nothing the reuse added — the store's
   * value, the debug lines it writes — names a session key, a password or the
   * Basic material, and a record's generated {@code toString} does not name
   * one either.
   */
  @Test
  void nothingTheReuseAddsEverNamesAKey() {
    answer(200, loginOk(ROOT_KEY, ROOT_UID));
    answer(200, "[]");
    answer(401, "");
    answer(200, loginOk(SECOND_KEY, ROOT_UID));
    answer(200, "[]");
    answer(200, "");

    client.readAcl(rootHere, CONTAINER);
    client.readAcl(rootHere, CONTAINER);
    session.forget(SERVER, "root");

    String kept = new Login("https://bm.example.com:8443", ROOT_KEY, ROOT_UID, "bm.example.com").toString();
    assertFalse(kept.contains(ROOT_KEY), kept);
    assertTrue(kept.contains(ROOT_UID), "it still says who the session is, which is what a log line needs");

    assertFalse(logged.list.isEmpty(), "the capture must see the client's own lines, or this test proves nothing");
    String basic = basic(ROOT_LOGIN).substring("Basic ".length());
    for (ILoggingEvent event : logged.list) {
      String message = event.getFormattedMessage();
      assertFalse(message.contains(ROOT_KEY), message);
      assertFalse(message.contains(SECOND_KEY), message);
      assertFalse(message.contains(PASSWORD), message);
      assertFalse(message.contains(basic), message);
    }
  }

  /**
   * How many requests went to a path.
   *
   * @param path the path
   * @return the count
   */
  private long countOf(String path) {
    return sent.stream().filter(request -> request.uri().getPath().equals(path)).count();
  }

  /**
   * The session key a request carried.
   *
   * @param request the request
   * @return the key, or null
   */
  private static String keyOf(HttpRequest request) {
    return request.headers().firstValue(BlueMindRestSession.API_KEY_HEADER).orElse(null);
  }

  /**
   * The login a {@code /api/auth/login} request named in its query.
   *
   * @param request the request
   * @return the decoded login
   */
  private static String loginNameOf(HttpRequest request) {
    String query = request.uri().getQuery();
    return query.substring("login=".length(), query.indexOf("&origin="));
  }

  /**
   * The BlueMind account an eXo login is served by, which is both what the
   * Basic header names and what the provider says it addresses.
   *
   * @param exoLogin the eXo login
   * @return the BlueMind login
   */
  private String bluemindLoginOf(String exoLogin) {
    return "eric".equals(exoLogin) ? ERIC_LOGIN : rootAccount;
  }

  /**
   * A Basic header for a login and the shared password.
   *
   * @param login the BlueMind login
   * @return the header value
   */
  private static String basic(String login) {
    return "Basic " + Base64.getEncoder().encodeToString((login + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * A login answer of the shape BlueMind's {@code LoginResponse} has, with a
   * chosen key so that two sessions can be told apart.
   *
   * @param key the session key
   * @param uid the authenticated directory entry uid
   * @return the JSON body
   */
  private static String loginOk(String key, String uid) {
    return "{\"status\":\"Ok\",\"authKey\":\"" + key + "\",\"authUser\":{\"uid\":\"" + uid
        + "\",\"domainUid\":\"bm.example.com\"}}";
  }

  /**
   * Queues an answer.
   *
   * @param status the status
   * @param body the body
   */
  @SuppressWarnings("unchecked")
  private void answer(int status, String body) {
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    lenient().when(response.statusCode()).thenReturn(status);
    lenient().when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    lenient().when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
    answers.add(response);
  }
}
