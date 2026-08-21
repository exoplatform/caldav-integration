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
 * The server refused the credentials. Distinguished from
 * {@link CalDavException} because the caller reacts differently: a sync
 * engine must treat a credential refusal as a <b>state</b> (pause the pair,
 * tell the user to reconnect), never as a transient failure to retry —
 * hammering a corporate account with a stale password is how accounts get
 * locked.
 * <p>
 * Thrown on 401 and 407 for every verb, and also on 403 for the read verbs
 * (PROPFIND, REPORT, GET): BlueMind answers <b>403, not 401</b>, for refused
 * Basic auth on {@code /dav/} — verified live against the demo server, which
 * answers 403 with an HTML body and no {@code WWW-Authenticate} even to an
 * unauthenticated request. On the write verbs a 403 stays a plain refusal:
 * there it legitimately means "this resource may not be written" (BlueMind
 * refusing MKCALENDAR, a read-only collection), and classifying it as a
 * credential failure would pause accounts whose password is fine.
 */
public class CalDavAuthenticationException extends CalDavException {

  private static final long serialVersionUID = 5121186957076217602L;

  /**
   * A credential refusal, named by the status and the URI it came from.
   *
   * @param message what was refused, in server-and-URL terms
   */
  public CalDavAuthenticationException(String message) {
    super(message);
  }
}
