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
 * Carries an edit made in eXo out to every calendar copy of that meeting that
 * already exists.
 *
 * <p>
 * Bound to {@code exo.agenda.event.updated}, which is the only thing agenda
 * broadcasts when an existing meeting changes. It covers more than it looks
 * like: an ordinary edit in the event drawer, a field-level edit
 * ({@code updateEventFields} — how a meeting is <b>cancelled</b>, by setting its
 * status), and a date poll being confirmed into a real meeting. All three end
 * at the same place, because all three mean the same thing to a calendar: this
 * meeting is not what your copy of it says.
 *
 * <p>
 * A cancellation needs no listener of its own for the same reason. eXo keeps the
 * event and marks it CANCELLED; the copy is rewritten carrying
 * {@code STATUS:CANCELLED}, so the attendee sees the meeting struck through
 * instead of watching it vanish.
 *
 * <p>
 * Glue only, per the extensibility norm: it hands over the event and what agenda
 * says moved, and every decision is taken in
 * {@link CaldavEventPropagationService}.
 */
@Asynchronous
public class EventUpdatedListener extends AgendaEventPropagationListener {

  /**
   * Asks for every existing copy of the edited meeting to be rewritten.
   *
   * @param propagationService the resolved service
   * @param modification the modification agenda broadcast
   */
  @Override
  protected void propagate(CaldavEventPropagationService propagationService, AgendaEventModification modification) {
    propagationService.propagateUpdate(modification.getEventId(), modification.getModificationTypes());
  }
}
