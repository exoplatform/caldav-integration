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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.managed.ManagedConnectorService;
import org.exoplatform.services.connector.credentials.managed.ManagedConnectorStorage;
import org.exoplatform.services.security.MembershipEntry;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * What an administrator's change does to the CalDAV users managed mode
 * attached, and to the users of a server moved to another provider. The verdict is the
 * real {@link ManagedConnectorService}'s: only the users' groups are stubbed.
 */
@ExtendWith(MockitoExtension.class)
class CaldavManagedDisconnectionServiceTest {

  private static final String               ADMIN = "root";

  @Mock
  private CaldavManagedModeService          caldavManagedModeService;

  @Mock
  private CaldavConnectorStorage            caldavConnectorStorage;

  @Mock
  private CaldavRelayService                caldavRelayService;

  @Mock
  private CaldavServerService               caldavServerService;

  @Mock
  private IdentityManager                   identityManager;

  @Mock
  private UserACL                           userAcl;

  private CaldavManagedDisconnectionService service;

  private MockedStatic<ExoContainerContext> containerContext;

  private final List<Runnable>              queued = new ArrayList<>();

  @BeforeEach
  void setUp() {
    // reconcile() and disconnectAll() are @ContainerTransactional: the woven aspect reads the current
    // container, and with none bound it would boot the kernel.
    containerContext = mockStatic(ExoContainerContext.class);
    containerContext.when(ExoContainerContext::getCurrentContainer).thenReturn(mock(ExoContainer.class));
    service = new CaldavManagedDisconnectionService();
    ReflectionTestUtils.setField(service,
                                 "managedConnectorService",
                                 new ManagedConnectorService(mock(ManagedConnectorStorage.class),
                                                             mock(ConnectorCredentialsService.class),
                                                             userAcl));
    ReflectionTestUtils.setField(service, "caldavManagedModeService", caldavManagedModeService);
    ReflectionTestUtils.setField(service, "caldavConnectorStorage", caldavConnectorStorage);
    ReflectionTestUtils.setField(service, "caldavRelayService", caldavRelayService);
    ReflectionTestUtils.setField(service, "caldavServerService", caldavServerService);
    ReflectionTestUtils.setField(service, "identityManager", identityManager);
    service.setExecutor(Runnable::run);
    lenient().when(caldavServerService.canEdit(ADMIN)).thenReturn(true);
    named(41, "alice");
    named(42, "bob");
    named(43, "chloe");
  }

  @AfterEach
  void tearDown() {
    containerContext.close();
  }

  /** alice is identity 41, bob 42, chloe 43, all on the given server. */
  private void attachedByManagedMode(Long serverId, long... identities) {
    List<Long> all = new ArrayList<>();
    for (long identityId : identities) {
      all.add(identityId);
    }
    when(caldavConnectorStorage.getIdentitiesConnectedByManagedMode()).thenReturn(all);
    for (long identityId : identities) {
      CaldavUserSetting setting = new CaldavUserSetting();
      setting.setUsername("user" + identityId + "@bm.example.org");
      setting.setServerId(serverId);
      lenient().when(caldavConnectorStorage.getCaldavSetting(identityId)).thenReturn(setting);
      lenient().when(caldavConnectorStorage.isConnectedByManagedMode(identityId)).thenReturn(true);
    }
  }

  private static CaldavUserSetting settingOn(Long serverId) {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setServerId(serverId);
    return setting;
  }

  private void named(long identityId, String username) {
    Identity identity = new Identity(String.valueOf(identityId));
    identity.setRemoteId(username);
    lenient().when(identityManager.getIdentity(String.valueOf(identityId))).thenReturn(identity);
  }

  private void inForce(Long designation, String... excludedGroups) {
    when(caldavManagedModeService.getManagedServerId()).thenReturn(designation);
    lenient().when(caldavManagedModeService.getExcludedGroups()).thenReturn(List.of(excludedGroups));
  }

  private void groupsOf(String user, String... groups) {
    List<MembershipEntry> memberships = new ArrayList<>();
    for (String group : groups) {
      memberships.add(new MembershipEntry(group, "member"));
    }
    when(userAcl.getUserIdentity(user)).thenReturn(new org.exoplatform.services.security.Identity(user, memberships));
  }

  /** A becomes B: the users managed mode attached to A are disconnected. */
  @Test
  void anotherDesignatedServerDisconnectsTheUsersAttachedToTheFormerOne() {
    attachedByManagedMode(3L, 41, 42);
    inForce(5L);

    service.disconnectUsersNoLongerManaged();

    verify(caldavRelayService).disconnectForUser(41L, "alice");
    verify(caldavRelayService).disconnectForUser(42L, "bob");
  }

