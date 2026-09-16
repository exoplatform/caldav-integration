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
 * "Observed", not "granted": a count is written either by the share eXo itself
 * made — recorded as the server accepts it, so it is right at once — or by
 * what another eXo user's CalDAV home listed on its own synchronisation pass,
 * which is what also catches a share made in the calendar server's own web
 * client. Two things are therefore absent: a calendar eXo did not export but
 * the user owns on the server, which neither writer can name; and a share made
 * outside eXo with somebody who is not an eXo user with a connected account,
 * or made outside eXo less than one synchronisation period ago. Those make a
 * count a floor on a calendar's exposure. It can also overstate, in one
 * direction: a sighting stands until a listing contradicts it, so a colleague
 * who has been suspended or removed, or whose home listed nothing, keeps their
 * last one. The Share drawer, which reads the server live, answers who.
 *
 * @param sharedWith agenda calendar id to the number of colleagues, carrying
 *          only the calendars with at least one — a calendar nobody is
 *          observed to see is absent rather than present with a zero
 */
public record ObservedShares(Map<Long, Long> sharedWith) {
}
