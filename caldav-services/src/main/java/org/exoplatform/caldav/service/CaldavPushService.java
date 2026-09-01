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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.RemoteEvent;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.MkCalendarResult;
import org.exoplatform.caldav.client.PutResult;
import org.exoplatform.caldav.ics.IcsMerger;
import org.exoplatform.caldav.ics.IcsText;
import org.exoplatform.caldav.ics.IcsWriter;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.MirrorTargetKind;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Writes eXo's space events into the user's remote calendar, server-side.
 *
 * <p>
 * This is the half of the migration that stops the browser from wielding the
 * user's CalDAV credentials to build and PUT iCalendar objects. What changes
 * for the user is nothing; what changes for us is that every write becomes
 * assertable — a test can now claim "the mirror calendar exists after we said
 * we created it", which was precisely the assertion nobody could write while
 * the work happened in a page.
 */
@Service
public class CaldavPushService {

  /** The collection eXo copies space events into, derived from this slug alone. */
  public static final String     MIRROR_COLLECTION_SLUG = "exo-meetings";

  /** How the collection presents itself in the user's own calendar client. */
  public static final String     MIRROR_DISPLAY_NAME    = "eXo Meetings";

  /** No CalDAV account is connected, so there is nowhere to write. */
  public static final String     NOT_CONNECTED          = "caldav.error.noCalendar";

  /** The stored credentials were rejected upstream. */
  public static final String     CREDENTIALS            = "caldav.error.credentials";

  /** Someone else changed the object since we last read it. */
  public static final String     CONFLICT               = "caldav.error.conflict";

  /** The write failed for a reason the user cannot act on individually. */
  public static final String     SAVE                   = "caldav.error.save";

  /** The server would not create a collection and no calendar could be adopted. */
  public static final String     CREATION_REFUSED       = "calendarCreationRefused";

  /**
   * The registration asks for the account's own default calendar, and the
   * account names none that its calendar home actually holds.
   *
   * <p>
   * Refused rather than worked around. Falling back to a calendar of eXo's own
   * making would put the copies exactly where the administrator had just
   * stopped asking for them, and picking one out of the listing is the guess
   * this whole path exists not to make.
   */
  public static final String     MAIN_CALENDAR_UNKNOWN  = "caldav.error.mainCalendarUnknown";

  /**
   * The name this add-on registers itself under as an agenda remote provider,
   * in caldav-configuration.xml. It has to match that declaration exactly:
   * agenda resolves the provider by name when it stores the mapping between
   * an eXo event and the object written for it.
   */
  public static final String     CONNECTOR_NAME = "agenda.caldavCalendar";

  /**
   * The codes that describe a state of the subject rather than a failure of the
   * attempt: retrying changes nothing, and only a person — the user, or their
   * administrator — clears them.
   *
   * <p>
   * Declared here, immediately under the vocabulary, because that is where the
   * next code is written and this is the question its author has to answer. A
   * code left out of both this set and {@link #FAILURE_CODES} is treated as a
   * failure, which is the safe default: a state nobody classified is exactly
   * the thing worth hearing about.
   */
  private static final Set<String> KNOWN_STATE_CODES = Set.of(NOT_CONNECTED, MAIN_CALENDAR_UNKNOWN);

  /**
   * The codes that describe an attempt that failed — a refused save, a
   * conflict, rejected credentials, a collection the server would not make.
   *
   * <p>
   * Listed rather than left to the default, so that {@link #isClassified} can
   * tell "declared a failure" from "never classified at all" and a test can
   * hold every code in the vocabulary to one answer or the other. Codes owned
   * by other services — {@code CaldavDeletionService.NOTHING_DELETED} — are
   * absent on purpose: they reach the default, which already says failure, and
   * naming them here would point this class at its own callers.
   */
  private static final Set<String> FAILURE_CODES     = Set.of(CREDENTIALS,
                                                              CONFLICT,
                                                              SAVE,
                                                              CREATION_REFUSED);

  private static final Log       LOG                    = ExoLogger.getLogger(CaldavPushService.class);

  @Autowired
  private CalDavClient           calDavClient;

  @Autowired
  private CaldavConnectorStorage caldavConnectorStorage;

  @Autowired
  private CaldavSyncStorage      caldavSyncStorage;

  @Autowired
  private IcsWriter              icsWriter;

  @Autowired
  private IcsMerger              icsMerger;

  @Autowired
  private AgendaEventService     agendaEventService;

  @Autowired
  private AgendaEventIcsMapper   agendaEventIcsMapper;

  @Autowired
  private AgendaRemoteEventService agendaRemoteEventService;

  @Autowired
  private AgendaCalendarService  agendaCalendarService;

  @Autowired
  private CaldavCopyPolicy       caldavCopyPolicy;

  /**
   * The registry the destination setting is read from. Injected here and
   * nowhere else on this path: the mapper, the sweep and the listeners all
   * reach a destination through this service, so one reader is one answer.
   */
  @Autowired
  private CaldavServerService    caldavServerService;

  /**
   * Whether a push refusal describes a persistent state of the subject rather
   * than a failure of the attempt.
   *
   * <p>
   * The distinction exists for the log and nothing else: a known state is
   * recorded at debug and without a trace, a failure at warn and with one. A
   * user who has never connected an account is an ordinary state of that user,
   * not an incident — printing eleven frames for it, once per attendee per
   * meeting, buries the copies that genuinely failed under the ones that were
   * never going to be made.
   *
   * <p>
   * <b>Anything unrecognised is a failure.</b> Not silence: a code nobody
   * classified is the one worth hearing about, and a default of "known state"
   * would turn this from a filter into a silencer the next time the vocabulary
   * grows.
   *
   * @param code the code a {@link CaldavPushException} carries, may be null
   * @return true when only a person can clear it and retrying cannot
   */
  public static boolean isKnownState(String code) {
    return code != null && KNOWN_STATE_CODES.contains(code);
  }

  /**
   * Whether a code was deliberately placed in one category or the other.
   *
   * <p>
   * Exists for the test that walks the vocabulary and holds every code
   * declared in this class to an explicit answer. Without it "failure" and
   * "never classified" are the same answer from {@link #isKnownState}, and a
   * code added without a thought would look classified.
   *
   * @param code the code a {@link CaldavPushException} carries, may be null
   * @return true when the code is named in either category
   */
  public static boolean isClassified(String code) {
    return code != null && (KNOWN_STATE_CODES.contains(code) || FAILURE_CODES.contains(code));
  }

  /**
   * The accounts whose main calendar could not be resolved and which have
   * already been said out loud, keyed by user and server.
   *
   * <p>
   * <b>Once per transition into the state, never once per pass.</b> This
   * question is asked on every push, on every sweep and on every render of the
   * settings screen; said each time it would be the very noise EXO-89798 is
   * removing, and said not at all it is what let a whole afternoon of copies go
   * to the wrong calendar with nothing in the log but a null in an admin JSON
   * endpoint. The entry is added when the state is entered and removed the
   * moment a main calendar does resolve, so a state that comes back is
   * announced again.
   *
   * <p>
   * In memory, for the reason the relocation pass keeps its own set that way:
   * it records something being wrong right now, not a fact about the account
   * worth carrying across a restart — and a restart saying it once more is the
   * right bias after a deploy.
   */
  private final Set<String>      unresolvedMainCalendars = ConcurrentHashMap.newKeySet();

  /**
   * Writes one event into the user's mirror calendar, creating the collection
   * and the mapping row if this is the first time.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param event the event to copy, with identities already resolved
   * @return the mapping row as it now stands
   * @throws CaldavPushException when the write cannot be carried out
   */
  public ObjectSync pushEvent(long userIdentityId, IcsEvent event) {
    return pushEvent(userIdentityId, event, null);
  }

  /**
   * Writes one event into the user's mirror calendar, recording which eXo
   * event the resulting object stands for.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param event the event to copy, with identities already resolved
   * @param localEventId the agenda event this object stands for, or null when
   *          the caller has none — the read half fills it in later
   * @return the mapping row as it now stands
   * @throws CaldavPushException when the write cannot be carried out
   */
  public ObjectSync pushEvent(long userIdentityId, IcsEvent event, Long localEventId) {
    return pushEvent(userIdentityId, event, localEventId, false);
  }

  /**
   * Writes one event into the user's mirror calendar, overwriting a drifted
   * copy or not.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param event the event to copy, with identities already resolved
   * @param localEventId the agenda event this object stands for, or null
   * @param overwrite true to write without the conditional guard
   * @return the mapping row as it now stands
   * @throws CaldavPushException when the write cannot be carried out
   */
  private ObjectSync pushEvent(long userIdentityId, IcsEvent event, Long localEventId, boolean overwrite) {
    CaldavUserSetting settings = connectedSettings(userIdentityId);
    CalDavEndpoint endpoint = endpointOf(settings);
    MirrorTarget mirror = ensureMirror(userIdentityId, settings, endpoint);
    return writeInto(userIdentityId, mirrorPair(userIdentityId, settings, mirror), event, localEventId, overwrite);
  }

  /**
   * The mapping row for an iCalendar UID, in whichever collection holds it.
   *
   * <p>
   * The mirror is searched first because most copies live there, but a
   * personal calendar's collection holds its own, and one UID belongs to at
   * most one of them.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   * @param icsUid the iCalendar UID looked for
   * @return the mapping row, or null when no collection holds it
   */
  private ObjectSync objectAnywhere(long userIdentityId, CaldavUserSetting settings, String icsUid) {
    CalendarSync mirror = existingMirrorPair(userIdentityId, settings);
    if (mirror != null) {
      ObjectSync inMirror = caldavSyncStorage.getObjectByUid(mirror.getId(), icsUid);
      if (inMirror != null) {
        return inMirror;
      }
    }
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    for (CalendarSync pair : caldavSyncStorage.getPairs(userIdentityId, serverId)) {
      // Objects.equals, not ==: these identifiers are Long, so == compares
      // references and answers false for every value a real database issues.
      // Written as ==, the mirror is simply searched a second time — harmless
      // today, and the same mistake that cost a deletion elsewhere.
      if (mirror != null && Objects.equals(pair.getId(), mirror.getId())) {
        continue;
      }
      ObjectSync known = caldavSyncStorage.getObjectByUid(pair.getId(), icsUid);
      if (known != null) {
        return known;
      }
    }
    return null;
  }

