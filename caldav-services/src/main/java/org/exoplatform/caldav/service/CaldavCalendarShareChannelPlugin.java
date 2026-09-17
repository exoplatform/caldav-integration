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
package org.exoplatform.caldav.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.CalendarShare;
import org.exoplatform.agenda.model.ChannelDelivery;
import org.exoplatform.agenda.model.ExternalShare;
import org.exoplatform.agenda.plugin.CalendarShareChannelPlugin;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.model.CalendarShares;
import org.exoplatform.caldav.model.CalendarShares.CalendarSharee;
import org.exoplatform.caldav.model.CalendarShares.ShareAccess;
import org.exoplatform.caldav.model.CalendarShares.ShareUser;
import org.exoplatform.caldav.model.CalendarShares.ShareeKind;
import org.exoplatform.caldav.service.CaldavCalendarShareService.SharedCollection;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Carries a calendar share agenda recorded onto the owner's CalDAV server
 * (EXO-90357): the colleague's principal is granted read access to the
 * collection the calendar is bound to, through {@link CaldavCalendarShareService}
 * and every check it makes, and the grant is taken away when agenda revokes
 * the share.
 *
 * <h2>Agenda owns the share; this add-on delivers it</h2>
 *
 * <p>
 * Whether a share exists is agenda's record and agenda's ACL. This plugin
 * answers what happened on the server, and agenda keeps the record whatever
 * the answer. A share the server has nothing to do with — the owner has no
 * account, the calendar is not exported, the server offers no sharing eXo
 * can confirm, the colleague has no account on that server — is the normal
 * eXo-only case, answered as not applicable. Every other refusal of the
 * share service is a genuine delivery failure ({@code NOT_READ_ONLY},
 * {@code SERVER_UNREACHABLE}, ...): agenda logs it and keeps the record
 * undelivered; the owner is told nothing.
 *
 * <h2>Channel identifiers</h2>
 *
 * <p>
 * The plugin is one bean and answers {@link #id()} {@code caldav}; a delivery
 * is stamped {@code caldav:<serverId>} so the record says which server holds
 * the grant, and agenda resolves the channel by that prefix.
 *
 * <h2>No business logic here</h2>
 *
 * <p>
 * Every rule — who owns what, which collection, what the server allows — is
 * the share service's. This class resolves the owner and the colleague to
 * logins, transfers the call, and maps the outcome onto agenda's answers.
 */
@Service
public class CaldavCalendarShareChannelPlugin implements CalendarShareChannelPlugin {

  /** What {@link #id()} answers, and the prefix of every delivery reference. */
  public static final String       CHANNEL_ID       = "caldav";

  /** What agenda words as "the calendar server could not be reached". */
  static final String              SERVER_UNREACHABLE = "SERVER_UNREACHABLE";

  /** The kind agenda's drawer gives a colleague's grant it may record in eXo. */
  static final String              EXO_USER         = "EXO_USER";

  /** A mail address: no markup, one {@code @}. */
  private static final java.util.regex.Pattern ADDRESS = java.util.regex.Pattern.compile("[^\\s<>&\"'@/]+@[^\\s<>&\"'@/]+");

  private static final Log         LOG              = ExoLogger.getLogger(CaldavCalendarShareChannelPlugin.class);

  /**
   * The refusals that say the share is none of this channel's business: the
   * owner has no account, the calendar is not on the server, the server
   * offers no sharing eXo can confirm, or the colleague has no account on
   * that server. A share then stays eXo-only: the normal case for a colleague
   * who reads their calendars in eXo, not a failure.
   */
  private static final Set<String> NOT_APPLICABLE   = Set.of(CaldavCalendarShareService.NOT_CONNECTED,
                                                             CaldavCalendarShareService.CALENDAR_NOT_ON_SERVER,
                                                             CaldavCalendarShareService.NOT_SUPPORTED,
                                                             CaldavCalendarShareService.SHAREE_NOT_CONNECTED);

  @Autowired
  private CaldavCalendarShareService caldavCalendarShareService;

  @Autowired
  private AgendaCalendarService      agendaCalendarService;

  @Autowired
  private IdentityManager            identityManager;

  /**
   * {@inheritDoc}
   */
  @Override
  public String id() {
    return CHANNEL_ID;
  }

  /**
   * Grants the colleague read access to the calendar's collection on the
   * owner's server, as the share service does it, and answers where the grant
   * now lives — the server and the collection — or why it could not be made.
   *
   * @param share the eXo record
   * @param ownerUsername the owner's login
   * @return delivered with {@code caldav:<serverId>} and the collection href;
   *         not applicable when the owner has no account or the calendar is
   *         not on the server; failed with the refusal's code otherwise
   */
  @Override
  public ChannelDelivery deliver(CalendarShare share, String ownerUsername) {
    if (share == null || StringUtils.isBlank(ownerUsername)) {
      return ChannelDelivery.notApplicable();
    }
    try {
      long ownerId = ownerOf(share.getCalendarId());
      String shareeUsername = usernameOf(share.getShareeIdentityId());
      SharedCollection collection = caldavCalendarShareService.sharedCollectionOf(ownerId, ownerUsername, share.getCalendarId());
      caldavCalendarShareService.grant(ownerId, ownerUsername, share.getCalendarId(), shareeUsername);
      return ChannelDelivery.delivered(channelIdOf(collection.serverId()), collection.href());
    } catch (CaldavShareException | IllegalArgumentException e) {
      return outcomeOf(e, share);
    } catch (ObjectNotFoundException | IllegalAccessException e) {
      LOG.debug("The share of calendar {} with {} cannot be carried to the server: {}", share.getCalendarId(), share.getShareeIdentityId(), e.getMessage());
      return ChannelDelivery.failed(codeOf(e.getMessage()));
    }
  }

  /**
   * Takes the colleague's read access away on the server, as the share
   * service does it. A colleague who holds no grant any more changes nothing
   * and counts as withdrawn.
   *
   * @param share the eXo record this channel carried
   * @param ownerUsername the owner's login
   * @return true when the grant is gone
   */
  @Override
  public boolean withdraw(CalendarShare share, String ownerUsername) {
    if (share == null || StringUtils.isBlank(ownerUsername)) {
      return false;
    }
    try {
      long ownerId = ownerOf(share.getCalendarId());
      String shareeUsername = usernameOf(share.getShareeIdentityId());
      caldavCalendarShareService.revoke(ownerId, ownerUsername, share.getCalendarId(), shareeUsername);
      return true;
    } catch (CaldavShareException | IllegalArgumentException | ObjectNotFoundException | IllegalAccessException e) {
      LOG.debug("The share of calendar {} with {} could not be withdrawn from the server: {}",
                share.getCalendarId(),
                share.getShareeIdentityId(),
                e.getMessage());
      return false;
    }
  }

  /**
   * The grants the server holds on the calendar's collection that agenda
   * has no record of, read live: a colleague granted from another client,
   * someone outside eXo, everyone, a published link. A colleague agenda
   * already holds a record for is left out. A colleague's plain read grant
   * carries the collection href as its delivery reference: agenda records it
   * as an adopted share, silently, the moment the owner lists their shares.
   *
   * <p>
   * Only a colleague's plain read grant can be removed from here, since the
   * share service revokes by login; any other grant is listed as it is, for
   * the owner to manage where it was set.
   *
   * @param calendarId the agenda calendar
   * @param ownerUsername the owner's login
   * @param recordedShareeIds the colleagues agenda already holds a record for
   * @return the external shares, empty when the calendar is not on the
   *         server, the owner has no account, or the list cannot be read
   */
  @Override
  public List<ExternalShare> listExternalShares(long calendarId, String ownerUsername, List<Long> recordedShareeIds) {
    ServerShares server = sharesOf(calendarId, ownerUsername);
    if (server == null) {
      return List.of();
    }
    String channelId = channelIdOf(server.collection().serverId());
    List<Long> recorded = recordedShareeIds == null ? List.of() : recordedShareeIds;
    List<ExternalShare> external = new ArrayList<>();
    for (CalendarSharee sharee : server.shares().sharees()) {
      boolean readOnly = sharee.access() == ShareAccess.READ;
      if (sharee.kind() == ShareeKind.EXO_USERS) {
        for (ShareUser user : sharee.users()) {
          if (!recorded.contains(user.identityId())) {
            external.add(new ExternalShare(channelId,
                                           user.username(),
                                           EXO_USER,
                                           user.identityId(),
                                           user.fullName(),
                                           sharee.removable(),
                                           readOnly,
                                           null,
                                           readOnly ? server.collection().href() : null));
          }
        }
      } else {
        external.add(new ExternalShare(channelId,
                                       sharee.principal(),
                                       sharee.kind().name(),
                                       0,
                                       displayNameOf(sharee),
                                       false,
                                       readOnly,
                                       emailOf(sharee),
                                       null));
      }
    }
    return external;
  }

  /**
   * Removes a colleague's read grant from the server: the external
   * identifier is their login, as {@link #listExternalShares} gave it.
   *
   * @param calendarId the agenda calendar
   * @param externalId the colleague's login
   * @param ownerUsername the owner's login
   * @return true when removed
   */
  @Override
  public boolean removeExternalShare(long calendarId, String externalId, String ownerUsername) {
    if (StringUtils.isBlank(externalId) || StringUtils.isBlank(ownerUsername)) {
      return false;
    }
    try {
      caldavCalendarShareService.revoke(ownerOf(calendarId), ownerUsername, calendarId, externalId);
      return true;
    } catch (CaldavShareException | IllegalArgumentException | ObjectNotFoundException | IllegalAccessException e) {
      LOG.debug("The grant of calendar {} to {} could not be removed from the server: {}", calendarId, externalId, e.getMessage());
      return false;
    }
  }

  /**
   * Whether the calendar is where this add-on writes the owner's eXo meeting
   * copies — the mirror of a connected account — so agenda asks before
   * sharing it.
   *
   * @param calendarId the agenda calendar
   * @param ownerUsername the owner's login
   * @return true when the calendar receives the meeting copies; false when
   *         it does not, is not on the server, or cannot be told
   */
  @Override
  public boolean holdsMeetingCopies(long calendarId, String ownerUsername) {
    try {
      return caldavCalendarShareService.holdsMeetingCopies(ownerOf(calendarId), ownerUsername, calendarId);
    } catch (RuntimeException | ObjectNotFoundException | IllegalAccessException e) {
      LOG.debug("Whether calendar {} holds meeting copies could not be told; answered as no", calendarId, e);
      return false;
    }
  }

  /**
   * The server's access list for a calendar, with the collection it was read
   * from, or null whenever it cannot be read: the drawer then lists no
   * external share rather than failing.
   *
   * @param calendarId the agenda calendar
   * @param ownerUsername the owner's login
   * @return the collection and the shares, or null
   */
  private ServerShares sharesOf(long calendarId, String ownerUsername) {
    if (StringUtils.isBlank(ownerUsername)) {
      return null;
    }
    try {
      long ownerId = ownerOf(calendarId);
      SharedCollection collection = caldavCalendarShareService.sharedCollectionOf(ownerId, ownerUsername, calendarId);
      return new ServerShares(collection, caldavCalendarShareService.listShares(ownerId, ownerUsername, calendarId));
    } catch (CaldavShareException | IllegalArgumentException | ObjectNotFoundException | IllegalAccessException e) {
      LOG.debug("The server's shares of calendar {} could not be read: {}", calendarId, e.getMessage());
      return null;
    }
  }

  /**
   * What a refusal of the share service means to agenda: nothing to carry
   * for the refusals in {@link #NOT_APPLICABLE}, a named failure otherwise.
   *
   * @param refusal the refusal
   * @param share the record, for the log
   * @return the outcome
   */
  private static ChannelDelivery outcomeOf(RuntimeException refusal, CalendarShare share) {
    String code = refusal instanceof CaldavShareException failure ? failure.getCode() : refusal.getMessage();
    if (code != null && NOT_APPLICABLE.contains(code)) {
      LOG.debug("The share of calendar {} with {} stays in eXo only: {}", share.getCalendarId(), share.getShareeIdentityId(), code);
      return ChannelDelivery.notApplicable();
    }
    LOG.debug("The share of calendar {} with {} was not carried to the server: {}",
              share.getCalendarId(),
              share.getShareeIdentityId(),
              code,
              refusal);
    return ChannelDelivery.failed(codeOf(code));
  }

  /**
   * A share service code in the shape agenda logs a delivery failure:
   * {@code caldav.share.notReadOnly} is {@code NOT_READ_ONLY}; an
   * unreachable server is {@link #SERVER_UNREACHABLE}.
   *
   * @param code the code, may be null
   * @return the failure code, never blank
   */
  static String codeOf(String code) {
    if (StringUtils.isBlank(code)) {
      return "DELIVERY_FAILED";
    }
    if (CaldavCalendarShareService.SERVER_UNAVAILABLE.equals(code)) {
      return SERVER_UNREACHABLE;
    }
    String last = code.substring(code.lastIndexOf('.') + 1);
    return last.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
  }

  /**
   * The owner of a calendar.
   *
   * @param calendarId the agenda calendar
   * @return the owner's identity id
   * @throws ObjectNotFoundException when there is no such calendar
   */
  private long ownerOf(long calendarId) throws ObjectNotFoundException {
    Calendar calendar = agendaCalendarService.getCalendarById(calendarId);
    if (calendar == null || calendar.isDeleted()) {
      throw new ObjectNotFoundException(CaldavCalendarShareService.CALENDAR_NOT_FOUND);
    }
    return calendar.getOwnerId();
  }

  /**
   * A colleague's login.
   *
   * @param identityId the colleague's identity
   * @return the login
   * @throws IllegalArgumentException with {@link CaldavCalendarShareService#SHAREE_UNKNOWN}
   *           when the identity is gone
   */
  private String usernameOf(long identityId) {
    Identity identity = identityManager.getIdentity(String.valueOf(identityId));
    if (identity == null || identity.isDeleted() || StringUtils.isBlank(identity.getRemoteId())) {
      throw new IllegalArgumentException(CaldavCalendarShareService.SHAREE_UNKNOWN);
    }
    return identity.getRemoteId();
  }

  /**
   * The channel identifier of a delivery on one server: what a share record
   * carries as {@code deliveredTo}, and what a disconnect clears by.
   *
   * @param serverId the server key
   * @return {@code caldav:<serverId>}
   */
  public static String channelIdOf(long serverId) {
    return CHANNEL_ID + ":" + serverId;
  }

  /**
   * The mail address of a sharee that is no eXo user, when the server names
   * one: a principal whose last segment is an address (Stalwart names its
   * principals so), else a display name that is one.
   *
   * @param sharee the sharee
   * @return the address, or null
   */
  private static String emailOf(CalendarSharee sharee) {
    if (sharee.kind() != ShareeKind.OUTSIDE_EXO) {
      return null;
    }
    String principal = StringUtils.defaultString(sharee.principal());
    String last = principal.substring(principal.replaceAll("/+$", "").lastIndexOf('/') + 1).replaceAll("/+$", "");
    try {
      last = java.net.URLDecoder.decode(last, java.nio.charset.StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      // Not encoded: taken as is
    }
    if (ADDRESS.matcher(last).matches()) {
      return last;
    }
    String name = StringUtils.defaultString(sharee.displayName());
    return ADDRESS.matcher(name).matches() ? name : null;
  }

  /**
   * What to call a sharee that is no eXo user: the name the server gives,
   * the mode of a published link, or the principal.
   *
   * @param sharee the sharee
   * @return the name, never blank
   */
  private static String displayNameOf(CalendarSharee sharee) {
    if (StringUtils.isNotBlank(sharee.displayName())) {
      return sharee.displayName();
    }
    if (sharee.kind() == ShareeKind.PUBLISHED_LINK && sharee.publishedLink() != null) {
      return StringUtils.capitalize(sharee.publishedLink().name().toLowerCase(Locale.ROOT));
    }
    return StringUtils.defaultIfBlank(sharee.principal(), sharee.kind().name());
  }

  /**
   * A server's access list, with the collection it belongs to.
   *
   * @param collection the server and the collection
   * @param shares the shares as read
   */
  private record ServerShares(SharedCollection collection, CalendarShares shares) {
  }

}
