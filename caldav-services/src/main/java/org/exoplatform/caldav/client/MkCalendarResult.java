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
 * MKCALENDAR responses have been caught telling both possible lies against
 * BlueMind: a 207 whose failing propstat means nothing was created still
 * counts as 2xx, and an empty answer proves nothing. So this result carries
 * the raw status plus every non-2xx propstat status found in the body, and
 * even {@link #provenCreated()} is only the server's claim: the one
 * statement of success a caller may trust is the collection's presence in a
 * fresh listing, which is the caller's read-back to perform — the same
 * confirm-by-listing discipline the browser connector ships today.
 *
 * @param status the HTTP status the server answered
 * @param failedPropstatStatuses every non-2xx status carried at propstat
 *          level inside the body, empty when none — a 207 with entries here
 *          is a refusal wearing a 2xx
 */
public record MkCalendarResult(int status, List<Integer> failedPropstatStatuses) {

  /**
   * Whether the server refused to create the collection outright — the
   * BlueMind behaviour (it refuses MKCALENDAR), and the state the plan maps
   * to {@code REMOTE_CREATE_REFUSED} for personal calendars.
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
   * by listing, never a fact.
   *
   * @return true when the answer was an unqualified 201
   */
  public boolean provenCreated() {
    return status == 201 && failedPropstatStatuses.isEmpty();
  }
}
