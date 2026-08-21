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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.container.ExoContainerContext;

/**
 * The mirror calendar href is the identity of the collection every pushed
 * meeting is written to, so an empty one is worth refusing before it reaches
 * storage: a setting saved blank cannot be told apart from an account that
 * never chose a mirror, and the connector would silently start writing to
 * whichever collection it found first.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavConnectorServiceImplTest {

  private static final long              USER_IDENTITY_ID = 42L;

  @Mock
  private CaldavConnectorStorage         caldavConnectorStorage;

  @InjectMocks
  private CaldavConnectorServiceImpl     caldavConnectorService;

  /**
   * An href reaches storage as it was given, for the user it was given for.
   */
  @Test
  public void shouldSaveMirrorCalendarHref() {
    caldavConnectorService.saveMirrorCalendarHref("/dav/root/mirror/", USER_IDENTITY_ID);

    verify(caldavConnectorStorage).saveMirrorCalendarHref("/dav/root/mirror/", USER_IDENTITY_ID);
  }

  /**
   * A blank href is refused with the message code the REST layer turns into a
   * 400, and nothing is written.
   */
  @Test
  public void shouldRefuseBlankMirrorCalendarHref() {
    IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                                                  () -> caldavConnectorService.saveMirrorCalendarHref("   ", USER_IDENTITY_ID));
    assertEquals("caldav.mirrorCalendarHrefMandatory", blank.getMessage());

    IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                                                    () -> caldavConnectorService.saveMirrorCalendarHref(null, USER_IDENTITY_ID));
    assertEquals("caldav.mirrorCalendarHrefMandatory", missing.getMessage());

    verifyNoInteractions(caldavConnectorStorage);
  }

  /**
   * URL resolution of a user's setting, in the documented order. The service
   * is constructed inside each test rather than injected, because the legacy
   * property is read once at construction and these tests need to control it.
   */

  private static final String LEGACY_PROPERTY = "exo.agenda.caldav.connector.url";

  /**
   * Builds the service with the legacy property holding the given value (or
   * cleared), restoring nothing — each caller sets what it needs first.
   *
   * @param legacyUrl value of the legacy property, or null to clear it
   * @return a service constructed under that property
   */
  private CaldavConnectorServiceImpl serviceWithLegacyProperty(String legacyUrl) {
    String previous = System.getProperty(LEGACY_PROPERTY);
    try {
      if (legacyUrl == null) {
        System.clearProperty(LEGACY_PROPERTY);
      } else {
        System.setProperty(LEGACY_PROPERTY, legacyUrl);
      }
      return new CaldavConnectorServiceImpl(caldavConnectorStorage);
    } finally {
      if (previous == null) {
        System.clearProperty(LEGACY_PROPERTY);
      } else {
        System.setProperty(LEGACY_PROPERTY, previous);
      }
    }
  }

  /**
   * An account referencing a declared server speaks to THAT server's URL,
   * not to the legacy property's — and the DTO carries the resolved row's
   * id, which is how the browser addresses the relay.
   */
  @Test
  public void shouldResolveTheReferencedServerUrl() {
    CaldavConnectorServiceImpl service = serviceWithLegacyProperty("https://legacy.example.org/");
    CaldavServerService registry = mock(CaldavServerService.class);
    service.setCaldavServerService(registry);
    CaldavUserSetting stored = new CaldavUserSetting();
    stored.setServerId(7L);
    when(caldavConnectorStorage.getCaldavSetting(USER_IDENTITY_ID)).thenReturn(stored);
    when(registry.resolveServer(7L)).thenReturn(new CaldavServer(7, "agenda.caldavCalendar.7", "Declared", null,
                                                                 "https://declared.example.org/cal/{username}/", true, null,
                                                                 null, null, null));

    CaldavUserSetting setting = service.getCaldavSetting(USER_IDENTITY_ID);

    assertEquals("https://declared.example.org/cal/{username}/", setting.getCaldavUrl());
    assertEquals(7L, setting.getServerId());
  }

  /**
   * A legacy account that stored no server reference still receives the id
   * of the registration it resolves through (the seed): without an id the
   * browser could not name a relay target, and the account would fall back
   * to addressing the CalDAV server directly — the very thing the relay
   * exists to end.
   */
  @Test
  public void shouldCarryTheEffectiveServerIdForLegacyAccounts() {
    CaldavConnectorServiceImpl service = serviceWithLegacyProperty(null);
    CaldavServerService registry = mock(CaldavServerService.class);
    service.setCaldavServerService(registry);
    when(caldavConnectorStorage.getCaldavSetting(USER_IDENTITY_ID)).thenReturn(new CaldavUserSetting());
    when(registry.resolveServer(null)).thenReturn(new CaldavServer(1, "agenda.caldavCalendar", "Seed", null,
                                                                   "https://seed.example.org/cal/{username}/", true, null,
                                                                   null, null, null));

    CaldavUserSetting setting = service.getCaldavSetting(USER_IDENTITY_ID);

    assertEquals("https://seed.example.org/cal/{username}/", setting.getCaldavUrl());
    assertEquals(1L, setting.getServerId());
  }

  /**
   * A registry that answers nothing (no referenced row, no seed) leaves the
   * legacy property in charge — today's behaviour, unchanged.
   */
  @Test
  public void shouldFallBackToTheLegacyPropertyUrl() {
    CaldavConnectorServiceImpl service = serviceWithLegacyProperty("https://legacy.example.org/");
    CaldavServerService registry = mock(CaldavServerService.class);
    service.setCaldavServerService(registry);
    when(caldavConnectorStorage.getCaldavSetting(USER_IDENTITY_ID)).thenReturn(new CaldavUserSetting());
    when(registry.resolveServer(null)).thenReturn(null);

    CaldavUserSetting setting = service.getCaldavSetting(USER_IDENTITY_ID);

    assertEquals("https://legacy.example.org/", setting.getCaldavUrl());
    assertNull(setting.getServerId());
  }

  /**
   * The password never leaves the platform: whatever the storage decodes,
   * the setting the REST serializes carries none — the browser's only
   * remaining signal is "credentials exist", which the username provides.
   * This is the assertion that was structurally impossible while the
   * browser built its own Basic header from this very field.
   */
  @Test
  public void shouldNeverReturnThePassword() {
    CaldavConnectorServiceImpl service = serviceWithLegacyProperty("https://legacy.example.org/");
    service.setCaldavServerService(mock(CaldavServerService.class));
    CaldavUserSetting stored = new CaldavUserSetting();
    stored.setUsername("john");
    stored.setPassword("s3cret");
    when(caldavConnectorStorage.getCaldavSetting(USER_IDENTITY_ID)).thenReturn(stored);

    CaldavUserSetting setting = service.getCaldavSetting(USER_IDENTITY_ID);

    assertEquals("john", setting.getUsername());
    assertNull(setting.getPassword());
  }

  /**
   * A complete pair of credentials is stored for the user who gave it.
   *
   * @throws Exception never, the storage is mocked
   */
  @Test
  public void shouldStoreCompleteCredentials() throws Exception {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("root");
    setting.setPassword("s3cret");

    caldavConnectorService.createCaldavSetting(setting, USER_IDENTITY_ID);

    verify(caldavConnectorStorage).createCaldavSetting(setting, USER_IDENTITY_ID);
  }

  /**
   * A connection missing its username or its password is refused before
   * anything reaches storage: half a credential stored is an account that can
   * never authenticate but looks connected to every screen that checks.
   */
  @Test
  public void shouldRefuseIncompleteCredentials() {
    CaldavUserSetting passwordless = new CaldavUserSetting();
    passwordless.setUsername("root");
    assertThrows(IllegalAccessException.class, () -> caldavConnectorService.createCaldavSetting(passwordless,
                                                                                                USER_IDENTITY_ID));

    CaldavUserSetting nameless = new CaldavUserSetting();
    nameless.setPassword("s3cret");
    assertThrows(IllegalAccessException.class, () -> caldavConnectorService.createCaldavSetting(nameless, USER_IDENTITY_ID));

    verifyNoInteractions(caldavConnectorStorage);
  }

  /**
   * Disconnecting hands the deletion to storage for the same user.
   */
  @Test
  public void shouldDeleteTheSettingOfTheUser() {
    caldavConnectorService.deleteCaldavSetting(USER_IDENTITY_ID);

    verify(caldavConnectorStorage).deleteCaldavSetting(USER_IDENTITY_ID);
  }

  /**
   * The server registry is resolved lazily through the kernel/Spring bridge
   * and then REMEMBERED: resolving it on every read would pay the container
   * lookup on every settings fetch.
   */
  @Test
  public void shouldResolveTheRegistryOnceThroughTheBridge() {
    CaldavServerService registry = mock(CaldavServerService.class);
    try (MockedStatic<ExoContainerContext> containerContext = mockStatic(ExoContainerContext.class)) {
      containerContext.when(() -> ExoContainerContext.getService(CaldavServerService.class)).thenReturn(registry);

      assertSame(registry, caldavConnectorService.getCaldavServerService());
      assertSame(registry, caldavConnectorService.getCaldavServerService());

      containerContext.verify(() -> ExoContainerContext.getService(CaldavServerService.class));
    }
  }

  /**
   * A bridge that cannot resolve the registry (Spring context not up yet, or
   * a test container without it) answers null instead of failing — and the
   * settings read then keeps the legacy property-based URL in charge, which
   * is exactly the pre-registry behaviour.
   */
  @Test
  public void shouldKeepTheLegacyUrlWhenTheBridgeCannotResolve() {
    CaldavConnectorServiceImpl service = serviceWithLegacyProperty("https://legacy.example.org/");
    when(caldavConnectorStorage.getCaldavSetting(USER_IDENTITY_ID)).thenReturn(new CaldavUserSetting());
    try (MockedStatic<ExoContainerContext> containerContext = mockStatic(ExoContainerContext.class)) {
      containerContext.when(() -> ExoContainerContext.getService(CaldavServerService.class))
                      .thenThrow(new IllegalStateException("no container"));

      assertNull(service.getCaldavServerService());

      CaldavUserSetting setting = service.getCaldavSetting(USER_IDENTITY_ID);

      assertEquals("https://legacy.example.org/", setting.getCaldavUrl());
    }
  }

  /**
   * An account referencing no server resolves through the registry with a
   * null reference — which is where the seed row answers — and takes what the
   * registry says over the property.
   */
  @Test
  public void shouldResolveTheSeedUrlForUnreferencedAccounts() {
    CaldavConnectorServiceImpl service = serviceWithLegacyProperty("https://legacy.example.org/");
    CaldavServerService registry = mock(CaldavServerService.class);
    service.setCaldavServerService(registry);
    when(caldavConnectorStorage.getCaldavSetting(USER_IDENTITY_ID)).thenReturn(new CaldavUserSetting());
    when(registry.resolveServer(null)).thenReturn(new CaldavServer(1, "agenda.caldavCalendar", "Seed", null,
                                                                   "https://seed.example.org/", true, null, null, null,
                                                                   null));

    CaldavUserSetting setting = service.getCaldavSetting(USER_IDENTITY_ID);

    assertEquals("https://seed.example.org/", setting.getCaldavUrl());
  }
}
