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

  private BlueMindSubscriptionClient client;

  private CalDavEndpoint        endpoint;

  private Logger                logger;

  private ListAppender<ILoggingEvent> logged;

  private Level                 previousLevel;

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
    HttpClient transport = mock(HttpClient.class);
    when(transport.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
      sent.add(invocation.getArgument(0));
      Object next = answers.removeFirst();
      if (next instanceof IOException failure) {
        throw failure;
      }
      return next;
    });
    credentials = mock(ConnectorCredentialsService.class);
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
    Logger own = (Logger) LoggerFactory.getLogger(BlueMindSubscriptionClient.class);
    own.setLevel(Level.TRACE);
    own.addAppender(logged);
  }

  /**
   * The logger is left as found.
   */
  @AfterEach
  void restoreLogger() {
    logger.detachAppender(logged);
    logger.setLevel(previousLevel);
    ((Logger) LoggerFactory.getLogger(BlueMindSubscriptionClient.class)).detachAppender(logged);
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
   * a login this API takes: nothing is sent.
   *
   * @throws Exception never — the mock declares it
   */
  @Test
  void credentialsThatAreNotALoginAndPasswordAreNeverSent() throws Exception {
    lenient().doReturn(new HttpConnectorCredentials("Bearer eyJhbGciOi", null)).when(credentials).produce(any());

    assertThrows(UnsupportedOperationException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER));
    assertFalse(client.acceptsCredentials(endpoint));
    assertTrue(sent.isEmpty());
  }

  /**
   * Neither the session key nor the password nor the Basic header ever
   * reaches a log line or an exception message — on an edit that lands, an
   * edit that fails with the key in the body, a fault carrying the password,
   * a refused subject, and a logout whose transport fails. The capture is
   * proved live: the logout failure is logged.
   */
  @Test
  void neitherTheKeyNorThePasswordIsEverLoggedOrThrown() {
    List<String> messages = new ArrayList<>();

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, "");
    answers.add(new IOException("connection reset while sending " + KEY));
    client.subscribe(endpoint, LOGIN_UID, CONTAINER);

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(500, "{\"errorCode\":\"UNKNOWN\",\"errorType\":\"ServerFault\",\"message\":\"" + KEY + " " + PASSWORD + "\"}");
    answer(200, "");
    messages.add(assertThrows(CalDavException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER)).getMessage());

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(403, "{\"errorCode\":\"PERMISSION_DENIED\",\"errorType\":\"ServerFault\",\"message\":\"" + PASSWORD + "\"}");
    answer(200, "");
    messages.add(assertThrows(CalDavForbiddenException.class, () -> client.subscribe(endpoint, LOGIN_UID, CONTAINER)).getMessage());

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, "");
    messages.add(assertThrows(BlueMindSubjectMismatchException.class, () -> client.subscribe(endpoint, OTHER_UID, CONTAINER))
                              .getMessage());

    answer(200, "{\"status\":\"Bad\",\"message\":\"" + PASSWORD + "\"}");
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
}
