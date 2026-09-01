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
package org.exoplatform.caldav.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.model.Event;

/**
 * The one rule about which events a calendar copy may stand for.
 *
 * <p>
 * Worth its own class rather than being implied by the tests of the three
 * services that consult it, because the defect this rule was written for was
 * the two halves of it disagreeing: the seeding pass refused a date poll while
 * the push core wrote one for whoever's browser asked. What is pinned here is
 * the rule itself, including the relation between its two questions, so a later
 * change to one of them cannot quietly stop implying the other.
 */
public class CaldavCopyPolicyTest {

  private final CaldavCopyPolicy policy = new CaldavCopyPolicy();

  /**
   * The decision of EXO-89863. A date poll is stored as one event spanning the
   * envelope of every option proposed, so its copy is a multi-day block
   * describing a meeting that is not happening.
   */
  @Test
  public void aDatePollMayHoldNoCopy() {
    assertFalse(policy.mayHoldCopy(event(EventStatus.TENTATIVE)));
  }

  /**
   * The ordinary case, and the one every other test in this module depends on
   * staying true.
   */
  @Test
  public void aConfirmedMeetingMayHoldACopy() {
    assertTrue(policy.mayHoldCopy(event(EventStatus.CONFIRMED)));
  }

  /**
   * The exception that is not an exception: a cancelled meeting keeps its copy,
   * because that copy is the only place its attendees are still told the
   * meeting is off — eXo hides a cancelled event from its own screens.
   */
  @Test
  public void aCancelledMeetingKeepsItsTombstone() {
    assertTrue(policy.mayHoldCopy(event(EventStatus.CANCELLED)));
  }

  /**
   * An event nobody could read says nothing about whether its copy should go.
   * The other answer would turn a momentary agenda failure into a retirement of
   * every copy that user holds, on a pass that runs every few minutes.
   */
  @Test
  public void anEventThatCouldNotBeReadIsNotTreatedAsAPoll() {
    assertTrue(policy.mayHoldCopy(null));
  }

  /**
   * Agenda writes a status on every event it stores, but an event assembled in
   * memory — by a caller, or by a test — may carry none, and that is not a
   * poll either.
   */
  @Test
  public void anEventWithNoStatusIsNotTreatedAsAPoll() {
    assertTrue(policy.mayHoldCopy(new Event()));
  }

  /** Only a confirmed meeting is given a copy it does not already have. */
  @Test
  public void onlyAConfirmedMeetingIsSeeded() {
    assertTrue(policy.maySeedCopy(event(EventStatus.CONFIRMED)));
    assertFalse(policy.maySeedCopy(event(EventStatus.TENTATIVE)));
    assertFalse(policy.maySeedCopy(event(EventStatus.CANCELLED)));
    assertFalse(policy.maySeedCopy(null));
    assertFalse(policy.maySeedCopy(new Event()));
  }

  /**
   * The relation between the two questions, pinned rather than left as a
   * sentence in the javadoc: anything that may be seeded may be held. Written
   * over the enum rather than over three cases, so a status agenda adds
   * tomorrow is included without anybody remembering to add it.
   */
  @Test
  public void seedingIsStrictlyNarrowerThanHolding() {
    for (EventStatus status : EventStatus.values()) {
      Event event = event(status);
      assertTrue(!policy.maySeedCopy(event) || policy.mayHoldCopy(event),
                 "a " + status + " event may be seeded but may not hold a copy");
    }
  }

  /**
   * An event in one state.
   *
   * @param status the status agenda holds for it
   * @return the event
   */
  private Event event(EventStatus status) {
    Event event = new Event();
    event.setStatus(status);
    return event;
  }
}
