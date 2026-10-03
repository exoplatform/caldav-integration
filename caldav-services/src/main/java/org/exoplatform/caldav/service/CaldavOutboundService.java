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
import org.exoplatform.caldav.client.CalendarHome;
import org.exoplatform.caldav.client.MkCalendarResult;
import org.exoplatform.caldav.client.PropPatchResult;
import org.exoplatform.caldav.client.bluemind.BlueMindContainerNaming;
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

  @Autowired
  private CaldavConnectionIdentityService caldavConnectionIdentityService;

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
   * <p>
   * Being that first step, it is also where the account's identity is
   * recorded (EXO-90243): the discovery answers the
   * {@code current-user-principal} on its way to the home, so keeping it costs
   * no request, and it is kept on connect and at the start of every pass
   * alike. Recorded as soon as the discovery answers, before the listing,
   * because who the account is does not depend on whether its calendars can
   * be listed.
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
      CalendarHome account = calDavClient.discoverHome(endpoint);
      home = account.href();
      caldavConnectionIdentityService.recordPrincipal(userIdentityId,
                                                      settings.getServerId() == null ? 0L : settings.getServerId(),
                                                      account.principal());
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
   * The sweep asks this before reading through a binding, the push before
   * writing through one, and {@link #ownershipOf} — which the sweep and the
   * read-through both classify a listed collection with — asks it for a
   * collection none of the user's own pairs recognise. One definition, so
   * the answers cannot drift. A path outside the outbound prefix asks
   * nothing: no eXo minted it, and keeping the database out of that case is
   * what keeps the question cheap on a listing that is mostly the user's own
   * calendars.
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
   * Whether a user of this deployment, anyone, holds a calendar for the
   * container a collection stands for — under whatever home the server
   * listed it to them (EXO-90347).
   *
   * <p>
   * The third arm of the deployment's word, for what
   * {@link #isMintedByThisDeployment} cannot see: a calendar a colleague
   * <em>imported</em> rather than exported. Her pair is REMOTE, its slug is
   * not her anchor, and its recorded path is under her home while the
   * listing spells the same container under the sharee's — so neither the
   * anchor nor the path answers, and the container uid, the last segment,
   * is what both spellings share. Asked only once the server's own listing
   * has named another owner for the collection, to tell a colleague's
   * calendar (held here, {@link CollectionOwnership#COLLEAGUES_EXO_CALENDAR})
   * from one shared from outside this deployment
   * ({@link CollectionOwnership#SHARED}); either way the collection is not
   * adopted, so the answer names the more useful fact rather than deciding
   * the adoption.
   *
   * @param serverId the declared server registration
   * @param containerUid the collection's last path segment
   * @return true when a calendar binding of any user here records that
   *         container
   */
  public boolean isHeldByThisDeployment(long serverId, String containerUid) {
    return caldavSyncStorage.isCollectionHeldOnServer(serverId, containerUid);
  }

  /**
   * The container uid a collection is named by: its last path segment.
   *
   * @param href the collection path, canonical or not
   * @return the last segment, or null for a blank path
   */
  public static String containerUidOf(String href) {
    String canonical = CaldavSyncStorage.canonicalHref(href);
    if (StringUtils.isBlank(canonical)) {
      return null;
    }
    return StringUtils.defaultIfBlank(StringUtils.substringAfterLast(canonical, "/"), canonical);
  }

  /**
   * Which user of this deployment exported the calendar a collection stands
   * for — the <em>who</em> form of {@link #isMintedByThisDeployment}.
   *
   * <p>
   * Asked by the calendar list once {@link #ownershipOf} has classified a
   * collection as a colleague's eXo calendar and the list wants to name the
   * colleague (EXO-90237). The same two arms in the same order — the anchor
   * the slug carries first, the recorded path second — so it names the user
   * behind the very pair that made the boolean form say yes, and cannot
   * name somebody else's. The boolean form stays the sweep's and the push's
   * question, deliberately: they need only the yes, an existence query is
   * cheaper than a row, and rewriting them over this one would add a fetch
   * to every pass for an answer they discard.
   *
   * <p>
   * Null is a plain answer, not a failure: a path outside the outbound
   * prefix, a collection another deployment minted, or a pair that vanished
   * between the classification and this question all name nobody, and the
   * caller says nothing about the owner rather than guessing.
   *
   * @param serverId the declared server registration
   * @param href the collection path, canonical or not
   * @return the identity of the user whose EXO pair stands behind the
   *         collection, or null when no user of this deployment does
   */
  public Long exportingUserOf(long serverId, String href) {
    String anchor = anchorOf(href);
    if (anchor == null) {
      return null;
    }
    CalendarSync pair = caldavSyncStorage.getExoCalendarPairOnServer(serverId, anchor);
    if (pair == null) {
      pair = caldavSyncStorage.getExoCollectionPairOnServer(serverId, href);
    }
    return pair == null ? null : pair.getUserIdentityId();
  }

  /**
   * Whose a listed collection is, to the user whose account listed it.
   *
   * <p>
   * The one classification the sweep, the calendar list and the event
   * read-through act on (EXO-90234), combining the two witnesses
   * {@link CollectionOwnership} names. <b>This deployment</b> is heard first,
   * and only about a collection under the outbound prefix: the slug carries
   * the anchor eXo minted it with, and the question is whose calendar that
   * anchor names here. The user's own pairs, already in hand, answer first
   * and for free — an EXO pair of theirs carrying that anchor, or recorded
   * at that path, makes it {@link CollectionOwnership#OWN_EXO_CALENDAR},
   * their own calendar met again under a path BlueMind republished it at.
   * Failing that, the account-wide question
   * ({@link #isMintedByThisDeployment}) says whether <em>another</em> user
   * of this deployment exported it, and a yes is
   * {@link CollectionOwnership#COLLEAGUES_EXO_CALENDAR}: the colleague shared
   * their eXo calendar, eXo already holds the original, and the user must
   * never be given a writable copy of it. <b>The server</b> is heard second,
   * for every collection, through
   * {@link CalendarCollection#isSharedWith(String)}; a collection neither
   * witness speaks against is {@link CollectionOwnership#OWN}.
   *
   * <p>
   * The deployment before the server, on purpose, although the server's word
   * costs no query. On BlueMind the server is silent: a subscribed share is
   * listed under the user's own home, names the user as owner and grants the
   * full privilege set (observed live, 2026-09-13), so the deployment's word
   * is the only one there is. On Stalwart both speak — the colleague's
   * collection is listed at her path with her as owner and read-only — and
   * hearing the deployment first names the more useful fact in the line the
   * sweep writes: not merely "somebody else's", but "a calendar of this
   * deployment, another user's". The cost is bounded as before: one
   * account-wide question per pass per prefixed collection the user holds no
   * pair for, which after this change is exactly the set of colleagues'
   * shares and other deployments' collections.
   *
   * <p>
   * <b>The server's naming</b> is heard between the two (EXO-90275). BlueMind
   * lists a calendar the user subscribed to — a colleague's main or created
   * calendar, a pool vehicle, a room — under the user's own home with the
   * user as owner and the full privilege set, so neither server signal fires;
   * the container uid it names the collection by carries the owner instead
   * ({@link BlueMindContainerNaming}). A uid other than the account's own is
   * {@link CollectionOwnership#SUBSCRIBED_PERSON} or
   * {@link CollectionOwnership#SUBSCRIBED_RESOURCE}. Before the server's two
   * signals, so that a resource is told from a person wherever both could
   * speak; after the deployment, because no eXo-minted slug has that shape.
   * It costs no query. Until it was read, a subscribed resource and a
   * subscribed colleague's main calendar were materialised as the user's own
   * calendars (rig calendar 16, acceptance calendar 33).
   *
   * <p>
   * <b>The server's own listing</b> is heard last among the deployment's
   * questions, and only for a prefixed collection the other three could not
   * settle (EXO-90347). Everything above reads what eXo <em>exported</em>;
   * what none of it can see is a calendar a colleague <em>imported</em> —
   * made by eXo's naming elsewhere, adopted by her as a REMOTE pair whose
   * slug is not her anchor — and then shared. On BlueMind that share is
   * listed under the sharee's own home with the sharee as owner, so it
   * classified as the sharee's own and was adopted (rig calendar 24). The
   * one place BlueMind says whose it is, is its subscription listing
   * ({@link AccountCalendarOwners}): an owner other than the account's own
   * makes it a share — a colleague's when a user here holds the container
   * ({@link #isHeldByThisDeployment}), somebody else's otherwise — and the
   * account's own uid lets it through to adoption, however it was created:
   * eXo is one CalDAV client among several, and a calendar the server says
   * is the account's own is the account's to adopt. A listing that cannot
   * be had, or that does not name the collection, withholds the answer
   * rather than giving one: the naming and the DAV signals below are still
   * heard, a collection they call a share is a share, and only one they
   * would have called the account's own is
   * {@link CollectionOwnership#OWNER_UNKNOWN} — nothing is adopted on it,
   * and the next pass asks again. So the listing is heard after the
   * deployment's two arms and before the server's own signals: where it
   * withholds its answer it can only ever replace one that would have been
   * {@code OWN}, and where it names another owner it answers before the DAV
   * signals — which on BlueMind cannot contradict it: the subscriber is the
   * DAV owner there, so those signals call a writable share the account's
   * own (the defect this arm closes) and a read-only one a share as well —
   * and tells a colleague's share from an outsider's, which those signals
   * cannot. A server that is not asked — not BlueMind — leaves this arm
   * silent and the classification exactly as before.
   *
   * @param serverId the declared server registration, which scopes the
   *          account-wide question
   * @param principal the account's own {@code current-user-principal}, as
   *          the discovery walk answered it; null when the server named none,
   *          which leaves the owner comparison off
   * @param usersPairs every pair this user holds on this server, whatever
   *          its origin or state
   * @param collection the listed collection
   * @return whose it is
   */
  public CollectionOwnership ownershipOf(long serverId,
                                         String principal,
                                         List<CalendarSync> usersPairs,
                                         CalendarCollection collection) {
    return ownershipOf(serverId, principal, usersPairs, collection, AccountCalendarOwners.silent());
  }

  /**
   * Whose a listed collection is, with the server's own word on its owners
   * heard — the form the sweep and the calendar list ask (EXO-90347).
   *
   * @param serverId the declared server registration, which scopes the
   *          account-wide question
   * @param principal the account's own {@code current-user-principal}, as
   *          the discovery walk answered it; null when the server named none
   * @param usersPairs every pair this user holds on this server, whatever
   *          its origin or state
   * @param collection the listed collection
   * @param owners what the server says about who owns the account's
   *          calendars, asked once per pass; the silent witness for a server
   *          that is not asked, and read as silent when null
   * @return whose it is
   * @see #ownershipOf(long, String, List, CalendarCollection)
   */
  public CollectionOwnership ownershipOf(long serverId,
                                         String principal,
                                         List<CalendarSync> usersPairs,
                                         CalendarCollection collection,
                                         AccountCalendarOwners owners) {
    String href = CaldavSyncStorage.canonicalHref(collection.href());
    String anchor = anchorOf(href);
    if (anchor != null) {
      boolean exportedByThisUser = usersPairs.stream()
                                             .filter(pair -> pair.getOrigin() == SyncOrigin.EXO)
                                             .anyMatch(pair -> anchor.equals(pair.getLocalCalendarSyncUid())
                                                 || href.equals(CaldavSyncStorage.canonicalHref(pair.getRemoteHref())));
      if (exportedByThisUser) {
        return CollectionOwnership.OWN_EXO_CALENDAR;
      }
      if (isMintedByThisDeployment(serverId, href)) {
        return CollectionOwnership.COLLEAGUES_EXO_CALENDAR;
      }
      String containerUid = containerUidOf(href);
      AccountCalendarOwners.Verdict verdict = (owners == null ? AccountCalendarOwners.silent() : owners).ownerOf(containerUid);
      switch (verdict.word()) {
        case ANOTHERS:
          return isHeldByThisDeployment(serverId, containerUid) ? CollectionOwnership.COLLEAGUES_EXO_CALENDAR
                                                                : CollectionOwnership.SHARED;
        case UNKNOWN:
          // Withheld, not given: the server's naming and DAV signals still
          // speak, and a collection they call a share is one. Only what they
          // would have called the account's own is left unanswered.
          CollectionOwnership byTheServer = byNamingAndDavSignals(href, principal, collection);
          return byTheServer == CollectionOwnership.OWN ? CollectionOwnership.OWNER_UNKNOWN : byTheServer;
        case ACCOUNTS_OWN, SILENT:
        default:
          // The account's own, or a server not asked: the naming and the
          // server's DAV signals decide, as they did before this arm existed.
          break;
      }
    }
    return byNamingAndDavSignals(href, principal, collection);
  }

  /**
   * The server's own word on a collection: its naming, then its DAV owner
   * and privilege signals — the two witnesses heard after the deployment's,
   * for every collection, as they were before the listing was read.
   *
   * @param href the canonical collection path
   * @param principal the account's own principal; null when the server named
   *          none, which leaves the owner comparison off
   * @param collection the listed collection
   * @return a subscription the naming reveals, a share the DAV signals
   *         reveal, or {@link CollectionOwnership#OWN}
   */
  private static CollectionOwnership byNamingAndDavSignals(String href, String principal, CalendarCollection collection) {
    BlueMindContainerNaming.Subscription subscription = BlueMindContainerNaming.subscriptionOf(href, principal);
    if (subscription != null) {
      return subscription.resource() ? CollectionOwnership.SUBSCRIBED_RESOURCE : CollectionOwnership.SUBSCRIBED_PERSON;
    }
    if (collection.isSharedWith(principal)) {
      return CollectionOwnership.SHARED;
    }
    return CollectionOwnership.OWN;
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
