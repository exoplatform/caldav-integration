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
 * One calendar object (an .ics resource) as the server answered it.
 *
 * @param href the object's server-absolute raw path
 * @param etag the version the server named, exactly as sent — quotes and
 *          all, because it only works as a precondition verbatim; null when
 *          the server sent none
 * @param calendarData the iCalendar text, or null when the answer carried
 *          only the version (an ETag listing, a sync-collection change)
 */
public record CalendarObject(String href, String etag, String calendarData) {
}
