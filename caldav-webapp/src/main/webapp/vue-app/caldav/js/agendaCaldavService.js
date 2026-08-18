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
 * Asks the CalDAV server itself whether it recognises the account, before
 * anything is stored or declared connected. The request is a plain PROPFIND
 * rather than a tsdav call on purpose: tsdav reacts to a 401 by falling back
 * to probing the server root and finally reports "cannot find principalUrl" —
 * a discovery error for what is simply a rejected password.
 *
 * Three failures are told apart, each rejecting with an Error carrying its
 * own stable code:
 * - the server cannot be reached at all            -> caldav.error.connection
 * - the server answers but refuses the credentials -> caldav.error.credentials
 * - the URL reaches something that is not a CalDAV
 *   collection (404, 405, a web page...)           -> caldav.error.notCaldav
 *
 * @param {String} caldavUrl URL of the CalDAV server, holding a {username} placeholder
 * @param {String} username account to verify
 * @param {String} password password to verify
 * @returns {Promise} resolved when the server accepted the credentials,
 *          rejected with an Error carrying a `code` and, when the server
 *          answered, the HTTP `status` that produced it
 */
export const probeCaldavAccount = (caldavUrl, username, password) => {
  if (!caldavUrl || !username) {
    return Promise.reject(caldavError('caldav.error.connection'));
  }
  return fetch(caldavUrl.replace('{username}', username), {
    method: 'PROPFIND',
    headers: {
      'Depth': '0',
      'Content-Type': 'application/xml',
      'Authorization': `Basic ${btoa(`${username}:${password}`)}`,
    },
    body: '<?xml version="1.0"?><propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>',
  }).then(response => {
    if (response.status === 401 || response.status === 403) {
      throw caldavError('caldav.error.credentials', response.status);
    }
    // A CalDAV collection answers a PROPFIND with 207 Multi-Status. Anything
    // else means the URL reaches something, but not a calendar: a wrong path
    // (404), the server root (405), a web server serving a page (200)...
    if (response.status !== 207) {
      throw caldavError('caldav.error.notCaldav', response.status);
    }
  }, () => {
    // fetch itself rejected: nothing answered at all (connection refused,
    // unknown host, network down) — distinct from a server that answered no.
    throw caldavError('caldav.error.connection');
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