  /**
   * The event's calendar, when it is one of this user's own.
   *
   * <p>
   * The question the routing turns on. An event of the user's own calendar is
   * theirs, and belongs in that calendar's collection or nowhere; anything
   * else — a space meeting they attend — belongs in the mirror, which exists
   * precisely because a space calendar has no counterpart on a personal
   * account.
   *
   * @param event the agenda event being pushed
   * @param userIdentityId identity of the user
   * @return the calendar when the user owns it, null otherwise
   */
  private Calendar ownCalendarOf(Event event, long userIdentityId) {
    Calendar calendar = agendaCalendarService.getCalendarById(event.getCalendarId());
    return calendar != null && calendar.getOwnerId() == userIdentityId ? calendar : null;
  }

  /**
   * The collection bound to one of this user's own calendars.
   *
   * <p>
   * Answers null in two cases, and neither sends the event to the mirror —
   * that decision belongs to the caller now, which is what makes the refusal
   * enforceable rather than merely documented. Either the calendar carries no
   * anchor, so nothing stable identifies it; or the server refused to create
   * its collection, and outbound stays unavailable for that calendar until it
   * allows one.
   *
   * @param calendar one of the user's own calendars, already loaded
   * @param userIdentityId identity of the user
   * @return the bound collection, or null when there is none to write into
   */
  private CalendarSync personalPairFor(Calendar calendar, long userIdentityId) {
    if (StringUtils.isBlank(calendar.getSyncUid())) {
      return null;
    }
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    long serverId = settings == null || settings.getServerId() == null ? 0L : settings.getServerId();
    CalendarSync pair = caldavSyncStorage.getPairByLocalCalendar(userIdentityId, serverId, calendar.getSyncUid());
    if (pair == null || pair.getStatus() != CalendarSyncStatus.ACTIVE) {
      LOG.debug("Personal calendar {} has no usable collection; its events are not copied out", calendar.getSyncUid());
      return null;
    }
    return pair;
  }

  /**
   * Writes one event into a collection this user is already bound to.
   *
   * <p>
   * The same write for the space mirror and for a personal calendar: which
   * collection an event belongs in is the caller's decision, and everything
   * that follows — the conditional write, the merge, the mapping row — is the
   * same regardless. Keeping one path means a defect fixed for one is fixed
   * for both, which was not true while the browser held two of them.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param pair the collection to write into
   * @param event the event to copy, with identities already resolved
   * @param localEventId the agenda event this object stands for, or null
   * @return the mapping row as it now stands
   * @throws CaldavPushException when the write cannot be carried out
   */
  public ObjectSync writeInto(long userIdentityId, CalendarSync pair, IcsEvent event, Long localEventId) {
    return writeInto(userIdentityId, pair, event, localEventId, false);
  }

  /**
   * Writes one event into a collection, overwriting a drifted copy or not.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param pair the collection to write into
   * @param event the event to copy, with identities already resolved
   * @param localEventId the agenda event this object stands for, or null
   * @param overwrite true to write without the conditional guard, which only
   *          a repair may ask for
   * @return the mapping row as it now stands
   * @throws CaldavPushException when the write cannot be carried out
   */
  private ObjectSync writeInto(long userIdentityId,
                               CalendarSync pair,
                               IcsEvent event,
                               Long localEventId,
                               boolean overwrite) {
    CaldavUserSetting settings = connectedSettings(userIdentityId);
    CalDavEndpoint endpoint = endpointOf(settings);

    String ics = icsWriter.write(event);
    ObjectSync known = caldavSyncStorage.getObjectByUid(pair.getId(), event.getUid());
    // Only when the destination holds no mapping, which is a create — or a
    // move. The lookup above is scoped to one collection, so an event that has
    // just changed calendar looks new here while its old mapping, and its old
    // object, are still sitting in the calendar it left.
    ObjectSync leftBehind = known == null ? mappingElsewhere(userIdentityId, pair, event.getUid(), localEventId) : null;
    String href = known != null && StringUtils.isNotBlank(known.getRemoteHref()) ? known.getRemoteHref()
                                                                                : objectHref(pair.getRemoteHref(),
                                                                                             event.getUid());
    PutResult result = write(endpoint, settings, href, ics, known, overwrite);
    if (result.preconditionFailed()) {
      // Someone else wrote the object between our read and our write. Never
      // retried blindly: the whole point of the conditional write is that the
      // caller decides what to do about a concurrent edit.
      throw new CaldavPushException(CONFLICT, "The calendar object at " + href + " changed since it was last read");
    }
    ObjectSync mapping = known == null ? new ObjectSync() : known;
    mapping.setCalendarSyncId(pair.getId());
    mapping.setIcsUid(event.getUid());
    // Never cleared once set: a later push that does not know the event id —
    // a sweep, a repair — must not erase the link the first one established.
    if (localEventId != null) {
      mapping.setLocalEventId(localEventId);
    }
    mapping.setRemoteHref(href);
    mapping.setEtag(result.etag());
    mapping.setLastSync(new Date());
    ObjectSync saved = caldavSyncStorage.saveObject(mapping);
    // Last, and only once the destination holds the event: a move that failed
    // here would otherwise take the copy away without having written the new
    // one, which loses the user's event rather than tidying it.
    removeWhatWasLeftBehind(userIdentityId, leftBehind, href, endpoint, settings);
    return saved;
  }

  /**
   * The mapping of this event in some other collection of the same account.
   *
   * <p>
   * Answers "was this event somewhere else a moment ago?". A mapping is stored
   * per collection, so moving an event between calendars leaves one behind
   * rather than moving it, and the object it points at stays on the server —
   * where it goes on looking like a real event, on the user's phone, in the
   * calendar they took it out of.
   *
   * @param userIdentityId identity of the user
   * @param destination the binding the event is being written into
   * @param icsUid the event's iCalendar identifier
   * @param localEventId the agenda event being written, searched for before the
   *          iCalendar UID because it does not depend on a UID surviving the
   *          move — which this codebase has lost before. May be null when the
   *          caller does not know it.
   * @return the mapping it had elsewhere, or null when this is an ordinary
   *         first write
   */
  private ObjectSync mappingElsewhere(long userIdentityId, CalendarSync destination, String icsUid, Long localEventId) {
    String into = CaldavSyncStorage.canonicalHref(destination.getRemoteHref());
    for (CalendarSync other : caldavSyncStorage.getPairs(userIdentityId, destination.getServerId())) {
      if (Objects.equals(other.getId(), destination.getId())) {
        continue;
      }
      if (StringUtils.isNotBlank(into) && into.equals(CaldavSyncStorage.canonicalHref(other.getRemoteHref()))) {
        // A different pair, the same collection — which two pairs sharing a
        // calendar is exactly what pointing the mirror at an ordinary calendar
        // produces. "Elsewhere" then means the very place just written to, and
        // the row it hands back points at the same physical object: the
        // cleanup below would delete what the write has just put there. This
        // is not a move, and there is nothing left behind.
        continue;
      }
      // By the eXo event first, and the iCalendar UID only after. The UID is
      // supposed to survive a move — it is adopted from agenda's remote-event
      // mapping rather than minted afresh — but that mapping has been lost
      // before in this codebase, and when it is, the push mints a new UID and
      // a search by UID silently finds nothing. The event's own identifier
      // does not depend on any of that.
      ObjectSync elsewhere = localEventId == null ? null
                                                  : caldavSyncStorage.getObjectByEvent(other.getId(), localEventId);
      if (elsewhere == null && StringUtils.isNotBlank(icsUid)) {
        elsewhere = caldavSyncStorage.getObjectByUid(other.getId(), icsUid);
      }
      if (elsewhere != null && StringUtils.isNotBlank(elsewhere.getRemoteHref())) {
        return elsewhere;
      }
    }
    return null;
  }

  /**
   * Removes the copy an event left in the calendar it moved out of.
   *
   * <p>
   * Conditional on the ETag eXo recorded, so a copy someone has since edited on
   * another device is refused rather than destroyed — the user moved an event
   * between their own calendars, which is not consent to discard a change they
   * made elsewhere. A refusal leaves both the object and its mapping alone: a
   * stray eXo still knows about is a much smaller problem than one it has
   * forgotten, and the next write can try again.
   *
   * <p>
   * A delete addressing the path just written is refused outright, whatever
   * the mapping says. The two are the same object the moment two pairs share a
   * collection, and the sequence — write, then delete — would leave the user
   * with nothing where their event had just been put. The guard in
   * {@link #mappingElsewhere} already keeps such a row from being selected;
   * this one is the second lock, because the cost of the first one ever being
   * wrong is silent data loss and the cost of this check is a string compare.
   *
   * @param userIdentityId identity of the user, for the log
   * @param leftBehind the mapping in the old collection, may be null
   * @param writtenHref the path the event was just written to, never deleted
   * @param endpoint where the account lives
   * @param settings the connected account
   */
  private void removeWhatWasLeftBehind(long userIdentityId,
                                       ObjectSync leftBehind,
                                       String writtenHref,
                                       CalDavEndpoint endpoint,
                                       CaldavUserSetting settings) {
    if (leftBehind == null) {
      return;
    }
    // StringUtils.equals rather than written.equals: canonicalHref hands back
    // what it was given when that is blank, so `written` is null for a null
    // href. The isNotBlank guard already short-circuits before any dereference,
    // but an analyser that does not model commons-lang reads it as a possible
    // NPE; comparing through a null-safe call leaves nothing to misread.
    String written = CaldavSyncStorage.canonicalHref(writtenHref);
    String previous = CaldavSyncStorage.canonicalHref(leftBehind.getRemoteHref());
    if (StringUtils.isNotBlank(written) && StringUtils.equals(written, previous)) {
      LOG.debug("The copy said to be left behind at {} is the object just written there; it is kept", writtenHref);
      return;
    }
    try {
      calDavClient.deleteObject(endpoint,
                                leftBehind.getRemoteHref(),
                                leftBehind.getEtag(),
                                settings.getUsername(),
                                settings.getPassword());
    } catch (RuntimeException e) {
      LOG.warn("The copy user {} left at {} when moving the event could not be removed; it stays, and so does its mapping",
               userIdentityId,
               leftBehind.getRemoteHref(),
               e);
      return;
    }
    caldavSyncStorage.deleteObject(leftBehind.getId());
    LOG.info("The copy left at {} was removed after the event moved to another calendar", leftBehind.getRemoteHref());
  }

