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
 * <h2>Where an administrator wants the meeting copies of a server to land.</h2>
 *
 * <p>
 * One decision per registration, because it is a property of the calendar
 * estate behind that server and of nothing else: a deployment can connect a
 * BlueMind whose users live in one shared main calendar and a Stalwart whose
 * users each keep a calendar per project, and want a different answer for each.
 *
 * <p>
 * The three values are not three degrees of the same thing. Two of them name a
 * destination eXo can work out on its own; the third deliberately names none,
 * and refuses to write anything until the user has said where.
 */
public enum MirrorTargetKind {

  /**
   * A calendar of eXo's own, created on the account under the derived
   * {@code exo-meetings} path. The default, and what every deployment did
   * before this setting existed.
   */
  DEDICATED_CALENDAR,

  /**
   * The calendar the account itself calls its default — the one a client
   * files an invitation into when nobody says otherwise.
   *
   * <p>
   * Discovered by asking, never by guessing: the scheduling inbox names it
   * ({@code schedule-default-calendar-URL}, RFC 6638), and the answer is only
   * believed once the collection has been seen in the account's own home
   * listing. A server that names none leaves this unresolved rather than
   * letting eXo pick a calendar for somebody.
   */
  MAIN_CALENDAR,

  /**
   * A calendar the user picks themselves, and <b>nothing is written until they
   * have</b>.
   *
   * <p>
   * The refusal is the feature. An administrator choosing this said the
   * destination is not eXo's to decide, so falling back to a calendar of eXo's
   * own making would contradict the very setting that was changed — quietly,
   * and in somebody else's calendar.
   */
  USER_CHOICE;

  /**
   * Reads a stored value into a kind, tolerating anything a database can hold.
   *
   * <p>
   * Never throws. The column is written by this code and by a Liquibase
   * default, so an unexpected value means somebody edited the row by hand or a
   * later version wrote a name this one does not know — and the safe reading of
   * both is the behaviour every deployment already had.
   *
   * @param stored the value read from the registration row, may be null or
   *          blank
   * @return the kind it names, or {@link #DEDICATED_CALENDAR} when it names
   *         none
   */
  public static MirrorTargetKind of(String stored) {
    if (stored == null || stored.isBlank()) {
      return DEDICATED_CALENDAR;
    }
    for (MirrorTargetKind kind : values()) {
      if (kind.name().equalsIgnoreCase(stored.trim())) {
        return kind;
      }
    }
    return DEDICATED_CALENDAR;
  }
}
