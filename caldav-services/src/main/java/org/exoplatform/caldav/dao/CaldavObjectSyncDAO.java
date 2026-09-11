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
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.caldav.entity.CaldavObjectSyncEntity;
import org.exoplatform.caldav.model.SyncOrigin;

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
   * Every mapping of one eXo event, across the collections of every user.
   *
   * <p>
   * The query the propagation of an edit is driven from: the rows it answers
   * <b>are</b> the set of people who already hold a copy of that meeting, which
   * is the only set an edit is allowed to write to. Asked of the mapping table
   * rather than of agenda's attendee list on purpose — an attendee without a
   * copy must not acquire one from an edit, and a meeting destroyed in eXo has
   * no attendee list left to ask.
   *
   * <p>
   * Paginated like every other listing here: the row count is the number of
   * attendees who connected a calendar account, which is the users' number to
   * decide and not the developer's.
   *
   * @param localEventId the eXo event
   * @param pageable page and sort; required
   * @return one page of mappings
   */
  Page<CaldavObjectSyncEntity> findByLocalEventId(long localEventId, Pageable pageable);

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
   * Which of these eXo events this user already holds a copy of.
   *
   * Scoped through the pair to its owner, and that scope is the whole point:
   * a mapping says "somebody has a copy of this event", and every attendee of
   * a meeting needs one of their own. Asking the unscoped question in a
   * per-user pass meant the first attendee to be copied answered for all the
   * others, who were then skipped and never got their copy.
   *
   * @param userIdentityId the user whose copies are asked about
   * @param localEventIds the eXo events to ask about
   * @return the identifiers this user already has a copy of
   */
  @Query("SELECT DISTINCT o.localEventId FROM CaldavObjectSyncEntity o, CaldavCalendarSyncEntity p"
      + " WHERE o.calendarSyncId = p.id AND p.userIdentityId = :userIdentityId"
      + " AND o.localEventId IN :localEventIds")
  List<Long> findMappedLocalEventIdsOfUser(@Param("userIdentityId") long userIdentityId,
                                           @Param("localEventIds") Collection<Long> localEventIds);

  /**
   * How many pairs of one origin on one <em>account</em> — whoever holds
   * them — already map this iCalendar UID.
   *
   * <p>
   * The ownership question the inbound half asks before importing an object:
   * is this a copy eXo wrote? A MIRROR pair is an eXo copy by construction,
   * whoever owns it, and the mapping table says so for every user at once —
   * which is what the pair-scoped and then the user-scoped versions of this
   * question could not see, and why two eXo users on one CalDAV account
   * imported each other's copies as genuine remote events every sweep
   * (EXO-90190).
   *
   * <p>
   * <b>Scoped to the account, not to the server registration.</b> The first
   * widening went one level too far: asked for every pair of the server, it
   * answered "eXo's" about a UID that another user's mirror maps on a
   * <em>different</em> account of the same server. That is reachable — an
   * externally organised meeting keeps the organiser's UID as its remote
   * identity, so once user A moves such an event onto a space calendar, A's
   * mirror maps an externally issued UID; user C, invited by the same
   * organiser and connected with their own account on the same server, then
   * finds their own copy "owned" and never sees the meeting in eXo. Copies
   * live in accounts: every pair this connector holds sits directly under its
   * account's calendar home (the listing is depth one, and eXo mints its own
   * collections there), so the home is the account's identity and the
   * predicate is a prefix on the pair's href. The server predicate stays as
   * the cheap first cut; the prefix is what decides.
   *
   * <p>
   * <b>No status predicate, on purpose.</b> Disconnecting only pauses a pair
   * and deletes neither its rows nor the copies on the server, so a paused
   * mirror's rows still describe objects that are physically in the account
   * and still eXo's; restricting the question to active pairs would import
   * those as genuine remote events for whoever else reads the account — the
   * defect back, for departed users. The rows go when the copies go, which is
   * the cleanup's job, not this predicate's.
   *
   * <p>
   * A count rather than the rows, for the reason every read here shares: the
   * caller only ever asks whether the object is ours, and the row it would be
   * handed belongs to another pair — of another user, now — so returning it
   * would invite writing to a mapping this pair does not own.
   *
   * <p>
   * The prefix is the caller's, wildcards already escaped with {@code !};
   * the repository only says which character escapes.
   *
   * @param serverId the declared server registration
   * @param origin which side created the collections that count as owning
   * @param icsUid the iCalendar UID looked for
   * @param homePrefix a LIKE pattern for the account's calendar home, ending
   *          in {@code /%}
   * @return how many mappings of that origin under that home carry the UID,
   *         zero when none do
   */
  @Query("SELECT COUNT(o) FROM CaldavObjectSyncEntity o, CaldavCalendarSyncEntity p"
      + " WHERE o.calendarSyncId = p.id"
      + " AND p.serverId = :serverId AND p.origin = :origin AND o.icsUid = :icsUid"
      + " AND p.remoteHref LIKE :homePrefix ESCAPE '!'")
  long countByHomeAndOriginAndIcsUid(@Param("serverId") long serverId,
                                     @Param("origin") SyncOrigin origin,
                                     @Param("icsUid") String icsUid,
                                     @Param("homePrefix") String homePrefix);

  /**
   * How many pairs of one origin on one account, held by a user <em>other</em>
   * than this one, already map this iCalendar UID.
   *
   * <p>
   * The outbound half's lock (EXO-90190). Two pairs on one collection derive
   * the same href from a UID, so a personal-calendar write of an object that
   * another user's mirror maps would overwrite that user's copy in place. The
   * push asks this before the PUT and refuses. Scoped to the account for the
   * reason {@link #countByHomeAndOriginAndIcsUid} gives — asked for the whole
   * server, it refused a user's push of their own event into their own
   * collection because another account of the server mirrored the same
   * externally issued UID. A count and never a row, and no status predicate,
   * for the reasons given there too.
   *
   * @param userIdentityId the user about to write, whose own pairs do not count
   * @param serverId the declared server registration
   * @param origin which side created the collections that count as owning
   * @param icsUid the iCalendar UID about to be written
   * @param homePrefix a LIKE pattern for the account's calendar home, ending
   *          in {@code /%}, wildcards escaped with {@code !}
   * @return how many other users' mappings of that origin under that home
   *         carry the UID
   */
  @Query("SELECT COUNT(o) FROM CaldavObjectSyncEntity o, CaldavCalendarSyncEntity p"
      + " WHERE o.calendarSyncId = p.id AND p.userIdentityId <> :userIdentityId"
      + " AND p.serverId = :serverId AND p.origin = :origin AND o.icsUid = :icsUid"
      + " AND p.remoteHref LIKE :homePrefix ESCAPE '!'")
  long countByOtherOwnerAndHomeAndOriginAndIcsUid(@Param("userIdentityId") long userIdentityId,
                                                  @Param("serverId") long serverId,
                                                  @Param("origin") SyncOrigin origin,
                                                  @Param("icsUid") String icsUid,
                                                  @Param("homePrefix") String homePrefix);

  /**
   * Which eXo events this user's pairs of one origin, on one server, hold that
   * iCalendar UID for.
   *
   * <p>
   * The question above answers "did eXo write this object?"; this one answers
   * "and what did it write it for?" (EXO-89807). The inbound half needs the
   * second because the owner's answer arrives on an object it is about to drop
   * as one of eXo's own, and an answer can only be recorded against the event
   * the copy stands for — which lives on a <em>different</em> pair from the one
   * doing the reading, and so is out of reach of every pair-scoped lookup here.
   *
   * <p>
   * <b>Still scoped to the user, on purpose, while the ownership count is not
   * (EXO-90190).</b> The two questions look alike and must not be made alike.
   * This one feeds the adoption of the owner's answer: the PARTSTAT read off
   * the copy is recorded as the <em>reading</em> user's response to the event
   * it names. Asked for the deployment, it would name the event behind another
   * user's copy on a shared account, and the reading user's pass would record
   * that other user's phone answer as its own. On a foreign copy this answers
   * nothing, no answer is read, and that is right: the copy's owner reads their
   * own answer on their own pass. Anyone widening this "for consistency" is
   * reintroducing exactly that.
   *
   * <p>
   * The event identifiers and nothing else, deliberately, for the reason the
   * count above records: the caller must not be handed a mapping row belonging
   * to another pair, because a row it holds is a row it can write to. An
   * identifier is a fact it can only read.
   *
   * @param userIdentityId the user whose pairs are asked about
   * @param serverId the declared server registration
   * @param origin which side created the collections that count as owning
   * @param icsUid the iCalendar UID looked for
   * @return the events those mappings name, empty when the UID is not ours
   */
  @Query("SELECT DISTINCT o.localEventId FROM CaldavObjectSyncEntity o, CaldavCalendarSyncEntity p"
      + " WHERE o.calendarSyncId = p.id AND p.userIdentityId = :userIdentityId"
      + " AND p.serverId = :serverId AND p.origin = :origin AND o.icsUid = :icsUid"
      + " AND o.localEventId IS NOT NULL")
  List<Long> findEventIdsByOwnerAndOriginAndIcsUid(@Param("userIdentityId") long userIdentityId,
                                                   @Param("serverId") long serverId,
                                                   @Param("origin") SyncOrigin origin,
                                                   @Param("icsUid") String icsUid);

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
