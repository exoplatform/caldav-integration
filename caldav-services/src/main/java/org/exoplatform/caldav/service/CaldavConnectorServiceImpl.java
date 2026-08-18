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
package org.exoplatform.caldav.service;

import org.apache.commons.lang3.StringUtils;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

public class CaldavConnectorServiceImpl implements CaldavConnectorService {
  private CaldavConnectorStorage caldavConnectorStorage;

  private static final Log       LOG = ExoLogger.getLogger(CaldavConnectorServiceImpl.class);

  private String                 caldavUrl;

  public CaldavConnectorServiceImpl(CaldavConnectorStorage caldavConnectorStorage) {
    String caldavUrl = System.getProperty("exo.agenda.caldav.connector.url");
    this.caldavConnectorStorage = caldavConnectorStorage;
    this.caldavUrl = caldavUrl;
  }

  @Override
  public void createCaldavSetting(CaldavUserSetting caldavUserSetting, long userIdentityId) throws IllegalAccessException {
    if (StringUtils.isNotBlank(caldavUserSetting.getPassword()) && StringUtils.isNotBlank(caldavUserSetting.getUsername())) {
      caldavConnectorStorage.createCaldavSetting(caldavUserSetting, userIdentityId);
    } else {
      throw new IllegalAccessException("username or password not be null");
    }
  }

  @Override
  public CaldavUserSetting getCaldavSetting(long userIdentityId) {
    CaldavUserSetting caldavUserSetting = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    caldavUserSetting.setCaldavUrl(this.caldavUrl);
    return caldavUserSetting;
  }

  @Override
  public void deleteCaldavSetting(long userIdentityId) {
    caldavConnectorStorage.deleteCaldavSetting(userIdentityId);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void saveMirrorCalendarHref(String mirrorCalendarHref, long userIdentityId) {
    if (StringUtils.isBlank(mirrorCalendarHref)) {
      throw new IllegalArgumentException("caldav.mirrorCalendarHrefMandatory");
    }
    caldavConnectorStorage.saveMirrorCalendarHref(mirrorCalendarHref, userIdentityId);
  }
}
