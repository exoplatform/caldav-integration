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
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarCollection;
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
 * <li>A <b>share the server reported</b> names an owner principal, and
 * nothing maps a DAV principal to an eXo user — doing so would be identity
 * resolution over a login the server chose, out of scope here. The owner is
 * therefore a name alone: the principal's {@code DAV:displayname}, read with
 * one PROPFIND of depth 0, or when the server answers none, refuses, or
 * cannot be reached, the decoded last segment of the principal path — the
 * very path the server returned to the viewer, so nothing is shown that the
 * viewer was not already told.</li>
 * <li>The user's own calendars, and anything else, name nobody.</li>
 * </ul>
 *
 * <p>
 * Naming the owner to the viewer is acceptable because the viewer already
 * holds read access the owner granted, on the server or in eXo. What this
 * class never does is fail or slow a listing over an owner: the identity
 * lookup is local, the PROPFIND is bounded by the client's own timeouts and
 * memoised per principal within one listing, and every failure degrades to
 * a lesser name or to none.
 */
@Component
public class CaldavCalendarOwnerService {

  private static final Log      LOG = ExoLogger.getLogger(CaldavCalendarOwnerService.class);

  private final CaldavOutboundService caldavOutboundService;

  private final IdentityManager       identityManager;

  private final CalDavClient          calDavClient;

  /**
   * @param caldavOutboundService the one place that knows which user's pair
   *          stands behind an eXo-minted collection
   * @param identityManager the registry that turns that user into a login and
   *          a full name
   * @param calDavClient the client that asks a principal what it calls itself
   */
  @Autowired
  public CaldavCalendarOwnerService(CaldavOutboundService caldavOutboundService,
                                    IdentityManager identityManager,
                                    CalDavClient calDavClient) {
    this.caldavOutboundService = caldavOutboundService;
    this.identityManager = identityManager;
    this.calDavClient = calDavClient;
  }

  /**
   * Who a listed collection belongs to, given whose the list already found
   * it to be.
   *
   * @param serverId the declared server registration the account is on
   * @param endpoint the account's endpoint, which a principal is asked
   *          through — never another authority
   * @param ownership whose the collection is, as
   *          {@link CaldavOutboundService#ownershipOf} answered
   * @param collection the listed collection
   * @param principalNames the names already read for owner principals during
   *          this listing, keyed by principal path; read and written here,
   *          so that two shares of one colleague cost one PROPFIND, not two,
   *          and a principal that could not be asked is not asked again
   * @return the owner, {@link CalendarOwner#NONE} for the user's own and for
   *         a share whose owner cannot be named
   */
  public CalendarOwner ownerOf(long serverId,
                               CalDavEndpoint endpoint,
                               CollectionOwnership ownership,
                               CalendarCollection collection,
                               Map<String, String> principalNames) {
    if (ownership == CollectionOwnership.COLLEAGUES_EXO_CALENDAR) {
      return colleagueBehind(serverId, collection.href());
    }
    if (ownership == CollectionOwnership.SHARED) {
      return principalNamed(endpoint, collection.owner(), principalNames);
    }
    return CalendarOwner.NONE;
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
   * @param href the collection path
   * @return the colleague, or {@link CalendarOwner#NONE}
   */
  private CalendarOwner colleagueBehind(long serverId, String href) {
    Long userIdentityId = caldavOutboundService.exportingUserOf(serverId, href);
    if (userIdentityId == null) {
      return CalendarOwner.NONE;
    }
    Identity identity = identityManager.getIdentity(String.valueOf(userIdentityId));
    if (identity == null || identity.isDeleted() || StringUtils.isBlank(identity.getRemoteId())) {
      LOG.debug("The user {} whose calendar {} is shared is no longer known; the share is listed with no owner named",
                userIdentityId,
                href);
      return CalendarOwner.NONE;
    }
    Profile profile = identity.getProfile();
    String fullName = profile == null ? null : StringUtils.trimToNull(profile.getFullName());
    return new CalendarOwner(userIdentityId,
                             identity.getRemoteId(),
                             StringUtils.defaultIfBlank(fullName, identity.getRemoteId()));
  }

  /**
   * What an owner principal calls itself, or failing that what its path
   * calls it.
   *
   * <p>
   * One PROPFIND per distinct principal per listing, through the account's
   * own endpoint — the client refuses any other authority before opening a
   * socket, and a foreign-host owner never reaches here anyway, the listing
   * having already left it null. Any refusal, timeout or blank answer falls
   * back to the path's last segment, decoded, and that fallback is memoised
   * too: a principal that would not answer once is not asked again for the
   * next share it owns.
   *
   * @param endpoint the account's endpoint
   * @param ownerPath the owner principal as a server-absolute path, or null
   *          when the server named none
   * @param principalNames the listing's memo, keyed by principal path
   * @return the owner by name, or {@link CalendarOwner#NONE} when the server
   *         named no principal
   */
  private CalendarOwner principalNamed(CalDavEndpoint endpoint, String ownerPath, Map<String, String> principalNames) {
    if (StringUtils.isBlank(ownerPath)) {
      return CalendarOwner.NONE;
    }
    String name = principalNames.get(ownerPath);
    if (name == null) {
      name = StringUtils.defaultIfBlank(displayNameOf(endpoint, ownerPath), lastSegmentOf(ownerPath));
      principalNames.put(ownerPath, name);
    }
    return CalendarOwner.named(StringUtils.trimToNull(name));
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
