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
import org.exoplatform.caldav.client.CalDavSubjectMismatchException;

/**
 * How a calendar server product lays out, and lets eXo edit, the calendars
 * an account <em>subscribes</em> to — the calendars of somebody else that its
 * DAV listing shows under the account's own home, and that a share only
 * reaches once the colleague is subscribed.
 *
 * <p>
 * <b>How it is found.</b> {@code CalendarSubscriptionChannelRegistry}
 * collects every bean implementing this interface, across web applications:
 * a contributor is a Spring {@code @Service}, never {@code final}. The channel
 * of an account is the first one that names its principal's uid
 * ({@link #userUidOf}); a listed collection is a subscription when the first
 * channel that reads one in it says so ({@link #subscriptionOf}).
 *
 * <p>
 * <b>What happens without one.</b> A listed collection is what the DAV
 * listing says it is (no subscription is recognised by its name), no owner
 * listing is read, and a subscription change eXo owes an account is
 * abandoned with a reason at the next drain rather than retried
 * ({@link NoSubscriptionChannel}).
 */
public interface CalendarSubscriptionChannel {

  /**
   * The channel's stable identifier, for the log.
   *
   * @return the identifier, never blank
   */
  String id();

  /**
   * The uid this product addresses a user by, read from their DAV principal.
   *
   * @param principal a principal path, any spelling, may be null
   * @return the uid, or null when the principal is not one of this product's
   *         user principals — acting on a guessed uid is worse than not acting
   */
  String userUidOf(String principal);

  /**
   * The subscription a collection listed in an account's home is, when its
   * name says it belongs to somebody else.
   *
   * @param href the collection's path, raw or canonical
   * @param principal the account's own principal path, may be null or blank
   *          when the server named none
   * @return the subscription, or null when the collection is not one, names
   *         the account itself, or no principal is known
   */
  CalendarSubscription subscriptionOf(String href, String principal);

  /**
   * Runs subscription edits in one session opened as a user, after checking
   * the session really is theirs.
   *
   * @param <T> what the job produces
   * @param endpoint the user's DAV endpoint, minted for <em>their</em> eXo
   *          login
   * @param userUid the uid eXo recorded for them
   * @param job the edits to make
   * @return what the job produced
   * @throws UnsupportedOperationException when the user's configured
   *           credentials cannot open such a session
   * @throws CalDavSubjectMismatchException when the server authenticated
   *           somebody other than the recorded uid; nothing was sent
   * @throws org.exoplatform.caldav.client.CalDavException when the session
   *           cannot be opened or an edit is answered with a failure
   */
  <T> T asSubscriber(CalDavEndpoint endpoint, String userUid, Function<SubscriptionEdits, T> job);

  /**
   * The owner of every calendar an account's session sees, as the server
   * itself answers it.
   *
   * @param endpoint the account's endpoint
   * @return the listing, never null
   * @throws UnsupportedOperationException when the account's configured
   *           credentials cannot open such a session
   * @throws RuntimeException whatever stops the listing from being read
   */
  CalendarOwners ownersOf(CalDavEndpoint endpoint);

}
