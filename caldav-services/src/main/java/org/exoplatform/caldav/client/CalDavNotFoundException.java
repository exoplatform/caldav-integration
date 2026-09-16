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
 * The server says the thing a call addressed does not exist.
 *
 * <p>
 * Distinguished from its parent {@link CalDavException} because <b>the caller
 * reacts differently</b>: a plain failure is worth another attempt when the
 * server has a bad afternoon, while an object the server does not hold will
 * not appear by being asked for again. A subscription change owed to a
 * container that is gone is given up on at once rather than argued for until
 * the bound (EXO-90277).
 *
 * <p>
 * On BlueMind's REST API it stands for both spellings of the same answer: a
 * 404, and a 500 whose fault body carries {@code errorCode: NOT_FOUND} —
 * {@code ResponseBuilder.replyServerFault} answers 500 for every code but
 * {@code PERMISSION_DENIED}, and a live capture is what settles which one a
 * deployment sends.
 */
public class CalDavNotFoundException extends CalDavException {

  private static final long serialVersionUID = -5836611829021466175L;

  /**
   * An absent object explained by its message alone — the status naming the
   * method and URI it answered.
   *
   * @param message what was not found, in server-and-URL terms
   */
  public CalDavNotFoundException(String message) {
    super(message);
  }
}
