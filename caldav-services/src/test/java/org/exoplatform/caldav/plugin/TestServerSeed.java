/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.caldav.plugin;

import java.util.List;

import org.exoplatform.caldav.model.MirrorTargetKind;
import org.exoplatform.caldav.model.ServerQuirk;
import org.exoplatform.caldav.model.WriteChannel;

/**
 * A server seed as a server-specific add-on contributes one, for the host's
 * tests: every field of the contract is stated.
 *
 * @param id the seed's identifier
 * @param name the seeded row's name
 * @param serverUrl the seeded row's address
 * @param quirks the catalogue entries the row arrives excused for
 * @param mirrorTarget the destination of the row's copies
 * @param writeChannel the channel the row writes through
 */
public record TestServerSeed(String id,
                             String name,
                             String serverUrl,
                             List<ServerQuirk> quirks,
                             MirrorTargetKind mirrorTarget,
                             WriteChannel writeChannel) implements CalendarServerSeed {

  /** The address the product seed below is declared with, a placeholder. */
  public static final String            PRODUCT_URL    = "https://caldav.example.invalid/dav/";

  /** The entries the product seed below arrives excused for. */
  public static final List<ServerQuirk> PRODUCT_QUIRKS = List.of(ServerQuirk.DROPS_CONFERENCE,
                                                                 ServerQuirk.ADDS_COMPATIBILITY_MARKERS,
                                                                 ServerQuirk.ADDS_FORMATTED_DESCRIPTION,
                                                                 ServerQuirk.STAMPS_DEFAULT_PRIORITY);

  /**
   * The seed of a product with a door of its own, as the recorded
   * transcripts' server is seeded: four tolerance entries, the main calendar,
   * the import channel.
   *
   * @return the seed
   */
  public static TestServerSeed product() {
    return new TestServerSeed("bluemind",
                              "Bluemind",
                              PRODUCT_URL,
                              PRODUCT_QUIRKS,
                              MirrorTargetKind.MAIN_CALENDAR,
                              WriteChannel.BLUEMIND_IMPORT);
  }
}
