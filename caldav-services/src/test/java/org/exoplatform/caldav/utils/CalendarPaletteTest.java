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
package org.exoplatform.caldav.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The colour a user recognises their calendar by.
 *
 * <p>
 * The expected values below are not chosen: they were produced by running the
 * browser connector's own derivation in node over the same inputs. That is the
 * whole point of the test. A colour derived by a different-but-reasonable
 * arithmetic would silently repaint everyone's agenda on the day this ships —
 * same events, same calendars, different colours, and nothing to explain it —
 * and no assertion written from intent would have noticed.
 *
 * <p>
 * Java's own {@code String.hashCode} is a different function from the one the
 * JavaScript uses, which is exactly the trap: the port compiles, looks right,
 * and produces the wrong colours.
 */
public class CalendarPaletteTest {

  @ParameterizedTest(name = "{0} at {1} of {2} is {3}")
  @CsvSource({ "/dav/calendars/john/personal/, 0, 3, #22817F",
      "/dav/calendars/john/work/, 1, 3, #BF33C1",
      "/dav/calendars/john/exo-meetings/, 2, 3, #797720",
      "/dav/calendars/__uids__/751E6D1A-7FDB-49B2-B668-B569E9A5A42D/exo-meetings, 0, 1, #994C29",
      "/a/b/c/, 5, 2, #0D8719" })
  public void derivesTheSameColourTheBrowserDid(String href, int position, int total, String expected) {
    assertEquals(expected, CalendarPalette.colourOf(null, href, position, total));
  }

  @Test
  public void anEmptyHrefStillGetsAColour() {
    // A calendar with no href is a defect elsewhere, but a missing colour
    // would make it invisible rather than merely wrong.
    assertEquals("#992929", CalendarPalette.colourOf(null, "", 0, 1));
  }

  @Test
  public void aPublishedColourWins() {
    assertEquals("#D688DB", CalendarPalette.colourOf("#d688db", "/dav/cal/x/", 0, 1));
  }

  @Test
  public void anAlphaPairIsDroppedRatherThanHonoured() {
    // BlueMind publishes #RRGGBBAA. A translucent event on a calendar grid
    // reads as a lighter event, not a transparent one.
    assertEquals("#D688DB", CalendarPalette.normalise("#D688DBFF"));
  }

  @Test
  public void somethingThatIsNotAColourIsNotOne() {
    assertNull(CalendarPalette.normalise("chartreuse"));
    assertNull(CalendarPalette.normalise(""));
    assertNull(CalendarPalette.normalise(null));
  }

  @Test
  public void everyDerivedColourIsReadableOnWhite() {
    // The lightness walk exists for this. A calendar whose name cannot be read
    // is not a colour scheme.
    for (int position = 0; position < 12; position++) {
      String colour = CalendarPalette.colourOf(null, "/dav/calendars/user/cal-" + position + "/", position, 12);
      assertTrue(CalendarPalette.contrastWithWhite(colour) >= 4.5,
                 () -> "colour " + colour + " is unreadable on white");
    }
  }

  @Test
  public void orderComesFromTheHrefNotFromTheServerListing() {
    // A server free to reorder its listing would otherwise repaint the
    // calendars between two reads.
    List<String> ordered = CalendarPalette.inStableOrder(List.of("/dav/c/zeta/", "/dav/c/alpha/", "/dav/c/mid/"));

    assertEquals(List.of("/dav/c/alpha/", "/dav/c/mid/", "/dav/c/zeta/"), ordered);
  }
}
