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

import java.util.List;

/**
 * What an MKCALENDAR answered — deliberately never a boolean "created".
 * MKCALENDAR responses have been caught lying against BlueMind in every
 * shape a 2xx can take: a 207 whose failing propstat means nothing was
 * created still counts as 2xx, an empty answer proves nothing, and —
 * proven live 2026-08-20 — a plain 201 arrives even when the server
 * created NOTHING, because a request without
 * {@code supported-calendar-component-set} makes BlueMind's kind
 * derivation fail internally, and the failure is swallowed while 201 goes
 * out anyway. So this result carries the raw status plus every non-2xx
 * propstat status found in the body, and even {@link #provenCreated()} is
 * only the server's claim: the one statement of success a caller may trust
 * is the collection's presence in a fresh listing, which is the caller's
 * read-back to perform — the same confirm-by-listing discipline the
 * browser connector ships today, and a rule rather than a workaround for
 * one bug: a server that swallows one internal failure behind a 2xx can do
 * it again elsewhere.
 *
 * @param status the HTTP status the server answered
 * @param failedPropstatStatuses every non-2xx status carried at propstat
 *          level inside the body, empty when none — a 207 with entries here
 *          is a refusal wearing a 2xx
 */
public record MkCalendarResult(int status, List<Integer> failedPropstatStatuses) {

  /**
   * Whether the server refused to create the collection outright — the
   * state the plan maps to {@code REMOTE_CREATE_REFUSED} for personal
   * calendars. NOT the BlueMind behaviour, despite what three rounds of
   * debugging concluded: BlueMind never refuses an MKCALENDAR aimed under
   * the calendar home — it answers 201 even when it creates nothing (a
   * request without {@code supported-calendar-component-set}, see the class
   * javadoc), so on that server this never fires and only the
   * confirm-by-listing read-back tells creation from silence.
   *
   * @return true on 403, 405 and 501
   */
  public boolean refused() {
    return status == 403 || status == 405 || status == 501;
  }

  /**
   * Whether the server plainly claimed a creation: a 201 with no failing
   * propstat. A 207 never qualifies — MKCALENDAR is atomic (RFC 4791
   * section 5.3.1), so a 207 reports at least one rejected property and a
   * collection that was NOT created. Even a true here is a claim to confirm
   * by listing, never a fact — BlueMind has answered exactly this
   * unqualified 201 over a creation that never happened (class javadoc).
   *
   * @return true when the answer was an unqualified 201
   */
  public boolean provenCreated() {
    return status == 201 && failedPropstatStatuses.isEmpty();
  }
}
