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
package org.exoplatform.caldav.ics;

/**
 * A calendar object that cannot be read as iCalendar.
 *
 * <p>
 * Its own type rather than a generic runtime exception: the caller has a real
 * decision to make when a server hands back something unreadable — refuse the
 * write and keep the object intact, rather than overwrite a document we failed
 * to understand.
 */
public class IcsParseException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message what could not be read
   * @param cause the underlying parse failure
   */
  public IcsParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
