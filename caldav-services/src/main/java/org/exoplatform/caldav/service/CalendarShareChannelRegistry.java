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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

import org.exoplatform.caldav.client.DavOptions;
import org.exoplatform.caldav.client.SharingMechanism;
import org.exoplatform.caldav.plugin.CalendarShareChannel;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

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

  /** The registry's log. */
  private static final Log                           LOG     = ExoLogger.getLogger(CalendarShareChannelRegistry.class);

  /** Where the contributed channels are read from, on each question. */
  private final Supplier<List<CalendarShareChannel>> channels;

  /** The channels already reported as ignored, with the reason. */
  private final Set<String>                          ignored = ConcurrentHashMap.newKeySet();

  /**
   * The registry over every contributed channel.
   *
   * @param channels the contributions, from every web application, each read on its
   *          own so that one which cannot be created is left out
   *          ({@link ContributedBeans}); null reads as none
   */
  @Autowired
  public CalendarShareChannelRegistry(ObjectProvider<CalendarShareChannel> channels) {
    this(new ContributedBeans<>(channels, "share channel", CalendarShareChannel::mechanism));
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
   * The mechanism a collection gets: the first usable channel that applies
   * to it, the host's own selection from what it advertises otherwise.
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
    return usable().stream()
                   .filter(channel -> channel.applies(options, collectionHref, selected))
                   .map(CalendarShareChannel::mechanism)
                   .findFirst()
                   .orElse(selected);
  }

  /**
   * The channel carrying a mechanism, or null when the host carries it
   * itself — or nobody does. A usable channel is the only one of its
   * mechanism, so it is the channel whose {@link CalendarShareChannel#applies}
   * selected that mechanism in {@link #mechanismOf}.
   *
   * @param mechanism the mechanism selected, may be null
   * @return the channel, or null
   */
  public CalendarShareChannel channelFor(SharingMechanism mechanism) {
    if (mechanism == null) {
      return null;
    }
    return usable().stream().filter(channel -> channel.mechanism() == mechanism).findFirst().orElse(null);
  }

  /**
   * The contributed channels the host may hand a collection to, in their
   * declared order. Left out, and said so once each: a channel claiming no
   * mechanism, a mechanism eXo does not offer, or {@link SharingMechanism#WEBDAV_ACL}
   * — RFC 3744 is the host's own, and a channel claiming it would take over
   * every Stalwart share without its {@code applies} ever being asked — and a
   * second channel for a mechanism an earlier one carries, so that the
   * channel {@link #channelFor} answers is always the one whose
   * {@code applies} was asked.
   *
   * @return the usable channels, never null
   */
  private List<CalendarShareChannel> usable() {
    List<CalendarShareChannel> usable = new ArrayList<>();
    Set<SharingMechanism> carried = EnumSet.noneOf(SharingMechanism.class);
    for (CalendarShareChannel channel : channels.get()) {
      if (channel == null) {
        continue;
      }
      SharingMechanism mechanism = channel.mechanism();
      if (mechanism == null || mechanism == SharingMechanism.WEBDAV_ACL || !mechanism.isOffered()) {
        ignore(channel, "claims the mechanism " + mechanism + ", which a contribution cannot carry");
      } else if (!carried.add(mechanism)) {
        ignore(channel, "carries " + mechanism + ", which an earlier channel already carries");
      } else {
        usable.add(channel);
      }
    }
    return usable;
  }

  /**
   * Says a contributed channel is ignored: at WARN the first time, at debug
   * afterwards, since the contributions are read on every question.
   *
   * @param channel the channel ignored
   * @param reason why, as the log states it
   */
  private void ignore(CalendarShareChannel channel, String reason) {
    String name = ClassUtils.getUserClass(channel).getName();
    if (ignored.add(name + ' ' + reason)) {
      LOG.warn("The share channel {} is ignored: it {}", name, reason);
    } else {
      LOG.debug("The share channel {} is still ignored: it {}", name, reason);
    }
  }

}
