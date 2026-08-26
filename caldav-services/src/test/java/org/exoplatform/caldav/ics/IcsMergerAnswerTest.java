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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Writing one attendee's answer onto an object a server already holds.
 *
 * <p>
 * A targeted rewrite rather than a fresh serialisation, because the object may
 * carry overrides and properties another client wrote and an answer is not
 * consent to discard them. Answering null when nothing moved is what makes the
 * write idempotent — and what stops an answer just adopted from a copy being
 * pushed straight back at it.
 */
public class IcsMergerAnswerTest {

  private final IcsMerger merger = new IcsMerger();

  /**
   * The answer moves, and only the answer.
   */
  @Test
  public void theAnswerIsRewrittenAndNothingElseIs() {
    String rewritten = merger.setAttendeeResponse(meeting("DECLINED"), "alice@stalwart.local", "ACCEPTED");

    assertNotNull(rewritten);
    String unfolded = unfolded(rewritten);
    assertTrue(unfolded.contains("PARTSTAT=ACCEPTED"), unfolded);
    assertFalse(unfolded.contains("PARTSTAT=DECLINED"), unfolded);
    // The other attendee's answer, the organiser, the summary and the
    // scheduling agent the copy carries are all still there.
    assertTrue(unfolded.contains("PARTSTAT=TENTATIVE"), unfolded);
    assertTrue(unfolded.contains("mailto:bob@stalwart.local"), unfolded);
    assertTrue(unfolded.contains("mailto:root@stalwart.local"), unfolded);
    assertTrue(unfolded.contains("SUMMARY:invit5"), unfolded);
    assertTrue(unfolded.contains("SCHEDULE-AGENT=CLIENT"), unfolded);
    // And the name the copy knows the answerer by survives the rewrite: it is
    // a parameter of the same property, and replacing the property rather than
    // the parameter would have taken it with it.
    assertTrue(unfolded.contains("CN=Alice"), unfolded);
  }

  /**
   * An object that already says this is left alone. Writing it back would move
   * the ETag for nothing, and would send an answer straight back at the server
   * it had just been read from.
   */
  @Test
  public void anObjectThatAlreadySaysThisIsNotRewritten() {
    assertNull(merger.setAttendeeResponse(meeting("ACCEPTED"), "alice@stalwart.local", "ACCEPTED"));
  }

  /**
   * An attendee the object does not name has no line to rewrite. Adding one
   * would put a person on a meeting's roster because they answered it, which
   * is the wrong way round.
   */
  @Test
  public void anAttendeeTheObjectDoesNotNameIsNotAddedToIt() {
    assertNull(merger.setAttendeeResponse(meeting("DECLINED"), "carol@stalwart.local", "ACCEPTED"));
  }

  /**
   * The address is matched without its scheme and without case. Clients differ
   * on the case of the scheme, and a server that echoes an address back
   * capitalised must not leave the answer unpropagated.
   */
  @Test
  public void theAddressIsMatchedWhateverCaseAndSchemeItIsWrittenIn() {
    String rewritten = merger.setAttendeeResponse(meeting("DECLINED").replace("mailto:alice@stalwart.local",
                                                                             "MAILTO:Alice@Stalwart.Local"),
                                                  "alice@stalwart.local",
                                                  "ACCEPTED");

    assertNotNull(rewritten);
    assertTrue(unfolded(rewritten).contains("PARTSTAT=ACCEPTED"), rewritten);
  }

  /**
   * Every component of the object is visited, master and overrides alike:
   * agenda propagates an answer to a series onto each of its exceptional
   * occurrences, and a copy that moved only the master would show the user one
   * answer for the series and another for the instances they had moved.
   */
  @Test
  public void anOverrideCarriesTheAnswerToo() {
    String rewritten = merger.setAttendeeResponse(series("DECLINED"), "alice@stalwart.local", "ACCEPTED");

    assertNotNull(rewritten);
    String unfolded = unfolded(rewritten);
    assertFalse(unfolded.contains("PARTSTAT=DECLINED"), unfolded);
    assertEquals(2, count(unfolded, "PARTSTAT=ACCEPTED"), unfolded);
  }

