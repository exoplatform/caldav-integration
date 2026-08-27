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

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.constant.AgendaEventModificationType;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Carries an edit or a cancellation made in eXo out to every calendar copy of
 * that meeting that already exists.
 *
 * <p>
 * The half of the feature that was never built. A copy was written when
 * somebody asked for it — the organiser's browser, on saving — and then never
 * written again by anything an edit could reach. So an organiser who moved a
 * meeting moved it on their own phone and on nobody else's: the attendees'
 * copies kept the original time, the old location and the old video link, and
 * their owners acted on them.
 *
 * <p>
 * It was masked, on one family of servers, by a defect: BlueMind re-serialises
 * what it is handed, every copy therefore looked tampered with, and the
 * verification pass rewrote all of them on every sweep. That accident is what
 * delivered updates. EXO-89716 stops it — correctly — and on a byte-stable
 * server such as Stalwart there was never anything to stop.
 *
 * <h2>What it writes to, and what it refuses to</h2>
 *
 * <p>
 * Only where a copy already exists. The set is read from the mapping table, not
 * from agenda's attendee list, and that is the whole guard: an attendee who has
 * never had a copy of this meeting does not acquire one from somebody editing
 * it. Seeding a copy is a different decision, taken elsewhere, for its own
 * reasons — being added to a meeting is not the same event as the meeting
 * changing, and conflating them would push a year of past meetings at the first
 * person to connect an account.
 *
 * <p>
 * A mapping row with no href is not a copy. It is the tombstone a removal
 * leaves, and treating it as a destination would re-create on the server
 * exactly the object somebody deleted.
 *
 * <h2>Cost</h2>
 *
 * <p>
 * Per edit: one indexed query per page of holders, then per holder one GET (the
 * object is merged into, never replaced), one PUT, and one GET to read back what
 * the server stored. Three round trips per attendee <i>who has a copy</i> —
 * attendees who have none cost nothing at all, because they are not in the
 * query's answer. A holder whose server is unreachable costs one timeout and
 * does not touch the others.
 *
 * <p>
 * It runs on the listener's asynchronous thread and fans out on that same
 * thread, deliberately: a thread of its own would leave the kernel's container
 * behind, and the transactional write that records what was pushed would then
 * fail as a warning nobody reads.
 */
@Service
public class CaldavEventPropagationService {

  private static final Log                                     LOG                 =
                                                                   ExoLogger.getLogger(CaldavEventPropagationService.class);

  /** How many mappings are read at a time; the fan-out is walked in slices. */
  private static final int                                     SLICE               = 50;

  /**
   * The modifications a copy cannot show, so no copy is rewritten for them
   * alone.
   *
   * <p>
   * A deny-list rather than an allow-list, on purpose and at a cost: an
   * unrecognised modification type — one agenda adds tomorrow — falls through
   * and is carried, which is one wasted round trip. An allow-list would fail
   * the other way, and a modification silently not carried is the very defect
   * this class exists to end.
   *
   * <p>
   * Each entry is here because the written object provably does not carry it:
   * {@code UPDATED} accompanies every single edit and says only that one
   * happened; eXo's colour is a property of the eXo calendar and never reaches
   * the object; TRANSP is written {@code OPAQUE} unconditionally, so
   * availability cannot move it; who may invite or modify is an eXo permission
   * with no iCalendar counterpart; and the date-option rows are a poll's
   * bookkeeping, for an event no copy of which is ever pushed.
   */
  private static final Set<AgendaEventModificationType>        INVISIBLE_ON_A_COPY =
                                                                                   EnumSet.of(AgendaEventModificationType.UPDATED,
                                                                                              AgendaEventModificationType.COLOR_UPDATED,
                                                                                              AgendaEventModificationType.AVAILABILITY_UPDATED,
                                                                                              AgendaEventModificationType.ALLOW_INVITE_UPDATED,
                                                                                              AgendaEventModificationType.ALLOW_MODIFY_UPDATED,
                                                                                              AgendaEventModificationType.DATE_OPTION_CREATED,
                                                                                              AgendaEventModificationType.DATE_OPTION_UPDATED,
                                                                                              AgendaEventModificationType.DATE_OPTION_DELETED);

  @Autowired
  private CaldavPushService                                    caldavPushService;

  @Autowired
  private CaldavSyncStorage                                    caldavSyncStorage;

  @Autowired
  private AgendaEventService                                   agendaEventService;

