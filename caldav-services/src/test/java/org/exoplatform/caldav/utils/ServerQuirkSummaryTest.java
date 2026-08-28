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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.exoplatform.caldav.model.ServerQuirkDirection;
import org.exoplatform.caldav.utils.ServerQuirkSummary.Observation;
import org.exoplatform.caldav.utils.ServerQuirkSummary.Tally;

/**
 * The rolling summary's format: what survives a write, and what a build that
 * cannot read an entry does with the rest of the row.
 */
public class ServerQuirkSummaryTest {

  @Test
  public void aSummaryReadsBackWhatItWrote() {
    Map<Observation, Tally> observations = new LinkedHashMap<>();
    observations.put(Observation.of(ServerQuirkDirection.DROPPED, "CONFERENCE"), new Tally(399L, 20329L));
    observations.put(Observation.of(ServerQuirkDirection.ADDED, "X-MOZ-GENERATION"), new Tally(41L, 20330L));

    assertEquals(observations, ServerQuirkSummary.parse(ServerQuirkSummary.format(observations)));
  }

  @Test
  public void anEntryWrittenBeforeStampsExistedKeepsItsCountAndNoDate() {
    // The upgrade path. Dropping somebody's history to enforce a rule that did
    // not apply when it was recorded would be the wrong bias, so an unstamped
    // entry parses, says it has no date, and is never stale until one is given.
    Map<Observation, Tally> stored = ServerQuirkSummary.parse("DROPPED:CONFERENCE=399");

    Tally tally = stored.get(Observation.of(ServerQuirkDirection.DROPPED, "CONFERENCE"));
    assertEquals(399L, tally.count());
    assertEquals(Tally.UNKNOWN_DAY, tally.lastSeenDay());
    assertFalse(tally.staleOn(99999L, 30L), "an entry with no date is never stale");
    assertEquals(20400L, tally.stamped(20400L).lastSeenDay(), "the next write gives it one");
  }

  @Test
  public void anEntryGoesStaleOnlyAfterTheWindowHasPassed() {
    Tally tally = new Tally(4L, 20300L);

    assertFalse(tally.staleOn(20330L, 30L), "the last day of the window is still inside it");
    assertTrue(tally.staleOn(20331L, 30L));
  }

  @Test
  public void beingSeenAgainAddsToTheCountAndMovesTheDate() {
    assertEquals(new Tally(7L, 20400L), new Tally(4L, 20300L).seen(3L, 20400L));
  }

  @Test
  public void theLargestCountsAreWrittenFirst() {
    Map<Observation, Tally> observations = new LinkedHashMap<>();
    observations.put(Observation.of(ServerQuirkDirection.ADDED, "X-MOZ-GENERATION"), new Tally(41L, 20330L));
    observations.put(Observation.of(ServerQuirkDirection.DROPPED, "CONFERENCE"), new Tally(399L, 20329L));

    assertEquals("DROPPED:CONFERENCE=399@20329;ADDED:X-MOZ-GENERATION=41@20330", ServerQuirkSummary.format(observations));
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
    Map<Observation, Tally> observations = new LinkedHashMap<>();
    for (int index = 0; index < ServerQuirkSummary.MAX_ENTRIES * 3; index++) {
      observations.put(Observation.of(ServerQuirkDirection.ADDED, "X-NOISE-" + index), new Tally(index + 1L, 20330L));
    }

    Map<Observation, Tally> stored = ServerQuirkSummary.parse(ServerQuirkSummary.format(observations));

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
    Map<Observation, Tally> stored = ServerQuirkSummary.parse("SIDEWAYS:X-NEW=3@20330;DROPPED:CONFERENCE=7@20330;garbage;"
        + "ADDED:X=nope");

    assertEquals(Map.of(Observation.of(ServerQuirkDirection.DROPPED, "CONFERENCE"), new Tally(7L, 20330L)), stored);
  }

  @Test
  public void nothingObservedStoresNothing() {
    assertNull(ServerQuirkSummary.format(Map.of()));
    assertNull(ServerQuirkSummary.format(null));
    assertTrue(ServerQuirkSummary.parse(null).isEmpty());
    assertTrue(ServerQuirkSummary.parse("  ").isEmpty());
  }
}
