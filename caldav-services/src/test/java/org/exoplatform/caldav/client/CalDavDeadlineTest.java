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
package org.exoplatform.caldav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * The time left of the budget open on the thread, which a write channel that
 * does not send through {@link HttpCalDavClient} bounds its own calls by.
 */
class CalDavDeadlineTest {

  /**
   * None open: no bound. Open: at most the budget, the sooner end of two
   * nested ones, and the outer one again once the inner is closed. Run out:
   * nothing left, and the budget is spent.
   */
  @Test
  void theTimeLeftIsTheOpenBudgetsOnThisThreadOnly() {
    assertNull(CalDavDeadline.remaining(), "no budget open");
    try (CalDavDeadline outer = CalDavDeadline.start(Duration.ofSeconds(20))) {
      Duration left = CalDavDeadline.remaining();
      assertTrue(left.compareTo(Duration.ofSeconds(20)) <= 0 && left.compareTo(Duration.ofSeconds(19)) > 0, String.valueOf(left));
      try (CalDavDeadline inner = CalDavDeadline.start(Duration.ofSeconds(5))) {
        assertTrue(CalDavDeadline.remaining().compareTo(Duration.ofSeconds(5)) <= 0, "the sooner end");
      }
      assertTrue(CalDavDeadline.remaining().compareTo(Duration.ofSeconds(5)) > 0, "the outer budget again");
    }
    assertNull(CalDavDeadline.remaining(), "closed");

    try (CalDavDeadline spent = CalDavDeadline.start(Duration.ZERO)) {
      assertEquals(Duration.ZERO, CalDavDeadline.remaining());
      assertThrows(CalDavDeadlineException.class, () -> spent.checkNotSpent("the next step"));
    }
  }
}
