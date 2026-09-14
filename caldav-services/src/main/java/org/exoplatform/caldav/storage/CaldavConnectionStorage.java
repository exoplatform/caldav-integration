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

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.caldav.dao.CaldavConnectionDAO;
import org.exoplatform.caldav.entity.CaldavConnectionEntity;

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
   * The principal recorded for a user on one server.
   *
   * @param userIdentityId the eXo user
   * @param serverId the declared server registration, zero for an account
   *          attached before registrations existed
   * @return the canonical principal, or null when none is recorded for that
   *         user on that server
   */
  public String getPrincipal(long userIdentityId, long serverId) {
    return connectionDAO.findByUserIdentityId(userIdentityId)
                        .filter(connection -> connection.getServerId() == serverId)
                        .map(CaldavConnectionEntity::getPrincipal)
                        .orElse(null);
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
   * The users recorded as connected under exactly one principal on one
   * server, lowest identity first, at most {@link #CONNECTED_USERS_READ}.
   *
   * <p>
   * The database answers the candidates and this keeps only the exact
   * matches: MySQL compares the column under {@code utf8mb4_0900_ai_ci},
   * where {@code /dav/pal/josé} equals {@code /dav/pal/JOSE}, and an owner
   * mapped to the wrong person through a collation is worse than an owner not
   * mapped at all.
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
