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

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Date;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.caldav.dao.CaldavCalendarSyncDAO;
import org.exoplatform.caldav.dao.CaldavObjectSyncDAO;
import org.exoplatform.caldav.entity.CaldavCalendarSyncEntity;
import org.exoplatform.caldav.entity.CaldavObjectSyncEntity;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;

/**
 * Maps calendar pairs and their object mappings between their JPA entities and
 * the service-layer DTOs, and is the single place hrefs are made canonical.
 *
 * <p>
 * No business logic: what a pair means, when it may be created, whether a
 * deletion propagates — all of that belongs to the service. What lives here is
 * the mechanical part the service should not have to remember, above all that
 * an href is stored decoded and without its trailing slash. A binding that
 * compared raw hrefs would lose its pair the first time a server answered a
 * differently-escaped one, and that is not a decision, it is an invariant.
 */
@Component
public class CaldavSyncStorage {

  /** The relay's own root, which hrefs stored by the browser carry. */
  private static final java.util.regex.Pattern RELAY_PREFIX =
                                                            java.util.regex.Pattern.compile("^/caldav/rest/dav/\\d+");


  /**
   * How many other users the shared-account question names at most: enough
   * to list every member of a shared team account, and a bound on a scan of
   * the href column that would otherwise list a deployment.
   */
  static final int              OTHER_USERS_NAMED = 20;

  @Autowired
  private CaldavCalendarSyncDAO calendarSyncDAO;

  @Autowired
  private CaldavObjectSyncDAO   objectSyncDAO;

  /**
   * Every pair a user holds on one server.
   *
   * @param userIdentityId identity of the user
   * @param serverId declared server registration
   * @return the user's pairs on that server
   */
  public List<CalendarSync> getPairs(long userIdentityId, long serverId) {
    return calendarSyncDAO.findByUserIdentityIdAndServerId(userIdentityId, serverId).stream().map(this::fromEntity).toList();
  }

  /**
   * The pair bound to one local calendar.
   *
   * @param userIdentityId identity of the user
   * @param serverId declared server registration
   * @param localCalendarSyncUid agenda's immutable calendar anchor
   * @return the pair, or null when the calendar is not bound
   */
  public CalendarSync getPairByLocalCalendar(long userIdentityId, long serverId, String localCalendarSyncUid) {
    return calendarSyncDAO.findByUserIdentityIdAndServerIdAndLocalCalendarSyncUid(userIdentityId,
                                                                                  serverId,
                                                                                  localCalendarSyncUid)
                          .map(this::fromEntity)
                          .orElse(null);
  }

  /**
   * The pair bound to one remote collection, matched on the canonical path.
   *
   * <p>
   * Resolved in memory over the user's own pairs rather than by a query: the
   * href column is too long to index on MySQL under utf8mb4, and a person's
   * pairs are few. Querying it would have meant either a scan or a schema
   * contortion, for a set that fits in a handful of rows.
   *
   * @param userIdentityId identity of the user
   * @param serverId declared server registration
   * @param remoteHref the collection href, in any spelling
   * @return the pair, or null when the collection is not bound
   */
  public CalendarSync getPairByRemoteHref(long userIdentityId, long serverId, String remoteHref) {
    String canonical = canonicalHref(remoteHref);
    if (StringUtils.isBlank(canonical)) {
      return null;
    }
    return calendarSyncDAO.findByUserIdentityIdAndServerId(userIdentityId, serverId)
                          .stream()
                          .filter(entity -> canonical.equals(entity.getRemoteHref()))
                          .findFirst()
                          .map(this::fromEntity)
                          .orElse(null);
  }

  /**
   * The pairs of one origin a user holds on a server. A list even for
   * {@link SyncOrigin#MIRROR}, which should be single: the database cannot
   * enforce that uniqueness, so the caller is given what is actually there
   * rather than the first of several.
   *
   * @param userIdentityId identity of the user
   * @param serverId declared server registration
   * @param origin which side created the collection
   * @return the matching pairs
   */
  public List<CalendarSync> getPairsByOrigin(long userIdentityId, long serverId, SyncOrigin origin) {
    return calendarSyncDAO.findByUserIdentityIdAndServerIdAndOrigin(userIdentityId, serverId, origin)
                          .stream()
                          .map(this::fromEntity)
                          .toList();
  }

