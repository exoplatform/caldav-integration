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

import java.util.List;

import org.exoplatform.caldav.model.IcsDivergence;

/**
 * What comparing a copy against the object eXo would write concluded.
 *
 * <p>
 * Its own type rather than a nested one, since EXO-89771 gave it a third
 * component: it is the comparison's whole public surface, three readers depend
 * on it — the mirror pass, the recording of what a server does, and the tests —
 * and {@link IcsEquivalence} is at the size where a separable concept belongs
 * beside it rather than inside it.
 *
 * <p>
 * <b>Three answers, not two.</b> {@code DIFFERENT} and {@code EQUIVALENT} are
 * the judgements; {@code UNJUDGEABLE} is what comes back when eXo's own render
 * cannot be read as a calendar object holding one event. That is a defect on
 * eXo's side, never evidence about the user's calendar, and the caller leaves
 * the copy alone rather than overwriting it on the strength of one.
 *
 * @param verdict what was concluded
 * @param detail what diverged, or why nothing could be concluded; null when the
 *          two objects state the same thing
 * @param divergences what this server was seen doing to the copy, one entry per
 *          property — <b>including the ones its administrator has already
 *          excused</b>, so a ticked quirk goes on being shown with its count
 *          instead of vanishing from the list it was ticked in. Never null;
 *          empty when nothing could be concluded
 */
public record IcsJudgement(Verdict verdict, String detail, List<IcsDivergence> divergences) {

  /**
   * A judgement that says nothing about the server's own behaviour — the two
   * objects agree, or eXo could not read one of them.
   *
   * @param conclusion what was concluded
   * @param explanation what diverged, or why nothing could be concluded
   */
  public IcsJudgement(Verdict conclusion, String explanation) {
    this(conclusion, explanation, List.of());
  }

  /**
   * @return whether the server's copy is not what eXo would write
   */
  public boolean different() {
    return verdict == Verdict.DIFFERENT;
  }

  /** What a comparison can conclude. */
  public enum Verdict {
    /** The server's copy states what eXo would write. */
    EQUIVALENT,
    /** It does not, and the difference is one a repair would remove. */
    DIFFERENT,
    /** Nothing can be concluded, because eXo's own render is not usable. */
    UNJUDGEABLE
  }
}
