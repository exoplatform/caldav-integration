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
package org.exoplatform.caldav.ics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.exoplatform.caldav.model.IcsEvent;

/**
 * What a series looks like on the wire.
 *
 * <p>
 * The golden fixtures pin whole objects against captured server output. These
 * pin the parts of the recurrence writer those fixtures do not reach: the
 * exclusions, the overrides, and the two rules that exist only because a
 * server or agenda does something the RFC does not.
 */
public class IcsWriterRecurrenceTest {

  private static final Instant START = Instant.parse("2026-10-05T07:00:00Z");

  private final IcsWriter      writer = new IcsWriter();

  @Test
  public void agendasNoLimitCountIsNotWrittenAsANeverOccurringSeries() {
    // COUNT=0 is agenda's way of saying "no limit". RFC 5545 gives it the
    // opposite meaning — a series that never occurs — so a server told COUNT=0
    // would show the user nothing at all.
    IcsEvent event = base();
    event.setRecurrenceRule("COUNT=0;FREQ=WEEKLY;BYDAY=MO");

    String ics = writer.write(event);

    assertTrue(ics.contains("RRULE:"), "the series must still be written");
    assertFalse(ics.contains("COUNT=0"), "COUNT=0 must never reach the server");
  }

  @Test
  public void aRuleThatCannotBeParsedIsDroppedRatherThanGuessedAt() {
    // Better a single event than a series repeating by a rule nobody wrote.
    IcsEvent event = base();
    event.setRecurrenceRule("FREQ=NONSENSE;BYDAY=??");

    String ics = writer.write(event);

    assertFalse(ics.contains("RRULE:"), "an unreadable rule must not be invented");
    assertTrue(ics.contains("BEGIN:VEVENT"), "the event itself is still worth having");
  }

  @Test
  public void anAllDayExclusionIsWrittenAsADate() {
    // An exclusion only removes an occurrence it matches: written as an
    // instant against an all-day series it matches nothing, and the deleted
    // day comes back. The stored form is agenda's — an ISO date, no T — which
    // is what tells this apart from a timed exclusion.
    IcsEvent event = base();
    event.setAllDay(true);
    event.setRecurrenceRule("FREQ=DAILY");
    event.setExceptionDates(List.of("2026-10-12"));

    String ics = writer.write(event);

    assertTrue(ics.contains("EXDATE;VALUE=DATE:20261012"), "an all-day exclusion is a date, not an instant");
    assertFalse(ics.contains("VALUE=DATE;VALUE=DATE"), "the parameter must be written once");
  }

  @Test
  public void anExclusionOnAZonedSeriesCarriesItsZone() {
    IcsEvent event = base();
    event.setTimeZoneId("Europe/Paris");
    event.setRecurrenceRule("FREQ=WEEKLY");
    event.setExceptionDates(List.of("2026-10-12T07:00:00Z"));

    String ics = writer.write(event);

    assertTrue(ics.contains("EXDATE;TZID=Europe/Paris"), "the exclusion must be anchored like the series it excludes from");
  }

  @Test
  public void anExclusionOnAUtcSeriesIsWrittenInUtc() {
    IcsEvent event = base();
    event.setTimeZoneId(null);
    event.setRecurrenceRule("FREQ=WEEKLY");
    event.setExceptionDates(List.of("2026-10-12T07:00:00Z"));

    String ics = writer.write(event);

    assertTrue(ics.contains("EXDATE"), "the exclusion must be written");
    assertTrue(ics.contains("20261012T070000Z"), "a UTC-anchored series excludes a UTC instant");
  }

  @Test
  public void blankExclusionsAreSkippedRatherThanWritten() {
    // A stored empty string, which an EXDATE with nothing in it would turn
    // into a property no server can read.
    IcsEvent event = base();
    event.setRecurrenceRule("FREQ=WEEKLY");
    event.setExceptionDates(java.util.Arrays.asList("", null));

    String ics = writer.write(event);

    assertFalse(ics.contains("EXDATE"), "nothing to exclude means no EXDATE at all");
  }

  @Test
  public void anOverrideOfAnAllDaySeriesNamesItsDayNotAnInstant() {
    // RECURRENCE-ID has to match the occurrence it replaces in the same form
    // DTSTART uses; as an instant against an all-day series it replaces
    // nothing and the override shows up as a second event.
    IcsEvent event = base();
    event.setAllDay(true);
    event.setOccurrenceId("2026-10-12T00:00:00Z");

    String ics = writer.write(event);

    assertTrue(ics.contains("RECURRENCE-ID;VALUE=DATE:20261012"), "an all-day override names a date");
    assertFalse(ics.contains("VALUE=DATE;VALUE=DATE"), "the parameter must be written once");
  }

  @Test
  public void anOverrideOfAZonedSeriesCarriesItsZone() {
    IcsEvent event = base();
    event.setTimeZoneId("Europe/Paris");
    event.setOccurrenceId("2026-10-12T07:00:00Z");

    String ics = writer.write(event);

    assertTrue(ics.contains("RECURRENCE-ID;TZID=Europe/Paris"), "the override is anchored like the series");
  }

  @Test
  public void anOverrideOfAUtcSeriesIsWrittenInUtc() {
    IcsEvent event = base();
    event.setTimeZoneId(null);
    event.setOccurrenceId("2026-10-12T07:00:00Z");

    String ics = writer.write(event);

    assertTrue(ics.contains("RECURRENCE-ID:20261012T070000Z"), "a UTC-anchored override names a UTC instant");
  }

  @Test
  public void anAllDayEventCarriesTheDateParameterExactlyOnce() {
    // ical4j's Date type already makes a property carry VALUE=DATE. Adding it
    // by hand as well wrote "VALUE=DATE;VALUE=DATE" on DTSTART, DTEND, EXDATE
    // and RECURRENCE-ID alike — malformed, and a strict server may refuse the
    // object with nothing to show for it but an event that never arrived.
    IcsEvent event = base();
    event.setAllDay(true);

    String ics = writer.write(event);

    assertTrue(ics.contains("DTSTART;VALUE=DATE:20261005"), "the start is a date");
    assertFalse(ics.contains("VALUE=DATE;VALUE=DATE"), "the parameter must be written once");
  }

  /**
   * @return an event with the least that makes a writable VEVENT
   */
  private IcsEvent base() {
    IcsEvent event = new IcsEvent();
    event.setUid("uid-1@example.test");
    event.setSummary("Weekly");
    event.setStart(START);
    event.setEnd(START.plusSeconds(3600));
    event.setTimeZoneId("Etc/UTC");
    return event;
  }
}
