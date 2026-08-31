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
 * What reading the connected account over a period actually produced, failures
 * included.
 *
 * <p>
 * A bare list of events cannot carry the one thing a client most needs to
 * know. "This account holds no meeting this week" and "this account could not
 * be reached" are both an empty array, and a caller that receives only the
 * array has no way back to the distinction: it has to be told, or it will draw
 * an empty week and call it the truth.
 *
 * <p>
 * Two grains, because the failures come in two grains. {@code failed} says the
 * account itself could not be asked — the server was unreachable, or it
 * refused the stored credentials before any calendar was named — in which case
 * nothing at all was read and {@code events} is necessarily empty.
 * {@code failedCalendars} names the collections that were listed but could not
 * then be read; the account answered, the other calendars' events are in
 * {@code events}, and only the named ones are missing.
 *
 * <p>
 * Partial answers are kept deliberately. Refusing the whole request when one
 * collection of four fails would throw away three calendars' events to report
 * the fourth, which is a worse agenda than the one the failure caused.
 *
 * @param events the occurrences that were read, each tagged with its calendar
 *          and colour; empty when the account could not be asked at all
 * @param failed true when the account could not be asked, so the empty list
 *          above means "not read" rather than "nothing there"
 * @param failedCalendars hrefs of the collections that were listed but could
 *          not be read; empty when every listed calendar answered
 */
public record RemoteEventsRead(List<RemoteIcsEvent> events, boolean failed, List<String> failedCalendars) {

  /**
   * The answer of an account that has nothing to say and no failure to report
   * — most often one that is simply not connected.
   *
   * @return an empty, unfailed reading
   */
  public static RemoteEventsRead empty() {
    return new RemoteEventsRead(List.of(), false, List.of());
  }

  /**
   * The answer of an account that could not be asked at all.
   *
   * @return an empty reading that says so
   */
  public static RemoteEventsRead unreachable() {
    return new RemoteEventsRead(List.of(), true, List.of());
  }

}
