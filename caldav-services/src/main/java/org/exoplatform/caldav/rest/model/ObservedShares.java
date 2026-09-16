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
package org.exoplatform.caldav.rest.model;

import java.util.Map;

/**
 * How many colleagues the caller's own calendars have been observed to be
 * shared with (EXO-90331).
 *
 * <p>
 * "Observed", not "granted": the counts come from what other eXo users' CalDAV
 * homes listed on their own synchronisation passes, so a calendar shared only
 * with somebody who is not an eXo user with a connected account is absent, and
 * a share granted or revoked since that user's last pass is not reflected yet.
 * A count is a floor on a calendar's exposure; the Share drawer, which reads
 * the server live, answers who.
 *
 * @param sharedWith agenda calendar id to the number of colleagues, carrying
 *          only the calendars with at least one — a calendar nobody is
 *          observed to see is absent rather than present with a zero
 */
public record ObservedShares(Map<Long, Long> sharedWith) {
}
