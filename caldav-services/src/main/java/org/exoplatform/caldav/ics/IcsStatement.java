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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.caldav.model.IcsDivergence;
import org.exoplatform.caldav.model.ServerQuirk;
import org.exoplatform.caldav.model.ServerQuirkDirection;

/**
 * How the canonical statements {@link IcsEquivalence} compares are spelled, how
 * the property name is read back out of one, and what a divergence on one says
 * the server does.
 *
 * <p>
 * Three readers need this and they must never drift apart: the comparison
 * itself, the excusal decision in {@link ServerExcusals}, and what is offered
 * to an administrator as something this server does. Written once, the
 * statement grammar is one fact rather than three implementations of it.
 */
final class IcsStatement {

  /**
   * The marker a statement carries when its property name is outside the
   * recognised set, so that it can only ever compare unequal against a side
   * that does not carry it.
   */
  static final String UNRECOGNISED = "UNRECOGNISED:";

  /** The property naming the meeting's organizer. */
  private static final String ORGANIZER = "ORGANIZER";

  /**
   * Not instantiated: a grammar, not an object.
   */
  private IcsStatement() {
  }

  /**
   * The property name a canonical statement was built from: everything before
   * its parameter suffix or its value.
   *
   * <p>
   * Statements are written as {@code NAME[;PARAM=…]=VALUE}, so the name ends at
   * the first {@code ;} or {@code =}, whichever comes first. A statement with
   * neither — {@code OWNER-ATTENDEE}, or an embedded {@code VALARM{…}} — yields
   * its whole text, which matches no event property and so can never be excused
   * by an operator's list.
   *
   * @param statement the canonical statement
   * @return the property name it states, never null
   */
  static String propertyNameOf(String statement) {
    int end = statement.length();
    for (int index = 0; index < statement.length(); index++) {
      char character = statement.charAt(index);
      if (character == ';' || character == '=') {
        end = index;
        break;
      }
    }
    return statement.substring(0, end);
  }

  /**
   * The property name of a statement built for a name outside the recognised
   * set.
   *
   * @param statement the canonical statement
   * @return the name, or null when the statement is not an unrecognised one
   */
  static String unrecognisedNameOf(String statement) {
    if (!statement.startsWith(UNRECOGNISED)) {
      return null;
    }
    return propertyNameOf(statement.substring(UNRECOGNISED.length()));
  }

  /**
   * The property name a statement is about, as an administrator is told it —
   * the {@code UNRECOGNISED:} marker stripped, because they are being told what
   * the server sends and not how this package classified it.
   *
   * @param statement the canonical statement
   * @return the property name, never null
   */
  static String observedPropertyOf(String statement) {
    String unrecognised = unrecognisedNameOf(statement);
    return unrecognised == null ? propertyNameOf(statement) : unrecognised;
  }

  /**
   * Records what a divergence says the server does, for the drawer to offer.
   *
   * <p>
   * <b>One divergence is reported as a case rather than as a property.</b> An
   * organizer the copy does not carry means two different things: on an ordinary
   * meeting it is an organizer somebody removed, which must stay reported and
   * must stay un-excusable; on an event with nobody but its creator it is one
   * server's own shape for an appointment, and the answer to that is that eXo
   * stops writing one (EXO-89775). Naming the second case
   * {@link ServerQuirk#SOLO_ORGANIZER} is what lets the drawer offer the second
   * without ever offering the first — and, because that token is not a property
   * {@link IcsWriter} emits, no excusal list can be pointed at it either.
   *
   * @param statement the canonical statement that diverged
   * @param onServer how many times the server's copy states it
   * @param inExo how many times eXo's render states it
   * @param observed the accumulator
   * @param soloEvent whether eXo's render names no participant other than the
   *          account's own owner
   */
  static void observe(String statement, int onServer, int inExo, List<IcsDivergence> observed, boolean soloEvent) {
    String property = observedPropertyOf(statement);
    if (StringUtils.isBlank(property)) {
      return;
    }
    ServerQuirkDirection direction = onServer > inExo ? ServerQuirkDirection.ADDED : ServerQuirkDirection.DROPPED;
    if (soloEvent && direction == ServerQuirkDirection.DROPPED && ORGANIZER.equals(property)) {
      observed.add(new IcsDivergence(ServerQuirk.SOLO_ORGANIZER, direction));
      return;
    }
    observed.add(new IcsDivergence(property, direction));
  }

  /**
   * Folds the divergences seen on one property into the one fact about the
   * server they state.
   *
   * <p>
   * A value the server substituted for eXo's arrives as two divergences — eXo's
   * statement the copy does not carry, and the copy's statement eXo never made
   * — and offering an administrator both halves of it, in opposite directions,
   * would be offering them a puzzle. A property seen in both directions in one
   * comparison is one server behaviour: {@link ServerQuirkDirection#REWRITTEN}.
   *
   * @param observed the divergences as the comparison found them
   * @return one entry per property, deduplicated
   */
  static List<IcsDivergence> collapse(List<IcsDivergence> observed) {
    Map<String, ServerQuirkDirection> byProperty = new TreeMap<>();
    for (IcsDivergence divergence : observed) {
      byProperty.merge(divergence.property(),
                       divergence.direction(),
                       (first, second) -> first == second ? first : ServerQuirkDirection.REWRITTEN);
    }
    List<IcsDivergence> behaviours = new ArrayList<>();
    byProperty.forEach((property, direction) -> behaviours.add(new IcsDivergence(property, direction)));
    return behaviours;
  }
}
