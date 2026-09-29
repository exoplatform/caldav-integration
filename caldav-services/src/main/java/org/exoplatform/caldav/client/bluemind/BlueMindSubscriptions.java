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
package org.exoplatform.caldav.client.bluemind;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavForbiddenException;
import org.exoplatform.caldav.client.CalDavNotFoundException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.plugin.SubscriptionEdits;

/**
 * The edits one open session, checked to be the colleague's own, can make.
 */
public interface BlueMindSubscriptions extends SubscriptionEdits {

  /**
   * Subscribes the colleague to a container, with {@link BlueMindSubscriptionClient#OFFLINE_SYNC} and
   * {@link BlueMindSubscriptionClient#AUTOMOUNT}. A no-op on the server when they already are.
   *
   * @param containerUid the container uid, the last segment of the
   *          calendar collection's path
   * @throws CalDavAuthenticationException when BlueMind refuses the session
   * @throws CalDavForbiddenException when BlueMind refuses the edit
   * @throws CalDavNotFoundException when BlueMind does not hold the container
   * @throws CalDavUnreachableException when the server cannot be reached
   * @throws CalDavException when the answer is anything else
   */
  void subscribe(String containerUid);

  /**
   * Unsubscribes the colleague from a container. BlueMind removes the
   * subscription row whether or not the container still exists
   * ({@code UserSubscriptionService.unsubscribe}: "unsub anyway").
   *
   * @param containerUid the container uid
   * @throws CalDavAuthenticationException when BlueMind refuses the session
   * @throws CalDavForbiddenException when BlueMind refuses the edit
   * @throws CalDavNotFoundException when BlueMind answers not found
   * @throws CalDavUnreachableException when the server cannot be reached
   * @throws CalDavException when the answer is anything else
   */
  void unsubscribe(String containerUid);
}
