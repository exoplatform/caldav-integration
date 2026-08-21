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
 * What an RFC 6578 sync-collection REPORT answered: the hrefs that changed,
 * the hrefs that are gone, and the token to store for the next run. Token
 * invalidation is a result, not an exception, because the plan's tiering
 * treats it as a routine downgrade: the run falls through to the ctag/ETag
 * tier and comes back with the fresh token this result carries next time.
 *
 * @param tokenValid false when the server rejected the presented token (a
 *          507, or a 403 carrying the {@code valid-sync-token} precondition)
 *          — everything else in the result is then empty and the caller must
 *          run a full listing instead
 * @param syncToken the new token to store, null when the token was rejected
 * @param changed the objects the server reported changed or new since the
 *          token, versions included, calendar data never (fetching is the
 *          multiget's job)
 * @param deleted the server-absolute raw paths reported gone — the responses
 *          the REPORT answers with a direct 404 status
 */
public record SyncCollectionResult(boolean tokenValid,
                                   String syncToken,
                                   List<CalendarObject> changed,
                                   List<String> deleted) {

  /**
   * The one invalid-token result: the caller falls back to a listing.
   *
   * @return a result whose token is rejected and whose lists are empty
   */
  public static SyncCollectionResult invalidToken() {
    return new SyncCollectionResult(false, null, List.of(), List.of());
  }
}
