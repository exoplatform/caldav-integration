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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.UnknownHostException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.agenda.model.RemoteProvider;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
import org.exoplatform.caldav.client.bluemind.BlueMindServerSeed;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.MirrorTargetKind;
import org.exoplatform.caldav.model.ServerQuirk;
import org.exoplatform.caldav.model.WriteChannel;
import org.exoplatform.caldav.plugin.CalendarServerSeed;
import org.exoplatform.caldav.storage.CaldavServerStorage;

/**
 * The first-install seeding over contributed seeds (EXO-90737): the host seeds
 * its own default and every contribution, one row per product, into an empty
 * registry only.
 *
 * <p>
 * The BlueMind row's own values — excusals, destination, channel, inactive
 * placeholder — are pinned by {@code CaldavServerServiceTest}, over the
 * built-in seed; this class pins what the host does with seeds as such.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavServerServiceSeedTest {

  /** The provider name the storage hands a created row back under. */
  private static final String      CREATED_PROVIDER = "agenda.caldavCalendar.2";

  @Mock
  private CaldavServerStorage      caldavServerStorage;

  @Mock
  private AgendaRemoteEventService agendaRemoteEventService;

  /**
   * The address check, real, over a resolver that knows no host: every seeded
   * placeholder is refused, which is what a fresh install meets.
   */
  @Spy
  private CaldavServerUrlValidator caldavServerUrlValidator = new CaldavServerUrlValidator("https", "80,443", "", false, host -> {
    throw new UnknownHostException(host);
  });

  @InjectMocks
  private CaldavServerService      caldavServerService;

  /** The legacy address property as found, restored afterwards. */
  private String                   previousUrlProperty;

  /**
   * Clears the legacy address property so the default's placeholder is
   * seeded.
   */
  @BeforeEach
  public void clearLegacyProperty() {
    previousUrlProperty = System.getProperty(CaldavServerService.CALDAV_SERVER_URL_PROPERTY);
    System.clearProperty(CaldavServerService.CALDAV_SERVER_URL_PROPERTY);
  }

  /**
   * Puts the legacy address property back as found.
   */
  @AfterEach
  public void restoreLegacyProperty() {
    if (previousUrlProperty == null) {
      System.clearProperty(CaldavServerService.CALDAV_SERVER_URL_PROPERTY);
    } else {
      System.setProperty(CaldavServerService.CALDAV_SERVER_URL_PROPERTY, previousUrlProperty);
    }
  }

  /**
   * Installs the given seeds, in the order the bean factory would give them.
   *
   * @param seeds the contributions
   */
  private void install(CalendarServerSeed... seeds) {
    ReflectionTestUtils.setField(caldavServerService, "calendarServerSeedRegistry", CalendarServerSeedRegistry.of(List.of(seeds)));
  }

  /**
   * Has the storage hand every created row back under a provider name, the
   * way the real storage does.
   */
  private void echoCreatedRows() {
    when(caldavServerStorage.countServers()).thenReturn(0L);
    when(caldavServerStorage.createServer(any(), eq(CaldavServerService.CALDAV_PROVIDER_NAME))).thenAnswer(invocation -> {
      CaldavServer created = invocation.getArgument(0);
      created.setProviderName(CREATED_PROVIDER);
      return created;
    });
  }

  /**
   * The rows created besides the default, in order.
   *
   * @param count how many are expected
   * @return the rows
   */
  private List<CaldavServer> createdRows(int count) {
    ArgumentCaptor<CaldavServer> rows = ArgumentCaptor.forClass(CaldavServer.class);
    verify(caldavServerStorage, times(count)).createServer(rows.capture(), eq(CaldavServerService.CALDAV_PROVIDER_NAME));
    return rows.getAllValues();
  }

  /**
   * With no seed contributed, a fresh registry receives the host's default
   * only: no BlueMind row, and a single agenda provider. The same whether the
   * seed registry is installed and empty or not installed at all.
   */
  @Test
  public void withNoContributedSeedOnlyTheDefaultIsSeeded() {
    for (boolean installed : List.of(true, false)) {
      org.mockito.Mockito.reset(caldavServerStorage, agendaRemoteEventService);
      when(caldavServerStorage.countServers()).thenReturn(0L);
      ReflectionTestUtils.setField(caldavServerService,
                                   "calendarServerSeedRegistry",
                                   installed ? CalendarServerSeedRegistry.of(List.of()) : null);

      caldavServerService.seedDefaultServers();

      ArgumentCaptor<CaldavServer> stalwart = ArgumentCaptor.forClass(CaldavServer.class);
      verify(caldavServerStorage).createSeedServer(stalwart.capture(), eq(CaldavServerService.CALDAV_PROVIDER_NAME));
      assertEquals(CaldavServerService.STALWART_SERVER_NAME, stalwart.getValue().getName());
      verify(caldavServerStorage, never()).createServer(any(), any());
      verify(agendaRemoteEventService, times(1)).saveRemoteProvider(any());
    }
  }

  /**
   * With caldav's own BlueMind seed installed, the fresh registry receives
   * the BlueMind row exactly as it did when the host named it, and its agenda
   * provider under the created row's name.
   */
  @Test
  public void theBuiltInBlueMindSeedWritesTodaysRow() {
    install(new BlueMindServerSeed());
    echoCreatedRows();

    caldavServerService.seedDefaultServers();

    CaldavServer bluemind = createdRows(1).get(0);
    assertEquals("Bluemind", bluemind.getName());
    assertEquals("https://caldav.example.invalid/dav/", bluemind.getServerUrl());
    assertFalse(bluemind.isActive());
    assertEquals("X-MICROSOFT-*,X-MOZ-*,X-ALT-DESC,PRIORITY", bluemind.getIgnoredProperties());
    assertEquals("CONFERENCE", bluemind.getDroppedProperties());
    assertNull(bluemind.getOmittedProperties());
    assertEquals(MirrorTargetKind.MAIN_CALENDAR, bluemind.getMirrorTarget());
    assertEquals(WriteChannel.BLUEMIND_IMPORT, bluemind.getWriteChannel());
    assertEquals(true, bluemind.isAnswerLinksInCopy());
    ArgumentCaptor<RemoteProvider> providers = ArgumentCaptor.forClass(RemoteProvider.class);
    verify(agendaRemoteEventService, times(2)).saveRemoteProvider(providers.capture());
    assertEquals(CREATED_PROVIDER, providers.getAllValues().get(1).getName());
    assertFalse(providers.getAllValues().get(1).isEnabled());
  }

  /**
   * The deprecated host constants still read today's values, now the seed's.
   */
  @SuppressWarnings("removal")
  @Test
  public void theDeprecatedConstantsReadTheSeedsValues() {
    assertEquals(BlueMindServerSeed.SERVER_NAME, CaldavServerService.BLUEMIND_SERVER_NAME);
    assertEquals(BlueMindServerSeed.SERVER_URL, CaldavServerService.DEFAULT_BLUEMIND_URL);
    assertEquals(BlueMindServerSeed.QUIRKS, CaldavServerService.BLUEMIND_SEED_QUIRKS);
  }

  /**
   * With both BlueMind seeds installed — the add-on's first, as its order puts
   * it — the fresh registry receives exactly one BlueMind row, the add-on's.
   */
  @Test
  public void withTwoSeedsForOneProductOnlyTheFirstIsSeeded() {
    install(new StandIn("bluemind", "BlueMind (add-on)", "https://add-on.example.invalid/dav/"), new BlueMindServerSeed());
    echoCreatedRows();

    caldavServerService.seedDefaultServers();

    List<CaldavServer> rows = createdRows(1);
    assertEquals("BlueMind (add-on)", rows.get(0).getName());
    assertEquals("https://add-on.example.invalid/dav/", rows.get(0).getServerUrl());
  }

  /**
   * A registry that already holds a row is left exactly as it is, whatever is
   * installed: nothing is created, nothing is updated, no provider is written
   * — a contribution installed after the first boot adds no row.
   */
  @Test
  public void aRegistryHoldingARowIsUntouched() {
    install(new BlueMindServerSeed(), new StandIn("other", "Other", "https://other.example.invalid/dav/"));
    when(caldavServerStorage.countServers()).thenReturn(1L);

    caldavServerService.seedDefaultServers();

    verify(caldavServerStorage).countServers();
    verifyNoMoreInteractions(caldavServerStorage);
    verifyNoInteractions(agendaRemoteEventService);
  }

  /**
   * A seed never writes a second row of a name already seeded in the pass —
   * the host's default's, or an earlier seed's under another identifier.
   */
  @Test
  public void aSeedWhoseNameIsTakenIsLeftOut() {
    install(new StandIn("fake-stalwart", "stalwart", "https://s.example.invalid/dav/"),
            new StandIn("first", "Acme", "https://a.example.invalid/dav/"),
            new StandIn("second", "ACME", "https://b.example.invalid/dav/"));
    echoCreatedRows();

    caldavServerService.seedDefaultServers();

    List<CaldavServer> rows = createdRows(1);
    assertEquals("Acme", rows.get(0).getName());
  }

  /**
   * A seed that fails while it is written does not cost the next one its row,
   * nor the default its row.
   */
  @Test
  public void aFailingSeedDoesNotStopTheNext() {
    StandIn failing = new StandIn("failing", "Failing", "https://f.example.invalid/dav/") {
      @Override
      public List<ServerQuirk> quirks() {
        throw new IllegalStateException("broken contribution");
      }
    };
    install(failing, new StandIn("next", "Next", "https://n.example.invalid/dav/"));
    echoCreatedRows();

    caldavServerService.seedDefaultServers();

    verify(caldavServerStorage).createSeedServer(any(), eq(CaldavServerService.CALDAV_PROVIDER_NAME));
    assertEquals("Next", createdRows(1).get(0).getName());
  }

  /**
   * An entry that changes what eXo writes is not a tolerance, so a seed that
   * names one gets its tolerance entries and not that one — logged, never
   * written into a tolerance column.
   */
  @Test
  public void anOmitEntryIsNotWrittenAsATolerance() {
    StandIn seed = new StandIn("omitting", "Omitting", "https://o.example.invalid/dav/") {
      @Override
      public List<ServerQuirk> quirks() {
        return List.of(ServerQuirk.OMITS_SOLO_ORGANIZER, ServerQuirk.DROPS_CONFERENCE);
      }
    };
    install(seed);
    echoCreatedRows();

    caldavServerService.seedDefaultServers();

    CaldavServer row = createdRows(1).get(0);
    assertEquals("CONFERENCE", row.getDroppedProperties());
    assertEquals("", row.getIgnoredProperties());
    assertNull(row.getOmittedProperties());
    // The defaults of the contract: plain CalDAV into a dedicated calendar.
    assertEquals(MirrorTargetKind.DEDICATED_CALENDAR, row.getMirrorTarget());
    assertEquals(WriteChannel.CALDAV, row.getWriteChannel());
  }

  /**
   * A seed as a test states it, with the contract's defaults for everything
   * but its identity.
   */
  private static class StandIn implements CalendarServerSeed {

    /** The identifier. */
    private final String id;

    /** The row's name. */
    private final String name;

    /** The row's address. */
    private final String serverUrl;

    /**
     * A seed of the given identity.
     *
     * @param id the identifier
     * @param name the row's name
     * @param serverUrl the row's address
     */
    StandIn(String id, String name, String serverUrl) {
      this.id = id;
      this.name = name;
      this.serverUrl = serverUrl;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String id() {
      return id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
      return name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String serverUrl() {
      return serverUrl;
    }
  }
}
