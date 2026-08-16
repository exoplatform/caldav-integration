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
  connect() {
    return new Promise((resolve, reject) => {
      document.dispatchEvent(new CustomEvent('open-caldav-connector-settings-drawer'));
      document.addEventListener('test-connection', (settings) => {
        if (settings.detail) {
          resolve(settings.detail.username);
        } else {
          reject('connection canceled');
        }
      });
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
  deleteEvent(event) {
    this.getEvents(event.startDate,event.endDate).then((events)=> {
      events.forEach((obj) => {
        if (event.id === parseInt(obj.uid)) {
          return caldavConnectorService.getCaldavSetting().then((settings)=> {
            return this.removeEvent(obj, settings);
          });
        }
      });
    });
  },
  async removeEvent(event, settings) {
    const clientCaldav = await createClient(settings);
    await this.getCalendar(clientCaldav);
    return clientCaldav.deleteCalendarObject({
      calendarObject: {
        url: event.url,
        etag: event.etag,
      },
      headersToExclude: ['If-None-Match']
    });
  },
  async saveEvent(event, settings) {
    const clientCaldav = await createClient(settings);
    const calendar = await this.getCalendar(clientCaldav);
    const isOccurrence = !!event.occurrence;
    const icalUID = isOccurrence ? event.parent.remoteId : event.remoteId || crypto.randomUUID();
    const filename = `${icalUID}.ics`;

    const dtStamp = new Date().toISOString().replace(/[-:]|\.\d{3}/g, '').replace('Z', 'Z');

    let iCalString = `BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Exo Platform//NONSGML v1.0//EN
BEGIN:VEVENT
SUMMARY:${escapeText(event.summary)}
UID:${icalUID}
DTSTAMP:${dtStamp}
`;
    if (event.allDay) {
      iCalString += `DTSTART;VALUE=DATE:${toIcsDate(event.start, event.timeZoneId)}
DTEND;VALUE=DATE:${toIcsEndDate(event.end, event.timeZoneId)}
`;
    } else {
      iCalString += `DTSTART:${toUTCString(event.start)}Z
DTEND:${toUTCString(event.end)}Z
`;
    }
    if (event.location) {
      iCalString += `LOCATION:${escapeText(event.location)}\n`;
    }
    const descriptionParts = [];
    if (event.description) {
      descriptionParts.push(htmlToText(event.description));
    }
    if (event.conferences?.length > 0 && event.conferences[0]?.url) {
      descriptionParts.push(event.conferences[0].url);
    }
    if (descriptionParts.length > 0) {
      iCalString += `DESCRIPTION:${escapeText(descriptionParts.join('\n\n'))}\n`;
    }
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
        iCalString += `RECURRENCE-ID;VALUE=DATE:${toIcsDate(event.occurrence.id, event.timeZoneId)}\n`;
      } else {
        iCalString += `RECURRENCE-ID:${event.occurrence.id.replace(/[-:]|\.\d{3}/g, '')}Z\n`;
      }
    } else if (event.recurrence?.rrule) {
      let rruleValue = event.recurrence.rrule.trim();
      rruleValue = rruleValue.replace(/COUNT=0;?/, '');
      if (rruleValue.length > 0) {
        iCalString += `RRULE:${rruleValue}\n`;
      }
    }
    if (!isOccurrence && event.recurrence?.exceptions && event.recurrence.exceptions.length > 0) {
      event.recurrence.exceptions.forEach(exdate => {
        const exDateFormatted = exdate.date.replace(/[-:]|\.\d{3}/g, '');
        if (exDateFormatted.includes('T')) {
          iCalString += `EXDATE:${exDateFormatted}Z\n`;
        } else {
          iCalString += `EXDATE;VALUE=DATE:${exDateFormatted}\n`;
        }
      });
    }
    iCalString += buildAlarms(event);
    iCalString += `END:VEVENT
END:VCALENDAR
`;
    iCalString = iCalString.trim();
    try {
      await clientCaldav.createCalendarObject({
        calendar, iCalString, filename, headersToExclude: ['If-None-Match']
      });
      return {id: icalUID};
    } catch (e) {
      console.error('Error creating/updating CalDAV:', e, 'iCalString:', iCalString);
      throw caldavError('caldav.error.save', e);
    }
  }
};

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