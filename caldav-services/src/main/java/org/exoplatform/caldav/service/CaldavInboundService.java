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
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.EventAttendee;
import org.exoplatform.agenda.model.RemoteEvent;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
import org.exoplatform.agenda.model.EventFilter;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.RequestLifeCycle;
import java.util.Collection;
import org.exoplatform.caldav.client.SyncCollectionResult;
import java.util.concurrent.ConcurrentHashMap;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.ics.IcsEventMapper;
import org.exoplatform.caldav.ics.IcsParser;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Brings the events of a materialised collection into the eXo calendar
 * standing for it.
 *
 * <p>
 * This is the half that makes a materialised calendar more than a shell. Until
 * it runs, the user sees a calendar with the right name and no events in it,
 * which is why the browser overlay cannot be retired before this.
 */
@Service
public class CaldavInboundService {

  /**
   * The name this add-on registers itself under as an agenda remote provider,
   * in caldav-configuration.xml. Agenda resolves the provider by name when it
   * stores what an event is called on the server, and a record naming none is
   * read as an instruction to delete the mapping rather than keep it.
   */
  private static final String    CONNECTOR_NAME = "agenda.caldavCalendar";

  private static final Log       LOG = ExoLogger.getLogger(CaldavInboundService.class);

  /** How many mapping rows are walked at a time. */
  private static final int       OBJECT_PAGE_SIZE = 200;

  /**
   * How long a collection that did not answer is left unasked.
   *
   * <p>
   * Long enough that a user clicking repeatedly pays the timeout once rather
   * than every time, short enough that a server coming back is noticed within
   * a few minutes without anyone doing anything.
   */
  private static final Duration  NOT_ANSWERING_FOR = Duration.ofMinutes(10);

  /**
   * When each collection last failed to answer at all.
   *
   * <p>
   * In memory on purpose: it is a hint about a server's mood, not a fact about
   * the binding, and a restart should forget it. Recording it in the schema
   * would turn a passing outage into stored state someone later has to explain.
   */
  private final Map<Long, Instant> notAnswering = new ConcurrentHashMap<>();

  /**
   * How a copy eXo wrote names the event it stands for.
   *
   * <p>
   * Both the {@code URL} property and the "Event link" line of the description
   * carry {@code <deployment>/portal/<site>/agenda?eventId=<id>} — the address
   * {@code EventIcsBuilder.eventUrl} derives from
   * {@code NotificationUtils.getEventURL}, whose domain is the deployment's
   * configured one. Group 1 is that deployment: the authority of the address,
   * with or without a scheme, because the copy EXO-89824 was diagnosed from
   * carried its link without one. Nothing but this exact shape is recognised,
   * so a link to anything else — a conference, an intranet page, another
   * portal's wiki — is not a copy and is never reported as one.
   *
   * <p>
   * The address is <b>anchored</b>: it counts only where it begins the text or
   * follows a delimiter — whitespace, a bracket, a quote, a comma or a
   * semicolon — never where it begins in the middle of something longer. Two
   * mechanisms were available for this and the reason for choosing the anchor
   * is worth keeping. Unanchored, group 1 could start at any path segment, so
   * a deployment whose {@code exo.base.url} carries a path —
   * {@code https://exo.example.test/intranet} — read {@code intranet} out of
   * its <em>own</em> copies' {@code .../intranet/portal/dw/agenda?eventId=42},
   * compared it against its own authority {@code exo.example.test} and
   * reported itself as somebody else. The alternative — widening group 1 to
   * everything before {@code /portal/} and comparing that whole prefix — reads
   * such a deployment correctly, but it lets the group cross {@code /}, and
   * then any text glued to the front of the link (a label with no space after
   * its colon, a server's own markup) is swallowed into the answer and the
   * deployment is misnamed. Both failures point the same way, at a false
   * accusation, and this one accuses nobody: a link whose authority cannot be
   * read off cleanly is simply not recognised as a copy. The price is a blind
   * spot — a <em>foreign</em> deployment serving from a path is not seen at
   * all — which is the same kind of blindness as the EXO-89751 floor below,
   * and the right one to prefer.
   *
   * <p>
   * Not the pattern that strips agenda's invitation text off an imported
   * description ({@link org.exoplatform.agenda.util.InvitationText},
   * EXO-90227). That one asks a different question — is this whole line a
   * label and an event link — and reads nothing off the address, so it can
   * afford to recognise a link under a path where this one, which reads the
   * authority, must not. The two are kept apart on purpose.
   */
  private static final Pattern EXO_EVENT_LINK =
                                              Pattern.compile("(?<![^\\s<>\"'()\\[\\],;])(?:https?://)?([^/\\s<>\"']+)"
                                                  + "/portal/[^/\\s<>?]+/agenda\\?eventId=\\d+",
                                                              Pattern.CASE_INSENSITIVE);

  /**
   * The (account, deployment) pairs already said at WARN — see
   * {@link #warnOnceIfAnotherDeploymentWrites}. The deployment is in the key
   * because an account can hold copies of more than one, and saying only the
   * first is how a detection latches onto the wrong writer for ever.
   */
  private final Set<String>        foreignDeploymentsSaid = ConcurrentHashMap.newKeySet();

  /**
   * When each (account, deployment) pair was last recorded on its server's
   * row, in nanoseconds — see {@link #warnOnceIfAnotherDeploymentWrites}.
   *
   * <p>
   * Separate from {@link #foreignDeploymentsSaid}, and deliberately: the log
   * line is said once per process, because a second identical line teaches
   * nobody anything, while the row has to keep being refreshed or the entry
   * ages out from under a condition that still holds. Stamped only when a
   * foreign copy is actually found, so an account that has none is examined
   * exactly as often as it was before this record existed.
   */
  private final Map<String, Long>  foreignDeploymentsRecorded = new ConcurrentHashMap<>();

  /** A nanosecond, so the record interval reads in seconds where it is used. */
  private static final long        NANOS_PER_SECOND           = 1_000_000_000L;

  /**
   * This deployment's own address as its copies name it, kept once resolved —
   * see {@link #ownDeployment()}. Null until the portal could be asked.
   */
  private volatile String          ownDeployment;

  @Autowired
  private CalDavClient           calDavClient;

  @Autowired
  private CaldavSyncStorage      caldavSyncStorage;

  @Autowired
  private CaldavConnectorStorage caldavConnectorStorage;

  @Autowired
  private AgendaEventService     agendaEventService;

  @Autowired
  private AgendaEventAttendeeService agendaEventAttendeeService;

  /**
   * Agenda's remote-event mapping, read here rather than only written.
   *
   * <p>
   * The import records a remote identity on every event it creates
   * (see {@link #remoteIdentity(IcsEvent)}); this is the half that reads it
   * back, so an object whose event is already in the calendar is adopted
   * instead of created a second time.
   */
  @Autowired
  private AgendaRemoteEventService agendaRemoteEventService;

  /**
   * Told, before agenda is asked to apply a change read from the server, which
   * copy the change came from — so the listener that carries agenda's
   * broadcast to every holder of a copy leaves that one alone (EXO-90190).
   */
  @Autowired
  private CaldavEventPropagationService caldavEventPropagationService;

  @Autowired
  private IcsParser              icsParser;

  @Autowired
  private IcsEventMapper         icsEventMapper;

  /**
   * Turns the owner's PARTSTAT on one of eXo's own copies into an agenda
   * response, for the one object this pass recognises as eXo's and drops
   * (EXO-89807). The narrow inbound mapping of EXO-89681, reused rather than
   * repeated: this half decides <em>when</em> to ask, never what an answer
   * means.
   */
  @Autowired
  private CaldavAnswerAdoptionService caldavAnswerAdoptionService;

  /**
   * Where a foreign deployment is recorded so an administrator can see it.
   */
  @Autowired
  private CaldavServerService         caldavServerService;

  /**
   * How often one account may refresh its server's record of a foreign
   * deployment.
   *
   * <p>
   * An hour. The record is day-grained and kept for a month, so nothing finer
   * changes what the drawer shows; what this bounds is a deployment with
   * hundreds of accounts on one server, each of them sweeping every few
   * minutes, all writing the same row. It is a throttle on a write, not on the
   * detection: an account that has never been found to hold a foreign copy is
   * examined on every object of every pass, exactly as before.
   */
  @Value("${exo.agenda.caldav.mirror.foreignWriterRecordSeconds:3600}")
  private long                        foreignWriterRecordSeconds;

  /**
   * How many days one calendar-query asks for. Small enough that a busy
   * calendar answers inside the client's request timeout.
   */
  @Value("${exo.agenda.caldav.sync.sliceDays:30}")
  private long                   sliceDays;

  /**
   * Imports the objects of one bound collection over a window.
   *
   * <p>
   * A window rather than the whole collection: a calendar with ten years of
   * history behind it would otherwise cost a full download on a page load. The
   * window is the caller's to choose, and widening it is a decision about
   * cost, not about correctness.
   *
   * @param userIdentityId identity of the user
   * @param username their eXo login, which the credentials provider maps to
   *          their account on the server
   * @param pair the binding whose collection is read
   * @param calendar the eXo calendar standing for it
   * @param from beginning of the window
   * @param to end of the window
   * @return how many events were created or updated
   */
  public int importInto(long userIdentityId, String username, CalendarSync pair, Calendar calendar, Instant from, Instant to) {
    return importInto(userIdentityId, username, pair, calendar, from, to, adoptable(userIdentityId, calendar, from, to));
  }

  /**
   * Imports the objects of one bound collection over a window, consulting a
   * shared index of the events already in the calendar.
   *
   * @param userIdentityId identity of the user
   * @param username their eXo login, which the credentials provider maps to
   *          their account on the server
   * @param pair the binding whose collection is read
   * @param calendar the eXo calendar standing for it
   * @param from beginning of the window
   * @param to end of the window
   * @param adoptable the events of this calendar an object may be adopted
   *          into, built at most once for the whole pass
   * @return how many events were created or updated
   */
  private int importInto(long userIdentityId,
                         String username,
                         CalendarSync pair,
                         Calendar calendar,
                         Instant from,
                         Instant to,
                         AdoptableEvents adoptable) {
    if (pair == null || calendar == null) {
      return 0;
    }
    CaldavUserSetting settings = settingsFor(userIdentityId, pair);
    if (settings == null) {
      return 0;
    }
    CalDavEndpoint endpoint = calDavClient.endpoint(pair.getServerId(), username);
    int touched = 0;
    // The window is walked in slices rather than asked for at once. A
    // calendar-query returns the full ICS of everything it covers, so a year
    // asked for in one REPORT is one enormous response — observed live as a
    // request timeout against a real calendar, with the whole collection lost
    // for it. Sliced, each round trip is small, a slow calendar still makes
    // progress, and one slice that fails costs only its own days.
    for (Instant sliceStart = from; sliceStart.isBefore(to);) {
      Instant sliceEnd = sliceStart.plus(Duration.ofDays(sliceDays));
      if (sliceEnd.isAfter(to)) {
        sliceEnd = to;
      }
      touched += importSlice(userIdentityId, pair, calendar, settings, endpoint, sliceStart, sliceEnd, adoptable);
      sliceStart = sliceEnd;
    }
    return touched;
  }

