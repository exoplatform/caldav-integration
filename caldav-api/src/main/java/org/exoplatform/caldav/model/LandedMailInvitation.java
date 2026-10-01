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
 * What this add-on did with a mail invitation in the user's calendar
 * (EXO-90848).
 *
 * @param eventId the agenda event the invitation stands for
 * @param link where the event is read in the platform, built from the
 *          platform's own domain; null when removed, or when no page could be
 *          named
 * @param removed true when the event was removed — its organiser cancelled it
 *          — rather than put there
 */
public record LandedMailInvitation(long eventId, String link, boolean removed) {
}
