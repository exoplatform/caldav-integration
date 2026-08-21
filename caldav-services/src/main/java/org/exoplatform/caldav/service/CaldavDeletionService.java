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

import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Deleting a personal calendar, on both sides.
 *
 * <p>
 * The only irreversible outward-facing action in this design. Removing a
 * collection removes every event in it — including events the user added from
 * their own phone, which eXo never authored and cannot restore. Everything
 * here is arranged around that fact.
 */
@Service
public class CaldavDeletionService {

  /** Nothing was deleted, on either side. */
  public static final String     NOTHING_DELETED = "caldav.error.deleteFailed";

  private static final Log       LOG             = ExoLogger.getLogger(CaldavDeletionService.class);

  @Autowired
  private CalDavClient           calDavClient;

  @Autowired
  private CaldavConnectorStorage caldavConnectorStorage;

  @Autowired
  private CaldavSyncStorage      caldavSyncStorage;

  @Autowired
  private AgendaCalendarService  agendaCalendarService;

  /**
   * Deletes a personal calendar in eXo and, when eXo created it, on the server
   * too.
   *
   * <p>
   * <b>Remote first, and the order is the whole design.</b> The failable step
   * runs while nothing has happened yet, so a server that refuses or cannot be
   * reached leaves both sides exactly as they were. The reverse order can
   * strand a collection on the server after the record that knew about it is
   * gone — an orphan nothing will ever find again, holding the user's events.
   *
   * <p>
   * Success is confirmed by the collection's <b>absence from a fresh
   * listing</b>, never by a status code. This migration has twice met a server
   * that answers success while doing nothing, and a deletion that reports done
   * while the collection still stands would take the local calendar with it.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login, which agenda's ACL reads
   * @param calendarId the eXo calendar to delete
   * @throws CaldavPushException when the remote deletion could not be carried
   *           out; nothing has been deleted on either side
   */
  public void deleteCalendar(long userIdentityId, String username, long calendarId) {
    Calendar calendar = agendaCalendarService.getCalendarById(calendarId);
    CalendarSync pair = pairOf(userIdentityId, calendar);

    if (pair == null || pair.getOrigin() != SyncOrigin.EXO) {
      // Nothing eXo created out there. A REMOTE pair's collection is the
      // user's own, made in their own client, and a deletion in eXo is a
      // decision about eXo — the tombstone is what stops the next sync from
      // materialising it straight back.
      deleteLocally(userIdentityId, username, calendarId, pair, CalendarSyncStatus.LOCALLY_DELETED);
      return;
    }

    // Excluded from sync runs before anything is touched: a background sweep
    // finding this pair mid-deletion would push events into a collection that
    // is about to stop existing.
    pair.setStatus(CalendarSyncStatus.DELETING);
    caldavSyncStorage.savePair(pair);

    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    try {
      if (!removeCollection(settings, pair)) {
        throw new CaldavPushException(NOTHING_DELETED,
                                      "The collection " + pair.getRemoteHref() + " is still listed after the deletion");
      }
    } catch (CaldavPushException e) {
      restore(pair);
      throw e;
    } catch (RuntimeException e) {
      restore(pair);
      throw new CaldavPushException(NOTHING_DELETED,
                                    "The calendar could not be deleted on the server; nothing was deleted, in eXo or "
                                        + "on the server",
                                    e);
    }
    deleteLocally(userIdentityId, username, calendarId, pair, null);
  }

  /**
   * Deletes the calendar in eXo and leaves the remote collection standing,
   * because the user asked for exactly that.
   *
   * <p>
   * The escape hatch from the atomic rule, and it exists so that divergence
   * between the two sides is only ever <b>chosen, named and recorded</b> —
   * never a side effect of a failed deletion. The pair is kept as an
   * {@link CalendarSyncStatus#EXO_ORPHANED} tombstone so the settings can say
   * that a collection of eXo's making is still out there.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   * @param calendarId the eXo calendar to delete
   */
  public void deleteCalendarLocallyOnly(long userIdentityId, String username, long calendarId) {
    Calendar calendar = agendaCalendarService.getCalendarById(calendarId);
    CalendarSync pair = pairOf(userIdentityId, calendar);
    CalendarSyncStatus tombstone = pair != null && pair.getOrigin() == SyncOrigin.EXO ? CalendarSyncStatus.EXO_ORPHANED
                                                                                     : CalendarSyncStatus.LOCALLY_DELETED;
    deleteLocally(userIdentityId, username, calendarId, pair, tombstone);
  }

