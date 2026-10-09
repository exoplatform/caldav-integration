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
 * What a calendar server product adds to the plain CalDAV the host speaks:
 * which registrations are that product's, the write channels it may use
 * besides CalDAV, and the per-account sessions it keeps outside the protocol.
 *
 * <p>
 * <b>How it is found.</b> {@code CalendarServerFlavourRegistry} collects every
 * bean implementing this interface, across web applications: a contributor
 * is a Spring {@code @Service}, never {@code final}. The first flavour that
 * {@link #recognises} a registration is that registration's flavour; a
 * registration nobody recognises is plain CalDAV
 * ({@link PlainServerFlavour}).
 *
 * <p>
 * <b>What happens without one.</b> A registration is plain CalDAV: only
 * {@link WriteChannel#CALDAV} is accepted on save, and a stored channel no
 * flavour allows is <em>refused</em> on the next save that states it and on
 * every write (see {@link CalendarWriteChannelPlugin}), never silently
 * switched back to CalDAV.
 */
public interface CalendarServerFlavour {

  /**
   * The flavour's stable identifier, the same as the front end's server
   * preset for the product.
   *
   * @return the identifier, never blank
   */
  String id();

  /**
   * Whether a registration stands for this product.
   *
   * @param server the registration as posted or stored, may be null
   * @return true when this flavour is the registration's
   */
  boolean recognises(CaldavServer server);

  /**
   * The channels, besides {@link WriteChannel#CALDAV}, a registration of this
   * product may declare.
   *
   * @return the channels, never null, possibly empty
   */
  Set<WriteChannel> writeChannels();

  /**
   * Drops whatever session this product keeps for one account, because eXo
   * changed what that account is — a connection, a reconnection, a
   * disconnection. Never throws for an account it keeps nothing for.
   *
   * @param userIdentityId the social identity of the connected user
   * @param serverId the registration the account was on, null for the legacy
   *          deployment property
   */
  default void forgetSession(long userIdentityId, Long serverId) {
    // Nothing kept by default
  }

  /**
   * Drops every session this product keeps, because a registration was
   * written or removed under them.
   */
  default void forgetAllSessions() {
    // Nothing kept by default
  }

}
