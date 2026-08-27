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

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.EventFilter;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.agenda.service.AgendaUserSettingsService;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Puts the meetings a user has not answered yet into their own calendar, with
 * their honest {@code PARTSTAT=NEEDS-ACTION} (EXO-89681).
 *
 * <p>
 * Until this ran, a copy only ever reached the account because the user acted
 * in a browser — and the act that pushed it was <i>accepting</i>, so a meeting
 * the user was still deciding about was exactly the one their phone never
 * showed. This pass runs server-side inside the background sync, so a pending
 * invitation becomes visible on the user's devices while they are deciding —
 * which is the precondition for answering it there at all: the verification
 * pass reads the answer back off the copy.
 *
 * <p>
 * It seeds; it does not maintain. An event that already has a mapping row is
 * left to the machinery that owns it — the browser-triggered push for edits,
 * the verification pass for drift and answers. And it only writes meetings:
 * events of the user's own calendars belong to their own collections and to
 * the flow that fills those, never to the mirror.
 */
@Service
public class CaldavPendingInvitationService {

  private static final Log          LOG = ExoLogger.getLogger(CaldavPendingInvitationService.class);

  @Autowired
  private AgendaEventService        agendaEventService;

  @Autowired
  private AgendaCalendarService     agendaCalendarService;

  @Autowired
  private AgendaUserSettingsService agendaUserSettingsService;

  @Autowired
  private CaldavPushService         caldavPushService;

  @Autowired
  private CaldavSyncStorage         caldavSyncStorage;

  /**
   * How far ahead the pass looks for meetings to seed. Far enough that an
   * invitation sent well in advance still shows up while the user decides,
   * near enough that one pass stays one agenda query and a handful of writes.
   */
  @Value("${exo.agenda.caldav.mirror.seedDays:60}")
  private int                       seedDays;

  /**
   * How many events one pass reads from agenda. A bound, not a page: a user
   * with more upcoming meetings than this gets the rest on the next pass,
   * once these are mapped.
   */
  @Value("${exo.agenda.caldav.mirror.seedLimit:200}")
  private int                       seedLimit;

  /**
   * Copies the user's upcoming meetings — pending ones included — into their
   * connected account, skipping everything a copy already exists for.
   *
   * @param userIdentityId identity of the user whose account receives copies
   * @return how many meetings were written this pass
   */
  public int pushUpcomingMeetings(long userIdentityId) {
    if (!copiesEnabled(userIdentityId)) {
      return 0;
    }
    List<Event> upcoming;
    try {
      ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
      // The three answers that keep a meeting on the user's plate. DECLINED
      // is deliberately absent: a declined meeting has no business appearing
      // in the user's calendar, and the answer flow removes its copy.
      EventFilter filter = new EventFilter(userIdentityId,
                                           null,
                                           List.of(EventAttendeeResponse.ACCEPTED,
                                                   EventAttendeeResponse.TENTATIVE,
                                                   EventAttendeeResponse.NEEDS_ACTION),
                                           now,
                                           now.plusDays(seedDays),
                                           seedLimit);
      upcoming = agendaEventService.getEvents(filter, ZoneOffset.UTC, userIdentityId);
    } catch (Exception e) { // NOSONAR agenda declares a checked exception here
      LOG.warn("The upcoming meetings of user {} could not be listed; nothing is seeded this round", userIdentityId, e);
      return 0;
    }
    // The series behind each occurrence, once each and in the order the window
    // returned them.
    Set<Long> candidates = new LinkedHashSet<>();
    for (Event occurrence : upcoming) {
      long eventId = occurrence.getParentId() > 0 ? occurrence.getParentId() : occurrence.getId();
      if (eventId > 0) {
        candidates.add(eventId);
      }
    }
    // Asked once for the whole window, and asked FIRST. In the steady state
    // every meeting in the window already has a copy, so this is the answer
    // for all of them — and it is the cheapest question available. Asking it
    // per meeting, after loading the event and its calendar, made the cost of
    // finding nothing to do grow with how much had already been done, on a
    // pass that repeats for ever.
    Set<Long> alreadyCopied = caldavSyncStorage.mappedEventIds(userIdentityId, candidates);
    int pushed = 0;
    for (Long eventId : candidates) {
      if (alreadyCopied.contains(eventId)) {
        continue;
      }
      if (seedOne(userIdentityId, eventId)) {
        pushed++;
      }
    }
    if (pushed > 0) {
      LOG.info("Seeded {} upcoming meeting(s) into the calendar account of user {}", pushed, userIdentityId);
    }
    return pushed;
  }

