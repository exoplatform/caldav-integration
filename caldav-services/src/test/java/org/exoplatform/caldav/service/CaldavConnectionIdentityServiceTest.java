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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.entity.CaldavConnectionEntity;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectionStorage;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;

/**
 * Who each connected user is on their server, and who is believed to be
 * connected as a principal (EXO-90243), on the rig's accounts: alice (5) and
 * alice2 (6) on one Stalwart login, bob (9) on his own.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavConnectionIdentityServiceTest {

  private static final long       ALICE           = 5L;

  private static final long       ALICE2          = 6L;

  private static final long       BOB             = 9L;

  private static final long       STALWART        = 1L;

  /** Alice's principal as Stalwart answers it: login encoded, trailing slash. */
  private static final String     ALICE_ANSWERED  = "/dav/pal/alice%40stalwart.local/";

  /** The same principal as it is recorded and compared. */
  private static final String     ALICE_PRINCIPAL = "/dav/pal/alice@stalwart.local";

  @Mock
  private CaldavConnectionStorage caldavConnectionStorage;

  @Mock
  private CaldavConnectorStorage  caldavConnectorStorage;

  @InjectMocks
  private CaldavConnectionIdentityService service;

  // ------------------------------------ the canonical form

  /**
   * A principal is recorded in the form an owner is compared in: decoded,
   * no trailing slash, and a {@code +} left a plus.
   */
  @Test
  public void aPrincipalIsCanonicalAsAnOwnerIsCompared() {
    assertEquals(ALICE_PRINCIPAL, CaldavConnectionIdentityService.canonicalPrincipal(ALICE_ANSWERED));
    assertEquals(ALICE_PRINCIPAL, CaldavConnectionIdentityService.canonicalPrincipal(ALICE_PRINCIPAL + "/"));
    assertEquals("/dav/pal/alice+work@stalwart.local",
                 CaldavConnectionIdentityService.canonicalPrincipal("/dav/pal/alice+work%40stalwart.local/"),
                 "a plus in a login is a plus, not a space");
    assertNull(CaldavConnectionIdentityService.canonicalPrincipal(null));
    assertNull(CaldavConnectionIdentityService.canonicalPrincipal("  "));
    assertNull(CaldavConnectionIdentityService.canonicalPrincipal("/"));
  }

  // ------------------------------------ recording

  /**
   * A discovery's principal is recorded canonical, under the server key.
   */
  @Test
  public void aDiscoveredPrincipalIsRecordedCanonical() {
    service.recordPrincipal(ALICE, STALWART, ALICE_ANSWERED);

    verify(caldavConnectionStorage).savePrincipal(ALICE, STALWART, ALICE_PRINCIPAL);
    verify(caldavConnectionStorage, never()).deletePrincipal(anyLong());
  }

  /**
   * A server that named no principal leaves the account unknown: whatever was
   * recorded before is removed rather than left to describe it.
   */
  @Test
  public void aDiscoveryThatNamedNoPrincipalLeavesTheAccountUnknown() {
    service.recordPrincipal(ALICE, STALWART, null);

    verify(caldavConnectionStorage).deletePrincipal(ALICE);
    verify(caldavConnectionStorage, never()).savePrincipal(anyLong(), anyLong(), anyString());
  }

  /**
   * A principal too long for the column is never truncated into somebody
   * else's: it is not recorded, and the previous row goes. One that just fits
   * is recorded.
   */
  @Test
  public void aPrincipalTooLongForTheColumnIsNotRecordedAndNeverTruncated() {
    String fits = "/" + "a".repeat(CaldavConnectionEntity.PRINCIPAL_MAX_LENGTH - 1);
    String tooLong = fits + "b";

    service.recordPrincipal(ALICE, STALWART, tooLong);
    verify(caldavConnectionStorage).deletePrincipal(ALICE);
    verify(caldavConnectionStorage, never()).savePrincipal(anyLong(), anyLong(), anyString());

    service.recordPrincipal(ALICE, STALWART, fits);
    verify(caldavConnectionStorage).savePrincipal(ALICE, STALWART, fits);
  }

  /**
   * A principal carrying a character outside the Basic Multilingual Plane is
   * not recorded, and not looked up: MySQL's utf8mb3 column would refuse it on
   * every pass or, out of strict mode, cut it down into another value.
   */
  @Test
  public void aPrincipalOutsideTheBasicMultilingualPlaneIsNeitherRecordedNorLookedUp() {
    String supplementary = "/dav/pal/alice\uD83D\uDE00@stalwart.local/";

    service.recordPrincipal(ALICE, STALWART, supplementary);
    assertTrue(service.usersConnectedAs(STALWART, supplementary).isEmpty());

    verify(caldavConnectionStorage).deletePrincipal(ALICE);
    verify(caldavConnectionStorage, never()).savePrincipal(anyLong(), anyLong(), anyString());
    verify(caldavConnectionStorage, never()).getUsersConnectedAs(anyLong(), anyString());
  }

  /**
   * Two nodes recording one user at once: the second meets the unique index,
   * and its recording lands on the row the first wrote.
   */
  @Test
  public void aConcurrentRecordingOfTheSameUserLandsOnTheRowItCollidedWith() {
    when(caldavConnectionStorage.savePrincipal(ALICE, STALWART, ALICE_PRINCIPAL))
                                                                               .thenThrow(new IllegalStateException("insert refused",
                                                                                                                    new SQLIntegrityConstraintViolationException("UQ_CALDAV_CONNECTION_USER",
                                                                                                                                                                 "23505")))
                                                                               .thenReturn(false);

    service.recordPrincipal(ALICE, STALWART, ALICE_ANSWERED);

    verify(caldavConnectionStorage, times(2)).savePrincipal(ALICE, STALWART, ALICE_PRINCIPAL);
  }

  /**
   * Any other failure is absorbed, once: a pass must not fail over its
   * account's identity, and only a duplicate key is worth a second attempt.
   */
  @Test
  public void anyOtherFailureToRecordIsAbsorbedAndNotRetried() {
    when(caldavConnectionStorage.savePrincipal(ALICE, STALWART, ALICE_PRINCIPAL)).thenThrow(new IllegalStateException("database down"));

    assertDoesNotThrow(() -> service.recordPrincipal(ALICE, STALWART, ALICE_ANSWERED));

    verify(caldavConnectionStorage, times(1)).savePrincipal(ALICE, STALWART, ALICE_PRINCIPAL);
  }

  /**
   * Forgetting removes the row, and a failure to do so is absorbed: connect
   * and disconnect must succeed whatever happens here.
   */
  @Test
  public void forgettingRemovesTheRowAndAbsorbsItsFailure() {
    service.forgetPrincipal(ALICE);
    verify(caldavConnectionStorage).deletePrincipal(ALICE);

    doThrow(new IllegalStateException("database down")).when(caldavConnectionStorage).deletePrincipal(BOB);
    assertDoesNotThrow(() -> service.forgetPrincipal(BOB));
  }

  // ------------------------------------ believing

  /**
   * <b>Only a user still connected to that server is believed.</b> alice is;
   * alice2 disconnected (her settings hold no password any more) and her row
   * was left behind; carol's settings now name another server. A row is
   * forgotten on disconnect through the kernel bridge, which can fail, and a
   * row left behind must not name somebody who is no longer there.
   */
  @Test
  public void onlyUsersStillConnectedToThatServerAreBelieved() {
    long carol = 10L;
    when(caldavConnectionStorage.getUsersConnectedAs(STALWART, ALICE_PRINCIPAL)).thenReturn(List.of(ALICE, ALICE2, carol));
    when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(connected(STALWART));
    CaldavUserSetting disconnected = connected(STALWART);
    disconnected.setPassword(null);
    when(caldavConnectorStorage.getCaldavSetting(ALICE2)).thenReturn(disconnected);
    when(caldavConnectorStorage.getCaldavSetting(carol)).thenReturn(connected(2L));

    assertEquals(List.of(ALICE), service.usersConnectedAs(STALWART, ALICE_ANSWERED));
  }

  /**
   * An account attached before registrations existed is keyed zero, the way
   * its pairs are.
   */
  @Test
  public void aLegacyAccountIsBelievedUnderServerZero() {
    when(caldavConnectionStorage.getUsersConnectedAs(0L, ALICE_PRINCIPAL)).thenReturn(List.of(ALICE));
    when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(connected(null));

    assertEquals(List.of(ALICE), service.usersConnectedAs(0L, ALICE_ANSWERED));
  }

  /**
   * The rig's shared account: alice asking names alice2, and the user asking
   * is left out before anything is read about them.
   */
  @Test
  public void theOtherUsersOfAnAccountLeaveTheAskerOut() {
    when(caldavConnectionStorage.getUsersConnectedAs(STALWART, ALICE_PRINCIPAL)).thenReturn(List.of(ALICE, ALICE2));
    when(caldavConnectorStorage.getCaldavSetting(ALICE2)).thenReturn(connected(STALWART));

    assertEquals(List.of(ALICE2), service.otherUsersConnectedAs(ALICE, STALWART, ALICE_ANSWERED));

    verify(caldavConnectorStorage, never()).getCaldavSetting(ALICE);
  }

  /**
   * The users missing on a server are counted among those holding an active
   * pair there: none once every one is recorded, one — alice2, before her
   * first pass — while she is not.
   */
  @Test
  public void theUsersMissingOnAServerAreCountedAmongItsActiveUsers() {
    when(caldavConnectionStorage.countActiveUsersWithoutIdentity(STALWART)).thenReturn(0L, 1L);

    assertEquals(0L, service.activeUsersWithoutIdentityOn(STALWART));
    assertEquals(1L, service.activeUsersWithoutIdentityOn(STALWART));
  }

  /**
   * A principal that cannot be recorded cannot be looked up either, and asks
   * nothing.
   */
  @Test
  public void aPrincipalThatCannotBeRecordedNamesNobody() {
    assertTrue(service.usersConnectedAs(STALWART, null).isEmpty());
    assertTrue(service.usersConnectedAs(STALWART, "/" + "a".repeat(CaldavConnectionEntity.PRINCIPAL_MAX_LENGTH)).isEmpty());

    verify(caldavConnectionStorage, never()).getUsersConnectedAs(anyLong(), anyString());
  }

  /**
   * @param serverId the registration the settings name, null for a legacy
   *          account
   * @return a connected account's settings
   */
  private static CaldavUserSetting connected(Long serverId) {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("alice@stalwart.local");
    setting.setPassword("secret");
    setting.setServerId(serverId);
    return setting;
  }
}
