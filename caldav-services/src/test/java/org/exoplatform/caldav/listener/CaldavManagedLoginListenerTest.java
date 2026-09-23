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
package org.exoplatform.caldav.listener;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.service.CaldavManagedEnrollmentService;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.security.ConversationRegistry;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

/**
 * EXO-89653. The login listener is glue: the registered state's login goes to
 * the enrolment service, and nothing goes when there is no login to enrol.
 */
@ExtendWith(MockitoExtension.class)
class CaldavManagedLoginListenerTest {

  @Mock
  private CaldavManagedEnrollmentService enrollmentService;

  @Mock
  private ConversationRegistry           registry;

  private CaldavManagedLoginListener     listener;

  @BeforeEach
  void wireTheService() {
    listener = new CaldavManagedLoginListener();
    listener.setEnrollmentService(enrollmentService);
  }

  private Event<ConversationRegistry, ConversationState> loginOf(String username) {
    ConversationState state = username == null ? null : new ConversationState(new Identity(username, List.of()));
    return new Event<>("exo.core.security.ConversationRegistry.register", registry, state);
  }

  @Test
  void handsTheLoginToTheEnrolment() {
    when(enrollmentService.scheduleEnrollment("mary")).thenReturn(true);

    listener.onEvent(loginOf("mary"));

    verify(enrollmentService).scheduleEnrollment("mary");
  }

  @Test
  void ignoresAnEventWithNoIdentity() {
    assertDoesNotThrow(() -> listener.onEvent(loginOf(null)));
    assertDoesNotThrow(() -> listener.onEvent(null));

    verifyNoInteractions(enrollmentService);
  }
}