  /**
   * One page of pairs in a given state whose last synchronisation ended before
   * a cutoff, or has never ended.
   *
   * @param status the state to select
   * @param before pairs last synchronised strictly before this instant
   * @param offset page offset, in pages
   * @param limit page size
   * @return one page of due pairs, oldest synchronisation first
   */
  public Page<CalendarSync> getDuePairs(CalendarSyncStatus status, Date before, int offset, int limit) {
    Pageable pageable = PageRequest.of(offset, limit, Sort.by(Sort.Direction.ASC, "lastSyncEnd"));
    return calendarSyncDAO.findDue(status, before, pageable).map(this::fromEntity);
  }

  /**
   * The users whose bindings are due, oldest waiting first.
   *
   * The account-wise form of {@link #getDuePairs}, and what the sweep asks:
   * batching bindings let one user's collections fill a whole run, so no
   * other account was reached at all.
   *
   * @param status the binding state that counts as sweepable
   * @param before bindings last synchronised strictly before this instant
   * @param offset page index
   * @param limit how many users one page carries
   * @return one page of user identities
   */
  public Page<Long> getDueAccounts(CalendarSyncStatus status, Date before, int offset, int limit) {
    return calendarSyncDAO.findDueAccounts(status, before, PageRequest.of(offset, limit));
  }

  /**
   * One binding by its identifier, whoever it belongs to.
   *
   * <p>
   * Returns it without checking ownership on purpose: the check belongs to the
   * caller that knows who is asking, and hiding it here would make a service
   * look safe while the storage quietly decided for it.
   *
   * @param id the binding's identifier
   * @return the binding, or null when there is none
   */
  public CalendarSync getPair(long id) {
    return calendarSyncDAO.findById(id).map(this::fromEntity).orElse(null);
  }

  /**
   * Creates or updates a pair, canonicalising its href on the way in.
   *
   * @param pair the pair to persist
   * @return the persisted pair, carrying its identifier
   */
  @Transactional
  public CalendarSync savePair(CalendarSync pair) {
    CaldavCalendarSyncEntity entity = toEntity(pair);
    return fromEntity(calendarSyncDAO.save(entity));
  }

  /**
   * Removes a pair and, by the foreign key, its object mappings.
   *
   * @param id technical identifier of the pair
   */
  @Transactional
  public void deletePair(long id) {
    calendarSyncDAO.deleteById(id);
  }

  /**
   * The mapping for one iCalendar object inside a pair.
   *
   * @param calendarSyncId the pair
   * @param icsUid the iCalendar UID
   * @return the mapping, or null when the object is unknown
   */
  public ObjectSync getObjectByUid(long calendarSyncId, String icsUid) {
    return objectSyncDAO.findByCalendarSyncIdAndIcsUid(calendarSyncId, icsUid).map(this::fromEntity).orElse(null);
  }

  /**
   * Whether this iCalendar object is a copy eXo itself wrote into the user's
   * mirror.
   *
   * <p>
   * Ownership expressed per object rather than per collection. Until now a
   * mirror copy was protected by <em>where</em> it lived — the inbound sweep
   * skipped the dedicated collection wholesale — so pointing the mirror at a
   * calendar the inbound half also reads would have removed the protection
   * entirely, and eXo would have imported its own copies back as duplicate
   * personal events.
   *
   * <p>
   * The mapping table is the authority because its row is saved in the same
   * flow as the PUT that created the object: by the time any inbound pass can
   * see the object, the row exists. The one window it does not cover is an
   * interruption between the two — the PUT went through and the row was never
   * saved — which leaves an unowned copy the next push reconciles.
   *
   * <p>
   * Asked for the account and not for one user (EXO-90190): a CalDAV account
   * can be connected by several eXo users, and a copy one of them wrote into
   * it is still eXo's. And for the account rather than the whole server
   * registration, because a copy is a fact about the account it sits in — the
   * same UID mirrored on another account of the server says nothing about
   * this one, and calling it "ours" there drops a third user's genuine
   * meeting. The account is named by the calendar home the collection sits
   * under, derived from the collection's own href by
   * {@link #calendarHomeOf(String)}.
   *
   * @param serverId the declared server registration
   * @param collectionHref the href of the collection being read, in any
   *          spelling; the account is the calendar home it sits under
   * @param icsUid the iCalendar UID being imported
   * @return true when a mirror pair of any user on that account already maps
   *         that UID; false when the href names no home to ask about
   */
  public boolean isMirrorOwned(long serverId, String collectionHref, String icsUid) {
    String homePrefix = homePrefixOf(collectionHref);
    if (StringUtils.isBlank(icsUid) || homePrefix == null) {
      return false;
    }
    return objectSyncDAO.countByHomeAndOriginAndIcsUid(serverId, SyncOrigin.MIRROR, icsUid, homePrefix) > 0;
  }

