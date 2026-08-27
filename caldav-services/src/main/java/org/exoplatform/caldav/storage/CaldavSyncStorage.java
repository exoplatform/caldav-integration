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
                            entity.getConsecutiveFailures());
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
                                        pair.getConsecutiveFailures());
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
                          entity.getPushedHash(),
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
                                      object.getPushedHash(),
                                      object.getLastSync());
  }

}
