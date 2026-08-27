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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Every equivalence decision the mirror comparison makes, one test each, in
 * both directions.
 *
 * <p>
 * The two directions are not symmetric in cost. Calling a normalised copy
 * "different" produces a rewrite every five minutes for ever — the defect
 * EXO-89716 exists to end. Calling an edited copy "equal" loses a user's change
 * silently, with nothing in any log to find it by. So each relaxation below is
 * paired with the edit it must still catch, and a relaxation with no such pair
 * would be an unexamined one.
 */
public class IcsEquivalenceTest {

  /** The judge under test. */
  private IcsEquivalence  judge;

  /** What eXo renders: the reference every case varies from. */
  private static final String EXO = "BEGIN:VCALENDAR\r\n"
      + "VERSION:2.0\r\n"
      + "PRODID:-//Exo Platform//NONSGML v1.0//EN\r\n"
      + "CALSCALE:GREGORIAN\r\n"
      + "BEGIN:VEVENT\r\n"
      + "SUMMARY:Sprint review\r\n"
      + "UID:evt-1\r\n"
      + "DTSTAMP:20260901T080000Z\r\n"
      + "DTSTART:20260901T090000Z\r\n"
      + "DTEND:20260901T100000Z\r\n"
      + "LOCATION:Room 3\r\n"
      + "DESCRIPTION:Bring the board\r\n"
      + "ORGANIZER;CN=The Boss:mailto:boss@acme.test\r\n"
      + "ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:mailto:ann@acme.test\r\n"
      + "STATUS:CONFIRMED\r\n"
      + "TRANSP:OPAQUE\r\n"
      + "BEGIN:VALARM\r\n"
      + "ACTION:DISPLAY\r\n"
      + "DESCRIPTION:Sprint review\r\n"
      + "TRIGGER:-PT15M\r\n"
      + "END:VALARM\r\n"
      + "END:VEVENT\r\n"
      + "END:VCALENDAR\r\n";

  /**
   * Both addresses a copy on this account may name its owner by: the one their
   * CalDAV account answers to, and their eXo profile address. They differ, and
   * that is the point — every test below runs with the pair, so nothing here
   * can pass by having guessed the right one.
   */
  private static final java.util.List<String> OWNER = java.util.List.of("alice@stalwart.local", "bob@stalwart.local");

  /** A zone definition an object can carry so a TZID in it resolves. */
  private static final String PARIS = "BEGIN:VTIMEZONE\r\n"
      + "TZID:Europe/Paris\r\n"
      + "BEGIN:STANDARD\r\n"
      + "DTSTART:19701025T030000\r\n"
      + "TZOFFSETFROM:+0200\r\n"
      + "TZOFFSETTO:+0100\r\n"
      + "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"
      + "END:STANDARD\r\n"
      + "BEGIN:DAYLIGHT\r\n"
      + "DTSTART:19700329T020000\r\n"
      + "TZOFFSETFROM:+0100\r\n"
      + "TZOFFSETTO:+0200\r\n"
      + "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n"
      + "END:DAYLIGHT\r\n"
      + "END:VTIMEZONE\r\n";

  /**
   * A judge with nothing configured away, which is the shipped default.
   */
  @BeforeEach
  public void aJudgeWithNothingIgnored() {
    judge = new IcsEquivalence();
    ReflectionTestUtils.setField(judge, "ignoredProperties", "");
  }

  // ---------------------------------------------------------------- DTSTAMP

  @Test
  public void aDifferentDtstampIsNotAnEdit() {
    // IcsWriter writes "now" into DTSTAMP on every render, so comparing it
    // would make the object differ from itself on every single pass.
    assertEquivalent(EXO.replace("DTSTAMP:20260901T080000Z", "DTSTAMP:20261114T235959Z"));
  }

  // ------------------------------------------------- property & param order

  @Test
  public void aDifferentPropertyOrderIsNotAnEdit() {
    // A server holds a model, not a document; the order it writes properties
    // back in is its own. Nothing about the meeting depends on it.
    assertEquivalent(EXO.replace("SUMMARY:Sprint review\r\nUID:evt-1", "UID:evt-1\r\nSUMMARY:Sprint review"));
  }

  @Test
  public void aDifferentParameterOrderIsNotAnEdit() {
    assertEquivalent(EXO.replace("ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:",
                                 "ATTENDEE;SCHEDULE-AGENT=CLIENT;PARTSTAT=ACCEPTED;CN=Ann:"));
  }

  // ------------------------------------------- what the parser already does