  /**
   * Whether this iCalendar object is a copy eXo wrote into the mirror of a
   * <em>different</em> user on this account.
   *
   * <p>
   * What the outbound half asks before writing a personal-calendar object
   * (EXO-90190): two users' pairs on one collection compute the same href for
   * one UID, so the write would land on the other user's copy and replace it.
   * The user's own mirror is excluded because a UID it maps is that user's
   * own copy, which a write of theirs may legitimately move or rewrite. Scoped
   * to the account the collection sits in, like
   * {@link #isMirrorOwned(long, String, String)}: a mirror on another account
   * of the server holds no copy this write could reach, and refusing for it
   * kept a user from writing their own event into their own collection.
   *
   * @param userIdentityId identity of the user about to write
   * @param serverId the declared server registration
   * @param collectionHref the href of the collection about to be written
   *          into, in any spelling
   * @param icsUid the iCalendar UID about to be written
   * @return true when another user's mirror pair on that account maps that
   *         UID; false when the href names no home to ask about
   */
  public boolean isMirrorOwnedByAnotherUser(long userIdentityId, long serverId, String collectionHref, String icsUid) {
    String homePrefix = homePrefixOf(collectionHref);
    if (StringUtils.isBlank(icsUid) || homePrefix == null) {
      return false;
    }
    return objectSyncDAO.countByOtherOwnerAndHomeAndOriginAndIcsUid(userIdentityId,
                                                                    serverId,
                                                                    SyncOrigin.MIRROR,
                                                                    icsUid,
                                                                    homePrefix) > 0;
  }

  /**
   * The other users whose active pairs on this server live under one calendar
   * home — the users who connected the same account.
   *
   * <p>
   * The home is made canonical the way every stored href is, so the prefix
   * compares against what the rows hold, and the LIKE pattern's own wildcards
   * are escaped: an account path may carry an underscore, and unescaped it
   * would match any character. Capped at {@link #OTHER_USERS_NAMED}: the
   * warning this feeds names who else is on the account, and a bound keeps a
   * scan of the href column from listing a whole deployment.
   *
   * @param userIdentityId identity of the user asking, who does not count
   * @param serverId the declared server registration
   * @param calendarHome the account's calendar home, in any spelling
   * @return the other users' identities, at most {@link #OTHER_USERS_NAMED},
   *         empty when nobody else is under it
   */
  public List<Long> getOtherUsersUnderCalendarHome(long userIdentityId, long serverId, String calendarHome) {
    String canonical = canonicalHref(calendarHome);
    if (StringUtils.isBlank(canonical)) {
      return List.of();
    }
    return calendarSyncDAO.findOtherUsersUnderHref(userIdentityId,
                                                   serverId,
                                                   CalendarSyncStatus.ACTIVE,
                                                   homePrefix(canonical),
                                                   PageRequest.of(0, OTHER_USERS_NAMED));
  }

  /**
   * Whether a calendar of this deployment, anyone's, is exported to one
   * server under one anchor.
   *
   * <p>
   * The account-scoped ownership question for calendar pairs (EXO-90226),
   * the sibling of the per-object one {@link #isMirrorOwned} asks for copies.
   * An ORIGIN=EXO pair on the server for the anchor means the collection whose
   * slug carries that anchor was minted by this deployment for a calendar that
   * exists here — whichever user's, and whatever state the pair is in now.
   * No such pair, and the collection was minted by another eXo deployment
   * writing into the same account, which is a calendar this one has never
   * seen.
   *
   * @param serverId the declared server registration
   * @param anchor the calendar anchor the collection's slug carries
   * @return true when a user of this deployment holds an EXO pair for it;
   *         false when the anchor is blank, which names no calendar
   */
  public boolean isExoCalendarOnServer(long serverId, String anchor) {
    if (StringUtils.isBlank(anchor)) {
      return false;
    }
    return calendarSyncDAO.existsByServerIdAndOriginAndLocalCalendarSyncUid(serverId, SyncOrigin.EXO, anchor);
  }

