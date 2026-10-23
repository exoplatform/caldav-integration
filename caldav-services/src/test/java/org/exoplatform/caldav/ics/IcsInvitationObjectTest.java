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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.exoplatform.caldav.model.IcsEvent;

/**
 * What a scheduling message received by mail becomes on the way to a calendar
 * (EXO-90848): its revision is read, and the object stored keeps the event and
 * its zones, and nothing of the message.
 */
public class IcsInvitationObjectTest {

  private static final String REQUEST = "BEGIN:VCALENDAR\r\n"
      + "PRODID:-//Google Inc//Google Calendar 70.9054//EN\r\n"
      + "VERSION:2.0\r\n"
      + "METHOD:REQUEST\r\n"
      + "BEGIN:VTIMEZONE\r\n"
      + "TZID:Europe/Paris\r\n"
      + "BEGIN:STANDARD\r\n"
      + "DTSTART:19701025T030000\r\n"
      + "TZOFFSETFROM:+0200\r\n"
      + "TZOFFSETTO:+0100\r\n"
      + "END:STANDARD\r\n"
      + "END:VTIMEZONE\r\n"
      + "BEGIN:VEVENT\r\n"
      + "UID:weekly-sync@google.com\r\n"
      + "SEQUENCE:3\r\n"
      + "DTSTAMP:20261001T080000Z\r\n"
      + "DTSTART;TZID=Europe/Paris:20261005T100000\r\n"
      + "DTEND;TZID=Europe/Paris:20261005T110000\r\n"
      + "RRULE:FREQ=WEEKLY;BYDAY=MO\r\n"
      + "SUMMARY:Weekly sync\r\n"
      + "ORGANIZER:mailto:olivia@partner.example\r\n"
      + "ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:john@acme.com\r\n"
      + "END:VEVENT\r\n"
      + "BEGIN:VEVENT\r\n"
      + "UID:weekly-sync@google.com\r\n"
      + "SEQUENCE:3\r\n"
      + "RECURRENCE-ID;TZID=Europe/Paris:20261012T100000\r\n"
      + "DTSTAMP:20261001T080000Z\r\n"
      + "DTSTART;TZID=Europe/Paris:20261012T140000\r\n"
      + "DTEND;TZID=Europe/Paris:20261012T150000\r\n"
      + "SUMMARY:Weekly sync (moved)\r\n"
      + "END:VEVENT\r\n"
      + "BEGIN:VEVENT\r\n"
      + "UID:something-else@google.com\r\n"
      + "DTSTAMP:20261001T080000Z\r\n"
      + "DTSTART:20261005T080000Z\r\n"
      + "SUMMARY:Not this one\r\n"
      + "END:VEVENT\r\n"
      + "BEGIN:VTODO\r\n"
      + "UID:todo@google.com\r\n"
      + "DTSTAMP:20261001T080000Z\r\n"
      + "SUMMARY:A task\r\n"
      + "END:VTODO\r\n"
      + "END:VCALENDAR\r\n";

  private final IcsParser     parser  = new IcsParser();

  private final IcsMerger     merger  = new IcsMerger();

  /**
   * SEQUENCE is read on every component; absent or unreadable, it is 0, the
   * RFC default and the oldest revision a comparison can meet.
   */
  @Test
  public void theRevisionIsReadAndDefaultsToZero() {
    // Masters first, then overrides: the other event, carrying no SEQUENCE, sits
    // between the series and its override.
    List<IcsEvent> events = parser.parse(REQUEST);
    assertEquals(3, events.get(0).getSequence());
    assertEquals(0, events.get(1).getSequence(), "no SEQUENCE");
    assertEquals(3, events.get(2).getSequence(), "the override carries its own");

    // A SEQUENCE that is not a number is refused by the library at the parse,
    // which is the object unreadable as a whole, not a revision of 0.
    assertTrue(parser.parse(REQUEST.replace("SEQUENCE:3", "SEQUENCE:three")).isEmpty());
    assertEquals(0, parser.parse(REQUEST.replace("SEQUENCE:3", "SEQUENCE:-4")).get(0).getSequence());
  }

  /**
   * The object stored keeps the message's zones and every component of the
   * event — the master and its override — with the message's METHOD gone, and
   * drops what is not that event.
   */
  @Test
  public void theStoredObjectIsTheEventAndItsZonesWithoutTheMethod() {
    String stored = merger.storedObject(REQUEST, "weekly-sync@google.com").replace("\r\n ", "");

    assertFalse(stored.contains("METHOD:"), stored);
    assertTrue(stored.contains("PRODID:-//Google Inc//Google Calendar 70.9054//EN"), stored);
    assertTrue(stored.contains("VERSION:2.0"), stored);
    assertTrue(stored.contains("TZID:Europe/Paris"), stored);
    assertEquals(2, stored.split("UID:weekly-sync@google.com", -1).length - 1, "the master and its override: " + stored);
    assertTrue(stored.contains("RECURRENCE-ID;TZID=Europe/Paris:20261012T100000"), stored);
    assertTrue(stored.contains("PARTSTAT=NEEDS-ACTION"), "the answer is somebody else's to set: " + stored);
    assertFalse(stored.contains("something-else@google.com"), stored);
    assertFalse(stored.contains("VTODO"), stored);
    assertEquals(2, parser.parseOrFail(stored).size(), "readable back as the sweep reads it");
  }

  /**
   * A message that is not iCalendar, or that carries no event with the UID
   * answered, cannot be stored.
   */
  @Test
  public void aMessageWithoutThatEventCannotBeStored() {
    assertThrows(IcsParseException.class, () -> merger.storedObject(REQUEST, "unknown@google.com"));
    assertThrows(IcsParseException.class, () -> merger.storedObject("not a calendar", "weekly-sync@google.com"));
  }
}