  /**
   * Imports one slice of the window.
   *
   * @param userIdentityId identity of the user
   * @param pair the binding being read
   * @param calendar the eXo calendar standing for it
   * @param settings the connected account
   * @param endpoint the declared server
   * @param from beginning of the slice
   * @param to end of the slice
   * @param adoptable the events of this calendar an object may be adopted into
   * @return how many events this slice created
   */
  private int importSlice(long userIdentityId,
                          CalendarSync pair,
                          Calendar calendar,
                          CaldavUserSetting settings,
                          CalDavEndpoint endpoint,
                          Instant from,
                          Instant to,
                          AdoptableEvents adoptable) {
    List<CalendarObject> objects;
    try {
      objects = calDavClient.calendarQuery(endpoint,
                                           collectionUrl(pair),
                                           from,
                                           to);
    } catch (CalDavException e) {
      // One slice a server cannot answer must not cost the rest of the window,
      // and one collection must not cost the others. The calendar keeps what
      // it already holds rather than being emptied on a bad round trip.
      LOG.warn("The objects of collection {} between {} and {} could not be read; those days are left as they are",
               pair.getRemoteHref(),
               from,
               to,
               e);
      return 0;
    }
    int touched = 0;
    for (CalendarObject object : objects) {
      if (importObject(userIdentityId, pair, calendar, object, adoptable)) {
        touched++;
      }
    }
    return touched;
  }

  /**
   * Imports one calendar object.
   *
   * @param userIdentityId identity of the user
   * @param pair the binding being read
   * @param calendar the eXo calendar standing for it
   * @param object the object as the server sent it
   * @param adoptable the events of this calendar an object may be adopted into
   * @return true when an event was created or updated
   */
  private boolean importObject(long userIdentityId,
                               CalendarSync pair,
                               Calendar calendar,
                               CalendarObject object,
                               AdoptableEvents adoptable) {
    List<IcsEvent> parsed = icsParser.parse(object.calendarData());
    if (parsed.isEmpty()) {
      return false;
    }
    IcsEvent master = parsed.get(0);
    warnOnceIfAnotherDeploymentWrites(pair, object, master);
    if (StringUtils.isNotBlank(master.getOccurrenceId())) {
      // An object holding only overrides, with the series living elsewhere.
      // Creating them as events of their own would show the amendments as
      // separate meetings beside a series that already covers those days.
      LOG.debug("Object {} carries only overrides and is left for the occurrence pass", object.href());
      return false;
    }
    if (isMirrorOwned(pair, master.getUid())) {
      // Read before it is dropped, and that ordering is the whole of
      // EXO-89807. The object is in hand, its owner's answer is on it, and
      // this is the only reader that was told it changed.
      adoptAnswerOnCopy(userIdentityId, pair, object, master.getUid());
      // A copy eXo wrote itself — into this user's mirror, or into another
      // user's on an account they share. Importing it would show the user a
      // second, personal event standing for a meeting eXo already holds.
      LOG.debug("Object {} is a copy eXo wrote into a mirror and is not imported back", object.href());
      return false;
    }
    ObjectSync known = caldavSyncStorage.getObjectByUid(pair.getId(), master.getUid());
    if (known != null && StringUtils.isNotBlank(known.getEtag()) && known.getEtag().equals(object.etag())) {
      // The server says nothing changed. Re-writing the event would bump its
      // modification date and make every sync look like an edit to anything
      // watching agenda.
      return false;
    }
    if (known != null) {
      return update(userIdentityId, pair, calendar, object, master, known, parsed);
    }
    return create(userIdentityId, pair, calendar, object, master, parsed, adoptable);
  }

  /**
   * Records the owner's answer off a copy eXo wrote, on its way to dropping it.
   *
   * <p>
   * <b>Why the answer is read here at all.</b> The mirror is eXo's projection
   * and the one field allowed back is the owner's own PARTSTAT (EXO-89681),
   * whose adoption lives in the verification pass and is gated there on the
   * copy's ETag having moved — the server's own statement that a client wrote
   * after eXo did. On a server that records an answer <em>without</em> moving
   * that value the gate never opens, and the answer is never read: measured on
   * BlueMind, where a copy carrying {@code PARTSTAT=ACCEPTED} was reported by
   * the collection's own sync report while its ETag, and even its
   * LAST-MODIFIED, stood exactly where eXo's write had left them (EXO-89807).
   *
   * <p>
   * <b>Why here is the right place and not a second trick for the gate.</b>
   * Two readers meet the same changed object in the same sweep and only one of
   * them can act: this one is <em>told</em> the object changed — by the sync
   * report, which is evidence independent of any ETag — and already holds its
   * body, having fetched it; the other has the permission to adopt and no way
   * to see the change. Widening the gate would be teaching it to distrust the
   * one thing it is built on. This simply lets the reader that has the evidence
   * hand it to the reader that has the permission.
   *
   * <p>
   * <b>What it does not do.</b> It does not import: the copy is still dropped,
   * on the very next line, because importing it is the duplicate personal event
   * EXO-89802 exists to prevent. Adoption reads one field off it and changes
   * nothing else.
   *
   * <p>
   * <b>The direction question the ETag was answering.</b> This path sees eXo's
   * own writes too — a copy eXo has just pushed is a change the sync report
   * reports — so it cannot tell "answered on the phone" from "answered in eXo"
   * by provenance. It does not need to: a copy eXo wrote carries the answer
   * agenda already holds, which the adoption compares and treats as nothing to
   * do, and a copy carrying NEEDS-ACTION is never an answer at all, so eXo's
   * own writing can neither change nor erase a recorded answer. What remains is
   * one narrow window — the user changes an answer in eXo, and the sweep reads
   * a copy still carrying the previous one before the push that carries it out
   * has landed. It is the same window the verification pass lives with (a
   * server rewriting a copy for its own reasons moves the ETag over eXo's older
   * answer just as well), it is bounded by that push, and the alternative —
   * refusing every answer this server sends — is the defect being fixed.
   *
   * <p>
   * Never allowed to fail the pass. An answer that cannot be read is one field
   * lost on one object; the collection around it still has to import.
   *
   * @param userIdentityId identity of the user whose account is being read
   * @param pair the binding being read, which is never the mirror's own
   * @param object the copy as the server sent it, body included
   * @param icsUid the object's iCalendar UID, already known to be one of eXo's
   */
  private void adoptAnswerOnCopy(long userIdentityId, CalendarSync pair, CalendarObject object, String icsUid) {
    try {
      if (StringUtils.isBlank(object.calendarData())) {
        return;
      }
      Long localEventId = caldavSyncStorage.getMirrorEventId(userIdentityId, pair.getServerId(), icsUid);
      if (localEventId == null || localEventId <= 0) {
        // The copy is eXo's by UID but names no event in THIS user's mirror:
        // another user's copy on an account they share (EXO-90190), an
        // interrupted push, an event since deleted. Nothing to do, and nothing
        // wrong — whatever answer it carries belongs to whoever wrote it, and
        // recording it as this user's would be the attribution error the
        // user-scoped question exists to prevent.
        LOG.debug("The copy at {} is eXo's but not user {}'s own; no answer is read off it", object.href(), userIdentityId);
        return;
      }
      CaldavAnswerAdoptionService.Outcome outcome = caldavAnswerAdoptionService.adoptAnswer(userIdentityId,
                                                                                           localEventId,
                                                                                           object.calendarData());
      if (outcome == CaldavAnswerAdoptionService.Outcome.ADOPTED) {
        LOG.debug("An answer of user {} was read off their own copy at {} before it was left where it is",
                  userIdentityId,
                  object.href());
      }
    } catch (RuntimeException | LinkageError e) {
      // Deliberately swallowed to a line. This runs inside the import of one
      // object among many, and one unreadable answer must not cost the
      // collection its import — the same discipline every other per-object
      // failure on this pass follows.
      LOG.debug("The answer on the copy at {} could not be read", object.href(), e);
    }
  }

  /**
   * Whether this object is one eXo copied into the user's mirror, and so must
   * never be imported back as an event of its own.
   *
   * <p>
   * The rule that replaces "skip the whole mirror collection". That skip was
   * protection by <em>location</em>: it worked only for as long as the mirror
   * lived somewhere the inbound half never read. Point the mirror at a
   * calendar the user also synchronises and the location says nothing, while
   * the pair-scoped identity lookup below cannot help either — a mirror copy
   * carries its mapping on the MIRROR pair, so this pair's lookup finds
   * nothing, calls it new, and creates a duplicate personal event out of eXo's
   * own copy of a space meeting.
   *
   * <p>
   * Asked before the identity lookup rather than after, so it governs an
   * update as well as a create: an object that is ours is not ours a little
   * less because this pair happens to hold a stale row for the same UID.
   *
   * <p>
   * Asked for the account the collection sits in — not for the reading user,
   * and not for the whole server registration (EXO-90190). The first version
   * of this rule asked "did <em>this user's</em> mirror write it?", which is
   * one level short: on an account two eXo users share, the copy was written
   * by the other one, the user-scoped question answered no, and the copy was
   * imported as a genuine remote event — then pushed back under a fresh UID,
   * imported by the other user in turn, and so on every sweep. A mirror copy
   * is eXo's whoever wrote it, and the mapping table says so for every user
   * at once. The second version asked for every account of the server, which
   * is one level too far: an externally organised meeting keeps the
   * organiser's UID, so a third user with their own account on the same
   * server found their own copy "owned" and never saw the meeting. Copies
   * live in accounts; the storage names the account by the collection's
   * calendar home.
   *
   * <p>
   * The mirror pair itself is exempt. Reading the mirror back is not importing
   * a foreign object, and answering true there would make the mirror unable to
   * reconcile the copies it owns. The sweep never reads through a mirror pair
   * in the first place; the exemption states the intent where the rule lives.
   *
   * @param pair the binding being read
   * @param icsUid the object's iCalendar UID
   * @return true when a mirror pair of any user on the pair's account already
   *         maps that UID
   */
  private boolean isMirrorOwned(CalendarSync pair, String icsUid) {
    if (pair.getOrigin() == SyncOrigin.MIRROR) {
      return false;
    }
    return caldavSyncStorage.isMirrorOwned(pair.getServerId(), pair.getRemoteHref(), icsUid);
  }

  /**
   * What this event is called on the server, recorded against it.
   *
   * <p>
   * An imported event used to be created with no remote identity at all, and
   * for as long as nothing pushed from a materialised calendar that cost
   * nothing. Now that such a calendar synchronises both ways, it costs a
   * duplicate every time: asked to write the event back, the push looks for
   * the identifier this event is known by, finds none, mints a fresh one and
   * writes a <em>second</em> object — leaving the one it was imported from
   * untouched. Rename an imported meeting in eXo and it appears twice on the
   * account it came from.
   *
   * <p>
   * The UID is the server's own, taken from the object being imported, so a
   * later push addresses the object this event came from rather than a new
   * one beside it.
   *
   * <p>
   * Recorded on update as well as on creation, and from the object rather than
   * from what agenda already holds: the object's UID <em>is</em> the identity,
   * the two agree by construction wherever a record exists, and an event
   * imported before EXO-89530 — which has no record — gets one on its first
   * remote edit instead of a duplicate on its first push.
   *
   * @param master the parsed remote event
   * @return the remote identity to record with the event
   */
  private RemoteEvent remoteIdentity(IcsEvent master) {
    RemoteEvent remoteEvent = new RemoteEvent();
    remoteEvent.setRemoteId(master.getUid());
    // Named, or agenda reads the record as an instruction to delete the
    // mapping rather than store it.
    remoteEvent.setRemoteProviderName(CONNECTOR_NAME);
    return remoteEvent;
  }

