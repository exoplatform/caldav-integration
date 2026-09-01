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

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.caldav.model.IcsEvent;

/**
 * How a copy says its time is still free.
 *
 * <p>
 * The user-facing half of EXO-89870. Agenda has always let an owner mark an
 * event {@code FREE}, meaning it does not consume their time, and the copy
 * pushed to their other calendars said the opposite: {@code TRANSP:OPAQUE},
 * unconditionally, so an event they had deliberately declared free went on
 * answering "busy" for them everywhere else. It is written
 * {@code TRANSP:TRANSPARENT} now.
 *
 * <p>
 * The transition is pinned in both directions here, because a property that
 * only ever goes on is worse than one that never went on at all: an event
 * whose copy still says the time is free after its owner unmarked it is an
 * event colleagues book over.
 */
public class IcsWriterFreeTimeTest {

  private final IcsWriter    writer = new IcsWriter();

  /** The sweep's own comparison, for the convergence pin below. */
  private IcsEquivalence     judge;

  /** The addresses the account this copy sits on answers to. */
  private static final List<String> OWNER = List.of("alice@stalwart.local");

  /**
   * A judge with nothing configured away, which is the shipped default.
   */
  @BeforeEach
  public void aJudgeWithNothingIgnored() {
    judge = new IcsEquivalence();
    ReflectionTestUtils.setField(judge, "ignoredProperties", "");
    ReflectionTestUtils.setField(judge, "droppedProperties", "");
  }

  /**
   * The property that carries the whole decision.
   */
  @Test
  public void anEventMarkedFreeIsWrittenAsFreeTime() {
    String ics = writer.write(meeting(true));

    assertTrue(ics.contains("TRANSP:TRANSPARENT"), "an event its owner marked free must not claim their time:\n" + ics);
    assertFalse(ics.contains("TRANSP:OPAQUE"), "and must not also claim it:\n" + ics);
  }

  /**
   * And the transition back, which is the half that stops an event being
   * booked over: the writer takes the flag and nothing else, so the copy
   * returns to busy the moment the flag does.
   */
  @Test
  public void anOrdinaryMeetingStillClaimsItsTime() {
    String ics = writer.write(meeting(false));

    assertTrue(ics.contains("TRANSP:OPAQUE"), "an ordinary meeting stays busy:\n" + ics);
    assertFalse(ics.contains("TRANSP:TRANSPARENT"), "and never says its time is free:\n" + ics);
  }

  /**
   * The property is stated once, whichever way it falls.
   *
   * <p>
   * Written as one property with two values rather than an extra one added
   * beside the default: a component carrying both {@code TRANSP:OPAQUE} and
   * {@code TRANSP:TRANSPARENT} would be malformed, and a server is free to
   * keep either.
   */
  @Test
  public void theCopyStatesItsAvailabilityExactlyOnce() {
    assertTrue(occurrences(writer.write(meeting(true)), "TRANSP:") == 1, "a free event states TRANSP once");
    assertTrue(occurrences(writer.write(meeting(false)), "TRANSP:") == 1, "and so does a busy one");
  }

  /**
   * A copy stating free time still says what the meeting is.
   *
   * <p>
   * STATUS and TRANSP are separate statements about separate things — whether
   * the meeting is on, and whether it consumes its owner's time — and this
   * pins that saying the second does not disturb the first. A copy that lost
   * its STATUS would read to a client the way a failed synchronisation does.
   */
  @Test
  public void aFreeCopyIsStillAConfirmedEntry() {
    String ics = writer.write(meeting(true));

    assertTrue(ics.contains("STATUS:CONFIRMED"), "the entry is still shown by every client:\n" + ics);
  }

  /**
   * The convergence pin: a free copy already on the server is not pushed again
   * on the next pass.
   *
   * <p>
   * <b>The risk this whole change carries.</b> A copy that gains a property
   * eXo did not previously write is the shape of EXO-89826 and EXO-89828 — one
   * statement present on one side, absent on the other, judged altered and
   * repaired every five minutes for ever. So the pin runs the actual sweep
   * comparison: what the server holds is the object eXo pushed, and what it is
   * compared against is what {@link IcsWriter} renders for the same event on
   * the next pass.
   *
   * <p>
   * The server's side is deliberately <b>not</b> a second call to the writer,
   * which would move with any mutation of the writer and prove nothing. It is
   * the busy render with {@code OPAQUE} swapped for {@code TRANSPARENT} — a
   * faithful stand-in for a server that kept what eXo wrote, built without
   * going through the branch under test. Take the branch out of
   * {@link IcsWriter} and the render states {@code OPAQUE}, which folds to the
   * RFC default and therefore to nothing, while the server's copy still states
   * {@code TRANSPARENT}: the comparison reports a TRANSP divergence and this
   * fails.
   */
  @Test
  public void aFreeCopyAlreadyPushedIsNotPushedAgain() {
    String onServer = writer.write(meeting(false)).replace("TRANSP:OPAQUE", "TRANSP:TRANSPARENT");
    // The stand-in must really carry the statement, or this proves nothing.
    assertTrue(onServer.contains("TRANSP:TRANSPARENT"), "the server's copy must state the time is free");

    IcsJudgement judgement = judge.compare(onServer, writer.write(meeting(true)), OWNER);

    assertEquals(IcsJudgement.Verdict.EQUIVALENT, judgement.verdict(), String.valueOf(judgement.detail()));
  }

  /**
   * And the same object once its owner unmarks it <b>is</b> pushed again,
   * which is the transition the ticket asks to be pinned in both directions.
   *
   * <p>
   * Without this the property could go on and never come off and every test
   * above would still pass: the copy would be free for ever, and an event that
   * really does consume its owner's time would be one colleagues book over.
   */
  @Test
  public void anEventNoLongerFreeDivergesUntilItIsRewritten() {
    String onServer = writer.write(meeting(false)).replace("TRANSP:OPAQUE", "TRANSP:TRANSPARENT");

    IcsJudgement judgement = judge.compare(onServer, writer.write(meeting(false)), OWNER);

    assertEquals(IcsJudgement.Verdict.DIFFERENT, judgement.verdict(), "a busy event must not stay free on the copy");
    assertNotNull(judgement.detail(), "and the sweep must say what it is repairing");
  }

  /**
   * How many times a fragment appears in the object.
   *
   * @param ics the written object
   * @param fragment the text to count
   * @return the number of occurrences
   */
  private int occurrences(String ics, String fragment) {
    int count = 0;
    int at = ics.indexOf(fragment);
    while (at >= 0) {
      count++;
      at = ics.indexOf(fragment, at + fragment.length());
    }
    return count;
  }

  /**
   * One meeting, claiming its owner's time or not.
   *
   * @param transparent whether the copy states the time is still free
   * @return the event to write
   */
  private IcsEvent meeting(boolean transparent) {
    return IcsEvent.builder()
                   .uid("uid-transp-1")
                   .summary("Steering point")
                   .start(Instant.parse("2026-09-08T07:00:00Z"))
                   .end(Instant.parse("2026-09-08T08:00:00Z"))
                   .timeZoneId("Europe/Paris")
                   .attendees(List.of())
                   .exceptionDates(List.of())
                   .reminders(List.of())
                   .transparent(transparent)
                   .build();
  }
}
