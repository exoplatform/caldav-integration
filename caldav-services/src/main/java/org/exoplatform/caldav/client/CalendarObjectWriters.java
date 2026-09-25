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
package org.exoplatform.caldav.client;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.caldav.client.bluemind.BlueMindImportWriter;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.WriteChannel;
import org.exoplatform.caldav.service.CaldavServerService;

/**
 * Picks the door a server's copies go through: the one write-channel decision
 * of the whole engine (EXO-90307).
 *
 * <p>
 * Chosen per <b>server</b>, never per call site: the registration's
 * {@link CaldavServer#getWriteChannel()} is read for the server the endpoint
 * was minted from, and every write and removal on that endpoint — the
 * organizer's copy, an invitee's copy, an answer carried onto a copy, a
 * repair, a relocation — goes through the same door. That is what makes the
 * administrator's flag a complete rollback: nothing else decides.
 *
 * <p>
 * No fallback on a registry failure, and that is deliberate where
 * {@code CaldavPushService.mirrorTargetOf} does fall back. There, writing the
 * copies where they have always gone is at worst unchanged behaviour. Here,
 * writing through CalDAV a copy meant for the import channel is the very
 * defect the channel exists to prevent — BlueMind would schedule the meeting
 * — so a registry that cannot be read fails the write, which the sweep
 * retries, rather than quietly choosing the wrong door. The endpoint was
 * minted from the same registry a moment earlier, so this read failing is a
 * real incident, not a routine miss.
 */
@Component
public class CalendarObjectWriters {

  private final CaldavServerService  caldavServerService;

  private final CalDavObjectWriter   calDavObjectWriter;

  private final BlueMindImportWriter blueMindImportWriter;

  /**
   * The resolver over the registry and the two doors.
   *
   * @param caldavServerService the registry the channel is read from
   * @param calDavObjectWriter the CalDAV door
   * @param blueMindImportWriter the BlueMind import door
   */
  @Autowired
  public CalendarObjectWriters(CaldavServerService caldavServerService,
                               CalDavObjectWriter calDavObjectWriter,
                               BlueMindImportWriter blueMindImportWriter) {
    this.caldavServerService = caldavServerService;
    this.calDavObjectWriter = calDavObjectWriter;
    this.blueMindImportWriter = blueMindImportWriter;
  }

  /**
   * The writer for every object of an endpoint's server.
   *
   * @param endpoint the endpoint minted from the registry
   * @return the door the server's registration declares
   * @throws RuntimeException whatever the registry raises when it cannot be
   *           read — propagated, never turned into a door
   */
  public CalendarObjectWriter writer(CalDavEndpoint endpoint) {
    return channelOf(endpoint) == WriteChannel.BLUEMIND_IMPORT ? blueMindImportWriter : calDavObjectWriter;
  }

  /**
   * The channel an endpoint's registration declares.
   *
   * <p>
   * An endpoint minted from the legacy deployment property carries no server
   * id and has no registration to declare anything; a registration that
   * states nothing declares CalDAV. Both are the door every deployment used
   * before the setting existed.
   *
   * @param endpoint the endpoint minted from the registry
   * @return the channel, never null
   */
  WriteChannel channelOf(CalDavEndpoint endpoint) {
    if (endpoint == null || endpoint.getServerId() == null) {
      return WriteChannel.CALDAV;
    }
    CaldavServer server = caldavServerService.resolveServer(endpoint.getServerId());
    return server == null || server.getWriteChannel() == null ? WriteChannel.CALDAV : server.getWriteChannel();
  }
}
