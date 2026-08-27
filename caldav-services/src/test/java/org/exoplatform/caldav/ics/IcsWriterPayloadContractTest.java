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

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import org.exoplatform.agenda.util.EventIcsBuilder;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.IcsPerson;

/**
 * The payload contract of the calendar copy, checked defect by defect.
 *
 * <p>
 * Five payload defects were found and fixed in the <i>other</i> iCalendar
 * writer eXo owns — the one that produces the document attached to a
 * notification mail (EXO-89703). Because the two writers shared no code, no
 * one knew whether the copy carried the same faults. This class asks the
 * question of the copy writer, one test per defect, so the answer is a test
 * result rather than a reading of the source: PRODID repeating its own
 * property name, ORGANIZER written without a <code>mailto:</code> scheme,
 * METHOD absent from the body, a raw HTML fragment left in DESCRIPTION, and an
 * accented character mangled by a second round of escaping.
 *
 * <p>
 * Every one of them passes on the writer as it stands, which is worth pinning
 * rather than merely asserting once: the writer is about to start taking its
 * description from a builder shared with the mail channel (EXO-89732), and
 * these are exactly the properties that shared code could regress.
 *
 * <p>
 * METHOD is the one that is deliberately <i>not</i> shared. A mailed document
 * declares <code>METHOD:PUBLISH</code>; a calendar object resource must not
 * declare a method at all — RFC 4791 &sect;4.1 forbids it — so the test below
 * pins its absence rather than its value.
 */
public class IcsWriterPayloadContractTest {

  /** The link back to the event in eXo, of the shape agenda mints. */
  private static final String EXO_LINK = "https://exo.example.com/portal/dw/agenda?eventId=1";

  /** The writer under test, which needs nothing injected. */
  private final IcsWriter writer = new IcsWriter();

  /**
   * Defect 1 — PRODID repeating its own name.
   *
   * <p>
   * ical4j writes the property name itself, so a value that spells it again
   * puts <code>PRODID:PRODID:-//...</code> on the wire.
   */
  @Test
  public void prodIdCarriesItsValueAlone() {
    String prodId = property(writer.write(meeting()), "PRODID");

    assertNotNull(prodId, "the copy must name the product that wrote it");
    assertFalse(prodId.startsWith("PRODID:PRODID:"), "PRODID must not repeat its own property name: " + prodId);
    assertTrue(prodId.startsWith("PRODID:-//"), "PRODID must carry a formal public identifier: " + prodId);
  }

  /**
   * Defect 2 — ORGANIZER without a scheme.
   *
   * <p>
   * RFC 5545 &sect;3.3.3 makes the value a CAL-ADDRESS, which is a URI. A bare
   * mail address has no scheme, and a client that validates the value drops
   * the property — taking the attribution of the meeting with it. ATTENDEE
   * carries the same value type and is checked with it, because on the copy it
   * is the one the client matches against its own accounts to decide whether
   * to offer RSVP.
   */
  @Test
  public void organizerAndAttendeeAreCalendarUserAddresses() {
    String ics = writer.write(meeting());

    String organizer = property(ics, "ORGANIZER");
    assertNotNull(organizer, "the copy must say who called the meeting");
    assertTrue(organizer.contains(":mailto:organiser@example.com"), "ORGANIZER must be a mailto: URI: " + organizer);

    String attendee = property(ics, "ATTENDEE");
    assertNotNull(attendee, "the copy must carry the roster, which is what makes RSVP possible");
    assertTrue(attendee.contains(":mailto:guest@example.com"), "ATTENDEE must be a mailto: URI: " + attendee);
  }

  /**
   * Defect 3 — METHOD missing from the body.
   *
   * <p>
   * A defect in the mail, and the correct behaviour here: the copy is a
   * calendar object resource, and RFC 4791 &sect;4.1 says such a resource must
   * not specify METHOD. This is one of the properties that stays per-channel,
   * and the test exists so that sharing the core never introduces it.
   */
  @Test
  public void theCopyDeclaresNoMethod() {
    assertNull(property(writer.write(meeting()), "METHOD"),
               "a calendar object resource must not carry METHOD (RFC 4791 4.1)");
  }

  /**
   * Defect 4 — a raw HTML fragment left in DESCRIPTION.
   *
   * <p>
   * DESCRIPTION is plain text by definition. A description the editor stored
   * as markup has to be rendered before it is written, not passed through.
   */
  @Test
  public void descriptionCarriesNoMarkup() {
    String description = property(writer.write(meeting()), "DESCRIPTION");

    assertNotNull(description, "the copy must describe the meeting");
    assertFalse(description.contains("<"), "DESCRIPTION must not carry markup: " + description);
    assertFalse(description.contains("&lt;"), "DESCRIPTION must not carry escaped markup either: " + description);
    assertTrue(description.contains("Bring the slides."), "the text of the description must survive: " + description);
  }

  /**
   * Defect 5 — an accented character mangled by a second round of escaping.
   *
   * <p>
   * The mail writer ran its labels through an HTML entity encoder and then
   * through the iCalendar writer, so a French word reached the wire as
   * <code>envoy&amp;eacute\;e</code> — the entity's own semicolon escaped as
   * if it were an iCalendar separator. The copy must carry the character
   * itself.
   */
  @Test
  public void accentedTextSurvivesAsItself() {
    String description = property(writer.write(meeting()), "DESCRIPTION");

    assertNotNull(description);
    assertTrue(description.contains("Réunion préparée"), "an accented word must reach the wire intact: " + description);
    assertFalse(description.contains("&eacute;"), "an entity must have been decoded, not carried: " + description);
    assertFalse(description.contains("\\;"), "and must not have had its semicolon escaped again: " + description);
  }