  /**
   * Whether a calendar of this deployment, anyone's, is exported to one
   * server at one collection path.
   *
   * <p>
   * The other arm of the same question (EXO-90226), by the recorded path
   * rather than the anchor: for the collection a server republishes under a
   * slug that is not the anchor eXo minted, which {@link
   * #isExoCalendarOnServer} cannot recognise. Matched on the canonical path,
   * which is how the href is stored, so the spelling the listing uses — host,
   * percent-encoding, trailing slash — does not decide the answer. Every user
   * and every status, as for the anchor.
   *
   * @param serverId the declared server registration
   * @param href the collection path, in any spelling
   * @return true when a user of this deployment holds an EXO pair recorded
   *         there; false when the href is blank, which names no collection
   */
  public boolean isExoCollectionOnServer(long serverId, String href) {
    String canonical = canonicalHref(href);
    if (StringUtils.isBlank(canonical)) {
      return false;
    }
    return calendarSyncDAO.existsByServerIdAndOriginAndRemoteHref(serverId, SyncOrigin.EXO, canonical);
  }

  /**
   * The calendar home a collection sits under: its canonical href without the
   * last segment.
   *
   * <p>
   * The account's identity, as far as the mapping table can tell it
   * (EXO-90190). Nothing stores the home a pair was listed from, and nothing
   * needs to: the listing that produces every remote pair is a depth-one
   * PROPFIND of the home, and the collections eXo mints — the mirror, the
   * outbound copies of the user's own calendars — are created directly under
   * it. So the parent of a pair's href is its home on every server this
   * connector has met, and two pairs share an account exactly when they share
   * a parent. A server that nested calendars below the home would break that
   * reading, in the safe direction: the question would find no mirror and
   * answer "not ours".
   *
   * @param href a collection href, in any spelling
   * @return the canonical home, or null when the href has no parent to name
   *         one — blank, a bare segment, or a child of the root
   */
  public static String calendarHomeOf(String href) {
    String canonical = canonicalHref(href);
    if (StringUtils.isBlank(canonical) || !canonical.contains("/")) {
      return null;
    }
    String home = StringUtils.substringBeforeLast(canonical, "/");
    return StringUtils.isBlank(home) ? null : home;
  }

  /**
   * The LIKE pattern matching every collection of the account a collection
   * belongs to, or null when the href names no account.
   *
   * @param collectionHref a collection href, in any spelling
   * @return the escaped pattern for {@code home/%}, or null
   */
  public static String homePrefixOf(String collectionHref) {
    String home = calendarHomeOf(collectionHref);
    return home == null ? null : homePrefix(home);
  }

  /**
   * The LIKE pattern matching every path under one canonical calendar home.
   *
   * @param canonicalHome the home, canonical
   * @return the escaped pattern for {@code home/%}
   */
  static String homePrefix(String canonicalHome) {
    return likePrefix(canonicalHome + "/");
  }

