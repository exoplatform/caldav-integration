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
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.services.connector.credentials.managed.ManagedConnectorService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

import io.meeds.common.ContainerTransactional;
import jakarta.annotation.PreDestroy;

/**
 * Disconnects the users an administrator's change leaves on a CalDAV server that is no
 * longer theirs, and counts them beforehand for the warning the
 * administration screen shows.
 * <p>
 * Two changes, two populations:
 * <ul>
 * <li>a change of the managed mode - another designated server, managed mode off, a
 * group excluded - disconnects the users managed mode attached and no longer governs;
 * the users who chose a server themselves are never touched;</li>
 * <li>a server moved to another credentials provider disconnects every user of that
 * server, whoever made the connection. Nobody is reconnected automatically.</li>
 * </ul>
 * Every disconnection is {@link CaldavRelayService#disconnectForUser(long, String)}:
 * agenda's record of the connection is removed, then
 * {@link CaldavConnectorService#deleteCaldavSetting(long, String)}, the path a user's own
 * disconnection takes - the calendars are tidied, the shares and the BlueMind session
 * forgotten, as they are then. They run in the background, one user
 * at a time: the administrator's request does not wait for them, and the failure of one
 * is logged without abandoning the rest - a user left connected is caught at their next
 * login, or at the next change.
 */
@Service
public class CaldavManagedDisconnectionService {

  private static final Log         LOG = ExoLogger.getLogger(CaldavManagedDisconnectionService.class);

  @Autowired
  private ManagedConnectorService  managedConnectorService;

  @Autowired
  private CaldavManagedModeService caldavManagedModeService;

  @Autowired
  private CaldavConnectorStorage   caldavConnectorStorage;

  @Autowired
  private CaldavRelayService       caldavRelayService;

  @Autowired
  private CaldavServerService      caldavServerService;

  @Autowired
  private IdentityManager          identityManager;

  private Executor                 executor = newDisconnectionExecutor();

  /**
   * How many accounts a managed-mode change would disconnect, before it is applied: the
   * users managed mode attached that the proposed state no longer governs.
   *
   * @param designation the server the change designates, null when it switches managed
   *          mode off
   * @param excludedGroups the groups the change excludes, null for none
   * @param username the eXo login of the caller
   * @return the number of accounts the change would disconnect
   * @throws IllegalAccessException when the caller may not administer CalDAV servers
   */
  public int countUsersNoLongerManaged(Long designation, List<String> excludedGroups, String username) throws IllegalAccessException {
    requireAdministrator(username);
    return identitiesNoLongerManaged(designation, excludedGroups).size();
  }

  /**
   * How many accounts moving a server to another provider would disconnect: every user
   * connected to it.
   *
   * @param serverId the server
   * @param username the eXo login of the caller
   * @return the number of users connected to the server
   * @throws IllegalAccessException when the caller may not administer CalDAV servers
   * @throws ObjectNotFoundException when no server has this id
   */
  public int countUsersOf(long serverId, String username) throws IllegalAccessException, ObjectNotFoundException {
    requireAdministrator(username);
    caldavServerService.getServerById(serverId);
    return caldavServerService.getUserIdentitiesOfServer(serverId).size();
  }

  /**
   * Disconnects, in the background, the users managed mode attached and no longer
   * governs in the state now stored. The selection itself runs in the background too:
   * the administrator's request returns as soon as the change is stored.
   */
  public void disconnectUsersNoLongerManaged() {
    executor.execute(this::reconcile);
  }

  /**
   * Disconnects, in the background, every user of a server.
   *
   * @param serverId the server whose provider changed
   */
  public void disconnectAllUsersOf(long serverId) {
    executor.execute(() -> disconnectAll(serverId));
  }

  /**
   * Selects and disconnects the users managed mode no longer governs, on the
   * executor's thread.
   * <p>
   * {@code @ContainerTransactional} because this runs on a bare executor thread; the
   * work itself is in {@link #reconcileNow()}, the un-advised method.
   * <p>
   * One request lifecycle for the whole run, {@link #disconnectAll(long)} likewise:
   * every setting read stays in its persistence context until the run ends. Accepted:
   * a run reads each user it walks once, and a lifecycle per user would set the
   * container up again for every one of them.
   */
  @ContainerTransactional
  public void reconcile() {
    reconcileNow();
  }

