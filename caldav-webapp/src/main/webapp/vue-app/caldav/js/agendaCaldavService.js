/*
 * Copyright (C) 2023 eXo Platform SAS.
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
export const USER_TIMEZONE_ID = new window.Intl.DateTimeFormat().resolvedOptions().timeZone;

export const createCaldavSetting = (caldavSettings) => {
  return fetch(`${eXo.env.portal.context}/${eXo.env.portal.rest}/v1/caldav`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST',
    body: JSON.stringify(caldavSettings)
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    } else {
      return resp.status;
    }
  });
};

/**
 * The declared CalDAV servers — the credential-free registry rows any
 * authenticated user may read, since the browser itself needs the names and
 * URLs to offer the connectors.
 *
 * @returns {Promise<Array>} every declared server
 */
export const getCaldavServers = () => {
  return fetch('/caldav/rest/servers', {
    credentials: 'include',
    method: 'GET',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    } else {
      return resp.json();
    }
  });
};

/**
 * Turns a refused response into an Error carrying what the server said.
 *
 * The provider configuration is validated server-side and refused with a message
 * code — a missing required field, a value outside a field's options. Thrown as a
 * bare sentence that code never reaches the screen, and the administrator is told
 * "error" about a form they can in fact correct.
 *
 * @param {Response} resp the refused response
 * @returns {Promise} a promise rejecting with the Error to throw
 */
const refusal = (resp) => {
  return resp.text().then(body => {
    const error = new Error(body || 'Response code indicates a server error');
    error.messageCode = body || null;
    throw error;
  });
};

/**
 * Declares a new CalDAV server (administrators only).
 *
 * @param {Object} server the registration to create {name, description, serverUrl, active}
 * @returns {Promise<Object>} the created registration, carrying its id and provider name
 */
export const createCaldavServer = (server) => {
  return fetch('/caldav/rest/servers', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST',
    body: JSON.stringify(server),
  }).then(resp => {
    if (!resp || !resp.ok) {
      return refusal(resp);
    } else {
      return resp.json();
    }
  });
};

/**
 * Updates a declared CalDAV server (administrators only).
 *
 * @param {Object} server the registration to update, carrying its id
 * @returns {Promise<Object>} the updated registration
 */
