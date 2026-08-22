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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarDeletionPlan;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.caldav.model.HiddenCalendar;
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
   * <p>
   * The local half is <b>not</b> done here. Agenda owns the calendar and
   * deletes it itself, once this returns without throwing — which is exactly
   * what makes the ordering work: the failable step runs first, and a refusal
   * here stops agenda before it has touched anything.
   *
   * @param userIdentityId identity of the user
   * @param calendarId the eXo calendar being deleted
   * @throws CaldavPushException when the remote deletion could not be carried
   *           out; nothing has been deleted on either side
   */
  public void deleteRemoteCounterpart(long userIdentityId, long calendarId) {
    Calendar calendar = agendaCalendarService.getCalendarById(calendarId);
    CalendarSync pair = pairOf(userIdentityId, calendar);

    if (pair == null) {
      // Never bound, nothing to propagate. The connector must not become a
      // reason a plain local deletion fails.
      return;
    }
    if (pair.getOrigin() != SyncOrigin.EXO) {
      // Nothing eXo created out there. A REMOTE pair's collection is the
      // user's own, made in their own client, and a deletion in eXo is a
      // decision about eXo — the tombstone is what stops the next sync from
      // materialising it straight back.
      tombstone(pair, CalendarSyncStatus.LOCALLY_DELETED, calendarId);
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
    // Both sides are done with: the binding has nothing left to describe, and
    // its object mappings go with it. Agenda removes the calendar itself,
    // after this returns — the order the whole design rests on.
    caldavSyncStorage.deleteObjects(pair.getId());
    caldavSyncStorage.deletePair(pair.getId());
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
   * @param calendarId the eXo calendar being deleted
   */
  public void keepRemoteCounterpart(long userIdentityId, long calendarId) {
    Calendar calendar = agendaCalendarService.getCalendarById(calendarId);
    CalendarSync pair = pairOf(userIdentityId, calendar);
    if (pair == null) {
      return;
    }
    CalendarSyncStatus state = pair.getOrigin() == SyncOrigin.EXO ? CalendarSyncStatus.EXO_ORPHANED
                                                                 : CalendarSyncStatus.LOCALLY_DELETED;
    tombstone(pair, state, calendarId);
  }

  /**
   * What deleting this calendar would also do, so the page can say it before
   * the user confirms.
   *
   * <p>
   * The page cannot work this out for itself: it does not know whether eXo
   * created the remote collection, nor which server holds it. Both decide what
   * the confirmation must warn about — and whether it must warn at all.
   *
   * @param userIdentityId identity of the user
   * @param calendarId the eXo calendar in question
   * @return what is bound to it, or a plan claiming nothing
   */
  public CalendarDeletionPlan describeDeletion(long userIdentityId, long calendarId) {
    Calendar calendar = agendaCalendarService.getCalendarById(calendarId);
    CalendarSync pair = pairOf(userIdentityId, calendar);
    if (pair == null || pair.getStatus() == CalendarSyncStatus.LOCALLY_DELETED
        || pair.getStatus() == CalendarSyncStatus.EXO_ORPHANED) {
      return new CalendarDeletionPlan(false, false, null);
    }
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    String server = settings == null ? null : settings.getCaldavUrl();
    // Only an EXO collection is eXo's to delete. A REMOTE one is the user's
    // own, made in their own client, and saying otherwise in a dialog would
    // be a promise to destroy something we will not touch.
    return new CalendarDeletionPlan(true, pair.getOrigin() == SyncOrigin.EXO, server);
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
   * The calendars this user has hidden on this account.
   *
   * <p>
   * A tombstone is what makes a deletion stick: it keeps a collection from
   * being materialised again, and since the shim stopped serving bound
   * collections it keeps it off the screen entirely. That is what the user
   * asked for — and it leaves them with no way back, which is why this exists.
   *
   * <p>
   * The name comes from the server, read now rather than stored: a calendar
   * renamed in the user's own client since they hid it should be offered back
   * under the name they would recognise today. A collection the server no
   * longer has is left out — offering to show something that is gone would be
   * a promise nothing can keep.
   *
   * @param userIdentityId identity of the user
   * @return what can be shown again, empty when nothing is hidden
   */
  public List<HiddenCalendar> listHidden(long userIdentityId) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (settings == null || StringUtils.isBlank(settings.getUsername())) {
      return List.of();
    }
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    List<CalendarSync> tombstones = caldavSyncStorage.getPairs(userIdentityId, serverId)
                                                     .stream()
                                                     .filter(pair -> pair.getStatus() == CalendarSyncStatus.LOCALLY_DELETED)
                                                     .toList();
    if (tombstones.isEmpty()) {
      // The common answer, and it costs no round trip: a section that is not
      // shown must not make the drawer wait on a server to find that out.
      return List.of();
    }
    Map<String, String> namesByHref = collectionNames(settings);
    List<HiddenCalendar> hidden = new ArrayList<>();
    for (CalendarSync tombstone : tombstones) {
      String name = namesByHref.get(CaldavSyncStorage.canonicalHref(tombstone.getRemoteHref()));
      if (StringUtils.isNotBlank(name)) {
        hidden.add(new HiddenCalendar(tombstone.getId(), name));
      }
    }
    return hidden;
  }

  /**
   * Shows a hidden calendar again.
   *
   * <p>
   * Dropping the tombstone is all it takes: the next sync finds a collection
   * with no binding and materialises it afresh. Deliberately a new calendar
   * rather than a resurrection — the one the user deleted is gone, and
   * pretending otherwise would promise back events that agenda moved to their
   * default calendar at deletion time.
   *
   * @param userIdentityId identity of the user
   * @param pairId the tombstone to lift
   * @throws IllegalAccessException when the tombstone is not this user's
   * @throws ObjectNotFoundException when there is no such tombstone
   */
  public void showAgain(long userIdentityId, long pairId) throws IllegalAccessException, ObjectNotFoundException {
    CalendarSync pair = caldavSyncStorage.getPair(pairId);
    if (pair == null || pair.getStatus() != CalendarSyncStatus.LOCALLY_DELETED) {
      throw new ObjectNotFoundException("No hidden calendar with id " + pairId);
    }
    if (pair.getUserIdentityId() != userIdentityId) {
      // The binding carries whose it is, and that is the only thing standing
      // between one user and another user's calendars.
      throw new IllegalAccessException("Binding " + pairId + " does not belong to user " + userIdentityId);
    }
    caldavSyncStorage.deleteObjects(pairId);
    caldavSyncStorage.deletePair(pairId);
    LOG.info("Tombstone {} lifted; the collection will be materialised again at the next sync", pairId);
  }

  /**
   * The account's collections, by canonical path.
   *
   * @param settings the connected account
   * @return their display names, empty when the server cannot be listed
   */
  private Map<String, String> collectionNames(CaldavUserSetting settings) {
    Map<String, String> names = new HashMap<>();
    try {
      CalDavEndpoint endpoint = calDavClient.endpoint(settings.getServerId(), settings.getUsername());
      String home = calDavClient.discoverCalendarHome(endpoint, settings.getUsername(), settings.getPassword());
      for (CalendarCollection collection : calDavClient.listCalendars(endpoint,
                                                                     home,
                                                                     settings.getUsername(),
                                                                     settings.getPassword())) {
        names.put(CaldavSyncStorage.canonicalHref(collection.href()),
                  StringUtils.defaultIfBlank(collection.displayName(), collection.href()));
      }
    } catch (CalDavException e) {
      // Nothing offered rather than a list of paths the user never chose to
      // see. They can try again when the server answers.
      LOG.debug("The account's collections could not be listed; nothing is offered to show again", e);
    }
    return names;
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
   * Keeps the binding as a record of a deletion that only happened on one
   * side.
   *
   * <p>
   * Never dropped, on purpose. Without it the next sweep sees a remote
   * collection eXo has no calendar for and materialises it straight back,
   * undoing the deletion in front of the user.
   *
   * @param pair the binding to keep
   * @param state what the tombstone records
   * @param calendarId the calendar being deleted, for the log
   */
  private void tombstone(CalendarSync pair, CalendarSyncStatus state, long calendarId) {
    pair.setStatus(state);
    pair.setLastSyncEnd(new Date());
    caldavSyncStorage.savePair(pair);
    LOG.info("Calendar {} is being deleted in eXo only; its binding is kept as {}", calendarId, state);
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
