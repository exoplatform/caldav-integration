import * as caldavConnectorService from '../js/agendaCaldavService.js';
import * as tsdav from 'tsdav';
import ICAL from 'ical.js';
const caldavConnector = {
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
  // A multi-instance connector: its rows are managed in the dedicated CalDAV
  // servers section of the agenda administration, not in the generic
  // connectors table (which keeps Google/Office365/Exchange only).
  multiInstance: true,
  // Identifies this connector — and every per-server copy, which inherits it
  // through createCaldavConnector's Object.assign — as a CalDAV one. Agenda
  // reads it to place calendars: a CalDAV account's collections are
  // materialised as the user's OWN personal calendars, so they belong under
  // "My Calendars" and the account under "Your calendars" in the settings,
  // while a connector that only fetches foreign calendars (Google, Office 365,
  // Exchange) gets its own named left-panel section and sits in the "Remote
  // calendars" list.
  //
  // Deliberately keyed on connector identity rather than on a capability:
  // canPush is dynamic on Google (it flips the moment the user grants the
  // write scope, which would make a section disappear mid-session), and a
  // remote connector keeps its own section even if it becomes writable one
  // day. Testing the name is not an option either — createCaldavConnector
  // overrides it with the declared server's providerName.
  isCaldav: true,
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
      // The drawer must know WHICH declared server this connector fronts:
      // the URL to probe the typed credentials against, and the registration
      // id to store beside them. A legacy (property-configured) connector
      // carries neither, and the drawer falls back to the resolved settings
      // URL exactly as before.
      document.dispatchEvent(new CustomEvent('open-caldav-connector-settings-drawer', {
        detail: {
          serverId: this.serverId || null,
          serverUrl: this.serverUrl || null,
          name: this.name,
        },
      }));
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
    // One request, answered with events already mapped. What this replaces is
    // one REPORT per calendar issued from the page, then every iCalendar
    // object parsed in the main thread — with the account's password in that
    // page to make the requests at all.
    const start = isoInstant(periodStartDate);
    const end = isoInstant(periodEndDate);
    if (!start || !end) {
      return Promise.resolve([]);
    }
    return fetch(`${window.location.origin}/caldav/rest/events?start=${start}&end=${end}`, {
      credentials: 'include',
    }).then(readOutcome).then(events => (events || []).map(event => ({...event, type: 'remoteEvent', id: event.uid})));
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
    return fetch(`${window.location.origin}/caldav/rest/calendars`, {credentials: 'include'})
      .then(readOutcome)
      .then(calendars => calendars || []);
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
    // wrong password. Through the relay the stored credentials are injected
    // server-side and their rejection arrives as a 403 carrying the
    // caldav.error.credentials relay code — same outcome, no secret in the
    // page.
    const probe = await fetch(relayServerUrl(settings), {
      method: 'PROPFIND',
      headers: davHeaders(settings, {
        'Depth': '0',
        'Content-Type': 'application/xml',
      }),
      credentials: 'include',
      body: '<?xml version="1.0"?><propfind xmlns="DAV:"><prop><displayname/></prop></propfind>',
    }).catch(() => null);
    if (!probe) {
      throw caldavError('caldav.error.connection');
    }
    if (probe.status === 401 || probe.status === 403) {
      throw caldavError('caldav.error.credentials', probe);
    }
    const clientCaldav = await createClient(settings);
    const calendars = await clientCaldav.fetchCalendars({
      props: calendarProps(),
      projectedProps: {[PRIVILEGE_SET_PROP]: true},
      headersToExclude: ['If-None-Match'],
    });
    // Reconciling the mirror calendar must not be able to take the listing
    // down with it: it is a background repair, and a transient failure here
    // would otherwise leave the user with no calendars at all.
    await this.recoverMirrorCalendar(settings, calendars)
      .catch(e => console.error('cannot reclaim the mirror calendar', e));
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
   * Success means the server lists the calendar, not that MKCALENDAR failed
   * to complain: the collection is re-fetched after the request and only its
   * presence saves the href and reports success. MKCALENDAR is atomic (RFC
   * 4791), so a server rejecting any single property answers 207 and creates
   * nothing — and a 207 is a 2xx, which the previous check read as success,
   * telling the user a calendar existed that did not. When the rejected
   * property can only be the colour or the description — decoration, not
   * identity — the request is retried with the display name alone: a mirror
   * calendar with the wrong colour is worth far more than no calendar.
   *
   * MKCALENDAR is not universally permitted. When the server refuses, the
   * account's first calendar — the very one pushing targeted while no mirror
   * href was stored — is adopted as the destination instead: its href is
   * stored, so the settings can name the calendar genuinely receiving the
   * copies and the push switch has a destination to latch on, and the
   * outcome is reported with `adopted` so the caller explains it rather
   * than announcing a created calendar. Left implicit, the destination
   * resolved nothing: the settings had no name to show, refused to store the
   * setting, and pushing never happened. Only an account holding no calendar
   * at all still surfaces an error flagged calendarCreationRefused — the
   * copies then genuinely have nowhere to go.
   *
   * @param {Object} calendarToCreate description of the wanted calendar
   * @param {String} calendarToCreate.name display name, from the platform branding
   * @param {String} calendarToCreate.color `#RRGGBB` colour, from the platform branding
   * @param {String} calendarToCreate.description explains the calendar in the user's own client
   * @returns {Promise<Object>} `{id}` where id is the href of the destination
   *          collection; `{id, adopted, name}` when an existing calendar was
   *          adopted because the server refused to create one
   */
  createCalendar() {
    // The whole lifecycle now runs on the server: derive the path from the
    // slug, create, confirm by reading the calendar home back — never by the
    // MKCALENDAR status, since at least one server answers 201 while creating
    // nothing — and adopt an existing calendar when the server genuinely
    // refuses.
    //
    // The name, colour and description this used to take are gone: they were
    // the platform's own branding, which the server reads for itself. A page
    // that passed them could disagree with a page that did not.
    return fetch(`${window.location.origin}/caldav/rest/push/mirror`, {
      method: 'POST',
      credentials: 'include',
    }).then(pushOutcome).then(target => {
      if (!target || !target.href) {
        throw caldavError('calendarCreationRefused', null);
      }
      // The drawer reads {id, adopted, name} and says which calendar actually
      // receives the copies; keeping that shape is what lets it keep telling
      // the truth about an adoption.
      return {id: target.href, adopted: target.adopted, name: target.name};
    });
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
   * The stored value is returned in relay space, the URL space every other
   * id of this connector lives in: calendar ids come from tsdav, whose hrefs
   * the relay rewrote, so a stored href predating the relay (rooted at the
   * CalDAV server itself) would never compare equal to them by path. agenda
   * compares these ids on decoded paths, which the mapping keeps stable.
   *
   * @returns {Promise<String>} the href, or null when no mirror is configured
   */
  getMirrorCalendarId() {
    return caldavConnectorService.getCaldavSetting()
      .then(settings => settings.mirrorCalendarHref && toRelayUrl(settings.mirrorCalendarHref, settings) || null);
  },
  /**
   * Whether a mirror href designates a calendar eXo created to hold the
   * copies, as opposed to an existing calendar of the user adopted when the
   * server refused MKCALENDAR. The two must not be treated alike by UIs: a
   * dedicated mirror holds nothing but copies of meetings the agenda already
   * shows, so calendar lists leave it out — while an adopted calendar is one
   * the user keeps for themselves, and hiding it would make a calendar they
   * rely on quietly disappear. Judged on the collection path eXo controls,
   * the same signal recoverMirrorCalendar trusts, so a rename in the user's
   * own client never flips the answer.
   *
   * @param {String} href the mirror calendar href
   * @returns {Boolean} true when the href is an eXo-created mirror collection
   */
  isDedicatedMirrorCalendar(href) {
    return isMirrorCollection(href);
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
    // The fallback href must be addressable: a stored href predating the
    // relay is rooted at the CalDAV server itself, which the browser no
    // longer reaches — mapped into relay space it targets the same
    // collection through the platform.
    return calendars.find(calendar => isSameCollection(calendar.url, mirrorCalendarHref))
      || {url: toRelayUrl(mirrorCalendarHref, settings)};
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
    // The window bounds, made safe before any request. The agenda's first
    // remote read can fire from its connector watcher before the calendar
    // has emitted a period: Agenda.vue starts with {start: new Date(),
    // end: null} and the event-form calendar with {} — so a bound here may
    // be null. Passed through, tsdav rejects the time range with "invalid
    // timeRange format, not in ISO8601" for every collection and the agenda
    // shows no remote event at all (observed live against BlueMind through
    // the relay, 2026-08-20; latent before, when BlueMind never got past
    // the connection). No usable start means no window to ask any server
    // about — nothing is read. A missing end means "from the start
    // onwards", bounded to one year past the start — the same default the
    // agenda timeline widget applies to its own open-ended period.
    const start = timeRangeBound(periodStartDate);
    const end = timeRangeBound(periodEndDate) || oneYearAfter(start);
    if (!start || !end) {
      return [];
    }
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
  pushEvent(event) {
    // The write now happens on the server. What used to be built here — the
    // iCalendar object, the destination, the conditional write — is decided
    // where the credentials already live, so the page never handles them and
    // the outcome becomes something a test can assert. What the page keeps is
    // rendering the failure, which is why the codes below are unchanged.
    const link = eventUrl(event);
    const query = link && `?eventUrl=${encodeURIComponent(link)}` || '';
    return fetch(`${window.location.origin}/caldav/rest/push/events/${event.id}${query}`, {
      method: 'POST',
      credentials: 'include',
    }).then(pushOutcome);
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
    // A whole event is removed server-side. Excluding a single occurrence is
    // NOT yet: it rewrites the stored object, and that rewrite has no
    // server-side counterpart until EXO-89526's exclusion work lands. Routing
    // it to an endpoint that cannot do it would delete the whole series
    // instead of one instance, so it keeps the path that works.
    if (event.occurrence) {
      const uid = event.parent && event.parent.remoteId;
      const instance = isoInstant(event.occurrence.id);
      if (!uid || !instance) {
        return Promise.resolve(null);
      }
      // A rewrite on the server, not a delete: every component of a series
      // lives in one calendar object, so removing the object would cancel
      // every meeting of the series to cancel one.
      return fetch(`${window.location.origin}/caldav/rest/push/objects/${encodeURIComponent(uid)}`
                   + `/occurrences/${encodeURIComponent(instance)}`, {
        method: 'DELETE',
        credentials: 'include',
      }).then(pushOutcome);
    }
    if (!event.remoteId) {
      return Promise.resolve(null);
    }
    return fetch(`${window.location.origin}/caldav/rest/push/objects/${encodeURIComponent(event.remoteId)}`, {
      method: 'DELETE',
      credentials: 'include',
    }).then(pushOutcome);
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
      await ensureAccepted(response);
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
  // NOTHING CALLS THIS ANY MORE. pushEvent now posts to the server, which
  // builds the object with the Java engine (EXO-89524). The builder below and
  // everything it uses is kept only until PR6 removes tsdav from this file
  // wholesale — deleting it here would have mixed a large removal into a
  // behaviour change and made both harder to review. Its jest tests still
  // pass, and are now guarding code the product does not execute: that is a
  // fact worth knowing when reading a green suite, not a reason to keep it.
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
      await ensureAccepted(response);
      return {id: icalUID};
    } catch (e) {
      console.error('Error creating/updating CalDAV:', e, 'iCalString:', iCalString);
      throw e.code ? e : caldavError('caldav.error.save', e);
    }
  }
};

export default caldavConnector;


/**
 * Builds one agenda connector descriptor for one declared CalDAV server: the
 * base descriptor above, closed over the server's provider name, registration
 * id and URL. The provider name is the descriptor's identity — it is what
 * agenda's enabled-check and connected-provider binding key on — so every
 * declared server becomes a full connector with zero agenda backend change.
 *
 * @param {Object} server a declared server {id, providerName, name, description, serverUrl}
 * @param {Number} index position of the server in the declared list, keeps ranks distinct
 * @returns {Object} the connector descriptor to register under agenda/connectors
 */
export function createCaldavConnector(server, index) {
  return Object.assign({}, caldavConnector, {
    name: server.providerName,
    description: `${server.providerName}.description`,
    serverId: server.id,
    serverUrl: server.serverUrl,
    // The visual identity, in the admin's order of precedence: the uploaded
    // image, else the font icon chosen in admin, else the packaged CalDAV
    // default. `avatar` stays an image URL for every consumer that renders
    // an <img> (toolbar badge, timeline); `icon`+`imageUrl` let the connect
    // drawer render the font icon when that is what the admin configured, so
    // the drawer and the admin list show the same identity.
    avatar: server.imageUrl || caldavConnector.avatar,
    icon: server.icon || null,
    imageUrl: server.imageUrl || null,
    rank: caldavConnector.rank + (index || 0),
  });
}

/**
 * The host a declared server points at, for the connect drawer's secondary
 * line when the administrator typed no description: with several CalDAV
 * servers sharing one icon, the host is the one piece of always-present data
 * that genuinely tells two of them apart. The full URL would drag its path —
 * often holding the raw `{username}` placeholder — into the UI; the host
 * never does.
 *
 * @param {String} serverUrl the configured base URL of the server
 * @returns {String} the host (with its port when one is set), or the trimmed
 *          input when it does not parse as a URL, or an empty string
 */
export function serverHost(serverUrl) {
  if (!serverUrl) {
    return '';
  }
  try {
    return new URL(serverUrl).host;
  } catch (e) {
    // not a parseable URL (relative path, bare host...): keep what identifies
    // it best — everything up to the first slash after an optional scheme
    return serverUrl.trim().replace(/^[a-z]+:\/\//i, '').split('/')[0];
  }
}

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
  ics += organizerLines(event);
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
 * The scheduling identities of the copy: who called the meeting, and who is
 * expected in it. Added for servers whose model treats an organizer as part
 * of an event's identity — BlueMind tends to fail such a PUT server-side
 * rather than answer a clean 4xx — and written truthfully rather than
 * expediently: ORGANIZER is the eXo event's organizer, `event.creator`,
 * never the connected CalDAV account. For a meeting the user merely
 * accepted, naming the account owner would put a subtly false event in
 * their calendar, and their own client could then offer to send invitations
 * on their behalf for a meeting somebody else called.
 *
 * The organizer's address comes from the creator's profile as agenda already
 * ships it with the event, and profile visibility can withhold it. An
 * address is never invented — RFC 5545 makes the value a CAL-ADDRESS, and a
 * fabricated one would be forwarded as a reply-to by any client acting on
 * the copy — so when none is visible the properties are omitted entirely,
 * ATTENDEE included: RFC 5545 section 3.8.4.1 defines ATTENDEE only in
 * group-scheduled components, which the ORGANIZER property is what marks.
 *
 * Every ATTENDEE carries SCHEDULE-AGENT=CLIENT, and so does ORGANIZER when
 * the pushing user is not the organizer (the parameter belongs on ORGANIZER
 * precisely in an attendee's copy, RFC 6638 section 7.1): the copy mirrors
 * scheduling that already happened in eXo, and without the parameter a
 * scheduling-aware server would take the PUT as an instruction to run that
 * scheduling itself — mailing invitations to every attendee, or a reply to
 * the organizer.
 *
 * Attendees without a visible address — spaces, and users whose profile
 * hides their email — are left off the copy for the same no-invented-address
 * reason; the roster on the phone may therefore be shorter than the one in
 * eXo, which the URL property already links to in full.
 *
 * @param {Object} event agenda event being pushed
 * @returns {String} the ORGANIZER and ATTENDEE lines, or an empty string
 */
function organizerLines(event) {
  const organizerEmail = event.creator?.profile?.email;
  if (!organizerEmail) {
    return '';
  }
  const currentUserIdentityId = window.eXo?.env?.portal?.userIdentityId;
  const pusherIsOrganizer = !!currentUserIdentityId && String(event.creator.id) === String(currentUserIdentityId);
  const organizerAgent = pusherIsOrganizer ? '' : ';SCHEDULE-AGENT=CLIENT';
  let ics = `ORGANIZER${cnParam(event.creator.profile.fullname)}${organizerAgent}:mailto:${toUri(organizerEmail)}\n`;
  (event.attendees || []).forEach(attendee => {
    const profile = attendee?.identity?.profile;
    if (!profile?.email) {
      return;
    }
    ics += `ATTENDEE${cnParam(profile.fullname)};PARTSTAT=${partStat(attendee.response)};SCHEDULE-AGENT=CLIENT:mailto:${toUri(profile.email)}\n`;
  });
  return ics;
}

/**
 * A CN parameter naming a calendar user, always as a quoted string so that a
 * name holding a comma or a semicolon does not end the parameter (RFC 5545
 * section 3.2). A double quote cannot appear inside a quoted string at all
 * and is removed, as are line breaks, which would end the content line.
 *
 * @param {String} name display name of the calendar user, possibly absent
 * @returns {String} the `;CN="..."` parameter, or an empty string
 */
function cnParam(name) {
  const cleaned = typeof name === 'string' && name.replace(/["\r\n]/g, '').trim();
  return cleaned && `;CN="${cleaned}"` || '';
}

/**
 * The PARTSTAT token for an agenda attendee response. Agenda's own values are
 * the RFC 5545 tokens already — NEEDS-ACTION, ACCEPTED, DECLINED, TENTATIVE —
 * up to the underscore spelling the enum constant uses; anything else becomes
 * NEEDS-ACTION, the RFC default, rather than an invalid token.
 *
 * @param {String} response the attendee's response as agenda holds it
 * @returns {String} a valid PARTSTAT value
 */
function partStat(response) {
  const token = String(response || '').toUpperCase().replace(/_/g, '-');
  return ['ACCEPTED', 'DECLINED', 'TENTATIVE'].includes(token) && token || 'NEEDS-ACTION';
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
 * Built through the class-based tsdav DAVClient rather than the
 * createDAVClient entry point, because login() stores the discovered account
 * — principal, root and calendar homeUrl — on the instance, while
 * createDAVClient runs the very same discovery and keeps the account inside a
 * closure. Every reader of clientCaldav.account therefore saw undefined, and
 * the mirror-calendar derivation that documents itself as using "the
 * account's own calendar home" silently fell through to its fallbacks — down
 * to the configured server URL, the DAV root on servers registered by their
 * root, where MKCALENDAR does not answer a refusal but a gateway error
 * (BlueMind's answers 504 there, and 201 under the home). The unit tests
 * stubbed an `account` property on the mocked client, which is why they kept
 * passing over a property the real library never exposed.
 *
 * @param {Object} settings connector settings holding the URL and credentials
 * @returns {Promise<Object>} a logged-in tsdav client carrying its account
 */
async function createClient(settings) {
  try {
    // Auth is 'Custom' with the relay in charge: the eXo session cookie —
    // sent by the browser on this same-origin URL — is what authenticates
    // the request, and the relay injects the stored CalDAV credentials
    // server-side. The Basic header survives only for the legacy
    // direct-to-server fallback, when no declared server resolves and the
    // settings still carry a password.
    const authHeaders = settings.password
      ? {authorization: `Basic ${btoa(`${settings.username}:${settings.password}`)}`}
      : {};
    const clientCaldav = new tsdav.DAVClient({
      serverUrl: relayServerUrl(settings),
      credentials: {},
      authMethod: 'Custom',
      authFunction: () => Promise.resolve(authHeaders),
      defaultAccountType: 'caldav',
    });
    await clientCaldav.login();
    return clientCaldav;
  } catch (e) {
    console.error('cannot connect to the CalDAV server, check the URL and credentials', e);
    throw caldavError('caldav.error.connection', e);
  }
}

/**
 * One bound of the read window, as an RFC 3339 timestamp a CalDAV
 * time-range filter accepts — or null when the value does not designate an
 * instant. toRFC3339 answers null for an absent input, and formats an
 * Invalid Date into a "NaN-NaN-NaN…" string; both would be sent to the
 * server as-is and rejected by tsdav's own validation before any request,
 * so a bound that does not parse back into a real date is reported as
 * missing instead.
 *
 * @param {Date|String|Number} date one end of the requested period, possibly absent
 * @returns {String} the RFC 3339 timestamp, or null when there is no usable instant
 */
function timeRangeBound(date) {
  const bound = caldavConnectorService.toRFC3339(date, false, true);
  return bound && !Number.isNaN(new Date(bound).getTime()) ? bound : null;
}

/**
 * The default far end of an open-ended read window: one year past its
 * start, matching what the agenda timeline widget applies to its own
 * period when the end is not set.
 *
 * @param {String} start RFC 3339 timestamp of the window's start, or null
 * @returns {String} the RFC 3339 timestamp one year later, or null without a start
 */
function oneYearAfter(start) {
  if (!start) {
    return null;
  }
  const end = new Date(start);
  end.setFullYear(end.getFullYear() + 1);
  return timeRangeBound(end);
}

/**
 * One end of a read window as an ISO instant, or null when it does not name
 * one.
 *
 * Refused rather than defaulted: a window nobody asked for answers with the
 * wrong events, which a user reads as missing meetings rather than as a
 * failure.
 *
 * @param {String|Date|Number} value the period bound agenda supplied
 * @returns {String} the ISO instant, or null
 */
function isoInstant(value) {
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/**
 * Reads a server-side read outcome.
 *
 * A read that fails answers with nothing rather than rejecting: the agenda
 * shows several connectors at once, and one of them failing must not take the
 * whole month down. The server already degraded per calendar for the same
 * reason; this is the last step of the same rule.
 *
 * @param {Response} response the server's answer
 * @returns {Promise} resolves the payload, or an empty result
 */
function readOutcome(response) {
  if (!response.ok) {
    console.error('cannot read the remote calendars', response.status);
    return Promise.resolve([]);
  }
  return response.json().catch(() => []);
}

/**
 * Reads a server-side write outcome.
 *
 * The body of a refusal is the connector's own code — caldav.error.credentials,
 * conflict, save, noCalendar, calendarCreationRefused — kept identical across
 * the move so that every message the page already renders keeps working. A
 * status alone would not do: the same 409 covers "your account is not
 * connected" and "someone else edited this meeting", and those are different
 * things to tell a user.
 *
 * @param {Response} response the server's answer
 * @returns {Promise} resolves the outcome, or rejects with a coded error
 */
function pushOutcome(response) {
  if (response.ok) {
    return response.status === 204 ? Promise.resolve(null) : response.json().catch(() => null);
  }
  return response.text().catch(() => '').then(code => {
    throw caldavError(code && code.trim() || 'caldav.error.save', {status: response.status});
  });
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
 * @returns {Promise} resolves when the server accepted the write
 * @throws {Error} caldav.error.conflict on 412, caldav.error.save otherwise
 */
async function ensureAccepted(response) {
  if (response.status === 412) {
    throw caldavError('caldav.error.conflict', response);
  }
  if (!response.ok) {
    throw await refusedByServer(response);
  }
}

/**
 * Turns a refused request into an error that names itself: the status and the
 * body the server answered with are read, logged, and carried on the error,
 * instead of being discarded. The failure mode this ends: the connector
 * reported `caldav.error.save` and threw away the one sentence in which the
 * server said why — a BlueMind 500 then surfaced with nothing to diagnose it
 * by, its body already unavailable by the time devtools was opened.
 *
 * Only the response is read, never the request: no credentials and no
 * Authorization header can end up in the log.
 *
 * @param {Object} response fetch Response the server refused with
 * @returns {Promise<Error>} the error to throw, carrying status and body
 */
async function refusedByServer(response) {
  let body = '';
  try {
    body = await response.text();
  } catch (e) {
    body = `(the response body could not be read: ${e && e.message || e})`;
  }
  console.error('the CalDAV server refused the request:',
    response.status, response.statusText || '', response.url || '', body);
  const error = caldavError('caldav.error.save', response);
  error.body = body;
  return error;
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
    headers: davHeaders(settings, {}),
    credentials: 'include',
  });
  if (response.status === 404) {
    return null;
  }
  // A server error on the existence probe is not an answer about the event,
  // and it must not abort the push. BlueMind answers 500 — not 404 — for an
  // .ics that is simply not there, so treating anything but 404 as fatal made
  // every first push of an event fail before a single byte was written.
  //
  // Reporting "absent" here is safe rather than optimistic: the creating
  // write keeps its If-None-Match: * precondition, so if the object did exist
  // after all and only the read failed, the server answers 412 and the push
  // surfaces as a conflict. The worst case is a refused write, never a
  // silently overwritten one.
  if (response.status >= 500) {
    console.warn(
      'the CalDAV server failed to say whether the event exists, treating it as new:',
      response.status,
      response.statusText,
      url);
    return null;
  }
  if (!response.ok) {
    throw await refusedByServer(response);
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
 * The relay prefix a URL path may carry: the per-server namespace the
 * platform relays DAV requests under. One pattern, shared by the helpers
 * below, so "is this relay space?" has exactly one definition.
 */
const RELAY_PREFIX_PATTERN = /^\/caldav\/rest\/dav\/\d+/;

/**
 * The relay root of the connected account: where the platform forwards DAV
 * requests to the declared server the account references, injecting the
 * stored credentials server-side. Null when no declared server resolves —
 * the pre-registry deployment — in which case the connector falls back to
 * addressing the server directly, exactly as before the relay.
 *
 * @param {Object} settings connector settings carrying the effective serverId
 * @returns {String} the absolute relay root URL, or null
 */
function relayRoot(settings) {
  return settings && settings.serverId != null
    ? `${window.location.origin}/caldav/rest/dav/${settings.serverId}`
    : null;
}

/**
 * A collection or object href, made addressable by this browser: an href
 * already in relay space is kept, anything else — a stored href predating
 * the relay, rooted at the CalDAV server itself, or a bare path — has its
 * path folded under the account's relay root. Without a relay root the href
 * is returned unchanged, for the legacy direct fallback.
 *
 * @param {String} href collection or object URL, path, or legacy absolute URL
 * @param {Object} settings connector settings carrying the effective serverId
 * @returns {String} the href to actually request
 */
function toRelayUrl(href, settings) {
  const root = relayRoot(settings);
  if (!root || !href) {
    return href;
  }
  try {
    const url = new URL(href, window.location.origin);
    if (url.origin === window.location.origin && RELAY_PREFIX_PATTERN.test(url.pathname)) {
      return url.href;
    }
    return `${root}${url.pathname}${url.search}`;
  } catch (e) {
    return href;
  }
}

/**
 * The base URL every DAV conversation of the account starts from: the
 * resolved server URL with the username substituted, moved into relay space
 * — or left direct when no declared server resolves. tsdav discovers
 * everything else (principal, home, collections) from the hrefs the
 * responses advertise, which the relay rewrites into the same space, so the
 * whole URL universe of a session stays behind the platform origin.
 *
 * @param {Object} settings connector settings holding the URL and serverId
 * @returns {String} the URL to open the DAV conversation at
 */
function relayServerUrl(settings) {
  return toRelayUrl(settings.caldavUrl.replace('{username}', settings.username), settings);
}

/**
 * The headers a connector-issued DAV request carries beside the
 * request-specific ones: nothing auth-shaped when the relay is in charge —
 * the eXo session cookie authenticates the request and the relay injects the
 * stored CalDAV credentials — and the legacy Basic header only in the
 * direct fallback, when the settings still carry a password.
 *
 * @param {Object} settings connector settings
 * @param {Object} headers request-specific headers to extend
 * @returns {Object} the headers to send
 */
function davHeaders(settings, headers) {
  if (settings && settings.password) {
    return {...headers, 'Authorization': `Basic ${btoa(`${settings.username}:${settings.password}`)}`};
  }
  return headers;
}

/**
 * The decoded path of a collection URL, without a trailing slash — the part
 * of an href that identifies the collection regardless of host or of how the
 * server percent-encodes it. A relay prefix is stripped first: the same
 * collection is one path when stored before the relay (rooted at the CalDAV
 * server) and another when enumerated through it, and both must keep
 * designating the same collection — otherwise every pre-relay account would
 * lose its mirror on the day the relay ships.
 *
 * @param {String} url collection URL or href
 * @returns {String} its decoded, slash-trimmed, relay-prefix-free path
 */
function collectionPath(url) {
  try {
    return decodeURIComponent(new URL(url, window.location.origin).pathname)
      .replace(RELAY_PREFIX_PATTERN, '')
      .replace(/\/+$/, '');
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
