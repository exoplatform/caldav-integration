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

/**
 * The address a phone or desktop calendar app must be pointed at for the
 * connected account: the real server URL — never the relay, which exists
 * only to get this browser past CORS; a device talks to the CalDAV server
 * directly.
 *
 * The configured URL may carry a `{username}` placeholder (the Stalwart
 * seed does: `.../dav/cal/{username}/`). It is substituted here exactly the
 * way the platform's own client builds its endpoint: every occurrence
 * replaced with the account's username, percent-encoded so it stays a
 * single path segment. Beyond that the URL is only trimmed — the backend
 * uses the configured URL as-is as the DAV base and lets the server's own
 * discovery (current-user-principal / calendar-home-set) do the rest, and
 * device clients run the same discovery, so rewriting the path here could
 * only break servers the platform already speaks to.
 *
 * @param {String} serverUrl the resolved base URL of the server the account
 *          is connected to, possibly holding a `{username}` placeholder
 * @param {String} username the username of the connected CalDAV account
 * @returns {String} the URL to type into a device, or an empty string when
 *          none can be built (no URL, or a placeholder with no username to
 *          fill it — a URL still holding `{username}` helps nobody)
 */
export function deviceCaldavUrl(serverUrl, username) {
  const url = serverUrl && serverUrl.trim() || '';
  if (!url) {
    return '';
  }
  if (url.includes('{username}')) {
    const account = username && username.trim() || '';
    if (!account) {
      return '';
    }
    return url.replace(/\{username\}/g, encodeURIComponent(account));
  }
  return url;
}
