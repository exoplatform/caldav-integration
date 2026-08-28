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
package org.exoplatform.caldav.storage;

import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.utils.CaldavConnectorUtils;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;

public class CaldavConnectorStorage {

  private SettingService settingService;

  public CaldavConnectorStorage(SettingService settingService) {
    this.settingService = settingService;
  }

  /**
   * Stores the credentials of the connected CalDAV account of a user and,
   * when the account was connected through a declared server registration,
   * the identifier of that registration. A connection made outside any
   * registration (the legacy single-server deployment) removes a previously
   * stored reference rather than leaving a stale one pointing the new account
   * at the old server.
   *
   * @param caldavUserSetting setting holding the credentials and, optionally,
   *          the server registration identifier
   * @param userIdentityId technical identity identifier of the user
   */
  public void createCaldavSetting(CaldavUserSetting caldavUserSetting, long userIdentityId) {

    String encodedPassword = CaldavConnectorUtils.encode(caldavUserSetting.getPassword());

    this.settingService.set(Context.USER.id(String.valueOf(userIdentityId)),
                            CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                            CaldavConnectorUtils.CALDAV_USERNAME_KEY,
                            SettingValue.create(caldavUserSetting.getUsername()));
    this.settingService.set(Context.USER.id(String.valueOf(userIdentityId)),
                            CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                            CaldavConnectorUtils.CALDAV_PASSWORD_KEY,
                            SettingValue.create(encodedPassword));
    if (caldavUserSetting.getServerId() != null) {
      this.settingService.set(Context.USER.id(String.valueOf(userIdentityId)),
                              CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                              CaldavConnectorUtils.CALDAV_SERVER_ID_KEY,
                              SettingValue.create(String.valueOf(caldavUserSetting.getServerId())));
    } else {
      this.settingService.remove(Context.USER.id(String.valueOf(userIdentityId)),
                                 CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                 CaldavConnectorUtils.CALDAV_SERVER_ID_KEY);
    }
  }

  /**
   * Saves the href of the mirror calendar of a user, the remote collection
   * every meeting pushed by eXo is written to. The href is stored beside the
   * other settings of the CalDAV account of that user, so disconnecting the
   * account removes it with the rest.
   *
   * @param mirrorCalendarHref href of the mirror calendar collection
   * @param userIdentityId technical identity identifier of the user
   */
  public void saveMirrorCalendarHref(String mirrorCalendarHref, long userIdentityId) {
    this.settingService.set(Context.USER.id(String.valueOf(userIdentityId)),
                            CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                            CaldavConnectorUtils.CALDAV_MIRROR_CALENDAR_KEY,
                            SettingValue.create(mirrorCalendarHref));
  }

  /**
   * Records the collection this user picked for themselves, on a server whose
   * registration leaves the destination to them.
   *
   * <p>
   * Stored apart from the mirror href for the reason
   * {@code CaldavUserSetting#chosenCalendarHref} gives: one says where eXo
   * writes, the other says that a human chose it, and only the second can
   * answer "has this user chosen yet".
   *
   * <p>
   * A blank href clears the choice rather than storing an empty one, so the
   * account goes back to owing a choice instead of holding one that resolves to
   * nothing.
   *
   * @param chosenCalendarHref href of the collection the user picked, blank to
   *          clear the choice
   * @param userIdentityId technical identity identifier of the user
   */
  public void saveChosenCalendarHref(String chosenCalendarHref, long userIdentityId) {
    if (chosenCalendarHref == null || chosenCalendarHref.isBlank()) {
      this.settingService.remove(Context.USER.id(String.valueOf(userIdentityId)),
                                 CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                 CaldavConnectorUtils.CALDAV_CHOSEN_CALENDAR_KEY);
      return;
    }
    this.settingService.set(Context.USER.id(String.valueOf(userIdentityId)),
                            CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                            CaldavConnectorUtils.CALDAV_CHOSEN_CALENDAR_KEY,
                            SettingValue.create(chosenCalendarHref));
  }

