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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.model.CaldavProbeResult;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

import io.meeds.common.ContainerTransactional;
import jakarta.annotation.PreDestroy;

/**
 * Enrols a user on the CalDAV server managed mode designated, when they log in
 * (EXO-89653): the login-time attachment the board describes.
 *
 * <p>
 * Three rules, in the order the board states them and with one reordering
 * that changes no outcome: the user already has a CalDAV configuration -
 * whatever server it names - and nothing happens; the user is in a population
 * the administrator excluded and nothing happens; otherwise they are attached
 * to the designated server. The designation is read first here because it is
 * the cheapest read and the one that is null on every instance where managed
 * mode is off: such an instance pays two setting reads per login and never
 * opens the user's own settings.
 *
 * <p>
 * Having no configuration and having removed one are the same case: a user
 * who disconnects is attached again at their next login, and disconnecting
 * stays useful because whoever connects elsewhere has a configuration, which
 * rule one leaves alone. Nothing is stored about the outcome - it is a
 * function of (user, designation, exclusions, the user's settings) and a
 * stored outcome would drift the moment an administrator changed one of them.
 *
 * <p>
 * The attachment is the one-click connect of EXO-90358: the server is probed
 * with the material the provider produces and the connection is recorded only
 * if that passed. A user the designated server does not know is left
 * unattached, and tried again at their next login, rather than recorded as
 * connected to a server that will not answer.
 *
 * <p>
 * <b>In the background.</b> The login never waits for the remote server: the
 * listener hands the user to a small bounded executor of this service and
 * returns. The load spreads at the rate people arrive, which is why no
 * staggering exists. A full queue drops the attempt with a WARN - the next
 * login retries - rather than blocking the login thread.
 *
 * <p>
 * Lives beside {@link CaldavManagedModeService} rather than inside it: the
 * enrolment needs {@link CaldavRelayService}, which needs
 * {@link CaldavServerService}, which needs the managed-mode service for its
 * guards - injecting the relay into the managed-mode service would close that
 * cycle.
 */
@Service
public class CaldavManagedEnrollmentService {

  private static final Log         LOG         = ExoLogger.getLogger(CaldavManagedEnrollmentService.class);

  /** Enrolments waiting for a thread: beyond this, a login's attempt is dropped and retried next time. */
  static final int                 QUEUE_DEPTH = 256;

  @Autowired
  private CaldavManagedModeService caldavManagedModeService;

  @Autowired
  private CaldavRelayService       caldavRelayService;

  @Autowired
  private CaldavConnectorStorage   caldavConnectorStorage;

  @Autowired
  private IdentityManager          identityManager;

  private Executor                 executor    = newEnrollmentExecutor();

  /**
   * Queues the enrolment of a user who just logged in. Returns at once.
   *
   * @param username the eXo login of the user who logged in
   * @return true when the attempt was queued, false when it was dropped
   */
  public boolean scheduleEnrollment(String username) {
    if (StringUtils.isBlank(username)) {
      return false;
    }
    try {
      executor.execute(() -> enrollOnLogin(username));
      return true;
    } catch (RejectedExecutionException e) {
      // The queue is full: this is a login storm, and the next login of this
      // user will try again. Blocking the login thread instead would turn a
      // slow CalDAV server into a slow platform.
      LOG.warn("Too many CalDAV enrolments pending; user {} will be attached at their next login", username);
      return false;
    }
  }

  /**
   * Applies the three rules for one user, on the executor's thread.
   *
   * <p>
   * {@code @ContainerTransactional} because this runs on a bare executor
   * thread: the aspect binds the portal container and a request lifecycle
   * around the call, which the setting reads and the recorded connection need.
   *
   * @param username the eXo login of the user who logged in
   * @return what happened, for the tests and the log
   */
  @ContainerTransactional
  public Outcome enrollOnLogin(String username) {
    try {
      Long serverId = caldavManagedModeService.designatedServerFor(username);
      if (serverId == null) {
        LOG.debug("User {} not enrolled: no managed CalDAV server applies to them", username);
        return Outcome.NOT_MANAGED;
      }
      if (hasConfiguration(username)) {
        LOG.debug("User {} not enrolled: they already have a CalDAV configuration", username);
        return Outcome.ALREADY_CONFIGURED;
      }
      return attach(serverId, username);
    } catch (Exception e) {
      LOG.warn("Cannot attach user {} to the managed CalDAV server at login; their next login will try again", username, e);
      return Outcome.FAILED;
    }
  }

