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
package org.exoplatform.caldav.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import org.exoplatform.caldav.dao.CaldavConnectionDAO;
import org.exoplatform.caldav.entity.CaldavConnectionEntity;
import org.exoplatform.caldav.model.CalendarSyncStatus;

/**
 * The mechanical guarantees of the connection-identity storage
 * (EXO-90243): a recording that changes nothing writes nothing, a recording
 * that changes something replaces the user's one row, and "connected as this
 * principal" is exact whatever the database collation calls equal.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavConnectionStorageTest {

  private static final long   ALICE     = 5L;

  private static final long   STALWART  = 1L;

  private static final String PRINCIPAL = "/dav/pal/alice@stalwart.local";

  @Mock
  private CaldavConnectionDAO connectionDAO;

  @InjectMocks
  private CaldavConnectionStorage storage;

  /**
   * The first recording inserts the row, with every field set.
   */
  @Test
  public void aFirstRecordingInsertsTheRow() {
    when(connectionDAO.findByUserIdentityId(ALICE)).thenReturn(Optional.empty());

    assertTrue(storage.savePrincipal(ALICE, STALWART, PRINCIPAL));

    ArgumentCaptor<CaldavConnectionEntity> saved = ArgumentCaptor.forClass(CaldavConnectionEntity.class);
    verify(connectionDAO).save(saved.capture());
    assertNull(saved.getValue().getId());
    assertEquals(ALICE, saved.getValue().getUserIdentityId());
    assertEquals(STALWART, saved.getValue().getServerId());
    assertEquals(PRINCIPAL, saved.getValue().getPrincipal());
  }

  /**
   * <b>A pass that finds the account unchanged writes nothing.</b> Recording
   * runs on every discovery; a write per pass per user would be the cost of
   * saying the same thing every five minutes.
   */
  @Test
  public void aRecordingThatChangesNothingWritesNothing() {
    when(connectionDAO.findByUserIdentityId(ALICE)).thenReturn(Optional.of(row(11L, ALICE, STALWART, PRINCIPAL)));

    assertFalse(storage.savePrincipal(ALICE, STALWART, PRINCIPAL));

    verify(connectionDAO, never()).save(any());
  }

  /**
   * Another principal, or another server, replaces the user's one row rather
   * than adding a second.
   */
  @Test
  public void aChangedPrincipalOrServerReplacesTheUsersRow() {
    when(connectionDAO.findByUserIdentityId(ALICE)).thenReturn(Optional.of(row(11L, ALICE, STALWART, PRINCIPAL)));
    assertTrue(storage.savePrincipal(ALICE, STALWART, "/dav/pal/alice2@stalwart.local"));

    when(connectionDAO.findByUserIdentityId(ALICE)).thenReturn(Optional.of(row(11L, ALICE, STALWART, PRINCIPAL)));
    assertTrue(storage.savePrincipal(ALICE, 2L, PRINCIPAL));

    ArgumentCaptor<CaldavConnectionEntity> saved = ArgumentCaptor.forClass(CaldavConnectionEntity.class);
    verify(connectionDAO, org.mockito.Mockito.times(2)).save(saved.capture());
    assertEquals(11L, saved.getAllValues().get(0).getId());
    assertEquals("/dav/pal/alice2@stalwart.local", saved.getAllValues().get(0).getPrincipal());
    assertEquals(11L, saved.getAllValues().get(1).getId());
    assertEquals(2L, saved.getAllValues().get(1).getServerId());
  }

  /**
   * Forgetting a user is handed to the DAO for that user.
   */
  @Test
  public void forgettingAUserDeletesTheirRow() {
    storage.deletePrincipal(ALICE);

    verify(connectionDAO).deleteByUserIdentityId(ALICE);
  }

  /**
   * <b>Exactly this principal.</b> The database answers the candidates, and
   * on MySQL's accent- and case-insensitive collation those include a
   * principal that merely looks alike; only the exact one counts. The answer
   * is bounded by its page.
   */
  @Test
  public void onlyTheExactPrincipalCountsWhateverTheCollationMatched() {
    when(connectionDAO.findByServerAndPrincipal(eq(STALWART), eq(PRINCIPAL), any(Pageable.class)))
                                                                                                .thenReturn(List.of(row(1L, ALICE, STALWART, PRINCIPAL),
                                                                                                                    row(2L, 7L, STALWART, "/dav/pal/ALICE@stalwart.local"),
                                                                                                                    row(3L, 8L, STALWART, "/dav/pal/alicé@stalwart.local"),
                                                                                                                    row(4L, 6L, STALWART, PRINCIPAL)));

    assertEquals(List.of(ALICE, 6L), storage.getUsersConnectedAs(STALWART, PRINCIPAL));

    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    verify(connectionDAO).findByServerAndPrincipal(eq(STALWART), eq(PRINCIPAL), page.capture());
    assertEquals(CaldavConnectionStorage.CONNECTED_USERS_READ, page.getValue().getPageSize());
    assertEquals(0, page.getValue().getPageNumber());
  }

  /**
   * The users missing an identity are counted among the active pairs of the
   * server, the state whose discoveries succeed.
   */
  @Test
  public void theUsersMissingAnIdentityAreCountedAmongActivePairs() {
    when(connectionDAO.countUsersWithPairsButNoIdentity(STALWART, CalendarSyncStatus.ACTIVE)).thenReturn(2L);

    assertEquals(2L, storage.countActiveUsersWithoutIdentity(STALWART));
  }

  /**
   * A blank principal names nobody and asks the database nothing.
   */
  @Test
  public void aBlankPrincipalAsksNobody() {
    assertTrue(storage.getUsersConnectedAs(STALWART, " ").isEmpty());
    assertTrue(storage.getUsersConnectedAs(STALWART, null).isEmpty());

    verify(connectionDAO, never()).findByServerAndPrincipal(anyLong(), anyString(), any());
  }

  /**
   * The principal a sharee is named by is the one recorded for that server
   * (EXO-90253): a row recorded for another server says nothing about who
   * the user is on this one, and no row names nobody.
   */
  @Test
  public void aRecordedPrincipalIsTheOneOfThatServer() {
    when(connectionDAO.findByUserIdentityId(ALICE)).thenReturn(Optional.of(row(11L, ALICE, STALWART, PRINCIPAL)));
    when(connectionDAO.findByUserIdentityId(9L)).thenReturn(Optional.empty());

    assertEquals(PRINCIPAL, storage.getRecordedPrincipal(ALICE, STALWART));
    assertNull(storage.getRecordedPrincipal(ALICE, 2L), "recorded for another server");
    assertNull(storage.getRecordedPrincipal(9L, STALWART), "never recorded");
  }

  /**
   * The connections of a server are read in the DAO's order, bounded by the
   * storage's page, keyed by user (EXO-90253).
   */
  @Test
  public void theConnectionsOfAServerAreReadBoundedAndInOrder() {
    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    when(connectionDAO.findByServer(eq(STALWART), page.capture())).thenReturn(List.of(row(11L, ALICE, STALWART, PRINCIPAL),
                                                                                      row(12L, 6L, STALWART, PRINCIPAL),
                                                                                      row(13L, 9L, STALWART, "/dav/pal/bob@stalwart.local")));

    assertEquals(List.of(ALICE, 6L, 9L), List.copyOf(storage.getPrincipalsOn(STALWART).keySet()));
    assertEquals("/dav/pal/bob@stalwart.local", storage.getPrincipalsOn(STALWART).get(9L));
    assertEquals(CaldavConnectionStorage.CONNECTIONS_ON_SERVER_READ, page.getValue().getPageSize());
  }

  /**
   * @param id the row id
   * @param user the eXo user
   * @param server the server key
   * @param principal the recorded principal
   * @return a stored row
   */
  private static CaldavConnectionEntity row(long id, long user, long server, String principal) {
    return new CaldavConnectionEntity(id, user, server, principal);
  }
}
