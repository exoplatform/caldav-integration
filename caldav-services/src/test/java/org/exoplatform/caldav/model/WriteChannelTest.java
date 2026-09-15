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
package org.exoplatform.caldav.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Reading a stored write channel, including the values a database can hold
 * and this code did not write — the same tolerance {@code MirrorTargetKind}
 * has, for the same reason: this read stands between a hand-edited row and an
 * exception on every account resolving through the registration.
 */
public class WriteChannelTest {

  /**
   * Every name the enum declares reads back as itself, which is what makes the
   * column round-trip at all.
   */
  @Test
  public void everyChannelReadsBackAsItself() {
    for (WriteChannel channel : WriteChannel.values()) {
      assertEquals(channel, WriteChannel.of(channel.name()), channel + " must survive being written and read");
    }
  }

  /**
   * Nothing stored is the door every deployment already used — the same answer
   * the column's own DEFAULT gives.
   */
  @Test
  public void nothingStoredMeansCalDav() {
    assertEquals(WriteChannel.CALDAV, WriteChannel.of(null));
    assertEquals(WriteChannel.CALDAV, WriteChannel.of(""));
    assertEquals(WriteChannel.CALDAV, WriteChannel.of("   "));
  }

  /**
   * A value this version does not know degrades to CalDAV rather than
   * throwing; padding and case are tolerated.
   */
  @Test
  public void anUnknownOrUntidyValueDegradesRatherThanThrows() {
    assertEquals(WriteChannel.CALDAV, WriteChannel.of("SOMETHING_A_LATER_VERSION_WROTE"));
    assertEquals(WriteChannel.BLUEMIND_IMPORT, WriteChannel.of(" bluemind_import "));
  }
}
