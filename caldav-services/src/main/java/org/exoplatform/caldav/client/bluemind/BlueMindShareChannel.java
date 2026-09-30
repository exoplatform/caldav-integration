/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.caldav.client.bluemind;

import static org.exoplatform.caldav.service.CaldavCalendarShareService.ACL_UNREADABLE;
import static org.exoplatform.caldav.service.CaldavCalendarShareService.NOT_APPLIED;
import static org.exoplatform.caldav.service.CaldavCalendarShareService.NOT_OWNED_ON_SERVER;
import static org.exoplatform.caldav.service.CaldavCalendarShareService.NOT_READ_ONLY;
import static org.exoplatform.caldav.service.CaldavCalendarShareService.NOT_SUPPORTED;
import static org.exoplatform.caldav.service.CaldavCalendarShareService.SERVER_REFUSED;
import static org.exoplatform.caldav.service.CaldavCalendarShareService.SHAREE_ADDRESS_UNKNOWN;
import static org.exoplatform.caldav.service.CaldavCalendarShareService.SHAREE_HAS_OTHER_ACCESS;
import static org.exoplatform.caldav.service.CaldavCalendarShareService.SHAREE_NOT_CONNECTED;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.AccessControlEntry;
import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavForbiddenException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.DavOptions;
import org.exoplatform.caldav.client.SharingMechanism;
import org.exoplatform.caldav.client.bluemind.BlueMindAclClient.BlueMindAce;
import org.exoplatform.caldav.model.CalendarShares;
import org.exoplatform.caldav.model.CalendarShares.CalendarSharee;
import org.exoplatform.caldav.model.CalendarShares.PublishedLinkMode;
import org.exoplatform.caldav.model.CalendarShares.ShareAccess;
import org.exoplatform.caldav.model.CalendarShares.ShareUser;
import org.exoplatform.caldav.model.CalendarShares.ShareeKind;
import org.exoplatform.caldav.plugin.CalendarShareChannel;
import org.exoplatform.caldav.plugin.ShareHost;
import org.exoplatform.caldav.plugin.ShareRecipient;
import org.exoplatform.caldav.plugin.SharedCalendar;
import org.exoplatform.caldav.service.CaldavPushService;
import org.exoplatform.caldav.service.CaldavShareException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Sharing a BlueMind calendar with a colleague (EXO-90253, EXO-90277,
 * EXO-90378): Apple's {@code POST CS:share} over DAV, every change confirmed
 * by reading the container's access list back through BlueMind's REST API
 * ({@link BlueMindAclClient}), and the colleague subscribed to the calendar
 * once a grant is confirmed.
 *
 * <p>
 * <b>Recognised</b> by two independent facts that must both hold: the
 * collection advertises {@code calendarserver-sharing} in the {@code DAV}
 * header of its PROPFIND answers ({@code PropFindProtocol.java:125}, captured
 * in {@code bluemind-principal.captured.xml}), and it has the path BlueMind's
 * DAV server gives every calendar,
 * {@code /dav/calendars/__uids__/<owner uid>/<container uid>/}
 * ({@code ResType.VSTUFF_CONTAINER}). Apple's CalendarServer uses the same
 * {@code __uids__} layout without the {@code /dav} root, and Nextcloud and
 * iCloud use other paths; those stay not offered. The share service confirms
 * the recognition before any change: BlueMind's REST API must accept the
 * owner's login ({@link #acceptsCredentials}), which no other server
 * answers.
 *
 * <p>
 * <b>Around the protocol</b>, because it cannot be trusted alone: the server
 * answers 200 whatever happened, so every change is confirmed by reading the
 * container's access list back; and the server rewrites or removes
 * <em>every</em> entry of the sharee, so a sharee holding anything but a
 * grant eXo writes is refused rather than downgraded.
 *
 * <p>
 * A {@code @Service}, like any contribution from another add-on, so that the
 * host collects it by type through the Kernel/Spring bridge and never names
 * it. It logs under the share service's category, where the audit lines of
 * every other sharing mechanism are, so that what an operator filters on does
 * not depend on which server a calendar lives on.
 */
@Service
public class BlueMindShareChannel implements CalendarShareChannel {

  /** The compliance class of Apple's sharing extension, which BlueMind advertises. */
  static final String              CALENDARSERVER_SHARING = "calendarserver-sharing";

