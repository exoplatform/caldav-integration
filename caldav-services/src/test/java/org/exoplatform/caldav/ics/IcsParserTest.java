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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.exoplatform.caldav.model.IcsEvent;

/**
 * The inbound half of the ICS engine, read against the captured goldens.
 *
 * <p>
 * These assert what a materialised event must end up holding, not what the
 * parser happens to produce today: a series stays one event with a rule, an
 * all-day event covers the days it covers, and a zone survives the trip.
 */
public class IcsParserTest {

  private final IcsParser parser = new IcsParser();

  /**
   * A recurring object stays one event carrying its rule.
   *
   * @throws Exception when the golden cannot be read
   */
  @Test
  public void aSeriesIsOneEventWithItsRuleNotAHundredEvents() throws Exception {
    // The whole reason this parser exists beside IcsReader. Expanding here
    // would give the user a hundred unrelated events where they have one
    // weekly meeting, and nothing left to edit as a series.
    List<IcsEvent> events = parser.parse(golden("r01-thunderbird-weekly-paris"));

    assertEquals(1, events.size());
    IcsEvent event = events.get(0);
    assertEquals("r01-tb-weekly@example.test", event.getUid());
    assertEquals("FREQ=WEEKLY;BYDAY=MO", event.getRecurrenceRule());
    assertNull(event.getOccurrenceId());
  }

  /**
   * A timed event keeps the zone it was written in.
   *
   * @throws Exception when the golden cannot be read
   */
  @Test
  public void aTimedEventKeepsItsZoneAndItsInstant() throws Exception {
    // 09:00 in Paris on 5 October is 07:00Z — the zone is not decoration, it
    // is what makes the instant right for everyone else reading it.
    IcsEvent event = parser.parse(golden("r01-thunderbird-weekly-paris")).get(0);

    assertFalse(event.isAllDay());
    assertEquals("Europe/Paris", event.getTimeZoneId());
    assertEquals(Instant.parse("2026-10-05T07:00:00Z"), event.getStart());
    assertEquals(Instant.parse("2026-10-05T08:00:00Z"), event.getEnd());
  }

  /**
   * An all-day event covers the day it covers.
   *
   * @throws Exception when the golden cannot be read
   */
  @Test
  public void aOneDayEventEndsOnTheDayItCoversNotTheDayAfter() throws Exception {
    // RFC 5545 makes an all-day DTEND exclusive, so this golden's 12th-to-13th
    // is a single day. Agenda holds the last day covered, so the day comes
    // back off — the same asymmetry that once made a pushed all-day event
    // arrive a day short, in the other direction.
    IcsEvent event = parser.parse(golden("r02-apple-allday")).get(0);

    assertTrue(event.isAllDay());
    assertEquals(Instant.parse("2026-10-12T00:00:00Z"), event.getStart());
    assertEquals(Instant.parse("2026-10-12T00:00:00Z"), event.getEnd());
  }

  /**
   * Text properties survive the trip.
   *
   * @throws Exception when the golden cannot be read
   */
  @Test
  public void theTextTheUserWroteIsCarriedThrough() throws Exception {
    IcsEvent event = parser.parse(golden("r01-thunderbird-weekly-paris")).get(0);

    assertEquals("Point hebdo équipe — préparation du budget", event.getSummary());
    assertEquals("Salle A", event.getLocation());
    assertNotNull(event.getDescription());
    assertTrue(event.getDescription().contains("budget 2027"));
  }

  /**
   * An object holding a master and an override yields both, master first.
   *
   * @throws Exception when the golden cannot be read
   */
  @Test
  public void theMasterComesBackBeforeItsOverrideWhateverTheFileOrder() throws Exception {
    // This golden puts the override first on purpose. A caller creating the
    // series before amending one of its occurrences should not have to sort.
    List<IcsEvent> events = parser.parse(golden("r03-override-first-ordering"));

    assertTrue(events.size() >= 2);
    assertNull(events.get(0).getOccurrenceId());
    assertNotNull(events.get(1).getOccurrenceId());
  }

  /**
   * A TZID the object never defines is still honoured.
   *
   * @throws Exception when the golden cannot be read
   */
  @Test
  public void aZoneNamedWithoutAVtimezoneIsStillRead() throws Exception {
    IcsEvent event = parser.parse(golden("r04-tzid-without-vtimezone")).get(0);

    assertFalse(event.isAllDay());
    assertNotNull(event.getStart());
    assertNotNull(event.getTimeZoneId());
  }

  /**
   * A body that is not a calendar yields nothing rather than throwing.
   */
  @Test
  public void anUnreadableObjectIsSkippedRatherThanStoppingTheSync() {
    // One object a server serialises badly must not stop a synchronisation
    // that has other objects to bring in.
    assertTrue(parser.parse("this is not a calendar").isEmpty());
    assertTrue(parser.parse("").isEmpty());
    assertTrue(parser.parse(null).isEmpty());
  }

