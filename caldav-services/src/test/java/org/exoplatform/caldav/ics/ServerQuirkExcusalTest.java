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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.caldav.model.IcsDivergence;
import org.exoplatform.caldav.model.ServerQuirk;
import org.exoplatform.caldav.model.ServerQuirkDirection;

/**
 * What one server is excused for, and what excusing it costs (EXO-89771).
 *
 * <p>
 * Three questions, and they are deliberately separate. <b>Whose list decides</b>
 * — a registration that has never been asked defers to the deployment-wide
 * property, so upgrading changes nothing, and one that has its own ignores the
 * global entirely, so two servers in one deployment can be excused differently.
 * <b>What an excusal covers</b> — an absence for every entry, a substituted
 * value for the one entry that declares it, and nothing at all for a property
 * eXo does not write. And <b>what is still shown</b> — an excused divergence is
 * still observed, or the very quirk somebody ticked would disappear from the
 * list they ticked it in.
 */
public class ServerQuirkExcusalTest {

  /** The judge under test. */
  private IcsEquivalence      judge;

  /** What eXo renders: the reference every case varies from. */
  private static final String EXO         = "BEGIN:VCALENDAR\r\n"
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
      + "DESCRIPTION:Bring the board. Accept: https://exo.test/a?t=s3cr3t\r\n"
      + "URL:https://exo.test/portal/dw/agenda?eventId=7\r\n"
      + "CONFERENCE;FEATURE=VIDEO;VALUE=URI:https://exo.test/meet/1\r\n"
      + "ORGANIZER;CN=The Boss:mailto:boss@acme.test\r\n"
      + "STATUS:CONFIRMED\r\n"
      + "TRANSP:OPAQUE\r\n"
      + "END:VEVENT\r\n"
      + "END:VCALENDAR\r\n";

  /** The conference line eXo renders, as the copy would carry it. */
  private static final String CONFERENCE  = "CONFERENCE;FEATURE=VIDEO;VALUE=URI:https://exo.test/meet/1\r\n";

  /** The organizer line eXo renders. */
  private static final String ORGANIZER   = "ORGANIZER;CN=The Boss:mailto:boss@acme.test\r\n";

  /** The invitation text eXo renders, answer link included. */
  private static final String DESCRIPTION = "DESCRIPTION:Bring the board. Accept: https://exo.test/a?t=s3cr3t";

  /** Both addresses a copy on this account may name its owner by. */
  private static final List<String> OWNER = List.of("alice@stalwart.local", "bob@stalwart.local");

  /**
   * A judge with nothing configured away anywhere — no deployment-wide
   * property, no per-server list — which is the shipped default.
   */
  @BeforeEach
  public void aJudgeWithNothingExcused() {
    judge = new IcsEquivalence();
    ReflectionTestUtils.setField(judge, "ignoredProperties", "");
    ReflectionTestUtils.setField(judge, "droppedProperties", "");
  }

  // ------------------------------------------------------- whose list decides

  @Test
  public void aServerThatHasNeverBeenAskedFallsBackToTheDeploymentWideList() {
    // The upgrade story. A deployment running the interim global setting keeps
    // exactly the behaviour it has today until somebody opens a drawer: the
    // registration carries null, and null defers.
    ReflectionTestUtils.setField(judge, "droppedProperties", "CONFERENCE");

    assertEquivalent(without(CONFERENCE), EXO, null, null);
  }

  @Test
  public void aServerWithItsOwnListIgnoresTheDeploymentWideOne() {
    // The whole point of making it per server: naming CONFERENCE for BlueMind
    // must not blind a well-behaved server standing beside it. Here the
    // deployment excuses it and this server does not, and the copy is reported.
    ReflectionTestUtils.setField(judge, "droppedProperties", "CONFERENCE");

    assertDifferent(without(CONFERENCE), EXO, null, "");
  }

  @Test
  public void aServerCarriesItsOwnExcusalWithNoDeploymentWideListAtAll() {
    // And the other direction: the deployment says nothing, this server's
    // administrator ticked the box, and only this server's copies are excused.
    assertEquivalent(without(CONFERENCE), EXO, null, "CONFERENCE");
  }

  @Test
  public void anEmptyListIsAnAnswerAndNotAnAbsentOne() {
    // Null and empty must not collapse. If an empty string fell back to the
    // global, unticking the last box in the drawer would be a no-op, and an
    // administrator would have no way to say "excuse nothing on this server".
    ReflectionTestUtils.setField(judge, "droppedProperties", "CONFERENCE");

    assertEquivalent(without(CONFERENCE), EXO, null, null);
    assertDifferent(without(CONFERENCE), EXO, null, "");
  }

