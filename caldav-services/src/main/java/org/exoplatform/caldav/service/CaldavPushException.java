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
package org.exoplatform.caldav.service;

/**
 * A push that could not be carried out, carrying the machine-readable code the
 * browser already knows how to turn into a message the user can act on.
 *
 * <p>
 * The codes are the connector's own — {@code caldav.error.credentials},
 * {@code conflict}, {@code save}, {@code noCalendar},
 * {@code calendarCreationRefused} — kept identical on purpose. The browser
 * stops building iCalendar in this PR but keeps rendering the failures, and a
 * renamed code would silently degrade every one of them into the generic
 * message.
 *
 * <p>
 * Not every code is a failure, whatever this class is called. Some describe a
 * state of the subject that no retry changes — a user who never connected an
 * account, one who has not said where their copies go — and those are recorded
 * at debug, without a trace, rather than as incidents nobody can act on.
 * {@link CaldavPushService#isKnownState} draws the line, and a code left out of
 * it is a failure: see the two sets declared under the vocabulary there, which
 * a new code has to join one of.
 */
public class CaldavPushException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The stable code the front end matches on. */
  private final String      code;

  /**
   * @param code the stable failure code
   * @param message what went wrong, for the log
   */
  public CaldavPushException(String code, String message) {
    super(message);
    this.code = code;
  }

  /**
   * @param code the stable failure code
   * @param message what went wrong, for the log
   * @param cause the underlying failure
   */
  public CaldavPushException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  /**
   * The stable code the front end matches on.
   *
   * @return the code
   */
  public String getCode() {
    return code;
  }
}
