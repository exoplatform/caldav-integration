/*
 * Copyright (C) 2023 eXo Platform SAS.
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

import org.apache.commons.lang3.StringUtils;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

public class CaldavConnectorServiceImpl implements CaldavConnectorService {
  private CaldavConnectorStorage caldavConnectorStorage;

  private static final Log       LOG = ExoLogger.getLogger(CaldavConnectorServiceImpl.class);

  private String                 caldavUrl;

  /**
   * The registry of declared CalDAV servers — a Spring bean, while this class
   * is a kernel component, so it is resolved lazily through the bridge rather
   * than injected by the kernel constructor: at kernel wiring time the Spring
   * context of this add-on may not have registered its beans back yet.
   */
  private CaldavServerService    caldavServerService;

  /**
   * The sync engine, resolved lazily for the same reason as the registry
   * above: this class is a kernel component and the engine is a Spring bean.
   */
  private CaldavSyncService      caldavSyncService;

  public CaldavConnectorServiceImpl(CaldavConnectorStorage caldavConnectorStorage) {
    String caldavUrl = System.getProperty("exo.agenda.caldav.connector.url");
    this.caldavConnectorStorage = caldavConnectorStorage;
    this.caldavUrl = caldavUrl;
  }

  @Override
  public void createCaldavSetting(CaldavUserSetting caldavUserSetting, long userIdentityId) throws IllegalAccessException {
    if (StringUtils.isNotBlank(caldavUserSetting.getPassword()) && StringUtils.isNotBlank(caldavUserSetting.getUsername())) {
      caldavConnectorStorage.createCaldavSetting(caldavUserSetting, userIdentityId);
      // Someone who has just entered their credentials is owed their calendars
      // now, not in a quarter of an hour — and a throttle stamped by a previous
      // account's run has nothing to say about this one.
      CaldavSyncService syncService = getCaldavSyncService();
      if (syncService != null) {
        syncService.forgetThrottle(userIdentityId);
      }
    } else {
      throw new IllegalAccessException("username or password not be null");
    }
  }

  /**
   * Retrieves the CalDAV settings of a user, with the base URL of the server
   * the account speaks to resolved in this order: the declared server the
   * account references, else the seed registration, else the legacy
   * {@code exo.agenda.caldav.connector.url} property — today's behaviour, kept
   * for deployments whose registry is empty.
   * <p>
   * Two relay-era guarantees are made here. The <b>password never leaves the
   * platform</b>: it is blanked before the setting is returned, since the
   * only consumer of this method is the settings REST the browser reads, and
   * the browser no longer speaks to CalDAV servers itself — the relay
   * injects the stored credentials server-side. And the <b>serverId is the
   * effective one</b>: when the registry resolves a row (the referenced one,
   * else the seed), its id is set on the DTO even for legacy accounts that
   * stored none, because that id is how the browser addresses the relay.
   *
   * @param userIdentityId User identity getting the caldav user setting
   * @return the setting of that user, its caldavUrl resolved, its password
   *         blanked, never null
   */
  @Override
  public CaldavUserSetting getCaldavSetting(long userIdentityId) {
    CaldavUserSetting caldavUserSetting = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    // "Credentials exist" survives as the presence of the username; the
    // secret itself has no reason to reach the page anymore.
    caldavUserSetting.setPassword(null);
    String resolvedUrl = null;
    CaldavServerService serverRegistry = getCaldavServerService();
    if (serverRegistry != null) {
      CaldavServer resolvedServer = serverRegistry.resolveServer(caldavUserSetting.getServerId());
      if (resolvedServer != null) {
        resolvedUrl = resolvedServer.getServerUrl();
        caldavUserSetting.setServerId(resolvedServer.getId());
      }
    }
    caldavUserSetting.setCaldavUrl(StringUtils.isNotBlank(resolvedUrl) ? resolvedUrl : this.caldavUrl);
    return caldavUserSetting;
  }

  /**
   * Resolves the server registry lazily through the kernel/Spring bridge, and
   * remembers it. A registry that cannot be resolved (the Spring context not
   * up yet, or a test container without it) simply leaves the legacy
   * property-based URL in charge.
   *
   * @return the registry, or null when the bridge cannot provide it
   */
  protected CaldavServerService getCaldavServerService() {
    if (caldavServerService == null) {
      try {
        caldavServerService = ExoContainerContext.getService(CaldavServerService.class);
      } catch (Exception e) {
        LOG.debug("CalDAV server registry not resolvable yet, keeping the property-based URL", e);
      }
    }
    return caldavServerService;
  }

  /**
   * Resolves the sync engine lazily through the kernel/Spring bridge, and
   * remembers it. An engine that cannot be resolved — the Spring context not
   * up yet, or a test container without it — simply leaves the throttle
   * alone, which costs a first sync its promptness and nothing else.
   *
   * @return the engine, or null when the bridge cannot provide it
   */
  protected CaldavSyncService getCaldavSyncService() {
    if (caldavSyncService == null) {
      try {
        caldavSyncService = ExoContainerContext.getService(CaldavSyncService.class);
      } catch (Exception | LinkageError e) {
        // LinkageError deliberately, not only Exception: resolving a bean
        // through the bridge loads a class graph, and a container assembled
        // without part of it fails with NoClassDefFoundError rather than an
        // exception — seen in a unit context here. Forgetting a throttle is a
        // convenience; it must never be what stops someone connecting their
        // account.
        LOG.debug("CalDAV sync engine not resolvable; the throttle is left as it is", e);
      }
    }
    return caldavSyncService;
  }

  /**
   * Hands the engine to tests, which have no container to resolve it from.
   *
   * @param caldavSyncService the engine to use
   */
  protected void setCaldavSyncService(CaldavSyncService caldavSyncService) {
    this.caldavSyncService = caldavSyncService;
  }

  /**
   * Hands the registry to tests, which have no container to resolve it from.
   *
   * @param caldavServerService the registry to use
   */
  protected void setCaldavServerService(CaldavServerService caldavServerService) {
    this.caldavServerService = caldavServerService;
  }

  @Override
  public void deleteCaldavSetting(long userIdentityId) {
    caldavConnectorStorage.deleteCaldavSetting(userIdentityId);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void saveMirrorCalendarHref(String mirrorCalendarHref, long userIdentityId) {
    if (StringUtils.isBlank(mirrorCalendarHref)) {
      throw new IllegalArgumentException("caldav.mirrorCalendarHrefMandatory");
    }
    caldavConnectorStorage.saveMirrorCalendarHref(mirrorCalendarHref, userIdentityId);
  }
}
