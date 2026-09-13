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
 * Who a shared calendar belongs to, as far as the viewer may be told
 * (EXO-90237).
 *
 * <p>
 * Two grains, matching the two witnesses {@link CollectionOwnership} hears.
 * When <b>this deployment</b> recognised a colleague's eXo calendar, the
 * owner is one of its users and all three fields are set — identity, login
 * and full name — so agenda can show the person, avatar and popover
 * included. When only <b>the server</b> said the collection is somebody
 * else's, nothing maps its principal to an eXo user, and the owner is a
 * display name alone: what the principal calls itself, or failing that the
 * last segment of the path the server returned. {@link #NONE} is the answer
 * for the user's own calendars and for a share whose owner cannot be named.
 *
 * <p>
 * Services-local like its sibling enum: computed per listing, persisted
 * nowhere, and copied field by field onto the API's {@code RemoteCalendar}.
 *
 * @param identityId the social identity of the owning eXo user, or null
 * @param username that user's eXo login, or null
 * @param displayName how to name the owner to the viewer, or null
 */
public record CalendarOwner(Long identityId, String username, String displayName) {

  /** Nobody to name: the user's own calendar, or a share of unknown owner. */
  public static final CalendarOwner NONE = new CalendarOwner(null, null, null);

  /**
   * An owner known only by the name the server gives them.
   *
   * @param displayName what to call them; null when even that is unknown
   * @return the owner, {@link #NONE} when the name is null
   */
  public static CalendarOwner named(String displayName) {
    return displayName == null ? NONE : new CalendarOwner(null, null, displayName);
  }
}
