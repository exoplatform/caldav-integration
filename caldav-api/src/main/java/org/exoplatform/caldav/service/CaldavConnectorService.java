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

import org.exoplatform.caldav.model.CaldavUserSetting;

public interface CaldavConnectorService {

  /**
   * Creates a new caldav user setting
   *
   * @param caldavUserSetting {@link CaldavUserSetting} object to create
   * @param userIdentityId User identity creating the exchange user setting
   * @throws IllegalAccessException when the user is not authorized to create
   *           caldav setting
   */
  void createCaldavSetting(CaldavUserSetting caldavUserSetting, long userIdentityId) throws IllegalAccessException;

  /**
   * Retrieves caldav user setting by its technical user identity identifier.
   *
   * @param userIdentityId User identity getting the caldav user setting
   * @return A {@link CaldavUserSetting} object
   */
  CaldavUserSetting getCaldavSetting(long userIdentityId);

  /**
   * Deletes an caldav user setting
   *
   * @param userIdentityId User identity deleting his caldav user setting
   */
  void deleteCaldavSetting(long userIdentityId);

  /**
   * Disconnects a user's CalDAV account, and tidies what eXo built from it.
   *
   * <p>
   * Nothing on the server is touched. In eXo, a calendar eXo materialised from
   * a collection of that account goes with the account — everything in it
   * lives there and nothing in it was created here — while a calendar the user
   * made in eXo and eXo pushed out stays, since the account was its
   * destination and never its source.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login, which agenda's ACL needs to remove a
   *          calendar on their behalf; without it only the settings are
   *          removed
   */
  void deleteCaldavSetting(long userIdentityId, String username);

  /**
   * Saves the href of the mirror calendar of a user: the collection, on the
   * connected CalDAV server, that receives the meetings pushed by eXo. The
   * href — never the display name — identifies the collection, so renaming it
   * from any CalDAV client does not orphan it.
   *
   * @param mirrorCalendarHref href of the mirror calendar collection
   * @param userIdentityId User identity saving his mirror calendar
   * @throws IllegalArgumentException when the href is blank
   */
  void saveMirrorCalendarHref(String mirrorCalendarHref, long userIdentityId);
}
