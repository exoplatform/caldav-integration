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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.exoplatform.commons.exception.ObjectNotFoundException;
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

  @Autowired
  private IcsParser              icsParser;

  @Autowired
  private IcsEventMapper         icsEventMapper;

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
   * @param pair the binding whose collection is read
   * @param calendar the eXo calendar standing for it
   * @param from beginning of the window
   * @param to end of the window
   * @return how many events were created or updated
   */
  public int importInto(long userIdentityId, CalendarSync pair, Calendar calendar, Instant from, Instant to) {
    if (pair == null || calendar == null) {
      return 0;
    }
    CaldavUserSetting settings = settingsFor(userIdentityId, pair);
    if (settings == null) {
      return 0;
    }
    CalDavEndpoint endpoint = calDavClient.endpoint(pair.getServerId(), settings.getUsername());
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
      touched += importSlice(userIdentityId, pair, calendar, settings, endpoint, sliceStart, sliceEnd);
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
   * @return how many events this slice created
   */
  private int importSlice(long userIdentityId,
                          CalendarSync pair,
                          Calendar calendar,
                          CaldavUserSetting settings,
                          CalDavEndpoint endpoint,
                          Instant from,
                          Instant to) {
    List<CalendarObject> objects;
    try {
      objects = calDavClient.calendarQuery(endpoint,
                                           collectionUrl(pair),
                                           from,
                                           to,
                                           settings.getUsername(),
                                           settings.getPassword());
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
      if (importObject(userIdentityId, pair, calendar, object)) {
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
   * @return true when an event was created or updated
   */
  private boolean importObject(long userIdentityId, CalendarSync pair, Calendar calendar, CalendarObject object) {
    List<IcsEvent> parsed = icsParser.parse(object.calendarData());
    if (parsed.isEmpty()) {
      return false;
    }
    IcsEvent master = parsed.get(0);
    if (StringUtils.isNotBlank(master.getOccurrenceId())) {
      // An object holding only overrides, with the series living elsewhere.
      // Creating them as events of their own would show the amendments as
      // separate meetings beside a series that already covers those days.
      LOG.debug("Object {} carries only overrides and is left for the occurrence pass", object.href());
      return false;
    }
    if (isMirrorOwned(pair, master.getUid())) {
      // A copy eXo wrote itself. Importing it would show the user a second,
      // personal event standing for a space meeting they already see.
      LOG.debug("Object {} is a copy eXo wrote into the mirror and is not imported back", object.href());
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
    return create(userIdentityId, pair, calendar, object, master, parsed);
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
   * The mirror pair itself is exempt. Reading the mirror back is not importing
   * a foreign object, and answering true there would make the mirror unable to
   * reconcile the copies it owns.
   *
   * @param pair the binding being read
   * @param icsUid the object's iCalendar UID
   * @return true when a mirror pair of this user already maps that UID
   */
  private boolean isMirrorOwned(CalendarSync pair, String icsUid) {
    if (pair.getOrigin() == SyncOrigin.MIRROR) {
      return false;
    }
    return caldavSyncStorage.isMirrorOwned(pair.getUserIdentityId(), pair.getServerId(), icsUid);
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
   * @return true when the event was created
   */
  private boolean create(long userIdentityId,
                         CalendarSync pair,
                         Calendar calendar,
                         CalendarObject object,
                         IcsEvent master,
                         List<IcsEvent> parsed) {
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
    caldavSyncStorage.saveObject(mapping);
    applyOccurrences(userIdentityId, calendar, created.getId(), master, parsed);
    return true;
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
      caldavSyncStorage.deleteObject(known.getId());
      return false;
    }
    Event local = agendaEventService.getEventById(known.getLocalEventId());
    if (local == null) {
      LOG.debug("Event {} is gone; its mapping is dropped so the object can be imported again", known.getLocalEventId());
      caldavSyncStorage.deleteObject(known.getId());
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
                                     null,
                                     false,
                                     userIdentityId);
    } catch (Exception e) { // NOSONAR agenda declares several checked exceptions here
      LOG.warn("The event of object {} could not be updated in calendar {}", object.href(), calendar.getId(), e);
      return false;
    }
    known.setEtag(object.etag());
    known.setRemoteHref(object.href());
    known.setLastSync(new Date());
    caldavSyncStorage.saveObject(known);
    applyOccurrences(userIdentityId, calendar, local.getId(), master, parsed);
    return true;
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
   * @param master the parsed master
   * @param parsed every event the object carried, master first
   */
  private void applyOccurrences(long userIdentityId,
                                Calendar calendar,
                                long masterEventId,
                                IcsEvent master,
                                List<IcsEvent> parsed) {
    for (IcsEvent override : parsed) {
      if (StringUtils.isBlank(override.getOccurrenceId())) {
        continue;
      }
      amendOccurrence(userIdentityId, calendar, masterEventId, override);
    }
    if (master.getExceptionDates() == null) {
      return;
    }
    for (String excluded : master.getExceptionDates()) {
      cancelOccurrence(userIdentityId, masterEventId, excluded, master.getTimeZoneId());
    }
  }

  /**
   * Applies one override to the occurrence it amends.
   *
   * @param userIdentityId identity of the user
   * @param calendar the eXo calendar standing for the collection
   * @param masterEventId the series in agenda
   * @param override the parsed override
   */
  private void amendOccurrence(long userIdentityId, Calendar calendar, long masterEventId, IcsEvent override) {
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
      agendaEventService.updateEvent(amended,
                                     keptAttendees(occurrence.getId(), userIdentityId),
                                     List.of(),
                                     List.of(),
                                     List.of(),
                                     null,
                                     false,
                                     userIdentityId);
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
   * @param excluded the raw excluded date
   * @param zoneId the zone the series is anchored on
   */
  private void cancelOccurrence(long userIdentityId, long masterEventId, String excluded, String zoneId) {
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
      agendaEventService.updateEvent(occurrence,
                                     keptAttendees(occurrence.getId(), userIdentityId),
                                     List.of(),
                                     List.of(),
                                     List.of(),
                                     null,
                                     false,
                                     userIdentityId);
    } catch (Exception e) { // NOSONAR agenda declares several checked exceptions here
      // A meeting the user cancelled elsewhere still showing here is wrong,
      // but it is a smaller wrong than losing the series over it.
      LOG.warn("Occurrence {} of series {} could not be cancelled", occurrenceId, masterEventId, e);
    }
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
   * The connected account behind a binding, when it is still usable.
   *
   * @param userIdentityId identity of the user
   * @param pair the binding being read
   * @return the account, or null when there is none to read with
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
   * @param pair the binding being read
   * @param calendar the eXo calendar it fills
   * @param from start of the window, for a full read
   * @param to end of the window, for a full read
   * @param fullRead true to re-read the whole window rather than ask what
   *          changed — a binding with no token, or one whose window has moved
   * @return what the reconciliation removed and what it could not
   */
  public VanishedCleanup syncContents(long userIdentityId,
                                      CalendarSync pair,
                                      Calendar calendar,
                                      Instant from,
                                      Instant to,
                                      boolean fullRead) {
    if (pair == null || calendar == null) {
      return VanishedCleanup.nothing();
    }
    if (!fullRead && StringUtils.isNotBlank(pair.getSyncToken())) {
      CaldavUserSetting settings = settingsFor(userIdentityId, pair);
      if (settings != null) {
        VanishedCleanup incremental = readWhatChanged(userIdentityId, pair, calendar, settings);
        if (incremental != null) {
          return incremental;
        }
      }
    }
    importInto(userIdentityId, pair, calendar, from, to);
    return removeVanishedObjects(userIdentityId, pair, calendar.getTitle());
  }

  /**
   * Asks the account what changed since the token, and acts on the answer.
   *
   * @param userIdentityId identity of the user
   * @param pair the binding being read
   * @param calendar the eXo calendar it fills
   * @param settings the connected account
   * @return what was reconciled, or null when the token could not be used and
   *         the caller should read the window in full instead
   */
  private VanishedCleanup readWhatChanged(long userIdentityId,
                                          CalendarSync pair,
                                          Calendar calendar,
                                          CaldavUserSetting settings) {
    CalDavEndpoint endpoint;
    SyncCollectionResult report;
    try {
      endpoint = calDavClient.endpoint(pair.getServerId(), settings.getUsername());
      report = calDavClient.syncCollection(endpoint,
                                           collectionUrl(pair),
                                           pair.getSyncToken(),
                                           settings.getUsername(),
                                           settings.getPassword());
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
    if (!changed.isEmpty() && !importByHref(userIdentityId, pair, calendar, settings, endpoint, changed)) {
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
   * @return true when they were fetched; false when the account could not be
   *         asked, which must stop the token moving
   */
  private boolean importByHref(long userIdentityId,
                               CalendarSync pair,
                               Calendar calendar,
                               CaldavUserSetting settings,
                               CalDavEndpoint endpoint,
                               List<String> hrefs) {
    List<CalendarObject> objects;
    try {
      objects = calDavClient.multiget(endpoint,
                                      collectionUrl(pair),
                                      hrefs,
                                      settings.getUsername(),
                                      settings.getPassword());
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
      importObject(userIdentityId, pair, calendar, object);
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
   * @param pair the binding whose collection is reconciled
   * @return what was removed and what could not be
   */
  public VanishedCleanup removeVanishedObjects(long userIdentityId, CalendarSync pair) {
    return removeVanishedObjects(userIdentityId, pair, null);
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
   * @param pair the binding whose collection is reconciled
   * @param calendarName the eXo calendar's name, for the log only, may be null
   * @return what was removed and what could not be
   */
  public VanishedCleanup removeVanishedObjects(long userIdentityId, CalendarSync pair, String calendarName) {
    if (pair == null || StringUtils.isBlank(pair.getRemoteHref())) {
      return VanishedCleanup.nothing();
    }
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (settings == null || StringUtils.isBlank(settings.getUsername())) {
      return VanishedCleanup.nothing();
    }
    CalDavEndpoint endpoint;
    try {
      endpoint = calDavClient.endpoint(settings.getServerId(), settings.getUsername());
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
                                             token,
                                             settings.getUsername(),
                                             settings.getPassword());
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
                                                               collectionUrl(pair),
                                                               settings.getUsername(),
                                                               settings.getPassword());
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
                                             collectionUrl(pair),
                                             settings.getUsername(),
                                             settings.getPassword());
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

  private boolean removeOne(long userIdentityId, ObjectSync object) {
    try {
      agendaEventService.deleteEventById(object.getLocalEventId(), userIdentityId);
    } catch (ObjectNotFoundException e) {
      LOG.debug("Event {} was already gone from agenda; only its mapping is dropped", object.getLocalEventId(), e);
    } catch (IllegalAccessException e) {
      // Their own calendar, so this should not happen — and if it does, the
      // mapping is kept, because dropping it would hide an event eXo can no
      // longer account for.
      LOG.warn("User {} may not delete event {}; it stays as it is", userIdentityId, object.getLocalEventId(), e);
      return false;
    } catch (RuntimeException e) {
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
}