  /**
   * The attendee standing for the user whose calendar this is.
   *
   * <p>
   * An event with no attendee is not merely missing a detail: agenda's default
   * view — "my events" — filters on the attendee table with an inner join, so
   * an event nobody attends is invisible in the very calendar it was imported
   * into. It shows only once the user thinks to switch the filter to every
   * event, which reads as "the import did nothing".
   *
   * <p>
   * Every event agenda's own form creates carries its author as an attendee;
   * an imported one has to as well. The response is ACCEPTED rather than
   * NEEDS_ACTION because the user is not being invited to anything — this is
   * their own calendar, read from their own account, and asking them to answer
   * an invitation they already accepted elsewhere would be noise.
   *
   * @param userIdentityId identity of the user
   * @return the attendee to record on an imported event
   */
  private EventAttendee selfAttendee(long userIdentityId) {
    EventAttendee attendee = new EventAttendee();
    attendee.setIdentityId(userIdentityId);
    attendee.setResponse(EventAttendeeResponse.ACCEPTED);
    return attendee;
  }

  /**
   * The attendees to hand agenda when updating an event it already holds.
   *
   * <p>
   * agenda reads this list as the whole truth about who attends: whoever it
   * omits is deleted. An empty list is therefore not "leave the attendees
   * alone" but "remove them all" — so every remote edit used to strip the
   * event bare, including of anyone the user had added on this side. The
   * attendees are read back and returned as they stand instead.
   *
   * <p>
   * The owner is added when missing, which repairs events imported before
   * selfAttendee existed: they were created with no attendee at all, and would
   * otherwise stay invisible until re-imported from scratch.
   *
   * @param eventId the event agenda already holds
   * @param userIdentityId identity of the user
   * @return the attendees the event should keep
   */
  private List<EventAttendee> keptAttendees(long eventId, long userIdentityId) {
    List<EventAttendee> attendees = new ArrayList<>();
    try {
      attendees.addAll(agendaEventAttendeeService.getEventAttendees(eventId).getEventAttendees());
    } catch (Exception e) { // NOSONAR the attendees are a detail; the edit itself matters more
      LOG.debug("Attendees of event {} could not be read; the owner alone is kept", eventId, e);
    }
    if (attendees.stream().noneMatch(attendee -> attendee.getIdentityId() == userIdentityId)) {
      attendees.add(selfAttendee(userIdentityId));
    }
    return attendees;
  }

  /**
   * Creates the agenda event for an object seen for the first time.
   *
   * @param userIdentityId identity of the user
   * @param pair the binding being read
   * @param calendar the eXo calendar standing for it
   * @param object the object as the server sent it
   * @param master the parsed master event
   * @param parsed every event the object carried, master first, so the
   *          overrides and exclusions that travel with a series are applied to
   *          the event this call has just created
   * @param adoptable the events of this calendar an object may be adopted
   *          into instead of being created again
   * @return true when the event was created or adopted
   */
  private boolean create(long userIdentityId,
                         CalendarSync pair,
                         Calendar calendar,
                         CalendarObject object,
                         IcsEvent master,
                         List<IcsEvent> parsed,
                         AdoptableEvents adoptable) {
    Long already = adoptable == null ? null : adoptable.forRemoteId(master.getUid());
    if (already != null) {
      return adopt(userIdentityId, pair, calendar, object, master, parsed, already, adoptable);
    }
    Event event = icsEventMapper.toEvent(master, calendar.getId());
    Event created;
    try {
      // sendInvitation is false, and it is the most consequential argument
      // here. These attendees were invited by whoever organised the meeting,
      // on a server the user already reads; inviting them again because eXo
      // has just noticed the event would send real mail to real people for
      // something that happened days ago.
      //
      // The ATTENDEE lines the object carries are still not mapped. Binding a
      // server-provided address to an eXo identity is a trust-boundary
      // decision — an ATTENDEE line is content, and content must not name a
      // platform user — and it deserves its own review rather than riding
      // along here. The one attendee recorded is the calendar's own owner,
      // whose identity the caller already holds; see selfAttendee.
      created = agendaEventService.createEvent(event,
                                               List.of(selfAttendee(userIdentityId)),
                                               List.of(),
                                               List.of(),
                                               List.of(),
                                               remoteIdentity(master),
                                               false,
                                               userIdentityId);
    } catch (Exception e) { // NOSONAR agenda declares several checked exceptions here
      // One object agenda refuses must not stop the collection. The failure is
      // recorded nowhere, so the next run tries it again.
      LOG.warn("The event of object {} could not be created in calendar {}", object.href(), calendar.getId(), e);
      return false;
    }
    ObjectSync mapping = new ObjectSync();
    mapping.setCalendarSyncId(pair.getId());
    mapping.setIcsUid(master.getUid());
    mapping.setLocalEventId(created.getId());
    mapping.setRemoteHref(object.href());
    mapping.setEtag(object.etag());
    mapping.setLastSync(new Date());
    ObjectSync recorded = caldavSyncStorage.saveObject(mapping);
    applyOccurrences(userIdentityId, calendar, created.getId(), mappingIdOf(recorded), master, parsed);
    return true;
  }

  /**
   * Binds this pair to the event the calendar already holds for that object,
   * rather than creating a second one beside it.
   *
   * <p>
   * The mapping table answers "has <em>this pair</em> seen this object", and
   * that question is only as durable as the pair. Replace the pair — a
   * disconnect that dropped it, a binding pruned because its calendar could
   * not be read for one pass, a collection re-bound by hand — and the answer
   * becomes no for every object in the collection, so every event is created
   * again beside the one already there.
   *
   * <p>
   * The event's remote identity outlives the pair, which is what makes this
   * possible: the import records the server's own UID against every event it
   * creates ({@link #remoteIdentity(IcsEvent)}), and that record belongs to
   * the event and the user, not to the binding.
   *
   * <p>
   * <b>Adopting is scoped so it cannot cross a calendar, a user or a server.</b>
   * A UID is unique on the server that issued it and nowhere else, so a
   * lookup wide enough to see two accounts could attach one person's meeting
   * to another's. The candidates are therefore drawn from the events of
   * <em>this calendar alone</em> ({@link AdoptableEvents}) — one calendar
   * belongs to one user and is bound by one pair, so the user and the server
   * come with it — and an event already mapped by any pair is excluded, so a
   * mirror copy cannot be hijacked by the collection it is a copy of.
   *
   * <p>
   * The mapping is recorded with no etag, then the object is applied through
   * the ordinary update path: adopting says which event this object is, not
   * that the two already agree.
   *
   * @param userIdentityId identity of the user
   * @param pair the binding being read
   * @param calendar the eXo calendar standing for it
   * @param object the object as the server sent it
   * @param master the parsed master event
   * @param parsed every event the object carried, master first
   * @param localEventId the event this calendar already holds for that UID
   * @param adoptable the index the candidate came from, so it is not offered
   *          twice
   * @return true when the adopted event was brought in line with the object
   */
  private boolean adopt(long userIdentityId,
                        CalendarSync pair,
                        Calendar calendar,
                        CalendarObject object,
                        IcsEvent master,
                        List<IcsEvent> parsed,
                        long localEventId,
                        AdoptableEvents adoptable) {
    ObjectSync mapping = new ObjectSync();
    mapping.setCalendarSyncId(pair.getId());
    mapping.setIcsUid(master.getUid());
    mapping.setLocalEventId(localEventId);
    mapping.setRemoteHref(object.href());
    // Deliberately no etag. The event is this object's, which is all adopting
    // establishes; whether the two sides agree is the update's question, and
    // recording an etag here would answer it by assertion.
    mapping.setLastSync(new Date());
    ObjectSync saved = caldavSyncStorage.saveObject(mapping);
    if (saved == null) {
      saved = mapping;
    }
    adoptable.taken(master.getUid());
    LOG.info("Object {} is already in calendar {} as event {}; it is adopted rather than imported a second time",
             object.href(),
             calendar.getId(),
             localEventId);
    return update(userIdentityId, pair, calendar, object, master, saved, parsed);
  }

  /**
   * Applies a remote change to an event already imported.
   *
   * <p>
   * The rule is the newest wins, and the tie goes to the server. Not because
   * the server is more trustworthy, but because the tie is unresolvable and
   * one side has to be named in advance: a rule nobody can predict is worse
   * than a rule that occasionally loses the wrong edit. Remote is the side
   * the user's other clients write to, so it is the side more likely to hold
   * what they meant.
   *
   * <p>
   * A local event edited more recently is left alone. It is not lost — the
   * outbound push carries it — and the etag is deliberately <em>not</em>
   * recorded, so the next run reconsiders instead of believing the two sides
   * agree.
   *
   * <h4>The identity that the update threw away (EXO-90190)</h4>
   *
   * <p>
   * Agenda's update takes the event's remote identity as an argument and reads
   * a null there as "forget it": the mapping row goes, exactly as a blank
   * identifier or an unnamed provider would make it go. This method passed
   * null. So every remote edit applied here silently unrecorded what the event
   * is called on the server, and the next push of that event — which the
   * update itself set off, through agenda's broadcast — found no identifier,
   * minted one, and wrote a second object beside the one it had just read.
   * Two eXo users on one account then imported each other's second objects,
   * and one edit became three meetings. The identity is now recorded again
   * with the update, in the shape {@link #remoteIdentity(IcsEvent)} gives a
   * new import, and the change is announced as the server's own before agenda
   * is asked, so its own copy is not written back to at all.
   *
   * @param userIdentityId identity of the user
   * @param pair the binding being read
   * @param calendar the eXo calendar standing for it
   * @param object the object as the server sent it
   * @param master the parsed master event
   * @param known the mapping recorded when it was imported
   * @param parsed every event the object carried, master first, so an override
   *          added or an occurrence cancelled since the last read lands with
   *          the master's own change rather than a pass later
   * @return true when the event was updated
   */
  private boolean update(long userIdentityId,
                         CalendarSync pair,
                         Calendar calendar,
                         CalendarObject object,
                         IcsEvent master,
                         ObjectSync known,
                         List<IcsEvent> parsed) {
    if (known.getLocalEventId() == null) {
      // A mapping with no event behind it: the import was interrupted between
      // creating the event and recording it, or the event has since been
      // deleted. Either way there is nothing to update, and the mapping is
      // dropped so the object is imported afresh.
      LOG.debug("Mapping {} has no event behind it; it is dropped so the object can be imported again", known.getId());
      dropMapping(known);
      return false;
    }
    Event local = agendaEventService.getEventById(known.getLocalEventId());
    if (local == null) {
      LOG.debug("Event {} is gone; its mapping is dropped so the object can be imported again", known.getLocalEventId());
      dropMapping(known);
      return false;
    }
    if (isLocalNewer(local, master)) {
      // Left for the outbound half, and the etag is not recorded: the next run
      // must look again rather than assume the two sides agree.
      LOG.debug("Event {} was edited here more recently than on the server; the remote change is not applied",
                local.getId());
      return false;
    }
    Event updated = icsEventMapper.toEvent(master, calendar.getId());
    updated.setId(local.getId());
    updated.setParentId(local.getParentId());
    updated.setCreatorId(local.getCreatorId());
    long originId = mappingIdOf(known);
    caldavEventPropagationService.changedOnTheServer(local.getId(), originId);
    try {
      // sendInvitation false, for the same reason as on creation: these people
      // were invited by whoever organised the meeting, and telling them again
      // because eXo noticed an edit would send real mail about something that
      // already happened.
      agendaEventService.updateEvent(updated,
                                     keptAttendees(local.getId(), userIdentityId),
                                     List.of(),
                                     List.of(),
                                     List.of(),
                                     remoteIdentity(master),
                                     false,
                                     userIdentityId);
    } catch (Exception e) { // NOSONAR agenda declares several checked exceptions here
      caldavEventPropagationService.notChangedAfterAll(local.getId());
      LOG.warn("The event of object {} could not be updated in calendar {}", object.href(), calendar.getId(), e);
      return false;
    }
    known.setEtag(object.etag());
    known.setRemoteHref(object.href());
    known.setLastSync(new Date());
    caldavSyncStorage.saveObject(known);
    applyOccurrences(userIdentityId, calendar, local.getId(), originId, master, parsed);
    return true;
  }

