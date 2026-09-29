/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.caldav.client;

/**
 * The calendar server authenticated a session as somebody other than the
 * account eXo asked for, and nothing was sent in it. Acting on behalf of the
 * wrong person is worse than not acting, so the caller treats it as a final
 * refusal, never as something to retry.
 */
public class CalDavSubjectMismatchException extends CalDavException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message which account was expected and which was authenticated
   */
  public CalDavSubjectMismatchException(String message) {
    super(message);
  }

}
