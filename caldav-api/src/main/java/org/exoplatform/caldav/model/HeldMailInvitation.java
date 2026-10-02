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

import org.exoplatform.agenda.constant.EventAttendeeResponse;

/**
 * The copy of a mail invitation the user's CalDAV-bound calendar holds, as the
 * mail reader asks about it when the invitation is opened (EXO-90873).
 *
 * @param eventId the agenda event the copy stands for
 * @param link where the event is read in the platform, built from the
 *          platform's domain and the event's id
 * @param response the user's answer on the copy, read from their attendee
 *          line; null when no line names them, or its PARTSTAT is one agenda
 *          has no word for
 * @param sequence the SEQUENCE of the copy's master, 0 when absent
 */
public record HeldMailInvitation(long eventId, String link, EventAttendeeResponse response, int sequence) {
}