  /**
   * Copies one agenda event into the user's mirror calendar.
   *
   * <p>
   * The entry point the browser now calls instead of building iCalendar
   * itself: it hands over an event id, and every decision that used to happen
   * in the page — which identities are addressable, what the object looks
   * like, where it goes, whether the write is conditional — happens here.
   *
   * <p>
   * The event is read through agenda's own service, so its ACL applies: a user
   * who may not see an event cannot have it copied into their calendar by
   * asking for its id.
   *
   * <p>
   * It takes no link back into eXo. It used to, and the browser used to build
   * one and put it on the request — which meant only a browser push carried
   * one, so the link appeared once and the next repair stripped it. The link
   * is derived from the event by the mapper now, so every path renders the
   * same one (EXO-89751).
   *
   * <p>
   * A date poll is refused here and nothing is written for it — see
   * {@link CaldavCopyPolicy} for why, and the private core below for why this
   * is the only place that has to say so.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param eventId the agenda event to copy
   * @return the mapping row as it now stands, or null when nothing was written
   *         — the event is a date poll, or its calendar has no collection
   * @throws CaldavPushException when the event cannot be read or written
   */
  public ObjectSync pushAgendaEvent(long userIdentityId, long eventId) {
    return pushAgendaEvent(userIdentityId, eventId, false);
  }

  /**
   * Writes the copy of an agenda event again, over whatever now stands in its
   * place on the server.
   *
   * <p>
   * The entry point for repairs, and the only one that writes unconditionally.
   * A conditional write cannot repair anything: the condition is the stored
   * etag, and an object needs repairing exactly when the server's etag has
   * moved away from it — so the guarded path refuses every repair it is asked
   * to make. What makes the overwrite legitimate is that the caller has
   * already read the object, compared it against the eXo event, and decided
   * which copy wins.
   *
   * <p>
   * A repair is a write like any other, so it meets the same refusal: an event
   * that has become a date poll is not repaired into place, it is left for the
   * retirement that removes its copy.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param eventId the agenda event whose copy is rebuilt
   * @return the mapping row as it now stands, or null when nothing was written
   *         — the event is a date poll, or its calendar has no collection
   * @throws CaldavPushException when the event cannot be read or written
   */
  public ObjectSync rewriteAgendaEvent(long userIdentityId, long eventId) {
    return pushAgendaEvent(userIdentityId, eventId, true);
  }

  /**
   * The calendar object eXo would write for one event right now, without
   * writing it.
   *
   * <p>
   * This is the baseline the mirror verification compares a server's copy
   * against, and it is <b>generated rather than stored</b>. A recorded digest of
   * what was pushed — or of what the server was seen to store just after —
   * describes one moment and goes stale from the next: a server that finishes
   * settling the object after the read-back, an upgrade that changes its
   * serialisation, a deploy that changes ours. Regenerating costs one read of an
   * event already in cache and can never be out of date.
   *
   * <p>
   * The UID is taken from the mapping row rather than minted: this must have no
   * effect on anything. Asking {@code adoptOrMintUid} would record a new
   * identifier for an event that has one, on a path that is only supposed to be
   * looking.
   *
   * <p>
   * <b>It renders the link back into eXo, exactly as a push does.</b> That was
   * once the opposite: the link arrived on a push request, so this method had
   * none to render, and the comparison had to exempt the {@code URL} property
   * or every copy would have been judged altered once and then stripped of the
   * very link it was missing. Deriving the link from the event (EXO-89751) is
   * what makes this baseline the same document a push writes, property for
   * property, and what let the exemption go.
   *
   * @param userIdentityId identity of the user whose copy it is
   * @param eventId the agenda event to render
   * @param icsUid the iCalendar identifier the copy is written under
   * @return the object as text, or null when the event can no longer be read —
   *         it may have been deleted, or hidden from this user, and neither is
   *         something to conclude a rewrite from
   */
  public String renderAgendaEvent(long userIdentityId, long eventId, String icsUid) {
    Event event;
    try {
      // The event's own zone, for the all-day reason pushAgendaEvent records.
      event = agendaEventService.getEventById(eventId, null, userIdentityId);
    } catch (IllegalAccessException e) {
      LOG.debug("Event {} is not visible to user {}; nothing is rendered for it", eventId, userIdentityId, e);
      return null;
    }
    if (event == null || StringUtils.isBlank(icsUid)) {
      return null;
    }
    return icsWriter.write(agendaEventIcsMapper.toIcsEvent(event, icsUid, userIdentityId));
  }

  /**
   * Copies one agenda event into the user's account, overwriting a drifted
   * copy or not.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param eventId the agenda event to copy
   * @param overwrite true to write without the conditional guard, which only
   *          a repair may ask for
   * @return the mapping row as it now stands
   * @throws CaldavPushException when the event cannot be read or written
   */
  private ObjectSync pushAgendaEvent(long userIdentityId, long eventId, boolean overwrite) {
    Event event;
    try {
      // Read in the event's OWN zone, which is what a null argument asks
      // agenda for. Not UTC, and not a viewer's zone.
      //
      // For a timed event any zone gives the same instant, so it looks like a
      // free choice. It is not, because agenda treats an all-day event
      // differently: it re-anchors the covered days at midnight in whatever
      // zone is asked for. Ask for UTC and an all-day event of a user west of
      // Greenwich comes back starting at 20:00 the previous day — and the
      // copy is then written one day early, silently, for exactly the users
      // whose zone made it happen.
      event = agendaEventService.getEventById(eventId, null, userIdentityId);
    } catch (IllegalAccessException e) {
      throw new CaldavPushException(SAVE, "Event " + eventId + " is not visible to user " + userIdentityId, e);
    }
    if (event == null) {
      throw new CaldavPushException(SAVE, "Event " + eventId + " does not exist");
    }
    if (!caldavCopyPolicy.mayHoldCopy(event)) {
      // The one place a date poll is refused, and the reason it only needs to
      // be said once: every path that writes an event's copy arrives here.
      // The author's own browser (CaldavPushRest.push), the fan-out and the
      // seeding pass (through pushAgendaEvent), the mirror repair and the
      // retry of an owed write (through rewriteAgendaEvent, which is this
      // method with overwrite set) all read the event through this core. A
      // guard placed in any one of them would have been a guard the other
      // three walked past.
      //
      // Null, not an exception, because nothing failed. The caller asked for a
      // copy of something a calendar has no truthful way to show — see
      // CaldavCopyPolicy for what a poll looks like once it is written — and
      // "nothing was written" is the honest answer to that. Every caller
      // already handles it: the REST layer answers 204, the seeding pass reads
      // it as "not written", the fan-out as "not carried".
      LOG.debug("Event {} is a date poll; no copy of it is written into the account of user {}", eventId, userIdentityId);
      return null;
    }
    long seriesId = event.getParentId() > 0 ? event.getParentId() : event.getId();
    String icsUid = adoptOrMintUid(seriesId, userIdentityId);
    IcsEvent icsEvent = agendaEventIcsMapper.toIcsEvent(event, icsUid, userIdentityId);

    // Where an event goes is decided from the calendar it lives in, not from
    // the caller. An event of one of the user's own calendars belongs in that
    // calendar's own collection; anything else — a space event the user
    // attends — belongs in the mirror, which exists precisely because a space
    // calendar has no counterpart on a personal account.
    Calendar own = ownCalendarOf(event, userIdentityId);
    if (own != null) {
      CalendarSync personal = personalPairFor(own, userIdentityId);
      if (personal == null) {
        // Nothing to write into, and the mirror is not a consolation. A
        // personal event filed among the copies of space meetings is exactly
        // the mixing this refuses to do; outbound stays unavailable for this
        // calendar until it has a collection of its own. Answering null says
        // "nothing was pushed" without pretending it failed.
        LOG.debug("Calendar {} has no usable collection; event {} is not copied out",
                  event.getCalendarId(),
                  event.getId());
        return null;
      }
      return writeInto(userIdentityId, personal, icsEvent, event.getId(), overwrite);
    }
    return pushEvent(userIdentityId, icsEvent, event.getId(), overwrite);
  }

  /**
   * The iCalendar UID this event's object is written under: the one agenda
   * already recorded, or a new one recorded now.
   *
   * <p>
   * This is where events pushed before the migration are adopted rather than
   * duplicated. The browser stored a remote identifier on every event it
   * pushed, and that identifier is the UID of the object sitting on the
   * server. Minting a fresh one here would write a second object for every
   * event a migrated user already has — and since migrated users are exactly
   * the ones with events on the server, that is not an edge case but their
   * normal first run.
   *
   * @param seriesId the agenda event, or its parent for an occurrence — a
   *          series and its overrides share one UID
   * @param userIdentityId identity of the user
   * @return the UID to write under
   */
  private String adoptOrMintUid(long seriesId, long userIdentityId) {
    RemoteEvent known = agendaRemoteEventService.findRemoteEvent(seriesId, userIdentityId);
    if (known != null && StringUtils.isNotBlank(known.getRemoteId())) {
      return known.getRemoteId();
    }
    String minted = UUID.randomUUID().toString();
    RemoteEvent remoteEvent = new RemoteEvent();
    remoteEvent.setEventId(seriesId);
    remoteEvent.setIdentityId(userIdentityId);
    remoteEvent.setRemoteId(minted);
    // Naming the provider is what makes agenda keep this row. Without it —
    // and this connector left it unset — saveRemoteEvent reads the record as
    // an instruction to DELETE the mapping rather than store it, so the
    // identifier minted here was thrown away the moment it was handed over.
    // Every later push then found nothing, minted a fresh identifier, and
    // wrote a second object: an edit duplicated the meeting and orphaned the
    // original, and a delete looked for an identifier the server had never
    // seen. The provider itself already exists — this add-on registers it as
    // a RemoteProviderDefinitionPlugin — so naming it is all that was missing.
    remoteEvent.setRemoteProviderName(CONNECTOR_NAME);
    // Recorded before the write, not after: an interrupted push leaves an
    // identifier pointing at an object that may or may not exist, which the
    // next push reconciles. Recording it afterwards would leave a written
    // object nothing points at, which nothing ever reconciles.
    agendaRemoteEventService.saveRemoteEvent(seriesId, remoteEvent, userIdentityId);
    return minted;
  }

  /**
   * Removes one event's object from wherever this connector wrote it.
   *
   * <p>
   * Every collection the user has, not only the mirror. An event of one of
   * their own calendars is written into that calendar's collection, so a
   * removal that looked only in the mirror would find nothing and quietly
   * succeed — leaving the object on the server for ever, in the one place the
   * user is most likely to notice it. A copy is written in one place and has
   * to be removable from that same place.
   *
   * <p>
   * A deletion whose object is already gone is a success, not a failure: the
   * end state the caller asked for is the end state that holds.
   *
   * @param userIdentityId identity of the user
   * @param icsUid the iCalendar UID of the event to remove
   * @throws CaldavPushException when the deletion cannot be carried out
   */
  public void deleteEvent(long userIdentityId, String icsUid) {
    CaldavUserSetting settings = connectedSettings(userIdentityId);
    ObjectSync known = objectAnywhere(userIdentityId, settings, icsUid);
    if (known == null || StringUtils.isBlank(known.getRemoteHref())) {
      return;
    }
    try {
      calDavClient.deleteObject(endpointOf(settings),
                               known.getRemoteHref(),
                               known.getEtag(),
                               settings.getUsername(),
                               settings.getPassword());
    } catch (CalDavAuthenticationException e) {
      throw new CaldavPushException(CREDENTIALS, "The stored CalDAV credentials were rejected", e);
    } catch (CalDavException e) {
      throw new CaldavPushException(SAVE, "The calendar object could not be removed", e);
    }
    caldavSyncStorage.saveObject(cleared(known));
  }

