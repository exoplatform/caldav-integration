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

import java.util.function.Function;

import org.exoplatform.caldav.client.CalDavEndpoint;

/**
 * The subscription channel of an account no contributed channel recognises:
 * no subscription is read in its listing, and nothing can be edited. The null
 * object of {@link CalendarSubscriptionChannel}.
 */
public final class NoSubscriptionChannel implements CalendarSubscriptionChannel {

  /** The identifier of the absent channel. */
  public static final String                ID       = "none";

  /** The single instance. */
  public static final NoSubscriptionChannel INSTANCE = new NoSubscriptionChannel();

  /**
   * The single instance only.
   */
  private NoSubscriptionChannel() {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String id() {
    return ID;
  }

  /**
   * No uid: nobody is addressed.
   *
   * @param principal the principal
   * @return null
   */
  @Override
  public String userUidOf(String principal) {
    return null;
  }

  /**
   * No subscription: the collection is what the DAV listing says.
   *
   * @param href the collection
   * @param principal the account's principal
   * @return null
   */
  @Override
  public CalendarSubscription subscriptionOf(String href, String principal) {
    return null;
  }

  /**
   * Refuses: no session can be opened.
   *
   * @param <T> what the job would produce
   * @param endpoint the user's endpoint
   * @param userUid the user's uid
   * @param job the edits
   * @return never
   * @throws UnsupportedOperationException always
   */
  @Override
  public <T> T asSubscriber(CalDavEndpoint endpoint, String userUid, Function<SubscriptionEdits, T> job) {
    throw new UnsupportedOperationException("No subscription channel is installed for this calendar server");
  }

  /**
   * Refuses: no listing can be read.
   *
   * @param endpoint the account's endpoint
   * @return never
   * @throws UnsupportedOperationException always
   */
  @Override
  public CalendarOwners ownersOf(CalDavEndpoint endpoint) {
    throw new UnsupportedOperationException("No subscription channel is installed for this calendar server");
  }

}
