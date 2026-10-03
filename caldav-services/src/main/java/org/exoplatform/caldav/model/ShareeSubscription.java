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
 * A share as the owner's grant or revoke knows it, with everything the
 * colleague's subscription needs and the audit line names.
 *
 * @param ownerUsername the owner's login, for the audit line
 * @param shareeIdentityId the colleague's social identity
 * @param shareeUsername the colleague's eXo login, which the credentials
 *          provider maps to their account on the server
 * @param shareeUid the directory entry uid eXo recorded for the colleague
 * @param serverId the server key, zero for the legacy property
 * @param containerUid the shared calendar's BlueMind container uid
 */
public record ShareeSubscription(String ownerUsername,
                                 long shareeIdentityId,
                                 String shareeUsername,
                                 String shareeUid,
                                 long serverId,
                                 String containerUid) {
}
