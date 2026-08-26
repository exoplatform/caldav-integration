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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.exoplatform.caldav.model.IcsEvent;

/**
 * How a copy says a meeting is off.
 *
 * <p>
 * The user-facing half of the decision this feature had to take: a cancelled
 * meeting is rewritten with {@code STATUS:CANCELLED} rather than removed, so
 * the attendee's client shows it struck through and they can see it was called
 * off. A copy that simply disappeared would be indistinguishable from a
 * synchronisation that broke.
 */
public class IcsWriterCancellationTest {

  private final IcsWriter writer = new IcsWriter();

  /**
   * The property that carries the whole decision.
   */
  @Test
  public void aCancelledMeetingIsWrittenAsCancelled() {
    String ics = writer.write(meeting(true));

    assertTrue(ics.contains("STATUS:CANCELLED"), "a cancelled meeting must say so on the copy:\n" + ics);
    assertFalse(ics.contains("STATUS:CONFIRMED"), "and must not also claim to be confirmed:\n" + ics);
  }

  /**
   * And every other meeting is unaffected: the golden corpus pins CONFIRMED for
   * every event it writes, and this feature must not have moved it.
   */
  @Test
  public void anOrdinaryMeetingIsStillWrittenAsConfirmed() {
    String ics = writer.write(meeting(false));

    assertTrue(ics.contains("STATUS:CONFIRMED"), "an ordinary meeting stays confirmed:\n" + ics);
    assertFalse(ics.contains("STATUS:CANCELLED"), "and is never marked cancelled:\n" + ics);
  }

  /**
   * One meeting, cancelled or not.
   *
   * @param cancelled whether the meeting has been called off
   * @return the event to write
   */
  private IcsEvent meeting(boolean cancelled) {
    return IcsEvent.builder()
                   .uid("uid-cancel-1")
                   .summary("Steering point")
                   .start(Instant.parse("2026-09-08T07:00:00Z"))
                   .end(Instant.parse("2026-09-08T08:00:00Z"))
                   .timeZoneId("Europe/Paris")
                   .attendees(List.of())
                   .exceptionDates(List.of())
                   .reminders(List.of())
                   .cancelled(cancelled)
                   .build();
  }
}
