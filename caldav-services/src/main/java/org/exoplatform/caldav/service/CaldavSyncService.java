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
package org.exoplatform.caldav.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Keeps a user's eXo calendars and their remote collections in step, in both
 * directions.
 *
 * <p>
 * The outward half already exists: eXo calendars get collections of their own.
 * This adds the inward one — a calendar the user made in their own client
 * becomes an eXo personal calendar — and the entry point that runs both.
 */
@Service
public class CaldavSyncService {

  private static final Log            LOG        = ExoLogger.getLogger(CaldavSyncService.class);

  /**
   * When each user last synchronised, so opening the agenda repeatedly does not
   * mean talking to a calendar server repeatedly.
   *
   * <p>
   * In memory on purpose: a throttle is a courtesy to the server, not a fact
   * worth surviving a restart. Losing it costs one extra sync per user after a
   * restart, which is the cheap direction to be wrong in.
   */
  private final Map<Long, Instant>    lastSync   = new ConcurrentHashMap<>();

  /**
   * Users a sync is running for, so two page loads a second apart do not run
   * two syncs against the same account at once.
   */
  private final Map<Long, Boolean>    syncing    = new ConcurrentHashMap<>();

  /** How long a sync stays fresh; deployment-tunable. */
  @Value("${exo.agenda.caldav.sync.throttleMinutes:15}")
  private long                        throttleMinutes;

  /**
   * How far back events are imported. A calendar with ten years of history
   * behind it would otherwise cost a full download on a page load.
   */
  @Value("${exo.agenda.caldav.sync.pastDays:60}")
  private long                        pastDays;

  /**
   * How far ahead events are imported.
   */
  @Value("${exo.agenda.caldav.sync.futureDays:365}")
  private long                        futureDays;

  @Autowired
  private CalDavClient                calDavClient;

  @Autowired
  private CaldavConnectorStorage      caldavConnectorStorage;

  @Autowired
  private CaldavSyncStorage           caldavSyncStorage;

  @Autowired
  private CaldavOutboundService       caldavOutboundService;

  @Autowired
  private CaldavInboundService        caldavInboundService;

  @Autowired
  private IdentityManager             identityManager;

  @Autowired
  private AgendaCalendarService       agendaCalendarService;

  /**
   * Synchronises a user's calendars if they have not been synchronised
   * recently.
   *
   * <p>
   * The trigger for opening the agenda. Doing nothing is the common answer and
   * has to be cheap: a page load must not wait on a calendar server that has
   * nothing new to say.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login, which agenda's ACL reads
   */
  public void syncIfDue(long userIdentityId, String username) {
    Instant last = lastSync.get(userIdentityId);
    if (last != null && last.isAfter(Instant.now().minus(Duration.ofMinutes(throttleMinutes)))) {
      return;
    }
    sync(userIdentityId, username);
  }

  /**
   * Synchronises now, whatever the throttle says.
   *
   * <p>
   * What a "sync now" button calls. It bypasses the throttle because a user
   * pressing it has a reason the throttle cannot know — they just changed
   * something on their phone and want to see it.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   */
  public void syncNow(long userIdentityId, String username) {
    sync(userIdentityId, username);
  }

  /**
   * Synchronises because a calendar has just been created.
   *
   * <p>
   * The engine needs the owner's login to satisfy agenda's ACL, and a listener
   * has only their identity — resolving it is this method's reason to exist,
   * and it keeps the listener the glue it is meant to be.
   *
   * <p>
   * Nothing happens for a user with no connected account, which is most of
   * them: a calendar created by someone who never set up CalDAV must not cost
   * an identity lookup and a wasted pass.
   *
   * @param userIdentityId identity of the calendar's owner
   */
  public void syncAfterCalendarCreated(long userIdentityId) {
    if (!connected(caldavConnectorStorage.getCaldavSetting(userIdentityId))) {
      return;
    }
    Identity identity = identityManager.getIdentity(String.valueOf(userIdentityId));
    if (identity == null || StringUtils.isBlank(identity.getRemoteId())) {
      LOG.debug("Calendar owner {} has no resolvable login; the next sync will carry the calendar", userIdentityId);
      return;
    }
    // syncNow rather than syncIfDue: the point is that the user does not wait.
    // The concurrency guard keeps this harmless when the calendar was itself
    // created by a sync that is still running — that pass returns immediately
    // rather than recursing into itself.
    syncNow(userIdentityId, identity.getRemoteId());
  }

