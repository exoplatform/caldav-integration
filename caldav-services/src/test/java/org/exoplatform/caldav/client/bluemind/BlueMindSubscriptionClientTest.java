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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavForbiddenException;
import org.exoplatform.caldav.client.CalDavNotFoundException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.TestEndpoints;
import org.exoplatform.caldav.provider.CaldavCredentialsResolver;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.HttpConnectorCredentials;

/**
 * The REST conversation that subscribes a colleague to a calendar shared with
 * them (EXO-90277): log in as the <em>colleague</em>, check the session is
 * theirs, post the subscription, log out — against canned answers, never a
 * server.
 *
 * <p>
 * What is pinned is what a wrong line would silently cost: a request leaving
 * for any host but the declared server's (the colleague's password with it);
 * a subscription edited on an account other than the one BlueMind
 * authenticated (the trust boundary this feature opens, and the one check
 * that closes it); a key or a password in a log line or an exception message;
 * a session left open when the edit fails; and a fault read as a success.
 *
 * <p>
 * The login and fault answers are DERIVED fixtures, each naming the BlueMind
 * source it follows; {@code bluemind-rest-login-ok.derived.json} carries the
 * {@code authUser} the domain and the uid check are read from.
 */
public class BlueMindSubscriptionClientTest {

  /** The colleague's login on the rig: eric, connected in eXo as bob. */
  private static final String   LOGIN          = "eric.meyer@bm.example.com";

  /** A password with a colon, a space, a quote and a backslash. */
  private static final String   PASSWORD       = "Er!c:pa\"ss\\ word";

  private static final String   AUTHORIZATION  = "Basic "
      + Base64.getEncoder().encodeToString((LOGIN + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));

  /** The key in the DERIVED login answer. */
  private static final String   KEY            = "bm-session-7f1e9c2a-4d6b-4e1f-9a3c-5b8d2e0f6a41";

  /** The uid the DERIVED login answer authenticates: FRANCOIS's. */
  private static final String   LOGIN_UID      = "9F3C1A20-4D5E-4B7A-8C61-2E0D7A4B9C13";

  /** The domain in the DERIVED login answer. */
  private static final String   DOMAIN         = "bm.example.com";

  /** Somebody else: eric's uid, which the login answer does not authenticate. */
  private static final String   OTHER_UID      = "6B2E4F10-8A3C-4D7E-9B51-0C2D4E6F8A17";

  private static final String   CONTAINER      = "exo-cal-9f1c2d3e-4b5a-6c7d-8e9f-0a1b2c3d4e5f";

  /** The declared server: a port, a path, and a user part that must not travel. */
  private static final URI      BASE           = URI.create("https://sync@bm.example.com:8443/dav/calendars/__uids__/" + LOGIN_UID + "/");

  private static final String   SUBSCRIBE_PATH = "/api/users/" + DOMAIN + "/subscriptions/" + LOGIN_UID + "/_subscribe";

  private static final String   UNSUBSCRIBE_PATH = "/api/users/" + DOMAIN + "/subscriptions/" + LOGIN_UID + "/_unsubscribe";

  private final List<HttpRequest> sent         = new ArrayList<>();

  private final Deque<Object>   answers        = new ArrayDeque<>();

  private ConnectorCredentialsService credentials;

  /** Kept so that a test can build a client over a POOLED session too. */
  private HttpClient            transport;

  private BlueMindSubscriptionClient client;

  private CalDavEndpoint        endpoint;

  private Logger                logger;

  private ListAppender<ILoggingEvent> logged;

  private Level                 previousLevel;

  private Logger                own;

  private Level                 ownPreviousLevel;

