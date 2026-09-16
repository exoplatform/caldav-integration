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

  /**
   * The deletion engine, resolved lazily for the same reason as the two above.
   */
  private CaldavDeletionService  caldavDeletionService;

  /**
   * Who each account is on its server (EXO-90243), resolved lazily for the
   * same reason as the three above.
   */
  private CaldavConnectionIdentityService caldavConnectionIdentityService;

  /**
   * What each account's home listed of its colleagues' eXo calendars
   * (EXO-90331), resolved lazily for the same reason as the four above.
   */
  private CaldavShareObservationService caldavShareObservationService;

  public CaldavConnectorServiceImpl(CaldavConnectorStorage caldavConnectorStorage) {
    String caldavUrl = System.getProperty("exo.agenda.caldav.connector.url");
    this.caldavConnectorStorage = caldavConnectorStorage;
    this.caldavUrl = caldavUrl;
  }

  @Override
  public void createCaldavSetting(CaldavUserSetting caldavUserSetting, long userIdentityId) throws IllegalAccessException {
    if (StringUtils.isNotBlank(caldavUserSetting.getPassword()) && StringUtils.isNotBlank(caldavUserSetting.getUsername())) {
      // Read before the write, because the write is an upsert: afterwards
      // there is no way left to tell a new account from the same one entered
      // again (EXO-90331).
      CaldavUserSetting previous = caldavConnectorStorage.getCaldavSetting(userIdentityId);
      caldavConnectorStorage.createCaldavSetting(caldavUserSetting, userIdentityId);
      // The credentials just changed, so the server identity recorded under
      // the previous ones no longer describes this account (EXO-90243). Gone
      // before anything else is asked of the server: the destinations step
      // below records the new one from its own discovery, and if that
      // discovery fails the account is unknown rather than wrongly known.
      forgetServerIdentity(userIdentityId);
      if (accountChanged(previous, caldavUserSetting)) {
        forgetShareObservations(userIdentityId);
      }
      // Disconnecting froze the bindings of the calendars eXo pushed out, so
      // that reconnecting would find its collections again. Reconnecting is
      // what thaws them: until it does, the account is connected while the
      // user's own calendars still report themselves as failing.
      try {
        CaldavDeletionService deletionService = getCaldavDeletionService();
        if (deletionService != null) {
          CaldavUserSetting stored = caldavConnectorStorage.getCaldavSetting(userIdentityId);
          Long serverId = stored == null ? caldavUserSetting.getServerId() : stored.getServerId();
          deletionService.thawOnConnect(userIdentityId, serverId == null ? 0L : serverId);
        }
      } catch (RuntimeException e) {
        // Connecting must succeed. A user who has just given valid credentials
        // and is told it failed, because a stale pause could not be lifted,
        // is worse off than one whose calendars take a sweep to catch up.
        LOG.warn("The frozen calendars of user {} could not be resumed on connect", userIdentityId, e);
      }
      // Someone who has just entered their credentials is owed their calendars
      // now, not in a quarter of an hour — and a throttle stamped by a previous
      // account's run has nothing to say about this one.
      CaldavSyncService syncService = getCaldavSyncService();
      if (syncService != null) {
        syncService.forgetThrottle(userIdentityId);
        establishDestinations(syncService, userIdentityId);
      }
    } else {
      throw new IllegalAccessException("username or password not be null");
    }
  }

  /**
   * Gives the copies of a freshly connected account somewhere to go, before
   * the user has created anything to copy.
   *
   * <p>
   * Connecting is a deliberate act by somebody who is watching, so the
   * destination is resolved on that act rather than on whatever background
   * sweep happens next. Without it the first meetings a user creates are
   * refused silently — there is no collection to write them into — and stay
   * refused until a pass minutes or half an hour away puts one there
   * (EXO-89803).
   *
   * <p>
   * Contained for the reason the thaw above it is contained: a user who has
   * just given valid credentials and is told the connection failed, because a
   * calendar server would not create a collection, is worse off than one whose
   * first copies wait for a sweep. The engine guards each step itself; this
   * catch is what makes that true even of a failure it did not foresee.
   *
   * @param syncService the resolved engine
   * @param userIdentityId identity of the user who has just connected
   */
  private void establishDestinations(CaldavSyncService syncService, long userIdentityId) {
    try {
      syncService.establishDestinations(userIdentityId);
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The copies of user {} were left without a destination on connect; a sweep establishes one",
               userIdentityId,
               e);
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
   * The deletion engine, resolved through the bridge on first use.
   *
   * <p>
   * Null when it cannot be resolved, and disconnecting then removes the
   * settings and nothing else — the previous behaviour. Leaving a mirror
   * calendar behind is a poor outcome; refusing to disconnect because of it
   * would be a worse one.
   *
   * @return the engine, or null when the bridge cannot provide it
   */
  protected CaldavDeletionService getCaldavDeletionService() {
    if (caldavDeletionService == null) {
      try {
        caldavDeletionService = ExoContainerContext.getService(CaldavDeletionService.class);
      } catch (Exception | LinkageError e) {
        LOG.debug("CalDAV deletion engine not resolvable; disconnecting removes the settings only", e);
      }
    }
    return caldavDeletionService;
  }

  /**
   * Hands the deletion engine to tests, which have no container to resolve it
   * from.
   *
   * @param caldavDeletionService the engine to use
   */
  protected void setCaldavDeletionService(CaldavDeletionService caldavDeletionService) {
    this.caldavDeletionService = caldavDeletionService;
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
    deleteCaldavSetting(userIdentityId, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void deleteCaldavSetting(long userIdentityId, String username) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (settings != null && StringUtils.isNotBlank(username)) {
      // Before the settings go, while the account can still be identified.
      // Without the login there is no ACL to delete a calendar under, so the
      // bindings are left as they are rather than half-processed.
      long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
      try {
        CaldavDeletionService deletionService = getCaldavDeletionService();
        if (deletionService != null) {
          deletionService.freezeOnDisconnect(userIdentityId, serverId, username);
        }
      } catch (RuntimeException e) {
        // Disconnecting must succeed. A user asking to unlink their account
        // and being told it failed, because a calendar could not be tidied
        // away, would be left connected to an account they no longer want.
        LOG.warn("The calendars of user {} could not be tidied on disconnect", userIdentityId, e);
      }
    }
    caldavConnectorStorage.deleteCaldavSetting(userIdentityId);
    // After the settings, so that a failure here leaves a row that every
    // reader already ignores: an identity counts only while its account is
    // connected (EXO-90243).
    forgetServerIdentity(userIdentityId);
    forgetShareObservations(userIdentityId);
  }

  /**
   * Forgets who the user's account is on its server, when that engine can be
   * resolved.
   *
   * <p>
   * Nothing here may fail a connection or a disconnection: the service absorbs
   * its own failures, and an engine the bridge cannot provide leaves a row that
   * the next discovery replaces, or that no reader believes once the account
   * is gone.
   *
   * @param userIdentityId identity of the user
   */
  private void forgetServerIdentity(long userIdentityId) {
    CaldavConnectionIdentityService identityService = getCaldavConnectionIdentityService();
    if (identityService != null) {
      identityService.forgetPrincipal(userIdentityId);
    }
  }

  /**
   * Forgets what this user's calendar home listed of their colleagues' eXo
   * calendars, when that engine can be resolved (EXO-90331).
   *
   * <p>
   * A home that is no longer read confirms nothing. Left in place, its
   * sightings would keep drawing a "shared" mark on colleagues' calendar rows
   * with nothing able to contradict them ever again — the stale mark that is
   * worse than no mark, since it tells its owner they are exposed when they
   * may no longer be. The first pass after a connection records whatever is
   * still true.
   *
   * <p>
   * Asked unconditionally on a disconnection, and on a connection only when
   * the account actually changed ({@link #accountChanged}) — unlike the
   * recorded server identity, which is cleared either way. The asymmetry is
   * deliberate and its reason is on that method: these rows are other owners'
   * marks, not this user's.
   *
   * <p>
   * Nothing here may fail a connection or a disconnection; the service absorbs
   * its own failures, and an engine the bridge cannot provide leaves rows the
   * next pass reconciles, or that nobody's panel asks about while the account
   * is gone.
   *
   * @param userIdentityId identity of the user
   */
  private void forgetShareObservations(long userIdentityId) {
    CaldavShareObservationService observationService = getCaldavShareObservationService();
    if (observationService != null) {
      observationService.forgetObservationsOf(userIdentityId);
    }
  }

  /**
   * Whether a connection is to a different account from the one recorded
   * (EXO-90331).
   *
   * <p>
   * <b>Why the sightings are not cleared unconditionally, while the recorded
   * server identity is.</b> The two look alike and their costs are not. A
   * forgotten identity costs this user one rediscovery; forgotten sightings
   * cost <em>other</em> owners their marks, because
   * {@code forgetObservationsOf} deletes every row in which this user is the
   * sharee, and those rows are what draws the mark on their colleagues' rows.
   * Those owners did nothing, cannot see that it happened, and cannot cause
   * the pass that would restore it — their own pass never lists their own
   * calendar as a colleague's. So the clear is worth its cost when the account
   * really changed, and is pure damage when it did not.
   *
   * <p>
   * Not a hypothetical, and not the everyday path either. The agenda drawer
   * offers Connect only on a row that is not connected, so a user normally
   * reaches a re-connect through Disconnect — which clears by design — and the
   * front end then chains a full synchronising pass that re-records within
   * seconds. What has no <em>upstream</em> guard — no affordance stopping the
   * call from being made on a live account — is {@code POST /v1/caldav} called
   * directly, and any state where agenda's connected-account setting is
   * missing while the credential is not, which puts Connect back on a live
   * account. This method is the guard those routes reach: it is why they now
   * keep the rows instead of clearing them. Without it the pass may not
   * follow, and the marks then wait on the sweep — which does not pick an
   * account whose bindings were just written until it is stale
   * ({@code exo.agenda.caldav.sync.sweep.staleMinutes}, thirty minutes by
   * default), not merely until the next sweep tick.
   *
   * <p>
   * Compared on the two things that make an account a different account: the
   * login and the server registration. Deliberately <b>not</b> on the
   * password — a password change is the same account, and its sightings go on
   * describing it.
   *
   * <p>
   * A first connection needs no arm of its own, and deliberately has none.
   * {@code CaldavConnectorStorage.getCaldavSetting} answers a blank
   * {@code CaldavUserSetting} rather than nothing when a user has never
   * connected, so {@code previous.getUsername()} is null while the caller has
   * already refused a blank incoming one — the login comparison below is
   * therefore true, and the rows (of which there are none yet) are cleared.
   * An explicit blank-username arm was written here first and removed: it
   * could not be made to fail, which is the definition of a branch nothing
   * holds in place.
   *
   * <p>
   * The null guard is not of that kind. It is unreachable from production for
   * the same reason — the storage never answers null — but it is load-bearing
   * all the same, since without it the {@code getServerId()} below throws
   * rather than returning an answer. {@code anUnknownPreviousAccountForgetsThemToo}
   * pins it and says it is a guard.
   *
   * @param previous what was recorded before this call, blank when the user
   *          has never connected; null only if the storage contract changes
   * @param incoming what is being recorded now, its login already checked
   *          non-blank by the caller
   * @return true when the sightings this user's home produced no longer
   *         describe the account being connected
   */
  private static boolean accountChanged(CaldavUserSetting previous, CaldavUserSetting incoming) {
    if (previous == null) {
      return true;
    }
    long before = previous.getServerId() == null ? 0L : previous.getServerId();
    long now = incoming.getServerId() == null ? 0L : incoming.getServerId();
    return before != now || !StringUtils.equals(previous.getUsername(), incoming.getUsername());
  }

  /**
   * The share-observation engine, resolved through the bridge on first use.
   *
   * @return the engine, or null when the bridge cannot provide it
   */
  protected CaldavShareObservationService getCaldavShareObservationService() {
    if (caldavShareObservationService == null) {
      try {
        caldavShareObservationService = ExoContainerContext.getService(CaldavShareObservationService.class);
      } catch (Exception | LinkageError e) {
        LOG.debug("CalDAV share-observation engine not resolvable; the recorded sightings are left as they are", e);
      }
    }
    return caldavShareObservationService;
  }

  /**
   * Hands the share-observation engine to tests, which have no container to
   * resolve it from.
   *
   * @param caldavShareObservationService the engine to use
   */
  protected void setCaldavShareObservationService(CaldavShareObservationService caldavShareObservationService) {
    this.caldavShareObservationService = caldavShareObservationService;
  }

  /**
   * The connection-identity engine, resolved through the bridge on first use.
   *
   * @return the engine, or null when the bridge cannot provide it
   */
  protected CaldavConnectionIdentityService getCaldavConnectionIdentityService() {
    if (caldavConnectionIdentityService == null) {
      try {
        caldavConnectionIdentityService = ExoContainerContext.getService(CaldavConnectionIdentityService.class);
      } catch (Exception | LinkageError e) {
        LOG.debug("CalDAV connection identity engine not resolvable; the recorded server identity is left as it is", e);
      }
    }
    return caldavConnectionIdentityService;
  }

  /**
   * Hands the connection-identity engine to tests, which have no container to
   * resolve it from.
   *
   * @param caldavConnectionIdentityService the engine to use
   */
  protected void setCaldavConnectionIdentityService(CaldavConnectionIdentityService caldavConnectionIdentityService) {
    this.caldavConnectionIdentityService = caldavConnectionIdentityService;
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
