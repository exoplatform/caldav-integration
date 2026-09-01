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

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.model.EventAttendee;
import org.exoplatform.caldav.service.CaldavEventPropagationService;
import org.exoplatform.caldav.service.CaldavPushService;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.listener.Asynchronous;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.Listener;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Carries an answer recorded in eXo out to the copy on the user's calendar
 * server.
 *
 * <p>
 * Without this the two places a user can answer a meeting from never converge.
 * Answering in the calendar client reaches eXo, because the verification pass
 * reads the copy back; answering in eXo — in the agenda screen, or through the
 * Accept and Decline links in the notification mail, which write agenda
 * directly — reached nothing at all. The copy kept the older answer, showed it
 * to its own author, and handed it back as fresh the next time any client
 * rewrote that object for any reason.
 *
 * <p>
 * Bound to {@code responseSaved} rather than {@code responseSent}: the copy has
 * to track what agenda <i>holds</i>, not only what a user deliberately sent.
 * The reset to NEEDS-ACTION that follows a material edit of a meeting, and the
 * propagation of an answer onto a series' exceptional occurrences, are both
 * saved-without-being-sent — and a copy that missed them would tell the user
 * they had accepted a meeting whose invitation had been reissued.
 *
 * <p>
 * Asynchronous on purpose, for the same reason as
 * {@link CalendarCreatedListener}: the event is raised inside the transaction
 * that records the answer, and talking to a calendar server there would hold a
 * database transaction open across the network — and would let a slow or
 * unreachable server fail an answer that has nothing to do with it.
 *
 * <h2>Two halves of one answer (EXO-89868)</h2>
 *
 * <p>
 * An answer has to reach two different sets of copies, and until EXO-89868
 * only the first existed. {@code pushAnswer} writes the <b>answerer's own</b>
 * copy, off their own account.
 * {@link CaldavEventPropagationService#propagateAnswer(long, long, String)}
 * writes <b>everybody else's</b>, off theirs. Without the second, root created
 * a meeting, Benjamin accepted it in macOS Calendar, eXo recorded it correctly
 * — and root's BlueMind copy never learned, so the organiser read a stale RSVP
 * in the client they actually live in.
 *
 * <p>
 * Both are attempted, each inside its own guard, and neither is allowed to
 * stop the other. They fail for entirely unrelated reasons — one account's
 * server against fifty others' — and a single {@code try} around the pair
 * would have made the answerer having no connected account the reason nobody
 * else's copy learned anything. Which is, in miniature, the defect this fixes.
 *
 * <p>
 * Glue only, per the extensibility norm: it reads who answered what and hands
 * the work to the services.
 */
@Asynchronous
public class EventResponseSavedListener extends Listener<EventAttendee, EventAttendee> {

  private static final Log              LOG = ExoLogger.getLogger(EventResponseSavedListener.class);

  private CaldavPushService             caldavPushService;

  private CaldavEventPropagationService caldavEventPropagationService;

  /**
   * Reacts to an attendee's answer having been recorded.
   *
   * @param event carries the answer as it was before as its source, and as it
   *          now stands as its data
   */
  @Override
  public void onEvent(Event<EventAttendee, EventAttendee> event) {
    EventAttendee answer = event == null ? null : event.getData();
    if (answer == null || answer.getEventId() <= 0 || answer.getIdentityId() <= 0 || answer.getResponse() == null) {
      return;
    }
    if (isUnchanged(event.getSource(), answer)) {
      // Agenda broadcasts a save, not a change, and re-saving the same answer
      // is ordinary — a second click, a reset onto an attendee who was already
      // at NEEDS-ACTION. Nothing to carry outward, and a fetch of the copy is
      // not free.
      LOG.debug("Answer of user {} to event {} is unchanged; nothing to carry to their calendar server",
                answer.getIdentityId(),
                answer.getEventId());
      return;
    }
    carryToTheirOwnCopy(answer);
    carryToTheOtherAttendeesCopies(answer);
  }

  /**
   * Writes the answer onto the answerer's own copy.
   *
   * <p>
   * Unchanged by EXO-89868 on purpose. It was never wrong — it was half a
   * feature — and the fan-out is the other half rather than a replacement:
   * this one goes through the answerer's own account, which is the only place
   * their own copy lives.
   *
   * @param answer the answer as it now stands
   */
  private void carryToTheirOwnCopy(EventAttendee answer) {
    CaldavPushService pushService = getCaldavPushService();
    if (pushService == null) {
      return;
    }
    try {
      pushService.pushAnswer(answer.getIdentityId(), answer.getEventId(), answer.getResponse().name());
    } catch (Exception | LinkageError e) {
      // The answer is recorded in eXo and that must stand whatever the
      // calendar server says. Carrying it outward is a convenience the
      // verification pass retries.
      LOG.warn("The answer of user {} to event {} could not be carried to their calendar server",
               answer.getIdentityId(),
               answer.getEventId(),
               e);
    }
  }

  /**
   * Writes the answer onto every other attendee's copy.
   *
   * <p>
   * Its own method and its own guard, so that the answerer's account being
   * unreachable — or not existing at all — is not the reason the organiser's
   * copy goes on showing a stale RSVP. The fan-out is deliberately not
   * conditioned on the answerer having a connected account: the holders'
   * copies have to learn regardless, and whose account can be written to is a
   * question about each holder, one at a time, which the service asks there.
   *
   * @param answer the answer as it now stands
   */
  private void carryToTheOtherAttendeesCopies(EventAttendee answer) {
    CaldavEventPropagationService propagationService = getCaldavEventPropagationService();
    if (propagationService == null) {
      return;
    }
    try {
      propagationService.propagateAnswer(answer.getEventId(), answer.getIdentityId(), answer.getResponse().name());
    } catch (Exception | LinkageError e) {
      LOG.warn("The answer of user {} to event {} could not be carried to the other attendees' calendar copies",
               answer.getIdentityId(),
               answer.getEventId(),
               e);
    }
  }

  /**
   * Whether the recorded answer says what it already said.
   *
   * @param previous the answer as it was, null when the attendee had none
   * @param current the answer as it now stands
   * @return true when nothing about the answer moved
   */
  private boolean isUnchanged(EventAttendee previous, EventAttendee current) {
    if (previous == null) {
      return false;
    }
    EventAttendeeResponse before = previous.getResponse();
    return before != null && before == current.getResponse();
  }

  /**
   * Resolves the push service lazily through the kernel/Spring bridge.
   *
   * <p>
   * LinkageError as well as Exception: resolving a bean through the bridge
   * loads a class graph, and a container assembled without part of it fails
   * with an error rather than an exception. Nothing here is worth breaking an
   * answer over.
   *
   * @return the service, or null when the bridge cannot provide it
   */
  private CaldavPushService getCaldavPushService() {
    if (caldavPushService == null) {
      try {
        caldavPushService = ExoContainerContext.getService(CaldavPushService.class);
      } catch (Exception | LinkageError e) {
        LOG.debug("CalDAV push service not resolvable; the answer stays in eXo until the next verification pass", e);
      }
    }
    return caldavPushService;
  }

  /**
   * Resolves the propagation service lazily through the kernel/Spring bridge.
   *
   * <p>
   * {@code LinkageError} as well as {@code Exception}, for the reason its
   * sibling gives: resolving a bean through the bridge loads a class graph,
   * and a container assembled without part of it fails with an error rather
   * than an exception.
   *
   * @return the service, or null when the bridge cannot provide it
   */
  private CaldavEventPropagationService getCaldavEventPropagationService() {
    if (caldavEventPropagationService == null) {
      try {
        caldavEventPropagationService = ExoContainerContext.getService(CaldavEventPropagationService.class);
      } catch (Exception | LinkageError e) {
        LOG.debug("CalDAV propagation service not resolvable; the answer reaches no other attendee's copy this round", e);
      }
    }
    return caldavEventPropagationService;
  }

  /**
   * Hands the service to tests, which have no container to resolve it from.
   *
   * @param caldavPushService the service to use
   */
  protected void setCaldavPushService(CaldavPushService caldavPushService) {
    this.caldavPushService = caldavPushService;
  }

  /**
   * Hands the propagation service to tests, which have no container to resolve
   * it from.
   *
   * @param caldavEventPropagationService the service to use
   */
  protected void setCaldavEventPropagationService(CaldavEventPropagationService caldavEventPropagationService) {
    this.caldavEventPropagationService = caldavEventPropagationService;
  }
}
