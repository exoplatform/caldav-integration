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
package org.exoplatform.caldav.rest.model;

/**
 * Whether managed mode keeps the calling user on a CalDAV server, and which one: what
 * the user's own screens need to offer neither disconnecting nor another server, and
 * to connect the designated one in one click (EXO-90836). The instance's exclusions are
 * the administration screen's facts and are not carried.
 *
 * @param managed whether managed mode governs the caller
 * @param serverId the registration it keeps them on, null when it does not govern them
 * @param refused whether the connection managed mode made for them on that registration
 *          was refused because of their own account, and still would be: their screens
 *          then offer no connection and say why (EXO-91017)
 */
public record CaldavManagedForUser(boolean managed, Long serverId, boolean refused) {
}
