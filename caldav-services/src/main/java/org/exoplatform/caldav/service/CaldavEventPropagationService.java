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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.constant.AgendaEventModificationType;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.EventAttendee;
import org.exoplatform.agenda.model.EventAttendeeList;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.caldav.ics.IcsText;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.PendingPush;
import org.exoplatform.caldav.model.PendingPushKind;
import org.exoplatform.caldav.storage.CaldavPendingPushStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Carries what happens to a meeting in eXo out to the calendars of the people
 * it concerns: a creation to everyone invited, an edit or a cancellation to
 * every copy that already exists, a deletion to every copy that has to go.
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
 * For an <b>edit</b> or a <b>deletion</b>: only where a copy already exists. The
 * set is read from the mapping table, not from agenda's attendee list, and that
 * is the whole guard: an attendee who has never had a copy of this meeting does
 * not acquire one from somebody editing it. Conflating the two would push a year
 * of past meetings at the first person to connect an account.
 *
 * <p>
 * For a <b>creation</b> the set is agenda's attendee list, because handing out a
 * copy is precisely what a creation means — and it is bounded by construction:
 * one meeting, the people invited to it, once. What each of them gets is still
 * not decided here: {@link #propagateCreation} asks
 * {@link CaldavPendingInvitationService}, which already owns that question for
 * the background seeding pass, so the answer does not depend on which path
 * arrived at it.
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
 *
 * <h2>What happens when a write does not land (EXO-89773)</h2>
 *
 * <p>
 * It is recorded, and tried again. Before this, a push that failed left nothing
 * behind but a log line saying the verification pass would retry — and that
 * pass never could. Its first gate compares the version the server publishes
 * for a copy against the version eXo recorded, and <b>an edit that never
 * reached the server does not move the server's version</b>, so the copy was
 * judged untouched before anything was fetched or compared. The copy stayed
 * wrong for ever, and nothing said so. A failed <i>removal</i> had no safety net
 * at all: a destroyed event renders to nothing, and the verification pass
 * deliberately concludes nothing from an empty render, so no pass would ever
 * take that meeting out of somebody's calendar.
 *
 * <p>
 * It was masked until this week by the same accident that masked the missing
 * listeners: BlueMind moved every ETag, so every copy looked altered and the
 * repair pass delivered pending edits as a side effect. EXO-89716 and
 * EXO-89756 removed that churn, correctly, and with it a safety net nobody knew
 * was load-bearing.
 *
 * <p>
 * So the obligation is written down on eXo's own side, <b>before</b> the write
 * is attempted, as a row of {@code CALDAV_PENDING_PUSH}: this copy does not yet
 * show what eXo holds. A write that lands deletes it. A write that does not
 * leaves it, and {@link #retryOwedPushes} — run by the background sweep, one
 * account at a time — attempts it again, up to a bound, so a server that
 * refuses for ever is argued with a few times and then left alone and said out
 * loud.
 *
 * <p>
 * Recorded before rather than after, and that ordering is the whole of what it
 * buys over a {@code catch} block: a PUT that times out ambiguously, a thread
 * killed mid-fan-out and a platform restarted between two attendees all leave
 * no exception for anybody to catch, and all of them leave the obligation
 * standing. The cost is one small write per holder per edit on the happy path,
 * against the three network round trips that holder already costs.
 *
 * <p>
 * <b>What it deliberately does not do.</b> It does not re-render every copy on
 * every sweep to find out which are behind — that is precisely the churn
 * EXO-89716 and EXO-89756 spent two days removing. A converged account has an
 * empty obligation table and the retry costs it one index lookup that answers
 * no rows.
 */
@Service
public class CaldavEventPropagationService {

  private static final Log                                     LOG                 =
                                                                   ExoLogger.getLogger(CaldavEventPropagationService.class);

  /** How many mappings are read at a time; the fan-out is walked in slices. */
  private static final int                                     SLICE               = 50;

  /**
   * How many owed writes one account's retry pass takes on.
   *
   * <p>
   * A bound rather than "everything owed", for the reason every listing in this
   * add-on is bounded: a backlog is somebody's calendar having gone wrong at
   * scale, and the pass that drains it must not be the one that also stalls the
   * sweep behind it. What is left over waits for the next sweep, which is
   * minutes away.
   */
  private static final int                                     RETRY_BATCH         = 200;

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
   * the object; who may invite or modify is an eXo permission with no
   * iCalendar counterpart; and a date option is a row of a poll's own
   * bookkeeping, which the copy does not carry — the copy carries the
   * <i>envelope</i> the options span, and an option that moves the envelope
   * moves the event's own start or end with it, so that change arrives here as
   * {@code START_DATE_UPDATED}/{@code END_DATE_UPDATED} as well and is carried
   * on those. An option edited inside the envelope changes nothing a copy
   * states, which is what this entry is for.
   *
   * <p>
   * <b>{@code AVAILABILITY_UPDATED} left this list in EXO-89870</b>, and the
   * sentence that justified it — "TRANSP is written {@code OPAQUE}
   * unconditionally, so availability cannot move it" — is no longer true. The
   * copy of an event marked {@code FREE} now carries
   * {@code TRANSP:TRANSPARENT}, so availability is a visible property of the
   * object and a change to it must reach every holder's copy. Leaving it here
   * would have made the one modification that moves TRANSP the one
   * modification no rewrite is issued for, and the mirror sweep would have had
   * to find it minutes later as a divergence and repair it — a repair standing
   * in for an edit nobody carried.
   */
  private static final Set<AgendaEventModificationType>        INVISIBLE_ON_A_COPY =
                                                                                   EnumSet.of(AgendaEventModificationType.UPDATED,
                                                                                              AgendaEventModificationType.COLOR_UPDATED,
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

  @Autowired
  private AgendaEventAttendeeService                           agendaEventAttendeeService;

  @Autowired
  private IdentityManager                                      identityManager;

  @Autowired
  private CaldavPendingInvitationService                       caldavPendingInvitationService;

  @Autowired
  private CaldavCopyPolicy                                     caldavCopyPolicy;

  @Autowired
  private CaldavPendingPushStorage                             caldavPendingPushStorage;

  /**
   * How many times the retry may be refused before it stops arguing.
   *
   * <p>
   * It counts the <b>retries</b> and not the write that failed first: that
   * write is the reason the record exists, so counting it as an attempt would
   * make the bound mean one thing on the listener's path and another on the
   * sweep's. Five is enough to ride out a server having a bad afternoon at a
   * five-minute cadence, and few enough that a permanently refusing one is
   * given up on inside half an hour rather than argued with for ever.
   */
  @Value("${exo.agenda.caldav.push.maxAttempts:5}")
  private int                                                  maxPushAttempts;

  /**
   * Copies a meeting that has just been created into the calendar of everybody
   * invited to it.
   *
   * <p>
   * The asymmetry with {@link #propagateUpdate} is the whole point of having a
   * second method rather than one. An edit writes only where a copy already
   * exists — the set is read from the mapping table, so that editing a meeting
   * never hands a copy to somebody who has never had one. A creation is the one
   * moment where handing out a copy <i>is</i> the instruction, so the set is
   * read from agenda's attendee list instead, and it is bounded by construction:
   * one meeting, the people invited to it, once.
   *
   * <p>
   * The decision about each of them is not taken here. It belongs to
   * {@link CaldavPendingInvitationService}, which already owns the question
   * "does this user get a copy of this meeting" for the background seeding pass
   * — the account has to be connected, copies have to be enabled on it, the
   * meeting has to be CONFIRMED rather than a date poll, the user must not have
   * declined it, and a meeting that already has a copy is left to the machinery
   * that owns it. Asking the same service here is what keeps the answer the same
   * whichever path arrives at it, and it is the guard that makes a second
   * trigger on the same creation write nothing.
   *
   * <p>
   * Keeping the answer the same is not a figure of speech; it stopped being
   * true once and had to be restored (EXO-89796). While the shared decision
   * also refused any event of a calendar the user owns, the two paths diverged
   * in practice: an event somebody made for themselves was copied by their
   * browser on save and never seeded afterwards, so the same event was copied
   * or not according only to whether it predated the connection. Where an event
   * belongs is {@link CaldavPushService#pushAgendaEvent(long, long)}'s question
   * and it answers it — own calendar to its own collection, anything else to
   * the mirror — so the shared decision no longer duplicates it.
   *
   * <p>
   * Only the attendees that are <b>users</b> are considered. A space invited to
   * a meeting is one attendee row standing for its members, and expanding it
   * here would turn one creation into as many settings reads and writes as the
   * space has members — for people this add-on does not even name on the copy it
   * writes. They are reached by the background seeding pass, which resolves
   * space membership through agenda's own query and costs one pass per user
   * rather than one fan-out per event.
   *
   * <b>The author's own copy is not written here, and that is the double push.</b>
   *
   * <p>
   * The person who created the meeting is skipped, because their <b>browser
   * already pushes their own copy on save</b> — agenda's connector panel calls
   * {@code pushEvent} the moment the event is created, for the current user and
   * only for them ({@code AgendaConnector.vue}). That was the one path that
   * worked before this method existed, and it is why the defect looked like a
   * feature that worked: whoever created an event saw a copy appear, and every
   * other attendee got nothing.
   *
   * <p>
   * Writing it here as well is not a harmless duplicate. Measured on a rig
   * (2026-08-27): both writers mint the same stable iCalendar UID, both PUT the
   * same object, and the second one to record its mapping row dies on
   * {@code UQ_CALDAV_OBJECT_SYNC_UID} — a constraint violation logged as an
   * ERROR with a stack trace, on the ordinary path, on every creation. The
   * check that a copy already exists cannot prevent it: both writers read "no
   * copy" before either had written one.
   *
   * <p>
   * What this costs, said out loud: an event created <b>without</b> a browser —
   * through the REST API or an MCP tool — leaves its author's own copy to the
   * background seeding pass, which writes it on the next sweep of that account.
   * That is exactly what happened to them before this method existed, so it is
   * a gap this does not close rather than one it opens; closing it means moving
   * the author's copy off the browser and onto the server, which is a change to
   * how agenda's connector panel saves, not to this listener.
   *
   * <p>
   * That sentence was a promise the seeding pass could not keep until
   * EXO-89796, and this is the one line worth reading twice, because it is how
   * the defect stayed invisible: the pass refused own-calendar events outright
   * and listed only events with an attendee row, so an author's own copy was
   * left to a pass that would never write it. The pass now asks after the
   * events of the user's own calendars too, and the promise holds.
   *
   * @param eventId the agenda event that was just created
   * @param authorIdentityId whoever created it, as the broadcast names them;
   *          skipped, and 0 or less means the broadcast named nobody and
   *          everybody invited is written to
   * @return how many copies were written
   */
  public int propagateCreation(long eventId, long authorIdentityId) {
    if (eventId <= 0) {
      return 0;
    }
    Set<Long> invited = invitedUsers(eventId);
    invited.remove(authorIdentityId);
    if (invited.isEmpty()) {
      LOG.debug("Event {} was created with nobody this add-on has to copy it to; nothing is written", eventId);
      return 0;
    }
    int written = 0;
    for (Long userIdentityId : invited) {
      if (seedOne(userIdentityId, eventId)) {
        written++;
      }
    }
    LOG.info("Event {} was created; a copy was written for {} of the {} invited user(s) its author does not cover",
             eventId,
             written,
             invited.size());
    return written;
  }

  /**
   * Everybody invited to a meeting who is a user, once each.
   *
   * <p>
   * Read from agenda at the moment this runs rather than from the broadcast:
   * agenda saves the attendees before it broadcasts the creation
   * ({@code AgendaEventServiceImpl.createEvent} saves them, then broadcasts),
   * so the list is complete by the time this is asked — and asking agenda
   * rather than trusting a payload keeps that true if the order ever changes.
   *
   * @param eventId the agenda event
   * @return the identities of the invited users, in the order agenda lists them
   */
  private Set<Long> invitedUsers(long eventId) {
    Set<Long> invited = new LinkedHashSet<>();
    EventAttendeeList attendeeList;
    try {
      attendeeList = agendaEventAttendeeService.getEventAttendees(eventId);
    } catch (Exception | LinkageError e) {
      LOG.warn("The attendees of the new event {} could not be listed; no copy is written for it", eventId, e);
      return invited;
    }
    List<EventAttendee> attendees = attendeeList == null ? null : attendeeList.getEventAttendees();
    if (attendees == null) {
      return invited;
    }
    for (EventAttendee attendee : attendees) {
      long identityId = attendee.getIdentityId();
      if (identityId > 0 && isUser(identityId)) {
        invited.add(identityId);
      }
    }
    return invited;
  }

  /**
   * Whether an attendee identity is a person rather than a space.
   *
   * @param identityId the social identity an attendee row names
   * @return true when it is a user identity
   */
  private boolean isUser(long identityId) {
    try {
      Identity identity = identityManager.getIdentity(String.valueOf(identityId));
      return identity != null && identity.isUser();
    } catch (Exception | LinkageError e) {
      LOG.debug("Identity {} could not be read; it is not offered a copy of the new meeting", identityId, e);
      return false;
    }
  }

  /**
   * Writes one invited user's copy, absorbing whatever that one account does to
   * it.
   *
   * <p>
   * Contained here for the same reason {@link #rewriteOne} is: an account being
   * down, full or mid-password-change is an ordinary Tuesday, and it is not a
   * reason the other attendees never see the meeting.
   *
   * @param userIdentityId the invited user
   * @param eventId the agenda event just created
   * @return true when a copy was written
   */
  private boolean seedOne(long userIdentityId, long eventId) {
    try {
      return caldavPendingInvitationService.seedMeeting(userIdentityId, eventId);
    } catch (Exception | LinkageError e) {
      LOG.warn("The new event {} could not be copied into the calendar of user {}; the seeding pass will retry",
               eventId,
               userIdentityId,
               e);
      return false;
    }
  }

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
   * <h4>An edit can make a meeting stop deserving a copy</h4>
   *
   * <p>
   * Adding a second date option to a confirmed meeting turns it back into a
   * date poll — {@code AgendaEventServiceImpl.updateEvent} calls
   * {@code checkAndComputeDateOptions}, which sets {@code TENTATIVE} and
   * stretches the event over the envelope of the options — and that is
   * reachable from the REST API, not only from the poll screen. So the copies
   * written while it was a meeting have to be <b>retired</b>, not rewritten:
   * rewriting one would replace a real meeting on somebody's calendar with the
   * multi-day block {@link CaldavCopyPolicy} exists to keep off it. Refusing
   * new writes in the push core is what stops a poll acquiring a copy; this is
   * what takes away the copy it already had.
   *
   * <p>
   * Asked <b>before</b> {@link #worthCarrying}, deliberately. What agenda
   * broadcasts when date options are added is
   * {@code DATE_OPTION_CREATED} — which is on the invisible list, because for a
   * poll that stays a poll an option inside the envelope changes nothing a copy
   * states. Asking the cheap question first would therefore have made the one
   * edit that must retire a copy the one edit this method returns early from,
   * and the retirement would never have run. The status is read from the event
   * itself rather than inferred from the modification set for exactly that
   * reason: the set describes what moved, and what matters here is where the
   * event ended up.
   *
   * <h4>And a confirmation can make one start deserving it</h4>
   *
   * <p>
   * The other direction is not symmetrical and cannot be. When a poll is
   * confirmed, <b>nobody holds a copy</b> — none was ever written — so there is
   * nothing for a rewrite to reach, and the loop below would count zero and
   * stop. The confirmation is therefore seeded like a creation. See
   * {@link #seedConfirmedPoll(long, Event)} for what it is keyed on, which is
   * the one detail in this whole change that fails silently if it is got wrong.
   *
   * @param eventId the agenda event that was edited
   * @param modificationTypes what agenda says moved, as the broadcast carried
   *          it; null or empty is treated as "something did"
   * @return how many copies were rewritten, removed, or newly written
   */
  public int propagateUpdate(long eventId, Set<AgendaEventModificationType> modificationTypes) {
    if (eventId <= 0) {
      return 0;
    }
    Event event = readEvent(eventId);
    if (!caldavCopyPolicy.mayHoldCopy(event)) {
      return retireCopies(eventId);
    }
    if (!worthCarrying(modificationTypes)) {
      LOG.debug("Event {} changed only in ways a calendar copy cannot show ({}); no copy is rewritten",
                eventId,
                modificationTypes);
      return 0;
    }
    Map<Long, ObjectSync> holders = holdersOf(eventId, true);
    if (holders.isEmpty()) {
      if (modificationTypes != null
          && modificationTypes.contains(AgendaEventModificationType.SWITCHED_DATE_POLL_TO_EVENT)) {
        return seedConfirmedPoll(eventId, event);
      }
      LOG.debug("Event {} was edited but nobody holds a copy of it; nothing to carry out", eventId);
      return 0;
    }
    // Every obligation first, then every write. Not interleaved, deliberately:
    // a thread killed at the third of fifty attendees must leave the other
    // forty-seven recorded as owed, and interleaving would leave them looking
    // as though nobody had ever intended to write to them.
    for (Map.Entry<Long, ObjectSync> holder : holders.entrySet()) {
      owe(holder.getValue(), holder.getKey(), PendingPushKind.REWRITE, eventId);
    }
    int carried = 0;
    for (Map.Entry<Long, ObjectSync> holder : holders.entrySet()) {
      if (rewriteOne(holder.getKey(), eventId, holder.getValue().getId())) {
        carried++;
      }
    }
    LOG.info("Event {} was edited; its copy was rewritten for {} of {} holders", eventId, carried, holders.size());
    return carried;
  }

  /**
   * The event as agenda holds it now, or null when it cannot be read.
   *
   * <p>
   * Read without a viewer, like the seeding pass does: this is asked to decide
   * what happens to <b>other people's</b> copies, so there is no one user whose
   * visibility the answer should depend on, and the alternative — reading it
   * once per holder — would ask agenda the same question fifty times to get
   * fifty identical answers.
   *
   * <p>
   * A failure to read is not allowed to break the fan-out, and answers null
   * rather than throwing: the caller treats null as "this says nothing", which
   * leaves an edit to be carried as an ordinary edit. That is the safe
   * direction — the mirror sweep retires a poll's copy on its own pass, so an
   * unreadable event costs a wasted rewrite and a few minutes, where the other
   * direction would retire copies of live meetings whenever agenda hiccupped.
   *
   * @param eventId the agenda event
   * @return the event, or null when agenda could not answer for it
   */
  private Event readEvent(long eventId) {
    try {
      return agendaEventService.getEventById(eventId);
    } catch (Exception | LinkageError e) {
      LOG.debug("Event {} could not be read; its copies are treated as an ordinary edit", eventId, e);
      return null;
    }
  }

  /**
   * Takes away every copy of an event that may no longer hold one.
   *
   * <p>
   * The same machinery a deletion uses, and on purpose: what the holder's
   * calendar has to end up with is identical — no entry — and the only
   * difference is that eXo still holds the event. So the obligation recorded is
   * a {@link PendingPushKind#REMOVE} like any other, settled by the same retry
   * and satisfied by the same {@link #removeOne} — which means a holder whose
   * server is down when a poll is created out of a meeting has the copy taken
   * away on the next sweep instead of never.
   *
   * <p>
   * The series is resolved, as it is for an edit: an override and its series
   * share one object, so an override that became a poll names a copy filed
   * under the series' identity.
   *
   * <p>
   * A tombstone is not written here, unlike a cancellation. A cancelled meeting
   * still happened and its attendees have to be told it is off; a meeting that
   * became a poll is being <i>re-planned</i>, and the people invited are about
   * to be asked to vote on it in eXo. Leaving {@code STATUS:CANCELLED} on their
   * calendars would announce a cancellation that is not one.
   *
   * @param eventId the agenda event whose copies must go
   * @return how many copies were removed
   */
  private int retireCopies(long eventId) {
    Map<Long, ObjectSync> holders = holdersOf(eventId, true);
    if (holders.isEmpty()) {
      LOG.debug("Event {} may hold no copy, and nobody holds one of it; nothing to retire", eventId);
      return 0;
    }
    // Recorded before any of them is attempted, for the reason propagateUpdate
    // and propagateDeletion both give: a thread that dies at the third of fifty
    // holders must leave the other forty-seven owed a removal.
    for (Map.Entry<Long, ObjectSync> holder : holders.entrySet()) {
      owe(holder.getValue(), holder.getKey(), PendingPushKind.REMOVE, null);
    }
    int removed = 0;
    for (Map.Entry<Long, ObjectSync> holder : holders.entrySet()) {
      ObjectSync mapping = holder.getValue();
      if (removeOne(holder.getKey(), mapping.getIcsUid(), mapping.getId(), mapping.getRemoteHref())) {
        // At INFO and one line per copy, deliberately: this is the line an
        // administrator watching the first sweep after the deploy is looking
        // for, and a per-pass total would tell them how many without telling
        // them which.
        LOG.info("Event {} became a date poll; the copy held by user {} has been retired",
                 eventId,
                 holder.getKey());
        removed++;
      }
    }
    LOG.info("Event {} may hold no copy; it was retired for {} of {} holders", eventId, removed, holders.size());
    return removed;
  }

  /**
   * Writes the copies of a date poll that has just become a real meeting.
   *
   * <h2>Keyed on {@code SWITCHED_DATE_POLL_TO_EVENT}, and on nothing else</h2>
   *
   * <p>
   * This is the one line of this change that breaks silently if it is wrong, so
   * it is written down. Confirming a poll goes through
   * {@code AgendaEventServiceImpl.selectEventDateOption}, which sets the event
   * {@code CONFIRMED} and broadcasts exactly three modification types:
   * {@code UPDATED}, {@code DATE_OPTION_SELECTED} and
   * {@code SWITCHED_DATE_POLL_TO_EVENT}. <b>There is no
   * {@code STATUS_UPDATED}</b> — agenda has the constant, and does not put it
   * in this broadcast (verified on {@code origin/develop} of
   * {@code Meeds-io/agenda}, {@code AgendaEventServiceImpl}, the block ending
   * in {@code Utils.broadcastEvent(listenerService, POST_UPDATE_AGENDA_EVENT_EVENT, ...)}).
   * Keyed on {@code STATUS_UPDATED} this method would never run, no copy of a
   * confirmed poll would ever be written, and nothing anywhere would say so:
   * the feature would fail at exactly the moment it starts mattering.
   * {@code UPDATED} cannot serve either — it accompanies every edit and is on
   * the invisible list.
   *
   * <h2>Why it also closes a gap that predates this change</h2>
   *
   * <p>
   * Before this, a confirmed poll reached its attendees' calendars only through
   * the background seeding pass — so a poll confirmed for a date beyond that
   * pass's upcoming window, or one confirmed for a date in the past, reached
   * nobody at all, ever. Seeding here is window-free, because it is told about
   * one event rather than looking for candidates.
   *
   * <p>
   * The author of the confirmation is skipped, for the same reason
   * {@link #propagateCreation(long, long)} skips a creation's author: their own
   * browser pushes their copy on save, and both writers minting the same UID
   * means the second one to record its mapping row dies on the uniqueness
   * constraint — an ERROR with a stack trace on an ordinary path. Their
   * identity is read from the event agenda has just stored, which carries it:
   * {@code selectEventDateOption} does {@code event.setModifierId(userIdentityId)}
   * before saving. What that costs is the same cost stated there: a
   * confirmation made without a browser leaves its author's own copy to the
   * seeding pass, which now writes it because the event is {@code CONFIRMED}.
   *
   * @param eventId the agenda event that has just stopped being a poll
   * @param event the event as it now stands, never null here — the caller has
   *          already asked the policy about it
   * @return how many copies were written
   */
  private int seedConfirmedPoll(long eventId, Event event) {
    long confirmedBy = event == null ? 0L : event.getModifierId();
    LOG.info("Event {} was a date poll and has been confirmed; its copies are written now", eventId);
    return propagateCreation(eventId, confirmedBy);
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
    // Recorded before any of them is attempted, for the reason propagateUpdate
    // gives — and it matters more here: a removal that is not carried out has
    // no other safety net at all, so this record is the only thing standing
    // between a failure and a meeting that stays in somebody's calendar after
    // it was destroyed.
    for (Map.Entry<Long, ObjectSync> holder : holders.entrySet()) {
      owe(holder.getValue(), holder.getKey(), PendingPushKind.REMOVE, null);
    }
    int removed = 0;
    for (Map.Entry<Long, ObjectSync> holder : holders.entrySet()) {
      ObjectSync mapping = holder.getValue();
      if (removeOne(holder.getKey(), mapping.getIcsUid(), mapping.getId(), mapping.getRemoteHref())) {
        removed++;
      }
    }
    LOG.info("Event {} was deleted; its copy was removed for {} of {} holders", eventId, removed, holders.size());
    return removed;
  }

  /**
   * Carries one attendee's answer out to every <b>other</b> attendee's copy of
   * the same meeting.
   *
   * <h4>The half of answering that stopped at the answerer (EXO-89868)</h4>
   *
   * <p>
   * {@code CaldavPushService.pushAnswer} opens by reading the <b>answerer's
   * own</b> account and rewrites the copy on it. So an acceptance reached the
   * accepter's own phone and nothing else: the organiser's copy, and every
   * other attendee's, went on displaying the answer it last carried. eXo's own
   * screens were right throughout, which is what made it invisible — nothing
   * in the product disagreed out loud, and the organiser read a stale RSVP in
   * the client they actually live in.
   *
   * <p>
   * This is the fan-out, and it changes the mirror's rule from "your copy
   * reflects your answer" to "every attendee's copy reflects everyone's
   * answers". {@code pushAnswer} is left exactly as it was and still writes
   * the answerer's own copy; this writes the others.
   *
   * <h4>Whose copies, and whose name on them</h4>
   *
   * <p>
   * The holders are read from the mapping table, never from agenda's attendee
   * list — the same guard {@link #propagateUpdate} rests on: answering a
   * meeting must not hand a copy to somebody who has never had one, or the
   * first person to accept anything would push a year of meetings at every
   * colleague who has since connected an account. Tombstones are skipped by
   * {@link #holdersOf} for the same reason it always skips them.
   *
   * <p>
   * The answerer is taken out of that set, and not as an optimisation. Their
   * own copy is {@code pushAnswer}'s, both run off the same listener, and two
   * conditional writes racing for the same object would make one of them fail
   * its If-Match — a self-inflicted conflict reported as a divergence.
   *
   * <p>
   * On each of those copies it is the <b>answerer's</b> addresses that decide
   * which ATTENDEE line is rewritten, and that crossing over is the whole
   * mechanism. A copy written for Bob spells Bob by the address his own CalDAV
   * account answers to and everybody else — Alice included — by their eXo
   * profile address, so Alice's answer is found on Bob's copy under Alice's
   * profile address. Both of hers are offered anyway
   * ({@code addressesNaming}), because being handed one address and told it is
   * <i>the</i> one is how this propagation silently matched nothing on a live
   * rig once already.
   *
   * <h4>Why NEEDS-ACTION is not fanned out</h4>
   *
   * <p>
   * Because something else already carries it, N times over. The reset to
   * NEEDS-ACTION that follows a material edit is broadcast once per attendee,
   * and the edit that caused it has already been carried to every copy by
   * {@link #propagateUpdate} — which renders every attendee's current answer,
   * resets included. Fanning out on each reset as well would turn one edit of
   * an eight-person meeting into fifty-six extra conditional writes saying
   * what fifty-six writes have just said.
   *
   * <p>
   * The skip is on the <b>PARTSTAT this would write</b>, not on the response
   * name, and that is deliberately wider than the rule it implements.
   * {@code IcsText.partStat} maps anything it does not recognise to
   * {@code NEEDS-ACTION}, so a response agenda grows tomorrow and this add-on
   * has no word for would otherwise fan a reset out to every copy in the name
   * of an answer nobody gave. Skipping on the token covers both, and can only
   * ever decline to write a reset.
   *
   * <p>
   * <b>An answer propagated onto a series' occurrences is not a reset and is
   * not skipped.</b> Agenda saves-without-sending there too, but what it saves
   * is a real answer, and the copies of the other attendees have to learn it
   * like any other.
   *
   * <h4>Why an organiser's own answer is not fanned out either</h4>
   *
   * <p>
   * Because no copy carries it. A copy names whoever called the meeting on its
   * ORGANIZER line and never on an ATTENDEE line, so there is no participation
   * status of theirs anywhere for a targeted rewrite to find — and, worse, the
   * {@code NOT_NAMED} that resulted queued a full rewrite per holder that
   * could never satisfy the obligation. See {@link #isOrganizerOf} for what
   * was measured on the rig and for the product question this deliberately
   * leaves open.
   *
   * <h4>What is written down before anything is written out</h4>
   *
   * <p>
   * An obligation per holder, before the first network call, exactly as
   * {@link #propagateUpdate} records them and for the same reason: a thread
   * killed at the third of fifty holders must leave the other forty-seven
   * recorded as owed. It is a {@link PendingPushKind#REWRITE} on the same
   * table with no schema change, and it is the right kind rather than a
   * near-enough one — the retry renders the event <i>as it now stands</i>, and
   * the event as it now stands carries this answer. A copy that could not be
   * reached by the targeted write therefore converges through a full rewrite,
   * which is slower and blunter and is the fallback, not the path.
   *
   * @param eventId the agenda event answered, master or occurrence
   * @param answererIdentityId identity of the user whose answer was recorded;
   *          their own copy is not written here
   * @param response the answer as agenda holds it, e.g. {@code ACCEPTED}
   * @return how many other attendees' copies now carry the answer
   */
  public int propagateAnswer(long eventId, long answererIdentityId, String response) {
    if (eventId <= 0 || answererIdentityId <= 0 || StringUtils.isBlank(response)) {
      return 0;
    }
    String partStat = IcsText.partStat(response);
    if (IcsText.NEEDS_ACTION.equals(partStat)) {
      LOG.debug("The answer {} of user {} to event {} is a reset; the edit that caused it is carried to every copy already",
                response,
                answererIdentityId,
                eventId);
      return 0;
    }
    if (isOrganizerOf(eventId, answererIdentityId)) {
      LOG.debug("User {} organizes event {}; their answer reaches no copy, because a copy names them as its ORGANIZER"
          + " and never as an ATTENDEE, so there is no participation status of theirs on one to rewrite",
                answererIdentityId,
                eventId);
      return 0;
    }
    List<String> addresses = caldavPushService.addressesNaming(answererIdentityId);
    if (addresses.isEmpty()) {
      // No address means this add-on wrote no ATTENDEE line for them on
      // anybody's copy — the mapper leaves off whoever has no visible address
      // — so there is nothing on any copy a targeted rewrite could find, and a
      // full rewrite would not put a line there either. Recording obligations
      // for that would queue a rewrite per holder that could never satisfy
      // them. Said at WARN because it is a state of the profile somebody can
      // repair, not a user simply not using the feature.
      LOG.warn("The answer of user {} to event {} reaches no other copy: no address names them on one", answererIdentityId, eventId);
      return 0;
    }
    Map<Long, ObjectSync> holders = holdersOf(eventId, true);
    holders.remove(answererIdentityId);
    if (holders.isEmpty()) {
      LOG.debug("User {} answered event {}, but nobody else holds a copy of it; nothing to carry out",
                answererIdentityId,
                eventId);
      return 0;
    }
    // Every obligation first, then every write, for the reason propagateUpdate
    // states: a thread that dies at the third of fifty holders must leave the
    // other forty-seven recorded as owed, and interleaving would leave them
    // looking as though nobody had ever intended to write to them.
    for (Map.Entry<Long, ObjectSync> holder : holders.entrySet()) {
      owe(holder.getValue(), holder.getKey(), PendingPushKind.REWRITE, eventId);
    }
    int carried = 0;
    for (Map.Entry<Long, ObjectSync> holder : holders.entrySet()) {
      if (answerOne(holder.getKey(), holder.getValue(), addresses, eventId, answererIdentityId, response)) {
        carried++;
      }
    }
    LOG.info("User {} answered event {}; the answer was written onto the copy of {} of {} other holders",
             answererIdentityId,
             eventId,
             carried,
             holders.size());
    return carried;
  }

  /**
   * Whether this user is the person a copy of this event names as its
   * ORGANIZER.
   *
   * <h2>Why the fan-out has to ask (EXO-89868, found on the rig)</h2>
   *
   * <p>
   * Because a copy never carries an organiser's participation status, so there
   * is nothing on one for the fan-out to rewrite — structurally, on every
   * holder's copy, by design.
   * {@link org.exoplatform.caldav.ics.IcsWriter}'s {@code guests} excludes
   * whoever heads the ORGANIZER line from the ATTENDEE lines (EXO-89768: a
   * server whose model holds an organizer and a list of attendees that
   * excludes them silently drops the duplicate, and the repair loop that
   * followed could never close). In eXo they <i>are</i> an attendee and their
   * answer is recorded like anybody's; the copy simply never says so.
   *
   * <p>
   * Measured, not reasoned. On the rig, root organised event 1040 and
   * answered it, and every holder's copy refused the write with "it names none
   * of [root's address], so there is no participation status of theirs to
   * rewrite".
   *
   * <p>
   * <b>And it is not benign, which is why this is a skip rather than a
   * shrug.</b> That refusal is {@code NOT_NAMED}, which deliberately leaves the
   * obligation standing so that a full rewrite can put a missing line there.
   * No full rewrite can put <i>this</i> line there — the render is what omits
   * it — so every organiser's answer scheduled a doomed
   * {@link #retryOwedPushes} against every other holder's copy, up to the
   * bound, on the one path that can destroy an unread answer. Refusing here
   * costs an agenda read and buys back all of it.
   *
   * <h2>What this deliberately does not fix</h2>
   *
   * <p>
   * Whether a copy <i>should</i> name an attending organiser on both lines.
   * RFC 5545 expects both, and Google, Outlook and macOS all emit both, so
   * there is a real case that the render is what is wrong here. That is a
   * product decision Benjamin is keeping apart from this change, and it does
   * not belong to the fan-out either way: if it is ever taken, it is
   * {@code IcsWriter.guests} that changes, and <b>this method is what comes
   * out</b> — the skip exists only for as long as the render omits the line.
   *
   * <h2>Decided the way the mapper decides it</h2>
   *
   * <p>
   * On {@code getCreatorId()}, by identity, which is what
   * {@code AgendaEventIcsMapper.organizerOf} and
   * {@code toIcsEvent}'s {@code organizerIsPusher} both use. Emphatically
   * <b>not</b> by comparing addresses, although {@code guests} compares
   * addresses: the creator's own line is spelled with the address their CalDAV
   * account answers to and their ORGANIZER line with their eXo profile
   * address, so an address comparison answers differently for the same person
   * depending on whose copy is being looked at. A skip keyed on anything but
   * the render's own predicate would drift from the render it mirrors, which
   * is the matched-pair failure this delivery has already paid for more than
   * once.
   *
   * <p>
   * Read on the event as given rather than on its series, for the same reason:
   * {@code toIcsEvent} is handed the occurrence when an occurrence is pushed
   * and reads {@code getCreatorId()} off that, so an override whose creator
   * differs from its series' must be judged by its own.
   *
   * <p>
   * An event that cannot be read answers false — the fan-out goes ahead. The
   * bias {@link #readEvent} already takes: proceeding costs at worst the
   * doomed retries this method exists to prevent, for as long as agenda cannot
   * answer, while refusing on an unreadable event would silently drop the
   * answers of people who are not organisers at all.
   *
   * @param eventId the agenda event answered, master or occurrence
   * @param answererIdentityId identity of the user whose answer was recorded
   * @return true when a copy of this event would name them as its organizer
   */
  private boolean isOrganizerOf(long eventId, long answererIdentityId) {
    Event event = readEvent(eventId);
    return event != null && event.getCreatorId() == answererIdentityId;
  }

  /**
   * Writes one answer onto one other attendee's copy, absorbing whatever that
   * one account does to it.
   *
   * <p>
   * Every failure is contained here for the reason {@link #rewriteOne}
   * contains its own: fifty attendees is fifty accounts on as many servers,
   * and one of them being down is not a reason the other forty-nine keep a
   * stale RSVP. {@code LinkageError} as well as {@code Exception}, because one
   * escaped a {@code catch (RuntimeException)} on this very code path once and
   * took a whole sweep with it.
   *
   * <p>
   * The outcomes divide three ways rather than two, and the middle one is what
   * a boolean would have got wrong. A copy this write corrected and a copy
   * that already carried the answer are both settled — the second especially,
   * since leaving it owed would send the sweep to rewrite a correct copy in
   * full, which is the one operation this design avoids because it can destroy
   * an answer nothing has read yet. A copy that does not name the answerer, or
   * that could not be written to at all, stays owed and converges through that
   * full rewrite, which here is the fallback rather than the path.
   *
   * <p>
   * A CONFLICT settles rather than retries, the same rule
   * {@link #rewriteOne} follows: a conflict means the server's version moved
   * away from the one eXo recorded, which is precisely the gate the
   * verification pass opens on, and retrying would fight the same conditional
   * write to the same refusal.
   *
   * @param holderIdentityId whose copy is written to
   * @param copy the mapping row naming that copy
   * @param answererAddresses every address the copy might name the answerer by
   * @param eventId the agenda event, for the log
   * @param answererIdentityId who answered, for the log
   * @param response the answer, for the log
   * @return true when the copy now carries the answer
   */
  private boolean answerOne(long holderIdentityId,
                            ObjectSync copy,
                            List<String> answererAddresses,
                            long eventId,
                            long answererIdentityId,
                            String response) {
    try {
      CaldavPushService.AnswerOutcome outcome = caldavPushService.pushAnswerOnto(holderIdentityId,
                                                                                copy,
                                                                                answererAddresses,
                                                                                response);
      if (outcome.settles()) {
        settled(copy.getId());
      }
      return outcome == CaldavPushService.AnswerOutcome.WRITTEN;
    } catch (CaldavPushException e) {
      if (CaldavPushService.CONFLICT.equals(e.getCode())) {
        LOG.debug("The copy of event {} held by user {} changed under the answer of user {};"
            + " the verification pass will reconcile it",
                  eventId,
                  holderIdentityId,
                  answererIdentityId,
                  e);
        settled(copy.getId());
      } else if (CaldavPushService.isKnownState(e.getCode())) {
        // A state of the holder rather than a failure of this write: they have
        // no connected account, or none that names a destination. Recorded
        // without a trace and without the word failure. The obligation still
        // stands, so the day they connect the sweep writes their copy.
        LOG.debug("The answer of user {} to event {} is not carried to the copy of user {}: {} ({})",
                  answererIdentityId,
                  eventId,
                  holderIdentityId,
                  e.getMessage(),
                  e.getCode());
      } else {
        LOG.warn("The answer of user {} to event {} could not be carried to the copy held by user {} ({});"
            + " it stays owed and is retried",
                 answererIdentityId,
                 eventId,
                 holderIdentityId,
                 e.getCode(),
                 e);
      }
      return false;
    } catch (Exception | LinkageError e) {
      LOG.warn("The answer of user {} to event {} could not be carried to the copy held by user {};"
          + " it stays owed and is retried",
               answererIdentityId,
               eventId,
               holderIdentityId,
               e);
      return false;
    }
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
   * <p>
   * <b>Contained is not the same as forgotten.</b> A failure here leaves the
   * obligation recorded against the copy standing, so {@link #retryOwedPushes}
   * comes back to it. This used to say the verification pass would retry, which
   * it could not: an edit that never reached the server does not move the
   * server's version, and an unmoved version is exactly what makes that pass
   * decide there is nothing to look at.
   *
   * @param userIdentityId the holder
   * @param eventId the agenda event to write again
   * @param objectSyncId the mapping row the copy is recorded under, null when
   *          the caller has no row to settle an obligation against
   * @return true when the copy was rewritten
   */
  private boolean rewriteOne(long userIdentityId, long eventId, Long objectSyncId) {
    try {
      boolean written = caldavPushService.pushAgendaEvent(userIdentityId, eventId) != null;
      if (written) {
        settled(objectSyncId);
      }
      return written;
    } catch (CaldavPushException e) {
      if (CaldavPushService.CONFLICT.equals(e.getCode())) {
        // Somebody wrote that object between the read and the write — very
        // often the editor's own browser, which still pushes their own copy on
        // save. Not an incident, and the one failure the verification pass
        // genuinely does cover: a conflict means the server's version moved
        // away from the one eXo recorded, which is precisely the gate that pass
        // opens on. So the obligation is cleared rather than retried — retrying
        // it would fight the same conditional write to the same refusal.
        LOG.debug("The copy of event {} held by user {} changed under the rewrite; the verification pass will reconcile it",
                  eventId,
                  userIdentityId,
                  e);
        settled(objectSyncId);
      } else if (CaldavPushService.isKnownState(e.getCode())) {
        // A state of the holder rather than a failure of this write: they have
        // no connected account, or none that names a destination. Retrying
        // cannot move it and nobody but they can, so it is recorded without a
        // trace and without the word failure. The obligation still stands —
        // this branch changes what is printed, not what is owed, and the day
        // they connect the sweep writes the copy.
        LOG.debug("The edit of event {} is not carried to the copy of user {}: {} ({})",
                  eventId,
                  userIdentityId,
                  e.getMessage(),
                  e.getCode());
      } else {
        LOG.warn("The edit of event {} could not be carried to the copy held by user {} ({}); it stays owed and is retried",
                 eventId,
                 userIdentityId,
                 e.getCode(),
                 e);
      }
      return false;
    } catch (Exception | LinkageError e) {
      LOG.warn("The edit of event {} could not be carried to the copy held by user {}; it stays owed and is retried",
               eventId,
               userIdentityId,
               e);
      return false;
    }
  }

  /**
   * Removes one holder's copy, absorbing whatever that one account does to it.
   *
   * <p>
   * A failure leaves the obligation standing, and here that is the whole
   * safety net: the event is gone from eXo, so it renders to nothing, and the
   * verification pass refuses to conclude anything from an empty render.
   * Nothing but the record written before this ran will ever take that meeting
   * out of the attendee's calendar.
   *
   * @param userIdentityId the holder
   * @param icsUid the iCalendar identity of the object to remove, which is
   *          where a removal has to address it from — agenda no longer holds
   *          the event
   * @param objectSyncId the mapping row the copy is recorded under, null when
   *          the caller has no row to settle an obligation against
   * @param remoteHref where the copy sits, for the log only; may be null
   * @return true when the copy was removed
   */
  private boolean removeOne(long userIdentityId, String icsUid, Long objectSyncId, String remoteHref) {
    if (StringUtils.isBlank(icsUid)) {
      LOG.warn("Mapping {} of user {} carries no iCalendar identity; the copy it names cannot be removed",
               objectSyncId,
               userIdentityId);
      return false;
    }
    try {
      caldavPushService.deleteEvent(userIdentityId, icsUid);
      settled(objectSyncId);
      return true;
    } catch (Exception | LinkageError e) {
      if (e instanceof CaldavPushException refusal && CaldavPushService.isKnownState(refusal.getCode())) {
        // Nowhere to remove it from, because there is no account: a removal
        // owed to a user who never connected one is the same ordinary state as
        // a copy never written for them, and it recurs on every sweep.
        //
        // Tested on the caught throwable rather than caught in a clause of its
        // own, so that the one message this method has stays written once.
        LOG.debug("The copy of the deleted event held by user {} at {} is not removed: {} ({})",
                  userIdentityId,
                  remoteHref,
                  refusal.getMessage(),
                  refusal.getCode());
        return false;
      }
      LOG.warn("The copy of the deleted event held by user {} at {} could not be removed; it stays owed and is retried",
               userIdentityId,
               remoteHref,
               e);
      return false;
    }
  }

  /**
   * Writes down that eXo owes one copy a write, before trying to make it.
   *
   * <p>
   * Never allowed to break the fan-out. A bookkeeping row that cannot be
   * written is a copy that will not be retried — bad, and said out loud — but
   * it is not a reason the other forty-nine attendees keep a stale meeting,
   * and it is not a reason to skip the write this row was recorded for.
   *
   * @param mapping the mapping row whose copy is behind
   * @param userIdentityId whose calendar the copy sits in
   * @param kind whether the copy has to be written again or removed
   * @param localEventId the agenda event to render, null for a removal whose
   *          event no longer exists
   */
  private void owe(ObjectSync mapping, long userIdentityId, PendingPushKind kind, Long localEventId) {
    if (mapping.getId() == null || mapping.getId() <= 0) {
      // A mapping that has never been persisted names no copy anything could
      // be owed to. Nothing storage answers looks like this; a caller that
      // built one by hand would.
      return;
    }
    if (kind == PendingPushKind.REMOVE && StringUtils.isBlank(mapping.getIcsUid())) {
      // A removal addresses the object by its iCalendar identity and by
      // nothing else, so an obligation without one could never be satisfied —
      // it would be attempted, refused and abandoned, five times, to say what
      // removeOne says once. The copy is genuinely unreachable, and that is
      // reported there rather than queued here.
      return;
    }
    try {
      caldavPendingPushStorage.owe(mapping.getId(), userIdentityId, kind, localEventId, mapping.getIcsUid());
    } catch (Exception | LinkageError e) {
      LOG.warn("What eXo owes the copy of user {} at {} could not be recorded; a failed write there will not be retried",
               userIdentityId,
               mapping.getRemoteHref(),
               e);
    }
  }

  /**
   * Forgets what was owed to a copy, because the write landed.
   *
   * <p>
   * Contained for the same reason {@link #owe} is, and failing the other way
   * round: an obligation that cannot be cleared makes the next sweep write a
   * copy that is already correct, which costs three round trips and changes
   * nothing. That is the cheaper of the two mistakes, and the reason this is a
   * warning rather than a failure.
   *
   * @param objectSyncId the mapping row whose copy was written; null when the
   *          caller had no row, in which case there is nothing to forget
   */
  private void settled(Long objectSyncId) {
    if (objectSyncId == null || objectSyncId <= 0) {
      return;
    }
    try {
      caldavPendingPushStorage.settled(objectSyncId);
    } catch (Exception | LinkageError e) {
      LOG.warn("The write owed to mapping {} landed but could not be struck off; the next sweep writes it again",
               objectSyncId,
               e);
    }
  }

  /**
   * Makes the writes eXo owes one account's copies and has not managed to
   * make.
   *
   * <p>
   * The half of the fix that converges. The listeners are the only delivery
   * path there is, and a listener is a single attempt against somebody else's
   * server: this is what turns that attempt into an outcome. It reads what is
   * owed rather than deciding it — the obligation was recorded when the meeting
   * changed, by whoever was carrying the change out — so a copy nobody owes
   * anything to is not read, not rendered and not written, on this pass or any
   * other.
   *
   * <p>
   * <b>What it costs a converged account: one index lookup, answering nothing.</b>
   * That is the whole reason the obligation is a row in a table of its own
   * rather than a column on the mapping row or a comparison the sweep makes
   * for itself. Re-rendering every copy on every pass to find out which are
   * behind is the churn EXO-89716 and EXO-89756 removed, and re-introducing it
   * would be a worse defect than the one this fixes.
   *
   * <p>
   * Wider than the verification pass on purpose: that pass scopes to the MIRROR
   * pair, so a copy sitting in one of the user's own calendars was outside it
   * entirely. An obligation names the copy, whichever collection it lives in.
   *
   * @param userIdentityId whose calendar's copies are settled
   * @return how many owed writes landed this pass
   */
  public int retryOwedPushes(long userIdentityId) {
    List<PendingPush> owed;
    try {
      owed = caldavPendingPushStorage.attemptable(userIdentityId, maxPushAttempts, RETRY_BATCH);
    } catch (Exception | LinkageError e) {
      LOG.warn("What eXo owes the copies of user {} could not be read; nothing is retried this round", userIdentityId, e);
      return 0;
    }
    if (owed.isEmpty()) {
      return 0;
    }
    int landed = 0;
    for (PendingPush pending : owed) {
      if (settleOwed(userIdentityId, pending)) {
        landed++;
      }
    }
    LOG.info("User {} was owed {} calendar write(s); {} landed this round", userIdentityId, owed.size(), landed);
    return landed;
  }

  /**
   * How many of a user's calendar copies eXo holds a write for and has not
   * managed to make.
   *
   * <p>
   * <b>The number that makes the backstop visible.</b> Everything about the
   * obligation table was, until now, addressed to whoever reads the server
   * log: a copy that could not be written left a row, the sweep settled it
   * minutes later, and the person whose calendar it was had no way to tell
   * that anything was outstanding — the meeting simply was not there, which is
   * indistinguishable from the feature being broken.
   *
   * <p>
   * <b>What it deliberately does not count, and this is the part worth reading
   * twice.</b> A meeting that has just been <i>created</i> and could not be
   * copied is <b>not</b> in here, and cannot be: an obligation names a mapping
   * row, and a copy that was never written has none. Only a rewrite and a
   * removal are recorded, because only those address a copy that already
   * exists. What carries a missed creation is the seeding pass — a window
   * query, not a recorded obligation — so this number is honest about
   * <i>this</i> kind of lateness and silent about the other. The cure for the
   * other is not a queue to look at, it is
   * {@link CaldavSyncService#establishDestinations(long)}: give the copies
   * somewhere to go at the moment the account is connected, and there is no
   * lateness to narrate.
   *
   * <p>
   * Abandoned obligations are left out for the same reason the number exists:
   * it is read as "eXo is still trying". A copy eXo has given up on is a
   * different sentence, said to the administrator once, at WARN, by
   * {@link #refuse}.
   *
   * @param userIdentityId whose calendar's copies are counted
   * @return how many writes are outstanding, zero when the store cannot be
   *         read — an unreadable count is not evidence of a backlog, and
   *         claiming one that may not exist is worse than saying nothing
   */
  public long owedCopies(long userIdentityId) {
    if (userIdentityId <= 0) {
      return 0;
    }
    try {
      return caldavPendingPushStorage.owedAndStillTrying(userIdentityId, maxPushAttempts);
    } catch (Exception | LinkageError e) {
      LOG.debug("What eXo owes the copies of user {} could not be counted", userIdentityId, e);
      return 0;
    }
  }

  /**
   * Makes one owed write, and records the refusal when it does not land.
   *
   * <p>
   * The kind is read from the record rather than worked out here, and that is
   * the point of recording it: by the time this runs, a removal's event has
   * been destroyed and there is nothing left to look at that would say the copy
   * has to go rather than be rewritten.
   *
   * @param userIdentityId whose calendar the copy sits in
   * @param pending what is owed to it
   * @return true when the write landed
   */
  private boolean settleOwed(long userIdentityId, PendingPush pending) {
    boolean landed;
    if (pending.getKind() == PendingPushKind.REMOVE) {
      landed = removeOne(userIdentityId, pending.getIcsUid(), pending.getObjectSyncId(), null);
    } else if (pending.getLocalEventId() == null || pending.getLocalEventId() <= 0) {
      // A rewrite with no event to render is one nothing can ever satisfy.
      // Counted as a refusal rather than skipped, so the bound below takes it
      // off the pass instead of it being read for ever.
      LOG.warn("The copy of user {} is owed a rewrite that names no event; there is nothing to render for it",
               userIdentityId);
      landed = false;
    } else {
      landed = rewriteOne(userIdentityId, pending.getLocalEventId(), pending.getObjectSyncId());
    }
    if (!landed) {
      refuse(userIdentityId, pending);
    }
    return landed;
  }

  /**
   * Counts one more refusal against an owed write, and says so once when that
   * is the last one.
   *
   * <p>
   * Bounded because the alternative is arguing with a server that is not going
   * to change its mind, every five minutes, for as long as the account exists.
   * The record is left where it is rather than deleted: it is the only place
   * anybody can see that a copy is wrong and that eXo has stopped trying to
   * put it right.
   *
   * @param userIdentityId whose calendar the copy sits in, for the log
   * @param pending what is owed to it, carrying the count as it stood
   */
  private void refuse(long userIdentityId, PendingPush pending) {
    try {
      caldavPendingPushStorage.refused(pending.getId());
    } catch (Exception | LinkageError e) {
      LOG.warn("A refused write owed to mapping {} could not be counted; it will be attempted again",
               pending.getObjectSyncId(),
               e);
      return;
    }
    if (pending.getAttempts() + 1 >= maxPushAttempts) {
      // Once, on the attempt that reaches the bound, and never again: the next
      // pass does not read this record at all. Said at WARN because it is the
      // one state in this whole mechanism a human has to know about — a
      // calendar copy that is wrong and is going to stay wrong.
      LOG.warn("The copy of user {} has refused the write eXo owes it {} times; eXo stops trying to settle it",
               userIdentityId,
               maxPushAttempts);
    }
  }
}