  /**
   * Removes one occurrence from a series without removing the series.
   *
   * <p>
   * RFC 4791 puts every component of a series in one object, so a deletion here
   * is a rewrite: the override carrying that instance is dropped and the master
   * gains an EXDATE for it. Deleting the object instead would cancel every
   * meeting of the series to cancel one.
   *
   * <p>
   * The rewrite is conditional on the ETag last seen, so a series someone else
   * changed in the meantime surfaces as a conflict rather than being
   * overwritten with a stale copy.
   *
   * @param userIdentityId identity of the user
   * @param icsUid the iCalendar UID of the series
   * @param occurrence the instance to exclude
   * @throws CaldavPushException when the rewrite cannot be carried out
   */
  public void excludeOccurrence(long userIdentityId, String icsUid, Instant occurrence) {
    CaldavUserSetting settings = connectedSettings(userIdentityId);
    CalendarSync pair = existingMirrorPair(userIdentityId, settings);
    if (pair == null) {
      return;
    }
    ObjectSync known = caldavSyncStorage.getObjectByUid(pair.getId(), icsUid);
    if (known == null || StringUtils.isBlank(known.getRemoteHref())) {
      return;
    }
    CalDavEndpoint endpoint = endpointOf(settings);
    try {
      CalendarObject existing = calDavClient.fetchObject(endpoint,
                                                         known.getRemoteHref(),
                                                         settings.getUsername(),
                                                         settings.getPassword());
      if (existing == null || StringUtils.isBlank(existing.calendarData())) {
        return;
      }
      String rewritten = icsMerger.excludeOccurrence(existing.calendarData(), occurrence);
      if (rewritten == null) {
        // Nothing left in the object: the last instance was the one excluded.
        calDavClient.deleteObject(endpoint,
                                  known.getRemoteHref(),
                                  known.getEtag(),
                                  settings.getUsername(),
                                  settings.getPassword());
        caldavSyncStorage.saveObject(cleared(known));
        return;
      }
      PutResult result = calDavClient.updateObject(endpoint,
                                                   known.getRemoteHref(),
                                                   rewritten,
                                                   known.getEtag(),
                                                   settings.getUsername(),
                                                   settings.getPassword());
      if (result.preconditionFailed()) {
        throw new CaldavPushException(CONFLICT, "The series at " + known.getRemoteHref() + " changed since it was read");
      }
      known.setEtag(result.etag());
      known.setLastSync(new Date());
      caldavSyncStorage.saveObject(known);
    } catch (CalDavAuthenticationException e) {
      throw new CaldavPushException(CREDENTIALS, "The stored CalDAV credentials were rejected", e);
    } catch (CalDavException e) {
      throw new CaldavPushException(SAVE, "The occurrence could not be excluded", e);
    }
  }

