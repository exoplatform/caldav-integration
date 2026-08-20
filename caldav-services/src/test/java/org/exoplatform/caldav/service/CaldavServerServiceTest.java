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
package org.exoplatform.caldav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.model.RemoteProvider;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
import java.util.List;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.storage.CaldavServerStorage;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.Identity;

/**
 * The registry of CalDAV servers is an administration surface bridged into
 * agenda by provider name: what these tests pin down is exactly the part a
 * regression would silently break — who may write, what reaches the agenda
 * remote provider on each write, when the legacy property seeds the registry,
 * and which URL a user's account ends up speaking to.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavServerServiceTest {

  private static final String      ADMIN_USER   = "root";

  private static final String      REGULAR_USER = "mary";

  private static final String      SERVER_URL   = "https://dav.example.org/cal/{username}/";

  @Mock
  private CaldavServerStorage      caldavServerStorage;

  @Mock
  private UserACL                  userAcl;

  @Mock
  private AgendaRemoteEventService agendaRemoteEventService;

  @Mock
  private SettingService           settingService;

  @InjectMocks
  private CaldavServerService      caldavServerService;

  private String                   previousUrlProperty;

  private String                   previousEnabledProperty;

  /**
   * Keeps the JVM-wide legacy properties restorable: the seeding tests set
   * them, and leaking a value into another test would fake a configured
   * deployment.
   */
  @BeforeEach
  public void saveLegacyProperties() {
    previousUrlProperty = System.getProperty(CaldavServerService.CALDAV_SERVER_URL_PROPERTY);
    previousEnabledProperty = System.getProperty(CaldavServerService.CALDAV_ENABLED_PROPERTY);
  }

  /**
   * Restores the JVM-wide legacy properties exactly as found.
   */
  @AfterEach
  public void restoreLegacyProperties() {
    restoreProperty(CaldavServerService.CALDAV_SERVER_URL_PROPERTY, previousUrlProperty);
    restoreProperty(CaldavServerService.CALDAV_ENABLED_PROPERTY, previousEnabledProperty);
  }

  /**
   * Puts one system property back to a saved value, clearing it when there
   * was none.
   *
   * @param name property to restore
   * @param value saved value, or null when the property was absent
   */
  private void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }

  /**
   * Makes the mocked ACL recognise a user as platform administrator (or not).
   *
   * @param username user to describe
   * @param administrator whether the ACL should call them administrator
   */
  private void withUser(String username, boolean administrator) {
    Identity identity = new Identity(username);
    lenient().when(userAcl.getUserIdentity(username)).thenReturn(identity);
    lenient().when(userAcl.isAdministrator(identity)).thenReturn(administrator);
  }

  /**
   * A regular user's create is refused before anything is written or bridged.
   */
  @Test
  public void shouldRefuseCreateToNonAdministrator() {
    withUser(REGULAR_USER, false);
    CaldavServer server = server(0, null, "Nextcloud", null, SERVER_URL, true);

    assertThrows(IllegalAccessException.class, () -> caldavServerService.createServer(server, REGULAR_USER));

    verifyNoInteractions(caldavServerStorage);
    verifyNoInteractions(agendaRemoteEventService);
  }

  /**
   * An anonymous caller (blank username) is refused too — deliberately unlike
   * email-connector's canEdit, which lets a blank username through.
   */
  @Test
  public void shouldRefuseCreateToAnonymous() {
    CaldavServer server = server(0, null, "Nextcloud", null, SERVER_URL, true);

    assertThrows(IllegalAccessException.class, () -> caldavServerService.createServer(server, null));

    verifyNoInteractions(caldavServerStorage);
    verifyNoInteractions(agendaRemoteEventService);
  }

  /**
   * A nameless or URL-less declaration is refused with the message code the
   * REST layer answers 400 with.
   */
  @Test
  public void shouldRefuseInvalidServer() {
    withUser(ADMIN_USER, true);

    IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                                                    () -> caldavServerService.createServer(null, ADMIN_USER));
    assertEquals("caldav.server.mandatory", missing.getMessage());

    IllegalArgumentException nameless =
                                      assertThrows(IllegalArgumentException.class,
                                                   () -> caldavServerService.createServer(server(0, null, " ", null,
                                                                                                           SERVER_URL, true),
                                                                                          ADMIN_USER));
    assertEquals("caldav.server.nameMandatory", nameless.getMessage());

    IllegalArgumentException urlless =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> caldavServerService.createServer(server(0, null, "Nextcloud",
                                                                                                          null, " ", true),
                                                                                         ADMIN_USER));
    assertEquals("caldav.server.urlMandatory", urlless.getMessage());

    verifyNoInteractions(caldavServerStorage);
    verifyNoInteractions(agendaRemoteEventService);
  }

  /**
   * An administrator's create is stored under the caldav provider prefix and
   * bridged: the agenda remote provider is upserted under the row's derived
   * name, carrying the activation, as a non-OAuth provider.
   */
  @Test
  public void shouldCreateServerAndBridgeItsProvider() throws Exception {
    withUser(ADMIN_USER, true);
    CaldavServer server = server(0, null, "Nextcloud", "Team server", SERVER_URL, true);
    CaldavServer createdServer = server(7, "agenda.caldavCalendar.7", "Nextcloud", "Team server", SERVER_URL, true);
    when(caldavServerStorage.createServer(server, CaldavServerService.CALDAV_PROVIDER_NAME)).thenReturn(createdServer);

    CaldavServer result = caldavServerService.createServer(server, ADMIN_USER);

    assertEquals(createdServer, result);
    ArgumentCaptor<RemoteProvider> provider = ArgumentCaptor.forClass(RemoteProvider.class);
    verify(agendaRemoteEventService).saveRemoteProvider(provider.capture());
    assertEquals("agenda.caldavCalendar.7", provider.getValue().getName());
    assertEquals(true, provider.getValue().isEnabled());
    assertEquals(Boolean.FALSE, provider.getValue().getOauth());
  }

  /**
   * Updating a row that does not exist is a 404, not a silent create, and
   * nothing is bridged.
   */
  @Test
  public void shouldRefuseUpdateOfMissingServer() {
    withUser(ADMIN_USER, true);
    CaldavServer server = server(99, null, "Nextcloud", null, SERVER_URL, true);
    when(caldavServerStorage.updateServer(server)).thenReturn(null);

    assertThrows(ObjectNotFoundException.class, () -> caldavServerService.updateServer(server, ADMIN_USER));

    verifyNoInteractions(agendaRemoteEventService);
  }

  /**
   * Flipping the activation switch reaches the agenda remote provider — the
   * switch users' connector lists actually read — under the row's provider
   * name and with the new state.
   */
  @Test
  public void shouldPropagateActivationToAgenda() throws Exception {
    withUser(ADMIN_USER, true);
    CaldavServer server = server(7, "agenda.caldavCalendar.7", "Nextcloud", null, SERVER_URL, true);
    when(caldavServerStorage.getServerById(7)).thenReturn(server);
    when(caldavServerStorage.updateServer(any())).thenAnswer(invocation -> invocation.getArgument(0));

    caldavServerService.setServerActive(7, false, ADMIN_USER);

    ArgumentCaptor<RemoteProvider> provider = ArgumentCaptor.forClass(RemoteProvider.class);
    verify(agendaRemoteEventService).saveRemoteProvider(provider.capture());
    assertEquals("agenda.caldavCalendar.7", provider.getValue().getName());
    assertEquals(false, provider.getValue().isEnabled());
  }

  /**
   * A row that disappears between the read and the write is reported as
   * missing, not as a crash.
   * <p>
   * Another administrator deleting the registration in between is the
   * plausible case. Storage answers that with null, and passing it on threw a
   * NullPointerException out of a method that already declares the honest
   * answer — so the caller saw a 500 where the contract promises a 404. The
   * sibling updateServer carried this guard and this one did not.
   */
  @Test
  public void shouldRefuseActivationOfVanishedServer() {
    withUser(ADMIN_USER, true);
    CaldavServer server = server(7, "agenda.caldavCalendar.7", "Nextcloud", null, SERVER_URL, true);
    when(caldavServerStorage.getServerById(7)).thenReturn(server);
    when(caldavServerStorage.updateServer(any())).thenReturn(null);

    assertThrows(ObjectNotFoundException.class, () -> caldavServerService.setServerActive(7, false, ADMIN_USER));

    verifyNoInteractions(agendaRemoteEventService);
  }

  /**
   * A regular user cannot flip the switch.
   */
  @Test
  public void shouldRefuseActivationToNonAdministrator() {
    withUser(REGULAR_USER, false);

    assertThrows(IllegalAccessException.class, () -> caldavServerService.setServerActive(7, false, REGULAR_USER));

    verifyNoInteractions(caldavServerStorage);
    verifyNoInteractions(agendaRemoteEventService);
  }

  /**
   * An empty registry is seeded with exactly the two defaults: Stalwart under
   * the FIXED legacy provider name (accounts connected before the registry
   * keep resolving through it), carrying the legacy property's URL and the
   * activation the legacy enabled property declares; and Bluemind as a
   * normally-named row whose agenda remote provider is upserted here, since
   * no kernel plugin declares it.
   */
  @Test
  public void shouldSeedEmptyRegistryWithBothDefaults() {
    System.setProperty(CaldavServerService.CALDAV_SERVER_URL_PROPERTY, SERVER_URL);
    System.setProperty(CaldavServerService.CALDAV_ENABLED_PROPERTY, "false");
    when(caldavServerStorage.countServers()).thenReturn(0L);
    CaldavServer createdBluemind = server(2, "agenda.caldavCalendar.2", "Bluemind", null,
                                          CaldavServerService.DEFAULT_BLUEMIND_URL, true);
    when(caldavServerStorage.createServer(any(), eq(CaldavServerService.CALDAV_PROVIDER_NAME))).thenReturn(createdBluemind);

    caldavServerService.seedDefaultServers();

    ArgumentCaptor<CaldavServer> stalwart = ArgumentCaptor.forClass(CaldavServer.class);
    verify(caldavServerStorage).createSeedServer(stalwart.capture(), eq(CaldavServerService.CALDAV_PROVIDER_NAME));
    assertEquals("Stalwart", stalwart.getValue().getName());
    assertEquals(SERVER_URL, stalwart.getValue().getServerUrl());
    assertEquals(false, stalwart.getValue().isActive());

    ArgumentCaptor<CaldavServer> bluemind = ArgumentCaptor.forClass(CaldavServer.class);
    verify(caldavServerStorage).createServer(bluemind.capture(), eq(CaldavServerService.CALDAV_PROVIDER_NAME));
    assertEquals("Bluemind", bluemind.getValue().getName());
    assertEquals(CaldavServerService.DEFAULT_BLUEMIND_URL, bluemind.getValue().getServerUrl());
    assertEquals(true, bluemind.getValue().isActive());

    // BOTH providers are pushed: Stalwart's explicitly (the kernel plugin
    // only creates a missing provider, it never re-enables a stored one, so
    // the seeded row's activation must land on it here), then Bluemind's.
    ArgumentCaptor<RemoteProvider> provider = ArgumentCaptor.forClass(RemoteProvider.class);
    verify(agendaRemoteEventService, times(2)).saveRemoteProvider(provider.capture());
    assertEquals(CaldavServerService.CALDAV_PROVIDER_NAME, provider.getAllValues().get(0).getName());
    assertEquals(false, provider.getAllValues().get(0).isEnabled());
    assertEquals("agenda.caldavCalendar.2", provider.getAllValues().get(1).getName());
    assertEquals(true, provider.getAllValues().get(1).isEnabled());
  }

  /**
   * A registry an administrator already wrote into is never seeded again: a
   * restart must not overwrite their edits.
   */
  @Test
  public void shouldNotSeedNonEmptyRegistry() {
    System.setProperty(CaldavServerService.CALDAV_SERVER_URL_PROPERTY, SERVER_URL);
    when(caldavServerStorage.countServers()).thenReturn(1L);

    caldavServerService.seedDefaultServers();

    verify(caldavServerStorage, never()).createSeedServer(any(), anyString());
    verify(caldavServerStorage, never()).createServer(any(), anyString());
    verifyNoInteractions(agendaRemoteEventService);
  }

  /**
   * Without the legacy property, Stalwart seeds its literal default URL —
   * the registry still gets both defaults.
   */
  @Test
  public void shouldSeedLiteralStalwartUrlWithoutLegacyProperty() {
    System.clearProperty(CaldavServerService.CALDAV_SERVER_URL_PROPERTY);
    System.clearProperty(CaldavServerService.CALDAV_ENABLED_PROPERTY);
    when(caldavServerStorage.countServers()).thenReturn(0L);
    when(caldavServerStorage.createServer(any(), anyString()))
                                                              .thenReturn(server(2, "agenda.caldavCalendar.2", "Bluemind", null,
                                                                                 CaldavServerService.DEFAULT_BLUEMIND_URL,
                                                                                 true));

    caldavServerService.seedDefaultServers();

    ArgumentCaptor<CaldavServer> stalwart = ArgumentCaptor.forClass(CaldavServer.class);
    verify(caldavServerStorage).createSeedServer(stalwart.capture(), eq(CaldavServerService.CALDAV_PROVIDER_NAME));
    assertEquals(CaldavServerService.DEFAULT_STALWART_URL, stalwart.getValue().getServerUrl());
    assertEquals(true, stalwart.getValue().isActive());
  }

  /**
   * A regular user's delete is refused before anything is read or removed.
   */
  @Test
  public void shouldRefuseDeleteToNonAdministrator() {
    withUser(REGULAR_USER, false);

    assertThrows(IllegalAccessException.class, () -> caldavServerService.deleteServer(7, REGULAR_USER));

    verifyNoInteractions(caldavServerStorage);
    verifyNoInteractions(agendaRemoteEventService);
  }

  /**
   * Deleting a row that does not exist is a 404.
   */
  @Test
  public void shouldRefuseDeleteOfMissingServer() {
    withUser(ADMIN_USER, true);
    when(caldavServerStorage.getServerById(99)).thenReturn(null);

    assertThrows(ObjectNotFoundException.class, () -> caldavServerService.deleteServer(99, ADMIN_USER));
  }

  /**
   * A server that connected accounts still reference cannot be deleted:
   * deleting under them would re-point their stored credentials at whatever
   * the resolution falls back to — a different server. The refusal carries
   * the count, and neither the row nor its provider is touched.
   */
  @Test
  public void shouldRefuseDeleteOfReferencedServer() {
    withUser(ADMIN_USER, true);
    when(caldavServerStorage.getServerById(7)).thenReturn(server(7, "agenda.caldavCalendar.7", "Nextcloud", null, SERVER_URL,
                                                                 true));
    Context first = Context.USER.id("11");
    Context second = Context.USER.id("22");
    Context other = Context.USER.id("33");
    when(settingService.getContextsByTypeAndScopeAndSettingName(anyString(), anyString(), anyString(), anyString(), anyInt(),
                                                                anyInt())).thenReturn(List.of(first, second, other));
    doReturn(SettingValue.create("7")).when(settingService).get(eq(first), any(), anyString());
    doReturn(SettingValue.create("7")).when(settingService).get(eq(second), any(), anyString());
    doReturn(SettingValue.create("8")).when(settingService).get(eq(other), any(), anyString());

    IllegalStateException refusal = assertThrows(IllegalStateException.class,
                                                 () -> caldavServerService.deleteServer(7, ADMIN_USER));

    assertEquals("caldav.server.referenced:2", refusal.getMessage());
    verify(caldavServerStorage, never()).deleteServer(anyLong());
    verifyNoInteractions(agendaRemoteEventService);
  }

  /**
   * Deleting an unreferenced server first disables its agenda remote
   * provider — agenda has no provider-delete API, and disabled is what
   * removes the connector from every user's list — then removes the row.
   */
  @Test
  public void shouldDeleteUnreferencedServerAndDisableItsProvider() throws Exception {
    withUser(ADMIN_USER, true);
    when(caldavServerStorage.getServerById(7)).thenReturn(server(7, "agenda.caldavCalendar.7", "Nextcloud", null, SERVER_URL,
                                                                 true));
    when(settingService.getContextsByTypeAndScopeAndSettingName(anyString(), anyString(), anyString(), anyString(), anyInt(),
                                                                anyInt())).thenReturn(List.of());

    caldavServerService.deleteServer(7, ADMIN_USER);

    ArgumentCaptor<RemoteProvider> provider = ArgumentCaptor.forClass(RemoteProvider.class);
    verify(agendaRemoteEventService).saveRemoteProvider(provider.capture());
    assertEquals("agenda.caldavCalendar.7", provider.getValue().getName());
    assertEquals(false, provider.getValue().isEnabled());
    verify(caldavServerStorage).deleteServer(7);
  }

  /**
   * URL resolution, in the documented order: the account's own registration
   * when it exists; the seed row when the account references none (or a row
   * that has disappeared); null when the registry answers nothing — at which
   * point the caller keeps the legacy property URL.
   */
  @Test
  public void shouldResolveServerUrlInOrder() {
    CaldavServer declared = server(7, "agenda.caldavCalendar.7", "Nextcloud", null, SERVER_URL, true);
    CaldavServer seed = server(1, "agenda.caldavCalendar", "CalDAV", null, "https://seed.example.org/", true);

    when(caldavServerStorage.getServerById(7)).thenReturn(declared);
    assertEquals(SERVER_URL, caldavServerService.resolveServerUrl(7L));

    when(caldavServerStorage.getServerById(99)).thenReturn(null);
    when(caldavServerStorage.getServerByProviderName(CaldavServerService.CALDAV_PROVIDER_NAME)).thenReturn(seed);
    assertEquals("https://seed.example.org/", caldavServerService.resolveServerUrl(99L));

    assertEquals("https://seed.example.org/", caldavServerService.resolveServerUrl(null));

    when(caldavServerStorage.getServerByProviderName(CaldavServerService.CALDAV_PROVIDER_NAME)).thenReturn(null);
    assertNull(caldavServerService.resolveServerUrl(null));
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
    return new CaldavServer(id, providerName, name, description, serverUrl, active, null, null, null, null);
  }
}
