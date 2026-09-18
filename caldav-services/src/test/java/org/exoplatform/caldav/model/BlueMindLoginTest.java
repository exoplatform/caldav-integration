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
package org.exoplatform.caldav.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlueMindLoginTest {

  /**
   * A login logged or concatenated by mistake names who the session belongs
   * to and never its key: the key opens the account until it expires.
   */
  @Test
  void itsTextNamesTheUserAndNeverTheKey() {
    String text = String.valueOf(new BlueMindLogin("https://bm.example.com", "bm-session-secret-key", "9F3C1A20", "bm.example.com"));

    assertFalse(text.contains("bm-session-secret-key"), text);
    assertTrue(text.contains("9F3C1A20") && text.contains("bm.example.com"), text);
  }
}
