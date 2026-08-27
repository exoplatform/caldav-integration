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
import org.exoplatform.caldav.model.RemoteCalendarsRead;
import org.exoplatform.caldav.model.RemoteEventsRead;
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
   * The calendars of the connected account, and whether the account could be
   * asked at all.
   *
   * <p>
   * The failure travels in the answer rather than as an exception. A caller
   * that receives a bare empty list cannot tell an account holding no calendar
   * from an account whose server is down, and the second is the case that
   * actually happens — so it is said, next to the list, instead of being left
   * in a log line no browser reads.
   *
   * @param userIdentityId identity of the user
   * @param username their eXo login, which the credentials provider maps to
   *          their account on the server
   * @return the calendars, each with a usable colour, and the flag saying
   *         whether the listing failed; empty and unfailed when no account is
   *         connected
   */
  public RemoteCalendarsRead listCalendars(long userIdentityId, String username) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!connected(settings)) {
      // Not a failure: there is no account to fail. A user who has connected
      // nothing must not be told their calendar server is down.
      return RemoteCalendarsRead.empty();
    }
    CalDavEndpoint endpoint = endpointOf(settings, username);
    CollectionListing listing = readableCollections(userIdentityId, endpoint, settings);
    List<CalendarCollection> collections = listing.collections();
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
    return new RemoteCalendarsRead(calendars, listing.failed());
  }

  /**
   * The events of the connected account over a window, one calendar at a time,
   * and which part of that reading failed.
   *
   * <p>
   * A calendar that fails degrades to no events <i>for that calendar</i>,
   * exactly as the browser's Promise.allSettled did: one collection a server
   * refuses, or one object it cannot serialise, must not blank the whole
   * agenda. The failure is still not raised, because the user's remaining
   * calendars are worth showing — but it is now <b>reported</b>, in the answer,
   * because a caller handed only the surviving events draws them as the whole
   * truth and says nothing is missing.
   *
   * <p>
   * Two grains, matching the two ways a read fails. The account could not be
   * asked at all — nothing was read, and the empty list is not an answer about
   * the user's week. Or the account answered and some of its collections did
   * not, in which case the events of the others are returned and only the
   * failed hrefs are named.
   *
   * @param userIdentityId identity of the user
   * @param username their eXo login, which the credentials provider maps to
   *          their account on the server
   * @param start beginning of the window
   * @param end end of the window
   * @return the occurrences, each tagged with the calendar it came from,
   *         beside the flag and the hrefs that say what was not read
   */
  public RemoteEventsRead readEvents(long userIdentityId, String username, Instant start, Instant end) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!connected(settings) || start == null || end == null || !start.isBefore(end)) {
      // Neither of these is a failure of the account: there is no account, or
      // there is no window worth asking about. Reporting them as one would put
      // a "could not be reached" banner on a user who connected nothing.
      return RemoteEventsRead.empty();
    }
    CalDavEndpoint endpoint = endpointOf(settings, username);
    CollectionListing listing = readableCollections(userIdentityId, endpoint, settings);
    if (listing.failed()) {
      // The account itself could not be asked, so there is no per-calendar
      // truth to report: not one collection was ever named.
      return RemoteEventsRead.unreachable();
    }
    List<CalendarCollection> collections = listing.collections();
    List<String> order = CalendarPalette.inStableOrder(collections.stream().map(CalendarCollection::href).toList());

    List<RemoteIcsEvent> events = new ArrayList<>();
    List<String> failedCalendars = new ArrayList<>();
    for (CalendarCollection collection : collections) {
      String colour = CalendarPalette.colourOf(collection.color(),
                                               collection.href(),
                                               order.indexOf(collection.href()),
                                               order.size());
      CalendarReading reading = readCalendar(endpoint, settings, collection, colour, start, end);
      events.addAll(reading.events());
      if (reading.failed()) {
        failedCalendars.add(collection.href());
      }
    }
    return new RemoteEventsRead(events, false, failedCalendars);
  }

  /**
   * One calendar's occurrences, and whether reading it failed.
   *
   * @param endpoint the declared server
   * @param settings the connected account
   * @param collection the calendar to read
   * @param colour the colour its events are shown in
   * @param start beginning of the window
   * @param end end of the window
   * @return the occurrences, possibly empty, beside the flag saying whether
   *         that emptiness is an answer or a failure
   */
  private CalendarReading readCalendar(CalDavEndpoint endpoint,
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
                                                                end);
      for (CalendarObject object : objects) {
        events.addAll(readObject(object, collection.href(), colour, start, end));
      }
    } catch (CalDavAuthenticationException e) {
      // Worth its own line: every calendar of this account will fail the same
      // way, and the cause is a stale password rather than a broken calendar.
      LOG.warn("The stored CalDAV credentials were rejected while reading {}", collection.href(), e);
      return new CalendarReading(List.of(), true);
    } catch (CalDavException e) {
      LOG.warn("Calendar {} could not be read; its events are omitted from this answer", collection.href(), e);
      return new CalendarReading(List.of(), true);
    }
    return new CalendarReading(events, false);
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
   * @param userIdentityId the identity whose mirror setting and bindings are
   *          read; both exclusions are that user's own, and another's would
   *          hide the wrong collections
   * @param endpoint the declared server
   * @param settings the connected account
   * @return the collections whose events belong on the agenda, beside the flag
   *         saying whether the account could be listed at all
   */
  private CollectionListing readableCollections(long userIdentityId,
                                                CalDavEndpoint endpoint,
                                                CaldavUserSetting settings) {
    CollectionListing listing = collectionsOf(endpoint, settings);
    if (listing.failed()) {
      return listing;
    }
    String mirror = CaldavSyncStorage.canonicalHref(settings.getMirrorCalendarHref());
    Set<String> bound = boundCollections(userIdentityId, settings);
    return new CollectionListing(listing.collections()
                                        .stream()
                                        .filter(collection -> !isMirror(collection, mirror))
                                        .filter(collection -> !bound.contains(CaldavSyncStorage.canonicalHref(collection.href())))
                                        .toList(),
                                 false);
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
   * The calendars of an account, or the statement that it could not be asked.
   *
   * <p>
   * This is the one failure that used to disappear entirely. The server being
   * down makes both endpoints answer with an empty list, and every caller
   * downstream — the REST layer, the connector, the three agenda views — read
   * that list as "this account holds nothing". The WARN below stays, and stays
   * the place to look for <i>why</i>; what changes is that it is no longer the
   * only place the failure exists.
   *
   * @param endpoint the declared server
   * @param settings the connected account
   * @return the collections, possibly empty, beside the flag saying whether
   *         that emptiness is an answer or a failure
   */
  private CollectionListing collectionsOf(CalDavEndpoint endpoint, CaldavUserSetting settings) {
    try {
      String home = calDavClient.discoverCalendarHome(endpoint);
      return new CollectionListing(calDavClient.listCalendars(endpoint,
                                                              home),
                                   false);
    } catch (CalDavException e) {
      LOG.warn("The calendars of the connected account could not be listed", e);
      return new CollectionListing(List.of(), true);
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
   * @param username their eXo login, which the credentials provider maps to
   *          their account on the server
   * @return the endpoint
   */
  private CalDavEndpoint endpointOf(CaldavUserSetting settings, String username) {
    return calDavClient.endpoint(settings.getServerId(), username);
  }

  /**
   * What listing an account's collections produced, failure included.
   *
   * <p>
   * Internal to this service: the outside world is told in the vocabulary of
   * {@link RemoteCalendarsRead} and {@link RemoteEventsRead}. It exists so the
   * flag survives the two filtering steps between the listing and the answer,
   * which a bare {@code List} would drop on the floor.
   *
   * @param collections the collections that were listed, empty on failure
   * @param failed true when the account could not be asked
   */
  private record CollectionListing(List<CalendarCollection> collections, boolean failed) {
  }

  /**
   * What reading one collection produced, failure included.
   *
   * @param events the occurrences read from it, empty on failure
   * @param failed true when the collection could not be read
   */
  private record CalendarReading(List<RemoteIcsEvent> events, boolean failed) {
  }
}
