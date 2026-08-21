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
 * What a conditional PUT answered. A 412 is a result, not an exception: under
 * {@code If-None-Match: *} it means "already exists", under {@code If-Match}
 * it means "somebody changed it first" — facts the caller's conflict rules
 * consume, not faults.
 *
 * @param status the HTTP status the server answered
 * @param etag the stored object's version when the server named one, exactly
 *          as sent; null otherwise — and a null here does NOT mean the write
 *          failed, only that confirming the stored version needs a read-back
 * @param location the entry's real URL when the server chose its own path
 *          (BlueMind's CardDAV twin does), resolved absolute; null otherwise
 */
public record PutResult(int status, String etag, String location) {

  /** The status a refused precondition answers. */
  public static final int PRECONDITION_FAILED = 412;

  /**
   * Whether the precondition was refused rather than the write performed.
   *
   * @return true on a 412
   */
  public boolean preconditionFailed() {
    return status == PRECONDITION_FAILED;
  }
}
