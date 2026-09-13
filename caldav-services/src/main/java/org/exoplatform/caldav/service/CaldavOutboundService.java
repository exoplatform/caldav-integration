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
import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.MkCalendarResult;
import org.exoplatform.caldav.client.PropPatchResult;
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
 *
 * <p>
 * The path is the identity of a binding and the display name is data carried
 * along it: every pass that settles on a collection — listed, answered
 * directly, or just created — also makes sure the collection reads under the
 * calendar's current eXo name, and confirms that by reading the name back.
 * Before EXO-89528's fix the name was written once, inside MKCALENDAR, and
 * never again, so a calendar renamed in eXo kept its first name on the server
 * for good — and re-connecting the account could not help, because the
 * binding is recovered by path and found the same collection under the same
 * stale name.
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
   * <p>
   * This is the <b>first</b> step that talks to the server in both sequences
   * that reach it — a connection and a synchronisation pass — so what it does
   * with a failure decides how many credential-bearing requests the whole
   * sequence makes. Two failures are re-thrown rather than absorbed, because
   * they are properties of the <i>server</i> and are settled after one
   * attempt: {@link CalDavAuthenticationException} (a person must act) and
   * {@link CalDavUnreachableException} (nothing is there). Letting the caller
   * continue past either of them buys nothing and costs four more
   * authenticated requests against a server that may be counting them
   * (EXO-89806).
   *
   * <p>
   * Anything else stays absorbed exactly as before: a home this account
   * cannot be read out of, an answer that is not DAV XML, a quirk on one
   * account — those say nothing about the rest of the pass, whose later steps
   * ask the server different questions and may well be answered.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login, which agenda's ACL reads
   * @return the pairs, bound or refused, one per personal calendar
   * @throws CalDavAuthenticationException when the server refused the stored
   *           credentials
   * @throws CalDavUnreachableException when the server could not be reached
   */
  public List<CalendarSync> bindPersonalCalendars(long userIdentityId, String username) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!connected(settings)) {
      return List.of();
    }
    CalDavEndpoint endpoint = calDavClient.endpoint(settings.getServerId(), username);
    String home;
    List<CalendarCollection> collections;
    try {
      home = calDavClient.discoverCalendarHome(endpoint);
      collections = calDavClient.listCalendars(endpoint, home);
    } catch (CalDavAuthenticationException | CalDavUnreachableException e) {
      // Not logged here, and that is the point: the caller says it once, for
      // the whole sequence it is abandoning. Logging here as well would put
      // the same fact in the log twice per connection.
      throw e;
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
      reconcileDisplayName(settings, endpoint, existing.get(), calendar);
      return record(userIdentityId, serverId, anchor, existing.get().href(), CalendarSyncStatus.ACTIVE, pair);
    }
    CalendarCollection answering = pair == null ? null : askDirectly(settings, endpoint, pair.getRemoteHref());
    if (answering != null) {
      // The listing did not show it, the collection itself answers. Observed
      // live: a collection vanished from an account's home for a quarter of an
      // hour and came back, and the only reason eXo did not create a duplicate
      // is that the path it derives happens to be stable. A listing is a fair
      // way to confirm a collection is *there*; it is not evidence that one is
      // gone.
      reconcileDisplayName(settings, endpoint, answering, calendar);
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
                                                          null);
      // The status is never proof. One server answers 201 while creating
      // nothing; only reading the home back settles it.
      Optional<CalendarCollection> created = calDavClient.listCalendars(endpoint, home)
                                                         .stream()
                                                         .filter(collection -> isSameCollection(collection.href(),
                                                                                                null,
                                                                                                wanted))
                                                         .findFirst()
                                                         .or(() -> Optional.ofNullable(askDirectly(settings,
                                                                                                   endpoint,
                                                                                                   wanted)));
      if (created.isPresent()) {
        // MKCALENDAR carried the name, and the same rule applies to it: what
        // counts is the name the server holds, not the one the request asked.
        reconcileDisplayName(settings, endpoint, created.get(), calendar);
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
   * Makes the collection read under the calendar's current eXo name, and
   * confirms that it does.
   *
   * <p>
   * Nothing is sent when the listing already shows the name eXo would set, so
   * a pass over calendars nobody renamed costs no request. Otherwise the name
   * is written with a PROPPATCH and then <b>read back</b>: the status is never
   * the proof, exactly as it is not for MKCALENDAR — a server free to answer
   * 201 having created nothing is as free to answer 207 having changed nothing
   * — and only the name the collection reports afterwards says whether the
   * rename happened. A read-back that still shows the old name is logged as
   * such, so an administrator reads the fact rather than the claim.
   *
   * <p>
   * The name is data, never identity. Whatever the server did with it, the
   * binding is untouched: the pair still points at the same path, and a
   * refused or ignored rename leaves the calendar syncing under its old name
   * rather than unbound. eXo's name wins over one set from the user's own
   * client on a collection eXo created, because the collection is the
   * calendar's — a user who wants their calendar called something else
   * renames it in eXo, and every client of theirs follows.
   *
   * @param settings the connected account
   * @param endpoint the declared server
   * @param collection the collection as the server currently describes it
   * @param calendar the eXo calendar it is bound to
   */
  private void reconcileDisplayName(CaldavUserSetting settings,
                                    CalDavEndpoint endpoint,
                                    CalendarCollection collection,
                                    Calendar calendar) {
    String wanted = displayNameOf(calendar);
    if (StringUtils.equals(collection.displayName(), wanted)) {
      return;
    }
    String href = StringUtils.appendIfMissing(collection.href(), "/");
    try {
      PropPatchResult answer = calDavClient.setDisplayName(endpoint, href, wanted);
      CalendarCollection readBack = calDavClient.readCalendar(endpoint, href);
      String actual = readBack == null ? null : readBack.displayName();
      if (StringUtils.equals(actual, wanted)) {
        LOG.debug("Collection {} now reads under the current name of calendar {}", href, calendar.getId());
      } else {
        LOG.warn("The server answered {} to renaming collection {} for calendar {}, but read back it is still called"
            + " '{}' rather than '{}'; the binding stands and the next sync asks again",
                 answer.status(),
                 href,
                 calendar.getId(),
                 actual,
                 wanted);
      }
    } catch (CalDavException e) {
      LOG.warn("The name of calendar {} could not be carried to collection {}; the binding stands and the next sync"
          + " asks again", calendar.getId(), href, e);
    }
  }

  /**
   * The collection as it answers for itself, whatever a listing said.
   *
   * <p>
   * A home listing omitting a collection is not proof the collection is gone —
   * seen live, where one disappeared from an account's home for a quarter of
   * an hour and returned. That matters because the answer to "it is not there"
   * is to create it, and on a server that keeps both the user ends up with two
   * calendars where they had one.
   *
   * <p>
   * A server that cannot be reached answers null: an unreachable server is
   * not evidence either way, and treating it as "still there" would leave a
   * binding pointing at something nobody has confirmed.
   *
   * @param settings the connected account
   * @param endpoint the declared server
   * @param href the collection to ask about, or null
   * @return the collection as it describes itself, or null when it does not
   *         answer
   */
  private CalendarCollection askDirectly(CaldavUserSetting settings, CalDavEndpoint endpoint, String href) {
    if (StringUtils.isBlank(href)) {
      return null;
    }
    try {
      return calDavClient.readCalendar(endpoint, StringUtils.appendIfMissing(href, "/"));
    } catch (CalDavException e) {
      LOG.debug("Collection {} could not be asked about directly", href, e);
      return null;
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
   * Whether <em>an</em> eXo created this collection, judged from its path
   * alone — this deployment or any other.
   *
   * <p>
   * The path says which connector minted a collection, not which deployment:
   * every eXo derives the same {@link #COLLECTION_PREFIX} slug, so a
   * collection under it was made by this connector for one of some user's
   * calendars, on this instance or on another one writing into the same
   * account. That is as far as the path can see, and it used to be the whole
   * test (EXO-89530, EXO-90190): a pair check is scoped to one user, while
   * the collections in a shared account were made by any of them, so one
   * user's outbound copy looked, to another user's pair, like an ordinary
   * remote calendar to materialise, read and write back into — observed live
   * as one user's <code>exo-cal-946eec40…</code> coming back as another
   * user's calendar 23.
   *
   * <p>
   * The sweep and the push no longer stop at this answer. What they need to
   * know is whether the calendar behind the collection exists <em>here</em>,
   * which is {@link #isMintedByThisDeployment(long, String)}; a collection
   * another deployment minted is an ordinary remote calendar to them
   * (EXO-90226). This remains the cheap, stateless form for the places that
   * only need to tell eXo-shaped paths from the rest.
   *
   * @param href the collection path, canonical or not; only its last segment
   *          is read
   * @return true when the path is one an eXo derives for a user's own calendar
   */
  public static boolean isExoCreated(String href) {
    return anchorOf(href) != null;
  }

  /**
   * The calendar anchor a collection's slug carries, when eXo minted it.
   *
   * <p>
   * The inverse of {@link #collectionHref(String, String)}: the slug is the
   * prefix followed by agenda's calendar sync uid, and nothing else. Read
   * from the last segment alone, so a server that republishes the collection
   * under another parent — BlueMind lists eXo's collections under a path
   * other than the one they were created at — still yields the anchor.
   *
   * @param href the collection path, canonical or not
   * @return the anchor, or null when the slug is not one eXo mints, or
   *         carries nothing after the prefix
   */
  public static String anchorOf(String href) {
    String slug = StringUtils.substringAfterLast(StringUtils.stripEnd(href, "/"), "/");
    if (!StringUtils.startsWith(slug, COLLECTION_PREFIX)) {
      return null;
    }
    return StringUtils.defaultIfBlank(StringUtils.removeStart(slug, COLLECTION_PREFIX), null);
  }

  /**
   * Whether this deployment is the one that created this collection: its
   * slug is eXo's and a user here holds the calendar it stands for.
   *
   * <p>
   * The distinction a path prefix cannot make (EXO-90226). Two users on
   * <em>one</em> deployment sharing an account: the other user's calendar
   * already exists inside eXo, so importing its collection would duplicate
   * what eXo knows natively — skip it. Two <em>separate</em> deployments
   * sharing an account: the other instance's calendar exists nowhere here,
   * there is nothing to duplicate, and importing it is exactly what any
   * second CalDAV client connected to the account does. Both cases wear the
   * same prefix; only the pair table tells them apart, and since EXO-90190 it
   * can be asked account-wide rather than for one user.
   *
   * <p>
   * Ownership is asked two ways, and either answers. <b>By the anchor the
   * slug carries</b> first: the server may list the collection under a
   * parent other than the one eXo created it at — BlueMind republishes them
   * under {@code …/publish/…} — and the slug survives that. <b>By the
   * recorded path</b> second: the same server has also reported a collection
   * under a slug other than the one it was created with (EXO-89590 — prefix
   * kept, suffix replaced), and then the slug carries no anchor anyone here
   * holds, while an EXO pair recorded at that path still says the collection
   * is this deployment's. The path arm only ever adds skips, so it cannot
   * reintroduce what made the path wrong as the <em>sole</em> key: a
   * colleague's collection republished under another parent still answers
   * by its anchor. What neither arm answers is a collection republished
   * under another slug whose pair still records the created path; only a
   * captured BlueMind listing can say whether that shape occurs.
   *
   * <p>
   * The failure direction depends on which side kept its state. A deployment
   * <em>cloned</em> from another's database answers "ours" for the other's
   * collections, which is the skip that held before this question existed.
   * A deployment whose pair rows are <em>gone</em> while the collections are
   * not — wiped and re-seeded, restored to a point before the pairs were
   * written, re-pointed at an account it used to serve — answers "not ours"
   * for its own former collections and adopts them. Where the restore kept
   * agenda's calendars and their sync uids, that heals within one pass:
   * {@link #bindPersonalCalendars} runs before materialisation and re-records
   * the binding by the derived path, so materialisation finds the anchor
   * again. Only a full wipe adopts, and what it adopts is bounded — one extra
   * REMOTE-bound calendar per pre-wipe calendar, named from the collection,
   * holding the old events; no anchor collision, since materialisation
   * records the new anchor; no name-collision failure, since agenda accepts
   * two calendars of one name; and nothing pushed back out, since a REMOTE
   * pair stops {@code bind} before it creates anything. Visible and
   * reversible by the user, which is the property the adoption default rests
   * on.
   *
   * <p>
   * The sweep asks this before materialising a listed collection and before
   * reading through a binding; the push asks it before writing through one.
   * One definition, so the three answers cannot drift. A path outside the
   * outbound prefix asks nothing: no eXo minted it, and keeping the database
   * out of that case is what keeps the question cheap on a listing that is
   * mostly the user's own calendars.
   *
   * @param serverId the declared server registration
   * @param href the collection path, canonical or not
   * @return true when a calendar of this deployment stands behind it
   */
  public boolean isMintedByThisDeployment(long serverId, String href) {
    String anchor = anchorOf(href);
    if (anchor == null) {
      return false;
    }
    return caldavSyncStorage.isExoCalendarOnServer(serverId, anchor)
        || caldavSyncStorage.isExoCollectionOnServer(serverId, href);
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