  // ------------------------------------------------ what an excusal covers

  @Test
  public void tickingAQuirkStopsThatDivergenceBeingReported() {
    assertEquivalent(without(CONFERENCE), EXO, null, "CONFERENCE");
  }

  @Test
  public void notTickingItLeavesTheDivergenceReported() {
    // The pair to the test above, and the one that matters: an untouched
    // registration reports what its server drops, which is how anybody ever
    // learns there is something to tick.
    assertDifferent(without(CONFERENCE), EXO, null, null);
  }

  @Test
  public void aClientThatRewroteTheConferenceLinkIsStillCaughtOnAnExcusedServer() {
    // The excusal is an absence, not a blanket. CONFERENCE is not the
    // catalogue's rewritten entry, so a client substituting its own link
    // arrives as a surplus on the server's side that nothing covers.
    String tampered = EXO.replace(CONFERENCE, "CONFERENCE;FEATURE=VIDEO;VALUE=URI:https://mallory.test/meet/1\r\n");

    assertDifferent(tampered, EXO, null, "CONFERENCE");
  }

  @Test
  public void aPropertyExoDoesNotWriteCannotBeHiddenByTheDroppedList() {
    // Guarded so the list can only ever excuse, never blind: eXo writes no
    // LOCATION here, so a LOCATION on the copy is a client addition and stays
    // a difference however the list is set.
    String exoWithout = EXO.replace("LOCATION:Room 3\r\n", "");

    assertDifferent(EXO, exoWithout, null, "LOCATION");
  }

  @Test
  public void aMarkerFamilyIsExcusedByItsPrefix() {
    // Outlook and Thunderbird markers are open-ended, so the catalogue entry
    // stores a prefix and the comparison reads the same one — a marker this
    // deployment has not seen yet is covered by the box already ticked.
    String stamped = EXO.replace("END:VEVENT", "X-MOZ-GENERATION:4\r\nX-MICROSOFT-CDO-BUSYSTATUS:BUSY\r\nEND:VEVENT");

    assertDifferent(stamped, EXO, null, null);
    assertEquivalent(stamped, EXO, "X-MICROSOFT-*,X-MOZ-*", null);
  }

  @Test
  public void aStarOnItsOwnExcusesNothing() {
    // "*" would not be an excusal but a way to switch the comparison off, so
    // the matcher refuses an empty prefix.
    String stamped = EXO.replace("END:VEVENT", "X-BM-FOO:1\r\nEND:VEVENT");

    assertDifferent(stamped, EXO, "*", null);
  }

  // ------------------------------------------- the invitation text, and its pin

  @Test
  public void excusingTheInvitationTextStopsItsTextBeingCompared() {
    // The blunt entry. BlueMind genuinely rewrites the description rather than
    // dropping it, so both sides state it with different values — which no
    // absence rule could cover. This is the one catalogue entry that declares
    // itself REWRITTEN, and that is what makes the substituted value excusable.
    assertEquivalent(rewrittenText(), EXO, null, "DESCRIPTION");
  }

  @Test
  public void excusingAnyOtherQuirkLeavesTheInvitationTextCompared() {
    // The pin the whole feature turns on. Excusing the description means no
    // longer noticing a rewritten answer link — EXO-89752 and EXO-89753 exist
    // to bound exactly that — so no other tick may ever reach it. Ticking the
    // conference box, or the marker box, leaves a rewritten answer link
    // reported.
    assertDifferent(rewrittenText(), EXO, null, "CONFERENCE");
    assertDifferent(rewrittenText(), EXO, "X-MICROSOFT-*,X-MOZ-*", "CONFERENCE");
    assertDifferent(rewrittenText(), EXO, null, null);
  }

  @Test
  public void excusingTheInvitationTextDoesNotReachTheReminderItCarries() {
    // DESCRIPTION is also an alarm property. The excusal is restricted to the
    // event's own recognised properties, and an embedded VALARM is one
    // statement of the event, not a property of it — so a reminder whose text
    // a client rewrote is still reported on a server excused for the
    // invitation text.
    String exo = EXO.replace("END:VEVENT",
                             "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Sprint review\r\nTRIGGER:-PT15M\r\n"
                                 + "END:VALARM\r\nEND:VEVENT");
    String server = exo.replace("DESCRIPTION:Sprint review", "DESCRIPTION:Something else entirely");

    assertDifferent(server, exo, null, "DESCRIPTION");
  }

  // --------------------------------------------------- what is still observed