  /**
   * Forgets a mapping, when there is one recorded to forget.
   *
   * <p>
   * A mapping the storage has never seen has no identifier, and asking it to
   * delete one would fail on the null rather than on anything real. That is
   * not a hypothetical: an adopted mapping is handed straight to the update
   * path, and a storage that gave nothing back leaves it unpersisted.
   *
   * @param mapping the mapping to forget
   */
  private void dropMapping(ObjectSync mapping) {
    if (mapping.getId() != null) {
      caldavSyncStorage.deleteObject(mapping.getId());
    }
  }

  /**
   * Whether the eXo copy has been edited more recently than the remote one.
   *
   * <p>
   * An object that carries no LAST-MODIFIED answers false — the server said
   * nothing about when it changed, and refusing its change on that silence
   * would freeze the event here for good.
   *
   * @param local the event as agenda holds it
   * @param master the parsed remote event
   * @return true when the local copy is strictly newer
   */
  private boolean isLocalNewer(Event local, IcsEvent master) {
    if (master.getUpdated() == null || local.getUpdated() == null) {
      return false;
    }
    return local.getUpdated().toInstant().isAfter(master.getUpdated());
  }

  /**
   * Applies what the object says about individual occurrences of a series.
   *
   * <p>
   * Two things travel with a master and mean nothing without it: an override
   * amends one occurrence, an excluded date cancels one. Agenda expresses both
   * through the same door — an <em>exceptional occurrence</em>, which is the
   * series' shape for one date made editable on its own. So an override
   * becomes one that is then updated, and an exclusion becomes one that is
   * then deleted.
   *
   * <p>
   * A series with nothing to say about its occurrences costs nothing here,
   * which is almost every series.
   *
   * @param userIdentityId identity of the user
   * @param calendar the eXo calendar standing for the collection
   * @param masterEventId the series in agenda
   * @param objectSyncId the mapping the object was read through — an override
   *          and its series share one object, so every occurrence applied here
   *          is the server's own change to that mapping's copy; 0 when the
   *          mapping is not recorded yet
   * @param master the parsed master
   * @param parsed every event the object carried, master first
   */
  private void applyOccurrences(long userIdentityId,
                                Calendar calendar,
                                long masterEventId,
                                long objectSyncId,
                                IcsEvent master,
                                List<IcsEvent> parsed) {
    for (IcsEvent override : parsed) {
      if (StringUtils.isBlank(override.getOccurrenceId())) {
        continue;
      }
      amendOccurrence(userIdentityId, calendar, masterEventId, objectSyncId, override);
    }
    if (master.getExceptionDates() == null) {
      return;
    }
    for (String excluded : master.getExceptionDates()) {
      cancelOccurrence(userIdentityId, masterEventId, objectSyncId, excluded, master.getTimeZoneId());
    }
  }

  /**
   * A mapping's identifier as the propagation ledger takes it.
   *
   * <p>
   * A mapping the storage never returned, or an adopted one not persisted yet,
   * has no identifier; 0 stands for "no copy to leave alone", which the ledger
   * ignores rather than records.
   *
   * @param mapping the mapping, possibly null or unpersisted
   * @return its identifier, or 0
   */
  private static long mappingIdOf(ObjectSync mapping) {
    return mapping == null || mapping.getId() == null ? 0 : mapping.getId();
  }

  /**
   * Applies one override to the occurrence it amends.
   *
   * @param userIdentityId identity of the user
   * @param calendar the eXo calendar standing for the collection
   * @param masterEventId the series in agenda
   * @param objectSyncId the mapping the series' object was read through
   * @param override the parsed override
   */
  private void amendOccurrence(long userIdentityId,
                               Calendar calendar,
                               long masterEventId,
                               long objectSyncId,
                               IcsEvent override) {
    ZonedDateTime occurrenceId = icsEventMapper.occurrenceOf(override);
    if (occurrenceId == null) {
      LOG.debug("An override of series {} names an occurrence that cannot be read; it is skipped", masterEventId);
      return;
    }
    try {
      Event occurrence = agendaEventService.saveEventExceptionalOccurrence(masterEventId, occurrenceId);
      if (occurrence == null) {
        return;
      }
      Event amended = icsEventMapper.toEvent(override, calendar.getId());
      amended.setId(occurrence.getId());
      amended.setParentId(masterEventId);
      // An override amends one date; it never carries the series' rule, and
      // handing agenda one here would turn a single amended meeting into a
      // second series running beside the first.
      amended.setRecurrence(null);
      amended.setOccurrence(occurrence.getOccurrence());
      // The identity stays null here on purpose: an occurrence carries no
      // identity of its own. The UID is the series', recorded against the
      // series, and that is the only row either the push or the adoption pass
      // ever reads — both resolve an occurrence to its series first.
      caldavEventPropagationService.changedOnTheServer(occurrence.getId(), objectSyncId);
      try {
        agendaEventService.updateEvent(amended,
                                       keptAttendees(occurrence.getId(), userIdentityId),
                                       List.of(),
                                       List.of(),
                                       List.of(),
                                       null,
                                       false,
                                       userIdentityId);
      } catch (Exception e) { // NOSONAR rethrown once the announcement is withdrawn
        caldavEventPropagationService.notChangedAfterAll(occurrence.getId());
        throw e;
      }
    } catch (Exception e) { // NOSONAR agenda declares several checked exceptions here
      // One occurrence that will not take must not cost the series, which is
      // already in place and correct for every other date.
      LOG.warn("Occurrence {} of series {} could not be amended", occurrenceId, masterEventId, e);
    }
  }

  /**
   * Cancels the occurrence an excluded date names.
   *
   * <p>
   * Agenda has no "this date is excluded" flag: a cancelled occurrence is an
   * exceptional occurrence that has been deleted. So the date is materialised
   * first and removed second — which reads oddly and is what the model asks
   * for.
   *
   * @param userIdentityId identity of the user
   * @param masterEventId the series in agenda
   * @param objectSyncId the mapping the series' object was read through
   * @param excluded the raw excluded date
   * @param zoneId the zone the series is anchored on
   */
  private void cancelOccurrence(long userIdentityId,
                                long masterEventId,
                                long objectSyncId,
                                String excluded,
                                String zoneId) {
    ZonedDateTime occurrenceId = icsEventMapper.occurrenceOf(excluded, zoneId);
    if (occurrenceId == null) {
      return;
    }
    try {
      Event occurrence = agendaEventService.saveEventExceptionalOccurrence(masterEventId, occurrenceId);
      if (occurrence == null) {
        return;
      }
      // Marked cancelled, not deleted. Deleting the exceptional occurrence
      // removes the *exception*, not the date — the series then simply covers
      // that day again, which is how a cancelled meeting came back on the
      // first live run.
      occurrence.setStatus(EventStatus.CANCELLED);
      occurrence.setRecurrence(null);
      caldavEventPropagationService.changedOnTheServer(occurrence.getId(), objectSyncId);
      try {
        agendaEventService.updateEvent(occurrence,
                                       keptAttendees(occurrence.getId(), userIdentityId),
                                       List.of(),
                                       List.of(),
                                       List.of(),
                                       null,
                                       false,
                                       userIdentityId);
      } catch (Exception e) { // NOSONAR rethrown once the announcement is withdrawn
        caldavEventPropagationService.notChangedAfterAll(occurrence.getId());
        throw e;
      }
    } catch (Exception e) { // NOSONAR agenda declares several checked exceptions here
      // A meeting the user cancelled elsewhere still showing here is wrong,
      // but it is a smaller wrong than losing the series over it.
      LOG.warn("Occurrence {} of series {} could not be cancelled", occurrenceId, masterEventId, e);
    }
  }

