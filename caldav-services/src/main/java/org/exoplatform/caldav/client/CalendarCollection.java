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
package org.exoplatform.caldav.client;

/**
 * One calendar collection as a PROPFIND listed it: everything the sync
 * engine binds a pair on, and nothing it does not need. Hrefs are answered
 * as server-absolute raw paths — exactly the shape they travel back out in,
 * so a round trip never re-encodes them.
 *
 * @param href the collection's server-absolute raw path, trailing slash as
 *          the server sent it
 * @param displayName the collection's display name, or null — data, never
 *          identity: nothing may key on it
 * @param ctag the CalendarServer ctag, or null when the server has none
 * @param syncToken the RFC 6578 sync token, or null when unsupported
 * @param color the Apple calendar-color, or null
 * @param writable whether the current user's privilege set carries write —
 *          false as well when the server answered no privilege set at all
 */
public record CalendarCollection(String href,
                                 String displayName,
                                 String ctag,
                                 String syncToken,
                                 String color,
                                 boolean writable) {
}