  /**
   * A calendar collection as BlueMind's DAV server lays it out
   * ({@code plugins/net.bluemind.dav.server/.../store/ResType.java},
   * {@code VSTUFF_CONTAINER}): the owner's directory entry uid, then the
   * container uid, under {@code /dav/calendars/__uids__/}.
   */
  static final Pattern             BLUEMIND_COLLECTION_PATH = Pattern.compile("/dav/calendars/__uids__/[^/]+/[^/]+");

  private static final Log         LOG                    = ExoLogger.getLogger("org.exoplatform.caldav.service.CaldavCalendarShareService");

  /** The BlueMind verb plain reading is ({@code Verb.Read}). */
  private static final String      BLUEMIND_READ          = "Read";

  /**
   * What one stored {@code Read} lists as. BlueMind's REST access list is
   * expanded ({@code AclService.get}: {@code AccessControlEntry.expand}) and
   * {@code Verb.java} declares {@code Read(Freebusy, Visible)} and
   * {@code Freebusy(Invitation)}; a subject holding nothing outside this set
   * holds no more than reading.
   */
  private static final Set<String> BLUEMIND_READ_CLOSURE  = Set.of(BLUEMIND_READ, "Freebusy", "Invitation", "Visible");

  /** The BlueMind verb a {@code CS:read-write} share stores (EXO-90378). */
  private static final String      BLUEMIND_WRITE         = "Write";

  /**
   * What one stored {@code Write} lists as (EXO-90378). BlueMind's
   * {@code Verb.java} declares {@code Write(Read)} above
   * {@code Read(Freebusy, Visible)} and {@code Freebusy(Invitation)}, and its
   * REST access list is expanded; a subject holding nothing outside this set
   * holds no more than reading and writing the calendar's events — not
   * {@code Manage}, not {@code All}, not {@code ReadExtended}.
   */
  private static final Set<String> BLUEMIND_WRITE_CLOSURE = Set.of(BLUEMIND_WRITE, BLUEMIND_READ, "Freebusy", "Invitation", "Visible");

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

  /** Reads a BlueMind calendar's access list back through its REST API. */
  private final BlueMindAclClient  blueMindAclClient;

  /** Posts the share and reads a sharee's addresses, as the owner. */
  private final CalDavClient       calDavClient;

