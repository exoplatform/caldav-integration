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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavForbiddenException;
import org.exoplatform.caldav.client.TestEndpoints;
import org.exoplatform.caldav.client.bluemind.BlueMindCalendarImportClient.ImportReport;
import org.exoplatform.caldav.provider.CaldavCredentialsResolver;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.HttpConnectorCredentials;

/**
 * The import conversation with BlueMind (EXO-90307), against canned answers
 * and a clock the test moves: the request shapes, the task poll, the deadline
 * that ends a task nobody finishes, the removal with notifications off, and
 * the fault mapping. Every answer is a DERIVED fixture naming the BlueMind
 * source it follows.
 */
public class BlueMindCalendarImportClientTest {

  private static final String   LOGIN         = "francois@bm.example.com";

  private static final String   PASSWORD      = "Fr@n:co\"is\\ Pass1!";

  private static final String   AUTHORIZATION = "Basic "
      + Base64.getEncoder().encodeToString((LOGIN + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));

  /** The key in the DERIVED login answer. */
  private static final String   KEY           = "bm-session-7f1e9c2a-4d6b-4e1f-9a3c-5b8d2e0f6a41";

  private static final String   OWNER_UID     = "9F3C1A20-4D5E-4B7A-8C61-2E0D7A4B9C13";

  private static final String   CONTAINER     = "calendar:Default:" + OWNER_UID;

  private static final String   ICS           = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:evt-1\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

  private static final URI      BASE          = URI.create("https://bm.example.com:8443/dav/");

  private final List<HttpRequest> sent        = new ArrayList<>();

  private final Deque<Object>   answers       = new ArrayDeque<>();

  /** A clock the fake sleeper advances, so the deadline is reached without waiting. */
  private Instant               now           = Instant.parse("2026-09-15T21:00:00Z");

  private final List<Duration>  slept         = new ArrayList<>();

  private BlueMindCalendarImportClient client;

  private CalDavEndpoint        endpoint;

  /**
   * A client over a mocked transport answering from a queue, the account's
   * credentials as a Basic header, a sleeper that only moves the clock, and a
   * two-second deadline.
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
    lenient().doReturn(new HttpConnectorCredentials(AUTHORIZATION, null)).when(credentials).produce(any());
    lenient().doReturn(LOGIN).when(credentials).resolveTargetIdentity(any());
    Clock clock = new Clock() {
      @Override
      public ZoneOffset getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(java.time.ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        return now;
      }
    };
    client = new BlueMindCalendarImportClient(new BlueMindRestSession(transport, new CaldavCredentialsResolver(credentials)),
                                              duration -> {
                                                slept.add(duration);
                                                now = now.plus(duration);
                                              },
                                              clock,
                                              Duration.ofSeconds(2));
    endpoint = TestEndpoints.endpoint(1L, BASE, "personal", "root");
  }

  /**
   * Login, the raw ICS PUT under the session key to the container's import
   * path, one status read per poll until the task ends, logout — and the
   * report read out of the task's result string.
   */
  @Test
  void anImportIsPutRawThenPolledToItsEndInOneSession() {
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, derived("bluemind-rest-import-taskref.derived.json"));
    answer(200, derived("bluemind-rest-task-status-inprogress.derived.json"));
    answer(200, derived("bluemind-rest-task-status-success.derived.json"));
    answer(200, "");

    ImportReport report = client.importIcs(endpoint, CONTAINER, ICS);

