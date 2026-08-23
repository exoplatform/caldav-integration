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
 * A calendar the user hid, and the handle to show it again.
 *
 * <p>
 * Carries the binding's id rather than the collection's path, deliberately: a
 * path travelling through a browser is something a caller could change, and
 * what it would then name is another collection on the same account. An id is
 * looked up and checked against whose it is before anything happens.
 *
 * @param id the binding to lift
 * @param name what the server calls the collection today
 */
public record HiddenCalendar(long id, String name) {
}