  /**
   * Says once, at warn, that another eXo deployment writes copies into this
   * account.
   *
   * <p>
   * EXO-89824: an acceptance server and a developer's rig were connected to
   * one CalDAV account, and each wrote its meeting copies into it. Every
   * mirror assumes it is the only writer of the copies in its collection —
   * it lists them, compares each against what eXo would write and repairs what
   * diverges — and a second deployment breaks that premise, along with every
   * count and give-up rule that reasons over "the copies in this collection".
   * The condition is invisible from inside either deployment: a foreign copy
   * has no row in this database, so no query can see it, and on every screen
   * it looks like an ordinary event.
   *
   * <p>
   * What tells it apart is the copy itself. eXo names the event a copy stands
   * for, as a {@code URL} property and as a link in the description, and that
   * address carries the deployment that wrote it ({@link #EXO_EVENT_LINK}).
   * It is the one marker known to survive a server's rewriting: the copy this
   * was diagnosed from was written by one deployment and read, link intact,
   * on another, where a property of eXo's own invention would be a bet nobody
   * has verified.
   *
   * <p>
   * <b>Detect and tell, nothing more.</b> Nothing is imported, skipped,
   * removed or repaired differently for it, which is why this is asked before
   * any of those decisions and returns nothing. Whether a foreign copy should
   * be left alone, imported or removed is a product decision nobody has taken,
   * and the wrong one destroys real calendar entries. The resolution is an
   * environment one — one of the two deployments moves to a different account —
   * and the line says so, because that is what an administrator acts on.
   *
   * <p>
   * Conservative on purpose: only an address of eXo's own shape counts, only
   * when it names a deployment other than this one, and only when this one's
   * address is known at all. Deployments are told apart by authority — host
   * and port — of the portal's configured domain ({@code
   * gatein.email.domain.url}, which an eXo deployment sets from
   * {@code exo.base.url}), the value the task asked to compare; two
   * deployments configured with the same address are indistinguishable here,
   * and a stored instance identity would be needed to tell them apart.
   *
   * <p>
   * Once per account per process, in the shape of the shared-account warning
   * of EXO-90190; a restart says it again, which is the right bias after a
   * deploy. Never allowed to fail the import: a warning is not worth a
   * calendar.
   *
   * @param pair the binding being read
   * @param object the object as the server sent it
   * @param master the object's parsed event
   */
  private void warnOnceIfAnotherDeploymentWrites(CalendarSync pair, CalendarObject object, IcsEvent master) {
    try {
      String writer = deploymentNamedBy(master);
      if (writer == null) {
        return;
      }
      String own = ownDeployment();
      if (own == null || own.equals(writer)) {
        return;
      }
      // Keyed on the WRITER as well as the account, and that is the whole point
      // of the key. Keyed on the account alone, the first authority ever seen
      // latched it: a copy this deployment wrote under a different base URL, or
      // a single ICS imported by hand from another eXo, consumed the budget and
      // the genuine second writer - the thing this exists to find - was never
      // reported. Three deployments on one account is the environment EXO-89824
      // came from, and only one of them would have been named. Bounded all the
      // same: one entry per authority actually seen on an account, which is a
      // handful in the worst environment anybody runs.
      String seen = pair.getUserIdentityId() + ":" + pair.getServerId() + ":" + writer;
      Long recorded = foreignDeploymentsRecorded.get(seen);
      if (recorded != null && System.nanoTime() - recorded < foreignWriterRecordSeconds * NANOS_PER_SECOND) {
        return;
      }
      recordForeignWriter(seen, pair.getServerId(), writer);
      if (foreignDeploymentsSaid.add(seen)) {
        LOG.warn("Collection {} of user {} on server {} holds a copy written by another eXo deployment: object {} links"
            + " to {}, and this deployment is {}. Two eXo deployments are writing meeting copies into this CalDAV"
            + " account, and each believes it owns the copies it verifies and repairs. One of the two should be"
            + " connected to a different CalDAV account. Nothing is imported, removed or repaired differently on the"
            + " strength of this line",
                 pair.getRemoteHref(),
                 pair.getUserIdentityId(),
                 pair.getServerId(),
                 object.href(),
                 writer,
                 own);
      }
    } catch (RuntimeException e) {
      LOG.debug("Whether another eXo deployment writes into collection {} could not be told; it is asked again on the next object",
                pair.getRemoteHref(),
                e);
    }
  }

  /**
   * Puts a foreign deployment on its server's row, so the administration
   * drawer can state what until EXO-89824 existed only as the line above.
   *
   * <p>
   * <b>Never allowed to fail the import.</b> A row that could not be written
   * costs an administrator a line in a drawer; an exception here would cost a
   * user their calendar entry. The stamp is taken only on success, so a write
   * that failed is retried on the next object rather than suppressed for an
   * hour.
   *
   * @param seen the account and the deployment seen on it, as the throttle
   *          keys them
   * @param serverId technical identifier of the registration
   * @param writer the deployment the copy names
   */
  private void recordForeignWriter(String seen, long serverId, String writer) {
    try {
      caldavServerService.recordForeignWriter(serverId, writer);
      foreignDeploymentsRecorded.put(seen, System.nanoTime());
    } catch (RuntimeException e) {
      LOG.debug("The deployment {} seen writing into server {} could not be recorded on its row; it is recorded on a later object",
                writer,
                serverId,
                e);
    }
  }

  /**
   * The deployment a copy names, or null when the object is not a copy eXo
   * wrote.
   *
   * <p>
   * The {@code URL} property first, then the description: both carry the same
   * address, and the second is read because a server that drops a property it
   * does not know still keeps the text. Not because an older add-on wrote one
   * and not the other — it did not. Both carriers landed in the same commit
   * (EXO-89751, 2026-08-27), which is also the floor of what this can see at
   * all: a deployment running anything older writes copies with neither, and
   * is invisible here. The label in front of the link is localised, so the
   * address is matched by its shape rather than by the words around it — which
   * is also what keeps a server's bracketed repetition of the link (BlueMind
   * linkifies every URI in a description) from changing the answer.
   *
   * <p>
   * Package-private for one reason only: so the shapes it has to read — the
   * copy EXO-89824 was diagnosed from, a server's linkified repetition — can
   * be asserted directly rather than through an import. It is not an API, and
   * nothing outside this class is meant to call it.
   *
   * @param master the parsed event
   * @return the authority the copy's event link names, lower-cased, or null
   *         when the event carries no link of eXo's shape
   */
  static String deploymentNamedBy(IcsEvent master) {
    for (String text : new String[] { master.getEventUrl(), master.getDescription() }) {
      if (StringUtils.isBlank(text)) {
        continue;
      }
      Matcher link = EXO_EVENT_LINK.matcher(text);
      if (link.find()) {
        return link.group(1).toLowerCase(Locale.ROOT);
      }
    }
    return null;
  }

  /**
   * This deployment's own address as its copies name it: the authority of the
   * portal's configured domain, the value {@code EventIcsBuilder.eventUrl}
   * builds every copy's link from.
   *
   * <p>
   * Asked of the portal only once a copy is in hand, and kept once it
   * answered: the value is a JVM-wide property that does not change while the
   * process runs. The portal never leaves it unanswered — a deployment that
   * configured no domain is given {@code http://localhost:8080} and told so
   * in its own log — so two deployments that both left it unconfigured, or
   * both configured the same address, name themselves alike and cannot be
   * told apart here. Should the call fail all the same, the detection stays
   * off rather than guess: a deployment that cannot say its own name would
   * report every copy of its own as somebody else's.
   *
   * @return the authority, lower-cased, or null when the portal could not be
   *         asked or names no domain
   */
  private String ownDeployment() {
    String known = ownDeployment;
    if (known != null) {
      return known;
    }
    String domain;
    try {
      domain = CommonsUtils.getCurrentDomain();
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("This deployment's own address could not be resolved; copies of another deployment go unreported", e);
      return null;
    }
    String authority = authorityOf(domain);
    if (authority != null) {
      ownDeployment = authority;
    }
    return authority;
  }

  /**
   * The authority — host and port — of an address, with or without a scheme.
   *
   * <p>
   * {@code http://localhost:8080/}, {@code https://exo.example.test} and
   * {@code exo.example.test/portal} all answer their host and port alone,
   * which is the granularity two deployments differ at: a rig and an
   * acceptance server on one host but different ports are two deployments.
   *
   * <p>
   * Package-private for one reason only: so the spellings a configured domain
   * arrives in can be asserted directly. It is not an API, and nothing outside
   * this class is meant to call it.
   *
   * @param address the address, as configured or as a copy carries it
   * @return the authority, lower-cased, or null when there is none
   */
  static String authorityOf(String address) {
    if (StringUtils.isBlank(address)) {
      return null;
    }
    String rest = address.trim();
    int scheme = rest.indexOf("://");
    if (scheme >= 0) {
      rest = rest.substring(scheme + 3);
    }
    int slash = rest.indexOf('/');
    if (slash >= 0) {
      rest = rest.substring(0, slash);
    }
    return StringUtils.isBlank(rest) ? null : rest.toLowerCase(Locale.ROOT);
  }

  /**
   * The binding's collection as a path a request can be sent to.
   *
   * <p>
   * A pair's stored href is a <em>canonical</em> form: the storage strips the
   * trailing slash and decodes the path so two spellings of one collection
   * compare equal. That is right for comparison and wrong for addressing — a
   * form built to test equality is not a URL. The trailing slash is put back
   * here, which is how every other path in this add-on addresses a collection
   * and how the server itself spells it in a listing.
   *
   * @param pair the binding being read
   * @return the collection path to request
   */
  private String collectionUrl(CalendarSync pair) {
    return StringUtils.appendIfMissing(pair.getRemoteHref(), "/");
  }

  /*
   * Why every client call goes through the method above rather than taking the
   * stored href directly: the stored form is canonical — no trailing slash —
   * because that is what makes two spellings of the same path compare equal.
   * Addressing a collection is a different job: a server may answer one
   * spelling and not the other. BlueMind ignores the slashless form entirely,
   * neither answering nor redirecting, so a request to it spends the whole
   * 30-second timeout finding out nothing.
   *
   * The import already appended the slash, which is why events kept arriving
   * from calendars whose every probe timed out — the two halves of the same
   * pass were addressing the same collection by different names, and only one
   * of them worked.
   */

  /**
   * Brings a collection's contents into eXo and removes what left it, in one
   * conversation with the account.
   *
   * <p>
   * The pass used to ask two separate questions. What changed was answered by
   * re-reading the <b>whole window</b> — 426 days cut into 30-day slices, so
   * fifteen REPORTs each carrying the full iCalendar of everything in its
   * slice. Changing one event's title asked a real server to re-send a year,
   * fifteen times over, and the user waited for it. What vanished was answered
   * separately.
   *
   * <p>
   * A sync token answers both at once, and cheaply:
   * <a href="https://www.rfc-editor.org/rfc/rfc6578">RFC 6578</a> returns the
   * handful of objects that changed and the handful that were removed. The
   * changed ones are then fetched by path — one multiget carrying one event
   * rather than fifteen REPORTs carrying a year.
   *
   * <p>
   * <b>One report, never two.</b> The two halves must come from the same call:
   * a report consumes the token it was given and hands back a new one, so two
   * calls sharing a token would let whichever ran second miss everything the
   * first had already taken. That is why importing and reconciling live in one
   * method rather than being called in sequence.
   *
   * <p>
   * <b>The window still has to be read in full sometimes</b>, and that is what
   * {@code fullRead} is for. A token says what changed; it says nothing about
   * days sliding into range as time passes. An event a year out is untouched
   * by anyone and reported by nothing, and it still has to appear when the
   * window reaches it — so the caller asks for a full read when the window has
   * moved, which is once a day.
   *
   * @param userIdentityId identity of the user
   * @param username their eXo login, which the credentials provider maps to
   *          their account on the server
   * @param pair the binding being read
   * @param calendar the eXo calendar it fills
   * @param from start of the window, for a full read
   * @param to end of the window, for a full read
   * @param fullRead true to re-read the whole window rather than ask what
   *          changed — a binding with no token, or one whose window has moved
   * @return what the reconciliation removed and what it could not
   */
  public VanishedCleanup syncContents(long userIdentityId,
                                      String username,
                                      CalendarSync pair,
                                      Calendar calendar,
                                      Instant from,
                                      Instant to,
                                      boolean fullRead) {
    if (pair == null || calendar == null) {
      return VanishedCleanup.nothing();
    }
    // Built once for the whole pass and shared by both halves, because it is
    // the expensive part: the window's events are listed once and their
    // remote identities read once, whichever route the objects arrive by.
    AdoptableEvents adoptable = adoptable(userIdentityId, calendar, from, to);
    if (!fullRead && StringUtils.isNotBlank(pair.getSyncToken())) {
      CaldavUserSetting settings = settingsFor(userIdentityId, pair);
      if (settings != null) {
        VanishedCleanup incremental = readWhatChanged(userIdentityId, username, pair, calendar, settings, adoptable);
        if (incremental != null) {
          return incremental;
        }
      }
    }
    importInto(userIdentityId, username, pair, calendar, from, to, adoptable);
    return removeVanishedObjects(userIdentityId, username, pair, calendar.getTitle());
  }

