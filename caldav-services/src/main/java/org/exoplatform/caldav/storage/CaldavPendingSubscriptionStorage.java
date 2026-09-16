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
package org.exoplatform.caldav.storage;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.caldav.dao.CaldavPendingSubscriptionDAO;
import org.exoplatform.caldav.entity.CaldavPendingSubscriptionEntity;
import org.exoplatform.caldav.model.PendingSubscription;
import org.exoplatform.caldav.model.PendingSubscriptionKind;

/**
 * Maps the subscription changes eXo owes colleagues' BlueMind accounts
 * between their JPA entity and the service-layer DTO (EXO-90277).
 *
 * <p>
 * No business logic: which changes are owed, how hard to try and when to
 * stop belong to the service. What lives here is the mechanical part — that
 * an obligation is one per colleague, server and container, so recording the
 * same one twice replaces it rather than queueing it, and that renewing it
 * resets the attempt count because a new instruction deserves its own
 * patience. The shape is {@link CaldavPendingPushStorage}'s, keyed by the
 * sharee rather than by a mapping row.
 */
@Component
public class CaldavPendingSubscriptionStorage {

  @Autowired
  private CaldavPendingSubscriptionDAO pendingSubscriptionDAO;

  /**
   * Records that a colleague's account owes a change, replacing whatever was
   * owed about the same container before.
   *
   * <p>
   * Replacing, not queueing: the latest instruction describes the account. A
   * revoke recorded while a subscribe is still pending turns the row into a
   * removal — harmless when nothing was ever subscribed, and the only right
   * answer when it was. The lookup before the write is a check-then-act and
   * the unique index is what settles two writers: the loser's insert is
   * refused and its caller says so.
   *
   * @param userIdentityId the sharee
   * @param serverId the server key
   * @param containerUid the container uid
   * @param kind whether to subscribe or unsubscribe
   * @return the obligation as it now stands
   */
  @Transactional
  public PendingSubscription owe(long userIdentityId, long serverId, String containerUid, PendingSubscriptionKind kind) {
    Optional<CaldavPendingSubscriptionEntity> existing =
                                                       pendingSubscriptionDAO.findByUserIdentityIdAndServerIdAndContainerUid(userIdentityId,
                                                                                                                              serverId,
                                                                                                                              containerUid);
    CaldavPendingSubscriptionEntity entity = existing.orElseGet(CaldavPendingSubscriptionEntity::new);
    entity.setUserIdentityId(userIdentityId);
    entity.setServerId(serverId);
    entity.setContainerUid(containerUid);
    entity.setKind(kind);
    entity.setAttempts(0);
    if (entity.getSince() == null) {
      entity.setSince(new Date());
    }
    return fromEntity(pendingSubscriptionDAO.save(entity));
  }

  /**
   * Forgets what was owed about one container, because the change landed.
   *
   * <p>
   * By the colleague, server and container rather than by the row's own
   * identifier: the caller that landed a change knows which account and
   * container it landed on, and looking the row up again is what makes
   * settling one that was renewed in the meantime remove the renewal too —
   * correct, since the change that landed is the latest instruction.
   *
   * @param userIdentityId the sharee
   * @param serverId the server key
   * @param containerUid the container uid
   */
  @Transactional
  public void settled(long userIdentityId, long serverId, String containerUid) {
    pendingSubscriptionDAO.findByUserIdentityIdAndServerIdAndContainerUid(userIdentityId, serverId, containerUid)
                          .ifPresent(entity -> pendingSubscriptionDAO.deleteById(entity.getId()));
  }

  /**
   * Records that one more attempt was refused.
   *
   * @param id the obligation
   */
  @Transactional
  public void refused(long id) {
    pendingSubscriptionDAO.recordAttempt(id);
  }

  /**
   * Records that an obligation is given up on, without counting toward the
   * bound first.
   *
   * @param id the obligation
   * @param maxAttempts the configured bound, written whole
   */
  @Transactional
  public void abandoned(long id, int maxAttempts) {
    pendingSubscriptionDAO.spendBudget(id, maxAttempts);
  }

  /**
   * What is owed to anybody and still worth attempting, oldest first.
   *
   * @param maxAttempts how many refusals are argued with before stopping
   * @param limit how many to take in one pass
   * @return the obligations to attempt now, possibly none
   */
  public List<PendingSubscription> attemptable(int maxAttempts, int limit) {
    return pendingSubscriptionDAO.findAttemptable(maxAttempts, oldestFirst(limit)).stream().map(this::fromEntity).toList();
  }

  /**
   * What one colleague is owed and is still worth attempting, oldest first.
   *
   * @param userIdentityId the sharee
   * @param maxAttempts how many refusals are argued with before stopping
   * @param limit how many to take in one pass
   * @return the obligations to attempt now, possibly none
   */
  public List<PendingSubscription> attemptable(long userIdentityId, int maxAttempts, int limit) {
    return pendingSubscriptionDAO.findAttemptableOf(userIdentityId, maxAttempts, oldestFirst(limit))
                                 .stream()
                                 .map(this::fromEntity)
                                 .toList();
  }

  /**
   * A page in insertion order: a backlog is drained, not shuffled.
   *
   * @param limit the page size
   * @return the page request
   */
  private static Pageable oldestFirst(int limit) {
    return PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "id"));
  }

  /**
   * Maps an obligation's entity onto its DTO.
   *
   * @param entity the persisted obligation
   * @return the DTO the service layer handles
   */
  private PendingSubscription fromEntity(CaldavPendingSubscriptionEntity entity) {
    return new PendingSubscription(entity.getId(),
                                   entity.getUserIdentityId(),
                                   entity.getServerId(),
                                   entity.getContainerUid(),
                                   entity.getKind(),
                                   entity.getAttempts(),
                                   entity.getSince());
  }

}
