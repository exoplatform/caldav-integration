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
 * Why a pair is {@link CalendarSyncStatus#PAUSED}. A pause is one state with
 * three causes, and one reader tells them apart: the subscription drain, which
 * logs in as the colleague, waits on a pause their credentials caused and goes
 * on through one they did not (EXO-90277). A pair paused before the reason was
 * recorded carries none.
 */
public enum CalendarSyncPauseReason {
  /**
   * The server refused the account's stored credentials. A login as this user
   * would be refused again, and counted against a server that may lock the
   * account.
   */
  CREDENTIALS,
  /**
   * The collection's imports failed too many times in a row. The account's
   * login is not in question.
   */
  FAILING_IMPORTS,
  /**
   * The user disconnected the account; the binding is kept for a reconnection
   * of the same account.
   */
  DISCONNECT
}
