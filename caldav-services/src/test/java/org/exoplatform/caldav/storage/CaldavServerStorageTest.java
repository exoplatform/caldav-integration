/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.caldav.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.dao.CaldavServerDAO;
import org.exoplatform.caldav.entity.CaldavServerEntity;
import org.exoplatform.caldav.model.CaldavServer;

/**
 * The provider name is the join key everything hangs from — agenda's
 * REMOTE_PROVIDER row, the user's connected-provider binding — so what this
 * storage must guarantee is that every created row ends up with its own
 * derived name (prefix.id, unique because ids are), that the seed keeps the
 * fixed legacy name, and that an update can move everything EXCEPT that name.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavServerStorageTest {

  private static final String PREFIX = "agenda.caldavCalendar";

  @Mock
  private CaldavServerDAO     caldavServerDAO;

  @InjectMocks
  private CaldavServerStorage caldavServerStorage;

  private AtomicLong          nextId;

  /**
   * Makes the mocked DAO behave like a database for ids: the first save of an
   * id-less entity assigns the next id, every save returns its argument.
   */
  @BeforeEach
  public void setUp() {
    nextId = new AtomicLong(4);
    lenient().when(caldavServerDAO.save(any(CaldavServerEntity.class))).thenAnswer(invocation -> {
      CaldavServerEntity entity = invocation.getArgument(0);
      if (entity.getId() == null) {
        entity.setId(nextId.incrementAndGet());
      }
      return entity;
    });
  }

  /**
   * Two created rows derive two distinct provider names, each being exactly
   * prefix.id of its own row — the uniqueness the DB index then enforces is
   * structural here, not accidental.
   */
  @Test
  public void shouldDeriveDistinctProviderNamesFromRowIds() {
    CaldavServer first = caldavServerStorage.createServer(new CaldavServer(0, null, "One", null, "https://one/", true),
                                                          PREFIX);
    CaldavServer second = caldavServerStorage.createServer(new CaldavServer(0, null, "Two", null, "https://two/", true),
                                                           PREFIX);

    assertEquals(PREFIX + "." + first.getId(), first.getProviderName());
    assertEquals(PREFIX + "." + second.getId(), second.getProviderName());
    assertNotEquals(first.getProviderName(), second.getProviderName());
  }

  /**
   * The seed row keeps the FIXED legacy provider name, undecorated: accounts
   * connected before the registry existed resolve through that exact name.
   */
  @Test
  public void shouldKeepFixedProviderNameOnSeed() {
    CaldavServer seed = caldavServerStorage.createSeedServer(new CaldavServer(0, null, "CalDAV", null, "https://seed/", true),
                                                             PREFIX);

    assertEquals(PREFIX, seed.getProviderName());
  }

  /**
   * An update may move the name, description, URL and activation — never the
   * provider name, even when the caller sends one.
   */
  @Test
  public void shouldUpdateEverythingButTheProviderName() {
    CaldavServerEntity existing = new CaldavServerEntity(7L, PREFIX + ".7", "Old", null, "https://old/", true);
    when(caldavServerDAO.findById(7L)).thenReturn(Optional.of(existing));

    CaldavServer updated = caldavServerStorage.updateServer(new CaldavServer(7, "hijacked.name", "New", "desc",
                                                                             "https://new/", false));

    assertEquals(PREFIX + ".7", updated.getProviderName());
    assertEquals("New", updated.getName());
    assertEquals("https://new/", updated.getServerUrl());
    assertEquals(false, updated.isActive());
  }

  /**
   * Updating a row that does not exist answers null — the service turns that
   * into the 404.
   */
  @Test
  public void shouldAnswerNullOnUpdateOfMissingRow() {
    when(caldavServerDAO.findById(99L)).thenReturn(Optional.empty());

    assertNull(caldavServerStorage.updateServer(new CaldavServer(99, null, "New", null, "https://new/", true)));
  }
}
