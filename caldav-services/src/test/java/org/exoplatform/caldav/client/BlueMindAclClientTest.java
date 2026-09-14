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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
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

import org.exoplatform.caldav.client.BlueMindAclClient.BlueMindAce;
import org.exoplatform.caldav.provider.CaldavCredentialsResolver;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.HttpConnectorCredentials;

/**
 * The one REST conversation eXo holds with BlueMind (EXO-90253): log in as the
 * calendar's owner, read the container's access list, log out — against
 * canned answers, never a server.
 *
 * <p>
 * What is pinned is what a wrong line would silently cost: a request leaving
 * for any host but the declared server's (the owner's password with it); a key
 * or a password in a log line or an exception message; a session left open
 * when the read fails; a credential the provider did not produce as a login
 * and password sent anyway; and an access list read partly, on which a grant
 * would then be confirmed.
 *
 * <p>
 * The login and access list answers are DERIVED fixtures, each naming the
 * BlueMind source it follows.
 */
public class BlueMindAclClientTest {

  private static final String   LOGIN          = "francois@bm.example.com";

  /** A password with a colon and a space: only the first colon separates. */
  private static final String   PASSWORD       = "Fr@n:cois Pass1!";

  private static final String   AUTHORIZATION  = "Basic "
      + Base64.getEncoder().encodeToString((LOGIN + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));

  /** The key in the DERIVED login answer. */
  private static final String   KEY            = "bm-session-7f1e9c2a-4d6b-4e1f-9a3c-5b8d2e0f6a41";

  private static final String   OWNER_UID      = "9F3C1A20-4D5E-4B7A-8C61-2E0D7A4B9C13";

  private static final String   CONTAINER      = "exo-cal-9f1c2d3e-4b5a-6c7d-8e9f-0a1b2c3d4e5f";

  /** The declared server: a port, a path, and a user part that must not travel. */
  private static final URI      BASE           = URI.create("https://sync@bm.example.com:8443/dav/calendars/__uids__/" + OWNER_UID + "/");

  private final List<HttpRequest> sent         = new ArrayList<>();

  private final Deque<Object>   answers        = new ArrayDeque<>();

  private ConnectorCredentialsService credentials;

  private BlueMindAclClient     client;

  private CalDavEndpoint        endpoint;

  private Logger                logger;

  private ListAppender<ILoggingEvent> logged;

  private Level                 previousLevel;

  /**
   * A client over a mocked transport answering from a queue, the owner's
   * credentials produced as a Basic header, and every line the client logs
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
    client = new BlueMindAclClient(transport, new CaldavCredentialsResolver(credentials));
    endpoint = new CalDavEndpoint(1L, BASE, "personal", "root");

    logger = (Logger) LoggerFactory.getLogger(BlueMindAclClient.class);
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
   * Three requests, in order, all to the declared server: the login with the
   * owner's login in the query and the password as the body — no
   * Authorization header; the read with the session key in
   * {@code X-BM-ApiKey}; the logout with the same key. The list comes back
   * entry by entry, as BlueMind lists it.
   */
  @Test
  void theAccessListIsReadAsTheOwnerInOneSession() {
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, derived("bluemind-rest-acl-shared-with-eric.derived.json"));
    answer(200, "");

    List<BlueMindAce> aces = client.readAcl(endpoint, CONTAINER);

    assertEquals(3, sent.size());
    HttpRequest login = sent.get(0);
    assertEquals("POST", login.method());
    assertEquals("https://bm.example.com:8443/api/auth/login?login=francois%40bm.example.com&origin=exo-caldav", login.uri().toString());
    assertEquals(PASSWORD, bodyOf(login));
    assertFalse(login.headers().firstValue("Authorization").isPresent());
    assertFalse(login.headers().firstValue(BlueMindAclClient.API_KEY_HEADER).isPresent());

    HttpRequest read = sent.get(1);
    assertEquals("GET", read.method());
    assertEquals("https://bm.example.com:8443/api/containers/_manage/" + CONTAINER + "/_acl", read.uri().toString());
    assertEquals(KEY, read.headers().firstValue("X-BM-ApiKey").orElse(null));
    assertFalse(read.headers().firstValue("Authorization").isPresent());

    HttpRequest logout = sent.get(2);
    assertEquals("POST", logout.method());
    assertEquals("https://bm.example.com:8443/api/auth/logout", logout.uri().toString());
    assertEquals(KEY, logout.headers().firstValue("X-BM-ApiKey").orElse(null));

