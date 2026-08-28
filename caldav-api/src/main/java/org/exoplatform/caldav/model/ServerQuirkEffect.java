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
 * What ticking a quirk actually does — and the two answers are not the same
 * kind of thing.
 *
 * <p>
 * <b>Why this is declared rather than inferred.</b> Every quirk reads, in the
 * drawer, as one more box beside the others. Most of them only change what eXo
 * <i>notices</i>; one changes what eXo <i>writes into somebody's calendar</i>.
 * An administrator has to be able to tell those apart at the moment they tick,
 * not a year later when they wonder why a copy on that server carries no
 * organizer. So the difference is a property of the catalogue entry, it decides
 * which stored list the tick is written into, and the drawer renders it.
 */
public enum ServerQuirkEffect {

  /**
   * eXo goes on writing exactly what it writes today and stops treating one
   * kind of divergence as evidence that somebody rewrote the copy.
   *
   * <p>
   * Reversible with no trace: unticking it makes the next sweep report the
   * divergence again, and nothing on any server changed in the meantime. What
   * it costs is stated per entry — always some form of "eXo will no longer
   * notice X".
   */
  TOLERATE,

  /**
   * eXo stops putting something into the copies it writes to this server.
   *
   * <p>
   * <b>This one alters stored data.</b> The next push, and the next repair,
   * write a document that says less than the one before it, in a calendar
   * somebody else reads. Unticking it puts the property back, but only on the
   * copies written after that — nothing goes back and repairs the ones written
   * meanwhile until the ordinary sweep next touches them.
   *
   * <p>
   * It usually needs no {@link #TOLERATE} entry beside it, and that is worth
   * checking rather than assuming: when eXo stops writing the property, the
   * copy and the render agree and there is no divergence left to excuse.
   * Shipping both would be excusing a difference that no longer exists — and
   * an excusal that outlives its reason is exactly the kind of thing that
   * silently hides the next real defect.
   */
  OMIT
}