export const updateCaldavServer = (server) => {
  return fetch(`/caldav/rest/servers/${server.id}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT',
    body: JSON.stringify(server),
  }).then(resp => {
    if (!resp || !resp.ok) {
      return refusal(resp);
    } else {
      return resp.json();
    }
  });
};

/**
 * The message code a refusal carries, read out of the JSON body Spring builds
 * for a ResponseStatusException.
 *
 * Read as JSON and not as text: the body is an object whose `message` holds
 * the code, so taking the raw text would hand the screen a blob of JSON where
 * it expects a translation key, and every refusal would read as the generic
 * failure. A body that will not parse yields nothing rather than a wrong code.
 *
 * @param {Object} resp the failed response
 * @returns {Promise<String>} the message code, empty when the body carries none
 */
function refusalCode(resp) {
  if (!resp || typeof resp.json !== 'function') {
    return Promise.resolve('');
  }
  return resp.json().then(body => body && body.message || '', () => '');
}

/**
 * Activates or deactivates a declared CalDAV server (administrators only).
 *
 * A refusal comes back as a 400 whose body is the message code: deactivating
 * the server managed mode points the whole instance at is refused with
 * `caldav.managed.serverInUse`, and the rejection carries it as `message` so
 * the snackbar can say which rule was hit rather than that something failed.
 *
 * @param {Number} serverId technical identifier of the registration
 * @param {Boolean} active whether users may connect to this server
 * @returns {Promise<Object>} the updated registration
 */
export const setCaldavServerStatus = (serverId, active) => {
  return fetch(`/caldav/rest/servers/${serverId}/status?active=${active}`, {
    credentials: 'include',
    method: 'PATCH',
  }).then(resp => {
    if (resp && resp.ok) {
      return resp.json();
    }
    return refusalCode(resp).then(code => {
      const error = new Error(code || 'Response code indicates a server error');
      error.status = resp && resp.status;
      error.messageCode = code;
      throw error;
    });
  });
};

/**
 * Deletes a declared CalDAV server (administrators only). A 409 means
 * connected accounts still reference it: the rejection carries `status` 409
 * and `referenceCount` parsed from the server's message code
 * (caldav.server.referenced:<count>), so the UI can explain rather than
 * merely fail.
 *
 * A 400 means a rule refused the deletion outright — today, that the row is
 * the one managed mode points the whole instance at — and the rejection
 * carries that message code as `messageCode`.
 *
 * @param {Number} serverId technical identifier of the registration
 * @returns {Promise} resolves when deleted, rejects with {status, referenceCount, messageCode}
 */
export const deleteCaldavServer = (serverId) => {
  return fetch(`/caldav/rest/servers/${serverId}`, {
    credentials: 'include',
    method: 'DELETE',
  }).then(resp => {
    if (resp && resp.ok) {
      return;
    }
    const error = new Error('Response code indicates a server error');
    error.status = resp && resp.status;
    if (error.status === 409 || error.status === 400) {
      return refusalCode(resp).then(code => {
        error.messageCode = code;
        const count = code.split(':')[1];
        error.referenceCount = count && parseInt(count) || null;
        throw error;
      });
    }
    throw error;
  });
};

export const getCaldavSetting = () => {
  return fetch(`${eXo.env.portal.context}/${eXo.env.portal.rest}/v1/caldav`, {
    credentials: 'include',
    method: 'GET',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    } else {
      return resp.json();
    }
  });
};

/**
 * Persists the href of the mirror calendar in the CalDAV account settings of
 * the current user. The href — never the display name — is the identity of
 * the collection eXo pushes accepted meetings to.
 *
 * @param {String} mirrorCalendarHref href of the mirror calendar collection
 * @returns {Promise<Number>} the HTTP status of the save
 */
export const saveMirrorCalendarHref = (mirrorCalendarHref) => {
  return fetch(`${eXo.env.portal.context}/${eXo.env.portal.rest}/v1/caldav/mirrorCalendar`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT',
    body: JSON.stringify({mirrorCalendarHref})
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    } else {
      return resp.status;
    }
  });
};

/**
 * Asks the CalDAV server whether it recognises the account, before anything
 * is stored or declared connected — probed by the PLATFORM, not by this
 * browser: the typed credentials travel once to the platform's own verify
 * endpoint, which performs the Depth:0 PROPFIND server-side. That is what
 * lets servers sending no CORS headers — BlueMind — connect without any
 * front proxy, and it keeps every direct browser-to-CalDAV request out of
 * the product.
 *
 * Three failures are told apart, each rejecting with an Error carrying the
 * same stable code the historical browser probe produced:
 * - the server cannot be reached at all            -> caldav.error.connection
 * - the server answers but refuses the credentials -> caldav.error.credentials
 * - the URL reaches something that is not a CalDAV
 *   collection (404, 405, a web page...)           -> caldav.error.notCaldav
 *
 * @param {Number} serverId identifier of the declared server to verify
 *          against, or null for the legacy resolution (the seed registration)
 * @param {String} username account to verify
 * @param {String} password password to verify
 * @returns {Promise} resolved when the server accepted the credentials,
 *          rejected with an Error carrying a `code` and, when the server
 *          answered, the HTTP `status` that produced it
 */
export const verifyCaldavAccount = (serverId, username, password) => {
  if (!username) {
    return Promise.reject(caldavError('caldav.error.connection'));
  }
  return fetch('/caldav/rest/connection/verify', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST',
    body: JSON.stringify({serverId, username, password}),
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw caldavError('caldav.error.connection', resp && resp.status);
    }
    return resp.json();
  }).then(outcome => {
    if (!outcome || outcome.result !== 'ok') {
      throw caldavError(outcome && outcome.result || 'caldav.error.connection', outcome && outcome.status);
    }
  });
};

/**
 * Builds the error a CalDAV failure is reported with: a stable code the UI
 * can translate into a message the user can act on, plus the HTTP status when
 * there was a response, so logs keep the raw fact while the screen explains it.
 *
 * @param {String} code stable identifier for the kind of failure
 * @param {Number} status HTTP status that produced it, when the server answered
 * @returns {Error} the error to reject with
 */
function caldavError(code, status) {
  const error = new Error(code);
  error.code = code;
  error.status = status;
  return error;
}

export const deleteCaldavSetting = () => {
  return fetch(`${eXo.env.portal.context}/${eXo.env.portal.rest}/v1/caldav`, {
    credentials: 'include',
    method: 'DELETE',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    } else {
      return resp.status;
    }
  });
};
export function pad(n) {
  return n < 10 && `0${n}` || n;
}
export function getUserTimezone() {
  const timeZoneOffset = - (new Date().getTimezoneOffset());
  let timezoneHours = Math.abs(parseInt(timeZoneOffset / 60));
  let timezoneMinutes = Math.abs(parseInt(timeZoneOffset % 60));
  timezoneHours = timezoneHours < 10 ? `0${timezoneHours}` : timezoneHours;
  timezoneMinutes = timezoneMinutes < 10 ? `0${timezoneMinutes}` : timezoneMinutes;
  const timezoneSign = timeZoneOffset >= 0 ? '+' : '-';
  return `${timezoneSign}${timezoneHours}:${timezoneMinutes}`;
}
export function toRFC3339(date, ignoreTime, useTimeZone) {
  if (!date) {
    return null;
  }
  if (typeof date === 'number') {
    date = new Date(date);
  } else if (typeof date === 'string') {
    if (date.indexOf('T') === 10 && date.length > 19) {
      date = date.substring(0, 19);
    }
    date = new Date(date);
  }
  let formattedDate;
  if (ignoreTime) {
    formattedDate = `${date.getFullYear()  }-${
      pad(date.getMonth() + 1)  }-${
      pad(date.getDate())  }T00:00:00`;
  } else {
    formattedDate = `${date.getFullYear()  }-${
      pad(date.getMonth() + 1)  }-${
      pad(date.getDate())  }T${
      pad(date.getHours())  }:${
      pad(date.getMinutes())  }:${
      pad(date.getSeconds())
    }`;
  }
  if (useTimeZone) {
    return `${formattedDate}${getUserTimezone()}`;
  }
  return formattedDate;
}

export function toDate(date) {
  if (!date) {
    return null;
  } else if (typeof date === 'number') {
    return new Date(date);
  } else if (typeof date === 'string') {
    if (date.indexOf('T') === 10 && date.length > 19) {
      // Delete TimeZone information
      return new Date(date.substring(0, 19));
    } else if (date.length === 10) {
      // Ensure that TimeZone information doesn't alter the real day of the event
      return new Date(`${date} 00:00:00`);
    }
    return new Date(date);
  } else if (typeof date === 'object') {
    return new Date(date);
  }
}

/**
 * The calendars the user deleted in eXo while choosing to keep them on the
 * server. Each carries the binding to lift and the name the server gives the
 * collection today — never a stored name, which would go stale the moment the
 * user renamed it in their own client.
 *
 * @returns {Promise<Array>} the hidden calendars, empty when there are none
 */
export const getHiddenCalendars = () => {
  return fetch(`${window.location.origin}/caldav/rest/hidden-calendars`, {
    credentials: 'include',
    method: 'GET',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    }
    return resp.json();
  });
};

/**
 * Shows a hidden calendar again, by the id of the binding that hides it.
 *
 * The id rather than the collection path: a path travelling through a browser
 * is something a caller could change, and what it would then name is another
 * collection on the same account.
 *
 * @param {Number} pairId the binding to lift
 * @returns {Promise} resolves once the calendar is back: at the next
 *          synchronisation for a calendar deleted here, at once for a share
 *          the user hid
 */
export const showCalendarAgain = pairId => {
  return fetch(`${window.location.origin}/caldav/rest/hidden-calendars/${pairId}`, {
    credentials: 'include',
    method: 'DELETE',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    }
  });
};

/**
 * Hides a calendar somebody shared with the user (EXO-90239).
 *
 * The calendar travels in a JSON body as the calendar list answered it: it is
 * a collection href, and in a path or a query its slashes and its
 * percent-encoded login would be refused or decoded one time too many before
 * any handler saw them. The platform matches it against the user's own
 * current listing and refuses anything that is not a share of theirs.
 *
 * A refusal rejects with an Error whose `code` is what the platform said —
 * `caldav.hiddenCalendars.notAShare` for a calendar of the user's own,
 * `caldav.error.noCalendar` when no account is connected — and whose `status`
 * is the HTTP status, so a caller can tell a calendar that cannot be hidden
 * from a platform that could not be asked.
 *
 * @param {String} calendarId the calendar's identity, as listCalendars gave it
 * @returns {Promise} resolves once the calendar is hidden, or was already
 */
export const hideCalendar = calendarId => {
  return fetch(`${window.location.origin}/caldav/rest/hidden-calendars`, {
    credentials: 'include',
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({calendarId}),
  }).then(resp => {
    if (!resp || !resp.ok) {
      return codedRefusal(resp);
    }
  });
};

/**
 * Turns a refused response into a rejection carrying the platform's code.
 *
 * Two body shapes reach here and both are read: the plain code a push failure
 * answers with (`caldav.error.noCalendar`), and the JSON object Spring builds
 * for a ResponseStatusException, whose `message` holds the code. A body that
 * is neither, or none at all, yields the generic error rather than a wrong
 * code.
 *
 * @param {Response} resp the refused response
 * @returns {Promise} a promise rejecting with the coded Error
 */
function codedRefusal(resp) {
  const status = resp && resp.status;
  const text = resp && typeof resp.text === 'function' ? resp.text().catch(() => '') : Promise.resolve('');
  return text.then(body => {
    let code = (body || '').trim();
    let details = {};
    if (code.startsWith('{')) {
      try {
        details = JSON.parse(code) || {};
        code = details.message || '';
      } catch (e) {
        code = '';
      }
    }
    const error = new Error(code || 'Response code indicates a server error');
    error.code = code || null;
    error.messageCode = error.code;
    error.status = status;
    // What a calendar server said when it refused a share (EXO-90253), so the
    // refusal can be shown as the server stated it. Empty for every other
    // refusal, which carries neither.
    error.preconditions = Array.isArray(details.preconditions) ? details.preconditions : [];
    error.missingPrivileges = Array.isArray(details.missingPrivileges) ? details.missingPrivileges : [];
    throw error;
  });
}

/**
 * The ids of the user's own calendars agenda may offer "Share…" on
 * (EXO-90253): owned, exported by eXo to the connected CalDAV account, on a
 * server where granting is verified.
 *
 * Never rejects. Whether an entry is offered is not worth an error: a
 * platform that cannot answer offers no calendar, and the menu reads as it
 * did before this feature.
 *
 * One request for every caller asking at the same moment. The add-on registers
 * a connector per declared server and agenda asks each of them on every
 * refresh, so without this one refresh made one identical request per server,
 * each costing the platform a round trip to the calendar server. The shared
 * request is forgotten once it settles: the next refresh asks again.
 *
 * @returns {Promise<Array>} the agenda calendar ids, possibly empty
 */
export const getShareableCalendars = () => {
  if (!shareableCalendarsInFlight) {
    shareableCalendarsInFlight = fetch(`${window.location.origin}/caldav/rest/calendars/shareable`, {credentials: 'include'})
      .then(resp => (resp && resp.ok ? resp.json() : null))
      .then(payload => (payload && Array.isArray(payload.calendarIds) ? payload.calendarIds : []))
      .catch(() => [])
      .finally(() => shareableCalendarsInFlight = null);
  }
  return shareableCalendarsInFlight;
};

/** The shareable-calendars request in flight, shared by concurrent callers. */
let shareableCalendarsInFlight = null;

/**
 * Who one of the user's calendars is shared with, read from the server now.
 *
 * @param {Number} calendarId the agenda calendar id
 * @returns {Promise<Object>} {calendarId, sharees}; rejects with the coded
 *          error, carrying what the server said when it refused
 */
export const getCalendarShares = calendarId => {
  return fetch(`${window.location.origin}/caldav/rest/calendars/${encodeURIComponent(calendarId)}/shares`, {
    credentials: 'include',
  }).then(resp => (resp && resp.ok ? resp.json() : codedRefusal(resp)));
};

/**
 * Shares one of the user's calendars read-only with a colleague.
 *
 * @param {Number} calendarId the agenda calendar id
 * @param {String} username the colleague's eXo login
 * @returns {Promise<Object>} the sharees as the server lists them afterwards
 */
export const shareCalendar = (calendarId, username) => {
  return fetch(`${window.location.origin}/caldav/rest/calendars/${encodeURIComponent(calendarId)}/shares`, {
    credentials: 'include',
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({username}),
  }).then(resp => (resp && resp.ok ? resp.json() : codedRefusal(resp)));
};

/**
 * Stops sharing one of the user's calendars with a colleague.
 *
 * @param {Number} calendarId the agenda calendar id
 * @param {String} username the colleague's eXo login
 * @returns {Promise<Object>} the sharees as the server lists them afterwards
 */
export const unshareCalendar = (calendarId, username) => {
  return fetch(`${window.location.origin}/caldav/rest/calendars/${encodeURIComponent(calendarId)}/shares/${encodeURIComponent(username)}`, {
    credentials: 'include',
    method: 'DELETE',
  }).then(resp => (resp && resp.ok ? resp.json() : codedRefusal(resp)));
};

/**
 * The colleagues one of the user's calendars can be shared with: eXo users
 * connected to the same CalDAV server under another login.
 *
 * @param {Number} calendarId the agenda calendar id
 * @returns {Promise<Array>} {identityId, username, fullName, avatarUrl}
 */
export const getShareCandidates = calendarId => {
  return fetch(`${window.location.origin}/caldav/rest/calendars/${encodeURIComponent(calendarId)}/share-candidates`, {
    credentials: 'include',
  }).then(resp => (resp && resp.ok ? resp.json() : codedRefusal(resp)))
    .then(candidates => (Array.isArray(candidates) ? candidates : []));
};

/**
 * Synchronises the connected account now, whatever the throttle says.
 *
 * @returns {Promise} resolves once the synchronisation has run
 */
export const syncNow = () => {
  return fetch(`${window.location.origin}/caldav/rest/sync`, {
    credentials: 'include',
    method: 'POST',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    }
  });
};

/**
 * When the connected account last finished synchronising.
 *
 * A 204 means it never has — an account connected a moment ago, or one whose
 * every attempt has failed — and resolves to null rather than to a date the
 * caller would have to recognise as meaningless.
 *
 * @returns {Promise<Date>} the instant, or null when nothing has synchronised
 */
export const lastSynchronised = () => {
  return fetch(`${window.location.origin}/caldav/rest/sync/state`, {
    credentials: 'include',
    method: 'GET',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    }
    if (resp.status === 204) {
      return null;
    }
    return resp.json().then(millis => millis && new Date(millis) || null);
  });
};

/**
 * The calendar the copies are currently written into, with the name the server
 * gives it now.
 *
 * Its own call rather than a scan of the calendar listing: that listing hides
 * this collection on purpose — it holds nothing but copies of events the
 * agenda already shows — so looking the destination up in it always came back
 * empty, and the settings screen read that as "no destination".
 *
 * @returns {Promise<Object>} {href, name}, or null when none is set
 */
export const currentMirrorCalendar = () => {
  return fetch(`${window.location.origin}/caldav/rest/push/mirror`, {
    credentials: 'include',
    method: 'GET',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    }
    return resp.status === 204 ? null : resp.json();
  });
};

/**
 * How often and how widely eXo synchronises CalDAV accounts.
 *
 * @returns {Promise<Object>} the tuning in force
 */
export const getSyncTuning = () => {
  return fetch(`${window.location.origin}/caldav/rest/servers/tuning`, {
    credentials: 'include',
    method: 'GET',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    }
    return resp.json();
  });
};

/**
 * Whether this deployment chooses the CalDAV server on its users' behalf.
 *
 * <p>Two different answers in one payload: `serverId`/`serverName` are what
 * the INSTANCE decided and are the same for everybody, while `managedForMe`
 * is the verdict for the calling user — the only one a screen may act on when
 * it takes an affordance away. Today they cannot disagree; group exclusions
 * are what will make them, and reading the wrong one now is what would cost a
 * change in every component then.</p>
 *
 * @returns {Promise<Object>} {serverId, serverName, managedForMe}
 */
export const getManagedMode = () => {
  return fetch(`${window.location.origin}/caldav/rest/servers/managed`, {
    credentials: 'include',
    method: 'GET',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    }
    return resp.json();
  });
};

/**
 * Points the whole instance at one declared server. Administrators only.
 *
 * A server that is unknown or deactivated is refused with a 400 whose body is
 * the message code the drawer shows, so the administrator is told why rather
 * than left with a switch that flicked back.
 *
 * @param {Number} serverId technical identifier of the registration
 * @returns {Promise<Object>} the mode now in force
 */
export const saveManagedMode = serverId => {
  return fetch(`${window.location.origin}/caldav/rest/servers/managed?serverId=${serverId}`, {
    credentials: 'include',
    method: 'PUT',
  }).then(resp => {
    if (resp && resp.ok) {
      return resp.json();
    }
    return refusalCode(resp).then(code => {
      throw new Error(code || 'caldav.admin.managed.saveFailed');
    });
  });
};

/**
 * Gives every user back the choice of their own CalDAV server. Administrators
 * only.
 *
 * Nothing is severed: the mode governs which affordances are offered, never
 * the connections that already exist.
 *
 * @returns {Promise<Object>} the mode now in force, naming no server
 */
export const clearManagedMode = () => {
  return fetch(`${window.location.origin}/caldav/rest/servers/managed`, {
    credentials: 'include',
    method: 'DELETE',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    }
    return resp.json();
  });
};

/**
 * What the last pass over each connected user's meeting copies found and
 * moved. Administrators only.
 *
 * <p>Kept in memory by the platform, so an empty answer after a restart means
 * "no account has synchronised since", never "nothing is happening".</p>
 *
 * @returns {Promise<Array>} one tally per user, newest first
 */
export const getMirrorReports = () => {
  return fetch(`${window.location.origin}/caldav/rest/servers/mirror/reports`, {
    credentials: 'include',
    method: 'GET',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    }
    return resp.json();
  });
};

/**
 * Records how often and how widely eXo synchronises. Administrators only.
 *
 * A refused value comes back as a 400 whose body is the message code the
 * screen shows, so the reason reaches the administrator instead of a generic
 * failure.
 *
 * @param {Object} tuning the values to store
 * @returns {Promise<Object>} the tuning now in force
 */
export const saveSyncTuning = tuning => {
  return fetch(`${window.location.origin}/caldav/rest/servers/tuning`, {
    credentials: 'include',
    method: 'PUT',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(tuning),
  }).then(resp => {
    if (resp && resp.status === 400) {
      return resp.text().then(body => {
        throw new Error(body || 'caldav.tuning.saveFailed');
      });
    }
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    }
    return resp.json();
  });
};

/**
 * The calendars whose synchronisation needs the user's attention.
 *
 * Only the states where something they might do would change the outcome: a
 * calendar that is synchronising is not news, and one they hid has its own
 * listing.
 *
 * @returns {Promise<Array>} the states, empty when everything is well
 */
/**
 * How many meeting copies eXo still owes this user's calendar and has not
 * managed to write.
 *
 * Only the ones it is still attempting: a copy it has given up on is a
 * different problem with a different answer, and lumping the two together
 * would tell somebody to wait for a write that is never coming.
 *
 * @returns {Promise<Number>} the count, zero when everything has landed
 */
export const getOwedCopies = () => {
  return fetch(`${window.location.origin}/caldav/rest/push/owed`, {
    credentials: 'include',
    method: 'GET',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    }
    return resp.json();
  });
};

export const getCalendarSyncStates = () => {
  return fetch(`${window.location.origin}/caldav/rest/calendar-states`, {
    credentials: 'include',
    method: 'GET',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    }
    return resp.json();
  });
};

/**
 * Reads back the provider configuration stored for a declared server.
 *
 * Secret values are never in the answer: the endpoint omits them, so a secret
 * field opens empty and an unrelated save leaves the stored one untouched.
 *
 * @param {number} serverId technical id of the registration
 * @returns {Promise<Object>} the stored values, keyed by descriptor field
 */
export const getCaldavServerProviderConfig = (serverId) => {
  return fetch(`/caldav/rest/servers/${serverId}/provider-config`, {
    credentials: 'include',
    method: 'GET',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    }
    return resp.json();
  });
};

/**
 * Reads the other eXo deployments seen writing meeting copies into a declared
 * server's accounts (EXO-89824).
 *
 * Its own request rather than a field on the registration: the condition is
 * evidence the inbound pass wrote, and nothing an administrator saves may carry
 * it back. Empty on every server nobody else writes into, which is what a
 * healthy deployment looks like.
 *
 * @param {number} serverId technical id of the registration
 * @returns {Promise<Array>} the deployments, most recently seen first, each
 *          carrying its authority and when a copy of its was last read here
 */
export const getCaldavServerForeignWriters = (serverId) => {
  return fetch(`/caldav/rest/servers/${serverId}/foreign-writers`, {
    credentials: 'include',
    method: 'GET',
  }).then(resp => {
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    }
    return resp.json();
  });
};
