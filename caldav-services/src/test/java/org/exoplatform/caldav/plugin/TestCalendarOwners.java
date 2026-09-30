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

import java.util.Map;

/**
 * An owner listing as a server-specific subscription channel answers one,
 * for the host's tests.
 *
 * @param accountUid the uid the session was authenticated as
 * @param ownerByContainerUid the owner uid of each calendar, by container uid
 */
public record TestCalendarOwners(String accountUid, Map<String, String> ownerByContainerUid) implements CalendarOwners {

  /**
   * The listing, with an unmodifiable copy of the owners.
   *
   * @param accountUid the uid the session was authenticated as
   * @param ownerByContainerUid the owners, by container uid
   */
  public TestCalendarOwners {
    ownerByContainerUid = Map.copyOf(ownerByContainerUid);
  }
}
