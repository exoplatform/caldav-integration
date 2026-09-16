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

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.caldav.entity.CaldavShareObservationEntity;

/**
 * Persistence access to which of a user's calendars other eXo users' CalDAV
 * homes list (EXO-90331).
 */
public interface CaldavShareObservationDAO extends JpaRepository<CaldavShareObservationEntity, Long> {

  /**
   * Every sighting one sharee's home produced on one server, oldest row first.
   *
   * <p>
   * The set a reconciliation compares its listing against, and the only read
   * the write path makes. Served by the leading columns of
   * {@code UQ_CALDAV_SHARE_OBSERVATION (SHAREE_IDENTITY_ID, SERVER_ID,
   * CALENDAR_SYNC_UID)}. Ordered by id so that a bounded page reads the same
   * rows on every pass.
   *
   * @param shareeIdentityId the eXo user whose home listed them
   * @param serverId the declared server registration
   * @param pageable how many rows to read at most; required
   * @return the rows, possibly empty
   */
  @Query("SELECT o FROM CaldavShareObservationEntity o WHERE o.shareeIdentityId = :shareeIdentityId"
      + " AND o.serverId = :serverId ORDER BY o.id ASC")
  List<CaldavShareObservationEntity> findBySharee(@Param("shareeIdentityId") long shareeIdentityId,
                                                  @Param("serverId") long serverId,
                                                  Pageable pageable);

  /**
   * How many colleagues see each of one owner's calendars on one server.
   *
   * <p>
   * The question the table exists for, answered in one statement for every
   * calendar of the owner at once — the panel refresh asks it, so an answer
   * costing one statement per calendar would be the N the whole design exists
   * to remove. Served by {@code IDX_CALDAV_SHARE_OBSERVATION_OWNER
   * (OWNER_IDENTITY_ID, SERVER_ID, CALENDAR_SYNC_UID)}, whose third column
   * carries the grouping.
   *
   * <p>
   * {@code COUNT(DISTINCT)} rather than {@code COUNT}: the unique index makes
   * a sharee's row unique per calendar already, so the two agree — and they
   * must keep agreeing if the key ever gains a column, which distinct is the
   * cheap insurance for. Returned as {@code Object[]} pairs of anchor and
   * count, which the storage maps; a projection interface would add a type for
   * two columns read in one place.
   *
   * @param ownerIdentityId the eXo user whose calendars they are
   * @param serverId the declared server registration
   * @return {@code {anchor, count}} rows, one per calendar with at least one
   *         sighting; empty when none has any
   */
  @Query("SELECT o.calendarSyncUid, COUNT(DISTINCT o.shareeIdentityId) FROM CaldavShareObservationEntity o"
      + " WHERE o.ownerIdentityId = :ownerIdentityId AND o.serverId = :serverId"
      + " GROUP BY o.calendarSyncUid")
  List<Object[]> countShareesByCalendar(@Param("ownerIdentityId") long ownerIdentityId, @Param("serverId") long serverId);

  /**
   * Removes every sighting one sharee's home ever produced, on whichever
   * server.
   *
   * <p>
   * A bulk statement rather than a find-then-delete: forgetting a user who saw
   * nothing costs one statement and no read. Asked when the account is
   * disconnected — a home that is no longer listed confirms nothing, and a row
   * nobody will ever contradict again is exactly the stale mark this table
   * must not produce.
   *
   * @param shareeIdentityId the eXo user whose home is no longer read
   * @return how many rows were removed
   */
  @Modifying
  @Transactional
  @Query("DELETE FROM CaldavShareObservationEntity o WHERE o.shareeIdentityId = :shareeIdentityId")
  int deleteBySharee(@Param("shareeIdentityId") long shareeIdentityId);
}
