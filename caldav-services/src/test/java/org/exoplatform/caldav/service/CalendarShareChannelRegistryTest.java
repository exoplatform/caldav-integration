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
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.DavOptions;
import org.exoplatform.caldav.client.SharingMechanism;
import org.exoplatform.caldav.client.bluemind.BlueMindAclClient;
import org.exoplatform.caldav.client.bluemind.BlueMindShareChannel;
import org.exoplatform.caldav.plugin.CalendarShareChannel;

/**
 * The share channels as contributions (EXO-90730): with none installed a
 * collection gets the mechanism its advertised classes select, with
 * BlueMind's installed a BlueMind collection is BlueMind's and nothing else
 * changes — the same answers the host's former static selection gave.
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
   * With BlueMind's channel, a BlueMind collection is BlueMind's; the same
   * header elsewhere, and any other server, are not.
   */
  @Test
  public void theBlueMindChannelIsPickedForABlueMindCollectionAndNothingElse() {
    BlueMindShareChannel channel = new BlueMindShareChannel(mock(BlueMindAclClient.class), mock(CalDavClient.class));
    CalendarShareChannelRegistry registry = CalendarShareChannelRegistry.of(List.of(channel));

    assertEquals(SharingMechanism.BLUEMIND_SHARE, registry.mechanismOf(BLUEMIND, BLUEMIND_COLLECTION));
    assertEquals(SharingMechanism.CALENDARSERVER_SHARE, registry.mechanismOf(BLUEMIND, "/calendars/__uids__/x/y/"));
    assertEquals(SharingMechanism.WEBDAV_ACL, registry.mechanismOf(STALWART, BLUEMIND_COLLECTION));
    assertSame(channel, registry.channelFor(SharingMechanism.BLUEMIND_SHARE));
    assertNull(registry.channelFor(SharingMechanism.WEBDAV_ACL));
  }

  /**
   * The proof the selection moved without changing: over every shape the
   * former static rule was pinned on, the registry with BlueMind's channel
   * answers exactly what {@code SharingMechanism.of(options, href)} answers.
   */
  @Test
  @SuppressWarnings("removal")
  public void theRegistryAnswersWhatTheFormerStaticSelectionAnswered() {
    CalendarShareChannelRegistry registry = CalendarShareChannelRegistry.of(List.of(new BlueMindShareChannel(mock(BlueMindAclClient.class),
                                                                                                             mock(CalDavClient.class))));
    List<DavOptions> options = new ArrayList<>(List.of(BLUEMIND,
                                                       STALWART,
                                                       DavOptions.of(List.of("1, access-control, resource-sharing"), List.of("ACL")),
                                                       DavOptions.of(List.of("1, access-control, calendar-access, calendar-proxy"),
                                                                     List.of("ACL")),
                                                       DavOptions.of(List.of("1, calendar-access"), List.of("PROPFIND")),
                                                       DavOptions.of(null, null)));
    options.add(null);
    List<String> hrefs = new ArrayList<>(List.of(BLUEMIND_COLLECTION,
                                                 "https://bm.example.com" + BLUEMIND_COLLECTION,
                                                 "/calendars/__uids__/x/y/",
                                                 "/dav/cal/alice/default/",
                                                 "/dav/calendars/__uids__/x/"));
    hrefs.add(null);
    for (DavOptions option : options) {
      for (String href : hrefs) {
        assertEquals(SharingMechanism.of(option, href), registry.mechanismOf(option, href), option + " " + href);
      }
    }
  }

  /**
   * Contributions cross the Kernel/Spring bridge only as {@code @Service}
   * beans, and the channel carries an offered mechanism.
   */
  @Test
  public void theRegistryAndTheBlueMindChannelAreServiceBeans() {
    assertTrue(CalendarShareChannelRegistry.class.isAnnotationPresent(Service.class));
    assertTrue(BlueMindShareChannel.class.isAnnotationPresent(Service.class));
    CalendarShareChannel channel = new BlueMindShareChannel(null, null);
    assertTrue(channel.mechanism().isOffered());
  }
}
