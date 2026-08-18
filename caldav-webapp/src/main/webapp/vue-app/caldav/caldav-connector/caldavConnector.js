import * as caldavConnectorService from '../js/agendaCaldavService.js';
import * as tsdav from 'tsdav';
import ICAL from 'ical.js';
export default {
  name: 'agenda.caldavCalendar',
  description: 'agenda.caldavCalendar.description',
  avatar: '/caldav/skin/image/caldav.png',
  isOauth: false,
  canConnect: true,
  canPush: true,
  initialized: true,
  isSignedIn: true,
  pushing: false,
  rank: 40,
  /**
   * Opens the settings drawer and resolves once the CalDAV server itself has
   * accepted the account. The drawer verifies the credentials against the
   * server before storing anything, and only then dispatches the
   * `test-connection` event with the connected settings — so receiving a
   * payload here genuinely means tested, not merely saved. A drawer closed
   * without connecting dispatches the event without payload. The listener is
   * armed for a single event, so an abandoned attempt does not leave a stale
   * listener behind to settle a later one.
   *
   * @returns {Promise<String>} the username of the connected account
   */
  connect() {
    return new Promise((resolve, reject) => {
      document.dispatchEvent(new CustomEvent('open-caldav-connector-settings-drawer'));
      document.addEventListener('test-connection', (settings) => {
        if (settings.detail) {
          resolve(settings.detail.username);
        } else {
          reject('connection canceled');
        }
      }, {once: true});
    });
  },
  disconnect() {
    return new Promise((resolve, reject) => {
      return caldavConnectorService.deleteCaldavSetting().then((respStatus) => {
        if (respStatus === 200) {
          return resolve(null);
        }
      }).catch(e => {
        return reject(e);
      });
    });
  },
  getEvents(periodStartDate, periodEndDate) {
    return caldavConnectorService.getCaldavSetting().then((settings)=> {
      return this.retrieveEvents(settings, periodStartDate, periodEndDate);
    });
  },
  async getCalendar(clientCaldav){
    const calendars = await clientCaldav.fetchCalendars({headersToExclude: ['If-None-Match']});
    if (calendars.length === 0) {
      throw caldavError('caldav.error.noCalendar');
    }
    return calendars[0];
  },
  async retrieveEvents(settings, periodStartDate, periodEndDate) {
    const start = caldavConnectorService.toRFC3339(periodStartDate, false, true);
    const end = caldavConnectorService.toRFC3339(periodEndDate, false, true);
    const clientCaldav = await createClient(settings);
    const calendar = await this.getCalendar(clientCaldav);
    const events = await clientCaldav.fetchCalendarObjects({
      calendar,
      expand: true,
      timeRange: {
        start: start,
        end: end,
      },
      headersToExclude: ['If-None-Match']
    });
    const listEvent = [];
    events.map(event => {
      const caldavEvent= {};
      const data = ICAL.parse(event.data);
      const iCal = new ICAL.Component(data); //component
      const eventComponent = iCal.getFirstSubcomponent('vevent'); //component
      const vEvent = new ICAL.Event(eventComponent); //Event
      if (vEvent) {
        if (vEvent.recurrenceId) {
          const recurrentEvents = iCal.getAllSubcomponents('vevent');
          recurrentEvents.forEach( e => {
            const eventItem = new ICAL.Event(e);
            const calEvent = {};
            calEvent.summary = eventItem.summary;
            calEvent.uid = eventItem.uid;
            calEvent.id = eventItem.uid;
            calEvent.color = '#FFFFFF';
            calEvent.type = 'remoteEvent';
            calEvent.location = eventItem.location;
            calEvent.description = eventItem.description;
            calEvent.recurringEventId = eventItem.uid;
            const startDate = e.getAllProperties('dtstart'); //ICAL.Property
            const endDate = e.getAllProperties('dtend'); //ICAL.Property
            calEvent.start = startDate && new Date(startDate[0].jCal[3]);
            if (startDate && !startDate[0].jCal[3].includes('T')) {
              calEvent.allDay = true;
            } else {
              calEvent.end = endDate && new Date(endDate[0].jCal[3]);
            }
            listEvent.push(calEvent);
          });
        } else if (vEvent.isRecurring()) {
          const startRangeDate = ICAL.Time.fromJSDate(caldavConnectorService.toDate(periodStartDate),false);//ICAL.Time
          const endRangeDate = ICAL.Time.fromJSDate(caldavConnectorService.toDate(periodEndDate),false);//ICAL.Time

          const expand = new ICAL.RecurExpansion({
            component: eventComponent,
            dtstart: vEvent.startDate
          });
          let next= expand.next(); //ICAL.Time
          while (next && next.compare(endRangeDate)<0) {
            if (next.compare(startRangeDate)>=0) {
              //create a new event for the recurrence :
              //we can have more than one occurence in the timerange requested
              const occurenceEvent= {};
              occurenceEvent.color = '#FFFFFF';
              occurenceEvent.type = 'remoteEvent';
              occurenceEvent.etag= event.etag;
              occurenceEvent.url = event.url;

              let realStartDate;

              if (vEvent.exceptions[next.toString()]) {
                //the current event have an exception for the next occurence
                const exceptionEvent = vEvent.exceptions[next.toString()];
                occurenceEvent.summary = exceptionEvent.summary;
                occurenceEvent.uid = exceptionEvent.uid;
                occurenceEvent.location = exceptionEvent.location;
                occurenceEvent.description = exceptionEvent.description;
                realStartDate = exceptionEvent.startDate;
              } else {
                occurenceEvent.summary = vEvent.summary;
                occurenceEvent.uid = vEvent.uid;
                occurenceEvent.location = vEvent.location;
                occurenceEvent.description = vEvent.description;
                realStartDate = next;
              }
              occurenceEvent.id = occurenceEvent.uid;
              occurenceEvent.recurringEventId = occurenceEvent.uid;
              const startDate = eventComponent.getAllProperties('dtstart'); //ICAL.Property
              occurenceEvent.start= new Date(realStartDate); //next : ICAL.Time
              if (startDate && !startDate[0].jCal[3].includes('T')) {
                occurenceEvent.allDay=true;
              } else {
                //if the event is not all day, we calculate the endDate as
                //endDate = next + duration
                //next is the startDate for the next occurence of the event
                //duration is the duration of the first event of the recurrence
                const calculatedEndDate = realStartDate.clone();
                calculatedEndDate.addDuration(vEvent.duration);
                occurenceEvent.end=calculatedEndDate.toJSDate();
              }
              listEvent.push(occurenceEvent);
            }
            next=expand.next();
          }
        } else {

          caldavEvent.summary = vEvent.summary;
          caldavEvent.uid = vEvent.uid;
          caldavEvent.id = vEvent.uid;
          caldavEvent.color = '#FFFFFF';
          caldavEvent.type = 'remoteEvent';
          caldavEvent.etag= event.etag;
          caldavEvent.url = event.url;
          const startDate = eventComponent.getAllProperties('dtstart'); //ICAL.Property
          const endDate = eventComponent.getAllProperties('dtend'); //ICAL.Property
          caldavEvent.start= startDate && new Date(startDate[0].jCal[3]);
          caldavEvent.location = vEvent.location;
          caldavEvent.description = vEvent.description;
          if (startDate && !startDate[0].jCal[3].includes('T')) {
            caldavEvent.allDay=true;
          } else {
            caldavEvent.end= endDate && new Date(endDate[0].jCal[3]);
          }
          listEvent.push(caldavEvent);
        }
      } else {
        return Promise.all(null);
      }

    });
    return listEvent;
  },
  pushEvent(event,) {
    return caldavConnectorService.getCaldavSetting().then((settings)=> {
      return this.saveEvent(event, settings);
    });
  },
  /**
   * Removes an agenda event from the remote calendar.
   *
   * The previous implementation searched the remote events of the period for
   * one whose UID equalled the agenda event id — a match that never held for
   * the UUID identifiers this connector writes — and it deleted the calendar
   * object by URL, so removing one occurrence of a recurring event removed
   * the object holding the whole series. The event now identifies its own
   * object through the remote identifier agenda stored at push time, and a
   * single occurrence is excluded from the series instead of the series
   * being deleted.
   *
   * @param {Object} event agenda event, or cancelled occurrence, to remove
   * @returns {Promise} resolves null once the remote calendar reflects it
   */
  deleteEvent(event) {
    return caldavConnectorService.getCaldavSetting().then((settings)=> {
      return this.removeEvent(event, settings);
    });
  },
  /**
   * Applies a deletion to the calendar object backing the event.
   *
   * A whole event, recurring or not, removes its object — overrides included,
   * since they live in the same object. A single occurrence must not: per RFC
   * 4791 every component of the series shares one object, so the occurrence
   * is excluded by rewriting the object without its override and with an
   * EXDATE on the master, under If-Match so a concurrent change of the
   * series surfaces as a conflict instead of being overwritten.
   *
   * @param {Object} event agenda event, or cancelled occurrence, to remove
   * @param {Object} settings connector settings holding the URL and credentials
   * @returns {Promise} resolves null once the remote calendar reflects it
   */
  async removeEvent(event, settings) {
    const clientCaldav = await createClient(settings);
    const calendar = await this.getCalendar(clientCaldav);
    const isOccurrence = !!event.occurrence;
    const icalUID = isOccurrence ? event.parent && event.parent.remoteId : event.remoteId;
    if (!icalUID) {
      return null;
    }
    const existing = await fetchCalendarObject(calendar, `${icalUID}.ics`, settings);
    if (!existing) {
      return null;
    }
    try {
      let response;
      const data = isOccurrence ? excludeOccurrence(existing.data, event) : null;
      if (data) {
        response = await clientCaldav.updateCalendarObject({
          calendarObject: {
            url: existing.url,
            data: data,
            etag: existing.etag,
          },
        });
      } else {
        response = await clientCaldav.deleteCalendarObject({
          calendarObject: {
            url: existing.url,
            etag: existing.etag,
          },
        });
      }
      ensureAccepted(response);
      return null;
    } catch (e) {
      console.error('Error deleting from CalDAV:', e);
      throw e.code ? e : caldavError('caldav.error.save', e);
    }
  },
  async saveEvent(event, settings) {
    const clientCaldav = await createClient(settings);
    const calendar = await this.getCalendar(clientCaldav);
    const isOccurrence = !!event.occurrence;
    const icalUID = isOccurrence ? event.parent.remoteId : event.remoteId || crypto.randomUUID();
    const filename = `${icalUID}.ics`;

    let iCalString = buildEventIcs(event, icalUID, isOccurrence);
    try {
      // Inside the try so that a property built wrongly is reported as a
      // failed save, with the offending object logged next to it, rather
      // than escaping as a bare parser error.
      iCalString = normalizeIcs(iCalString);
      // RFC 4791 stores every component sharing a UID — the master with its
      // RRULE plus one VEVENT per modified occurrence — in a single calendar
      // object. A PUT replaces that object wholesale, so pushing the VEVENT
      // built above on its own erased the rest of the series: writing an
      // occurrence override dropped the master and every other override,
      // which is how one edited occurrence deleted the whole series from the
      // server. The object is therefore read first and the VEVENT spliced
      // into what it already holds, and the write is conditional on the ETag
      // observed, so a concurrent change from another client surfaces as a
      // conflict instead of being silently overwritten.
      const existing = await fetchCalendarObject(calendar, filename, settings);
      let response;
      if (existing) {
        response = await clientCaldav.updateCalendarObject({
          calendarObject: {
            url: existing.url,
            data: mergeIntoCalendarObject(existing.data, iCalString, isOccurrence),
            etag: existing.etag,
          },
        });
      } else {
        // First push of the event: no read-modify-write needed, but the
        // If-None-Match: * precondition is kept — the previous code excluded
        // it because this call also served updates — so a concurrent creation
        // of the same object comes back as a conflict, not an overwrite.
        response = await clientCaldav.createCalendarObject({
          calendar, iCalString, filename
        });
      }
      ensureAccepted(response);
      return {id: icalUID};
    } catch (e) {
      console.error('Error creating/updating CalDAV:', e, 'iCalString:', iCalString);
      throw e.code ? e : caldavError('caldav.error.save', e);
    }
  }
};

