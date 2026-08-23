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

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.caldav.service.CaldavSyncService;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.listener.Asynchronous;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.Listener;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Pushes a calendar to the user's CalDAV server as soon as they create it.
 *
 * <p>
 * Without this the only trigger is the throttled on-access sync, so a calendar
 * created in eXo could sit here for a quarter of an hour before reaching the
 * server the user reads on their phone. Correct behaviour, and a poor one to
 * live with.
 *
 * <p>
 * Asynchronous on purpose. The event is raised inside the transaction that
 * creates the calendar, and talking to a calendar server there would hold a
 * database transaction open across the network — and would make a slow or
 * unreachable server able to fail creating a calendar that has nothing to do
 * with it.
 *
 * <p>
 * Glue only, per the extensibility norm: it resolves who the calendar belongs
 * to and hands the work to the sync engine.
 */
@Asynchronous
public class CalendarCreatedListener extends Listener<Calendar, Object> {

  private static final Log  LOG = ExoLogger.getLogger(CalendarCreatedListener.class);

  private CaldavSyncService caldavSyncService;

  /**
   * Reacts to a calendar having been created.
   *
   * @param event carries the created calendar as its source
   */
  @Override
  public void onEvent(Event<Calendar, Object> event) {
    Calendar calendar = event == null ? null : event.getSource();
    if (calendar == null || calendar.getOwnerId() <= 0) {
      return;
    }
    CaldavSyncService syncService = getCaldavSyncService();
    if (syncService == null) {
      return;
    }
    try {
      syncService.syncAfterCalendarCreated(calendar.getOwnerId());
    } catch (Exception e) {
      // A calendar has been created and that must stand whatever the server
      // says. Pushing it is a convenience the next sync will retry.
      LOG.warn("Calendar {} could not be pushed straight away; the next sync will carry it", calendar.getId(), e);
    }
  }

  /**
   * Resolves the sync engine lazily through the kernel/Spring bridge.
   *
   * <p>
   * LinkageError as well as Exception: resolving a bean through the bridge
   * loads a class graph, and a container assembled without part of it fails
   * with an error rather than an exception. Nothing here is worth breaking a
   * calendar creation over.
   *
   * @return the engine, or null when the bridge cannot provide it
   */
  private CaldavSyncService getCaldavSyncService() {
    if (caldavSyncService == null) {
      try {
        caldavSyncService = ExoContainerContext.getService(CaldavSyncService.class);
      } catch (Exception | LinkageError e) {
        LOG.debug("CalDAV sync engine not resolvable; the calendar will be pushed by the next sync", e);
      }
    }
    return caldavSyncService;
  }

  /**
   * Hands the engine to tests, which have no container to resolve it from.
   *
   * @param caldavSyncService the engine to use
   */
  protected void setCaldavSyncService(CaldavSyncService caldavSyncService) {
    this.caldavSyncService = caldavSyncService;
  }
}
