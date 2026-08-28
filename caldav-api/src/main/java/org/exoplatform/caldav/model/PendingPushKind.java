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
 * What eXo still owes one calendar copy.
 *
 * <p>
 * Two instructions and not one, because a calendar cannot infer the second
 * from the first. Rewriting a copy needs the eXo event to render; removing one
 * happens precisely when that event no longer exists, so the only identity
 * left to address the object by is the iCalendar UID the mapping row carries.
 * A single "something is owed" marker would have to guess which of the two it
 * meant, and the guess it would get wrong — treating a removal as a rewrite —
 * puts a meeting back into somebody's calendar after it was called off.
 */
public enum PendingPushKind {

  /**
   * The copy has to be written again from the eXo event: a meeting was moved,
   * renamed, relocated or cancelled, and the copy still shows what it said
   * before.
   */
  REWRITE,

  /**
   * The copy has to go: the eXo event behind it was destroyed. This is the case
   * with no other safety net at all — a destroyed event renders to nothing, and
   * the verification pass deliberately refuses to conclude anything from an
   * empty render, so nothing but this record will ever remove the object.
   */
  REMOVE

}
