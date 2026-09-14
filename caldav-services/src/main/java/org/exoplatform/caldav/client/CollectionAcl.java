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
import java.util.Set;

/**
 * A collection's {@code DAV:acl} as the server answered it to its owner
 * (EXO-90253), with the two facts that decide whether it may be written back.
 *
 * <p>
 * <b>Readable</b>: the server granted the property. Reading it needs
 * {@code DAV:read-acl} (RFC 3744 §3.6), and a server may answer it in a 403
 * or 404 propstat; {@link #currentUserPrivileges()} then says whether the
 * privilege was missing.
 *
 * <p>
 * <b>Understood</b>: every entry parsed into an {@link AccessControlEntry}.
 * Writing an ACL replaces every modifiable entry, so a list read with one
 * entry skipped would delete that entry — somebody's share, possibly made
 * outside eXo — the moment it is written back. An ACL that is not understood
 * is never written.
 *
 * @param readable whether the server granted {@code DAV:acl}
 * @param understood whether every entry was parsed, false as well when the
 *          ACL is not readable
 * @param entries the entries in document order, empty unless understood
 * @param currentUserPrivileges the caller's own privileges on the collection
 *          as Clark names, empty when the server answered none
 * @param reason what could not be parsed, for the log; null when understood
 */
public record CollectionAcl(boolean readable,
                            boolean understood,
                            List<AccessControlEntry> entries,
                            Set<String> currentUserPrivileges,
                            String reason) {

  /**
   * An ACL read and parsed entirely.
   *
   * @param entries the entries
   * @param currentUserPrivileges the caller's privileges
   * @return the ACL
   */
  public static CollectionAcl of(List<AccessControlEntry> entries, Set<String> currentUserPrivileges) {
    return new CollectionAcl(true, true, List.copyOf(entries), Set.copyOf(currentUserPrivileges), null);
  }

  /**
   * An ACL the server did not grant.
   *
   * @param currentUserPrivileges the caller's privileges, possibly empty
   * @return the ACL
   */
  public static CollectionAcl unreadable(Set<String> currentUserPrivileges) {
    return new CollectionAcl(false, false, List.of(), Set.copyOf(currentUserPrivileges), "DAV:acl was not granted");
  }

  /**
   * An ACL granted but holding something this client cannot represent.
   *
   * @param currentUserPrivileges the caller's privileges
   * @param reason what was not understood
   * @return the ACL
   */
  public static CollectionAcl notUnderstood(Set<String> currentUserPrivileges, String reason) {
    return new CollectionAcl(true, false, List.of(), Set.copyOf(currentUserPrivileges), reason);
  }
}