/**
 * The whole iCalendar object for one event, ready to be pushed.
 * <p>
 * Assembled from parts so that each stays readable on its own: the property
 * set an event needs has grown well past what one function can hold without
 * hiding its own shape.
 *
 * @param  {object} event - the event being pushed
 * @param  {string} icalUID - the UID shared by the series and its overrides
 * @param  {boolean} isOccurrence - whether this component amends one instance
 * @returns {string} the VCALENDAR object, trimmed
 */
function buildEventIcs(event, icalUID, isOccurrence) {
  const dtStamp = new Date().toISOString().replace(/[-:]|\.\d{3}/g, '').replace('Z', 'Z');
  let ics = `BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Exo Platform//NONSGML v1.0//EN
CALSCALE:GREGORIAN
BEGIN:VEVENT
SUMMARY:${escapeText(event.summary)}
UID:${icalUID}
DTSTAMP:${dtStamp}
`;
  ics += scheduleLines(event);
  ics += describeLines(event);
  ics += stampLines(event);
  // CONFIRMED for every event pushed, deliberately, rather than a mapping
  // of the agenda status: eXo spells a date poll TENTATIVE, which in RFC
  // 5545 means "provisionally scheduled" — a poll pushed with its own word
  // would show up as a real meeting nobody has confirmed. An event only
  // reaches this connector once it is scheduled, so the honest value is the
  // constant. TRANSP is the RFC default and is written for explicitness, so
  // that a client reading the object does not have to know the default.
  ics += `STATUS:CONFIRMED
TRANSP:OPAQUE
`;
  ics += recurrenceLines(event, isOccurrence);
  ics += exceptionLines(event, isOccurrence);
  ics += buildAlarms(event);
  ics += `END:VEVENT
END:VCALENDAR
`;
  return ics.trim();
}

