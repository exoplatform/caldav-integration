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
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.Event;
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
    if (parsed.size() > 1) {
      // The overrides travel with their master and are applied by the
      // occurrence pass; importing them here would duplicate days the series
      // already covers.
      LOG.debug("Object {} carries {} overrides, left for the occurrence pass", object.href(), parsed.size() - 1);
    }
    ObjectSync known = caldavSyncStorage.getObjectByUid(pair.getId(), master.getUid());
    if (known != null && StringUtils.isNotBlank(known.getEtag()) && known.getEtag().equals(object.etag())) {
      // The server says nothing changed. Re-writing the event would bump its
      // modification date and make every sync look like an edit to anything
      // watching agenda.
      return false;
    }
    if (known != null) {
      // Updating an event already imported belongs with the conflict rules —
      // deciding which side wins needs both sides' modification times, which
      // this pass does not gather. Left rather than guessed at, so a remote
      // edit does not silently overwrite a local one.
      LOG.debug("Object {} changed since it was imported; left for the conflict pass", object.href());
      return false;
    }
    return create(userIdentityId, pair, calendar, object, master);
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
                         IcsEvent master) {
    Event event = icsEventMapper.toEvent(master, calendar.getId());
    Event created;
    try {
      // sendInvitation is false, and it is the most consequential argument
      // here. These attendees were invited by whoever organised the meeting,
      // on a server the user already reads; inviting them again because eXo
      // has just noticed the event would send real mail to real people for
      // something that happened days ago.
      //
      // Attendees are not mapped at all in this pass. Binding a server-
      // provided address to an eXo identity is a trust-boundary decision —
      // an ATTENDEE line is content, and content must not name a platform
      // user — and it deserves its own review rather than riding along here.
      created = agendaEventService.createEvent(event,
                                               List.of(),
                                               List.of(),
                                               List.of(),
                                               List.of(),
                                               null,
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
    return true;
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