  // The four tests below are characterisations, not pins on code in
  // IcsEquivalence: ical4j unfolds, unescapes, trims and canonicalises a
  // duration before the comparison ever sees a value, and the comparison used
  // to redo all four until a mutation check showed no test could kill that
  // code. They are kept because the mirror pass depends on the behaviour
  // whoever provides it — an ical4j upgrade that stopped unescaping would make
  // every copy carrying a comma look rewritten, and this is what would say so.

  @Test
  public void aRefoldedLineIsNotAnEdit() {
    // RFC 5545 lets a line be broken at 75 octets and continued with a space.
    // Where a server chooses to break is not a statement about the meeting.
    assertEquivalent(EXO.replace("DESCRIPTION:Bring the board", "DESCRIPTION:Bring th\r\n e board"));
  }

  @Test
  public void aRewrittenDescriptionIsAnEdit() {
    // The pair for unfolding: the text itself still counts.
    assertDifferent(EXO.replace("Bring the board", "Bring the board and the laptop"));
  }

  @Test
  public void aDifferentlyEscapedTextIsNotAnEdit() {
    // A comma may be escaped or not depending on which library last wrote the
    // value; the reader sees the same characters either way.
    assertEquivalent(EXO.replace("SUMMARY:Sprint review", "SUMMARY:Sprint review")
                        .replace("LOCATION:Room 3", "LOCATION:Room\\, 3"),
                     EXO.replace("LOCATION:Room 3", "LOCATION:Room, 3"));
  }

  @Test
  public void surroundingWhitespaceIsNotAnEdit() {
    // Nobody renames a meeting by putting a space after it, and servers trim.
    assertEquivalent(EXO.replace("SUMMARY:Sprint review", "SUMMARY: Sprint review "));
  }

  // ------------------------------------------------------- empty vs absent

  @Test
  public void anEmptyValueEqualsTheAbsenceOfTheProperty() {
    // eXo writes SUMMARY unconditionally, empty included, and a server may drop
    // an empty property as saying nothing. This can only ever collapse empty
    // against absent — never empty against a value somebody wrote, which is the
    // next test.
    String blankInExo = EXO.replace("SUMMARY:Sprint review\r\n", "SUMMARY:\r\n");
    String droppedByServer = EXO.replace("SUMMARY:Sprint review\r\n", "");

    assertEquivalent(droppedByServer, blankInExo);
  }

  @Test
  public void aDroppedSummaryIsAnEditWhenExoHasOne() {
    assertDifferent(EXO.replace("SUMMARY:Sprint review\r\n", ""));
  }

  // ------------------------------------------------------------ TZID form

  @Test
  public void aTzidRespeltByTheServerIsNotAnEdit() {
    // BlueMind writes /freeassociation.sourceforge.net/Europe/Paris where eXo
    // writes Europe/Paris. Each side resolves its own TZID through the zone its
    // own object carries, so the identifier itself is never compared — only the
    // instant it produces.
    String exoZoned = zoned(EXO, "Europe/Paris", "20260901T110000", "20260901T120000", PARIS);
    String serverZoned = zoned(EXO,
                               "/freeassociation.sourceforge.net/Europe/Paris",
                               "20260901T110000",
                               "20260901T120000",
                               PARIS.replace("TZID:Europe/Paris",
                                             "TZID:/freeassociation.sourceforge.net/Europe/Paris"));

    assertEquivalent(serverZoned, exoZoned);
  }

  @Test
  public void aUtcTimeRestatedOnAZoneIsNotAnEditWhenItIsTheSameInstant() {
    // The re-anchoring the live defect tripped over: 09:00 UTC and 11:00 on
    // Europe/Paris in September are the same moment.
    assertEquivalent(zoned(EXO, "Europe/Paris", "20260901T110000", "20260901T120000", PARIS));
  }

  @Test
  public void aWallClockThatDenotesAnotherInstantIsAnEdit() {
    // The pair for the above, and the reason the fold is on the instant rather
    // than on the form: same TZID, one hour later, a different meeting.
    assertDifferent(zoned(EXO, "Europe/Paris", "20260901T120000", "20260901T130000", PARIS));
  }

  @Test
  public void aTzidNothingResolvesIsNotSilentlyEqualToAnAnchoredTime() {
    // A dangling zone reference is a floating time, which is what a client
    // reading it will do with it. Treating it as if it resolved would call a
    // copy nobody can place equal to one anchored on a real zone.
    assertDifferent(zoned(EXO, "Mars/Olympus", "20260901T110000", "20260901T120000", ""));
  }

  // ------------------------------------------------------------- defaults

  @Test
  public void aServerDroppingTheRedundantTranspIsNotAnEdit() {
    // TRANSP:OPAQUE is the RFC 5545 default; eXo writes it for explicitness and
    // a server is free not to.
    assertEquivalent(EXO.replace("TRANSP:OPAQUE\r\n", ""));
  }

