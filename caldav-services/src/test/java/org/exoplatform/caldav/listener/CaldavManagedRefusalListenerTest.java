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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.caldav.event.CaldavServerAuthenticationChangedEvent;
import org.exoplatform.caldav.service.CaldavRelayService;

@ExtendWith(MockitoExtension.class)
public class CaldavManagedRefusalListenerTest {

  @Mock
  private CaldavRelayService           caldavRelayService;

  @InjectMocks
  private CaldavManagedRefusalListener listener;

  /** EXO-91017. A change of a server's authentication forgets the refusals recorded on it. */
  @Test
  public void aChangedAuthenticationForgetsTheRefusalsOfTheServer() {
    listener.handleAuthenticationChanged(new CaldavServerAuthenticationChangedEvent(7L));

    verify(caldavRelayService).forgetManagedRefusalsOn(7L);
  }

  /**
   * Spring calls the handler through its annotation, after the commit and without a
   * transaction too: the annotation-removed mutant, which no direct call can kill.
   *
   * @throws NoSuchMethodException never, the handler exists
   */
  @Test
  public void theHandlerRunsAfterTheCommitOrWithoutTransaction() throws NoSuchMethodException {
    TransactionalEventListener annotation =
                                          CaldavManagedRefusalListener.class.getMethod("handleAuthenticationChanged",
                                                                                       CaldavServerAuthenticationChangedEvent.class)
                                                                            .getAnnotation(TransactionalEventListener.class);

    assertNotNull(annotation);
    assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
    assertTrue(annotation.fallbackExecution());
  }
}
