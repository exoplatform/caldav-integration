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
package org.exoplatform.caldav.plugin;

/**
 * The edits one session opened as a user can make to the calendars that user
 * subscribes to.
 */
public interface SubscriptionEdits {

  /**
   * Subscribes the user to a calendar container. A no-op on the server when
   * they already are.
   *
   * @param containerUid the container uid, the last segment of the calendar
   *          collection's path
   * @throws org.exoplatform.caldav.client.CalDavException when the server
   *           refuses or cannot be reached; the subclass says which
   */
  void subscribe(String containerUid);

  /**
   * Unsubscribes the user from a calendar container. A no-op on the server
   * when they are not subscribed.
   *
   * @param containerUid the container uid
   * @throws org.exoplatform.caldav.client.CalDavException when the server
   *           refuses or cannot be reached; the subclass says which
   */
  void unsubscribe(String containerUid);

}