    assertEquals(List.of("evt-1"), report.uids());
    assertEquals(1, report.total());
    assertTrue(report.imported("evt-1"));
    assertFalse(report.imported("evt-2"));
    assertEquals(5, sent.size());
    HttpRequest put = sent.get(1);
    assertEquals("PUT", put.method());
    assertEquals("https://bm.example.com:8443/api/calendars/vevent/calendar%3ADefault%3A" + OWNER_UID, put.uri().toString());
    assertEquals(KEY, put.headers().firstValue(BlueMindRestSession.API_KEY_HEADER).orElse(null));
    assertEquals("text/calendar; charset=utf-8", put.headers().firstValue("Content-Type").orElse(null));
    assertEquals(ICS, bodyOf(put), "the document is the body, byte for byte: BlueMind reads a Stream parameter raw");
    assertFalse(put.headers().firstValue("Authorization").isPresent());
    for (int i = 2; i <= 3; i++) {
      assertEquals("GET", sent.get(i).method());
      assertEquals("https://bm.example.com:8443/api/tasks/5d1f0b3e-2a7c-4c18-9e64-8f0a1b2c3d4e", sent.get(i).uri().toString());
      assertEquals(KEY, sent.get(i).headers().firstValue(BlueMindRestSession.API_KEY_HEADER).orElse(null));
    }
    assertEquals(List.of(BlueMindCalendarImportClient.POLL_INTERVAL), slept);
    assertEquals("https://bm.example.com:8443/api/auth/logout", sent.get(4).uri().toString());
  }

  /**
   * <b>Trap 3.</b> A task that never ends is a failed write once the deadline
   * passes — never a hang. The session is still closed.
   */
  @Test
  void aTaskThatNeverEndsIsAFailureAtTheDeadlineNotAHang() {
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, derived("bluemind-rest-import-taskref.derived.json"));
    for (int i = 0; i < 20; i++) {
      answer(200, derived("bluemind-rest-task-status-inprogress.derived.json"));
    }
    answer(200, "");

    CalDavException refused = assertThrows(CalDavException.class, () -> client.importIcs(endpoint, CONTAINER, ICS));

    assertTrue(refused.getMessage().contains("did not finish the import"), refused.getMessage());
    // Two seconds at a quarter-second interval: the deadline is checked after
    // each read, so nine reads happen and eight waits.
    assertEquals(8, slept.size(), "polling stops at the deadline");
    assertEquals("https://bm.example.com:8443/api/auth/logout", sent.get(sent.size() - 1).uri().toString());
  }

  /**
   * A task that ended in error is a failed write, said without BlueMind's own
   * log text in the message.
   */
  @Test
  void aTaskThatEndedInErrorIsAFailedWrite() {
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, derived("bluemind-rest-import-taskref.derived.json"));
    answer(200, derived("bluemind-rest-task-status-inerror.derived.json"));
    answer(200, "");

    CalDavException refused = assertThrows(CalDavException.class, () -> client.importIcs(endpoint, CONTAINER, ICS));

    assertTrue(refused.getMessage().contains("could not apply the import"), refused.getMessage());
    assertFalse(refused.getMessage().contains("Failed to deal"), refused.getMessage());
  }

  /**
   * <b>Trap 1, as BlueMind reports it.</b> A series the importer left
   * unhandled is absent from {@code uids} while counted in {@code total}, and
   * the report says so.
   */
  @Test
  void anUnhandledSeriesIsReportedAsNotImported() {
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, derived("bluemind-rest-import-taskref.derived.json"));
    answer(200, derived("bluemind-rest-task-status-unhandled.derived.json"));
    answer(200, "");

    ImportReport report = client.importIcs(endpoint, CONTAINER, ICS);

    assertFalse(report.imported("evt-1"));
    assertEquals(1, report.total());
    assertEquals(0, report.uids().size());
  }

  /**
   * The removal carries {@code sendNotifications=false} in its query and
   * nothing else names the item; 200 is 204, a NOT_FOUND fault is 404.
   */
  @Test
  void aRemovalCarriesNotificationsOffAndMapsItsAnswers() {
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(200, "");
    answer(200, "");
    assertEquals(204, client.deleteEvent(endpoint, CONTAINER, "evt 1"));
    HttpRequest delete = sent.get(1);
    assertEquals("DELETE", delete.method());
    assertEquals("/api/calendars/calendar%3ADefault%3A" + OWNER_UID + "/evt%201", delete.uri().getRawPath());
    assertEquals("sendNotifications=false", delete.uri().getRawQuery());
    assertEquals(KEY, delete.headers().firstValue(BlueMindRestSession.API_KEY_HEADER).orElse(null));

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(500, derived("bluemind-rest-fault-not-found.derived.json"));
    answer(200, "");
    assertEquals(404, client.deleteEvent(endpoint, CONTAINER, "evt-1"));
  }

  /**
   * The failures the DAV side already knows, raised the same way: a dead
   * session is a credentials failure, a permission fault is forbidden, another
   * fault is a refusal that names the code and never BlueMind's message; the
   * session is closed after each.
   */
  @Test
  void refusalsAreTheSameFailuresTheDavSideRaises() {
    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(401, "");
    answer(200, "");
    assertThrows(CalDavAuthenticationException.class, () -> client.importIcs(endpoint, CONTAINER, ICS));

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(403, "{\"errorCode\":\"PERMISSION_DENIED\",\"errorType\":\"ServerFault\",\"message\":\"no\"}");
    answer(200, "");
    assertThrows(CalDavForbiddenException.class, () -> client.importIcs(endpoint, CONTAINER, ICS));

    answer(200, derived("bluemind-rest-login-ok.derived.json"));
    answer(500, "{\"errorCode\":\"SQL_ERROR\",\"errorType\":\"ServerFault\",\"message\":\"secret detail\"}");
    answer(200, "");
    CalDavException refused = assertThrows(CalDavException.class, () -> client.deleteEvent(endpoint, CONTAINER, "evt-1"));
    assertTrue(refused.getMessage().contains("500 (SQL_ERROR)"), refused.getMessage());
    assertFalse(refused.getMessage().contains("secret detail"), refused.getMessage());

    assertEquals(3, sent.stream().filter(request -> request.uri().getPath().endsWith("/api/auth/logout")).count());
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
    try (InputStream stream = BlueMindCalendarImportClientTest.class.getResourceAsStream("/caldav/transcripts/" + name)) {
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
