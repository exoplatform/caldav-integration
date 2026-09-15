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
 * What kind of owner a calendar shared with the user belongs to
 * (EXO-90275): a person, or a resource such as a room or a pool vehicle.
 *
 * <p>
 * Travels on {@link RemoteCalendar} beside the owner's name, so that the
 * "Shared with me" list can draw a resource with a resource glyph rather
 * than with an avatar nobody has. It is said only of a shared calendar and
 * is null on the user's own.
 */
public enum CalendarOwnerKind {

  /**
   * A person: a colleague of this deployment, or a principal the server
   * named. The kind of every share whose owner the server does not
   * distinguish, which is every share but a BlueMind resource subscription
   * today.
   */
  PERSON,

  /**
   * A bookable resource the user subscribed to — on BlueMind, a calendar
   * listed as {@code calendar:<resource uid>} under the user's own home.
   */
  RESOURCE
}
