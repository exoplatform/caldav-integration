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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.provider.CaldavCredentialsResolver;
import org.exoplatform.caldav.service.CaldavServerService;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.HttpConnectorCredentials;

/**
 * A budget open on the thread ({@link CalDavDeadline}) bounds every request
 * the client sends, against a real server on the loopback that is slow in each
 * way a server can be: no answer at all, headers and then a body that never
 * ends. Without a budget, a request keeps the client's own timeout.
 */
class HttpCalDavClientDeadlineTest {

  private HttpServer      server;

  private ExecutorService handlers;

  private CountDownLatch  release;

  private AtomicInteger   requests;

  private HttpCalDavClient client;

  private CalDavEndpoint  endpoint;

  /**
   * A loopback server with a handler that never answers (/silent/), one that
   * sends its headers and a few bytes of a longer body and stops (/trickle/),
   * and a client registered against it.
   *
   * @throws Exception never
   */
  @BeforeEach
  void setUp() throws Exception {
    release = new CountDownLatch(1);
    requests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/dav/silent/", exchange -> {
      requests.incrementAndGet();
      awaitRelease();
      exchange.close();
    });
    server.createContext("/dav/trickle/", exchange -> {
      requests.incrementAndGet();
      exchange.sendResponseHeaders(200, 1000);
      try (OutputStream body = exchange.getResponseBody()) {
        body.write("BEGIN:VCALENDAR".getBytes(StandardCharsets.UTF_8));
        body.flush();
        awaitRelease();
      }
    });
    handlers = Executors.newCachedThreadPool();
    server.setExecutor(handlers);
    server.start();
    CaldavServerService caldavServerService = mock(CaldavServerService.class);
    lenient().when(caldavServerService.resolveServer(1L))
             .thenReturn(new CaldavServer(1L, "agenda.caldavCalendar", "Loopback", null,
                                          "http://127.0.0.1:" + server.getAddress().getPort() + "/dav/", true, null, null,
                                          null, null, true, null, null, null, null, null, null, "personal", null, null));
    ConnectorCredentialsService credentials = mock(ConnectorCredentialsService.class);
    doReturn(new HttpConnectorCredentials("Basic YWxpY2U6c2VjcmV0", null)).when(credentials).produce(any());
    client = new HttpCalDavClient(HttpClient.newHttpClient(), caldavServerService, new CaldavCredentialsResolver(credentials));
    endpoint = client.endpoint(1L, "alice");
  }

  /**
   * Lets the handlers go and stops the server.
   */
  @AfterEach
  void tearDown() {
    release.countDown();
    server.stop(0);
    handlers.shutdownNow();
  }

  /**
   * A server that never answers holds a request for the time left of the
   * budget, not for the client's 30-second timeout, and the failure says the
   * budget ran out rather than that the server is unreachable.
   */
  @Test
  void aRequestToASilentServerEndsWithTheBudget() {
    long started = System.nanoTime();
    try (CalDavDeadline deadline = CalDavDeadline.start(Duration.ofMillis(500))) {
      assertThrows(CalDavDeadlineException.class, () -> client.fetchObject(endpoint, "/dav/silent/a.ics"));
      assertThrows(CalDavDeadlineException.class, () -> deadline.checkNotSpent("the next step"));
    }
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertTrue(elapsed >= 400 && elapsed < 5000, "held for the budget only: " + elapsed + " ms");
  }

  /**
   * A server that sends its headers and then a body that never ends is cut
   * when the budget runs out: the request timeout no longer runs once the
   * headers are in.
   */
  @Test
  void aBodyThatNeverEndsIsCutWithTheBudget() {
    long started = System.nanoTime();
    try (CalDavDeadline deadline = CalDavDeadline.start(Duration.ofMillis(500))) {
      assertThrows(CalDavDeadlineException.class, () -> client.fetchObject(endpoint, "/dav/trickle/a.ics"));
    }
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertTrue(elapsed >= 400 && elapsed < 5000, "cut at the budget: " + elapsed + " ms");
  }

  /**
   * Once the budget is spent, a request is refused before it leaves: the
   * server never sees it.
   *
   * @throws Exception never
   */
  @Test
  void noRequestLeavesOnceTheBudgetIsSpent() throws Exception {
    try (CalDavDeadline deadline = CalDavDeadline.start(Duration.ZERO)) {
      assertThrows(CalDavDeadlineException.class, () -> client.fetchObject(endpoint, "/dav/silent/a.ics"));
    }
    assertEquals(0, requests.get(), "nothing reached the server");
  }

  /**
   * Without a budget a request keeps its own timeout; within one, the smaller
   * of the two; and a budget opened inside another never outlives it.
   */
  @Test
  void aRequestTimeoutIsTheSmallerOfItsOwnAndTheTimeLeft() {
    Duration own = Duration.ofSeconds(30);
    assertSame(own, CalDavDeadline.bound(own, "a request"));
    try (CalDavDeadline outer = CalDavDeadline.start(Duration.ofSeconds(2))) {
      assertTrue(CalDavDeadline.bound(own, "a request").compareTo(Duration.ofSeconds(2)) <= 0);
      assertEquals(Duration.ofMillis(5), CalDavDeadline.bound(Duration.ofMillis(5), "a request"));
      try (CalDavDeadline inner = CalDavDeadline.start(Duration.ofSeconds(60))) {
        assertTrue(CalDavDeadline.bound(own, "a request").compareTo(Duration.ofSeconds(2)) <= 0, "the sooner end holds");
      }
      assertTrue(CalDavDeadline.bound(own, "a request").compareTo(Duration.ofSeconds(2)) <= 0);
    }
    assertSame(own, CalDavDeadline.bound(own, "a request"), "closed: no budget left on the thread");
  }

  /**
   * Holds a handler until the test ends.
   */
  private void awaitRelease() {
    try {
      release.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
