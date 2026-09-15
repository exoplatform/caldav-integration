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
package org.exoplatform.caldav.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.model.AgendaConnectorAccount;
import org.exoplatform.agenda.model.AgendaUserSettings;
import org.exoplatform.agenda.service.AgendaUserSettingsService;

/**
 * Whether a user has said yes to meeting copies — the one question two
 * different decisions now put about two different people (EXO-90247).
 *
 * <p>
 * The provider-name rule is the part worth its own tests: a declared server
 * gets a provider name of its own, and matching only the bare seed name once
 * read as "copies disabled" for every user connected to a server an
 * administrator had declared, silently.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavCopyConsentTest {

  /** The user being asked about. */
  private static final long USER = 42L;

  @Mock
  private AgendaUserSettingsService agendaUserSettingsService;

  @InjectMocks
  private CaldavCopyConsent         consent;

  @Test
  public void aUserConnectedToTheSeedRegistrationReceivesCopies() {
    givenConnector(CaldavPushService.CONNECTOR_NAME, true);

    assertTrue(consent.copiesEnabled(USER));
  }

  @Test
  public void aUserConnectedToADeclaredServerReceivesCopiesToo() {
    // The provider name carries the registration identifier; matching the bare
    // name alone turned every declared server into "copies off".
    givenConnector(CaldavPushService.CONNECTOR_NAME + ".7", true);

    assertTrue(consent.copiesEnabled(USER));
  }

  @Test
  public void aUserWhoTurnedCopiesOffReceivesNone() {
    givenConnector(CaldavPushService.CONNECTOR_NAME + ".7", false);

    assertFalse(consent.copiesEnabled(USER));
  }

  @Test
  public void anotherAddonsConnectorIsNotThisOne() {
    givenConnector("agenda.googleCalendar", true);

    assertFalse(consent.copiesEnabled(USER));
  }

  @Test
  public void aConnectorWhoseProviderIsNotNamedIsNotThisOne() {
    givenConnector(null, true);

    assertFalse(consent.copiesEnabled(USER));
  }

  @Test
  public void aUserWithNoSettingsAtAllReceivesNone() {
    when(agendaUserSettingsService.getAgendaUserSettings(USER)).thenReturn(null);

    assertFalse(consent.copiesEnabled(USER));
  }

  /**
   * No consent readable is not consent. A write into somebody's calendar is the
   * one thing that must not proceed on an unanswered question.
   */
  @Test
  public void settingsThatCannotBeReadAreNotConsent() {
    when(agendaUserSettingsService.getAgendaUserSettings(USER)).thenThrow(new IllegalStateException("agenda is down"));

    assertFalse(consent.copiesEnabled(USER));
  }

  /**
   * @param providerName what the connected account names its provider
   * @param pushEnabled whether the user left copies on
   */
  private void givenConnector(String providerName, boolean pushEnabled) {
    AgendaUserSettings settings = new AgendaUserSettings();
    settings.getConnectedConnectors().add(new AgendaConnectorAccount(providerName, "john@example.test", pushEnabled));
    when(agendaUserSettingsService.getAgendaUserSettings(USER)).thenReturn(settings);
  }
}
