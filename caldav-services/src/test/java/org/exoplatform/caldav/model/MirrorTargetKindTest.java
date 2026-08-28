/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.caldav.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Reading a stored destination, including the values a database can hold and
 * this code did not write.
 *
 * <p>
 * The reason this is worth a test of its own: this method is the only thing
 * standing between a hand-edited row — or a row written by a later version —
 * and an exception thrown on <i>every</i> account resolving through that
 * registration. A tolerant read makes a strange value a degraded setting; an
 * intolerant one makes it an outage.
 */
public class MirrorTargetKindTest {

  /**
   * Every name the enum declares reads back as itself, which is what makes the
   * column round-trip at all.
   */
  @Test
  public void everyKindReadsBackAsItself() {
    for (MirrorTargetKind kind : MirrorTargetKind.values()) {
      assertEquals(kind, MirrorTargetKind.of(kind.name()), kind + " must survive being written and read");
    }
  }

  /**
   * Nothing stored is the behaviour every deployment already had — the same
   * answer the column's own DEFAULT gives, so a row this code never wrote and
   * one the database backfilled agree.
   */
  @Test
  public void nothingStoredMeansTheDedicatedCalendar() {
    assertEquals(MirrorTargetKind.DEDICATED_CALENDAR, MirrorTargetKind.of(null));
    assertEquals(MirrorTargetKind.DEDICATED_CALENDAR, MirrorTargetKind.of(""));
    assertEquals(MirrorTargetKind.DEDICATED_CALENDAR, MirrorTargetKind.of("   "));
  }

  /**
   * A value this version does not know degrades to the dedicated calendar
   * rather than throwing. Padding and case are tolerated for the same reason:
   * whoever typed the value into a database client was answering a question,
   * and refusing their answer costs an outage, not a correction.
   */
  @Test
  public void anUnknownOrUntidyValueDegradesRatherThanThrows() {
    assertEquals(MirrorTargetKind.DEDICATED_CALENDAR, MirrorTargetKind.of("SOMETHING_A_LATER_VERSION_WROTE"));
    assertEquals(MirrorTargetKind.MAIN_CALENDAR, MirrorTargetKind.of("Main_Calendar"));
  }

  /**
   * <b>The migration pin of EXO-89793.</b> A row written while
   * {@code USER_CHOICE} was a third kind (EXO-89760) still holds that string
   * today, and it must read as the dedicated calendar rather than throw.
   *
   * <p>
   * Asserted with the literal string and never an enum constant, deliberately:
   * the constant does not exist any more, and a database does not care. This is
   * the whole reason {@code MIRROR_TARGET} is mapped as a String rather than
   * {@code @Enumerated} — a throw on read would not merely misplace one
   * server's copies, it would make every registration behind that server
   * unreadable and take down every account resolving through it.
   *
   * <p>
   * It is also the reason no Liquibase data migration ships with the removal:
   * the value is already read correctly, so rewriting the stored rows would add
   * a write to a live table without adding any safety the reader does not
   * already give.
   */
  @Test
  public void aRowStoredAsTheWithdrawnUserChoiceReadsAsTheDedicatedCalendar() {
    assertEquals(MirrorTargetKind.DEDICATED_CALENDAR, MirrorTargetKind.of("USER_CHOICE"));
    assertEquals(MirrorTargetKind.DEDICATED_CALENDAR, MirrorTargetKind.of(" user_choice "));
  }
}
