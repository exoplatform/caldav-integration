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

import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.model.CalendarSync;

/**
 * A calendar of the caller's being shared, resolved and checked by the host:
 * the caller owns it in eXo, their account is connected, and it is bound to a
 * collection eXo created or to an imported one. The collection was resolved
 * from the pair, never named by the request.
 *
 * @param userIdentityId the owner, who is the caller
 * @param username the owner's login, for the audit lines
 * @param calendarId the agenda calendar
 * @param serverId the server registration
 * @param href the collection the calendar is bound to
 * @param endpoint the owner's endpoint on that server
 * @param pair the pair binding the calendar to the collection
 * @param imported true for an imported collection, whose ownership on the
 *          server must be confirmed before its access list is read or
 *          anything is changed
 */
public record SharedCalendar(long userIdentityId,
                             String username,
                             long calendarId,
                             long serverId,
                             String href,
                             CalDavEndpoint endpoint,
                             CalendarSync pair,
                             boolean imported) {
}
