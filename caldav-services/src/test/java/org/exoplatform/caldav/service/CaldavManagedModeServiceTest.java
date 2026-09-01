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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.model.CaldavManagedMode;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.storage.CaldavServerStorage;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.Identity;

/**
 * Managed mode is one key in one global setting, and everything this class has
 * to protect follows from that.
 *
 * <p>
 * What is pinned: absence means off — there is no second flag that can
 * disagree with the id; the choice cannot land on a server nobody can reach;
 * the verdict handed to a browser is asked <b>per viewer</b>, which is the
 * seam group exclusions plug into; and the two registry writes that would
 * strand the mode are refused with the code the admin screens render.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavManagedModeServiceTest {

  private static final String       USER = "mary";

  @Mock
  private SettingService            settingService;

  @Mock
  private CaldavServerStorage       caldavServerStorage;

  @Mock
  private UserACL                   userAcl;

  @Mock
  private Identity                  identity;

  @InjectMocks
  private CaldavManagedModeService  caldavManagedModeService;

  @BeforeEach
  public void nothingIsStoredByDefault() {
    lenient().when(settingService.get(any(), any(), any())).thenReturn(null);
    lenient().when(userAcl.getUserIdentity(anyString())).thenReturn(identity);
  }

  /**
   * A registration that exists and is active.
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
    return server;
  }

  /**
   * Says the setting holds this value.
   *
   * @param stored what the setting holds
   */
  private void stored(String stored) {
    doReturn(SettingValue.create(stored)).when(settingService)
                                         .get(eq(Context.GLOBAL),
                                              eq(CaldavManagedModeService.MANAGED_SCOPE),
                                              eq(CaldavManagedModeService.MANAGED_SERVER_KEY));
  }

  /**
   * Nothing stored is the whole definition of "off". There is deliberately no
   * enabled flag beside the id: a second key is a second answer, and the state
   * it makes reachable — on, with no server — cannot be rendered honestly.
   */
  @Test
  public void anAbsentKeyIsManagedModeOff() {
    assertNull(caldavManagedModeService.getManagedServerId());
    assertFalse(caldavManagedModeService.isManagedFor(identity));

    CaldavManagedMode mode = caldavManagedModeService.getManagedMode(USER);

    assertNull(mode.serverId());
    assertNull(mode.serverName());
    assertFalse(mode.managedForMe());
  }

  /**
   * A stored id is managed mode on, and the payload names the server rather
   * than making every screen turn an id into a word of its own.
   */
  @Test
  public void aStoredIdIsManagedModeOnAndNamesTheServer() {
    stored("7");
    when(caldavServerStorage.getServerById(7)).thenReturn(server(7, true));

    CaldavManagedMode mode = caldavManagedModeService.getManagedMode(USER);

    assertEquals(7L, mode.serverId());
    assertEquals("Bluemind", mode.serverName());
    assertTrue(mode.managedForMe());
  }

  /**
   * The verdict is asked of the identity, and it is the only thing a browser
   * acts on.
   *
   * <p>
   * Today it cannot differ from the global answer, which is exactly why this
   * has to be pinned now: the method is the single place group exclusions will
   * land, and a caller that read {@code serverId != null} instead would keep
   * working today and hide the connect button from the very users an exclusion
   * exists to let connect.
   */
  @Test
  public void theVerdictIsAskedPerViewer() {
    stored("7");

    assertTrue(caldavManagedModeService.isManagedFor(identity));
    assertTrue(caldavManagedModeService.isManagedFor(USER));
  }

  /**
   * Nobody is managed on nobody's behalf: an anonymous caller has no account
   * to govern, and answering true would hide affordances from a page with no
   * user behind it.
   */
  @Test
  public void anAnonymousCallerIsNeverManaged() {
    // Leniently: managed mode is deliberately ON here — the point is that the
    // refusal comes from having no user, not from having no setting — and the
    // setting is never reached, because it must not be.
    lenient().doReturn(SettingValue.create("7"))
             .when(settingService)
             .get(eq(Context.GLOBAL),
                  eq(CaldavManagedModeService.MANAGED_SCOPE),
                  eq(CaldavManagedModeService.MANAGED_SERVER_KEY));

    assertFalse(caldavManagedModeService.isManagedFor((Identity) null));
    assertFalse(caldavManagedModeService.isManagedFor(""));
    assertFalse(caldavManagedModeService.isManagedFor((String) null));
  }

  /**
   * Something that is not a number in the setting reads as off.
   *
   * <p>
   * The alternative — treating an unreadable value as "managed" — hides every
   * user's connect button while naming no server at all, which is the one
   * state this screen has no way out of.
   */
  @Test
  public void anUnreadableValueReadsAsOff() {
    stored("later");

    assertNull(caldavManagedModeService.getManagedServerId());
    assertFalse(caldavManagedModeService.isManagedFor(identity));
  }

  /**
   * An empty value is not a choice either — and nothing writes one, because
   * switching off REMOVES the key.
   */
  @Test
  public void anEmptyValueReadsAsOff() {
    stored("");

    assertNull(caldavManagedModeService.getManagedServerId());
  }

  /**
   * The choice is stored as the one key, under the global context.
   */
  @Test
  public void savingStoresTheChosenServer() {
    when(caldavServerStorage.getServerById(7)).thenReturn(server(7, true));

    caldavManagedModeService.saveManagedServer(7);

    verify(settingService).set(eq(Context.GLOBAL),
                               eq(CaldavManagedModeService.MANAGED_SCOPE),
                               eq(CaldavManagedModeService.MANAGED_SERVER_KEY),
                               any());
  }

  /**
   * A deactivated server is refused. It is precisely the row nobody can
   * connect to: pointing the whole instance at it would take every user's
   * connect affordance away in exchange for a server that answers nothing.
   */
  @Test
  public void aDeactivatedServerIsRefused() {
    when(caldavServerStorage.getServerById(7)).thenReturn(server(7, false));

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> caldavManagedModeService.saveManagedServer(7));

    assertEquals("caldav.managed.serverNotEligible", refusal.getMessage());
    verify(settingService, never()).set(any(), any(), any(), any());
  }

  /**
   * So is a server that does not exist — a stale row id from a screen someone
   * left open while another administrator deleted it.
   */
  @Test
  public void anUnknownServerIsRefused() {
    when(caldavServerStorage.getServerById(9)).thenReturn(null);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> caldavManagedModeService.saveManagedServer(9));

    assertEquals("caldav.managed.serverNotEligible", refusal.getMessage());
    verify(settingService, never()).set(any(), any(), any(), any());
  }

  /**
   * Switching off REMOVES the key rather than emptying it. Absence is the
   * whole definition of off, and a stored empty string would be a second way
   * to say the same thing — the sort that survives a refactoring of the first.
   */
  @Test
  public void switchingOffRemovesTheKey() {
    caldavManagedModeService.clearManagedServer();

    verify(settingService).remove(eq(Context.GLOBAL),
                                  eq(CaldavManagedModeService.MANAGED_SCOPE),
                                  eq(CaldavManagedModeService.MANAGED_SERVER_KEY));
    verify(settingService, never()).set(any(), any(), any(), any());
  }

  /**
   * The registry guard: the managed row refuses the writes that would strand
   * the mode, and every other row is untouched by it.
   */
  @Test
  public void theManagedRowRefusesRegistryWritesAndOtherRowsDoNot() {
    stored("7");

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> caldavManagedModeService.checkServerNotManaged(7));

    assertEquals("caldav.managed.serverInUse", refusal.getMessage());
    assertDoesNotThrow(() -> caldavManagedModeService.checkServerNotManaged(8));
  }

  /**
   * With managed mode off, no row is protected — the guard must not become a
   * rule that outlives the mode it enforces.
   */
  @Test
  public void nothingIsProtectedWhenManagedModeIsOff() {
    assertDoesNotThrow(() -> caldavManagedModeService.checkServerNotManaged(7));
  }

  /**
   * A row deleted out from under the setting leaves the name empty rather than
   * failing. The registry refuses that deletion, so this is the belt to that
   * braces — and a screen showing a blank name is recoverable, a 500 on the
   * page that holds the off switch is not.
   */
  @Test
  public void aVanishedManagedRowStillAnswers() {
    stored("7");
    when(caldavServerStorage.getServerById(7)).thenReturn(null);

    CaldavManagedMode mode = caldavManagedModeService.getManagedMode(USER);

    assertEquals(7L, mode.serverId());
    assertNull(mode.serverName());
    assertTrue(mode.managedForMe());
  }
}
