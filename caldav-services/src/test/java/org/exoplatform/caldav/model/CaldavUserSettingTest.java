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

public class CaldavUserSettingTest {

  @Test
  public void aSettingNeverPrintsItsPassword() {
    // EXO-89650. The generated toString would print the user's own password, in
    // the clear, into any log line or exception message the setting reaches.
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("john@example.invalid");
    setting.setPassword("s3cr3t-personal");

    assertFalse(setting.toString().contains("s3cr3t-personal"), setting.toString());
    assertTrue(setting.toString().contains("john@example.invalid"), setting.toString());
  }
}
