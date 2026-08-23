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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.ics.IcsReader;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.RemoteCalendar;
import org.exoplatform.caldav.model.RemoteIcsEvent;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.caldav.utils.CalendarPalette;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Reads the connected account's calendars server-side, so the page receives
 * mapped events instead of downloading and parsing every iCalendar object in
 * the main thread.
 *
 * <p>
 * One REPORT per calendar per request, bounded by how many calendars the
 * account holds — a handful. Not one per event: that is the shape this replaces.
 */
@Service
public class CaldavReadService {

  private static final Log       LOG = ExoLogger.getLogger(CaldavReadService.class);

  @Autowired
  private CalDavClient           calDavClient;

  @Autowired
  private CaldavConnectorStorage caldavConnectorStorage;

  @Autowired
  private IcsReader              icsReader;

  @Autowired
  private CaldavSyncStorage      caldavSyncStorage;

  /**
   * The calendars of the connected account.
   *
   * @param userIdentityId identity of the user
   * @return the calendars, each with a usable colour; empty when no account is
   *         connected
   */
  public List<RemoteCalendar> listCalendars(long userIdentityId) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!connected(settings)) {
      return List.of();
    }
    CalDavEndpoint endpoint = endpointOf(settings);
    List<CalendarCollection> collections = readableCollections(userIdentityId, endpoint, settings);
    List<String> order = CalendarPalette.inStableOrder(collections.stream().map(CalendarCollection::href).toList());
    List<RemoteCalendar> calendars = new ArrayList<>();
    for (CalendarCollection collection : collections) {
      if (isExoCreated(collection.href())) {
        // A collection eXo made, that eXo no longer has a binding for. It
        // cannot be materialised — the sync refuses its own creations — so
        // offering it here is offering something that can never become a
        // calendar. They appear after a database is restored or reset while
        // the account keeps what was pushed to it.
        continue;
      }
      if (!collection.holdsEvents()) {
        // The same refusal materialisation makes, for the same reason: a
        // CalDAV home publishes the account's task list beside its calendars,
        // and it answers a PROPFIND exactly as a calendar would. Listing it
        // here while refusing to materialise it left the Remote section alive
        // for a collection that can never hold an event — the one thing that
        // section exists to show.
        continue;
      }
      calendars.add(new RemoteCalendar(collection.href(),
                                       collection.displayName(),
                                       CalendarPalette.colourOf(collection.color(),
                                                                collection.href(),
                                                                order.indexOf(collection.href()),
                                                                order.size()),
                                       !collection.writable()));
    }
    return calendars;
  }

  /**
   * The events of the connected account over a window, one calendar at a time.
   *
   * <p>
   * A calendar that fails degrades to no events <i>for that calendar</i>,
   * exactly as the browser's Promise.allSettled did: one collection a server
   * refuses, or one object it cannot serialise, must not blank the whole
   * agenda. The failure is logged rather than raised, because the user's
   * remaining calendars are still worth showing.
   *
   * @param userIdentityId identity of the user
   * @param start beginning of the window
   * @param end end of the window
   * @return the occurrences, each tagged with the calendar it came from
   */
  public List<RemoteIcsEvent> readEvents(long userIdentityId, Instant start, Instant end) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!connected(settings) || start == null || end == null || !start.isBefore(end)) {
      return List.of();
    }
    CalDavEndpoint endpoint = endpointOf(settings);
    List<CalendarCollection> collections = readableCollections(userIdentityId, endpoint, settings);
    List<String> order = CalendarPalette.inStableOrder(collections.stream().map(CalendarCollection::href).toList());

    List<RemoteIcsEvent> events = new ArrayList<>();
    for (CalendarCollection collection : collections) {
      String colour = CalendarPalette.colourOf(collection.color(),
                                               collection.href(),
                                               order.indexOf(collection.href()),
                                               order.size());
      events.addAll(readCalendar(endpoint, settings, collection, colour, start, end));
    }
    return events;
  }

  /**
   * One calendar's occurrences, or none when reading it fails.
   *
   * @param endpoint the declared server
   * @param settings the connected account
   * @param collection the calendar to read
   * @param colour the colour its events are shown in
   * @param start beginning of the window
   * @param end end of the window
   * @return the occurrences, possibly empty
   */
  private List<RemoteIcsEvent> readCalendar(CalDavEndpoint endpoint,
                                            CaldavUserSetting settings,
                                            CalendarCollection collection,
                                            String colour,
                                            Instant start,
                                            Instant end) {
    List<RemoteIcsEvent> events = new ArrayList<>();
    try {
      List<CalendarObject> objects = calDavClient.calendarQuery(endpoint,
                                                                collection.href(),
                                                                start,
                                                                end,
                                                                settings.getUsername(),
                                                                settings.getPassword());
      for (CalendarObject object : objects) {
        events.addAll(readObject(object, collection.href(), colour, start, end));
      }
    } catch (CalDavAuthenticationException e) {
      // Worth its own line: every calendar of this account will fail the same
      // way, and the cause is a stale password rather than a broken calendar.
      LOG.warn("The stored CalDAV credentials were rejected while reading {}", collection.href(), e);
    } catch (CalDavException e) {
      LOG.warn("Calendar {} could not be read; its events are omitted from this answer", collection.href(), e);
    }
    return events;
  }

  /**
   * One object's occurrences, or none when it cannot be parsed.
   *
   * <p>
   * A single unreadable object must not cost the whole calendar. Some clients
   * write objects no parser accepts, and losing one meeting is better than
   * losing every meeting that shares its collection.
   *
   * @param object the calendar object as the server returned it
   * @param calendarId the collection it came from
   * @param colour the colour its events are shown in
   * @param start beginning of the window
   * @param end end of the window
   * @return the occurrences, possibly empty
   */
  private List<RemoteIcsEvent> readObject(CalendarObject object,
                                          String calendarId,
                                          String colour,
                                          Instant start,
                                          Instant end) {
    if (object == null || StringUtils.isBlank(object.calendarData())) {
      return List.of();
    }
    try {
      List<RemoteIcsEvent> occurrences = icsReader.read(object.calendarData(), start, end);
      occurrences.forEach(occurrence -> {
        occurrence.setCalendarId(calendarId);
        occurrence.setColor(colour);
      });
      return occurrences;
    } catch (RuntimeException e) {
      LOG.warn("Object {} could not be read; it is omitted from this answer", object.href(), e);
      return List.of();
    }
  }

  /**
   * The account's calendars, without the one eXo writes its own copies into.
   *
   * <p>
   * The mirror holds copies of events eXo already displays. Read back, every
   * one of them returns as a remote event and is drawn <b>next to the eXo
   * event it is a copy of</b> — the same meeting twice, at the same hour,
   * which reads as a bug in the sync rather than as a display rule.
   *
   * <p>
   * Excluded here rather than recognised later, on purpose. The front end used
   * to filter it by comparing calendar ids against the stored href, and those
   * two now live in different URL spaces: hrefs stored while the browser spoke
   * through the relay are rooted at {@code /caldav/rest/dav/{id}}, while the
   * server reports the collection's own path. A comparison that cannot match
   * is worse than no comparison — it fails silently and shows duplicates.
   * Not listing the collection at all leaves nothing to compare.
   *
   * <p>
   * Matched two ways, because either can be the one that holds: the href
   * recorded for this user, canonically, and the slug the collection path ends
   * with. The second survives a disconnect, which forgets the setting.
   *
   * @param endpoint the declared server
   * @param settings the connected account
   * @return the collections whose events belong on the agenda
   */
  private List<CalendarCollection> readableCollections(long userIdentityId,
                                                       CalDavEndpoint endpoint,
                                                       CaldavUserSetting settings) {
    String mirror = CaldavSyncStorage.canonicalHref(settings.getMirrorCalendarHref());
    Set<String> bound = boundCollections(userIdentityId, settings);
    return collectionsOf(endpoint, settings).stream()
                                            .filter(collection -> !isMirror(collection, mirror))
                                            .filter(collection -> !bound.contains(CaldavSyncStorage.canonicalHref(collection.href())))
                                            .toList();
  }

  /**
   * The collections eXo already accounts for, and so no longer shows here.
   *
   * <p>
   * This is the shim being retired, one collection at a time. A bound
   * collection's events are in an eXo calendar now — materialised from it, or
   * pushed to it — so serving them here as well would show the user every
   * meeting twice, once under Remote and once under their own calendar.
   *
   * <p>
   * A binding of <em>any</em> state counts, tombstones included. A tombstone
   * means the user deleted the eXo calendar, and the dialog that asked them
   * promised eXo would "simply stop showing it" — putting the collection back
   * under Remote would break that promise in the plainest way.
   *
   * <p>
   * A collection with no binding at all keeps being served: a materialisation
   * that has not happened yet, or one that failed, must leave the user seeing
   * their events rather than silently losing them.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   * @return the canonical paths eXo already holds, empty when none
   */
  /**
   * Whether a collection is one eXo created on the account.
   *
   * <p>
   * Read from the path, which eXo derives, rather than from a binding: the
   * point is precisely to recognise the ones no binding accounts for any more.
   *
   * @param href the collection path
   * @return true when eXo made it
   */
  private boolean isExoCreated(String href) {
    String slug = StringUtils.substringAfterLast(StringUtils.stripEnd(href, "/"), "/");
    return StringUtils.startsWith(slug, CaldavOutboundService.COLLECTION_PREFIX);
  }

  private Set<String> boundCollections(long userIdentityId, CaldavUserSetting settings) {
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    return caldavSyncStorage.getPairs(userIdentityId, serverId)
                            .stream()
                            .map(pair -> CaldavSyncStorage.canonicalHref(pair.getRemoteHref()))
                            .filter(StringUtils::isNotBlank)
                            .collect(Collectors.toSet());
  }

  /**
   * Whether a collection is the one eXo copies space events into.
   *
   * @param collection the collection to judge
   * @param storedMirror the href recorded for this user, canonical
   * @return true when it is the mirror
   */
  private boolean isMirror(CalendarCollection collection, String storedMirror) {
    String href = CaldavSyncStorage.canonicalHref(collection.href());
    if (href == null) {
      return false;
    }
    return href.equals(storedMirror) || href.endsWith("/" + CaldavPushService.MIRROR_COLLECTION_SLUG);
  }

  /**
   * The calendars of an account, or none when the server cannot be listed.
   *
   * @param endpoint the declared server
   * @param settings the connected account
   * @return the collections, possibly empty
   */
  private List<CalendarCollection> collectionsOf(CalDavEndpoint endpoint, CaldavUserSetting settings) {
    try {
      String home = calDavClient.discoverCalendarHome(endpoint, settings.getUsername(), settings.getPassword());
      return calDavClient.listCalendars(endpoint, home, settings.getUsername(), settings.getPassword());
    } catch (CalDavException e) {
      LOG.warn("The calendars of the connected account could not be listed", e);
      return List.of();
    }
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

  /**
   * The endpoint the account's server resolves to.
   *
   * @param settings the connected account
   * @return the endpoint
   */
  private CalDavEndpoint endpointOf(CaldavUserSetting settings) {
    return calDavClient.endpoint(settings.getServerId(), settings.getUsername());
  }
}