  /**
   * A LIKE pattern matching every path under one prefix, with the pattern's
   * own wildcards escaped by {@code !} — the escape character the DAO queries
   * declare.
   *
   * @param prefix the literal path prefix
   * @return the pattern
   */
  static String likePrefix(String prefix) {
    return prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  /**
   * Whether a persistence failure is the database refusing a row for an
   * integrity constraint — a duplicate key above all.
   *
   * <p>
   * Judged on the JDBC cause and not on the Spring type, because the type is
   * not stable on this platform (EXO-90190). The Kernel hands Spring its own
   * {@code EntityManagerFactory} as a plain bean — not an
   * {@code EntityManagerFactoryInfo} — so the {@code JpaTransactionManager}
   * runs {@code DefaultJpaDialect}, which turns every unrecognised
   * {@code PersistenceException} into a {@code JpaSystemException} at commit;
   * and no {@code PersistenceExceptionTranslator} bean exists for the
   * repository proxy to consult, so a failure raised inside the repository
   * call — an insert executed at persist time, as identity columns do on
   * MySQL — arrives as Hibernate's own {@code ConstraintViolationException},
   * a {@code PersistenceException} and no {@code DataAccessException} at all.
   * Only a Boot-managed factory, the test slice's, yields the
   * {@code DataIntegrityViolationException} a reader would expect. What every
   * shape shares is the {@code SQLException} in its cause chain: the JDBC
   * standard class {@code 23} of its SQLState is an integrity violation on
   * every driver, and MySQL's and HSQLDB's drivers also type it as
   * {@code SQLIntegrityConstraintViolationException}; PostgreSQL's does not,
   * which is why the SQLState is asked too. The deepest {@code SQLException}
   * is the one read: HSQLDB hangs its own internal exception below it.
   *
   * @param failure the exception a save surfaced
   * @return true when an integrity-constraint violation is in its cause chain
   */
  public static boolean isDuplicateKey(RuntimeException failure) {
    SQLException sql = null;
    Throwable cause = failure;
    for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
      if (cause instanceof SQLException candidate) {
        sql = candidate;
      }
    }
    if (sql == null) {
      return false;
    }
    return sql instanceof SQLIntegrityConstraintViolationException || StringUtils.startsWith(sql.getSQLState(), "23");
  }

  /**
   * The eXo event a copy eXo wrote into this user's mirror stands for.
   *
   * <p>
   * The companion of {@link #isMirrorOwned(long, String, String)} and asked
   * in the same breath (EXO-89807): the inbound half recognises one of eXo's own
   * copies and drops it, but the owner's answer is written on that copy and has
   * to be recorded against something. The mapping that knows which event lives
   * on the MIRROR pair, and the pair reading the collection is a different one,
   * so nothing pair-scoped can answer this.
   *
   * <p>
   * <b>Scoped to the user while its companion is not, and the difference is
   * load-bearing (EXO-90190).</b> An answer read off a copy is recorded as the
   * reading user's. On an account two users share, the companion says "eXo's"
   * of the other user's copy too — rightly, so it is not imported — but the
   * event it names is the other user's to answer for. Asked here for the
   * deployment, user six would record user one's phone answer as their own.
   * On a foreign copy this answers null, nothing is recorded, and that is the
   * behaviour: the copy's owner reads their own answer on their own pass.
   *
   * <p>
   * Two mirror pairs holding the same UID is not a state this connector
   * creates — a user has one mirror per server — and if it ever arises, the
   * copies stand for different events and no answer can be attributed to either
   * without guessing. Ambiguity therefore answers "no event", the same as
   * "never pushed": nothing is recorded rather than something recorded against
   * the wrong meeting.
   *
   * @param userIdentityId identity of the user whose copies are asked about
   * @param serverId the declared server registration
   * @param icsUid the iCalendar UID being read
   * @return the event the copy stands for, or null when it is not ours, names
   *         no event, or names more than one
   */
  public Long getMirrorEventId(long userIdentityId, long serverId, String icsUid) {
    if (StringUtils.isBlank(icsUid)) {
      return null;
    }
    List<Long> events = objectSyncDAO.findEventIdsByOwnerAndOriginAndIcsUid(userIdentityId,
                                                                           serverId,
                                                                           SyncOrigin.MIRROR,
                                                                           icsUid);
    if (events == null || events.size() != 1) {
      return null;
    }
    return events.get(0);
  }

  /**
   * The mapping for one eXo event inside a pair.
   *
   * @param calendarSyncId the pair
   * @param localEventId the eXo event
   * @return the mapping, or null when the event has never been pushed
   */
  public ObjectSync getObjectByEvent(long calendarSyncId, long localEventId) {
    return objectSyncDAO.findByCalendarSyncIdAndLocalEventId(calendarSyncId, localEventId).map(this::fromEntity).orElse(null);
  }

