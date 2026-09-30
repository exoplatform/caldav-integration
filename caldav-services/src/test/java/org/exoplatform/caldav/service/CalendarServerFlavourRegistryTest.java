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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.WriteChannel;
import org.exoplatform.caldav.plugin.CalendarServerFlavour;
import org.exoplatform.caldav.plugin.PlainServerFlavour;
import org.exoplatform.caldav.plugin.TestServerFlavour;

/**
 * The server flavours as contributions (EXO-90730): with none installed every
 * registration is plain CalDAV, with one installed the registrations it
 * recognises are its own and nothing else is.
 */
public class CalendarServerFlavourRegistryTest {

  /**
   * The null object: recognises nothing, allows no channel besides CalDAV,
   * keeps no session.
   */
  @Test
  public void thePlainFlavourRecognisesNothingAndAllowsOnlyCalDav() {
    PlainServerFlavour plain = PlainServerFlavour.INSTANCE;

    assertEquals(PlainServerFlavour.FLAVOUR_ID, plain.id());
    assertFalse(plain.recognises(named("Bluemind")));
    assertTrue(plain.writeChannels().isEmpty());
    assertDoesNotThrow(() -> plain.forgetSession(1L, 2L));
    assertDoesNotThrow(plain::forgetAllSessions);
  }

  /**
   * With no flavour installed, a BlueMind-named registration is plain CalDAV:
   * the import channel is neither accepted nor known.
   */
  @Test
  public void withNoFlavourEveryRegistrationIsPlainCalDav() {
    CalendarServerFlavourRegistry registry = CalendarServerFlavourRegistry.of(null);

    assertSame(PlainServerFlavour.INSTANCE, registry.flavourOf(named("Bluemind")));
    assertSame(PlainServerFlavour.INSTANCE, registry.flavourOf(null));
    assertTrue(registry.accepts(named("Bluemind"), WriteChannel.CALDAV));
    assertTrue(registry.accepts(named("Bluemind"), null));
    assertFalse(registry.accepts(named("Bluemind"), WriteChannel.BLUEMIND_IMPORT));
    assertFalse(registry.knows(WriteChannel.BLUEMIND_IMPORT));
    assertTrue(registry.knows(WriteChannel.CALDAV));
    assertDoesNotThrow(() -> registry.forgetSession(1L, 2L));
    assertDoesNotThrow(registry::forgetAllSessions);
  }

  /**
   * With a flavour installed, the registry picks it for a registration it
   * recognises, and plain CalDAV for any other; the channel it allows is
   * accepted on its registrations only, and known.
   */
  @Test
  public void anInstalledFlavourIsPickedForTheRegistrationsItRecognisesAndPlainOtherwise() {
    CalendarServerFlavour flavour = new TestServerFlavour("acme", "acme", Set.of(WriteChannel.BLUEMIND_IMPORT));
    CalendarServerFlavourRegistry registry = CalendarServerFlavourRegistry.of(List.of(flavour));

    for (String name : List.of("Acme", "Our ACME at Lyon")) {
      assertSame(flavour, registry.flavourOf(named(name)), name);
      assertTrue(registry.accepts(named(name), WriteChannel.BLUEMIND_IMPORT), name);
    }
    assertSame(PlainServerFlavour.INSTANCE, registry.flavourOf(named("Stalwart")));
    assertFalse(registry.accepts(named("Stalwart"), WriteChannel.BLUEMIND_IMPORT));
    assertTrue(registry.knows(WriteChannel.BLUEMIND_IMPORT));
  }

  /**
   * Session drops reach every flavour, and one that throws neither stops the
   * next nor fails the caller.
   */
  @Test
  public void sessionDropsReachEveryFlavourWhateverOneThrows() {
    CalendarServerFlavour failing = mock(CalendarServerFlavour.class);
    CalendarServerFlavour next = mock(CalendarServerFlavour.class);
    doThrow(new IllegalStateException("down")).when(failing).forgetSession(1L, 2L);
    doThrow(new IllegalStateException("down")).when(failing).forgetAllSessions();
    CalendarServerFlavourRegistry registry = CalendarServerFlavourRegistry.of(List.of(failing, next));

    assertDoesNotThrow(() -> registry.forgetSession(1L, 2L));
    assertDoesNotThrow(registry::forgetAllSessions);

    verify(next).forgetSession(1L, 2L);
    verify(next).forgetAllSessions();
  }

  /**
   * The Kernel connector service reaches the registry through the
   * Kernel/Spring bridge, which exports {@code @Service} beans and nothing
   * else. Without the annotation the lookup silently finds nothing.
   */
  @Test
  public void theRegistryIsAServiceBean() {
    assertTrue(CalendarServerFlavourRegistry.class.isAnnotationPresent(Service.class));
  }

  /**
   * A registration of a given name.
   *
   * @param name the name
   * @return the registration
   */
  private static CaldavServer named(String name) {
    CaldavServer server = new CaldavServer();
    server.setName(name);
    return server;
  }
}
