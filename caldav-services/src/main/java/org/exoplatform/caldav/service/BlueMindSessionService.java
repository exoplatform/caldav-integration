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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.caldav.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.bluemind.BlueMindRestSession;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Drops the BlueMind REST session an account holds when eXo itself changes
 * what that account is (EXO-90397).
 *
 * <p>
 * <b>Why a service of its own.</b> The sessions are kept by
 * {@code BlueMindSessionCache} under the eXo <i>login</i> the credentials
 * were produced for, because that is what a credentials provider resolves its
 * target account from. The events that must drop one — a connection, a
 * reconnection with new credentials, a disconnection — are raised by
 * {@code CaldavConnectorServiceImpl}, which knows the user by their social
 * <i>identity</i> and reaches its collaborators through the Kernel/Spring
 * bridge. That bridge exports {@code @Service} beans and nothing else
 * ({@code KernelContainerLifecyclePlugin}), so this class is one, as
 * {@link CaldavServerOwnerService} and {@link CaldavConnectionIdentityService}
 * are for the very same reason; and the identity-to-login translation lives
 * here, in one place, rather than being repeated at each call site.
 *
 * <p>
 * <b>The same events EXO-90243 already hooks.</b> Nothing new is raised: the
 * two places that forget an account's recorded server identity are the two
 * places that forget its session.
 */
@Service
public class BlueMindSessionService {

  private static final Log          LOG = ExoLogger.getLogger(BlueMindSessionService.class);

  @Autowired
  private BlueMindRestSession       blueMindRestSession;

  @Autowired
  private IdentityManager           identityManager;

  /**
   * Drops and closes the session kept for one account.
   *
   * @param userIdentityId the social identity of the connected user
   * @param serverId the declared server the account was on; null for the
   *          legacy deployment property
   */
  public void forget(long userIdentityId, Long serverId) {
    String login = loginOf(userIdentityId);
    if (StringUtils.isBlank(login)) {
      // Nothing can be keyed without a login; the entry expires on its own.
      LOG.debug("No login could be resolved for identity {}; its BlueMind session is left to expire", userIdentityId);
      return;
    }
    blueMindRestSession.forget(serverId == null ? 0L : serverId, login);
  }

  /**
   * Drops every kept session, because the declared servers changed under
   * them — a registration edited or deleted. The dropped sessions expire on
   * BlueMind's own clock; what matters is that none of them is sent again
   * under a registration that no longer says what it said.
   */
  public void forgetAll() {
    blueMindRestSession.forgetAll();
  }

  /**
   * The eXo login of a social identity.
   *
   * @param userIdentityId the social identity
   * @return the login, or null when the registry does not know them
   */
  private String loginOf(long userIdentityId) {
    Identity identity = identityManager.getIdentity(String.valueOf(userIdentityId));
    return identity == null ? null : StringUtils.trimToNull(identity.getRemoteId());
  }
}