  /**
   * One page of a pair's object mappings.
   *
   * @param calendarSyncId the pair
   * @param offset page offset, in pages
   * @param limit page size
   * @return one page of mappings, by identifier
   */
  public Page<ObjectSync> getObjects(long calendarSyncId, int offset, int limit) {
    Pageable pageable = PageRequest.of(offset, limit, Sort.by(Sort.Direction.ASC, "id"));
    return objectSyncDAO.findByCalendarSyncId(calendarSyncId, pageable).map(this::fromEntity);
  }

  /**
   * One page of the mappings of a single eXo event, across every user's
   * collections.
   *
   * <p>
   * The set an edit of that event is allowed to reach — every copy that already
   * exists, and nothing else.
   *
   * @param localEventId the eXo event
   * @param offset page offset, in pages
   * @param limit page size
   * @return one page of mappings, by identifier
   */
  public Page<ObjectSync> getObjectsByEvent(long localEventId, int offset, int limit) {
    Pageable pageable = PageRequest.of(offset, limit, Sort.by(Sort.Direction.ASC, "id"));
    return objectSyncDAO.findByLocalEventId(localEventId, pageable).map(this::fromEntity);
  }

  /**
   * How many objects a pair maps.
   *
   * @param calendarSyncId the pair
   * @return the mapping count
   */
  public long countObjects(long calendarSyncId) {
    return objectSyncDAO.countByCalendarSyncId(calendarSyncId);
  }

  /**
   * Whether an eXo event is already mapped. The question the backfill asks
   * before creating a row, and the reason re-running it creates nothing.
   *
   * @param localEventId the eXo event
   * @return true when a mapping exists
   */
  public boolean isEventMapped(long localEventId) {
    return objectSyncDAO.existsByLocalEventId(localEventId);
  }

