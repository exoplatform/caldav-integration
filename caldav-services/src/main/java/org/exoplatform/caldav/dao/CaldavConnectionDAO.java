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
package org.exoplatform.caldav.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.caldav.entity.CaldavConnectionEntity;

/**
 * Persistence access to who each connected eXo user is on their CalDAV server
 * (EXO-90243).
 */
public interface CaldavConnectionDAO extends JpaRepository<CaldavConnectionEntity, Long> {

  /**
   * The identity recorded for one eXo user, whichever server it names.
   *
   * <p>
   * Served by the unique index {@code UQ_CALDAV_CONNECTION_USER}: a point
   * lookup, asked once per discovery to decide whether anything changed.
   *
   * @param userIdentityId the eXo user
   * @return the row, when the user's identity has been recorded
   */
  Optional<CaldavConnectionEntity> findByUserIdentityId(long userIdentityId);

  /**
   * The rows recording one principal on one server, lowest user first.
   *
   * <p>
   * Served by {@code IDX_CALDAV_CONNECTION_PRINCIPAL (SERVER_ID, PRINCIPAL)}.
   * The equality is the database's, and on MySQL that equality is the column
   * collation's, which ignores case and accents: this answers the
   * <em>candidates</em>, and the caller keeps only the rows whose principal is
   * exactly the one asked about. Ordered so that a bounded page names the same
   * users on every call.
   *
   * @param serverId the declared server registration
   * @param principal the principal, canonical
   * @param pageable how many rows to read at most; required
   * @return the candidate rows
   */
  @Query("SELECT c FROM CaldavConnectionEntity c WHERE c.serverId = :serverId AND c.principal = :principal"
      + " ORDER BY c.userIdentityId ASC")
  List<CaldavConnectionEntity> findByServerAndPrincipal(@Param("serverId") long serverId,
                                                        @Param("principal") String principal,
                                                        Pageable pageable);

  /**
   * Removes the identity recorded for one eXo user.
   *
   * <p>
   * A bulk statement rather than a find-then-delete, so that forgetting a user
   * nobody recorded costs one statement and no read.
   *
   * @param userIdentityId the eXo user
   * @return how many rows were removed, zero or one
   */
  @Modifying
  @Transactional
  @Query("DELETE FROM CaldavConnectionEntity c WHERE c.userIdentityId = :userIdentityId")
  int deleteByUserIdentityId(@Param("userIdentityId") long userIdentityId);
}
