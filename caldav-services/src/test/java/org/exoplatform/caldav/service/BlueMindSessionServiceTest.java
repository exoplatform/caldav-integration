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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.client.bluemind.BlueMindRestSession;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The seam between the events that change what an account is — connect,
 * reconnect, disconnect, a registration written — and the BlueMind sessions
 * kept under the eXo login (EXO-90397).
 *
 * <p>
 * What is pinned is the translation: the events know a social identity, the
 * sessions are keyed by the login a credentials provider resolves its target
 * from, and a user the registry cannot name must not turn a disconnection
 * into a failure.
 */
@ExtendWith(MockitoExtension.class)
public class BlueMindSessionServiceTest {

  private static final long        USER = 42L;

  @Mock
  private BlueMindRestSession      blueMindRestSession;

  @Mock
  private IdentityManager          identityManager;

  @Mock
  private Identity                 identity;

  @InjectMocks
  private BlueMindSessionService   service;

  /**
   * The identity is turned into the login the session is keyed by, and the
   * declared server is carried as it is.
   */
  @Test
  public void theAccountIsNamedByItsLoginOnItsServer() {
    when(identityManager.getIdentity("42")).thenReturn(identity);
    when(identity.getRemoteId()).thenReturn("root");

    service.forget(USER, 7L);

    verify(blueMindRestSession).forget(7L, "root");
  }

  /**
   * A connection made before the registry existed carries no server: it is
   * the legacy deployment property, which the store keys as zero — the same
   * convention the calendar-owner cache uses.
   */
  @Test
  public void anAccountOnTheLegacyPropertyIsKeyedAsServerZero() {
    when(identityManager.getIdentity("42")).thenReturn(identity);
    when(identity.getRemoteId()).thenReturn("root");

    service.forget(USER, null);

    verify(blueMindRestSession).forget(0L, "root");
  }

  /**
   * A user the registry does not know, or knows without a login, has no key
   * to drop: nothing is asked of the store, and nothing is thrown at the
   * disconnection that asked.
   */
  @Test
  public void aUserThatCannotBeNamedDropsNothingAndFailsNothing() {
    when(identityManager.getIdentity("42")).thenReturn(null);

    service.forget(USER, 7L);

    verify(blueMindRestSession, never()).forget(anyLong(), anyString());
  }

  /**
   * Dropping every session needs no identity at all: a registration was
   * written, and it is the servers that changed, not one account.
   */
  @Test
  public void droppingEverySessionAsksTheRegistryNothing() {
    service.forgetAll();

    verify(blueMindRestSession).forgetAll();
    verifyNoInteractions(identityManager);
  }
}
