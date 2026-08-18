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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.service.CaldavServerService;
import org.exoplatform.commons.exception.ObjectNotFoundException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * What the registry endpoint owes its callers: the org's status contract
 * (404 for a missing row, 403 for a refused access, 400 with the message code
 * for an invalid input — the legacy JAX-RS resource of this add-on says 401
 * where this new controller says 403, deliberately), and a response that can
 * never carry a credential, because the model it serializes has nowhere to
 * put one.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavServerRestTest {

  private static final String SERVER_URL = "https://dav.example.org/cal/{username}/";

  @Mock
  private CaldavServerService caldavServerService;

  @Mock
  private HttpServletRequest  request;

  @InjectMocks
  private CaldavServerRest    caldavServerRest;

  /**
   * The listing is handed through as the service produced it — and its JSON,
   * serialized exactly as Spring would, holds no credential-shaped key. The
   * assertion is on the serialized form on purpose: a password field added to
   * the model would fail here even if every other test still passed.
   *
   * @throws Exception when serialization fails
   */
  @Test
  public void shouldListServersWithoutAnyCredential() throws Exception {
    List<CaldavServer> servers = List.of(new CaldavServer(1, "agenda.caldavCalendar", "CalDAV", null, SERVER_URL, true),
                                         new CaldavServer(2, "agenda.caldavCalendar.2", "Nextcloud", "Team", SERVER_URL,
                                                          false));
    when(caldavServerService.getServers()).thenReturn(servers);

    List<CaldavServer> response = caldavServerRest.getServers(request);

    assertEquals(servers, response);
    String json = new ObjectMapper().writeValueAsString(response).toLowerCase(Locale.ROOT);
    assertFalse(json.contains("password"), "a registry response must never carry a password");
    assertFalse(json.contains("secret"), "a registry response must never carry a secret");
    assertFalse(json.contains("username\""), "a registry response must never carry an account name");
  }

  /**
   * A service refusal (not an administrator) is a 403 — the org contract,
   * not the 401 the legacy JAX-RS resource answers.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldAnswer403WhenServiceRefusesAccess() throws Exception {
    when(request.getRemoteUser()).thenReturn("mary");
    when(caldavServerService.createServer(any(), anyString())).thenThrow(new IllegalAccessException("not admin"));

    ResponseStatusException refusal =
                                    assertThrows(ResponseStatusException.class,
                                                 () -> caldavServerRest.createServer(request,
                                                                                     new CaldavServer(0, null, "X", null,
                                                                                                      SERVER_URL, true)));
    assertEquals(HttpStatus.FORBIDDEN, refusal.getStatusCode());
  }

  /**
   * An invalid input is a 400 carrying the service's message code, which is
   * what the front-end translates.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldAnswer400WithMessageCodeOnInvalidInput() throws Exception {
    when(request.getRemoteUser()).thenReturn("root");
    when(caldavServerService.createServer(any(), anyString())).thenThrow(new IllegalArgumentException("caldav.server.urlMandatory"));

    ResponseStatusException refusal = assertThrows(ResponseStatusException.class,
                                                   () -> caldavServerRest.createServer(request,
                                                                                       new CaldavServer(0, null, "X", null, " ",
                                                                                                        true)));
    assertEquals(HttpStatus.BAD_REQUEST, refusal.getStatusCode());
    assertEquals("caldav.server.urlMandatory", refusal.getReason());
  }

  /**
   * A missing row is a 404, on the update and on the activation switch alike.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldAnswer404OnMissingServer() throws Exception {
    when(request.getRemoteUser()).thenReturn("root");
    when(caldavServerService.updateServer(any(), anyString())).thenThrow(new ObjectNotFoundException("missing"));
    when(caldavServerService.setServerActive(anyLong(), anyBoolean(), anyString())).thenThrow(new ObjectNotFoundException("missing"));

    ResponseStatusException updateRefusal =
                                          assertThrows(ResponseStatusException.class,
                                                       () -> caldavServerRest.updateServer(request, 99,
                                                                                           new CaldavServer(0, null, "X", null,
                                                                                                            SERVER_URL, true)));
    assertEquals(HttpStatus.NOT_FOUND, updateRefusal.getStatusCode());

    ResponseStatusException statusRefusal = assertThrows(ResponseStatusException.class,
                                                         () -> caldavServerRest.setServerActive(request, 99, true));
    assertEquals(HttpStatus.NOT_FOUND, statusRefusal.getStatusCode());
  }

  /**
   * The id in the path is the one that reaches the service, whatever the body
   * pretends its id is: the URL names the resource, the body only describes
   * it.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldTrustThePathIdOverTheBodyId() throws Exception {
    when(request.getRemoteUser()).thenReturn("root");
    when(caldavServerService.updateServer(any(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));

    CaldavServer body = new CaldavServer(55, null, "X", null, SERVER_URL, true);
    CaldavServer updated = caldavServerRest.updateServer(request, 7, body);

    assertEquals(7, updated.getId());
  }
}
