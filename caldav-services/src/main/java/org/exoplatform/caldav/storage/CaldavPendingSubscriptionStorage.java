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
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

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

  private static final Log LOG = ExoLogger.getLogger(CaldavPendingSubscriptionStorage.class);

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
   * Forgets whatever was owed about one container, because the caller has just
   * decided and applied the newest instruction about it.
   *
   * <p>
   * <b>For the caller who cannot be stale</b> — the grant and the revoke. They
   * decide the instruction in the same call, inside the share service's stripe
   * lock and after the access list was read back, and {@link #owe} — the only
   * method here that records an instruction, as opposed to settling or
   * counting one — has a single call site, theirs. So whatever row stands for
   * the container is older than what they just did, whichever kind it asks
   * for, and leaving it would have the drain later re-apply an instruction the
   * owner has already replaced. A
   * pending SUBSCRIBE surviving a landed revoke is the worst of those: the
   * drain re-subscribes the colleague to a calendar whose access entry is
   * gone — the dangling subscription this feature exists to prevent — and
   * BlueMind's subscribe makes no access check, so it lands.
   *
   * <p>
   * By the colleague, server and container rather than by the row's own
   * identifier, because {@link #owe} reuses the row and an id says nothing
   * about which instruction it now carries.
   *
   * @param userIdentityId the sharee
   * @param serverId the server key
   * @param containerUid the container uid
   */
  @Transactional
  public void settledWhateverWasOwed(long userIdentityId, long serverId, String containerUid) {
    pendingSubscriptionDAO.findByUserIdentityIdAndServerIdAndContainerUid(userIdentityId, serverId, containerUid)
                          .ifPresent(entity -> pendingSubscriptionDAO.deleteById(entity.getId()));
  }

  /**
   * Forgets what was owed about one container, because <em>that</em> change
   * landed — and only if the row is still asking for it.
   *
   * <p>
   * <b>For the caller who can be stale</b> — the drain, and only the drain. It
   * reads its rows once and then spends a round trip on each, inside a session
   * that costs a login and a logout. On one node the obligation's lock keeps a
   * revoke out of that window; on another node it does not, and a revoke there
   * records an UNSUBSCRIBE over the pending SUBSCRIBE — the same row, by the
   * one-row-per-container rule — while the drain lands its now-stale
   * SUBSCRIBE. Deleting by container alone would strike off the removal nobody
   * has made yet. With the kind matched in the delete itself, one statement,
   * the removal survives its predecessor's success and the next drain makes
   * it.
   *
   * <p>
   * The guard belongs here and <b>only</b> here: at the grant and the revoke
   * it could only ever refuse a delete that was right, which is why
   * {@link #settledWhateverWasOwed(long, long, String)} exists beside it.
   *
   * @param userIdentityId the sharee
   * @param serverId the server key
   * @param containerUid the container uid
   * @param kind the change that landed; a row now asking for the other one is
   *          left alone
   */
  @Transactional
  public void settledIfStillAsking(long userIdentityId, long serverId, String containerUid, PendingSubscriptionKind kind) {
    if (pendingSubscriptionDAO.deleteAsking(userIdentityId, serverId, containerUid, kind) == 0) {
      LOG.debug("A drained calendar {} landed for user {} and container {} on server {}, but no row asks for it any more;"
          + " whatever stands is left for the next drain", kind, userIdentityId, containerUid, serverId);
    }
  }

  /**
   * Whether an obligation still stands as it was read: the row is there and
   * still asks for the same change.
   *
   * @param id the obligation
   * @param kind the change it was read with
   * @return true when it still asks for it
   */
  public boolean stillAsking(long id, PendingSubscriptionKind kind) {
    return pendingSubscriptionDAO.findById(id).filter(entity -> entity.getKind() == kind).isPresent();
  }

  /**
   * Records that one more attempt was refused, unless the row has been renewed
   * with the other change since it was read.
   *
   * @param id the obligation
   * @param kind the change that was refused
   */
  @Transactional
  public void refused(long id, PendingSubscriptionKind kind) {
    pendingSubscriptionDAO.recordAttempt(id, kind);
  }

  /**
   * Records that an obligation is given up on, without counting toward the
   * bound first, unless the row has been renewed with the other change since
   * it was read.
   *
   * @param id the obligation
   * @param kind the change given up on
   * @param maxAttempts the configured bound, written whole
   */
  @Transactional
  public void abandoned(long id, PendingSubscriptionKind kind, int maxAttempts) {
    pendingSubscriptionDAO.spendBudget(id, kind, maxAttempts);
  }

  /**
   * One page of what is owed to anybody and still worth attempting, oldest first:
   * what lets a drain look past rows it has to leave where they are.
   *
   * @param maxAttempts how many refusals are argued with before stopping
   * @param page the zero-based page, in the same oldest-first order
   * @param limit the page size
   * @return the obligations of that page, possibly none
   */
  public List<PendingSubscription> attemptable(int maxAttempts, int page, int limit) {
    return pendingSubscriptionDAO.findAttemptable(maxAttempts, oldestFirst(page, limit)).stream().map(this::fromEntity).toList();
  }

  /**
   * What one colleague is owed and is still worth attempting, oldest first.
   *
   * @param userIdentityId the sharee
   * @param maxAttempts how many refusals are argued with before stopping
   * @param limit how many to take in one pass
   * @return the obligations to attempt now, possibly none
   */
  public List<PendingSubscription> attemptableOf(long userIdentityId, int maxAttempts, int limit) {
    return pendingSubscriptionDAO.findAttemptableOf(userIdentityId, maxAttempts, oldestFirst(0, limit))
                                 .stream()
                                 .map(this::fromEntity)
                                 .toList();
  }

  /**
   * A page in insertion order: a backlog is drained, not shuffled.
   *
   * @param page the zero-based page
   * @param limit the page size
   * @return the page request
   */
  private static Pageable oldestFirst(int page, int limit) {
    return PageRequest.of(page, limit, Sort.by(Sort.Direction.ASC, "id"));
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