  /**
   * Writes the copy of one meeting, when it is one this pass owns writing.
   *
   * @param userIdentityId identity of the user
   * @param eventId the agenda event — a series master or a single event
   * @return true when a copy was written
   */
  private boolean seedOne(long userIdentityId, long eventId) {
    Event event = agendaEventService.getEventById(eventId);
    if (event == null || event.getStatus() != EventStatus.CONFIRMED) {
      // A date poll is spelled TENTATIVE and a cancelled event CANCELLED;
      // neither is a scheduled meeting a calendar should show.
      return false;
    }
    Calendar calendar = agendaCalendarService.getCalendarById(event.getCalendarId());
    if (calendar == null || calendar.getOwnerId() == userIdentityId) {
      // The user's own calendars have collections and a flow of their own;
      // filing their events among the meeting copies is the mixing the
      // mirror refuses.
      return false;
    }
    if (!caldavSyncStorage.mappedEventIds(userIdentityId, List.of(eventId)).isEmpty()) {
      // Asked again despite the batch check the caller already made: that
      // answer was read before this pass started writing, and a copy may have
      // appeared since — from the user's own browser, or from an earlier
      // meeting in this very loop belonging to the same series.
      //
      // Asked of THIS user's copies, never of everyone's: a meeting has an
      // attendee list, and each of them needs a copy of their own. The
      // unscoped question let whichever attendee was copied first answer for
      // all the rest, who were skipped and never got theirs.
      return false;
    }
    try {
      return caldavPushService.pushAgendaEvent(userIdentityId, eventId, null) != null;
    } catch (CaldavPushException e) {
      // One refused meeting must not stop the rest; whatever refused it is
      // asked again next pass. Warn rather than debug: a meeting that never
      // reaches a user's calendar is invisible to them and, at debug, to
      // everyone else too.
      LOG.warn("Event {} could not be seeded into the account of user {}", eventId, userIdentityId, e);
      return false;
    }
  }

  /**
   * Whether this user receives copies at all — the same per-account switch
   * the browser flow honours, read from agenda's settings for this add-on's
   * provider. A user who turned copies off has said no to everything this
   * pass does, the seeded pending invitations included.
   *
   * @param userIdentityId identity of the user
   * @return true when the connected CalDAV account accepts copies
   */
  /**
   * Whether a connected account's provider is one of this add-on's.
   *
   * <p>
   * A declared CalDAV server gets a provider name of its own — the base name
   * with the server's identifier appended — so only a user connected to the
   * seed registration carries the bare name. Matching the bare name alone
   * therefore read as "copies disabled" for everyone connected to a server an
   * administrator had declared, and their meetings were never copied at all,
   * silently: the pass returned before it did anything, and its own
   * diagnostics printed nothing because nothing had been considered.
   *
   * @param providerName the provider a connected account names
   * @return true when it is a CalDAV account of this add-on
   */
  private boolean isCaldavConnector(String providerName) {
    return providerName != null
        && (providerName.equals(CaldavPushService.CONNECTOR_NAME)
            || providerName.startsWith(CaldavPushService.CONNECTOR_NAME + "."));
  }

  private boolean copiesEnabled(long userIdentityId) {
    try {
      var settings = agendaUserSettingsService.getAgendaUserSettings(userIdentityId);
      return settings != null
             && settings.getConnectedConnectors()
                        .stream()
                        .anyMatch(account -> isCaldavConnector(account.getProviderName())
                                             && account.isPushEnabled());
    } catch (RuntimeException e) {
      // No settings readable means no consent readable, and consent is the
      // one thing this pass must not assume.
      LOG.debug("The agenda settings of user {} could not be read; no meeting is seeded", userIdentityId, e);
      return false;
    }
  }
}
