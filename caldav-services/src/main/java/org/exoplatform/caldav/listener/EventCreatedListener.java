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

import org.exoplatform.agenda.model.AgendaEventModification;
import org.exoplatform.caldav.service.CaldavEventPropagationService;
import org.exoplatform.services.listener.Asynchronous;

/**
 * Puts a meeting into the calendars of the people invited to it, at the moment
 * it is created rather than at the next background sweep.
 *
 * <p>
 * Bound to {@code exo.agenda.event.created}, which nothing subscribed to. The
 * three listeners this add-on registered all react to a meeting that already
 * exists — it was edited, deleted, or answered — so the very first thing that
 * happens to a meeting reached nothing at all. What made it look like a
 * feature that worked is that editing it once, or answering it, fires
 * {@code updated} or {@code responseSaved} and the copy appears; anyone testing
 * by clicking around therefore saw it work.
 *
 * <h2>Why this is not covered by the seeding pass</h2>
 *
 * <p>
 * {@link org.exoplatform.caldav.service.CaldavPendingInvitationService} does
 * seed pending invitations from the background sweep, so on a healthy instance
 * most new meetings did eventually appear — minutes later, on the next sweep of
 * that account. It is a safety net, not the path: it only looks at the
 * <b>upcoming</b> window (60 days by default) and only at the first 200
 * meetings in it, so a meeting further out than that, or created in the past,
 * was never copied by anything. This listener has no window at all, because it
 * is told about exactly one meeting.
 *
 * <h2>Only {@code created}, deliberately not {@code poll.created}</h2>
 *
 * <p>
 * A date poll is broadcast under its own name and is spelled
 * {@code STATUS:TENTATIVE}; it is not a scheduled meeting, and no copy of one
 * is fanned out to the people invited to vote on it. The seeding path refuses
 * it on its own ({@code CaldavPendingInvitationService.seedOne} requires
 * {@code CONFIRMED}), but not subscribing is the honest way to say so.
 *
 * <p>
 * <b>That is a statement about the fan-out, not about the poll's author</b>,
 * and the sentence that used to stand here — "no copy of one is ever pushed" —
 * conflated the two. The creator's own browser pushes their own copy of
 * whatever they save, poll included ({@code AgendaConnector.vue} on
 * {@code agenda-event-saved}, through {@code CaldavPushRest} into
 * {@code CaldavPushService.pushAgendaEvent}, which carries no status guard),
 * and every path afterwards — the update listener, the retry pass, the mirror
 * sweep — keeps that copy in step because it is a copy like any other. So a
 * poll does reach a calendar today. Suppressing that copy entirely is tracked
 * separately, as EXO-89863; nothing here changes it.
 *
 * <h2>Why asynchronous, and why that is safe here</h2>
 *
 * <p>
 * Same reason as {@link EventUpdatedListener}: agenda broadcasts this from
 * inside the transaction that saves the event, and talking to one calendar
 * server per attendee there would hold a database transaction open across as
 * many network round trips. The kernel's {@code RunListener} establishes the
 * container on that thread, which is what the base class checks for.
 *
 * <p>
 * Being asynchronous is also what keeps a creation to <b>one</b> write per
 * attendee. Agenda auto-accepts the organiser inside this very broadcast — its
 * own {@code AgendaReplyOnSaveListener} is registered on {@code created}, runs
 * synchronously, and makes agenda emit {@code responseSaved}, which this
 * add-on also listens to. Both listeners are asynchronous and the kernel's
 * listener executor is a single thread, so they run one after the other in the
 * order they were queued, and neither ordering writes twice: the answer path
 * refuses when the meeting has no copy yet, and the seeding path refuses when
 * it already has one.
 *
 * <p>
 * The writer that is <i>not</i> on that thread is the author's own browser,
 * which pushes their copy on save. That one races, and it is why
 * {@link CaldavEventPropagationService#propagateCreation(long, long)} is told
 * who the author is and skips them — see its javadoc for what that costs.
 *
 * <p>
 * Glue only, per the extensibility norm: every decision about who receives a
 * copy and what it says is taken in {@link CaldavEventPropagationService}.
 */
@Asynchronous
public class EventCreatedListener extends AgendaEventPropagationListener {

  /**
   * Asks for the new meeting to be copied into the calendar of everybody
   * invited to it who accepts copies.
   *
   * <p>
   * The modifier is handed over as well, because a creation names its author
   * and the author's own copy is written by their browser rather than here. The
   * service is what decides that; this only carries what the broadcast said.
   *
   * @param propagationService the resolved service
   * @param modification the modification agenda broadcast
   */
  @Override
  protected void propagate(CaldavEventPropagationService propagationService, AgendaEventModification modification) {
    propagationService.propagateCreation(modification.getEventId(), modification.getModifierId());
  }
}
