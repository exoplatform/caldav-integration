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
package org.exoplatform.caldav.client;

import java.util.List;

/**
 * What the server answered to a PROPPATCH — the raw status and the statuses
 * of every propstat that did not grant its property.
 *
 * <p>
 * Like {@link MkCalendarResult}, this is a claim and never a fact. A server
 * that answers 201 to a MKCALENDAR having created nothing is as free to answer
 * 207 with a granting propstat having changed nothing, and the one statement
 * a caller may trust is the property read back from the collection itself.
 * {@link #accepted()} therefore only says the server did not <i>decline</i>;
 * whether the name is now what eXo asked for is the read-back's to say.
 *
 * @param status the HTTP status the server answered
 * @param failedPropstatStatuses the statuses of the propstats that did not
 *          grant their property, in document order; empty when none failed
 *          or the body carried no readable propstat
 */
public record PropPatchResult(int status, List<Integer> failedPropstatStatuses) {

  /**
   * Whether the server declined to change properties on this collection at
   * all — a method it does not offer, or a collection it will not let this
   * account write.
   *
   * @return true when the verb itself was refused
   */
  public boolean refused() {
    return status == 403 || status == 405 || status == 501;
  }

  /**
   * Whether the server claims to have applied the change: a 2xx answer
   * (207 Multi-Status or a bare 200) with no propstat reporting a failure.
   *
   * @return true when nothing in the answer says no
   */
  public boolean accepted() {
    return (status == 200 || status == 207) && failedPropstatStatuses.isEmpty();
  }
}
