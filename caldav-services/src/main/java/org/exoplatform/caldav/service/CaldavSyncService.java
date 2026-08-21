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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Calendar;
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

  @Autowired
  private CalDavClient                calDavClient;

  @Autowired
  private CaldavConnectorStorage      caldavConnectorStorage;

  @Autowired
  private CaldavSyncStorage           caldavSyncStorage;

  @Autowired
  private CaldavOutboundService       caldavOutboundService;

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
      materialiseRemoteCalendars(userIdentityId, username, settings);
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
    if (href == null) {
      return true;
    }
    if (href.equals(CaldavSyncStorage.canonicalHref(settings.getMirrorCalendarHref()))
        || href.endsWith("/" + CaldavPushService.MIRROR_COLLECTION_SLUG)) {
      return true;
    }
    return known.stream().anyMatch(pair -> href.equals(CaldavSyncStorage.canonicalHref(pair.getRemoteHref())));
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
    calendar.setTitle(StringUtils.defaultIfBlank(collection.displayName(), collection.href()));
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