  @Test
  public void markingTheMeetingFreeIsAnEdit() {
    // The pair: OPAQUE equals its own absence, TRANSPARENT does not equal
    // either. Somebody chose to stop being busy during this meeting.
    assertDifferent(EXO.replace("TRANSP:OPAQUE", "TRANSP:TRANSPARENT"));
  }

  @Test
  public void aServerAddingTheDefaultSequenceIsNotAnEdit() {
    assertEquivalent(EXO.replace("STATUS:CONFIRMED", "SEQUENCE:0\r\nSTATUS:CONFIRMED"));
  }

  @Test
  public void aBumpedSequenceIsAnEdit() {
    // SEQUENCE above zero is a client saying it revised the event.
    assertDifferent(EXO.replace("STATUS:CONFIRMED", "SEQUENCE:1\r\nSTATUS:CONFIRMED"));
  }

  @Test
  public void aServerFillingInTheDefaultAttendeeParametersIsNotAnEdit() {
    // ROLE, CUTYPE and RSVP at the values the RFC already implies.
    assertEquivalent(EXO.replace("ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:",
                                 "ATTENDEE;CN=Ann;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;RSVP=FALSE;"
                                     + "PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:"));
  }

  @Test
  public void aChangedPartstatIsAnEdit() {
    // The pair for the parameter defaults, and the one that matters most: an
    // answer given on the copy is a real change to it.
    assertDifferent(EXO.replace("PARTSTAT=ACCEPTED", "PARTSTAT=DECLINED"));
  }

  // ------------------------------------------------------- SCHEDULE-AGENT

  @Test
  public void aServerConsumingScheduleAgentIsNotAnEdit() {
    // RFC 6638 SCHEDULE-AGENT is an instruction to the server about how to
    // process the write, not a statement about the meeting. eXo sends it on
    // every attendee, and a scheduling-aware server that consumes it would
    // otherwise leave every copy permanently altered.
    assertEquivalent(EXO.replace(";SCHEDULE-AGENT=CLIENT", ""));
  }

  @Test
  public void aServerAddingScheduleStatusIsNotAnEdit() {
    assertEquivalent(EXO.replace("PARTSTAT=ACCEPTED", "PARTSTAT=ACCEPTED;SCHEDULE-STATUS=2.0"));
  }

  // ------------------------------------------------- CREATED, LAST-MODIFIED

  @Test
  public void aRefreshedLastModifiedIsNotAnEdit() {
    // A timestamp about the record, which a server sets when it stores. It
    // cannot be authored as an intent, and any real edit moves something else
    // that is compared — the next test being one.
    assertEquivalent(EXO.replace("STATUS:CONFIRMED", "LAST-MODIFIED:20261114T235959Z\r\nSTATUS:CONFIRMED"));
  }

  // ---------------------------------------------------------------- URL

  @Test
  public void theLinkBackIntoExoIsNotCompared() {
    // It is carried on the push request and never stored, so a sweep renders
    // none. Comparing it would report every copy altered exactly once and then
    // strip the very link it complained about, because a repair cannot
    // reconstruct it either.
    assertEquivalent(EXO.replace("STATUS:CONFIRMED", "URL:https://exo.test/portal/event/7\r\nSTATUS:CONFIRMED"));
  }

  @Test
  public void aTriggerWrittenAnotherWayIsNotAnEdit() {
    // -PT15M and -PT900S are the same reminder, and RELATED=START is the
    // default the RFC already implies.
    assertEquivalent(EXO.replace("TRIGGER:-PT15M", "TRIGGER;RELATED=START:-PT900S"));
  }

  // --------------------------------------------------------------- alarms

  @Test
  public void aMovedReminderIsAnEdit() {
    assertDifferent(EXO.replace("TRIGGER:-PT15M", "TRIGGER:-PT60M"));
  }

  @Test
  public void aDismissedAlarmIsNotAnEdit() {
    // ACKNOWLEDGED records that somebody dismissed the reminder — per-viewer
    // state. Rewriting the copy over it would resurrect an alarm the user has
    // just put away.
    assertEquivalent(EXO.replace("TRIGGER:-PT15M", "TRIGGER:-PT15M\r\nACKNOWLEDGED:20260901T084500Z"));
  }

  @Test
  public void aDeletedAlarmIsAnEdit() {
    assertDifferent(EXO.replaceAll("(?s)BEGIN:VALARM.*END:VALARM\r\n", ""));
  }

  // ------------------------------------------------- the owner's own line

