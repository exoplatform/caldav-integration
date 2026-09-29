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
package org.exoplatform.caldav.client.bluemind;

import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.plugin.CalendarOwners;
import org.exoplatform.caldav.plugin.CalendarSubscription;
import org.exoplatform.caldav.plugin.CalendarSubscriptionChannel;
import org.exoplatform.caldav.plugin.SubscriptionEdits;

/**
 * BlueMind's subscriptions as a channel: its container naming
 * ({@link BlueMindContainerNaming}) tells a subscription from the account's
 * own calendar, and its REST subscription API
 * ({@link BlueMindSubscriptionClient}) subscribes a colleague to a calendar
 * shared with them (EXO-90275, EXO-90277, EXO-90347).
 *
 * <p>
 * A {@code @Service}, like any contribution from another add-on, so that the
 * host collects it by type through the Kernel/Spring bridge and never names
 * it.
 */
@Service
public class BlueMindSubscriptionChannel implements CalendarSubscriptionChannel {

  /** The channel's identifier. */
  public static final String               ID = "bluemind";

  /** BlueMind's REST subscription API. */
  private final BlueMindSubscriptionClient blueMindSubscriptionClient;

  /**
   * The channel over BlueMind's subscription client.
   *
   * @param blueMindSubscriptionClient the REST client, may be null where only
   *          the naming is used
   */
  @Autowired
  public BlueMindSubscriptionChannel(BlueMindSubscriptionClient blueMindSubscriptionClient) {
    this.blueMindSubscriptionClient = blueMindSubscriptionClient;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String id() {
    return ID;
  }

  /**
   * The directory entry uid of a BlueMind user principal.
   *
   * @param principal a principal path, may be null
   * @return the uid, or null when the path is not a BlueMind user principal
   */
  @Override
  public String userUidOf(String principal) {
    return BlueMindContainerNaming.userUidOf(principal);
  }

  /**
   * The subscription BlueMind's container naming reveals, with the owner's
   * principal spelled the account's way.
   *
   * @param href the collection's path
   * @param principal the account's own principal path, may be null
   * @return the subscription, or null
   */
  @Override
  public CalendarSubscription subscriptionOf(String href, String principal) {
    BlueMindContainerNaming.Subscription subscription = BlueMindContainerNaming.subscriptionOf(href, principal);
    if (subscription == null) {
      return null;
    }
    return new CalendarSubscription(subscription.ownerUid(),
                                    subscription.resource(),
                                    BlueMindContainerNaming.principalOf(principal, subscription.ownerUid()));
  }

  /**
   * Runs the edits in one BlueMind session opened as the user.
   *
   * @param <T> what the job produces
   * @param endpoint the user's endpoint
   * @param userUid the directory entry uid recorded for them
   * @param job the edits
   * @return what the job produced
   */
  @Override
  public <T> T asSubscriber(CalDavEndpoint endpoint, String userUid, Function<SubscriptionEdits, T> job) {
    return blueMindSubscriptionClient.asSharee(endpoint, userUid, job::apply);
  }

  /**
   * BlueMind's own listing of who owns each calendar the account sees.
   *
   * @param endpoint the account's endpoint
   * @return the listing
   */
  @Override
  public CalendarOwners ownersOf(CalDavEndpoint endpoint) {
    return blueMindSubscriptionClient.ownersOf(endpoint);
  }

}