  /**
   * Rewrites every existing copy of a meeting that has just been edited.
   *
   * <p>
   * A cancellation arrives here too, and needs no branch of its own: eXo keeps
   * the event and marks it CANCELLED, the mapper reads that, and the object
   * written carries {@code STATUS:CANCELLED}. The attendee's client shows the
   * meeting struck through rather than making it disappear — which matters,
   * because a meeting that disappears is indistinguishable from a
   * synchronisation that broke.
   *
   * @param eventId the agenda event that was edited
   * @param modificationTypes what agenda says moved, as the broadcast carried
   *          it; null or empty is treated as "something did"
   * @return how many copies were rewritten
   */
  public int propagateUpdate(long eventId, Set<AgendaEventModificationType> modificationTypes) {
    if (eventId <= 0) {
      return 0;
    }
    if (!worthCarrying(modificationTypes)) {
      LOG.debug("Event {} changed only in ways a calendar copy cannot show ({}); no copy is rewritten",
                eventId,
                modificationTypes);
      return 0;
    }
    Map<Long, ObjectSync> holders = holdersOf(eventId, true);
    if (holders.isEmpty()) {
      LOG.debug("Event {} was edited but nobody holds a copy of it; nothing to carry out", eventId);
      return 0;
    }
    int carried = 0;
    for (Map.Entry<Long, ObjectSync> holder : holders.entrySet()) {
      if (rewriteOne(holder.getKey(), eventId)) {
        carried++;
      }
    }
    LOG.info("Event {} was edited; its copy was rewritten for {} of {} holders", eventId, carried, holders.size());
    return carried;
  }

  /**
   * Removes every existing copy of a meeting eXo has destroyed.
   *
   * <p>
   * Removal, not a cancelled tombstone, and the asymmetry with
   * {@link #propagateUpdate} is the point: a copy shows what agenda holds, and
   * here agenda holds nothing. A tombstone would be unreclaimable — eXo has
   * forgotten the event, so no later pass could verify it, repair it or ever
   * decide to clear it, and its mapping row would name an event id that no
   * longer resolves. The attendee is not left to guess either way: agenda sends
   * them a cancellation notification on the same call that destroys the event.
   *
   * @param eventId the agenda event that was destroyed
   * @return how many copies were removed
   */
  public int propagateDeletion(long eventId) {
    if (eventId <= 0) {
      return 0;
    }
    // Not resolving the series here, and not able to: the event row is already
    // gone by the time this event is broadcast, so there is nothing left to
    // read a parent from.
    Map<Long, ObjectSync> holders = holdersOf(eventId, false);
    if (holders.isEmpty()) {
      LOG.debug("Event {} was deleted but nobody holds a copy of it; nothing to carry out", eventId);
      return 0;
    }
    int removed = 0;
    for (Map.Entry<Long, ObjectSync> holder : holders.entrySet()) {
      if (removeOne(holder.getKey(), holder.getValue())) {
        removed++;
      }
    }
    LOG.info("Event {} was deleted; its copy was removed for {} of {} holders", eventId, removed, holders.size());
    return removed;
  }

  /**
   * Whether anything that moved can show on a calendar copy.
   *
   * @param modificationTypes what agenda says moved; null or empty means the
   *          broadcast said nothing, which is not a reason to skip
   * @return true when at least one modification is one a copy can carry
   */
  private boolean worthCarrying(Set<AgendaEventModificationType> modificationTypes) {
    if (modificationTypes == null || modificationTypes.isEmpty()) {
      return true;
    }
    return modificationTypes.stream().anyMatch(type -> !INVISIBLE_ON_A_COPY.contains(type));
  }

  /**
   * Everyone who already holds a copy of this meeting, one mapping each.
   *
   * <p>
   * Keyed by user because a copy is written once per user, wherever it lives:
   * one user can hold a mapping in their mirror and another in a personal
   * collection, and rewriting the meeting twice for them would be two writes to
   * settle the same object.
   *
   * @param eventId the agenda event
   * @param resolveSeries whether to also look under the event's parent — an
   *          override and its series share one object, written under the
   *          series' identity, so an override edited alone would otherwise find
   *          no copy at all. Never asked for a deleted event, whose row is gone
   * @return the holders, by user identity, in the order the mappings were read
   */
  private Map<Long, ObjectSync> holdersOf(long eventId, boolean resolveSeries) {
    Map<Long, ObjectSync> holders = new LinkedHashMap<>();
    collectHolders(eventId, holders);
    if (resolveSeries) {
      long seriesId = seriesOf(eventId);
      if (seriesId > 0 && seriesId != eventId) {
        collectHolders(seriesId, holders);
      }
    }
    return holders;
  }

