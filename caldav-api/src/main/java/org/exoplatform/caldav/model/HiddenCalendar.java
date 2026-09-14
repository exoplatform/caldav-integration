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
 * <p>
 * Two kinds since EXO-90239, told apart by {@code shared}. A calendar the
 * user <b>deleted</b> here while keeping it on their account comes back at
 * the next synchronisation, materialised afresh. A calendar <b>shared</b>
 * with them that they chose not to see comes back under the remote calendars
 * the moment its record is dropped, and is named with whoever shared it when
 * that can be told — the same owner the calendar list shows, resolved the
 * same way; null when nobody can be named, and always null for a deleted
 * calendar of the user's own.
 *
 * @param id the binding to lift
 * @param name what the server calls the collection today
 * @param shared true for a calendar somebody shared with the user, false
 *          for one of their own they deleted here
 * @param ownerDisplayName how to name whoever shared it, or null
 */
public record HiddenCalendar(long id, String name, boolean shared, String ownerDisplayName) {

  /**
   * A calendar of the user's own, deleted here and kept on their account —
   * the only kind there was before shares could be hidden, kept so that a
   * caller building one by hand never describes a share by accident.
   *
   * @param id the binding to lift
   * @param name what the server calls the collection today
   */
  public HiddenCalendar(long id, String name) {
    this(id, name, false, null);
  }
}
