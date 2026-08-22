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
 * What one calendar's synchronisation is doing, in the terms a user can act
 * on.
 *
 * <p>
 * The engine knows this per binding and has always kept it in the database and
 * the logs. A user seeing their agenda has no way to tell a calendar that is
 * synchronising from one whose server refused it — both simply sit there — so
 * the states that call for an action are surfaced and the rest is not.
 *
 * @param id the binding, which is what an action refers to
 * @param calendarId the eXo calendar this is about, so the calendar list can
 *          mark the row itself rather than leaving the notice on a settings
 *          page nobody in trouble thinks to open. Zero when there is no eXo
 *          calendar — a collection the server refused to create has none
 * @param name what the calendar is called, read from the server so a rename
 *          in the user's own client shows here
 * @param status the engine's own state for this binding
 * @param lastSyncEnd when it last finished, in epoch milliseconds, or null
 *          when it never has
 */
public record CalendarSyncState(long id, long calendarId, String name, CalendarSyncStatus status, Long lastSyncEnd) {

  /**
   * Whether this state is one the user should be told about.
   *
   * <p>
   * A calendar that is synchronising is not news, and a tombstone has its own
   * row — the hidden-calendars one, which offers to bring it back rather than
   * to worry about it. What is left is the three states where something the
   * user might do would change the outcome.
   *
   * @return true when this belongs on screen
   */
  public boolean worthTelling() {
    return status == CalendarSyncStatus.REMOTE_CREATE_REFUSED
        || status == CalendarSyncStatus.PAUSED
        || status == CalendarSyncStatus.EXO_ORPHANED
        || status == CalendarSyncStatus.REMOTE_GONE;
  }
}
