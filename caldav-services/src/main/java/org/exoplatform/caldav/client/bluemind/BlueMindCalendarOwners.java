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

import java.util.Map;

/**
 * Whose each calendar in a BlueMind account's view is, as BlueMind's own
 * subscription listing reports it (EXO-90347).
 *
 * <p>
 * The answer to
 * {@code GET /api/users/{domainUid}/subscriptions/{uid}?type=calendar}, read
 * as the account itself: one {@code ContainerSubscriptionDescriptor} per
 * calendar the account sees — its own, and every one it subscribed to — each
 * carrying the {@code owner} directory entry uid
 * ({@code parent/core/net.bluemind.core.container.api/.../ContainerSubscriptionDescriptor.java};
 * captured live on 2026-09-16 as
 * {@code bluemind-rest-subscriptions-after-subscribe.captured.json}). It is
 * the one place BlueMind says whose a calendar is: its DAV listing puts
 * every subscribed calendar under the subscriber's own home and names the
 * subscriber as {@code DAV:owner} ({@link BlueMindContainerNaming}), so a
 * colleague's calendar and the account's own look alike over CalDAV.
 *
 * @param accountUid the directory entry uid the session was authenticated
 *          as ({@code authUser.uid} of the login answer) — the uid a calendar
 *          of the account's own carries as its owner
 * @param ownerByContainerUid the owner uid of each listed calendar, keyed by
 *          the container uid, which is the last segment of the calendar's
 *          collection path; unmodifiable
 */
public record BlueMindCalendarOwners(String accountUid, Map<String, String> ownerByContainerUid) {

  /**
   * An answer, its map made unmodifiable.
   *
   * @param accountUid the authenticated account's uid
   * @param ownerByContainerUid the owner of each listed calendar
   */
  public BlueMindCalendarOwners {
    ownerByContainerUid = Map.copyOf(ownerByContainerUid);
  }
}
