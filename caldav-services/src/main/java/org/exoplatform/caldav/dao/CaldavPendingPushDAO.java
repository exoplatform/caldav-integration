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

import org.exoplatform.caldav.entity.CaldavPendingPushEntity;

/**
 * Persistence access to the writes eXo owes but has not made.
 *
 * <p>
 * Every listing is bounded, like every other listing in this add-on — and here
 * the bound carries a second meaning: this table is empty once everything has
 * landed, so a query able to return "all of it" would be a query whose cost is
 * a backlog nobody is watching.
 */
public interface CaldavPendingPushDAO extends JpaRepository<CaldavPendingPushEntity, Long> {

  /**
   * The obligation recorded against one mapping row, if there is one.
   *
   * <p>
   * One per copy, which is what makes recording an obligation idempotent: a
   * meeting edited five times in a minute owes its copy one write, not five.
   *
   * @param objectSyncId the mapping row
   * @return the obligation, when the copy is behind
   */
  Optional<CaldavPendingPushEntity> findByObjectSyncId(long objectSyncId);

  /**
   * How many copies of one account are behind, abandoned ones included.
   *
   * @param userIdentityId whose calendar the copies sit in
   * @return the count, zero on a converged account
   */
  long countByUserIdentityId(long userIdentityId);

  /**
   * The writes still worth attempting for one account.
   *
   * <p>
   * The attempt bound sits in the query rather than in the loop that reads it,
   * so an abandoned obligation costs nothing to skip: the row stays as the
   * record that eXo gave up — the only place anybody can see that it did —
   * while this pass never reads it again unless a later edit renews it.
   *
   * <p>
   * Spelled out rather than derived from the method name: the derived spelling
   * would be {@code findByUserIdentityIdAndAttemptsLessThan}, whose
   * {@code LessThan} reads as a property to anything checking that the names in
   * a derived query exist — including this module's own repository contract
   * test.
   *
   * @param userIdentityId whose calendar the copies sit in
   * @param maxAttempts how many refusals are argued with before stopping
   * @param pageable how many to take and in which order; required
   * @return the obligations to attempt now
   */
  @Query("SELECT q FROM CaldavPendingPushEntity q WHERE q.userIdentityId = :userIdentityId"
      + " AND q.attempts < :maxAttempts")
  List<CaldavPendingPushEntity> findAttemptable(@Param("userIdentityId") long userIdentityId,
                                                @Param("maxAttempts") int maxAttempts,
                                                Pageable pageable);

  /**
   * How many of one account's copies are behind and still worth attempting.
   *
   * <p>
   * The same predicate as {@link #findAttemptable}, counted rather than
   * listed, and it has to stay the same predicate: this number is shown to the
   * user as "copies eXo has not managed to write yet", and a count that
   * included the obligations eXo has given up on would promise a retry that is
   * never coming. Those are a different sentence, said once at WARN to whoever
   * runs the platform.
   *
   * <p>
   * Spelled out as a query for the reason {@link #findAttemptable} is: the
   * derived spelling would carry a {@code LessThan} that reads as a property
   * name to anything checking this repository's method names.
   *
   * @param userIdentityId whose calendar the copies sit in
   * @param maxAttempts how many refusals are argued with before stopping
   * @return the count, zero on a converged account
   */
  @Query("SELECT COUNT(q) FROM CaldavPendingPushEntity q WHERE q.userIdentityId = :userIdentityId"
      + " AND q.attempts < :maxAttempts")
  long countAttemptable(@Param("userIdentityId") long userIdentityId, @Param("maxAttempts") int maxAttempts);

  /**
   * Records one more refusal against an obligation.
   *
   * <p>
   * An increment in the database rather than a read, an add and a save: the
   * counter bounds how hard eXo argues with one server, and two passes racing
   * must not both write "attempt 3".
   *
   * @param id the obligation
   * @return how many rows were updated; zero when it has since been settled
   */
  @Modifying(flushAutomatically = true)
  @Transactional
  @Query("UPDATE CaldavPendingPushEntity q SET q.attempts = q.attempts + 1 WHERE q.id = :id")
  int recordAttempt(@Param("id") long id);

}
