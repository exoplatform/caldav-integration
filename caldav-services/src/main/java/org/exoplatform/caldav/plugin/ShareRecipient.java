/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
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
package org.exoplatform.caldav.plugin;

/**
 * A colleague checked by the host for sharing: an active eXo user, not the
 * caller, connected to the same server registration under a recorded
 * principal that is not the caller's.
 *
 * @param identityId the colleague's social identity
 * @param username the colleague's login
 * @param principal the canonical principal recorded for them on the server
 */
public record ShareRecipient(long identityId, String username, String principal) {
}
