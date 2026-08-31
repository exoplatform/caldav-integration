/*
 * Copyright (C) 2026 eXo Platform SAS.
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
import caldavConnector from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';

/*
 * EXO-89843. The connector is the one party that can tell agenda "this
 * account could not be read", because the failure is invisible at every layer
 * above it: the calendar server being down reaches the platform as a caught
 * exception, and the platform answers 200 with an empty list. Measured live —
 * the server stopped, the proxy answering 502, the platform logging that it
 * could not be reached — and the browser learned nothing.
 *
 * So every pin here is a CONTRAST between two answers a view cannot otherwise
 * tell apart: an empty reading that succeeded, and an empty reading that
 * failed. A mutant that drops the flag collapses the two, and the assertion
 * fires on the equality — not on a crash.
 */

const PERIOD_START = new Date('2026-09-01T00:00:00Z');

const PERIOD_END = new Date('2026-09-08T00:00:00Z');

/**
 * Answers the next fetch with a 200 carrying the given body.
 *
 * @param {Object|Array} body what the endpoint returns
 * @returns {void}
 */
function respondWith(body) {
  window.fetch = jest.fn(() => Promise.resolve({
    ok: true,
    status: 200,
    json: () => Promise.resolve(body),
  }));
}

/**
 * Answers the next fetch with a failure status and no usable body.
 *
 * @param {Number} status the HTTP status
 * @returns {void}
 */
function respondWithStatus(status) {
  window.fetch = jest.fn(() => Promise.resolve({
    ok: false,
    status,
    json: () => Promise.reject(new Error('no body')),
  }));
}

describe('getEvents tells a failed reading apart from an empty one', () => {

  beforeEach(() => {
    jest.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('reports no failure when the account answered and holds nothing', () => {
    respondWith({events: [], failed: false, failedCalendars: []});
    return caldavConnector.getEvents(PERIOD_START, PERIOD_END).then(read => {
      expect(read.events).toEqual([]);
      expect(read.failed).toBe(false);
    });
  });

  it('reports a failure when the account could not be asked at all', () => {
    // byte for byte the same visible events as above: the flag is the whole
    // difference, and it is the thing a view has to be able to see
    respondWith({events: [], failed: true, failedCalendars: []});
    return caldavConnector.getEvents(PERIOD_START, PERIOD_END).then(read => {
      expect(read.events).toEqual([]);
      expect(read.failed).toBe(true);
    });
  });

  it('reports a failure when some calendars were listed but not read', () => {
    respondWith({events: [{uid: 'a'}], failed: false, failedCalendars: ['/dav/cal/john/work/']});
    return caldavConnector.getEvents(PERIOD_START, PERIOD_END).then(read => {
      // the events that did arrive are kept — a partial week beats no week
      expect(read.events).toHaveLength(1);
      expect(read.failed).toBe(true);
    });
  });

  it('maps the events it did read into agenda remote events', () => {
    respondWith({events: [{uid: 'abc', summary: 'Standup'}], failed: false, failedCalendars: []});
    return caldavConnector.getEvents(PERIOD_START, PERIOD_END).then(read => {
      expect(read.events[0].id).toBe('abc');
      expect(read.events[0].type).toBe('remoteEvent');
    });
  });

  it('reports a failure when the platform itself refused the read', () => {
    // a read the platform refused is a read that did not happen; answering an
    // empty list here would put the failure back where this change took it out
    respondWithStatus(500);
    return caldavConnector.getEvents(PERIOD_START, PERIOD_END).then(read => {
      expect(read.events).toEqual([]);
      expect(read.failed).toBe(true);
    });
  });

  it('reports no failure for a period this page never asked about', () => {
    // a half-built window is this page's own doing, not the account's: the
    // calendar emits its period after a connector signs in, and the read that
    // goes out meanwhile must not raise a banner about the server
    window.fetch = jest.fn();
    return caldavConnector.getEvents(undefined, PERIOD_END).then(read => {
      expect(read.events).toEqual([]);
      expect(read.failed).toBe(false);
      expect(window.fetch).not.toHaveBeenCalled();
    });
  });

  it('still reads a bare array, so a stale bundle degrades to the old shape', () => {
    respondWith([{uid: 'abc'}]);
    return caldavConnector.getEvents(PERIOD_START, PERIOD_END).then(read => {
      expect(read.events).toHaveLength(1);
      expect(read.failed).toBe(false);
    });
  });
});

describe('listCalendars unwraps the envelope for agenda', () => {

  it('answers the calendars the envelope carries', () => {
    respondWith({calendars: [{id: '/dav/cal/john/work/', name: 'Work'}], failed: false});
    return caldavConnector.listCalendars().then(calendars => {
      expect(calendars).toHaveLength(1);
      expect(calendars[0].name).toBe('Work');
    });
  });

  it('still reads a bare array, so a stale bundle degrades to the old shape', () => {
    respondWith([{id: '/dav/cal/john/work/', name: 'Work'}]);
    return caldavConnector.listCalendars().then(calendars => {
      expect(calendars).toHaveLength(1);
    });
  });
});
