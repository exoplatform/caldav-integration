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

import org.exoplatform.caldav.dao.CaldavPendingPushDAO;
import org.exoplatform.caldav.entity.CaldavPendingPushEntity;
import org.exoplatform.caldav.model.PendingPush;
import org.exoplatform.caldav.model.PendingPushKind;

/**
 * Maps the writes eXo owes a calendar copy between their JPA entity and the
 * service-layer DTO.
 *
 * <p>
 * No business logic: which copies are owed a write, how hard to try and when to
 * stop belong to the service. What lives here is the mechanical part — that an
 * obligation is one per copy, so recording the same one twice replaces it
 * rather than queueing it, and that renewing it resets the attempt count
 * because a new edit deserves its own patience.
 */
@Component
public class CaldavPendingPushStorage {

  @Autowired
  private CaldavPendingPushDAO pendingPushDAO;

  /**
   * Records that one copy is behind, replacing whatever was owed to it before.
   *
   * <p>
   * Replacing, not queueing, and the attempt count goes back to zero with it.
   * Both follow from what an obligation says: not "this write failed" but "this
   * copy does not yet show what eXo holds". Five edits in a minute leave one
   * write owed, and the latest instruction is the one that describes the copy —
   * a rewrite recorded after a removal would otherwise put back a meeting
   * somebody destroyed.
   *
   * @param objectSyncId the mapping row whose copy is behind
   * @param userIdentityId whose calendar the copy sits in
   * @param kind whether the copy has to be written again or removed
   * @param localEventId the eXo event to render, null for a removal
   * @param icsUid the iCalendar identity a removal addresses the object by
   * @return the obligation as it now stands
   */
  @Transactional
  public PendingPush owe(long objectSyncId, long userIdentityId, PendingPushKind kind, Long localEventId, String icsUid) {
    Optional<CaldavPendingPushEntity> existing = pendingPushDAO.findByObjectSyncId(objectSyncId);
    CaldavPendingPushEntity entity = existing.orElseGet(CaldavPendingPushEntity::new);
    entity.setObjectSyncId(objectSyncId);
    entity.setUserIdentityId(userIdentityId);
    entity.setKind(kind);
    entity.setLocalEventId(localEventId);
    entity.setIcsUid(icsUid);
    entity.setAttempts(0);
    if (entity.getSince() == null) {
      entity.setSince(new Date());
    }
    return fromEntity(pendingPushDAO.save(entity));
  }

  /**
   * Forgets what was owed to one copy, because it has been written.
   *
   * <p>
   * By the mapping row rather than by the obligation's own identifier: the
   * caller settling a copy knows which copy it settled, and looking the
   * obligation up again is what makes settling one that was renewed in the
   * meantime remove the renewal too — which is correct, since the write that
   * just landed carries whatever the renewal was about.
   *
   * @param objectSyncId the mapping row whose copy was written
   */
  @Transactional
  public void settled(long objectSyncId) {
    pendingPushDAO.findByObjectSyncId(objectSyncId).ifPresent(entity -> pendingPushDAO.deleteById(entity.getId()));
  }

  /**
   * Records that one more attempt was refused.
   *
   * @param id the obligation
   */
  @Transactional
  public void refused(long id) {
    pendingPushDAO.recordAttempt(id);
  }

  /**
   * What one account is owed and is still worth attempting, oldest first.
   *
   * <p>
   * Oldest first because a backlog is drained rather than shuffled: a copy that
   * has been wrong the longest is the one somebody is most likely to be acting
   * on.
   *
   * @param userIdentityId whose calendar the copies sit in
   * @param maxAttempts how many refusals are argued with before stopping
   * @param limit how many to take in one pass
   * @return the obligations to attempt now, possibly none
   */
  public List<PendingPush> attemptable(long userIdentityId, int maxAttempts, int limit) {
    Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "id"));
    return pendingPushDAO.findAttemptable(userIdentityId, maxAttempts, pageable).stream().map(this::fromEntity).toList();
  }

  /**
   * How many copies of one account are behind, abandoned ones included.
   *
   * @param userIdentityId whose calendar the copies sit in
   * @return the count, zero on an account whose copies all landed
   */
  public long owed(long userIdentityId) {
    return pendingPushDAO.countByUserIdentityId(userIdentityId);
  }

  /**
   * Maps an obligation's entity onto its DTO.
   *
   * @param entity the persisted obligation
   * @return the DTO the service layer handles
   */
  private PendingPush fromEntity(CaldavPendingPushEntity entity) {
    return new PendingPush(entity.getId(),
                           entity.getObjectSyncId(),
                           entity.getUserIdentityId(),
                           entity.getKind(),
                           entity.getLocalEventId(),
                           entity.getIcsUid(),
                           entity.getAttempts(),
                           entity.getSince());
  }

}
