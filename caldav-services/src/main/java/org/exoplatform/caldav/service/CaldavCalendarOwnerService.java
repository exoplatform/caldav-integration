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

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Names the owner of a calendar shared with the user, from whichever witness
 * said it was shared (EXO-90237).
 *
 * <p>
 * The calendar list already knows a collection is somebody else's
 * ({@link CaldavOutboundService#ownershipOf}); what it could not say was
 * whose, so a colleague's calendar sat under a section named after the
 * server with nothing to tell the user it was shared, let alone by whom.
 * This answers the second question from the first's own evidence, and from
 * nothing else:
 *
 * <ul>
 * <li>A <b>colleague's eXo calendar</b> was recognised by the pair this
 * deployment holds for it, and that pair names its user. The owner is that
 * user, resolved through the identity registry to a login and a full name —
 * which is what agenda needs to show the person rather than a lock. On
 * BlueMind this is the only owner there is to name: the server reports the
 * <em>viewer</em> as owner of a calendar they merely subscribed to, so its
 * word would name the wrong person.</li>
 * <li>A <b>share the server reported</b> names an owner principal. When
 * exactly one eXo user of this deployment is connected to that server as
 * that principal — the identity each connection's discovery records
 * (EXO-90243), and every user synchronising with that server has been
 * recorded, so that "exactly one" is not merely "one so far" — the owner is
 * that user, named as a colleague is. Otherwise — nobody connected as it,
 * several eXo users on that one login, the viewer themself, or a server whose
 * users are not all recorded yet — the owner is a name alone: the principal's
 * {@code DAV:displayname}, read with one PROPFIND of depth 0, or when the
 * server answers none, refuses, or cannot be reached, the decoded last
 * segment of the principal path — the very path the server returned to the
 * viewer, so nothing is shown that the viewer was not already told.</li>
 * <li>The user's own calendars, and anything else, name nobody.</li>
 * </ul>
 *
 * <p>
 * Naming the owner to the viewer is acceptable because the viewer already
 * holds read access the owner granted, on the server or in eXo; mapping the
 * principal to a colleague shows the viewer the eXo person behind a login
 * the server has already named to them, and only when the mapping is
 * unambiguous. What this class never does is fail or slow a listing over an
 * owner: the identity lookups are local, the PROPFIND is bounded by the
 * client's own timeouts, both are memoised per principal within one listing,
 * and every failure degrades to a lesser name or to none.
 */
@Component
public class CaldavCalendarOwnerService {

  private static final Log                      LOG = ExoLogger.getLogger(CaldavCalendarOwnerService.class);

  private final CaldavOutboundService           caldavOutboundService;

  private final IdentityManager                 identityManager;

  private final CalDavClient                    calDavClient;

  private final CaldavConnectionIdentityService caldavConnectionIdentityService;

  /**
   * The servers already said, at info, to have users without a recorded
   * identity — see {@link #connectedUserAs}. Once per server per process: the
   * condition can last for good, and a line per listing would repeat it on
   * every agenda load.
   */
  private final Set<Long>                       incompleteServersSaid = ConcurrentHashMap.newKeySet();

  /**
   * @param caldavOutboundService the one place that knows which user's pair
   *          stands behind an eXo-minted collection
   * @param identityManager the registry that turns that user into a login and
   *          a full name
   * @param calDavClient the client that asks a principal what it calls itself
   * @param caldavConnectionIdentityService the record of which eXo users are
   *          connected as which principal
   */
  @Autowired
  public CaldavCalendarOwnerService(CaldavOutboundService caldavOutboundService,
                                    IdentityManager identityManager,
                                    CalDavClient calDavClient,
                                    CaldavConnectionIdentityService caldavConnectionIdentityService) {
    this.caldavOutboundService = caldavOutboundService;
    this.identityManager = identityManager;
    this.calDavClient = calDavClient;
    this.caldavConnectionIdentityService = caldavConnectionIdentityService;
  }

  /**
   * Who a listed collection belongs to, given whose the list already found
   * it to be.
   *
   * @param viewerIdentityId the eXo user the list is for, who is never named
   *          as the owner of a calendar shared with them
   * @param serverId the declared server registration the account is on
   * @param endpoint the account's endpoint, which a principal is asked
   *          through — never another authority
   * @param principal the account's own {@code current-user-principal}, as
   *          the discovery walk answered it; null when the server named none.
   *          What a server-named owner is compared against, so that a share
   *          by privilege alone whose owner is the user themself names nobody
   *          rather than the user
   * @param ownership whose the collection is, as
   *          {@link CaldavOutboundService#ownershipOf} answered
   * @param collection the listed collection
   * @param memo what this listing already found out about owners: read and
   *          written here, so that two shares of one colleague cost one lookup
   *          and at most one PROPFIND, not two, a principal that could not be
   *          asked is not asked again, and a server's count of unrecorded users
   *          is asked once, whatever the number of colleagues' shares
   * @return the owner, {@link CalendarOwner#NONE} for the user's own and for
   *         a share whose owner cannot be named
   */
  public CalendarOwner ownerOf(long viewerIdentityId,
                               long serverId,
                               CalDavEndpoint endpoint,
                               String principal,
                               CollectionOwnership ownership,
                               CalendarCollection collection,
                               CalendarOwnerMemo memo) {
    if (ownership == CollectionOwnership.COLLEAGUES_EXO_CALENDAR) {
      // The canonical path, as the classification asked its question: the
      // pair named is then the very one that made the collection a
      // colleague's, by construction and not only in practice.
      return colleagueBehind(serverId, CaldavSyncStorage.canonicalHref(collection.href()));
    }
    if (ownership == CollectionOwnership.SHARED) {
      // Only an owner that is somebody else. A collection is a share by the
      // privilege signal alone when the server withholds write while naming
      // the user as owner; naming that owner would say "shared by yourself".
      return shareOwnedBy(viewerIdentityId, serverId, endpoint, collection.ownerIfAnother(principal), memo);
    }
    if (ownership.isSubscription()) {
      // BlueMind names the viewer as the owner of a subscription, so the
      // owner comes from the container uid instead (EXO-90275).
      BlueMindContainerNaming.Subscription subscription = BlueMindContainerNaming.subscriptionOf(collection.href(), principal);
      if (subscription == null) {
        return CalendarOwner.NONE;
      }
      String ownerPath = BlueMindContainerNaming.principalOf(principal, subscription.ownerUid());
      return subscription.resource() ? resourceNamed(endpoint, ownerPath, collection, memo)
                                     : shareOwnedBy(viewerIdentityId, serverId, endpoint, ownerPath, memo);
    }
    return CalendarOwner.NONE;
  }

  /**
   * A resource the user subscribed to, as an owner to show: by name only.
   *
   * <p>
   * The resource's principal is asked its {@code DAV:displayname}, which
   * BlueMind answers from the directory entry of that uid
   * ({@code DisplayName#fetch}, case PRINCIPAL:
   * {@code lc.principalDirEntry(dr).displayName}). A resource is never an eXo
   * user, so no identity is looked up. When the principal cannot say, the
   * collection's own display name stands in — on BlueMind the container's
   * name, which is the resource's — and never the uid, which names nothing a
   * person recognises.
   *
   * @param endpoint the account's endpoint
   * @param ownerPath the resource's principal path
   * @param collection the listed collection, whose name is the fallback
   * @param memo the listing's memo, keyed by principal path
   * @return the resource named, or {@link CalendarOwner#NONE} when neither
   *         the principal nor the collection carries a name
   */
  private CalendarOwner resourceNamed(CalDavEndpoint endpoint,
                                      String ownerPath,
                                      CalendarCollection collection,
                                      CalendarOwnerMemo memo) {
    CalendarOwner named = memo.ownerOf(ownerPath);
    if (named == null) {
      named = CalendarOwner.named(displayNameOf(endpoint, ownerPath));
      memo.remember(ownerPath, named);
    }
    return named == CalendarOwner.NONE ? CalendarOwner.named(StringUtils.trimToNull(collection.displayName())) : named;
  }

  /**
   * The eXo user whose exported calendar a collection is.
   *
   * <p>
   * The pair that classified the collection names its user; the registry
   * names the user. A user the registry no longer knows, or knows as deleted,
   * names nobody: the calendar is still a share — the classification stands
   * — but there is no person to show for it, and showing the login of a
   * deleted account would be worse than showing nothing. The server's owner
   * is deliberately not consulted as a fallback here: on BlueMind it is the
   * viewer, which is the one wrong answer.
   *
   * @param serverId the declared server registration
   * @param href the collection path, canonical
   * @return the colleague, or {@link CalendarOwner#NONE}
   */
  private CalendarOwner colleagueBehind(long serverId, String href) {
    Long userIdentityId = caldavOutboundService.exportingUserOf(serverId, href);
    if (userIdentityId == null) {
      return CalendarOwner.NONE;
    }
    CalendarOwner colleague = eXoUserOf(userIdentityId);
    if (colleague == CalendarOwner.NONE) {
      LOG.debug("The user {} whose calendar {} is shared is no longer known; the share is listed with no owner named",
                userIdentityId,
                href);
    }
    return colleague;
  }

  /**
   * The owner of a share the server reported: the eXo user connected as its
   * principal when there is exactly one, else what the principal is called.
   *
   * <p>
   * The identity is looked up first, and when it answers no PROPFIND is made:
   * the eXo user's full name is the better name, and it is local. Both answers
   * are memoised per principal, the lesser one included, so a principal that
   * mapped to nobody and would not say its name is asked neither question
   * again in this listing.
   *
   * @param viewerIdentityId the eXo user the list is for
   * @param serverId the declared server registration
   * @param endpoint the account's endpoint
   * @param ownerPath the owner principal as a server-absolute path, or null
   *          when the server named none, or named the user themself
   * @param memo the listing's memo
   * @return the owner, or {@link CalendarOwner#NONE} when there is no other
   *         principal to name
   */
  private CalendarOwner shareOwnedBy(long viewerIdentityId,
                                     long serverId,
                                     CalDavEndpoint endpoint,
                                     String ownerPath,
                                     CalendarOwnerMemo memo) {
    if (StringUtils.isBlank(ownerPath)) {
      return CalendarOwner.NONE;
    }
    CalendarOwner owner = memo.ownerOf(ownerPath);
    if (owner == null) {
      owner = connectedUserAs(viewerIdentityId, serverId, ownerPath, memo);
      if (owner == CalendarOwner.NONE) {
        String name = StringUtils.defaultIfBlank(displayNameOf(endpoint, ownerPath), lastSegmentOf(ownerPath));
        owner = CalendarOwner.named(StringUtils.trimToNull(name));
      }
      memo.remember(ownerPath, owner);
    }
    return owner;
  }

  /**
   * The one eXo user connected to this server as an owner principal, as a
   * person to show (EXO-90243).
   *
   * <p>
   * Exactly one, or nobody. Several users on one login — a shared team
   * account, alice and alice2 on the rig — cannot be told apart by anything
   * the server says, and naming one of them would be a guess; nobody is named
   * then, and the principal's own name stands. The viewer is never named:
   * the owner principal is by construction not theirs, so a viewer recorded
   * under it is a record the next discovery corrects, not an owner. Nobody is
   * named either while a user synchronising with the server has no identity
   * recorded for it — after an upgrade, before that user's first pass — since
   * one recorded user of a login cannot then be told from the only one; that
   * is said once per server at info, since for some accounts it lasts for
   * good (see {@link CaldavConnectionIdentityService}). A user
   * the registry does not know or knows as deleted names nobody either, and
   * the name falls back as for any principal. A lookup that fails costs the
   * listing nothing but the identity.
   *
   * @param viewerIdentityId the eXo user the list is for
   * @param serverId the declared server registration
   * @param ownerPath the owner principal, not blank
   * @param memo the listing's memo, which asks the server's count of
   *          unrecorded users once per listing
   * @return the owner as an eXo user, or {@link CalendarOwner#NONE}
   */
  private CalendarOwner connectedUserAs(long viewerIdentityId, long serverId, String ownerPath, CalendarOwnerMemo memo) {
    try {
      List<Long> users = caldavConnectionIdentityService.usersConnectedAs(serverId, ownerPath);
      if (users.size() != 1) {
        if (users.size() > 1) {
          LOG.debug("The principal {} is connected by eXo users {}; its share is named by the principal alone", ownerPath, users);
        }
        return CalendarOwner.NONE;
      }
      long userIdentityId = users.get(0);
      if (userIdentityId == viewerIdentityId) {
        LOG.debug("The principal {} owning a share is recorded for its own viewer {}; it is named by the principal alone",
                  ownerPath,
                  viewerIdentityId);
        return CalendarOwner.NONE;
      }
      long unrecorded = memo.unrecordedOn(serverId, () -> caldavConnectionIdentityService.activeUsersWithoutIdentityOn(serverId));
      if (unrecorded > 0) {
        if (incompleteServersSaid.add(serverId)) {
          LOG.info("{} eXo users synchronising with CalDAV server {} have no recorded server identity; until each of them"
              + " has one, calendars shared on that server are named by their owner's principal, not as eXo users. It"
              + " lasts until their next successful synchronisation, or for good for an account left on another server"
              + " or one whose principal cannot be recorded",
                   unrecorded,
                   serverId);
        } else {
          LOG.debug("Server {} still has {} users without a recorded identity; the share of principal {} is named by the"
              + " principal alone",
                    serverId,
                    unrecorded,
                    ownerPath);
        }
        return CalendarOwner.NONE;
      }
      return eXoUserOf(userIdentityId);
    } catch (RuntimeException e) {
      LOG.debug("The eXo user connected as principal {} could not be looked up; its share is named by the principal alone",
                ownerPath,
                e);
      return CalendarOwner.NONE;
    }
  }

  /**
   * An eXo user as an owner to show: identity, login and full name.
   *
   * @param userIdentityId the social identity of the user
   * @return the user, or {@link CalendarOwner#NONE} when the registry does
   *         not know them, knows them as deleted, or knows no login for them
   */
  private CalendarOwner eXoUserOf(long userIdentityId) {
    Identity identity = identityManager.getIdentity(userIdentityId);
    if (identity == null || identity.isDeleted() || StringUtils.isBlank(identity.getRemoteId())) {
      return CalendarOwner.NONE;
    }
    Profile profile = identity.getProfile();
    String fullName = profile == null ? null : StringUtils.trimToNull(profile.getFullName());
    return new CalendarOwner(userIdentityId,
                             identity.getRemoteId(),
                             StringUtils.defaultIfBlank(fullName, identity.getRemoteId()));
  }

  /**
   * The principal's {@code DAV:displayname}, or null when it cannot be had.
   *
   * <p>
   * Every failure is caught and said at debug: an owner's name is decoration
   * on a listing that must not fail over it, and the client has already
   * warned about anything worth a warning. {@code RuntimeException} rather
   * than the client's own type on purpose — an unexpected failure in a
   * name lookup is still not worth the user's calendars.
   *
   * @param endpoint the account's endpoint
   * @param principalPath the principal to ask
   * @return its display name, or null
   */
  private String displayNameOf(CalDavEndpoint endpoint, String principalPath) {
    try {
      return StringUtils.trimToNull(calDavClient.readDisplayName(endpoint, principalPath));
    } catch (RuntimeException e) {
      LOG.debug("The principal {} could not be asked for its display name; its path names it instead", principalPath, e);
      return null;
    }
  }

  /**
   * The last segment of a principal path, percent-decoding undone.
   *
   * <p>
   * Stalwart spells the principal with the login encoded
   * ({@code /dav/pal/alice%40stalwart.local/}), and {@code alice@stalwart.local}
   * is a name a person recognises where {@code alice%40stalwart.local} is
   * not. Decoded through {@link URI}, as the ownership comparison does,
   * rather than {@code URLDecoder}, which would also turn a {@code +} into a
   * space; a path {@link URI} cannot parse is read as it came.
   *
   * @param path a server-absolute principal path, not blank
   * @return its last segment, decoded; the path itself when it has no
   *         segment to speak of
   */
  private static String lastSegmentOf(String path) {
    String decoded = path.trim();
    try {
      String parsed = URI.create(decoded).getPath();
      if (StringUtils.isNotBlank(parsed)) {
        decoded = parsed;
      }
    } catch (IllegalArgumentException e) {
      // Read raw, below.
    }
    String stripped = StringUtils.stripEnd(decoded, "/");
    return StringUtils.defaultIfBlank(StringUtils.substringAfterLast(stripped, "/"), stripped);
  }
}
