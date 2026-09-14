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

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.entity.CaldavConnectionEntity;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectionStorage;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Who each connected eXo user is on their CalDAV server, and which eXo users
 * are connected as a given principal (EXO-90243).
 *
 * <p>
 * <b>The problem.</b> The engine knew "the account" only as the parent path of
 * a calendar's href, and a path cannot tell two eXo users on one login
 * (EXO-90190) from a colleague's calendar the server lists in the user's own
 * home (EXO-90235). On the rig that confusion named bob in the shared-account
 * warning of alice's account, because bob had a share of alice's calendar
 * under alice's home; and the owner of a share the server reported could only
 * ever be shown as a name, never as the eXo user it is. The server already
 * answers the discriminator on every discovery — the
 * {@code current-user-principal} — and nothing kept it.
 *
 * <p>
 * <b>What is recorded, and when.</b> The principal of each discovery the
 * account's first step makes ({@code CaldavOutboundService#bindPersonalCalendars},
 * run on connect and at the start of every pass), so recording it costs no
 * request. It is forgotten when the account is connected again — the
 * credentials just changed, and the identity recorded under the previous ones
 * must not outlive a first discovery that fails — and when it is
 * disconnected. There is no backfill: a user is known by identity from their
 * first pass after the upgrade. Until then the shared-account warning is only
 * late — it names what it finds and is asked again every pass — while the
 * owner of a share could be misread: a login two eXo users share would look
 * like one user's as long as only one of them is recorded. So no share owner
 * on a server is mapped to an eXo user while any user holding an active pair
 * there has no identity recorded for it ({@link #isEveryActiveUserRecordedOn}).
 * What that cannot see is a connected user holding no pair at all.
 *
 * <p>
 * <b>What is believed.</b> A user matched by principal counts only while their
 * settings still hold a connected account on the same server. The row is
 * forgotten on disconnect through the kernel bridge, and that resolution can
 * fail; a row left behind must not keep naming someone who is no longer
 * connected. The recorded principal of a user who connected <em>another</em>
 * account on the same server is forgotten when they connect it and recorded
 * again by the next discovery after it; a pass already running for that user
 * on another node at that moment may write the previous principal back, and
 * it then stands until that user's next discovery, one pass later.
 *
 * <p>
 * Never allowed to fail a connection, a pass or a listing: recording and
 * forgetting absorb their own failures, and a lookup that fails is the
 * caller's to degrade.
 */
@Service
public class CaldavConnectionIdentityService {

  private static final Log        LOG = ExoLogger.getLogger(CaldavConnectionIdentityService.class);

  @Autowired
  private CaldavConnectionStorage caldavConnectionStorage;

  @Autowired
  private CaldavConnectorStorage  caldavConnectorStorage;

  /**
   * A principal in the one form it is recorded and compared in: the form
   * {@link CalendarCollection} compares a {@code DAV:owner} in, so a stored
   * principal and an owner a listing reports meet on the same spelling.
   *
   * @param principal a principal path as a server answered it, may be null
   * @return the canonical path, or null when there is none to speak of
   */
  public static String canonicalPrincipal(String principal) {
    if (StringUtils.isBlank(principal)) {
      return null;
    }
    return StringUtils.trimToNull(CalendarCollection.principalPathOf(principal));
  }

  /**
   * Records the principal a discovery found for a user's account.
   *
   * <p>
   * A principal the server did not name, or one the column cannot hold
   * faithfully — longer than it, or carrying a character outside the Basic
   * Multilingual Plane, which MySQL's {@code utf8mb3} column refuses or, out
   * of strict mode, truncates — is recorded as unknown: whatever was stored
   * before is removed rather than left to describe an account nobody
   * confirmed, and a principal is never cut down into somebody else's. A second node recording the same
   * user at the same instant meets the unique index; its row is then updated
   * instead, the end state being the same.
   *
   * @param userIdentityId the eXo user whose account was discovered
   * @param serverId the server key the user's pairs use, zero for an account
   *          attached before registrations existed
   * @param principal the {@code current-user-principal} as the discovery
   *          answered it, may be null
   */
  public void recordPrincipal(long userIdentityId, long serverId, String principal) {
    String canonical = canonicalPrincipal(principal);
    try {
      if (!isRecordable(canonical)) {
        LOG.debug("The account of user {} on server {} named no principal that can be recorded; none is kept for it",
                  userIdentityId,
                  serverId);
        caldavConnectionStorage.deletePrincipal(userIdentityId);
        return;
      }
      if (save(userIdentityId, serverId, canonical)) {
        LOG.debug("User {} is connected to server {} as principal {}", userIdentityId, serverId, canonical);
      }
    } catch (RuntimeException e) {
      LOG.warn("The server identity of user {} could not be recorded; the next discovery records it", userIdentityId, e);
    }
  }

  /**
   * Forgets the principal recorded for a user: their account was connected
   * again, or disconnected.
   *
   * <p>
   * Absorbs its own failure, because the two acts calling it must succeed
   * whatever happens here; a row it could not remove is ignored by every
   * reader once the account is no longer connected, and replaced by the next
   * discovery when it is.
   *
   * @param userIdentityId the eXo user
   */
  public void forgetPrincipal(long userIdentityId) {
    try {
      caldavConnectionStorage.deletePrincipal(userIdentityId);
    } catch (RuntimeException e) {
      LOG.warn("The server identity of user {} could not be forgotten; it is ignored while the account is not connected",
               userIdentityId,
               e);
    }
  }

  /**
   * Whether every user holding an active pair on a server has an identity
   * recorded for that server.
   *
   * <p>
   * What makes "exactly one user is connected as this principal" true rather
   * than merely what the table says: until it holds, a second user of the same
   * login may simply not be recorded yet.
   *
   * @param serverId the server key
   * @return true when nobody synchronising with that server is missing
   * @throws RuntimeException when the question itself fails; the caller degrades
   */
  public boolean isEveryActiveUserRecordedOn(long serverId) {
    return caldavConnectionStorage.countActiveUsersWithoutIdentity(serverId) == 0;
  }

  /**
   * The eXo users connected as exactly one principal on one server.
   *
   * @param serverId the server key
   * @param principal the principal in any spelling a server answers
   * @return the users, lowest identity first, each still connected to that
   *         server; empty when nobody is, or the principal names nobody
   * @throws RuntimeException when the lookup itself fails; the caller degrades
   */
  public List<Long> usersConnectedAs(long serverId, String principal) {
    return connectedAs(serverId, principal, null);
  }

  /**
   * The eXo users other than one connected as exactly one principal on one
   * server — the users who connected the same account.
   *
   * @param userIdentityId the user asking, who does not count
   * @param serverId the server key
   * @param principal the principal in any spelling a server answers
   * @return the other users, lowest identity first, each still connected to
   *         that server
   * @throws RuntimeException when the lookup itself fails; the caller degrades
   */
  public List<Long> otherUsersConnectedAs(long userIdentityId, long serverId, String principal) {
    return connectedAs(serverId, principal, userIdentityId);
  }

  /**
   * The users recorded under a principal, the one excluded left out before
   * anything is read for them, and each checked to be connected still.
   *
   * @param serverId the server key
   * @param principal the principal, any spelling
   * @param excluded a user not to count, or null
   * @return the believed users
   */
  private List<Long> connectedAs(long serverId, String principal, Long excluded) {
    String canonical = canonicalPrincipal(principal);
    if (!isRecordable(canonical)) {
      return List.of();
    }
    return caldavConnectionStorage.getUsersConnectedAs(serverId, canonical)
                                  .stream()
                                  .filter(user -> excluded == null || user.longValue() != excluded.longValue())
                                  .filter(user -> isStillConnectedTo(user, serverId))
                                  .toList();
  }

  /**
   * Whether a recorded user's settings still hold a connected account on the
   * server their row names.
   *
   * @param userIdentityId the recorded user
   * @param serverId the server key their row names
   * @return true when the credentials are there and name that server
   */
  private boolean isStillConnectedTo(long userIdentityId, long serverId) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (settings == null || StringUtils.isBlank(settings.getUsername()) || StringUtils.isBlank(settings.getPassword())) {
      return false;
    }
    long connectedTo = settings.getServerId() == null ? 0L : settings.getServerId();
    return connectedTo == serverId;
  }

  /**
   * Saves a principal, turning a concurrent insert of the same user into an
   * update of the row it produced.
   *
   * @param userIdentityId the eXo user
   * @param serverId the server key
   * @param canonical the canonical principal
   * @return whether a row was written
   */
  private boolean save(long userIdentityId, long serverId, String canonical) {
    try {
      return caldavConnectionStorage.savePrincipal(userIdentityId, serverId, canonical);
    } catch (RuntimeException e) {
      if (!CaldavSyncStorage.isDuplicateKey(e)) {
        throw e;
      }
      LOG.debug("The server identity of user {} was recorded by a concurrent discovery; this one is written over it",
                userIdentityId);
      return caldavConnectionStorage.savePrincipal(userIdentityId, serverId, canonical);
    }
  }

  /**
   * Whether a canonical principal can be recorded and looked up.
   *
   * <p>
   * Held to what every supported database stores unchanged: at most the
   * column's length, and characters of the Basic Multilingual Plane only. On
   * MySQL and MariaDB the {@code NVARCHAR} column is {@code utf8mb3} whatever
   * the table's character set, so a supplementary character is refused at
   * insert — a warning on every pass — or, without strict mode, cut off with
   * the rest of the value, which could leave another account's principal
   * behind. Refusing it here makes every database agree.
   *
   * @param canonical the canonical principal, may be null
   * @return true when it is present and every database holds it as it is
   */
  private static boolean isRecordable(String canonical) {
    return canonical != null && canonical.length() <= CaldavConnectionEntity.PRINCIPAL_MAX_LENGTH
        && canonical.codePoints().allMatch(Character::isBmpCodePoint);
  }
}
