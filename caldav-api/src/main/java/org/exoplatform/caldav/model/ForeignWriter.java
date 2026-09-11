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
 * Another eXo deployment seen writing meeting copies into an account on this
 * server (EXO-89824).
 *
 * <p>
 * <b>Why this is worth a line in the drawer.</b> Each mirror assumes it is the
 * only writer of the copies in its collection: it lists them, compares each
 * against what eXo would write, and repairs what diverges. A second deployment
 * writing into the same CalDAV account breaks that premise, and the condition
 * is invisible from inside either one — a foreign copy has no row in this
 * database, so no query can see it, and on every screen it looks like an
 * ordinary event. The inbound pass is the only place it shows, and until this
 * record existed it showed only as a line in {@code platform.log}.
 *
 * <p>
 * <b>Evidence, not configuration.</b> Nothing is imported, removed or repaired
 * differently on the strength of an entry here, and there is no checkbox beside
 * it: what to do with a foreign copy is a product decision nobody has taken,
 * and the wrong one destroys real calendar entries. The resolution is an
 * environment one — one of the two deployments moves to a different account —
 * which is why the drawer states it in words rather than offering a control.
 *
 * @param authority how the other deployment names itself in the copies it
 *          writes: the host and port of its configured domain, lower-cased,
 *          read off the event link its copies carry. Two deployments
 *          configured with the same address are indistinguishable, and none is
 *          reported as foreign then
 * @param lastSeen when a copy of that deployment's was last read here, as
 *          milliseconds. Day-grained: the value answers "is this still
 *          happening", and an entry nothing has seen for a month is dropped, so
 *          the section empties on its own once the foreign copies are gone
 */
public record ForeignWriter(String authority, long lastSeen) {
}
