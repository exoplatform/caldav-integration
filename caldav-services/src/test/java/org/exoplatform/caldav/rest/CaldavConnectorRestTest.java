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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.ws.rs.core.Response;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.service.CaldavConnectorService;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The endpoint that records which remote collection a user's meetings are
 * pushed to. What matters here is the status it answers with: the drawer reads
 * it to decide whether the mirror was accepted, and a failure reported as a
 * success would leave the connector writing into a collection the server never
 * agreed to store.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavConnectorRestTest {

  private static final String    USER_NAME   = "root";

  private static final String    IDENTITY_ID = "42";

  private static final String    MIRROR_HREF = "/dav/root/exo-mirror/";

  @Mock
  private CaldavConnectorService caldavConnectorService;

  @Mock
  private IdentityManager        identityManager;

  private CaldavConnectorRest    caldavConnectorRest;

  @BeforeEach
  public void setUp() {
    caldavConnectorRest = new CaldavConnectorRest(caldavConnectorService, identityManager);
    ConversationState.setCurrent(new ConversationState(new org.exoplatform.services.security.Identity(USER_NAME)));
  }

  @AfterEach
  public void tearDown() {
    ConversationState.setCurrent(null);
  }

  private void withCurrentUser() {
    Identity identity = new Identity(OrganizationIdentityProvider.NAME, USER_NAME);
    identity.setId(IDENTITY_ID);
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, USER_NAME)).thenReturn(identity);
  }

  /**
   * The href reaches the service for the user who asked, and the caller is told
   * it was stored.
   */
  @Test
  public void shouldSaveTheMirrorCalendarHref() {
    withCurrentUser();
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setMirrorCalendarHref(MIRROR_HREF);

    Response response = caldavConnectorRest.saveMirrorCalendarHref(setting);

    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    verify(caldavConnectorService).saveMirrorCalendarHref(MIRROR_HREF, Long.parseLong(IDENTITY_ID));
  }

  /**
   * A request with no body is refused before anything is looked up.
   */
  @Test
  public void shouldRefuseAMissingSetting() {
    Response response = caldavConnectorRest.saveMirrorCalendarHref(null);

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  /**
   * The service's own refusal is passed on as a bad request, carrying the
   * message code so the drawer can translate it.
   */
  @Test
  public void shouldReportARefusedHrefAsBadRequest() {
    withCurrentUser();
    doThrow(new IllegalArgumentException("caldav.mirrorCalendarHrefMandatory")).when(caldavConnectorService)
                                                                              .saveMirrorCalendarHref(anyString(), anyLong());
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setMirrorCalendarHref(" ");

    Response response = caldavConnectorRest.saveMirrorCalendarHref(setting);

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    assertEquals("caldav.mirrorCalendarHrefMandatory", response.getEntity());
  }

  /**
   * Anything else is a server error, and is not reported as success.
   */
  @Test
  public void shouldReportAnUnexpectedFailureAsServerError() {
    withCurrentUser();
    doThrow(new RuntimeException("storage is down")).when(caldavConnectorService)
                                                    .saveMirrorCalendarHref(anyString(), anyLong());
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setMirrorCalendarHref(MIRROR_HREF);

    Response response = caldavConnectorRest.saveMirrorCalendarHref(setting);

    assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
  }

  /**
   * Credentials reach the service, and a stored account answers 200.
   */
  @Test
  public void connectingHandsTheCredentialsToTheService() throws Exception {
    withCurrentUser();
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("john");
    setting.setPassword("secret");

    Response response = caldavConnectorRest.createCaldavSetting(setting);

    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    verify(caldavConnectorService).createCaldavSetting(setting, 42L);
  }

  /**
   * A body with nothing in it is refused before anything is stored.
   */
  @Test
  public void connectingWithNoSettingIsRefused() throws Exception {
    Response response = caldavConnectorRest.createCaldavSetting(null);

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    verify(caldavConnectorService, never()).createCaldavSetting(any(), anyLong());
  }

  /**
   * A refused credential is a 500 here, not a silent success.
   */
  @Test
  public void aServiceFailureWhileConnectingIsNotReportedAsSuccess() throws Exception {
    // The drawer reads this status to decide whether the account was
    // accepted. A failure told as a success leaves it believing an account is
    // connected that is not.
    withCurrentUser();
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("john");
    setting.setPassword("secret");
    doThrow(new IllegalAccessException("refused")).when(caldavConnectorService).createCaldavSetting(setting, 42L);

    Response response = caldavConnectorRest.createCaldavSetting(setting);

    assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
  }

  /**
   * Reading the account hands back what the service holds.
   */
  @Test
  public void readingTheAccountAnswersWhatTheServiceHolds() {
    withCurrentUser();
    CaldavUserSetting stored = new CaldavUserSetting();
    stored.setUsername("john");
    when(caldavConnectorService.getCaldavSetting(42L)).thenReturn(stored);

    Response response = caldavConnectorRest.getCaldavSetting();

    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    assertEquals(stored, response.getEntity());
  }

  /**
   * A failure while reading is a 500 rather than an empty account.
   */
  @Test
  public void aFailureWhileReadingIsNotAnEmptyAccount() {
    // Answering 200 with nothing would read as "no account connected", and
    // the drawer would offer to connect one that already exists.
    withCurrentUser();
    when(caldavConnectorService.getCaldavSetting(42L)).thenThrow(new IllegalStateException("boom"));

    Response response = caldavConnectorRest.getCaldavSetting();

    assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
  }

  /**
   * Disconnecting names the caller's own account and nobody else's.
   */
  @Test
  public void disconnectingNamesTheCallersOwnAccount() {
    // The request carries no way to name another user's: the identity comes
    // from the conversation state.
    withCurrentUser();

    Response response = caldavConnectorRest.deleteCaldavSetting();

    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    verify(caldavConnectorService).deleteCaldavSetting(42L);
  }

  /**
   * A failure while disconnecting is reported rather than swallowed.
   */
  @Test
  public void aFailureWhileDisconnectingIsReported() {
    withCurrentUser();
    doThrow(new IllegalStateException("boom")).when(caldavConnectorService).deleteCaldavSetting(anyLong());

    Response response = caldavConnectorRest.deleteCaldavSetting();

    assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
  }

}