  // The relaxation EXO-89716's first deploy forced, and the tests that bound
  // it. BlueMind attaches the calendar's owner to every copy that lands in it,
  // as ATTENDEE;CN=FRANCOIS;DIR=bm://19d43..., which made all 20 copies of a
  // live account altered and re-pushed on every sweep — and no repair could
  // remove it, because the server puts the line straight back. The exemption
  // is one line of code; what follows is the fence around it.

  @Test
  public void anOwnerTheServerAttachedIsNotAnEdit() {
    // The observation itself, spelled as BlueMind spells it.
    assertEquivalent(EXO.replace("STATUS:CONFIRMED",
                                 "ATTENDEE;CN=FRANCOIS;DIR=\"bm://19d43a7c-dead-beef\":mailto:alice@stalwart.local\r\n"
                                     + "STATUS:CONFIRMED"));
  }

  @Test
  public void anOwnerTheServerAttachedUnderTheirOtherAddressIsAlsoNotAnEdit() {
    // The EXO-89715 trap, made a test. A copy names its owner either by the
    // address their CalDAV account answers to or by their eXo profile address,
    // and an exemption that recognised only one of the two would miss half the
    // time — silently, and in the direction that churns.
    assertEquivalent(EXO.replace("STATUS:CONFIRMED",
                                 "ATTENDEE;CN=FRANCOIS:mailto:bob@stalwart.local\r\nSTATUS:CONFIRMED"));
  }

  @Test
  public void anOwnerLineTheServerReRenderedIsNotAnEdit() {
    // The other shape it takes: eXo already wrote the owner's line and the
    // server rewrote it in its own terms — its directory's spelling of the
    // name, its own DIR pointer, the address re-cased. Same person, same
    // answer, so the same statement.
    String exo = EXO.replace("STATUS:CONFIRMED",
                             "ATTENDEE;CN=Alice Martin;PARTSTAT=ACCEPTED:mailto:alice@stalwart.local\r\n"
                                 + "STATUS:CONFIRMED");
    String server = EXO.replace("STATUS:CONFIRMED",
                                "ATTENDEE;CN=ALICE;DIR=\"bm://19d43a7c\";PARTSTAT=ACCEPTED:"
                                    + "MAILTO:Alice@Stalwart.Local\r\nSTATUS:CONFIRMED");

    assertEquivalent(server, exo);
  }

  @Test
  public void anOwnerAttachedWithAnAnswerOnItIsStillAnEdit() {
    // The condition that keeps EXO-89681 working. A surplus owner line saying
    // ACCEPTED is not the server attaching somebody — it is the user replying
    // from their own client, and the pass has to report it so the answer can be
    // read off the copy before anything overwrites it.
    assertDifferent(EXO.replace("STATUS:CONFIRMED",
                                "ATTENDEE;CN=FRANCOIS;PARTSTAT=ACCEPTED:mailto:alice@stalwart.local\r\n"
                                    + "STATUS:CONFIRMED"));
  }

  @Test
  public void anOwnerWhoAnsweredOnTheirPhoneIsStillAnEdit() {
    // The same, for a copy whose owner line eXo does write. This is the case
    // the whole answer feature rests on, and the exemption must not touch it.
    String exo = EXO.replace("STATUS:CONFIRMED",
                             "ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:alice@stalwart.local\r\nSTATUS:CONFIRMED");
    String server = EXO.replace("STATUS:CONFIRMED",
                                "ATTENDEE;CN=ALICE;DIR=\"bm://19d4\";PARTSTAT=DECLINED:mailto:alice@stalwart.local\r\n"
                                    + "STATUS:CONFIRMED");

    assertDifferent(server, exo);
  }

  @Test
  public void anOwnerTheCopyLostIsStillAnEdit() {
    // The surplus has to be on the server's side. eXo stating a line the copy
    // no longer carries is somebody having removed the owner from the meeting,
    // which is a difference in the ordinary way.
    String exo = EXO.replace("STATUS:CONFIRMED",
                             "ATTENDEE;PARTSTAT=ACCEPTED:mailto:alice@stalwart.local\r\nSTATUS:CONFIRMED");

    assertDifferent(EXO, exo);
  }

  @Test
  public void anOwnerWhoHadNotAnsweredAndWasRemovedIsStillAnEdit() {
    // The direction condition, on its own. The line eXo states carries no
    // answer, so it reduces to exactly the statement the exemption tolerates —
    // and it must still be reported, because here the surplus is on eXo's side:
    // somebody took the owner off a meeting they had not replied to.
    //
    // Without this the direction condition is untestable: the sibling case
    // above states an ACCEPTED line, which reduces to a different statement and
    // would be caught by the answer condition instead. The mutation check found
    // that, and this is the test it was missing.
    String exo = EXO.replace("STATUS:CONFIRMED",
                             "ATTENDEE;CN=Alice:mailto:alice@stalwart.local\r\nSTATUS:CONFIRMED");

    assertDifferent(EXO, exo);
  }

