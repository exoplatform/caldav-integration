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
package org.exoplatform.caldav.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The comparator's own correctness, proved in both directions — because the
 * comparator is the piece that decides whether PR3's engine is faithful, a
 * comparator that shrugs at a real difference or cries at a cosmetic one
 * would silently invalidate the whole harness. Every "cosmetic" case here is
 * a difference the two serialisers (the browser's hand-built strings +
 * ical.js, and PR3's ical4j) legitimately produce; every "semantic" case is a
 * bug class the harness exists to catch, taken from the connector's own
 * documented history and from EXO-89402.
 */
public class IcsSemanticComparatorTest {

  private static final String PARIS_VTIMEZONE = """
      BEGIN:VTIMEZONE
      TZID:Europe/Paris
      BEGIN:DAYLIGHT
      TZOFFSETFROM:+0100
      TZOFFSETTO:+0200
      DTSTART:19700329T020000
      RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=3
      END:DAYLIGHT
      BEGIN:STANDARD
      TZOFFSETFROM:+0200
      TZOFFSETTO:+0100
      DTSTART:19701025T030000
      RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=10
      END:STANDARD
      END:VTIMEZONE
      """;

  /**
   * Wraps VEVENT (and optional VTIMEZONE) content into a full VCALENDAR with
   * CRLF endings, the way a calendar object travels.
   *
   * @param prodId the PRODID the object claims
   * @param timezone a VTIMEZONE block, or an empty string
   * @param eventLines the VEVENT content lines, without BEGIN/END
   * @return the calendar object as ICS text
   */
  private static String calendarOf(String prodId, String timezone, String eventLines) {
    String body = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:" + prodId + "\n" + timezone + "BEGIN:VEVENT\n" + eventLines
        + "END:VEVENT\nEND:VCALENDAR\n";
    return body.replace("\r\n", "\n").replace("\n", "\r\n");
  }

  /**
   * Asserts two objects compare as semantically equal, printing every
   * reported difference when they do not.
   *
   * @param left one calendar object
   * @param right the other calendar object
   */
  private static void assertSame(String left, String right) {
    List<SemanticDifference> differences = IcsSemanticComparator.compare(left, right);
    assertTrue(differences.isEmpty(), () -> "expected no semantic difference, got:\n" + join(differences));
  }

  /**
   * Asserts two objects compare as different, and returns the differences so
   * the caller can pin what was flagged.
   *
   * @param left one calendar object
   * @param right the other calendar object
   * @return the reported differences, never empty
   */
  private static List<SemanticDifference> assertDifferent(String left, String right) {
    List<SemanticDifference> differences = IcsSemanticComparator.compare(left, right);
    assertFalse(differences.isEmpty(), "expected a semantic difference, got none");
    return differences;
  }

  /**
   * Joins differences for a failure message.
   *
   * @param differences the reported differences
   * @return one line per difference
   */
  private static String join(List<SemanticDifference> differences) {
    return differences.stream().map(SemanticDifference::toString).reduce((a, b) -> a + "\n" + b).orElse("");
  }

  /**
   * Folding, property order, DTSTAMP and PRODID are exactly the differences
   * two healthy serialisers produce: none of them may register.
   */
  @Test
  public void foldingPropertyOrderDtstampAndProdidNeverRegister() {
    String left = calendarOf("-//Exo Platform//NONSGML v1.0//EN",
                             "",
                             """
                                 UID:cosmetic-1
                                 DTSTAMP:20260820T100000Z
                                 SUMMARY:A summary long enough to be folded by one serialiser and left alone by the other one entirely
                                 DTSTART:20260908T090000Z
                                 DTEND:20260908T100000Z
                                 """);
    // Same statements: other DTSTAMP, other PRODID, properties in another
    // order, and the summary folded mid-word with CRLF + space.
    String right = ("BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//ical4j 3.2//EN\nBEGIN:VEVENT\n"
        + "DTSTART:20260908T090000Z\nDTEND:20260908T100000Z\n"
        + "SUMMARY:A summary long enough to be folded by one serialiser and left alo\n ne by the other one entirely\n"
        + "DTSTAMP:20261231T235959Z\nUID:cosmetic-1\nEND:VEVENT\nEND:VCALENDAR\n").replace("\n", "\r\n");
    assertSame(left, right);
  }

  /**
   * A single timed event stated in UTC and the same event stated as a Paris
   * wall clock with its VTIMEZONE denote the same instant: equal.
   */
  @Test
  public void utcAndEquivalentTzidFormOfOneInstantAreEqual() {
    String utc = calendarOf("-//A//EN", "", """
        UID:form-1
        DTSTAMP:20260820T100000Z
        SUMMARY:One instant, two spellings
        DTSTART:20260908T090000Z
        DTEND:20260908T100000Z
        """);
    String tzid = calendarOf("-//B//EN", PARIS_VTIMEZONE, """
        UID:form-1
        DTSTAMP:20260820T100000Z
        SUMMARY:One instant, two spellings
        DTSTART;TZID=Europe/Paris:20260908T110000
        DTEND;TZID=Europe/Paris:20260908T120000
        """);
    assertSame(utc, tzid);
  }

  /**
   * The flagship: a weekly series anchored in UTC and one anchored on the
   * Paris wall clock agree on every occurrence before the October DST
   * transition and drift an hour apart after it — the EXO-89402 class. The
   * per-property instants are equal, so only the recurrence expansion can see
   * it, and it must.
   */
  @Test
  public void utcAnchoredAndTzidAnchoredSeriesDivergeAcrossDstTransition() {
    String utcAnchored = calendarOf("-//A//EN", "", """
        UID:drift-1
        DTSTAMP:20260820T100000Z
        SUMMARY:Morning stand-up
        DTSTART:20261012T070000Z
        DTEND:20261012T071500Z
        RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=8
        """);
    String tzidAnchored = calendarOf("-//B//EN", PARIS_VTIMEZONE, """
        UID:drift-1
        DTSTAMP:20260820T100000Z
        SUMMARY:Morning stand-up
        DTSTART;TZID=Europe/Paris:20261012T090000
        DTEND;TZID=Europe/Paris:20261012T091500
        RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=8
        """);
    List<SemanticDifference> differences = assertDifferent(utcAnchored, tzidAnchored);
    assertTrue(differences.stream().anyMatch(difference -> difference.getKind() == SemanticDifference.Kind.EXPANSION),
               () -> "the drift must be reported by the recurrence expansion, got:\n" + join(differences));
    // The first diverging occurrence is the one following the 2026-10-25
    // transition: 07:00Z stays 07:00Z on one side, becomes 08:00Z on the other.
    SemanticDifference drift = differences.stream()
                                          .filter(difference -> difference.getKind() == SemanticDifference.Kind.EXPANSION)
                                          .findFirst()
                                          .orElseThrow();
    assertTrue(drift.getLeft().contains("2026-10-26T07:00:00Z") && drift.getRight().contains("2026-10-26T08:00:00Z"),
               () -> "expected the divergence on the first post-transition Monday, got: " + drift);
  }

  /**
   * A trigger stated in minutes and the same trigger stated in hours, a CN
   * quoted and bare, parameters in another order, and explicit RFC defaults
   * (TRANSP:OPAQUE, SEQUENCE:0) against their absence: all equal.
   */
  @Test
  public void equivalentDurationsParametersAndDefaultsAreEqual() {
    String left = calendarOf("-//A//EN", "", """
        UID:equiv-1
        DTSTAMP:20260820T100000Z
        SUMMARY:Board meeting
        DTSTART:20260930T080000Z
        DTEND:20260930T090000Z
        TRANSP:OPAQUE
        SEQUENCE:0
        ORGANIZER;CN="Martin, Alice";SCHEDULE-AGENT=CLIENT:mailto:alice.martin@example.test
        BEGIN:VALARM
        ACTION:DISPLAY
        DESCRIPTION:Board meeting
        TRIGGER:-PT60M
        END:VALARM
        """);
    String right = calendarOf("-//B//EN", "", """
        UID:equiv-1
        DTSTAMP:20260820T100000Z
        SUMMARY:Board meeting
        DTSTART:20260930T080000Z
        DTEND:20260930T090000Z
        ORGANIZER;SCHEDULE-AGENT=CLIENT;CN="Martin, Alice":mailto:Alice.Martin@example.test
        BEGIN:VALARM
        ACTION:DISPLAY
        DESCRIPTION:Board meeting
        TRIGGER;RELATED=START:-PT1H
        END:VALARM
        """);
    assertSame(left, right);
  }

  /**
   * A start shifted by one hour — the exact same text everywhere else — must
   * register.
   */
  @Test
  public void aShiftedStartRegisters() {
    String reference = calendarOf("-//A//EN", "", """
        UID:shift-1
        DTSTAMP:20260820T100000Z
        SUMMARY:Steering point
        DTSTART:20260908T090000Z
        DTEND:20260908T100000Z
        """);
    String shifted = reference.replace("DTSTART:20260908T090000Z", "DTSTART:20260908T100000Z");
    assertDifferent(reference, shifted);
  }

  /**
   * A dropped attendee and a changed participation status must both register:
   * the roster on the phone is part of the meaning.
   */
  @Test
  public void aDroppedAttendeeAndAChangedPartstatRegister() {
    String reference = calendarOf("-//A//EN", "", """
        UID:att-1
        DTSTAMP:20260820T100000Z
        SUMMARY:QBR
        DTSTART:20260924T120000Z
        DTEND:20260924T133000Z
        ORGANIZER;CN="Martin, Alice":mailto:alice.martin@example.test
        ATTENDEE;CN="John Doe";PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:mailto:john.doe@example.test
        ATTENDEE;CN="Sam Waverly";PARTSTAT=TENTATIVE;SCHEDULE-AGENT=CLIENT:mailto:sam@example.test
        """);
    String dropped = reference.replace(
        "ATTENDEE;CN=\"Sam Waverly\";PARTSTAT=TENTATIVE;SCHEDULE-AGENT=CLIENT:mailto:sam@example.test\r\n", "");
    assertDifferent(reference, dropped);
    String demoted = reference.replace("PARTSTAT=ACCEPTED", "PARTSTAT=DECLINED");
    assertDifferent(reference, demoted);
  }

  /**
   * A changed rule, a lost exclusion and a lost override each must register —
   * the series bugs the connector's history is made of.
   */
  @Test
  public void aChangedRuleALostExdateAndALostOverrideRegister() {
    String override = """
        BEGIN:VEVENT
        UID:series-1
        DTSTAMP:20260820T100000Z
        RECURRENCE-ID;TZID=Europe/Paris:20260916T150000
        SUMMARY:Design review (moved)
        DTSTART;TZID=Europe/Paris:20260916T170000
        DTEND;TZID=Europe/Paris:20260916T180000
        END:VEVENT
        """;
    String reference = ("BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//A//EN\n" + PARIS_VTIMEZONE + "BEGIN:VEVENT\n" + """
        UID:series-1
        DTSTAMP:20260820T100000Z
        SUMMARY:Design review
        DTSTART;TZID=Europe/Paris:20260902T150000
        DTEND;TZID=Europe/Paris:20260902T160000
        RRULE:FREQ=WEEKLY;BYDAY=WE
        EXDATE;TZID=Europe/Paris:20260909T150000
        END:VEVENT
        """ + override + "END:VCALENDAR\n").replace("\n", "\r\n");
    assertDifferent(reference, reference.replace("BYDAY=WE", "BYDAY=TH"));
    assertDifferent(reference, reference.replace("EXDATE;TZID=Europe/Paris:20260909T150000\r\n", ""));
    assertDifferent(reference, reference.replace(override.replace("\n", "\r\n"), ""));
  }

  /**
   * An all-day end off by one day — the exclusive-DTEND bug — must register,
   * while the identical statement is equal.
   */
  @Test
  public void anAllDayEndOffByOneDayRegisters() {
    String reference = calendarOf("-//A//EN", "", """
        UID:allday-1
        DTSTAMP:20260820T100000Z
        SUMMARY:Company holiday
        DTSTART;VALUE=DATE:20260910
        DTEND;VALUE=DATE:20260911
        """);
    assertSame(reference, calendarOf("-//B//EN", "", """
        UID:allday-1
        DTSTAMP:20260820T100000Z
        SUMMARY:Company holiday
        DTSTART;VALUE=DATE:20260910
        DTEND;VALUE=DATE:20260911
        """));
    assertDifferent(reference, reference.replace("DTEND;VALUE=DATE:20260911", "DTEND;VALUE=DATE:20260910"));
  }

  /**
   * A lost alarm and changed text must register; and an EXDATE list written
   * on one line equals the same exclusions written as two lines.
   */
  @Test
  public void aLostAlarmChangedTextAndSplitExdateListsBehave() {
    String reference = calendarOf("-//A//EN", "", """
        UID:alarm-1
        DTSTAMP:20260820T100000Z
        SUMMARY:Ops daily
        DESCRIPTION:Salle « Turing »\\, 3e étage\\; apporter les métriques
        DTSTART:20260901T063000Z
        DTEND:20260901T064500Z
        RRULE:FREQ=WEEKLY;BYDAY=TU,TH
        EXDATE:20260910T063000Z,20260922T063000Z
        BEGIN:VALARM
        ACTION:DISPLAY
        DESCRIPTION:Ops daily
        TRIGGER:-PT10M
        END:VALARM
        """);
    String twoLines = reference.replace("EXDATE:20260910T063000Z,20260922T063000Z",
                                        "EXDATE:20260910T063000Z\r\nEXDATE:20260922T063000Z");
    assertSame(reference, twoLines);
    String withoutAlarm = reference.replace(
        "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Ops daily\r\nTRIGGER:-PT10M\r\nEND:VALARM\r\n", "");
    assertDifferent(reference, withoutAlarm);
    assertDifferent(reference, reference.replace("apporter les métriques", "apporter les slides"));
    List<SemanticDifference> differences =
                                         IcsSemanticComparator.compare(reference,
                                                                       reference.replace("EXDATE:20260910T063000Z,20260922T063000Z",
                                                                                         "EXDATE:20260910T063000Z"));
    assertEquals(SemanticDifference.Kind.PROPERTY,
                 differences.stream()
                            .filter(difference -> difference.getKind() == SemanticDifference.Kind.PROPERTY)
                            .findFirst()
                            .orElseThrow()
                            .getKind(),
                 "a dropped exclusion is a property-level difference");
    assertFalse(differences.isEmpty(), "dropping one exclusion of a list must register");
  }
}