  /** Managed mode off: every user it attached is disconnected. */
  @Test
  void managedModeOffDisconnectsEveryUserItAttached() {
    attachedByManagedMode(3L, 41, 42);
    inForce(null);

    service.disconnectUsersNoLongerManaged();

    verify(caldavRelayService).disconnectForUser(41L, "alice");
    verify(caldavRelayService).disconnectForUser(42L, "bob");
  }

  /** A group excluded: only its members managed mode attached are disconnected. */
  @Test
  void aNewlyExcludedGroupDisconnectsOnlyItsMembers() {
    attachedByManagedMode(3L, 41, 42);
    inForce(3L, "/externals");
    groupsOf("alice", "/platform/users", "/externals");
    groupsOf("bob", "/platform/users");

    service.disconnectUsersNoLongerManaged();

    verify(caldavRelayService).disconnectForUser(41L, "alice");
    verify(caldavRelayService, never()).disconnectForUser(42L, "bob");
  }

  /**
   * A user whose identity cannot be resolved while exclusions apply - the identity cache
   * answers null on a directory failure - is skipped, not taken for excluded: deleting
   * their connection on that answer could not be undone.
   */
  @Test
  void aUserWhoseIdentityCannotBeResolvedIsNotTakenForExcluded() throws Exception {
    attachedByManagedMode(3L, 41, 42);
    inForce(3L, "/externals");
    groupsOf("alice", "/externals");
    when(userAcl.getUserIdentity("bob")).thenReturn(null);

    assertEquals(1, service.countUsersNoLongerManaged(3L, List.of("/externals"), ADMIN));
    service.disconnectUsersNoLongerManaged();

    verify(caldavRelayService).disconnectForUser(41L, "alice");
    verify(caldavRelayService, never()).disconnectForUser(42L, "bob");
  }

  /** A save that changes nothing disconnects nobody. */
  @Test
  void aSaveThatChangesNothingDisconnectsNobody() {
    attachedByManagedMode(3L, 41);
    inForce(3L);

    service.disconnectUsersNoLongerManaged();

    verify(caldavRelayService, never()).disconnectForUser(anyLong(), anyString());
  }

  /** The failure of one disconnection does not abandon the others. */
  @Test
  void aFailedDisconnectionDoesNotStopTheOthers() {
    attachedByManagedMode(3L, 41, 42);
    inForce(null);
    doThrow(new IllegalStateException("settings unavailable")).when(caldavRelayService).disconnectForUser(41L, "alice");

    service.disconnectUsersNoLongerManaged();

    verify(caldavRelayService).disconnectForUser(42L, "bob");
  }

  /** A checked exception thrown sneakily by one disconnection does not abandon the others either. */
  @Test
  void aCheckedFailureOfOneDisconnectionDoesNotStopTheOthers() {
    attachedByManagedMode(3L, 41, 42);
    inForce(null);
    doAnswer(invocation -> {
      throw new IOException("store unreachable");
    }).when(caldavRelayService).disconnectForUser(41L, "alice");

    service.disconnectUsersNoLongerManaged();

    verify(caldavRelayService).disconnectForUser(42L, "bob");
  }

  /** A checked exception thrown sneakily by one user's verdict skips that user only. */
  @Test
  void aCheckedFailureOfOneVerdictSkipsThatUserOnly() {
    attachedByManagedMode(3L, 41, 42);
    inForce(null);
    Identity alice = new Identity("41");
    alice.setRemoteId("alice");
    // The verdict's read fails; any later read would answer, so a user let through
    // would reach the disconnection.
    when(identityManager.getIdentity("41")).thenAnswer(invocation -> {
      throw new IOException("identity store unreachable");
    }).thenReturn(alice);

    service.disconnectUsersNoLongerManaged();

    verify(caldavRelayService, never()).disconnectForUser(eq(41L), any());
    verify(caldavRelayService).disconnectForUser(42L, "bob");
  }

  /**
   * A user whose verdict cannot be computed - an identity the platform cannot resolve -
   * is skipped: the others are still disconnected.
   */
  @Test
  void aUserWhoseVerdictFailsIsSkippedAndTheOthersProcessed() {
    attachedByManagedMode(3L, 41, 42);
    inForce(null);
    when(identityManager.getIdentity("41")).thenThrow(new IllegalStateException("identity store unavailable"));

    service.disconnectUsersNoLongerManaged();

    verify(caldavRelayService, never()).disconnectForUser(anyLong(), eq("alice"));
    verify(caldavRelayService).disconnectForUser(42L, "bob");
  }

