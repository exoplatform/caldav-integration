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

import org.junit.jupiter.api.Test;

/**
 * The "said once" memory behind the shared-collection INFO line: a key is
 * said once, and a server republishing collections at ever new paths cannot
 * grow it past its bound.
 */
public class CaldavSyncServiceSaidOnceTest {

  /**
   * A key is said the first time only.
   */
  @Test
  public void aKeyIsSaidOnce() {
    CaldavSyncService.SaidOnce said = new CaldavSyncService.SaidOnce();

    assertTrue(said.first("5:1:/dav/cal/bob/shared/"));
    assertFalse(said.first("5:1:/dav/cal/bob/shared/"));
  }

  /**
   * Past its bound it starts over rather than growing: the churn a server can
   * cause costs lines said again, never memory.
   */
  @Test
  public void newKeysPastTheBoundNeverGrowItBeyondTheBound() {
    CaldavSyncService.SaidOnce said = new CaldavSyncService.SaidOnce();

    for (int i = 0; i < CaldavSyncService.SaidOnce.MAX_KEYS * 3; i++) {
      said.first("5:1:/dav/cal/bob/republished-" + i + "/");
      assertTrue(said.size() <= CaldavSyncService.SaidOnce.MAX_KEYS, "size " + said.size() + " at " + i);
    }
    assertEquals(CaldavSyncService.SaidOnce.MAX_KEYS, said.size());
  }
}
