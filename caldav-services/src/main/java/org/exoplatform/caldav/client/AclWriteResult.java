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
 * What the server answered to an {@code ACL} request (EXO-90253): a claim,
 * never a fact.
 *
 * <p>
 * A success status says the server accepted the body; only the ACL read back
 * afterwards says the grant is there — the same discipline as
 * {@link MkCalendarResult}. A refusal carries what RFC 3744 §7.1.1 and §8.1.1
 * let a server say about why: the preconditions its {@code DAV:error} body
 * names ({@code need-privileges}, {@code no-ace-conflict},
 * {@code recognized-principal}, {@code allowed-principal},
 * {@code no-abstract}, {@code grant-only}, {@code limited-number-of-aces}…),
 * and for {@code need-privileges} the privileges that were missing. A body
 * that names nothing leaves both lists empty; the status still stands.
 *
 * @param status the HTTP status
 * @param preconditions the local names of the {@code DAV:error} children, a
 *          Clark name for one outside the DAV namespace; empty when none
 * @param missingPrivileges the privileges {@code need-privileges} named, as
 *          local names for DAV privileges and Clark names for others; empty
 *          when none
 */
public record AclWriteResult(int status, List<String> preconditions, List<String> missingPrivileges) {

  /**
   * Whether the server accepted the request: RFC 3744 §8.1 answers 200,
   * and 204 is how some servers acknowledge a body-less success.
   *
   * @return true on 200 or 204
   */
  public boolean accepted() {
    return status == 200 || status == 204;
  }
}
