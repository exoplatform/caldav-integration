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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
      + "URL:https://exo.test/portal/dw/agenda?eventId=7\r\n"
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
    ReflectionTestUtils.setField(judge, "droppedProperties", "");
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

  // -------------------------- a copy eXo itself writes free (EXO-89870)

  /**
   * The convergence pin, and the one that matters.
   *
   * <p>
   * Since EXO-89870 eXo writes {@code TRANSP:TRANSPARENT} itself, on the copy
   * of an event its owner marked {@code FREE} — a property this engine did not
   * previously emit at all. A copy gaining a statement eXo did not previously
   * write is exactly the shape of EXO-89826 and EXO-89828: one statement
   * present on one side, absent on the other, judged altered and repaired on
   * every sweep for ever. It is not that shape only if a server that kept what
   * eXo wrote compares equal to what eXo writes next time, so that is what this
   * asserts, on both sides of the property at once.
   */
  @Test
  public void aFreeCopyTheServerKeptIsNotRePushed() {
    String free = EXO.replace("TRANSP:OPAQUE", "TRANSP:TRANSPARENT");
    // The fixture must really carry the statement, or this proves nothing.
    assertNotEquals(EXO, free);

    assertEquivalent(free, free);
  }

  /**
   * And a free copy is not equal to a busy one in either direction, which is
   * the bound on the relaxation above.
   *
   * <p>
   * The direction that is new is the second: eXo renders the copy free and the
   * server states the RFC default instead — a server that forces every event
   * busy, or simply drops what it does not keep. That has to register,
   * because the whole point of the property is that it reached the copy;
   * silently tolerating its loss would fix the ticket in the render and leave
   * the calendar showing exactly what it showed before.
   */
  @Test
  public void aServerThatWillNotHoldTheCopyFreeIsStillReported() {
    String free = EXO.replace("TRANSP:OPAQUE", "TRANSP:TRANSPARENT");

    assertDifferent(EXO, free);
    assertDifferent(EXO.replace("TRANSP:OPAQUE\r\n", ""), free);
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

  /**
   * URL used to be exempt, and the exemption was right at the time: the link
   * arrived on the push request, so only a browser push carried one and a
   * sweep rendered none — comparing it would have reported every copy altered
   * once and then stripped the link it complained about.
   *
   * <p>
   * EXO-89751 derives the link from the event, so eXo renders the same one on
   * every path. A copy whose link the client rewrote is a copy that no longer
   * says where the meeting lives, and it has to be noticed: leaving the
   * exemption in place would have shipped a link nobody was watching.
   */
  @Test
  public void aRewrittenLinkBackIntoExoIsAnEdit() {
    assertDifferent(EXO.replace("agenda?eventId=7", "agenda?eventId=999"));
  }

  /**
   * And a client that drops the property altogether is the same finding: the
   * copy has lost its way back into eXo, which is exactly what this ticket
   * exists to stop happening silently.
   */
  @Test
  public void aStrippedLinkBackIntoExoIsAnEdit() {
    assertDifferent(EXO.replace("URL:https://exo.test/portal/dw/agenda?eventId=7\r\n", ""));
  }

  /**
   * The link eXo wrote, left alone, is not an edit — the property being
   * compared must not make a converged mirror churn.
   */
  @Test
  public void theSameLinkBackIntoExoIsNotAnEdit() {
    assertEquivalent(EXO);
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

  // ------------- the identifier a client stamps on an alarm (EXO-89828)

  @Test
  public void anAlarmIdentifierTheClientStampedIsNotAnEdit() {
    // The divergence the rig logged at 14:14:35 on 2026-08-31, on the build
    // deployed at 13:56, on both copies a macOS client had answered:
    //   VALARM{ACTION=DISPLAY&DESCRIPTION=test16&TRIGGER=-PT5M
    //          &UNRECOGNISED:X-WR-ALARMUID=3E427B17-...}  (server 1, eXo 0)
    //   VALARM{ACTION=DISPLAY&DESCRIPTION=test16&TRIGGER=-PT5M}
    //                                                     (server 0, eXo 1)
    // One reminder, on both sides, counted as two. An alarm is compared as one
    // folded statement of the event that carries it, so a single property
    // inside it that one side does not state makes the whole reminder a
    // different reminder: the copy was judged altered, rewritten, and stamped
    // again the moment the client next touched it.
    assertEquivalent(EXO.replace("TRIGGER:-PT15M",
                                 "TRIGGER:-PT15M\r\nX-WR-ALARMUID:3E427B17-E128-4DF9-8090-18D619BCDC81"));
  }

  @Test
  public void aMovedReminderIsStillAnEditOnAnAlarmTheClientStamped() {
    // The bound. What is dropped is the identifier and nothing beside it: the
    // three properties that say when and how the reminder fires are compared
    // exactly as before, on a stamped alarm as on a bare one.
    assertDifferent(EXO.replace("TRIGGER:-PT15M",
                                "TRIGGER:-PT60M\r\nX-WR-ALARMUID:3E427B17-E128-4DF9-8090-18D619BCDC81"));
  }

  @Test
  public void aReminderTheClientAddedIsStillAnEditHoweverItIsIdentified() {
    // And the identifier cannot smuggle a whole reminder past the comparison:
    // a second alarm is a second alarm, whatever the client calls it.
    assertDifferent(EXO.replace("END:VEVENT\r\n",
                                "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Sprint review\r\n"
                                    + "TRIGGER:-PT60M\r\nX-WR-ALARMUID:7154708B-083E-4286-BD4B-AEAADD777DAE\r\n"
                                    + "END:VALARM\r\nEND:VEVENT\r\n"));
  }

  @Test
  public void aPriorityAUserSetIsStillAnEdit() {
    // Not everything the alarm and event models fail to parse is the client
    // indexing itself. The same sweep — 2026-08-31 12:45:01, one BlueMind copy
    // — reported UNRECOGNISED:PRIORITY=5 in the same comparison that reported
    // SUMMARY=test12 against SUMMARY=test121, a moved DTEND and an attendee
    // the copy had gained. Somebody had edited that meeting; PRIORITY is a
    // fact about it that a person set, and admitting it because it arrived
    // through the unrecognised bucket would have hidden half the edit.
    assertDifferent(EXO.replace("STATUS:CONFIRMED", "PRIORITY:5\r\nSTATUS:CONFIRMED"));
  }

  @Test
  public void aSequenceTheOrganizerBumpedIsStillAnEdit() {
    // Its neighbour in the same log line, UNRECOGNISED:SEQUENCE=1, and the
    // same verdict for a different reason: SEQUENCE is bookkeeping, but it is
    // the organizer's count of how many times the meeting has changed, not a
    // client's private handle on a record. It is how a client says an edit
    // happened, so it is the last statement that may be ignored.
    assertDifferent(EXO.replace("STATUS:CONFIRMED", "SEQUENCE:1\r\nSTATUS:CONFIRMED"));
  }

  @Test
  public void aCapturedCopyCarryingTheClientsAlarmIdentifierIsNotRePushed() throws Exception {
    // The convergence pin, on the captured specimen rather than a synthetic
    // one. caldav/golden/read/objects/r07-exo-reminder-repaired-onto-stalwart
    // .ics is the exact body Stalwart held for
    // /dav/cal/alice@stalwart.local/exo-meetings/fd5fdafc-...ics — event 1020,
    // "test16" — read back at 14:39 on 2026-08-31, which is eXo's own repair
    // of 14:14:35 as the server stored it, alarm and all.
    //
    // The server's side is that body with the client's identifier put back
    // into its VALARM. The value is not invented: it is the one the sweep
    // logged for this object minutes earlier, verbatim —
    //   VALARM{ACTION=DISPLAY&DESCRIPTION=test16&TRIGGER=-PT5M
    //          &UNRECOGNISED:X-WR-ALARMUID=3E427B17-E128-4DF9-8090-18D619BCDC81}
    // — and the sweep's own repair is what removed it from the object before
    // it could be captured decorated. So the fixture is captured and the one
    // line derived, which is the opposite way round from EXO-89826's pin and
    // is stated as such in the corpus README.
    //
    // What it pins is the whole ticket in one assertion: a second pass over a
    // copy the client has stamped concludes EQUIVALENT and re-pushes nothing.
    String inExo = golden("r07-exo-reminder-repaired-onto-stalwart");
    String onServer = inExo.replace("TRIGGER:-PT5M\r\n",
                                    "TRIGGER:-PT5M\r\nX-WR-ALARMUID:3E427B17-E128-4DF9-8090-18D619BCDC81\r\n");
    // The fixture must really gain the identifier, or this test proves nothing.
    assertNotEquals(onServer, inExo);

    assertEquivalent(onServer, inExo);
  }

  @Test
  public void anUnknownPropertyInsideAnAlarmIsStillAnEdit() {
    // The rule admits one name, not a bucket. A property the alarm model does
    // not recognise is still a difference wherever it appears, which is what
    // keeps "ignore anything we failed to parse" from being what was written.
    assertDifferent(EXO.replace("TRIGGER:-PT15M", "TRIGGER:-PT15M\r\nPRIORITY:5"));
  }

  // ------------------------------------------- names, and who they belong to

  @Test
  public void aDisplayNameTheServerSubstitutedOnTheOrganizerIsNotAnEdit() {
    // What the third deploy found, on the rig's root account once its profile
    // address matched the one BlueMind knows it by:
    //   ORGANIZER;CN=FRANCOIS=mailto:anais.francois@...   (server 1, eXo 0)
    //   ORGANIZER;CN=Root Root=mailto:anais.francois@...  (server 0, eXo 1)
    // Same person, same address, two directories' opinion of their name — and
    // the copies were reaching maxRepairs over it.
    assertEquivalent(EXO.replace("ORGANIZER;CN=The Boss:", "ORGANIZER;CN=FRANCOIS:"));
  }

  @Test
  public void aDisplayNameTheServerSubstitutedOnAnAttendeeIsNotAnEdit() {
    // The observation was on ORGANIZER; attendee names come from the same
    // directory, so the next deploy would have found these instead.
    assertEquivalent(EXO.replace("ATTENDEE;CN=Ann;", "ATTENDEE;CN=ANN MARTIN;"));
  }

  @Test
  public void anOrganizerWithNoNameAtAllIsNotAnEdit() {
    // A server that keeps no display name is saying the same nothing.
    assertEquivalent(EXO.replace("ORGANIZER;CN=The Boss:", "ORGANIZER:"));
  }

  @Test
  public void aChangedOrganizerAddressIsStillAnEdit() {
    // The whole risk of dropping CN, and the reason it is small: the address is
    // the identity, and it is compared regardless. Somebody else called this
    // meeting, and that still registers.
    assertDifferent(EXO.replace("mailto:boss@acme.test", "mailto:mallory@acme.test"));
  }

  @Test
  public void aRemovedOrganizerIsStillAnEdit() {
    // ORGANIZER is neither an attendee statement nor the owner's, so no
    // tolerance covers it in either direction.
    assertDifferent(EXO.replace("ORGANIZER;CN=The Boss:mailto:boss@acme.test\r\n", ""));
  }

  @Test
  public void theIgnoreListCannotSilenceTheOrganizerOrTheAttendees() {
    // ORGANIZER and ATTENDEE are properties eXo emits, so the operator's lever
    // must not reach them — it only ever suppresses a name outside the
    // recognised set. Pinned because the difference is invisible from the
    // outcome alone: drop ORGANIZER from the recognised set and an address
    // change still registers, as UNRECOGNISED, so no other test can tell. What
    // changes is that a deployment could then configure away who called the
    // meeting, which is exactly what the lever is built not to allow.
    ReflectionTestUtils.setField(judge, "ignoredProperties", "ORGANIZER,ATTENDEE");

    assertDifferent(EXO.replace("mailto:boss@acme.test", "mailto:mallory@acme.test"));
    assertDifferent(EXO.replace("ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:mailto:ann@acme.test",
                                "ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:mailto:mallory@acme.test"));
  }

  @Test
  public void aChangedAttendeeAddressIsStillAnEditWithBothTolerancesActive() {
    // Dropping CN must not let an address change slip past the pair of
    // tolerances. It cannot: the substituted address is a surplus on the
    // SERVER's side and neither rule covers a non-owner there — so what the
    // eXo-side tolerance hides, the server-side strictness still reports.
    assertDifferent(EXO.replace("ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:mailto:ann@acme.test",
                                "ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:mailto:mallory@acme.test"));
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
    IcsJudgement judgement =
        judge.compare(EXO.replace("STATUS:CONFIRMED",
                                  "ATTENDEE;CN=FRANCOIS:mailto:alice@stalwart.local\r\nSTATUS:CONFIRMED"),
                      EXO,
                      java.util.List.of());

    assertEquals(IcsJudgement.Verdict.DIFFERENT, judgement.verdict());
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

  // ------------------------- the address a server restates (EXO-89826)

  @Test
  public void anEmailParameterOnTheOrganizerIsNotAnEdit() {
    // The divergence the rig logged at 13:08 on 2026-08-31, byte for byte:
    // the copy states ORGANIZER;EMAIL=x:mailto:x and eXo's render states
    // ORGANIZER:mailto:x. One organizer, spelled twice, counted as two
    // statements — one missing from eXo, one missing from the copy — so the
    // copy was judged altered and repaired, and diverged again as soon as the
    // client touched it. EMAIL (RFC 6047 §2) restates, as a parameter, the
    // address the value already carries; IcsWriter never emits it.
    assertEquivalent(EXO.replace("ORGANIZER;CN=The Boss:", "ORGANIZER;CN=The Boss;EMAIL=boss@acme.test:"));
  }

  @Test
  public void anEmailParameterOnAnAttendeeIsNotAnEdit() {
    // macOS Calendar writes it on the attendee line too, whenever its user
    // answers an invitation — the same parameter, the same argument.
    assertEquivalent(EXO.replace("ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:",
                                 "ATTENDEE;CN=Ann;EMAIL=ann@acme.test;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:"));
  }

  @Test
  public void anAnswerIsStillAnEditOnALineCarryingAnEmailParameter() {
    // The pair that bounds the relaxation, and the reason PARTSTAT is not in
    // IGNORED_PARAMETERS with it. PARTSTAT is a parameter too, but it states a
    // person's answer rather than the server's index of them, and EXO-89807
    // and EXO-89814 both depend on a PARTSTAT difference being seen. Dropping
    // EMAIL must not drag its neighbours with it.
    assertDifferent(EXO.replace("ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:",
                                "ATTENDEE;CN=Ann;EMAIL=ann@acme.test;PARTSTAT=DECLINED;SCHEDULE-AGENT=CLIENT:"));
  }

  @Test
  public void anAddressMovedOutOfTheValueIsStillAnEdit() {
    // The other bound. What makes EMAIL safe to drop is that the address in
    // the property's own value stays compared: a server that replaced the
    // value with a handle of its own and moved the address into EMAIL would
    // be rewriting the identity, and the value it left behind still says so.
    assertDifferent(EXO.replace("ORGANIZER;CN=The Boss:mailto:boss@acme.test",
                                "ORGANIZER;CN=The Boss;EMAIL=boss@acme.test:urn:uuid:8f14e45f"));
  }

  @Test
  public void aDifferentPersonInTheValueIsStillAnEditWhateverTheEmailParameterSays() {
    // And the identity cannot be smuggled past the relaxation by agreeing in
    // the parameter and disagreeing in the value.
    assertDifferent(EXO.replace("ORGANIZER;CN=The Boss:mailto:boss@acme.test",
                                "ORGANIZER;CN=The Boss;EMAIL=boss@acme.test:mailto:mallory@acme.test"));
  }

  @Test
  public void aCapturedMacosCopyStatesWhatExoWritesAndIsNotRePushed() throws Exception {
    // The convergence pin, on the captured specimen rather than a synthetic
    // one: caldav/golden/read/objects/r06-macos-answer-internal-domain.ics is
    // the exact body Stalwart held for
    // /dav/cal/alice@stalwart.local/exo-meetings/f291b55a-...ics after macOS
    // Calendar 26.5.1 answered, EMAIL parameters and all — the same object the
    // rig reported as "1 altered, 1 re-pushed" at 12:55.
    //
    // eXo's side is that body with its EMAIL parameters removed and nothing
    // else touched, which is faithful because IcsWriter emits EMAIL on no
    // property at all (verified: the only parameter it ever adds to a calendar
    // user line is CN). So this pins exactly the claim the ticket is about — a
    // copy carrying the server's spelling compares equal to what eXo writes —
    // and nothing else.
    String onServer = golden("r06-macos-answer-internal-domain");
    String inExo = withoutEmailParameters(onServer);
    // The fixture must really carry the parameter, or this test proves nothing.
    assertNotEquals(onServer, inExo);

    assertEquivalent(onServer, inExo);
  }

  /**
   * A calendar object with every {@code EMAIL} parameter removed: eXo's own
   * spelling of the same lines.
   *
   * <p>
   * Unfolds first, because a parameter can straddle a folded line and the
   * captured fixture folds both of the lines that carry one.
   *
   * @param ics the object as the server holds it
   * @return the same object without its EMAIL parameters
   */
  private String withoutEmailParameters(String ics) {
    String unfolded = ics.replace("\r\n ", "").replace("\n ", "");
    return unfolded.replaceAll("(?i);EMAIL=(\"[^\"]*\"|[^;:\r\n]*)", "");
  }

  /**
   * Reads a golden object from the corpus.
   *
   * @param name the file name, without extension
   * @return its contents
   * @throws Exception when it cannot be read
   */
  private String golden(String name) throws Exception {
    return java.nio.file.Files.readString(java.nio.file.Paths.get(IcsEquivalenceTest.class.getClassLoader()
                                                                                          .getResource("caldav/golden/read/objects/"
                                                                                              + name + ".ics")
                                                                                          .toURI()));
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
    IcsJudgement judgement =
        judge.compare(EXO.replace("BEGIN:VEVENT\r\n", "BEGIN:VEVENT\r\nVERSION:2.0\r\n")
                         .replace("STATUS:CONFIRMED",
                                  "X-MICROSOFT-CDO-BUSYSTATUS:BUSY\r\nX-MICROSOFT-DISALLOW-COUNTER:false\r\n"
                                      + "STATUS:CONFIRMED"),
                      EXO,
                      OWNER);

    assertEquals(IcsJudgement.Verdict.DIFFERENT, judgement.verdict());
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
    IcsJudgement judgement = judge.compare("this is not a calendar object at all", EXO, OWNER);

    assertEquals(IcsJudgement.Verdict.DIFFERENT, judgement.verdict());
    assertNotNull(judgement.detail());
  }

  @Test
  public void anExoRenderThatCannotBeReadConcludesNothing() {
    // A defect on this side is never evidence about the user's calendar. The
    // caller leaves the copy exactly as it is.
    assertEquals(IcsJudgement.Verdict.UNJUDGEABLE, judge.compare(EXO, "not a calendar object", OWNER).verdict());
  }

  @Test
  public void anExoRenderCarryingNoEventConcludesNothing() {
    assertEquals(IcsJudgement.Verdict.UNJUDGEABLE,
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


  // --------------------------------- the whitespace a server refolds (EXO-89756)

  @Test
  public void aBlankLineTheServerRefoldedIsNotAnEdit() {
    // The residual BlueMind loop, byte for byte as the rig logged it: eXo
    // writes "Chemistry.\n\nEvent link: ..." and the copy comes back as
    // "Chemistry.\n Event link: ...". The blank line between the paragraphs
    // returns as a newline and a continuation space — one whitespace character
    // for another, nothing about the meeting touched. Left as a difference it
    // rewrote every copy of a live account every five minutes.
    String exo = EXO.replace("DESCRIPTION:Bring the board",
                             "DESCRIPTION:Bring the board.\\n\\nEvent link: https://exo.test/a?t=s3cr3t");
    String server = EXO.replace("DESCRIPTION:Bring the board",
                                "DESCRIPTION:Bring the board.\\n Event link: https://exo.test/a?t=s3cr3t");
    assertEquivalent(server, exo);
  }

  @Test
  public void aRewrittenAnswerTokenIsStillAnEdit() {
    // The pair, and the one that matters most. Since EXO-89753 the description
    // carries the tokenised answer links, so collapsing whitespace must not
    // un-guard them: a token is not whitespace, and rewriting one is an edit.
    String exo = EXO.replace("DESCRIPTION:Bring the board",
                             "DESCRIPTION:Bring the board.\\n\\nEvent link: https://exo.test/a?t=s3cr3t");
    String server = EXO.replace("DESCRIPTION:Bring the board",
                                "DESCRIPTION:Bring the board.\\n\\nEvent link: https://exo.test/a?t=f0rged");
    assertDifferent(server, exo);
  }

  @Test
  public void aDroppedParagraphIsStillAnEdit() {
    // The other half of the pair: collapsing runs of whitespace must not make
    // a paragraph the copy has lost look like layout.
    String exo = EXO.replace("DESCRIPTION:Bring the board",
                             "DESCRIPTION:Bring the board.\\n\\nEvent link: https://exo.test/a?t=s3cr3t");
    String server = EXO.replace("DESCRIPTION:Bring the board", "DESCRIPTION:Bring the board.");
    assertDifferent(server, exo);
  }

  @Test
  public void aReindentedSummaryIsNotAnEdit() {
    // The relaxation is over every TEXT property IcsWriter emits, not only the
    // description, because folding is not description-specific.
    assertEquivalent(EXO.replace("SUMMARY:Sprint review", "SUMMARY:Sprint\\n  review"),
                     EXO.replace("SUMMARY:Sprint review", "SUMMARY:Sprint review"));
  }

  @Test
  public void aRenamedMeetingIsStillAnEditDespiteWhitespaceFolding() {
    assertDifferent(EXO.replace("SUMMARY:Sprint review", "SUMMARY:Sprint retrospective"));
  }

  // ------------------------------ the links a server linkified (EXO-89756)

  @Test
  public void aLinkTheServerAppendedInBracketsIsNotAnEdit() {
    // The residual loop, in the shape the rig logged on 2026-08-28: BlueMind
    // appends every URI in the description a second time in angle brackets,
    // immediately after the one already there. eXo judged the copy rewritten,
    // repaired it, and the server linkified the repair — five copies, every
    // five-minute sweep, for ever.
    assertEquivalent(described(LINKIFIED), described(WRITTEN));
  }

  @Test
  public void aRewrittenAnswerTokenIsStillAnEditDespiteLinkification() {
    // The case that must never pass. The answer links EXO-89753 writes reply on
    // somebody's behalf, so a rewritten token is exactly the edit this pass
    // exists to catch — and the exemption is a backreference, which no forged
    // token can satisfy: it is stated twice, and it still is not what eXo wrote.
    String forged = LINKIFIED.replace("token=s3cr3t", "token=f0rged");
    assertDifferent(described(forged), described(WRITTEN));
  }

  @Test
  public void aRewrittenEventLinkIsStillAnEditDespiteLinkification() {
    // The same, one link along: a copy pointing the event link at somebody
    // else's host is a difference however faithfully the brackets repeat it.
    String elsewhere = LINKIFIED.replace("https://exo.test/portal/dw/agenda?eventId=7",
                                         "https://mallory.test/portal/dw/agenda?eventId=7");
    assertDifferent(described(elsewhere), described(WRITTEN));
  }

  @Test
  public void aBracketedLinkThatRepeatsNothingIsStillAnEdit() {
    // "Drop what is bracketed" would have swallowed this. The rule is narrower:
    // the brackets must repeat the URI immediately before them, so a copy that
    // put a second, different link behind eXo's is reported.
    String smuggled = WRITTEN.replace("eventId=7", "eventId=7 <https://mallory.test/collect?e=7>");
    assertDifferent(described(smuggled), described(WRITTEN));
  }

  @Test
  public void aLinkReplacedByItsOwnBracketedFormIsStillAnEdit() {
    // Only the bracketed copy is ever dropped, never the original — so a copy
    // that kept the brackets and lost the link says something eXo does not.
    String bracketedOnly = WRITTEN.replace("Event link: https://exo.test/portal/dw/agenda?eventId=7",
                                           "Event link: <https://exo.test/portal/dw/agenda?eventId=7>");
    assertDifferent(described(bracketedOnly), described(WRITTEN));
  }

  @Test
  public void theLinkifyExemptionCoversTheDescriptionOnly() {
    // Scope pin. Only the description is known to be linkified, and it is the
    // only TEXT property eXo composes URIs into; a summary or a location
    // carries one only because somebody typed it, and neither has ever been
    // seen coming back with the brackets. Widening the set is one word — and
    // wants a divergence report naming the property first.
    assertDifferent(EXO.replace("LOCATION:Room 3", "LOCATION:https://exo.test/meet/1 <https://exo.test/meet/1>"),
                    EXO.replace("LOCATION:Room 3", "LOCATION:https://exo.test/meet/1"));
  }

  @Test
  public void aWordRepeatedInBracketsIsNotDroppedFromProse() {
    // The exemption is about URIs, not about angle brackets: a bracketed word
    // in prose has no scheme, matches nothing, and is compared as before.
    assertDifferent(described("Ask reception <reception> for the room"), described("Ask reception for the room"));
  }

  // ------------------ a property the server stored twice over (EXO-89756)

  @Test
  public void aPropertyTheServerStoredTwiceIsNotAnEdit() {
    // The second shape of the same loop: BlueMind keeps two identical URL lines
    // where eXo wrote one — URL=… (server 2, eXo 1) on every copy of the live
    // account. Not a value difference but a cardinality one, and saying a thing
    // twice says nothing the once did not.
    assertEquivalent(EXO.replace("URL:https://exo.test/portal/dw/agenda?eventId=7\r\n",
                                 "URL:https://exo.test/portal/dw/agenda?eventId=7\r\n"
                                     + "URL:https://exo.test/portal/dw/agenda?eventId=7\r\n"));
  }

  @Test
  public void aSecondDifferentUrlIsStillAnEdit() {
    // The whole safety argument, in one case. Only a statement eXo also makes
    // is forgiven for being repeated; a link of the server's own is a statement
    // eXo never made, and no rule covers that.
    assertDifferent(EXO.replace("URL:https://exo.test/portal/dw/agenda?eventId=7\r\n",
                                "URL:https://exo.test/portal/dw/agenda?eventId=7\r\n"
                                    + "URL:https://mallory.test/portal/dw/agenda?eventId=7\r\n"));
  }

  @Test
  public void anAttendeeStatedTwiceOverIsStillOneAttendee() {
    // The rule is not URL-specific, and does not need to be: among the
    // properties IcsWriter emits, none carries meaning in how many times it is
    // written. The same person is one attendee however many lines name them.
    assertEquivalent(EXO.replace("ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:mailto:ann@acme.test\r\n",
                                 "ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:mailto:ann@acme.test\r\n"
                                     + "ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:mailto:ann@acme.test\r\n"));
  }

  @Test
  public void anAttendeeTheCopyAddedIsStillAnEditHoweverOftenItIsRepeated() {
    // Repetition is forgiven, appearance never is: eXo states nothing about
    // this person, so both of the server's lines are a surplus no rule covers.
    assertDifferent(EXO.replace("STATUS:CONFIRMED\r\n",
                                "ATTENDEE:mailto:mallory@acme.test\r\n"
                                    + "ATTENDEE:mailto:mallory@acme.test\r\n"
                                    + "STATUS:CONFIRMED\r\n"));
  }

  @Test
  public void aReminderTheCopyStatesTwiceIsStillAnEdit() {
    // The third condition, pinned: the rule is restricted to the property names
    // IcsWriter emits, which is what keeps an embedded VALARM — and the owner's
    // own canonical statement — outside it. A second reminder is a second
    // reminder.
    assertDifferent(EXO.replace("END:VEVENT\r\n",
                                "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Sprint review\r\n"
                                    + "TRIGGER:-PT15M\r\nEND:VALARM\r\nEND:VEVENT\r\n"));
  }

  /**
   * The description eXo composes: prose, then the links EXO-89751 and
   * EXO-89753 put in it, one of them carrying an answer token.
   */
  private static final String WRITTEN   =
                                      "Bring the board.\\n\\nEvent link: https://exo.test/portal/dw/agenda?eventId=7"
                                          + "\\n\\nAnswer this invitation: Accepted "
                                          + "https://exo.test/rest/v1/agenda/events/7/response/send?response=ACCEPTED&token=s3cr3t";

  /**
   * The same description as the server hands it back: every URI stated once
   * more, in angle brackets, immediately after itself.
   */
  private static final String LINKIFIED =
                                        "Bring the board.\\n Event link: https://exo.test/portal/dw/agenda?eventId=7"
                                            + " <https://exo.test/portal/dw/agenda?eventId=7>"
                                            + "\\n Answer this invitation: Accepted "
                                            + "https://exo.test/rest/v1/agenda/events/7/response/send?response=ACCEPTED&token=s3cr3t"
                                            + " <https://exo.test/rest/v1/agenda/events/7/response/send?response=ACCEPTED&token=s3cr3t>";

  /**
   * The reference object carrying a given description.
   *
   * @param description the DESCRIPTION value, already escaped as iCalendar TEXT
   * @return the object
   */
  private String described(String description) {
    return EXO.replace("DESCRIPTION:Bring the board\r\n", "DESCRIPTION:" + description + "\r\n");
  }

  // ------------------------- a property the server will not store (EXO-89756)

  @Test
  public void aConferenceTheServerDropsIsAnEditUntilTheOperatorSaysOtherwise() {
    // Shipped default: nothing is excused. BlueMind drops CONFERENCE from every
    // copy, and until somebody has read a log line naming it, eXo says so.
    assertDifferent(withConference(""), withConference(CONFERENCE));
  }

  @Test
  public void aConferenceTheOperatorDeclaredDroppedIsNotAnEdit() {
    // The lever the rig runs on. Note it could not be the existing one:
    // ignoredProperties is consulted only for a name outside the recognised
    // set, and CONFERENCE is a property IcsWriter emits.
    ReflectionTestUtils.setField(judge, "droppedProperties", "CONFERENCE");

    assertEquivalent(withConference(""), withConference(CONFERENCE));
  }

  @Test
  public void theOldLeverCannotExcuseAPropertyExoEmits() {
    // Pins the boundary between the two levers, and the reason the second one
    // had to exist at all.
    ReflectionTestUtils.setField(judge, "ignoredProperties", "CONFERENCE");

    assertDifferent(withConference(""), withConference(CONFERENCE));
  }

  @Test
  public void aRewrittenConferenceIsStillAnEditWhileTheLeverIsOn() {
    // The whole safety argument for the lever, in one case. eXo's value is
    // excused as an absence, but the client's value arrives as a surplus on the
    // SERVER's side, which no rule covers — so a rewritten video link is still
    // reported even on a server declared to drop the property.
    ReflectionTestUtils.setField(judge, "droppedProperties", "CONFERENCE");

    assertDifferent(withConference("CONFERENCE;FEATURE=VIDEO;VALUE=URI:https://mallory.test/meet/1\r\n"),
                    withConference(CONFERENCE));
  }

  @Test
  public void theLeverCannotBePointedAtTheOwnersAnswer() {
    // OWNER-ATTENDEE is a canonical statement, not a property IcsWriter emits,
    // and the list is restricted to the recognised set precisely so it cannot
    // be aimed at somebody's reply.
    ReflectionTestUtils.setField(judge, "droppedProperties", "OWNER-ATTENDEE,ATTENDEE;PARTSTAT");

    String exo = EXO.replace("END:VEVENT",
                             "ATTENDEE;PARTSTAT=ACCEPTED:mailto:alice@stalwart.local\r\nEND:VEVENT");
    assertDifferent(EXO, exo);
  }

  /**
   * The conference line eXo renders for a video meeting.
   */
  private static final String CONFERENCE = "CONFERENCE;FEATURE=VIDEO;VALUE=URI:https://exo.test/meet/1\r\n";

  /**
   * The reference object with a conference line, or without one.
   *
   * @param conference the line to carry, empty for a copy that has none
   * @return the object
   */
  private String withConference(String conference) {
    return EXO.replace("STATUS:CONFIRMED\r\n", conference + "STATUS:CONFIRMED\r\n");
  }

  /**
   * Asserts that two objects state the same thing.
   *
   * @param onServer the object the server holds
   * @param inExo the object eXo renders
   */
  private void assertEquivalent(String onServer, String inExo) {
    IcsJudgement judgement = judge.compare(onServer, inExo, OWNER);
    assertEquals(IcsJudgement.Verdict.EQUIVALENT, judgement.verdict(), String.valueOf(judgement.detail()));
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
    IcsJudgement judgement = judge.compare(onServer, inExo, OWNER);
    assertEquals(IcsJudgement.Verdict.DIFFERENT, judgement.verdict());
    assertNotNull(judgement.detail(), "a difference must say what it is, or nobody can act on the log line");
  }
}
