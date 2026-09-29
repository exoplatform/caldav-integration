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

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.WriteChannel;
import org.exoplatform.caldav.plugin.CalendarServerFlavour;
import org.exoplatform.caldav.plugin.PlainServerFlavour;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The server flavours installed on this platform, and the one question the
 * host asks of them: which product a registration stands for.
 *
 * <p>
 * A {@code @Service} so that the Kernel components of this add-on — the
 * connector service among them — reach it through the Kernel/Spring bridge,
 * which exports {@code @Service} beans and nothing else. The contributions
 * are read on each question, through the bean factory, so a flavour from
 * another web application is found whatever the boot order.
 */
@Service
public class CalendarServerFlavourRegistry {

  private static final Log                           LOG = ExoLogger.getLogger(CalendarServerFlavourRegistry.class);

  private final Supplier<List<CalendarServerFlavour>> flavours;

  /**
   * The registry over every contributed flavour.
   *
   * @param flavours the contributions, from every web application; null reads
   *          as none
   */
  @Autowired
  public CalendarServerFlavourRegistry(ObjectProvider<CalendarServerFlavour> flavours) {
    this(() -> flavours == null ? List.of() : flavours.orderedStream().toList());
  }

  /**
   * The registry over a supplier of contributions.
   *
   * @param flavours where the contributions are read from
   */
  private CalendarServerFlavourRegistry(Supplier<List<CalendarServerFlavour>> flavours) {
    this.flavours = flavours;
  }

  /**
   * The registry over a fixed list of flavours, as a test states them.
   *
   * @param flavours the flavours, may be null for none
   * @return the registry
   */
  public static CalendarServerFlavourRegistry of(List<CalendarServerFlavour> flavours) {
    List<CalendarServerFlavour> fixed = flavours == null ? List.of() : List.copyOf(flavours);
    return new CalendarServerFlavourRegistry(() -> fixed);
  }

  /**
   * The flavour a registration stands for: the first contributed one that
   * recognises it, plain CalDAV otherwise.
   *
   * @param server the registration, may be null
   * @return the flavour, never null
   */
  public CalendarServerFlavour flavourOf(CaldavServer server) {
    if (server == null) {
      return PlainServerFlavour.INSTANCE;
    }
    return flavours.get()
                   .stream()
                   .filter(Objects::nonNull)
                   .filter(flavour -> flavour.recognises(server))
                   .findFirst()
                   .orElse(PlainServerFlavour.INSTANCE);
  }

  /**
   * Whether a registration may declare a write channel: CalDAV, or no
   * channel at all, always; any other one when the registration's flavour
   * allows it.
   *
   * @param server the registration
   * @param channel the channel it declares, may be null
   * @return true when the declaration is acceptable
   */
  public boolean accepts(CaldavServer server, WriteChannel channel) {
    return channel == null || channel == WriteChannel.CALDAV || flavourOf(server).writeChannels().contains(channel);
  }

  /**
   * Whether any installed flavour allows a write channel, whichever
   * registration it is for: false for a channel whose add-on is not
   * installed.
   *
   * @param channel the channel, may be null
   * @return true for CalDAV, no channel, or a channel some flavour allows
   */
  public boolean knows(WriteChannel channel) {
    return channel == null || channel == WriteChannel.CALDAV
        || flavours.get().stream().filter(Objects::nonNull).anyMatch(flavour -> flavour.writeChannels().contains(channel));
  }

  /**
   * Drops the session every flavour keeps for one account. One flavour that
   * fails does not stop the others, and never fails the caller.
   *
   * @param userIdentityId the social identity of the connected user
   * @param serverId the registration the account was on, null for the legacy
   *          deployment property
   */
  public void forgetSession(long userIdentityId, Long serverId) {
    for (CalendarServerFlavour flavour : flavours.get()) {
      try {
        flavour.forgetSession(userIdentityId, serverId);
      } catch (RuntimeException e) {
        LOG.warn("The {} session kept for user {} could not be dropped", flavour.id(), userIdentityId, e);
      }
    }
  }

  /**
   * Drops every session every flavour keeps. One flavour that fails does not
   * stop the others, and never fails the caller.
   */
  public void forgetAllSessions() {
    for (CalendarServerFlavour flavour : flavours.get()) {
      try {
        flavour.forgetAllSessions();
      } catch (RuntimeException e) {
        LOG.warn("The kept {} sessions could not be dropped after a server registration was written", flavour.id(), e);
      }
    }
  }

}
