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
package org.exoplatform.caldav.client;

/**
 * What one discovery walk answers: who the authenticated user is on the
 * server, and where that user's calendars live.
 *
 * <p>
 * The two travel together because the listing that follows needs both. The
 * home is where the collections are read from; the principal is what a
 * collection's {@code DAV:owner} is compared against to tell the user's own
 * calendars from one a colleague shared with them (EXO-90235). Answering
 * them from one walk rather than two keeps the principal PROPFIND from being
 * paid twice on every pass, and keeps the two facts from coming out of two
 * different conversations with the server.
 *
 * @param principal the {@code current-user-principal} as a server-absolute
 *          raw path, or null when the implementer names none — a fake, a
 *          server spoken to by href alone; the HTTP client throws instead,
 *          because an account whose server will not say who it is has
 *          nothing to discover from
 * @param href the calendar home's server-absolute raw path, never null
 */
public record CalendarHome(String principal, String href) {
}