  /**
   * Disconnects every user of a server, on the executor's thread.
   *
   * @param serverId the server whose provider changed
   */
  @ContainerTransactional
  public void disconnectAll(long serverId) {
    disconnectAllNow(serverId);
  }

  /**
   * Selects, against the state now stored, the users managed mode attached and no
   * longer governs, and disconnects them one by one.
   *
   * @return the number of users disconnected
   */
  int reconcileNow() {
    List<Long> identities = identitiesNoLongerManaged(caldavManagedModeService.getManagedServerId(),
                                                      caldavManagedModeService.getExcludedGroups());
    return (int) identities.stream().filter(this::disconnectNow).count();
  }

  /**
   * Disconnects every user of a server one by one.
   *
   * @param serverId the server whose provider changed
   * @return the number of users disconnected
   */
  int disconnectAllNow(long serverId) {
    return (int) caldavServerService.getUserIdentitiesOfServer(serverId).stream().filter(this::disconnectNow).count();
  }

  /**
   * Whether managed mode, in the given state, no longer governs a user it attached: it
   * designates nothing for them, or another server than the one they are on. A user
   * already on the server the state designates is left alone, and so is an identity
   * that no longer names anybody.
   *
   * @param userIdentityId the identity of a user managed mode attached
   * @param designation the designated server in that state, null when off
   * @param excludedGroups the excluded groups in that state
   * @return true when the user is to be disconnected
   */
  boolean isNoLongerManaged(long userIdentityId, Long designation, List<String> excludedGroups) {
    String username = usernameOf(userIdentityId);
    if (username == null) {
      return false;
    }
    Long governing = managedConnectorService.designatedConnectorFor(designation, excludedGroups, username);
    CaldavUserSetting setting = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    return governing == null || !Objects.equals(governing, setting == null ? null : setting.getServerId());
  }

  /**
   * Disconnects one user, logging a failure rather than throwing it - any failure, a
   * checked exception thrown sneakily included: the next user must still be processed.
   *
   * @param userIdentityId the identity to disconnect
   * @return true when the user was disconnected
   */
  boolean disconnectNow(long userIdentityId) {
    try {
      caldavRelayService.disconnectForUser(userIdentityId, usernameOf(userIdentityId));
      LOG.info("User identity {} disconnected from their CalDAV server after an administrator's change", userIdentityId);
      return true;
    } catch (Exception e) {
      LOG.warn("Cannot disconnect user identity {} from their CalDAV server after an administrator's change; the next change or their next login will retry",
               userIdentityId,
               e);
      return false;
    }
  }

  private List<Long> identitiesNoLongerManaged(Long designation, List<String> excludedGroups) {
    return caldavConnectorStorage.getIdentitiesConnectedByManagedMode()
                                 .stream()
                                 .filter(identityId -> isNoLongerManagedOrSkipped(identityId, designation, excludedGroups))
                                 .toList();
  }

  /**
   * The verdict for one user, a failure to reach it logged and the user skipped: one
   * unreadable setting or unresolvable identity must not abandon the others.
   */
  private boolean isNoLongerManagedOrSkipped(long userIdentityId, Long designation, List<String> excludedGroups) {
    try {
      return isNoLongerManaged(userIdentityId, designation, excludedGroups);
    } catch (Exception e) {
      LOG.warn("Cannot tell whether managed mode still governs user identity {}; left connected, the next change or their next login will retry",
               userIdentityId,
               e);
      return false;
    }
  }

  private String usernameOf(long userIdentityId) {
    Identity identity = identityManager.getIdentity(String.valueOf(userIdentityId));
    return identity == null ? null : identity.getRemoteId();
  }

  private void requireAdministrator(String username) throws IllegalAccessException {
    if (!caldavServerService.canEdit(username)) {
      throw new IllegalAccessException("User " + username + " may not administer CalDAV servers");
    }
  }

  private static ExecutorService newDisconnectionExecutor() {
    // One thread, an unbounded queue: an administrator's change is rare, and every
    // user it affects must be processed - none may be dropped as a login's attempt is.
    return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
      Thread thread = new Thread(runnable, "caldav-managed-disconnection");
      thread.setDaemon(true);
      return thread;
    });
  }

  @PreDestroy
  public void stop() {
    if (executor instanceof ExecutorService service) {
      service.shutdownNow();
    }
  }

  /** For the tests: run the selection and the disconnections on the caller's thread. */
  void setExecutor(Executor executor) {
    this.executor = executor;
  }
}
