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
package org.exoplatform.caldav.ics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Removing one occurrence from a series.
 *
 * <p>
 * The trap this guards is specific and was a live defect once: RFC 5545
 * matches instances by <i>identical value</i>, so an EXDATE written as a
 * date-time against a date-valued series matches nothing, and the occurrence
 * the user deleted quietly comes back. The exclusion therefore takes the shape
 * of the master's own DTSTART, never the shape of the identifier it was handed.
 *
 * <p>
 * And it is a rewrite, never a delete: every component of a series lives in one
 * object, so deleting the object would remove the whole series to cancel one
 * meeting.
 */
public class IcsMergerExclusionTest {

  private final IcsMerger merger = new IcsMerger();

  @Test
  public void aTimedSeriesIsExcludedInItsOwnZone() {
    String rewritten = merger.excludeOccurrence(timedSeries(), Instant.parse("2026-09-15T07:00:00Z"));

    // The master is anchored on Europe/Paris, so the exclusion is too: an
    // instant written in UTC would be equivalent only to a client that
    // compares instants, and enough of them compare the written value.
    assertTrue(unfolded(rewritten).contains("EXDATE;TZID=Europe/Paris:20260915T090000"),
               () -> "expected a zone-anchored EXDATE in:\n" + rewritten);
  }

  @Test
  public void anAllDaySeriesIsExcludedByDate() {
    String rewritten = merger.excludeOccurrence(allDaySeries(), Instant.parse("2026-09-15T00:00:00Z"));

    assertTrue(unfolded(rewritten).contains("EXDATE;VALUE=DATE:20260915"),
               () -> "expected a date-valued EXDATE in:\n" + rewritten);
    assertFalse(unfolded(rewritten).contains("EXDATE;TZID"), "a date-valued series takes no zone-anchored exclusion");
  }

  @Test
  public void theOverrideForThatInstanceGoesWithIt() {
    // Left behind, an override contradicting an EXDATE keeps showing the
    // occurrence the user just deleted.
    String rewritten = merger.excludeOccurrence(seriesWithOverride(), Instant.parse("2026-09-15T07:00:00Z"));

    assertFalse(rewritten.contains("Moved instance"), "the override for the excluded instance must be gone");
    assertEquals(1, countOf(rewritten, "BEGIN:VEVENT"), "only the master should remain");
  }

  @Test
  public void excludingTwiceDoesNotWriteTheExclusionTwice() {
    String once = merger.excludeOccurrence(timedSeries(), Instant.parse("2026-09-15T07:00:00Z"));
    String twice = merger.excludeOccurrence(once, Instant.parse("2026-09-15T07:00:00Z"));

    assertEquals(1, countOf(unfolded(twice), "EXDATE"), "a repeated deletion must not accumulate exclusions");
  }

  @Test
  public void anObjectWithNothingLeftIsAnswerAsNothing() {
    // Writing back a VCALENDAR with no VEVENT is accepted by some servers and
    // then served to clients that choke on it. The caller deletes instead.
    assertNull(merger.excludeOccurrence(singleOverrideOnly(), Instant.parse("2026-09-15T07:00:00Z")));
  }

  @Test
  public void aSingleEventIsNeverGivenAnExclusion() {
    // Reaching here means the caller believes in a series this object does not
    // have. RFC 5545 defines EXDATE against a recurrence set, and a lenient
    // client handed one on a single event may hide the meeting entirely — so
    // the object is returned untouched. Changing nothing beats hiding
    // something.
    String rewritten = merger.excludeOccurrence(singleEvent(), Instant.parse("2026-09-15T07:00:00Z"));

    assertFalse(rewritten.contains("EXDATE"), "a single event must not gain an exclusion");
    assertTrue(rewritten.contains("SUMMARY:One meeting"), "and must not lose the meeting either");
  }

