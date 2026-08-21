import * as caldavConnectorService from '../js/agendaCaldavService.js';
/**
 * What deleting each calendar would do, kept from the moment agenda asks until
 * the deletion it precedes. Not a cache of remote state: it holds one answer
 * for the length of one confirmation.
 */
const deletionPlans = new Map();

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
   * What deleting an eXo calendar would also do on this connector's side.
   *
   * Asked before the confirmation dialog opens, so the sentence it returns is
   * read before the user confirms rather than after. Two different warnings,
   * because two different things happen: a collection eXo created is deleted
   * with the calendar — and everything in it goes, including events other
   * devices added, which is the part a user cannot guess and cannot undo — while
   * a calendar the user made in their own client is left untouched, and saying
   * so is worth as much, since otherwise they assume the worst and keep a
   * calendar they meant to remove from eXo.
   *
   * @param {Object} calendar the eXo calendar about to be deleted
   * @returns {Promise<Object>} resolves {claims, warning}
   */
  describeCalendarDeletion(calendar) {
    if (!calendar || !calendar.id) {
      return Promise.resolve({claims: false, warning: ''});
    }
    return fetch(`${window.location.origin}/caldav/rest/push/calendars/${calendar.id}/deletion-plan`, {
      credentials: 'include',
    }).then(readOutcome).then(plan => {
      if (!plan || !plan.claimed) {
        return {claims: false, warning: ''};
      }
      deletionPlans.set(String(calendar.id), plan);
      return {claims: true, warning: deletionWarning(plan)};
    });
  },
  /**
   * Removes the remote counterpart before agenda removes the calendar.
   *
   * Rejects to abort the whole deletion, which is what puts the failable step
   * first: a server that refuses or cannot be reached leaves both sides exactly
   * as they were, rather than leaving a collection stranded after the record
   * that knew about it is gone.
   *
   * @param {Object} calendar the eXo calendar being deleted
   * @returns {Promise} resolves once the remote side is dealt with
   */
  deleteCalendar(calendar) {
    const plan = calendar && deletionPlans.get(String(calendar.id));
    if (!plan || !plan.claimed) {
      return Promise.resolve();
    }
    // Either way the server is told: a propagating deletion removes the
    // collection, a non-propagating one records that the user kept it. The
    // second matters as much — without it the next sweep would materialise the
    // remote calendar straight back and undo the deletion in front of them.
    return fetch(`${window.location.origin}/caldav/rest/push/calendars/${calendar.id}${
      plan.propagates && '/remote' || '/keep-remote'}`, {
      method: plan.propagates && 'DELETE' || 'POST',
      credentials: 'include',
    }).then(pushOutcome).then(() => deletionPlans.delete(String(calendar.id)));
  },
  /**
   * The stored href of the mirror calendar, so UIs can single it out — for
   * instance to keep it off calendar lists, since it only holds copies of
   * events eXo already displays.
   *
   * Compared on decoded paths, as agenda compares every calendar id: the same
   * collection is written %40 by a server and @ by a client, and a href stored
   * before this connector spoke to a relay is rooted at the CalDAV server
   * itself. None of that makes it a different calendar.
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
 * The sentence shown before a deletion is confirmed.
 *
 * @param {Object} plan what the server said deleting this calendar would do
 * @returns {String} the warning, already in the user's language
 */
function deletionWarning(plan) {
  const key = plan.propagates
    ? 'agenda.caldavCalendar.calendarDelete.propagates'
    : 'agenda.caldavCalendar.calendarDelete.keepsRemote';
  const bundle = window.eXo && eXo.env && eXo.env.portal && eXo.env.portal.i18n || {};
  const server = serverHost(plan.server) || plan.server || '';
  return (bundle[key] || '').replace('{0}', server);
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
 * is read-only, try again — from a code the server sends, without parsing
 * prose or depending on any library's error shapes.
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
