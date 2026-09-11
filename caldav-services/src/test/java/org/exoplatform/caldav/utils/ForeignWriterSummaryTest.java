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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The format one server's row holds its foreign deployments in (EXO-89824).
 *
 * <p>
 * Tested as a format rather than through the storage, because every way it can
 * go wrong is a string: a name that cannot survive the round trip, a day that
 * ages an entry out from under a live condition, a bound that drops the writer
 * still writing instead of the one that stopped.
 */
public class ForeignWriterSummaryTest {

  /** A day far enough from the epoch to read like a real one. */
  private static final long TODAY = 20342;

  /**
   * What is written is what is read back.
   */
  @Test
  public void whatIsWrittenIsReadBack() {
    Map<String, Long> recorded = ForeignWriterSummary.record(Map.of(), "acceptance.example.test", TODAY, 30);
    String stored = ForeignWriterSummary.format(recorded);

    assertEquals("acceptance.example.test@" + TODAY, stored);
    assertEquals(Map.of("acceptance.example.test", TODAY), ForeignWriterSummary.parse(stored));
  }

  /**
   * A host and port survive, because that is the granularity two deployments
   * differ at — a rig and an acceptance server on one host, different ports.
   */
  @Test
  public void aPortIsPartOfTheName() {
    String stored = ForeignWriterSummary.format(ForeignWriterSummary.record(Map.of(), "host:8080", TODAY, 30));

    assertEquals(Map.of("host:8080", TODAY), ForeignWriterSummary.parse(stored));
  }

  /**
   * A deployment seen again today is refreshed rather than added twice — the
   * entry has to keep moving or it ages out from under a condition that still
   * holds.
   */
  @Test
  public void aDeploymentSeenAgainIsRefreshed() {
    Map<String, Long> stored = Map.of("acceptance.example.test", TODAY - 10);

    Map<String, Long> recorded = ForeignWriterSummary.record(stored, "acceptance.example.test", TODAY, 30);

    assertEquals(1, recorded.size());
    assertEquals(TODAY, recorded.get("acceptance.example.test"));
  }

  /**
   * A deployment nothing has seen for longer than the window is forgotten, so
   * an administrator who moved one of the two deployments to its own account
   * gets their drawer back without clearing anything.
   */
  @Test
  public void aDeploymentNobodyHasSeenForAMonthIsForgotten() {
    Map<String, Long> stored = Map.of("gone.example.test", TODAY - 31, "here.example.test", TODAY - 29);

    Map<String, Long> recorded = ForeignWriterSummary.record(stored, null, TODAY, 30);

    assertEquals(Map.of("here.example.test", TODAY - 29), recorded);
  }

  /**
   * The bound keeps the deployments still writing, not the ones that stopped.
   */
  @Test
  public void theBoundDropsTheLeastRecentlySeen() {
    Map<String, Long> stored = Map.of("a.test", TODAY - 5,
                                      "b.test", TODAY - 4,
                                      "c.test", TODAY - 3,
                                      "d.test", TODAY - 2,
                                      "e.test", TODAY - 1);

    Map<String, Long> recorded = ForeignWriterSummary.record(stored, "f.test", TODAY, 30);

    assertEquals(5, recorded.size());
    assertEquals(List.of("f.test", "e.test", "d.test", "c.test", "b.test"), List.copyOf(recorded.keySet()));
  }

  /**
   * A name carrying one of the separators is refused rather than escaped: the
   * value comes from a URL in somebody else's calendar, and a name that cannot
   * survive the round trip is better left out than stored wrong.
   */
  @Test
  public void aNameThatCouldNotBeReadBackIsNotStored() {
    assertNull(ForeignWriterSummary.format(ForeignWriterSummary.record(Map.of(), "user:pass@host", TODAY, 30)));
    assertNull(ForeignWriterSummary.format(ForeignWriterSummary.record(Map.of(), "a;b", TODAY, 30)));
    assertNull(ForeignWriterSummary.format(ForeignWriterSummary.record(Map.of(), "  ", TODAY, 30)));
  }

  /**
   * A row a hand edit or an older version left unreadable costs an entry, never
   * the drawer.
   */
  @Test
  public void aMalformedRowCostsAnEntryAndNothingElse() {
    Map<String, Long> parsed = ForeignWriterSummary.parse("good.test@" + TODAY + ";nodayhere;bad.test@notanumber;@" + TODAY);

    assertEquals(Map.of("good.test", TODAY), parsed);
    assertTrue(ForeignWriterSummary.parse(null).isEmpty());
    assertTrue(ForeignWriterSummary.parse("  ").isEmpty());
  }

  /**
   * Nothing to say reads as null, so a server that has never seen a foreign
   * deployment and one whose last entry has just aged out hold the same value.
   */
  @Test
  public void nothingToSayIsNull() {
    assertNull(ForeignWriterSummary.format(Map.of()));
    assertNull(ForeignWriterSummary.format(null));
    assertNull(ForeignWriterSummary.format(ForeignWriterSummary.record(Map.of("old.test", TODAY - 40), null, TODAY, 30)));
  }
}
