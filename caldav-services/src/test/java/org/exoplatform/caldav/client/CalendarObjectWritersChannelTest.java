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
package org.exoplatform.caldav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.WriteChannel;
import org.exoplatform.caldav.plugin.CalendarWriteChannelPlugin;
import org.exoplatform.caldav.service.CaldavPushException;
import org.exoplatform.caldav.service.CaldavPushService;
import org.exoplatform.caldav.service.CaldavServerService;

/**
 * The write doors as contributions (EXO-90730): CalDAV built in, every other
 * channel served by whatever {@link CalendarWriteChannelPlugin} is installed,
 * and a channel nobody serves refused rather than written through CalDAV.
 */
public class CalendarObjectWritersChannelTest {

  private CaldavServerService registry;

  private CalDavObjectWriter  caldav;

  /**
   * A registry and a CalDAV door, both mocked.
   */
  @BeforeEach
  void aRegistryAndTheCalDavDoor() {
    registry = mock(CaldavServerService.class);
    caldav = mock(CalDavObjectWriter.class);
  }

  /**
   * With no contribution at all, CalDAV is still the door of every server
   * declaring it or declaring nothing.
   */
  @Test
  void withNoContributionCalDavStillWrites() {
    CalendarObjectWriters writers = CalendarObjectWriters.of(registry, caldav, (List<CalendarWriteChannelPlugin>) null);
    when(registry.resolveServer(1L)).thenReturn(server(WriteChannel.CALDAV));
    when(registry.resolveServer(2L)).thenReturn(server(null));

    assertSame(caldav, writers.writer(endpoint(1L)));
    assertSame(caldav, writers.writer(endpoint(2L)));
    assertSame(caldav, writers.writer(endpoint(null)));
    assertTrue(writers.serves(WriteChannel.CALDAV));
    assertTrue(writers.serves(null));
    assertFalse(writers.serves(WriteChannel.BLUEMIND_IMPORT));
  }

  /**
   * The refused-and-reported state of a server on the import channel when
   * the BlueMind contribution is absent: the write fails with the known-state
   * code, and the CalDAV door is never handed out in its place — that would
   * make BlueMind send the invitations the channel exists to suppress.
   */
  @Test
  void anImportChannelWithNoContributionIsRefusedNeverWrittenOverCalDav() {
    CalendarObjectWriters writers = CalendarObjectWriters.of(registry, caldav, List.of());
    when(registry.resolveServer(7L)).thenReturn(server(WriteChannel.BLUEMIND_IMPORT));

    CalDavEndpoint endpoint = endpoint(7L);
    CaldavPushException refused = assertThrows(CaldavPushException.class, () -> writers.writer(endpoint));

    assertEquals(CaldavPushService.WRITE_CHANNEL_UNAVAILABLE, refused.getCode());
    assertTrue(CaldavPushService.isKnownState(refused.getCode()), "reported as a state, at debug, not as an incident");
    verifyNoInteractions(caldav);
  }

  /**
   * A contribution for the import channel, registered as any add-on's would
   * be, serves that channel and nothing else.
   */
  @Test
  void aContributionServesItsChannelAndNothingElse() {
    CalendarObjectWriter importDoor = mock(CalendarObjectWriter.class);
    CalendarObjectWriters writers = CalendarObjectWriters.of(registry,
                                                              caldav,
                                                              List.of(plugin(WriteChannel.BLUEMIND_IMPORT, importDoor)));
    when(registry.resolveServer(7L)).thenReturn(server(WriteChannel.BLUEMIND_IMPORT));
    when(registry.resolveServer(1L)).thenReturn(server(WriteChannel.CALDAV));

    assertSame(importDoor, writers.writer(endpoint(7L)));
    assertSame(caldav, writers.writer(endpoint(1L)));
    assertTrue(writers.serves(WriteChannel.BLUEMIND_IMPORT));
  }

  /**
   * CalDAV cannot be claimed, and a channel contributed twice keeps its first
   * door: a contribution never replaces the built-in protocol nor silently
   * swaps another add-on's door.
   */
  @Test
  void calDavCannotBeClaimedAndTheFirstContributionWins() {
    CalendarObjectWriter first = mock(CalendarObjectWriter.class);
    CalendarObjectWriter second = mock(CalendarObjectWriter.class);
    CalendarObjectWriter usurper = mock(CalendarObjectWriter.class);
    CalendarObjectWriters writers = CalendarObjectWriters.of(registry,
                                                              caldav,
                                                              List.of(plugin(WriteChannel.CALDAV, usurper),
                                                                      plugin(WriteChannel.BLUEMIND_IMPORT, first),
                                                                      plugin(WriteChannel.BLUEMIND_IMPORT, second)));
    when(registry.resolveServer(1L)).thenReturn(server(WriteChannel.CALDAV));
    when(registry.resolveServer(7L)).thenReturn(server(WriteChannel.BLUEMIND_IMPORT));

    assertSame(caldav, writers.writer(endpoint(1L)));
    assertSame(first, writers.writer(endpoint(7L)));
  }

  /**
   * A contribution serving one channel with one writer.
   *
   * @param channel the channel
   * @param writer the writer
   * @return the contribution
   */
  private static CalendarWriteChannelPlugin plugin(WriteChannel channel, CalendarObjectWriter writer) {
    return new CalendarWriteChannelPlugin() {
      @Override
      public WriteChannel channel() {
        return channel;
      }

      @Override
      public CalendarObjectWriter writer() {
        return writer;
      }
    };
  }

  /**
   * A registration declaring a channel.
   *
   * @param channel the channel, may be null
   * @return the registration
   */
  private static CaldavServer server(WriteChannel channel) {
    CaldavServer server = new CaldavServer();
    server.setId(7L);
    server.setWriteChannel(channel);
    return server;
  }

  /**
   * An endpoint minted for a registration.
   *
   * @param serverId the registration, null for the legacy property
   * @return the endpoint
   */
  private static CalDavEndpoint endpoint(Long serverId) {
    return TestEndpoints.endpoint(serverId, URI.create("https://dav.example/dav/"), "personal", "john");
  }
}
