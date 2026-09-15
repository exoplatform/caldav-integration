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
package org.exoplatform.caldav.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.caldav.dao.CaldavConnectionDAO;
import org.exoplatform.caldav.entity.CaldavConnectionEntity;
import org.exoplatform.caldav.model.CalendarSyncStatus;

/**
 * Reads and writes who each connected eXo user is on their CalDAV server
 * (EXO-90243).
 *
 * <p>
 * No business logic: when a principal is recorded, forgotten or believed is
 * the service's decision. What lives here is the mechanical part — that a
 * recording which changes nothing writes nothing, and that "connected as this
 * principal" means <em>exactly</em> this principal whatever the database's
 * collation thinks equal.
 */
@Component
public class CaldavConnectionStorage {

  /**
   * How many users recorded under one principal are read at most: enough to
   * name every member of a shared team account, and a bound on a lookup that
   * would otherwise list a deployment connected through one technical login.
   */
  static final int            CONNECTED_USERS_READ = 20;

  @Autowired
  private CaldavConnectionDAO connectionDAO;

  /**
   * How many rows of one server are read at most when listing who a calendar
   * on it could be shared with (EXO-90253): a bound on a deployment where a
   * whole company is connected to one server, above which the later users
   * are not offered.
   */
  static final int            CONNECTIONS_ON_SERVER_READ = 500;

  /**
   * The principal recorded for one user on one server.
   *
   * @param userIdentityId the eXo user
   * @param serverId the declared server registration
   * @return the principal, canonical, or null when none is recorded for that
   *         user on that server
   */
  public String getRecordedPrincipal(long userIdentityId, long serverId) {
    return connectionDAO.findByUserIdentityId(userIdentityId)
                        .filter(connection -> connection.getServerId() == serverId)
                        .map(CaldavConnectionEntity::getPrincipal)
                        .filter(StringUtils::isNotBlank)
                        .orElse(null);
  }

  /**
   * The principals recorded on one server, by user, lowest identity first, at
   * most {@link #CONNECTIONS_ON_SERVER_READ}.
   *
   * @param serverId the declared server registration
   * @return user identity to canonical principal, in identity order
   */
  public Map<Long, String> getPrincipalsOn(long serverId) {
    Map<Long, String> principals = new LinkedHashMap<>();
    connectionDAO.findByServer(serverId, PageRequest.of(0, CONNECTIONS_ON_SERVER_READ))
                 .stream()
                 .filter(connection -> StringUtils.isNotBlank(connection.getPrincipal()))
                 .forEach(connection -> principals.put(connection.getUserIdentityId(), connection.getPrincipal()));
    return principals;
  }

  /**
   * Records the principal a user is connected as on one server, replacing
   * whatever was recorded for that user before.
   *
   * <p>
   * A recording that matches the stored row writes nothing: this is asked on
   * every discovery, and a pass that found the account unchanged must cost one
   * indexed read, not a write.
   *
   * @param userIdentityId the eXo user
   * @param serverId the declared server registration
   * @param principal the principal, canonical and not blank
   * @return true when a row was inserted or updated, false when it already
   *         said this
   */
  @Transactional
  public boolean savePrincipal(long userIdentityId, long serverId, String principal) {
    CaldavConnectionEntity connection = connectionDAO.findByUserIdentityId(userIdentityId)
                                                     .orElseGet(CaldavConnectionEntity::new);
    if (connection.getId() != null && connection.getServerId() == serverId && principal.equals(connection.getPrincipal())) {
      return false;
    }
    connection.setUserIdentityId(userIdentityId);
    connection.setServerId(serverId);
    connection.setPrincipal(principal);
    connectionDAO.save(connection);
    return true;
  }

  /**
   * Removes the principal recorded for a user, on whichever server.
   *
   * @param userIdentityId the eXo user
   */
  @Transactional
  public void deletePrincipal(long userIdentityId) {
    connectionDAO.deleteByUserIdentityId(userIdentityId);
  }

  /**
   * How many users hold an active pair on one server without an identity
   * recorded for that server.
   *
   * @param serverId the declared server registration
   * @return the number of such users, zero when every one is recorded
   */
  public long countActiveUsersWithoutIdentity(long serverId) {
    return connectionDAO.countUsersWithPairsButNoIdentity(serverId, CalendarSyncStatus.ACTIVE);
  }

  /**
   * The users recorded as connected under exactly one principal on one
   * server, lowest identity first, at most {@link #CONNECTED_USERS_READ}.
   *
   * <p>
   * The database answers the candidates and this keeps only the exact
   * matches: MySQL and MariaDB give the {@code NVARCHAR} column the national
   * character set, {@code utf8mb3} with {@code utf8mb3_general_ci}, whatever
   * the table's own collation, and compare it case- and accent-insensitively
   * — so {@code /dav/pal/josé} equals {@code /dav/pal/JOSE} there, and an
   * owner mapped to the wrong person through a collation is worse than an
   * owner not mapped at all.
   *
   * <p>
   * The bound applies to the candidates, before that filter: on such a
   * collation, more than {@link #CONNECTED_USERS_READ} lookalike rows with
   * lower identities could push the exact ones off the page. No server hands
   * out a population of principals differing only by case or accent, and that
   * case is accepted rather than paid for with an unbounded read.
   *
   * @param serverId the declared server registration
   * @param principal the principal, canonical
   * @return the users' identities, empty for a blank principal
   */
  public List<Long> getUsersConnectedAs(long serverId, String principal) {
    if (StringUtils.isBlank(principal)) {
      return List.of();
    }
    return connectionDAO.findByServerAndPrincipal(serverId, principal, PageRequest.of(0, CONNECTED_USERS_READ))
                        .stream()
                        .filter(connection -> principal.equals(connection.getPrincipal()))
                        .map(CaldavConnectionEntity::getUserIdentityId)
                        .toList();
  }
}
