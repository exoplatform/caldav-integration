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
 * Removes every calendar copy of a meeting eXo has destroyed.
 *
 * <p>
 * Bound to {@code exo.agenda.event.deleted}, which agenda broadcasts <b>after</b>
 * the event row is gone. That ordering is the reason this cannot be folded into
 * the update path: there is no event left to render an object from, so the copy
 * is identified from the mapping row alone and removed rather than rewritten.
 *
 * <p>
 * Removed, not tombstoned, and that is a deliberate asymmetry with a
 * cancellation — which does leave a {@code STATUS:CANCELLED} copy behind. A copy
 * shows what agenda holds; here agenda holds nothing, and a tombstone eXo can
 * never verify, repair or clear is worse than an absence the attendee was told
 * about by the cancellation notification agenda sends on the same call.
 *
 * <p>
 * Glue only, per the extensibility norm.
 */
@Asynchronous
public class EventDeletedListener extends AgendaEventPropagationListener {

  /**
   * Asks for every existing copy of the destroyed meeting to be removed.
   *
   * @param propagationService the resolved service
   * @param modification the modification agenda broadcast
   */
  @Override
  protected void propagate(CaldavEventPropagationService propagationService, AgendaEventModification modification) {
    propagationService.propagateDeletion(modification.getEventId());
  }
}