  /**
   * Asks the account what changed since the token, and acts on the answer.
   *
   * @param userIdentityId identity of the user
   * @param username their eXo login, which the credentials provider maps to
   *          their account on the server
   * @param pair the binding being read
   * @param calendar the eXo calendar it fills
   * @param settings the connected account
   * @param adoptable the events of this calendar an object may be adopted into
   * @return what was reconciled, or null when the token could not be used and
   *         the caller should read the window in full instead
   */
  private VanishedCleanup readWhatChanged(long userIdentityId,
                                          String username,
                                          CalendarSync pair,
                                          Calendar calendar,
                                          CaldavUserSetting settings,
                                          AdoptableEvents adoptable) {
    CalDavEndpoint endpoint;
    SyncCollectionResult report;
    try {
      endpoint = calDavClient.endpoint(pair.getServerId(), username);
      report = calDavClient.syncCollection(endpoint,
                                           collectionUrl(pair),
                                           pair.getSyncToken());
    } catch (RuntimeException e) {
      // Nothing is concluded from a report that could not be made — neither
      // that the collection is empty nor that it is unchanged. Reading the
      // window in full is the honest fallback.
      LOG.warn("Collection {} could not report its changes; its window is read in full", pair.getRemoteHref(), e);
      return null;
    }
    if (report == null || !report.tokenValid()) {
      LOG.info("The sync token of collection {} was refused; its window is read in full", pair.getRemoteHref());
      return null;
    }
    List<String> changed = report.changed()
                                 .stream()
                                 .map(CalendarObject::href)
                                 .filter(StringUtils::isNotBlank)
                                 .toList();
    if (!changed.isEmpty() && !importByHref(userIdentityId, pair, calendar, settings, endpoint, changed, adoptable)) {
      // The changes could not be fetched, so the token must not move: it would
      // claim they had been taken in, and nothing would ever fetch them again.
      return VanishedCleanup.nothing();
    }
    return removeAll(userIdentityId,
                     pair,
                     mappingsMatching(pair, canonical(report.deleted()), true),
                     report.syncToken());
  }

  /**
   * Fetches named objects and imports them.
   *
   * @param userIdentityId identity of the user
   * @param pair the binding being read
   * @param calendar the eXo calendar they belong to
   * @param settings the connected account
   * @param endpoint where its server lives
   * @param hrefs the paths the account reported changed
   * @param adoptable the events of this calendar an object may be adopted into
   * @return true when they were fetched; false when the account could not be
   *         asked, which must stop the token moving
   */
  private boolean importByHref(long userIdentityId,
                               CalendarSync pair,
                               Calendar calendar,
                               CaldavUserSetting settings,
                               CalDavEndpoint endpoint,
                               List<String> hrefs,
                               AdoptableEvents adoptable) {
    List<CalendarObject> objects;
    try {
      objects = calDavClient.multiget(endpoint,
                                      collectionUrl(pair),
                                      hrefs);
    } catch (RuntimeException e) {
      LOG.warn("The {} changed object(s) of collection {} could not be fetched; they are left for the next pass",
               hrefs.size(),
               pair.getRemoteHref(),
               e);
      return false;
    }
    if (objects == null) {
      return false;
    }
    for (CalendarObject object : objects) {
      importObject(userIdentityId, pair, calendar, object, adoptable);
    }
    LOG.debug("{} changed object(s) read from collection {} without re-reading its window",
              objects.size(),
              pair.getRemoteHref());
    return true;
  }

  /**
   * Removes the eXo events whose objects are no longer on the account.
   *
   * <p>
   * The other half of reading a calendar back in. An event created or edited
   * on the user's phone reaches eXo; an event <b>deleted</b> there did not,
   * because an object that is gone is simply absent from what the server
   * returns, and absence was never looked for. The calendar the user is
   * looking at on their phone and the one eXo shows them drift apart, and
   * nothing says so.
   *
   * <p>
   * The listing is deliberately a full <code>PROPFIND</code> of the
   * collection rather than the windowed query the import uses. The import
   * walks a time window in slices, so an object it did not return may simply
   * lie outside the window — concluding "deleted" from that would destroy
   * every event the user has outside the period eXo happens to read.
   *
   * <p>
   * Two further limits, both because this deletes the user's data:
   * <ul>
   * <li>only events eXo holds a mapping for are ever removed. An event
   * authored in eXo that never reached the account has no mapping and is not
   * this method's business;</li>
   * <li>a listing that could not be made removes nothing. An unreachable
   * server is not a statement that everything was deleted, and treating it as
   * one would empty the user's calendar the moment their network dropped.</li>
   * </ul>
   *
   * @param userIdentityId identity of the user, whose ACL the deletion runs
   *          under
   * @param username their eXo login, which the credentials provider maps to
   *          their account on the server
   * @param pair the binding whose collection is reconciled
   * @return what was removed and what could not be
   */
  public VanishedCleanup removeVanishedObjects(long userIdentityId, String username, CalendarSync pair) {
    return removeVanishedObjects(userIdentityId, username, pair, null);
  }

  /**
   * The same, told which calendar it is working on so its warnings can name it.
   *
   * <p>
   * A collection href is <code>exo-cal-&lt;syncUid&gt;</code>, and nothing a
   * user or an administrator can reach maps that back to a calendar — the
   * agenda REST does not expose <code>syncUid</code> at all. A warning naming
   * only the href therefore cannot be acted on by the person reading it, which
   * is how one failing collection went unexplained for a whole morning.
   *
   * @param userIdentityId identity of the user
   * @param username their eXo login, which the credentials provider maps to
   *          their account on the server
   * @param pair the binding whose collection is reconciled
   * @param calendarName the eXo calendar's name, for the log only, may be null
   * @return what was removed and what could not be
   */
  public VanishedCleanup removeVanishedObjects(long userIdentityId, String username, CalendarSync pair, String calendarName) {
    if (pair == null || StringUtils.isBlank(pair.getRemoteHref())) {
      return VanishedCleanup.nothing();
    }
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (settings == null || StringUtils.isBlank(settings.getUsername())) {
      return VanishedCleanup.nothing();
    }
    CalDavEndpoint endpoint;
    try {
      endpoint = calDavClient.endpoint(settings.getServerId(), username);
    } catch (RuntimeException e) {
      LOG.warn("No endpoint for collection {}; nothing is removed from it this round", pair.getRemoteHref(), e);
      return VanishedCleanup.nothing();
    }
    Vanished found = findVanished(pair, settings, endpoint, calendarName);
    if (!found.conclusive()) {
      return VanishedCleanup.nothing();
    }
    return removeAll(userIdentityId, pair, found.objects(), found.freshToken());
  }

  /**
   * Removes a set of mappings whose objects are gone, and records the token.
   *
   * @param userIdentityId identity of the user, whose ACL the deletions run
   *          under
   * @param pair the binding being reconciled
   * @param vanished the mappings to remove, may be empty
   * @param freshToken the token the account gave, recorded only if every
   *          removal goes through
   * @return what was removed and what could not be
   */
  private VanishedCleanup removeAll(long userIdentityId,
                                    CalendarSync pair,
                                    List<ObjectSync> vanished,
                                    String freshToken) {
    if (vanished.isEmpty()) {
      // Nothing to remove, but the account was reached and answered — so the
      // token it gave is worth keeping, or the next pass asks the expensive
      // question again for no reason.
      rememberToken(pair, freshToken);
      return VanishedCleanup.nothing();
    }
    // The removals get a persistence context of their own, and that is the
    // difference between working and not. Deleting inside the one the pass has
    // been using fails at commit: since Hibernate 6, the pre-flush cascade
    // walks EVERY entity the session holds — clean or dirty — and refuses the
    // flush when any of them still references the event being removed, which
    // a session that has just read collections and events for the whole pass
    // always does. The identical deletion through agenda's own REST succeeds
    // because its request's session holds nothing else.
    //
    // A single RequestLifeCycle.end()/begin() does NOT deliver that context:
    // the kernel enrolls each ComponentRequestLifecycle only once per stack,
    // so on an HTTP request — whose filter began the outer lifecycle — a
    // nested begin() is an empty lifecycle and the matching end() closes
    // nothing; the EntityManager quietly survives the "renewal". Measured,
    // not supposed: the removals failed identically while logging that they
    // had their own context. restartTransaction() is the kernel's own way to
    // really do it — it unwinds the whole stack, which closes the
    // EntityManager at the level actually holding it, then re-begins the same
    // depth so the caller never knows.
    //
    // Safe to restart here: everything this class holds across the boundary
    // is a DTO, not a managed entity, so nothing is left detached by it.
    // IfPresent, not getCurrentContainer(): outside a portal — a unit test —
    // the latter bootstraps a root container off the test classpath, which is
    // neither wanted nor able to succeed there. No container simply means no
    // context to restart.
    ExoContainer container = ExoContainerContext.getCurrentContainerIfPresent();
    restartContext(container);
    // Read every event once BEFORE deleting any, then leave that context
    // behind too. This is not an optimisation: agenda's delete reads the
    // event, and on a cache miss that read pulls the real EventEntity into
    // the session before the attendee rows are read, so the attendees hold
    // the real instance rather than a lazy proxy. Hibernate's flush check
    // exempts an uninitialised proxy but refuses a real instance that the
    // same flush deletes — which is why a deletion that follows any read of
    // the event (a REST GET, a previous failed attempt) always worked, and
    // the first cold attempt never did. Reading here fills agenda's event
    // cache, and the restart drops the entities the read loaded, so the
    // delete that follows finds its event in the cache and touches nothing
    // but the row it removes.
    for (ObjectSync object : vanished) {
      warmEvent(object.getLocalEventId());
    }
    restartContext(container);
    LOG.info("Reconciling {} vanished object(s) of {}", vanished.size(), pair.getRemoteHref());
    int removed = 0;
    int failed = 0;
    try {
      for (ObjectSync object : vanished) {
        if (removeOne(userIdentityId, object)) {
          removed++;
        } else {
          failed++;
        }
      }
    } finally {
      // The caller carries on with the pass, so it is handed a context the
      // removals have not touched.
      restartContext(container);
    }
    if (removed > 0) {
      LOG.info("{} event(s) deleted on the account are no longer shown from collection {}", removed, pair.getRemoteHref());
    }
    if (failed == 0) {
      // Only when every removal went through. A token recorded over a failed
      // removal is a claim to have dealt with everything up to that point, and
      // the next incremental report would not mention the object again — so the
      // event the user deleted would stay in eXo for good.
      rememberToken(pair, freshToken);
    }
    return new VanishedCleanup(removed, failed);
  }