/**
 * The DTSTART/DTEND pair, in the form the event's own all-day flag calls for.
 *
 * @param  {object} event - the event being pushed
 * @returns {string} the two lines, terminated
 */
function scheduleLines(event) {
  let ics = '';
  if (event.allDay) {
    ics += `DTSTART;VALUE=DATE:${toIcsDate(event.start, event.timeZoneId)}
DTEND;VALUE=DATE:${toIcsEndDate(event.end, event.timeZoneId)}
`;
  } else {
    ics += `DTSTART:${toUTCString(event.start)}Z
DTEND:${toUTCString(event.end)}Z
`;
  }
  return ics;
}

/**
 * Everything a reader shows as text: where it is, what it is about, where it
 * lives in eXo, and the conference it can be joined through.
 *
 * @param  {object} event - the event being pushed
 * @returns {string} the properties, each terminated
 */
function describeLines(event) {
  let ics = '';
  if (event.location) {
    ics += `LOCATION:${escapeText(event.location)}\n`;
  }
  const conferenceUrl = toUri(event.conferences?.length > 0 && event.conferences[0]?.url);
  const descriptionParts = [];
  if (event.description) {
    descriptionParts.push(htmlToText(event.description));
  }
  if (conferenceUrl) {
    descriptionParts.push(conferenceUrl);
  }
  if (descriptionParts.length > 0) {
    ics += `DESCRIPTION:${escapeText(descriptionParts.join('\n\n'))}\n`;
  }
  const eventLink = eventUrl(event);
  if (eventLink) {
    ics += `URL:${eventLink}\n`;
  }
  if (conferenceUrl) {
    // In addition to the line the description already carries, never
    // instead of it: support for this property is patchy — Apple mostly
    // recognises known providers by sniffing the description rather than
    // by reading CONFERENCE, Thunderbird handles it partially — so the
    // description line stays the one thing every client can show, and the
    // property is what a client that does read it can act on.
    // A single feature, not the VIDEO,AUDIO list RFC 7986 allows: ical.js
    // quotes any parameter value holding a comma, which turns the list into
    // one value that a strict reader then ignores. One correct token beats
    // two that are read as none.
    ics += `CONFERENCE;VALUE=URI;FEATURE=VIDEO:${conferenceUrl}\n`;
  }
  return ics;
}

