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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.caldav.dao.CaldavServerDAO;
import org.exoplatform.caldav.entity.CaldavServerEntity;
import org.exoplatform.caldav.model.CaldavServer;

/**
 * Maps CalDAV server registrations between their JPA entity and the
 * credential-free {@link CaldavServer} DTO. No business logic lives here: the
 * provider-name derivation is mechanical (the seed name is given by the
 * service, every other row derives its name from its own id) and the choices
 * — who may write, when to seed — belong to the service.
 */
@Component
public class CaldavServerStorage {

  @Autowired
  private CaldavServerDAO caldavServerDAO;

  /**
   * Reads every registration, seed first (it holds the lowest id by
   * construction).
   *
   * @return all registrations, ordered by id
   */
  public List<CaldavServer> getServers() {
    return caldavServerDAO.findAll(Sort.by("id")).stream().map(this::fromEntity).toList();
  }

  /**
   * Reads one registration by its technical identifier.
   *
   * @param id technical identifier of the registration
   * @return the registration, or null when the row does not exist
   */
  public CaldavServer getServerById(long id) {
    return caldavServerDAO.findById(id).map(this::fromEntity).orElse(null);
  }

  /**
   * Reads one registration by the agenda remote provider it is bridged to.
   *
   * @param providerName name of the agenda remote provider
   * @return the registration, or null when none carries that name
   */
  public CaldavServer getServerByProviderName(String providerName) {
    return caldavServerDAO.findByProviderName(providerName).map(this::fromEntity).orElse(null);
  }

  /**
   * Counts the registrations — what the seeding decision reads: the seed row
   * is only ever written into an empty registry, so an administrator's edits
   * are never overwritten by a restart.
   *
   * @return number of registration rows
   */
  public long countServers() {
    return caldavServerDAO.count();
  }

  /**
   * Creates a registration whose provider name derives from its own row id
   * ({@code <providerNamePrefix>.<id>}). Two steps in one transaction —
   * insert, then name the row after the id the insert produced — because the
   * column is NOT NULL and the id does not exist before the insert. The
   * transaction keeps the intermediate placeholder name unobservable.
   *
   * @param server registration to create (provider name ignored)
   * @param providerNamePrefix prefix the provider name is derived from
   * @return the created registration, carrying its id and provider name
   */
  @Transactional
  public CaldavServer createServer(CaldavServer server, String providerNamePrefix) {
    CaldavServerEntity entity = toEntity(server);
    entity.setId(null);
    entity.setProviderName(providerNamePrefix + ".pending." + System.nanoTime());
    entity = caldavServerDAO.save(entity);
    entity.setProviderName(providerNamePrefix + "." + entity.getId());
    entity = caldavServerDAO.save(entity);
    return fromEntity(entity);
  }

  /**
   * Creates the seed registration under the fixed legacy provider name — the
   * one row whose provider the kernel plugin already registers, so the
   * accounts connected before the registry existed keep resolving through it.
   *
   * @param server registration to create
   * @param providerName fixed provider name of the seed
   * @return the created registration
   */
  @Transactional
  public CaldavServer createSeedServer(CaldavServer server, String providerName) {
    CaldavServerEntity entity = toEntity(server);
    entity.setId(null);
    entity.setProviderName(providerName);
    return fromEntity(caldavServerDAO.save(entity));
  }

  /**
   * Updates the user-editable fields of a registration: name, description,
   * URL and activation. The provider name never changes — it is the join key
   * user settings and agenda rows hang from.
   *
   * @param server registration carrying the id to update and the new values
   * @return the updated registration, or null when the row does not exist
   */
  @Transactional
  public CaldavServer updateServer(CaldavServer server) {
    return caldavServerDAO.findById(server.getId()).map(entity -> {
      entity.setName(server.getName());
      entity.setDescription(server.getDescription());
      entity.setServerUrl(server.getServerUrl());
      entity.setActive(server.isActive());
      return fromEntity(caldavServerDAO.save(entity));
    }).orElse(null);
  }

  /**
   * Maps an entity to the credential-free DTO.
   *
   * @param entity row to map
   * @return the DTO
   */
  private CaldavServer fromEntity(CaldavServerEntity entity) {
    return new CaldavServer(entity.getId() == null ? 0 : entity.getId(),
                            entity.getProviderName(),
                            entity.getName(),
                            entity.getDescription(),
                            entity.getServerUrl(),
                            entity.isActive());
  }

  /**
   * Maps a DTO to an entity, provider name excluded — it is always decided by
   * the storage method doing the write.
   *
   * @param server DTO to map
   * @return the entity
   */
  private CaldavServerEntity toEntity(CaldavServer server) {
    CaldavServerEntity entity = new CaldavServerEntity();
    entity.setId(server.getId() <= 0 ? null : server.getId());
    entity.setName(server.getName());
    entity.setDescription(server.getDescription());
    entity.setServerUrl(server.getServerUrl());
    entity.setActive(server.isActive());
    return entity;
  }
}
