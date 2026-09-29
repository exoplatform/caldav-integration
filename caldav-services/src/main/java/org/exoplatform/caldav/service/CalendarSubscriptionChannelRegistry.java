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

import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.plugin.CalendarOwners;
import org.exoplatform.caldav.plugin.CalendarSubscription;
import org.exoplatform.caldav.plugin.CalendarSubscriptionChannel;
import org.exoplatform.caldav.plugin.NoSubscriptionChannel;

/**
 * The subscription channels installed on this platform, and the questions
 * the host asks of them: whose calendar a listed collection is, which uid a
 * principal addresses, and through which channel an account's subscriptions
 * are edited.
 *
 * <p>
 * The contributions are read on each question, through the bean factory, so
 * a channel from another web application is found whatever the boot order.
 */
@Service
public class CalendarSubscriptionChannelRegistry {

  /** Where the contributed channels are read from, on each question. */
  private final Supplier<List<CalendarSubscriptionChannel>> channels;

  /**
   * The registry over every contributed channel.
   *
   * @param channels the contributions, from every web application, each read on its
   *          own so that one which cannot be created is left out
   *          ({@link ContributedBeans}); null reads as none
   */
  @Autowired
  public CalendarSubscriptionChannelRegistry(ObjectProvider<CalendarSubscriptionChannel> channels) {
    this(new ContributedBeans<>(channels, "subscription channel", CalendarSubscriptionChannel::id));
  }

  /**
   * The registry over a supplier of contributions.
   *
   * @param channels where the contributions are read from
   */
  private CalendarSubscriptionChannelRegistry(Supplier<List<CalendarSubscriptionChannel>> channels) {
    this.channels = channels;
  }

  /**
   * The registry over a fixed list of channels, as a test states them.
   *
   * @param channels the channels, may be null for none
   * @return the registry
   */
  public static CalendarSubscriptionChannelRegistry of(List<CalendarSubscriptionChannel> channels) {
    List<CalendarSubscriptionChannel> fixed = channels == null ? List.of() : List.copyOf(channels);
    return new CalendarSubscriptionChannelRegistry(() -> fixed);
  }

  /**
   * The channel of an account: the first one that names its principal's uid,
   * the null channel otherwise.
   *
   * @param principal the account's principal path, may be null
   * @return the channel, never null
   */
  public CalendarSubscriptionChannel channelOf(String principal) {
    return channels.get()
                   .stream()
                   .filter(Objects::nonNull)
                   .filter(channel -> channel.userUidOf(principal) != null)
                   .findFirst()
                   .orElse(NoSubscriptionChannel.INSTANCE);
  }

  /**
   * The uid a principal addresses its user by, through its channel.
   *
   * @param principal a principal path, may be null
   * @return the uid, or null when no installed channel reads one
   */
  public String userUidOf(String principal) {
    return channelOf(principal).userUidOf(principal);
  }

  /**
   * The subscription a listed collection is, as the first channel that reads
   * one in it says.
   *
   * @param href the collection's path
   * @param principal the account's own principal path, may be null
   * @return the subscription, or null when no installed channel reads one
   */
  public CalendarSubscription subscriptionOf(String href, String principal) {
    for (CalendarSubscriptionChannel channel : channels.get()) {
      CalendarSubscription subscription = channel == null ? null : channel.subscriptionOf(href, principal);
      if (subscription != null) {
        return subscription;
      }
    }
    return null;
  }

  /**
   * The channel asked where only a uid, not a principal, is in hand: the
   * first installed one, the null channel when none is.
   *
   * <p>
   * Two questions are asked that way: the subscription a share follows at
   * once, addressed by the uid the share channel read, and the owner listing
   * the owner cache keeps per server and user, not per channel. Both hold
   * while one product contributes subscriptions, which is the case this
   * seam was drawn for; a second one would carry the channel on those two
   * keys.
   *
   * @return the channel, never null
   */
  public CalendarSubscriptionChannel primary() {
    return channels.get().stream().filter(Objects::nonNull).findFirst().orElse(NoSubscriptionChannel.INSTANCE);
  }

  /**
   * The owner listing of an account, as the {@link #primary} channel answers
   * it. The caller only asks for an account whose principal a channel
   * already named ({@link #userUidOf}).
   *
   * @param endpoint the account's endpoint
   * @return the listing
   * @throws UnsupportedOperationException when no channel is installed, or
   *           the account's credentials cannot open a session
   */
  public CalendarOwners ownersOf(CalDavEndpoint endpoint) {
    return primary().ownersOf(endpoint);
  }

  /**
   * Whether any subscription channel is installed at all.
   *
   * @return true when at least one is
   */
  public boolean isEmpty() {
    return channels.get().stream().noneMatch(Objects::nonNull);
  }

}
