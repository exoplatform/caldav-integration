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

import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import org.exoplatform.caldav.entity.CaldavCalendarSyncEntity;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;

/**
 * Persistence access to the calendar pairs.
 *
 * <p>
 * Two shapes of read, deliberately kept apart. Everything scoped to one user on
 * one server is read whole: a person has a handful of calendars, and paginating
 * that would cost more than it saves. Everything that spans users — what the
 * background sweep does — is paginated, because that set grows with the
 * deployment and a full read of it would grow with it.
 */
public interface CaldavCalendarSyncDAO extends JpaRepository<CaldavCalendarSyncEntity, Long> {

  /**
   * Every pair a user holds on one server. The set the engine matches remote
   * collections against, in memory: matching by href in SQL would need an index
   * on a column too long for one on MySQL, and this set is small enough that
   * the question does not arise.
   *
   * @param userIdentityId identity of the user
   * @param serverId declared server registration
   * @return the user's pairs on that server, in no particular order
   */
  List<CaldavCalendarSyncEntity> findByUserIdentityIdAndServerId(long userIdentityId, long serverId);

  /**
   * The pair bound to one local calendar. The lookup the outbound half performs
   * before deciding whether a calendar needs creating remotely.
   *
   * @param userIdentityId identity of the user
   * @param serverId declared server registration
   * @param localCalendarSyncUid agenda's immutable calendar anchor
   * @return the pair, if the calendar is already bound
   */
  Optional<CaldavCalendarSyncEntity> findByUserIdentityIdAndServerIdAndLocalCalendarSyncUid(long userIdentityId,
                                                                                            long serverId,
                                                                                            String localCalendarSyncUid);

  /**
   * The pairs of one origin a user holds on a server. Used with
   * {@link SyncOrigin#MIRROR} to find the single mirror pair — a list rather
   * than an optional because the database cannot enforce that uniqueness (the
   * anchor is null there, and standard SQL leaves NULL rows outside a unique
   * index), so the caller must be able to see a duplicate rather than have one
   * silently picked for it.
   *
   * @param userIdentityId identity of the user
   * @param serverId declared server registration
   * @param origin which side created the collection
   * @return the matching pairs
   */
  List<CaldavCalendarSyncEntity> findByUserIdentityIdAndServerIdAndOrigin(long userIdentityId,
                                                                          long serverId,
                                                                          SyncOrigin origin);

  /**
   * Pairs in a given state whose last synchronisation ended before a cutoff, or
   * has never ended. What the background sweep selects on.
   *
   * <p>
   * Written as JPQL rather than derived from the method name: the derived form
   * of this predicate needs the status repeated on both sides of the OR, which
   * produces a name nobody can read and a parameter that must be passed twice.
   *
   * @param status the state to select
   * @param before pairs last synchronised strictly before this instant
   * @param pageable page and sort; required, this set spans every user
   * @return one page of due pairs
   */
  @Query("SELECT p FROM CaldavCalendarSyncEntity p WHERE p.status = :status"
      + " AND (p.lastSyncEnd IS NULL OR p.lastSyncEnd < :before)")
  Page<CaldavCalendarSyncEntity> findDue(@Param("status") CalendarSyncStatus status,
                                         @Param("before") Date before,
                                         Pageable pageable);

  /**
   * Every pair on one server, paginated. Used when a registration is
   * deactivated or removed and its bindings have to be dealt with.
   *
   * @param serverId declared server registration
   * @param pageable page and sort; required, this set spans every user
   * @return one page of pairs on that server
   */
  Page<CaldavCalendarSyncEntity> findByServerId(long serverId, Pageable pageable);

}
