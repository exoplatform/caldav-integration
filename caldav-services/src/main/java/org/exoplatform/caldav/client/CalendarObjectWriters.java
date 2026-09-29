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

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.WriteChannel;
import org.exoplatform.caldav.plugin.CalendarWriteChannelPlugin;
import org.exoplatform.caldav.service.CaldavPushException;
import org.exoplatform.caldav.service.CaldavPushService;
import org.exoplatform.caldav.service.CaldavServerService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

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
 *
 * <p>
 * <b>The doors are contributed.</b> CalDAV is built in; every other channel's
 * writer comes from a {@link CalendarWriteChannelPlugin}, collected by type
 * from every web application, so this class names no server. A registration
 * declaring a channel nobody contributes is refused with
 * {@link CaldavPushService#WRITE_CHANNEL_UNAVAILABLE} — never written through
 * CalDAV instead, for the reason stated above.
 */
@Component
public class CalendarObjectWriters {

  private static final Log                                LOG = ExoLogger.getLogger(CalendarObjectWriters.class);

  private final CaldavServerService                       caldavServerService;

  private final CalDavObjectWriter                        calDavObjectWriter;

  /** Where the contributed doors are read from, once. */
  private final Supplier<List<CalendarWriteChannelPlugin>> plugins;

  /**
   * The contributed doors, by channel, read once on first use: contributions
   * from another web application are only known once every context has
   * published its beans.
   */
  private volatile Map<WriteChannel, CalendarObjectWriter> contributed;

  /**
   * The resolver over the registry, the CalDAV door and every contributed one.
   *
   * @param caldavServerService the registry the channel is read from
   * @param calDavObjectWriter the CalDAV door
   * @param plugins the contributed doors, from every web application; null
   *          reads as none
   */
  @Autowired
  public CalendarObjectWriters(CaldavServerService caldavServerService,
                               CalDavObjectWriter calDavObjectWriter,
                               ObjectProvider<CalendarWriteChannelPlugin> plugins) {
    this(caldavServerService, calDavObjectWriter, () -> plugins == null ? List.of() : plugins.orderedStream().toList());
  }

  /**
   * The resolver over a fixed list of contributions, as a test states them.
   *
   * @param caldavServerService the registry the channel is read from
   * @param calDavObjectWriter the CalDAV door
   * @param plugins the contributed doors, may be null for none
   * @return the resolver
   */
  public static CalendarObjectWriters of(CaldavServerService caldavServerService,
                                         CalDavObjectWriter calDavObjectWriter,
                                         List<CalendarWriteChannelPlugin> plugins) {
    return new CalendarObjectWriters(caldavServerService, calDavObjectWriter, () -> plugins == null ? List.of() : plugins);
  }

  /**
   * The one constructor both others delegate to.
   *
   * @param caldavServerService the registry the channel is read from
   * @param calDavObjectWriter the CalDAV door
   * @param plugins where the contributed doors are read from
   */
  private CalendarObjectWriters(CaldavServerService caldavServerService,
                                CalDavObjectWriter calDavObjectWriter,
                                Supplier<List<CalendarWriteChannelPlugin>> plugins) {
    this.caldavServerService = caldavServerService;
    this.calDavObjectWriter = calDavObjectWriter;
    this.plugins = plugins;
  }

  /**
   * The writer for every object of an endpoint's server.
   *
   * @param endpoint the endpoint minted from the registry
   * @return the door the server's registration declares
   * @throws CaldavPushException with
   *           {@link CaldavPushService#WRITE_CHANNEL_UNAVAILABLE} when the
   *           registration declares a channel no contribution serves
   * @throws RuntimeException whatever the registry raises when it cannot be
   *           read — propagated, never turned into a door
   */
  public CalendarObjectWriter writer(CalDavEndpoint endpoint) {
    WriteChannel channel = channelOf(endpoint);
    if (channel == WriteChannel.CALDAV) {
      return calDavObjectWriter;
    }
    CalendarObjectWriter writer = contributed().get(channel);
    if (writer == null) {
      throw new CaldavPushException(CaldavPushService.WRITE_CHANNEL_UNAVAILABLE,
                                    "The server registration " + endpoint.getServerId() + " declares the write channel "
                                        + channel + ", which no installed add-on serves; the copy is not written");
    }
    return writer;
  }

  /**
   * Whether a channel has a door on this platform: CalDAV always, any other
   * one when a contribution serves it.
   *
   * @param channel the channel, may be null for CalDAV
   * @return true when a write on that channel can be carried out
   */
  public boolean serves(WriteChannel channel) {
    return channel == null || channel == WriteChannel.CALDAV || contributed().containsKey(channel);
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

  /**
   * The contributed doors by channel, read on first use. The first
   * contribution for a channel wins, in the contributions' declared order; a
   * later one for the same channel, and one claiming CalDAV, are ignored and
   * said so.
   *
   * @return the doors, never null
   */
  private Map<WriteChannel, CalendarObjectWriter> contributed() {
    Map<WriteChannel, CalendarObjectWriter> doors = contributed;
    if (doors == null) {
      Map<WriteChannel, CalendarObjectWriter> read = new EnumMap<>(WriteChannel.class);
      for (CalendarWriteChannelPlugin plugin : plugins.get()) {
        WriteChannel channel = plugin == null ? null : plugin.channel();
        if (channel == null || channel == WriteChannel.CALDAV || plugin.writer() == null) {
          LOG.warn("The write channel contribution {} names no channel eXo can hand over ({}); it is ignored", plugin, channel);
        } else if (read.putIfAbsent(channel, plugin.writer()) != null) {
          LOG.warn("The write channel {} is contributed twice; {} is ignored", channel, plugin);
        }
      }
      doors = Collections.unmodifiableMap(read);
      contributed = doors;
    }
    return doors;
  }
}
