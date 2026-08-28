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
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.Listener;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * What the two agenda-modification listeners share: unwrapping the broadcast,
 * resolving the propagation service, and refusing to run outside a container.
 *
 * <p>
 * Glue only, per the extensibility norm. Nothing here decides anything about a
 * calendar copy — which modifications matter, who holds a copy, what is written
 * and what is removed all live in
 * {@link CaldavEventPropagationService}. Two subclasses exist rather than one
 * class switching on the event name, because "the meeting changed" and "the
 * meeting is gone" are two different instructions to a calendar and reading
 * them apart is worth two files.
 *
 * <h2>Why asynchronous</h2>
 *
 * <p>
 * Same reason as {@link CalendarCreatedListener} and
 * {@link EventResponseSavedListener}: agenda raises these inside the
 * transaction that saves the edit. Talking to one calendar server there would
 * hold a database transaction open across a network round trip; talking to
 * fifty would let the slowest of fifty unrelated accounts decide whether
 * somebody can move a meeting.
 *
 * <h2>The container the asynchronous thread runs in</h2>
 *
 * <p>
 * It has one, and that is not incidental. The kernel wraps an
 * {@code @Asynchronous} listener in its own {@code RunListener}, which does
 * {@code ExoContainerContext.setCurrentContainer(container)} and
 * {@code RequestLifeCycle.begin(container)} before calling {@code onEvent}
 * (kernel, {@code ListenerService.java:241-242}). That is what makes the
 * transactional write recording what was pushed actually commit — outside a
 * container it fails as a warning nobody reads.
 *
 * <p>
 * It is checked rather than assumed — with
 * {@code getCurrentContainerIfPresent()}, which answers null rather than
 * building a root container out of a check — because the failure it guards
 * against is silent by construction, and because the guarantee holds only for as long as
 * the work stays on <i>this</i> thread. Nothing below here may start a thread
 * of its own: a fan-out onto an executor would leave the container behind and
 * reintroduce exactly the silent failure. The fan-out is therefore sequential,
 * on this thread, by design and not by omission.
 */
public abstract class AgendaEventPropagationListener extends Listener<AgendaEventModification, Object> {

  private static final Log              LOG = ExoLogger.getLogger(AgendaEventPropagationListener.class);

  private CaldavEventPropagationService caldavEventPropagationService;

  /**
   * Unwraps the broadcast and hands the modification to the subclass.
   *
   * @param event carries the modification as its source
   */
  @Override
  public void onEvent(Event<AgendaEventModification, Object> event) {
    AgendaEventModification modification = event == null ? null : event.getSource();
    if (modification == null || modification.getEventId() <= 0) {
      return;
    }
    if (ExoContainerContext.getCurrentContainerIfPresent() == null) {
      // Said out loud rather than discovered later. Without a container the
      // persistence writes that record what was pushed are rolled back as a
      // warning, and the copies would look carried out while nothing was.
      LOG.warn("Event {} changed but this listener is running with no portal container;"
          + " its copies are left stale and nothing records that they are", modification.getEventId());
      return;
    }
    CaldavEventPropagationService propagationService = getCaldavEventPropagationService();
    if (propagationService == null) {
      return;
    }
    try {
      propagate(propagationService, modification);
    } catch (Exception | LinkageError e) {
      // The edit is recorded in eXo and that must stand whatever any calendar
      // server says. What is owed to each copy has already been written down by
      // then, so the sweep settles what this attempt did not (EXO-89773) — it
      // used to say the verification pass would, which it never could.
      // LinkageError as well as Exception: one escaped a
      // catch (RuntimeException) on this code path once and took a whole sweep
      // down with it.
      LOG.warn("The change to event {} could not be carried to the calendar copies of its attendees",
               modification.getEventId(),
               e);
    }
  }

  /**
   * What this listener asks of the propagation service.
   *
   * @param propagationService the resolved service
   * @param modification the modification agenda broadcast
   */
  protected abstract void propagate(CaldavEventPropagationService propagationService, AgendaEventModification modification);

  /**
   * Resolves the propagation service lazily through the kernel/Spring bridge.
   *
   * <p>
   * {@code LinkageError} as well as {@code Exception}: resolving a bean through
   * the bridge loads a class graph, and a container assembled without part of it
   * fails with an error rather than an exception. Nothing here is worth breaking
   * an edit over.
   *
   * @return the service, or null when the bridge cannot provide it
   */
  private CaldavEventPropagationService getCaldavEventPropagationService() {
    if (caldavEventPropagationService == null) {
      try {
        caldavEventPropagationService = ExoContainerContext.getService(CaldavEventPropagationService.class);
      } catch (Exception | LinkageError e) {
        LOG.debug("CalDAV propagation service not resolvable; the copies stay as they are and nothing records that they should not",
                  e);
      }
    }
    return caldavEventPropagationService;
  }

  /**
   * Hands the service to tests, which have no container to resolve it from.
   *
   * @param caldavEventPropagationService the service to use
   */
  protected void setCaldavEventPropagationService(CaldavEventPropagationService caldavEventPropagationService) {
    this.caldavEventPropagationService = caldavEventPropagationService;
  }
}
