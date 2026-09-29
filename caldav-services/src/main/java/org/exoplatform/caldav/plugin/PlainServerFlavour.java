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

import java.util.Set;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.WriteChannel;

/**
 * The flavour of a registration no contributed flavour recognises: a plain
 * CalDAV server, which writes through CalDAV only and keeps no session of its
 * own. The null object of {@link CalendarServerFlavour}.
 */
public final class PlainServerFlavour implements CalendarServerFlavour {

  /** The identifier of plain CalDAV. */
  public static final String             ID       = "caldav";

  /** The single instance. */
  public static final PlainServerFlavour INSTANCE = new PlainServerFlavour();

  /**
   * The single instance only.
   */
  private PlainServerFlavour() {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String id() {
    return ID;
  }

  /**
   * Recognises nothing: it is what is left when nobody else does.
   *
   * @param server the registration
   * @return false
   */
  @Override
  public boolean recognises(CaldavServer server) {
    return false;
  }

  /**
   * No channel besides CalDAV.
   *
   * @return an empty set
   */
  @Override
  public Set<WriteChannel> writeChannels() {
    return Set.of();
  }

}
