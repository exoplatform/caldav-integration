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
 * The one failure the CalDAV client throws for protocol and transport
 * trouble: the server unreachable, an answer outside the accepted statuses
 * of the verb, a body that is not DAV XML, or a target this client refuses
 * to address. The message never carries credentials or any request header —
 * only the method and the URI — so it is safe to log and to surface.
 */
public class CalDavException extends RuntimeException {

  private static final long serialVersionUID = 2921186957076217601L;

  /**
   * A failure explained by its message alone.
   *
   * @param message what went wrong, in server-and-URL terms
   */
  public CalDavException(String message) {
    super(message);
  }

  /**
   * A failure wrapping its transport or parsing cause.
   *
   * @param message what went wrong, in server-and-URL terms
   * @param cause the underlying failure
   */
  public CalDavException(String message, Throwable cause) {
    super(message, cause);
  }
}
