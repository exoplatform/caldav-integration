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
import java.util.Map;
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
import org.exoplatform.caldav.client.CalendarHome;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.ics.IcsReader;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
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

  @Autowired
  private CaldavOutboundService  caldavOutboundService;

  @Autowired
  private CaldavCalendarOwnerService caldavCalendarOwnerService;

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
    // What this one listing finds out about owners, so that a colleague who
    // shared three calendars is looked up, or asked what she calls herself,
    // once, and a server's count of unrecorded users is asked once however
    // many colleagues share (EXO-90237, EXO-90243). Per listing on purpose.
    CalendarOwnerMemo owners = new CalendarOwnerMemo();
    for (CalendarCollection collection : collections) {
      if (!collection.holdsEvents()) {
        // The same refusal materialisation makes, for the same reason: a
        // CalDAV home publishes the account's task list beside its calendars,
        // and it answers a PROPFIND exactly as a calendar would. Listing it
        // here while refusing to materialise it left the Remote section alive
        // for a collection that can never hold an event — the one thing that
        // section exists to show.
        continue;
      }
      // The one classification the sweep runs on the same listing, with the
      // same principal and the same pairs (EXO-90234). Asked after the
      // component test, so a task list never costs the account-wide question.
      CollectionOwnership ownership = caldavOutboundService.ownershipOf(serverId(settings),
                                                                        listing.principal(),
                                                                        listing.pairs(),
                                                                        collection);
      if (CaldavOutboundService.isExoCreated(collection.href()) && !ownership.isShared()) {
        // A collection an eXo made, that this user has no binding for and
        // that is nobody else's. Either the user's own eXo calendar, met again
        // under a path BlueMind republished it at — the sync skips it, so
        // offering it here is offering something that can never become a
        // calendar; or another eXo deployment's, which the sync adopts as an
        // ordinary remote calendar on its next pass (EXO-90226) and which is
        // then bound, and so excluded from this list by the binding rather
        // than by the path. Neither belongs under Remote. A colleague's eXo
        // calendar shared with the user does (EXO-90234): the sync never
        // binds it, so this list is the only place it can appear, and it is
        // classified a share above rather than dropped on its prefix here.
        continue;
      }
      calendars.add(remoteCalendarOf(userIdentityId, settings, endpoint, listing, collection, ownership, order, owners));
    }
    return new RemoteCalendarsRead(calendars, listing.failed());
  }

  /**
   * The named collections of the connected account, bound or not, each
   * classified and named as {@link #listCalendars} would have listed it
   * (EXO-90239).
   *
   * <p>
   * What the hidden-calendars listing asks. A hidden calendar is bound by
   * construction — the binding is what hides it — so {@link #listCalendars},
   * which serves the unbound, can never describe one; and the drawer that
   * offers it back needs what the list would have said about it: the name
   * the server gives it today, whether it is a share, and whose. Answered
   * from the same listing, the same classification and the same owner lookup
   * the list uses, so a calendar reads the same on its way out of the agenda
   * as on its way back in. Neither the bound filter nor the mirror exclusion
   * applies: the caller names what it wants to hear about, and hears about
   * those the server still lists. A collection absent from the answer is one
   * the account no longer holds — or, when {@code failed} says so, one
   * nothing could be asked about.
   *
   * @param userIdentityId identity of the user
   * @param username their eXo login, which the credentials provider maps to
   *          their account on the server
   * @param hrefs the collection paths to describe, canonical
   * @return the collections the server still lists among those named, in
   *         the shape of the calendar list, beside the flag saying whether
   *         the listing failed; empty and unfailed when nothing was asked or
   *         no account is connected
   */
  public RemoteCalendarsRead describeCollections(long userIdentityId, String username, Set<String> hrefs) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!connected(settings) || hrefs == null || hrefs.isEmpty()) {
      return RemoteCalendarsRead.empty();
    }
    CalDavEndpoint endpoint;
    CollectionListing account;
    try {
      endpoint = endpointOf(settings, username);
      account = collectionsOf(endpoint, settings);
    } catch (RuntimeException e) {
      // Resolving the endpoint happens before the listing's own catch, and
      // fails on its own: no server declared any more, a login no URL can
      // carry, a declared URL that is not one. The hidden-calendars row is a
      // settings screen that must render whatever the account is doing, so
      // this is a listing that failed — the rule the name lookup it replaced
      // always applied — never an exception the endpoint answers as a 500.
      LOG.debug("The collections of user {} could not be described", userIdentityId, e);
      return new RemoteCalendarsRead(List.of(), true);
    }
    if (account.failed()) {
      return new RemoteCalendarsRead(List.of(), true);
    }
    // The user's pairs travel with the listing for the same reason they do
    // in readableCollections: the classification reads them to tell the
    // user's own exported calendar from a colleague's.
    CollectionListing listing = new CollectionListing(account.collections()
                                                             .stream()
                                                             .filter(collection -> hrefs.contains(CaldavSyncStorage.canonicalHref(collection.href())))
                                                             .toList(),
                                                      false,
                                                      account.principal(),
                                                      caldavSyncStorage.getPairs(userIdentityId, serverId(settings)));
    List<String> order = CalendarPalette.inStableOrder(listing.collections().stream().map(CalendarCollection::href).toList());
    CalendarOwnerMemo owners = new CalendarOwnerMemo();
    List<RemoteCalendar> calendars = new ArrayList<>();
    for (CalendarCollection collection : listing.collections()) {
      CollectionOwnership ownership = caldavOutboundService.ownershipOf(serverId(settings),
                                                                        listing.principal(),
                                                                        listing.pairs(),
                                                                        collection);
      calendars.add(remoteCalendarOf(userIdentityId, settings, endpoint, listing, collection, ownership, order, owners));
    }
    return new RemoteCalendarsRead(calendars, false);
  }

  /**
   * One listed collection in the shape agenda expects, whose it is already
   * settled.
   *
   * <p>
   * Read-only when the server granted no write, as before — and also when
   * the collection is somebody else's, whichever witness said so: the server
   * naming another owner (EXO-90235), or this deployment recognising a
   * colleague's exported calendar (EXO-90234). It is the same classification
   * the sweep refuses to materialise on, so a calendar the sweep will never
   * make the user's own is never offered here as one they could write into.
   * The three paths — skip, list, serve — agree because they share the one
   * question rather than three spellings of it.
   *
   * <p>
   * Shared is said beside read-only, not folded into it (EXO-90237): agenda
   * groups the shares under "Shared with me" and locks the read-only, and a
   * calendar of the user's own the server will not let them write is the
   * second without being the first. The owner is named from the witness that
   * made it a share — the colleague's pair, or the principal the server
   * returned — and is nobody when neither can say. The owner's kind travels
   * beside it (EXO-90275): a resource the user subscribed to on BlueMind is
   * named by the resource's name and said to be a resource, so the list draws
   * it as one rather than with an avatar.
   *
   * @param userIdentityId identity of the user the list is for, who is never
   *          named as the owner of a calendar shared with them
   * @param settings the connected account
   * @param endpoint the declared server
   * @param listing the listing the collection came from, for its principal
   * @param collection the listed collection
   * @param ownership whose it is, as the classification answered
   * @param order every listed href in the palette's stable order
   * @param owners what this listing already found out about owners
   * @return the calendar as agenda receives it
   */
  private RemoteCalendar remoteCalendarOf(long userIdentityId,
                                          CaldavUserSetting settings,
                                          CalDavEndpoint endpoint,
                                          CollectionListing listing,
                                          CalendarCollection collection,
                                          CollectionOwnership ownership,
                                          List<String> order,
                                          CalendarOwnerMemo owners) {
    boolean readOnly = !collection.writable() || ownership.isShared();
    CalendarOwner owner = caldavCalendarOwnerService.ownerOf(userIdentityId,
                                                             serverId(settings),
                                                             endpoint,
                                                             listing.principal(),
                                                             ownership,
                                                             collection,
                                                             owners);
    return new RemoteCalendar(collection.href(),
                              collection.displayName(),
                              CalendarPalette.colourOf(collection.color(),
                                                       collection.href(),
                                                       order.indexOf(collection.href()),
                                                       order.size()),
                              readOnly,
                              ownership.isShared(),
                              owner.identityId(),
                              owner.username(),
                              owner.displayName(),
                              ownership.ownerKind());
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
    // Loaded once and carried with the listing: the bound filter below reads
    // them, and the classification the calendar list runs reads them again
    // (EXO-90234) — the user's own EXO pairs are what tell their own exported
    // calendar from a colleague's before the database is asked.
    List<CalendarSync> pairs = caldavSyncStorage.getPairs(userIdentityId, serverId(settings));
    Set<String> bound = boundCollections(pairs);
    // A collection a colleague shared is not filtered here, on purpose: the
    // sweep never binds it (EXO-90235, EXO-90234), so it stays unbound, and an
    // unbound collection is precisely what this path exists to serve. Its
    // events reach the agenda read-only through here, which is the only way
    // they reach it.
    return new CollectionListing(listing.collections()
                                        .stream()
                                        .filter(collection -> !isMirror(collection, mirror))
                                        .filter(collection -> !bound.contains(CaldavSyncStorage.canonicalHref(collection.href())))
                                        .toList(),
                                 false,
                                 listing.principal(),
                                 pairs);
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
   * under Remote would break that promise in the plainest way. A hidden
   * share ({@code CalendarSyncStatus#HIDDEN_SHARE}, EXO-90239) counts by the
   * same rule and for the same reason: it is a pair that exists precisely so
   * that this filter drops the collection and its events stop being served.
   *
   * <p>
   * A collection with no binding at all keeps being served: a materialisation
   * that has not happened yet, or one that failed, must leave the user seeing
   * their events rather than silently losing them.
   *
   * @param pairs every pair this user holds on this server
   * @return the canonical paths eXo already holds, empty when none
   */
  private Set<String> boundCollections(List<CalendarSync> pairs) {
    return pairs.stream()
                .map(pair -> CaldavSyncStorage.canonicalHref(pair.getRemoteHref()))
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
  }

  /**
   * The declared server an account is connected to, as the pair table keys
   * it.
   *
   * @param settings the connected account
   * @return the server registration, zero when the account names none
   */
  private long serverId(CaldavUserSetting settings) {
    return settings.getServerId() == null ? 0L : settings.getServerId();
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
      // The principal comes out of the same walk that finds the home, so
      // telling a colleague's calendar from the user's own costs this listing
      // no request it was not already making (EXO-90235).
      CalendarHome account = calDavClient.discoverHome(endpoint);
      return new CollectionListing(calDavClient.listCalendars(endpoint,
                                                              account.href()),
                                   false,
                                   account.principal(),
                                   List.of());
    } catch (CalDavException e) {
      LOG.warn("The calendars of the connected account could not be listed", e);
      return new CollectionListing(List.of(), true, null, List.of());
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
   * @param principal the account's own {@code current-user-principal}, as the
   *          server named it during the walk that found the home; null on
   *          failure, and null when the server named none — which leaves the
   *          owner comparison off rather than pointing it at anybody
   * @param pairs every pair the user holds on this server, loaded once for
   *          the bound filter and read again by the classification the
   *          calendar list runs (EXO-90234); empty before the filter ran, and
   *          on failure
   */
  private record CollectionListing(List<CalendarCollection> collections,
                                   boolean failed,
                                   String principal,
                                   List<CalendarSync> pairs) {
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
