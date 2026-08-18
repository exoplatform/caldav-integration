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
    const calendars = await clientCaldav.fetchCalendars({
      props: calendarProps(),
      projectedProps: {[PRIVILEGE_SET_PROP]: true},
      headersToExclude: ['If-None-Match'],
    });
    await this.recoverMirrorCalendar(settings, calendars);
    const ordered = inStableOrder(calendars);
    return ordered.map((calendar, index) => ({
      id: calendar.url,
      name: calendarName(calendar),
      color: calendarColor(calendar, index, ordered.length),
      readOnly: isReadOnly(calendar),
    }));
  },
  /**
   * Takes the mirror calendar back over when the account holds one but
   * nothing records it.
   *
   * Disconnecting forgets the stored href along with the credentials, which
   * is right — a different account must not inherit the mirror of the last
   * one. But reconnecting the same account left its own mirror unclaimed: it
   * reappeared among the calendars as an ordinary one, showing every meeting
   * a second time, and the copies went to whichever calendar the account
   * listed first until the step was answered again.
   *
   * The calendar is recognised by its path, which eXo controls and which is
   * the same in every language, so this claims back a calendar eXo made for
   * this account and never one the user keeps for themselves.
   *
   * @param {Object} settings connector settings, holding mirrorCalendarHref
   * @param {Array} calendars the collections the server enumerates
   * @returns {Promise} resolves once a recovered href has been stored
   */
  async recoverMirrorCalendar(settings, calendars) {
    if (!settings || settings.mirrorCalendarHref || !calendars || !calendars.length) {
      return;
    }
    const existing = calendars.find(calendar => isMirrorCollection(calendar.url));
    if (existing) {
      await caldavConnectorService.saveMirrorCalendarHref(existing.url);
      settings.mirrorCalendarHref = existing.url;
    }
  },

  canCreateCalendar: true,
  /**
   * Creates, on the connected CalDAV server, the dedicated calendar that will
   * receive the meetings eXo pushes — MKCALENDAR with the display name,
   * colour and description set atomically — then stores its href as the push
   * destination.
   *
   * The name is written once, in the language of the user at that moment, and
   * never renamed afterwards: the href is the identity of the collection, so
   * the user remains free to rename it from any of their own clients.
   *
   * MKCALENDAR is not universally permitted. A refusal surfaces as an error
   * flagged calendarCreationRefused, so the caller can fall back to letting
   * the user pick an existing calendar at connect time rather than fail at
   * first push.
   *
   * @param {Object} calendarToCreate description of the wanted calendar
   * @param {String} calendarToCreate.name display name, from the platform branding
   * @param {String} calendarToCreate.color `#RRGGBB` colour, from the platform branding
   * @param {String} calendarToCreate.description explains the calendar in the user's own client
   * @returns {Promise<Object>} `{id}` where id is the href of the created collection
   */
  async createCalendar({name, color, description}) {
    const settings = await caldavConnectorService.getCaldavSetting();
    const clientCaldav = await createClient(settings);
    const calendars = await clientCaldav.fetchCalendars({headersToExclude: ['If-None-Match']});
    const serverUrl = settings.caldavUrl.replace('{username}', settings.username);
    const homeUrl = calendars.length && new URL('..', calendars[0].url).href
      || (serverUrl.endsWith('/') && serverUrl || `${serverUrl}/`);
    // The path is derived from the name alone, with nothing random in it, so
    // that asking twice for the same calendar means asking for the same
    // collection. A random suffix made every request a different one, and a
    // user who disconnected and reconnected — which forgets the stored href —
    // collected a new calendar on the server each time.
    const url = new URL(`${MIRROR_COLLECTION_SLUG}/`, homeUrl).href;
    const existing = findMirrorCalendar(calendars, settings.mirrorCalendarHref, url, name);
    if (existing) {
      // Re-storing matters: after a reconnect the setting is empty even though
      // the collection is still there, and without this the mirror would stay
      // unconfigured until the next push guessed a destination.
      await caldavConnectorService.saveMirrorCalendarHref(existing.url);
      return {id: existing.url};
    }
    const props = {
      [`${tsdav.DAVNamespaceShort.DAV}:displayname`]: name,
    };
    if (description) {
      props[`${tsdav.DAVNamespaceShort.CALDAV}:calendar-description`] = description;
    }
    if (color) {
      props[`${tsdav.DAVNamespaceShort.CALDAV_APPLE}:calendar-color`] = color;
    }
    const responses = await clientCaldav.makeCalendar({
      url,
      props,
      headersToExclude: ['If-None-Match'],
    });
    const refusal = (responses || []).find(response => response && response.ok === false);
    if (refusal) {
      // 405 on MKCALENDAR means a collection already sits at that URL. Since
      // the URL is derived from the name, that collection is the one being
      // asked for: adopt it rather than report a failure the user cannot act
      // on. Any other status is a genuine refusal.
      if (refusal.status === 405) {
        const created = await clientCaldav.fetchCalendars({headersToExclude: ['If-None-Match']});
        const adopted = findMirrorCalendar(created, null, url, name);
        if (adopted) {
          await caldavConnectorService.saveMirrorCalendarHref(adopted.url);
          return {id: adopted.url};
        }
      }
      const error = new Error(`MKCALENDAR refused by the server with status ${refusal.status}`);
      error.calendarCreationRefused = true;
      error.status = refusal.status;
      throw error;
    }
    await caldavConnectorService.saveMirrorCalendarHref(url);
    return {id: url};
  },
  /**
   * Points the mirror at an existing calendar of the connected account
   * instead of a created one — the fallback when MKCALENDAR is refused, and
   * an option the user may always prefer.
   *
   * @param {String} calendarId href of the chosen calendar collection
   * @returns {Promise<Object>} `{id}` echoing the stored href
   */
  async setMirrorCalendar(calendarId) {
    await caldavConnectorService.saveMirrorCalendarHref(calendarId);
    return {id: calendarId};
  },
  /**
   * The stored href of the mirror calendar, so UIs can single it out — for
   * instance to keep it off calendar lists, since it only holds copies of
   * events eXo already displays.
   *
   * @returns {Promise<String>} the href, or null when no mirror is configured
   */
  getMirrorCalendarId() {
    return caldavConnectorService.getCaldavSetting().then(settings => settings.mirrorCalendarHref || null);
  },
  /**
   * The calendar every push and remote deletion must target: the stored
   * mirror calendar. Matching is done on decoded pathnames, so an encoding
   * difference (%40 versus @) or a changed host never detaches the mirror.
   * When the server no longer enumerates the collection, the href itself is
   * still targeted rather than silently writing somewhere else.
   *
   * Accounts connected before the mirror existed have no stored href and keep
   * the previous behaviour, the first calendar the server enumerates.
   *
   * @param {Object} clientCaldav authenticated tsdav client
   * @param {Object} settings connector settings, holding mirrorCalendarHref
   * @returns {Promise<Object>} the destination calendar, or null when none exists
   */
  async getDestinationCalendar(clientCaldav, settings) {
    const mirrorCalendarHref = settings && settings.mirrorCalendarHref;
    if (!mirrorCalendarHref) {
      return this.getCalendar(clientCaldav);
    }
    const calendars = await clientCaldav.fetchCalendars({headersToExclude: ['If-None-Match']});
    return calendars.find(calendar => isSameCollection(calendar.url, mirrorCalendarHref))
      || {url: mirrorCalendarHref};
  },
  async getCalendar(clientCaldav){
    const calendars = await clientCaldav.fetchCalendars({
      props: calendarProps(),
      projectedProps: {[PRIVILEGE_SET_PROP]: true},
      headersToExclude: ['If-None-Match'],
    });
    if (calendars.length === 0) {
      throw caldavError('caldav.error.noCalendar');
    }
    return calendars[0];
  },
  async retrieveEvents(settings, periodStartDate, periodEndDate) {
    const start = caldavConnectorService.toRFC3339(periodStartDate, false, true);
    const end = caldavConnectorService.toRFC3339(periodEndDate, false, true);
    const clientCaldav = await createClient(settings);
    // Every calendar of the account, not just the first the server happens to
    // enumerate. Each event is tagged with the collection it came from, which
    // is what lets the agenda show one calendar and hide another, and what
    // gives an event the colour of its own calendar rather than one shared
    // colour for everything remote.
    const calendars = await clientCaldav.fetchCalendars({
      props: calendarProps(),
      projectedProps: {[PRIVILEGE_SET_PROP]: true},
      headersToExclude: ['If-None-Match'],
    });
    // Read every calendar at once rather than one after another: a user with
    // several collections would otherwise wait for the sum of the round trips
    // each time the displayed period changes.
    //
    // Settled rather than all: with one collection a failure meant no events
    // either way, but an account now has several, and letting one unreachable
    // or misbehaving calendar reject the whole batch would empty the agenda of
    // every other calendar too. A calendar that fails is logged and contributes
    // nothing; the rest still render.
    const readPerCalendar = await Promise.allSettled(calendars.map(calendar => clientCaldav.fetchCalendarObjects({
      calendar,
      expand: true,
      timeRange: {
        start: start,
        end: end,
      },
      headersToExclude: ['If-None-Match']
    })));
    const objectsPerCalendar = readPerCalendar.map((outcome, index) => {
      if (outcome.status === 'fulfilled') {
        return outcome.value;
      }
      console.error('cannot read the calendar', calendars[index] && calendars[index].url, outcome.reason);
      return [];
    });
    const listEvent = [];
    const ordered = inStableOrder(calendars);
    calendars.forEach((calendar, calendarIndex) => {
      const calendarId = calendar.url;
      // Position is taken from the stable order, not from this loop's index:
      // the loop walks the server's order because that is what pairs a calendar
      // with its own fetched objects, while the colour must not depend on it.
      const color = calendarColor(calendar, ordered.findIndex(one => one.url === calendar.url), ordered.length);
      const events = objectsPerCalendar[calendarIndex];
      events.map(event => {
        const caldavEvent= {};
        const data = ICAL.parse(event.data);
        const iCal = new ICAL.Component(data); //component
        registerTimeZones(iCal);
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
                // toJSDate resolves the occurrence through the zone its series is
                // anchored in; new Date(icalTime) went through toString(), which
                // drops the zone unless it is UTC, so a series anchored on a TZID
                // — as this connector now writes recurring events, and as most
                // other clients write them — was read as if its wall clock were
                // the reader's own, showing the meeting at the right hour only to
                // users who happen to sit in the organiser's zone.
                occurenceEvent.start= realStartDate.toJSDate(); //next : ICAL.Time
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
    const calendar = await this.getDestinationCalendar(clientCaldav, settings);
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
  /**
   * Writes an accepted eXo meeting to the mirror calendar of the connected
   * account, as one ICS object named after its UID.
   *
   * @param {Object} event the eXo event to push
   * @param {Object} settings connector settings holding credentials and mirror href
   * @returns {Promise<Object>} `{id}` where id is the ICS UID of the pushed copy
   */
  async saveEvent(event, settings) {
    const clientCaldav = await createClient(settings);
    //get the mirror calendar, the only collection eXo writes to
    const calendar = await this.getDestinationCalendar(clientCaldav, settings);
    if (!calendar) {
      return null;
    }
    const isOccurrence = !!event.occurrence;
    const icalUID = isOccurrence ? event.parent.remoteId : event.remoteId || crypto.randomUUID();
    const filename = `${icalUID}.ics`;

    // A timed series must be anchored on the wall clock of a zone, not on an
    // instant. RFC 5545 section 3.8.5.3 expands every occurrence of an RRULE
    // from DTSTART, so a 09:00 Paris weekly meeting written as 08:00Z stays at
    // 08:00Z all year and shows up at 10:00 local for the half of it that falls
    // in summer time — on every client, because that is what the data says.
    // Anchoring on TZID makes each occurrence 09:00 in that zone, whichever
    // offset the zone is on that day.
    //
    // The parameter is worth nothing on its own: section 3.2.19 requires the
    // TZID to name a VTIMEZONE carried by the same object, and without it a
    // strict server rejects the PUT as invalid calendar data while a lenient
    // parser reads the time as floating — a different wrong answer. So the two
    // travel together, and when the component cannot be produced the event
    // falls back to the UTC form rather than shipping a dangling reference.
    //
    // Only recurring events are moved: a single timed event is one instant,
    // which UTC already states unambiguously. Occurrence overrides count as
    // recurring — they belong to a series that is now TZID-anchored, and an
    // override has to speak the same language as the master it amends.
    const timeZoneId = event.timeZoneId || caldavConnectorService.USER_TIMEZONE_ID;
    const isRecurring = isOccurrence || !!event.recurrence?.rrule;
    const vTimeZone = !event.allDay && isRecurring ? await resolveVTimeZone(timeZoneId, referenceYear(event.start)) : null;

    let iCalString = buildEventIcs(event, icalUID, isOccurrence, vTimeZone, timeZoneId);
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
 * Assembled from parts so that each stays readable on its own: the property set
 * an event needs has grown well past what one function can hold without hiding
 * its own shape. The zone is resolved by the caller, which is where the await
 * belongs, so that this stays a pure function of the event.
 *
 * @param  {object} event - the event being pushed
 * @param  {string} icalUID - the UID shared by the series and its overrides
 * @param  {boolean} isOccurrence - whether this component amends one instance
 * @param  {string} vTimeZone - the VTIMEZONE component, when one was produced
 * @param  {string} timeZoneId - the zone the wall clock is read in
 * @returns {string} the VCALENDAR object, trimmed
 */
function buildEventIcs(event, icalUID, isOccurrence, vTimeZone, timeZoneId) {
  const dtStamp = new Date().toISOString().replace(/[-:]|\.\d{3}/g, '').replace('Z', 'Z');
  let ics = `BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Exo Platform//NONSGML v1.0//EN
CALSCALE:GREGORIAN
${vTimeZone || ''}BEGIN:VEVENT
SUMMARY:${escapeText(event.summary)}
UID:${icalUID}
DTSTAMP:${dtStamp}
`;
  ics += scheduleLines(event, vTimeZone, timeZoneId);
  ics += describeLines(event);
  ics += stampLines(event);
  // CONFIRMED for every event pushed, deliberately, rather than a mapping of
  // the agenda status: eXo spells a date poll TENTATIVE, which in RFC 5545
  // means "provisionally scheduled" — a poll pushed with its own word would
  // show up as a real meeting nobody has confirmed. An event only reaches this
  // connector once it is scheduled, so the honest value is the constant.
  // TRANSP is the RFC default and is written for explicitness, so that a client
  // reading the object does not have to know the default.
  ics += `STATUS:CONFIRMED
TRANSP:OPAQUE
`;
  ics += recurrenceLines(event, isOccurrence, vTimeZone, timeZoneId);
  ics += exceptionLines(event, isOccurrence, vTimeZone, timeZoneId);
  ics += buildAlarms(event);
  ics += `END:VEVENT
END:VCALENDAR
`;
  return ics.trim();
}

/**
 * The DTSTART/DTEND pair: dates for an all-day event, wall clock anchored on
 * the zone when the object carries its VTIMEZONE, and UTC otherwise.
 *
 * @param  {object} event - the event being pushed
 * @param  {string} vTimeZone - the VTIMEZONE component, when one was produced
 * @param  {string} timeZoneId - the zone the wall clock is read in
 * @returns {string} the two lines, terminated
 */
function scheduleLines(event, vTimeZone, timeZoneId) {
  let ics = '';
  if (event.allDay) {
    ics += `DTSTART;VALUE=DATE:${toIcsDate(event.start, event.timeZoneId)}
DTEND;VALUE=DATE:${toIcsEndDate(event.end, event.timeZoneId)}
`;
  } else if (vTimeZone) {
    ics += `DTSTART;TZID=${timeZoneId}:${toIcsLocalDateTime(event.start, timeZoneId)}
DTEND;TZID=${timeZoneId}:${toIcsLocalDateTime(event.end, timeZoneId)}
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
 * the master repeats by. An override has to denote its instance in the same
 * form the master's DTSTART uses, hence the same three cases.
 *
 * @param  {object} event - the event being pushed
 * @param  {boolean} isOccurrence - whether this component amends one instance
 * @param  {string} vTimeZone - the VTIMEZONE component, when one was produced
 * @param  {string} timeZoneId - the zone the wall clock is read in
 * @returns {string} the property, or an empty string
 */
function recurrenceLines(event, isOccurrence, vTimeZone, timeZoneId) {
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
    //
    // The timed form follows DTSTART for the same reason it follows allDay:
    // the property has to denote the instance as the master's RRULE produces
    // it, and a TZID-anchored master produces wall-clock times. Naming the
    // same instant in UTC would be equivalent only to a client that compares
    // instants, and enough of them compare the written value instead — the
    // override then binds to nothing and surfaces as a second event sitting
    // next to the occurrence it was meant to replace.
    if (event.allDay) {
      ics += `RECURRENCE-ID;VALUE=DATE:${toIcsDate(event.occurrence.id, event.timeZoneId)}\n`;
    } else if (vTimeZone) {
      ics += `RECURRENCE-ID;TZID=${timeZoneId}:${toIcsLocalDateTime(event.occurrence.id, timeZoneId)}\n`;
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
 * The instances deleted from a series, written by the master only, in the same
 * form its DTSTART uses.
 *
 * @param  {object} event - the event being pushed
 * @param  {boolean} isOccurrence - whether this component amends one instance
 * @param  {string} vTimeZone - the VTIMEZONE component, when one was produced
 * @param  {string} timeZoneId - the zone the wall clock is read in
 * @returns {string} one EXDATE per exception
 */
function exceptionLines(event, isOccurrence, vTimeZone, timeZoneId) {
  let ics = '';
  if (!isOccurrence && event.recurrence?.exceptions && event.recurrence.exceptions.length > 0) {
    // Same reasoning as RECURRENCE-ID above: an exclusion only removes an
    // occurrence it matches, so it is written in the form the master
    // generates.
    event.recurrence.exceptions.forEach(exdate => {
      const exDateFormatted = exdate.date.replace(/[-:]|\.\d{3}/g, '');
      if (!exDateFormatted.includes('T')) {
        ics += `EXDATE;VALUE=DATE:${exDateFormatted}\n`;
      } else if (vTimeZone) {
        ics += `EXDATE;TZID=${timeZoneId}:${toIcsLocalDateTime(exdate.date, timeZoneId)}\n`;
      } else {
        ics += `EXDATE:${exDateFormatted}Z\n`;
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
 * The VTIMEZONE the push carries is copied over as well when the object does
 * not already define that zone. Only the VEVENT used to be lifted out of the
 * pushed object, so every update after the first would have left the DTSTART
 * of the series pointing at a TZID nothing in the object defined — the very
 * dangling reference the push takes care to avoid.
 *
 * @param {String} existingData the calendar object as fetched from the server
 * @param {String} newComponentString VCALENDAR holding the one VEVENT to push
 * @param {Boolean} isOccurrence whether that VEVENT is an occurrence override
 * @returns {String} the merged calendar object, ready to PUT
 */
function mergeIntoCalendarObject(existingData, newComponentString, isOccurrence) {
  const calendar = new ICAL.Component(ICAL.parse(existingData));
  const newCalendar = new ICAL.Component(ICAL.parse(newComponentString));
  registerTimeZones(calendar);
  registerTimeZones(newCalendar);
  const newEvent = newCalendar.getFirstSubcomponent('vevent');
  newCalendar.getAllSubcomponents('vtimezone')
    .filter(vtimezone => !calendar.getAllSubcomponents('vtimezone')
      .some(known => known.getFirstPropertyValue('tzid') === vtimezone.getFirstPropertyValue('tzid')))
    .forEach(vtimezone => calendar.addSubcomponent(vtimezone));
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
  registerTimeZones(calendar);
  const master = calendar.getAllSubcomponents('vevent')
    .find(vevent => !vevent.getFirstPropertyValue('recurrence-id'));
  const dtstart = master && master.getFirstPropertyValue('dtstart');
  const occurrence = occurrenceToTime(event, dtstart ? dtstart.isDate : event.allDay, dtstart && dtstart.zone);
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
 * series, otherwise the instant expressed in the zone the series is anchored
 * in — mirroring how saveEvent writes RECURRENCE-ID for the same identifier.
 *
 * Expressing it in the master's own zone rather than always in UTC matters
 * once the series is TZID-anchored: the EXDATE this value becomes has to match
 * an occurrence the master generates, and clients that compare the written
 * value instead of the instant would otherwise keep showing the cancelled
 * occurrence.
 *
 * @param {Object} event occurrence event, carrying occurrence.id
 * @param {Boolean} asDate whether the series is date-valued
 * @param {Object} zone the master's ICAL.Timezone, when it has one
 * @returns {Object} the occurrence as an ICAL.Time
 */
function occurrenceToTime(event, asDate, zone) {
  if (asDate) {
    const {year, month, day} = datePartsInZone(event.occurrence.id, event.timeZoneId);
    return new ICAL.Time({year, month, day, isDate: true});
  }
  const instant = ICAL.Time.fromJSDate(new Date(event.occurrence.id), true);
  return zone && zone !== ICAL.Timezone.localTimezone ? instant.convertToZone(zone) : instant;
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
 * @param {Number} position index of this calendar among the account's own
 * @param {Number} total how many calendars the account holds
 * @returns {String} a stable, legible `#RRGGBB` colour for that collection
 */
function derivedCalendarColor(calendarUrl, position, total) {
  const url = calendarUrl || '';
  const hash = hashOf(url);
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
  const offset = hashOf(url.replace(/[^/]+\/?$/, '')) % 360;
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
 * @param {Number} position index of this calendar among the account's own
 * @param {Number} total how many calendars the account holds
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
/**
 * The properties every calendar listing asks the server for.
 * <p>
 * tsdav's own default list is repeated here because passing `props` replaces it
 * rather than extending it, and dropping it would cost the display name and the
 * colour. What this adds is `current-user-privilege-set`: the property that says
 * whether the account may write to the collection. Without asking for it,
 * `isReadOnly()` reads a field the server was never asked to send and every
 * calendar looks writable — the refusal then surfaces at push time, on the first
 * event the user tries to save into somebody else's calendar.
 *
 * @returns {object} the PROPFIND property set
 */
/**
 * The calendars in an order the server cannot change under us.
 * <p>
 * The derived hue depends on a calendar's position among the others, and
 * WebDAV promises nothing about the order of the `response` elements in a
 * multistatus answer. Read twice — once for the legend, once for the events —
 * the same account could come back in two orders and the same calendar would
 * change colour between the two, which is the problem this is meant to solve.
 * The URL is the one identifier the protocol does guarantee, so it is what the
 * order is built on.
 *
 * @param  {Array} calendars - the calendars as the server returned them
 * @returns {Array} the same calendars, in a stable order
 */
function inStableOrder(calendars) {
  return [...(calendars || [])].sort((one, other) => (one.url || '').localeCompare(other.url || ''));
}

/**
 * The name to show for a calendar.
 * <p>
 * `displayName` is typed as a string or an object because a property the server
 * does not hold comes back as an empty object rather than as undefined — the
 * same quirk `normalizeColor` guards against. An empty object is truthy, so the
 * obvious `||` would render the calendar as "[object Object]".
 *
 * @param  {object} calendar - the calendar as tsdav returned it
 * @returns {string} its display name, or its URL when the server holds none
 */
function calendarName(calendar) {
  return (typeof calendar.displayName === 'string' && calendar.displayName) || calendar.url;
}

function calendarProps() {
  return {
    [`${tsdav.DAVNamespaceShort.CALDAV}:calendar-description`]: {},
    [`${tsdav.DAVNamespaceShort.CALDAV}:calendar-timezone`]: {},
    [`${tsdav.DAVNamespaceShort.DAV}:displayname`]: {},
    [`${tsdav.DAVNamespaceShort.CALDAV_APPLE}:calendar-color`]: {},
    [`${tsdav.DAVNamespaceShort.CALENDAR_SERVER}:getctag`]: {},
    [`${tsdav.DAVNamespaceShort.DAV}:resourcetype`]: {},
    [`${tsdav.DAVNamespaceShort.CALDAV}:supported-calendar-component-set`]: {},
    [`${tsdav.DAVNamespaceShort.DAV}:sync-token`]: {},
    [`${tsdav.DAVNamespaceShort.DAV}:current-user-privilege-set`]: {},
  };
}

/** Where tsdav hands back a property it was asked to project. */
const PRIVILEGE_SET_PROP = '{DAV:}current-user-privilege-set';

function isReadOnly(calendar) {
  const projected = calendar && calendar.projectedProps && calendar.projectedProps[PRIVILEGE_SET_PROP];
  const privileges = collectPrivileges(projected);
  // A server that answers without the property tells us nothing about what we
  // may do, and a calendar is treated as writable in that case: refusing a
  // write nobody said was forbidden would take the feature away from every
  // server that does not implement the property.
  if (privileges.length === 0) {
    return false;
  }
  return !privileges.some(privilege => ['write', 'write-content', 'all'].includes(privilege));
}

/**
 * The privilege names in a `current-user-privilege-set` answer.
 * <p>
 * The value arrives as parsed XML rather than as a list: one `privilege`
 * element per granted right, each holding one empty element named after the
 * right itself, and a single privilege comes back as an object where several
 * come back as an array.
 *
 * @param  {object} projected - the property as the server answered it
 * @returns {Array} the granted privilege names, lower-cased
 */
function collectPrivileges(projected) {
  const privilege = projected && projected.privilege;
  if (!privilege) {
    return [];
  }
  return (Array.isArray(privilege) ? privilege : [privilege])
    .flatMap(entry => Object.keys(entry || {}))
    .map(name => name.replace(/^.*:/, '').toLowerCase());
}
/**
 * Teaches ical.js the zones a calendar object defines.
 *
 * ical.js ships no time zone database — its own timezone_service calls itself
 * "all manual registry" — and resolves a TZID solely against what has been
 * registered. An unknown TZID is not an error there: the date-time silently
 * becomes floating, which reads as "whatever the wall clock says here", so an
 * event pushed at 09:00 Paris came back as 09:00 for a reader in Tunis. The
 * VTIMEZONE the object carries is exactly the missing definition, so it is
 * registered before anything is read out of the object.
 *
 * @param {Object} calendar the VCALENDAR as an ICAL.Component
 * @returns {void} nothing; the zones become resolvable globally
 */
function registerTimeZones(calendar) {
  calendar.getAllSubcomponents('vtimezone').forEach(vtimezone => {
    const tzid = vtimezone.getFirstPropertyValue('tzid');
    if (tzid && !ICAL.TimezoneService.has(tzid)) {
      ICAL.TimezoneService.register(vtimezone);
    }
  });
}

const vTimeZoneCache = new Map();

/**
 * The VTIMEZONE component defining a zone, ready to be dropped into a
 * VCALENDAR, or null when this connector cannot vouch for one.
 *
 * This is the single seam through which the object gets its zone definition,
 * and it is asynchronous on purpose: the authoritative source is the server,
 * which already builds these components from the ical4j registry — a full
 * tzdata with the historical rules — in agenda's Utils.getICalTimeZone /
 * getVTimeZone. The day agenda exposes them (see the endpoint proposed
 * alongside this change), the body below becomes a fetch and nothing else in
 * the file moves.
 *
 * Until then the component is derived in the browser, because the two other
 * sources are worse. ical.js carries no tzdata at all — the npm package is
 * lib and build, no timezone data anywhere — so ical.timezones.js would have
 * to be vendored from the project's releases rather than installed: roughly a
 * megabyte of generated definitions added to a bundle that is currently a
 * fraction of that, pinned at the tzdb release it was cut from and going stale
 * from the day it lands, with nothing in the build to tell us it has. The
 * browser's own database, by contrast, is current, costs nothing to ship, and
 * is the same one the user's clock already runs on.
 *
 * The result is memoised: a zone's rules do not change during a session, and a
 * push should not re-derive them. The promise is returned rather than awaited
 * so that the caller already treats this as a round trip, which is what the
 * server-backed version will be.
 *
 * @param {String} timeZoneId IANA zone the event is anchored in
 * @param {Number} year first year the definition has to cover
 * @returns {Promise<String>} the VTIMEZONE component, or null when unavailable
 */
function resolveVTimeZone(timeZoneId, year) {
  const key = `${timeZoneId}@${year}`;
  if (!vTimeZoneCache.has(key)) {
    let component = null;
    try {
      component = buildVTimeZone(timeZoneId, year);
    } catch (e) {
      console.warn('cannot describe the time zone', timeZoneId, '; the event will be pushed in UTC', e);
    }
    vTimeZoneCache.set(key, component);
  }
  return Promise.resolve(vTimeZoneCache.get(key));
}

/**
 * Derives a VTIMEZONE from the browser's own time zone database, by observing
 * what the zone actually does over a year rather than by looking its rules up.
 *
 * The offsets come from Intl, which is backed by the platform's tzdata, so the
 * observed behaviour is authoritative; what has to be guessed is how to state
 * it as a rule that also covers the years the series runs into. A zone that
 * changes offset twice a year is described as two yearly rules — the shape
 * every calendar client and every tzurl "Outlook" definition uses — and a zone
 * that never changes as one fixed offset.
 *
 * A zone doing anything else in that year — a handful move their clocks on a
 * lunar calendar and change offset three or four times — is declined rather
 * than approximated, since a wrong VTIMEZONE is harder to notice than no
 * VTIMEZONE. Declining returns the event to the UTC form it has today: still
 * adrift across a transition, but no worse than before this change.
 *
 * Two limits are worth stating plainly, and both are why the server remains
 * the better source. The rules are read from one year and projected forward,
 * so a zone that changes its legislation mid-series — or a lunar-calendar zone
 * that happens to show two transitions in the year read, and so is not
 * declined — is described by the rule it followed then, not the one it follows
 * later. And nothing here is versioned: agenda's ical4j registry carries the
 * full historical record, this carries a snapshot of one year's behaviour.
 *
 * @param {String} timeZoneId IANA zone to describe
 * @param {Number} year year whose behaviour is read, and from which the
 * definition takes effect; every occurrence of the series is later than it
 * @returns {String} the VTIMEZONE component, or null when it cannot be stated
 */
function buildVTimeZone(timeZoneId, year) {
  const transitions = zoneTransitions(timeZoneId, year);
  let subcomponents;
  if (transitions.length === 0) {
    const offset = formatUtcOffset(zoneOffsetMinutes(Date.UTC(year, 0, 1), timeZoneId));
    subcomponents = `BEGIN:STANDARD
DTSTART:${year}0101T000000
TZOFFSETFROM:${offset}
TZOFFSETTO:${offset}
END:STANDARD
`;
  } else if (transitions.length === 2) {
    subcomponents = transitions.map(zoneSubcomponent).join('');
  } else {
    return null;
  }
  return `BEGIN:VTIMEZONE
TZID:${timeZoneId}
${subcomponents}END:VTIMEZONE
`;
}

/**
 * States one observed offset change as a STANDARD or DAYLIGHT subcomponent
 * recurring every year.
 *
 * Two details are easy to get wrong and both are load-bearing. The DTSTART of
 * such a subcomponent is a local time read with the offset in force *before*
 * the change, per RFC 5545 section 3.6.5 — the instant the change happens has
 * no wall-clock reading in the new offset, since that is the hour the zone
 * skips or repeats. And the day of the change is stated as an ordinal weekday,
 * "the last Sunday of March" rather than "29 March", because that is the rule
 * the zone follows; a fixed day would drift by up to six days each year.
 *
 * @param {Object} transition an observed change, its instant and both offsets
 * @returns {String} the subcomponent, newline-terminated
 */
function zoneSubcomponent(transition) {
  const name = transition.to > transition.from ? 'DAYLIGHT' : 'STANDARD';
  const onset = new Date(transition.instant + transition.from * 60000);
  const month = onset.getUTCMonth() + 1;
  const day = onset.getUTCDate();
  const lastDayOfMonth = new Date(Date.UTC(onset.getUTCFullYear(), month, 0)).getUTCDate();
  const ordinal = day + 7 > lastDayOfMonth ? -1 : Math.floor((day - 1) / 7) + 1;
  const weekday = ['SU', 'MO', 'TU', 'WE', 'TH', 'FR', 'SA'][onset.getUTCDay()];
  return `BEGIN:${name}
DTSTART:${onset.getUTCFullYear()}${pad(month)}${pad(day)}T${pad(onset.getUTCHours())}${pad(onset.getUTCMinutes())}${pad(onset.getUTCSeconds())}
TZOFFSETFROM:${formatUtcOffset(transition.from)}
TZOFFSETTO:${formatUtcOffset(transition.to)}
RRULE:FREQ=YEARLY;BYMONTH=${month};BYDAY=${ordinal}${weekday}
END:${name}
`;
}

/**
 * Every offset change a zone goes through during a year.
 *
 * The year is swept a month at a time — a transition never happens twice
 * inside one month — and each month whose ends disagree is then bisected, so
 * the whole scan costs a few dozen formatter calls and lands on the exact
 * second of the change.
 *
 * @param {String} timeZoneId IANA zone to observe
 * @param {Number} year year to sweep
 * @returns {Array} the changes found, in chronological order
 */
function zoneTransitions(timeZoneId, year) {
  const transitions = [];
  let previousMonth = Date.UTC(year, 0, 1);
  let previousOffset = zoneOffsetMinutes(previousMonth, timeZoneId);
  for (let month = 1; month <= 12; month++) {
    const currentMonth = Date.UTC(year, month, 1);
    const currentOffset = zoneOffsetMinutes(currentMonth, timeZoneId);
    if (currentOffset !== previousOffset) {
      transitions.push(findTransition(previousMonth, currentMonth, previousOffset, timeZoneId));
    }
    previousMonth = currentMonth;
    previousOffset = currentOffset;
  }
  return transitions;
}

/**
 * Narrows an interval known to contain one offset change down to the second it
 * happens, by bisection.
 *
 * @param {Number} start instant still on the old offset
 * @param {Number} end instant already on the new one
 * @param {Number} offsetBefore the old offset, in minutes
 * @param {String} timeZoneId IANA zone being observed
 * @returns {Object} the change: its instant, and the offsets either side
 */
function findTransition(start, end, offsetBefore, timeZoneId) {
  let low = start;
  let high = end;
  while (high - low > 1000) {
    const middle = low + Math.floor((high - low) / 2);
    if (zoneOffsetMinutes(middle, timeZoneId) === offsetBefore) {
      low = middle;
    } else {
      high = middle;
    }
  }
  return {
    instant: high,
    from: offsetBefore,
    to: zoneOffsetMinutes(high, timeZoneId),
  };
}

/**
 * How far a zone is from UTC at a given instant, in minutes.
 *
 * There is no API that answers this directly, but reading the instant's wall
 * clock in the zone and subtracting the instant itself does: the difference
 * between what the zone's clock shows and what UTC shows is the offset.
 *
 * @param {Number} instant the moment to measure, in milliseconds
 * @param {String} timeZoneId IANA zone to measure it in
 * @returns {Number} minutes to add to UTC to obtain that zone's wall clock
 */
function zoneOffsetMinutes(instant, timeZoneId) {
  const {year, month, day, hour, minute, second} = wallClockInZone(instant, timeZoneId);
  return Math.round((Date.UTC(year, month - 1, day, hour, minute, second) - instant) / 60000);
}

/**
 * Formats an offset the way iCalendar states one, as +HHMM or -HHMM.
 *
 * @param {Number} minutes offset from UTC, negative west of Greenwich
 * @returns {String} the offset as a UTC-OFFSET value
 */
function formatUtcOffset(minutes) {
  const absolute = Math.abs(minutes);
  return `${minutes < 0 ? '-' : '+'}${pad(Math.floor(absolute / 60))}${pad(absolute % 60)}`;
}

/**
 * The wall clock a zone shows at a given moment, as numbers.
 *
 * The companion of datePartsInZone for values that carry a time, and it exists
 * for the same reason: the parts have to come from a formatter told which zone
 * to use, never from slicing the instant's own string representation.
 *
 * The hour is reduced modulo 24 because engines disagree on midnight under
 * hour12: false — some report it as hour 24 of the previous day, which would
 * put the event a day early and 24 hours late at once.
 *
 * @param {String|Number} value instant as agenda supplies it, or milliseconds
 * @param {String} timeZoneId the zone to read it in; the runtime's if absent
 * @returns {Object} the year, month, day, hour, minute and second shown there
 */
function wallClockInZone(value, timeZoneId) {
  const options = {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  };
  if (timeZoneId) {
    options.timeZone = timeZoneId;
  }
  const parts = new Intl.DateTimeFormat('en-US', options).formatToParts(new Date(value));
  const read = type => Number(parts.find(part => part.type === type).value);
  return {
    year: read('year'),
    month: read('month'),
    day: read('day'),
    hour: read('hour') % 24,
    minute: read('minute'),
    second: read('second'),
  };
}

/**
 * Formats a value as an iCalendar local date-time, the form a TZID-qualified
 * property carries: the wall clock of the zone, with neither offset nor Z.
 *
 * @param {String} value instant as agenda supplies it
 * @param {String} timeZoneId the zone whose wall clock is written
 * @returns {String} the value as YYYYMMDDTHHMMSS
 */
function toIcsLocalDateTime(value, timeZoneId) {
  const {year, month, day, hour, minute, second} = wallClockInZone(value, timeZoneId);
  return `${year}${pad(month)}${pad(day)}T${pad(hour)}${pad(minute)}${pad(second)}`;
}

/**
 * The year a zone definition has to take effect from to cover a series.
 *
 * The year before the series starts, so that both of that year's transitions
 * precede every occurrence: RFC 5545 leaves the offset ambiguous for a time
 * earlier than a VTIMEZONE's first onset, and clients resolve that ambiguity
 * differently.
 *
 * @param {String} start the first occurrence, as agenda supplies it
 * @returns {Number} the year to describe the zone from
 */
function referenceYear(start) {
  return new Date(start).getUTCFullYear() - 1;
}


/**
 * The decoded path of a collection URL, without a trailing slash — the part
 * of an href that identifies the collection regardless of host or of how the
 * server percent-encodes it.
 *
 * @param {String} url collection URL or href
 * @returns {String} its decoded, slash-trimmed path
 */
function collectionPath(url) {
  try {
    return decodeURIComponent(new URL(url, window.location.origin).pathname).replace(/\/+$/, '');
  } catch (e) {
    return url;
  }
}

/**
 * Whether two hrefs designate the same calendar collection. Compared on
 * decoded paths: the same collection may be written `%40` by the server and
 * `@` by a client, or reached through different hosts.
 *
 * @param {String} url first collection URL
 * @param {String} href second collection URL
 * @returns {Boolean} true when both point at the same collection
 */
function isSameCollection(url, href) {
  return !!url && !!href && collectionPath(url) === collectionPath(href);
}

/**
 * The mirror calendar among those the server enumerates, so that asking for
 * it twice never produces a second one.
 *
 * Three signals, in decreasing order of confidence:
 * the stored href, which is the identity of the collection and the only one
 * that survives a rename; the URL the connector would create, which survives
 * a disconnect (the setting does not) since it is derived from the name; and
 * the display name itself, which is what recovers a collection created when
 * storing its href failed.
 *
 * Name matching is a last resort on purpose: it is the only signal that can
 * adopt a calendar the connector did not create, and it stops matching as
 * soon as the user renames it — which is the right outcome, since the stored
 * href takes over from the first successful configuration.
 *
 * @param {Array} calendars collections the server enumerates
 * @param {String} mirrorCalendarHref stored mirror href, if any
 * @param {String} url the URL this connector creates for that name
 * @param {String} name display name asked for
 * @returns {Object} the matching collection, or undefined when there is none
 */
function findMirrorCalendar(calendars, mirrorCalendarHref, url, name) {
  return mirrorCalendarHref && (calendars || []).find(calendar => isSameCollection(calendar.url, mirrorCalendarHref))
    || (calendars || []).find(calendar => isSameCollection(calendar.url, url))
    || (calendars || []).find(calendar => calendar.displayName && calendar.displayName === name);
}

/**
 * The last path segment of the mirror collection. A constant, not a slug of
 * the display name: that name is branded and translated, so deriving the path
 * from it produced a different collection per language — a user switching
 * from English to French would ask for a path that does not exist and collect
 * a second calendar. The path is shown to nobody; the display name is.
 */
const MIRROR_COLLECTION_SLUG = 'exo-meetings';

/**
 * Whether a collection is one eXo created to receive the copies, judged on
 * its path alone so the answer does not depend on the language of whoever
 * created it. Collections made before the path became a constant carry a
 * random suffix, and are recognised too.
 *
 * @param {String} url collection URL
 * @returns {Boolean} true when the collection is an eXo mirror
 */
function isMirrorCollection(url) {
  const segment = collectionPath(url || '').split('/').pop();
  return segment === MIRROR_COLLECTION_SLUG || segment.startsWith(`${MIRROR_COLLECTION_SLUG}-`);
}
