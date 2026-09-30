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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.DavOptions;
import org.exoplatform.caldav.client.SharingMechanism;
import org.exoplatform.caldav.plugin.CalendarShareChannel;

/**
 * The share channels as contributions (EXO-90730): with none installed a
 * collection gets the mechanism its advertised classes select; with one
 * installed, a collection the channel applies to gets the channel's
 * mechanism and nothing else changes.
 */
public class CalendarShareChannelRegistryTest {

  private static final String     BLUEMIND_DAV        = "1, access-control, calendar-access, calendar-proxy, calendarserver-sharing, addressbook";

  private static final String     BLUEMIND_COLLECTION = "/dav/calendars/__uids__/751E6D1A-7FDB-49B2-B668-B569E9A5A42D/exo-cal-1/";

  private static final DavOptions BLUEMIND            = DavOptions.of(List.of(BLUEMIND_DAV), List.of());

  private static final DavOptions STALWART            = DavOptions.of(List.of("1, 2, 3, access-control, calendar-access"),
                                                                      List.of("PROPFIND, REPORT, ACL"));

  /**
   * With no channel, a BlueMind collection selects Apple sharing, which is
   * not offered, and no mechanism has a channel.
   */
  @Test
  public void withNoChannelABlueMindCollectionSelectsAnUnofferedMechanism() {
    CalendarShareChannelRegistry registry = CalendarShareChannelRegistry.of(null);

    SharingMechanism mechanism = registry.mechanismOf(BLUEMIND, BLUEMIND_COLLECTION);

    assertEquals(SharingMechanism.CALENDARSERVER_SHARE, mechanism);
    assertFalse(mechanism.isOffered());
    assertNull(registry.channelFor(SharingMechanism.BLUEMIND_SHARE));
    assertNull(registry.channelFor(null));
    assertEquals(SharingMechanism.WEBDAV_ACL, registry.mechanismOf(STALWART, "/dav/cal/alice/default/"));
    assertEquals(SharingMechanism.NONE, registry.mechanismOf(null, BLUEMIND_COLLECTION));
  }

  /**
   * With a channel installed, a collection it applies to gets its mechanism;
   * a collection it does not apply to, and any other server, keep the
   * mechanism their advertised classes select. The channel is asked with the
   * host's own selection.
   */
  @Test
  public void anInstalledChannelIsPickedWhereItAppliesAndNowhereElse() {
    CalendarShareChannel channel = mock(CalendarShareChannel.class);
    when(channel.mechanism()).thenReturn(SharingMechanism.BLUEMIND_SHARE);
    when(channel.applies(any(), any(), any())).thenAnswer(call -> BLUEMIND_COLLECTION.equals(call.getArgument(1))
        && call.getArgument(2) == SharingMechanism.CALENDARSERVER_SHARE);
    CalendarShareChannelRegistry registry = CalendarShareChannelRegistry.of(List.of(channel));

    assertEquals(SharingMechanism.BLUEMIND_SHARE, registry.mechanismOf(BLUEMIND, BLUEMIND_COLLECTION));
    assertEquals(SharingMechanism.CALENDARSERVER_SHARE, registry.mechanismOf(BLUEMIND, "/calendars/__uids__/x/y/"));
    assertEquals(SharingMechanism.WEBDAV_ACL, registry.mechanismOf(STALWART, BLUEMIND_COLLECTION));
    assertSame(channel, registry.channelFor(SharingMechanism.BLUEMIND_SHARE));
    assertNull(registry.channelFor(SharingMechanism.WEBDAV_ACL));
    verify(channel).applies(BLUEMIND, BLUEMIND_COLLECTION, SharingMechanism.CALENDARSERVER_SHARE);
  }

  /**
   * A contribution claiming RFC 3744 is ignored: the host keeps every
   * Stalwart share, and the contribution's {@code applies} is never asked.
   */
  @Test
  public void aChannelClaimingTheHostsOwnAclMechanismIsIgnored() {
    CalendarShareChannel acl = channel(SharingMechanism.WEBDAV_ACL, true);
    CalendarShareChannelRegistry registry = CalendarShareChannelRegistry.of(List.of(acl));

    assertEquals(SharingMechanism.WEBDAV_ACL, registry.mechanismOf(STALWART, "/dav/cal/alice/default/"));
    assertNull(registry.channelFor(SharingMechanism.WEBDAV_ACL));
    verify(acl, never()).applies(any(), any(), any());
  }

  /**
   * A contribution claiming a mechanism eXo does not offer, or none, is
   * ignored: the host's own selection stands.
   */
  @Test
  public void aChannelClaimingAnUnofferedMechanismIsIgnored() {
    CalendarShareChannelRegistry registry = CalendarShareChannelRegistry.of(List.of(channel(SharingMechanism.NONE, true),
                                                                                    channel(null, true)));

    assertEquals(SharingMechanism.WEBDAV_ACL, registry.mechanismOf(STALWART, "/dav/cal/alice/default/"));
    assertNull(registry.channelFor(SharingMechanism.NONE));
  }

  /**
   * Of two channels for one mechanism, the first is the only one: the
   * channel that carries the operations is always the one whose
   * {@code applies} selected the mechanism, never a later one's answer
   * handed to the earlier channel.
   */
  @Test
  public void aSecondChannelForTheSameMechanismIsIgnored() {
    CalendarShareChannel first = channel(SharingMechanism.BLUEMIND_SHARE, false);
    CalendarShareChannel second = channel(SharingMechanism.BLUEMIND_SHARE, true);
    CalendarShareChannelRegistry registry = CalendarShareChannelRegistry.of(List.of(first, second));

    assertEquals(SharingMechanism.CALENDARSERVER_SHARE, registry.mechanismOf(BLUEMIND, BLUEMIND_COLLECTION));
    assertSame(first, registry.channelFor(SharingMechanism.BLUEMIND_SHARE));
    verify(second, never()).applies(any(), any(), any());
  }

  /**
   * The registry crosses the Kernel/Spring bridge only as a {@code @Service}
   * bean.
   */
  @Test
  public void theRegistryIsAServiceBean() {
    assertTrue(CalendarShareChannelRegistry.class.isAnnotationPresent(Service.class));
  }

  /**
   * A contributed channel carrying a mechanism and answering every
   * collection the same way.
   *
   * @param mechanism the mechanism it claims, may be null
   * @param applies what its {@code applies} answers
   * @return the channel
   */
  private static CalendarShareChannel channel(SharingMechanism mechanism, boolean applies) {
    CalendarShareChannel channel = mock(CalendarShareChannel.class);
    when(channel.mechanism()).thenReturn(mechanism);
    when(channel.applies(any(), any(), any())).thenReturn(applies);
    return channel;
  }
}