  /**
   * A VEVENT with no UID is not importable and is left out.
   */
  @Test
  public void aVeventWithoutAUidIsNotImportable() {
    // Without a UID there is nothing to bind the event to across syncs, so it
    // could never be updated or deleted again once created.
    String ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//test//EN
        BEGIN:VEVENT
        DTSTAMP:20261001T080000Z
        DTSTART:20261012T090000Z
        DTEND:20261012T100000Z
        SUMMARY:Anonymous
        END:VEVENT
        END:VCALENDAR
        """;

    assertTrue(parser.parse(ics).isEmpty());
  }

  /**
   * An object carrying only a task is not an event.
   */
  @Test
  public void aCalendarHoldingOnlyATaskYieldsNoEvent() {
    String ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//test//EN
        BEGIN:VTODO
        DTSTAMP:20261001T080000Z
        UID:task-1@example.test
        SUMMARY:Buy milk
        END:VTODO
        END:VCALENDAR
        """;

    assertTrue(parser.parse(ics).isEmpty());
  }

  /**
   * An alarm set before the start becomes a reminder in minutes.
   */
  @Test
  public void anAlarmBecomesTheMinutesBeforeTheEvent() {
    String ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//test//EN
        BEGIN:VEVENT
        DTSTAMP:20261001T080000Z
        UID:alarm-1@example.test
        DTSTART:20261012T090000Z
        DTEND:20261012T100000Z
        SUMMARY:With a reminder
        BEGIN:VALARM
        ACTION:DISPLAY
        TRIGGER:-PT15M
        DESCRIPTION:Reminder
        END:VALARM
        END:VEVENT
        END:VCALENDAR
        """;

    IcsEvent event = parser.parse(ics).get(0);

    assertEquals(1, event.getReminders().size());
    assertEquals(15, event.getReminders().get(0).getBefore());
    assertEquals("minute", event.getReminders().get(0).getBeforePeriodType());
  }

  /**
   * An alarm agenda cannot express is dropped, not moved.
   */
  @Test
  public void anAlarmAfterTheStartIsDroppedRatherThanMoved() {
    // Agenda holds reminders as minutes *before* the event. Inventing an
    // offset for an alarm set after the fact would move the user's reminder
    // rather than admit we cannot hold it.
    String ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//test//EN
        BEGIN:VEVENT
        DTSTAMP:20261001T080000Z
        UID:alarm-2@example.test
        DTSTART:20261012T090000Z
        DTEND:20261012T100000Z
        SUMMARY:Late alarm
        BEGIN:VALARM
        ACTION:DISPLAY
        TRIGGER:PT10M
        DESCRIPTION:Too late
        END:VALARM
        END:VEVENT
        END:VCALENDAR
        """;

    assertTrue(parser.parse(ics).get(0).getReminders().isEmpty());
  }

  /**
   * Attendees keep their address and their answer.
   */
  @Test
  public void anAttendeeKeepsTheirAddressAndTheirAnswer() {
    String ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//test//EN
        BEGIN:VEVENT
        DTSTAMP:20261001T080000Z
        UID:people-1@example.test
        DTSTART:20261012T090000Z
        DTEND:20261012T100000Z
        SUMMARY:With people
        ORGANIZER;CN=Alice:mailto:alice@example.test
        ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.test
        END:VEVENT
        END:VCALENDAR
        """;

    IcsEvent event = parser.parse(ics).get(0);

    assertEquals("alice@example.test", event.getOrganizer().getEmail());
    assertEquals("Alice", event.getOrganizer().getDisplayName());
    assertEquals(1, event.getAttendees().size());
    assertEquals("bob@example.test", event.getAttendees().get(0).getEmail());
    assertEquals("ACCEPTED", event.getAttendees().get(0).getResponse());
  }

  /**
   * Every excluded date is read, however the object spells them.
   */
  @Test
  public void everyExcludedDateIsReadWhicheverWayTheyAreSpelled() {
    // A property may list several dates and an object may repeat the property.
    // Both are legal, and a missed EXDATE puts back an occurrence the user
    // deleted.
    String ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//test//EN
        BEGIN:VEVENT
        DTSTAMP:20261001T080000Z
        UID:exdate-1@example.test
        DTSTART:20261005T090000Z
        DTEND:20261005T100000Z
        SUMMARY:Weekly
        RRULE:FREQ=WEEKLY
        EXDATE:20261012T090000Z,20261019T090000Z
        EXDATE:20261026T090000Z
        END:VEVENT
        END:VCALENDAR
        """;

    assertEquals(3, parser.parse(ics).get(0).getExceptionDates().size());
  }

  /**
   * @param name the golden's file name, without extension
   * @return its contents
   * @throws Exception when it cannot be read
   */
  private String golden(String name) throws Exception {
    Path path = Paths.get(IcsParserTest.class.getClassLoader()
                                             .getResource("caldav/golden/read/objects/" + name + ".ics")
                                             .toURI());
    return Files.readString(path);
  }
}
