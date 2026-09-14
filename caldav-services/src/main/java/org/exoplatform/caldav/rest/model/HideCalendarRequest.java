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
 * What the browser sends to hide a calendar shared with the user
 * (EXO-90239).
 *
 * <p>
 * A body rather than a path segment or a query parameter, because the
 * identifier is a collection href — slashes, and on Stalwart a
 * percent-encoded login ({@code /dav/cal/alice%40stalwart.local/default/}).
 * In a path it is rejected before any handler runs, the firewall refusing an
 * encoded slash and an encoded percent alike; in a query it is encoded twice
 * and decoded once. A JSON body carries it as the calendar list answered it,
 * which is the only form the service accepts: the id is matched against the
 * user's own current listing, never trusted further.
 *
 * @param calendarId the calendar's identity, exactly as
 *          {@code GET /caldav/rest/calendars} returned it
 */
public record HideCalendarRequest(String calendarId) {
}
