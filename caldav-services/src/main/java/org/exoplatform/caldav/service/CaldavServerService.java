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
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.RemoteProvider;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.MirrorTargetKind;
import org.exoplatform.caldav.storage.CaldavServerStorage;
import org.exoplatform.caldav.utils.CaldavConnectorUtils;
import org.exoplatform.caldav.utils.CopySettingsFingerprint;
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

  /** The name the Stalwart seed is declared under. */
  public static final String       STALWART_SERVER_NAME          = "Stalwart";

  /** The name the Bluemind seed is declared under. */
  public static final String       BLUEMIND_SERVER_NAME          = "Bluemind";

  /**
   * Address the Stalwart seed row falls back to when the deployment named none
   * through {@link #CALDAV_SERVER_URL_PROPERTY}.
   *
   * <p>
   * <b>It is a placeholder, and it is one on purpose (EXO-89794).</b> This
   * constant used to read {@code http://localhost:8888/dav/cal/{username}/} —
   * the local development rig — so a fresh install shipped an <i>active</i>
   * registration pointing at its own loopback interface, through which a
   * connected user could drive the relay's verbs at a row nobody had typed.
   * A product default must not name a host that exists only on a developer's
   * machine, and it must certainly not arrive switched on.
   *
   * <p>
   * The replacement is an RFC 2606 {@code .invalid} name, which is guaranteed
   * never to resolve: it cannot be a live target under any deployment's
   * resolver, it reads unmistakably as "replace me", and it fails the address
   * check — which is what makes the row arrive inactive, see
   * {@link #seedDefaultServers()}. The rig keeps working the documented way,
   * by setting {@link #CALDAV_SERVER_URL_PROPERTY} (plus the opt-ins the
   * address check reads); it no longer works by being the shipped default.
   */
  public static final String       DEFAULT_STALWART_URL          = "https://stalwart.example.invalid/dav/cal/{username}/";

  /**
   * Address the Bluemind seed row is declared with: a placeholder an
   * administrator is expected to replace with the DAV endpoint of their own
   * BlueMind, whose shape it mirrors (BlueMind serves DAV under {@code /dav/}
   * and answers there with 401 Basic realm="bm.basic.auth.v2"; the bare host
   * only redirects).
   *
   * <p>
   * An RFC 2606 {@code .invalid} name for the same reason as
   * {@link #DEFAULT_STALWART_URL}: it can never resolve, so the row can never
   * be a live target, and it fails the address check — so the row is seeded
   * inactive rather than offered to users as a connector that goes nowhere.
   *
   * <p>
   * Note also that BlueMind sends no CORS headers, so connecting from the
   * browser needs the portal to front it on its own origin.
   */
  public static final String       DEFAULT_BLUEMIND_URL          = "https://caldav.example.invalid/dav/";

  private static final String      SERVER_MANDATORY_MESSAGE      = "caldav.server.mandatory";

  private static final String      SERVER_NAME_MANDATORY_MESSAGE = "caldav.server.nameMandatory";

  private static final String      SERVER_URL_MANDATORY_MESSAGE  = "caldav.server.urlMandatory";

  private static final Log         LOG                           = ExoLogger.getLogger(CaldavServerService.class);

  @Autowired
  private CaldavServerStorage      caldavServerStorage;

  @Autowired
  private CaldavServerQuirkService caldavServerQuirkService;

  @Autowired
  private CaldavServerUrlValidator caldavServerUrlValidator;

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
   *
   * <p>
   * <b>A seeded row is switched ON only when its address passes the same check
   * an administrator's would (EXO-89794).</b> Seeding used to be exempt from
   * the address check of EXO-89774 altogether, which left the loophole that
   * check was written to close: a fresh install carried an <i>active</i>
   * registration at {@code http://localhost:8888/dav/cal/{username}/}, so a
   * connected user could drive the relay's allowed verbs at loopback through a
   * row nobody had typed. Neither the check nor the seeds are the thing to
   * drop; what had to go is the row arriving <i>switched on</i> without ever
   * meeting the check.
   *
   * <p>
   * So the seeds are still written — they are the pre-filled form an
   * administrator edits, and skipping them would leave a fresh install with an
   * empty administration screen and no hint of what belongs in it — but their
   * activation is the address check's answer, not a constant:
   * <ul>
   * <li>An address the deployment actually named and vouched for — through
   * {@link #CALDAV_SERVER_URL_PROPERTY}, plus whichever of the four
   * {@code exo.agenda.caldav.server.*} opt-ins that address needs — passes,
   * and the row is seeded active exactly as before. This is how the local
   * development rig keeps working: through its documented properties, not
   * through a default that walks past them.</li>
   * <li>Anything else — both shipped placeholders included — is seeded
   * <b>inactive</b>, and said out loud in the log (see
   * {@link #isDeclarableSeedAddress(String, String)}). Inactive is the honest
   * state for an address nobody has vouched for: no user is offered the
   * connector, nothing can be driven at it, and switching it on later is a
   * write that {@link #setServerActive(long, boolean, String)} already puts
   * through the full check. The administrator edits the row, which is checked
   * too, and turns it on — the check is never bypassed, only deferred to the
   * person who knows the address.</li>
   * </ul>
   *
   * <p>
   * This governs a <b>fresh</b> registry and nothing else. The guard below is
   * the whole upgrade story: a deployment that already holds rows returns
   * before any of this runs, so an install that has been serving its users for
   * months does not find its servers deactivated — or judged at all — by
   * taking this version.
   */
  protected void seedDefaultServers() {
    if (caldavServerStorage.countServers() > 0) {
      return;
    }
    String stalwartUrl = System.getProperty(CALDAV_SERVER_URL_PROPERTY);
    if (StringUtils.isBlank(stalwartUrl)) {
      stalwartUrl = DEFAULT_STALWART_URL;
    }
    // The address check runs FIRST, and unconditionally: a deployment holding
    // an unvalidatable registration has to say so at boot whatever the legacy
    // enabled property says. Ordering the operands the other way round would
    // let `enabled=false` short-circuit the warning away, and the row would be
    // off for a reason nobody could read.
    boolean stalwartActive = isDeclarableSeedAddress(STALWART_SERVER_NAME, stalwartUrl)
        && !StringUtils.equalsIgnoreCase(System.getProperty(CALDAV_ENABLED_PROPERTY), "false");
    caldavServerStorage.createSeedServer(new CaldavServer(0, null, STALWART_SERVER_NAME, null, stalwartUrl, stalwartActive, null, null,
                                                          null, null, true, null, null, null, null, null,
                                                          MirrorTargetKind.DEDICATED_CALENDAR),
                                         CALDAV_PROVIDER_NAME);
    // The kernel plugin only CREATES the provider when missing — an existing
    // one keeps whatever enabled state it holds (an admin may have disabled
    // it, or a deleted seed left it off). The row being seeded is the truth
    // now, so its activation is pushed onto the provider explicitly; on a
    // fresh install both writes carry the same property-driven value.
    saveAgendaRemoteProvider(new CaldavServer(0, CALDAV_PROVIDER_NAME, STALWART_SERVER_NAME, null, stalwartUrl, stalwartActive, null,
                                              null, null, null, true, null, null, null, null, null,
                                              MirrorTargetKind.DEDICATED_CALENDAR));
    LOG.info("Seeded the Stalwart CalDAV server ({}), active: {}", stalwartUrl, stalwartActive);
    boolean bluemindActive = isDeclarableSeedAddress(BLUEMIND_SERVER_NAME, DEFAULT_BLUEMIND_URL);
    CaldavServer bluemind = caldavServerStorage.createServer(new CaldavServer(0, null, BLUEMIND_SERVER_NAME, null, DEFAULT_BLUEMIND_URL,
                                                                              bluemindActive, null, null, null, null, true, null,
                                                                              null, null, null, null,
                                                                              MirrorTargetKind.DEDICATED_CALENDAR),
                                                             CALDAV_PROVIDER_NAME);
    saveAgendaRemoteProvider(bluemind);
    LOG.info("Seeded the Bluemind CalDAV server ({}), active: {}", DEFAULT_BLUEMIND_URL, bluemindActive);
  }

  /**
   * Whether a row about to be seeded may arrive switched on: it may exactly
   * when its address passes the check an administrator's would (EXO-89774) —
   * and when it does not, says so at boot, naming the address, the reason and
   * the properties that would settle it.
   *
   * <p>
   * One method rather than two deliberately. The activation and the warning
   * are the same judgement, and when they were separate — a gate that did not
   * exist, and a warning that only described the gap — the seed could ship
   * active while the log said the address would be refused. Reading the answer
   * once, here, is what keeps the two from ever disagreeing again.
   *
   * <p>
   * The warning survives the gate rather than being replaced by it, and that
   * matters: a deployment now holds a registration it cannot use, and the
   * administrator has to be able to find out why from the boot log rather than
   * from a connector that silently never appears. The check is not the
   * administration screen's to explain — the row is inactive before anyone
   * opens it.
   *
   * @param name display name of the row being seeded, for the log line
   * @param url address the row is being seeded with
   * @return true when the address passes and the row may be seeded active
   */
  private boolean isDeclarableSeedAddress(String name, String url) {
    try {
      caldavServerUrlValidator.validate(url);
      return true;
    } catch (IllegalArgumentException e) {
      LOG.warn("The seeded CalDAV server {} carries the address {}, which the platform must not be made to connect to ({}), "
          + "so the row is seeded INACTIVE: no user is offered it and nothing is driven at it. Replace the address from the "
          + "administration screen and switch the row on, or — if that address is the one this deployment means — opt in "
          + "through exo.agenda.caldav.server.allowedSchemes / allowedPorts / allowedHosts / allowPrivateAddresses. "
          + "Editing and activating the row both go through the same check.", name, url, e.getMessage());
      return false;
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
    return caldavServerStorage.getServers().stream().map(caldavServerQuirkService::decorate).toList();
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
    return caldavServerQuirkService.decorate(server);
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
    return caldavServerQuirkService.decorate(createdServer);
  }

  /**
   * Updates a declared server: name, description, URL, activation, whether the
   * copies pushed to it carry answer links, and the two lists of behaviours
   * this server is excused for. The provider name never
   * changes, and the activation is propagated to the agenda remote provider —
   * the switch users' connector lists actually read.
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
    // The address is judged only when it CHANGES. Re-judging an unchanged one
    // buys no safety and costs an administrator their settings: the row is
    // already declared and already dialled by every sweep, so refusing the save
    // does not stop a single request - it only blocks renaming a server, or
    // changing its icon, on a deployment whose CalDAV server has always been
    // internal. That is the ordinary case for an on-premises install, and those
    // are the administrators who changed nothing. A hostile address still
    // cannot arrive: it cannot be introduced without editing the field, and
    // editing the field is exactly what is checked. Activation is checked too
    // (see setServerActive), so a row cannot be parked and switched on later.
    validateWithoutAddress(server);
    CaldavServer stored = server.getId() > 0 ? caldavServerStorage.getServerById(server.getId()) : null;
    if (stored == null || !StringUtils.equals(stored.getServerUrl(), server.getServerUrl())) {
      caldavServerUrlValidator.validate(server.getServerUrl());
    }
    stampCopySettings(server);
    CaldavServer updatedServer = caldavServerStorage.updateServer(server);
    if (updatedServer == null) {
      throw new ObjectNotFoundException("CalDAV server with id " + server.getId() + " doesn't exist");
    }
    saveAgendaRemoteProvider(updatedServer);
    return caldavServerQuirkService.decorate(updatedServer);
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
   * @throws IllegalArgumentException carrying a message code when the row is
   *           being ACTIVATED and its stored address is one the platform must
   *           not be made to connect to
   */
  public CaldavServer setServerActive(long serverId, boolean active, String username) throws IllegalAccessException,
                                                                                      ObjectNotFoundException {
    checkCanEdit(username);
    CaldavServer server = getServerById(serverId);
    if (active) {
      // Activation is a write like any other, and the row being activated may
      // predate the address check — a registration stored before EXO-89774, or
      // a seeded default. Checking here is what keeps those from being switched
      // back on unexamined; deactivating is never checked, because taking a bad
      // address out of service must always be possible.
      caldavServerUrlValidator.validate(server.getServerUrl());
    }
    server.setActive(active);
    // The switch changes nothing a copy carries, so the stamp must come out of
    // this write exactly as it went in. Recomputing it rather than trusting the
    // row read above is the same single path every other write takes.
    stampCopySettings(server);
    CaldavServer updatedServer = caldavServerStorage.updateServer(server);
    // The row can go between the read above and this write — another
    // administrator deleting it, most plausibly. Storage answers that with
    // null, and passing it on threw a NullPointerException out of an
    // endpoint that already declares the honest answer: the registration is
    // not there. Same guard as updateServer, which had it and this did not.
    if (updatedServer == null) {
      throw new ObjectNotFoundException("CalDAV server with id " + serverId + " doesn't exist");
    }
    saveAgendaRemoteProvider(updatedServer);
    return caldavServerQuirkService.decorate(updatedServer);
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
                                              server.getDescription(), server.getServerUrl(), false, null, null, null, null,
                                              server.isAnswerLinksInCopy(), null, null, null, null,
                                              server.getCopySettingsUpdated(), server.getMirrorTarget()));
    caldavServerStorage.deleteServer(serverId);
    caldavServerQuirkService.forget(serverId);
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
   * Resolves the registration a user's account reads and pushes through: the
   * row the account references when it references one and that row still
   * exists, else the seed row, else null. This single resolution rule is
   * shared by the URL resolution below and by the relay's authorization
   * check — the relay refuses any target that is not the row this method
   * answers for the user.
   *
   * @param serverId registration the user's account references, or null
   * @return the resolved registration, or null when the registry answers
   *         nothing
   */
  public CaldavServer resolveServer(Long serverId) {
    if (serverId != null) {
      CaldavServer server = caldavServerStorage.getServerById(serverId);
      if (server != null) {
        return caldavServerQuirkService.decorate(server);
      }
    }
    return caldavServerQuirkService.decorate(caldavServerStorage.getServerByProviderName(CALDAV_PROVIDER_NAME));
  }

  /**
   * Resolves the CalDAV base URL a user's account reads and pushes through:
   * the URL of the resolved registration, else null — the caller then falls
   * back to the legacy configuration property, which is today's behaviour
   * for deployments that never touched the registry.
   *
   * @param serverId registration the user's account references, or null
   * @return the resolved base URL, or null when the registry answers nothing
   */
  public String resolveServerUrl(Long serverId) {
    CaldavServer server = resolveServer(serverId);
    return server == null ? null : server.getServerUrl();
  }

  /**
   * Decides the copy-settings stamp a write is to carry, and puts it on the
   * registration about to be saved (EXO-89759).
   *
   * <p>
   * <b>Why it is here and not in the storage.</b> "Has this change to a server
   * to reach the copies already written for it" is a judgement about the
   * domain, and judgements belong to the service. The storage only writes down
   * the answer.
   *
   * <p>
   * <b>Why the stored row is read again.</b> The stamp is never taken from the
   * caller. Trusting the body would let a client set every mirror in the
   * deployment re-comparing its copies by inventing a timestamp, or stop one
   * that owes a round by echoing back an old one. Reading the row that is about
   * to be overwritten is what makes the comparison a comparison.
   *
   * <p>
   * Through the storage rather than {@link #getServerById(long)} deliberately:
   * this needs the row as it is persisted, and a decorated copy of it would
   * compare unequal on fields nobody wrote.
   *
   * <p>
   * A row that cannot be read — deleted between this and the write, most
   * plausibly — leaves the stamp alone, and the write that follows answers the
   * disappearance with the not-found it already declares.
   *
   * @param server the registration about to be written, mutated in place
   */
  private void stampCopySettings(CaldavServer server) {
    if (server == null) {
      return;
    }
    CaldavServer stored = caldavServerStorage.getServerById(server.getId());
    server.setCopySettingsUpdated(CopySettingsFingerprint.stampFor(stored, server, new Date()));
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
   * Refuses a registration whose display name or URL is missing, or whose URL
   * is an address the platform must not be made to connect to, with the
   * message codes the REST layer answers 400 with.
   *
   * <p>
   * The address check runs HERE, at declaration time, rather than at
   * synchronisation time: an administrator reading "this address points inside
   * the network" while the drawer is still open can fix it, whereas the same
   * refusal an hour later is an unexplained sync failure nobody attributes to
   * what they typed. See {@link CaldavServerUrlValidator} for what is checked
   * and — as importantly — for what declaration-time validation does not
   * close.
   *
   * @param server registration to validate
   */
  private void validate(CaldavServer server) {
    validateWithoutAddress(server);
    caldavServerUrlValidator.validate(server.getServerUrl());
  }

  /**
   * The mandatory-field half of the checks, without the address check.
   *
   * <p>
   * Split out for the one caller that must not re-judge an address it is not
   * changing — see {@link #updateServer(CaldavServer, String)}.
   *
   * @param server registration to validate
   */
  private void validateWithoutAddress(CaldavServer server) {
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
