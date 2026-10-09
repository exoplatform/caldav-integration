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

import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.DavOptions;
import org.exoplatform.caldav.client.SharingMechanism;
import org.exoplatform.caldav.model.CalendarShares;
import org.exoplatform.caldav.model.CalendarShares.ShareAccess;

/**
 * A server-specific way of sharing a calendar collection with a colleague,
 * for a server on which the host's built-in mechanism — RFC 3744's
 * {@code ACL} method — is not how the server's own users share, and whose
 * every change the contribution can confirm by reading it back.
 *
 * <p>
 * <b>How it is found.</b> {@code CalendarShareChannelRegistry} collects every
 * bean implementing this interface, across web applications: a contributor
 * is a Spring {@code @Service}, never {@code final}. The mechanism a
 * collection gets is the one the host selects from what it advertises
 * ({@link SharingMechanism#of(DavOptions)}), unless a channel
 * {@link #applies} to it, in which case it is that channel's
 * {@link #mechanism}; every operation on such a collection is then the
 * channel's. The host keeps every check that is not the server's: the
 * calendar, its owner, the pair, the sharee, the capability probe.
 *
 * <p>
 * <b>What happens without one.</b> The host's own selection stands: a
 * BlueMind collection, which advertises Apple's sharing, selects
 * {@link SharingMechanism#CALENDARSERVER_SHARE}, which eXo does not offer,
 * so "Share" is not offered and every share operation is refused with
 * {@code caldav.share.notSupported} — never attempted through a mechanism
 * whose changes nobody reads back.
 */
public interface CalendarShareChannel {

  /**
   * The mechanism this channel carries, as the host reports it in its logs
   * and answers.
   *
   * @return an offered mechanism other than
   *         {@link SharingMechanism#WEBDAV_ACL}, which is the host's own,
   *         never null; a channel answering anything else is ignored, and so
   *         is a second channel for a mechanism an earlier one carries
   */
  SharingMechanism mechanism();

  /**
   * Whether this channel is how a collection is shared, given what it
   * advertises and what the host would select from that alone.
   *
   * @param options what the collection answered, never null
   * @param collectionHref the collection's path, may be null
   * @param selected what the host selects from the options alone
   * @return true when this channel takes the collection over
   */
  boolean applies(DavOptions options, String collectionHref, SharingMechanism selected);

  /**
   * Whether the owner's configured credentials can open whatever session
   * this channel reads the access list back through; the menu offers no
   * "Share" otherwise.
   *
   * @param endpoint the owner's endpoint
   * @return true when they can
   */
  boolean acceptsCredentials(CalDavEndpoint endpoint);

  /**
   * Whether a collection's path does not rule out the caller owning it — the
   * cheap check a listing affords, before the access list is read.
   *
   * @param canonicalHref the canonical collection href
   * @param principal the caller's recorded principal, never null
   * @return true when the path may be the caller's own calendar
   */
  boolean mayOwn(String canonicalHref, String principal);

  /**
   * Who the calendar is shared with, read from the server now.
   *
   * @param calendar the calendar, checked by the host
   * @param host what the host lends the operation
   * @return the sharees
   */
  CalendarShares shares(SharedCalendar calendar, ShareHost host);

  /**
   * Refuses an imported calendar the caller may not decide the access of on
   * the server; an eXo-created one passes untouched.
   *
   * @param calendar the calendar, checked by the host
   * @param host what the host lends the operation
   */
  void requireManager(SharedCalendar calendar, ShareHost host);

  /**
   * Gives a colleague access at the level asked for, confirmed by reading it
   * back.
   *
   * @param calendar the calendar, checked by the host
   * @param sharee the colleague, checked by the host
   * @param ownerPrincipal the caller's canonical principal, distinct from the
   *          sharee's
   * @param access {@link ShareAccess#READ} or {@link ShareAccess#WRITE}
   * @param host what the host lends the operation
   * @return the sharees as read back
   */
  CalendarShares grant(SharedCalendar calendar, ShareRecipient sharee, String ownerPrincipal, ShareAccess access, ShareHost host);

  /**
   * Takes a colleague's access away, confirmed by reading it back.
   *
   * @param calendar the calendar, checked by the host
   * @param sharee the colleague, checked by the host
   * @param ownerPrincipal the caller's canonical principal, distinct from the
   *          sharee's
   * @param host what the host lends the operation
   * @return the sharees as read back
   */
  CalendarShares revoke(SharedCalendar calendar, ShareRecipient sharee, String ownerPrincipal, ShareHost host);

}
