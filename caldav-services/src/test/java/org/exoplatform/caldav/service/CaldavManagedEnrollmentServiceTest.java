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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.RejectedExecutionException;

import ch.qos.logback.classic.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.LogRecorder;
import org.exoplatform.caldav.client.CalDavProviderMissingException;
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
  private CaldavServerService            caldavServerService;

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

  private void configuredOn(Long serverId) {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("mary@bm.example.org");
    setting.setServerId(serverId);
    when(caldavConnectorStorage.getCaldavSetting(IDENTITY_ID)).thenReturn(setting);
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

  private void attachedByManagedModeTo(Long serverId) {
    when(caldavConnectorStorage.isConnectedByManagedMode(IDENTITY_ID)).thenReturn(true);
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("mary@bm.example.org");
    setting.setServerId(serverId);
    lenient().when(caldavConnectorStorage.getCaldavSetting(IDENTITY_ID)).thenReturn(setting);
  }

  /** A user managed mode attached, now in an excluded group, is disconnected at login. */
  @Test
  void disconnectsAtLoginAUserManagedModeAttachedAndNoLongerGoverns() throws Exception {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(null);
    when(caldavManagedModeService.governingServerFor(USER)).thenReturn(null);
    attachedByManagedModeTo(7L);

    assertEquals(Outcome.DETACHED, service.enrollOnLogin(USER));

    verify(caldavRelayService).disconnectForUser(IDENTITY_ID, USER);
    verify(caldavRelayService, never()).connectThroughProvider(anyLong(), anyString(), anyBoolean());
  }

  /**
   * A user managed mode attached whose identity cannot be resolved - the
   * directory failed - is not disconnected: the enrolment fails and the next login
   * decides.
   */
  @Test
  void neverDisconnectsAtLoginAUserWhoseIdentityCannotBeResolved() {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(null);
    attachedByManagedModeTo(7L);
    when(caldavManagedModeService.governingServerFor(USER)).thenThrow(new IllegalStateException("no identity"));

    assertEquals(Outcome.FAILED, service.enrollOnLogin(USER));

    verify(caldavRelayService, never()).disconnectForUser(anyLong(), any());
  }

  /** A user who chose their server is not touched in the same situation. */
  @Test
  void neverDisconnectsAUserWhoChoseTheirServer() {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(null);
    when(caldavConnectorStorage.isConnectedByManagedMode(IDENTITY_ID)).thenReturn(false);

    assertEquals(Outcome.NOT_MANAGED, service.enrollOnLogin(USER));

    verify(caldavRelayService, never()).disconnectForUser(anyLong(), any());
  }

  /** Attached to a server no longer designated: disconnected, then attached to the one designated now. */
  @Test
  void movesAtLoginAUserManagedModeAttachedToAServerNoLongerDesignated() throws Exception {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(7L);
    when(caldavManagedModeService.governingServerFor(USER)).thenReturn(7L);
    attachedByManagedModeTo(3L);
    when(caldavRelayService.connectThroughProvider(7L, USER, true)).thenReturn(probe(CaldavProbeResult.OK));
    // After the disconnection the user has no configuration left.
    CaldavUserSetting none = new CaldavUserSetting();
    CaldavUserSetting before = new CaldavUserSetting();
    before.setUsername("mary@bm.example.org");
    before.setServerId(3L);
    when(caldavConnectorStorage.getCaldavSetting(IDENTITY_ID)).thenReturn(before, none);

    assertEquals(Outcome.ATTACHED, service.enrollOnLogin(USER));

    InOrder order = inOrder(caldavRelayService);
    order.verify(caldavRelayService).disconnectForUser(IDENTITY_ID, USER);
    order.verify(caldavRelayService).connectThroughProvider(7L, USER, true);
  }

  /** Attached by managed mode to the designated server: left alone. */
  @Test
  void leavesAloneAUserManagedModeAttachedToTheDesignatedServer() {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(7L);
    when(caldavManagedModeService.governingServerFor(USER)).thenReturn(7L);
    attachedByManagedModeTo(7L);
    when(caldavServerService.isOnServer(any(CaldavUserSetting.class), eq(7L))).thenReturn(true);

    assertEquals(Outcome.ALREADY_CONFIGURED, service.enrollOnLogin(USER));

    verify(caldavRelayService, never()).disconnectForUser(anyLong(), any());
  }

  /**
   * Managed mode does not apply - nothing designated, or an excluded user;
   * commons-exo answered null and this class does not care which: only the
   * managed-mode mark is read, which is what every login pays on an instance
   * where the mode is off.
   */
  @Test
  void doesNothingWhenManagedModeDoesNotApply() {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(null);

    assertEquals(Outcome.NOT_MANAGED, service.enrollOnLogin(USER));

    // Only the managed-mode mark is read: a user managed mode never attached is left alone.
    verify(caldavConnectorStorage).isConnectedByManagedMode(anyLong());
    verifyNoMoreInteractions(caldavConnectorStorage);
    verifyNoInteractions(caldavRelayService);
  }

  /**
   * A user already on the designated server - they chose it, or were attached before -
   * is left alone, whether managed mode marked the connection or not.
   */
  @Test
  void leavesAloneAUserAlreadyOnTheDesignatedServer() throws Exception {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(7L);
    configuredOn(7L);
    when(caldavServerService.isOnServer(any(CaldavUserSetting.class), eq(7L))).thenReturn(true);

    assertEquals(Outcome.ALREADY_CONFIGURED, service.enrollOnLogin(USER));

    verify(caldavRelayService, never()).connectThroughProvider(anyLong(), anyString(), anyBoolean());
    verify(caldavRelayService, never()).switchThroughProvider(anyLong(), anyString());
  }

  /**
   * EXO-90836. A governed user whose account is on another server is switched to the
   * designated one, and nothing is disconnected first: the switch changes the account
   * only once the designated server answered.
   */
  @Test
  void switchesAtLoginAGovernedUserOnAnotherServer() throws Exception {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(7L);
    configured(true);
    when(caldavRelayService.switchThroughProvider(7L, USER)).thenReturn(probe(CaldavProbeResult.OK));

    assertEquals(Outcome.SWITCHED, service.enrollOnLogin(USER));

    verify(caldavRelayService, never()).disconnectForUser(anyLong(), anyString());
    verify(caldavRelayService, never()).connectThroughProvider(anyLong(), anyString(), anyBoolean());
  }

  /** EXO-90836. A switch the designated server refuses leaves the user's account as it was. */
  @Test
  void aRefusedSwitchLeavesTheUsersAccount() throws Exception {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(7L);
    configured(true);
    when(caldavRelayService.switchThroughProvider(7L, USER)).thenReturn(probe(CaldavProbeResult.CREDENTIALS));

    assertEquals(Outcome.REFUSED, service.enrollOnLogin(USER));

    verify(caldavRelayService, never()).disconnectForUser(anyLong(), anyString());
  }

  /**
   * Rule three: attached through the one-click connect, which records the
   * connection only once the server accepted the provider's material.
   */
  @Test
  void attachesAUserWithoutAConfiguration() throws Exception {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(7L);
    configured(false);
    when(caldavRelayService.connectThroughProvider(7L, USER, true)).thenReturn(probe(CaldavProbeResult.OK));

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
    when(caldavRelayService.connectThroughProvider(7L, USER, true)).thenReturn(probe(CaldavProbeResult.CREDENTIALS));

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
    doThrow(refusal).when(caldavRelayService).connectThroughProvider(7L, USER, true);

    assertEquals(Outcome.REFUSED, service.enrollOnLogin(USER));
  }

  /**
   * A designated server whose credentials provider is not installed - an add-on's,
   * not installed or not started yet - leaves the user unattached as a refusal does,
   * but says nothing at INFO or above and carries no stack: it is met at every login
   * of every governed user, and the resolver has said it once for the provider's name.
   */
  @Test
  void leavesUnattachedQuietlyAUserWhoseServersProviderIsNotInstalled() throws Exception {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(7L);
    configured(false);
    doThrow(CalDavProviderMissingException.named("bluemind-sudo")).when(caldavRelayService)
                                                                 .connectThroughProvider(7L, USER, true);

    try (LogRecorder log = new LogRecorder(CaldavManagedEnrollmentService.class)) {
      assertEquals(Outcome.REFUSED, service.enrollOnLogin(USER));

      assertTrue(log.events().stream().noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.INFO)),
                 log.events().toString());
      assertTrue(log.events().stream().noneMatch(event -> event.getThrowableProxy() != null), log.events().toString());
    }
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
    doThrow(unreachable).when(caldavRelayService).connectThroughProvider(7L, USER, true);

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

  /**
   * EXO-90836. An account connected through the legacy connector names no registration;
   * when the one it resolves to is the designated server, the user is on it already,
   * and nothing is switched over their account.
   */
  @Test
  void leavesAloneALegacyAccountOnTheRegistrationItResolvesTo() throws Exception {
    when(caldavManagedModeService.designatedServerFor(USER)).thenReturn(7L);
    configuredOn(null);
    when(caldavServerService.isOnServer(any(CaldavUserSetting.class), eq(7L))).thenReturn(true);

    assertEquals(Outcome.ALREADY_CONFIGURED, service.enrollOnLogin(USER));

    verify(caldavRelayService, never()).switchThroughProvider(anyLong(), anyString());
  }
}
