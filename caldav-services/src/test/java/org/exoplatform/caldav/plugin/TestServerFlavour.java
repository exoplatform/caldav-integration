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

import java.util.Locale;
import java.util.Set;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.WriteChannel;

/**
 * A server flavour as an add-on contributes one, for the host's tests: it
 * recognises a registration whose name carries its marker, in any case, and
 * allows the write channels it is given. It keeps no session.
 */
public class TestServerFlavour implements CalendarServerFlavour {

  /** The flavour's identifier. */
  private final String            id;

  /** The lower-case marker a recognised registration's name carries. */
  private final String            marker;

  /** The write channels the flavour allows besides CalDAV. */
  private final Set<WriteChannel> channels;

  /**
   * A flavour recognising a name marker.
   *
   * @param id the flavour's identifier
   * @param marker the marker a recognised name carries, any case
   * @param channels the write channels it allows besides CalDAV
   */
  public TestServerFlavour(String id, String marker, Set<WriteChannel> channels) {
    this.id = id;
    this.marker = marker.toLowerCase(Locale.ROOT);
    this.channels = Set.copyOf(channels);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String id() {
    return id;
  }

  /**
   * Whether the registration's name carries the marker.
   *
   * @param server the registration, may be null
   * @return true when its name carries the marker
   */
  @Override
  public boolean recognises(CaldavServer server) {
    return server != null && server.getName() != null && server.getName().toLowerCase(Locale.ROOT).contains(marker);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<WriteChannel> writeChannels() {
    return channels;
  }
}
