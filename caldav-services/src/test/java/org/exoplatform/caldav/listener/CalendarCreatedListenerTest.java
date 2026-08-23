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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.caldav.service.CaldavSyncService;
import org.exoplatform.services.listener.Event;

/**
 * The glue between a calendar being created and it reaching the user's server.
 */
@ExtendWith(MockitoExtension.class)
public class CalendarCreatedListenerTest {

  private static final long       OWNER = 7L;

  @Mock
  private CaldavSyncService       caldavSyncService;

  private CalendarCreatedListener listener;

  /**
   * A listener holding the engine, since there is no container to resolve it.
   */
  @BeforeEach
  public void wireTheEngine() {
    listener = new CalendarCreatedListener();
    listener.setCaldavSyncService(caldavSyncService);
  }

  /**
   * A created calendar is handed straight to the engine.
   */
  @Test
  public void aCreatedCalendarIsPushedWithoutWaitingForTheThrottle() {
    listener.onEvent(eventFor(calendar(OWNER)));

    verify(caldavSyncService).syncAfterCalendarCreated(OWNER);
  }

  /**
   * A calendar with no owner is not something to synchronise for.
   */
  @Test
  public void aCalendarWithNoOwnerIsIgnored() {
    listener.onEvent(eventFor(calendar(0L)));

    verify(caldavSyncService, never()).syncAfterCalendarCreated(anyLong());
  }

  /**
   * An event carrying nothing is not an error.
   */
  @Test
  public void anEventCarryingNoCalendarIsIgnored() {
    assertDoesNotThrow(() -> listener.onEvent(eventFor(null)));
    assertDoesNotThrow(() -> listener.onEvent(null));

    verify(caldavSyncService, never()).syncAfterCalendarCreated(anyLong());
  }

  /**
   * A failing sync never costs the calendar that was created.
   */
  @Test
  public void aFailingSyncDoesNotUndoTheCalendarCreation() {
    // The calendar exists and must stand whatever the server says. Pushing it
    // is a convenience the next sync retries; letting this throw would make a
    // slow or unreachable server able to break creating a calendar that has
    // nothing to do with it.
    doThrow(new IllegalStateException("down")).when(caldavSyncService).syncAfterCalendarCreated(OWNER);

    assertDoesNotThrow(() -> listener.onEvent(eventFor(calendar(OWNER))));
  }

  /**
   * @param ownerId the identity owning the calendar
   * @return a created calendar
   */
  private Calendar calendar(long ownerId) {
    Calendar calendar = new Calendar();
    calendar.setId(42L);
    calendar.setOwnerId(ownerId);
    return calendar;
  }

  /**
   * @param calendar the event's source, possibly null
   * @return the event agenda broadcasts
   */
  private Event<Calendar, Object> eventFor(Calendar calendar) {
    return new Event<>("exo.agenda.calendar.created", calendar, null);
  }
}
