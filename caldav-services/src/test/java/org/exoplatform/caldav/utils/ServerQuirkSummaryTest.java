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
package org.exoplatform.caldav.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.exoplatform.caldav.model.ServerQuirkDirection;
import org.exoplatform.caldav.utils.ServerQuirkSummary.Observation;

/**
 * The rolling summary's format: what survives a write, and what a build that
 * cannot read an entry does with the rest of the row.
 */
public class ServerQuirkSummaryTest {

  @Test
  public void aSummaryReadsBackWhatItWrote() {
    Map<Observation, Long> observations = new LinkedHashMap<>();
    observations.put(Observation.of(ServerQuirkDirection.DROPPED, "CONFERENCE"), 399L);
    observations.put(Observation.of(ServerQuirkDirection.ADDED, "X-MOZ-GENERATION"), 41L);

    assertEquals(observations, ServerQuirkSummary.parse(ServerQuirkSummary.format(observations)));
  }

  @Test
  public void theLargestCountsAreWrittenFirst() {
    Map<Observation, Long> observations = new LinkedHashMap<>();
    observations.put(Observation.of(ServerQuirkDirection.ADDED, "X-MOZ-GENERATION"), 41L);
    observations.put(Observation.of(ServerQuirkDirection.DROPPED, "CONFERENCE"), 399L);

    assertEquals("DROPPED:CONFERENCE=399;ADDED:X-MOZ-GENERATION=41", ServerQuirkSummary.format(observations));
  }

  @Test
  public void aPropertyNameIsStoredInOneSpellingOnly() {
    // Two spellings of one property must not become two entries an
    // administrator has to tick twice.
    assertEquals(Observation.of(ServerQuirkDirection.ADDED, "x-bm-foo"),
                 Observation.of(ServerQuirkDirection.ADDED, " X-BM-FOO "));
  }

  @Test
  public void aSummaryCannotGrowWithoutLimit() {
    // A column is not a log: a server minting a new proprietary property on
    // every copy must not be able to grow the row.
    Map<Observation, Long> observations = new LinkedHashMap<>();
    for (int index = 0; index < ServerQuirkSummary.MAX_ENTRIES * 3; index++) {
      observations.put(Observation.of(ServerQuirkDirection.ADDED, "X-NOISE-" + index), (long) index + 1);
    }

    Map<Observation, Long> stored = ServerQuirkSummary.parse(ServerQuirkSummary.format(observations));

    assertEquals(ServerQuirkSummary.MAX_ENTRIES, stored.size());
    assertTrue(stored.containsKey(Observation.of(ServerQuirkDirection.ADDED,
                                                 "X-NOISE-" + (ServerQuirkSummary.MAX_ENTRIES * 3 - 1))),
               "and what survives is what happens most, not what happened first");
  }

  @Test
  public void anEntryThisBuildCannotReadCostsOnlyThatEntry() {
    // The row may have been written by another build. A summary that cannot be
    // read must leave the drawer showing less, never leave the registration
    // unreadable.
    Map<Observation, Long> stored = ServerQuirkSummary.parse("SIDEWAYS:X-NEW=3;DROPPED:CONFERENCE=7;garbage;ADDED:X=nope");

    assertEquals(Map.of(Observation.of(ServerQuirkDirection.DROPPED, "CONFERENCE"), 7L), stored);
  }

  @Test
  public void nothingObservedStoresNothing() {
    assertNull(ServerQuirkSummary.format(Map.of()));
    assertNull(ServerQuirkSummary.format(null));
    assertTrue(ServerQuirkSummary.parse(null).isEmpty());
    assertTrue(ServerQuirkSummary.parse("  ").isEmpty());
  }
}
