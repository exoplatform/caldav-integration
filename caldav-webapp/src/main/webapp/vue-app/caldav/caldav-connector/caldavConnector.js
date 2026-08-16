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
        if (!settings.detail) {
          reject('connection canceled');
          return;
        }
        // Actually reach the server before declaring the account connected.
        // Saving the credentials only proves eXo stored them; until something
        // asks the server, a mistyped password is accepted, the connector
        // reports itself connected, and every later request answers 401 —
        // which surfaces as an empty calendar list, or as the browser's own
        // credentials dialog, rather than as "wrong password".
        this.retrieveCalendars(settings.detail)
          .then(() => resolve(settings.detail.username))
          .catch(error => {
            console.error('the CalDAV server refused the credentials', error);
            document.dispatchEvent(new CustomEvent('caldav-connection-refused'));
            reject('caldav.error.credentials');
          });
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
  canListCalendars: true,
  /**
   * The calendars of the connected account, in the shape agenda expects from
   * any connector: an identity, a name, a colour that is always usable, and
   * whether the collection may be written to.
   *
   * The identity is the collection URL and never the display name. A user
   * renaming a calendar in their own client must not detach whatever eXo has
   * associated with it, and nothing stops two collections sharing a name.
   *
   * @returns {Promise<Array>} one entry per calendar of the connected account
   */
  listCalendars() {
    return caldavConnectorService.getCaldavSetting().then(settings => this.retrieveCalendars(settings));
  },
  /**
   * Reads the calendar collections of an account and maps them to the
   * normalised shape.
   *
   * @param {Object} settings connector settings holding the URL and credentials
   * @returns {Promise<Array>} the calendars of that account
   */
  async retrieveCalendars(settings) {
    // No account, no request. Once the user disconnects, the stored settings
    // are gone and the username is null, which used to be interpolated into
    // the URL and sent as /dav/cal/null/ — answered with a 401 that the
    // browser turns into its own credentials prompt, in the middle of the
    // agenda, for an account that no longer exists.
    if (!settings || !settings.username || !settings.caldavUrl) {
      return [];
    }
    // A rejected credential must surface as such. tsdav answers a 401 by
    // falling back to probing the server root, which then fails with "cannot
    // find principalUrl" — a message about discovery for what is simply a
    // wrong password.
    const probe = await fetch(settings.caldavUrl.replace('{username}', settings.username), {
      method: 'PROPFIND',
      headers: {
        'Depth': '0',
        'Content-Type': 'application/xml',
        'Authorization': `Basic ${btoa(`${settings.username}:${settings.password}`)}`,
      },
      body: '<?xml version="1.0"?><propfind xmlns="DAV:"><prop><displayname/></prop></propfind>',
    }).catch(() => null);
    if (!probe) {
      throw caldavError('caldav.error.connection');
    }
    if (probe.status === 401 || probe.status === 403) {
      throw caldavError('caldav.error.credentials', probe);
    }
    const clientCaldav = await tsdav.createDAVClient({
      serverUrl: settings.caldavUrl.replace('{username}', settings.username),
      credentials: {
        username: settings.username,
        password: settings.password,
      },
      authMethod: 'Basic',
      defaultAccountType: 'caldav',
    });
    const calendars = await clientCaldav.fetchCalendars({headersToExclude: ['If-None-Match']});
    return calendars.map((calendar, index) => ({
      id: calendar.url,
      name: calendar.displayName || calendar.url,
      color: calendarColor(calendar, index, calendars.length),
      readOnly: isReadOnly(calendar),
    }));
  },
  async getCalendar(clientCaldav){
    const calendars = await clientCaldav.fetchCalendars({headersToExclude: ['If-None-Match']});
    if (calendars.length === 0) {
      console.error('No calendar found');
      return null;
    } else {
      return calendars[0];
    }
  },
  async retrieveEvents(settings, periodStartDate, periodEndDate) {
    const start = caldavConnectorService.toRFC3339(periodStartDate, false, true);
    const end = caldavConnectorService.toRFC3339(periodEndDate, false, true);
    const clientCaldav = await tsdav.createDAVClient({
      serverUrl: settings.caldavUrl.replace('{username}',settings.username),
      credentials: {
        username: settings.username,
        password: settings.password,
      },
      authMethod: 'Basic',
      defaultAccountType: 'caldav',
    }).catch(() => {
      console.error('cant connect to caldav client check username and password');
    });
    // Every calendar of the account, not just the first the server happens to
    // enumerate. Each event is tagged with the collection it came from, which
    // is what lets the agenda show one calendar and hide another, and what
    // gives an event the colour of its own calendar rather than one shared
    // colour for everything remote.
    const calendars = await clientCaldav.fetchCalendars({headersToExclude: ['If-None-Match']});
    // Read every calendar at once rather than one after another: a user with
    // several collections would otherwise wait for the sum of the round trips
    // each time the displayed period changes.
    const objectsPerCalendar = await Promise.all(calendars.map(calendar => clientCaldav.fetchCalendarObjects({
      calendar,
      expand: true,
      timeRange: {
        start: start,
        end: end,
      },
      headersToExclude: ['If-None-Match']
    })));
    const listEvent = [];
    calendars.forEach((calendar, calendarIndex) => {
      const calendarId = calendar.url;
      const color = calendarColor(calendar, calendarIndex, calendars.length);
      const events = objectsPerCalendar[calendarIndex];
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
              calEvent.color = color;
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
              calEvent.calendarId = calendarId;
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
                occurenceEvent.color = color;
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
                occurenceEvent.calendarId = calendarId;
                listEvent.push(occurenceEvent);
              }
              next=expand.next();
            }
          } else {

            caldavEvent.summary = vEvent.summary;
            caldavEvent.uid = vEvent.uid;
            caldavEvent.id = vEvent.uid;
            caldavEvent.color = color;
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
            caldavEvent.calendarId = calendarId;
            listEvent.push(caldavEvent);
          }
        }
      });
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
    const clientCaldav = await tsdav.createDAVClient({
      serverUrl: settings.caldavUrl.replace('{username}',settings.username),
      credentials: {
        username: settings.username,
        password: settings.password,
      },
      authMethod: 'Basic',
      defaultAccountType: 'caldav',
    }).catch(() => {
      console.error('cant connect to caldav client check username and password');
    });
    //get calendar
    const calendar = await this.getCalendar(clientCaldav);
    if (!calendar) {
      return Promise.all(null);
    } else {
      return clientCaldav.deleteCalendarObject({
        calendarObject: {
          url: event.url,
          etag: event.etag,
        },
        headersToExclude: ['If-None-Match']
      });
    }
  },
  async saveEvent(event, settings) {
    const clientCaldav = await tsdav.createDAVClient({
      serverUrl: settings.caldavUrl.replace('{username}',settings.username),
      credentials: {
        username: settings.username,
        password: settings.password,
      },
      authMethod: 'Basic',
      defaultAccountType: 'caldav',
    }).catch((e) => {
      console.error('cant connect to caldav client check username and password', e);
      return null;
    });
    //get calendar
    const calendar = clientCaldav ? await this.getCalendar(clientCaldav) : null;
    if (!calendar) {
      return Promise.all(null);
    }
    const isOccurrence = !!event.occurrence;
    const icalUID = isOccurrence ? event.parent.remoteId : event.remoteId || crypto.randomUUID();
    const filename = `${icalUID}.ics`;

    let start = toUTCString(event.start);
    let end = toUTCString(event.end);
    const dtStamp = new Date().toISOString().replace(/[-:]|\.\d{3}/g, '').replace('Z', 'Z');

    let iCalString = `BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Exo Platform//NONSGML v1.0//EN
BEGIN:VEVENT
SUMMARY:${event.summary}
UID:${icalUID}
DTSTAMP:${dtStamp}
`;
    if (event.allDay) {
      start = start.substring(0, 8);
      end = end.substring(0, 8);
      iCalString += `DTSTART;VALUE=DATE:${start}
DTEND;VALUE=DATE:${end}
`;
    } else {
      iCalString += `DTSTART:${start}Z
DTEND:${end}Z
`;
    }
    if (event.location) {
      iCalString += `LOCATION:${event.location}\n`;
    }
    let description = '';
    if (event.description) {
      description += `${event.description.replace(/\n/g, '\\n')}\\n`;
    }
    if (event.conferences?.length > 0) {
      description += event.conferences[0]?.url;
    }
    if (description !== '') {
      iCalString += `DESCRIPTION:${description}\n`;
    }
    if (isOccurrence) {
      const recurrenceId = event.occurrence.id.replace(/[-:]|\.\d{3}/g, '');
      const isTimePresent = event.occurrence.id.includes('T');
      if (isTimePresent) {
        iCalString += `RECURRENCE-ID:${recurrenceId}Z\n`; // Add Z for UTC if current time
        iCalString += `RECURRENCE-ID;VALUE=DATE:${recurrenceId}\n`;
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
      console.error('Error creating/updating CalDAV:', e, 'Contenu iCalString:', iCalString);
      throw e;
    }
  }
};

