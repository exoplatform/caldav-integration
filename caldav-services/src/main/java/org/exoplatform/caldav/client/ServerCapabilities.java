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
 * Which incremental-sync tier a collection's server can serve, read from one
 * Depth:0 PROPFIND ({@code supported-report-set}, ctag, sync token) plus the
 * {@code DAV} response header. Deliberately stateless and uncached here: the
 * probe is bound-time information whose caching (in the pair's sync state)
 * belongs to the caller, not to this client.
 *
 * @param syncCollection whether the server advertises the RFC 6578
 *          sync-collection REPORT and answered a sync token to start from
 * @param calendarMultiget whether the calendar-multiget REPORT is advertised
 * @param calendarQuery whether the calendar-query REPORT is advertised
 * @param ctag whether the collection carries a CalendarServer ctag
 * @param davHeader the raw {@code DAV} response header, or null — kept for
 *          diagnostics, never parsed into decisions here
 */
public record ServerCapabilities(boolean syncCollection,
                                 boolean calendarMultiget,
                                 boolean calendarQuery,
                                 boolean ctag,
                                 String davHeader) {

  /** The incremental strategies of the plan, cheapest first. */
  public enum SyncTier {
    /** RFC 6578 sync-collection with a stored token. */
    SYNC_COLLECTION,
    /** ctag short-circuit, then Depth:1 ETag diff. */
    CTAG_ETAG,
    /** No ctag: every run pays the Depth:1 ETag listing. */
    ETAG_LISTING
  }

  /**
   * The cheapest tier this server can serve. The windowed calendar-query
   * floor below {@link SyncTier#ETAG_LISTING} is not probed: it is the
   * engine's runtime fallback for a listing that turns out to carry no
   * usable ETags, which no capability answer predicts.
   *
   * @return the tier to start each run at
   */
  public SyncTier tier() {
    if (syncCollection) {
      return SyncTier.SYNC_COLLECTION;
    }
    return ctag ? SyncTier.CTAG_ETAG : SyncTier.ETAG_LISTING;
  }
}