  @Test
  public void anUnrecognisedPropertyStillSurfacesInTheObservedList() {
    // The catalogue is deliberately incomplete, so a server nobody here has
    // seen must still be describable: the divergence comes back named by the
    // property itself, which is what the drawer's generic wording renders.
    String stamped = EXO.replace("END:VEVENT", "X-BM-FOO:1\r\nEND:VEVENT");

    IcsJudgement judgement = judge.compare(stamped, EXO, OWNER, null, null);

    assertEquals(IcsJudgement.Verdict.DIFFERENT, judgement.verdict());
    assertTrue(judgement.divergences().contains(new IcsDivergence("X-BM-FOO",
                                                                              ServerQuirkDirection.ADDED)),
               "an unrecognised property must be offered by its own name: " + judgement.divergences());
  }

  @Test
  public void anExcusedDivergenceIsStillObserved() {
    // Otherwise the very behaviour an administrator ticked would vanish from
    // the list they ticked it in, and nobody could untick it — or see what a
    // deployment-wide property is already hiding.
    IcsJudgement judgement = judge.compare(without(CONFERENCE), EXO, OWNER, null, "CONFERENCE");

    assertEquals(IcsJudgement.Verdict.EQUIVALENT, judgement.verdict(), String.valueOf(judgement.detail()));
    assertTrue(judgement.divergences().contains(new IcsDivergence("CONFERENCE",
                                                                              ServerQuirkDirection.DROPPED)),
               "an excused behaviour must still be reported to the drawer: " + judgement.divergences());
  }

  @Test
  public void aRewrittenValueIsObservedAsOneBehaviourAndNotTwo() {
    // A substituted value arrives as two divergences pointing opposite ways.
    // Offering an administrator both halves of it would be offering them a
    // puzzle, so they are folded into the one fact they state.
    IcsJudgement judgement = judge.compare(rewrittenText(), EXO, OWNER, null, null);

    assertTrue(judgement.divergences().contains(new IcsDivergence("DESCRIPTION",
                                                                              ServerQuirkDirection.REWRITTEN)),
               "a rewritten value is one behaviour: " + judgement.divergences());
    assertEquals(1,
                 judgement.divergences().stream().filter(divergence -> "DESCRIPTION".equals(divergence.property())).count(),
                 "and it must be offered once");
  }

  @Test
  public void normalCaldavBehaviourIsNotOfferedAsSomethingToExcuse() {
    // A server repeating a statement eXo also makes says nothing new, and the
    // comparison already understands that. Offering it as a decision would
    // bury the entries that are decisions.
    String repeated = EXO.replace("URL:https://exo.test/portal/dw/agenda?eventId=7\r\n",
                                  "URL:https://exo.test/portal/dw/agenda?eventId=7\r\n"
                                      + "URL:https://exo.test/portal/dw/agenda?eventId=7\r\n");

    IcsJudgement judgement = judge.compare(repeated, EXO, OWNER, null, null);

    assertEquals(IcsJudgement.Verdict.EQUIVALENT, judgement.verdict(), String.valueOf(judgement.detail()));
    assertTrue(judgement.divergences().isEmpty(), "built-in tolerances are not decisions: " + judgement.divergences());
  }

  @Test
  public void aCopyThatSaysWhatExoSaysOffersNothing() {
    IcsJudgement judgement = judge.compare(EXO, EXO, OWNER, null, null);

    assertEquals(IcsJudgement.Verdict.EQUIVALENT, judgement.verdict());
    assertTrue(judgement.divergences().isEmpty());
  }

  @Test
  public void aJudgementThatConcludesNothingOffersNothing() {
    // Never null, so no caller has to guard: an unusable render says nothing
    // about the server either.
    IcsJudgement judgement = judge.compare(EXO, "not a calendar", OWNER, null, null);

    assertEquals(IcsJudgement.Verdict.UNJUDGEABLE, judgement.verdict());
    assertNotNull(judgement.divergences());
    assertTrue(judgement.divergences().isEmpty());
  }

  @Test
  public void theThreeArgumentComparisonStillRunsOnTheDeploymentWideLists() {
    // Kept so nothing that only knows the deployment has to learn about the
    // registry; the mirror pass is the one caller that carries a server.
    ReflectionTestUtils.setField(judge, "droppedProperties", "CONFERENCE");

    assertEquals(IcsJudgement.Verdict.EQUIVALENT, judge.compare(without(CONFERENCE), EXO, OWNER).verdict());
  }

  // ------- the organizer of an event with nobody else on it (EXO-89775)