/**
 * CREATED and LAST-MODIFIED, when the event carries them.
 *
 * @param  {object} event - the event being pushed
 * @returns {string} the properties, each terminated
 */
function stampLines(event) {
  let ics = '';
  const created = toIcsTimestamp(event.created);
  if (created) {
    ics += `CREATED:${created}\n`;
  }
  const lastModified = toIcsTimestamp(event.updated);
  if (lastModified) {
    ics += `LAST-MODIFIED:${lastModified}\n`;
  }
  return ics;
}

/**
 * What ties the component to a series: the occurrence it amends, or the rule
 * the master repeats by.
 *
 * @param  {object} event - the event being pushed
 * @param  {boolean} isOccurrence - whether this component amends one instance
 * @returns {string} the property, or an empty string
 */
function recurrenceLines(event, isOccurrence) {
  let ics = '';
  if (isOccurrence) {
    // Exactly one RECURRENCE-ID, and always one. Both writes used to sit
    // under the same condition, so a timed occurrence carried the property
    // twice — it may occur at most once — while an all-day occurrence
    // carried none at all and, having no anchor, replaced the master event
    // instead of amending the single instance it was meant to.
    //
    // The form follows event.allDay, matching how DTSTART above decides, and
    // not whether the occurrence identifier happens to contain a time. RFC
    // 5545 requires this property to carry the same value type as the
    // DTSTART of the series it points into, and agenda identifies an
    // occurrence of an all-day event by an instant all the same — so keying
    // off the identifier wrote a date-time reference into a date-valued
    // series, which matches no instance at all.
    if (event.allDay) {
      ics += `RECURRENCE-ID;VALUE=DATE:${toIcsDate(event.occurrence.id, event.timeZoneId)}\n`;
    } else {
      ics += `RECURRENCE-ID:${event.occurrence.id.replace(/[-:]|\.\d{3}/g, '')}Z\n`;
    }
  } else if (event.recurrence?.rrule) {
    let rruleValue = event.recurrence.rrule.trim();
    rruleValue = rruleValue.replace(/COUNT=0;?/, '');
    if (rruleValue.length > 0) {
      ics += `RRULE:${rruleValue}\n`;
    }
  }
  return ics;
}

/**
 * The instances deleted from a series, written by the master only.
 *
 * @param  {object} event - the event being pushed
 * @param  {boolean} isOccurrence - whether this component amends one instance
 * @returns {string} one EXDATE per exception
 */