  /**
   * Undoes line folding, so a statement can be looked for without depending on
   * where a serialiser broke its lines.
   *
   * @param ics the document
   * @return the same text, unfolded
   */
  private String unfolded(String ics) {
    return ics.replace("\r\n ", "").replace("\n ", "");
  }

  /**
   * How many times a statement appears.
   *
   * @param ics the document
   * @param needle what to count
   * @return the count
   */
  private int countOf(String ics, String needle) {
    return ics.split(needle, -1).length - 1;
  }

  /**
   * @return a weekly series anchored on Europe/Paris
   */
  private String timedSeries() {
    return """
        BEGIN:VCALENDAR\r
        VERSION:2.0\r
        PRODID:-//Test//EN\r
        BEGIN:VTIMEZONE\r
        TZID:Europe/Paris\r
        BEGIN:STANDARD\r
        DTSTART:19701025T030000\r
        TZOFFSETFROM:+0200\r
        TZOFFSETTO:+0100\r
        RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=10\r
        END:STANDARD\r
        BEGIN:DAYLIGHT\r
        DTSTART:19700329T020000\r
        TZOFFSETFROM:+0100\r
        TZOFFSETTO:+0200\r
        RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=3\r
        END:DAYLIGHT\r
        END:VTIMEZONE\r
        BEGIN:VEVENT\r
        UID:series@test\r
        DTSTAMP:20260901T000000Z\r
        DTSTART;TZID=Europe/Paris:20260908T090000\r
        DTEND;TZID=Europe/Paris:20260908T100000\r
        SUMMARY:Weekly\r
        RRULE:FREQ=WEEKLY\r
        END:VEVENT\r
        END:VCALENDAR\r
        """;
  }

  /**
   * @return an all-day weekly series
   */
  private String allDaySeries() {
    return """
        BEGIN:VCALENDAR\r
        VERSION:2.0\r
        PRODID:-//Test//EN\r
        BEGIN:VEVENT\r
        UID:allday@test\r
        DTSTAMP:20260901T000000Z\r
        DTSTART;VALUE=DATE:20260908\r
        DTEND;VALUE=DATE:20260909\r
        SUMMARY:Daily standup\r
        RRULE:FREQ=WEEKLY\r
        END:VEVENT\r
        END:VCALENDAR\r
        """;
  }

  /**
   * @return the same series with an override on the instance to exclude
   */
  private String seriesWithOverride() {
    return timedSeries().replace("END:VCALENDAR\r\n", """
        BEGIN:VEVENT\r
        UID:series@test\r
        DTSTAMP:20260901T000000Z\r
        RECURRENCE-ID;TZID=Europe/Paris:20260915T090000\r
        DTSTART;TZID=Europe/Paris:20260915T140000\r
        DTEND;TZID=Europe/Paris:20260915T150000\r
        SUMMARY:Moved instance\r
        END:VEVENT\r
        END:VCALENDAR\r
        """);
  }

  /**
   * @return an object holding only the override for the excluded instance
   */
  private String singleOverrideOnly() {
    return """
        BEGIN:VCALENDAR\r
        VERSION:2.0\r
        PRODID:-//Test//EN\r
        BEGIN:VEVENT\r
        UID:orphan@test\r
        DTSTAMP:20260901T000000Z\r
        RECURRENCE-ID:20260915T070000Z\r
        DTSTART:20260915T070000Z\r
        DTEND:20260915T080000Z\r
        SUMMARY:Only instance\r
        END:VEVENT\r
        END:VCALENDAR\r
        """;
  }

  /**
   * @return a single event, no series at all
   */
  private String singleEvent() {
    return """
        BEGIN:VCALENDAR\r
        VERSION:2.0\r
        PRODID:-//Test//EN\r
        BEGIN:VEVENT\r
        UID:single@test\r
        DTSTAMP:20260901T000000Z\r
        DTSTART:20260915T070000Z\r
        DTEND:20260915T080000Z\r
        SUMMARY:One meeting\r
        END:VEVENT\r
        END:VCALENDAR\r
        """;
  }
}
