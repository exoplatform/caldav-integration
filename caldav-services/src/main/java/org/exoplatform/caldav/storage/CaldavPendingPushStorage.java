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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

  private static final int IN_CHUNK = 500;

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
   * <p>
   * <b>The lookup before the write is a check-then-act, and the database is what
   * settles it.</b> Two listener threads carrying two edits of the same meeting
   * can both read "nothing owed" before either has written, and the unique
   * constraint on the mapping row is what makes the outcome one row rather than
   * two — the loser's insert is refused and its caller says so. Nothing is lost
   * by that: the winner's row already records the same instruction about the
   * same copy. The only case where the two differ is an edit and a deletion of
   * one meeting in the same instant, and there the refusal is visible in the log
   * rather than silent.
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
   * Forgets what was owed to one copy, because a write of that kind landed.
   *
   * <p>
   * By the mapping row rather than by the obligation's own identifier: the
   * caller settling a copy knows which copy it settled, and an obligation of
   * the same kind renewed in the meantime goes too. A rewrite renders the
   * event as agenda holds it when the write reads it, so it carries every edit
   * agenda saved before that read; one saved after it is not carried, and is
   * left to that edit's own write, which this write overwrites when that one
   * lands before this one reads the copy's version.
   *
   * <p>
   * Only of that kind. A rewrite landing while a removal was recorded for the
   * same copy, or a removal landing while a rewrite was, carried out an
   * instruction that no longer stands, and the obligation recorded since is
   * left for whoever recorded it or the next retry pass. One statement, so a
   * kind changed by another thread between a read and the deletion is never
   * deleted with it.
   *
   * @param objectSyncId the mapping row whose copy was written
   * @param kind the kind of write that landed
   */
  @Transactional
  public void settled(long objectSyncId, PendingPushKind kind) {
    pendingPushDAO.deleteOwed(objectSyncId, kind);
  }

  /**
   * What is owed to one copy as it stands now.
   *
   * <p>
   * Read from the database on each call: the retry pass asks it just before
   * carrying out an obligation it read in a batch, which another thread may
   * have settled or replaced since.
   *
   * @param objectSyncId the mapping row
   * @return the obligation recorded against it, empty when nothing is owed
   */
  public Optional<PendingPush> owedTo(long objectSyncId) {
    return pendingPushDAO.findByObjectSyncId(objectSyncId).map(this::fromEntity);
  }

  /**
   * Records that one more attempt was refused, unless the obligation has been
   * settled or replaced with the other kind since it was read.
   *
   * @param id the obligation
   * @param kind the kind of write that was refused
   * @return true when the refusal was counted
   */
  @Transactional
  public boolean refused(long id, PendingPushKind kind) {
    return pendingPushDAO.recordAttempt(id, kind) > 0;
  }

  /**
   * Records that an obligation is given up on, without counting toward the
   * bound first, unless it has been settled or replaced with the other kind
   * since it was read.
   *
   * @param id the obligation
   * @param kind the kind of write given up on
   * @param maxAttempts the configured bound, written whole
   * @return true when the obligation was given up on
   */
  @Transactional
  public boolean abandoned(long id, PendingPushKind kind, int maxAttempts) {
    return pendingPushDAO.spendBudget(id, kind, maxAttempts) > 0;
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
   * Which of these mapping rows are still owed a removal, abandoned or not.
   *
   * <p>
   * Asked once per edit or answer, before a rewrite is recorded over the same
   * rows, in chunks of {@value #IN_CHUNK}. The bound is this class's own, not
   * an engine's: the holders of a meeting are not bounded, and no statement's
   * {@code IN} list carries more identifiers than that, however many there
   * are.
   *
   * @param objectSyncIds the mapping rows
   * @return the mapping rows among them owed a removal, empty when none are
   */
  public Set<Long> removalsOwed(Collection<Long> objectSyncIds) {
    Set<Long> owed = new HashSet<>();
    if (objectSyncIds == null || objectSyncIds.isEmpty()) {
      return owed;
    }
    List<Long> ids = new ArrayList<>(new LinkedHashSet<>(objectSyncIds));
    for (int from = 0; from < ids.size(); from += IN_CHUNK) {
      List<Long> chunk = ids.subList(from, Math.min(from + IN_CHUNK, ids.size()));
      owed.addAll(pendingPushDAO.findOwedObjectSyncIds(chunk, PendingPushKind.REMOVE));
    }
    return owed;
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
   * How many copies of one account are behind and still being argued for.
   *
   * <p>
   * Deliberately not {@link #owed(long)}: that one counts every row, the
   * abandoned ones included, which is the right number for a report about the
   * store and the wrong one for a sentence telling somebody to wait.
   *
   * @param userIdentityId whose calendar the copies sit in
   * @param maxAttempts how many refusals are argued with before stopping
   * @return the count, zero on an account whose copies all landed
   */
  public long owedAndStillTrying(long userIdentityId, int maxAttempts) {
    return pendingPushDAO.countAttemptable(userIdentityId, maxAttempts);
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
