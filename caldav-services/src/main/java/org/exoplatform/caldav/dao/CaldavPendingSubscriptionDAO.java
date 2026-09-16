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

import org.exoplatform.caldav.entity.CaldavPendingSubscriptionEntity;

/**
 * The subscription changes eXo owes colleagues' BlueMind accounts
 * (EXO-90277).
 *
 * <p>
 * Every query string is spelled out and every parameter named, for the
 * reason {@link CaldavPendingPushDAO} records: a mock answers happily to a
 * query the engine would refuse, and {@code CaldavSyncDAOQueryTest} runs
 * these through a real repository proxy over HSQLDB.
 */
public interface CaldavPendingSubscriptionDAO extends JpaRepository<CaldavPendingSubscriptionEntity, Long> {

  /**
   * The one row a colleague, a server and a container may have.
   *
   * @param userIdentityId the sharee
   * @param serverId the server key
   * @param containerUid the container uid
   * @return the row, if any
   */
  Optional<CaldavPendingSubscriptionEntity> findByUserIdentityIdAndServerIdAndContainerUid(long userIdentityId,
                                                                                            long serverId,
                                                                                            String containerUid);

  /**
   * The changes still worth attempting, whoever they are owed to.
   *
   * <p>
   * Table-wide on purpose (hole 2 of the brief): the colleague owed a
   * subscription holds no active pair and is never selected by the sweep's
   * per-account paging, so the drain reads the table rather than visiting
   * accounts. The caller pages oldest first and groups by sharee.
   *
   * @param maxAttempts the configured bound
   * @param pageable the page, sorted by the caller
   * @return the rows
   */
  @Query("SELECT q FROM CaldavPendingSubscriptionEntity q WHERE q.attempts < :maxAttempts")
  List<CaldavPendingSubscriptionEntity> findAttemptable(@Param("maxAttempts") int maxAttempts, Pageable pageable);

  /**
   * The changes one colleague is owed and that are still worth attempting.
   *
   * @param userIdentityId the sharee
   * @param maxAttempts the configured bound
   * @param pageable the page, sorted by the caller
   * @return the rows
   */
  @Query("SELECT q FROM CaldavPendingSubscriptionEntity q WHERE q.userIdentityId = :userIdentityId"
      + " AND q.attempts < :maxAttempts")
  List<CaldavPendingSubscriptionEntity> findAttemptableOf(@Param("userIdentityId") long userIdentityId,
                                                          @Param("maxAttempts") int maxAttempts,
                                                          Pageable pageable);

  /**
   * Counts one more refused attempt, in one statement, so two nodes draining
   * the same row do not lose an increment to a read-modify-save.
   *
   * @param id the row
   * @return rows updated, one or zero
   */
  @Modifying(flushAutomatically = true)
  @Transactional
  @Query("UPDATE CaldavPendingSubscriptionEntity q SET q.attempts = q.attempts + 1 WHERE q.id = :id")
  int recordAttempt(@Param("id") long id);

  /**
   * Spends the whole budget at once: the change is given up on without
   * counting toward the bound, because the answer will not change by asking
   * again — a refusal, a container that is gone, a colleague no longer
   * connected, a session that is not theirs.
   *
   * @param id the row
   * @param attempts the configured bound, written whole
   * @return rows updated, one or zero
   */
  @Modifying(flushAutomatically = true)
  @Transactional
  @Query("UPDATE CaldavPendingSubscriptionEntity q SET q.attempts = :attempts WHERE q.id = :id")
  int spendBudget(@Param("id") long id, @Param("attempts") int attempts);

}
