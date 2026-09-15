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

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * What one calendar listing has already found out about owners, so that it
 * asks each question once (EXO-90237, EXO-90243).
 *
 * <p>
 * Two answers, keyed by what each depends on. The <b>owner of a principal</b> —
 * an eXo user, a name, or nobody — depends on the principal, so a colleague who
 * shared three calendars is looked up, or asked her name, once. <b>How many
 * users of a server have no recorded identity</b> depends on the server alone,
 * and it is a count over every active pair of the deployment: keyed by the
 * principal, it was asked again for every colleague's share in one listing.
 *
 * <p>
 * Per listing on purpose, and so not thread-safe: created by the listing that
 * uses it and dropped with the request. An owner is not worth a cache that
 * outlives the request that found it, and a count that moves as users are
 * recorded must be asked again by the next listing.
 */
final class CalendarOwnerMemo {

  private final Map<String, CalendarOwner> ownersByPrincipal  = new HashMap<>();

  private final Map<Long, Long>            unrecordedByServer = new HashMap<>();

  /**
   * The owner already found for a principal in this listing.
   *
   * @param principalPath the owner principal, as the listing reported it
   * @return the owner found, {@link CalendarOwner#NONE} included, or null when
   *         the principal has not been looked up yet
   */
  CalendarOwner ownerOf(String principalPath) {
    return ownersByPrincipal.get(principalPath);
  }

  /**
   * Remembers the owner found for a principal, a lesser answer included, so
   * that a principal that mapped to nobody and would not say its name is asked
   * neither question again.
   *
   * @param principalPath the owner principal, as the listing reported it
   * @param owner the owner found
   */
  void remember(String principalPath, CalendarOwner owner) {
    ownersByPrincipal.put(principalPath, owner);
  }

  /**
   * How many users of a server have no recorded identity, asked at most once
   * per server in this listing.
   *
   * <p>
   * A count that fails is not remembered: the exception reaches the caller,
   * which degrades, and the next share on the same server asks again.
   *
   * @param serverId the server key
   * @param count the question, run only when this listing has not asked it
   * @return the count
   */
  long unrecordedOn(long serverId, LongSupplier count) {
    Long known = unrecordedByServer.get(serverId);
    if (known == null) {
      known = count.getAsLong();
      unrecordedByServer.put(serverId, known);
    }
    return known;
  }
}
