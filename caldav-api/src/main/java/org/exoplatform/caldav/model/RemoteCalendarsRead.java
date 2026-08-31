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

import java.util.List;

/**
 * What listing the connected account's calendars actually produced, failures
 * included.
 *
 * <p>
 * The same distinction {@link RemoteEventsRead} carries for events, at the one
 * grain a listing has: either the account was asked and answered — with as
 * many calendars as it holds, possibly none — or it could not be asked, and
 * the empty list below says nothing about what the account holds.
 *
 * @param calendars the account's calendars, each with a usable colour; empty
 *          when the account could not be asked at all
 * @param failed true when the account could not be listed, so the empty list
 *          above means "not read" rather than "no calendars"
 */
public record RemoteCalendarsRead(List<RemoteCalendar> calendars, boolean failed) {

  /**
   * The answer of an account that has no calendar to offer and no failure to
   * report — most often one that is simply not connected.
   *
   * @return an empty, unfailed listing
   */
  public static RemoteCalendarsRead empty() {
    return new RemoteCalendarsRead(List.of(), false);
  }

}
