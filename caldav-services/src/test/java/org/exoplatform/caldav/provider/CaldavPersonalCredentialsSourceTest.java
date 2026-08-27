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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.services.connector.credentials.PersonalCredentialsProvider;
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

  @Mock
  private PersonalCredentialsProvider    personalCredentialsProvider;

  @InjectMocks
  private CaldavPersonalCredentialsSource caldavPersonalCredentialsSource;

  /**
   * Plants the provider in the field Spring would have populated. Mockito's
   * {@code @InjectMocks} used the constructor and, having succeeded, skipped
   * field injection - so the optional collaborator has to be set here.
   *
   * @param source the source to wire
   * @param provider the provider to plant, possibly null
   */
  private static void wireProvider(CaldavPersonalCredentialsSource source, PersonalCredentialsProvider provider) {
    try {
      java.lang.reflect.Field field = CaldavPersonalCredentialsSource.class.getDeclaredField("personalCredentialsProvider");
      field.setAccessible(true);
      field.set(source, provider);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The whole point of the {@code @PostConstruct}: this source pushes itself to
   * the provider, because the provider's own context is built before this one and
   * could not have collected it.
   */
  @Test
  public void testRegistersItselfWithTheProvider() {
    wireProvider(caldavPersonalCredentialsSource, personalCredentialsProvider);

    caldavPersonalCredentialsSource.register();

    verify(personalCredentialsProvider).register(caldavPersonalCredentialsSource);
  }

  /**
   * The case {@code @Autowired(required = false)} exists for: no provider bean in
   * the context - this addon's own Spring test context, or a platform without the
   * credentials module. Registering must then be a no-op, not a failure.
   */
  @Test
  public void testRegisteringWithoutAProviderIsHarmless() {
    CaldavPersonalCredentialsSource orphan = new CaldavPersonalCredentialsSource(caldavConnectorStorage, identityManager);

    assertDoesNotThrow(orphan::register);
  }

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