function exceptionLines(event, isOccurrence) {
  let ics = '';
  if (!isOccurrence && event.recurrence?.exceptions && event.recurrence.exceptions.length > 0) {
    event.recurrence.exceptions.forEach(exdate => {
      const exDateFormatted = exdate.date.replace(/[-:]|\.\d{3}/g, '');
      if (exDateFormatted.includes('T')) {
        ics += `EXDATE:${exDateFormatted}Z\n`;
      } else {
        ics += `EXDATE;VALUE=DATE:${exDateFormatted}\n`;
      }
    });
  }
  return ics;
}

/**
 * Opens a DAV client for the connected account.
 *
 * Rejects rather than resolving null when the connection cannot be made. The
 * previous behaviour logged and returned null, which the caller turned into a
 * blank remote identifier, which agenda then read as "this event has no
 * remote counterpart, drop the link". A failed push therefore did not merely
 * fail: it erased the record that anything was ever meant to sync, leaving a
 * later retry with nothing to retry from.
 *
 * @param {Object} settings connector settings holding the URL and credentials
 * @returns {Promise<Object>} a connected tsdav client
 */
async function createClient(settings) {
  try {
    return await tsdav.createDAVClient({
      serverUrl: settings.caldavUrl.replace('{username}', settings.username),
      credentials: {
        username: settings.username,
        password: settings.password,
      },
      authMethod: 'Basic',
      defaultAccountType: 'caldav',
    });
  } catch (e) {
    console.error('cannot connect to the CalDAV server, check the URL and credentials', e);
    throw caldavError('caldav.error.connection', e);
  }
}

/**
 * Builds an error carrying a stable code, so that agenda can turn a failure
 * into a message the user can act on — check your credentials, this calendar
 * is read-only, try again — without parsing text or reaching into tsdav's
 * own error shapes.
 *
 * @param {String} code stable identifier for the kind of failure
 * @param {Object} cause underlying error, kept for logging and for its status
 * @returns {Error} the error to reject with
 */
function caldavError(code, cause) {
  const error = new Error(code);
  error.code = code;
  error.status = cause?.status || cause?.response?.status;
  error.cause = cause;
  return error;
}

/**
 * Checks the outcome of a write to the server. tsdav hands back the raw
 * fetch Response without looking at it, so a rejected write — including the
 * 412 a failed If-Match or If-None-Match precondition produces — would
 * otherwise count as a success while the server kept its own version.
 *
 * @param {Object} response fetch Response of a PUT or DELETE
 * @returns {void} nothing when the server accepted the write
 * @throws {Error} caldav.error.conflict on 412, caldav.error.save otherwise
 */
function ensureAccepted(response) {
  if (response.status === 412) {
    throw caldavError('caldav.error.conflict', response);
  }
  if (!response.ok) {
    throw caldavError('caldav.error.save', response);
  }
}

/**
 * Reads one calendar object of the connected calendar, with the ETag the
 * later conditional write needs.
 *
 * The lookup is a plain GET on the one URL the filename denotes, rather than
 * a calendar-multiget through tsdav: tsdav turns the 404 entry a multiget
 * reports for a missing object into a thrown query failure, which would make
 * "the object does not exist yet" — the normal first push of an event —
 * indistinguishable from a real error. The GET answers it in one request
 * with an unambiguous status.
 *
 * @param {Object} calendar calendar collection holding the object
 * @param {String} filename name of the object inside the collection
 * @param {Object} settings connector settings holding the URL and credentials
 * @returns {Promise<Object>} the object with url, etag and data, or null
 */
async function fetchCalendarObject(calendar, filename, settings) {
  const url = new URL(filename, calendar.url).href;
  const response = await fetch(url, {
    headers: {
      authorization: `Basic ${btoa(`${settings.username}:${settings.password}`)}`,
    },
  });
  if (response.status === 404) {
    return null;
  }
  if (!response.ok) {
    throw caldavError('caldav.error.save', response);
  }
  return {
    url: url,
    etag: response.headers.get('etag'),
    data: await response.text(),
  };
}

/**
 * Splices the freshly built VEVENT into the calendar object as the server
 * holds it, keeping every component the push is not about.
 *
 * An occurrence override replaces the override carrying the same
 * RECURRENCE-ID — so editing the same occurrence twice updates it rather
 * than duplicating it — and is appended when none matches; the master and
 * the other overrides are untouched. A master replaces the master and keeps
 * the overrides: agenda re-pushes every exceptional occurrence right after
 * the series itself, so the overrides converge on their own, and dropping
 * them here would lose them entirely on the pushes — a response update, for
 * one — that are not followed by that loop. Overrides falling on a date the
 * new master excludes with an EXDATE are removed, though: their occurrence
 * was deleted, and an override contradicting an EXDATE leaves the deleted
 * occurrence visible on clients that favor the override.
 *
 * @param {String} existingData the calendar object as fetched from the server
 * @param {String} newComponentString VCALENDAR holding the one VEVENT to push
 * @param {Boolean} isOccurrence whether that VEVENT is an occurrence override
 * @returns {String} the merged calendar object, ready to PUT
 */
