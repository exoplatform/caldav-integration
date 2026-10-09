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
package org.exoplatform.caldav.model;

/**
 * A connection managed mode made for a user and the provider refused: the
 * designated registration it was made on, and the account the provider named for
 * them then (EXO-91017). It stays true while both are what they were: another
 * designation, or another account resolved for the user, is a new attempt.
 *
 * @param serverId the designated registration the connection was made on
 * @param account the account the provider named, empty when it named none
 */
public record CaldavManagedRefusal(long serverId, String account) {
}