  @Test
  public void aSecondOwnerLineTheServerAddedIsNotAnEdit() {
    // Decided, not stumbled into. eXo already names the owner by the address
    // their account answers to; the server adds its own directory user beside
    // it under their other address. Two lines, one person, and neither states
    // an answer — so this is the exemption, for the same reason the first line
    // is: the account owns both spellings, and a server keeping its own
    // bookkeeping next to ours has not changed who is invited.
    String exo = EXO.replace("STATUS:CONFIRMED",
                             "ATTENDEE;CN=Alice:mailto:alice@stalwart.local\r\nSTATUS:CONFIRMED");
    String server = EXO.replace("STATUS:CONFIRMED",
                                "ATTENDEE;CN=Alice:mailto:alice@stalwart.local\r\n"
                                    + "ATTENDEE;CN=FRANCOIS;DIR=\"bm://19d43\":mailto:bob@stalwart.local\r\n"
                                    + "STATUS:CONFIRMED");

    assertEquivalent(server, exo);
  }

  @Test
  public void aSecondOwnerLineCarryingAnAnswerIsStillAnEdit() {
    // The limit of the decision above. A duplicate line is bookkeeping; a
    // duplicate line that says ACCEPTED is the user replying, and it has to
    // reach EXO-89681 like any other answer.
    String exo = EXO.replace("STATUS:CONFIRMED",
                             "ATTENDEE;CN=Alice:mailto:alice@stalwart.local\r\nSTATUS:CONFIRMED");
    String server = EXO.replace("STATUS:CONFIRMED",
                                "ATTENDEE;CN=Alice:mailto:alice@stalwart.local\r\n"
                                    + "ATTENDEE;CN=FRANCOIS;PARTSTAT=ACCEPTED:mailto:bob@stalwart.local\r\n"
                                    + "STATUS:CONFIRMED");

    assertDifferent(server, exo);
  }

  @Test
  public void someoneElseTheServerAttachedIsStillAnEdit() {
    // The exemption is the owner and nobody else. A client adding an attendee
    // is a real edit, and it is the thing this pass exists to catch.
    assertDifferent(EXO.replace("STATUS:CONFIRMED",
                                "ATTENDEE;CN=Mallory:mailto:mallory@stalwart.local\r\nSTATUS:CONFIRMED"));
  }

  @Test
  public void anAttendeeTheServerDidNotKeepIsNotAnEdit() {
    // This test asserted the opposite two rounds ago, and the reversal is the
    // architect's decision rather than a discovery here. BlueMind discards
    // attendees whose addresses are not in its own directory — carol@stalwart
    // in a BlueMind copy — so repairing achieves nothing: eXo re-pushes the
    // whole roster, the server drops the same address again, and the next pass
    // says the same thing. For the question this pass asks, an attendee the
    // server declined to carry is not evidence that a client rewrote anything.
    String exo = EXO.replace("STATUS:CONFIRMED",
                             "ATTENDEE;CN=Carol:mailto:carol@stalwart.local\r\nSTATUS:CONFIRMED");

    assertEquivalent(EXO, exo);
  }

  @Test
  public void anAttendeeTheServerAddedIsStillAnEditWhileAnotherIsBeingTolerated() {
    // Both tolerances live at once, which is the only configuration worth
    // testing: each alone is easy to get right. eXo holds Carol and the copy
    // does not (tolerated); the copy holds Mallory and eXo does not (a client
    // added somebody, and it must still register). If these two rules had been
    // written as "compare only the attendees both sides share" this would pass
    // as equivalent and the pass would be blind to exactly what it is for.
    String exo = EXO.replace("STATUS:CONFIRMED",
                             "ATTENDEE;CN=Carol:mailto:carol@stalwart.local\r\nSTATUS:CONFIRMED");
    String server = EXO.replace("STATUS:CONFIRMED",
                                "ATTENDEE;CN=Mallory:mailto:mallory@stalwart.local\r\nSTATUS:CONFIRMED");

    assertDifferent(server, exo);
  }

  @Test
  public void aChangedPartstatIsStillAnEditWhileAnAttendeeIsBeingTolerated() {
    // The one that matters most, with both tolerances active. Ann answered on
    // her phone; Carol was never carried by the server. A PARTSTAT change
    // always leaves a surplus on the SERVER's side — the answered line — and
    // neither rule covers a non-owner surplus there, so it still registers and
    // EXO-89681 still gets to read it.
    String exo = EXO.replace("ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:",
                             "ATTENDEE;CN=Ann;PARTSTAT=NEEDS-ACTION;SCHEDULE-AGENT=CLIENT:")
                    .replace("STATUS:CONFIRMED", "ATTENDEE;CN=Carol:mailto:carol@stalwart.local\r\nSTATUS:CONFIRMED");

    assertDifferent(EXO, exo);
  }

