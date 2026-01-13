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
    const calendars = await clientCaldav.fetchCalendars({headersToExclude: 'If-None-Match'});
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
    //get calendar
    const calendar = await this.getCalendar(clientCaldav);
    if (!calendar) {
      return Promise.all(null);
    }
    const events = await clientCaldav.fetchCalendarObjects({
      calendar,
      expand: true,
      timeRange: {
        start: start,
        end: end,
      },
      headersToExclude: 'If-None-Match'
    });
    const listEvent = [];
    events.map(event => {
      const caldavEvent= {};
      const data = ICAL.parse(event.data);
      const iCal = new ICAL.Component(data); //component
      const eventComponent = iCal.getFirstSubcomponent('vevent'); //component
      const vEvent = new ICAL.Event(eventComponent); //Event
      if (vEvent) {
        if (vEvent.isRecurring()) {
          const startRangeDate = ICAL.Time.fromJSDate(caldavConnectorService.toDate(periodStartDate),false);//ICAL.Time
          const endRangeDate = ICAL.Time.fromJSDate(caldavConnectorService.toDate(periodEndDate),false);//ICAL.Time

          const expand = new ICAL.RecurExpansion({
            component: eventComponent,
            dtstart: vEvent.startDate
          });
          let next=expand.next(); //ICAL.Time
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
                occurenceEvent.summary = vEvent.exceptions[next.toString()].summary;
                occurenceEvent.uid = vEvent.exceptions[next.toString()].uid;
                realStartDate = vEvent.exceptions[next.toString()].startDate;
              } else {
                occurenceEvent.summary = vEvent.summary;
                occurenceEvent.uid = vEvent.uid;
                realStartDate = next;
              }
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
          caldavEvent.color = '#FFFFFF';
          caldavEvent.type = 'remoteEvent';
          caldavEvent.etag= event.etag;
          caldavEvent.url = event.url;
          const startDate = eventComponent.getAllProperties('dtstart'); //ICAL.Property
          const endDate = eventComponent.getAllProperties('dtend'); //ICAL.Property
          caldavEvent.start= startDate && new Date(startDate[0].jCal[3]);
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
        headersToExclude: 'If-None-Match'
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
    const parentEventId = isOccurrence ? event.parent.id : event.id; // L'UID doit être celui du parent pour les exceptions
    const icalUID = parentEventId;
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
        calendar, iCalString, filename, headersToExclude: 'If-None-Match'
      });
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