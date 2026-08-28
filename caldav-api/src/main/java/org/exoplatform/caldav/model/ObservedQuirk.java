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
 * One thing a server has actually been seen doing to the copies eXo pushes to
 * it, with how often, and whether an administrator has excused it.
 *
 * <p>
 * This is what the drawer offers instead of a text field of property names: the
 * administrator ticks from evidence the sweep gathered rather than from a log
 * line they had to find and a name they had to type correctly.
 *
 * <p>
 * <b>One entry per behaviour, not per property.</b> A catalogue entry can cover
 * a family — {@code X-MICROSOFT-*} and {@code X-MOZ-*} are one sentence about
 * one server habit — and a live BlueMind account produced three of those
 * markers, which rendered as three identical checkboxes each saying "seen
 * once". They are one decision, so they are one entry, and its count is the sum
 * of what each property contributed. A behaviour the catalogue does not
 * describe is its own entry per property, because there each property genuinely
 * is a separate thing the server does.
 *
 * @param quirkId identifier of the {@link ServerQuirk} that describes this
 *          behaviour, or null when nothing in the catalogue does — in which
 *          case the drawer describes it generically by its property name, so
 *          an incomplete catalogue never blocks an administrator
 * @param properties the property names the sweep saw diverge and this entry
 *          covers — several when a catalogue entry names a family, exactly one
 *          when nothing describes it
 * @param direction which way the behaviour points. The catalogue entry's own
 *          direction when one describes it, so a family whose members were seen
 *          pointing different ways still reads as the one behaviour it is; the
 *          observed direction otherwise
 * @param effect whether ticking it changes what eXo notices or what eXo writes
 *          into copies on this server — the drawer says which, because they are
 *          not the same kind of decision. {@link ServerQuirkEffect#TOLERATE}
 *          for anything the catalogue does not describe: an entry nobody has
 *          written a rule for can only ever relax a comparison
 * @param count how many times it has been seen — a rolling tally, deliberately
 *          approximate: it answers "is this what this server always does or did
 *          it happen once", and nothing finer
 * @param excused whether the server's stored lists cover it today, which is
 *          what the checkbox shows
 * @param patterns the property-name patterns ticking it writes into the
 *          server's list — the catalogue entry's own family when one describes
 *          it, the observed property alone otherwise
 */
public record ObservedQuirk(String quirkId,
                            List<String> properties,
                            ServerQuirkDirection direction,
                            ServerQuirkEffect effect,
                            long count,
                            boolean excused,
                            List<String> patterns) {

  /**
   * The property name this entry is described by when nothing in the catalogue
   * describes it — the one the drawer's generic wording renders.
   *
   * @return the first property covered, or null when there is none
   */
  public String property() {
    return properties == null || properties.isEmpty() ? null : properties.get(0);
  }
}
