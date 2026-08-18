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
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.RemoteProvider;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.storage.CaldavServerStorage;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.Identity;

import jakarta.annotation.PostConstruct;

/**
 * The registry of CalDAV servers a deployment offers its users: what an
 * administrator declares in the third section of the agenda administration
 * page. Holds all the business logic — who may write (platform
 * administrators), the provider naming that bridges each registration to an
 * agenda remote provider with zero agenda backend change, the seeding that
 * migrates the legacy configuration property, and the URL resolution the
 * per-user connector settings read.
 */
@Service
public class CaldavServerService {

  /**
   * Provider name of the seed registration — the name the kernel plugin has
   * always registered on the agenda side — and the prefix every derived
   * provider name starts with.
   */
  public static final String       CALDAV_PROVIDER_NAME          = "agenda.caldavCalendar";

  /**
   * Legacy configuration property holding the single CalDAV server URL.
   * Becomes the seed row, then stays forever as a deprecated fallback for
   * deployments whose registry is empty. Never dropped.
   */
  public static final String       CALDAV_SERVER_URL_PROPERTY    = "exo.agenda.caldav.connector.url";

  /**
   * Legacy configuration property telling whether the single CalDAV connector
   * is enabled; read once, to give the seed row its initial activation.
   */
  public static final String       CALDAV_ENABLED_PROPERTY       = "exo.agenda.caldav.connector.enabled";

  private static final String      SERVER_MANDATORY_MESSAGE      = "caldav.server.mandatory";

  private static final String      SERVER_NAME_MANDATORY_MESSAGE = "caldav.server.nameMandatory";

  private static final String      SERVER_URL_MANDATORY_MESSAGE  = "caldav.server.urlMandatory";

  private static final Log         LOG                           = ExoLogger.getLogger(CaldavServerService.class);

  @Autowired
  private CaldavServerStorage      caldavServerStorage;

  @Autowired
  private UserACL                  userAcl;

  @Autowired
  private AgendaRemoteEventService agendaRemoteEventService;

  /**
   * Seeds the registry from the legacy configuration property, asynchronously
   * so a slow or failing seed never blocks the WAR boot. Only an empty
   * registry is ever seeded: once a row exists — seeded or declared — an
   * administrator's edits are the truth and a restart must not overwrite
   * them. The seed row's agenda provider is not written here: the kernel
   * plugin registers it as it always has.
   *
   * @return nothing
   */
  @PostConstruct
  public void start() {
    CompletableFuture.runAsync(this::seedFromLegacyProperty);
  }

  /**
   * The seeding itself: when the registry is empty and the legacy property
   * holds a URL, that URL becomes the seed row, active when the legacy
   * enabled property says so (its historical default being enabled).
   *
   * @return nothing
   */
  protected void seedFromLegacyProperty() {
    try {
      String legacyUrl = System.getProperty(CALDAV_SERVER_URL_PROPERTY);
      if (StringUtils.isBlank(legacyUrl) || caldavServerStorage.countServers() > 0) {
        return;
      }
      boolean active = !StringUtils.equalsIgnoreCase(System.getProperty(CALDAV_ENABLED_PROPERTY), "false");
      CaldavServer seed = new CaldavServer(0, null, "CalDAV", null, legacyUrl, active);
      caldavServerStorage.createSeedServer(seed, CALDAV_PROVIDER_NAME);
      LOG.info("Seeded the CalDAV server registry from the legacy property {} = {}", CALDAV_SERVER_URL_PROPERTY, legacyUrl);
    } catch (Exception e) {
      LOG.warn("Error seeding the CalDAV server registry from the legacy property", e);
    }
  }

  /**
   * The registrations, seed first. Callable by any authenticated user: the
   * rows hold nothing secret — no credential ever enters this table — and the
   * browser needs the names and URLs to offer the connectors.
   *
   * @return every registration
   */
  public List<CaldavServer> getServers() {
    return caldavServerStorage.getServers();
  }

  /**
   * One registration by its technical identifier.
   *
   * @param serverId technical identifier of the registration
   * @return the registration
   * @throws ObjectNotFoundException when no registration carries that id
   */
  public CaldavServer getServerById(long serverId) throws ObjectNotFoundException {
    CaldavServer server = caldavServerStorage.getServerById(serverId);
    if (server == null) {
      throw new ObjectNotFoundException("CalDAV server with id " + serverId + " doesn't exist");
    }
    return server;
  }

  /**
   * Declares a new CalDAV server. The provider name is derived from the row
   * id ({@code agenda.caldavCalendar.<id>}), then the matching agenda remote
   * provider is upserted under that name — which is all agenda needs: its
   * existing enabled-check and connected-provider binding key on the name.
   *
   * @param server registration to create (id and provider name ignored)
   * @param username user declaring the server
   * @return the created registration
   * @throws IllegalAccessException when the user is not a platform administrator
   * @throws IllegalArgumentException when the registration, its name or its
   *           URL is missing
   */
  public CaldavServer createServer(CaldavServer server, String username) throws IllegalAccessException {
    checkCanEdit(username);
    validate(server);
    CaldavServer createdServer = caldavServerStorage.createServer(server, CALDAV_PROVIDER_NAME);
    saveAgendaRemoteProvider(createdServer);
    return createdServer;
  }