  /**
   * Carries an answer recorded in eXo out to the copy on the user's calendar
   * server.
   *
   * <p>
   * The outward half of answering a meeting, and the one that was missing. A
   * user can answer in two places — their calendar client, or the Accept and
   * Decline links in the notification mail — and only the first of the two
   * ever reached both sides. An answer given in eXo stopped at eXo's database:
   * the copy went on displaying the previous answer to the person who had just
   * changed it, and, worse, went on being the thing the verification pass
   * reads a client's answer back from. The next time any client rewrote that
   * object for any reason — a rename, a drag, a re-serialising sync — the
   * stale answer on it was adopted as if it were fresh and the newer one was
   * silently lost.
   *
   * <p>
   * A targeted rewrite of one ATTENDEE's PARTSTAT, not a re-push of the whole
   * event, and conditional on the ETag last seen. Both follow from what this
   * knows: it has been told an answer, it has not read the object, so it is in
   * no position to decide anything about the rest of it. A concurrent edit is
   * therefore refused rather than overwritten — which is the same discipline
   * every other ordinary write here follows, and leaves the divergence to the
   * verification pass, whose job is precisely to look before it writes.
   *
   * <p>
   * Answers false for every ordinary reason there is nothing to do — no
   * connected account, no copy of this meeting, a copy that already says this.
   * <b>Every one of those says so in the log before it returns.</b> This
   * method did all of it silently once, and a propagation that silently does
   * nothing is indistinguishable from the defect it was written to fix: it
   * cost a whole live test round to find out which guard had refused. The
   * ordinary no-ops are DEBUG; the states that should not arise — a mapping
   * with no ETag, an object the server serves as empty, a copy that does not
   * name the user it belongs to — are WARN, because each is an inconsistency
   * somebody has to repair rather than a user simply not using the feature.
   *
   * @param userIdentityId identity of the user whose answer was recorded
   * @param eventId the agenda event answered — an occurrence is resolved to
   *          its series, which is what the copy is written under
   * @param response the answer as agenda holds it, e.g. {@code ACCEPTED}
   * @return true when the copy was rewritten
   * @throws CaldavPushException when the copy could not be written
   */
  public boolean pushAnswer(long userIdentityId, long eventId, String response) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!connected(settings)) {
      LOG.debug("Answer of user {} to event {} is not carried out: no connected CalDAV account", userIdentityId, eventId);
      return false;
    }
    List<String> addresses = addressesNaming(userIdentityId, settings);
    if (addresses.isEmpty()) {
      LOG.debug("Answer of user {} to event {} is not carried out: no address could name them on a copy",
                userIdentityId,
                eventId);
      return false;
    }
    String icsUid = icsUidOf(eventId, userIdentityId);
    if (icsUid == null) {
      LOG.debug("Answer of user {} to event {} is not carried out: this meeting has no copy on their account",
                userIdentityId,
                eventId);
      return false;
    }
    ObjectSync known = objectAnywhere(userIdentityId, settings, icsUid);
    if (known == null || StringUtils.isBlank(known.getRemoteHref())) {
      LOG.debug("Answer of user {} to event {} is not carried out: nothing is mapped under {}",
                userIdentityId,
                eventId,
                icsUid);
      return false;
    }
    if (StringUtils.isBlank(known.getEtag())) {
      // A mapping pointing at an href while recording no ETag describes an
      // object eXo never managed to write. There is nothing to condition a
      // write on, and the client refuses an unconditional one on purpose.
      LOG.warn("Answer of user {} to event {} is not carried out: the copy at {} is mapped without an ETag,"
          + " so no conditional write is possible; the verification pass has to re-establish it",
               userIdentityId,
               eventId,
               known.getRemoteHref());
      return false;
    }
    CalDavEndpoint endpoint = endpointOf(settings);
    try {
      CalendarObject existing = calDavClient.fetchObject(endpoint,
                                                        known.getRemoteHref(),
                                                        settings.getUsername(),
                                                        settings.getPassword());
      if (existing == null || StringUtils.isBlank(existing.calendarData())) {
        LOG.warn("Answer of user {} to event {} is not carried out: the copy at {} is mapped but the server serves"
            + " nothing at it",
                 userIdentityId,
                 eventId,
                 known.getRemoteHref());
        return false;
      }
      IcsMerger.AnswerRewrite rewrite = icsMerger.setAttendeeResponse(existing.calendarData(),
                                                                     addresses,
                                                                     IcsText.partStat(response));
      if (!rewrite.attendeeNamed()) {
        // The state that made this whole thing do nothing on a live rig, and
        // the reason the addresses are now offered as a set. Naming them in
        // the message is the point: whoever reads this line can compare them
        // against the ATTENDEE lines on the object in one step.
        LOG.warn("Answer of user {} to event {} is not carried out: the copy at {} names none of {},"
            + " so there is no participation status of theirs to rewrite",
                 userIdentityId,
                 eventId,
                 known.getRemoteHref(),
                 addresses);
        return false;
      }
      if (!rewrite.hasChange()) {
        // The copy already says this. Writing anyway would move the ETag for
        // nothing — and would push straight back at the server an answer that
        // had just been adopted from it.
        LOG.debug("Answer of user {} to event {} is not carried out: the copy already says {}",
                  userIdentityId,
                  eventId,
                  response);
        return false;
      }
      PutResult result = calDavClient.updateObject(endpoint,
                                                   known.getRemoteHref(),
                                                   rewrite.document(),
                                                   known.getEtag(),
                                                   settings.getUsername(),
                                                   settings.getPassword());
      if (result.preconditionFailed()) {
        throw new CaldavPushException(CONFLICT, "The copy at " + known.getRemoteHref() + " changed since it was read");
      }
      // The version has to become the one this write produced, or the next
      // ordinary update carries an If-Match the server has already left behind.
      //
      // Nothing else is recorded, and nothing needs to be. This used to store a
      // digest of what was just written, so that the verification pass would
      // not judge this very write a client's doing and repair the copy back to
      // what eXo last pushed — undoing the answer. EXO-89716 makes that
      // impossible by construction instead: the pass compares the copy against
      // what eXo would render for the event <i>now</i>, and the answer was
      // recorded in agenda before this method was ever called, so eXo's own
      // render already carries it. The baseline agrees because it is derived
      // from the same source the answer came from, not because a digest was
      // remembered.
      known.setEtag(result.etag());
      known.setLastSync(new Date());
      caldavSyncStorage.saveObject(known);
      LOG.debug("Answer of user {} to event {} carried out onto the copy at {}: {}",
                userIdentityId,
                eventId,
                known.getRemoteHref(),
                response);
      return true;
    } catch (CalDavAuthenticationException e) {
      throw new CaldavPushException(CREDENTIALS, "The stored CalDAV credentials were rejected", e);
    } catch (CalDavException e) {
      throw new CaldavPushException(SAVE, "The answer could not be written to " + known.getRemoteHref(), e);
    }
  }

  /**
   * Every address a copy on this user's account might name them by.
   *
   * <p>
   * There is more than one, and assuming otherwise is what made this
   * propagation silently do nothing on a live rig. A copy names the account's
   * own owner by <b>the address their CalDAV account answers to</b> — that is
   * what lets a calendar client recognise the meeting as an invitation to
   * itself and offer to answer it — while every other line on that object, and
   * every object written before that rule existed, carries the person's
   * <b>eXo profile address</b>. On the rig the two were {@code alice@…} and
   * {@code bob@…} for the same user, so asking the profile alone matched no
   * line at all.
   *
   * <p>
   * Both are offered and the object decides. Neither is authoritative here on
   * purpose: an address that names nobody costs one failed comparison, while
   * the wrong single answer costs the whole feature, without a sound.
   *
   * <p>
   * <b>The account half is optional, and that is what EXO-89868 needed of
   * it.</b> Fanning an answer out to the other attendees' copies has to name
   * the <i>answerer</i> on somebody else's object, and the answerer need not
   * have connected an account at all — their answer still has to reach the
   * copies of the people who did. A null or half-filled account is therefore
   * an ordinary input here and contributes nothing rather than throwing; the
   * profile address alone is exactly what a copy written for somebody else
   * names them by, because {@code AgendaEventIcsMapper.attendees} spells the
   * account address on one line only, the line of the person the copy is
   * being written for.
   *
   * @param userIdentityId identity of the user whose answer was recorded
   * @param settings their connected account, null or unconnected when they
   *          have none — then only their profile address is offered
   * @return the addresses to look for, most specific first, possibly empty
   */
  public List<String> addressesNaming(long userIdentityId, CaldavUserSetting settings) {
    List<String> addresses = new ArrayList<>();
    String account = settings == null ? null : StringUtils.trimToNull(settings.getUsername());
    if (account != null) {
      addresses.add(account);
    }
    String profile = StringUtils.trimToNull(agendaEventIcsMapper.addressOf(userIdentityId));
    if (profile != null && !addresses.contains(profile)) {
      addresses.add(profile);
    }
    return addresses;
  }

  /**
   * Every address a copy might name one person by, without needing them to
   * have connected an account.
   *
   * <p>
   * The form EXO-89868's fan-out asks for. The two-argument sibling is called
   * from paths that are already holding the account they are writing to, and
   * making them look it up again would be a second read of the same row; this
   * one is called about a person whose account is not the one being written
   * to and may not exist at all, so it looks the account up itself and treats
   * its absence as ordinary rather than as a refusal.
   *
   * <p>
   * The account is read but not <i>required</i> to be connected. A stored
   * account with no password is not something this can write with — and it is
   * not being asked to write with it. It is being asked what a copy might
   * spell this person as, and a username stored without a password is still a
   * username some copy may have been written under.
   *
   * @param userIdentityId identity of the person to be named
   * @return the addresses to look for, most specific first, possibly empty
   */
  public List<String> addressesNaming(long userIdentityId) {
    CaldavUserSetting settings;
    try {
      settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    } catch (Exception | LinkageError e) {
      // Not being able to read an account is not being able to offer one more
      // address. The profile address is the one a copy written for somebody
      // else actually carries, so this degrades rather than fails.
      LOG.debug("The CalDAV account of user {} could not be read; only their profile address can name them",
                userIdentityId,
                e);
      settings = null;
    }
    return addressesNaming(userIdentityId, settings);
  }

  /**
   * Writes one person's answer onto somebody else's copy of the same meeting.
   *
   * <h4>The half of answering that was never built (EXO-89868)</h4>
   *
   * <p>
   * {@link #pushAnswer} opens by reading <b>the answerer's own</b> account and
   * rewrites the copy sitting on it. So an answer travelled to the answerer's
   * own calendar and stopped there: every other attendee's copy went on
   * showing the answer it last carried, on every server, for ever. The
   * organiser read a stale RSVP in the client they actually live in, while
   * eXo's own screens showed the right one — the worst shape a divergence can
   * take, because nothing in the product disagrees out loud.
   *
   * <p>
   * This is the same targeted write pointed at a different copy. Two things
   * are deliberately crossed over, and getting either the wrong way round is
   * the whole defect written a second time: the <b>holder's</b> account,
   * endpoint and ETag decide where and how the write goes, while the
   * <b>answerer's</b> addresses decide which ATTENDEE line on that object is
   * rewritten.
   *
   * <h4>Why a targeted line rewrite and not a rewrite of the event</h4>
   *
   * <p>
   * Because a full rewrite would destroy answers. {@link IcsMerger#merge}
   * replaces the master VEVENT wholesale with what eXo renders, and on a
   * server that records its own owner's answer <b>without moving the
   * ETag</b> — BlueMind, measured on this rig — the conditional write
   * succeeds and takes an unread answer down with it. The verification pass
   * guards that case by reading before it writes; the push path does not read.
   * Rewriting one named ATTENDEE line and leaving every other byte alone
   * avoids it by construction, and that property is what the fan-out is safe
   * on rather than something it is careful about.
   *
   * <p>
   * The mapping is handed in rather than looked up, and for a reason worth
   * more than the round trip it saves: the caller has already recorded an
   * obligation against <i>that</i> row, and a lookup here could resolve a
   * different one — the same user can hold a mapping in their mirror and
   * another in a personal collection. The copy written and the obligation
   * settled must be the same copy.
   *
   * @param holderIdentityId identity of the user whose copy is written to
   * @param copy the mapping row naming that copy, with its href and the ETag
   *          the write is conditioned on
   * @param answererAddresses every address the copy might name the answerer
   *          by, from {@link #addressesNaming(long)}
   * @param response the answer as agenda holds it, e.g. {@code ACCEPTED}
   * @return what happened to the copy, which is what tells the caller whether
   *         anything is still owed to it
   * @throws CaldavPushException when the copy could not be written
   */
  public AnswerOutcome pushAnswerOnto(long holderIdentityId,
                                      ObjectSync copy,
                                      List<String> answererAddresses,
                                      String response) {
    if (copy == null || StringUtils.isBlank(copy.getRemoteHref())) {
      // A tombstone, or a row built by hand. Nothing to write to, and writing
      // to it would re-create the object somebody deleted.
      return AnswerOutcome.UNWRITABLE;
    }
    if (answererAddresses == null || answererAddresses.isEmpty()) {
      LOG.debug("An answer is not carried onto the copy of user {} at {}: no address could name the answerer on it",
                holderIdentityId,
                copy.getRemoteHref());
      return AnswerOutcome.UNWRITABLE;
    }
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(holderIdentityId);
    if (!connected(settings)) {
      // A refusal rather than a false, so the caller records it the way it
      // records every other state of a holder it cannot write to: the copy
      // stays owed, and the day they reconnect the sweep settles it.
      throw new CaldavPushException(NOT_CONNECTED, "User " + holderIdentityId + " has no connected CalDAV account");
    }
    if (StringUtils.isBlank(copy.getEtag())) {
      // A mapping pointing at an href while recording no ETag describes an
      // object eXo never managed to write. There is nothing to condition a
      // write on, and the client refuses an unconditional one on purpose.
      LOG.warn("An answer is not carried onto the copy of user {} at {}: it is mapped without an ETag,"
          + " so no conditional write is possible; the verification pass has to re-establish it",
               holderIdentityId,
               copy.getRemoteHref());
      return AnswerOutcome.UNWRITABLE;
    }
    CalDavEndpoint endpoint = endpointOf(settings);
    try {
      CalendarObject existing = calDavClient.fetchObject(endpoint,
                                                        copy.getRemoteHref(),
                                                        settings.getUsername(),
                                                        settings.getPassword());
      if (existing == null || StringUtils.isBlank(existing.calendarData())) {
        LOG.warn("An answer is not carried onto the copy of user {} at {}: it is mapped but the server serves nothing at it",
                 holderIdentityId,
                 copy.getRemoteHref());
        return AnswerOutcome.UNWRITABLE;
      }
      IcsMerger.AnswerRewrite rewrite = icsMerger.setAttendeeResponse(existing.calendarData(),
                                                                     answererAddresses,
                                                                     IcsText.partStat(response));
      if (!rewrite.attendeeNamed()) {
        // The answerer is not on this object at all: their profile hides their
        // address, or the copy predates their invitation and has never been
        // rewritten since. Said at WARN because eXo holds a mapping asserting
        // this is a copy of a meeting they are invited to, so the two
        // disagree. The line cannot be added by a targeted rewrite — there is
        // nothing to target — and this is exactly the outcome the caller
        // leaves owed, so that the bounded full rewrite puts the line there.
        LOG.warn("An answer is not carried onto the copy of user {} at {}: it names none of {},"
            + " so there is no participation status of theirs to rewrite",
                 holderIdentityId,
                 copy.getRemoteHref(),
                 answererAddresses);
        return AnswerOutcome.NOT_NAMED;
      }
      if (!rewrite.hasChange()) {
        // The copy already says this, and saying so is a settled copy rather
        // than a failed write. Both halves matter. Writing anyway would move
        // the ETag for nothing — the guard the task names, without which one
        // answer rewrites every copy that already agrees, every time anybody
        // answers. And leaving the obligation standing would be the same waste
        // deferred by five minutes: the sweep would come back and rewrite a
        // correct copy in full, which is the one operation this whole design
        // avoids because it can destroy an answer nothing has read yet.
        LOG.debug("An answer is not carried onto the copy of user {} at {}: it already says {}",
                  holderIdentityId,
                  copy.getRemoteHref(),
                  response);
        return AnswerOutcome.ALREADY_SAID;
      }
      PutResult result = calDavClient.updateObject(endpoint,
                                                   copy.getRemoteHref(),
                                                   rewrite.document(),
                                                   copy.getEtag(),
                                                   settings.getUsername(),
                                                   settings.getPassword());
      if (result.preconditionFailed()) {
        throw new CaldavPushException(CONFLICT, "The copy at " + copy.getRemoteHref() + " changed since it was read");
      }
      // The version has to become the one this write produced, or the next
      // ordinary update carries an If-Match the server has already left behind.
      copy.setEtag(result.etag());
      copy.setLastSync(new Date());
      caldavSyncStorage.saveObject(copy);
      LOG.debug("An answer was carried onto the copy of user {} at {}: {} for {}",
                holderIdentityId,
                copy.getRemoteHref(),
                response,
                answererAddresses);
      return AnswerOutcome.WRITTEN;
    } catch (CalDavAuthenticationException e) {
      throw new CaldavPushException(CREDENTIALS, "The stored CalDAV credentials were rejected", e);
    } catch (CalDavException e) {
      throw new CaldavPushException(SAVE, "The answer could not be written to " + copy.getRemoteHref(), e);
    }
  }

  /**
   * What became of one attempt to write an answer onto one copy.
   *
   * <p>
   * Four states rather than a boolean, because two of them look like failure
   * and are not, and the caller has to tell them apart to decide whether the
   * copy still owes eXo anything. A copy that already carries the answer is as
   * settled as one this write just corrected; a copy that does not name the
   * answerer at all is not settled by anything a targeted rewrite can do.
   * Collapsing those into "false" is what would make the obligation table
   * either grow for ever or forget the copies that genuinely need a full
   * rewrite.
   */
  public enum AnswerOutcome {

    /** The answer was written onto the copy, which now carries it. */
    WRITTEN,

    /**
     * The copy already carried the answer, so nothing was written and nothing
     * is owed.
     */
    ALREADY_SAID,

    /**
     * The copy carries no ATTENDEE line for the answerer, so a targeted
     * rewrite has nothing to target. Only a full rewrite can put the line
     * there, and the obligation is what brings one.
     */
    NOT_NAMED,

    /**
     * The copy could not be written to at all — no href, no recorded version
     * to condition on, nothing served at it, or no address to name the
     * answerer by. The obligation stands.
     */
    UNWRITABLE;

    /**
     * Whether this outcome leaves the copy agreeing with what eXo holds.
     *
     * @return true when nothing more is owed to the copy
     */
    public boolean settles() {
      return this == WRITTEN || this == ALREADY_SAID;
    }
  }

  /**
   * The iCalendar UID the copy of one agenda event is written under.
   *
   * <p>
   * Resolved through the event rather than through the mapping row, because a
   * series and its overrides share one object and one UID while each override
   * is a separate agenda event with an id of its own. Answering an event whose
   * id is an override's would otherwise find no copy at all.
   *
   * <p>
   * The event is read through agenda's own service, so its ACL applies — and
   * a user who may not see it answers null rather than raising: this is only
   * ever asked on behalf of the user whose answer was just recorded, so a
   * refusal means the event went away, not that anything was smuggled.
   *
   * @param eventId the agenda event, master or override
   * @param userIdentityId identity of the user whose copy is looked for
   * @return the UID, or null when this user has no copy of this meeting
   */
  private String icsUidOf(long eventId, long userIdentityId) {
    Event event;
    try {
      event = agendaEventService.getEventById(eventId, null, userIdentityId);
    } catch (IllegalAccessException e) {
      LOG.debug("Event {} is not visible to user {}; its copy is left alone", eventId, userIdentityId);
      return null;
    }
    if (event == null) {
      return null;
    }
    long seriesId = event.getParentId() > 0 ? event.getParentId() : event.getId();
    RemoteEvent known = agendaRemoteEventService.findRemoteEvent(seriesId, userIdentityId);
    return known == null ? null : StringUtils.trimToNull(known.getRemoteId());
  }

  /**
   * The collection space events are copied into, creating it when it does not
   * exist yet.
   *
   * <p>
   * Ported from the browser connector, including the parts that look
   * redundant and are not. The path is derived from the slug alone, with
   * nothing random in it, so asking twice for the same calendar means asking
   * for the same collection — a random suffix made every request a different
   * one, and a user who disconnected and reconnected collected a new calendar
   * on the server each time.
   *
   * <p>
   * This reads it; it never creates one. Nothing configured is an answer, not
   * a reason to make a calendar on someone's account — the settings screen
   * asks this question on every render.
   *
   * <p>
   * The name comes from the server on each call rather than from anything
   * stored: the user may have renamed the calendar in their own client, and
   * the screen showing a stale name is how a destination stops being
   * recognisable as the one it names.
   *
   * Recorded href or not, the account is asked. Disconnecting clears the
   * href while leaving the collection on the server, so a reconnected account
   * has a destination it does not remember — and answering "none" there made
   * eXo offer to create a calendar that already existed, and invite a second
   * one beside it. The collection eXo creates lives at a path eXo derives, so
   * it can be recognised without having been remembered: the same second
   * candidate {@link #ensureMirror} adopts. The two now answer alike, where
   * before creation adopted what this reported absent.
   *
   * A destination that was recorded and cannot be reached still raises: the
   * user chose it, so the screen must say the account is unreachable rather
   * than quietly report having no destination. With nothing recorded there is
   * no such claim to keep, and an unreachable server answers null.
   *
   * Which of those candidates is asked for depends on the registration's
   * destination setting, and it is asked through the same helper
   * {@link #ensureMirror} uses: the screen must never name a calendar the next
   * push would not write to.
   *
   * @param userIdentityId identity of the user
   * @return the destination and its current name, or null when the account
   *         has none — neither the one recorded nor one at the derived path
   */
  public MirrorTarget currentMirror(long userIdentityId) {
    CaldavUserSetting settings = connectedSettings(userIdentityId);
    boolean recorded = StringUtils.isNotBlank(settings.getMirrorCalendarHref());
    try {
      return lookUpMirror(userIdentityId, settings);
    } catch (RuntimeException e) {
      if (recorded) {
        throw e;
      }
      // Nothing was recorded, so nothing is being claimed lost: the account
      // simply has no destination as far as anyone knew, and an unreachable
      // server cannot turn that into a failure. Before this method looked for
      // a collection it had not recorded, the same account answered "none"
      // without a request at all — an error here would make every render of
      // the settings page report a problem the user does not have.
      LOG.debug("could not look for a destination calendar of user {}", userIdentityId, e);
      return null;
    }
  }

  /**
   * Asks the account for the calendar holding the copies.
   *
   * @param userIdentityId identity of the user, so that a main calendar which
   *          cannot be resolved is said out loud from here too
   * @param settings the connected account
   * @return the destination and its current name, or null when the account
   *         holds neither the recorded collection nor one at the derived path
   */
  private MirrorTarget lookUpMirror(long userIdentityId, CaldavUserSetting settings) {
    CalDavEndpoint endpoint = endpointOf(settings);
    String home = calDavClient.discoverCalendarHome(endpoint, settings.getUsername(), settings.getPassword());
    List<CalendarCollection> calendars = calDavClient.listCalendars(endpoint,
                                                                   home,
                                                                   settings.getUsername(),
                                                                   settings.getPassword());
    return candidateOf(userIdentityId, mirrorTargetOf(settings), settings, endpoint, calendars, home)
                                                                                     .map(collection -> new MirrorTarget(collection.href(),
                                                                                                                         false,
                                                                                                                         collection.displayName()))
                                                                                     .orElse(null);
  }

  /**
   * The collection this account's destination resolves to among the calendars
   * the server just listed, without creating or recording anything.
   *
   * <p>
   * <b>The one place the kinds differ, and the reason it is one method.</b>
   * Reading the destination and establishing it used to be two walks over the
   * same listing with the same two candidate hrefs written out twice. Both
   * callers now ask this the same question, so the settings screen cannot
   * report a destination the next push would not write to.
   *
   * @param userIdentityId identity of the user, for the one line an
   *          unresolvable main calendar is worth
   * @param kind where the registration wants the copies written
   * @param settings the connected account
   * @param endpoint the resolved server endpoint
   * @param calendars what the account's home holds, already listed
   * @param home the calendar home's path
   * @return the collection the destination resolves to, or empty when the
   *         account holds none — which for the account's own default means the
   *         account named none
   */
  private Optional<CalendarCollection> candidateOf(long userIdentityId,
                                                   MirrorTargetKind kind,
                                                   CaldavUserSetting settings,
                                                   CalDavEndpoint endpoint,
                                                   List<CalendarCollection> calendars,
                                                   String home) {
    return switch (kind) {
    // The account's own default, asked of the account and then confirmed
    // against the account's own listing — see mainCalendarOf for the two ways
    // an answer is confirmed and the one refusal that remains.
    case MAIN_CALENDAR -> mainCalendarOf(userIdentityId, settings, endpoint, calendars);
    // The path the slug derives under their home, and only then the href
    // recorded for this user.
    //
    // Asked in two steps rather than as one two-signal match, because the
    // recorded href is no longer a reliable second name for the dedicated
    // calendar: every kind records where its copies currently go, so an
    // account that spent a while on MAIN_CALENDAR has its main calendar
    // recorded here. Matched together, a return to this kind found both
    // collections in the listing and answered whichever the server happened to
    // list first — the account's main calendar as often as not, which is the
    // one destination this kind is not. Ordered, the derived path wins when the
    // account holds it, and the recorded href still answers for the account
    // whose collection was adopted rather than created (the server refused
    // MKCALENDAR) or established before the slug existed.
    default -> findMirror(calendars, null, collectionHref(home, MIRROR_COLLECTION_SLUG))
                                                                                       .or(() -> findMirror(calendars,
                                                                                                            settings.getMirrorCalendarHref(),
                                                                                                            null));
    };
  }

  /**
   * The account's own default calendar, as the account's own home listing
   * holds it.
   *
   * <p>
   * <b>Asked, then confirmed — the confirmation is the whole discipline.</b>
   * The account names a default calendar ({@code schedule-default-calendar-URL},
   * RFC 6638) and that href is a claim until the home listing shows the
   * collection, exactly as an MKCALENDAR status is a claim until the same
   * listing shows what it created (EXO-89760). Nothing here is ever taken from
   * outside that listing.
   *
   * <p>
   * <b>Why a second way of confirming.</b> BlueMind answers the property, and
   * answers it with a href that is not the collection's: its scheduling inbox
   * returns <code>&lt;home&gt;/calendar</code> — a fixed string built from the
   * account uid — while the collection the same account lists, and the one its
   * own web client uses, is <code>&lt;home&gt;/calendar:Default:&lt;uid&gt;</code>.
   * Exact matching therefore refused a default calendar that was plainly there,
   * on the very server this destination was designed for, and every copy went
   * on being written where the administrator had stopped asking for them.
   *
   * <p>
   * <b>What the second way keys on, and what it refuses.</b> Not a name
   * pattern: nothing here knows the string {@code calendar:Default:}, and it
   * would be worthless on the next server anyway. It keys on the server's own
   * answer, and accepts a listed collection only when that collection's path
   * <i>extends the answered one inside the same parent collection</i> — same
   * home, same last path segment up to a suffix, no extra slash — and only when
   * <b>exactly one</b> listed collection does. Two candidates is not a
   * near-miss to arbitrate, it is an account this rule cannot read, and it
   * refuses. So does an answer nothing extends, and so does no answer at all.
   *
   * <p>
   * The fail-closed guarantee of EXO-89760 is untouched: every path out of here
   * that is not a collection the account itself listed is {@link Optional#empty},
   * which {@link #ensureMirror} turns into {@link #MAIN_CALENDAR_UNKNOWN}. This
   * is another way to resolve the main calendar, never a substitution of a
   * different one — the dedicated calendar is not reachable from here at all.
   *
   * @param userIdentityId identity of the user, for the one line the state is
   *          worth
   * @param settings the connected account
   * @param endpoint the resolved server endpoint
   * @param calendars what the account's home holds, already listed
   * @return the collection the account's default resolves to, or empty when
   *         none can be confirmed
   */
  private Optional<CalendarCollection> mainCalendarOf(long userIdentityId,
                                                      CaldavUserSetting settings,
                                                      CalDavEndpoint endpoint,
                                                      List<CalendarCollection> calendars) {
    String named = calDavClient.discoverDefaultCalendar(endpoint, settings.getUsername(), settings.getPassword());
    Optional<CalendarCollection> resolved = findMirror(calendars, named, null).or(() -> extensionOf(calendars, named));
    if (resolved.isPresent()) {
      // Out of the state: a later spell of it is worth saying again.
      unresolvedMainCalendars.remove(unresolvedKey(userIdentityId, settings));
    } else {
      announceUnresolvedMainCalendar(userIdentityId, settings, named);
    }
    return resolved;
  }

  /**
   * The one listed collection whose path extends the one the account named,
   * within the same parent collection.
   *
   * <p>
   * The remainder after the answered path must be non-empty — an exact match is
   * the caller's first question, not this one's — and must carry no slash, so
   * that a server answering the calendar <i>home</i> cannot resolve to whatever
   * single calendar happens to hang under it. The two together confine this to
   * one shape: a server that truncates its own collection's last path segment.
   *
   * <p>
   * Ambiguity refuses. A wrong answer here files a user's meetings into a
   * calendar nobody chose, which is worse than filing them nowhere and saying
   * so; two candidates therefore end this the same way none does.
   *
   * @param calendars what the account's home holds, already listed
   * @param namedHref the href the account answered, may be null
   * @return the single collection extending it, or empty
   */
  private Optional<CalendarCollection> extensionOf(List<CalendarCollection> calendars, String namedHref) {
    String named = CaldavSyncStorage.canonicalHref(namedHref);
    if (StringUtils.isBlank(named) || calendars == null) {
      return Optional.empty();
    }
    List<CalendarCollection> extensions = calendars.stream()
                                                   .filter(CalendarCollection::holdsEvents)
                                                   .filter(calendar -> extendsWithinSegment(CaldavSyncStorage.canonicalHref(calendar.href()),
                                                                                            named))
                                                   .toList();
    if (extensions.size() != 1) {
      LOG.debug("The default calendar {} the account names is extended by {} listed collections; none is taken",
                named,
                extensions.size());
      return Optional.empty();
    }
    return Optional.of(extensions.get(0));
  }

  /**
   * Whether one canonical path extends another inside the same parent
   * collection.
   *
   * @param href the listed collection's canonical path
   * @param named the canonical path the account answered
   * @return true when href is named plus a non-empty, slash-free tail
   */
  private boolean extendsWithinSegment(String href, String named) {
    if (href == null || !href.startsWith(named)) {
      return false;
    }
    String tail = href.substring(named.length());
    return !tail.isEmpty() && tail.indexOf('/') < 0;
  }

  /**
   * Says, once, that this account's main calendar cannot be resolved.
   *
   * <p>
   * <b>The state had no voice at all.</b> It threw a code the push path turns
   * into a refusal nobody sees, the relocation pass swallowed it and deferred,
   * and the only trace left was a null field in an admin JSON endpoint — while
   * the copies went on flowing into the calendar the administrator had just
   * stopped asking for. Hours of that is what this line is for.
   *
   * <p>
   * <b>Once per transition, not once per pass.</b> The question is asked on
   * every push, every sweep and every render of the settings screen; at warn
   * each time it would be exactly the noise EXO-89798 is removing from this
   * add-on. It is not a contradiction of that task: what is quietened there is
   * a persistent state repeated per occurrence, and what is said here is the
   * <i>edge</i> into that state, once, with the account cleared the moment a
   * calendar does resolve.
   *
   * <p>
   * Names the user, the server and what was asked for, plus the href the
   * account itself answered — that last one is the whole diagnosis on a server
   * whose answer does not match its own listing, and there is no credential
   * anywhere near it.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   * @param named the href the account answered, or null when it named none
   */
  private void announceUnresolvedMainCalendar(long userIdentityId, CaldavUserSetting settings, String named) {
    if (!unresolvedMainCalendars.add(unresolvedKey(userIdentityId, settings))) {
      return;
    }
    LOG.warn("CalDAV server {} ({}) is set to write meeting copies into each account's main calendar, and the account"
        + " of user {} names {} as its default calendar, which its own calendar home does not list. No copy is written"
        + " for them and the copies already written are not moved until this resolves.",
             settings.getServerId(),
             StringUtils.defaultIfBlank(declaredAddress(settings), "address unknown"),
             userIdentityId,
             StringUtils.defaultIfBlank(named, "no calendar at all"));
  }

  /**
   * The key one account's unresolved-main-calendar state is remembered under.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   * @return the key, per user and per server
   */
  private String unresolvedKey(long userIdentityId, CaldavUserSetting settings) {
    return userIdentityId + "|" + settings.getServerId();
  }

  /**
   * The address an administrator declared for this account's server, for a log
   * line and nothing else.
   *
   * <p>
   * Never allowed to become a failure of its own: the registry is being read
   * here only so that a warning names something a person recognises, and a
   * registry that cannot answer must not turn an already-degraded state into an
   * exception on the push path.
   *
   * @param settings the connected account
   * @return the declared URL, or null when the registry answers none
   */
  private String declaredAddress(CaldavUserSetting settings) {
    try {
      return caldavServerService == null ? null : caldavServerService.resolveServerUrl(settings.getServerId());
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("The address of CalDAV server {} could not be read for a log line", settings.getServerId(), e);
      return null;
    }
  }

  /**
   * Where the registration behind a connected account wants its meeting copies
   * written.
   *
   * <p>
   * <b>Every failure resolves to the dedicated calendar</b>, the behaviour
   * every deployment had before this setting existed: an account referencing no
   * registration, a registry that answers nothing, a registration deleted
   * between the account being stored and this call, a row carrying a value this
   * version does not know — {@code USER_CHOICE}, withdrawn by EXO-89793,
   * among them. The asymmetry is deliberate: refusing to write because the
   * registry could not be read would strand every copy on an incident that has
   * nothing to do with where they go, while writing them where they have always
   * gone is at worst unchanged behaviour.
   *
   * @param settings the connected account
   * @return the kind the registration declares, never null
   */
  private MirrorTargetKind mirrorTargetOf(CaldavUserSetting settings) {
    try {
      CaldavServer server = caldavServerService == null ? null : caldavServerService.resolveServer(settings.getServerId());
      return server == null || server.getMirrorTarget() == null ? MirrorTargetKind.DEDICATED_CALENDAR
                                                                : server.getMirrorTarget();
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("No CalDAV registration could be resolved for server {}; copies go to the dedicated calendar",
                settings.getServerId(),
                e);
      return MirrorTargetKind.DEDICATED_CALENDAR;
    }
  }

  /**
   * Establishes the calendar the copies are written into, creating it when it
   * is not there.
   *
   * <p>
   * <b>The one place the destination is decided.</b> Every push arrives here,
   * and the registration's {@code mirrorTarget} is read here and nowhere else:
   * not in the mapper, which describes an event and does not place it; not in
   * the sweep, which decides when to write and not where; not in the listeners,
   * which decide whether. A second reader would be a second answer, and the two
   * would disagree on exactly the account where it mattered.
   *
   * <p>
   * What "establishes" means depends on the kind, and only one of the two
   * creates anything: the dedicated calendar is created when absent and an
   * existing one adopted when the server refuses; the account's own default is
   * asked for and never invented.
   *
   * @param userIdentityId identity of the user
   * @return where the copies go, and whether an existing calendar was adopted
   * @throws CaldavPushException when no destination can be established —
   *           {@link #MAIN_CALENDAR_UNKNOWN} when the account names no default
   *           calendar, {@link #CREATION_REFUSED} when nothing could be created
   *           or adopted
   */
  public MirrorTarget ensureMirror(long userIdentityId) {
    CaldavUserSetting settings = connectedSettings(userIdentityId);
    return ensureMirror(userIdentityId, settings, endpointOf(settings));
  }

  /**
   * The mirror lifecycle, once the account and endpoint are known.
   *
   * @param userIdentityId identity of the user
   * @param settings the user's connected account
   * @param endpoint the resolved server endpoint
   * @return the destination
   */
  private MirrorTarget ensureMirror(long userIdentityId, CaldavUserSetting settings, CalDavEndpoint endpoint) {
    String home = calDavClient.discoverCalendarHome(endpoint, settings.getUsername(), settings.getPassword());
    List<CalendarCollection> calendars = calDavClient.listCalendars(endpoint,
                                                                    home,
                                                                    settings.getUsername(),
                                                                    settings.getPassword());
    // One question, asked the same way the settings screen asks it. On a
    // converged account of either kind this is where the method ends: the
    // collection is there, it is recorded, and nothing is created, adopted or
    // refused.
    MirrorTargetKind kind = mirrorTargetOf(settings);
    Optional<CalendarCollection> resolved = candidateOf(userIdentityId, kind, settings, endpoint, calendars, home);
    if (resolved.isPresent()) {
      caldavConnectorStorage.saveMirrorCalendarHref(resolved.get().href(), userIdentityId);
      return new MirrorTarget(resolved.get().href(), false, resolved.get().displayName());
    }

    if (kind == MirrorTargetKind.MAIN_CALENDAR) {
      // The account named no default calendar, or named one its own home does
      // not list. Creating a calendar of eXo's own would put the copies exactly
      // where this setting stopped asking for them, and taking one out of the
      // listing is the guess this kind exists not to make.
      throw new CaldavPushException(MAIN_CALENDAR_UNKNOWN,
                                    "The account of user " + userIdentityId
                                        + " states no default calendar its calendar home holds");
    }

    String wanted = collectionHref(home, MIRROR_COLLECTION_SLUG);
    MkCalendarResult creation = calDavClient.mkCalendar(endpoint,
                                                        wanted,
                                                        MIRROR_DISPLAY_NAME,
                                                        null,
                                                        settings.getUsername(),
                                                        settings.getPassword());
    // The status is never taken as proof. BlueMind answers 201 while creating
    // nothing when a request omits the supported component set, and that
    // false success cost three rounds of wrong diagnosis — so creation is
    // confirmed by reading the home again, and only that counts.
    Optional<CalendarCollection> created = findMirror(calDavClient.listCalendars(endpoint,
                                                                                 home,
                                                                                 settings.getUsername(),
                                                                                 settings.getPassword()),
                                                      null,
                                                      wanted);
    if (created.isPresent()) {
      caldavConnectorStorage.saveMirrorCalendarHref(created.get().href(), userIdentityId);
      return new MirrorTarget(created.get().href(), false, created.get().displayName());
    }

    LOG.debug("MKCALENDAR at {} did not produce a collection (status {}); falling back to adoption",
              wanted,
              creation.status());
    return adopt(userIdentityId, calendars);
  }

  /**
   * Takes an existing calendar as the destination, because the server would
   * not create one.
   *
   * <p>
   * Only for the mirror, and deliberately: a space event is a copy the user
   * did not ask to be filed anywhere in particular, so putting it in a
   * calendar they already had is a compromise they can see and undo. The same
   * fallback for a personal calendar would be corruption dressed as
   * resilience, which is why PR7 refuses it there.
   *
   * @param userIdentityId identity of the user
   * @param calendars the calendars the account holds
   * @return the adopted destination
   * @throws CaldavPushException when the account holds no calendar at all
   */
  private MirrorTarget adopt(long userIdentityId, List<CalendarCollection> calendars) {
    if (calendars.isEmpty()) {
      throw new CaldavPushException(CREATION_REFUSED,
                                    "The server refused to create a calendar and the account holds none to adopt");
    }
    CalendarCollection adopted = calendars.get(0);
    caldavConnectorStorage.saveMirrorCalendarHref(adopted.href(), userIdentityId);
    return new MirrorTarget(adopted.href(), true, adopted.displayName());
  }

  /**
   * The mirror among the calendars a server enumerates, so that asking for it
   * twice never produces a second one.
   *
   * <p>
   * Two signals, in decreasing order of confidence: the stored href, which is
   * the identity of the collection and the only one that survives a rename;
   * and the path the slug derives, which survives a disconnect — the setting
   * does not — because it depends on nothing but the slug.
   *
   * <p>
   * <b>Either may be null, and a null matches nothing.</b> The dedicated
   * calendar is the only destination with a derived path — an account's own
   * default and a user's own pick are both named, never computed — so those
   * two pass one signal and null for the other, and get the empty answer their
   * refusals are built on rather than a silent match.
   *
   * @param calendars what the server lists
   * @param storedHref the href recorded for this user, possibly absent
   * @param derivedHref the path the slug produces under the account's home, or
   *          null when the destination in question derives no path
   * @return the mirror, if it is there
   */
  private Optional<CalendarCollection> findMirror(List<CalendarCollection> calendars,
                                                  String storedHref,
                                                  String derivedHref) {
    String stored = CaldavSyncStorage.canonicalHref(storedHref);
    String derived = CaldavSyncStorage.canonicalHref(derivedHref);
    return calendars.stream()
                    .filter(calendar -> {
                      String href = CaldavSyncStorage.canonicalHref(calendar.href());
                      return href != null && (href.equals(stored) || href.equals(derived));
                    })
                    .findFirst();
  }

  /**
   * The pair row backing the mirror, created on first use.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   * @param mirror the destination
   * @return the pair
   */
  private CalendarSync mirrorPair(long userIdentityId, CaldavUserSetting settings, MirrorTarget mirror) {
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    List<CalendarSync> mirrors = caldavSyncStorage.getPairsByOrigin(userIdentityId, serverId, SyncOrigin.MIRROR);
    if (!mirrors.isEmpty()) {
      // More than one would mean the database could not stop it — its anchor
      // is null and no unique index covers NULL rows — so it is worth saying
      // rather than silently working on the first.
      if (mirrors.size() > 1) {
        LOG.warn("User {} holds {} mirror pairs on server {}; using the first", userIdentityId, mirrors.size(), serverId);
      }
      CalendarSync pair = mirrors.get(0);
      if (!StringUtils.equals(pair.getRemoteHref(), CaldavSyncStorage.canonicalHref(mirror.href()))) {
        pair.setRemoteHref(mirror.href());
        return caldavSyncStorage.savePair(pair);
      }
      return pair;
    }
    CalendarSync pair = new CalendarSync();
    pair.setUserIdentityId(userIdentityId);
    pair.setServerId(serverId);
    pair.setRemoteHref(mirror.href());
    pair.setOrigin(SyncOrigin.MIRROR);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return caldavSyncStorage.savePair(pair);
  }

  /**
   * The mirror pair, without creating anything.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   * @return the pair, or null when none exists yet
   */
  private CalendarSync existingMirrorPair(long userIdentityId, CaldavUserSetting settings) {
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    List<CalendarSync> mirrors = caldavSyncStorage.getPairsByOrigin(userIdentityId, serverId, SyncOrigin.MIRROR);
    return mirrors.isEmpty() ? null : mirrors.get(0);
  }

  /**
   * Writes the object, conditionally in both directions.
   *
   * <p>
   * A first push is conditional on the object <i>not</i> existing, so two
   * pushes racing cannot both believe they created it; a later push is
   * conditional on the ETag we last saw, so a concurrent edit surfaces as a
   * 412 rather than as a silent overwrite. When the object already exists and
   * holds content another client wrote, what is sent is the merge of the two,
   * never a replacement.
   *
   * @param endpoint the resolved server endpoint
   * @param settings the connected account
   * @param href where the object lives
   * @param ics the object this engine built
   * @param known the mapping row, or null on a first push
   * @param overwrite whether a repair is driving this write, in which case the
   *          conditional headers are dropped. The object a repair puts back is
   *          usually already on the server under a href this connector has lost
   *          track of, so a conditional write would be refused for ever.
   * @return the server's answer
   */
  private PutResult write(CalDavEndpoint endpoint,
                          CaldavUserSetting settings,
                          String href,
                          String ics,
                          ObjectSync known,
                          boolean overwrite) {
    try {
      if (known == null || StringUtils.isBlank(known.getEtag())) {
        if (overwrite) {
          // A repair with nothing recorded against this UID. The create-only
          // write below would send If-None-Match: * and be refused, because
          // the object it is trying to create is usually already there —
          // under a href this connector has lost track of. That is precisely
          // the state a repair exists to leave: forcing the write puts the
          // object back under the href being repaired and re-establishes the
          // mapping, where the create refused for ever.
          return calDavClient.overwriteObject(endpoint, href, ics, settings.getUsername(), settings.getPassword());
        }
        return calDavClient.putObject(endpoint, href, ics, settings.getUsername(), settings.getPassword());
      }
      CalendarObject existing = calDavClient.fetchObject(endpoint, href, settings.getUsername(), settings.getPassword());
      String merged = existing == null || StringUtils.isBlank(existing.calendarData()) ? ics
                                                                              : icsMerger.merge(existing.calendarData(), ics, false);
      if (overwrite) {
        // No precondition at all — and it has to be neither of the two the
        // client otherwise sends. The guard protects against clobbering a
        // change nobody has looked at, and a repair is the one case where
        // somebody has: verification read this object, compared it, and
        // decided the eXo copy is the one to keep. An If-Match would refuse
        // the write precisely when the object has drifted, which is the only
        // time a repair is attempted; an If-None-Match would refuse it
        // because the object exists, which it always does here.
        return calDavClient.overwriteObject(endpoint, href, merged, settings.getUsername(), settings.getPassword());
      }
      return calDavClient.updateObject(endpoint,
                                       href,
                                       merged,
                                       known.getEtag(),
                                       settings.getUsername(),
                                       settings.getPassword());
    } catch (CalDavAuthenticationException e) {
      throw new CaldavPushException(CREDENTIALS, "The stored CalDAV credentials were rejected", e);
    } catch (CalDavException e) {
      throw new CaldavPushException(SAVE, "The calendar object could not be written to " + href, e);
    }
  }

  /**
   * The user's connected account, or a refusal naming what is missing.
   *
   * @param userIdentityId identity of the user
   * @return the account
   */
  private CaldavUserSetting connectedSettings(long userIdentityId) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!connected(settings)) {
      throw new CaldavPushException(NOT_CONNECTED, "User " + userIdentityId + " has no connected CalDAV account");
    }
    return settings;
  }

  /**
   * Whether a stored account can actually be written to.
   *
   * <p>
   * Half a credential is not a connected account: letting it through sends an
   * unauthenticated request, which comes back as a credentials error telling
   * the user their password was rejected when none was ever stored.
   *
   * @param settings the stored account, possibly null
   * @return true when both halves of the credential are there
   */
  private boolean connected(CaldavUserSetting settings) {
    return settings != null && StringUtils.isNotBlank(settings.getUsername()) && StringUtils.isNotBlank(settings.getPassword());
  }

  /**
   * The endpoint the account's server resolves to.
   *
   * @param settings the connected account
   * @return the endpoint
   */
  private CalDavEndpoint endpointOf(CaldavUserSetting settings) {
    try {
      return calDavClient.endpoint(settings.getServerId(), settings.getUsername());
    } catch (CalDavException e) {
      throw new CaldavPushException(NOT_CONNECTED, "No CalDAV server resolves for this account", e);
    }
  }

  /**
   * A child collection's href under a home.
   *
   * @param home the calendar home
   * @param slug the collection name
   * @return the collection href, with its trailing slash
   */
  private String collectionHref(String home, String slug) {
    return StringUtils.appendIfMissing(home, "/") + slug + "/";
  }

  /**
   * Where one event's object lives inside a collection. The filename
   * convention the browser push has always used, kept so that objects written
   * before this migration are found rather than duplicated.
   *
   * <p>
   * Static and package-visible so that the relocation of EXO-89761 writes a
   * moved copy under exactly this name rather than under a second convention
   * of its own. A filename rule duplicated across two classes is one that
   * drifts, and the first symptom of the drift would be a moved copy the next
   * ordinary push no longer recognises — a duplicate in the user's calendar.
   * Destination <i>resolution</i> stays where EXO-89760 put it; this is only
   * the leaf name inside whatever destination that resolves to.
   *
   * @param collectionHref the collection
   * @param icsUid the iCalendar UID
   * @return the object href
   */
  static String objectHref(String collectionHref, String icsUid) {
    return StringUtils.appendIfMissing(collectionHref, "/") + icsUid + ".ics";
  }

  /**
   * The mapping with everything the remote side owned cleared, kept as the
   * record that this event was once pushed.
   *
   * @param mapping the mapping to clear
   * @return the same mapping, without its remote identity
   */
  private ObjectSync cleared(ObjectSync mapping) {
    mapping.setRemoteHref(null);
    mapping.setEtag(null);
    mapping.setLastSync(new Date());
    return mapping;
  }

}
