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
package org.exoplatform.caldav.exception;

/**
 * A user managed mode governs asked to disconnect their CalDAV account, connect one with
 * typed credentials, or connect another server than the designated one.
 * <p>
 * An {@link IllegalAccessException}, as the other refusals of these calls; its own type
 * lets the REST layer answer it with <b>403</b> and its message code in the body, without
 * changing how the other refusals are answered.
 */
public class ManagedConnectionLockedException extends IllegalAccessException {

  /** The message code of the refusal, carried in the 403 body. */
  public static final String MESSAGE_CODE     = "caldav.managed.connectionLocked";

  private static final long  serialVersionUID = 1L;

  /**
   * The refusal, carrying {@link #MESSAGE_CODE}.
   */
  public ManagedConnectionLockedException() {
    super(MESSAGE_CODE);
  }
}
