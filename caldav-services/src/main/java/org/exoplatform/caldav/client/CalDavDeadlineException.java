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
 * The time a sequence of calls was given ran out ({@link CalDavDeadline}): a
 * request was not sent because nothing was left of it, or was cut when it ran
 * out. A property of the caller's budget, not of the server — the server may
 * well be there, only slower than the caller could wait.
 */
public class CalDavDeadlineException extends CalDavException {

  private static final long serialVersionUID = -3015290845121807794L;

  /**
   * @param message what was cut, in method-and-URL terms
   */
  public CalDavDeadlineException(String message) {
    super(message);
  }

  /**
   * @param message what was cut, in method-and-URL terms
   * @param cause the transport's own failure when the request was cut
   */
  public CalDavDeadlineException(String message, Throwable cause) {
    super(message, cause);
  }
}
