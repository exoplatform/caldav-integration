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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.MirrorTargetKind;
import org.exoplatform.caldav.model.CaldavManagedMode;
import org.exoplatform.caldav.service.CaldavManagedModeService;
import org.exoplatform.caldav.service.CaldavServerService;
import org.exoplatform.caldav.service.CaldavTuningService;
import org.exoplatform.caldav.model.CaldavSyncTuning;
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
  private CaldavTuningService caldavTuningService;

  @Mock
  private CaldavManagedModeService caldavManagedModeService;

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
    List<CaldavServer> servers = List.of(server(1, "agenda.caldavCalendar", "CalDAV", null, SERVER_URL, true),
                                         server(2, "agenda.caldavCalendar.2", "Nextcloud", "Team", SERVER_URL,
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
                                                                                     server(0, null, "X", null,
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
                                                                                       server(0, null, "X", null, " ",
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
                                                                                           server(0, null, "X", null,
                                                                                                            SERVER_URL, true)));
    assertEquals(HttpStatus.NOT_FOUND, updateRefusal.getStatusCode());

    ResponseStatusException statusRefusal = assertThrows(ResponseStatusException.class,
                                                         () -> caldavServerRest.setServerActive(request, 99, true));
    assertEquals(HttpStatus.NOT_FOUND, statusRefusal.getStatusCode());
  }

  /**
   * A delete refused because connected accounts still reference the row is a
   * 409 carrying the message code with the count — what the admin UI
   * translates into an explanation, not a mystery failure.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldAnswer409OnDeleteOfReferencedServer() throws Exception {
    when(request.getRemoteUser()).thenReturn("root");
    doThrow(new IllegalStateException("caldav.server.referenced:3")).when(caldavServerService).deleteServer(7, "root");

    ResponseStatusException refusal = assertThrows(ResponseStatusException.class,
                                                   () -> caldavServerRest.deleteServer(request, 7));

    assertEquals(HttpStatus.CONFLICT, refusal.getStatusCode());
    assertEquals("caldav.server.referenced:3", refusal.getReason());
  }

  /**
   * A row without an image answers 404 on its image endpoint — not a broken
   * stream, not a placeholder pretending to be the upload.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldAnswer404OnMissingImage() throws Exception {
    when(caldavServerService.getServerImageInputStream(7)).thenReturn(null);

    ResponseStatusException refusal = assertThrows(ResponseStatusException.class,
                                                   () -> caldavServerRest.getServerImage(request, 7));

    assertEquals(HttpStatus.NOT_FOUND, refusal.getStatusCode());
  }

  /**
   * A stored image is served back as a PNG stream.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldServeTheStoredImage() throws Exception {
    when(caldavServerService.getServerImageInputStream(7)).thenReturn(new ByteArrayInputStream(new byte[] { 1, 2, 3 }));

    var response = caldavServerRest.getServerImage(request, 7);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
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

    CaldavServer body = server(55, null, "X", null, SERVER_URL, true);
    CaldavServer updated = caldavServerRest.updateServer(request, 7, body);

    assertEquals(7, updated.getId());
  }

  /**
   * An accepted create answers the created registration, id and provider name
   * filled by the service — what the admin drawer reads back into its table.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldAnswerTheCreatedServer() throws Exception {
    when(request.getRemoteUser()).thenReturn("root");
    CaldavServer created = server(7, "agenda.caldavCalendar.7", "Nextcloud", null, SERVER_URL, true);
    when(caldavServerService.createServer(any(), anyString())).thenReturn(created);

    assertEquals(created, caldavServerRest.createServer(request, server(0, null, "Nextcloud", null, SERVER_URL, true)));
  }

  /**
   * The update maps the whole contract: an accepted one answers the updated
   * registration, a refused access is 403, an invalid input is 400 carrying
   * the message code.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldMapUpdateOutcomesOntoStatuses() throws Exception {
    when(request.getRemoteUser()).thenReturn("mary");
    CaldavServer body = server(0, null, "X", null, SERVER_URL, true);

    when(caldavServerService.updateServer(any(), anyString())).thenThrow(new IllegalAccessException("not admin"));
    ResponseStatusException accessRefusal = assertThrows(ResponseStatusException.class,
                                                         () -> caldavServerRest.updateServer(request, 7, body));
    assertEquals(HttpStatus.FORBIDDEN, accessRefusal.getStatusCode());

    org.mockito.Mockito.reset(caldavServerService);
    when(caldavServerService.updateServer(any(), anyString())).thenThrow(new IllegalArgumentException("caldav.server.nameMandatory"));
    ResponseStatusException inputRefusal = assertThrows(ResponseStatusException.class,
                                                        () -> caldavServerRest.updateServer(request, 7, body));
    assertEquals(HttpStatus.BAD_REQUEST, inputRefusal.getStatusCode());
    assertEquals("caldav.server.nameMandatory", inputRefusal.getReason());
  }

  /**
   * An accepted activation switch answers the updated registration, and a
   * refused one (not an administrator) is a 403.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldFlipTheSwitchOrAnswer403() throws Exception {
    when(request.getRemoteUser()).thenReturn("root");
    CaldavServer deactivated = server(7, "agenda.caldavCalendar.7", "Nextcloud", null, SERVER_URL, false);
    when(caldavServerService.setServerActive(7, false, "root")).thenReturn(deactivated);

    assertEquals(deactivated, caldavServerRest.setServerActive(request, 7, false));

    org.mockito.Mockito.reset(caldavServerService);
    when(request.getRemoteUser()).thenReturn("mary");
    when(caldavServerService.setServerActive(anyLong(), anyBoolean(), anyString())).thenThrow(new IllegalAccessException("not admin"));
    ResponseStatusException refusal = assertThrows(ResponseStatusException.class,
                                                   () -> caldavServerRest.setServerActive(request, 7, false));
    assertEquals(HttpStatus.FORBIDDEN, refusal.getStatusCode());
  }

  /**
   * The delete maps the whole contract: an accepted one answers plainly, a
   * missing row is 404, a refused access is 403 (the 409 for a still
   * referenced row has its own test).
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldMapDeleteOutcomesOntoStatuses() throws Exception {
    when(request.getRemoteUser()).thenReturn("root");

    caldavServerRest.deleteServer(request, 7);
    verify(caldavServerService).deleteServer(7, "root");

    doThrow(new ObjectNotFoundException("missing")).when(caldavServerService).deleteServer(99, "root");
    ResponseStatusException missingRefusal = assertThrows(ResponseStatusException.class,
                                                          () -> caldavServerRest.deleteServer(request, 99));
    assertEquals(HttpStatus.NOT_FOUND, missingRefusal.getStatusCode());

    doThrow(new IllegalAccessException("not admin")).when(caldavServerService).deleteServer(8, "root");
    ResponseStatusException accessRefusal = assertThrows(ResponseStatusException.class,
                                                         () -> caldavServerRest.deleteServer(request, 8));
    assertEquals(HttpStatus.FORBIDDEN, accessRefusal.getStatusCode());
  }

  /**
   * Asking the image of a row that does not exist at all is a 404 too — the
   * service's ObjectNotFoundException, not just its null-stream answer.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void shouldAnswer404OnImageOfMissingServer() throws Exception {
    when(caldavServerService.getServerImageInputStream(99)).thenThrow(new ObjectNotFoundException("missing"));

    ResponseStatusException refusal = assertThrows(ResponseStatusException.class,
                                                   () -> caldavServerRest.getServerImage(request, 99));

    assertEquals(HttpStatus.NOT_FOUND, refusal.getStatusCode());
  }

  /**
   * Builds a registration with the six identity fields — the icon/image
   * fields default to null, exactly as a fresh REST payload leaves them.
   *
   * @param id technical identifier
   * @param providerName agenda provider name
   * @param name display name
   * @param description optional description
   * @param serverUrl base URL
   * @param active activation
   * @return the registration
   */
  private static CaldavServer server(long id, String providerName, String name, String description, String serverUrl,
                                     boolean active) {
    return new CaldavServer(id, providerName, name, description, serverUrl, active, null, null, null, null, true, null,
                            null, null, null, null, MirrorTargetKind.DEDICATED_CALENDAR, null);
  }

  /**
   * The tuning is handed through as the service resolved it.
   */
  @Test
  public void theTuningIsReadThroughToTheService() {
    // Property, or saved value, or coded default — which of the three it is
    // has already been decided by the time it reaches here, and this layer
    // must not second-guess it.
    CaldavSyncTuning tuning = new CaldavSyncTuning(15L, 60L, 365L, 30L, 50);
    when(caldavTuningService.getTuning()).thenReturn(tuning);

    assertEquals(tuning, caldavServerRest.getTuning());
  }

  /**
   * Saving answers with what is now in force, not with what was sent.
   */
  @Test
  public void savingAnswersWithWhatIsNowInForce() {
    // The service is what decides. Echoing the request back would let a value
    // it adjusted sit on the administrator's screen as though it had been
    // taken as typed.
    CaldavSyncTuning sent = new CaldavSyncTuning(5L, 30L, 90L, 60L, 20);
    CaldavSyncTuning stored = new CaldavSyncTuning(5L, 30L, 90L, 60L, 20);
    when(caldavTuningService.getTuning()).thenReturn(stored);

    assertEquals(stored, caldavServerRest.saveTuning(sent));

    verify(caldavTuningService).saveTuning(sent);
  }

  /**
   * A value out of range comes back as a 400 carrying its message code.
   */
  @Test
  public void aValueOutOfRangeIsFourHundredWithItsCode() {
    // The code is the whole point of the status: it is what lets the screen
    // say which value was refused instead of "something went wrong".
    CaldavSyncTuning tooWide = new CaldavSyncTuning(15L, 60L, 40000L, 30L, 50);
    doThrow(new IllegalArgumentException("caldav.tuning.futureDaysOutOfRange")).when(caldavTuningService)
                                                                              .saveTuning(tooWide);

    ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                                                   () -> caldavServerRest.saveTuning(tooWide));

    assertEquals(HttpStatus.BAD_REQUEST, refused.getStatusCode());
    assertEquals("caldav.tuning.futureDaysOutOfRange", refused.getReason());
  }


  /**
   * The managed-mode read hands back exactly what the service decided,
   * per viewer.
   *
   * <p>
   * The username is passed down rather than the verdict computed here: this
   * layer holds no ACL knowledge by contract, and the whole point of answering
   * per viewer is that the decision stays in one service method when group
   * exclusions arrive.
   */
  @Test
  public void theManagedReadIsAnsweredPerViewer() {
    when(request.getRemoteUser()).thenReturn("mary");
    CaldavManagedMode mode = new CaldavManagedMode(7L, "Bluemind", true);
    when(caldavManagedModeService.getManagedMode("mary")).thenReturn(mode);

    assertEquals(mode, caldavServerRest.getManagedMode(request));
  }

  /**
   * Turning managed mode on answers with what is now in force, not with what
   * was asked for.
   */
  @Test
  public void turningManagedModeOnAnswersWithWhatIsNowInForce() {
    when(request.getRemoteUser()).thenReturn("root");
    CaldavManagedMode stored = new CaldavManagedMode(7L, "Bluemind", true);
    when(caldavManagedModeService.getManagedMode("root")).thenReturn(stored);

    assertEquals(stored, caldavServerRest.saveManagedMode(request, 7));

    verify(caldavManagedModeService).saveManagedServer(7);
  }

  /**
   * A server that is not a candidate comes back as a 400 carrying its message
   * code, which is what lets the drawer say why rather than flick its switch
   * back without a word.
   */
  @Test
  public void anIneligibleServerIsFourHundredWithItsCode() {
    doThrow(new IllegalArgumentException("caldav.managed.serverNotEligible")).when(caldavManagedModeService)
                                                                            .saveManagedServer(9);

    ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                                                   () -> caldavServerRest.saveManagedMode(request, 9));

    assertEquals(HttpStatus.BAD_REQUEST, refused.getStatusCode());
    assertEquals("caldav.managed.serverNotEligible", refused.getReason());
  }

  /**
   * Turning managed mode off clears the choice and answers the mode that now
   * stands, which names no server.
   */
  @Test
  public void turningManagedModeOffClearsTheChoice() {
    when(request.getRemoteUser()).thenReturn("root");
    CaldavManagedMode off = new CaldavManagedMode(null, null, false);
    when(caldavManagedModeService.getManagedMode("root")).thenReturn(off);

    assertEquals(off, caldavServerRest.clearManagedMode(request));

    verify(caldavManagedModeService).clearManagedServer();
  }

  /**
   * Deleting the managed server is a 400 carrying its code, not a 409 and not
   * a 500.
   *
   * <p>
   * A 409 would say another object is in the way, which is not what happened:
   * nothing references the row, a rule refuses it. And without the catch this
   * pins, the message code reached the browser as a 500 and the snackbar had
   * nothing to say.
   */
  @Test
  public void deletingTheManagedServerIsFourHundredWithItsCode() throws Exception {
    doThrow(new IllegalArgumentException("caldav.managed.serverInUse")).when(caldavServerService)
                                                                      .deleteServer(anyLong(), any());

    ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                                                   () -> caldavServerRest.deleteServer(request, 7));

    assertEquals(HttpStatus.BAD_REQUEST, refused.getStatusCode());
    assertEquals("caldav.managed.serverInUse", refused.getReason());
  }

  /**
   * Deactivating it is refused the same way, through the status the registry
   * endpoint already maps IllegalArgumentException onto.
   */
  @Test
  public void deactivatingTheManagedServerIsFourHundredWithItsCode() throws Exception {
    doThrow(new IllegalArgumentException("caldav.managed.serverInUse")).when(caldavServerService)
                                                                      .setServerActive(anyLong(), anyBoolean(), any());

    ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                                                   () -> caldavServerRest.setServerActive(request, 7, false));

    assertEquals(HttpStatus.BAD_REQUEST, refused.getStatusCode());
    assertEquals("caldav.managed.serverInUse", refused.getReason());
  }

}
