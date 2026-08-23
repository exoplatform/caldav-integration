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
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.MkCalendarResult;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Gives each of a user's eXo personal calendars a collection of its own on the
 * remote server.
 *
 * <p>
 * This is the first code that creates collections in the customer's own
 * calendar space. Until now eXo only ever wrote into a collection it had made
 * for itself, and everything here is shaped by that difference.
 */
@Service
public class CaldavOutboundService {

  /** Every collection this service creates is named from this prefix. */
  public static final String     COLLECTION_PREFIX = "exo-cal-";

  private static final Log       LOG               = ExoLogger.getLogger(CaldavOutboundService.class);

  @Autowired
  private CalDavClient           calDavClient;

  @Autowired
  private CaldavConnectorStorage caldavConnectorStorage;

  @Autowired
  private CaldavSyncStorage      caldavSyncStorage;

  @Autowired
  private AgendaCalendarService  agendaCalendarService;

  /**
   * Binds every personal calendar of a user to a collection on their server,
   * creating what does not exist yet.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login, which agenda's ACL reads
   * @return the pairs, bound or refused, one per personal calendar
   */
  public List<CalendarSync> bindPersonalCalendars(long userIdentityId, String username) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!connected(settings)) {
      return List.of();
    }
    CalDavEndpoint endpoint = calDavClient.endpoint(settings.getServerId(), settings.getUsername());
    String home;
    List<CalendarCollection> collections;
    try {
      home = calDavClient.discoverCalendarHome(endpoint, settings.getUsername(), settings.getPassword());
      collections = calDavClient.listCalendars(endpoint, home, settings.getUsername(), settings.getPassword());
    } catch (CalDavException e) {
      LOG.warn("The account's calendars could not be listed; personal calendars are not bound this round", e);
      return List.of();
    }
    return personalCalendarsOf(userIdentityId, username).stream()
                                                        .map(calendar -> bind(userIdentityId,
                                                                              settings,
                                                                              endpoint,
                                                                              home,
                                                                              collections,
                                                                              calendar))
                                                        .filter(java.util.Objects::nonNull)
                                                        .toList();
  }

  /**
   * The calendars a user owns, which are the ones eXo may mirror outward.
   *
   * <p>
   * A space calendar is deliberately absent: its events already travel to the
   * dedicated mirror, and giving a space a collection in one member's personal
   * account would put a shared calendar somewhere only that member can see.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   * @return the user's own calendars
   */
  private List<Calendar> personalCalendarsOf(long userIdentityId, String username) {
    try {
      return agendaCalendarService.getCalendars(0, Integer.MAX_VALUE, username)
                                  .stream()
                                  .filter(calendar -> calendar.getOwnerId() == userIdentityId)
                                  .filter(calendar -> !calendar.isDeleted())
                                  .toList();
    } catch (Exception e) { // NOSONAR agenda declares a bare Exception here
      LOG.warn("The personal calendars of user {} could not be read", userIdentityId, e);
      return List.of();
    }
  }

  /**
   * Binds one calendar, creating its collection when there is none.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   * @param endpoint the declared server
   * @param home the account's calendar home
   * @param collections what the server currently lists
   * @param calendar the eXo calendar to bind
   * @return the pair, or null when the calendar carries no anchor to bind by
   */
  private CalendarSync bind(long userIdentityId,
                            CaldavUserSetting settings,
                            CalDavEndpoint endpoint,
                            String home,
                            List<CalendarCollection> collections,
                            Calendar calendar) {
    String anchor = calendar.getSyncUid();
    if (StringUtils.isBlank(anchor)) {
      // Without an anchor there is nothing stable to bind by, and binding on
      // the calendar id instead would break the first time a restore renumbers
      // it. Skipped rather than bound wrongly.
      LOG.warn("Calendar {} carries no sync uid and cannot be bound", calendar.getId());
      return null;
    }
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    CalendarSync pair = caldavSyncStorage.getPairByLocalCalendar(userIdentityId, serverId, anchor);
    if (pair != null && pair.getOrigin() == SyncOrigin.REMOTE) {
      // This calendar exists *because* a collection was materialised into it.
      // Pushing it back out would give the user's own calendar a second
      // collection on their own server, and — because recording a binding
      // marks it ORIGIN=EXO — would relabel their collection as one eXo
      // created. Two harms follow from that single lie: the inbound pass
      // skips the collection, so its events never arrive again; and eXo
      // believes it may delete a calendar it never made. The structural guard
      // on the DELETE path refuses that today, but it should not be the only
      // thing standing in the way.
      LOG.debug("Calendar {} was materialised from a remote collection and is not pushed back out", calendar.getId());
      return pair;
    }
    String wanted = collectionHref(home, anchor);

    // The path carries the anchor, so a binding is recoverable from the server
    // alone: a pair row lost to a restore is found again by looking for the
    // collection whose path ends in this calendar's own uid. No stored state
    // is needed to recognise it.
    Optional<CalendarCollection> existing = collections.stream()
                                                       .filter(collection -> isSameCollection(collection.href(),
                                                                                              pair == null ? wanted
                                                                                                           : pair.getRemoteHref(),
                                                                                              wanted))
                                                       .findFirst();
    if (existing.isPresent()) {
      return record(userIdentityId, serverId, anchor, existing.get().href(), CalendarSyncStatus.ACTIVE, pair);
    }
    if (pair != null && stillThere(settings, endpoint, pair.getRemoteHref())) {
      // The listing did not show it, the collection itself answers. Observed
      // live: a collection vanished from an account's home for a quarter of an
      // hour and came back, and the only reason eXo did not create a duplicate
      // is that the path it derives happens to be stable. A listing is a fair
      // way to confirm a collection is *there*; it is not evidence that one is
      // gone.
      return record(userIdentityId, serverId, anchor, pair.getRemoteHref(), CalendarSyncStatus.ACTIVE, pair);
    }
    if (pair != null && pair.getStatus() == CalendarSyncStatus.REMOTE_CREATE_REFUSED) {
      // Asked once, refused once. Asking again on every sync would hammer a
      // server that has already said no, and the state is what the settings
      // show the user.
      return pair;
    }
    return create(userIdentityId, settings, endpoint, home, serverId, calendar, wanted, pair);
  }

  /**
   * Creates the collection for one calendar, and confirms it exists.
   *
   * <p>
   * Never adopted when the server refuses. A space event copied into a
   * calendar the user already had is a compromise they can see and undo; a
   * personal calendar's events written into a calendar that was never created
   * for them is corruption dressed as resilience — two calendars' events
   * mixed, with nothing recording which came from where.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   * @param endpoint the declared server
   * @param home the account's calendar home
   * @param serverId the declared server registration
   * @param calendar the eXo calendar being bound
   * @param wanted where the collection should live
   * @param pair the existing pair, or null
   * @return the pair, active or refused
   */
  private CalendarSync create(long userIdentityId,
                              CaldavUserSetting settings,
                              CalDavEndpoint endpoint,
                              String home,
                              long serverId,
                              Calendar calendar,
                              String wanted,
                              CalendarSync pair) {
    String anchor = calendar.getSyncUid();
    try {
      MkCalendarResult creation = calDavClient.mkCalendar(endpoint,
                                                          wanted,
                                                          displayNameOf(calendar),
                                                          null,
                                                          settings.getUsername(),
                                                          settings.getPassword());
      // The status is never proof. One server answers 201 while creating
      // nothing; only reading the home back settles it.
      boolean created = calDavClient.listCalendars(endpoint, home, settings.getUsername(), settings.getPassword())
                                    .stream()
                                    .anyMatch(collection -> isSameCollection(collection.href(), null, wanted))
          || stillThere(settings, endpoint, wanted);
      if (created) {
        return record(userIdentityId, serverId, anchor, wanted, CalendarSyncStatus.ACTIVE, pair);
      }
      LOG.info("The server would not create a collection for calendar {} (status {}); outbound is unavailable there",
               anchor,
               creation.status());
      return record(userIdentityId, serverId, anchor, wanted, CalendarSyncStatus.REMOTE_CREATE_REFUSED, pair);
    } catch (CalDavException e) {
      LOG.warn("The collection for calendar {} could not be created", anchor, e);
      return pair;
    }
  }

  /**
   * Writes the pair down, creating the row on first binding.
   *
   * @param userIdentityId identity of the user
   * @param serverId the declared server registration
   * @param anchor agenda's calendar sync uid
   * @param href where the collection lives
   * @param status the pair's state
   * @param existing the pair to update, or null
   * @return the persisted pair
   */
  private CalendarSync record(long userIdentityId,
                              long serverId,
                              String anchor,
                              String href,
                              CalendarSyncStatus status,
                              CalendarSync existing) {
    CalendarSync pair = existing == null ? new CalendarSync() : existing;
    pair.setUserIdentityId(userIdentityId);
    pair.setServerId(serverId);
    pair.setLocalCalendarSyncUid(anchor);
    pair.setRemoteHref(href);
    // ORIGIN=EXO is what tells the inbound sweep to leave this collection
    // alone. Without it every collection created here would be materialised
    // back as a second eXo calendar, which this service would then push out as
    // a third collection, and so on.
    pair.setOrigin(SyncOrigin.EXO);
    pair.setStatus(status);
    pair.setLastSyncEnd(new Date());
    return caldavSyncStorage.savePair(pair);
  }

  /**
   * Whether a listed collection is the one a calendar is bound to.
   *
   * @param listed the collection the server reports
   * @param storedHref the href recorded for the pair, or null
   * @param derivedHref the path the anchor produces
   * @return true when they name the same collection
   */
  private boolean isSameCollection(String listed, String storedHref, String derivedHref) {
    String href = CaldavSyncStorage.canonicalHref(listed);
    if (href == null) {
      return false;
    }
    return href.equals(CaldavSyncStorage.canonicalHref(storedHref))
        || href.equals(CaldavSyncStorage.canonicalHref(derivedHref));
  }

  /**
   * Whether the collection answers for itself, whatever a listing said.
   *
   * <p>
   * A home listing omitting a collection is not proof the collection is gone —
   * seen live, where one disappeared from an account's home for a quarter of
   * an hour and returned. That matters because the answer to "it is not there"
   * is to create it, and on a server that keeps both the user ends up with two
   * calendars where they had one.
   *
   * <p>
   * A server that cannot be reached answers false: an unreachable server is
   * not evidence either way, and treating it as "still there" would leave a
   * binding pointing at something nobody has confirmed.
   *
   * @param settings the connected account
   * @param endpoint the declared server
   * @param href the collection to ask about, or null
   * @return true when the collection itself answers
   */
  private boolean stillThere(CaldavUserSetting settings, CalDavEndpoint endpoint, String href) {
    if (StringUtils.isBlank(href)) {
      return false;
    }
    try {
      return calDavClient.readCalendar(endpoint,
                                       StringUtils.appendIfMissing(href, "/"),
                                       settings.getUsername(),
                                       settings.getPassword()) != null;
    } catch (CalDavException e) {
      LOG.debug("Collection {} could not be asked about directly", href, e);
      return false;
    }
  }

  /**
   * Where a calendar's collection lives, derived from its anchor alone.
   *
   * @param home the account's calendar home
   * @param anchor agenda's calendar sync uid
   * @return the collection href
   */
  private String collectionHref(String home, String anchor) {
    return StringUtils.appendIfMissing(home, "/") + COLLECTION_PREFIX + anchor + "/";
  }

  /**
   * The name the collection presents itself under in the user's own client.
   *
   * @param calendar the eXo calendar being bound
   * @return the calendar's own name, or a uid-derived last resort
   */
  private String displayNameOf(Calendar calendar) {
    // What the user reads in their own client. The uid was never a name: a
    // collection called "eXo c434ba2a-3f58-…" tells its owner nothing about
    // which of their calendars it is.
    //
    // getName() first, and the order is the whole point. getTitle() is the
    // display field agenda computes, and for a personal calendar it resolves
    // to the *owner's* identity — so preferring it names every one of a
    // user's collections after the user, which is just the old uid problem
    // wearing a friendlier face. The computed title is still the right last
    // resort for a calendar that genuinely has no name of its own, such as
    // the system one.
    return StringUtils.firstNonBlank(calendar.getName(), calendar.getTitle(), "eXo " + calendar.getSyncUid());
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