  /** Counting the users of a server that does not exist is refused as such, not answered zero. */
  @Test
  void countingTheUsersOfAnUnknownServerIsNotFound() throws Exception {
    when(caldavServerService.getServerById(99L)).thenThrow(new ObjectNotFoundException("CalDAV server with id 99 doesn't exist"));

    assertThrows(ObjectNotFoundException.class, () -> service.countUsersOf(99L, ADMIN));
  }

  /** The selection and the disconnections run on the executor, not in the administrator's request. */
  @Test
  void theDisconnectionsRunInTheBackground() {
    service.setExecutor(queued::add);
    attachedByManagedMode(3L, 41);
    inForce(null);

    service.disconnectUsersNoLongerManaged();

    // Nothing is even read before the task runs: the request returns at once.
    verify(caldavConnectorStorage, never()).getIdentitiesConnectedByManagedMode();
    verify(caldavRelayService, never()).disconnectForUser(anyLong(), anyString());
    assertEquals(1, queued.size());
    queued.get(0).run();
    verify(caldavRelayService).disconnectForUser(41L, "alice");
  }

  /** The announced count is what the change then disconnects, and counting writes nothing. */
  @Test
  void theCountIsWhatTheChangeDisconnects() throws Exception {
    attachedByManagedMode(3L, 41, 42);
    groupsOf("alice", "/externals");
    groupsOf("bob", "/platform/users");

    assertEquals(1, service.countUsersNoLongerManaged(3L, List.of("/externals"), ADMIN));
    assertEquals(2, service.countUsersNoLongerManaged(null, List.of(), ADMIN));
    verifyNoInteractions(caldavRelayService);
  }

  /** Counting is an administration act. */
  @Test
  void onlyAnAdministratorMayCount() {
    when(caldavServerService.canEdit("mary")).thenReturn(false);

    assertThrows(IllegalAccessException.class, () -> service.countUsersNoLongerManaged(null, List.of(), "mary"));
    assertThrows(IllegalAccessException.class, () -> service.countUsersOf(3L, "mary"));
    verifyNoInteractions(caldavConnectorStorage);
  }

  /** A provider change disconnects every user of the server, whoever connected them. */
  @Test
  void aProviderChangeDisconnectsEveryUserOfTheServer() throws Exception {
    when(caldavServerService.getUserIdentitiesOfServer(3L)).thenReturn(List.of(41L, 43L));
    when(caldavConnectorStorage.getCaldavSetting(41L)).thenReturn(settingOn(3L));
    when(caldavConnectorStorage.getCaldavSetting(43L)).thenReturn(settingOn(3L));

    assertEquals(2, service.countUsersOf(3L, ADMIN));
    service.disconnectAllUsersOf(3L);

    verify(caldavRelayService).disconnectForUser(41L, "alice");
    verify(caldavRelayService).disconnectForUser(43L, "chloe");
  }

  /**
   * Managed mode turned off: alice was selected, then connected her own account before
   * her turn, which cleared the mark. Her connection is hers, and stays. Killed by the
   * mutant that drops the mark from isStillNoLongerManaged.
   */
  @Test
  void aUserWhoConnectedThemselvesDuringTheRunIsLeftConnected() {
    attachedByManagedMode(3L, 41, 42);
    inForce(null);
    when(caldavConnectorStorage.isConnectedByManagedMode(41L)).thenReturn(false);

    service.disconnectUsersNoLongerManaged();

    verify(caldavRelayService, never()).disconnectForUser(eq(41L), any());
    verify(caldavRelayService).disconnectForUser(42L, "bob");
  }

  /**
   * The designation moved to 7: alice, on 5, was selected, then logged in before her
   * turn and was attached to 7. That connection is the one managed mode now wants, and
   * stays. Killed by the mutant that drops the verdict from isStillNoLongerManaged.
   */
  @Test
  void aUserAttachedToTheDesignatedServerDuringTheRunIsLeftConnected() {
    attachedByManagedMode(5L, 41);
    inForce(7L);
    when(caldavConnectorStorage.getCaldavSetting(41L)).thenReturn(settingOn(5L), settingOn(7L));

    service.disconnectUsersNoLongerManaged();

    verify(caldavRelayService, never()).disconnectForUser(anyLong(), any());
  }