  @Test
  public void theOwnerIsOutsideTheSecondToleranceSoALostOwnerStillRegisters() {
    // The architect's reason for tolerating a dropped attendee — that a repair
    // would be undone on the next write — does not hold for the owner: they are
    // in the server's own directory by construction, so re-pushing their line
    // sticks. Their exemption points the other way and only the other way.
    String exo = EXO.replace("STATUS:CONFIRMED",
                             "ATTENDEE;CN=Alice:mailto:alice@stalwart.local\r\nSTATUS:CONFIRMED");

    assertDifferent(EXO, exo);
  }

  @Test
  public void withNoOwnerDeclaredNothingIsExempt() {
    // The exemption is not a property of ATTENDEE lines, it is a property of
    // one known person. Told about nobody, the comparison is as strict as it
    // was before the relaxation existed.
    IcsEquivalence.Judgement judgement =
        judge.compare(EXO.replace("STATUS:CONFIRMED",
                                  "ATTENDEE;CN=FRANCOIS:mailto:alice@stalwart.local\r\nSTATUS:CONFIRMED"),
                      EXO,
                      java.util.List.of());

    assertEquals(IcsEquivalence.Verdict.DIFFERENT, judgement.verdict());
  }

  @Test
  public void aDirectoryPointerIsNotAnEdit() {
    // DIR is a URI into the server's own directory (RFC 5545 3.2.6) — who the
    // server thinks the person is, in its own namespace. It says nothing about
    // the meeting, and dropping it cannot hide an attendee change: the address
    // and the PARTSTAT are compared regardless, as the two tests above prove.
    assertEquivalent(EXO.replace("ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:",
                                 "ATTENDEE;CN=Ann;DIR=\"bm://c0ffee\";PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:"));
  }

  // ------------------------------------------------------------ unknowns

  @Test
  public void aPropertyNobodyRecognisesIsADifference() {
    // Conservative by construction. eXo emits a closed set, and anything else
    // inside the component it owns is a change it cannot vouch for.
    assertDifferent(EXO.replace("STATUS:CONFIRMED", "X-FAKEMIND-SEQ:41\r\nSTATUS:CONFIRMED"));
  }

  @Test
  public void aPropertyTheOperatorDeclaredUninterestingIsNotADifference() {
    ReflectionTestUtils.setField(judge, "ignoredProperties", " x-fakemind-seq , X-OTHER ");

    assertEquivalent(EXO.replace("STATUS:CONFIRMED", "X-FAKEMIND-SEQ:41\r\nSTATUS:CONFIRMED"));
  }

  @Test
  public void theIgnoreListCannotSilenceAPropertyExoEmits() {
    // The lever is narrow by construction: it only ever suppresses an
    // unrecognised name, so no configuration of it can make a renamed meeting
    // look equal.
    ReflectionTestUtils.setField(judge, "ignoredProperties", "SUMMARY,DTSTART");

    // Only the SUMMARY line: replacing the title everywhere would also change
    // the alarm's DESCRIPTION, and the test would then pass on that instead —
    // which it did, until the mutation check caught it.
    assertDifferent(EXO.replace("SUMMARY:Sprint review", "SUMMARY:Sprint retro"));
  }

  // ---------------------------------- the document's own structural properties

  @Test
  public void aCalendarLevelVersionIsNeverAComponentStatement() {
    // The scoping itself, pinned. VERSION is a calendar-level property and the
    // comparison is scoped to one component, so it must not reach the statement
    // set from any position a server may write it in — before the components,
    // after them, after a VTIMEZONE, or not at all. These four passing is what
    // says the leak that reached production was not the scope going wrong.
    assertEquivalent(EXO.replace("VERSION:2.0\r\n", "").replace("END:VCALENDAR", "VERSION:2.0\r\nEND:VCALENDAR"));
    assertEquivalent(EXO.replace("VERSION:2.0\r\n", ""));
    assertEquivalent(EXO.replace("BEGIN:VEVENT\r\n", PARIS + "BEGIN:VEVENT\r\n")
                        .replace("VERSION:2.0\r\n", "")
                        .replace("END:VCALENDAR", "VERSION:2.0\r\nEND:VCALENDAR"));
  }

