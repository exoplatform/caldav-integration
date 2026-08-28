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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
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
import org.mockito.MockedStatic;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.model.RemoteProvider;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.List;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.MirrorTargetKind;
import org.exoplatform.caldav.storage.CaldavServerStorage;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.RootContainer.PortalContainerInitTask;
import org.exoplatform.container.component.RequestLifeCycle;
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

  @Mock
  private FileService              fileService;

  @Mock
  private PortalContainer         portalContainer;

  /**
   * The decorator that fills in what each server has been seen doing. Stubbed
   * to hand back what it was given, so every assertion in this class goes on
   * measuring the registry rather than the enrichment, which has its own test.
   */
  @Mock
  private CaldavServerQuirkService caldavServerQuirkService;

  /**
   * The address check, REAL rather than mocked, so these tests keep measuring
   * what the registry actually refuses (EXO-89774). Its name resolution is a
   * table, not the machine's resolver: {@code dav.example.org} answers a public
   * address, everything else is unknown — which is also what makes the seeding
   * warning path exercised here reach its refusal branch without a DNS query.
   */
  @Spy
  private CaldavServerUrlValidator caldavServerUrlValidator =
                                                            new CaldavServerUrlValidator("https", "80,443", "", false,
                                                                                         CaldavServerServiceTest::resolve);

  @InjectMocks
  private CaldavServerService      caldavServerService;

  private String                   previousUrlProperty;

  private String                   previousEnabledProperty;

  /**
   * The table the address check resolves through in this class: one public
   * name, one private literal, and nothing else — which is all these tests
   * need, and is what keeps them off the network.
   *
   * @param host host of a declared URL
   * @return the addresses it points at
   * @throws UnknownHostException when the table holds no answer for the host
   */
  private static InetAddress[] resolve(String host) throws UnknownHostException {
    if ("dav.example.org".equals(host)) {
      return new InetAddress[] { InetAddress.getByAddress(host, new byte[] { (byte) 203, (byte) 0, (byte) 113, (byte) 10 }) };
    }
    if ("10.1.2.3".equals(host)) {
      return new InetAddress[] { InetAddress.getByAddress(host, new byte[] { (byte) 10, (byte) 1, (byte) 2, (byte) 3 }) };
    }
    throw new UnknownHostException(host);
  }

  /**
   * Two things one setup does, because JUnit orders sibling {@code @BeforeEach}
   * methods arbitrarily and a reader should not have to wonder whether that
   * matters here.
   *
   * <p>
   * The decorator is passed through unchanged so a test reads the registration
   * it stored rather than one an observation service rewrote; and the JVM-wide
   * legacy properties are captured so they can be restored afterwards, since
   * leaking a value into another test would fake a configured deployment.
   */
  @BeforeEach
  public void passRegistrationsThroughAndSaveLegacyProperties() {
    lenient().when(caldavServerQuirkService.decorate(any())).thenAnswer(invocation -> invocation.getArgument(0));
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
   * The address check is part of what a declaration must pass, on create and
   * on update alike (EXO-89774). What is pinned here is the WIRING — that the
   * registry refuses an address the platform must not be driven to, and stores
   * nothing when it does. Which addresses those are, and why, is
   * CaldavServerUrlValidatorTest's subject.
   */
  @Test
  public void shouldRefuseAServerAddressThePlatformMustNotConnectTo() {
    withUser(ADMIN_USER, true);

    CaldavServer internal = server(0, null, "Internal", null, "https://10.1.2.3/dav/", true);
    IllegalArgumentException created =
                                    assertThrows(IllegalArgumentException.class,
                                                 () -> caldavServerService.createServer(internal, ADMIN_USER));
    assertEquals(CaldavServerUrlValidator.PRIVATE_ADDRESS_MESSAGE, created.getMessage());

    // The update path reads the stored row to see whether the address moved, so
    // the storage IS touched now - what must not happen is the write.
    when(caldavServerStorage.getServerById(7)).thenReturn(server(7, null, "Internal", null, SERVER_URL, true));
    CaldavServer plainHttp = server(7, null, "Internal", null, "http://dav.example.org/dav/", true);
    IllegalArgumentException updated =
                                    assertThrows(IllegalArgumentException.class,
                                                 () -> caldavServerService.updateServer(plainHttp, ADMIN_USER));
    assertEquals(CaldavServerUrlValidator.SCHEME_NOT_ALLOWED_MESSAGE, updated.getMessage());

    verify(caldavServerStorage, never()).createServer(any(), anyString());
    verify(caldavServerStorage, never()).updateServer(any());
    verifyNoInteractions(agendaRemoteEventService);
  }

  /**
   * An address that has not moved is not re-judged.
   *
   * <p>
   * <b>Refusing this save would buy no safety and cost an administrator their
   * settings.</b> The row is already declared and already dialled by every
   * sweep, so blocking the edit stops not one request - it only makes a server
   * unrenameable on a deployment whose CalDAV host has always been internal,
   * which is the ordinary shape of an on-premises install. Those are precisely
   * the administrators who changed nothing.
   */
  @Test
  public void anUnchangedAddressIsNotRejudgedWhenSomethingElseIsEdited() {
    withUser(ADMIN_USER, true);
    CaldavServer stored = server(7, null, "Internal", null, "https://10.1.2.3/dav/", true);
    when(caldavServerStorage.getServerById(7)).thenReturn(stored);
    CaldavServer renamed = server(7, null, "Internal renamed", null, "https://10.1.2.3/dav/", true);
    when(caldavServerStorage.updateServer(renamed)).thenReturn(renamed);
    when(caldavServerQuirkService.decorate(renamed)).thenReturn(renamed);

    CaldavServer result = assertDoesNotThrow(() -> caldavServerService.updateServer(renamed, ADMIN_USER));

    assertEquals("Internal renamed", result.getName());
    verify(caldavServerStorage).updateServer(renamed);
  }

  /**
   * Moving the address IS judged, on a row that already existed.
   *
   * <p>
   * The exemption above is scoped to an address that did not change; it is not
   * a licence to point an existing row anywhere. A hostile address cannot be
   * introduced without editing the field, and editing the field is what is
   * checked.
   */
  @Test
  public void aChangedAddressIsJudgedEvenOnARowThatAlreadyExisted() {
    withUser(ADMIN_USER, true);
    when(caldavServerStorage.getServerById(7)).thenReturn(server(7, null, "Public", null, SERVER_URL, true));
    CaldavServer moved = server(7, null, "Public", null, "https://10.1.2.3/dav/", true);

    IllegalArgumentException thrown =
                                   assertThrows(IllegalArgumentException.class,
                                                () -> caldavServerService.updateServer(moved, ADMIN_USER));

    assertEquals(CaldavServerUrlValidator.PRIVATE_ADDRESS_MESSAGE, thrown.getMessage());
    verify(caldavServerStorage, never()).updateServer(any());
  }

  /**
   * Switching a row back ON is a declaration too: a registration stored before
   * the address check existed, or a seeded default, must not be re-activated
   * unexamined. Switching one OFF is never checked — taking a bad address out
   * of service has to stay possible whatever it holds.
   */
  @Test
  public void shouldCheckTheAddressWhenActivatingButNotWhenDeactivating() {
    withUser(ADMIN_USER, true);
    CaldavServer stored = server(7, "agenda.caldavCalendar.7", "Legacy", null, "http://localhost:8888/dav/cal/{username}/",
                                 false);
    when(caldavServerStorage.getServerById(7)).thenReturn(stored);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> caldavServerService.setServerActive(7, true, ADMIN_USER));
    assertEquals(CaldavServerUrlValidator.SCHEME_NOT_ALLOWED_MESSAGE, refusal.getMessage());
    verify(caldavServerStorage, never()).updateServer(any());
    verifyNoInteractions(agendaRemoteEventService);

    when(caldavServerStorage.updateServer(any())).thenAnswer(invocation -> invocation.getArgument(0));
    assertDoesNotThrow(() -> caldavServerService.setServerActive(7, false, ADMIN_USER));
    verify(agendaRemoteEventService).saveRemoteProvider(any());
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
   * The row resolution the relay's authorization rides: same order as the
   * URL resolution, but answering the whole registration — the relay needs
   * its id and activation, not just its URL, to decide whether a target may
   * receive the user's stored credentials.
   */
  @Test
  public void shouldResolveTheServerRowInTheSameOrder() {
    CaldavServer declared = server(7, "agenda.caldavCalendar.7", "Nextcloud", null, SERVER_URL, true);
    CaldavServer seed = server(1, "agenda.caldavCalendar", "CalDAV", null, "https://seed.example.org/", true);

    when(caldavServerStorage.getServerById(7)).thenReturn(declared);
    assertEquals(declared, caldavServerService.resolveServer(7L));

    when(caldavServerStorage.getServerById(99)).thenReturn(null);
    when(caldavServerStorage.getServerByProviderName(CaldavServerService.CALDAV_PROVIDER_NAME)).thenReturn(seed);
    assertEquals(seed, caldavServerService.resolveServer(99L));
    assertEquals(seed, caldavServerService.resolveServer(null));

    when(caldavServerStorage.getServerByProviderName(CaldavServerService.CALDAV_PROVIDER_NAME)).thenReturn(null);
    assertNull(caldavServerService.resolveServer(null));
  }

  /**
   * The listing is handed through exactly as storage produced it — inactive
   * rows included, because the admin table shows both states. A service that
   * started filtering (say, hiding inactive rows) would silently empty the
   * administration screen.
   */
  @Test
  public void shouldListServersUnfiltered() {
    List<CaldavServer> servers = List.of(server(1, "agenda.caldavCalendar", "Stalwart", null, SERVER_URL, true),
                                         server(2, "agenda.caldavCalendar.2", "Bluemind", null, SERVER_URL, false));
    when(caldavServerStorage.getServers()).thenReturn(servers);

    assertEquals(servers, caldavServerService.getServers());
  }

  /**
   * An administrator's update of an existing row is validated, stored, and
   * bridged: the agenda remote provider is upserted under the row's UNCHANGED
   * provider name, carrying the new activation — losing that upsert would
   * leave users' connector lists reading a stale switch.
   *
   * @throws Exception never, the storage is mocked
   */
  @Test
  public void shouldUpdateServerAndPropagateToAgenda() throws Exception {
    withUser(ADMIN_USER, true);
    CaldavServer server = server(7, null, "Renamed", "New description", SERVER_URL, false);
    CaldavServer updatedServer = server(7, "agenda.caldavCalendar.7", "Renamed", "New description", SERVER_URL, false);
    when(caldavServerStorage.updateServer(server)).thenReturn(updatedServer);

    CaldavServer result = caldavServerService.updateServer(server, ADMIN_USER);

    assertEquals(updatedServer, result);
    ArgumentCaptor<RemoteProvider> provider = ArgumentCaptor.forClass(RemoteProvider.class);
    verify(agendaRemoteEventService).saveRemoteProvider(provider.capture());
    assertEquals("agenda.caldavCalendar.7", provider.getValue().getName());
    assertEquals(false, provider.getValue().isEnabled());
  }

  /**
   * A user the ACL does not even know (no identity resolvable) cannot edit —
   * the null identity must short-circuit BEFORE isAdministrator, which would
   * throw on null.
   */
  @Test
  public void shouldRefuseEditToUnknownIdentity() {
    when(userAcl.getUserIdentity("ghost")).thenReturn(null);

    assertFalse(caldavServerService.canEdit("ghost"));
  }

  /**
   * References whose stored value is unreadable (a null setting value inside
   * a non-null holder) are not counted as blocking a delete: only a value
   * that actually equals the row's id blocks it. Counting unreadable values
   * would freeze deletes forever on corrupt settings.
   */
  @Test
  public void shouldNotCountUnreadableReferences() {
    Context matching = Context.USER.id("11");
    Context nullHolder = Context.USER.id("22");
    Context nullValue = Context.USER.id("33");
    when(settingService.getContextsByTypeAndScopeAndSettingName(anyString(), anyString(), anyString(), anyString(), anyInt(),
                                                                anyInt())).thenReturn(List.of(matching, nullHolder, nullValue));
    doReturn(SettingValue.create("7")).when(settingService).get(eq(matching), any(), anyString());
    doReturn(null).when(settingService).get(eq(nullHolder), any(), anyString());
    doReturn(SettingValue.create((String) null)).when(settingService).get(eq(nullValue), any(), anyString());

    assertEquals(1, caldavServerService.countServerReferences(7));
  }

  /**
   * A row that never got an image answers a null stream — the REST layer's
   * cue for 404 — without ever asking the file storage anything.
   *
   * @throws Exception never, the storage is mocked
   */
  @Test
  public void shouldAnswerNoImageStreamWhenRowHoldsNone() throws Exception {
    CaldavServer imageless = server(7, "agenda.caldavCalendar.7", "Nextcloud", null, SERVER_URL, true);
    when(caldavServerStorage.getServerById(7)).thenReturn(imageless);

    assertNull(caldavServerService.getServerImageInputStream(7));

    // a zero file id means "no image" exactly like null — the drawer sends 0
    // where JSON dropped the null
    imageless.setImageFileId(0L);

    assertNull(caldavServerService.getServerImageInputStream(7));

    verifyNoInteractions(fileService);
  }

  /**
   * A row whose stored file has vanished from the file storage (or reads back
   * empty) answers a null stream too, rather than a stream that would blow up
   * on first read.
   *
   * @throws Exception never, the storage is mocked
   */
  @Test
  public void shouldAnswerNoImageStreamWhenStoredFileVanished() throws Exception {
    CaldavServer server = server(7, "agenda.caldavCalendar.7", "Nextcloud", null, SERVER_URL, true);
    server.setImageFileId(55L);
    when(caldavServerStorage.getServerById(7)).thenReturn(server);
    when(fileService.getFile(55L)).thenReturn(null);

    assertNull(caldavServerService.getServerImageInputStream(7));

    FileItem emptyFile = org.mockito.Mockito.mock(FileItem.class);
    when(emptyFile.getAsByte()).thenReturn(null);
    when(fileService.getFile(55L)).thenReturn(emptyFile);

    assertNull(caldavServerService.getServerImageInputStream(7));
  }

  /**
   * A stored image is served back byte for byte.
   *
   * @throws Exception never, the storage is mocked
   */
  @Test
  public void shouldServeTheStoredImageBytes() throws Exception {
    CaldavServer server = server(7, "agenda.caldavCalendar.7", "Nextcloud", null, SERVER_URL, true);
    server.setImageFileId(55L);
    when(caldavServerStorage.getServerById(7)).thenReturn(server);
    byte[] bytes = new byte[] { 1, 2, 3 };
    FileItem file = org.mockito.Mockito.mock(FileItem.class);
    when(file.getAsByte()).thenReturn(bytes);
    when(fileService.getFile(55L)).thenReturn(file);

    InputStream stream = caldavServerService.getServerImageInputStream(7);

    assertArrayEquals(bytes, stream.readAllBytes());
  }

  /**
   * Asking the image of a row that does not exist is the 404, not a null that
   * the REST layer would misreport as "row exists but has no image".
   */
  @Test
  public void shouldRefuseImageOfMissingServer() {
    when(caldavServerStorage.getServerById(99)).thenReturn(null);

    assertThrows(ObjectNotFoundException.class, () -> caldavServerService.getServerImageInputStream(99));
  }

  /**
   * The bootstrap registers the seeding as a portal post-create task — the
   * same deferral agenda's provider plugin uses — and that task runs the
   * seeding inside a container request lifecycle, which it closes even when
   * the seeding succeeds trivially (a registry already filled).
   */
  @Test
  public void shouldDeferSeedingToPortalPostCreate() {
    when(caldavServerStorage.countServers()).thenReturn(1L);
    try (MockedStatic<PortalContainer> portalContainerStatic = mockStatic(PortalContainer.class);
         MockedStatic<ExoContainerContext> containerContextStatic = mockStatic(ExoContainerContext.class);
         MockedStatic<RequestLifeCycle> requestLifeCycleStatic = mockStatic(RequestLifeCycle.class)) {
      caldavServerService.start();

      ArgumentCaptor<PortalContainerInitTask> task = ArgumentCaptor.forClass(PortalContainerInitTask.class);
      portalContainerStatic.verify(() -> PortalContainer.addInitTask(any(), task.capture()));

      task.getValue().execute(null, portalContainer);

      requestLifeCycleStatic.verify(() -> RequestLifeCycle.begin(portalContainer));
      requestLifeCycleStatic.verify(RequestLifeCycle::end);
      verify(caldavServerStorage).countServers();
    }
  }

  /**
   * A seeding that blows up must NOT fail the portal boot: the post-create
   * task logs and swallows, and still closes the request lifecycle it opened.
   * Letting the exception out would take the whole platform down over two
   * default rows.
   */
  @Test
  public void shouldSurviveASeedingFailureAtBoot() {
    when(caldavServerStorage.countServers()).thenThrow(new IllegalStateException("database is down"));
    try (MockedStatic<PortalContainer> portalContainerStatic = mockStatic(PortalContainer.class);
         MockedStatic<ExoContainerContext> containerContextStatic = mockStatic(ExoContainerContext.class);
         MockedStatic<RequestLifeCycle> requestLifeCycleStatic = mockStatic(RequestLifeCycle.class)) {
      caldavServerService.start();

      ArgumentCaptor<PortalContainerInitTask> task = ArgumentCaptor.forClass(PortalContainerInitTask.class);
      portalContainerStatic.verify(() -> PortalContainer.addInitTask(any(), task.capture()));

      assertDoesNotThrow(() -> task.getValue().execute(null, portalContainer));

      requestLifeCycleStatic.verify(RequestLifeCycle::end);
    }
  }


  // ---- the stamp that makes a settings change reach the copies (EXO-89759)

  /**
   * Turning the answer links off stamps the row, because that change alters
   * what eXo renders into every copy and moves no ETag on the server: without
   * the stamp, nothing would ever look at those copies again.
   *
   * @throws Exception never, the storage is mocked
   */
  @Test
  public void shouldStampTheRowWhenASettingGoverningTheCopiesChanges() throws Exception {
    withUser(ADMIN_USER, true);
    CaldavServer stored = server(7, "agenda.caldavCalendar.7", "Bluemind", null, SERVER_URL, true);
    CaldavServer incoming = server(7, "agenda.caldavCalendar.7", "Bluemind", null, SERVER_URL, true);
    incoming.setAnswerLinksInCopy(false);
    when(caldavServerStorage.getServerById(7)).thenReturn(stored);
    when(caldavServerStorage.updateServer(any())).thenAnswer(invocation -> invocation.getArgument(0));

    CaldavServer result = caldavServerService.updateServer(incoming, ADMIN_USER);

    assertNotNull(result.getCopySettingsUpdated(), "the change has to reach the copies already written");
  }

  /**
   * Renaming does not: presentation reaches a list in the connectors screen and
   * never a calendar object, so making every mirror in the deployment fetch and
   * compare every copy it holds would be pure cost.
   *
   * @throws Exception never, the storage is mocked
   */
  @Test
  public void shouldNotStampTheRowForAChangeNoCopyCarries() throws Exception {
    withUser(ADMIN_USER, true);
    Date earlier = new Date(1_700_000_000_000L);
    CaldavServer stored = server(7, "agenda.caldavCalendar.7", "Bluemind", null, SERVER_URL, true);
    stored.setCopySettingsUpdated(earlier);
    CaldavServer incoming = server(7, "agenda.caldavCalendar.7", "Renamed", "New description", SERVER_URL, true);
    when(caldavServerStorage.getServerById(7)).thenReturn(stored);
    when(caldavServerStorage.updateServer(any())).thenAnswer(invocation -> invocation.getArgument(0));

    CaldavServer result = caldavServerService.updateServer(incoming, ADMIN_USER);

    assertEquals(earlier, result.getCopySettingsUpdated(), "and the stamp already there must survive the rename");
  }

  /**
   * A stamp in the request body is ignored. Trusted, an invented timestamp
   * would set every mirror in the deployment re-comparing its copies, and an
   * echoed stale one would stop a mirror that owes a round — from a caller who
   * needs no more rights than editing the name.
   *
   * @throws Exception never, the storage is mocked
   */
  @Test
  public void shouldIgnoreAStampSentByTheCaller() throws Exception {
    withUser(ADMIN_USER, true);
    CaldavServer stored = server(7, "agenda.caldavCalendar.7", "Bluemind", null, SERVER_URL, true);
    CaldavServer incoming = server(7, "agenda.caldavCalendar.7", "Bluemind", null, SERVER_URL, true);
    incoming.setCopySettingsUpdated(new Date());
    when(caldavServerStorage.getServerById(7)).thenReturn(stored);
    when(caldavServerStorage.updateServer(any())).thenAnswer(invocation -> invocation.getArgument(0));

    CaldavServer result = caldavServerService.updateServer(incoming, ADMIN_USER);

    assertNull(result.getCopySettingsUpdated(), "the stamp is recomputed from the stored row, never accepted");
  }

  /**
   * Flipping the activation switch carries the stamp through untouched. Left
   * null by that write, it would quietly cancel a change an administrator made
   * a minute earlier that no sweep has reached yet.
   *
   * @throws Exception never, the storage is mocked
   */
  @Test
  public void shouldCarryTheStampThroughAnActivationToggle() throws Exception {
    withUser(ADMIN_USER, true);
    Date earlier = new Date(1_700_000_000_000L);
    CaldavServer stored = server(7, "agenda.caldavCalendar.7", "Bluemind", null, SERVER_URL, true);
    stored.setCopySettingsUpdated(earlier);
    when(caldavServerStorage.getServerById(7)).thenReturn(stored);
    when(caldavServerStorage.updateServer(any())).thenAnswer(invocation -> invocation.getArgument(0));

    CaldavServer result = caldavServerService.setServerActive(7, false, ADMIN_USER);

    assertEquals(earlier, result.getCopySettingsUpdated());
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
                            null, null, null, null, MirrorTargetKind.DEDICATED_CALENDAR);
  }
}
