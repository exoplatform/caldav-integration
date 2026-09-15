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
 * What the browser sends to share a calendar with a colleague (EXO-90253):
 * the colleague's eXo login and nothing else.
 *
 * <p>
 * Never a principal, never an href: the server-side name of the colleague is
 * the principal eXo recorded for them, and the collection is the one eXo
 * created for the calendar.
 *
 * @param username the colleague's eXo login
 */
public record ShareCalendarRequest(String username) {
}
