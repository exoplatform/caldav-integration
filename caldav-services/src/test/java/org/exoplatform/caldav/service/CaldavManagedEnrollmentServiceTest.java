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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.LogRecorder;
import org.exoplatform.caldav.model.CaldavProbeResult;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.service.CaldavManagedEnrollmentService.Outcome;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * EXO-89653. The three rules of the login-time enrolment, in order, and what
 * each exit records: nothing unless the server accepted, and nothing stored
 * about the outcome itself.
 */
@ExtendWith(MockitoExtension.class)
class CaldavManagedEnrollmentServiceTest {

  private static final String            USER        = "mary";

  private static final long              IDENTITY_ID = 42L;

  @Mock
  private CaldavManagedModeService       caldavManagedModeService;

  @Mock
  private CaldavRelayService             caldavRelayService;

  @Mock
  private CaldavConnectorStorage         caldavConnectorStorage;

  @Mock
  private IdentityManager                identityManager;

  @InjectMocks
  private CaldavManagedEnrollmentService service;

  private MockedStatic<ExoContainerContext> containerContext;

  @BeforeEach
  void runOnTheCallerThread() {
    // enrollOnLogin is @ContainerTransactional: the woven aspect reads the current
    // container, and with none bound it would boot the kernel. A mocked, non-root
    // container is what every such test on this platform binds.
    containerContext = mockStatic(ExoContainerContext.class);
    ExoContainer portalContainer = mock(ExoContainer.class);
    containerContext.when(ExoContainerContext::getCurrentContainer).thenReturn(portalContainer);
    // The executor is the caller's thread: what is pinned is what runs, not when.
    service.setExecutor(Runnable::run);
    Identity identity = new Identity(String.valueOf(IDENTITY_ID));
    lenient().when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, USER)).thenReturn(identity);
  }

  @AfterEach
  void forgetTheContainer() {
    containerContext.close();
  }

  private void configured(boolean hasConfiguration) {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername(hasConfiguration ? "mary@bm.example.org" : null);
    // On another server than the designated one (7): "whatever server it names".
    setting.setServerId(hasConfiguration ? 99L : null);
    when(caldavConnectorStorage.getCaldavSetting(IDENTITY_ID)).thenReturn(setting);
  }

  private CaldavProbeResult probe(String result) {
    CaldavProbeResult outcome = new CaldavProbeResult();
    outcome.setResult(result);
    return outcome;
  }

  /**
   * Managed mode does not apply - nothing designated, or an excluded user;
   * commons-exo answered null and this class does not care which: the user's
   * own settings are not even opened, which is what every login pays on an
   * instance where the mode is off.
   */
  @Test
  void doesNothingWhenManagedModeDoesNotApply() {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(null);

    assertEquals(Outcome.NOT_MANAGED, service.enrollOnLogin(USER));

    verifyNoInteractions(caldavConnectorStorage, caldavRelayService);
  }

  /**
   * Rule one: a configuration exists, whatever server it names - the user
   * chose, or was attached before - and nothing happens. Disconnecting is what
   * makes this false again.
   */
  @Test
  void leavesAloneAUserWhoAlreadyHasAConfiguration() throws Exception {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(7L);
    configured(true);

    assertEquals(Outcome.ALREADY_CONFIGURED, service.enrollOnLogin(USER));

    verify(caldavRelayService, never()).connectThroughProvider(anyLong(), anyString());
  }

  /**
   * Rule three: attached through the one-click connect, which records the
   * connection only once the server accepted the provider's material.
   */
  @Test
  void attachesAUserWithoutAConfiguration() throws Exception {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(7L);
    configured(false);
    when(caldavRelayService.connectThroughProvider(7L, USER)).thenReturn(probe(CaldavProbeResult.OK));

    assertEquals(Outcome.ATTACHED, service.enrollOnLogin(USER));
  }

  /**
   * A user the designated server does not know is left unattached - the relay
   * recorded nothing, and this class stores nothing either: the next login
   * tries again, and the administrator's remedies are an account there or an
   * exclusion.
   */
  @Test
  void leavesUnattachedAUserTheServerRefuses() throws Exception {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(7L);
    configured(false);
    when(caldavRelayService.connectThroughProvider(7L, USER)).thenReturn(probe(CaldavProbeResult.CREDENTIALS));

    assertEquals(Outcome.REFUSED, service.enrollOnLogin(USER));
  }

  /**
   * A connect that refuses before probing - the provider produced no credentials,
   * as when BlueMind is down - is a refusal like any other: no stack, and the next
   * login tries again.
   */
  @ParameterizedTest
  @MethodSource("refusals")
  void leavesUnattachedAUserTheConnectRefuses(Exception refusal) throws Exception {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(7L);
    configured(false);
    doThrow(refusal).when(caldavRelayService).connectThroughProvider(7L, USER);

    assertEquals(Outcome.REFUSED, service.enrollOnLogin(USER));
  }

  /** The three refusals the connect throws before probing, one per type the catch names. */
  static java.util.stream.Stream<Exception> refusals() {
    return java.util.stream.Stream.of(new IllegalAccessException("caldav.relay.serverInactive"),
                                      new IllegalArgumentException("caldav.connect.providerNamesNobody"),
                                      new IllegalStateException("caldav.relay.notConnected"));
  }

  /**
   * During a BlueMind outage the refusal reaches the enrolment wrapped several
   * times, and the innermost exception - the transport's own - has no message.
   * The INFO line must still say that BlueMind could not be reached.
   */
  @Test
  void namesEveryCauseOfARefusalInItsLogLine() throws Exception {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(7L);
    configured(false);
    Exception transport = new java.nio.channels.ClosedChannelException();
    Exception unreachable = new IllegalStateException("caldav.relay.notConnected",
                                                      new RuntimeException("Cannot reach BlueMind on /api/auth/login", transport));
    doThrow(unreachable).when(caldavRelayService).connectThroughProvider(7L, USER);

    try (LogRecorder log = new LogRecorder(CaldavManagedEnrollmentService.class)) {
      assertEquals(Outcome.REFUSED, service.enrollOnLogin(USER));

      assertEquals("User mary left unattached: the managed CalDAV server 7 refused "
          + "(caldav.relay.notConnected <- Cannot reach BlueMind on /api/auth/login <- ClosedChannelException)",
                   log.only().getFormattedMessage());
    }
  }

  /** Any other failure is logged and swallowed: a login must never fail on this. */
  @Test
  void swallowsAFailureAndLeavesTheUserForTheNextLogin() {
    when(caldavManagedModeService.designatedServerFor(USER)).thenThrow(new RuntimeException("boom"));

    assertEquals(Outcome.FAILED, service.enrollOnLogin(USER));
  }

  /** Scheduling hands the user to the executor and returns; a blank login is dropped before that. */
  @Test
  void schedulesOnTheExecutorAndDropsABlankLogin() {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(null);

    assertTrue(service.scheduleEnrollment(USER));
    assertFalse(service.scheduleEnrollment(" "));

    verify(caldavManagedModeService).designatedServerFor(USER);
  }

  /**
   * A full queue drops the attempt with a warning rather than blocking the
   * login thread; the user's next login tries again.
   */
  @Test
  void dropsTheAttemptWhenTheQueueIsFull() {
    service.setExecutor(runnable -> {
      throw new RejectedExecutionException("full");
    });

    assertFalse(service.scheduleEnrollment(USER));
  }
}