  /**
   * Retrieves the CalDAV settings of a user: the credentials of the connected
   * account and, when one has been chosen, the href of the mirror calendar.
   *
   * @param userIdentityId technical identity identifier of the user
   * @return the {@link CaldavUserSetting} of that user, never null
   */
  public CaldavUserSetting getCaldavSetting(long userIdentityId) {

    SettingValue<?> username = this.settingService.get(Context.USER.id(String.valueOf(userIdentityId)),
                                                       CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                                       CaldavConnectorUtils.CALDAV_USERNAME_KEY);
    SettingValue<?> password = this.settingService.get(Context.USER.id(String.valueOf(userIdentityId)),
                                                       CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                                       CaldavConnectorUtils.CALDAV_PASSWORD_KEY);
    SettingValue<?> mirrorCalendarHref = this.settingService.get(Context.USER.id(String.valueOf(userIdentityId)),
                                                                 CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                                                 CaldavConnectorUtils.CALDAV_MIRROR_CALENDAR_KEY);
    SettingValue<?> serverId = this.settingService.get(Context.USER.id(String.valueOf(userIdentityId)),
                                                       CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                                       CaldavConnectorUtils.CALDAV_SERVER_ID_KEY);
    SettingValue<?> chosenCalendarHref = this.settingService.get(Context.USER.id(String.valueOf(userIdentityId)),
                                                                 CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                                                 CaldavConnectorUtils.CALDAV_CHOSEN_CALENDAR_KEY);


    CaldavUserSetting caldavUserSetting = new CaldavUserSetting();
    if (username != null) {
      caldavUserSetting.setUsername((String) username.getValue());
    }
    if (password != null) {
      String decodePassword = CaldavConnectorUtils.decode((String) password.getValue());
      caldavUserSetting.setPassword(decodePassword);
    }
    if (mirrorCalendarHref != null) {
      caldavUserSetting.setMirrorCalendarHref((String) mirrorCalendarHref.getValue());
    }
    if (chosenCalendarHref != null) {
      caldavUserSetting.setChosenCalendarHref((String) chosenCalendarHref.getValue());
    }
    if (serverId != null && serverId.getValue() != null) {
      try {
        caldavUserSetting.setServerId(Long.valueOf(serverId.getValue().toString()));
      } catch (NumberFormatException e) {
        // An unreadable stored reference behaves exactly like no reference:
        // the URL resolution falls back to the seed registration, then the
        // legacy configuration property.
      }
    }
    return caldavUserSetting;
  }

  /**
   * Deletes every stored CalDAV setting of a user: credentials, mirror
   * calendar href and the collection they had chosen alike, so a later
   * reconnection starts from a clean state and never silently reuses the
   * mirror — or the choice — of a previous account.
   *
   * @param userIdentityId technical identity identifier of the user
   */
  public void deleteCaldavSetting(long userIdentityId) {

    this.settingService.remove(Context.USER.id(String.valueOf(userIdentityId)),
                               CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                               CaldavConnectorUtils.CALDAV_USERNAME_KEY);
    this.settingService.remove(Context.USER.id(String.valueOf(userIdentityId)),
                               CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                               CaldavConnectorUtils.CALDAV_PASSWORD_KEY);
    this.settingService.remove(Context.USER.id(String.valueOf(userIdentityId)),
                               CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                               CaldavConnectorUtils.CALDAV_MIRROR_CALENDAR_KEY);
    this.settingService.remove(Context.USER.id(String.valueOf(userIdentityId)),
                               CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                               CaldavConnectorUtils.CALDAV_SERVER_ID_KEY);
    // The choice goes with the account it was made on. A user reconnecting to
    // another account must be asked again: the collection they picked lives on
    // the old one, and keeping the href would make a stale path read as a
    // choice already made.
    this.settingService.remove(Context.USER.id(String.valueOf(userIdentityId)),
                               CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                               CaldavConnectorUtils.CALDAV_CHOSEN_CALENDAR_KEY);
  }
}