  /**
   * What one reconciliation managed.
   *
   * <p>
   * The failure count is not a statistic: a collection whose deletions did not
   * all go through must not have its ctag recorded as read, or the next pass
   * compares an unchanged ctag, concludes there is nothing to do, and never
   * retries — the events stay in eXo for good and nothing ever looks again.
   *
   * @param removed events removed because their object is gone
   * @param failed events whose object is gone but which could not be removed
   */
  public record VanishedCleanup(int removed, int failed) {

    /**
     * @return a reconciliation that did nothing, because it could not tell
     */
    public static VanishedCleanup nothing() {
      return new VanishedCleanup(0, 0);
    }
  }

  /**
   * Removes one event and the mapping that pointed at it.
   *
   * <p>
   * An event already gone from agenda is not a failure: the mapping is the
   * thing left to tidy, and keeping it would make eXo look for an event that
   * no longer exists on every later pass.
   *
   * @param userIdentityId identity of the user, whose ACL the deletion runs
   *          under
   * @param object the mapping whose object vanished
   * @return true when the mapping was dropped
   */

  /**
   * Closes the request's persistence context and opens a fresh one, however
   * deeply the request lifecycle is nested.
   *
   * <p>
   * {@code RequestLifeCycle.restartTransaction} unwinds the whole lifecycle
   * stack and re-begins it at the same depth. Unwinding it all is the point:
   * only the outermost level actually holds the EntityManager, so ending one
   * nested level — which is what a plain {@code end()}/{@code begin()} pair
   * does under an HTTP request — closes nothing at all.
   *
   * <p>
   * Guarded rather than assumed: outside a portal request — a unit test, a
   * scheduled thread — there may be no lifecycle to restart, which is fine:
   * there each transactional call already opens a context of its own, and
   * failing to restart what does not exist is not a reason to abandon the
   * work.
   *
   * @param container the container whose lifecycle is restarted, may be null
   */
  /**
   * Puts one event into agenda's cache so the deletion that follows does not
   * have to read it into its own session.
   *
   * <p>
   * Nothing this read learns is used; what matters is the side effect on the
   * cache. An event already gone, or unreadable for any reason, changes
   * nothing about what the deletion will then do with it — so nothing here is
   * allowed to fail the reconciliation.
   *
   * @param eventId the event about to be removed, may be null
   */
  private void warmEvent(Long eventId) {
    if (eventId == null) {
      return;
    }
    try {
      agendaEventService.getEventById(eventId);
    } catch (RuntimeException e) {
      LOG.debug("Event {} could not be read ahead of its removal", eventId, e);
    }
  }

  private void restartContext(ExoContainer container) {
    if (container == null) {
      return;
    }
    try {
      RequestLifeCycle.restartTransaction(container);
    } catch (RuntimeException e) {
      LOG.debug("The request lifecycle could not be restarted; the removals run in the caller's context", e);
    }
  }


  /**
   * What one discovery round concluded.
   *
   * <p>
   * <b>conclusive</b> is the safety flag and is not the same as "found
   * nothing". A report that could not be obtained, or a token the server
   * refused, tells us nothing about what the collection holds — and this code
   * deletes the user's events, so "we could not ask" must never be read as
   * "everything is gone".
   *
   * @param conclusive whether the account actually answered the question
   * @param objects the mappings whose objects are gone
   * @param freshToken the token to record for the next pass, null when none
   */
  private record Vanished(boolean conclusive, List<ObjectSync> objects, String freshToken) {

    /**
     * @return a round that concluded nothing, because it could not ask
     */
    static Vanished inconclusive() {
      return new Vanished(false, List.of(), null);
    }
  }

  /**
   * Works out which of a binding's objects are no longer on the account.
   *
   * <p>
   * Two ways, and which one is used matters for the user rather than only for
   * the code. With a sync token, the server is asked what changed since it
   * (<a href="https://www.rfc-editor.org/rfc/rfc6578">RFC 6578</a>) and answers
   * with the handful of objects actually removed. Without one, the whole
   * collection has to be listed and compared — which on a real calendar can
   * take longer than the request timeout allows, and did: a full listing per
   * collection per pass turned a synchronisation into a forty-second wait.
   *
   * <p>
   * So the expensive question is asked once, and only until the account has
   * given us a token to ask the cheap one with.
   *
   * @param pair the binding being reconciled
   * @param settings the connected account
   * @param endpoint where its server lives
   * @param calendarName the eXo calendar's name, carried through for the log
   *          alone: the full comparison it falls back to warns about a
   *          collection, and an operator has to recognise which one. May be
   *          null.
   * @return what this round concluded
   */
  private Vanished findVanished(CalendarSync pair,
                                CaldavUserSetting settings,
                                CalDavEndpoint endpoint,
                                String calendarName) {
    String token = pair.getSyncToken();
    if (StringUtils.isNotBlank(token)) {
      SyncCollectionResult report;
      try {
        report = calDavClient.syncCollection(endpoint,
                                             pair.getRemoteHref(),
                                             token);
      } catch (RuntimeException e) {
        LOG.warn("Collection {} could not report its changes; nothing is removed from it this round",
                 pair.getRemoteHref(),
                 e);
        return Vanished.inconclusive();
      }
      if (report != null && report.tokenValid()) {
        return new Vanished(true, mappingsMatching(pair, canonical(report.deleted()), true), report.syncToken());
      }
      // A refused token says the server can no longer tell us what changed —
      // never that nothing is there. Falling back to the full comparison is
      // what keeps that distinction.
      LOG.info("The sync token of collection {} was refused; it is compared in full this round", pair.getRemoteHref());
    }
    return byFullComparison(pair, settings, endpoint, calendarName);
  }

  /**
   * Compares every mapping against everything the collection holds.
   *
   * <p>
   * The fallback, for a binding that has never been read or whose token the
   * server refused. It asks for an initial sync report first, because that
   * returns the members <b>and</b> a token — so the next pass can take the
   * cheap path instead of paying this again. A server that does not answer
   * that is listed the older way, and simply keeps paying.
   *
   * @param pair the binding being reconciled
   * @param settings the connected account
   * @param endpoint where its server lives
   * @param calendarName the eXo calendar's name, used only in the two warnings
   *          this path logs — a calendar that stops answering has to be
   *          identifiable without resolving an href by hand. May be null.
   * @return what this round concluded
   */
  private Vanished byFullComparison(CalendarSync pair,
                                    CaldavUserSetting settings,
                                    CalDavEndpoint endpoint,
                                    String calendarName) {
    // The token is read first, and cheaply: a Depth:0 PROPFIND asks the
    // collection for one property and enumerates nothing.
    //
    // This ordering is the whole point. Obtaining the first token used to mean
    // an initial sync report, which returns every member — the same cost as the
    // listing below, and on a real calendar the same 30-second timeout. So a
    // collection too big to list was also too big to get a token for, and could
    // never reach the cheap path: the escape needed the very call that was
    // failing. Read the token separately and that trap disappears.
    if (silentRecently(pair)) {
      // Asked within the last few minutes and it did not answer. The timeout is
      // paid once, not on every click: a server that ignored a small PROPFIND a
      // moment ago will ignore this one too, and the user is the one waiting.
      LOG.debug("Collection {} did not answer recently; not asked again this round", pair.getRemoteHref());
      return Vanished.inconclusive();
    }
    String freshToken = null;
    try {
      CalendarCollection collection = calDavClient.readCalendar(endpoint,
                                                               collectionUrl(pair));
      if (collection != null) {
        freshToken = collection.syncToken();
      }
      notAnswering.remove(pair.getId());
    } catch (RuntimeException e) {
      // The cheap question failed, so the expensive one is not worth asking:
      // a collection that will not answer one small PROPFIND about itself is
      // not going to enumerate its whole contents, and the listing that
      // follows would spend the full request timeout finding that out — on the
      // user's own click, every pass. Measured at 30 seconds against a real
      // account, for a collection that had been failing all along.
      //
      // Nothing is concluded and nothing is removed, which is what a failure
      // always meant here. The difference is that it now costs one request
      // instead of a timeout.
      LOG.warn("Calendar \"{}\" did not answer at {}; it is left alone for the next {} minutes rather than listed at length",
               StringUtils.defaultIfBlank(calendarName, "?"),
               pair.getRemoteHref(),
               NOT_ANSWERING_FOR.toMinutes(),
               e);
      notAnswering.put(pair.getId(), Instant.now());
      return Vanished.inconclusive();
    }
    Map<String, String> etags;
    try {
      etags = calDavClient.listResourceEtags(endpoint,
                                             collectionUrl(pair));
    } catch (RuntimeException e) {
      // Nothing is removed — a listing that failed says nothing about what the
      // collection holds. But the token is kept if we got one, because that is
      // what lets the next pass ask the cheap question instead of failing here
      // again, every pass, for ever.
      LOG.warn("Calendar \"{}\" could not be listed at {}; nothing is removed from it this round{}",
               StringUtils.defaultIfBlank(calendarName, "?"),
               pair.getRemoteHref(),
               freshToken == null ? "" : ", but its sync token is recorded so the next pass can ask what changed",
               e);
      rememberToken(pair, freshToken);
      return Vanished.inconclusive();
    }
    if (etags == null) {
      rememberToken(pair, freshToken);
      return Vanished.inconclusive();
    }
    return new Vanished(true, mappingsMatching(pair, canonical(etags.keySet()), false), freshToken);
  }

  /**
   * The mappings of a binding whose object is, or is not, in a set of paths.
   *
   * <p>
   * Collected before anything is deleted. Removing rows while paging over them
   * shifts every later page, which silently skips half the mappings.
   *
   * @param pair the binding whose mappings are walked
   * @param hrefs canonical paths to test against
   * @param present true to select the mappings <b>in</b> the set (paths the
   *          server reported deleted), false to select those <b>absent</b> from
   *          it (paths the server no longer holds)
   * @return the mappings selected, never null
   */
  private List<ObjectSync> mappingsMatching(CalendarSync pair, Set<String> hrefs, boolean present) {
    List<ObjectSync> selected = new ArrayList<>();
    int page = 0;
    List<ObjectSync> objects = caldavSyncStorage.getObjects(pair.getId(), page, OBJECT_PAGE_SIZE).getContent();
    while (!objects.isEmpty()) {
      for (ObjectSync object : objects) {
        if (object.getLocalEventId() == null || StringUtils.isBlank(object.getRemoteHref())) {
          continue;
        }
        if (hrefs.contains(CaldavSyncStorage.canonicalHref(object.getRemoteHref())) == present) {
          selected.add(object);
        }
      }
      objects = caldavSyncStorage.getObjects(pair.getId(), ++page, OBJECT_PAGE_SIZE).getContent();
    }
    return selected;
  }

  /**
   * Canonicalises a collection of paths, so comparisons do not turn on a
   * trailing slash or an escape.
   *
   * @param hrefs the paths as the server wrote them
   * @return the same paths, canonical
   */
  private Set<String> canonical(Collection<String> hrefs) {
    return hrefs.stream()
                .filter(StringUtils::isNotBlank)
                .map(CaldavSyncStorage::canonicalHref)
                .collect(Collectors.toSet());
  }