  /**
   * Managed mode was off when alice was selected, and designates her own server again
   * before her turn: the verdict is computed against the designation read again, and she
   * stays. Killed by the mutant that keeps the selection's designation.
   */
  @Test
  void theDesignationIsReadAgainWhenAUsersTurnComes() {
    attachedByManagedMode(3L, 41);
    when(caldavManagedModeService.getManagedServerId()).thenReturn(null, 3L);
    lenient().when(caldavManagedModeService.getExcludedGroups()).thenReturn(List.of());

    service.disconnectUsersNoLongerManaged();

    verify(caldavRelayService, never()).disconnectForUser(anyLong(), any());
  }

  /**
   * A provider change on 3: chloe moved to server 4 before her turn, and that connection
   * is not one the change touches. Killed by the mutant that drops isStillOn.
   */
  @Test
  void aUserWhoMovedToAnotherServerDuringAProviderChangeIsLeftConnected() {
    when(caldavServerService.getUserIdentitiesOfServer(3L)).thenReturn(List.of(41L, 43L));
    when(caldavConnectorStorage.getCaldavSetting(41L)).thenReturn(settingOn(3L));
    when(caldavConnectorStorage.getCaldavSetting(43L)).thenReturn(settingOn(4L));

    service.disconnectAllUsersOf(3L);

    verify(caldavRelayService).disconnectForUser(41L, "alice");
    verify(caldavRelayService, never()).disconnectForUser(eq(43L), any());
  }

  /**
   * A provider change on the seed registration 3: chloe's legacy account stores no server
   * id, reads the seed, and is selected among its users, so she is disconnected with
   * alice. Killed by the mutant that compares the stored server id alone.
   */
  @Test
  void aProviderChangeOnTheSeedServerDisconnectsItsLegacyAccounts() {
    seedIs(3L);
    when(caldavServerService.getUserIdentitiesOfServer(3L)).thenReturn(List.of(41L, 43L));
    when(caldavConnectorStorage.getCaldavSetting(41L)).thenReturn(settingOn(3L));
    when(caldavConnectorStorage.getCaldavSetting(43L)).thenReturn(legacyAccount("chloe@bm.example.org"));

    service.disconnectAllUsersOf(3L);

    verify(caldavRelayService).disconnectForUser(41L, "alice");
    verify(caldavRelayService).disconnectForUser(43L, "chloe");
  }

  /**
   * A provider change on 3 while the seed is 9: an account that stores no server id
   * reads 9, not 3, and stays. Killed by the mutant that drops the seed condition.
   */
  @Test
  void aLegacyAccountIsLeftConnectedWhenTheChangedServerIsNotTheSeed() {
    seedIs(9L);
    when(caldavServerService.getUserIdentitiesOfServer(3L)).thenReturn(List.of(41L, 43L));
    when(caldavConnectorStorage.getCaldavSetting(41L)).thenReturn(settingOn(3L));
    when(caldavConnectorStorage.getCaldavSetting(43L)).thenReturn(legacyAccount("chloe@bm.example.org"));

    service.disconnectAllUsersOf(3L);

    verify(caldavRelayService).disconnectForUser(41L, "alice");
    verify(caldavRelayService, never()).disconnectForUser(eq(43L), any());
  }

  /**
   * A provider change on the seed 3: chloe disconnected before her turn, so her setting
   * holds neither a login nor a server id and she is not on the seed any more. Killed by
   * the mutant that drops the login condition.
   */
  @Test
  void aLegacyAccountDisconnectedDuringTheRunIsLeftAlone() {
    seedIs(3L);
    when(caldavServerService.getUserIdentitiesOfServer(3L)).thenReturn(List.of(41L, 43L));
    when(caldavConnectorStorage.getCaldavSetting(41L)).thenReturn(settingOn(3L));
    when(caldavConnectorStorage.getCaldavSetting(43L)).thenReturn(legacyAccount(null));

    service.disconnectAllUsersOf(3L);

    verify(caldavRelayService).disconnectForUser(41L, "alice");
    verify(caldavRelayService, never()).disconnectForUser(eq(43L), any());
  }

  private void seedIs(long seedId) {
    CaldavServer seed = new CaldavServer();
    seed.setId(seedId);
    when(caldavServerService.resolveServer(null)).thenReturn(seed);
  }

  private static CaldavUserSetting legacyAccount(String username) {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername(username);
    return setting;
  }
}
