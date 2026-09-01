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
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.EventAttendee;
import org.exoplatform.agenda.model.EventAttendeeList;
import org.exoplatform.agenda.model.EventFilter;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
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
 * the verification pass for drift and answers.
 *
 * <h2>What it seeds, and why that is not only meetings (EXO-89796)</h2>
 *
 * <p>
 * It used to be only meetings: the pass listed the events the user is an
 * <i>attendee</i> of, and refused any event living in a calendar the user
 * owns. Both halves of that were wrong, and together they made the add-on
 * answer two different questions depending on when an event had been created.
 *
 * <p>
 * An event a user creates in their own calendar with nobody invited has
 * <b>no attendee row at all</b> — agenda writes attendee rows only for the
 * attendees it is given ({@code AgendaEventServiceImpl.createEvent} saves them
 * under {@code if (attendees != null && !attendees.isEmpty())}) — and agenda's
 * window query joins on those rows ({@code EventDAO.getEventIds} does
 * {@code INNER JOIN ev.attendees att ... AND att.identityId IN (:attendeeIds)}).
 * So such an event was invisible to this pass whatever response the filter
 * asked for, and the own-calendar refusal would have turned it away even if it
 * had been listed. Created <i>while</i> the account was connected it was copied
 * anyway, by the browser's own push on save; created before, it was copied by
 * nothing, for ever. The same event, copied or not according only to when it
 * came into being.
 *
 * <p>
 * So the pass now asks two questions — the events the user attends, and the
 * events of the user's own calendars — and stops refusing the second. Nothing
 * is mixed by that: {@link CaldavPushService#pushAgendaEvent(long, long)} is
 * what routes an event, and it sends an event of the user's own calendar to
 * that calendar's own collection, never to the mirror, answering null when
 * there is no collection to write into.
 *
 * <h2>The one question both paths ask</h2>
 *
 * <p>
 * {@link #seedOne(long, long)} is the whole decision, and it is deliberately
 * the only one: the background pass reaches it through
 * {@link #pushUpcomingMeetings(long)} and the creation listener reaches it
 * through {@link #seedMeeting(long, long)}, so an event that would be copied
 * were it created now is the same event this pass backfills. The listing above
 * is candidate selection and nothing more — a cheap way to name events, never
 * a second set of rules. That is why the "not DECLINED" rule lives in
 * {@code seedOne} and not only in the query.
 */
@Service
public class CaldavPendingInvitationService {

  private static final Log                         LOG          =
                                                       ExoLogger.getLogger(CaldavPendingInvitationService.class);

  /**
   * The answers that keep an event on the user's plate — every response except
   * {@link EventAttendeeResponse#DECLINED}.
   *
   * <p>
   * Derived from the enum rather than written out, and that is the point. Named
   * as a list of the three responses that exist today, it silently dropped
   * every event carrying a response agenda might add tomorrow — the same shape
   * of defect as the one this class was fixed for. What is being said is
   * "not declined", so that is what is written.
   *
   * <p>
   * A declined meeting has no business appearing in the user's calendar, and
   * the answer flow removes its copy. Asked of agenda this is an exact
   * complement: {@code EventAttendeeEntity.RESPONSE} is
   * {@code nullable = false}, so an attendee row always carries one of the
   * four responses and never none.
   */
  private static final List<EventAttendeeResponse> NOT_DECLINED =
                                                                Arrays.stream(EventAttendeeResponse.values())
                                                                      .filter(response -> response != EventAttendeeResponse.DECLINED)
                                                                      .toList();

  @Autowired
  private AgendaEventService                       agendaEventService;

  @Autowired
  private AgendaCalendarService                    agendaCalendarService;

  @Autowired
  private AgendaEventAttendeeService               agendaEventAttendeeService;

  @Autowired
  private AgendaUserSettingsService                agendaUserSettingsService;

  @Autowired
  private CaldavPushService                        caldavPushService;

  @Autowired
  private CaldavCopyPolicy                         caldavCopyPolicy;

  @Autowired
  private CaldavSyncStorage                        caldavSyncStorage;

  /**
   * How far ahead the pass looks for events to seed. Far enough that an
   * invitation sent well in advance still shows up while the user decides,
   * near enough that one pass stays a couple of agenda queries and a handful
   * of writes.
   *
   * <h2>Why the window starts at now, and the past is never backfilled</h2>
   *
   * <p>
   * Stated as a decision rather than left as a consequence of writing
   * {@code now} (EXO-89796). A copy exists so the user can see a commitment on
   * their phone and answer it there; an event that has already finished offers
   * neither, so writing it buys the user nothing and costs three network round
   * trips. And history is unbounded in a way the future is not: a user
   * connecting an account after two years of eXo would have those two years
   * written to their device on the first sweeps, spending {@link #seedLimit} on
   * events nobody will look at while the meeting they are being asked about
   * this afternoon waits behind them.
   *
   * <p>
   * "The past" here means <b>finished</b>, not "started before now": agenda
   * keeps an event whose {@code endDate} is still ahead
   * ({@code EventDAO.getEventIds} asks for
   * {@code ev.endDate IS NULL OR ev.endDate >= :start}), so a meeting running
   * right now is inside the window and is seeded.
   *
   * <p>
   * The cost is accepted and worth saying out loud: an event that was never
   * copied and then ends is never copied at all — no later pass reaches back
   * for it. Backfilling history would need a bounded, one-off pass keyed on
   * when the account was connected, which is a different feature from this one.
   */
  @Value("${exo.agenda.caldav.mirror.seedDays:60}")
  private int                                      seedDays;

  /**
   * How many events one question puts to agenda. A bound, not a page: a user
   * with more upcoming events than this gets the rest on the next pass, once
   * these are mapped.
   *
   * <p>
   * Two questions are asked per pass — what the user attends, and what their
   * own calendars hold — so a pass reads at most twice this many events. The
   * bound is per question rather than over the merged answer on purpose: a
   * shared budget would let whichever question was asked first starve the
   * other, and which events a user gets would then depend on the order of two
   * lines of code.
   */
  @Value("${exo.agenda.caldav.mirror.seedLimit:200}")
  private int                                      seedLimit;

  /**
   * Copies the user's upcoming events — meetings they have not answered yet
   * and events of their own calendars alike — into their connected account,
   * skipping everything a copy already exists for.
   *
   * <p>
   * Two questions, because agenda cannot answer them as one: its window query
   * ANDs the calendar-owner predicate with the attendee predicate rather than
   * ORing them ({@code EventDAO.getEventIds}), so "events I attend or events in
   * my calendars" is two calls. Their answers are merged and asked about once.
   *
   * @param userIdentityId identity of the user whose account receives copies
   * @return how many events were written this pass
   */
  public int pushUpcomingMeetings(long userIdentityId) {
    if (!copiesEnabled(userIdentityId)) {
      return 0;
    }
    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
    ZonedDateTime until = now.plusDays(seedDays);
    // The series behind each occurrence, once each and in the order the window
    // returned them.
    Set<Long> candidates = new LinkedHashSet<>();
    collectSeries(candidates, attendedBy(userIdentityId, now, until));
    collectSeries(candidates, ownedBy(userIdentityId, now, until));
    if (candidates.isEmpty()) {
      return 0;
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
      LOG.info("Seeded {} upcoming event(s) into the calendar account of user {}", pushed, userIdentityId);
    }
    return pushed;
  }

  /**
   * The events in the window this user is an attendee of, declined ones aside.
   *
   * <p>
   * The response filter is a pre-filter and nothing more — {@code seedOne}
   * asks the same question again of every candidate, whichever list named it.
   * It is kept here because it is free: without it a meeting the user declined
   * would be listed, refused, and listed again on every pass for as long as it
   * stays in the window, never converging.
   *
   * @param userIdentityId identity of the user
   * @param from start of the window
   * @param to end of the window
   * @return what agenda answers, or nothing when it could not be asked
   */
  private List<Event> attendedBy(long userIdentityId, ZonedDateTime from, ZonedDateTime to) {
    return listed(userIdentityId,
                  new EventFilter(userIdentityId, null, NOT_DECLINED, from, to, seedLimit),
                  "the meetings they attend");
  }

  /**
   * The events in the window that live in a calendar this user owns.
   *
   * <p>
   * Asked by owner rather than by attendee, and that is the whole of what it
   * adds: an event a user creates for themselves alone carries no attendee row
   * for anybody, so no attendee-keyed question can ever name it (EXO-89796).
   *
   * <p>
   * No response filter here, because there is no attendee predicate for one to
   * apply to; the declined case is caught in {@code seedOne} instead, which is
   * where it holds for both lists at once.
   *
   * @param userIdentityId identity of the user
   * @param from start of the window
   * @param to end of the window
   * @return what agenda answers, or nothing when it could not be asked
   */
  private List<Event> ownedBy(long userIdentityId, ZonedDateTime from, ZonedDateTime to) {
    return listed(userIdentityId,
                  new EventFilter(0, List.of(userIdentityId), null, from, to, seedLimit),
                  "the events of their own calendars");
  }

  /**
   * Puts one window question to agenda, absorbing a failure to answer it.
   *
   * <p>
   * One list failing must not cost the user the other: an account whose space
   * memberships cannot be resolved still has its own calendar seeded, and the
   * other way round.
   *
   * @param userIdentityId identity of the user the window is read for
   * @param filter what is being asked
   * @param what names the question in the warning, read as "... of user 42"
   * @return what agenda answers, or nothing when it could not be asked
   */
  private List<Event> listed(long userIdentityId, EventFilter filter, String what) {
    try {
      List<Event> events = agendaEventService.getEvents(filter, ZoneOffset.UTC, userIdentityId);
      return events == null ? List.of() : events;
    } catch (Exception e) { // NOSONAR agenda declares a checked exception here
      LOG.warn("Listing {} of user {} failed; that list is not seeded this round", what, userIdentityId, e);
      return List.of();
    }
  }

  /**
   * Adds the series behind each listed occurrence to the candidate set.
   *
   * <p>
   * A window query expands a series into its occurrences and the copy is one
   * object under the master, so the master is what a candidate names — once,
   * however many of its occurrences the window returned.
   *
   * @param candidates the set being built, in the order the windows answered
   * @param events one window's answer
   */
  private void collectSeries(Set<Long> candidates, Collection<Event> events) {
    for (Event occurrence : events) {
      long eventId = occurrence.getParentId() > 0 ? occurrence.getParentId() : occurrence.getId();
      if (eventId > 0) {
        candidates.add(eventId);
      }
    }
  }

  /**
   * Writes the copy of one named meeting into one user's account, for a caller
   * that already knows which meeting and which user.
   *
   * <p>
   * The same decision as the pass above, asked one meeting at a time. It exists
   * because a meeting being created is a fact the platform already tells us
   * ({@code exo.agenda.event.created}, EXO-89754), and waiting for the next
   * sweep to rediscover it by listing a 60-day window is both slower and
   * narrower: the window is where a meeting further out, or created in the past,
   * used to fall through and never get a copy at all.
   *
   * <p>
   * Every refusal the pass makes is made here too, and that is the point of
   * routing through this service rather than calling the push directly: the
   * account has to be connected with copies enabled, the meeting has to be
   * CONFIRMED, the user must not have declined it, and a meeting that already
   * has a copy is left to the machinery that owns it. That last one is what
   * makes a second trigger on the same creation write nothing.
   *
   * @param userIdentityId identity of the user whose account receives the copy
   * @param eventId the agenda event — a series master or a single event
   * @return true when a copy was written
   */
  public boolean seedMeeting(long userIdentityId, long eventId) {
    if (userIdentityId <= 0 || eventId <= 0 || !copiesEnabled(userIdentityId)) {
      return false;
    }
    return seedOne(userIdentityId, eventId);
  }

  /**
   * Writes the copy of one event, when it is one this pass owns writing.
   *
   * <p>
   * <b>The single place the question is answered.</b> The background pass and
   * the creation listener both arrive here, so whatever this refuses is
   * refused on both, and whatever it writes is written on both. An event that
   * would be copied were it created now is therefore exactly an event this
   * backfills — which is the equivalence EXO-89796 restored, and the reason no
   * rule of substance is allowed to live in the window queries.
   *
   * @param userIdentityId identity of the user
   * @param eventId the agenda event — a series master or a single event
   * @return true when a copy was written
   */
  private boolean seedOne(long userIdentityId, long eventId) {
    Event event = agendaEventService.getEventById(eventId);
    if (!caldavCopyPolicy.maySeedCopy(event)) {
      // A date poll is spelled TENTATIVE and a cancelled event CANCELLED;
      // neither is a meeting a calendar should be given a fresh copy of.
      //
      // The rule used to be spelled out here, in the one place that happened
      // to need it. It moved into CaldavCopyPolicy (EXO-89863) so that it sits
      // beside the rule the push core enforces — a poll may not hold a copy at
      // all — because the two answer the same question about the same event
      // and had already drifted once: this refused a poll while the push core
      // wrote one for whoever's browser asked.
      return false;
    }
    Calendar calendar = agendaCalendarService.getCalendarById(event.getCalendarId());
    if (calendar == null) {
      // No calendar, no routing: which collection an event belongs in is read
      // from the calendar it lives in, so an event whose calendar cannot be
      // loaded has no destination anybody can name.
      //
      // What is deliberately NOT refused here any more is an event of a
      // calendar the user owns (EXO-89796). Routing is CaldavPushService's
      // question, and it answers it correctly: an own-calendar event goes to
      // that calendar's own collection, or nowhere. Refusing it here meant an
      // event a user made for themselves was copied when they created it in a
      // browser and never copied when it predated the connection.
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
    if (hasDeclined(userIdentityId, eventId)) {
      // A meeting somebody said no to has no business appearing in their
      // calendar, and the answer flow removes the copy of one they decline
      // later. Stated here rather than only in the window query, because the
      // own-calendar list has no attendee predicate for a query filter to ride
      // on, and because the creation listener never goes through a query at
      // all.
      //
      // Last of the refusals, because it is the only one that costs a query
      // agenda has not already been asked: everything already copied — which
      // in the steady state is everything — is turned away above it without
      // paying for it.
      return false;
    }
    try {
      return caldavPushService.pushAgendaEvent(userIdentityId, eventId) != null;
    } catch (CaldavPushException e) {
      if (CaldavPushService.isKnownState(e.getCode())) {
        // Not a failure and not an incident: a state of this user that no
        // retry changes and only they, or their administrator, can clear —
        // never having connected an account, not having said where their
        // copies go, an account naming no default calendar. Warning would
        // print one line per upcoming meeting per pass, for ever, for every
        // such user, burying the failures that are failures under the states
        // nobody is going to change.
        //
        // Without the exception: a known state does not need eleven frames to
        // be understood, and it is the trace, not the line, that made this
        // unreadable.
        LOG.debug("Event {} is not seeded into the account of user {}: {} ({})",
                  eventId,
                  userIdentityId,
                  e.getMessage(),
                  e.getCode());
        return false;
      }
      // One refused meeting must not stop the rest; whatever refused it is
      // asked again next pass. Warn rather than debug: a meeting that never
      // reaches a user's calendar is invisible to them and, at debug, to
      // everyone else too.
      LOG.warn("Event {} could not be seeded into the account of user {}", eventId, userIdentityId, e);
      return false;
    }
  }

  /**
   * Whether this user has answered no to this event.
   *
   * <p>
   * Asked of agenda's declined attendees rather than of the whole list, so the
   * answer is a membership test and the question carries what it means. A user
   * invited only through a space they belong to has no attendee row of their
   * own and so has declined nothing — which is right: they have not been asked
   * individually, and an unanswered invitation is exactly what this pass
   * exists to make visible.
   *
   * @param userIdentityId identity of the user
   * @param eventId the agenda event — a series master or a single event
   * @return true when the user is a declined attendee of it
   */
  private boolean hasDeclined(long userIdentityId, long eventId) {
    try {
      EventAttendeeList declined = agendaEventAttendeeService.getEventAttendees(eventId, EventAttendeeResponse.DECLINED);
      List<EventAttendee> attendees = declined == null ? null : declined.getEventAttendees();
      if (attendees == null) {
        return false;
      }
      return attendees.stream().anyMatch(attendee -> attendee.getIdentityId() == userIdentityId);
    } catch (Exception | LinkageError e) { // NOSONAR one unreadable answer must not stop the pass
      // Unreadable is not declined. Treating it as declined would silently
      // stop copying for a user whose attendee rows agenda cannot answer for,
      // which is the failure this class keeps being fixed for.
      LOG.debug("The answer of user {} to event {} could not be read; the event is treated as not declined",
                userIdentityId,
                eventId,
                e);
      return false;
    }
  }

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

  /**
   * Whether this user receives copies at all — the same per-account switch the
   * browser flow honours, read from agenda's settings for this add-on's
   * provider. A user who turned copies off has said no to everything this
   * service does, the seeded pending invitations included.
   *
   * <p>
   * Its documentation used to sit above {@link #isCaldavConnector} instead,
   * where the javadoc tool attributed it to that method and this one was
   * generated undocumented.
   *
   * @param userIdentityId identity of the user
   * @return true when the connected CalDAV account accepts copies
   */
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
