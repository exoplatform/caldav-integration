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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.RemoteProvider;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.storage.CaldavServerStorage;
import org.exoplatform.caldav.utils.CaldavConnectorUtils;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.RootContainer.PortalContainerPostCreateTask;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.Identity;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.ServletContext;
import lombok.SneakyThrows;

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

  /**
   * URL the Stalwart seed row falls back to when the legacy property is not
   * set on the deployment.
   */
  public static final String       DEFAULT_STALWART_URL          = "http://localhost:8888/dav/cal/{username}/";

  /**
   * URL of the Bluemind seed row: where that BlueMind actually serves DAV
   * (its /dav/ answers 401 Basic realm="bm.basic.auth.v2"; the bare host only
   * redirects). Note that BlueMind sends no CORS headers, so connecting from
   * the browser needs the portal to front it on its own origin.
   */
  public static final String       DEFAULT_BLUEMIND_URL          = "https://webmail.demo3.livecollab.fr/dav/";

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

  @Autowired
  private SettingService           settingService;

  @Autowired
  private FileService              fileService;

  @Autowired
  private PortalContainer         portalContainer;

  /**
   * Defers the seeding of the registry to the portal container's post-create
   * phase — the same deferral agenda's own provider plugin uses — because the
   * Bluemind default needs its agenda remote provider written, and agenda's
   * kernel services are only safely callable once the portal container is up.
   */
  @PostConstruct
  public void start() {
    PortalContainer.addInitTask(portalContainer.getPortalContext(), new PortalContainerPostCreateTask() {
      /**
       * Runs the seeding inside a container request lifecycle, logging
       * instead of failing the boot.
       *
       * @param context servlet context the task runs for
       * @param container the created portal container
       */
      public void execute(ServletContext context, PortalContainer container) {
        ExoContainerContext.setCurrentContainer(container);
        RequestLifeCycle.begin(container);
        try {
          seedDefaultServers();
        } catch (Exception e) {
          LOG.warn("Error seeding the default CalDAV servers", e);
        } finally {
          RequestLifeCycle.end();
        }
      }
    });
  }

  /**
   * Seeds the two default servers into an EMPTY registry — never over an
   * administrator's rows:
   * <ul>
   * <li><b>Stalwart</b>, under the fixed legacy provider name so accounts
   * connected before the registry existed keep resolving; its URL comes from
   * the legacy property when set, else the literal default, and its
   * activation from the legacy enabled property (historically enabled).</li>
   * <li><b>Bluemind</b>, a normally-named row whose agenda remote provider is
   * upserted here, since no kernel plugin declares it.</li>
   * </ul>
   */
  protected void seedDefaultServers() {
    if (caldavServerStorage.countServers() > 0) {
      return;
    }
    String stalwartUrl = System.getProperty(CALDAV_SERVER_URL_PROPERTY);
    if (StringUtils.isBlank(stalwartUrl)) {
      stalwartUrl = DEFAULT_STALWART_URL;
    }
    boolean stalwartActive = !StringUtils.equalsIgnoreCase(System.getProperty(CALDAV_ENABLED_PROPERTY), "false");
    caldavServerStorage.createSeedServer(new CaldavServer(0, null, "Stalwart", null, stalwartUrl, stalwartActive, null, null,
                                                          null, null),
                                         CALDAV_PROVIDER_NAME);
    // The kernel plugin only CREATES the provider when missing — an existing
    // one keeps whatever enabled state it holds (an admin may have disabled
    // it, or a deleted seed left it off). The row being seeded is the truth
    // now, so its activation is pushed onto the provider explicitly; on a
    // fresh install both writes carry the same property-driven value.
    saveAgendaRemoteProvider(new CaldavServer(0, CALDAV_PROVIDER_NAME, "Stalwart", null, stalwartUrl, stalwartActive, null,
                                              null, null, null));
    LOG.info("Seeded the Stalwart CalDAV server ({})", stalwartUrl);
    CaldavServer bluemind = caldavServerStorage.createServer(new CaldavServer(0, null, "Bluemind", null, DEFAULT_BLUEMIND_URL,
                                                                              true, null, null, null, null),
                                                             CALDAV_PROVIDER_NAME);
    saveAgendaRemoteProvider(bluemind);
    LOG.info("Seeded the Bluemind CalDAV server ({})", DEFAULT_BLUEMIND_URL);
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
   * Deletes a declared server. Refused — with the number of blocked accounts
   * — while ANY user's connection references the row: deleting under them
   * would silently re-point their stored credentials at whatever the
   * resolution falls back to, which is a different server. The administrator
   * deactivates instead, users disconnect, then the delete goes through.
   * Legacy accounts (no stored reference) never block a delete: they resolve
   * through the seed then the property, exactly as before the registry.
   * Before the row goes, its agenda remote provider is disabled — agenda has
   * no provider-delete API, so disabling is what actually removes the
   * connector from every user's list; the orphaned provider row stays,
   * disabled and invisible.
   *
   * @param serverId technical identifier of the registration
   * @param username user deleting the server
   * @throws IllegalAccessException when the user is not a platform administrator
   * @throws ObjectNotFoundException when no registration carries that id
   * @throws IllegalStateException carrying the message code
   *           {@code caldav.server.referenced:<count>} when connected
   *           accounts still reference the row (the REST layer answers 409)
   */
  public void deleteServer(long serverId, String username) throws IllegalAccessException, ObjectNotFoundException {
    checkCanEdit(username);
    CaldavServer server = getServerById(serverId);
    long references = countServerReferences(serverId);
    if (references > 0) {
      throw new IllegalStateException("caldav.server.referenced:" + references);
    }
    saveAgendaRemoteProvider(new CaldavServer(server.getId(), server.getProviderName(), server.getName(),
                                              server.getDescription(), server.getServerUrl(), false, null, null, null, null));
    caldavServerStorage.deleteServer(serverId);
  }

  /**
   * Counts the connected accounts referencing a registration: the user
   * contexts holding a CaldavServerId setting whose value is this row's id.
   * The enumeration is by setting NAME (the settings API offers no
   * by-value filter), each candidate's value then compared — the same walk
   * email-connector does to find a connector's users.
   *
   * @param serverId technical identifier of the registration
   * @return number of accounts whose stored connection references the row
   */
  public long countServerReferences(long serverId) {
    List<Context> contexts = settingService.getContextsByTypeAndScopeAndSettingName(Context.USER.getName(),
                                                                                    Scope.APPLICATION.getName(),
                                                                                    CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE.getId(),
                                                                                    CaldavConnectorUtils.CALDAV_SERVER_ID_KEY,
                                                                                    0,
                                                                                    Integer.MAX_VALUE);
    return contexts.stream().filter(context -> {
      SettingValue<?> value = settingService.get(context,
                                                 CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                                 CaldavConnectorUtils.CALDAV_SERVER_ID_KEY);
      return value != null && value.getValue() != null && String.valueOf(serverId).equals(value.getValue().toString());
    }).count();
  }

  /**
   * The stored image of a registration, as a stream the REST layer serves —
   * email-connector's illustration mechanics.
   *
   * @param serverId technical identifier of the registration
   * @return the image stream, or null when the row holds no image
   * @throws ObjectNotFoundException when no registration carries that id
   */
  @SneakyThrows
  public InputStream getServerImageInputStream(long serverId) throws ObjectNotFoundException {
    CaldavServer server = getServerById(serverId);
    if (server.getImageFileId() == null || server.getImageFileId() == 0) {
      return null;
    }
    FileItem fileItem = fileService.getFile(server.getImageFileId());
    if (fileItem == null || fileItem.getAsByte() == null) {
      return null;
    }
    return new ByteArrayInputStream(fileItem.getAsByte());
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
