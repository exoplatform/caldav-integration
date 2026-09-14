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
package org.exoplatform.caldav.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.EventFilter;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Undoes what the sweep did to a calendar the user only subscribed to, before
 * the server's naming was read (EXO-90275).
 *
 * <p>
 * Until then a BlueMind resource calendar or a colleague's main calendar the
 * user subscribed to was materialised as the user's own personal calendar:
 * an ACTIVE {@link SyncOrigin#REMOTE} binding, an agenda calendar the user
 * could edit, publish and delete, its events imported, the user's own edits
 * pushed into somebody else's calendar (rig calendar 16, acceptance calendar
 * 33). The classification now keeps new ones from being made; this retires
 * the ones that exist, so that the collection becomes what it would have been
 * had it never been materialised — a read-only calendar under "Shared with
 * me", its events served from the server.
 *
 * <p>
 * <b>Nothing is written to the server, ever.</b> What the calendar holds is
 * a copy of the server's collection, and the collection is not touched: the
 * agenda calendar is deleted through agenda, whose storage removes its events
 * in bulk and broadcasts only {@code exo.agenda.calendar.deleted}, which this
 * add-on does not listen to — so no deletion is carried out anywhere — and
 * the binding and its object mappings are then dropped.
 *
 * <p>
 * <b>An event the server never received is not deleted with the copies.</b>
 * The user's own edits in a materialised calendar are pushed on save through
 * its binding, which records a mapping; an event of that calendar no mapping
 * of the binding names is one the server does not hold, and deleting the
 * calendar would lose it. When there is one, the calendar and its events are
 * kept and the binding is paused instead — no more reading, no more writing,
 * no export either, since a REMOTE binding of any state keeps the calendar
 * from being pushed out as a new collection — and a warning names the count,
 * for a human to move those events and delete the calendar. The events are
 * looked for over the window the import reads, which is where the sweep put
 * copies and where a user edits.
 *
 * <p>
 * <b>Order.</b> The calendar first, then the mappings, then the binding. A
 * calendar that cannot be deleted leaves everything as it was, and the next
 * pass tries again; a binding left without its calendar is dropped by the
 * orphan pruning, and the collection is then classified and skipped. The
 * reverse order could leave a personal calendar with no binding, which the
 * outbound half exports to the server as a new collection — a write to the
 * server this class exists never to cause.
 *
 * <p>
 * Said once per binding: at info when it is retired, since the binding is
 * then gone; at warn when it is paused, since a paused binding is no longer
 * one this is asked about. A reconnection thaws paused bindings, and the
 * next pass pauses it and says so again.
 */
@Component
public class CaldavSubscriptionRetirementService {

  private static final Log      LOG   = ExoLogger.getLogger(CaldavSubscriptionRetirementService.class);

  /** How many object mappings one page reads. */
  private static final int      SLICE = 200;

  @Autowired
  private CaldavSyncStorage     caldavSyncStorage;

  @Autowired
  private AgendaCalendarService agendaCalendarService;

  @Autowired
  private AgendaEventService    agendaEventService;

  @Autowired
  private CaldavTuningService   caldavTuningService;

  /**
   * What retiring one binding came to.
   */
  public enum Retirement {
    /** The calendar, its mappings and its binding are gone. */
    RETIRED,
    /** The calendar holds events the server never received; the binding is paused. */
    PAUSED,
    /** Nothing was changed: not eligible, or a step failed and is retried next pass. */
    KEPT
  }