function toUTCString(dateStr) {
  const d = new Date(dateStr);
  return d.toISOString().replace(/[-:]|\.\d{3}/g, '').replace('Z', '');
}

/** Minimum contrast ratio a generated colour must reach against white text. */
const MIN_CONTRAST_RATIO = 4.5;

/**
 * A `#RRGGBB` colour from whatever the server returned, or null.
 *
 * Two shapes have to be handled. tsdav reduces every propstat block of a
 * response into one props object, including the 404 block listing the
 * properties the server does not hold — so a calendar with no colour yields an
 * empty object rather than undefined. Being truthy, it survives a `||`
 * fallback and reaches the UI as "[object Object]". And Apple writes eight
 * hex digits, the last two being alpha, which CSS before level 4 does not
 * understand.
 *
 * @param {*} value the property as tsdav returned it
 * @returns {String} a usable `#RRGGBB` colour, or null when there is none
 */
function normalizeColor(value) {
  if (typeof value !== 'string') {
    return null;
  }
  const match = value.trim().match(/^#?([0-9a-f]{6})(?:[0-9a-f]{2})?$/i);
  return match && `#${match[1].toUpperCase()}` || null;
}

/**
 * A stable non-cryptographic hash of a string.
 *
 * @param {String} value string to hash
 * @returns {Number} a non-negative integer derived from it
 */
function hashOf(value) {
  let hash = 0;
  for (let i = 0; i < value.length; i++) {
    hash = (hash << 5) - hash + value.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash);
}

/**
 * Converts an HSL triplet to `#RRGGBB`.
 *
 * @param {Number} hue hue in degrees
 * @param {Number} saturation saturation as a percentage
 * @param {Number} lightness lightness as a percentage
 * @returns {String} the colour as `#RRGGBB`
 */
function hslToHex(hue, saturation, lightness) {
  const l = lightness / 100;
  const a = saturation * Math.min(l, 1 - l) / 100;
  const channel = n => {
    const k = (n + hue / 30) % 12;
    const value = l - a * Math.max(-1, Math.min(k - 3, Math.min(9 - k, 1)));
    return Math.round(255 * value).toString(16).padStart(2, '0');
  };
  return `#${channel(0)}${channel(8)}${channel(4)}`.toUpperCase();
}

/**
 * Contrast ratio of a colour against white, per WCAG.
 *
 * @param {String} color colour as `#RRGGBB`
 * @returns {Number} the ratio, between 1 and 21
 */
function contrastWithWhite(color) {
  const channel = index => {
    const value = parseInt(color.substr(1 + index * 2, 2), 16) / 255;
    return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
  };
  const luminance = 0.2126 * channel(0) + 0.7152 * channel(1) + 0.0722 * channel(2);
  return 1.05 / (luminance + 0.05);
}

/**
 * Derives a colour from a collection URL, for the very common case of a server
 * holding none: MKCALENDAR assigns no colour, so an untouched account has none
 * anywhere. This is the normal path, not an error path.
 *
 * A single shared fallback would leave every calendar looking alike, which is
 * the problem being solved, and a random one would change on every read.
 * Hashing the URL gives a calendar the same colour on every device and in
 * every session, and two calendars different ones.
 *
 * The hue comes from the whole wheel rather than a fixed palette: a palette of
 * N entries runs into the birthday problem as soon as an account holds a
 * handful of calendars. Saturation and lightness take one of a few values from
 * unrelated bits of the same hash, so two calendars landing on neighbouring
 * hues still differ in tone.
 *
 * The result is then darkened until it is legible, because HSL lightness is
 * not perceptual — a cyan at 48% is nearly twice as bright as a blue at the
 * same value and would render as unreadable white-on-pale.
 *
 * @param {String} calendarUrl URL of the calendar collection
 * @returns {String} a stable, legible `#RRGGBB` colour for that collection
 */
function derivedCalendarColor(calendarUrl, position, total) {
  const hash = hashOf(calendarUrl || '');
  // Hashing the URL alone spreads colours over the whole wheel but guarantees
  // nothing about the distance between any two of them: on a real account,
  // two of three calendars landed nine degrees apart and read as the same
  // magenta. The hue is therefore laid out across the account's calendars by
  // position, from an offset the account itself decides, so the set is always
  // separated and still stable from one device and session to the next.
  //
  // The trade this makes: adding or removing a calendar re-spaces the others,
  // where pure hashing would have left them alone. A set that stays put keeps
  // its colours, and being able to tell two calendars apart matters more than
  // one never shifting.
  const count = Math.max(total || 1, 1);
  const index = Math.max(position || 0, 0);
  const offset = hashOf(calendarUrl.replace(/[^/]+\/?$/, '')) % 360;
  const hue = Math.round((offset + index * 360 / count) % 360);
  const saturation = 58 + (hash >>> 9) % 4 * 8;
  let lightness = 38 + (hash >>> 17) % 3 * 5;
  let color = hslToHex(hue, saturation, lightness);
  while (lightness > 20 && contrastWithWhite(color) < MIN_CONTRAST_RATIO) {
    lightness -= 2;
    color = hslToHex(hue, saturation, lightness);
  }
  return color;
}

/**
 * The colour to paint everything from a collection with: what the server holds
 * when it holds one, the derived colour otherwise. A colour the server holds
 * is never adjusted — it is the user's own choice, already mirrored in their
 * other clients.
 *
 * @param {Object} calendar calendar collection as returned by tsdav
 * @returns {String} the `#RRGGBB` colour of that calendar
 */
function calendarColor(calendar, position, total) {
  return normalizeColor(calendar && calendar.calendarColor)
      || derivedCalendarColor(calendar && calendar.url || '', position, total);
}

/**
 * Whether the connected user may only read a collection.
 *
 * Derived from the privileges the server reports. Servers are not obliged to
 * report them, and this has never been observed returning true: the read-only
 * calendar created on the test server for that purpose turned out fully
 * writable. Absent evidence the collection is restricted, it is reported
 * writable, so the answer is never a guess dressed as a fact.
 *
 * @param {Object} calendar calendar collection as returned by tsdav
 * @returns {Boolean} true only when the server says writing is not permitted
 */
function isReadOnly(calendar) {
  const privileges = calendar && calendar.privilegeSet;
  if (!Array.isArray(privileges) || privileges.length === 0) {
    return false;
  }
  return !privileges.some(privilege => ['write', 'write-content', 'all'].includes(privilege));
}
/**
 * An error carrying a stable code, so that agenda can turn a failure into a
 * message the user can act on — check your credentials, this calendar is
 * read-only, try again — without parsing text or reaching into tsdav's own
 * error shapes.
 *
 * @param {String} code stable identifier for the kind of failure
 * @param {Object} response response that produced it, when there was one
 * @returns {Error} the error to reject with
 */
function caldavError(code, response) {
  const error = new Error(code);
  error.code = code;
  error.status = response && response.status;
  return error;
}
