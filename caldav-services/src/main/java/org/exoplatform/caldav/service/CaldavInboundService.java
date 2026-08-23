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

  /**
   * The connected account behind a binding, when it is still usable.
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
}
