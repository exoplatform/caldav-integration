/*
 * Copyright (C) 2023 eXo Platform SAS.
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
package org.exoplatform.caldav.utils;

import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.web.security.codec.CodecInitializer;
import org.exoplatform.web.security.security.TokenServiceInitializationException;

public class CaldavConnectorUtils {
  public static final Scope  CALDAV_CONNECTOR_SETTING_SCOPE = Scope.APPLICATION.id("CaldavAgendaConnector");

  public static final String CALDAV_USERNAME_KEY            = "CaldavUsername";

  public static final String CALDAV_PASSWORD_KEY            = "CaldavPassword";

  public static final String CALDAV_MIRROR_CALENDAR_KEY     = "CaldavMirrorCalendarHref";

  public static final String CALDAV_SERVER_ID_KEY           = "CaldavServerId";

  /**
   * The collection a user picked for themselves, on a server whose
   * registration leaves the destination to them. Its own key rather than a
   * second meaning on {@link #CALDAV_MIRROR_CALENDAR_KEY}: that one records
   * where eXo is writing, this one records that a human said where.
   */
  public static final String CALDAV_CHOSEN_CALENDAR_KEY     = "CaldavChosenCalendarHref";

  public static final String CALDAV_SERVER_URL_INIT_PARAM   = "exo.agenda.caldav.connector.url";

  private static final Log   LOG                            = ExoLogger.getLogger(CaldavConnectorUtils.class);

  public CaldavConnectorUtils() {
  }

  public static final String getCurrentUser() {
    return ConversationState.getCurrent().getIdentity().getUserId();
  }

  public static final long getCurrentUserIdentityId(IdentityManager identityManager) {
    String currentUser = getCurrentUser();
    Identity identity = identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, currentUser);
    return identity == null ? 0 : Long.parseLong(identity.getId());
  }

  public static String encode(String password) {
    try {
      CodecInitializer codecInitializer = CommonsUtils.getService(CodecInitializer.class);
      return codecInitializer.getCodec().encode(password);
    } catch (TokenServiceInitializationException e) {
      LOG.warn("Error when encoding password", e);
      return null;
    }
  }

  public static String decode(String password) {
    try {
      CodecInitializer codecInitializer = CommonsUtils.getService(CodecInitializer.class);
      return codecInitializer.getCodec().decode(password);
    } catch (TokenServiceInitializationException e) {
      LOG.warn("Error when decoding password", e);
      return null;
    }
  }
}