  @Test
  public void anOrganizerDroppedFromAnEventWithNobodyElseOnItIsOfferedAsItsOwnCase() {
    // Named as a case rather than as a property, so the drawer can offer this
    // one without ever offering "this server does not keep ORGANIZER" - which
    // would stop a real organizer deletion being reported.
    IcsJudgement judgement = judge.compare(without(ORGANIZER), EXO, OWNER, null, null);

    assertEquals(IcsJudgement.Verdict.DIFFERENT, judgement.verdict());
    assertTrue(judgement.divergences().contains(new IcsDivergence(ServerQuirk.SOLO_ORGANIZER,
                                                                  ServerQuirkDirection.DROPPED)),
               "the solo case must be offered by its own name: " + judgement.divergences());
  }

  @Test
  public void anOrganizerDroppedFromARealMeetingStaysAnOrdinaryOrganizer() {
    // The pair, and the one that matters: an organizer disappearing from a copy
    // of a meeting other people are on is a real change, offered - if at all -
    // as itself, and never as the appointment case.
    String exo = EXO.replace("STATUS:CONFIRMED", "ATTENDEE;CN=Ann:mailto:ann@acme.test\r\nSTATUS:CONFIRMED");
    String server = exo.replace(ORGANIZER, "");

    IcsJudgement judgement = judge.compare(server, exo, OWNER, null, null);

    assertTrue(judgement.divergences().contains(new IcsDivergence("ORGANIZER", ServerQuirkDirection.DROPPED)),
               "a real meeting's organizer is reported as itself: " + judgement.divergences());
    assertFalse(judgement.divergences().stream().anyMatch(d -> ServerQuirk.SOLO_ORGANIZER.equals(d.property())),
                "and never as the appointment case");
  }

  @Test
  public void theSoloOrganizerCaseCannotBeExcusedInTheComparisonAtAll() {
    // It is answered by eXo writing less, not by eXo noticing less, so it must
    // never be readable as an excusal: the token is not a property IcsWriter
    // emits, and the excusal lists only accept those.
    assertDifferent(without(ORGANIZER), EXO, ServerQuirk.SOLO_ORGANIZER, ServerQuirk.SOLO_ORGANIZER);
  }

  @Test
  public void theCatalogueOffersNoEntryForAnOrdinaryMissingOrganizer() {
    // Pins the decision EXO-89768 and EXO-89775 both made: nothing shipped here
    // tolerates a missing ORGANIZER, and nothing here makes its value excusable.
    assertTrue(ServerQuirk.describing("ORGANIZER", ServerQuirkDirection.DROPPED).isEmpty());
    assertFalse(ServerQuirk.rewriteExcusable("ORGANIZER"));
  }

  @Test
  public void onceExoStopsWritingTheOrganizerThereIsNothingLeftToExcuse() {
    // The reason this addition ships no tolerance entry beside it. The same
    // mapping produces the push and the sweep's render, so a render without an
    // organizer meets a copy without one and the two simply agree - shipping an
    // excusal as well would be excusing a difference that no longer exists.
    assertEquivalent(without(ORGANIZER), without(ORGANIZER), null, null);
  }

  /**
   * The reference object without one of its lines.
   *
   * @param line the line the server did not keep
   * @return the object as that server holds it
   */
  private String without(String line) {
    return EXO.replace(line, "");
  }

  /**
   * The reference object with its invitation text replaced — what BlueMind
   * does, answer link and all.
   *
   * @return the object as such a server holds it
   */
  private String rewrittenText() {
    return EXO.replace(DESCRIPTION, "DESCRIPTION:Bring the board. Accept: https://mallory.test/a?t=forged");
  }

  /**
   * Asserts that a copy states what eXo would write, for a server excused as
   * given.
   *
   * @param onServer the object the server holds
   * @param inExo the object eXo renders
   * @param ignored the registration's ignored list, null to fall back
   * @param dropped the registration's dropped list, null to fall back
   */
  private void assertEquivalent(String onServer, String inExo, String ignored, String dropped) {
    IcsJudgement judgement = judge.compare(onServer, inExo, OWNER, ignored, dropped);
    assertEquals(IcsJudgement.Verdict.EQUIVALENT, judgement.verdict(), String.valueOf(judgement.detail()));
  }

  /**
   * Asserts that a copy does not state what eXo would write, for a server
   * excused as given.
   *
   * @param onServer the object the server holds
   * @param inExo the object eXo renders
   * @param ignored the registration's ignored list, null to fall back
   * @param dropped the registration's dropped list, null to fall back
   */
  private void assertDifferent(String onServer, String inExo, String ignored, String dropped) {
    IcsJudgement judgement = judge.compare(onServer, inExo, OWNER, ignored, dropped);
    assertEquals(IcsJudgement.Verdict.DIFFERENT, judgement.verdict());
    assertFalse(judgement.divergences().isEmpty(), "a difference must be offered to the drawer as well as logged");
  }
}