  /**
   * Forgets when this user last synchronised.
   *
   * <p>
   * What connecting an account calls. Someone who has just entered their
   * credentials is owed their calendars now, not in a quarter of an hour —
   * and a throttle stamped by the previous account's run has nothing to say
   * about the new one.
   *
   * @param userIdentityId identity of the user
   */
  public void forgetThrottle(long userIdentityId) {
    lastSync.remove(userIdentityId);
  }

  /**
   * One synchronisation pass, outward then inward.
   *
   * <p>
   * Outward first, and it matters: binding the user's own calendars marks
   * their collections ORIGIN=EXO, which is precisely what the inward pass then
   * skips. Run the other way round on a fresh account and every collection eXo
   * is about to create would first be materialised as a second eXo calendar.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   */
  private void sync(long userIdentityId, String username) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!connected(settings)) {
      return;
    }
    // One sync per user at a time. Two page loads a second apart would
    // otherwise both create the same calendar, and the second would find the
    // first's collection listed and bind a duplicate to it.
    if (syncing.putIfAbsent(userIdentityId, Boolean.TRUE) != null) {
      return;
    }
    try {
      caldavOutboundService.bindPersonalCalendars(userIdentityId, username);
      // Before materialising, not after: a binding with nothing behind it is
      // exactly what makes materialisation skip a collection, so healing it
      // afterwards leaves the user waiting a whole throttle window for a
      // calendar that could have come back in this same pass.
      pruneOrphanBindings(userIdentityId, username, settings);
      materialiseRemoteCalendars(userIdentityId, username, settings);
      importRemoteEvents(userIdentityId, username, settings);
      lastSync.put(userIdentityId, Instant.now());
    } catch (RuntimeException e) {
      // A sync that fails is not an error the caller can act on — the page it
      // was triggered from has its own events to show. It is logged and the
      // next trigger tries again.
      LOG.warn("Synchronising the CalDAV calendars of user {} failed", userIdentityId, e);
    } finally {
      syncing.remove(userIdentityId);
    }
  }

  /**
   * Drops bindings that have no eXo calendar behind them any more.
   *
   * <p>
   * Such a binding describes nothing, and keeping it costs the user the
   * collection for good: materialisation skips a collection that already has
   * one, so the calendar can never come back and nothing on screen says why.
   *
   * <p>
   * Only ACTIVE bindings are considered. A tombstone is a deliberate deletion
   * and its whole purpose is to keep the collection out — pruning those would
   * bring back exactly what the user asked to be rid of.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   * @param settings the connected account
   */
  private void pruneOrphanBindings(long userIdentityId, String username, CaldavUserSetting settings) {
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    List<CalendarSync> pairs = caldavSyncStorage.getPairsByOrigin(userIdentityId, serverId, SyncOrigin.REMOTE);
    if (pairs.isEmpty()) {
      return;
    }
    Map<String, Calendar> byAnchor = calendarsByAnchor(userIdentityId, username);
    if (byAnchor.isEmpty()) {
      // The calendars could not be read at all. Every binding would look like
      // an orphan, and pruning them would throw away bindings whose calendars
      // are perfectly well — the worst possible reading of a read failure.
      return;
    }
    for (CalendarSync pair : pairs) {
      if (pair.getStatus() == CalendarSyncStatus.ACTIVE && !byAnchor.containsKey(pair.getLocalCalendarSyncUid())) {
        LOG.info("Binding {} has no eXo calendar behind it; it is dropped so the collection can be materialised again",
                 pair.getId());
        caldavSyncStorage.deleteObjects(pair.getId());
        caldavSyncStorage.deletePair(pair.getId());
      }
    }
  }

  /**
   * Brings the events of every materialised collection into the calendar
   * standing for it.
   *
   * <p>
   * Only bindings eXo did <em>not</em> create are read. A collection eXo
   * pushed holds copies of events agenda already has, and importing them back
   * would show every one of the user's own meetings twice.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login, which agenda's ACL reads
   * @param settings the connected account
   */
  private void importRemoteEvents(long userIdentityId, String username, CaldavUserSetting settings) {
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    List<CalendarSync> pairs = caldavSyncStorage.getPairsByOrigin(userIdentityId, serverId, SyncOrigin.REMOTE);
    if (pairs.isEmpty()) {
      return;
    }
    Map<String, Calendar> byAnchor = calendarsByAnchor(userIdentityId, username);
    Instant now = Instant.now();
    Instant from = now.minus(Duration.ofDays(pastDays));
    Instant to = now.plus(Duration.ofDays(futureDays));
    for (CalendarSync pair : pairs) {
      if (pair.getStatus() != CalendarSyncStatus.ACTIVE) {
        // A paused or tombstoned binding is not one to read from. A tombstone
        // in particular means the user deleted the calendar in eXo, and
        // filling it back up is precisely what they asked not to happen.
        continue;
      }
      Calendar calendar = byAnchor.get(pair.getLocalCalendarSyncUid());
      if (calendar == null) {
        // Pruned earlier in this pass, or created between the two steps.
        continue;
      }
      try {
        caldavInboundService.importInto(userIdentityId, pair, calendar, from, to);
      } catch (RuntimeException e) {
        // One collection must not cost the others. The next run tries again.
        LOG.warn("The events of collection {} could not be imported", pair.getRemoteHref(), e);
      }
    }
  }

  /**
   * The user's calendars, keyed by the anchor a binding records.
   *
   * <p>
   * Agenda exposes no lookup by sync uid, so the calendars are listed once per
   * run and matched in memory rather than once per binding.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   * @return the calendars by anchor, empty when they cannot be read
   */
  private Map<String, Calendar> calendarsByAnchor(long userIdentityId, String username) {
    Map<String, Calendar> byAnchor = new HashMap<>();
    try {
      for (Calendar calendar : agendaCalendarService.getCalendars(0, Integer.MAX_VALUE, username)) {
        if (calendar.getOwnerId() == userIdentityId && !calendar.isDeleted()
            && StringUtils.isNotBlank(calendar.getSyncUid())) {
          byAnchor.put(calendar.getSyncUid(), calendar);
        }
      }
    } catch (Exception e) { // NOSONAR agenda declares a bare Exception here
      LOG.warn("The calendars of user {} could not be read; nothing is imported this round", userIdentityId, e);
    }
    return byAnchor;
  }

  /**
   * Gives every remote calendar an eXo personal calendar, except the ones eXo
   * put there itself.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   * @param settings the connected account
   */
  private void materialiseRemoteCalendars(long userIdentityId, String username, CaldavUserSetting settings) {
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    CalDavEndpoint endpoint = calDavClient.endpoint(settings.getServerId(), settings.getUsername());
    List<CalendarCollection> collections;
    try {
      String home = calDavClient.discoverCalendarHome(endpoint, settings.getUsername(), settings.getPassword());
      collections = calDavClient.listCalendars(endpoint, home, settings.getUsername(), settings.getPassword());
    } catch (CalDavException e) {
      LOG.warn("The account's calendars could not be listed; nothing is materialised this round", e);
      return;
    }
    List<CalendarSync> known = caldavSyncStorage.getPairs(userIdentityId, serverId);
    for (CalendarCollection collection : collections) {
      if (isAlreadyOurs(collection, known, settings)) {
        continue;
      }
      if (!collection.holdsEvents()) {
        // A CalDAV home holds more than calendars: BlueMind publishes the
        // account's task list beside them, and it answers a PROPFIND for
        // calendars just as a calendar would. Materialising it would give the
        // user an eXo calendar that can never hold an event.
        LOG.debug("Collection {} declares no VEVENT support and is not a calendar to materialise", collection.href());
        continue;
      }
      materialise(userIdentityId, username, serverId, collection);
    }
  }

  /**
   * Whether a listed collection is one eXo already accounts for.
   *
   * <p>
   * Three reasons to skip, and the first is the one that keeps the two halves
   * from feeding each other. A collection eXo created carries an ORIGIN=EXO
   * pair; materialising it would produce a second eXo calendar, which the
   * outward pass would then push as a third collection, and so on. Two
   * features each behaving correctly, multiplying calendars on both sides.
   *
   * <p>
   * The mirror is skipped because its contents are copies of events eXo
   * already shows, and a pair of any kind means the collection is accounted
   * for — including a tombstone, which is exactly what stops a calendar the
   * user deleted in eXo from coming straight back.
   *
   * @param collection the listed collection
   * @param known every pair this user holds on this server
   * @param settings the connected account, for the recorded mirror href
   * @return true when nothing should be created for it
   */
  private boolean isAlreadyOurs(CalendarCollection collection, List<CalendarSync> known, CaldavUserSetting settings) {
    String href = CaldavSyncStorage.canonicalHref(collection.href());
    if (StringUtils.isBlank(href)) {
      // A collection with no path is nothing we can bind to, name, or find
      // again. Blank rather than null on purpose: a server answering an empty
      // <d:href/> would otherwise match no pair, fail the eXo-created test,
      // and be materialised as a calendar whose name is the blank path too.
      return true;
    }
    if (href.equals(CaldavSyncStorage.canonicalHref(settings.getMirrorCalendarHref()))
        || href.endsWith("/" + CaldavPushService.MIRROR_COLLECTION_SLUG)) {
      return true;
    }
    if (isExoCreated(href)) {
      return true;
    }
    return known.stream().anyMatch(pair -> href.equals(CaldavSyncStorage.canonicalHref(pair.getRemoteHref())));
  }

  /**
   * Whether eXo is the one that created this collection, judged from its path
   * alone.
   *
   * <p>
   * The pair check below cannot answer this on its own: pairs are read for
   * <em>one</em> user, while a CalDAV account can be shared by several. Two
   * eXo users connected to the same account see each other's pushed
   * collections as ordinary remote ones, each materialises the other's, each
   * then pushes the result as a new collection — and the pair of them
   * multiply calendars without either behaving incorrectly. Observed live:
   * one user's <code>exo-cal-946eec40…</code> came back as another user's
   * calendar 23.
   *
   * <p>
   * The path is the reliable signal because eXo mints it: a collection under
   * the outbound prefix was created by eXo, whichever user asked for it, and
   * is never something to import.
   *
   * @param href the collection path, canonical
   * @return true when the path is one eXo derives
   */
  private boolean isExoCreated(String href) {
    String slug = StringUtils.substringAfterLast(StringUtils.stripEnd(href, "/"), "/");
    return StringUtils.startsWith(slug, CaldavOutboundService.COLLECTION_PREFIX);
  }

  /**
   * Creates the eXo calendar standing for one remote collection.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   * @param serverId the declared server registration
   * @param collection the remote collection
   */
  private void materialise(long userIdentityId, String username, long serverId, CalendarCollection collection) {
    Calendar calendar = new Calendar();
    calendar.setOwnerId(userIdentityId);
    // Agenda persists getName(); getTitle() is a display field it computes,
    // and a calendar left with no name falls back to the owner's identity —
    // which is how two materialised collections both came out called after
    // their owner instead of after themselves.
    String name = StringUtils.defaultIfBlank(collection.displayName(), collection.href());
    calendar.setName(name);
    calendar.setTitle(name);
    calendar.setColor(collection.color());
    Calendar created;
    try {
      created = agendaCalendarService.createCalendar(calendar, username);
    } catch (IllegalAccessException e) {
      LOG.warn("User {} may not have a calendar created for collection {}", userIdentityId, collection.href(), e);
      return;
    }
    CalendarSync pair = new CalendarSync();
    pair.setUserIdentityId(userIdentityId);
    pair.setServerId(serverId);
    // Agenda mints the anchor when it creates the calendar; the pair records
    // it so the binding survives anything that renumbers ids.
    pair.setLocalCalendarSyncUid(created.getSyncUid());
    pair.setRemoteHref(collection.href());
    // The user made this collection, not eXo. The origin decides what may
    // later be deleted, and getting it wrong here would let eXo remove a
    // calendar it never created.
    pair.setOrigin(SyncOrigin.REMOTE);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    pair.setCtag(collection.ctag());
    pair.setSyncToken(collection.syncToken());
    pair.setLastSyncEnd(new Date());
    caldavSyncStorage.savePair(pair);
    LOG.info("Materialised remote calendar {} as eXo calendar {}", collection.href(), created.getId());
  }

  /**
   * Whether an account is usable.
   *
   * @param settings the stored account
   * @return true when it carries credentials
   */
  private boolean connected(CaldavUserSetting settings) {
    return settings != null && StringUtils.isNotBlank(settings.getUsername())
        && StringUtils.isNotBlank(settings.getPassword());
  }
}
