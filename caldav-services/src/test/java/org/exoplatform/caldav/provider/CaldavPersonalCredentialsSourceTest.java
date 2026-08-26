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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.caldav.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.services.connector.credentials.RawCredentials;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

@ExtendWith(MockitoExtension.class)
public class CaldavPersonalCredentialsSourceTest {

  private static final String            TEST_USER = "testuser";

  @Mock
  private CaldavConnectorStorage         caldavConnectorStorage;

  @Mock
  private IdentityManager                identityManager;

  @InjectMocks
  private CaldavPersonalCredentialsSource caldavPersonalCredentialsSource;

  @Test
  public void testGetConnectorKind() {
    assertEquals("caldav", caldavPersonalCredentialsSource.getConnectorKind());
  }

  @Test
  public void testGetCredentialsWhenIdentityNotFound() {
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, TEST_USER)).thenReturn(null);

    assertNull(caldavPersonalCredentialsSource.getCredentials(TEST_USER));
  }

  @Test
  public void testGetCredentialsWhenConfigured() {
    Identity identity = new Identity(OrganizationIdentityProvider.NAME, TEST_USER);
    identity.setId("1");
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, TEST_USER)).thenReturn(identity);

    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("caldavUser");
    setting.setPassword("secret");
    when(caldavConnectorStorage.getCaldavSetting(1L)).thenReturn(setting);

    RawCredentials credentials = caldavPersonalCredentialsSource.getCredentials(TEST_USER);

    assertEquals("caldavUser", credentials.getUsername());
    assertEquals("secret", credentials.getSecret());
  }

  @Test
  public void testGetCredentialsWhenNotConfigured() {
    Identity identity = new Identity(OrganizationIdentityProvider.NAME, TEST_USER);
    identity.setId("1");
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, TEST_USER)).thenReturn(identity);
    when(caldavConnectorStorage.getCaldavSetting(1L)).thenReturn(new CaldavUserSetting());

    assertNull(caldavPersonalCredentialsSource.getCredentials(TEST_USER));
  }

}