  /**
   * Rule three: the one-click connect, run for the user. A refusal - the probe's
   * answer, or the connect refusing before probing - records nothing and is
   * retried at the next login; any other exception is the caller's failure.
   *
   * @param serverId the designated registration
   * @param username the eXo login of the user who logged in
   * @return ATTACHED or REFUSED
   * @throws Exception an unexpected failure, logged by the caller
   */
  private Outcome attach(Long serverId, String username) throws Exception {
    try {
      CaldavProbeResult outcome = caldavRelayService.connectThroughProvider(serverId, username);
      if (CaldavProbeResult.OK.equals(outcome.getResult())) {
        LOG.info("User {} attached to the managed CalDAV server {} at login", username, serverId);
        return Outcome.ATTACHED;
      }
      // The server refused the user: no account there, or a server that does
      // not answer. Nothing is recorded, and the next login tries again - the
      // administrator's remedy is a BlueMind account or an exclusion.
      LOG.info("User {} left unattached: the managed CalDAV server {} answered {}", username, serverId, outcome.getResult());
      return Outcome.REFUSED;
    } catch (IllegalAccessException | IllegalArgumentException | IllegalStateException e) {
      // The connect refused before probing: the server inactive, a provider that
      // asks the user or names nobody, or one that produced no credentials - a
      // BlueMind that does not answer lands here, once per login, without a stack.
      // The whole cause chain, not the outer code: a refusal from BlueMind is
      // wrapped several times on its way here, and the message that says why
      // is not always the innermost one.
      LOG.info("User {} left unattached: the managed CalDAV server {} refused ({})", username, serverId, causeChain(e));
      return Outcome.REFUSED;
    }
  }

  /**
   * Rule one: whether the user already has a CalDAV configuration, whatever
   * server it names. The username is what "credentials exist" survives as in
   * the stored setting - the letter of the board's rule, and the predicate the
   * personal credentials source reads. Deliberately not
   * {@code CaldavServerService.isConnected}, which also asks whether the stored
   * record can still authenticate: a record left behind by an administrator's
   * change (a server switched to a provider that asks the user) reads as "has a
   * configuration" here and as "reconnect" in the UI, and removing such records
   * is EXO-89654's, not a reason to re-attach over them at login. A calendar
   * connector of another kind - Google, Office 365, Exchange - is another
   * population of connectors and plays no part here: its user is attached to
   * CalDAV as well, and agenda lists both (decision 2026-09-23).
   *
   * @param username the eXo login
   * @return true when a configuration exists
   */
  private boolean hasConfiguration(String username) {
    Identity identity = identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, username);
    if (identity == null) {
      return false;
    }
    CaldavUserSetting setting = caldavConnectorStorage.getCaldavSetting(Long.parseLong(identity.getId()));
    return setting != null && StringUtils.isNotBlank(setting.getUsername());
  }

  /**
   * Two threads and a bounded queue: enough to absorb a morning's logins
   * against a server that answers in a second, small enough that a server
   * that does not answer cannot pile up threads.
   *
   * @return the executor the enrolments run on
   */
  private static ExecutorService newEnrollmentExecutor() {
    return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_DEPTH), runnable -> {
      Thread thread = new Thread(runnable, "caldav-managed-enrollment");
      thread.setDaemon(true);
      return thread;
    });
  }

  @PreDestroy
  public void stop() {
    if (executor instanceof ExecutorService service) {
      // Drop what is queued rather than run it against a context being torn
      // down; the probe already treats the interruption as "not reached", and
      // the next login retries.
      service.shutdownNow();
    }
  }

  /**
   * Every non-blank message of a failure's cause chain, outermost first. The
   * root alone is not enough: a transport failure's innermost exception usually
   * carries no message, and the one that says what happened - "Cannot reach
   * BlueMind on /api/auth/login" - sits a level above it. A throwable with no
   * message is named by its class.
   *
   * @param failure the refusal as the connect threw it
   * @return the chain, joined with {@code " <- "}
   */
  static String causeChain(Throwable failure) {
    return ExceptionUtils.getThrowableList(failure)
                         .stream()
                         .map(cause -> StringUtils.isBlank(cause.getMessage()) ? cause.getClass().getSimpleName()
                                                                               : cause.getMessage())
                         .collect(Collectors.joining(" <- "));
  }

  /** For the tests: run the enrolments on the caller's thread. */
  void setExecutor(Executor executor) {
    this.executor = executor;
  }

  /** What a login attempt came to, one value per branch of the three rules and their failures. */
  public enum Outcome {
    NOT_MANAGED, ALREADY_CONFIGURED, ATTACHED, REFUSED, FAILED
  }
}
