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
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.listener.Asynchronous;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.Listener;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import org.exoplatform.caldav.service.CaldavSyncService;

/**
 * Carries an edit of a calendar — its rename, above all — to the user's
 * CalDAV server without waiting for the throttled sync.
 *
 * <p>
 * Glue only: it resolves the engine and hands the owner over, and the
 * decision of what an edit means for the collection (a display name to
 * reconcile, read back and confirmed) lives in the outbound service, where a
 * sweep makes it too. Until EXO-89528 nothing listened here at all, and a
 * calendar renamed in eXo kept its first name on the server for good.
 */
@Asynchronous
public class CalendarUpdatedListener extends Listener<Calendar, Object> {

  private static final Log  LOG = ExoLogger.getLogger(CalendarUpdatedListener.class);

  private CaldavSyncService caldavSyncService;

  /**
   * Reacts to a calendar having been edited in eXo.
   *
   * @param event the broadcast, carrying the calendar as agenda now holds it
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
      syncService.syncAfterCalendarUpdated(calendar.getOwnerId());
    } catch (Exception e) {
      // The edit is saved and that must stand whatever the server says.
      // Carrying it over is a convenience the next sync will retry.
      LOG.warn("The edit of calendar {} could not be carried over straight away; the next sync will", calendar.getId(), e);
    }
  }

  /**
   * The engine, resolved lazily because a listener is instantiated by the
   * kernel before the Spring beans it needs exist.
   *
   * @return the engine, or null when it cannot be resolved
   */
  private CaldavSyncService getCaldavSyncService() {
    if (caldavSyncService == null) {
      try {
        caldavSyncService = ExoContainerContext.getService(CaldavSyncService.class);
      } catch (Exception | LinkageError e) {
        LOG.debug("CalDAV sync engine not resolvable; the edit will be carried by the next sync", e);
      }
    }
    return caldavSyncService;
  }

  /**
   * Wires the engine directly, for tests.
   *
   * @param caldavSyncService the engine to use
   */
  protected void setCaldavSyncService(CaldavSyncService caldavSyncService) {
    this.caldavSyncService = caldavSyncService;
  }
}
