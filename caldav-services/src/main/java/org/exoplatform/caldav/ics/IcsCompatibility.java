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

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.util.CompatibilityHints;

/**
 * The one place a parser of foreign iCalendar is built, and the one place the
 * library is told how strict to be about what other people's software writes.
 *
 * <p>
 * <b>Why a factory rather than a static block somewhere.</b> ical4j reads its
 * compatibility hints from a process-wide map at the moment a value is parsed,
 * so a hint set in one class's static initialiser only holds for a parse that
 * happens to run after that class was loaded. This add-on parses in four
 * places — {@link IcsParser}, {@link IcsReader}, {@link IcsEquivalence},
 * {@link IcsMerger} — and the hints used to live in the static block of one of
 * them. Routing every builder through here makes the ordering structural: a
 * parse cannot be reached without the hints being set first.
 *
 * <p>
 * <b>Relaxed validation, and why it is the one that mattered.</b>
 * {@code ical4j.parsing.relaxed} does not reach the EMAIL parameter:
 * {@code net.fortuna.ical4j.model.parameter.Email}'s constructor runs its
 * address through commons-validator and throws unless
 * {@code ical4j.validation.relaxed} is on. commons-validator requires a
 * <i>public</i> top-level domain, so an address on an internal mail domain —
 * {@code alice@stalwart.local}, and equally a customer's {@code .internal} or
 * {@code .corp} — made the whole object unreadable. Apple Calendar writes
 * {@code EMAIL=} on the attendee line whenever its user answers an invitation,
 * so on such a deployment every answered copy became unparseable: the answer
 * could not be read in, and the copy could not be rewritten out either, since
 * both directions parse the object first (EXO-89820).
 *
 * <p>
 * <b>What the hint reaches.</b> On a parse path, exactly one thing: the
 * address check in {@code Email}'s constructor. Everywhere else ical4j reads
 * it — {@code XProperty}, {@code XComponent}, the {@code VEvent} validator's
 * rule set, {@code CalendarValidatorImpl} — it is consulted inside
 * {@code validate()}, which this add-on never calls: eXo judges a calendar
 * object against what it itself wrote (see {@link IcsEquivalence}), never
 * against ical4j's RFC validator. Switching validation off therefore relaxes
 * no check eXo was relying on.
 *
 * <p>
 * <b>Scope.</b> ical4j 3.2 offers no per-parse strictness — {@code
 * CalendarBuilder} takes no such argument — so the hints are process-wide for
 * the classloader that holds ical4j, exactly as the relaxed-parsing hint
 * already was. That is acceptable because the relaxation only ever widens what
 * a <i>reader</i> accepts; it cannot change a byte this add-on writes.
 */
public final class IcsCompatibility {

  static {
    // This add-on reads iCalendar written by other people's servers and
    // clients, and it must not discard an object because one value offends a
    // validator.
    //
    // Relaxed parsing: a client gets a duration, a priority or a trailing dot
    // subtly wrong and the object is still perfectly readable.
    CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_PARSING, true);
    // Relaxed unfolding: line folding is another thing clients get subtly
    // wrong, and the same argument applies.
    CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_UNFOLDING, true);
    // Relaxed validation: the EMAIL parameter's address check, and nothing
    // else on a parse path. See the class comment — an internal mail domain
    // is ordinary, and a deployment must be able to read what its own server
    // publishes.
    CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_VALIDATION, true);
  }

  /**
   * Not instantiable: this holds a process-wide setting and a factory.
   */
  private IcsCompatibility() {
  }

  /**
   * A parser configured to read what other people's software writes.
   *
   * <p>
   * A fresh builder each time, deliberately: {@code CalendarBuilder} keeps
   * per-parse state and is not safe to share between the threads a sweep runs
   * on.
   *
   * @return a builder whose compatibility hints are already in force
   */
  public static CalendarBuilder newCalendarBuilder() {
    return new CalendarBuilder();
  }
}
