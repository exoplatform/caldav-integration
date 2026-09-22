/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.caldav.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.model.CaldavManagedMode;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.storage.CaldavServerStorage;
import org.exoplatform.services.connector.credentials.managed.ManagedConnectorService;

/**
 * The decision lives in commons-exo; what this class owes it is the CalDAV
 * knowledge it lacks, and what these tests pin is exactly that seam.
 *
 * <p>
 * Pinned: the kind every call carries is {@code caldav}; the per-viewer
 * verdict is asked of commons-exo per user, never derived from the global
 * designation; a row that is unknown or deactivated is refused here before
 * commons-exo is asked anything; the provider handed to commons-exo is the one
 * the row is configured with; exclusions are written before the designation;
 * off clears both; and the registry guard still refuses the writes that would
 * strand the mode.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavManagedModeServiceTest {

  private static final String      KIND = "caldav";

  private static final String      USER = "mary";

  private static final String      ADMIN = "root";

  @Mock
  private ManagedConnectorService  managedConnectorService;

  @Mock
  private CaldavServerStorage      caldavServerStorage;

  @InjectMocks
  private CaldavManagedModeService caldavManagedModeService;

  @BeforeEach
  public void nothingIsDesignatedByDefault() {
    lenient().when(managedConnectorService.designationOf(KIND)).thenReturn(null);
    lenient().when(managedConnectorService.exclusionsOf(KIND)).thenReturn(List.of());
    lenient().when(managedConnectorService.designatedConnectorFor(eq(KIND), anyString())).thenReturn(null);
  }

  /**
   * A registration configured with a provider.
   *
   * @param id the row's identifier
   * @param active whether users may connect to it
   * @return the registration
   */
  private CaldavServer server(long id, boolean active) {
    CaldavServer server = new CaldavServer();
    server.setId(id);
    server.setName("Bluemind");
    server.setActive(active);
    server.setAuthProviderName("bluemind-sudo");
    return server;
  }

  /**
   * Says commons-exo holds this designation for the caldav kind, applying to
   * everybody.
   *
   * @param serverId the designated registration
   */
  private void designated(long serverId) {
    lenient().when(managedConnectorService.designationOf(KIND)).thenReturn(serverId);
    lenient().when(managedConnectorService.designatedConnectorFor(eq(KIND), anyString())).thenReturn(serverId);
  }

  /** Nothing designated is the whole definition of "off", for everybody. */
  @Test
  public void noDesignationIsManagedModeOff() {
    assertNull(caldavManagedModeService.getManagedServerId());
    assertFalse(caldavManagedModeService.isManagedFor(USER));

    CaldavManagedMode mode = caldavManagedModeService.getManagedMode(USER);

    assertNull(mode.serverId());
    assertNull(mode.serverName());
    assertEquals(List.of(), mode.excludedGroups());
    assertFalse(mode.managedForMe());
  }

  /**
   * A designation is managed mode on, and the payload names the server and
   * lists the exclusions rather than making every screen fetch them.
   */
  @Test
  public void aDesignationIsManagedModeOnAndNamesTheServer() {
    designated(7);
    when(managedConnectorService.exclusionsOf(KIND)).thenReturn(List.of("/externals"));
    when(caldavServerStorage.getServerById(7)).thenReturn(server(7, true));

    CaldavManagedMode mode = caldavManagedModeService.getManagedMode(USER);

    assertEquals(7L, mode.serverId());
    assertEquals("Bluemind", mode.serverName());
    assertEquals(List.of("/externals"), mode.excludedGroups());
    assertTrue(mode.managedForMe());
  }

  /**
   * <b>The verdict is per viewer, and it is commons-exo's.</b> A designation
   * exists, and this user is excluded from it: the instance's choice is shown,
   * and it does not apply to them. A caller reading {@code serverId != null}
   * instead would hide the connect button from the very users an exclusion
   * exists to let connect.
   */
  @Test
  public void anExcludedUserSeesTheChoiceAndIsNotGovernedByIt() {
    designated(7);
    when(managedConnectorService.designatedConnectorFor(KIND, USER)).thenReturn(null);
    when(caldavServerStorage.getServerById(7)).thenReturn(server(7, true));

    assertFalse(caldavManagedModeService.isManagedFor(USER));

    CaldavManagedMode mode = caldavManagedModeService.getManagedMode(USER);

    assertEquals(7L, mode.serverId());
    assertFalse(mode.managedForMe());
  }

  /**
   * Nobody is managed on nobody's behalf: an anonymous caller has no account
   * to govern — and commons-exo, which refuses a blank user as a programming
   * error, is not even asked. Managed mode is deliberately ON here: the point
   * is that the refusal comes from having no user, not from having no
   * designation.
   */
  @Test
  public void anAnonymousCallerIsNeverManaged() {
    designated(7);

    assertFalse(caldavManagedModeService.isManagedFor(""));
    assertFalse(caldavManagedModeService.isManagedFor(null));
    verify(managedConnectorService, never()).designatedConnectorFor(anyString(), any());
  }

  /**
   * Saving hands commons-exo everything in one call - the row's provider (the
   * eligibility criterion it enforces), the exclusions and the caller - under
   * the caldav kind. One call, so that a refusal there writes nothing here.
   */
  @Test
  public void savingDesignatesTheRowWithItsProviderExclusionsAndCaller() throws Exception {
    when(caldavServerStorage.getServerById(7)).thenReturn(server(7, true));

    caldavManagedModeService.saveManagedServer(7, List.of("/externals"), ADMIN);

    verify(managedConnectorService).designate(KIND, 7, "bluemind-sudo", List.of("/externals"), ADMIN);
  }

  /** A caller commons-exo refuses is refused here, untouched. */
  @Test
  public void aNonAdministratorIsRefusedByCommons() throws Exception {
    when(caldavServerStorage.getServerById(7)).thenReturn(server(7, true));
    doThrow(new IllegalAccessException("managedConnector.administrator.required")).when(managedConnectorService)
                                                                                 .designate(eq(KIND), anyLong(), any(), any(), eq(USER));
    doThrow(new IllegalAccessException("managedConnector.administrator.required")).when(managedConnectorService)
                                                                                 .clearDesignation(KIND, USER);

    assertThrows(IllegalAccessException.class, () -> caldavManagedModeService.saveManagedServer(7, List.of(), USER));
    assertThrows(IllegalAccessException.class, () -> caldavManagedModeService.clearManagedServer(USER));
  }

  /**
   * A deactivated server is refused here, before commons-exo is asked
   * anything. It is precisely the row nobody can connect to, and only this
   * add-on can recognise it.
   */
  @Test
  public void aDeactivatedServerIsRefusedBeforeAnythingIsWritten() throws Exception {
    when(caldavServerStorage.getServerById(7)).thenReturn(server(7, false));

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> caldavManagedModeService.saveManagedServer(7, List.of(), ADMIN));

    assertEquals("caldav.managed.serverNotEligible", refusal.getMessage());
    verify(managedConnectorService, never()).designate(anyString(), anyLong(), any(), any(), anyString());
  }

  /**
   * So is a server that does not exist — a stale row id from a screen someone
   * left open while another administrator deleted it.
   */
  @Test
  public void anUnknownServerIsRefusedBeforeAnythingIsWritten() throws Exception {
    when(caldavServerStorage.getServerById(9)).thenReturn(null);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> caldavManagedModeService.saveManagedServer(9, List.of(), ADMIN));

    assertEquals("caldav.managed.serverNotEligible", refusal.getMessage());
    verify(managedConnectorService, never()).designate(anyString(), anyLong(), any(), any(), anyString());
  }

  /**
   * A provider that asks the user for something is commons-exo's refusal, and
   * it comes through untouched: the screen renders that code, not a CalDAV
   * paraphrase of it.
   */
  @Test
  public void aProviderThatAsksTheUserIsRefusedWithTheCommonsCode() throws Exception {
    when(caldavServerStorage.getServerById(7)).thenReturn(server(7, true));
    doThrow(new IllegalArgumentException("managedConnector.provider.asksTheUser")).when(managedConnectorService)
                                                                                .designate(KIND, 7, "bluemind-sudo", List.of(), ADMIN);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> caldavManagedModeService.saveManagedServer(7, List.of(), ADMIN));

    assertEquals("managedConnector.provider.asksTheUser", refusal.getMessage());
  }

  /** Off is commons-exo's clear, with the caller: designation and exclusions go together there. */
  @Test
  public void switchingOffClearsThroughCommons() throws Exception {
    caldavManagedModeService.clearManagedServer(ADMIN);

    verify(managedConnectorService).clearDesignation(KIND, ADMIN);
    verify(managedConnectorService, never()).designate(anyString(), anyLong(), any(), any(), anyString());
  }

  /**
   * The managed row may not move to a provider that asks the user: designating
   * it refused exactly that, and an edit must not be the way around. Any other
   * row changes provider freely, and commons-exo is not even asked.
   */
  @Test
  public void theManagedRowMayNotMoveToAProviderThatAsksTheUser() {
    designated(700);
    doThrow(new IllegalArgumentException("managedConnector.provider.asksTheUser")).when(managedConnectorService)
                                                                                .requireEligible("personal");

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> caldavManagedModeService.checkProviderChangeAllowed(700, "personal"));

    assertEquals("caldav.managed.providerNotEligible", refusal.getMessage());
    assertDoesNotThrow(() -> caldavManagedModeService.checkProviderChangeAllowed(700, "bluemind-sudo"));
    // Another row: commons-exo is not even asked about its provider.
    assertDoesNotThrow(() -> caldavManagedModeService.checkProviderChangeAllowed(800, "anything"));
    verify(managedConnectorService, never()).requireEligible("anything");
  }

  /**
   * The registry guard: the managed row refuses the writes that would strand
   * the mode, and every other row is untouched by it.
   */
  @Test
  public void theManagedRowRefusesRegistryWritesAndOtherRowsDoNot() {
    // Ids above the Long cache (-128..127): a boxed == would still pass at 7.
    designated(700);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> caldavManagedModeService.checkServerNotManaged(700));

    assertEquals("caldav.managed.serverInUse", refusal.getMessage());
    assertDoesNotThrow(() -> caldavManagedModeService.checkServerNotManaged(800));
  }

  /**
   * With managed mode off, no row is protected — the guard must not become a
   * rule that outlives the mode it enforces.
   */
  @Test
  public void nothingIsProtectedWhenManagedModeIsOff() {
    assertDoesNotThrow(() -> caldavManagedModeService.checkServerNotManaged(700));
  }

  /**
   * A row deleted out from under the designation leaves the name empty rather
   * than failing. The registry refuses that deletion, so this is the belt to
   * that braces — and a screen showing a blank name is recoverable, a 500 on
   * the page that holds the off switch is not.
   */
  @Test
  public void aVanishedManagedRowStillAnswers() {
    designated(7);
    when(caldavServerStorage.getServerById(7)).thenReturn(null);

    CaldavManagedMode mode = caldavManagedModeService.getManagedMode(USER);

    assertEquals(7L, mode.serverId());
    assertNull(mode.serverName());
    assertTrue(mode.managedForMe());
  }
}