function mergeIntoCalendarObject(existingData, newComponentString, isOccurrence) {
  const calendar = new ICAL.Component(ICAL.parse(existingData));
  const newEvent = new ICAL.Component(ICAL.parse(newComponentString)).getFirstSubcomponent('vevent');
  let replaced;
  if (isOccurrence) {
    const recurrenceId = newEvent.getFirstPropertyValue('recurrence-id');
    replaced = vevent => isSameOccurrence(vevent.getFirstPropertyValue('recurrence-id'), recurrenceId);
  } else {
    const exdates = newEvent.getAllProperties('exdate').map(property => property.getFirstValue());
    replaced = vevent => {
      const recurrenceId = vevent.getFirstPropertyValue('recurrence-id');
      return !recurrenceId || exdates.some(exdate => isSameOccurrence(recurrenceId, exdate));
    };
  }
  calendar.getAllSubcomponents('vevent').filter(replaced)
    .forEach(vevent => calendar.removeSubcomponent(vevent));
  calendar.addSubcomponent(newEvent);
  return calendar.toString();
}

/**
 * Rewrites the calendar object so that it no longer produces the given
 * occurrence: the override carrying its RECURRENCE-ID, if any, is removed,
 * and the master gains an EXDATE for the instance — in the value type of its
 * own DTSTART, since RFC 5545 matches instances by identical value.
 *
 * @param {String} existingData the calendar object as fetched from the server
 * @param {Object} event cancelled occurrence, carrying occurrence.id
 * @returns {String} the object to PUT back, or null when nothing remains of
 * it and the object itself should be deleted instead
 */
function excludeOccurrence(existingData, event) {
  const calendar = new ICAL.Component(ICAL.parse(existingData));
  const master = calendar.getAllSubcomponents('vevent')
    .find(vevent => !vevent.getFirstPropertyValue('recurrence-id'));
  const dtstart = master && master.getFirstPropertyValue('dtstart');
  const occurrence = occurrenceToTime(event, dtstart ? dtstart.isDate : event.allDay);
  calendar.getAllSubcomponents('vevent')
    .filter(vevent => isSameOccurrence(vevent.getFirstPropertyValue('recurrence-id'), occurrence))
    .forEach(vevent => calendar.removeSubcomponent(vevent));
  if (calendar.getAllSubcomponents('vevent').length === 0) {
    return null;
  }
  if (master && !master.getAllProperties('exdate').some(property => isSameOccurrence(property.getFirstValue(), occurrence))) {
    const exdate = new ICAL.Property('exdate');
    exdate.setValue(occurrence);
    master.addProperty(exdate);
  }
  return calendar.toString();
}

/**
 * The instant an occurrence identifier denotes, as an ICAL.Time in the form
 * the series uses: the calendar date in the event's zone for an all-day
 * series, the UTC instant otherwise — mirroring how saveEvent writes
 * RECURRENCE-ID for the same identifier.
 *
 * @param {Object} event occurrence event, carrying occurrence.id
 * @param {Boolean} asDate whether the series is date-valued
 * @returns {Object} the occurrence as an ICAL.Time
 */
function occurrenceToTime(event, asDate) {
  if (asDate) {
    const {year, month, day} = datePartsInZone(event.occurrence.id, event.timeZoneId);
    return new ICAL.Time({year, month, day, isDate: true});
  }
  return ICAL.Time.fromJSDate(new Date(event.occurrence.id), true);
}

/**
 * Whether two RECURRENCE-ID or EXDATE values denote the same occurrence.
 * When either side is date-valued the calendar dates are compared, so that a
 * date reference finds the occurrence even against a component written with
 * the other value type by an older client.
 *
 * @param {Object} left one value as an ICAL.Time, possibly absent
 * @param {Object} right the other value as an ICAL.Time, possibly absent
 * @returns {Boolean} whether both are present and denote the same occurrence
 */
function isSameOccurrence(left, right) {
  if (!left || !right) {
    return false;
  }
  if (left.isDate || right.isDate) {
    return left.year === right.year && left.month === right.month && left.day === right.day;
  }
  return left.compare(right) === 0;
}