  /**
   * Records the token the account just gave, so the next pass can ask what
   * changed rather than listing everything.
   *
   * @param pair the binding to record it on
   * @param freshToken the token, ignored when blank or unchanged
   */
  private void rememberToken(CalendarSync pair, String freshToken) {
    if (StringUtils.isBlank(freshToken) || StringUtils.equals(freshToken, pair.getSyncToken())) {
      return;
    }
    pair.setSyncToken(freshToken);
    caldavSyncStorage.savePair(pair);
  }


  /**
   * Whether a collection refused to answer within the last few minutes.
   *
   * @param pair the binding whose collection is asked about
   * @return true when it is too soon to ask again
   */
  private boolean silentRecently(CalendarSync pair) {
    Instant last = notAnswering.get(pair.getId());
    return last != null && last.isAfter(Instant.now().minus(NOT_ANSWERING_FOR));
  }

  /**
   * Removes from agenda the event of an object that vanished from the server.
   *
   * <p>
   * The deletion is announced as the server's own before agenda is asked, for
   * the same reason an update is: agenda broadcasts the deletion, the listener
   * carries it to every holder of a copy, and the mapping this object was
   * read through is one of them — still recorded at that moment, since it is
   * dropped only once agenda has agreed. Without the announcement the listener
   * asked the server to remove an object the server had just told us was
   * gone, and recorded that removal as owed against a mapping about to be
   * deleted — the retry the rig saw "stay owed" after a deletion (EXO-90190).
   *
   * @param userIdentityId identity of the user
   * @param object the mapping whose object vanished
   * @return true when the event is gone and the mapping dropped
   */
  private boolean removeOne(long userIdentityId, ObjectSync object) {
    // The selection never hands over a mapping with no event, so this is
    // 0 only in theory; 0 is what the ledger ignores.
    long localEventId = object.getLocalEventId() == null ? 0 : object.getLocalEventId();
    caldavEventPropagationService.changedOnTheServer(localEventId, mappingIdOf(object));
    try {
      agendaEventService.deleteEventById(object.getLocalEventId(), userIdentityId);
    } catch (ObjectNotFoundException e) {
      caldavEventPropagationService.notChangedAfterAll(localEventId);
      LOG.debug("Event {} was already gone from agenda; only its mapping is dropped", object.getLocalEventId(), e);
    } catch (IllegalAccessException e) {
      caldavEventPropagationService.notChangedAfterAll(localEventId);
      // Their own calendar, so this should not happen — and if it does, the
      // mapping is kept, because dropping it would hide an event eXo can no
      // longer account for.
      LOG.warn("User {} may not delete event {}; it stays as it is", userIdentityId, object.getLocalEventId(), e);
      return false;
    } catch (RuntimeException e) {
      caldavEventPropagationService.notChangedAfterAll(localEventId);
      Throwable cause = e;
      while (cause.getCause() != null && cause.getCause() != cause) {
        cause = cause.getCause();
      }
      LOG.warn("Event {} could not be removed after its object vanished: {}",
               object.getLocalEventId(),
               cause.toString());
      return false;
    }
    caldavSyncStorage.deleteObject(object.getId());
    return true;
  }

  /**
   * The connected account behind a binding, when it is still usable.
   *
   * <p>
   * Its Javadoc had come adrift from it — left stranded above another method,
   * where the generated documentation could attach it to nothing. Restored
   * here while this file was open.
   *
   * @param userIdentityId identity of the user
   * @param pair the binding being read
   * @return the account, or null when there is none to read with
   */
  private CaldavUserSetting settingsFor(long userIdentityId, CalendarSync pair) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (settings == null || StringUtils.isBlank(settings.getUsername()) || StringUtils.isBlank(settings.getPassword())) {
      return null;
    }
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    if (serverId != pair.getServerId()) {
      // The account has been pointed at another server since this binding was
      // made. Reading the collection with credentials for a different server
      // is not a thing to attempt.
      LOG.debug("Binding {} belongs to another server than the connected account", pair.getId());
      return null;
    }
    return settings;
  }

  /**
   * The index of events an object may be adopted into, for one calendar and
   * one window.
   *
   * <p>
   * Built lazily and at most once per pass. Its cost is one listing of the
   * window plus one remote-identity read per event of this calendar in it, so
   * paying it per object would make importing a collection quadratic in its
   * own size. It is also usually paid for nothing — in the steady state every
   * object already has a mapping and never reaches the create path — which is
   * exactly why it is deferred until the first object that does.
   *
   * @param userIdentityId identity of the user
   * @param calendar the eXo calendar being filled
   * @param from beginning of the window
   * @param to end of the window
   * @return the index, which reads nothing until it is first asked
   */
  private AdoptableEvents adoptable(long userIdentityId, Calendar calendar, Instant from, Instant to) {
    return new AdoptableEvents(userIdentityId, calendar, from, to);
  }

  /**
   * The events already in one calendar, by the identifier the server knows
   * them under.
   *
   * <p>
   * <b>The scope is the whole safety argument.</b> An iCalendar UID is unique
   * on the server that issued it and nowhere else: two accounts, or two
   * servers, can hand out the same one for entirely different meetings. A
   * lookup keyed on the UID alone would therefore be able to attach a remote
   * object to somebody else's event, which is a worse outcome than the
   * duplication it exists to prevent.
   *
   * <p>
   * So the candidates are the events of <em>one calendar</em>, and the three
   * boundaries follow from that one fact rather than from three separate
   * checks:
   * <ul>
   * <li><b>calendar</b> — an event is a candidate only if its own
   * {@code calendarId} is this calendar's; the listing is filtered on it
   * rather than trusted to be scoped;</li>
   * <li><b>user</b> — the calendar is read as its owner and filtered on
   * {@code ownerIds}, and the remote identity is read for that same identity,
   * so another user's mapping for the same event is not visible here;</li>
   * <li><b>server</b> — a calendar is bound by one pair, and a pair names one
   * server, so an event in this calendar came from this server. Only an
   * identity recorded under this connector counts, so an identifier a
   * different provider stored is never mistaken for a CalDAV UID.</li>
   * </ul>
   *
   * <p>
   * An event that any pair already maps is excluded as well. Those are not
   * orphans looking for a binding — a copy eXo wrote into the mirror is the
   * obvious case — and adopting one would move it under a collection it does
   * not belong to.
   *
   * <p>
   * <b>Two things it does not see</b>, both accepted rather than worked
   * around, because the cost of missing one is a duplicate and the cost of
   * widening the lookup to catch it is an adoption that crosses a boundary.
   * Agenda's listing returns confirmed events only, so an event cancelled in
   * eXo is created afresh rather than adopted; and the window is the import's
   * own, so an event that has been moved out of it is not a candidate either.
   *
   * <p>
   * <b>What it costs</b>: one listing of the user's window and one identity
   * read per event of this calendar in it — paid once per pass, and only on a
   * pass that reaches the create path at all. In the steady state every object
   * already has a mapping, nothing reaches it, and the index is never built.
   */
  private final class AdoptableEvents {

    /** The user whose calendar this is; every read is made as them. */
    private final long        userIdentityId;

    /** The one calendar an adoption may reach into. */
    private final Calendar    calendar;

    /** Beginning of the window the pass is importing. */
    private final Instant     from;

    /** End of that window. */
    private final Instant     to;

    /** Built on first use, then reused for the whole pass; null until then. */
    private Map<String, Long> byRemoteId;

    /**
     * @param userIdentityId identity of the user
     * @param calendar the eXo calendar being filled
     * @param from beginning of the window
     * @param to end of the window
     */
    private AdoptableEvents(long userIdentityId, Calendar calendar, Instant from, Instant to) {
      this.userIdentityId = userIdentityId;
      this.calendar = calendar;
      this.from = from;
      this.to = to;
    }

    /**
     * The event this calendar already holds under that server identifier.
     *
     * @param remoteId the identifier the server knows the object by
     * @return the event to adopt, or null when there is none to adopt
     */
    private Long forRemoteId(String remoteId) {
      if (StringUtils.isBlank(remoteId)) {
        return null;
      }
      if (byRemoteId == null) {
        byRemoteId = build();
      }
      return byRemoteId.get(remoteId);
    }

    /**
     * Takes one candidate out, so a collection holding the same UID twice
     * cannot bind two of its objects to one event.
     *
     * @param remoteId the identifier just adopted
     */
    private void taken(String remoteId) {
      if (byRemoteId != null) {
        byRemoteId.remove(remoteId);
      }
    }

    /**
     * Reads the calendar's events and their remote identities.
     *
     * @return the candidates by remote identifier, empty when there are none
     *         or when the calendar could not be read
     */
    private Map<String, Long> build() {
      List<Event> events;
      try {
        // Owner-scoped rather than attendee-scoped: the filter is the user's
        // own personal calendars, which is the widest this may ever look, and
        // the calendar test below narrows it to one of them. Agenda checks the
        // owner against the caller, so a filter naming anybody else is refused
        // rather than answered.
        EventFilter filter = new EventFilter(List.of(userIdentityId),
                                             ZonedDateTime.ofInstant(from, ZoneOffset.UTC),
                                             ZonedDateTime.ofInstant(to, ZoneOffset.UTC));
        events = agendaEventService.getEvents(filter, ZoneOffset.UTC, userIdentityId);
      } catch (Exception e) { // NOSONAR agenda declares a checked exception here
        // Nothing is adopted this pass. That costs a duplicate at worst; the
        // alternative — adopting against a list that could not be read — is
        // not available, and failing the import outright would be worse than
        // either.
        LOG.warn("The events of calendar {} could not be listed; nothing is adopted this pass",
                 calendar.getId(),
                 e);
        return new HashMap<>();
      }
      Set<Long> candidates = new LinkedHashSet<>();
      for (Event event : events) {
        if (event == null || event.getCalendarId() != calendar.getId()) {
          continue;
        }
        // The series, not the occurrence agenda expanded: the mapping and the
        // remote identity are both recorded against the event the object
        // stands for.
        long eventId = event.getParentId() > 0 ? event.getParentId() : event.getId();
        if (eventId > 0) {
          candidates.add(eventId);
        }
      }
      if (candidates.isEmpty()) {
        return new HashMap<>();
      }
      // Asked for the whole set at once, and asked first: in the steady state
      // every event here is already mapped, and that is the cheapest way to
      // learn there is nothing to adopt.
      Set<Long> alreadyBound = caldavSyncStorage.mappedEventIds(candidates);
      Map<String, Long> byRemote = new HashMap<>();
      for (Long eventId : candidates) {
        if (alreadyBound.contains(eventId)) {
          continue;
        }
        RemoteEvent identity;
        try {
          identity = agendaRemoteEventService.findRemoteEvent(eventId, userIdentityId);
        } catch (RuntimeException e) {
          LOG.debug("The remote identity of event {} could not be read; it is not offered for adoption", eventId, e);
          continue;
        }
        if (identity == null || StringUtils.isBlank(identity.getRemoteId())
            || !CONNECTOR_NAME.equals(identity.getRemoteProviderName())) {
          continue;
        }
        // The first wins. Two events of one calendar claiming one identifier
        // is a state nothing should produce; picking one of them
        // deterministically at least keeps the pass from oscillating between
        // them from one run to the next.
        byRemote.putIfAbsent(identity.getRemoteId(), eventId);
      }
      return byRemote;
    }
  }
}
