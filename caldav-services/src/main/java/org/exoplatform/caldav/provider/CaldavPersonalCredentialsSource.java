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

import jakarta.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.services.connector.credentials.PersonalCredentialsProvider;
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
 * <p>
 * Announces itself to {@link PersonalCredentialsProvider} from its own
 * {@code @PostConstruct} rather than waiting to be collected: this WAR's Spring
 * context is built after the provider's, so a {@code List<PersonalCredentialsSource>}
 * injected over there would be resolved before this bean existed - and would stay
 * empty, silently, since a missing source produces no credentials rather than an
 * error.
 * <p>
 * Two guards, both for this addon's own Spring test contexts rather than for a
 * platform without the credentials module: that module is a {@code provided}
 * prerequisite of this WAR - {@link CaldavCredentialsResolver} requires its
 * {@code ConnectorCredentialsService} bean outright, and the client and the relay
 * require the resolver - so the WAR does not start without it, and the addon's
 * install notes must say so. {@code @ConditionalOnClass} is kept as a
 * belt-and-braces guard, evaluated from bytecode metadata so the class is never
 * loaded; {@code @Autowired(required = false)} plus the null check below cover the
 * case where the class is there but the provider bean is not, which is exactly what
 * a context built from this addon's beans alone looks like.
 */
@Component
@ConditionalOnClass(PersonalCredentialsSource.class)
public class CaldavPersonalCredentialsSource implements PersonalCredentialsSource {

  @Autowired(required = false)
  private PersonalCredentialsProvider personalCredentialsProvider;

  private final CaldavConnectorStorage caldavConnectorStorage;

  private final IdentityManager  identityManager;

  public CaldavPersonalCredentialsSource(CaldavConnectorStorage caldavConnectorStorage, IdentityManager identityManager) {
    this.caldavConnectorStorage = caldavConnectorStorage;
    this.identityManager = identityManager;
  }

  /**
   * Announces this source to the generic Personal provider, if that provider is
   * there at all.
   *
   * @see PersonalCredentialsProvider#register(PersonalCredentialsSource)
   */
  @PostConstruct
  public void register() {
    if (personalCredentialsProvider != null) {
      personalCredentialsProvider.register(this);
    }
  }

  @Override
  public String getConnectorKind() {
    return CaldavCredentialsResolver.CONNECTOR_KIND;
  }

  @Override
  public RawCredentials getCredentials(String username) {
    Identity identity = identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, username);
    if (identity == null) {
      return null;
    }
    CaldavUserSetting setting = caldavConnectorStorage.getCaldavSetting(Long.parseLong(identity.getId()));
    // Both halves or nothing, like every other "is this account connected" predicate
    // of this addon: half a credential is not a credential. The only writer stores
    // both halves and a changed codec key stops startup rather than yielding a null
    // secret, so no caller reaches the second condition today - it aligns this
    // source with its siblings against a future writer.
    if (StringUtils.isBlank(setting.getUsername()) || StringUtils.isBlank(setting.getPassword())) {
      return null;
    }
    return new RawCredentials(setting.getUsername(), setting.getPassword());
  }

}