  @Test
  public void aVersionTheServerPutInsideTheComponentIsNotAnEdit() {
    // What BlueMind actually does, and what the first deploy of the semantic
    // comparison reported on every copy. It is not conformant — VERSION cannot
    // legally sit in a VEVENT — and it is still not a disagreement: it says the
    // document is iCalendar 2.0, eXo's render says exactly that one level up,
    // and neither is a statement about the meeting.
    assertEquivalent(EXO.replace("BEGIN:VEVENT\r\n", "BEGIN:VEVENT\r\nVERSION:2.0\r\n"));
  }

  @Test
  public void aProdidOrCalscaleInsideTheComponentIsNotAnEditEither() {
    // The same rule, and the reason it is stated by name rather than by
    // position: a server that misplaces one of the three can misplace the
    // others, and none of the three can carry a fact about a meeting.
    assertEquivalent(EXO.replace("BEGIN:VEVENT\r\n",
                                 "BEGIN:VEVENT\r\nPRODID:-//BlueMind//EN\r\nCALSCALE:GREGORIAN\r\n"));
  }

  @Test
  public void theOutlookInteropHintsAreStillReportedSoSomebodyCanDecideAboutThem() {
    // The rest of what the deploy found, kept strict on purpose. These two are
    // proprietary hints nobody could have anticipated, which is exactly what
    // the operator's ignoredProperties lever is for — a decision somebody makes
    // after reading a log line, not one this class makes for them.
    IcsEquivalence.Judgement judgement =
        judge.compare(EXO.replace("BEGIN:VEVENT\r\n", "BEGIN:VEVENT\r\nVERSION:2.0\r\n")
                         .replace("STATUS:CONFIRMED",
                                  "X-MICROSOFT-CDO-BUSYSTATUS:BUSY\r\nX-MICROSOFT-DISALLOW-COUNTER:false\r\n"
                                      + "STATUS:CONFIRMED"),
                      EXO,
                      OWNER);

    assertEquals(IcsEquivalence.Verdict.DIFFERENT, judgement.verdict());
    // And the line names them and nothing else — the VERSION that used to lead
    // it is gone, so whoever reads the log sees only what is still undecided.
    assertEquals("UNRECOGNISED:X-MICROSOFT-CDO-BUSYSTATUS=BUSY (server 1, eXo 0); "
        + "UNRECOGNISED:X-MICROSOFT-DISALLOW-COUNTER=false (server 1, eXo 0)", judgement.detail());
  }

  // ------------------------------------------- scope: what a repair rewrites

  @Test
  public void theEnclosingCalendarsOwnPropertiesAreNotCompared() {
    // A repair merges into the document the server holds and leaves its
    // VCALENDAR properties untouched — so a difference reported here would
    // produce a rewrite that changes nothing and a pass that says the same
    // thing again five minutes later, for ever. Every property compared is one
    // a repair actually rewrites.
    assertEquivalent(EXO.replace("PRODID:-//Exo Platform//NONSGML v1.0//EN", "PRODID:-//FakeMind//Calendar//EN")
                        .replace("CALSCALE:GREGORIAN\r\n", "METHOD:PUBLISH\r\n"));
  }

  @Test
  public void anotherClientsOverrideInTheSameObjectIsNotCompared() {
    // One object holds a whole series, and IcsMerger deliberately preserves
    // components eXo did not write. The comparison is scoped the same way it
    // writes.
    assertEquivalent(EXO.replace("END:VCALENDAR\r\n",
                                 "BEGIN:VEVENT\r\n"
                                     + "UID:evt-1\r\n"
                                     + "RECURRENCE-ID:20260908T090000Z\r\n"
                                     + "DTSTART:20260908T140000Z\r\n"
                                     + "DTEND:20260908T150000Z\r\n"
                                     + "SUMMARY:Sprint review, moved\r\n"
                                     + "END:VEVENT\r\n"
                                     + "END:VCALENDAR\r\n"));
  }

  @Test
  public void theComponentExoOwnsGoingMissingIsADifference() {
    // The other half of the scoping: the object may still be there and hold
    // somebody else's component, and eXo's own is gone.
    assertDifferent(EXO.replace("UID:evt-1", "UID:somebody-elses"));
  }

  // -------------------------------------------------------------- series

  @Test
  public void aRestatedRecurrenceRuleIsNotAnEdit() {
    // Parts in another order, and INTERVAL=1 which is the default anyway.
    String exo = EXO.replace("STATUS:CONFIRMED", "RRULE:FREQ=WEEKLY;BYDAY=MO,WE\r\nSTATUS:CONFIRMED");
    String server = EXO.replace("STATUS:CONFIRMED", "RRULE:BYDAY=WE,MO;INTERVAL=1;FREQ=WEEKLY\r\nSTATUS:CONFIRMED");

    assertEquivalent(server, exo);
  }