  /**
   * The conference link the copy offers, which the description carries because
   * CONFERENCE support is patchy across clients.
   *
   * <p>
   * Pinned because the shared description builder takes the link over: it must
   * still reach the reader, and still exactly once.
   */
  @Test
  public void theConferenceLinkIsCarriedOnceInTheDescription() {
    String description = property(writer.write(meeting()), "DESCRIPTION");

    assertNotNull(description);
    assertEquals(1,
                 countOccurrences(description, "https://meet.example.com/room"),
                 "the conference link belongs in the description exactly once: " + description);
  }

  /**
   * The defect the copy had that the mail did not: nothing saying where the
   * meeting came from.
   *
   * <p>
   * A delivered copy was verified to carry SUMMARY and ORGANIZER and no
   * DESCRIPTION at all. The mail says "Invitation sent by X in space
   * Chemistry"; the copy said nothing — and it is the copy that needs it, since
   * it sits in the user's own calendar among fifty other entries with no clue
   * which system put it there. Both channels now take the sentence from the
   * same builder.
   */
  @Test
  public void theCopySaysWhoCalledTheMeetingAndFromWhichSpace() {
    String description = property(writer.write(meeting()), "DESCRIPTION");

    assertNotNull(description, "the copy must describe where it came from");
    assertTrue(description.contains("The Organiser"), "the sender must be named: " + description);
    assertTrue(description.contains("Chemistry"), "the space must be named: " + description);
  }

  /**
   * The copy says where the meeting lives, in URL and in the description.
   *
   * <p>
   * URL is "where this event lives" (RFC 5545 &sect;3.8.4.6) — the event in
   * eXo, never the video call, which has its own CONFERENCE property. The
   * description repeats it beside the conference line because many clients
   * never surface URL, and the description is what a person reads
   * (EXO-89751).
   */
  @Test
  public void theCopySaysWhereTheMeetingLives() {
    String ics = writer.write(meeting());

    String url = property(ics, "URL");
    assertNotNull(url, "the copy must carry a way back to the event");
    assertTrue(url.contains(EXO_LINK), "URL must be the event in eXo: " + url);
    assertFalse(url.contains("meet.example.com"), "URL must not be the conference link: " + url);

    assertNotNull(property(ics, "CONFERENCE"), "and the video call keeps its own property");

    String description = property(ics, "DESCRIPTION");
    assertNotNull(description);
    assertTrue(description.contains(EXO_LINK), "the description must name the link too: " + description);
  }

  /**
   * One meeting carrying everything the five defects are about: a description
   * stored as markup, an accented word written as an entity, a conference
   * link, an organizer and one other attendee.
   *
   * <p>
   * The description is composed through {@link EventIcsBuilder} rather than
   * handed to the writer as raw markup, because that is where it is composed in
   * production: since EXO-89732 the rendering happens in
   * {@link org.exoplatform.caldav.service.AgendaEventIcsMapper}, where the
   * identity and calendar services are, and the writer takes the plain text
   * RFC 5545 &sect;3.8.1.5 defines DESCRIPTION to be. Building the fixture the
   * same way is what keeps these tests a check on the copy a user actually
   * receives rather than on an input production never produces.
   *
   * @return the event to write
   */
  private IcsEvent meeting() {
    return IcsEvent.builder()
                   .uid("uid-payload-1")
                   .summary("Steering point")
                   .description(EventIcsBuilder.description(Locale.ENGLISH,
                                                            "The Organiser",
                                                            "Chemistry",
                                                            "https://meet.example.com/room",
                                                            EXO_LINK,
                                                            null,
                                                            "<p>Bring the <b>slides</b>.</p>"
                                                                + "<p>R&eacute;union pr&eacute;par&eacute;e</p>"))
                   .conferenceUrl("https://meet.example.com/room")
                   .eventUrl(EXO_LINK)
                   .start(Instant.parse("2026-09-08T07:00:00Z"))
                   .end(Instant.parse("2026-09-08T08:00:00Z"))
                   .timeZoneId("Europe/Paris")
                   .organizer(new IcsPerson("The Organiser", "organiser@example.com", null))
                   .attendees(List.of(new IcsPerson("A Guest", "guest@example.com", "ACCEPTED")))
                   .exceptionDates(List.of())
                   .reminders(List.of())
                   .build();
  }

  /**
   * Reads one property out of an iCalendar document, unfolding the continuation
   * lines RFC 5545 &sect;3.1 allows a writer to break after 75 octets — without
   * which a description long enough to be folded cannot be matched whole.
   *
   * @param ics the document
   * @param propertyName the property name, without its parameters
   * @return the whole property line, or null when the document has none
   */
  private String property(String ics, String propertyName) {
    String unfolded = ics.replace("\r\n ", "").replace("\r\n\t", "").replace("\n ", "").replace("\n\t", "");
    return Arrays.stream(unfolded.split("\\R"))
                 .filter(line -> line.equals(propertyName) || line.startsWith(propertyName + ":")
                     || line.startsWith(propertyName + ";"))
                 .findFirst()
                 .orElse(null);
  }

  /**
   * How many times one token appears in a value.
   *
   * @param value the value to scan
   * @param token the token to count
   * @return the number of occurrences
   */
  private int countOccurrences(String value, String token) {
    int count = 0;
    int index = value.indexOf(token);
    while (index >= 0) {
      count++;
      index = value.indexOf(token, index + token.length());
    }
    return count;
  }
}