  /**
   * The channel over BlueMind's access-list client and the DAV client the
   * {@code CS:share} is posted through.
   *
   * @param blueMindAclClient reads a BlueMind calendar's access list back
   * @param calDavClient posts the share and reads a sharee's addresses, as
   *          the owner
   */
  @Autowired
  public BlueMindShareChannel(BlueMindAclClient blueMindAclClient, CalDavClient calDavClient) {
    this.blueMindAclClient = blueMindAclClient;
    this.calDavClient = calDavClient;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SharingMechanism mechanism() {
    return SharingMechanism.BLUEMIND_SHARE;
  }

  /**
   * Takes over a collection the host selects Apple sharing for, when it
   * advertises {@value #CALENDARSERVER_SHARING} and has BlueMind's path.
   *
   * @param options what the collection answered
   * @param collectionHref the collection's path, may be null
   * @param selected what the host selects from the options alone
   * @return true for a BlueMind calendar collection
   */
  @Override
  public boolean applies(DavOptions options, String collectionHref, SharingMechanism selected) {
    return selected == SharingMechanism.CALENDARSERVER_SHARE && options.advertises(CALENDARSERVER_SHARING)
        && isBlueMindCollection(collectionHref);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean acceptsCredentials(CalDavEndpoint endpoint) {
    return blueMindAclClient.acceptsCredentials(endpoint);
  }

  /**
   * BlueMind's path rule: a REST session per calendar per menu refresh would
   * be too much, and the access list is checked when the drawer reads the
   * sharees and before any change.
   *
   * @param canonicalHref the canonical collection href
   * @param principal the caller's recorded principal
   * @return true when the name does not rule out the caller owning it
   */
  @Override
  public boolean mayOwn(String canonicalHref, String principal) {
    return isOwnBlueMindCollection(canonicalHref, principal);
  }

  /**
   * The container's access list, the caller's right to manage it checked,
   * as sharees.
   *
   * @param target the calendar
   * @param host what the host lends
   * @return the sharees
   */
  @Override
  public CalendarShares shares(SharedCalendar target, ShareHost host) {
    List<BlueMindAce> aces = blueMindAclOf(target);
    requireBlueMindManager(target, aces, host);
    return blueMindSharesOf(target, aces, host.ownerPrincipalForRead(target), host);
  }

  /**
   * Refuses an imported calendar BlueMind gives the caller no right to
   * manage.
   *
   * @param target the calendar
   * @param host what the host lends
   */
  @Override
  public void requireManager(SharedCalendar target, ShareHost host) {
    if (target.imported()) {
      requireBlueMindManager(target, blueMindAclOf(target), host);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CalendarShares grant(SharedCalendar target,
                              ShareRecipient sharee,
                              String ownerPrincipal,
                              ShareAccess access,
                              ShareHost host) {
    return blueMindGrant(target, sharee, target.username(), ownerPrincipal, access, host);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CalendarShares revoke(SharedCalendar target, ShareRecipient sharee, String ownerPrincipal, ShareHost host) {
    return blueMindRevoke(target, sharee, target.username(), ownerPrincipal, host);
  }

  /**
   * Whether a path is a calendar collection as BlueMind's DAV server names one.
   *
   * @param collectionHref the collection's path, may be null
   * @return true for {@code /dav/calendars/__uids__/<uid>/<container>/}
   */
  public static boolean isBlueMindCollection(String collectionHref) {
    return collectionHref != null
        && BLUEMIND_COLLECTION_PATH.matcher(CalendarCollection.principalPathOf(collectionHref)).matches();
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
   * @param wanted the level to grant, read or write
   * @param host what the host lends the operation
   * @return the sharees as read back
   */
  private CalendarShares blueMindGrant(SharedCalendar target,
                                       ShareRecipient sharee,
                                       String username,
                                       String ownerPrincipal,
                                       ShareAccess wanted,
                                       ShareHost host) {
    String shareeUid = blueMindUidOf(sharee.principal());
    if (shareeUid == null) {
      throw new IllegalArgumentException(SHAREE_NOT_CONNECTED);
    }
    Lock lock = host.lockOf(target);
    lock.lock();
    try {
      List<BlueMindAce> before = blueMindAclOf(target);
      requireBlueMindManager(target, before, host);
      Set<String> theirs = blueMindVerbsOf(before, shareeUid);
      boolean write = wanted == ShareAccess.WRITE;
      if (!theirs.isEmpty() && !isExoShapedBlueMind(theirs)) {
        // Something eXo did not write — Manage, All, ReadExtended, or free/busy
        // alone. A CS:set overwrites the subject's verb, so rewriting one of
        // these would take away access the owner granted in BlueMind's own
        // interface; refused rather than rewritten, as revoke refuses it.
        throw new IllegalArgumentException(BLUEMIND_READ_CLOSURE.containsAll(theirs) ? SHAREE_HAS_OTHER_ACCESS
                                                                                     : NOT_READ_ONLY);
      }
      if (write ? isPlainBlueMindWrite(theirs) : isPlainBlueMindRead(theirs)) {
        LOG.debug("Calendar {} already grants {} to {}; nothing is sent", target.calendarId(), wanted, sharee.principal());
        return blueMindSharesOf(target, before, ownerPrincipal, host);
      }
      postBlueMindShare(target, blueMindAddressOf(target, sharee), false, write);
      List<BlueMindAce> after = blueMindAclOf(target);
      warnOnChangedBlueMindEntries(target, before, after, shareeUid);
      Set<String> now = blueMindVerbsOf(after, shareeUid);
      if (!now.contains(BLUEMIND_READ)) {
        LOG.warn("The server answered the share of calendar {} ({}) with {}, but its access list read back gives entry {} no read"
            + " access; reported as not applied", target.calendarId(), target.href(), sharee.principal(), shareeUid);
        throw new CaldavShareException(NOT_APPLIED);
      }
      if (write && !now.contains(BLUEMIND_WRITE)) {
        // BlueMind answers 200 even when the share did nothing — an unresolved
        // CS:href is logged and skipped, and its handler swallows every failure
        // — so a 200 is not proof of the level (EXO-90378). The read-back is,
        // and it says reading: reported as the level the server holds rather
        // than as the level asked for. Not a failure: the colleague reads the
        // calendar on the server and edits it in eXo, and agenda logs the
        // difference. blueMindSharesOf answers READ from the same verbs.
        LOG.warn("The server answered the write share of calendar {} ({}) with {}, but its access list read back gives entry {}"
            + " reading only; reported as read access", target.calendarId(), target.href(), sharee.principal(), shareeUid);
      }
      host.followSubscription(target, sharee, shareeUid, containerUidOf(target), true);
      LOG.info("CalDAV share granted: user {} gave {} {} access to calendar {} ({}) as BlueMind entry {} on server {}",
               username,
               sharee.username(),
               write ? "read and write" : "read",
               target.calendarId(),
               target.href(),
               shareeUid,
               target.serverId());
      return blueMindSharesOf(target, after, ownerPrincipal, host);
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
   * @param host what the host lends the operation
   * @return the sharees as read back
   */
  private CalendarShares blueMindRevoke(SharedCalendar target,
                                        ShareRecipient sharee,
                                        String username,
                                        String ownerPrincipal,
                                        ShareHost host) {
    String shareeUid = blueMindUidOf(sharee.principal());
    if (shareeUid == null) {
      throw new IllegalArgumentException(SHAREE_NOT_CONNECTED);
    }
    Lock lock = host.lockOf(target);
    lock.lock();
    try {
      List<BlueMindAce> before = blueMindAclOf(target);
      requireBlueMindManager(target, before, host);
      Set<String> theirs = blueMindVerbsOf(before, shareeUid);
      if (theirs.isEmpty()) {
        LOG.debug("Calendar {} gives {} nothing; nothing is sent", target.calendarId(), sharee.principal());
        return blueMindSharesOf(target, before, ownerPrincipal, host);
      }
      if (!isExoShapedBlueMind(theirs)) {
        throw new IllegalArgumentException(NOT_READ_ONLY);
      }
      postBlueMindShare(target, blueMindAddressOf(target, sharee), true);
      List<BlueMindAce> after = blueMindAclOf(target);
      warnOnChangedBlueMindEntries(target, before, after, shareeUid);
      if (!blueMindVerbsOf(after, shareeUid).isEmpty()) {
        LOG.warn("The server answered removing {} from calendar {} ({}), but its access list read back still names entry {};"
            + " reported as not applied", sharee.principal(), target.calendarId(), target.href(), shareeUid);
        throw new CaldavShareException(NOT_APPLIED);
      }
      host.followSubscription(target, sharee, shareeUid, containerUidOf(target), false);
      LOG.info("CalDAV share revoked: user {} took read access to calendar {} ({}) away from {} as BlueMind entry {} on server {}",
               username,
               target.calendarId(),
               target.href(),
               sharee.username(),
               shareeUid,
               target.serverId());
      return blueMindSharesOf(target, after, ownerPrincipal, host);
    } finally {
      lock.unlock();
    }
  }

  /**
   * A BlueMind calendar's access list, read through BlueMind's REST API as its
   * owner.
   *
   * @param target the calendar
   * @return the entries
   */
  private List<BlueMindAce> blueMindAclOf(SharedCalendar target) {
    return blueMindAclOf(target, () -> blueMindAclClient.readAcl(target.endpoint(), containerUidOf(target)));
  }

  /**
   * Runs one read of BlueMind's access list, turning what BlueMind answers
   * when the list cannot be read into the share refusal it means: a login
   * its REST API does not accept is "not offered", a refusal on an imported
   * calendar is "not the caller's", a refusal on an eXo-created one is "list
   * unreadable".
   *
   * @param target the calendar whose container is read
   * @param read the read itself
   * @param <T> what the read answers
   * @return what the read answered
   * @throws CaldavShareException when BlueMind refuses the read
   */
  private <T> T blueMindAclOf(SharedCalendar target, Supplier<T> read) {
    try {
      return read.get();
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
  private String blueMindAddressOf(SharedCalendar target, ShareRecipient sharee) {
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
  private void postBlueMindShare(SharedCalendar target, String address, boolean remove) {
    postBlueMindShare(target, address, remove, false);
  }

  /**
   * Posts one {@code CS:share} at one of the two levels eXo writes
   * (EXO-90378).
   *
   * @param target the calendar
   * @param address the sharee's mail address
   * @param remove true to stop sharing
   * @param write true for {@code CS:read-write}, false for {@code CS:read}
   */
  private void postBlueMindShare(SharedCalendar target, String address, boolean remove, boolean write) {
    try {
      calDavClient.postCalendarServerShare(target.endpoint(), target.pair(), address, remove, write);
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
   * @param host what the host lends the operation
   * @return the sharees
   */
  private CalendarShares blueMindSharesOf(SharedCalendar target, List<BlueMindAce> aces, String ownerPrincipal, ShareHost host) {
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
      // Three answers since EXO-90378: a plain read is READ, a plain write is
      // WRITE — both shapes eXo writes — and anything else was given outside
      // eXo and is MORE
      boolean read = isPlainBlueMindRead(verbs);
      boolean writeGrant = isPlainBlueMindWrite(verbs);
      boolean more = !read && !writeGrant;
      if (more && BLUEMIND_READ_CLOSURE.containsAll(verbs)) {
        // Free/busy alone, or nothing: access below viewing, not a sharee
        return;
      }
      String canonical = "/dav/principals/__uids__/" + subject;
      String href = AccessControlEntry.principalHrefOf(canonical);
      ShareAccess access;
      if (more) {
        access = ShareAccess.MORE;
      } else if (writeGrant) {
        access = ShareAccess.WRITE;
      } else {
        access = ShareAccess.READ;
      }
      List<ShareUser> users = host.usersConnectedAs(target, canonical);
      if (users.isEmpty()) {
        sharees.add(new CalendarSharee(href, ShareeKind.OUTSIDE_EXO, List.of(), host.displayNameOf(target, href, canonical), access, false));
      } else {
        sharees.add(new CalendarSharee(href, ShareeKind.EXO_USERS, users, null, access, access != ShareAccess.MORE));
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
   * Whether a BlueMind subject holds exactly what an eXo "can edit" share
   * grants there (EXO-90378): {@code Write} and the verbs it expands to, and
   * nothing outside them.
   *
   * @param verbs the subject's expanded verbs
   * @return true for a plain write grant
   */
  private static boolean isPlainBlueMindWrite(Set<String> verbs) {
    return verbs.contains(BLUEMIND_WRITE) && BLUEMIND_WRITE_CLOSURE.containsAll(verbs);
  }

  /**
   * Whether a BlueMind subject holds a grant of a shape eXo itself writes
   * (EXO-90378) — a plain read or a plain write — and which eXo may therefore
   * widen, narrow or take back.
   * <p>
   * Anything else was given outside eXo: {@code Manage}, {@code All},
   * {@code ReadExtended}, or free/busy alone. eXo never rewrites those, which
   * is what keeps a {@code CS:set} — which overwrites a subject's verb — from
   * taking away access the owner granted in BlueMind's own interface.
   *
   * @param verbs the subject's expanded verbs
   * @return true for a grant eXo could have written
   */
  private static boolean isExoShapedBlueMind(Set<String> verbs) {
    return isPlainBlueMindRead(verbs) || isPlainBlueMindWrite(verbs);
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
  private void warnOnChangedBlueMindEntries(SharedCalendar target, List<BlueMindAce> before, List<BlueMindAce> after, String changedSubject) {
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
  private static String containerUidOf(SharedCalendar target) {
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
   * Refuses an imported BlueMind calendar on which BlueMind's own access list
   * does not give the caller {@code All} or {@code Manage}: its DAV owner and
   * privileges name a subscriber as owner, so only the container's access list
   * says who may decide who sees it (the owner is listed with every verb).
   * An eXo-created calendar passes untouched.
   *
   * @param target the calendar
   * @param aces the container's expanded access list
   * @param host what the host lends the operation
   */
  private void requireBlueMindManager(SharedCalendar target, List<BlueMindAce> aces, ShareHost host) {
    if (!target.imported()) {
      return;
    }
    String uid = blueMindUidOf(host.recordedPrincipal(target));
    if (uid == null || blueMindVerbsOf(aces, uid).stream().noneMatch(BLUEMIND_MANAGING::contains)) {
      LOG.debug("BlueMind gives user {} no right to manage calendar {} ({}); it is not shared", target.userIdentityId(),
                target.calendarId(), target.href());
      throw new CaldavShareException(NOT_OWNED_ON_SERVER);
    }
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
}