  /**
   * An attendee written without an answer at all — RFC 5545 makes PARTSTAT
   * optional and defaults it to NEEDS-ACTION — gains one rather than being
   * skipped.
   */
  @Test
  public void anAttendeeWithNoAnswerYetGainsOne() {
    String rewritten = merger.setAttendeeResponse(meeting("DECLINED").replace(";PARTSTAT=DECLINED", ""),
                                                  "alice@stalwart.local",
                                                  "ACCEPTED");

    assertNotNull(rewritten);
    assertTrue(unfolded(rewritten).contains("PARTSTAT=ACCEPTED"), rewritten);
  }

  /**
   * Nothing to match on is nothing to do, rather than a rewrite of every
   * attendee or an exception on a path that runs on every answer.
   */
  @Test
  public void nothingToMatchOnIsNothingToDo() {
    assertNull(merger.setAttendeeResponse(meeting("DECLINED"), null, "ACCEPTED"));
    assertNull(merger.setAttendeeResponse(meeting("DECLINED"), "  ", "ACCEPTED"));
    assertNull(merger.setAttendeeResponse(meeting("DECLINED"), "alice@stalwart.local", " "));
  }

  /**
   * @param document the document to search
   * @param token what to count
   * @return how many times the token appears
   */
  private int count(String document, String token) {
    int found = 0;
    int at = document.indexOf(token);
    while (at >= 0) {
      found++;
      at = document.indexOf(token, at + token.length());
    }
    return found;
  }

  /**
   * @param ics a written document
   * @return the same document with its line folding undone
   */
  private String unfolded(String ics) {
    return ics.replace("\r\n ", "").replace("\n ", "");
  }

  /**
   * @param answer the answer Alice's line carries
   * @return one meeting, as a server serves it
   */
  private String meeting(String answer) {
    return String.join("\r\n",
                       "BEGIN:VCALENDAR",
                       "VERSION:2.0",
                       "PRODID:-//eXo//caldav//EN",
                       "BEGIN:VEVENT",
                       "UID:evt-1",
                       "DTSTAMP:20260826T150000Z",
                       "DTSTART:20260908T090000Z",
                       "DTEND:20260908T100000Z",
                       "SUMMARY:invit5",
                       "ORGANIZER;CN=Root Root:mailto:root@stalwart.local",
                       "ATTENDEE;CN=Alice;PARTSTAT=" + answer + ";SCHEDULE-AGENT=CLIENT:mailto:alice@stalwart.local",
                       "ATTENDEE;CN=Bob;PARTSTAT=TENTATIVE;SCHEDULE-AGENT=CLIENT:mailto:bob@stalwart.local",
                       "END:VEVENT",
                       "END:VCALENDAR",
                       "");
  }

  /**
   * @param answer the answer Alice's lines carry, on the master and on the
   *          override alike
   * @return a series with one override, as a server serves it
   */
  private String series(String answer) {
    return String.join("\r\n",
                       "BEGIN:VCALENDAR",
                       "VERSION:2.0",
                       "PRODID:-//eXo//caldav//EN",
                       "BEGIN:VEVENT",
                       "UID:evt-1",
                       "DTSTAMP:20260826T150000Z",
                       "DTSTART:20260908T090000Z",
                       "DTEND:20260908T100000Z",
                       "RRULE:FREQ=WEEKLY;COUNT=4",
                       "SUMMARY:invit5",
                       "ATTENDEE;CN=Alice;PARTSTAT=" + answer + ":mailto:alice@stalwart.local",
                       "END:VEVENT",
                       "BEGIN:VEVENT",
                       "UID:evt-1",
                       "RECURRENCE-ID:20260915T090000Z",
                       "DTSTAMP:20260826T150000Z",
                       "DTSTART:20260915T100000Z",
                       "DTEND:20260915T110000Z",
                       "SUMMARY:invit5 moved",
                       "ATTENDEE;CN=Alice;PARTSTAT=" + answer + ":mailto:alice@stalwart.local",
                       "END:VEVENT",
                       "END:VCALENDAR",
                       "");
  }
}
