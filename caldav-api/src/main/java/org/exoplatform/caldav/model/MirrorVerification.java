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
package org.exoplatform.caldav.model;

/**
 * What one pass over the copies eXo pushed found, and what it did about it.
 *
 * @param checked how many copies were compared against the server
 * @param missing how many the server no longer holds
 * @param altered how many the server holds in a form eXo did not write
 * @param repaired how many were written again
 * @param abandoned how many were left alone because repairing them has stopped
 *          working — the pass gives up rather than fighting the same object
 *          for ever
 */
public record MirrorVerification(int checked, int missing, int altered, int repaired, int abandoned) {

  /**
   * @return a pass that found nothing to do, or could not run
   */
  public static MirrorVerification nothing() {
    return new MirrorVerification(0, 0, 0, 0, 0);
  }

  /**
   * @return whether anything was written back this pass
   */
  public boolean changedAnything() {
    return repaired > 0;
  }
}
