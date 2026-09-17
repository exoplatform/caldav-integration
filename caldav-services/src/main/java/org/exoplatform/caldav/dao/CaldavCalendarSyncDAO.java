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
   * The users whose bindings are due, oldest first.
   *
   * The sweep works account by account, so this is the set it must page
   * through. Paging the BINDINGS instead let one user's collections fill a
   * whole batch — a user with forty of them, all stale after an outage,
   * occupied every run and no other account was ever reached. Grouping puts
   * one row per user in the page, so a batch of ten is ten users however many
   * collections each of them holds.
   *
   * Ordered by each user's oldest binding, so the account waiting longest is
   * served first.
   *
   * @param status the binding state that counts as sweepable
   * @param before bindings last synchronised strictly before this instant
   * @param pageable page and sort; required, this set spans every user
   * @return one page of user identities
   */
  @Query("SELECT p.userIdentityId FROM CaldavCalendarSyncEntity p WHERE p.status = :status"
      + " AND (p.lastSyncEnd IS NULL OR p.lastSyncEnd < :before)"
      + " GROUP BY p.userIdentityId ORDER BY MIN(COALESCE(p.lastSyncEnd, {d '1970-01-01'})) ASC")
  Page<Long> findDueAccounts(@Param("status") CalendarSyncStatus status,
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

  /**
   * Whether any user of this deployment holds a pair of one origin on one
   * server for one calendar anchor.
   *
   * <p>
   * Asked with {@link SyncOrigin#EXO} to decide whether a collection under
   * the outbound prefix was minted by <em>this</em> deployment (EXO-90226):
   * the slug eXo mints carries the calendar's anchor, and an EXO pair on the
   * server for that anchor means the calendar behind the collection exists
   * here. Deliberately spanning every user — the pair that answers may be
   * another user's on a shared account — and every status: a paused or
   * tombstoned pair still names a calendar this deployment made.
   *
   * <p>
   * Keyed on the anchor rather than on the href, because the server may
   * report an eXo-made collection under a path other than the one it was
   * created at (BlueMind republishes them under another parent), and the
   * anchor is the part of the path that survives that. Its sibling
   * {@link #existsByServerIdAndOriginAndRemoteHref} answers by the recorded
   * path instead, for the shape where the slug is the part that changed.
   *
   * <p>
   * What it costs: <b>no index serves this query</b>. The table's two indexes
   * are {@code UQ_CALDAV_CALENDAR_SYNC_LOCAL (USER_IDENTITY_ID, SERVER_ID,
   * LOCAL_CALENDAR_SYNC_UID)} and {@code IDX_CALDAV_CALENDAR_SYNC_STATUS
   * (STATUS, LAST_SYNC_END)}; neither leads with the server, and {@code
   * ORIGIN} is in no index at all. With the leading column unconstrained the
   * unique index cannot be entered as a range, so the engine walks every row
   * of the table, or scans the whole index and looks each candidate up for
   * its origin — O(table) either way. Kept off the common path by its
   * callers: the sweep tries the user's own pairs in memory before asking,
   * and the question is never asked for a collection outside the outbound
   * prefix, so only a prefixed collection this user does not hold costs a
   * walk. Since EXO-90234 the calendar list asks the same way on each
   * listing, through the same classification — per colleague's share, not
   * per collection, since the user's own pairs still answer first — so a
   * deployment with many shares per user is where the index below stops
   * being optional. An index on {@code (SERVER_ID, ORIGIN,
   * LOCAL_CALENDAR_SYNC_UID)} would serve it as a point lookup; adding one
   * is a changeset, and so a separate decision.
   *
   * @param serverId the declared server registration
   * @param origin which side created the collection
   * @param localCalendarSyncUid agenda's immutable calendar anchor
   * @return true when such a pair exists, whoever holds it
   */
  boolean existsByServerIdAndOriginAndLocalCalendarSyncUid(long serverId,
                                                           SyncOrigin origin,
                                                           String localCalendarSyncUid);

  /**
   * Whether any user of this deployment holds a pair of one origin on one
   * server recorded at one collection path.
   *
   * <p>
   * The second arm of the ownership question (EXO-90226), asked with
   * {@link SyncOrigin#EXO} when the anchor arm above found nothing. A server
   * may republish an eXo-made collection under a slug that is not the anchor
   * eXo minted — prefix kept, suffix replaced, the shape EXO-89590 recorded
   * against BlueMind — and then the slug names no calendar here while the
   * collection is still one this deployment made. The path a pair records is
   * what answers that shape, whenever the record holds the published path.
   * Like its sibling, deliberately every user and every status.
   *
   * <p>
   * The href is compared as stored, which is canonical (the storage
   * canonicalises on save); the caller canonicalises what it asks with. No
   * index can serve this one either, and none could be added: the href
   * column is too long to index on MySQL under utf8mb4, which is why the
   * unique constraint is carried by the anchor (changeset 1.0.0-5). So this
   * is a walk of the table, asked only after the anchor arm has missed —
   * once per pass for a prefixed collection this deployment holds no anchor
   * for, and, since EXO-90234, once per calendar listing for the same
   * collection, the list classifying it the way the sweep does.
   *
   * @param serverId the declared server registration
   * @param origin which side created the collection
   * @param remoteHref the collection path, canonical
   * @return true when such a pair exists, whoever holds it
   */
  boolean existsByServerIdAndOriginAndRemoteHref(long serverId, SyncOrigin origin, String remoteHref);

  /**
   * Whether any user of this deployment holds a calendar binding on one
   * server for a collection whose path ends with one segment.
   *
   * <p>
   * The third arm of the ownership question (EXO-90347), asked with
   * {@link SyncOrigin#MIRROR} as the origin to leave out, once the server's
   * own listing has named another owner for an eXo-shaped collection the two
   * arms above did not recognise. On BlueMind one container is listed under
   * <em>each</em> subscriber's home — the colleague's pair records it under
   * hers, the sharee's listing spells it under his — so neither the anchor
   * (the pair is REMOTE: a calendar she imported, whose slug is not her
   * anchor) nor the recorded path answers, while the container uid, the last
   * segment, is the same on both. A binding of any origin but the mirror
   * ledger and of any status: a colleague who has since deleted her calendar
   * in eXo still holds a tombstone naming the collection as hers.
   *
   * <p>
   * A {@code LIKE '%/<uid>'} the href column cannot be indexed for, as its
   * siblings' notes say; asked only after both other arms have missed and
   * the server has named another owner — a colleague's share, at most once
   * per such share per pass.
   *
   * @param serverId the declared server registration
   * @param origin the origin to leave out, the mirror ledger's
   * @param suffix a slash and the container uid, as the canonical path ends
   * @return true when such a binding exists, whoever holds it
   */
  boolean existsByServerIdAndOriginNotAndRemoteHrefEndingWith(long serverId, SyncOrigin origin, String suffix);

  /**
   * The pairs of one origin on one server for one calendar anchor, the one
   * to name first first.
   *
   * <p>
   * The <em>who</em> form of
   * {@link #existsByServerIdAndOriginAndLocalCalendarSyncUid}, asked with
   * {@link SyncOrigin#EXO} once a listed collection is known to be a
   * colleague's eXo calendar and the calendar list wants to say whose
   * (EXO-90237): the pair's user is the owner. Same predicate, same span —
   * every user, every status — and the same cost, a walk no index serves
   * (see the sibling's note); asked once per colleague's share per listing,
   * after the existence question has already said there is one to find.
   *
   * <p>
   * Ordered so that the first row is the one to believe, should there ever
   * be more than one: the unique index {@code (USER_IDENTITY_ID, SERVER_ID,
   * LOCAL_CALENDAR_SYNC_UID)} leaves two <em>users</em> free to record the
   * same anchor on the same server — a database restored and re-pointed, a
   * calendar whose rows were copied between accounts — and a listing that
   * named a different owner on each refresh would be worse than one that is
   * wrong the same way twice. A pair in the {@code preferred} state (active,
   * from the caller) is a calendar still exported, so its user is the
   * likelier owner; among equals the oldest row, by identifier, is the one
   * that has been true longest. The caller passes a page of one.
   *
   * @param serverId the declared server registration
   * @param origin which side created the collection
   * @param localCalendarSyncUid agenda's immutable calendar anchor
   * @param preferred the state a pair should be in to be named first
   * @param pageable how many to name; a page of one in practice
   * @return the matching pairs, preferred state first, then oldest first
   */
  @Query("SELECT p FROM CaldavCalendarSyncEntity p"
      + " WHERE p.serverId = :serverId AND p.origin = :origin AND p.localCalendarSyncUid = :localCalendarSyncUid"
      + " ORDER BY CASE WHEN p.status = :preferred THEN 0 ELSE 1 END, p.id ASC")
  List<CaldavCalendarSyncEntity> findPairsByAnchorPreferring(@Param("serverId") long serverId,
                                                                          @Param("origin") SyncOrigin origin,
                                                                          @Param("localCalendarSyncUid") String localCalendarSyncUid,
                                                                          @Param("preferred") CalendarSyncStatus preferred,
                                                                          Pageable pageable);

  /**
   * The pairs of one origin on one server recorded at one collection path,
   * the one to name first first.
   *
   * <p>
   * The <em>who</em> form of {@link #existsByServerIdAndOriginAndRemoteHref},
   * for the shape where the slug is not the anchor and the recorded path is
   * what recognised the collection (EXO-90237). Same span, same cost — a
   * walk, since the href column cannot be indexed — and the same ordering
   * rule as {@link #findPairsByAnchorPreferring}, for the same
   * reason: the path is unique to nobody in the schema, and the owner named
   * must not depend on row order.
   *
   * @param serverId the declared server registration
   * @param origin which side created the collection
   * @param remoteHref the collection path, canonical
   * @param preferred the state a pair should be in to be named first
   * @param pageable how many to name; a page of one in practice
   * @return the matching pairs, preferred state first, then oldest first
   */
  @Query("SELECT p FROM CaldavCalendarSyncEntity p"
      + " WHERE p.serverId = :serverId AND p.origin = :origin AND p.remoteHref = :remoteHref"
      + " ORDER BY CASE WHEN p.status = :preferred THEN 0 ELSE 1 END, p.id ASC")
  List<CaldavCalendarSyncEntity> findPairsByRemoteHrefPreferring(@Param("serverId") long serverId,
                                                                              @Param("origin") SyncOrigin origin,
                                                                              @Param("remoteHref") String remoteHref,
                                                                              @Param("preferred") CalendarSyncStatus preferred,
                                                                              Pageable pageable);

}
