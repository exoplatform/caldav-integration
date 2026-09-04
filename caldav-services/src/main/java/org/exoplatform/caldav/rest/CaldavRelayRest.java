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
package org.exoplatform.caldav.rest;

import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.caldav.model.CaldavProbeResult;
import org.exoplatform.caldav.model.CaldavRelayRequest;
import org.exoplatform.caldav.model.CaldavRelayedResponse;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.service.CaldavRelayService;
import org.exoplatform.commons.exception.ObjectNotFoundException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP face of the CalDAV relay: {@code /caldav/rest/dav/{serverId}/**}
 * forwards DAV requests of the authenticated user to the declared server the
 * id names, and {@code /caldav/rest/connection/verify} probes typed
 * credentials at connect time. Every decision — which targets are legal,
 * which methods and headers pass, whose credentials are injected — lives in
 * {@link CaldavRelayService}; this layer only moves bytes and maps the
 * service's exceptions onto statuses (404 unknown registration, 403 refused
 * target, 409 no connected account, 405 method outside the allow-list, 400
 * invalid path, 413 oversized body), each refusal carrying its
 * machine-readable code in the {@code x-caldav-relay-code} header so the
 * browser can tell a relay verdict from an upstream answer.
 */
@RestController
@Tag(name = "/caldav/rest/dav", description = "Relays DAV requests of the connected user to declared CalDAV servers")
public class CaldavRelayRest {

  @Autowired
  private CaldavRelayService caldavRelayService;

  /**
   * Relays one DAV request to the declared server the path names. Mapped
   * without a method restriction on purpose: the DAV verbs the relay exists
   * for — PROPFIND, REPORT, MKCALENDAR — are not in Spring's RequestMethod
   * vocabulary, and the service enforces the actual method allow-list.
   *
   * @param request the HTTP request, carrying the authenticated user, the
   *          method, the body and the DAV path suffix
   * @param serverId technical identifier of the targeted registration
   * @return the upstream response, statuses passed through, hrefs rewritten
   */
  // S3752: the mapping deliberately accepts any verb — PROPFIND, REPORT and
  // MKCALENDAR have no RequestMethod constant to enumerate, and restricting
  // the mapping to the standard verbs would disable the relay outright. The
  // real allow-list is CaldavRelayService.ALLOWED_METHODS, enforced before
  // anything is forwarded, and duplicated into the Spring Security firewall.
  @SuppressWarnings("java:S3752")
  @RequestMapping("/dav/{serverId}/**")
  @Secured("users")
  @Operation(summary = "Relays a DAV request to a declared CalDAV server", method = "PROPFIND",
      description = "Forwards the DAV request to the declared server the id names, injecting the stored credentials "
          + "of the connected user. Targets resolve only from the administrator registry, never from a client URL.")
  @ApiResponses(value = { @ApiResponse(responseCode = "207", description = "Upstream multistatus answer"),
      @ApiResponse(responseCode = "400", description = "Invalid DAV path"),
      @ApiResponse(responseCode = "403", description = "Target refused, or stored CalDAV credentials rejected upstream"),
      @ApiResponse(responseCode = "404", description = "Unknown server registration"),
      @ApiResponse(responseCode = "405", description = "Method outside the relay allow-list"),
      @ApiResponse(responseCode = "409", description = "No connected CalDAV account"),
      @ApiResponse(responseCode = "413", description = "Request body over the configured cap"),
      @ApiResponse(responseCode = "502", description = "CalDAV server unreachable or answer over the configured cap") })
  public ResponseEntity<byte[]> relay(HttpServletRequest request,
                                      @Parameter(description = "Technical identifier of the targeted registration",
                                          required = true)
                                      @PathVariable("serverId")
                                      long serverId) {
    String relayPrefix = request.getContextPath() + request.getServletPath() + "/dav/" + serverId;
    String requestUri = request.getRequestURI();
    if (!requestUri.startsWith(relayPrefix)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CaldavRelayService.INVALID_PATH_MESSAGE);
    }
    String davPath = requestUri.substring(relayPrefix.length());
    CaldavRelayRequest relayRequest = new CaldavRelayRequest(request.getRemoteUser(),
                                                             serverId,
                                                             request.getMethod(),
                                                             davPath,
                                                             request.getQueryString(),
                                                             readHeaders(request),
                                                             readBody(request),
                                                             relayPrefix);
    try {
      CaldavRelayedResponse relayed = caldavRelayService.relay(relayRequest);
      HttpHeaders headers = new HttpHeaders();
      relayed.getHeaders().forEach(headers::add);
      return ResponseEntity.status(HttpStatusCode.valueOf(relayed.getStatus())).headers(headers).body(relayed.getBody());
    } catch (ObjectNotFoundException e) {
      throw refusal(HttpStatus.NOT_FOUND, "caldav.relay.unknownServer");
    } catch (IllegalAccessException e) {
      throw refusal(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (IllegalStateException e) {
      throw refusal(HttpStatus.CONFLICT, e.getMessage());
    } catch (IllegalArgumentException e) {
      throw refusal(CaldavRelayService.METHOD_NOT_ALLOWED_MESSAGE.equals(e.getMessage()) ? HttpStatus.METHOD_NOT_ALLOWED
                                                                                         : HttpStatus.BAD_REQUEST,
                    e.getMessage());
    }
  }

  /**
   * Probes typed CalDAV credentials against a declared server, without
   * storing anything: what the connect drawer calls before saving an
   * account, now server-side so that servers sending no CORS headers —
   * BlueMind — connect without any front proxy.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param probe the typed credentials and the targeted registration id
   *          (null resolves the seed registration, like every legacy account)
   * @return the classified probe outcome
   */
  @PostMapping("/connection/verify")
  @Secured("users")
  @Operation(summary = "Verifies typed CalDAV credentials against a declared server", method = "POST",
      description = "Probes the account server-side with a Depth:0 PROPFIND and classifies the outcome with the "
          + "stable caldav.error.* codes. Nothing is stored.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Probe performed, outcome in the body"),
      @ApiResponse(responseCode = "400", description = "Missing credentials"),
      @ApiResponse(responseCode = "403", description = "Server deactivated"),
      @ApiResponse(responseCode = "404", description = "Unknown server registration") })
  public CaldavProbeResult verify(HttpServletRequest request,
                                  @RequestBody
                                  CaldavUserSetting probe) {
    if (probe == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CaldavRelayService.PROBE_CREDENTIALS_MESSAGE);
    }
    try {
      return caldavRelayService.probeAccount(probe.getServerId(), probe.getUsername(), probe.getPassword());
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * A relay refusal as a thrown status exception carrying its
   * machine-readable code — both as the reason (so the JSON error body names
   * it) and later surfaced to the browser.
   *
   * @param status HTTP status of the refusal
   * @param code machine-readable relay code
   * @return the exception to throw
   */
  private ResponseStatusException refusal(HttpStatus status, String code) {
    // The class contract above promises the code in a header on every refusal, so the
    // browser can tell a relay verdict from an upstream answer. Putting it only in the
    // exception reason did not honour that: the reason reaches the body, not the headers,
    // and the connector reads the header. ResponseStatusException carries its own headers,
    // which Spring copies onto the response.
    HttpHeaders headers = new HttpHeaders();
    headers.set(CaldavRelayService.RELAY_CODE_HEADER, code);
    return new ResponseStatusException(status, code, null) {
      @Override
      public HttpHeaders getHeaders() {
        return headers;
      }
    };
  }

  /**
   * The request headers as a lower-cased single-value map — all of them, the
   * service applying the allow-list, so the filtering rule lives in one
   * place.
   *
   * @param request the HTTP request
   * @return header name to first value, names lower-cased
   */
  private Map<String, String> readHeaders(HttpServletRequest request) {
    Map<String, String> headers = new LinkedHashMap<>();
    Enumeration<String> names = request.getHeaderNames();
    while (names != null && names.hasMoreElements()) {
      String name = names.nextElement();
      headers.putIfAbsent(name.toLowerCase(Locale.ENGLISH), request.getHeader(name));
    }
    return headers;
  }

  /**
   * Reads the request body up to the relay's configured cap.
   *
   * @param request the HTTP request
   * @return the body bytes, possibly empty
   * @throws ResponseStatusException 413 when the body exceeds the cap, 400
   *           when the body cannot be read
   */
  private byte[] readBody(HttpServletRequest request) {
    long max = caldavRelayService.getMaxBodyBytes();
    try {
      byte[] body = request.getInputStream().readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE));
      if (body.length > max) {
        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "caldav.relay.requestTooLarge");
      }
      return body;
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "caldav.relay.unreadableBody");
    }
  }
}