  /**
   * Adds the holders of one event id to the map, page by page.
   *
   * @param eventId the agenda event whose mappings are read
   * @param holders the map being filled, keyed by user identity
   */
  private void collectHolders(long eventId, Map<Long, ObjectSync> holders) {
    int page = 0;
    Page<ObjectSync> slice;
    do {
      slice = caldavSyncStorage.getObjectsByEvent(eventId, page, SLICE);
      for (ObjectSync mapping : slice.getContent()) {
        if (StringUtils.isBlank(mapping.getRemoteHref())) {
          // The tombstone a removal leaves. Writing to it would re-create on
          // the server the very object somebody deleted.
          continue;
        }
        CalendarSync pair = caldavSyncStorage.getPair(mapping.getCalendarSyncId());
        if (pair == null || pair.getUserIdentityId() <= 0) {
          LOG.warn("Mapping {} of event {} names collection {}, which does not resolve to a user; its copy is left alone",
                   mapping.getId(),
                   eventId,
                   mapping.getCalendarSyncId());
          continue;
        }
        holders.putIfAbsent(pair.getUserIdentityId(), mapping);
      }
      page++;
    } while (slice.hasNext());
  }

  /**
   * The series an event belongs to, or the event itself when it is not an
   * override.
   *
   * @param eventId the agenda event
   * @return the series' identifier, or 0 when the event cannot be read
   */
  private long seriesOf(long eventId) {
    try {
      Event event = agendaEventService.getEventById(eventId);
      if (event == null) {
        return 0;
      }
      return event.getParentId() > 0 ? event.getParentId() : event.getId();
    } catch (Exception | LinkageError e) {
      LOG.debug("Event {} could not be read to find its series; only its own copies are considered", eventId, e);
      return 0;
    }
  }

  /**
   * Rewrites one holder's copy, absorbing whatever that one account does to it.
   *
   * <p>
   * Every failure is contained here, and it has to be: fifty attendees means
   * fifty accounts on as many servers, and one of them being down, full or
   * mid-password-change is an ordinary Tuesday, not a reason the other
   * forty-nine keep a stale meeting.
   *
   * <p>
   * {@code LinkageError} as well as {@code Exception}. One escaped a
   * {@code catch (RuntimeException)} on this very code path once and took a
   * whole sweep down with it.
   *
   * @param userIdentityId the holder
   * @param eventId the agenda event to write again
   * @return true when the copy was rewritten
   */
  private boolean rewriteOne(long userIdentityId, long eventId) {
    try {
      return caldavPushService.pushAgendaEvent(userIdentityId, eventId) != null;
    } catch (CaldavPushException e) {
      if (CaldavPushService.CONFLICT.equals(e.getCode())) {
        // Somebody wrote that object between the read and the write — very
        // often the editor's own browser, which still pushes their own copy on
        // save. Not an incident: the conditional write did exactly its job, and
        // the verification pass is what looks before it writes.
        LOG.debug("The copy of event {} held by user {} changed under the rewrite; the verification pass will reconcile it",
                  eventId,
                  userIdentityId,
                  e);
      } else {
        LOG.warn("The edit of event {} could not be carried to the copy held by user {} ({}); the verification pass will retry",
                 eventId,
                 userIdentityId,
                 e.getCode(),
                 e);
      }
      return false;
    } catch (Exception | LinkageError e) {
      LOG.warn("The edit of event {} could not be carried to the copy held by user {}; the verification pass will retry",
               eventId,
               userIdentityId,
               e);
      return false;
    }
  }

  /**
   * Removes one holder's copy, absorbing whatever that one account does to it.
   *
   * @param userIdentityId the holder
   * @param mapping their mapping of the destroyed event, which is where the
   *          iCalendar identity comes from — agenda no longer has it
   * @return true when the copy was removed
   */
  private boolean removeOne(long userIdentityId, ObjectSync mapping) {
    if (StringUtils.isBlank(mapping.getIcsUid())) {
      LOG.warn("Mapping {} of user {} carries no iCalendar identity; the copy it names cannot be removed",
               mapping.getId(),
               userIdentityId);
      return false;
    }
    try {
      caldavPushService.deleteEvent(userIdentityId, mapping.getIcsUid());
      return true;
    } catch (Exception | LinkageError e) {
      LOG.warn("The copy of the deleted event held by user {} at {} could not be removed; it stays until a sweep clears it",
               userIdentityId,
               mapping.getRemoteHref(),
               e);
      return false;
    }
  }
}
