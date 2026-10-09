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

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.bluemind.BlueMindServerFlavour;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.WriteChannel;
import org.exoplatform.caldav.plugin.CalendarServerFlavour;
import org.exoplatform.caldav.plugin.PlainServerFlavour;

/**
 * The server flavours as contributions (EXO-90730): with none installed every
 * registration is plain CalDAV, with BlueMind's installed a BlueMind-named
 * registration is BlueMind's and nothing else is.
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
   * With BlueMind's flavour installed, the registry picks it for a
   * BlueMind-named registration — the seed's spelling, the preset's, any case
   * — and plain CalDAV for any other.
   */
  @Test
  public void theBlueMindFlavourIsPickedForABlueMindServerAndPlainOtherwise() {
    CalendarServerFlavourRegistry registry = CalendarServerFlavourRegistry.of(List.of(new BlueMindServerFlavour(null)));

    for (String name : List.of("Bluemind", "BlueMind", "Our BLUEMIND at Lyon")) {
      assertEquals(BlueMindServerFlavour.FLAVOUR_ID, registry.flavourOf(named(name)).id(), name);
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
   * The BlueMind flavour's sessions are the session engine's.
   */
  @Test
  public void theBlueMindFlavourDropsItsSessionsThroughTheSessionEngine() {
    BlueMindSessionService sessions = mock(BlueMindSessionService.class);
    BlueMindServerFlavour flavour = new BlueMindServerFlavour(sessions);

    flavour.forgetSession(1L, 2L);
    flavour.forgetAllSessions();

    verify(sessions).forget(1L, 2L);
    verify(sessions).forgetAll();
    assertDoesNotThrow(() -> new BlueMindServerFlavour(null).forgetSession(1L, 2L));
  }

  /**
   * The Kernel connector service reaches the registry through the
   * Kernel/Spring bridge, which exports {@code @Service} beans and nothing
   * else; and a contribution from another add-on crosses the same bridge
   * only as a {@code @Service}. Without the annotation the lookup silently
   * finds nothing.
   */
  @Test
  public void theRegistryAndTheBlueMindFlavourAreServiceBeans() {
    assertTrue(CalendarServerFlavourRegistry.class.isAnnotationPresent(Service.class));
    assertTrue(BlueMindServerFlavour.class.isAnnotationPresent(Service.class));
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
