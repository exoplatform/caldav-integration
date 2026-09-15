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
import org.exoplatform.caldav.model.CalendarSyncStatus;

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
   * The rows recorded on one server, lowest user first (EXO-90253).
   *
   * <p>
   * The population a calendar on that server can be shared with: an eXo user
   * is named to the server only by the principal recorded here. Served by the
   * leading column of {@code IDX_CALDAV_CONNECTION_PRINCIPAL (SERVER_ID,
   * PRINCIPAL)}; ordered so that a bounded page names the same users on every
   * call.
   *
   * @param serverId the declared server registration
   * @param pageable how many rows to read at most; required
   * @return the rows
   */
  @Query("SELECT c FROM CaldavConnectionEntity c WHERE c.serverId = :serverId ORDER BY c.userIdentityId ASC")
  List<CaldavConnectionEntity> findByServer(@Param("serverId") long serverId, Pageable pageable);

  /**
   * How many users hold a pair in one state on one server without an identity
   * recorded for that server.
   *
   * <p>
   * The completeness question behind the owner of a share (EXO-90243): "exactly
   * one user is connected as this principal" can be read off the table only
   * once every user synchronising with the server is in it, because a second
   * user on the same login who has not been recorded yet is invisible to
   * {@link #findByServerAndPrincipal}. Asked with the active state, the state
   * of an account being synchronised, whose next successful discovery records
   * its identity. An identity recorded for another server does not count,
   * since it says nothing about who the user is on this one.
   *
   * <p>
   * <b>Active is not "will be recorded".</b> Some users keep active pairs on a
   * server and are never recorded for it: an account pointed at another server
   * without being disconnected (its old pairs stay active, and every discovery
   * records it under the new server), a principal the column cannot hold (its
   * row is removed on every pass), settings removed without the disconnect
   * that pauses the pairs, a discovery that keeps failing with something other
   * than a refused credential. Each of them keeps this count above zero for as
   * long as it lasts — possibly for good — and so keeps every share on that
   * server named by its principal alone. That is the safe direction; the owner
   * service says so once per server at info.
   *
   * <p>
   * What it costs: the status index {@code IDX_CALDAV_CALENDAR_SYNC_STATUS}
   * serves the state, the server is compared on the rows it yields, and the
   * {@code NOT EXISTS} is a point lookup on {@code UQ_CALDAV_CONNECTION_USER}
   * per user. Asked only when a share's owner principal has exactly one
   * recorded user, once per principal per listing.
   *
   * @param serverId the declared server registration
   * @param status the state a pair has to be in to count, active in practice
   * @return the number of distinct such users, zero when every one is recorded
   */
  @Query("SELECT COUNT(DISTINCT p.userIdentityId) FROM CaldavCalendarSyncEntity p"
      + " WHERE p.serverId = :serverId AND p.status = :status"
      + " AND NOT EXISTS (SELECT c.id FROM CaldavConnectionEntity c"
      + " WHERE c.userIdentityId = p.userIdentityId AND c.serverId = p.serverId)")
  long countUsersWithPairsButNoIdentity(@Param("serverId") long serverId, @Param("status") CalendarSyncStatus status);

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
