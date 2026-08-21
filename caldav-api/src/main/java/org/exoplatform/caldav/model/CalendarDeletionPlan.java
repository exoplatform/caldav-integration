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
 * What deleting an eXo calendar would also do on the remote side, so the page
 * can say it before the user confirms.
 *
 * @param claimed whether a CalDAV binding exists for this calendar at all
 * @param propagates whether confirming also deletes the remote collection —
 *          true only for a collection eXo created. A calendar the user made in
 *          their own client is never removed by eXo, and a dialog saying
 *          otherwise would promise to destroy something we will not touch.
 * @param server the server holding it, named in the warning so the user knows
 *          which account is affected; null when nothing is claimed
 */
public record CalendarDeletionPlan(boolean claimed, boolean propagates, String server) {
}