  /**
   * Retires a binding the sweep made for a collection that is a subscription.
   *
   * @param userIdentityId identity of the user the binding belongs to
   * @param username the user's login, which agenda's calendar listing reads
   * @param pair the binding, which must be an ACTIVE REMOTE pair of this user
   * @param ownership whose the collection is, as the classification answered
   * @return what was done
   */
  public Retirement retire(long userIdentityId, String username, CalendarSync pair, CollectionOwnership ownership) {
    if (ownership == null || !ownership.isSubscription() || pair == null || pair.getId() == null
        || pair.getUserIdentityId() != userIdentityId || pair.getOrigin() != SyncOrigin.REMOTE
        || pair.getStatus() != CalendarSyncStatus.ACTIVE) {
      return Retirement.KEPT;
    }
    Calendar calendar = calendarOf(userIdentityId, username, pair.getLocalCalendarSyncUid());
    if (calendar == null) {
      // No calendar behind the binding, or none that could be read. The
      // orphan pruning handles the first; the second is retried.
      LOG.debug("Binding {} of user {} has no readable calendar behind it; it is not retired this pass", pair.getId(), userIdentityId);
      return Retirement.KEPT;
    }
    Set<Long> unreceived;
    try {
      unreceived = eventsTheServerNeverReceived(userIdentityId, calendar.getId(), mappedEventIds(pair.getId()));
    } catch (Exception e) { // NOSONAR agenda declares a checked exception here
      LOG.debug("The events of calendar {} could not be read; binding {} is not retired this pass", calendar.getId(), pair.getId(), e);
      return Retirement.KEPT;
    }
    if (!unreceived.isEmpty()) {
      pair.setStatus(CalendarSyncStatus.PAUSED);
      caldavSyncStorage.savePair(pair);
      LOG.warn("Calendar {} of user {} was materialised from {}, a {} the user only subscribed to, and holds {} event(s) the"
          + " server never received ({}); it is kept and its binding {} is paused, so nothing more is read from or written"
          + " to that calendar. Move those events to another calendar, then delete this one",
               calendar.getId(),
               userIdentityId,
               pair.getRemoteHref(),
               ownership == CollectionOwnership.SUBSCRIBED_RESOURCE ? "resource calendar" : "calendar of another person",
               unreceived.size(),
               unreceived,
               pair.getId());
      return Retirement.PAUSED;
    }
    try {
      agendaCalendarService.deleteCalendarById(calendar.getId());
    } catch (ObjectNotFoundException e) {
      LOG.debug("Calendar {} was already gone when binding {} was retired", calendar.getId(), pair.getId());
    } catch (RuntimeException e) {
      LOG.warn("Calendar {} of user {}, materialised from the subscription {}, could not be deleted; its binding is kept and"
          + " the next pass tries again",
               calendar.getId(),
               userIdentityId,
               pair.getRemoteHref(),
               e);
      return Retirement.KEPT;
    }
    caldavSyncStorage.deleteObjects(pair.getId());
    caldavSyncStorage.deletePair(pair.getId());
    LOG.info("Calendar {} of user {} had been materialised from {}, a {} the user only subscribed to; the calendar and its"
        + " copies are removed from eXo, nothing was written to the server, and the calendar is now listed read-only under"
        + " Shared with me",
             calendar.getId(),
             userIdentityId,
             pair.getRemoteHref(),
             ownership == CollectionOwnership.SUBSCRIBED_RESOURCE ? "resource calendar" : "calendar of another person");
    return Retirement.RETIRED;
  }

  /**
   * The user's own calendar carrying an anchor.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   * @param anchor the anchor the binding records
   * @return the calendar, or null when none carries the anchor or the
   *         calendars could not be read
   */
  private Calendar calendarOf(long userIdentityId, String username, String anchor) {
    try {
      return agendaCalendarService.getCalendars(0, Integer.MAX_VALUE, username)
                                  .stream()
                                  .filter(calendar -> calendar.getOwnerId() == userIdentityId && !calendar.isDeleted())
                                  .filter(calendar -> anchor != null && anchor.equals(calendar.getSyncUid()))
                                  .findFirst()
                                  .orElse(null);
    } catch (Exception e) { // NOSONAR agenda declares a bare Exception here
      LOG.debug("The calendars of user {} could not be read", userIdentityId, e);
      return null;
    }
  }

  /**
   * The agenda events a binding's object mappings name — the copies of what
   * the server holds.
   *
   * @param calendarSyncId the binding
   * @return the mapped event ids, empty when none
   */
  private Set<Long> mappedEventIds(long calendarSyncId) {
    Set<Long> mapped = new HashSet<>();
    int page = 0;
    Page<ObjectSync> slice;
    do {
      slice = caldavSyncStorage.getObjects(calendarSyncId, page, SLICE);
      for (ObjectSync mapping : slice.getContent()) {
        if (mapping.getLocalEventId() != null && mapping.getLocalEventId() > 0) {
          mapped.add(mapping.getLocalEventId());
        }
      }
      page++;
    } while (slice.hasNext());
    return mapped;
  }

  /**
   * The events of a calendar no mapping of its binding names, over the
   * window the import reads.
   *
   * <p>
   * An occurrence counts as mapped when its series is: a recurring event is
   * mapped once, under its parent.
   *
   * @param userIdentityId identity of the user, owner of the calendar
   * @param calendarId the calendar
   * @param mapped the event ids the binding's mappings name
   * @return the ids of the events the server never received, empty when none
   * @throws IllegalAccessException when agenda refuses the read
   */
  private Set<Long> eventsTheServerNeverReceived(long userIdentityId, long calendarId, Set<Long> mapped) throws IllegalAccessException {
    Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
    Instant from = today.minus(Duration.ofDays(caldavTuningService.getPastDays()));
    Instant to = today.plus(Duration.ofDays(caldavTuningService.getFutureDays() + 1L));
    EventFilter filter = new EventFilter(List.of(userIdentityId),
                                         ZonedDateTime.ofInstant(from, ZoneOffset.UTC),
                                         ZonedDateTime.ofInstant(to, ZoneOffset.UTC));
    List<Event> events = agendaEventService.getEvents(filter, ZoneOffset.UTC, userIdentityId);
    Set<Long> unreceived = new LinkedHashSet<>();
    for (Event event : events) {
      if (event.getCalendarId() != calendarId) {
        continue;
      }
      boolean copy = mapped.contains(event.getId()) || (event.getParentId() > 0 && mapped.contains(event.getParentId()));
      if (!copy) {
        unreceived.add(event.getId() > 0 ? event.getId() : event.getParentId());
      }
    }
    return unreceived;
  }
}
