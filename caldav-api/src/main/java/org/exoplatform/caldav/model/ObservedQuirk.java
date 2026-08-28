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
 * @param quirkId identifier of the {@link ServerQuirk} that describes this
 *          behaviour, or null when nothing in the catalogue does — in which
 *          case the drawer describes it generically by its property name, so
 *          an incomplete catalogue never blocks an administrator
 * @param property the property name the sweep saw diverge
 * @param direction which way the divergence pointed
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
                            String property,
                            ServerQuirkDirection direction,
                            long count,
                            boolean excused,
                            List<String> patterns) {
}
