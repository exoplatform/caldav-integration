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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import org.exoplatform.caldav.service.CaldavRelayService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Pins the webapp's Spring Security firewall to the DAV reality this add-on
 * lives in. The defect this guards: {@code StrictHttpFirewall}'s default
 * allowed-method list is the seven standard verbs, so every PROPFIND, REPORT
 * and MKCALENDAR was rejected as a 400 — a Tomcat error page — before the
 * relay controller existed to the request, seen live as "Error while
 * configuring the calendar receiving your meetings" the moment the relay
 * shipped. The firewall must admit exactly standard-HTTP plus the relay's
 * own {@link CaldavRelayService#ALLOWED_METHODS} (a shared constant, so the
 * two gates cannot diverge), keep refusing everything else, and accept the
 * URL shapes the validated servers really advertise — BlueMind's
 * {@code calendar:Default:<uid>} colons and Stalwart's {@code %40}.
 */
public class CaldavHttpFirewallConfigurationTest {

  /**
   * A request as the firewall inspects it: method plus the path split the
   * servlet container hands over.
   *
   * @param method HTTP method of the request
   * @param requestUri raw request URI, context path included
   * @param pathInfo decoded path within the REST servlet
   * @return the request to firewall
   */
  private HttpServletRequest request(String method, String requestUri, String pathInfo) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn(method);
    when(request.getRequestURI()).thenReturn(requestUri);
    when(request.getContextPath()).thenReturn("/caldav");
    when(request.getServletPath()).thenReturn("/rest");
    when(request.getPathInfo()).thenReturn(pathInfo);
    return request;
  }

  /**
   * Every DAV verb the relay allow-lists passes the firewall — the exact
   * regression that shipped: PROPFIND rejected before the dispatcher.
   */
  @Test
  public void shouldAdmitEveryRelayedDavVerb() {
    StrictHttpFirewall firewall = CaldavHttpFirewallConfiguration.davAwareHttpFirewall();
    for (String method : CaldavRelayService.ALLOWED_METHODS) {
      assertDoesNotThrow(() -> firewall.getFirewalledRequest(request(method,
                                                                     "/caldav/rest/dav/1/dav/cal/alice/",
                                                                     "/dav/1/dav/cal/alice/")),
                         method + " must pass the firewall");
    }
  }

  /**
   * The standard verbs the rest of this webapp's REST needs (the registry
   * admin REST speaks POST, PUT, PATCH, DELETE) keep passing.
   */
  @Test
  public void shouldKeepAdmittingTheStandardVerbs() {
    StrictHttpFirewall firewall = CaldavHttpFirewallConfiguration.davAwareHttpFirewall();
    for (String method : List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
      assertDoesNotThrow(() -> firewall.getFirewalledRequest(request(method, "/caldav/rest/servers", "/servers")),
                         method + " must pass the firewall");
    }
  }

  /**
   * The widening is an allow-list, not {@code setUnsafeAllowAnyHttpMethod}:
   * an invented verb, TRACE, and the DAV verbs the relay deliberately
   * refuses (PROPPATCH, MOVE, LOCK) are still rejected at the firewall — the
   * negative control proving the gate exists at all.
   */
  @Test
  public void shouldStillRejectEverythingOutsideTheTwoLists() {
    StrictHttpFirewall firewall = CaldavHttpFirewallConfiguration.davAwareHttpFirewall();
    for (String method : List.of("FOOBAR", "TRACE", "PROPPATCH", "MOVE", "LOCK", "COPY", "MKCOL")) {
      assertThrows(RequestRejectedException.class,
                   () -> firewall.getFirewalledRequest(request(method, "/caldav/rest/dav/1/dav/", "/dav/1/dav/")),
                   method + " must stay rejected");
    }
  }

  /**
   * The URL shapes the validated servers really advertise pass the strict
   * path rules: BlueMind roots calendar collections at
   * {@code calendar:Default:<uid>} (colons in a path segment are legal
   * pchars) and Stalwart spells the account {@code alice%40stalwart.local}.
   * Rejecting either would break the flows the relay exists for.
   */
  @Test
  public void shouldAcceptTheRealServersPathShapes() {
    StrictHttpFirewall firewall = CaldavHttpFirewallConfiguration.davAwareHttpFirewall();
    assertDoesNotThrow(() -> firewall.getFirewalledRequest(
        request("PROPFIND",
                "/caldav/rest/dav/2/dav/calendars/vcontainer/calendar:Default:jdoe@bluemind.test/",
                "/dav/2/dav/calendars/vcontainer/calendar:Default:jdoe@bluemind.test/")));
    assertDoesNotThrow(() -> firewall.getFirewalledRequest(
        request("REPORT",
                "/caldav/rest/dav/1/dav/cal/alice%40stalwart.local/default/",
                "/dav/1/dav/cal/alice@stalwart.local/default/")));
  }

  /**
   * The strict path rules are kept, not relaxed: an encoded slash, a
   * semicolon and a dot-segment stay rejected — the same shapes the relay's
   * own path validation refuses, so the two gates agree on paths exactly as
   * they agree on methods.
   */
  @Test
  public void shouldKeepTheStrictPathRules() {
    StrictHttpFirewall firewall = CaldavHttpFirewallConfiguration.davAwareHttpFirewall();
    assertThrows(RequestRejectedException.class,
                 () -> firewall.getFirewalledRequest(request("PROPFIND",
                                                             "/caldav/rest/dav/1/dav/a%2Fb/",
                                                             "/dav/1/dav/a/b/")));
    assertThrows(RequestRejectedException.class,
                 () -> firewall.getFirewalledRequest(request("PROPFIND",
                                                             "/caldav/rest/dav/1/dav/x;jsessionid=1/",
                                                             "/dav/1/dav/x;jsessionid=1/")));
    assertThrows(RequestRejectedException.class,
                 () -> firewall.getFirewalledRequest(request("PROPFIND",
                                                             "/caldav/rest/dav/1/../secrets/",
                                                             "/dav/1/../secrets/")));
  }

  /**
   * The customizer bean exists and hands Spring Security a firewall — the
   * wiring half of the fix.
   */
  @Test
  public void shouldExposeTheCustomizerBean() {
    assertNotNull(new CaldavHttpFirewallConfiguration().caldavHttpFirewallCustomizer());
  }
}