    assertEquals(17, aces.size());
    assertEquals(new BlueMindAce(OWNER_UID, "All"), aces.get(0));
    assertEquals(new BlueMindAce("6B2E4F10-8A3C-4D7E-9B51-0C2D4E6F8A17", "Read"), aces.get(8));
    assertEquals(new BlueMindAce("D41A7C22-3E5B-4F60-8A19-2B7C9D0E1F35", "Write"), aces.get(12));
  }

  /**
   * The REST root is the declared server's scheme, host and port and nothing
   * else: no user part, no path, and a container uid cannot leave its segment.
   * A server declared with another scheme is never called.
   */
  @Test
  void everyRequestGoesToTheDeclaredServerAndNowhereElse() {
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, "[]");
    answer(200, "");

    client.readAcl(endpoint, "../../auth/x?y=z#w");

    for (HttpRequest request : sent) {
      assertEquals("https", request.uri().getScheme());
      assertEquals("bm.example.com", request.uri().getHost());
      assertEquals(8443, request.uri().getPort());
      assertEquals(null, request.uri().getRawUserInfo());
      assertTrue(request.uri().getRawPath().startsWith("/api/"), request.uri().toString());
    }
    assertEquals("/api/containers/_manage/..%2F..%2Fauth%2Fx%3Fy%3Dz%23w/_acl", sent.get(1).uri().getRawPath());
    assertEquals(null, sent.get(1).uri().getRawQuery());

    sent.clear();
    CalDavEndpoint elsewhere = new CalDavEndpoint(1L, URI.create("ftp://bm.example.com/dav/"), "personal", "root");
    assertThrows(CalDavException.class, () -> client.readAcl(elsewhere, CONTAINER));
    assertTrue(sent.isEmpty());
  }

  /**
   * A session opened is closed, whatever the read did: refused, failed, or
   * unreadable.
   */
  @Test
  void theSessionIsClosedEvenWhenTheReadFails() {
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(403, "");
    answer(200, "");
    assertThrows(CalDavForbiddenException.class, () -> client.readAcl(endpoint, CONTAINER));
    assertEquals("/api/auth/logout", sent.get(2).uri().getPath());

    sent.clear();
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, "[{\"subject\":\"" + OWNER_UID + "\"}]");
    answer(200, "");
    assertThrows(CalDavException.class, () -> client.readAcl(endpoint, CONTAINER), "an entry without a verb is not read partly");
    assertEquals("/api/auth/logout", sent.get(2).uri().getPath());

    sent.clear();
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(503, "");
    answer(200, "");
    assertThrows(CalDavUnreachableException.class, () -> client.readAcl(endpoint, CONTAINER));
    assertEquals(3, sent.size());
  }

  /**
   * A login BlueMind refuses opens no session: {@code Bad} or {@code Expired}
   * in a 200, or a 401, is a credential refusal, and nothing more is sent. A
   * redirect is refused, never followed.
   */
  @Test
  void aRefusedLoginOpensNoSession() {
    answer(200, "{\"status\":\"Bad\",\"message\":\"bad credentials\"}");
    assertThrows(CalDavAuthenticationException.class, () -> client.readAcl(endpoint, CONTAINER));
    answer(200, "{\"status\":\"Expired\"}");
    assertThrows(CalDavAuthenticationException.class, () -> client.readAcl(endpoint, CONTAINER));
    answer(401, "");
    assertThrows(CalDavAuthenticationException.class, () -> client.readAcl(endpoint, CONTAINER));
    answer(302, "");
    CalDavException redirected = assertThrows(CalDavException.class, () -> client.readAcl(endpoint, CONTAINER));
    assertFalse(redirected instanceof CalDavAuthenticationException);

    assertEquals(4, sent.size());
    assertTrue(sent.stream().allMatch(request -> request.uri().getPath().equals("/api/auth/login")));
  }

  /**
   * Credentials the provider does not produce as a login and password are not
   * a login this API takes: nothing is sent.
   *
   * @throws Exception never — the mock declares it
   */
  @Test
  void credentialsThatAreNotALoginAndPasswordAreNeverSent() throws Exception {
    for (String authorization : new String[] { "Bearer eyJhbGciOi", "Basic !!!", "Basic "
        + Base64.getEncoder().encodeToString("no-colon".getBytes(StandardCharsets.UTF_8)) }) {
      doReturn(new HttpConnectorCredentials(authorization, null)).when(credentials).produce(any());

      assertThrows(UnsupportedOperationException.class, () -> client.readAcl(endpoint, CONTAINER), authorization);
    }
    assertTrue(sent.isEmpty());
  }

  /**
   * Neither the session key nor the password nor the Basic header ever
   * reaches a log line or an exception message — on a read that succeeds, a
   * read that fails, a login refused, and a logout whose transport fails. The
   * capture is proved live: the logout failure is logged.
   */
  @Test
  void neitherTheKeyNorThePasswordIsEverLoggedOrThrown() {
    List<String> messages = new ArrayList<>();

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, "[]");
    answers.add(new IOException("connection reset while sending " + KEY));
    client.readAcl(endpoint, CONTAINER);

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(500, KEY);
    answer(200, "");
    messages.add(assertThrows(CalDavException.class, () -> client.readAcl(endpoint, CONTAINER)).getMessage());

    answer(200, "{\"status\":\"Bad\",\"message\":\"" + PASSWORD + "\"}");
    messages.add(assertThrows(CalDavException.class, () -> client.readAcl(endpoint, CONTAINER)).getMessage());

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, "not json " + KEY);
    answer(200, "");
    messages.add(assertThrows(CalDavException.class, () -> client.readAcl(endpoint, CONTAINER)).getMessage());

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
    assertTrue(logged.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining()).contains("could not be closed"));
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
   * @return the JSON
   */
  private static String derived(String name) {
    try (InputStream stream = BlueMindAclClientTest.class.getResourceAsStream("/caldav/transcripts/" + name)) {
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
