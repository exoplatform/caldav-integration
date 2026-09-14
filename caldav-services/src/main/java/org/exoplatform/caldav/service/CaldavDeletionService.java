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

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarDeletionPlan;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.caldav.model.HiddenCalendar;
import org.exoplatform.caldav.model.CalendarSyncState;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.RemoteCalendar;
import org.exoplatform.caldav.model.RemoteCalendarsRead;
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

  /**
   * The calendar named to hide is not one shared with the user (EXO-90239):
   * their own, in whatever state — and hiding one's own calendar is a
   * different act, done by deleting it and keeping the remote one.
   */
  public static final String     NOT_A_SHARE     = "caldav.hiddenCalendars.notAShare";

  /** No calendar was named to hide. */
  public static final String     CALENDAR_REQUIRED = "caldav.hiddenCalendars.calendarRequired";

  /**
   * The account's calendars could not be listed, so whether the calendar
   * named is a share of the user's cannot be told; nothing was hidden.
   */
  public static final String     ACCOUNT_UNAVAILABLE = "caldav.hiddenCalendars.accountUnavailable";

  private static final Log       LOG             = ExoLogger.getLogger(CaldavDeletionService.class);

  @Autowired
  private CalDavClient           calDavClient;

  @Autowired
  private CaldavConnectorStorage caldavConnectorStorage;

  @Autowired
  private CaldavSyncStorage      caldavSyncStorage;

  @Autowired
  private AgendaCalendarService  agendaCalendarService;

  @Autowired
  private CaldavServerService    caldavServerService;

  @Autowired
  @Lazy
  private CaldavSyncService      caldavSyncService;

  @Autowired
  private CaldavReadService      caldavReadService;

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
   * @param username the eXo login the credentials provider resolves the
   *          account from
   * @param calendarId the eXo calendar being deleted
   * @throws CaldavPushException when the remote deletion could not be carried
   *           out; nothing has been deleted on either side
   */
  public void deleteRemoteCounterpart(long userIdentityId, String username, long calendarId) {
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
      if (!removeCollection(settings, username, pair)) {
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
    String server = serverName(settings);
    // Only an EXO collection is eXo's to delete. A REMOTE one is the user's
    // own, made in their own client, and saying otherwise in a dialog would
    // be a promise to destroy something we will not touch.
    return new CalendarDeletionPlan(true, pair.getOrigin() == SyncOrigin.EXO, server);
  }

  /**
   * What to call the server in a sentence shown to the user.
   *
   * <p>
   * The declared server's provider name first — it is what the user picked in
   * the connect drawer and what every other screen calls it. The stored URL
   * only ever holds a value for an account attached the legacy way, before
   * servers were declared, so reading it alone left the warning saying "the
   * matching calendar on  will not be touched", with a blank where the name
   * belongs.
   *
   * @param settings the connected account, possibly null
   * @return the name, or null when there is nothing to call it
   */
  private String serverName(CaldavUserSetting settings) {
    if (settings == null) {
      return null;
    }
    if (settings.getServerId() != null && settings.getServerId() > 0) {
      try {
        CaldavServer server = caldavServerService.getServerById(settings.getServerId());
        // getName, not getProviderName: the provider name is the key agenda
        // binds the connector under — "agenda.caldavCalendar.6" — and putting
        // it in a sentence shows the user a raw key. getName is the display
        // name the administrator typed.
        if (server != null && StringUtils.isNotBlank(server.getName())) {
          return server.getName();
        }
      } catch (ObjectNotFoundException e) {
        // A registration removed while an account still points at it. The URL
        // below is a poorer name, not a reason to say nothing.
        LOG.debug("CalDAV server {} is no longer declared", settings.getServerId(), e);
      }
    }
    return settings.getCaldavUrl();
  }

  /**
   * Puts back to work every binding a disconnect froze.
   *
   * <p>
   * Disconnecting pauses the bindings of the calendars eXo pushed out, so that
   * reconnecting the same account finds its collections again instead of
   * creating a second set beside them. Reconnecting is the other half of that
   * bargain: the account is usable again, so its bindings are too.
   *
   * <p>
   * Left paused they are repaired only incidentally — by the next sweep
   * re-ensuring each collection, a second or so later. In the meantime the
   * account is connected and the user's own calendars report themselves as
   * failing, which a UI reading the states has every reason to believe and
   * show.
   *
   * <p>
   * A pause set because credentials were refused is thawed here as well, and
   * deliberately: someone who has just entered a password is asking for
   * precisely that retry.
   *
   * @param userIdentityId identity of the user
   * @param serverId the declared server registration
   */
  public void thawOnConnect(long userIdentityId, long serverId) {
    for (CalendarSync pair : caldavSyncStorage.getPairs(userIdentityId, serverId)) {
      // Only a pause. A tombstone is a deletion the user made, and a binding
      // marked gone is a claim about the server that only the server's own
      // listing may withdraw.
      if (pair.getStatus() == CalendarSyncStatus.PAUSED) {
        pair.setStatus(CalendarSyncStatus.ACTIVE);
        caldavSyncStorage.savePair(pair);
      }
    }
  }

  /**
   * Freezes every binding of a user, which is what disconnecting an account
   * does.
   *
   * <p>
   * <b>Nothing on the server is ever touched.</b> A user unlinking their
   * account is saying "stop syncing", not "destroy what is on my server".
   *
   * <p>
   * <b>Nothing in eXo is destroyed either, and that is the change</b>
   * (EXO-89800). A calendar eXo materialised from a collection of the account
   * used to go with the account: its eXo calendar was deleted, and with it
   * every event in it, its object mappings and its binding. Reconnecting the
   * same account then found no binding for the collection, materialised it a
   * second time, and imported every event again under new identifiers —
   * observed live as the same BlueMind collection materialised at 23:28 and
   * again at 08:37 the next morning, as two different eXo calendars.
   *
   * <p>
   * That deletion could not be undone and was not the user's to lose. A
   * materialised calendar is an ordinary agenda calendar: the user adds events
   * to it, and the connector pushes those back to the account, so it holds
   * work of theirs that exists nowhere else. Deleting it on "stop syncing"
   * also broke every reference to the events it held — an activity, a
   * notification, a link — since the re-imported copies carry new identifiers.
   *
   * <p>
   * So every binding is now treated the way a pushed calendar already was, and
   * for the reason its own comment gave: <em>reconnecting the same account
   * must find its collection again instead of creating a second one beside
   * it</em>. The binding is paused, the calendar stays, and
   * {@link #thawOnConnect(long, long)} puts it back to work.
   *
   * <p>
   * A paused binding stops importing, which is what "no longer updating"
   * means; {@link #listSyncStates(long, String)} reports the state so the
   * settings can say so. And reconnecting a <em>different</em> account on the
   * same server does not resurrect the old collections: their hrefs are absent
   * from the new account's listing, so the first pass marks them
   * {@link CalendarSyncStatus#REMOTE_GONE} and they stop receiving events —
   * visibly, rather than by having been destroyed in advance.
   *
   * @param userIdentityId identity of the user
   * @param serverId the declared server registration
   * @param username the user's login. No longer needed to delete anything on
   *          their behalf, and kept because it is still what tells the caller
   *          the account can be identified at all —
   *          {@link CaldavConnectorServiceImpl#deleteCaldavSetting(long, String)}
   *          only freezes when it has one — and because a support trace of a
   *          disconnect is worth the login it happened under
   */
  public void freezeOnDisconnect(long userIdentityId, long serverId, String username) {
    List<CalendarSync> pairs = caldavSyncStorage.getPairs(userIdentityId, serverId);
    for (CalendarSync pair : pairs) {
      if (pair.getStatus() != CalendarSyncStatus.ACTIVE) {
        // A tombstone is a deliberate deletion the user already made, and a
        // paused binding has already been through this. Neither is ours to
        // reopen.
        continue;
      }
      pair.setStatus(CalendarSyncStatus.PAUSED);
      caldavSyncStorage.savePair(pair);
    }
    LOG.debug("The bindings of {} on server {} are paused; nothing was deleted on either side",
              username,
              serverId);
  }

  /**
   * Hides a calendar somebody shared with the user (EXO-90239).
   *
   * <p>
   * A share is never materialised, so until now it had no pair — and being
   * unbound is exactly what listed it under the remote calendars and served
   * its events. The only way to stop seeing it was the row checkbox, which
   * lasted until the next reload. Hiding records the choice as a pair in
   * {@link CalendarSyncStatus#HIDDEN_SHARE}: bound, the collection leaves
   * the list and its events stop being served ({@code CaldavReadService}),
   * and the sweep never considers it ({@code CaldavSyncService#isAlreadyOurs}).
   * No calendar is created, nothing is written to the server, and nothing
   * of the owner's is touched.
   *
   * <p>
   * <b>The id is trusted no further than the user's own listing.</b> It is a
   * collection href that travelled through a browser, and what a changed one
   * would name is another collection on the same account. So it is matched,
   * canonically, against the calendars the list would answer this user
   * <em>now</em> — the same listing, the same classification — and refused
   * unless that listing holds it as a share. A calendar of the user's own is
   * refused in words: hiding one's own is done by deleting it and keeping the
   * remote copy, which is a different act with a different warning. The
   * user's pairs are asked first, cheaply: a collection already bound is
   * either hidden already — nothing to do, and the answer is the same as if
   * this call had done it — or an eXo calendar of the user's own, and the
   * server is not asked about either.
   *
   * @param userIdentityId identity of the user
   * @param username the eXo login the credentials provider resolves the
   *          account from
   * @param calendarId the calendar's identity, exactly as the calendar list
   *          answered it — the collection href
   * @throws ObjectNotFoundException when the user's current listing holds no
   *           such calendar
   * @throws IllegalArgumentException with {@link #CALENDAR_REQUIRED} when no
   *           calendar is named, and with {@link #NOT_A_SHARE} when the one
   *           named is the user's own
   * @throws CaldavPushException with {@code NOT_CONNECTED} when no account is
   *           connected, and with {@link #ACCOUNT_UNAVAILABLE} when the
   *           account could not be listed; nothing was hidden either way
   */
  public void hideShare(long userIdentityId, String username, String calendarId) throws ObjectNotFoundException {
    String href = CaldavSyncStorage.canonicalHref(calendarId);
    if (StringUtils.isBlank(href)) {
      throw new IllegalArgumentException(CALENDAR_REQUIRED);
    }
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (settings == null || StringUtils.isBlank(settings.getUsername())) {
      throw new CaldavPushException(CaldavPushService.NOT_CONNECTED, "No connected CalDAV account; nothing was hidden");
    }
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    CalendarSync bound = caldavSyncStorage.getPairByRemoteHref(userIdentityId, serverId, href);
    if (bound != null) {
      if (bound.getStatus() == CalendarSyncStatus.HIDDEN_SHARE) {
        // Already hidden, by this user, on this account. The end state the
        // caller asked for holds, and a second row for one choice would give
        // the drawer two lines to lift for one calendar.
        return;
      }
      // Any other binding is an eXo calendar of the user's own — materialised,
      // exported, deleted here and kept there, paused. None of those is a
      // share, and none is hidden through here.
      throw new IllegalArgumentException(NOT_A_SHARE);
    }
    RemoteCalendarsRead listing = caldavReadService.listCalendars(userIdentityId, username);
    if (listing.failed()) {
      throw new CaldavPushException(ACCOUNT_UNAVAILABLE,
                                    "The account's calendars could not be listed; whether " + href
                                        + " is shared with the user cannot be told, and nothing was hidden");
    }
    RemoteCalendar calendar = listing.calendars()
                                     .stream()
                                     .filter(listed -> href.equals(CaldavSyncStorage.canonicalHref(listed.getId())))
                                     .findFirst()
                                     .orElseThrow(() -> new ObjectNotFoundException("No calendar " + href
                                         + " in the account's current listing"));
    if (!calendar.isShared()) {
      throw new IllegalArgumentException(NOT_A_SHARE);
    }
    CalendarSync pair = new CalendarSync();
    pair.setUserIdentityId(userIdentityId);
    pair.setServerId(serverId);
    pair.setRemoteHref(href);
    // REMOTE, because that is what the collection is to the engine: made on
    // the server by somebody, owned by eXo in no way. See SyncOrigin.REMOTE.
    // An anchor derived from the path, not a calendar's: see
    // hiddenShareAnchor for why the column cannot stay null here. No sync
    // times, because nothing was synchronised — the due-pairs query reads
    // ACTIVE pairs alone, so a null lastSyncEnd makes nothing due.
    pair.setLocalCalendarSyncUid(hiddenShareAnchor(href));
    pair.setOrigin(SyncOrigin.REMOTE);
    pair.setStatus(CalendarSyncStatus.HIDDEN_SHARE);
    try {
      caldavSyncStorage.savePair(pair);
    } catch (RuntimeException e) {
      if (!CaldavSyncStorage.isDuplicateKey(e)) {
        throw e;
      }
      // The same hide, recorded by a concurrent request between the lookup
      // above and this save — a double click. The unique index refused the
      // second row for the very reason the anchor is derived from the path,
      // and the end state the caller asked for holds.
      LOG.debug("User {} hid the calendar {} twice at once; the first request recorded it", userIdentityId, href);
      return;
    }
    LOG.info("User {} hid the calendar {} shared with them; it leaves the remote calendars until shown again",
             userIdentityId,
             href);
  }

  /**
   * The anchor a hidden share's pair records (EXO-90239): a name-based UUID
   * of the collection path, never an agenda calendar's.
   *
   * <p>
   * A hidden share has no eXo calendar, so it has no anchor of its own; the
   * column cannot simply stay null all the same. The pair table's one unique
   * index is {@code (USER_IDENTITY_ID, SERVER_ID, LOCAL_CALENDAR_SYNC_UID)},
   * and while MySQL, PostgreSQL and HSQLDB leave a row with a null key column
   * outside it, Oracle enforces a composite unique key over the columns that
   * are not null and SQL Server treats nulls as equal: there, a second hidden
   * share of one user on one server — or a hidden share beside the mirror
   * pair, the other null anchor — would be refused. A value derived from the
   * path is unique per collection on every engine, and it makes that index
   * enforce what the service means besides: one record per hidden calendar,
   * whatever two requests race.
   *
   * <p>
   * It cannot name a calendar by accident. Agenda mints its anchors with
   * {@code UUID.randomUUID()}, version 4, and this is version 3, so the two
   * never meet; and every consumer that reads an anchor as a calendar's either
   * looks up agenda's own anchors, selects {@link SyncOrigin#EXO} pairs, or
   * selects {@link CalendarSyncStatus#ACTIVE} ones.
   *
   * @param canonicalHref the collection path, canonical
   * @return a 36-character anchor, the same for the same path
   */
  public static String hiddenShareAnchor(String canonicalHref) {
    return UUID.nameUUIDFromBytes(("caldav-hidden-share:" + canonicalHref).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /**
   * The calendars this user has hidden on this account.
   *
   * <p>
   * A tombstone is what makes a deletion stick: it keeps a collection from
   * being materialised again, and since the shim stopped serving bound
   * collections it keeps it off the screen entirely. That is what the user
   * asked for — and it leaves them with no way back, which is why this exists.
   * A hidden share ({@link CalendarSyncStatus#HIDDEN_SHARE}, EXO-90239) is
   * listed beside the tombstones for the same reason, told apart by
   * {@code shared}, and named with whoever shared it — the owner the calendar
   * list would name, resolved through the same service, so the drawer says
   * "Shared by Alice" about the very calendar the list said that of.
   *
   * <p>
   * The name comes from the server, read now rather than stored: a calendar
   * renamed in the user's own client since they hid it should be offered back
   * under the name they would recognise today. A collection the server no
   * longer has is left out — offering to show something that is gone would be
   * a promise nothing can keep. For a share that also means the owner
   * withdrew it; the sweep drops its record on its next pass
   * ({@code CaldavSyncService#forgetRevokedShares}), and this listing does
   * not, because a listing is read on every visit to the settings and must
   * not write.
   *
   * @param userIdentityId identity of the user
   * @param username the eXo login the credentials provider resolves the
   *          account from
   * @return what can be shown again, empty when nothing is hidden
   */
  public List<HiddenCalendar> listHidden(long userIdentityId, String username) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (settings == null || StringUtils.isBlank(settings.getUsername())) {
      return List.of();
    }
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    List<CalendarSync> hiddenPairs = caldavSyncStorage.getPairs(userIdentityId, serverId)
                                                      .stream()
                                                      .filter(CaldavDeletionService::isHidden)
                                                      .toList();
    if (hiddenPairs.isEmpty()) {
      // The common answer, and it costs no round trip: a section that is not
      // shown must not make the drawer wait on a server to find that out.
      return List.of();
    }
    Set<String> hrefs = hiddenPairs.stream()
                                   .map(pair -> CaldavSyncStorage.canonicalHref(pair.getRemoteHref()))
                                   .filter(StringUtils::isNotBlank)
                                   .collect(Collectors.toSet());
    // One listing, classified and named as the calendar list would have it:
    // the tombstones take their name from it, the shares their name and
    // their owner. A listing that failed describes nothing, and nothing is
    // offered — the rule the name lookup always applied.
    Map<String, RemoteCalendar> described = caldavReadService.describeCollections(userIdentityId, username, hrefs)
                                                             .calendars()
                                                             .stream()
                                                             .collect(Collectors.toMap(calendar -> CaldavSyncStorage.canonicalHref(calendar.getId()),
                                                                                       Function.identity(),
                                                                                       (first, second) -> first));
    List<HiddenCalendar> hidden = new ArrayList<>();
    for (CalendarSync pair : hiddenPairs) {
      RemoteCalendar calendar = described.get(CaldavSyncStorage.canonicalHref(pair.getRemoteHref()));
      if (calendar == null) {
        continue;
      }
      boolean share = pair.getStatus() == CalendarSyncStatus.HIDDEN_SHARE;
      hidden.add(new HiddenCalendar(pair.getId(),
                                    StringUtils.defaultIfBlank(calendar.getName(), calendar.getId()),
                                    share,
                                    share ? calendar.getOwnerDisplayName() : null));
    }
    return hidden;
  }

  /**
   * Whether a pair is one the hidden-calendars drawer offers back: a
   * calendar deleted here and kept on the account, or a share the user chose
   * not to see.
   *
   * @param pair a pair of the user's
   * @return true for {@link CalendarSyncStatus#LOCALLY_DELETED} and
   *         {@link CalendarSyncStatus#HIDDEN_SHARE}
   */
  private static boolean isHidden(CalendarSync pair) {
    return pair.getStatus() == CalendarSyncStatus.LOCALLY_DELETED || pair.getStatus() == CalendarSyncStatus.HIDDEN_SHARE;
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
   * <p>
   * For a hidden share (EXO-90239) dropping the pair is all there is: a
   * share is never materialised, so no synchronisation would bring anything
   * back, and the collection is back under the remote calendars on the very
   * next listing because it is unbound again. Synchronising here would spend
   * a full pass of the account to change nothing.
   *
   * @param userIdentityId identity of the user
   * @param pairId the tombstone to lift
   * @param username the user's login, which the synchronisation run here needs
   *          because agenda's ACL reads it rather than the identity id
   * @throws IllegalAccessException when the tombstone is not this user's
   * @throws ObjectNotFoundException when there is no such tombstone
   */
  public void showAgain(long userIdentityId, long pairId, String username) throws IllegalAccessException,
                                                                           ObjectNotFoundException {
    CalendarSync pair = caldavSyncStorage.getPair(pairId);
    if (pair == null || !isHidden(pair)) {
      throw new ObjectNotFoundException("No hidden calendar with id " + pairId);
    }
    if (pair.getUserIdentityId() != userIdentityId) {
      // The binding carries whose it is, and that is the only thing standing
      // between one user and another user's calendars.
      throw new IllegalAccessException("Binding " + pairId + " does not belong to user " + userIdentityId);
    }
    if (pair.getStatus() == CalendarSyncStatus.HIDDEN_SHARE) {
      caldavSyncStorage.deletePair(pairId);
      LOG.info("User {} shows the shared calendar {} again; it is back under the remote calendars on the next listing",
               userIdentityId,
               pair.getRemoteHref());
      return;
    }
    caldavSyncStorage.deleteObjects(pairId);
    caldavSyncStorage.deletePair(pairId);
    LOG.info("Tombstone {} lifted; synchronising so the collection comes back now", pairId);
    // Synchronised here rather than left to the next run. Lifting the
    // tombstone alone makes the collection unbound, and an unbound collection
    // is exactly what the Remote section lists — so a user who pressed "Show
    // again" watched their calendar reappear in the section for calendars eXo
    // is NOT showing, and stay there until the throttle expired.
    //
    // The failure is swallowed on purpose: the tombstone is lifted either
    // way, and the next run will materialise the collection. Reporting a sync
    // failure here would say the un-hiding did not happen, which is false.
    try {
      caldavSyncService.syncNow(userIdentityId, username);
    } catch (RuntimeException e) {
      LOG.warn("The calendar was un-hidden but could not be synchronised back at once", e);
    }
  }

  /**
   * What each of this user's calendars is doing, for the ones worth telling
   * them about.
   *
   * <p>
   * The engine has always known this per binding and kept it in the database
   * and the logs. A user seeing their agenda has no way to tell a calendar
   * that is synchronising from one whose server refused it — both simply sit
   * there — so this surfaces the states where something they might do would
   * change the outcome, and nothing else.
   *
   * <p>
   * Names come from the eXo calendar for a binding that has one, and from the
   * server for the ones that do not — a collection eXo was refused permission
   * to create has no eXo calendar to name it by.
   *
   * @param userIdentityId whose calendars
   * @param username the user's login, which agenda's ACL needs to read their
   *          calendars
   * @return the states worth showing, empty when everything is well
   */
  public List<CalendarSyncState> listSyncStates(long userIdentityId, String username) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (settings == null || StringUtils.isBlank(settings.getUsername())) {
      return List.of();
    }
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    List<CalendarSync> pairs = caldavSyncStorage.getPairs(userIdentityId, serverId)
                                                .stream()
                                                .filter(pair -> pair.getStatus() != null)
                                                .toList();
    if (pairs.isEmpty()) {
      return List.of();
    }
    Map<String, Calendar> byAnchor = calendarsByAnchorForStates(userIdentityId, username);
    List<CalendarSyncState> states = new ArrayList<>();
    Map<String, String> remoteNames = null;
    for (CalendarSync pair : pairs) {
      Calendar calendar = byAnchor.get(pair.getLocalCalendarSyncUid());
      CalendarSyncState state = new CalendarSyncState(pair.getId(),
                                                      calendar == null ? 0L : calendar.getId(),
                                                      calendar == null ? null
                                                                       : StringUtils.firstNonBlank(calendar.getName(),
                                                                                                   calendar.getTitle()),
                                                      pair.getStatus(),
                                                      pair.getLastSyncEnd() == null ? null
                                                                                    : pair.getLastSyncEnd().getTime());
      if (!state.worthTelling()) {
        continue;
      }
      if (StringUtils.isBlank(state.name())) {
        // No eXo calendar to name it by — a collection eXo was refused
        // permission to create, or one whose calendar is already gone. The
        // server is asked, once, and only when it turns out to be needed.
        if (remoteNames == null) {
          remoteNames = collectionNames(settings, username);
        }
        state = new CalendarSyncState(state.id(),
                                      state.calendarId(),
                                      remoteNames.get(CaldavSyncStorage.canonicalHref(pair.getRemoteHref())),
                                      state.status(),
                                      state.lastSyncEnd());
      }
      if (StringUtils.isNotBlank(state.name())) {
        states.add(state);
      }
    }
    return states;
  }

  /**
   * A user's calendars, keyed by the anchor a binding records.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   * @return the calendars by anchor, empty when they cannot be read
   */
  private Map<String, Calendar> calendarsByAnchorForStates(long userIdentityId, String username) {
    Map<String, Calendar> byAnchor = new HashMap<>();
    try {
      for (Calendar calendar : agendaCalendarService.getCalendars(0, Integer.MAX_VALUE, username)) {
        if (calendar.getOwnerId() == userIdentityId && !calendar.isDeleted()
            && StringUtils.isNotBlank(calendar.getSyncUid())) {
          byAnchor.put(calendar.getSyncUid(), calendar);
        }
      }
    } catch (Exception e) { // NOSONAR agenda declares a bare Exception here
      LOG.warn("The calendars of user {} could not be read; their states are named from the server", userIdentityId, e);
    }
    return byAnchor;
  }

  /**
   * The account's collections, by canonical path.
   *
   * @param settings the connected account
   * @param username the eXo login the credentials provider resolves the
   *          account from
   * @return their display names, empty when the server cannot be listed
   */
  private Map<String, String> collectionNames(CaldavUserSetting settings, String username) {
    Map<String, String> names = new HashMap<>();
    try {
      CalDavEndpoint endpoint = calDavClient.endpoint(settings.getServerId(), username);
      String home = calDavClient.discoverCalendarHome(endpoint);
      for (CalendarCollection collection : calDavClient.listCalendars(endpoint,
                                                                     home)) {
        names.put(CaldavSyncStorage.canonicalHref(collection.href()),
                  StringUtils.defaultIfBlank(collection.displayName(), collection.href()));
      }
    } catch (RuntimeException e) {
      // RuntimeException, not only CalDavException: this is called from
      // screens that must render whatever the account is doing — a settings
      // row that throws because a server is unreachable is a worse outcome
      // than a row with nothing in it. Nothing offered rather than a list of
      // paths the user never chose to see; they can try again when the server
      // answers.
      LOG.debug("The account's collections could not be listed; nothing is named from the server", e);
    }
    return names;
  }

  /**
   * Removes the collection and confirms it is gone.
   *
   * @param settings the connected account
   * @param username the eXo login the credentials provider resolves the
   *          account from
   * @param pair the binding whose collection goes
   * @return true when a fresh listing no longer shows it
   */
  private boolean removeCollection(CaldavUserSetting settings, String username, CalendarSync pair) {
    if (settings == null || StringUtils.isBlank(settings.getUsername())) {
      throw new CaldavPushException(CaldavPushService.NOT_CONNECTED,
                                    "No connected CalDAV account; nothing was deleted, in eXo or on the server");
    }
    CalDavEndpoint endpoint = calDavClient.endpoint(settings.getServerId(), username);
    try {
      calDavClient.deleteCollection(endpoint, pair);
      String home = calDavClient.discoverCalendarHome(endpoint);
      String target = CaldavSyncStorage.canonicalHref(pair.getRemoteHref());
      return calDavClient.listCalendars(endpoint, home)
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
