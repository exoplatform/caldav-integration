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
 * Whether this deployment chooses the CalDAV server on its users' behalf, and
 * which one.
 *
 * <p>
 * Two answers in one payload, and they are deliberately not the same question.
 * {@code serverId} and {@code serverName} say what the <b>instance</b> decided
 * — the administration screen renders them, and they are the same for
 * everybody. {@code managedForMe} says whether the decision applies to
 * <b>the caller</b>, which is what the browser acts on when it takes the
 * connect and disconnect affordances away.
 *
 * <p>
 * Today the two cannot disagree: managed mode is on or off for the whole
 * instance. They are separate anyway because the next step is group
 * exclusions, and when an excluded group may still connect an account of its
 * own, the per-viewer verdict is the only one a component may read. Answering
 * it here costs one service method; discovering later that every front-end
 * component read the global flag would cost a component each.
 *
 * @param serverId the registration the instance chose, null when managed mode
 *          is off
 * @param serverName that registration's display name, null when managed mode
 *          is off — the screens name the server rather than print its id
 * @param managedForMe whether the caller is governed by that choice, so their
 *          connect and disconnect affordances must not be offered
 */
public record CaldavManagedMode(Long serverId, String serverName, boolean managedForMe) {
}
