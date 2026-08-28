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
      throw new Error('Response code indicates a server error', resp);
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
      throw new Error('Response code indicates a server error', resp);
    } else {
      return resp.json();
    }
  });
};

/**
 * Activates or deactivates a declared CalDAV server (administrators only).
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
    if (!resp || !resp.ok) {
      throw new Error('Response code indicates a server error', resp);
    } else {
      return resp.json();
    }
  });
};

/**
 * Deletes a declared CalDAV server (administrators only). A 409 means
 * connected accounts still reference it: the rejection carries `status` 409
 * and `referenceCount` parsed from the server's message code
 * (caldav.server.referenced:<count>), so the UI can explain rather than
 * merely fail.
 *
 * @param {Number} serverId technical identifier of the registration
 * @returns {Promise} resolves when deleted, rejects with {status, referenceCount}
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
    if (error.status === 409) {
      return resp.json().then(body => {
        const message = body && body.message || '';
        const count = message.split(':')[1];
        error.referenceCount = count && parseInt(count) || null;
        throw error;
      }, () => {
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
 * @returns {Promise} resolves once the calendar will come back on the next sync
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
