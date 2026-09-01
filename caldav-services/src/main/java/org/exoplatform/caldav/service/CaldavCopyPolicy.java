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

import org.springframework.stereotype.Service;

import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.model.Event;

/**
 * Which eXo events a calendar copy may stand for, said once.
 *
 * <h2>Why this is a class and not two conditions</h2>
 *
 * <p>
 * There are two questions, and they are not the same one: whether an event may
 * <b>acquire</b> a copy it does not have, and whether an event may <b>keep</b>
 * one it already has. They had drifted apart before this class existed — the
 * seeding pass required {@code CONFIRMED} and refused a date poll, while the
 * push core asked nothing at all and wrote whatever it was handed, so the
 * author's own browser pushed a copy of the very poll the fan-out had refused
 * everyone else. One rule stated in one place, read by every path, is the only
 * shape in which that cannot happen again.
 *
 * <h2>What a date poll is, and why no copy of one is written</h2>
 *
 * <p>
 * eXo spells a date poll {@code TENTATIVE} and stores it as <b>one</b> event
 * whose start and end are the <i>envelope</i> of every option proposed —
 * {@code AgendaEventServiceImpl.checkAndComputeDateOptions} sets the start to
 * the earliest option's start and the end to the latest option's end, then
 * marks the event {@code TENTATIVE}. So a poll offering Tuesday morning or
 * Friday afternoon is stored as an event running from Tuesday morning to
 * Friday afternoon, and a copy of it is a four-day block on the calendar of
 * everybody invited to vote. That block is not a meeting anybody is going to;
 * it describes nothing that will happen, and it makes its owner look busy for
 * four days. There is no fix for that inside the copy: the option list is what
 * carries the meaning and iCalendar has nowhere to put it. So the copy is not
 * written at all (EXO-89863).
 *
 * <p>
 * The idea this supersedes was to push the poll's envelope marked
 * {@code TRANSP:TRANSPARENT}, so at least it would not book the time. It was
 * shipped as far as the design and then dropped, for the reason above: a
 * four-day entry that does not book time is still a four-day entry describing
 * a meeting that is not happening, and being free-time makes it no more
 * truthful. The availability machinery it was built on stayed and is now used
 * for what it is actually for — an event its owner marked free (EXO-89870).
 *
 * <h2>Cancelled is not the same case</h2>
 *
 * <p>
 * A {@code CANCELLED} event keeps its copy, deliberately. The copy is the only
 * place an attendee is still told the meeting is off — eXo hides a cancelled
 * event from its own screens — and a client shows a cancelled entry struck
 * through where it shows a removed one not at all, which is also what a broken
 * synchronisation looks like. So the copy of a cancelled meeting is a
 * tombstone that has to exist, and only {@code TENTATIVE} is refused here.
 */
@Service
public class CaldavCopyPolicy {

  /**
   * Whether a calendar copy of this event may exist at all.
   *
   * <p>
   * The suppression rule, and the one every write path is guarded by. False
   * means more than "do not write": it means a copy that already exists is
   * retired, because an event can <i>become</i> a poll — adding a second date
   * option to a confirmed meeting through {@code updateEvent} sets it back to
   * {@code TENTATIVE}, and that is reachable from the REST API. Refusing new
   * writes alone would leave the copy written before the change standing for
   * ever.
   *
   * <p>
   * <b>A null event answers true, and that is not an oversight.</b> This
   * predicate says "a date poll may hold no copy", not "an event nobody could
   * read may hold none". An event that comes back null is a state each caller
   * already answers for itself and answers differently — the push refuses it
   * as a failure, the verification pass repairs or drops the row — and folding
   * it in here would turn a momentary read failure into a retirement, deleting
   * the copies of every meeting of a user whose agenda blinked.
   *
   * @param event the eXo event, as agenda holds it now; null when it could not
   *          be read, which this does not answer for
   * @return false only for a date poll
   */
  public boolean mayHoldCopy(Event event) {
    return event == null || event.getStatus() != EventStatus.TENTATIVE;
  }

  /**
   * Whether a calendar copy of this event may be written where none exists.
   *
   * <p>
   * Stricter than {@link #mayHoldCopy(Event)} by exactly one case, and the two
   * live side by side so that the difference is visible rather than inferred:
   * a {@code CANCELLED} event keeps the copy it has and is never given a new
   * one. Seeding a tombstone for a meeting somebody's calendar never held
   * would announce a cancellation to a person who was never told the meeting
   * existed.
   *
   * <p>
   * Everything this allows, {@link #mayHoldCopy(Event)} allows too. That
   * implication is what makes the pair safe to read as one rule, and it is
   * pinned by a test rather than left as a sentence.
   *
   * @param event the eXo event, as agenda holds it now; null when it could not
   *          be read, and a copy is never seeded for an event nobody can read
   * @return true only for a confirmed meeting
   */
  public boolean maySeedCopy(Event event) {
    return event != null && event.getStatus() == EventStatus.CONFIRMED;
  }
}
