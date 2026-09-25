/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.caldav.client;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.exoplatform.caldav.client.bluemind.BlueMindImportWriter;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.WriteChannel;
import org.exoplatform.caldav.service.CaldavServerService;

/**
 * The one write-channel decision (EXO-90307): which door a server's copies go
 * through is read from that server's registration and nowhere else.
 */
public class CalendarObjectWritersTest {

  private CaldavServerService  registry;

  private CalDavObjectWriter   caldav;

  private BlueMindImportWriter bluemind;

  private CalendarObjectWriters writers;

  /**
   * A resolver over a mocked registry and two distinguishable doors.
   */
  @BeforeEach
  void aResolverOverTheRegistry() {
    registry = mock(CaldavServerService.class);
    caldav = mock(CalDavObjectWriter.class);
    bluemind = mock(BlueMindImportWriter.class);
    writers = new CalendarObjectWriters(registry, caldav, bluemind);
  }

  /**
   * Only a registration that declares the import channel gets the BlueMind
   * door.
   */
  @Test
  void aServerDeclaringTheImportChannelWritesThroughBlueMind() {
    when(registry.resolveServer(7L)).thenReturn(server(WriteChannel.BLUEMIND_IMPORT));

    assertSame(bluemind, writers.writer(endpoint(7L)));
  }

  /**
   * CalDAV for a registration that declares it, for one that states nothing,
   * for a registration the registry does not hold, and for an endpoint minted
   * from the legacy property with no registration at all — the door every
   * deployment used before the setting existed.
   */
  @Test
  void everythingElseWritesThroughCalDav() {
    when(registry.resolveServer(1L)).thenReturn(server(WriteChannel.CALDAV));
    when(registry.resolveServer(2L)).thenReturn(server(null));
    when(registry.resolveServer(3L)).thenReturn(null);

    assertSame(caldav, writers.writer(endpoint(1L)));
    assertSame(caldav, writers.writer(endpoint(2L)));
    assertSame(caldav, writers.writer(endpoint(3L)));
    assertSame(caldav, writers.writer(endpoint(null)));
    verify(registry, never()).resolveServer(null);
  }

  /**
   * A registry that cannot be read fails the write rather than choosing a
   * door: choosing CalDAV for a BlueMind server is the very defect the
   * channel exists to prevent, and the endpoint was minted from the same
   * registry a moment earlier.
   */
  @Test
  void aRegistryThatCannotBeReadFailsTheWriteRatherThanPickingADoor() {
    when(registry.resolveServer(anyLong())).thenThrow(new IllegalStateException("registry down"));

    assertThrows(IllegalStateException.class, () -> writers.writer(endpoint(7L)));
  }

  /**
   * @param channel the declared channel, or null for a row stating nothing
   * @return a registration
   */
  private static CaldavServer server(WriteChannel channel) {
    CaldavServer server = new CaldavServer();
    server.setId(7L);
    server.setWriteChannel(channel);
    return server;
  }

  /**
   * @param serverId the registry row, or null for the legacy property
   * @return an endpoint minted for it
   */
  private static CalDavEndpoint endpoint(Long serverId) {
    return TestEndpoints.endpoint(serverId, URI.create("https://dav.example/dav/"), "personal", "john");
  }
}