/**
 * Rewrites a calendar object through ical.js, so that what leaves the browser
 * obeys the line rules of RFC 5545 section 3.1: CRLF endings, and content
 * lines of at most 75 octets with the remainder folded onto continuation
 * lines. The builder above concatenates bare newlines and folds nothing, so a
 * description of a few sentences went out as a single 500-character line —
 * which a strict server may reject outright and a lenient one stores as it
 * sees fit.
 *
 * It also reconciles the two write paths. An update already passed through
 * ical.js, since the VEVENT is spliced into the object the server holds and
 * that object is re-serialised; a creation PUT the hand-built text as it was.
 * The first push of an event and every later push of the same event were
 * therefore structurally different documents, which is exactly the kind of
 * difference that makes a bug reproduce on one client and not on another.
 *
 * Parsing here has a second effect worth as much as the folding: a property
 * this connector builds wrongly fails in the browser, where the value that
 * caused it is at hand, instead of coming back as a 400 with no detail.
 *
 * @param {String} iCalString calendar object as built by saveEvent
 * @returns {String} the same object, folded and with CRLF endings
 */
function normalizeIcs(iCalString) {
  return new ICAL.Component(ICAL.parse(iCalString)).toString();
}

/**
 * Absolute link to the event in eXo, for the URL property — the one way back
 * from the copy on the phone to the event itself, its attendees and its
 * space. It has to be absolute: a client is not a browser sitting on the
 * portal, so a path alone resolves against nothing.
 *
 * The path is the one the agenda application uses to open an event from
 * outside itself, as its search results already do.
 *
 * @param {Object} event agenda event being pushed
 * @returns {String} the link, or an empty string when the page carries no
 * portal environment to build it from
 */
function eventUrl(event) {
  const eventId = event.id || event.parent?.id;
  const portal = window.eXo?.env?.portal;
  if (!eventId || !portal?.portalName || !portal?.context) {
    return '';
  }
  return `${window.location.origin}${portal.context}/${portal.portalName}/agenda?eventId=${eventId}`;
}

/**
 * Prepares a value for a URI property. URI values are not TEXT: their commas
 * and semicolons are part of the address and escaping them would corrupt it,
 * so nothing is escaped here. A line break is the one character that cannot
 * be carried — it would end the property and turn the rest of the address
 * into a line of its own — and is removed rather than allowed to corrupt the
 * object.
 *
 * @param {String} value address as agenda supplies it, possibly absent
 * @returns {String} the address, or an empty string when there is none
 */
function toUri(value) {
  if (!value) {
    return '';
  }
  return String(value).replace(/[\r\n]/g, '').trim();
}

/**
 * Formats one of the event's own timestamps as a UTC date-time, for CREATED
 * and LAST-MODIFIED. Those two let a client tell which of two copies of an
 * event is the newer one; DTSTAMP cannot, since it says when the object was
 * written, which is now for every push.
 *
 * Agenda leaves the timestamp out on occasion — an event that has never been
 * updated has no updated date — so an unusable value yields nothing rather
 * than an epoch or an Invalid Date.
 *
 * @param {String} value timestamp as agenda supplies it, RFC 3339
 * @returns {String} the value as YYYYMMDDTHHMMSSZ, or an empty string
 */
function toIcsTimestamp(value) {
  if (!value) {
    return '';
  }
  const date = new Date(value);
  if (isNaN(date.getTime())) {
    return '';
  }
  return `${toUTCString(date.toISOString())}Z`;
}

/**
 * Escapes a value for an iCalendar TEXT property, per RFC 5545 section
 * 3.3.11. Nothing was escaped before: a title holding a comma or a semicolon
 * was read by the server as several values, and one holding a newline
 * injected a line into the object, corrupting everything after it.
 *
 * The backslash is replaced first, otherwise it would escape the escapes
 * added afterwards.
 *
 * @param {String} value raw text destined for a TEXT property
 * @returns {String} the escaped value, safe to concatenate into an ICS
 */
function escapeText(value) {
  if (!value) {
    return '';
  }
  return String(value)
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,')
    .replace(/\r\n|\r|\n/g, '\\n');
}

/**
 * Reduces the rich text agenda stores as a description to the plain text an
 * iCalendar DESCRIPTION can carry. Calendar clients render the property
 * literally, so markup left in place is shown as markup on the phone.
 *
 * @param {String} html description as agenda supplies it
 * @returns {String} the same content as plain text, block tags becoming breaks
 */
function htmlToText(html) {
  if (!html) {
    return '';
  }
  const withBreaks = String(html)
    .replace(/<\s*br\s*\/?\s*>/gi, '\n')
    .replace(/<\s*\/\s*(p|div|li|tr|h[1-6])\s*>/gi, '\n');
  const element = document.createElement('div');
  element.innerHTML = withBreaks;
  return (element.textContent || '').replace(/\n{3,}/g, '\n\n').trim();
}

