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

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.services.connector.credentials.PersonalCredentialsSource;
import org.exoplatform.services.connector.credentials.RawCredentials;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Exposes the CalDAV connector's own stored personal credentials to the generic
 * {@link org.exoplatform.services.connector.credentials.PersonalCredentialsProvider}.
 * <p>
 * Goes through {@link CaldavConnectorStorage} directly, not {@link org.exoplatform.caldav.service.CaldavConnectorService}:
 * the service's own {@code getCaldavSetting(...)} deliberately blanks the password
 * before returning it (it exists only to feed the browser-facing settings REST, the
 * relay injecting the real credentials server-side) - using it here would silently
 * produce material with no secret.
 */
@Component
public class CaldavPersonalCredentialsSource implements PersonalCredentialsSource {

  public static final String     CONNECTOR_KIND = "caldav";

  private final CaldavConnectorStorage caldavConnectorStorage;

  private final IdentityManager  identityManager;

  public CaldavPersonalCredentialsSource(CaldavConnectorStorage caldavConnectorStorage, IdentityManager identityManager) {
    this.caldavConnectorStorage = caldavConnectorStorage;
    this.identityManager = identityManager;
  }

  @Override
  public String getConnectorKind() {
    return CONNECTOR_KIND;
  }

  @Override
  public RawCredentials getCredentials(String username) {
    Identity identity = identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, username);
    if (identity == null) {
      return null;
    }
    CaldavUserSetting setting = caldavConnectorStorage.getCaldavSetting(Long.parseLong(identity.getId()));
    if (StringUtils.isBlank(setting.getUsername())) {
      return null;
    }
    return new RawCredentials(setting.getUsername(), setting.getPassword());
  }

}
