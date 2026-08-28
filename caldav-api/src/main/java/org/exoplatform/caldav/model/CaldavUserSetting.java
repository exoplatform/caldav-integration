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
package org.exoplatform.caldav.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CaldavUserSetting {

  private String  username;

  private String  password;

  private String  caldavUrl;

  /**
   * Href of the calendar collection, on the remote CalDAV server, that eXo
   * created (or the user designated) to receive the meetings pushed by eXo.
   * The href is the identity of that collection: it survives a rename made
   * from any CalDAV client, which a display name would not.
   */
  private String  mirrorCalendarHref;

  /**
   * Identifier of the CalDAV server registration this account is connected
   * to, when the deployment declares several servers. Null for accounts
   * connected before registrations existed: those keep resolving their URL
   * through the seed registration, then the legacy configuration property.
   */
  private Long    serverId;

  /**
   * Href of the collection this user picked themselves, on a server whose
   * registration says the destination is theirs to choose
   * ({@link MirrorTargetKind#USER_CHOICE}).
   *
   * <p>
   * <b>A different question from {@link #mirrorCalendarHref}, which is why it
   * is a different field.</b> That one records where the copies are going — eXo
   * writes it whenever it establishes a destination, however it established
   * one. This one records that a human said so. Folding the two would make a
   * href eXo left behind from an earlier setting read as a choice the user
   * never made, and copies would go on landing in the dedicated calendar an
   * administrator had just stopped asking for — the silent fallback the setting
   * exists to prevent.
   */
  private String  chosenCalendarHref;
}
