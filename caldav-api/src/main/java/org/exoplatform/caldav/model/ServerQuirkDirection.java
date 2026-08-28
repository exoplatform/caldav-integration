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
 * Which way a divergence between a copy and what eXo writes points.
 *
 * <p>
 * The direction is not decoration: it decides which of the two stored lists a
 * quirk is excused through, and it is what keeps an excusal from covering more
 * than the administrator agreed to. A property the server <b>adds</b> and a
 * property it <b>drops</b> are opposite facts about the same server, and each
 * costs something different to stop noticing.
 */
public enum ServerQuirkDirection {

  /**
   * The copy states a property eXo does not write at all — a proprietary hint
   * the server stamps on what it stores. Excused through the ignored list.
   */
  ADDED,

  /**
   * eXo writes a property the copy does not carry — the server declined to
   * store it. Excused through the dropped list.
   */
  DROPPED,

  /**
   * Both sides state the property and they disagree — the server kept the
   * property and rewrote its value. Excused through the dropped list too, but
   * only for a catalogue entry that declares this direction, because excusing
   * a rewrite gives up far more than excusing an absence: nothing about that
   * property's value is compared on that server any more.
   */
  REWRITTEN
}
