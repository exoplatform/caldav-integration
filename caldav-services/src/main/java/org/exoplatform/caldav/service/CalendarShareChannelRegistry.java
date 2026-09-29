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
package org.exoplatform.caldav.service;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.DavOptions;
import org.exoplatform.caldav.client.SharingMechanism;
import org.exoplatform.caldav.plugin.CalendarShareChannel;

/**
 * The share channels installed on this platform, and the two questions the
 * share service asks of them: which mechanism a collection gets, and which
 * channel, if any, carries that mechanism.
 *
 * <p>
 * The contributions are read on each question, through the bean factory, so
 * a channel from another web application is found whatever the boot order.
 */
@Service
public class CalendarShareChannelRegistry {

  /** Where the contributed channels are read from, on each question. */
  private final Supplier<List<CalendarShareChannel>> channels;

  /**
   * The registry over every contributed channel.
   *
   * @param channels the contributions, from every web application; null reads
   *          as none
   */
  @Autowired
  public CalendarShareChannelRegistry(ObjectProvider<CalendarShareChannel> channels) {
    this(() -> channels == null ? List.of() : channels.orderedStream().toList());
  }

  /**
   * The registry over a supplier of contributions.
   *
   * @param channels where the contributions are read from
   */
  private CalendarShareChannelRegistry(Supplier<List<CalendarShareChannel>> channels) {
    this.channels = channels;
  }

  /**
   * The registry over a fixed list of channels, as a test states them.
   *
   * @param channels the channels, may be null for none
   * @return the registry
   */
  public static CalendarShareChannelRegistry of(List<CalendarShareChannel> channels) {
    List<CalendarShareChannel> fixed = channels == null ? List.of() : List.copyOf(channels);
    return new CalendarShareChannelRegistry(() -> fixed);
  }

  /**
   * The mechanism a collection gets: the first channel that applies to it,
   * the host's own selection from what it advertises otherwise.
   *
   * @param options what the collection answered, null when nothing was asked
   * @param collectionHref the collection's path, may be null
   * @return the mechanism, never null
   */
  public SharingMechanism mechanismOf(DavOptions options, String collectionHref) {
    SharingMechanism selected = SharingMechanism.of(options);
    if (options == null) {
      return selected;
    }
    return channels.get()
                   .stream()
                   .filter(Objects::nonNull)
                   .filter(channel -> channel.applies(options, collectionHref, selected))
                   .map(CalendarShareChannel::mechanism)
                   .findFirst()
                   .orElse(selected);
  }

  /**
   * The channel carrying a mechanism, or null when the host carries it
   * itself — or nobody does.
   *
   * @param mechanism the mechanism selected, may be null
   * @return the channel, or null
   */
  public CalendarShareChannel channelFor(SharingMechanism mechanism) {
    if (mechanism == null) {
      return null;
    }
    return channels.get()
                   .stream()
                   .filter(Objects::nonNull)
                   .filter(channel -> channel.mechanism() == mechanism)
                   .findFirst()
                   .orElse(null);
  }

}