  /**
   * Which of these eXo events already carry a copy.
   *
   * The batch form of {@link #isEventMapped(long)}, for callers holding a
   * list: the seeding pass asks about a user's whole upcoming window on every
   * sweep, and asking one event at a time made the steady state — where every
   * one of them is already mapped — cost a query per meeting to learn there
   * was nothing to do.
   *
   * @param userIdentityId the identity whose mappings count, so the copy made
   *          for the first attendee does not answer for every other attendee
   * @param localEventIds the eXo events to ask about
   * @return the identifiers among them that are mapped, empty when none are
   */
  public Set<Long> mappedEventIds(long userIdentityId, Collection<Long> localEventIds) {
    if (localEventIds == null || localEventIds.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(objectSyncDAO.findMappedLocalEventIdsOfUser(userIdentityId, localEventIds));
  }

  /**
   * Which of these eXo events carry a copy for ANYONE.
   *
   * Kept for the callers that genuinely ask the global question; a per-user
   * pass wants {@link #mappedEventIds(long, Collection)} instead, or the
   * first attendee copied answers for every other attendee.
   *
   * @param localEventIds the eXo events to ask about
   * @return the identifiers among them that are mapped
   */
  public Set<Long> mappedEventIds(Collection<Long> localEventIds) {
    if (localEventIds == null || localEventIds.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(objectSyncDAO.findMappedLocalEventIds(localEventIds));
  }

  /**
   * Creates or updates an object mapping, canonicalising its href on the way
   * in.
   *
   * @param object the mapping to persist
   * @return the persisted mapping, carrying its identifier
   */
  @Transactional
  public ObjectSync saveObject(ObjectSync object) {
    return fromEntity(objectSyncDAO.save(toEntity(object)));
  }

  /**
   * Drops every object mapping of a pair, leaving the pair itself in place.
   *
   * @param calendarSyncId the pair
   * @return how many mappings were removed
   */
  @Transactional
  public int deleteObjects(long calendarSyncId) {
    return objectSyncDAO.deleteByCalendarSyncId(calendarSyncId);
  }

  /**
   * Forgets one object mapping.
   *
   * <p>
   * What a mapping with nothing behind it gets: dropping it is how the object
   * becomes importable again, rather than being skipped for ever by a row
   * that describes an event no longer there.
   *
   * @param id the mapping's identifier
   */
  public void deleteObject(long id) {
    objectSyncDAO.deleteById(id);
  }

  /**
   * An href reduced to what identifies the resource: its percent-decoded path,
   * without a trailing slash.
   *
   * <p>
   * The Java counterpart of the browser connector's collectionPath. The same
   * collection is written {@code %40} by one server and {@code @} by a client,
   * reported with and without a trailing slash, and reached through more than
   * one host — none of which makes it a different collection. An href that
   * cannot be parsed is returned trimmed rather than rejected: this is a
   * normalisation, not a validation, and refusing to store an odd href would
   * lose the binding rather than protect it.
   *
   * @param href a collection or object href, absolute or relative
   * @return the canonical path, or the trimmed input when it cannot be parsed
   */
  public static String canonicalHref(String href) {
    if (StringUtils.isBlank(href)) {
      return href;
    }
    String trimmed = href.trim();
    try {
      String path = URI.create(trimmed).getPath();
      if (StringUtils.isBlank(path)) {
        path = trimmed;
      }
      return withoutRelayPrefix(StringUtils.stripEnd(URLDecoder.decode(path, StandardCharsets.UTF_8), "/"));
    } catch (IllegalArgumentException e) {
      return withoutRelayPrefix(StringUtils.stripEnd(trimmed, "/"));
    }
  }

  /**
   * The same path with the relay's own prefix removed.
   *
   * <p>
   * While the browser spoke CalDAV it addressed servers through
   * {@code /caldav/rest/dav/{serverId}}, and hrefs stored then carry that
   * prefix. The server addresses the collection directly. Both name the same
   * collection, and a comparison that treats them as different does not fail
   * loudly — it silently fails to recognise a calendar, which is how a mirror
   * whose copies should be hidden ends up drawn next to the events it copies.
   *
   * @param path a canonical path, possibly rooted in relay space
   * @return the collection's own path
   */
  private static String withoutRelayPrefix(String path) {
    return RELAY_PREFIX.matcher(path).replaceFirst("");
  }

  /**
   * Maps a pair entity onto its DTO.
   *
   * @param entity the persisted pair
   * @return the pair as the service layer handles it
   */
  private CalendarSync fromEntity(CaldavCalendarSyncEntity entity) {
    return new CalendarSync(entity.getId(),
                            entity.getUserIdentityId(),
                            entity.getServerId(),
                            entity.getLocalCalendarSyncUid(),
                            entity.getRemoteHref(),
                            entity.getOrigin(),
                            entity.getSyncToken(),
                            entity.getCtag(),
                            entity.getStatus(),
                            entity.getLastSyncStart(),
                            entity.getLastSyncEnd(),
                            entity.getConsecutiveFailures(),
                            entity.getCopySettingsApplied());
  }

  /**
   * Maps a pair DTO onto its entity, canonicalising the href.
   *
   * @param pair the pair to persist
   * @return the entity to save
   */
  private CaldavCalendarSyncEntity toEntity(CalendarSync pair) {
    return new CaldavCalendarSyncEntity(pair.getId(),
                                        pair.getUserIdentityId(),
                                        pair.getServerId(),
                                        pair.getLocalCalendarSyncUid(),
                                        canonicalHref(pair.getRemoteHref()),
                                        pair.getOrigin(),
                                        pair.getSyncToken(),
                                        pair.getCtag(),
                                        pair.getStatus(),
                                        pair.getLastSyncStart(),
                                        pair.getLastSyncEnd(),
                                        pair.getConsecutiveFailures(),
                                        pair.getCopySettingsApplied());
  }

  /**
   * Maps an object entity onto its DTO.
   *
   * @param entity the persisted mapping
   * @return the mapping as the service layer handles it
   */
  private ObjectSync fromEntity(CaldavObjectSyncEntity entity) {
    return new ObjectSync(entity.getId(),
                          entity.getCalendarSyncId(),
                          entity.getLocalEventId(),
                          entity.getIcsUid(),
                          entity.getRemoteHref(),
                          entity.getEtag(),
                          entity.getLastSync());
  }

  /**
   * Maps an object DTO onto its entity, canonicalising the href.
   *
   * @param object the mapping to persist
   * @return the entity to save
   */
  private CaldavObjectSyncEntity toEntity(ObjectSync object) {
    return new CaldavObjectSyncEntity(object.getId(),
                                      object.getCalendarSyncId(),
                                      object.getLocalEventId(),
                                      object.getIcsUid(),
                                      canonicalHref(object.getRemoteHref()),
                                      object.getEtag(),
                                      object.getLastSync());
  }

}
