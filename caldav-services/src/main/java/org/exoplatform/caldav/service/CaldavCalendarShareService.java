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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.AccessControlEntry;
import org.exoplatform.caldav.client.AclWriteResult;
import org.exoplatform.caldav.client.bluemind.BlueMindAclClient;
import org.exoplatform.caldav.client.bluemind.BlueMindAclClient.BlueMindAce;
import org.exoplatform.caldav.client.CalDavForbiddenException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.CalendarHome;
import org.exoplatform.caldav.client.CollectionAcl;
import org.exoplatform.caldav.client.DavOptions;
import org.exoplatform.caldav.client.SharingMechanism;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarShares;
import org.exoplatform.caldav.model.CalendarShares.CalendarSharee;
import org.exoplatform.caldav.model.CalendarShares.PublishedLinkMode;
import org.exoplatform.caldav.model.CalendarShares.ShareAccess;
import org.exoplatform.caldav.model.CalendarShares.ShareUser;
import org.exoplatform.caldav.model.CalendarShares.ShareeKind;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Shares a user's own eXo calendar, read-only, with colleagues connected to
 * the same CalDAV server, by changing who may read the collection that
 * calendar is bound to — through the server's own granting mechanism, confirmed by
 * reading the access list back (EXO-90253).
 *
 * <p>
 * <b>Every check lives here</b>, and none of them takes anything from the
 * browser but a calendar id and a login:
 * <ul>
 * <li>the calendar exists and <b>the caller owns it</b> — a space calendar,
 * owned by its space, is refused, and so is a colleague's;</li>
 * <li>the caller's account is connected, and the calendar is bound either to
 * a collection <b>eXo created for it</b> — an {@link SyncOrigin#EXO} pair,
 * active, whose collection carries the slug eXo derives from the calendar's
 * own anchor — or to an <b>imported collection the caller really owns</b>: an
 * active, anchored {@link SyncOrigin#REMOTE} pair that is not the meetings
 * mirror, whose ownership is confirmed on the server before its access list is read or anything is
 * changed (only the capability probe, which selects the rule, comes first)
 * (on RFC 3744 servers its {@code DAV:owner} is the caller's recorded
 * principal, it is writable for them and it sits under their calendar home;
 * on BlueMind its path is under the caller's own uid, it is no subscription
 * to someone else's calendar, and BlueMind's REST access list gives the
 * caller {@code All} or {@code Manage}). The collection is resolved from the
 * pair and never named by the request, so a share, a hidden share, the
 * mirror or any other collection cannot be addressed; the client's
 * {@link CalDavClient#writeAcl} applies the pair rule again;</li>
 * <li>the server offers a granting mechanism whose every change eXo confirms
 * ({@link SharingMechanism}) — Stalwart's RFC 3744 {@code ACL} method, or
 * BlueMind's {@code CS:share} confirmed through its REST API;</li>
 * <li>the sharee is an eXo user, not the caller, <b>connected to the same
 * server registration</b>, whose principal was recorded by a discovery
 * (EXO-90243) and is not the caller's own principal — two eXo users on one
 * login would be "sharing" with themselves.</li>
 * </ul>
 *
 * <p>
 * <b>Read-modify-write, never overwrite.</b> RFC 3744 §8.1 has an {@code ACL}
 * request replace every entry that is neither protected nor inherited, and
 * Stalwart replaces its grant list the same way. So a grant reads the list,
 * keeps every modifiable entry — including entries eXo did not write, a share
 * made from another client — adds one {@code DAV:read} grant, and writes the
 * list back; a revoke removes that principal's read-only grants and nothing
 * else. A list with an entry the client cannot represent is never written.
 * Protected and inherited entries are never sent: the server keeps them.
 *
 * <p>
 * <b>What a list read over DAV cannot carry back.</b> A server's access list
 * over DAV is a projection of its own rights, and not a faithful one: on
 * Stalwart (source, {@code crates/dav/src/common/acl.rs} and
 * {@code crates/jmap-proto/src/object/calendar.rs}, main {@code 474dd022})
 * a colleague given "may delete" or "may write all" through JMAP reads back
 * as {@code DAV:write}, and that entry written back becomes full write — eXo
 * would widen somebody's rights by sharing with somebody else. Every
 * widening is an entry reading back with more than the read-only privileges,
 * so another principal holding any such entry stops the write
 * ({@link #FOREIGN_ACCESS_NOT_PRESERVED}), and the owner manages that
 * calendar's access where it was set. What remains is narrowing: rights DAV
 * does not show at all (JMAP "may write own", "may update private", "may
 * RSVP"; the invite and reply parts of a scheduling grant) sit behind a
 * read-only entry and are dropped when it is written back. That is accepted
 * as the lesser loss, and stated here so it is a decision rather than a
 * surprise.
 *
 * <p>
 * <b>Verified, not claimed.</b> The list is read again after every write,
 * and a grant absent from it — or a revoked grant still in it — is reported
 * as not applied, whatever status the write answered.
 *
 * <p>
 * <b>Nothing is stored.</b> Who a calendar is shared with is the server's
 * state and anyone's other client can change it, so every listing reads it
 * afresh. Concurrent edits by this node are serialised per collection; edits
 * from another node or another client at the same moment are last writer
 * wins, because RFC 3744 offers no conditional {@code ACL} request. Each
 * applied grant and revoke is logged at info: who, to whom, which calendar.
 *
 * <p>
 * <b>On BlueMind</b> ({@link SharingMechanism#BLUEMIND_SHARE}) the change is a
 * {@code POST CS:share} naming the colleague by the mail address their own
 * principal publishes, and the proof is the container's access list read back
 * through BlueMind's REST API with the owner's credentials. A colleague sees
 * the calendar only once they subscribed to it in BlueMind, which the listing
 * says ({@code subscriptionRequired}).
 */
@Service
public class CaldavCalendarShareService {

  /** The calendar does not exist, or was deleted. */
  public static final String      CALENDAR_NOT_FOUND     = "caldav.share.calendarNotFound";

  /** The caller does not own the calendar. */
  public static final String      NOT_OWNER              = "caldav.share.notOwner";

  /** The caller has no connected CalDAV account. */
  public static final String      NOT_CONNECTED          = "caldav.share.notConnected";

  /** The calendar is bound to no collection eXo can share: none eXo created for it, and no active imported one. */
  public static final String      CALENDAR_NOT_ON_SERVER = "caldav.share.calendarNotOnServer";

  /**
   * The imported collection is not the caller's own on the server: another
   * principal owns it, it is read-only for them, it lies outside their
   * calendar home, it is a subscription to someone else's calendar, or the
   * server gives them no right to manage who sees it.
   */
  public static final String      NOT_OWNED_ON_SERVER    = "caldav.share.notOwnedOnServer";

  /** The server offers no way to grant access whose changes eXo can confirm. */
  public static final String      NOT_SUPPORTED          = "caldav.share.notSupported";

  /** No sharee was named. */
  public static final String      SHAREE_REQUIRED        = "caldav.share.shareeRequired";

  /** The sharee is not a known, active eXo user. */
  public static final String      SHAREE_UNKNOWN         = "caldav.share.shareeUnknown";

  /** The sharee is the caller. */
  public static final String      SHAREE_IS_OWNER        = "caldav.share.shareeIsOwner";

  /** The sharee has no recorded identity on the same server. */
  public static final String      SHAREE_NOT_CONNECTED   = "caldav.share.shareeNotConnected";

  /** The sharee is connected to the server as the caller's own principal. */
  public static final String      SAME_PRINCIPAL         = "caldav.share.samePrincipal";

  /** The sharee holds more than read access, granted outside eXo. */
  public static final String      NOT_READ_ONLY          = "caldav.share.notReadOnly";

  /** The server would not let the caller read the calendar's access list. */
  public static final String      ACL_UNREADABLE         = "caldav.share.aclUnreadable";

  /** The access list holds an entry eXo cannot write back faithfully. */
  public static final String      ACL_NOT_UNDERSTOOD     = "caldav.share.aclNotUnderstood";

  /** The server refused the change. */
  public static final String      SERVER_REFUSED         = "caldav.share.serverRefused";

  /**
   * Neither the server nor eXo's record says which principal the caller is
   * connected as, so a colleague on the caller's own login cannot be told
   * apart.
   */
  public static final String      OWNER_UNKNOWN          = "caldav.share.ownerUnknown";

  /**
   * The server named no mail address for the sharee's principal, which is the
   * only way BlueMind's share handler finds a sharee.
   */
  public static final String      SHAREE_ADDRESS_UNKNOWN = "caldav.share.shareeAddressUnknown";

  /**
   * The colleague holds access below viewing given outside eXo — seeing when
   * the owner is free, say — which a share from eXo would replace and a later
   * stop would erase.
   */
  public static final String      SHAREE_HAS_OTHER_ACCESS = "caldav.share.shareeHasOtherAccess";

  /**
   * Another principal holds access beyond seeing the calendar, which writing
   * the list back could change on the server.
   */
  public static final String      FOREIGN_ACCESS_NOT_PRESERVED = "caldav.share.foreignAccessNotPreserved";

  /** The server accepted the change, and the list read back does not hold it. */
  public static final String      NOT_APPLIED            = "caldav.share.notApplied";

  /** The server could not be reached or answered something unusable. */
  public static final String      SERVER_UNAVAILABLE     = "caldav.share.serverUnavailable";

  /** The server refused the account's stored credentials. */
  public static final String      CREDENTIALS            = "caldav.share.credentials";

  private static final Log        LOG                    = ExoLogger.getLogger(CaldavCalendarShareService.class);

  /** {@code DAV:read-acl}, which reading a list requires. */
  private static final String     READ_ACL               = AccessControlEntry.clark(AccessControlEntry.DAV_NS, "read-acl");

  /** How many locks collections are striped over. */
  private static final int        LOCK_STRIPES           = 64;

  /** The BlueMind verb plain reading is ({@code Verb.Read}). */
  private static final String      BLUEMIND_READ          = "Read";

  /**
   * What one stored {@code Read} lists as. BlueMind's REST access list is
   * expanded ({@code AclService.get}: {@code AccessControlEntry.expand}) and
   * {@code Verb.java} declares {@code Read(Freebusy, Visible)} and
   * {@code Freebusy(Invitation)}; a subject holding nothing outside this set
   * holds no more than reading.
   */
  private static final Set<String> BLUEMIND_READ_CLOSURE  = Set.of("Read", "Freebusy", "Invitation", "Visible");

  /** A BlueMind user principal: the segment is the directory entry uid. */
  private static final Pattern     BLUEMIND_PRINCIPAL     = Pattern.compile("/dav/principals/__uids__/([^/]+)");

  /**
   * A BlueMind calendar collection: the first segment is its owner's directory
   * entry uid ({@code ResType.VSTUFF_CONTAINER}).
   */
  private static final Pattern     BLUEMIND_COLLECTION    = Pattern.compile("/dav/calendars/__uids__/([^/]+)/[^/]+");

  /**
   * A BlueMind calendar collection under a user's {@code __uids__} home, with
   * that uid and the container segment, which is the container uid itself
   * ({@code DavStore} lists {@code path + containerUid}; {@code LoggedCore}
   * decodes group 2 and looks the container up by it). BlueMind names a user's
   * default {@code calendar:Default:<uid>} and a calendar created through its
   * calendar service {@code calendar:UserCreated:<uid>:<uuid>}
   * ({@code UserCalendarService}; {@code ICalendarUids.userCreatedCalendar}
   * also allows a uid-less {@code calendar:UserCreated:<seed>}, which this rule
   * refuses); a DAV MKCALENDAR keeps
   * the client's segment, bare. The home also lists every subscription under
   * the subscriber's uid, with the subscribed container's own uid:
   * {@code calendar:Default:<other>}, {@code calendar:UserCreated:<other>:…},
   * {@code calendar:<resource>}, or a bare uid for a domain calendar or a
   * colleague's DAV-created one.
   */
  private static final Pattern     BLUEMIND_CONTAINER     = Pattern.compile("/dav/calendars/__uids__/([^/]+)/([^/]+)");

  /** How many of the user's calendars the menu probes for capabilities before giving up. */
  private static final int         MAX_CAPABILITY_PROBES  = 3;

  /** The BlueMind verb a user needs, at least, to decide who sees a container. */
  private static final String      BLUEMIND_MANAGE        = "Manage";

  /** The BlueMind verbs that let a user decide who sees a container. */
  private static final Set<String> BLUEMIND_MANAGING      = Set.of("All", BLUEMIND_MANAGE);

  /**
   * The subject prefix of a private link BlueMind's calendar publishing gave
   * out ({@code PublishCalendarService.PRIVATE_URL_PREFIX}): the rest of the
   * subject is the secret part of the link's URL.
   */
  private static final String      BLUEMIND_PRIVATE_LINK  = "x-calendar-private-";

  /** The subject prefix of a public published link ({@code PublishCalendarService.PUBLIC_URL_PREFIX}). */
  private static final String      BLUEMIND_PUBLIC_LINK   = "x-calendar-public-";

  /** What a published link's row is keyed by, before its mode: a fixed key, never the link's secret. */
  private static final String      PUBLISHED_LINK_KEY     = "published-link:";

  /**
   * A mail address a {@code CS:share} can carry, as
   * {@link CalDavClient#postCalendarServerShare} accepts it: no markup, one
   * {@code @}.
   */
  private static final Pattern     SHAREABLE_ADDRESS      = Pattern.compile("[^\\s<>&\"'@/]+@[^\\s<>&\"'@/]+");

  private final Lock[]            locks                  = new Lock[LOCK_STRIPES];

  private final AgendaCalendarService           agendaCalendarService;

  private final CaldavConnectorStorage          caldavConnectorStorage;

  private final CaldavSyncStorage               caldavSyncStorage;

  private final CalDavClient                    calDavClient;

  private final CaldavConnectionIdentityService caldavConnectionIdentityService;

  private final IdentityManager                 identityManager;

  private final BlueMindAclClient               blueMindAclClient;

  private final CaldavPushService               caldavPushService;

  private final CaldavShareSubscriptionService  caldavShareSubscriptionService;

  private final CaldavServerOwnerService        caldavServerOwnerService;

  /**
   * The servers this node has already reported, at INFO, as offering no
   * sharing. Reported once per server per process, so the reason is visible
   * without flooding the log on every refresh of the agenda's panel.
   */
  private final Set<Long>                       serversNotOffering = ConcurrentHashMap.newKeySet();

  /**
   * @param agendaCalendarService where the calendar and its owner are read
   * @param caldavConnectorStorage the caller's connected account
   * @param caldavSyncStorage the pair binding the calendar to its collection
   * @param calDavClient the protocol
   * @param caldavConnectionIdentityService who each eXo user is on the server
   * @param identityManager the social identities of caller and sharees
   * @param blueMindAclClient reads a BlueMind calendar's access list back
   * @param caldavPushService says where the copies of eXo meetings are written
   * @param caldavShareSubscriptionService subscribes a BlueMind colleague to
   *          the calendar just shared with them, and unsubscribes them on a
   *          revoke (EXO-90277)
   */
  @Autowired
  public CaldavCalendarShareService(AgendaCalendarService agendaCalendarService,
                                    CaldavConnectorStorage caldavConnectorStorage,
                                    CaldavSyncStorage caldavSyncStorage,
                                    CalDavClient calDavClient,
                                    CaldavConnectionIdentityService caldavConnectionIdentityService,
                                    IdentityManager identityManager,
                                    BlueMindAclClient blueMindAclClient,
                                    CaldavPushService caldavPushService,
                                    CaldavShareSubscriptionService caldavShareSubscriptionService,
                                    CaldavServerOwnerService caldavServerOwnerService) {
    this.caldavServerOwnerService = caldavServerOwnerService;
    this.agendaCalendarService = agendaCalendarService;
    this.caldavConnectorStorage = caldavConnectorStorage;
    this.caldavSyncStorage = caldavSyncStorage;
    this.calDavClient = calDavClient;
    this.caldavConnectionIdentityService = caldavConnectionIdentityService;
    this.identityManager = identityManager;
    this.blueMindAclClient = blueMindAclClient;
    this.caldavPushService = caldavPushService;
    this.caldavShareSubscriptionService = caldavShareSubscriptionService;
    for (int i = 0; i < LOCK_STRIPES; i++) {
      locks[i] = new ReentrantLock();
    }
  }

  /**
   * The caller's calendars that can be shared from eXo: owned, bound to a
   * collection eXo created or to an imported collection they own on the
   * server, on a server offering a mechanism whose changes eXo can confirm.
   *
   * <p>
   * What decides whether agenda shows "Share" on a calendar, so it never
   * fails: anything that goes wrong — no account, a server that cannot be
   * reached, agenda failing — answers no calendar, and the entry is simply
   * not offered. The server is asked what one such collection
   * advertises — one {@code OPTIONS}, plus a depth-0 {@code PROPFIND} where
   * that answer carries no {@code DAV} header, as on the BlueMind deployments
   * observed — since what a server supports does not vary between two
   * collections of one account in any server characterised. A collection eXo
   * created is asked first; when a collection fails on its own (gone, or any
   * error status other than 401, 403, 407 and a gateway status) the next
   * calendar is asked, up to {@code MAX_CAPABILITY_PROBES}, while
   * refused credentials (a 403 among them: on a read verb it cannot be told
   * from a credential refusal) and an unreachable server end the listing at once,
   * since asking again would only add failed requests. Every share operation
   * asks its own collection again.
   *
   * @param userIdentityId the caller
   * @param username the caller's login
   * @return the agenda ids of the shareable calendars, possibly empty
   */
  public List<Long> shareableCalendarIds(long userIdentityId, String username) {
    try {
      CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
      if (!connected(settings)) {
        return List.of();
      }
      long serverId = serverIdOf(settings);
      Map<String, CalendarSync> pairs = new LinkedHashMap<>();
      caldavSyncStorage.getPairsByOrigin(userIdentityId, serverId, SyncOrigin.EXO)
                       .stream()
                       .filter(CaldavCalendarShareService::isShareablePair)
                       .forEach(pair -> pairs.putIfAbsent(pair.getLocalCalendarSyncUid(), pair));
      caldavSyncStorage.getPairsByOrigin(userIdentityId, serverId, SyncOrigin.REMOTE)
                       .stream()
                       .filter(CaldavCalendarShareService::isShareableImportedPair)
                       .forEach(pair -> pairs.putIfAbsent(pair.getLocalCalendarSyncUid(), pair));
      if (pairs.isEmpty()) {
        return List.of();
      }
      // By owner, not getCalendars: that one also reads every calendar of
      // every space the user belongs to, each through an ACL check, for a list
      // this would then discard — on every refresh of the agenda's panel.
      List<Calendar> calendars = agendaCalendarService.getCalendarsByOwnerIds(List.of(userIdentityId), username)
                                                      .stream()
                                                      .filter(calendar -> calendar.getOwnerId() == userIdentityId)
                                                      .filter(calendar -> !calendar.isDeleted())
                                                      .filter(calendar -> pairs.containsKey(calendar.getSyncUid()))
                                                      .toList();
      if (calendars.isEmpty()) {
        return List.of();
      }
      CalDavEndpoint endpoint = calDavClient.endpoint(settings.getServerId(), username);
      // Probe a collection eXo created first: it is the user's own and exists as long as its pair is active,
      // while an imported one may be a subscription that went away. A collection that fails on its own (gone, or an error
      // status other than 401, 403, 407 and a gateway status) tries the next calendar, so one dead collection does not hide Share everywhere. Refused credentials
      // and an unreachable server are properties of the account and the server, known after one attempt: asking
      // again would only add failed requests, which a server may answer with a silent ban (CalDavUnreachableException).
      List<CalendarSync> probes = calendars.stream()
                                           .map(calendar -> pairs.get(calendar.getSyncUid()))
                                           .sorted(Comparator.comparingInt(pair -> pair.getOrigin() == SyncOrigin.EXO ? 0 : 1))
                                           .limit(MAX_CAPABILITY_PROBES)
                                           .toList();
      CalendarSync probe = null;
      DavOptions capabilities = null;
      CalDavException failure = null;
      for (CalendarSync candidate : probes) {
        try {
          capabilities = calDavClient.capabilities(endpoint, collectionOf(candidate));
          probe = candidate;
          break;
        } catch (CalDavAuthenticationException | CalDavUnreachableException e) {
          throw e;
        } catch (CalDavException e) {
          failure = e;
        }
      }
      if (probe == null) {
        throw failure;
      }
      SharingMechanism mechanism = SharingMechanism.of(capabilities, collectionOf(probe));
      if (!mechanism.isOffered()) {
        noteNotOffered(serverId, collectionOf(probe), capabilities, mechanism);
        return List.of();
      }
      if (mechanism == SharingMechanism.BLUEMIND_SHARE && !blueMindAclClient.acceptsCredentials(endpoint)) {
        LOG.debug("The credentials of user {} on server {} are not a login BlueMind's REST API accepts; sharing is not offered",
                  userIdentityId,
                  serverId);
        return List.of();
      }
      Set<String> ownedImported = ownedImportedHrefs(userIdentityId,
                                                     serverId,
                                                     endpoint,
                                                     mechanism,
                                                     calendars.stream()
                                                              .map(calendar -> pairs.get(calendar.getSyncUid()))
                                                              .filter(pair -> pair.getOrigin() == SyncOrigin.REMOTE)
                                                              .toList());
      return calendars.stream()
                      .filter(calendar -> {
                        CalendarSync pair = pairs.get(calendar.getSyncUid());
                        return pair.getOrigin() == SyncOrigin.EXO
                            || ownedImported.contains(CaldavSyncStorage.canonicalHref(pair.getRemoteHref()));
                      })
                      .map(Calendar::getId)
                      .toList();
    } catch (Exception e) { // NOSONAR this answer must never fail, whatever agenda or the server throws
      LOG.debug("Which calendars user {} can share could not be established; none is offered", userIdentityId, e);
      return List.of();
    }
  }

  /**
   * Who a calendar of the caller's is shared with, read from the server now.
   *
   * @param userIdentityId the caller
   * @param username the caller's login
   * @param calendarId the agenda calendar
   * @return the sharees
   * @throws ObjectNotFoundException when the calendar does not exist
   * @throws IllegalAccessException when the caller does not own it
   * @throws IllegalArgumentException when it is bound to no collection eXo can share
   * @throws CaldavShareException when the account, the server or its answer
   *           stops the read
   */
  public CalendarShares listShares(long userIdentityId, String username, long calendarId) throws ObjectNotFoundException,
                                                                                            IllegalAccessException {
    ShareTarget target = targetOf(userIdentityId, username, calendarId);
    return withMeetingCopies(target, username, onServer(() -> {
      SharingMechanism mechanism = requireOffered(target);
      requireImportedOwned(target, mechanism);
      if (mechanism == SharingMechanism.BLUEMIND_SHARE) {
        List<BlueMindAce> aces = blueMindAclOf(target);
        requireBlueMindManager(target, aces);
        return blueMindSharesOf(target, aces, ownerPrincipal(target));
      }
      return sharesOf(target, usableAcl(target), ownerPrincipal(target));
    }));
  }

  /**
   * Gives one colleague read access to a calendar of the caller's.
   *
   * <p>
   * Idempotent: a colleague who can already read it — through a grant eXo
   * made or one made elsewhere — is left as they are and nothing is written.
   * On BlueMind that holds for plain view access only: its access list shows
   * a colleague holding more as reading too, and such a colleague is refused
   * ({@link #NOT_READ_ONLY}) rather than rewritten, as is one holding only
   * other access given outside eXo ({@link #SHAREE_HAS_OTHER_ACCESS}).
   *
   * @param userIdentityId the caller
   * @param username the caller's login
   * @param calendarId the agenda calendar
   * @param shareeUsername the colleague's login
   * @return the sharees as the server lists them after the grant
   * @throws ObjectNotFoundException when the calendar does not exist
   * @throws IllegalAccessException when the caller does not own it
   * @throws IllegalArgumentException with a {@code caldav.share.*} code when
   *           the calendar or the sharee cannot be shared with
   * @throws CaldavShareException when the account, the server or its answer
   *           stops the grant
   */
  public CalendarShares grant(long userIdentityId,
                              String username,
                              long calendarId,
                              String shareeUsername) throws ObjectNotFoundException, IllegalAccessException {
    ShareTarget target = targetOf(userIdentityId, username, calendarId);
    Sharee sharee = shareeOf(target, shareeUsername);
    return withMeetingCopies(target, username, onServer(() -> {
      SharingMechanism mechanism = requireOffered(target);
      requireImportedOwned(target, mechanism);
      String ownerPrincipal = requiredOwnerPrincipal(target);
      if (sharee.principal().equals(ownerPrincipal)) {
        throw new IllegalArgumentException(SAME_PRINCIPAL);
      }
      if (mechanism == SharingMechanism.BLUEMIND_SHARE) {
        return blueMindGrant(target, sharee, username, ownerPrincipal);
      }
      Lock lock = lockOf(target);
      lock.lock();
      try {
        CollectionAcl before = usableAcl(target);
        if (before.entries().stream().anyMatch(entry -> entry.appliesTo(sharee.principal()) && entry.grantsRead())) {
          LOG.debug("Calendar {} is already readable by {}; nothing is written", calendarId, sharee.principal());
          return sharesOf(target, before, ownerPrincipal);
        }
        // The sharee's own entries are written back too, beside the new grant,
        // and on Stalwart an entry holding more than read — a JMAP "may delete"
        // right reads back as DAV:write with no read — comes back as full
        // write. Sharing is read-only: it never widens what the colleague
        // already holds, so such an entry is refused as revoke refuses it.
        if (modifiableEntriesOf(before).stream()
                                       .anyMatch(entry -> entry.appliesTo(sharee.principal()) && !entry.grantsReadOnly())) {
          throw new IllegalArgumentException(NOT_READ_ONLY);
        }
        List<AccessControlEntry> entries = new ArrayList<>(preservableEntriesOf(target, before, sharee.principal()));
        entries.add(AccessControlEntry.readGrantTo(AccessControlEntry.principalHrefOf(sharee.principal())));
        write(target, entries);
        CollectionAcl after = readBack(target);
        if (after.entries().stream().noneMatch(entry -> entry.appliesTo(sharee.principal()) && entry.grantsRead())) {
          LOG.warn("The server accepted read access to calendar {} ({}) for {}, but the access list read back does not"
              + " hold it; reported as not applied", calendarId, target.href(), sharee.principal());
          throw new CaldavShareException(NOT_APPLIED);
        }
        warnOnLostEntries(target, before, after, sharee.principal());
        LOG.info("CalDAV share granted: user {} gave {} read access to calendar {} ({}) as principal {} on server {}",
                 username,
                 sharee.username(),
                 calendarId,
                 target.href(),
                 sharee.principal(),
                 target.serverId());
        return sharesOf(target, after, ownerPrincipal);
      } finally {
        lock.unlock();
      }
    }));
  }

  /**
   * Takes read access to a calendar of the caller's away from one colleague.
   *
   * <p>
   * Removes that colleague's read-only grants and nothing else; a grant that
   * gives them more — made outside eXo — is not eXo's to take back, and the
   * whole request is refused rather than half applied. Idempotent: a
   * colleague with no grant is left alone and nothing is written.
   *
   * @param userIdentityId the caller
   * @param username the caller's login
   * @param calendarId the agenda calendar
   * @param shareeUsername the colleague's login
   * @return the sharees as the server lists them after the revoke
   * @throws ObjectNotFoundException when the calendar does not exist
   * @throws IllegalAccessException when the caller does not own it
   * @throws IllegalArgumentException with a {@code caldav.share.*} code when
   *           the calendar or the sharee cannot be handled
   * @throws CaldavShareException when the account, the server or its answer
   *           stops the revoke
   */
  public CalendarShares revoke(long userIdentityId,
                               String username,
                               long calendarId,
                               String shareeUsername) throws ObjectNotFoundException, IllegalAccessException {
    ShareTarget target = targetOf(userIdentityId, username, calendarId);
    Sharee sharee = shareeOf(target, shareeUsername);
    return withMeetingCopies(target, username, onServer(() -> {
      SharingMechanism mechanism = requireOffered(target);
      requireImportedOwned(target, mechanism);
      String ownerPrincipal = ownerPrincipal(target);
      if (mechanism == SharingMechanism.BLUEMIND_SHARE) {
        return blueMindRevoke(target, sharee, username, ownerPrincipal);
      }
      Lock lock = lockOf(target);
      lock.lock();
      try {
        CollectionAcl before = usableAcl(target);
        List<AccessControlEntry> grants = before.entries()
                                                .stream()
                                                .filter(entry -> entry.appliesTo(sharee.principal()) && !entry.deny())
                                                .toList();
        if (grants.isEmpty()) {
          LOG.debug("Calendar {} grants nothing to {}; nothing is written", calendarId, sharee.principal());
          return sharesOf(target, before, ownerPrincipal);
        }
        if (grants.stream().anyMatch(entry -> !entry.isModifiable() || !entry.grantsReadOnly())) {
          throw new IllegalArgumentException(NOT_READ_ONLY);
        }
        List<AccessControlEntry> entries = preservableEntriesOf(target, before, sharee.principal()).stream()
                                                                                                 .filter(entry -> !grants.contains(entry))
                                                                                                 .toList();
        write(target, entries);
        CollectionAcl after = readBack(target);
        if (after.entries().stream().anyMatch(entry -> entry.appliesTo(sharee.principal()) && entry.grantsRead())) {
          LOG.warn("The server accepted removing read access to calendar {} ({}) from {}, but the access list read back"
              + " still grants it; reported as not applied", calendarId, target.href(), sharee.principal());
          throw new CaldavShareException(NOT_APPLIED);
        }
        warnOnLostEntries(target, before, after, sharee.principal());
        LOG.info("CalDAV share revoked: user {} took read access to calendar {} ({}) away from {} as principal {} on server {}",
                 username,
                 calendarId,
                 target.href(),
                 sharee.username(),
                 sharee.principal(),
                 target.serverId());
        return sharesOf(target, after, ownerPrincipal);
      } finally {
        lock.unlock();
      }
    }));
  }

  /**
   * The colleagues a calendar of the caller's can be shared with: eXo users
   * connected to the same server registration, with a recorded principal that
   * is not the caller's own.
   *
   * <p>
   * The server is asked what the collection advertises, the capability check the listing,
   * the grant and the revoke make, so that nobody is listed on a server where
   * eXo offers no sharing. The colleagues themselves are read from eXo's own
   * record of each connection; the server is asked who the caller is only when
   * that is not recorded yet. Bounded by the storage's read of the server's
   * connections; a colleague beyond that bound is not offered.
   *
   * @param userIdentityId the caller
   * @param username the caller's login
   * @param calendarId the agenda calendar
   * @param query text their login or full name must contain, ignoring case;
   *          blank for everyone
   * @return the candidates, by full name
   * @throws ObjectNotFoundException when the calendar does not exist
   * @throws IllegalAccessException when the caller does not own it
   * @throws IllegalArgumentException when it is bound to no collection eXo can share
   * @throws CaldavShareException when sharing is not offered on the server,
   *           the credentials are refused, the server cannot be reached, the
   *           caller's principal cannot be named, or the calendar is an
   *           imported one the caller does not own on the server
   */
  public List<ShareUser> candidates(long userIdentityId,
                                    String username,
                                    long calendarId,
                                    String query) throws ObjectNotFoundException, IllegalAccessException {
    ShareTarget target = targetOf(userIdentityId, username, calendarId);
    // The same capability check its siblings make: on a server where eXo
    // offers no sharing, who is connected to that server is not listed either.
    onServer(() -> {
      SharingMechanism mechanism = requireOffered(target);
      requireImportedOwned(target, mechanism);
      if (mechanism == SharingMechanism.BLUEMIND_SHARE && target.imported()) {
        requireBlueMindManager(target, blueMindAclOf(target));
      }
      return null;
    });
    String recorded = caldavConnectionIdentityService.principalOf(userIdentityId, target.serverId());
    String ownerPrincipal = recorded != null ? recorded : onServer(() -> requiredOwnerPrincipal(target));
    String needle = StringUtils.lowerCase(StringUtils.trimToNull(query), Locale.ROOT);
    List<ShareUser> candidates = new ArrayList<>();
    for (Map.Entry<Long, String> connection : caldavConnectionIdentityService.principalsOn(target.serverId()).entrySet()) {
      if (connection.getKey() == userIdentityId || connection.getValue().equals(ownerPrincipal)) {
        continue;
      }
      ShareUser user = userOf(connection.getKey());
      if (user != null && (needle == null || StringUtils.containsIgnoreCase(user.username(), needle)
          || StringUtils.containsIgnoreCase(user.fullName(), needle))) {
        candidates.add(user);
      }
    }
    candidates.sort(Comparator.comparing(ShareUser::fullName, String.CASE_INSENSITIVE_ORDER)
                              .thenComparing(ShareUser::username));
    return candidates;
  }

  /**
   * The calendar, its owner, its pair and its endpoint, each checked.
   *
   * <p>
   * In the order the REST contract answers them: existence, ownership, then
   * whether it can be shared at all.
   *
   * @param userIdentityId the caller
   * @param username the caller's login
   * @param calendarId the agenda calendar
   * @return the target
   * @throws ObjectNotFoundException when the calendar does not exist
   * @throws IllegalAccessException when the caller does not own it
   */
  private ShareTarget targetOf(long userIdentityId, String username, long calendarId) throws ObjectNotFoundException,
                                                                                       IllegalAccessException {
    Calendar calendar = agendaCalendarService.getCalendarById(calendarId);
    if (calendar == null || calendar.isDeleted()) {
      throw new ObjectNotFoundException(CALENDAR_NOT_FOUND);
    }
    if (calendar.getOwnerId() != userIdentityId) {
      throw new IllegalAccessException(NOT_OWNER);
    }
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!connected(settings)) {
      throw new CaldavShareException(NOT_CONNECTED);
    }
    long serverId = serverIdOf(settings);
    CalendarSync pair = StringUtils.isBlank(calendar.getSyncUid()) ? null
                                                                   : caldavSyncStorage.getPairByLocalCalendar(userIdentityId,
                                                                                                              serverId,
                                                                                                              calendar.getSyncUid());
    if (pair == null || !(isShareablePair(pair) || isShareableImportedPair(pair))) {
      throw new IllegalArgumentException(CALENDAR_NOT_ON_SERVER);
    }
    CalDavEndpoint endpoint = onServer(() -> calDavClient.endpoint(settings.getServerId(), username));
    return new ShareTarget(userIdentityId, calendarId, serverId, pair, collectionOf(pair), endpoint);
  }

  /**
   * The sharee a login names, checked against the caller and the server.
   *
   * @param target the calendar being shared
   * @param shareeUsername the colleague's login
   * @return the sharee and their recorded principal
   */
  private Sharee shareeOf(ShareTarget target, String shareeUsername) {
    if (StringUtils.isBlank(shareeUsername)) {
      throw new IllegalArgumentException(SHAREE_REQUIRED);
    }
    Identity identity = identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, shareeUsername.trim());
    if (identity == null || identity.isDeleted() || !identity.isEnable() || StringUtils.isBlank(identity.getId())) {
      throw new IllegalArgumentException(SHAREE_UNKNOWN);
    }
    long shareeId = Long.parseLong(identity.getId());
    if (shareeId == target.userIdentityId()) {
      throw new IllegalArgumentException(SHAREE_IS_OWNER);
    }
    String principal = caldavConnectionIdentityService.principalOf(shareeId, target.serverId());
    if (principal == null) {
      throw new IllegalArgumentException(SHAREE_NOT_CONNECTED);
    }
    return new Sharee(shareeId, identity.getRemoteId(), principal);
  }

  /**
   * Refuses a server offering no granting mechanism eXo can confirm, from what the
   * collection itself answers.
   *
   * @param target the calendar being shared
   * @return the mechanism selected, always an offered one
   */
  private SharingMechanism requireOffered(ShareTarget target) {
    DavOptions capabilities = calDavClient.capabilities(target.endpoint(), target.href());
    SharingMechanism mechanism = SharingMechanism.of(capabilities, target.href());
    if (!mechanism.isOffered()) {
      noteNotOffered(target.serverId(), target.href(), capabilities, mechanism);
      throw new CaldavShareException(NOT_SUPPORTED);
    }
    return mechanism;
  }

  /**
   * Says why sharing is not offered on a server: at INFO the first time this
   * node meets it, at debug afterwards. Silent refusals are how a server that
   * advertises its classes somewhere unexpected goes unnoticed. The classes
   * and methods named are public protocol facts, never credentials.
   *
   * @param serverId the server registration
   * @param href the collection asked
   * @param capabilities what it advertised
   * @param mechanism what that selected
   */
  private void noteNotOffered(long serverId, String href, DavOptions capabilities, SharingMechanism mechanism) {
    if (capabilities == null) {
      LOG.debug("Server {} answered no capabilities for {}; sharing is not offered", serverId, href);
      return;
    }
    if (serversNotOffering.add(serverId)) {
      LOG.info("Sharing calendars is not offered on calendar server {}: collection {} advertised DAV classes {} and methods {},"
          + " which select {}. Reported once per server until restart", serverId, href, capabilities.davTokens(),
               capabilities.allowedMethods(), mechanism);
    } else {
      LOG.debug("Server {} selects sharing mechanism {} for {}, which eXo does not offer", serverId, mechanism, href);
    }
  }

  /**
   * The caller's principal as the server names it now, falling back to the
   * one recorded for them.
   *
   * @param target the calendar being shared
   * @return the canonical principal, or null when neither says
   */
  private String ownerPrincipal(ShareTarget target) {
    String discovered;
    try {
      discovered = CaldavConnectionIdentityService.canonicalPrincipal(calDavClient.discoverPrincipal(target.endpoint()));
    } catch (CalDavAuthenticationException e) {
      throw e;
    } catch (CalDavException e) {
      LOG.debug("The server did not name the principal of user {}; the recorded one is used", target.userIdentityId(), e);
      discovered = null;
    }
    return discovered != null ? discovered : caldavConnectionIdentityService.principalOf(target.userIdentityId(), target.serverId());
  }

  /**
   * The caller's principal where a decision rests on it, refused when nobody
   * can name it.
   *
   * <p>
   * What tells a colleague on the caller's own login (EXO-90190) from a
   * colleague on another: with no principal to compare, that colleague would
   * be offered and granted — "sharing" with oneself, which B.8.3 says must not
   * be offered. It is unknown only briefly in practice: the server answered no
   * principal, and the record is empty because the account was just connected
   * again, which forgets it until the next discovery.
   *
   * @param target the calendar being shared
   * @return the canonical principal, never null
   * @throws CaldavShareException with {@link #OWNER_UNKNOWN} when nobody names it
   */
  private String requiredOwnerPrincipal(ShareTarget target) {
    String principal = ownerPrincipal(target);
    if (principal == null) {
      LOG.debug("Neither the server nor the record names the principal of user {} on server {}; nothing is offered or granted",
                target.userIdentityId(),
                target.serverId());
      throw new CaldavShareException(OWNER_UNKNOWN);
    }
    return principal;
  }

  /**
   * The collection's list, refused unless it can be written back faithfully.
   *
   * @param target the calendar being shared
   * @return the list, readable and understood
   */
  private CollectionAcl usableAcl(ShareTarget target) {
    CollectionAcl acl = calDavClient.readAcl(target.endpoint(), target.href());
    if (!acl.readable()) {
      boolean readAclMissing = !acl.currentUserPrivileges().isEmpty() && !acl.currentUserPrivileges().contains(READ_ACL)
          && !acl.currentUserPrivileges().contains(AccessControlEntry.clark(AccessControlEntry.DAV_NS, "all"));
      throw new CaldavShareException(ACL_UNREADABLE, List.of(), readAclMissing ? List.of("read-acl") : List.of(), null);
    }
    if (!acl.understood()) {
      LOG.warn("The access list of {} holds {}; it is not written back, and the calendar cannot be shared from eXo",
               target.href(),
               acl.reason());
      throw new CaldavShareException(ACL_NOT_UNDERSTOOD);
    }
    return acl;
  }

  /**
   * The list read again after a write, in whatever state: a list no longer
   * readable or understood cannot confirm anything, which is not applied.
   *
   * @param target the calendar being shared
   * @return the list, readable and understood
   */
  private CollectionAcl readBack(ShareTarget target) {
    CollectionAcl acl = calDavClient.readAcl(target.endpoint(), target.href());
    if (!acl.readable() || !acl.understood()) {
      LOG.warn("The access list of {} could not be read back after a write ({}); the change is reported as not applied",
               target.href(),
               acl.reason());
      throw new CaldavShareException(NOT_APPLIED);
    }
    return acl;
  }

  /**
   * Writes the list, turning a refusal into the refusal the server stated.
   *
   * @param target the calendar being shared
   * @param entries the complete list of modifiable entries
   */
  private void write(ShareTarget target, List<AccessControlEntry> entries) {
    AclWriteResult result = calDavClient.writeAcl(target.endpoint(), target.pair(), entries);
    if (!result.accepted()) {
      LOG.info("The server refused the access list of {} with {} (preconditions {}, missing privileges {})",
               target.href(),
               result.status(),
               result.preconditions(),
               result.missingPrivileges());
      throw new CaldavShareException(SERVER_REFUSED, result.preconditions(), result.missingPrivileges(), null);
    }
  }

  /**
   * The modifiable entries a write may carry back unchanged, or a refusal
   * when one of them, for a principal other than the one being changed, could
   * come back with different rights.
   *
   * <p>
   * Only a plain read-only grant — {@code DAV:read},
   * {@code read-current-user-privilege-set}, {@code CALDAV:read-free-busy} —
   * is written back for somebody else: those never widen. Anything more (a
   * write privilege, access-control privileges, a scheduling or vendor
   * privilege), a deny and an inverted entry stop the write before a request
   * is built. See the class documentation for why a list read over DAV cannot
   * be trusted to carry more back, and what narrowing remains.
   *
   * @param target the calendar being shared
   * @param acl the list as read
   * @param changedPrincipal the principal the write is about, whose entries are
   *          eXo's to change
   * @return the modifiable entries, in order
   * @throws CaldavShareException with {@link #FOREIGN_ACCESS_NOT_PRESERVED}
   */
  private List<AccessControlEntry> preservableEntriesOf(ShareTarget target, CollectionAcl acl, String changedPrincipal) {
    List<AccessControlEntry> modifiable = modifiableEntriesOf(acl);
    long unsafe = modifiable.stream()
                            .filter(entry -> !entry.appliesTo(changedPrincipal))
                            .filter(entry -> !entry.grantsReadOnly())
                            .count();
    if (unsafe > 0) {
      LOG.info("Calendar {} ({}) gives {} other access entries more than read access; its list is not written back, since"
          + " the server could return those rights changed", target.calendarId(), target.href(), unsafe);
      throw new CaldavShareException(FOREIGN_ACCESS_NOT_PRESERVED);
    }
    return modifiable;
  }

  /**
   * The entries an {@code ACL} request carries back: every one neither
   * protected nor inherited, as it was read.
   *
   * @param acl the list
   * @return the modifiable entries, in order
   */
  private static List<AccessControlEntry> modifiableEntriesOf(CollectionAcl acl) {
    return acl.entries().stream().filter(AccessControlEntry::isModifiable).toList();
  }

  /**
   * Warns when an entry eXo sent back is missing from the list read after
   * the write: the server dropped somebody's access that eXo did not ask it to
   * touch.
   *
   * @param target the calendar being shared
   * @param before the list before the write
   * @param after the list after it
   * @param changedPrincipal the principal the write was about, whose entries
   *          are expected to change
   */
  private void warnOnLostEntries(ShareTarget target, CollectionAcl before, CollectionAcl after, String changedPrincipal) {
    long lost = modifiableEntriesOf(before).stream()
                                           .filter(entry -> !entry.appliesTo(changedPrincipal))
                                           .filter(entry -> after.entries().stream().noneMatch(kept -> sameEntry(entry, kept)))
                                           .count();
    if (lost > 0) {
      LOG.warn("{} access entries of {} that eXo sent back unchanged are missing from the list read after the write",
               lost,
               target.href());
    }
  }

  /**
   * Whether two entries say the same thing, principals compared as paths.
   *
   * @param first one entry
   * @param second the other
   * @return true when equivalent
   */
  private static boolean sameEntry(AccessControlEntry first, AccessControlEntry second) {
    AccessControlEntry.AcePrincipal a = first.principal();
    AccessControlEntry.AcePrincipal b = second.principal();
    boolean samePrincipal = a.kind() == b.kind()
        && (a.kind() != AccessControlEntry.AcePrincipal.Kind.HREF
            || CalendarCollection.principalPathOf(a.href()).equals(CalendarCollection.principalPathOf(b.href())))
        && StringUtils.equals(a.propertyNamespace(), b.propertyNamespace()) && StringUtils.equals(a.propertyName(), b.propertyName());
    return samePrincipal && first.inverted() == second.inverted() && first.deny() == second.deny()
        && first.privileges().equals(second.privileges());
  }

  /**
   * The sharees a list names: one per principal granted something, the
   * caller's own principal left out.
   *
   * @param target the calendar being shared
   * @param acl the list
   * @param ownerPrincipal the caller's canonical principal, may be null
   * @return the sharees
   */
  private CalendarShares sharesOf(ShareTarget target, CollectionAcl acl, String ownerPrincipal) {
    Map<String, List<AccessControlEntry>> byPrincipal = new LinkedHashMap<>();
    Map<String, AccessControlEntry.AcePrincipal> principals = new HashMap<>();
    for (AccessControlEntry entry : acl.entries()) {
      if (entry.deny() || entry.inverted()) {
        continue;
      }
      String key = switch (entry.principal().kind()) {
      case HREF -> CalendarCollection.principalPathOf(entry.principal().href());
      case ALL, AUTHENTICATED, UNAUTHENTICATED -> AccessControlEntry.clark(AccessControlEntry.DAV_NS,
                                                                           entry.principal().kind().name().toLowerCase(Locale.ROOT));
      // A property principal names whoever DAV:owner is — the caller — and
      // self means nothing on a calendar collection: neither is a sharee.
      case PROPERTY, SELF -> null;
      };
      if (key == null || key.equals(ownerPrincipal)) {
        continue;
      }
      byPrincipal.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
      principals.putIfAbsent(key, entry.principal());
    }
    List<CalendarSharee> sharees = new ArrayList<>();
    for (Map.Entry<String, List<AccessControlEntry>> group : byPrincipal.entrySet()) {
      sharees.add(shareeOf(target, group.getKey(), principals.get(group.getKey()), group.getValue()));
    }
    return new CalendarShares(target.calendarId(), sharees);
  }

  /**
   * One sharee: who the principal is to eXo, what its entries grant, and
   * whether eXo may take that away.
   *
   * @param target the calendar being shared
   * @param key the canonical principal, or the pseudo-principal's Clark name
   * @param principal the principal as the server wrote it
   * @param entries the grants naming it
   * @return the sharee
   */
  private CalendarSharee shareeOf(ShareTarget target,
                                  String key,
                                  AccessControlEntry.AcePrincipal principal,
                                  List<AccessControlEntry> entries) {
    ShareAccess access = entries.stream().allMatch(AccessControlEntry::grantsReadOnly) ? ShareAccess.READ : ShareAccess.MORE;
    if (principal.kind() != AccessControlEntry.AcePrincipal.Kind.HREF) {
      return new CalendarSharee(key, ShareeKind.EVERYONE, List.of(), null, access, false);
    }
    List<ShareUser> users = usersConnectedAs(target, key);
    if (users.isEmpty()) {
      return new CalendarSharee(principal.href(), ShareeKind.OUTSIDE_EXO, List.of(), nameOf(target, principal.href(), key), access, false);
    }
    boolean removable = access == ShareAccess.READ && entries.stream().allMatch(AccessControlEntry::isModifiable);
    return new CalendarSharee(principal.href(), ShareeKind.EXO_USERS, users, null, access, removable);
  }

  /**
   * The eXo users connected to the server as one principal, the caller left
   * out. A lookup that fails names nobody: the sharee is then listed as
   * someone outside eXo rather than not at all.
   *
   * @param target the calendar being shared
   * @param principal the canonical principal
   * @return the users, possibly empty
   */
  private List<ShareUser> usersConnectedAs(ShareTarget target, String principal) {
    try {
      return caldavConnectionIdentityService.usersConnectedAs(target.serverId(), principal)
                                            .stream()
                                            .filter(user -> user != target.userIdentityId())
                                            .map(this::userOf)
                                            .filter(java.util.Objects::nonNull)
                                            .toList();
    } catch (RuntimeException e) {
      LOG.debug("The eXo users connected as {} could not be looked up; the sharee is named by the principal", principal, e);
      return List.of();
    }
  }

  /**
   * What a principal no eXo user is connected as calls itself: its
   * {@code DAV:displayname}, else the decoded last segment of its path.
   *
   * @param target the calendar being shared
   * @param href the principal href as written
   * @param canonical the canonical principal
   * @return the name, never blank
   */
  private String nameOf(ShareTarget target, String href, String canonical) {
    String name = null;
    try {
      name = calDavClient.readDisplayName(target.endpoint(), href);
    } catch (CalDavException e) {
      // A refusal included, credentials or not: the name is cosmetic, it is
      // asked after the list was read with the same credentials, and in a
      // grant or a revoke after the write was read back. A server answering
      // 403 to a PROPFIND on somebody else's principal — read as a credential
      // refusal on read verbs — must not turn a change it applied into a
      // failure.
      LOG.debug("The principal {} did not say what it is called", href, e);
    }
    return StringUtils.defaultIfBlank(StringUtils.trimToNull(name),
                                      StringUtils.defaultIfBlank(StringUtils.substringAfterLast(canonical, "/"), canonical));
  }

  /**
   * An eXo user to show, or null when the registry does not know them as an
   * active user.
   *
   * @param userIdentityId the identity
   * @return the user, or null
   */
  private ShareUser userOf(long userIdentityId) {
    Identity identity = identityManager.getIdentity(userIdentityId);
    if (identity == null || identity.isDeleted() || !identity.isEnable() || StringUtils.isBlank(identity.getRemoteId())) {
      return null;
    }
    Profile profile = identity.getProfile();
    String fullName = profile == null ? null : StringUtils.trimToNull(profile.getFullName());
    return new ShareUser(userIdentityId,
                         identity.getRemoteId(),
                         StringUtils.defaultIfBlank(fullName, identity.getRemoteId()),
                         profile == null ? null : profile.getAvatarUrl());
  }

  /**
   * Shares a BlueMind calendar read-only with one colleague: {@code POST
   * CS:share}, then the container's access list read back.
   *
   * <p>
   * BlueMind's handler rewrites every entry of the sharee to {@code Read}
   * ({@code SharingProtocol.java}), so a colleague holding anything beyond
   * reading — {@code Write}, {@code Manage}, {@code All}… — would be
   * downgraded by a read-only share: refused before anything is sent. A
   * colleague already reading changes nothing. A colleague holding only
   * access below reading given in BlueMind — free/busy, invitation, visible —
   * is refused too: once rewritten to {@code Read} it is indistinguishable in
   * BlueMind's store from a share eXo made ({@code AclService.store} compacts
   * it), so a later stop from eXo would erase access eXo never gave. The handler answers 200
   * whatever it did, so the grant counts only when the access list read back
   * gives the colleague's directory entry {@code Read}.
   *
   * @param target the calendar
   * @param sharee the colleague
   * @param username the owner's login, for the audit line
   * @param ownerPrincipal the owner's canonical principal
   * @return the sharees as read back
   */
  private CalendarShares blueMindGrant(ShareTarget target, Sharee sharee, String username, String ownerPrincipal) {
    String shareeUid = blueMindUidOf(sharee.principal());
    if (shareeUid == null) {
      throw new IllegalArgumentException(SHAREE_NOT_CONNECTED);
    }
    Lock lock = lockOf(target);
    lock.lock();
    try {
      List<BlueMindAce> before = blueMindAclOf(target);
      requireBlueMindManager(target, before);
      Set<String> theirs = blueMindVerbsOf(before, shareeUid);
      if (!BLUEMIND_READ_CLOSURE.containsAll(theirs)) {
        throw new IllegalArgumentException(NOT_READ_ONLY);
      }
      if (theirs.contains(BLUEMIND_READ)) {
        LOG.debug("Calendar {} is already readable by {}; nothing is sent", target.calendarId(), sharee.principal());
        return blueMindSharesOf(target, before, ownerPrincipal);
      }
      if (!theirs.isEmpty()) {
        throw new IllegalArgumentException(SHAREE_HAS_OTHER_ACCESS);
      }
      postBlueMindShare(target, blueMindAddressOf(target, sharee), false);
      List<BlueMindAce> after = blueMindAclOf(target);
      if (!blueMindVerbsOf(after, shareeUid).contains(BLUEMIND_READ)) {
        LOG.warn("The server answered the share of calendar {} ({}) with {}, but its access list read back gives entry {} no read"
            + " access; reported as not applied", target.calendarId(), target.href(), sharee.principal(), shareeUid);
        throw new CaldavShareException(NOT_APPLIED);
      }
      warnOnChangedBlueMindEntries(target, before, after, shareeUid);
      followShareeSubscription(target, sharee, username, shareeUid, true);
      LOG.info("CalDAV share granted: user {} gave {} read access to calendar {} ({}) as BlueMind entry {} on server {}",
               username,
               sharee.username(),
               target.calendarId(),
               target.href(),
               shareeUid,
               target.serverId());
      return blueMindSharesOf(target, after, ownerPrincipal);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stops sharing a BlueMind calendar with one colleague: {@code POST CS:share}
   * with a remove, then the access list read back.
   *
   * <p>
   * BlueMind's handler removes <em>every</em> entry of the sharee, so only a
   * colleague holding plain reading — {@code Read} and the verbs it expands to,
   * nothing else — is removed; one holding more, or only free/busy, holds
   * something eXo did not give and is refused. Plain reading cannot hide
   * earlier free/busy access, because a grant is refused to a colleague
   * holding any.
   *
   * @param target the calendar
   * @param sharee the colleague
   * @param username the owner's login, for the audit line
   * @param ownerPrincipal the owner's canonical principal
   * @return the sharees as read back
   */
  private CalendarShares blueMindRevoke(ShareTarget target, Sharee sharee, String username, String ownerPrincipal) {
    String shareeUid = blueMindUidOf(sharee.principal());
    if (shareeUid == null) {
      throw new IllegalArgumentException(SHAREE_NOT_CONNECTED);
    }
    Lock lock = lockOf(target);
    lock.lock();
    try {
      List<BlueMindAce> before = blueMindAclOf(target);
      requireBlueMindManager(target, before);
      Set<String> theirs = blueMindVerbsOf(before, shareeUid);
      if (theirs.isEmpty()) {
        LOG.debug("Calendar {} gives {} nothing; nothing is sent", target.calendarId(), sharee.principal());
        return blueMindSharesOf(target, before, ownerPrincipal);
      }
      if (!isPlainBlueMindRead(theirs)) {
        throw new IllegalArgumentException(NOT_READ_ONLY);
      }
      postBlueMindShare(target, blueMindAddressOf(target, sharee), true);
      List<BlueMindAce> after = blueMindAclOf(target);
      if (!blueMindVerbsOf(after, shareeUid).isEmpty()) {
        LOG.warn("The server answered removing {} from calendar {} ({}), but its access list read back still names entry {};"
            + " reported as not applied", sharee.principal(), target.calendarId(), target.href(), shareeUid);
        throw new CaldavShareException(NOT_APPLIED);
      }
      warnOnChangedBlueMindEntries(target, before, after, shareeUid);
      followShareeSubscription(target, sharee, username, shareeUid, false);
      LOG.info("CalDAV share revoked: user {} took read access to calendar {} ({}) away from {} as BlueMind entry {} on server {}",
               username,
               target.calendarId(),
               target.href(),
               sharee.username(),
               shareeUid,
               target.serverId());
      return blueMindSharesOf(target, after, ownerPrincipal);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Makes the colleague's BlueMind account follow the change just confirmed
   * on the access list: subscribed to the calendar after a grant, unsubscribed
   * after a revoke (EXO-90277). Inside the stripe lock, after the read-back,
   * before the audit line — and never a failure of the owner's action: the
   * service records what did not land and never throws, and this seam guards
   * against it anyway, because the share on the server is already made.
   *
   * <p>
   * <b>The guard is not belt and braces.</b> This call sits lexically inside
   * {@link #onServer}, whose whole job is to turn a
   * {@code CalDavAuthenticationException} into a
   * {@code CaldavShareException(CREDENTIALS)} and fail the caller — and the
   * likeliest thing to come out of a subscription attempt is exactly that
   * exception, raised by the <em>colleague's</em> stale password. Letting it
   * travel would fail the owner's share over somebody else's credentials.
   *
   * <p>
   * Two things this seam does not do, both deliberate and both stated in
   * {@link CaldavShareSubscriptionService}'s own comment: it is not reached
   * when the colleague already holds {@code Read} (the grant returns before
   * it, so re-clicking Share is not a way to re-drive a subscription that has
   * spent its budget — revoking and granting again is), and it costs the
   * owner's request up to three synchronous round trips to BlueMind.
   *
   * @param target the calendar
   * @param sharee the colleague
   * @param username the owner's login, for the audit line
   * @param shareeUid the colleague's directory entry uid
   * @param subscribe true after a grant, false after a revoke
   */
  private void followShareeSubscription(ShareTarget target, Sharee sharee, String username, String shareeUid, boolean subscribe) {
    // What the sharee's mailbox sees just changed by eXo's own hand, so what
    // the sweep and the calendar list remember of it is dropped before the
    // subscription is followed (EXO-90347): the next pass reads it afresh,
    // and the drain evicts again once a deferred subscription lands.
    caldavServerOwnerService.evict(sharee.identityId(), target.serverId());
    try {
      CaldavShareSubscriptionService.ShareeSubscription subscription =
                                                                     new CaldavShareSubscriptionService.ShareeSubscription(username,
                                                                                                                           sharee.identityId(),
                                                                                                                           sharee.username(),
                                                                                                                           shareeUid,
                                                                                                                           target.serverId(),
                                                                                                                           containerUidOf(target));
      if (subscribe) {
        caldavShareSubscriptionService.subscribeSharee(subscription);
      } else {
        caldavShareSubscriptionService.unsubscribeSharee(subscription);
      }
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The subscription of {} to calendar {} ({}) could not be followed on the server; the share itself is applied",
               sharee.username(),
               target.calendarId(),
               target.href(),
               e);
    }
  }

  /**
   * A BlueMind calendar's access list, read through BlueMind's REST API as its
   * owner.
   *
   * @param target the calendar
   * @return the entries
   */
  private List<BlueMindAce> blueMindAclOf(ShareTarget target) {
    try {
      return blueMindAclClient.readAcl(target.endpoint(), containerUidOf(target));
    } catch (UnsupportedOperationException e) {
      LOG.debug("The credentials of user {} are not a login BlueMind's REST API accepts; sharing is not offered",
                target.userIdentityId());
      throw new CaldavShareException(NOT_SUPPORTED);
    } catch (CalDavForbiddenException e) {
      // BlueMind lets only a manager read the list (ContainerManagement checks Manage). For a calendar eXo
      // created that is the caller's own, so a refusal is an unreadable list; for an imported calendar it is
      // what a subscriber to someone else's calendar gets, so it means not theirs.
      if (target.imported()) {
        throw new CaldavShareException(NOT_OWNED_ON_SERVER, List.of(), List.of(BLUEMIND_MANAGE), e);
      }
      throw new CaldavShareException(ACL_UNREADABLE, List.of(), List.of(BLUEMIND_MANAGE), e);
    }
  }

  /**
   * The sharee's mail address, as their BlueMind principal publishes it: the
   * {@code mailto:} of its {@code calendar-user-address-set}, read through the
   * owner's own endpoint — never the sharee's credentials, and never guessed
   * from an eXo profile.
   *
   * @param target the calendar
   * @param sharee the colleague
   * @return the address, without {@code mailto:}
   */
  private String blueMindAddressOf(ShareTarget target, Sharee sharee) {
    List<String> addresses;
    try {
      addresses = calDavClient.readCalendarUserAddresses(target.endpoint(), AccessControlEntry.principalHrefOf(sharee.principal()));
    } catch (CalDavAuthenticationException | CalDavUnreachableException e) {
      throw e;
    } catch (CalDavException e) {
      LOG.debug("The principal {} did not say its calendar user addresses", sharee.principal(), e);
      throw new CaldavShareException(SHAREE_ADDRESS_UNKNOWN, e);
    }
    return addresses.stream()
                    .filter(address -> address.regionMatches(true, 0, "mailto:", 0, 7))
                    .map(address -> address.substring(7).trim())
                    .filter(address -> SHAREABLE_ADDRESS.matcher(address).matches())
                    .findFirst()
                    .orElseThrow(() -> new CaldavShareException(SHAREE_ADDRESS_UNKNOWN));
  }

  /**
   * Sends the {@code CS:share}, a 403 being the server's refusal.
   *
   * @param target the calendar
   * @param address the sharee's address
   * @param remove whether to stop sharing
   */
  private void postBlueMindShare(ShareTarget target, String address, boolean remove) {
    try {
      calDavClient.postCalendarServerShare(target.endpoint(), target.pair(), address, remove);
    } catch (CalDavForbiddenException e) {
      throw new CaldavShareException(SERVER_REFUSED, List.of(), List.of(), e);
    }
  }

  /**
   * The sharees a BlueMind access list names, one per subject, the calendar's
   * owner left out.
   *
   * <p>
   * A subject is a directory entry uid, which is the segment of that user's
   * DAV principal ({@code ResType.PRINCIPAL}; {@code CalendarUserAddressSet}
   * resolves {@code findByEntryUid} on that segment), so it maps to the eXo
   * users recorded under that principal; one nobody in eXo is connected as is
   * listed as someone outside eXo, never removable. The owner is recognised by
   * the collection's own path, where the list's expanded owner rights
   * ({@code AclService.get}: {@code addOwnerRights}) are keyed, and by the
   * owner's principal. A subject holding only free/busy cannot view the
   * calendar and is not listed. Anything beyond reading lists as more access
   * given outside eXo, never removable.
   *
   * <p>
   * A subject starting with a prefix BlueMind's calendar publishing gives its
   * links ({@code PublishCalendarService}) is a published link, the rest of
   * the subject being the secret part of the link's URL. Published links are
   * listed after the others, one row per mode, keyed and named by the mode
   * alone and never removable: their subject is never listed, looked up,
   * sent or logged.
   *
   * @param target the calendar
   * @param aces the expanded access list
   * @param ownerPrincipal the owner's canonical principal, may be null
   * @return the sharees
   */
  private CalendarShares blueMindSharesOf(ShareTarget target, List<BlueMindAce> aces, String ownerPrincipal) {
    Set<String> owners = new java.util.HashSet<>();
    Matcher collection = BLUEMIND_COLLECTION.matcher(CalendarCollection.principalPathOf(target.href()));
    if (collection.matches()) {
      owners.add(collection.group(1));
    }
    String ownerUid = blueMindUidOf(ownerPrincipal);
    if (ownerUid != null) {
      owners.add(ownerUid);
    }
    Map<String, Set<String>> verbsBySubject = new LinkedHashMap<>();
    Map<PublishedLinkMode, Set<String>> verbsByLinkMode = new java.util.EnumMap<>(PublishedLinkMode.class);
    for (BlueMindAce ace : aces) {
      PublishedLinkMode linkMode = publishedLinkModeOf(ace.subject());
      if (linkMode != null) {
        verbsByLinkMode.computeIfAbsent(linkMode, mode -> new java.util.LinkedHashSet<>()).add(ace.verb());
      } else if (!owners.contains(ace.subject())) {
        verbsBySubject.computeIfAbsent(ace.subject(), subject -> new java.util.LinkedHashSet<>()).add(ace.verb());
      }
    }
    List<CalendarSharee> sharees = new ArrayList<>();
    verbsBySubject.forEach((subject, verbs) -> {
      boolean more = !BLUEMIND_READ_CLOSURE.containsAll(verbs);
      if (!more && !verbs.contains(BLUEMIND_READ)) {
        return;
      }
      String canonical = "/dav/principals/__uids__/" + subject;
      String href = AccessControlEntry.principalHrefOf(canonical);
      ShareAccess access = more ? ShareAccess.MORE : ShareAccess.READ;
      List<ShareUser> users = usersConnectedAs(target, canonical);
      if (users.isEmpty()) {
        sharees.add(new CalendarSharee(href, ShareeKind.OUTSIDE_EXO, List.of(), nameOf(target, href, canonical), access, false));
      } else {
        sharees.add(new CalendarSharee(href, ShareeKind.EXO_USERS, users, null, access, isPlainBlueMindRead(verbs)));
      }
    });
    verbsByLinkMode.forEach((mode, verbs) -> sharees.add(new CalendarSharee(PUBLISHED_LINK_KEY + (mode == PublishedLinkMode.PUBLIC ? "public" : "private"),
                                                                           ShareeKind.PUBLISHED_LINK,
                                                                           List.of(),
                                                                           null,
                                                                           BLUEMIND_READ_CLOSURE.containsAll(verbs) ? ShareAccess.READ
                                                                                                                    : ShareAccess.MORE,
                                                                           false,
                                                                           mode)));
    return new CalendarShares(target.calendarId(), sharees, true);
  }

  /**
   * The mode of a link BlueMind's calendar publishing gave out, recognised by
   * the prefix of its access entry's subject.
   *
   * @param subject an access entry's subject
   * @return the link's mode, or null for a subject that is no published link
   */
  private static PublishedLinkMode publishedLinkModeOf(String subject) {
    if (subject.startsWith(BLUEMIND_PRIVATE_LINK)) {
      return PublishedLinkMode.PRIVATE;
    }
    return subject.startsWith(BLUEMIND_PUBLIC_LINK) ? PublishedLinkMode.PUBLIC : null;
  }

  /**
   * The verbs one subject holds.
   *
   * @param aces the expanded access list
   * @param subject the directory entry uid
   * @return its verbs, empty when it holds none
   */
  private static Set<String> blueMindVerbsOf(List<BlueMindAce> aces, String subject) {
    return aces.stream().filter(ace -> ace.subject().equals(subject)).map(BlueMindAce::verb).collect(java.util.stream.Collectors.toSet());
  }

  /**
   * Whether verbs are plain reading: {@code Read} and nothing it does not
   * expand to — what eXo grants, and so what eXo may take back.
   *
   * @param verbs a subject's verbs
   * @return true for plain reading
   */
  private static boolean isPlainBlueMindRead(Set<String> verbs) {
    return verbs.contains(BLUEMIND_READ) && BLUEMIND_READ_CLOSURE.containsAll(verbs);
  }

  /**
   * Warns when somebody else's entries differ after a change eXo asked about
   * one colleague only.
   *
   * @param target the calendar
   * @param before the list before
   * @param after the list after
   * @param changedSubject the colleague the change was about
   */
  private void warnOnChangedBlueMindEntries(ShareTarget target, List<BlueMindAce> before, List<BlueMindAce> after, String changedSubject) {
    List<String> others = before.stream().filter(ace -> !ace.subject().equals(changedSubject)).map(Object::toString).sorted().toList();
    List<String> othersAfter = after.stream().filter(ace -> !ace.subject().equals(changedSubject)).map(Object::toString).sorted().toList();
    if (!others.equals(othersAfter)) {
      LOG.warn("Access entries of calendar {} ({}) for others than {} differ after the change eXo asked for", target.calendarId(),
               target.href(), changedSubject);
    }
  }

  /**
   * The container uid of a BlueMind calendar: the last segment of its
   * collection path ({@code ResType.VSTUFF_CONTAINER} group 2, which
   * {@code SharingProtocol} manages).
   *
   * @param target the calendar
   * @return the container uid
   */
  private static String containerUidOf(ShareTarget target) {
    return StringUtils.substringAfterLast(CalendarCollection.principalPathOf(target.href()), "/");
  }

  /**
   * The directory entry uid a BlueMind principal names.
   *
   * @param principal a principal path, any spelling, may be null
   * @return the uid, or null when the path is not a BlueMind user principal
   */
  private static String blueMindUidOf(String principal) {
    if (StringUtils.isBlank(principal)) {
      return null;
    }
    Matcher matcher = BLUEMIND_PRINCIPAL.matcher(CalendarCollection.principalPathOf(principal));
    return matcher.matches() ? matcher.group(1) : null;
  }

  /**
   * The lock serialising this node's edits of one collection's list.
   *
   * @param target the calendar being shared
   * @return the lock
   */
  private Lock lockOf(ShareTarget target) {
    String key = target.serverId() + ":" + CaldavSyncStorage.canonicalHref(target.href());
    return locks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
  }

  /**
   * Runs a conversation with the server, turning what the client throws into
   * the failures the REST contract names.
   *
   * @param <T> the answer
   * @param call the conversation
   * @return its answer
   */
  private <T> T onServer(Supplier<T> call) {
    try {
      return call.get();
    } catch (CalDavAuthenticationException e) {
      throw new CaldavShareException(CREDENTIALS, e);
    } catch (CalDavException e) {
      LOG.debug("The calendar server could not be asked about a share", e);
      throw new CaldavShareException(SERVER_UNAVAILABLE, e);
    }
  }

  /**
   * Whether a pair binds a calendar to a collection eXo created for it:
   * exported by eXo, active, and at the slug eXo derives from the calendar's
   * anchor — the condition {@link CalDavClient#writeAcl} applies again.
   *
   * @param pair the pair
   * @return true when it may be shared through
   */
  private static boolean isShareablePair(CalendarSync pair) {
    if (pair == null || pair.getOrigin() != SyncOrigin.EXO || pair.getStatus() != CalendarSyncStatus.ACTIVE
        || StringUtils.isBlank(pair.getLocalCalendarSyncUid())) {
      return false;
    }
    String href = StringUtils.stripEnd(StringUtils.trimToEmpty(pair.getRemoteHref()), "/");
    return href.endsWith("/" + CaldavOutboundService.COLLECTION_PREFIX + pair.getLocalCalendarSyncUid());
  }

  /**
   * Whether a pair binds an imported calendar that may be shared, before its
   * ownership on the server is confirmed: {@link SyncOrigin#REMOTE}, active —
   * so neither a hidden share nor a paused, gone or deleted binding — with an
   * anchor, and not the dedicated meetings mirror, which holds copies only.
   *
   * @param pair the pair
   * @return true when the server may be asked whether the caller owns it
   */
  private static boolean isShareableImportedPair(CalendarSync pair) {
    return pair != null && pair.getOrigin() == SyncOrigin.REMOTE && pair.getStatus() == CalendarSyncStatus.ACTIVE
        && StringUtils.isNotBlank(pair.getLocalCalendarSyncUid()) && StringUtils.isNotBlank(pair.getRemoteHref())
        && !isMirrorCollection(pair.getRemoteHref());
  }

  /**
   * Whether a collection is the dedicated one eXo copies meetings into.
   *
   * @param href the collection href, any spelling
   * @return true for the {@code exo-meetings} slug
   */
  private static boolean isMirrorCollection(String href) {
    return StringUtils.stripEnd(CaldavSyncStorage.canonicalHref(href), "/").endsWith("/" + CaldavPushService.MIRROR_COLLECTION_SLUG);
  }

  /**
   * The imported collections the menu may offer "Share" on, with the checks a
   * listing can afford. On BlueMind the path rule alone: a REST session per
   * calendar per menu refresh would be too much, and the access list is
   * checked when the drawer reads the sharees and before any change. On an
   * RFC 3744 server one listing of the caller's calendar home, whose
   * {@code DAV:owner} and privileges decide. Anything that fails offers no
   * imported calendar; eXo-created ones are unaffected.
   *
   * @param userIdentityId the caller
   * @param serverId the server registration
   * @param endpoint the caller's endpoint
   * @param mechanism the mechanism the server offers
   * @param imported the imported pairs of the caller's owned calendars
   * @return the canonical hrefs of those the caller owns
   */
  private Set<String> ownedImportedHrefs(long userIdentityId,
                                         long serverId,
                                         CalDavEndpoint endpoint,
                                         SharingMechanism mechanism,
                                         List<CalendarSync> imported) {
    if (imported.isEmpty()) {
      return Set.of();
    }
    String principal = caldavConnectionIdentityService.principalOf(userIdentityId, serverId);
    if (principal == null) {
      return Set.of();
    }
    if (mechanism == SharingMechanism.BLUEMIND_SHARE) {
      return imported.stream()
                     .map(pair -> CaldavSyncStorage.canonicalHref(pair.getRemoteHref()))
                     .filter(href -> isOwnBlueMindCollection(href, principal))
                     .collect(java.util.stream.Collectors.toSet());
    }
    try {
      CalendarHome home = calDavClient.discoverHome(endpoint);
      Set<String> owned = new java.util.HashSet<>();
      for (CalendarCollection collection : calDavClient.listCalendars(endpoint, home.href())) {
        String href = CaldavSyncStorage.canonicalHref(collection.href());
        if (isOwnedRfc3744Collection(collection, href, home.href(), principal)) {
          owned.add(href);
        }
      }
      return owned;
    } catch (CalDavException e) {
      LOG.debug("The calendar home of user {} on server {} could not be listed; no imported calendar is offered", userIdentityId,
                serverId, e);
      return Set.of();
    }
  }

  /**
   * Refuses an imported calendar the caller does not own on the server, before
   * its access list is read or anything is changed (only the capability probe,
   * which selects the rule, comes first); an eXo-created calendar passes untouched. On
   * BlueMind this is the path rule, and {@link #requireBlueMindManager} checks
   * the access list read next. On an RFC 3744 server the collection's own
   * {@code DAV:owner} and privileges, read at depth 0, and the caller's
   * calendar home decide.
   *
   * @param target the calendar
   * @param mechanism the mechanism the server offers
   */
  private void requireImportedOwned(ShareTarget target, SharingMechanism mechanism) {
    if (!target.imported()) {
      return;
    }
    String principal = caldavConnectionIdentityService.principalOf(target.userIdentityId(), target.serverId());
    String href = CaldavSyncStorage.canonicalHref(target.href());
    boolean owned;
    if (principal == null) {
      owned = false;
    } else if (mechanism == SharingMechanism.BLUEMIND_SHARE) {
      owned = isOwnBlueMindCollection(href, principal);
    } else {
      CalendarHome home = calDavClient.discoverHome(target.endpoint());
      owned = isOwnedRfc3744Collection(calDavClient.readCalendar(target.endpoint(), target.href()), href, home.href(), principal);
    }
    if (!owned) {
      LOG.debug("Imported calendar {} ({}) is not user {}'s own on server {}; it is not shared", target.calendarId(), target.href(),
                target.userIdentityId(), target.serverId());
      throw new CaldavShareException(NOT_OWNED_ON_SERVER);
    }
  }

  /**
   * Refuses an imported BlueMind calendar on which BlueMind's own access list
   * does not give the caller {@code All} or {@code Manage}: its DAV owner and
   * privileges name a subscriber as owner, so only the container's access list
   * says who may decide who sees it (the owner is listed with every verb).
   * An eXo-created calendar passes untouched.
   *
   * @param target the calendar
   * @param aces the container's expanded access list
   */
  private void requireBlueMindManager(ShareTarget target, List<BlueMindAce> aces) {
    if (!target.imported()) {
      return;
    }
    String uid = blueMindUidOf(caldavConnectionIdentityService.principalOf(target.userIdentityId(), target.serverId()));
    if (uid == null || blueMindVerbsOf(aces, uid).stream().noneMatch(BLUEMIND_MANAGING::contains)) {
      LOG.debug("BlueMind gives user {} no right to manage calendar {} ({}); it is not shared", target.userIdentityId(),
                target.calendarId(), target.href());
      throw new CaldavShareException(NOT_OWNED_ON_SERVER);
    }
  }

  /**
   * Whether an RFC 3744 server says a collection is the caller's own: its
   * {@code DAV:owner} is the caller's recorded principal, its privilege set was
   * answered and lets them write, and it sits under their calendar home — a
   * colleague's calendar listed in that home fails the owner or the home check.
   *
   * @param collection the collection as read, may be null
   * @param canonicalHref its canonical href, blank when the server named none this
   *          client can use (no href, or one on another host): never owned
   * @param homeHref the caller's calendar home
   * @param principal the caller's recorded principal
   * @return true when all hold
   */
  private static boolean isOwnedRfc3744Collection(CalendarCollection collection, String canonicalHref, String homeHref, String principal) {
    if (collection == null || StringUtils.isBlank(canonicalHref) || StringUtils.isBlank(collection.owner()) || !collection.privilegesAnswered()
        || !collection.writable()) {
      return false;
    }
    if (!CalendarCollection.principalPathOf(collection.owner()).equals(CalendarCollection.principalPathOf(principal))) {
      return false;
    }
    String home = CaldavSyncStorage.canonicalHref(homeHref);
    return StringUtils.isNotBlank(home) && canonicalHref.startsWith(home + "/") && !isMirrorCollection(canonicalHref);
  }

  /**
   * Whether a BlueMind collection path may be the caller's own calendar, by
   * its name: under their own uid, and a container either named for them —
   * their default {@code calendar:Default:<uid>} or one they created
   * {@code calendar:UserCreated:<uid>:…} — or not named with the
   * {@code calendar:} prefix at all. A {@code calendar:} name for anyone else
   * (a colleague's default or created calendar, a resource) is a subscription
   * and refused here. A bare uid can still be a subscription (a domain
   * calendar, a colleague's DAV-created one), so BlueMind's access list read
   * next ({@link #requireBlueMindManager}) stays the authority. BlueMind's DAV
   * owner and privileges are not consulted: on a subscription they name the
   * subscriber as owner.
   *
   * @param canonicalHref the canonical collection href
   * @param principal the caller's recorded principal
   * @return true when the name does not rule out the caller owning it
   */
  private static boolean isOwnBlueMindCollection(String canonicalHref, String principal) {
    String uid = blueMindUidOf(principal);
    Matcher matcher = BLUEMIND_CONTAINER.matcher(StringUtils.trimToEmpty(canonicalHref));
    if (uid == null || !matcher.matches() || !matcher.group(1).equalsIgnoreCase(uid)) {
      return false;
    }
    String container = matcher.group(2);
    if (container.equals(CaldavPushService.MIRROR_COLLECTION_SLUG)) {
      return false;
    }
    if (!container.regionMatches(true, 0, "calendar:", 0, 9)) {
      return true;
    }
    String userCreated = "calendar:UserCreated:" + uid + ":";
    return container.equalsIgnoreCase("calendar:Default:" + uid)
        || container.regionMatches(true, 0, userCreated, 0, userCreated.length()) && container.length() > userCreated.length();
  }

  /**
   * Says whether the shared calendar is also where eXo writes the copies of the
   * user's eXo meetings, asked through the push's own resolution
   * ({@link CaldavPushService#mirrorDestination}, the lookup
   * resolution {@code currentMirror} and {@code ensureMirror} use), so that the drawer's warning
   * never disagrees with where the copies go. Asked for every calendar: the
   * copies usually land in an imported main calendar, but the push can also
   * adopt an existing calendar, an eXo-created one included, when it cannot
   * create its dedicated one.
   *
   * <p>
   * A lookup that fails warns rather than stays silent, since a missed warning
   * exposes meetings while a false one costs a click. The destination the
   * push last recorded decides when there is one
   * ({@code CaldavUserSetting.getMirrorCalendarHref}, saved by
   * {@code ensureMirror}). With none recorded, the warning is shown.
   *
   * @param target the calendar
   * @param username the caller's login
   * @param shares the shares as read
   * @return the shares with the flag
   */
  private CalendarShares withMeetingCopies(ShareTarget target, String username, CalendarShares shares) {
    if (shares == null) {
      return shares;
    }
    String href = CaldavSyncStorage.canonicalHref(target.href());
    boolean copies;
    try {
      MirrorTarget mirror = caldavPushService.mirrorDestination(target.userIdentityId(), username);
      copies = mirror != null && StringUtils.isNotBlank(mirror.href()) && CaldavSyncStorage.canonicalHref(mirror.href()).equals(href);
    } catch (RuntimeException e) {
      CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(target.userIdentityId());
      String recorded = settings == null ? null : settings.getMirrorCalendarHref();
      copies = StringUtils.isBlank(recorded) || CaldavSyncStorage.canonicalHref(recorded).equals(href);
      LOG.debug("Where the meeting copies of user {} go could not be asked; calendar {} is {} by the destination last recorded",
                target.userIdentityId(), target.calendarId(), copies ? "warned about" : "cleared", e);
    }
    return shares.withMeetingCopies(copies);
  }

  /**
   * The collection a pair binds, as a collection href.
   *
   * @param pair the pair
   * @return the href, with its trailing slash
   */
  private static String collectionOf(CalendarSync pair) {
    return StringUtils.appendIfMissing(StringUtils.trimToEmpty(pair.getRemoteHref()), "/");
  }

  /**
   * The server key of an account.
   *
   * @param settings the account
   * @return its registration, zero for an account predating registrations
   */
  private static long serverIdOf(CaldavUserSetting settings) {
    return settings.getServerId() == null ? 0L : settings.getServerId();
  }

  /**
   * Whether an account is usable.
   *
   * @param settings the stored account
   * @return true when it carries credentials
   */
  private static boolean connected(CaldavUserSetting settings) {
    return settings != null && StringUtils.isNotBlank(settings.getUsername())
        && StringUtils.isNotBlank(settings.getPassword());
  }

  /**
   * A calendar checked for sharing.
   *
   * @param userIdentityId the owner, who is the caller
   * @param calendarId the agenda calendar
   * @param serverId the server key
   * @param pair the pair binding it
   * @param href its collection
   * @param endpoint the owner's endpoint
   */
  private record ShareTarget(long userIdentityId,
                             long calendarId,
                             long serverId,
                             CalendarSync pair,
                             String href,
                             CalDavEndpoint endpoint) {

    /**
     * Whether the calendar is an imported one, whose ownership on the server
     * must be confirmed before its access list is read or anything is changed.
     *
     * @return true for a {@link SyncOrigin#REMOTE} pair
     */
    boolean imported() {
      return pair.getOrigin() == SyncOrigin.REMOTE;
    }
  }

  /**
   * A sharee checked for sharing.
   *
   * @param identityId the social identity
   * @param username the login
   * @param principal the canonical principal recorded on the calendar's server
   */
  private record Sharee(long identityId, String username, String principal) {
  }
}
