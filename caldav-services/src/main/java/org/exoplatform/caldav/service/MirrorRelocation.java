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
 * What one relocation pass did to the copies already on the server, and whether
 * the destination change may be recorded as applied.
 *
 * <p>
 * Counted rather than merely logged, because the two failures this can meet are
 * not the same and the caller acts differently on each. A copy the server
 * refused to remove leaves a duplicate the user can see, and the change is not
 * finished; a copy eXo can no longer render leaves nothing to move and never
 * will, whatever is retried.
 *
 * @param destination where the copies are now written, canonical — null when no
 *          destination could be established at all, which is the state a
 *          registration left to the user's choice sits in until they answer
 * @param moved how many copies were written into the destination and their
 *          mapping re-pointed
 * @param refused how many old copies the server would not remove, and which
 *          therefore stay where they were
 * @param failed how many copies could not be written into the destination
 * @param unmovable how many mapping rows eXo can no longer render an object
 *          for, and which are therefore left exactly where they are
 */
public record MirrorRelocation(String destination, int moved, int refused, int failed, int unmovable) {

  /**
   * The answer when no destination could be established — the account cannot be
   * reached, or the registration leaves the choice to a user who has not made
   * it.
   *
   * @return a relocation that did nothing and applied nothing
   */
  public static MirrorRelocation deferred() {
    return new MirrorRelocation(null, 0, 0, 0, 0);
  }

  /**
   * Whether a destination was established at all.
   *
   * <p>
   * The caller's gate on doing any settings work this pass: comparing the
   * contents of copies against a render is wasted when eXo does not yet know
   * which collection they belong in.
   *
   * @return true when the copies have a destination
   */
  public boolean applicable() {
    return destination != null;
  }

  /**
   * Whether the destination change may be stamped as applied.
   *
   * <p>
   * A refused removal and a failed write both mean the next pass has work left
   * to do, and the stamp is what would stop it looking. An unmovable row does
   * not block: it is a permanent property of a mapping that stands for no
   * event eXo can render, it will answer the same on every retry, and letting
   * it hold the stamp open would put every account holding one into a
   * full-content comparison round for ever — which is the cost this whole
   * mechanism exists to pay only once.
   *
   * @return true when nothing this pass tried to do failed
   */
  public boolean complete() {
    return applicable() && refused == 0 && failed == 0;
  }
}
