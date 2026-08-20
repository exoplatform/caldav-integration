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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import org.exoplatform.caldav.service.CaldavRelayService;

/**
 * Admits the DAV verbs through this webapp's Spring Security firewall.
 * {@code StrictHttpFirewall} — the default of the {@code FilterChainProxy}
 * every request crosses before reaching the dispatcher — only allows the
 * seven standard HTTP methods, so PROPFIND, REPORT and MKCALENDAR were
 * rejected as 400 (a Tomcat error page) before the relay controller ever
 * existed to the request. Verified live: {@code FOOBAR} and {@code PROPFIND}
 * on the relay path answered the same 400, while GET reached the security
 * chain.
 * <p>
 * The allowed-method list is built as <b>standard-HTTP ∪
 * {@link CaldavRelayService#ALLOWED_METHODS}</b> — the same constant the
 * relay's own 405 gate reads — never as
 * {@code setUnsafeAllowAnyHttpMethod(true)}. The two gates therefore cannot
 * diverge in the dangerous direction: a verb added to the relay's list is
 * automatically admitted here, and a verb only the firewall admits (the
 * standard ones the registry admin REST needs) still meets the relay's 405
 * on the relay path. Every other {@code StrictHttpFirewall} default is kept:
 * the hrefs the validated servers advertise pass them (BlueMind's
 * {@code calendar:Default:<uid>} colons and Stalwart's {@code %40} are
 * legal), and what they refuse — encoded slashes, semicolons, backslashes,
 * dot-segments — is exactly what the relay's own path validation refuses
 * too.
 * <p>
 * The customizer is a
 * {@code org.springframework.security.config.annotation.web.configuration}
 * {@code .WebSecurityCustomizer} (Spring Security's, not the same-named
 * Meeds interface, which customizes {@code HttpSecurity}): the platform's
 * {@code @EnableWebSecurity} configuration collects those beans and applies
 * them to this WAR's {@code WebSecurity} — the object holding the firewall.
 * Scoped to the caldav Spring context, so no other webapp's firewall
 * changes.
 */
@Configuration
public class CaldavHttpFirewallConfiguration {

  /**
   * The customizer installing the DAV-aware firewall on this webapp's
   * security filter chain.
   *
   * @return the customizer Spring Security's configuration collects
   */
  @Bean
  public WebSecurityCustomizer caldavHttpFirewallCustomizer() {
    return web -> web.httpFirewall(davAwareHttpFirewall());
  }

  /**
   * The firewall itself: strict defaults, methods widened to standard-HTTP
   * plus the relay's own allow-list. Static and public so the test can pin
   * the exact instance the webapp runs, not a reconstruction of it.
   *
   * @return the firewall admitting the DAV verbs
   */
  public static StrictHttpFirewall davAwareHttpFirewall() {
    StrictHttpFirewall firewall = new StrictHttpFirewall();
    // The seven verbs StrictHttpFirewall itself allows by default — spelled
    // out rather than taken from HttpMethod.values(), which would silently
    // add TRACE.
    Set<String> methods = new LinkedHashSet<>(List.of("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"));
    methods.addAll(CaldavRelayService.ALLOWED_METHODS);
    firewall.setAllowedHttpMethods(methods);
    return firewall;
  }
}
