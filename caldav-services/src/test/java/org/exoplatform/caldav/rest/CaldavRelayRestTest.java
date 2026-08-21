/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.caldav.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.caldav.model.CaldavProbeResult;
import org.exoplatform.caldav.model.CaldavRelayRequest;
import org.exoplatform.caldav.model.CaldavRelayedResponse;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.service.CaldavRelayService;
import org.exoplatform.commons.exception.ObjectNotFoundException;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The transport half of the relay: the DAV path suffix is carved out of the
 * raw request URI (percent-encoding preserved — an %40 decoded here would be
 * re-encoded differently upstream), the upstream answer travels back with
 * its status and rewritten headers, and each service refusal maps onto the
 * documented status with its machine-readable code as the reason.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavRelayRestTest {

  private static final long  SERVER_ID = 5L;

  @Mock
  private CaldavRelayService caldavRelayService;

  @Mock
  private HttpServletRequest request;

  @InjectMocks
  private CaldavRelayRest    caldavRelayRest;

  /**
   * A servlet input stream over fixed bytes, since the servlet API refuses a
   * plain InputStream.
   *
   * @param bytes the body to serve
   * @return the stream
   */
  private ServletInputStream bodyStream(byte[] bytes) {
    ByteArrayInputStream source = new ByteArrayInputStream(bytes);
    return new ServletInputStream() {
      /**
       * Delegates the read to the underlying byte source.
       *
       * @return the next byte, or -1 at the end
       * @throws IOException never, the source is in memory
       */
      @Override
      public int read() throws IOException {
        return source.read();
      }

      /**
       * Answers whether every byte has been served.
       *
       * @return true once the source is exhausted
       */
      @Override
      public boolean isFinished() {
        return source.available() == 0;
      }

      /**
       * The in-memory source is always readable.
       *
       * @return true always
       */
      @Override
      public boolean isReady() {
        return true;
      }

      /**
       * Non-blocking reads are not used by these tests.
       *
       * @param readListener ignored
       */
      @Override
      public void setReadListener(ReadListener readListener) {
        // in-memory stream, nothing asynchronous to arm
      }
    };
  }

  /**
   * Wires the request shape every relay call shares.
   *
   * @throws IOException never, the body is in memory
   */
  @BeforeEach
  public void setUpRequest() throws IOException {
    lenient().when(request.getContextPath()).thenReturn("/caldav");
    lenient().when(request.getServletPath()).thenReturn("/rest");
    lenient().when(request.getRemoteUser()).thenReturn("john");
    lenient().when(request.getMethod()).thenReturn("PROPFIND");
    lenient().when(request.getQueryString()).thenReturn(null);
    lenient().when(request.getHeaderNames()).thenReturn(Collections.enumeration(Collections.singletonList("Depth")));
    lenient().when(request.getHeader("Depth")).thenReturn("1");
    lenient().when(request.getInputStream()).thenReturn(bodyStream("<propfind/>".getBytes(StandardCharsets.UTF_8)));
    lenient().when(caldavRelayService.getMaxBodyBytes()).thenReturn(1024L);
  }

  /**
   * The DAV path is the raw suffix after the relay prefix, encoding intact,
   * and the service receives the caller, the method, the headers
   * (lower-cased) and the body untouched.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldCarveTheRawDavPathOutOfTheRequestUri() throws Exception {
    when(request.getRequestURI()).thenReturn("/caldav/rest/dav/5/dav/cal/john%40x/personal/");
    when(caldavRelayService.relay(any())).thenReturn(new CaldavRelayedResponse(207, Map.of(), new byte[0]));

    caldavRelayRest.relay(request, SERVER_ID);

    ArgumentCaptor<CaldavRelayRequest> relayed = ArgumentCaptor.forClass(CaldavRelayRequest.class);
    verify(caldavRelayService).relay(relayed.capture());
    CaldavRelayRequest sent = relayed.getValue();
    assertEquals("john", sent.getUsername());
    assertEquals(SERVER_ID, sent.getServerId());
    assertEquals("PROPFIND", sent.getMethod());
    assertEquals("/dav/cal/john%40x/personal/", sent.getDavPath());
    assertEquals("/caldav/rest/dav/5", sent.getRelayPrefix());
    assertEquals("1", sent.getHeaders().get("depth"));
    assertArrayEquals("<propfind/>".getBytes(StandardCharsets.UTF_8), sent.getBody());
  }

  /**
   * The upstream answer travels back as-is: status, allow-listed headers,
   * body bytes.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldAnswerWithTheRelayedStatusHeadersAndBody() throws Exception {
    when(request.getRequestURI()).thenReturn("/caldav/rest/dav/5/dav/cal/john/");
    byte[] body = "<multistatus/>".getBytes(StandardCharsets.UTF_8);
    when(caldavRelayService.relay(any())).thenReturn(new CaldavRelayedResponse(207,
                                                                               Map.of("content-type", "application/xml",
                                                                                      "etag", "\"e\""),
                                                                               body));

    ResponseEntity<byte[]> response = caldavRelayRest.relay(request, SERVER_ID);

    assertEquals(207, response.getStatusCode().value());
    assertEquals("application/xml", response.getHeaders().getFirst("content-type"));
    assertEquals("\"e\"", response.getHeaders().getFirst("etag"));
    assertArrayEquals(body, response.getBody());
  }

  /**
   * Each service refusal maps onto the documented status, carrying its
   * machine-readable code as the reason: 404 unknown row, 403 refused
   * target, 409 no connected account, 405 method outside the allow-list,
   * 400 invalid path.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldMapServiceRefusalsOntoTheDocumentedStatuses() throws Exception {
    when(request.getRequestURI()).thenReturn("/caldav/rest/dav/5/dav/");

    org.mockito.Mockito.doThrow(new ObjectNotFoundException("no row")).when(caldavRelayService).relay(any());
    assertEquals(HttpStatus.NOT_FOUND, statusOfRelay());

    org.mockito.Mockito.doThrow(new IllegalAccessException(CaldavRelayService.SERVER_MISMATCH_MESSAGE))
                       .when(caldavRelayService)
                       .relay(any());
    assertEquals(HttpStatus.FORBIDDEN, statusOfRelay());

    org.mockito.Mockito.doThrow(new IllegalStateException(CaldavRelayService.NOT_CONNECTED_MESSAGE))
                       .when(caldavRelayService)
                       .relay(any());
    assertEquals(HttpStatus.CONFLICT, statusOfRelay());

    org.mockito.Mockito.doThrow(new IllegalArgumentException(CaldavRelayService.METHOD_NOT_ALLOWED_MESSAGE))
                       .when(caldavRelayService)
                       .relay(any());
    assertEquals(HttpStatus.METHOD_NOT_ALLOWED, statusOfRelay());

    org.mockito.Mockito.doThrow(new IllegalArgumentException(CaldavRelayService.INVALID_PATH_MESSAGE))
                       .when(caldavRelayService)
                       .relay(any());
    assertEquals(HttpStatus.BAD_REQUEST, statusOfRelay());
  }

  /**
   * A request whose URI does not sit under the relay prefix — unreachable
   * through normal routing, conceivable through a crafted dispatch — is a
   * plain 400, never forwarded.
   */
  @Test
  public void shouldRefuseARequestOutsideTheRelayPrefix() {
    when(request.getRequestURI()).thenReturn("/elsewhere/rest/dav/5/dav/");

    ResponseStatusException refusal = assertThrows(ResponseStatusException.class,
                                                   () -> caldavRelayRest.relay(request, SERVER_ID));

    assertEquals(HttpStatus.BAD_REQUEST, refusal.getStatusCode());
  }

  /**
   * A request body over the relay's cap is refused with a 413 before the
   * service is even called.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldRefuseAnOversizedRequestBody() throws Exception {
    when(request.getRequestURI()).thenReturn("/caldav/rest/dav/5/dav/cal/john/x.ics");
    when(caldavRelayService.getMaxBodyBytes()).thenReturn(4L);
    when(request.getInputStream()).thenReturn(bodyStream("way over four bytes".getBytes(StandardCharsets.UTF_8)));

    ResponseStatusException refusal = assertThrows(ResponseStatusException.class,
                                                   () -> caldavRelayRest.relay(request, SERVER_ID));

    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, refusal.getStatusCode());
    verify(caldavRelayService, org.mockito.Mockito.never()).relay(any());
  }

  /**
   * The verify endpoint hands the probe outcome through, and maps the
   * service refusals: 404 unknown registration, 403 deactivated, 400
   * unusable credentials.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldVerifyTypedCredentialsThroughTheService() throws Exception {
    CaldavUserSetting probe = new CaldavUserSetting();
    probe.setServerId(SERVER_ID);
    probe.setUsername("john");
    probe.setPassword("pw");
    when(caldavRelayService.probeAccount(SERVER_ID, "john", "pw"))
                                                                  .thenReturn(new CaldavProbeResult(CaldavProbeResult.OK, 207));

    CaldavProbeResult result = caldavRelayRest.verify(request, probe);

    assertEquals(CaldavProbeResult.OK, result.getResult());

    when(caldavRelayService.probeAccount(SERVER_ID, "john", "pw")).thenThrow(new ObjectNotFoundException("no row"));
    assertEquals(HttpStatus.NOT_FOUND,
                 assertThrows(ResponseStatusException.class, () -> caldavRelayRest.verify(request, probe)).getStatusCode());

    org.mockito.Mockito.reset(caldavRelayService);
    when(caldavRelayService.probeAccount(SERVER_ID, "john", "pw"))
                                                                  .thenThrow(new IllegalAccessException(CaldavRelayService.SERVER_INACTIVE_MESSAGE));
    assertEquals(HttpStatus.FORBIDDEN,
                 assertThrows(ResponseStatusException.class, () -> caldavRelayRest.verify(request, probe)).getStatusCode());

    org.mockito.Mockito.reset(caldavRelayService);
    when(caldavRelayService.probeAccount(SERVER_ID, "john", "pw"))
                                                                  .thenThrow(new IllegalArgumentException(CaldavRelayService.PROBE_CREDENTIALS_MESSAGE));
    assertEquals(HttpStatus.BAD_REQUEST,
                 assertThrows(ResponseStatusException.class, () -> caldavRelayRest.verify(request, probe)).getStatusCode());

    assertEquals(HttpStatus.BAD_REQUEST,
                 assertThrows(ResponseStatusException.class, () -> caldavRelayRest.verify(request, null)).getStatusCode());
  }

  /**
   * The relay's own answer for a service refusal — asserted through the
   * thrown exception's status.
   *
   * @return the status the refusal maps onto
   */
  private HttpStatus statusOfRelay() {
    try {
      caldavRelayRest.relay(request, SERVER_ID);
      throw new AssertionError("the relay should have refused");
    } catch (ResponseStatusException e) {
      return HttpStatus.valueOf(e.getStatusCode().value());
    }
  }
}