  /**
   * A client over a mocked transport answering from a queue, the colleague's
   * credentials produced as a Basic header, and every line the session logs
   * captured at its most verbose.
   *
   * @throws Exception never — the mocks declare it
   */
  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    transport = mock(HttpClient.class);
    when(transport.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
      sent.add(invocation.getArgument(0));
      Object next = answers.removeFirst();
      if (next instanceof IOException failure) {
        throw failure;
      }
      return next;
    });
    credentials = mock(ConnectorCredentialsService.class);
    // The Personal provider carries what the user typed: it asks for user action, so
    // a refused credential is never retried on "fresh" material (EXO-89649).
    org.mockito.Mockito.lenient().when(credentials.requiresUserAction("personal")).thenReturn(true);
    lenient().doReturn(new HttpConnectorCredentials(AUTHORIZATION, null)).when(credentials).produce(any());
    lenient().doReturn(LOGIN).when(credentials).resolveTargetIdentity(any());
    client = new BlueMindSubscriptionClient(transport, new CaldavCredentialsResolver(credentials));
    endpoint = TestEndpoints.endpoint(1L, BASE, "personal", "bob");

    logger = (Logger) LoggerFactory.getLogger(BlueMindRestSession.class);
    previousLevel = logger.getLevel();
    logger.setLevel(Level.TRACE);
    logged = new ListAppender<>();
    logged.start();
    logger.addAppender(logged);
    own = (Logger) LoggerFactory.getLogger(BlueMindSubscriptionClient.class);
    ownPreviousLevel = own.getLevel();
    own.setLevel(Level.TRACE);
    own.addAppender(logged);
  }

  /**
   * Both loggers are left as found — level included. A level raised and not
   * put back outlives the test in the same JVM and quietly changes what every
   * later test of this class sees.
   */
  @AfterEach
  void restoreLogger() {
    logger.detachAppender(logged);
    logger.setLevel(previousLevel);
    own.detachAppender(logged);
    own.setLevel(ownPreviousLevel);
  }

  /**
   * Three requests, in order, all to the declared server: the login with the
   * colleague's login in the query and the password as a JSON string body;
   * the subscribe on {@code /api/users/<domainUid from the login
   * answer>/subscriptions/<the uid asked for>/_subscribe} with the key in
   * {@code X-BM-ApiKey} and exactly the body {@code IUserSubscription}
   * declares — {@code offlineSync} and {@code automount} both true (PO
   * decision 3); the logout with the same key. An empty 200 is success.
   */
  @Test
  void theColleagueIsSubscribedInOneSessionOfTheirOwn() {
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, derived("bluemind-rest-subscribe-200-empty.derived.json"));
    answer(200, "");

    client.subscribe(endpoint, LOGIN_UID, CONTAINER);

    assertEquals(3, sent.size());
    HttpRequest login = sent.get(0);
    assertEquals("POST", login.method());
    assertEquals("https://bm.example.com:8443/api/auth/login?login=eric.meyer%40bm.example.com&origin=exo-caldav",
                 login.uri().toString());
    assertEquals("application/json", login.headers().firstValue("Content-Type").orElse(null));
    assertEquals(PASSWORD, JsonMapper.builder().build().readValue(bodyOf(login), String.class));
    assertFalse(login.headers().firstValue("Authorization").isPresent());

    HttpRequest subscribe = sent.get(1);
    assertEquals("POST", subscribe.method());
    assertEquals("https://bm.example.com:8443" + SUBSCRIBE_PATH, subscribe.uri().toString());
    assertEquals(KEY, subscribe.headers().firstValue(BlueMindRestSession.API_KEY_HEADER).orElse(null));
    assertEquals("application/json", subscribe.headers().firstValue("Content-Type").orElse(null));
    assertFalse(subscribe.headers().firstValue("Authorization").isPresent());
    JsonNode body = JsonMapper.builder().build().readTree(bodyOf(subscribe));
    assertTrue(body.isArray() && body.size() == 1, "one ContainerSubscription in a list: " + body);
    assertEquals(CONTAINER, body.get(0).get("containerUid").asText());
    assertTrue(body.get(0).get("offlineSync").asBoolean(), "offlineSync=true, BlueMind's webmail default (PO decision 3)");
    assertTrue(body.get(0).get("automount").asBoolean(), "automount=true (PO decision 3)");
    assertEquals(3, body.get(0).size(), "nothing but the three fields ContainerSubscription declares");

    HttpRequest logout = sent.get(2);
    assertEquals("POST", logout.method());
    assertEquals("https://bm.example.com:8443/api/auth/logout", logout.uri().toString());
    assertEquals(KEY, logout.headers().firstValue(BlueMindRestSession.API_KEY_HEADER).orElse(null));
  }

  /**
   * The reverse: {@code _unsubscribe} with a JSON array of container uids.
   */
  @Test
  void theColleagueIsUnsubscribedWithAListOfContainerUids() {
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, "");
    answer(200, "");

    client.unsubscribe(endpoint, LOGIN_UID, CONTAINER);

    HttpRequest unsubscribe = sent.get(1);
    assertEquals("https://bm.example.com:8443" + UNSUBSCRIBE_PATH, unsubscribe.uri().toString());
    assertEquals("[\"" + CONTAINER + "\"]", bodyOf(unsubscribe));
    assertEquals("/api/auth/logout", sent.get(2).uri().getPath());
  }

  /**
   * <b>The trust boundary.</b> The session BlueMind opened is FRANCOIS's; the
   * client was asked to edit eric's subscriptions. Nothing is posted — the
   * refusal comes before any edit — the session is still closed, and the
   * refusal is its own kind, so the caller gives up rather than retries. A
   * login answer naming no {@code authUser} at all is refused the same way:
   * an account that cannot be checked is not edited.
   */
  @Test
  void aSessionThatIsNotTheRecordedColleaguesEditsNothing() {
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, "");
    BlueMindSubjectMismatchException refused = assertThrows(BlueMindSubjectMismatchException.class,
                                                            () -> client.subscribe(endpoint, OTHER_UID, CONTAINER));

    assertEquals(2, sent.size(), "login and logout, no edit");
    assertEquals("/api/auth/login", sent.get(0).uri().getPath());
    assertEquals("/api/auth/logout", sent.get(1).uri().getPath());
    assertTrue(refused.getMessage().contains(OTHER_UID) && refused.getMessage().contains(LOGIN_UID), refused.getMessage());
    assertFalse(refused.getMessage().contains(KEY));

    sent.clear();
    answer(200, "{\"status\":\"Ok\",\"authKey\":\"" + KEY + "\"}");
    answer(200, "");
    assertThrows(BlueMindSubjectMismatchException.class, () -> client.unsubscribe(endpoint, LOGIN_UID, CONTAINER));
    assertEquals(2, sent.size(), "no authUser: nothing is edited either");
    assertTrue(sent.stream().noneMatch(request -> request.uri().getPath().contains("/subscriptions/")));
  }

  /**
   * The domain in the path is the login answer's, never a constant: a login
   * answer authenticating the right uid but naming no domain edits nothing.
   */
  @Test
  void aLoginAnswerWithoutADomainEditsNothing() {
    answer(200, "{\"status\":\"Ok\",\"authKey\":\"" + KEY + "\",\"authUser\":{\"uid\":\"" + LOGIN_UID + "\"}}");
    answer(200, "");

    CalDavException refused = assertThrows(CalDavException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER));

    assertFalse(refused instanceof BlueMindSubjectMismatchException);
    assertEquals(2, sent.size(), "login and logout, no edit");
  }

  /**
   * The REST root is the declared server's scheme, host and port and nothing
   * else: no user part, no path, and neither a container uid nor a recorded
   * uid can leave its segment. A server declared with another scheme is never
   * called.
   */
  @Test
  void everyRequestGoesToTheDeclaredServerAndNowhereElse() {
    answer(200, "{\"status\":\"Ok\",\"authKey\":\"" + KEY + "\",\"authUser\":{\"uid\":\"../x\",\"domainUid\":\"d/../e\"}}");
    answer(200, "");
    answer(200, "");

    client.subscribe(endpoint, "../x", "../../auth/x?y=z#w");

    for (HttpRequest request : sent) {
      assertEquals("https", request.uri().getScheme());
      assertEquals("bm.example.com", request.uri().getHost());
      assertEquals(8443, request.uri().getPort());
      assertEquals(null, request.uri().getRawUserInfo());
      assertTrue(request.uri().getRawPath().startsWith("/api/"), request.uri().toString());
    }
    assertEquals("/api/users/d%2F..%2Fe/subscriptions/..%2Fx/_subscribe", sent.get(1).uri().getRawPath());
    assertEquals(null, sent.get(1).uri().getRawQuery());
    assertTrue(bodyOf(sent.get(1)).contains("\"../../auth/x?y=z#w\""), "the container travels in the body, as data");

    sent.clear();
    CalDavEndpoint elsewhere = TestEndpoints.endpoint(1L, URI.create("ftp://bm.example.com/dav/"), "personal", "bob");
    assertThrows(CalDavException.class, () -> client.subscribe(elsewhere, LOGIN_UID, CONTAINER));
    assertTrue(sent.isEmpty());
  }

  /**
   * Every answer BlueMind's service can give is read as what it means, and the
   * session is closed after each: 401 is a refused session, 403 a refused edit
   * (the DERIVED {@code PERMISSION_DENIED} fault), a 404 and a 500 carrying
   * {@code NOT_FOUND} are both the absent container (the DERIVED fault, either
   * status until a capture settles it), a 500 with another code is a plain
   * failure naming the code, a gateway status is unreachable, and a redirect is
   * never followed.
   */
  @Test
  void everyFaultIsReadAsWhatItMeansAndTheSessionIsClosed() {
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(401, "");
    answer(200, "");
    assertThrows(CalDavAuthenticationException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER));
    assertEquals("/api/auth/logout", sent.get(2).uri().getPath());

    sent.clear();
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(403, derived("bluemind-rest-fault-permission-denied.derived.json"));
    answer(200, "");
    assertThrows(CalDavForbiddenException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER));
    assertEquals("/api/auth/logout", sent.get(2).uri().getPath());

    sent.clear();
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(500, derived("bluemind-rest-fault-subscribe-not-found.derived.json"));
    answer(200, "");
    CalDavNotFoundException absent = assertThrows(CalDavNotFoundException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER));
    assertTrue(absent.getMessage().contains("NOT_FOUND"), absent.getMessage());
    assertFalse(absent.getMessage().contains("Container not found"), "BlueMind's own text is never quoted: " + absent.getMessage());
    assertEquals("/api/auth/logout", sent.get(2).uri().getPath());

    sent.clear();
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(404, "");
    answer(200, "");
    assertThrows(CalDavNotFoundException.class, () -> client.unsubscribe(endpoint, LOGIN_UID, CONTAINER));

    sent.clear();
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(500, "{\"errorCode\":\"SQL_ERROR\",\"errorType\":\"ServerFault\",\"message\":\"boom\"}");
    answer(200, "");
    CalDavException failed = assertThrows(CalDavException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER));
    assertFalse(failed instanceof CalDavNotFoundException);
    assertFalse(failed instanceof CalDavForbiddenException);
    assertTrue(failed.getMessage().contains("SQL_ERROR"), failed.getMessage());

    sent.clear();
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(503, "");
    answer(200, "");
    assertThrows(CalDavUnreachableException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER));
    assertEquals(3, sent.size());

    sent.clear();
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(302, "");
    answer(200, "");
    CalDavException redirected = assertThrows(CalDavException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER));
    assertFalse(redirected instanceof CalDavNotFoundException);
    assertEquals("/api/auth/logout", sent.get(2).uri().getPath());
  }

  /**
   * One session for several edits: the drain's shape. Two containers cost
   * one login and one logout, and a fault on the first does not stop the
   * second when the caller chooses to go on.
   */
  @Test
  void severalEditsShareOneSession() {
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(500, derived("bluemind-rest-fault-subscribe-not-found.derived.json"));
    answer(200, "");
    answer(200, "");

    List<String> outcomes = client.asSharee(endpoint, LOGIN_UID, edits -> {
      List<String> seen = new ArrayList<>();
      try {
        edits.subscribe("calendar:Default:unknown");
        seen.add("first ok");
      } catch (CalDavNotFoundException e) {
        seen.add("first absent");
      }
      edits.unsubscribe(CONTAINER);
      seen.add("second ok");
      return seen;
    });

    assertEquals(List.of("first absent", "second ok"), outcomes);
    assertEquals(4, sent.size());
    assertEquals(1, sent.stream().filter(request -> request.uri().getPath().equals("/api/auth/login")).count());
    assertEquals(1, sent.stream().filter(request -> request.uri().getPath().equals("/api/auth/logout")).count());
  }

  /**
   * Credentials the provider does not produce as a login and password are not
   * a login this API takes: nothing is sent, and the refusal is its own kind,
   * which is how the caller knows to give the change up rather than retry it
   * against a provider that will answer the same thing next time.
   *
   * @throws Exception never — the mock declares it
   */
  @Test
  void credentialsThatAreNotALoginAndPasswordAreNeverSent() throws Exception {
    lenient().doReturn(new HttpConnectorCredentials("Bearer eyJhbGciOi", null)).when(credentials).produce(any());

    assertThrows(UnsupportedOperationException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER));
    assertTrue(sent.isEmpty());
  }

  /**
   * Neither the session key nor the password nor the Basic header ever
   * reaches a log line or an exception message — on an edit that lands, an
   * edit that fails with the key in the body, a fault carrying the password,
   * a refused subject, a login refusal whose body carries one, and a logout
   * whose transport fails.
   *
   * <p>
   * <b>Every secret-bearing body here is built with the mapper, and that is
   * the point of this test rather than a detail of it.</b> Written by hand,
   * they were not JSON at all — {@link #PASSWORD} carries a quote and a
   * backslash — so {@code faultCode} threw on the parse and returned before
   * its own log line, and the whole assertion loop below ran over messages
   * that had never been near a secret: the test passed against a client that
   * logged the password, which was proved by making one do so. Valid JSON is
   * what makes the branch this test exists for actually execute, and the two
   * assertions at the end are what say it did.
   */
  @Test
  void neitherTheKeyNorThePasswordIsEverLoggedOrThrown() {
    List<String> messages = new ArrayList<>();

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, "");
    answers.add(new IOException("connection reset while sending " + KEY));
    client.subscribe(endpoint, LOGIN_UID, CONTAINER);

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(500, fault("UNKNOWN", KEY + " " + PASSWORD));
    answer(200, "");
    messages.add(assertThrows(CalDavException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER)).getMessage());

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(500, fault("NOT_FOUND", "container " + PASSWORD + " not found"));
    answer(200, "");
    messages.add(assertThrows(CalDavNotFoundException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER)).getMessage());

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(403, fault("PERMISSION_DENIED", PASSWORD));
    answer(200, "");
    messages.add(assertThrows(CalDavForbiddenException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER)).getMessage());

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, "");
    messages.add(assertThrows(BlueMindSubjectMismatchException.class, () -> client.subscribe(endpoint, OTHER_UID, CONTAINER))
                              .getMessage());

    answer(200, JsonMapper.builder().build().writeValueAsString(Map.of("status", "Bad", "message", PASSWORD)));
    messages.add(assertThrows(CalDavException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER)).getMessage());

    assertFalse(logged.list.isEmpty(), "the capture must see the client's own lines, or this test proves nothing");
    for (ILoggingEvent event : logged.list) {
      messages.add(event.getFormattedMessage());
      if (event.getThrowableProxy() != null) {
        messages.add(event.getThrowableProxy().getMessage());
      }
    }
    String basic = AUTHORIZATION.substring("Basic ".length());
    for (String message : messages) {
      assertFalse(message.contains(KEY), message);
      assertFalse(message.contains(PASSWORD), message);
      assertFalse(message.contains(basic), message);
      assertFalse(message.contains("login="), "the query carries the login and is never named: " + message);
    }
    String all = logged.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining());
    assertTrue(all.contains("could not be closed"));
    // The two lines that say the secret-bearing branch really ran: the client
    // read the faults (it named their codes) and told nobody their text.
    String own = logged.list.stream()
                            .filter(event -> event.getLoggerName().equals(BlueMindSubscriptionClient.class.getName()))
                            .map(ILoggingEvent::getFormattedMessage)
                            .collect(Collectors.joining("\n"));
    assertTrue(own.contains("UNKNOWN") && own.contains("NOT_FOUND"),
               "the client parsed the secret-bearing faults and logged their codes: " + own);
    assertFalse(own.contains("not found"), "and never a word of BlueMind's own text: " + own);
  }

  // ------------------------------------ the account's calendar owners, EXO-90347

  /** The uid the CAPTURED sharee login authenticates: root's, FRANCOIS. */
  private static final String   CAPTURED_UID    = "751E6D1A-7FDB-49B2-B668-B569E9A5A42D";

  /** The domain in the CAPTURED sharee login. */
  private static final String   CAPTURED_DOMAIN = "19d43481671.internal";

  /** eric's uid, the owner of the two calendars he shared with root. */
  private static final String   ERIC_UID        = "4C60FEDD-0562-4903-A524-E95E1CCBCDE0";

  /**
   * The listing read against the CAPTURED answers of 2026-09-16: login as the
   * account, one GET of
   * {@code /api/users/<domainUid from the login>/subscriptions/<uid from the
   * login>?type=calendar} with the key, logout. Ten calendars come back:
   * root's own carry root's uid, eric's two shares carry eric's, the pool
   * vehicle its own — and the account uid is the one BlueMind authenticated,
   * never one a caller named.
   */
  @Test
  void theAccountsCalendarOwnersAreReadInOneSessionOfItsOwn() {
    answer(200, captured("bluemind-rest-login-sharee.captured.json"));
    answer(200, captured("bluemind-rest-subscriptions-after-subscribe.captured.json"));
    answer(200, "");

    BlueMindCalendarOwners owners = client.ownersOf(endpoint);

    assertEquals(CAPTURED_UID, owners.accountUid());
    assertEquals(10, owners.ownerByContainerUid().size());
    assertEquals(CAPTURED_UID, owners.ownerByContainerUid().get("calendar:Default:" + CAPTURED_UID));
    assertEquals(CAPTURED_UID, owners.ownerByContainerUid().get("exo-cal-5c7e51bf-d8c5-47ef-bc00-e976249331bc"), "Personnel: root's own");
    assertEquals(ERIC_UID, owners.ownerByContainerUid().get("exo-cal-d691e7f4-8aa0-4c67-92ad-4d7c329bf6fb"), "testCalEric: eric's");
    assertEquals(ERIC_UID, owners.ownerByContainerUid().get("exo-cal-96f6f3c2-08cb-4109-84ba-730ac035a607"), "Cal2ShareFromEric: eric's");
    assertEquals("7E3AE6F3-98DF-43D9-B071-AAB477AC2CD8",
                 owners.ownerByContainerUid().get("calendar:7E3AE6F3-98DF-43D9-B071-AAB477AC2CD8"),
                 "the pool vehicle: the resource's");

    assertEquals(3, sent.size());
    assertEquals("/api/auth/login", sent.get(0).uri().getPath());
    HttpRequest listing = sent.get(1);
    assertEquals("GET", listing.method());
    assertEquals("https://bm.example.com:8443/api/users/" + CAPTURED_DOMAIN + "/subscriptions/" + CAPTURED_UID + "?type=calendar",
                 listing.uri().toString());
    assertEquals("<REDACTED-API-KEY>", listing.headers().firstValue(BlueMindRestSession.API_KEY_HEADER).orElse(null));
    assertFalse(listing.headers().firstValue("Authorization").isPresent());
    assertFalse(listing.bodyPublisher().map(publisher -> publisher.contentLength() != 0).orElse(false), "a GET carries no body");
    assertEquals("/api/auth/logout", sent.get(2).uri().getPath());
  }

  /**
   * A refused listing is its own kind, and the session is still closed.
   */
  @Test
  void aRefusedListingIsForbiddenAndTheSessionIsStillClosed() {
    answer(200, captured("bluemind-rest-login-sharee.captured.json"));
    answer(403, fault("PERMISSION_DENIED", "no"));
    answer(200, "");

    assertThrows(CalDavForbiddenException.class, () -> client.ownersOf(endpoint));

    assertEquals(3, sent.size());
    assertEquals("/api/auth/logout", sent.get(2).uri().getPath());
  }

  /**
   * An answer that is not a list — a fault body under a 200, an object —
   * is refused rather than read as an empty listing, which would say every
   * calendar is unknown while looking like a success.
   */
  @Test
  void aListingOfAnotherShapeIsRefused() {
    answer(200, captured("bluemind-rest-login-sharee.captured.json"));
    answer(200, fault("SERVER_ERROR", "not a list"));
    answer(200, "");

    assertThrows(CalDavException.class, () -> client.ownersOf(endpoint));
    assertEquals("/api/auth/logout", sent.get(2).uri().getPath());
  }

  /**
   * A login naming no entry reads nothing: the path could not be built
   * without guessing whose subscriptions to read. Login and logout only.
   */
  @Test
  void aLoginNamingNoEntryReadsNothing() {
    answer(200, "{\"status\":\"Ok\",\"authKey\":\"" + KEY + "\"}");
    answer(200, "");

    assertThrows(CalDavException.class, () -> client.ownersOf(endpoint));

    assertEquals(2, sent.size(), "login and logout, no listing");
    assertEquals("/api/auth/logout", sent.get(1).uri().getPath());
  }

  /**
   * A CAPTURED transcript's body: the {@code #} provenance lines, the status
   * line and the headers dropped, what follows the first blank line kept.
   *
   * @param name the file name
   * @return the body text
   */
  private static String captured(String name) {
    try (InputStream stream = BlueMindSubscriptionClientTest.class.getResourceAsStream("/caldav/transcripts/" + name)) {
      List<String> lines = new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                                                                                    .filter(line -> !line.startsWith("#"))
                                                                                    .toList();
      int blank = lines.indexOf("");
      return String.join("\n", lines.subList(blank + 1, lines.size())).trim();
    } catch (IOException | NullPointerException e) {
      throw new IllegalStateException("missing fixture " + name, e);
    }
  }

  /**
   * A BlueMind fault body, built by the mapper so that whatever the message
   * carries the body stays parseable — a hand-written one carrying
   * {@link #PASSWORD} is not JSON, and an unparseable body never reaches the
   * code that reads a fault.
   *
   * @param code the {@code errorCode}
   * @param message BlueMind's own text
   * @return the JSON body
   */
  private static String fault(String code, String message) {
    return JsonMapper.builder()
                     .build()
                     .writeValueAsString(Map.of("errorCode", code, "errorType", "ServerFault", "message", message));
  }

  /**
   * <b>Over a session that is kept, and swapped under the call.</b> The other
   * fixtures of this class drive an unpooled session — one login, one call,
   * one logout — which is what an un-keyable endpoint still does but no
   * longer what production does for this client (EXO-90397; review round 1
   * asked for the pooled path to be covered too).
   *
   * <p>
   * The sharp case is the one reuse created: the listing is asked for under
   * the uid the session names, the kept session turns out to be refused, and
   * the call opens another one — which BlueMind may authenticate as a
   * different entry. The answer must then be reported under the uid the
   * request actually carried, not under whoever the second session turned out
   * to be: a listing labelled with an entry it was not read for is a wrong
   * answer that looks like a right one.
   */
  @Test
  void aListingReadAcrossAReLoginIsReportedUnderTheEntryItWasAskedFor() {
    BlueMindSessionCache sessions = new BlueMindSessionCache(300, 10, System::currentTimeMillis);
    BlueMindSubscriptionClient pooled =
                                      new BlueMindSubscriptionClient(new BlueMindRestSession(transport,
                                                                                             new CaldavCredentialsResolver(credentials),
                                                                                             sessions));
    // A first call warms the store, so the second one reuses its session.
    answer(200, login(KEY, LOGIN_UID));
    answer(200, "[]");
    // The second call: the kept session is refused, another is opened — and
    // BlueMind authenticates it as somebody else entirely.
    answer(401, "");
    answer(200, login("bm-session-second", OTHER_UID));
    answer(200, "[]");

    pooled.ownersOf(endpoint);
    BlueMindCalendarOwners owners = pooled.ownersOf(endpoint);

    assertEquals(LOGIN_UID, owners.accountUid(),
                 "the listing is reported under the entry its request named, not under the session that answered it");
    HttpRequest resent = sent.get(sent.size() - 1);
    assertTrue(resent.uri().toString().contains("/subscriptions/" + LOGIN_UID + "?type=calendar"),
               "and that is the entry the resent request asked for: " + resent.uri());
    assertFalse(resent.uri().toString().contains(OTHER_UID), "the re-login never re-aims the request");
  }

  /**
   * A login answer naming an entry.
   *
   * @param key the session key it hands out
   * @param uid the directory entry uid it authenticates
   * @return the JSON body
   */
  private static String login(String key, String uid) {
    return "{\"status\":\"Ok\",\"authKey\":\"" + key + "\",\"authUser\":{\"uid\":\"" + uid + "\",\"domainUid\":\"" + DOMAIN
        + "\"}}";
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

  /**
   * A DERIVED JSON fixture, its {@code //} header lines removed.
   *
   * @param name the file name
   * @return the JSON, empty for a fixture that is all header
   */
  private static String derived(String name) {
    try (InputStream stream = BlueMindSubscriptionClientTest.class.getResourceAsStream("/caldav/transcripts/" + name)) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                                                                         .filter(line -> !line.startsWith("//"))
                                                                         .collect(Collectors.joining("\n"));
    } catch (IOException | NullPointerException e) {
      throw new IllegalStateException("missing fixture " + name, e);
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

      /**
       * Asks for the whole body at once: it is a request this test built and
       * holds in memory, not a stream off a socket.
       *
       * @param subscription the publisher's subscription
       */
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      /**
       * Appends one buffer of the body as UTF-8.
       *
       * @param item the buffer
       */
      @Override
      public void onNext(ByteBuffer item) {
        byte[] bytes = new byte[item.remaining()];
        item.get(bytes);
        text.append(new String(bytes, StandardCharsets.UTF_8));
      }

      /**
       * Fails the test: a body publisher this test built cannot error.
       *
       * @param throwable what it reported
       */
      @Override
      public void onError(Throwable throwable) {
        throw new IllegalStateException(throwable);
      }

      /**
       * Nothing to do: the body is drained synchronously above.
       */
      @Override
      public void onComplete() {
        // drained
      }
    });
    return text.toString();
  }
}