/**
 * The calendar date of a value, as seen in the event's own time zone.
 *
 * Neither of the obvious shortcuts works. Converting to UTC and truncating
 * moves the day for every user east of Greenwich — midnight in Tunis is 23:00
 * UTC the day before. Reading the leading YYYY-MM-DD off the string has the
 * same fault whenever the value is an instant rather than a plain date, and
 * agenda supplies both: an all-day event arrives with a date for its start and
 * an instant for its end, so the two were resolving to different days and the
 * event came out with no duration at all.
 *
 * The zone is therefore explicit, and the parts come from the formatter rather
 * than from any string slicing.
 *
 * @param {String} value date or instant as agenda supplies it
 * @param {String} timeZoneId the event's zone; the runtime's own zone if absent
 * @returns {Object} the year, month and day in that zone
 */
function datePartsInZone(value, timeZoneId) {
  const options = {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  };
  if (timeZoneId) {
    options.timeZone = timeZoneId;
  }
  const parts = new Intl.DateTimeFormat('en-US', options).formatToParts(new Date(value));
  const read = type => parts.find(part => part.type === type).value;
  return {
    year: Number(read('year')),
    month: Number(read('month')),
    day: Number(read('day')),
  };
}

/**
 * Formats the date part of a value for a VALUE=DATE property.
 *
 * @param {String} value date or instant as agenda supplies it
 * @param {String} timeZoneId the event's zone
 * @returns {String} the date as YYYYMMDD
 */
function toIcsDate(value, timeZoneId) {
  const {year, month, day} = datePartsInZone(value, timeZoneId);
  return `${year}${pad(month)}${pad(day)}`;
}

/**
 * The DTEND of an all-day event, which RFC 5545 defines as exclusive: a
 * one-day event ends on the day after it. Agenda holds the last day the event
 * covers, so a day is added here — the same adjustment the Google connector
 * already makes from the same source value, and its absence is why an
 * all-day event pushed to CalDAV came out a day short.
 *
 * The arithmetic is done at midday UTC so that no daylight-saving transition
 * can move the result onto a neighbouring day.
 *
 * @param {String} value last day the event covers, a date or an instant
 * @param {String} timeZoneId the event's zone, in which that day is read
 * @returns {String} the exclusive end date as YYYYMMDD
 */
function toIcsEndDate(value, timeZoneId) {
  const {year, month, day} = datePartsInZone(value, timeZoneId);
  const date = new Date(Date.UTC(year, month - 1, day, 12));
  date.setUTCDate(date.getUTCDate() + 1);
  return `${date.getUTCFullYear()}${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}`;
}

/**
 * Builds one VALARM per reminder the event carries, so that a mirrored
 * meeting alerts on the device the way it does in eXo. Without them the copy
 * is silent, which is the difference between an entry in a calendar and a
 * reminder to attend the thing.
 *
 * @param {Object} event agenda event being pushed
 * @returns {String} the VALARM components, empty when the event has none
 */
function buildAlarms(event) {
  if (!event.reminders?.length) {
    return '';
  }
  return event.reminders.map(reminder => {
    const minutes = reminderMinutes(reminder);
    if (minutes === null) {
      return '';
    }
    return `BEGIN:VALARM
ACTION:DISPLAY
DESCRIPTION:${escapeText(event.summary)}
TRIGGER:-PT${minutes}M
END:VALARM
`;
  }).join('');
}

/**
 * Converts one agenda reminder into minutes before the event.
 *
 * @param {Object} reminder reminder as agenda holds it, a quantity and a unit
 * @returns {Number} minutes before the start, or null when unusable
 */
function reminderMinutes(reminder) {
  const before = Number(reminder?.before);
  if (!Number.isFinite(before) || before < 0) {
    return null;
  }
  switch (String(reminder.beforePeriodType || '').toLowerCase()) {
  case 'hour':
    return before * 60;
  case 'day':
    return before * 60 * 24;
  case 'week':
    return before * 60 * 24 * 7;
  default:
    return before;
  }
}

/**
 * Pads a number to two digits, for the date formats above.
 *
 * @param {Number} value number to pad
 * @returns {String} the value as at least two characters
 */
function pad(value) {
  return value < 10 ? `0${value}` : `${value}`;
}

/**
 * Formats a timed value as an iCalendar UTC date-time, without the trailing
 * Z, which the callers add. Only used for events that carry a time; all-day
 * values go through toIcsDate.
 *
 * @param {String} dateStr date-time as agenda supplies it
 * @returns {String} the value as YYYYMMDDTHHMMSS in UTC
 */
function toUTCString(dateStr) {
  const d = new Date(dateStr);
  return d.toISOString().replace(/[-:]|\.\d{3}/g, '').replace('Z', '');
}