  /**
   * Updates a declared server: name, description, URL and activation. The
   * provider name never changes, and the activation is propagated to the
   * agenda remote provider — the switch users' connector lists actually read.
   *
   * @param server registration carrying the id to update and the new values
   * @param username user updating the server
   * @return the updated registration
   * @throws IllegalAccessException when the user is not a platform administrator
   * @throws IllegalArgumentException when the registration, its name or its
   *           URL is missing
   * @throws ObjectNotFoundException when no registration carries that id
   */
  public CaldavServer updateServer(CaldavServer server, String username) throws IllegalAccessException,
                                                                         ObjectNotFoundException {
    checkCanEdit(username);
    validate(server);
    CaldavServer updatedServer = caldavServerStorage.updateServer(server);
    if (updatedServer == null) {
      throw new ObjectNotFoundException("CalDAV server with id " + server.getId() + " doesn't exist");
    }
    saveAgendaRemoteProvider(updatedServer);
    return updatedServer;
  }

  /**
   * Activates or deactivates a declared server, propagating the switch to the
   * agenda remote provider so the connector appears in — or leaves — every
   * user's connectors list.
   *
   * @param serverId technical identifier of the registration
   * @param active whether users may connect to this server
   * @param username user flipping the switch
   * @return the updated registration
   * @throws IllegalAccessException when the user is not a platform administrator
   * @throws ObjectNotFoundException when no registration carries that id
   */
  public CaldavServer setServerActive(long serverId, boolean active, String username) throws IllegalAccessException,
                                                                                      ObjectNotFoundException {
    checkCanEdit(username);
    CaldavServer server = getServerById(serverId);
    server.setActive(active);
    CaldavServer updatedServer = caldavServerStorage.updateServer(server);
    saveAgendaRemoteProvider(updatedServer);
    return updatedServer;
  }

  /**
   * Resolves the CalDAV base URL a user's account reads and pushes through:
   * the row the account references when it references one and that row still
   * exists, else the seed row, else null — the caller then falls back to the
   * legacy configuration property, which is today's behaviour for
   * deployments that never touched the registry.
   *
   * @param serverId registration the user's account references, or null
   * @return the resolved base URL, or null when the registry answers nothing
   */
  public String resolveServerUrl(Long serverId) {
    if (serverId != null) {
      CaldavServer server = caldavServerStorage.getServerById(serverId);
      if (server != null) {
        return server.getServerUrl();
      }
    }
    CaldavServer seed = caldavServerStorage.getServerByProviderName(CALDAV_PROVIDER_NAME);
    return seed == null ? null : seed.getServerUrl();
  }

  /**
   * Whether a user may edit the registry: platform administrators only. This
   * is the same gate email-connector's admin registry uses
   * (EmailConnectorService.canEdit), minus its blank-username-passes quirk —
   * an anonymous caller is refused here.
   *
   * @param username user asking to write
   * @return true when the user is a platform administrator
   */
  public boolean canEdit(String username) {
    if (StringUtils.isBlank(username)) {
      return false;
    }
    Identity identity = userAcl.getUserIdentity(username);
    return identity != null && userAcl.isAdministrator(identity);
  }

  /**
   * Refuses the write when the user is not a platform administrator.
   *
   * @param username user asking to write
   * @throws IllegalAccessException when the user is not a platform administrator
   */
  private void checkCanEdit(String username) throws IllegalAccessException {
    if (!canEdit(username)) {
      throw new IllegalAccessException("User " + username + " is not allowed to manage CalDAV servers");
    }
  }

  /**
   * Refuses a registration whose display name or URL is missing, with the
   * message codes the REST layer answers 400 with.
   *
   * @param server registration to validate
   */
  private void validate(CaldavServer server) {
    if (server == null) {
      throw new IllegalArgumentException(SERVER_MANDATORY_MESSAGE);
    }
    if (StringUtils.isBlank(server.getName())) {
      throw new IllegalArgumentException(SERVER_NAME_MANDATORY_MESSAGE);
    }
    if (StringUtils.isBlank(server.getServerUrl())) {
      throw new IllegalArgumentException(SERVER_URL_MANDATORY_MESSAGE);
    }
  }

  /**
   * Upserts the agenda remote provider a registration is bridged to — by
   * name, which is how agenda's storage upserts — carrying the activation.
   * CalDAV providers are not OAuth ones and hold no API keys, so the upsert
   * overwrites nothing an administrator could have typed on the agenda side.
   *
   * @param server registration whose provider to upsert
   */
  private void saveAgendaRemoteProvider(CaldavServer server) {
    RemoteProvider remoteProvider = new RemoteProvider(0, server.getProviderName(), null, null, server.isActive(), false);
    agendaRemoteEventService.saveRemoteProvider(remoteProvider);
  }
}
