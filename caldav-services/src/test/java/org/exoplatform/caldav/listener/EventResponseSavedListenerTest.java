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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.model.EventAttendee;
import org.exoplatform.caldav.service.CaldavEventPropagationService;
import org.exoplatform.caldav.service.CaldavPushService;
import org.exoplatform.services.listener.Event;

/**
 * The glue between an answer being recorded in eXo and it reaching the copy on
 * the user's calendar server.
 */
@ExtendWith(MockitoExtension.class)
public class EventResponseSavedListenerTest {

  private static final long          USER  = 42L;

  private static final long          EVENT = 964L;

  @Mock
  private CaldavPushService             caldavPushService;

  @Mock
  private CaldavEventPropagationService caldavEventPropagationService;

  private EventResponseSavedListener    listener;

  /**
   * A listener holding the service, since there is no container to resolve it.
   */
  @BeforeEach
  public void wireTheService() {
    listener = new EventResponseSavedListener();
    listener.setCaldavPushService(caldavPushService);
    listener.setCaldavEventPropagationService(caldavEventPropagationService);
  }

  /**
   * The regression, seen from the glue: an answer recorded in eXo — here the
   * Accept link of a notification mail, which writes agenda directly and
   * nothing else — is handed to the service that carries it outward. Before
   * this listener existed, nothing was.
   */
  @Test
  public void anAnswerRecordedInExoIsHandedOnToBeCarriedOutward() {
    listener.onEvent(answer(EventAttendeeResponse.DECLINED, EventAttendeeResponse.ACCEPTED));

    verify(caldavPushService).pushAnswer(USER, EVENT, "ACCEPTED");
  }

  /**
   * An attendee answering for the first time has no previous answer, and that
   * is a change like any other.
   */
  @Test
  public void aFirstAnswerIsCarriedOutwardToo() {
    listener.onEvent(new Event<>("exo.agenda.event.responseSaved", null, attendee(EventAttendeeResponse.TENTATIVE)));

    verify(caldavPushService).pushAnswer(USER, EVENT, "TENTATIVE");
  }

  /**
   * Agenda broadcasts a save, not a change. Re-saving the same answer — a
   * second click, a reset onto an attendee already at NEEDS-ACTION — is not
   * worth a fetch of the copy and a write that would move its ETag for nothing.
   */
  @Test
  public void anAnswerThatSaysWhatItAlreadySaidIsNotCarriedOutward() {
    listener.onEvent(answer(EventAttendeeResponse.ACCEPTED, EventAttendeeResponse.ACCEPTED));

    verify(caldavPushService, never()).pushAnswer(anyLong(), anyLong(), anyString());
  }

  /**
   * An event carrying nothing usable is ignored rather than guessed at.
   */
  @Test
  public void anEventCarryingNoAnswerIsIgnored() {
    assertDoesNotThrow(() -> listener.onEvent(null));
    assertDoesNotThrow(() -> listener.onEvent(new Event<>("exo.agenda.event.responseSaved", null, null)));
    assertDoesNotThrow(() -> listener.onEvent(new Event<>("exo.agenda.event.responseSaved",
                                                          null,
                                                          new EventAttendee(1L, EVENT, USER, null))));

    verify(caldavPushService, never()).pushAnswer(anyLong(), anyLong(), anyString());
  }

  /**
   * An unreachable calendar server never costs the user the answer they gave.
   * The answer is recorded in eXo and must stand; carrying it outward is a
   * convenience the verification pass retries.
   */
  @Test
  public void aFailingPushDoesNotUndoTheAnswer() {
    when(caldavPushService.pushAnswer(USER, EVENT, "ACCEPTED")).thenThrow(new IllegalStateException("down"));

    assertDoesNotThrow(() -> listener.onEvent(answer(EventAttendeeResponse.DECLINED, EventAttendeeResponse.ACCEPTED)));
  }

  /**
   * The other half of the same answer (EXO-89868): it is handed on to reach
   * every <b>other</b> attendee's copy as well.
   *
   * <p>
   * Without this call, root created a meeting, Benjamin accepted it in macOS
   * Calendar, and root's BlueMind copy went on saying NEEDS-ACTION for ever —
   * because the only write an answer caused went to the answerer's own
   * account.
   */
  @Test
  public void anAnswerIsAlsoHandedOnToReachTheOtherAttendeesCopies() {
    listener.onEvent(answer(EventAttendeeResponse.DECLINED, EventAttendeeResponse.ACCEPTED));

    verify(caldavEventPropagationService).propagateAnswer(EVENT, USER, "ACCEPTED");
  }

  /**
   * The answerer's own account being unreachable is not the reason nobody
   * else's copy learns anything.
   *
   * <p>
   * The two halves fail for entirely unrelated reasons — one account's server
   * against fifty others' — so a single guard around the pair would have made
   * the answerer's own server outage into the very defect EXO-89868 fixes,
   * in miniature.
   */
  @Test
  public void aFailureOnTheAnswerersOwnCopyDoesNotStopTheFanOut() {
    when(caldavPushService.pushAnswer(USER, EVENT, "ACCEPTED")).thenThrow(new IllegalStateException("down"));

    assertDoesNotThrow(() -> listener.onEvent(answer(EventAttendeeResponse.DECLINED, EventAttendeeResponse.ACCEPTED)));

    verify(caldavEventPropagationService).propagateAnswer(EVENT, USER, "ACCEPTED");
  }

  /**
   * And symmetrically: the fan-out failing does not stop the answerer's own
   * copy from being written, nor cost them the answer they gave.
   */
  @Test
  public void aFailingFanOutDoesNotUndoTheAnswerOrStopTheirOwnCopy() {
    when(caldavEventPropagationService.propagateAnswer(EVENT, USER, "ACCEPTED"))
                                                                                .thenThrow(new IllegalStateException("everybody's servers are down"));

    assertDoesNotThrow(() -> listener.onEvent(answer(EventAttendeeResponse.DECLINED, EventAttendeeResponse.ACCEPTED)));

    verify(caldavPushService).pushAnswer(USER, EVENT, "ACCEPTED");
  }

  /**
   * A save that changes nothing costs neither half a round trip. The fan-out
   * declines resets on its own account too, but the cheapest place to decline
   * a re-save is before either service is called.
   */
  @Test
  public void anAnswerThatSaysWhatItAlreadySaidReachesNeitherHalf() {
    listener.onEvent(answer(EventAttendeeResponse.ACCEPTED, EventAttendeeResponse.ACCEPTED));

    verify(caldavEventPropagationService, never()).propagateAnswer(anyLong(), anyLong(), anyString());
  }

  /**
   * @param before the answer as it stood, null when there was none
   * @param now the answer as it now stands
   * @return the event agenda broadcasts
   */
  private Event<EventAttendee, EventAttendee> answer(EventAttendeeResponse before, EventAttendeeResponse now) {
    return new Event<>("exo.agenda.event.responseSaved", attendee(before), attendee(now));
  }

  /**
   * @param response the answer the attendee carries
   * @return an attendee row as agenda holds it
   */
  private EventAttendee attendee(EventAttendeeResponse response) {
    return new EventAttendee(1L, EVENT, USER, response);
  }
}