  /**
   * Freezes every binding of a user, which is what disconnecting an account
   * does.
   *
   * <p>
   * <b>Disconnecting never deletes anything.</b> A user unlinking their
   * calendar account is saying "stop syncing", not "destroy what is on my
   * server" — and the bindings are kept precisely so that reconnecting finds
   * its collections again instead of creating a second set beside them.
   *
   * @param userIdentityId identity of the user
   * @param serverId the declared server registration
   */
  public void freezeOnDisconnect(long userIdentityId, long serverId) {
    List<CalendarSync> pairs = caldavSyncStorage.getPairs(userIdentityId, serverId);
    for (CalendarSync pair : pairs) {
      if (pair.getStatus() != CalendarSyncStatus.ACTIVE) {
        continue;
      }
      pair.setStatus(CalendarSyncStatus.PAUSED);
      caldavSyncStorage.savePair(pair);
    }
  }

  /**
   * Removes the collection and confirms it is gone.
   *
   * @param settings the connected account
   * @param pair the binding whose collection goes
   * @return true when a fresh listing no longer shows it
   */
  private boolean removeCollection(CaldavUserSetting settings, CalendarSync pair) {
    if (settings == null || StringUtils.isBlank(settings.getUsername())) {
      throw new CaldavPushException(CaldavPushService.NOT_CONNECTED,
                                    "No connected CalDAV account; nothing was deleted, in eXo or on the server");
    }
    CalDavEndpoint endpoint = calDavClient.endpoint(settings.getServerId(), settings.getUsername());
    try {
      calDavClient.deleteCollection(endpoint, pair, settings.getUsername(), settings.getPassword());
      String home = calDavClient.discoverCalendarHome(endpoint, settings.getUsername(), settings.getPassword());
      String target = CaldavSyncStorage.canonicalHref(pair.getRemoteHref());
      return calDavClient.listCalendars(endpoint, home, settings.getUsername(), settings.getPassword())
                         .stream()
                         .noneMatch(collection -> target.equals(CaldavSyncStorage.canonicalHref(collection.href())));
    } catch (CalDavException e) {
      throw new CaldavPushException(NOTHING_DELETED,
                                    "The calendar could not be deleted on the server; nothing was deleted, in eXo or "
                                        + "on the server",
                                    e);
    }
  }

  /**
   * Deletes the calendar in eXo, and either drops the binding or leaves it as
   * a tombstone.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   * @param calendarId the eXo calendar to delete
   * @param pair the binding, or null when there is none
   * @param tombstone the state to keep the pair in, or null to drop it
   */
  private void deleteLocally(long userIdentityId,
                             String username,
                             long calendarId,
                             CalendarSync pair,
                             CalendarSyncStatus tombstone) {
    try {
      // Agenda's own contract, unchanged: the events of a deleted calendar
      // move to the user's default calendar. Nothing local is lost here, which
      // is what makes the remote side the only irreversible half.
      agendaCalendarService.deleteCalendarById(calendarId, username);
    } catch (Exception e) { // NOSONAR agenda declares checked exceptions here
      throw new CaldavPushException(NOTHING_DELETED, "The calendar could not be deleted in eXo", e);
    }
    if (pair == null) {
      return;
    }
    if (tombstone == null) {
      // Both sides are gone: the binding has nothing left to describe, and its
      // object mappings go with it.
      caldavSyncStorage.deleteObjects(pair.getId());
      caldavSyncStorage.deletePair(pair.getId());
      return;
    }
    // Kept on purpose. Without it the next sync would see a remote collection
    // eXo has no calendar for and materialise it straight back, undoing the
    // deletion in front of the user.
    pair.setStatus(tombstone);
    pair.setLastSyncEnd(new Date());
    caldavSyncStorage.savePair(pair);
    LOG.info("Calendar {} deleted in eXo only; its binding is kept as {}", calendarId, tombstone);
  }

  /**
   * Puts a pair back the way it was after a deletion that did not happen.
   *
   * @param pair the binding to restore
   */
  private void restore(CalendarSync pair) {
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    caldavSyncStorage.savePair(pair);
  }

  /**
   * The binding of a calendar, when it has one.
   *
   * @param userIdentityId identity of the user
   * @param calendar the eXo calendar
   * @return the pair, or null
   */
  private CalendarSync pairOf(long userIdentityId, Calendar calendar) {
    if (calendar == null || StringUtils.isBlank(calendar.getSyncUid())) {
      return null;
    }
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    long serverId = settings == null || settings.getServerId() == null ? 0L : settings.getServerId();
    return caldavSyncStorage.getPairByLocalCalendar(userIdentityId, serverId, calendar.getSyncUid());
  }
}