  @Test
  public void aChangedRecurrenceRuleIsAnEdit() {
    String exo = EXO.replace("STATUS:CONFIRMED", "RRULE:FREQ=WEEKLY;BYDAY=MO,WE\r\nSTATUS:CONFIRMED");
    String server = EXO.replace("STATUS:CONFIRMED", "RRULE:FREQ=WEEKLY;BYDAY=MO\r\nSTATUS:CONFIRMED");

    assertDifferent(server, exo);
  }

  @Test
  public void aSeriesReAnchoredInUtcIsCaughtWhereTheFirstOccurrenceAloneWouldNot() {
    // The divergence statement equality cannot see. A weekly series anchored on
    // Europe/Paris and the same series anchored in UTC agree on every
    // occurrence up to the October transition and part company after it:
    // identical DTSTARTs, different meetings from then on. This is why the
    // comparison expands a repeating component instead of stopping at its
    // properties.
    String exo = zoned(EXO.replace("STATUS:CONFIRMED", "RRULE:FREQ=WEEKLY\r\nSTATUS:CONFIRMED"),
                       "Europe/Paris",
                       "20260901T110000",
                       "20260901T120000",
                       PARIS);
    String server = EXO.replace("STATUS:CONFIRMED", "RRULE:FREQ=WEEKLY\r\nSTATUS:CONFIRMED");

    assertDifferent(server, exo);
  }

  // -------------------------------------------------------------- refusals

  @Test
  public void anObjectTheServerHoldsThatCannotBeReadIsADifference() {
    // Bounded rather than silent: a repair would fail on the same parse and the
    // pass gives up after a few attempts, saying so in the log — which is the
    // honest outcome for a copy nobody can read.
    IcsEquivalence.Judgement judgement = judge.compare("this is not a calendar object at all", EXO, OWNER);

    assertEquals(IcsEquivalence.Verdict.DIFFERENT, judgement.verdict());
    assertNotNull(judgement.detail());
  }

  @Test
  public void anExoRenderThatCannotBeReadConcludesNothing() {
    // A defect on this side is never evidence about the user's calendar. The
    // caller leaves the copy exactly as it is.
    assertEquals(IcsEquivalence.Verdict.UNJUDGEABLE, judge.compare(EXO, "not a calendar object", OWNER).verdict());
  }

  @Test
  public void anExoRenderCarryingNoEventConcludesNothing() {
    assertEquals(IcsEquivalence.Verdict.UNJUDGEABLE,
                 judge.compare(EXO,
                               "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//x//y//EN\r\nEND:VCALENDAR\r\n",
                               OWNER)
                      .verdict());
  }

  /**
   * Restates an object's start and end as a wall clock on a zone.
   *
   * @param ics the object to rewrite
   * @param tzid the zone identifier to anchor on
   * @param start the start wall clock
   * @param end the end wall clock
   * @param zone the VTIMEZONE to carry, possibly empty
   * @return the rewritten object
   */
  private String zoned(String ics, String tzid, String start, String end, String zone) {
    return ics.replace("DTSTART:20260901T090000Z", "DTSTART;TZID=" + tzid + ":" + start)
              .replace("DTEND:20260901T100000Z", "DTEND;TZID=" + tzid + ":" + end)
              .replace("BEGIN:VEVENT\r\n", zone + "BEGIN:VEVENT\r\n");
  }

  /**
   * Asserts that a variation of what the server holds still says what eXo
   * renders.
   *
   * @param onServer the object the server holds
   */
  private void assertEquivalent(String onServer) {
    assertEquivalent(onServer, EXO);
  }

  /**
   * Asserts that two objects state the same thing.
   *
   * @param onServer the object the server holds
   * @param inExo the object eXo renders
   */
  private void assertEquivalent(String onServer, String inExo) {
    IcsEquivalence.Judgement judgement = judge.compare(onServer, inExo, OWNER);
    assertEquals(IcsEquivalence.Verdict.EQUIVALENT, judgement.verdict(), String.valueOf(judgement.detail()));
  }

  /**
   * Asserts that a variation of what the server holds is a change eXo must
   * write back over.
   *
   * @param onServer the object the server holds
   */
  private void assertDifferent(String onServer) {
    assertDifferent(onServer, EXO);
  }

  /**
   * Asserts that two objects do not state the same thing.
   *
   * @param onServer the object the server holds
   * @param inExo the object eXo renders
   */
  private void assertDifferent(String onServer, String inExo) {
    IcsEquivalence.Judgement judgement = judge.compare(onServer, inExo, OWNER);
    assertEquals(IcsEquivalence.Verdict.DIFFERENT, judgement.verdict());
    assertNotNull(judgement.detail(), "a difference must say what it is, or nobody can act on the log line");
  }
}
