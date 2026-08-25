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

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.caldav.entity.CaldavObjectSyncEntity;

/**
 * Persistence access to the object mappings.
 *
 * <p>
 * Every listing here is paginated without exception: a calendar's object count
 * is the user's, not the developer's, to decide, and a busy shared calendar is
 * exactly the one whose full read would hurt.
 */
public interface CaldavObjectSyncDAO extends JpaRepository<CaldavObjectSyncEntity, Long> {

  /**
   * The mapping for one iCalendar object inside a pair. The identity lookup the
   * engine performs before every write.
   *
   * @param calendarSyncId the pair
   * @param icsUid the iCalendar UID
   * @return the mapping, if the object is already known
   */
  Optional<CaldavObjectSyncEntity> findByCalendarSyncIdAndIcsUid(long calendarSyncId, String icsUid);

  /**
   * The mapping for one eXo event inside a pair.
   *
   * @param calendarSyncId the pair
   * @param localEventId the eXo event
   * @return the mapping, if the event has been pushed
   */
  Optional<CaldavObjectSyncEntity> findByCalendarSyncIdAndLocalEventId(long calendarSyncId, long localEventId);

  /**
   * One page of a pair's object mappings.
   *
   * @param calendarSyncId the pair
   * @param pageable page and sort; required
   * @return one page of mappings
   */
  Page<CaldavObjectSyncEntity> findByCalendarSyncId(long calendarSyncId, Pageable pageable);

  /**
   * How many objects a pair maps.
   *
   * @param calendarSyncId the pair
   * @return the mapping count
   */
  long countByCalendarSyncId(long calendarSyncId);

  /**
   * Whether an eXo event is already mapped anywhere. The backfill's idempotence
   * check, asked once per event rather than per pair.
   *
   * @param localEventId the eXo event
   * @return true when a mapping exists
   */
  boolean existsByLocalEventId(long localEventId);

  /**
   * Which of these eXo events are already mapped.
   *
   * The same question as {@link #existsByLocalEventId(long)} asked once for a
   * whole batch. The seeding pass reads a user's upcoming meetings on every
   * sweep and, in the steady state, finds that all of them already have a
   * copy — so asking one event at a time made the cost of doing nothing grow
   * with how much had already been done, which is the wrong direction for
   * work that repeats for ever.
   *
   * @param localEventIds the eXo events to ask about
   * @return the identifiers among them that carry a mapping
   */
  @Query("SELECT DISTINCT o.localEventId FROM CaldavObjectSyncEntity o WHERE o.localEventId IN :localEventIds")
  List<Long> findMappedLocalEventIds(@Param("localEventIds") Collection<Long> localEventIds);

  /**
   * Drops every mapping of a pair. Called when a pair is unbound; the foreign
   * key would cascade on a row delete, but a pair is often kept as a tombstone
   * while its objects are not.
   *
   * @param calendarSyncId the pair
   * @return how many mappings were removed
   */
  @Modifying
  @Transactional
  @Query("DELETE FROM CaldavObjectSyncEntity o WHERE o.calendarSyncId = :calendarSyncId")
  int deleteByCalendarSyncId(@Param("calendarSyncId") long calendarSyncId);

}
