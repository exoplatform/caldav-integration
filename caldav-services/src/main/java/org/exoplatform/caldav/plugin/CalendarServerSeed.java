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
 * A server registration a calendar server product asks the host to write on
 * a first install, beside the host's own default (EXO-90737): the pre-filled
 * row an administrator edits into their own server.
 *
 * <p>
 * <b>How it is found.</b> {@code CalendarServerSeedRegistry} collects every
 * bean implementing this interface, across web applications: a contributor
 * is a Spring {@code @Service}, never {@code final}. Contributions are read
 * in order ({@code Ordered}), and the first one for an {@link #id()} is the
 * one seeded: a later contribution with the same identifier is ignored, so a
 * product seeds one row however many of its contributions are installed.
 *
 * <p>
 * <b>What the host keeps for itself.</b> A seed describes the row; it never
 * decides whether the row arrives switched on, nor which agenda provider it
 * is bridged to. The host seeds only an <em>empty</em> registry, puts the
 * address through the check an administrator's address meets and activates
 * the row only when it passes, names the provider, and turns the quirks into
 * the row's tolerance columns. An install that already holds registrations
 * is never seeded again, so a contribution installed later adds no row.
 *
 * <p>
 * <b>What happens without one.</b> The host seeds its own default only.
 */
public interface CalendarServerSeed {

  /**
   * The seed's stable identifier — the product's, the same as its server
   * flavour's — which decides which of two contributions for one product is
   * seeded.
   *
   * @return the identifier, never blank
   */
  String id();

  /**
   * The name the row is seeded under.
   *
   * @return the name, never blank
   */
  String name();

  /**
   * The address the row is seeded with, usually a placeholder the
   * administrator replaces with their own server's.
   *
   * @return the address, never blank
   */
  String serverUrl();

  /**
   * The catalogue entries the row arrives excused for, the same ones the
   * product's browser preset ticks. Only tolerance entries can be seeded;
   * an entry that changes what eXo writes is logged and left out.
   *
   * @return the entries, never null, possibly empty
   */
  default List<ServerQuirk> quirks() {
    return List.of();
  }

  /**
   * Which calendar of the account the row mirrors eXo's events into.
   *
   * @return the destination, never null
   */
  default MirrorTargetKind mirrorTarget() {
    return MirrorTargetKind.DEDICATED_CALENDAR;
  }

  /**
   * The channel the row writes eXo's copies through.
   *
   * @return the channel, never null
   */
  default WriteChannel writeChannel() {
    return WriteChannel.CALDAV;
  }

}
