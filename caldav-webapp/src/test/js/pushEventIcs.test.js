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

/**
 * Two guarantees of the push path, pinned after a BlueMind 500 that could not
 * be diagnosed.
 *
 * The ICS a push produces carries the scheduling identities truthfully:
 * ORGANIZER is the eXo event's organizer — the connected account only when
 * the user genuinely created the event, never when they merely accepted a
 * meeting somebody else called — and nothing is written at all when the
 * organizer's address is not available, because a CAL-ADDRESS must not be
 * invented.
 *
 * And a refused write names itself: the status and body the server answered
 * with are logged and carried on the thrown error, instead of being
 * discarded — which is what left that 500 blind.
 */
jest.mock('tsdav', () => ({
  DAVClient: jest.fn(),
  DAVNamespaceShort: {
    DAV: 'd',
    CALDAV: 'c',
    CALDAV_APPLE: 'ca',
  },
}));
jest.mock('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js', () => ({
  getCaldavSetting: jest.fn(),
  saveMirrorCalendarHref: jest.fn(),
  USER_TIMEZONE_ID: 'Europe/Paris',
}));

import * as tsdav from 'tsdav';
import ICAL from 'ical.js';
import caldavConnector from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';

const MIRROR_URL = 'https://server.test/dav/cal/john/exo-meetings/';
const SETTINGS = {
  username: 'john',
  password: 'secret',
  caldavUrl: 'https://server.test/dav/cal/{username}/',
  mirrorCalendarHref: MIRROR_URL,
};
const CURRENT_USER_IDENTITY_ID = '5';

/**
 * Installs a tsdav DAVClient constructor mock resolving to the given client
 * stub, with the login() the connector awaits before any request.
 *
 * @param {Object} clientStub the tsdav-client methods a test scripts
 * @returns {Object} the full client stub, for call assertions
 */
function mockConnectedClient(clientStub) {
  const client = {login: jest.fn(() => Promise.resolve()), ...clientStub};
  tsdav.DAVClient.mockImplementation(() => client);
  return client;
}


/** The user connected to eXo, as agenda's identity entities spell them. */
const ME = {id: CURRENT_USER_IDENTITY_ID, profile: {fullname: 'John Doe', email: 'john@example.test'}};

/** Somebody else, who called the meetings the user accepted. */
const ALICE = {id: '9', profile: {fullname: 'Alice Martin', email: 'alice@example.test'}};

/**
 * One plain timed event, from a given organizer. Non-recurring on purpose:
 * these tests are about the identities and the error path, not the zones.
 *
 * @param {Object} creator identity entity of the eXo organizer
 * @param {Array} attendees attendee entities, identity plus response
 * @returns {Object} the agenda event as the connector receives it
 */
function eventBy(creator, attendees) {
  return {
    id: 47,
    remoteId: 'uid-fixed-1',
    summary: 'test exo to BM',
    start: '2026-08-21T11:30:00.000Z',
    end: '2026-08-21T13:30:00.000Z',
    allDay: false,
    timeZoneId: 'Europe/Paris',
    creator,
    attendees,
  };
}

/**
 * Wires the tsdav mock around one scripted outcome of the write, with no
 * object existing remotely yet (the GET answers 404), and hands back the
 * capture of what was PUT.
 *
 * @param {Object} writeResponse what createCalendarObject resolves with
 * @returns {Object} `{created}` where created collects the PUT arguments
 */
function stubPush(writeResponse) {
  const created = [];
  mockConnectedClient({
    fetchCalendars: jest.fn(() => Promise.resolve([{url: MIRROR_URL}])),
    createCalendarObject: jest.fn(args => {
      created.push(args);
      return Promise.resolve(writeResponse);
    }),
  });
  global.fetch = jest.fn(() => Promise.resolve({status: 404, ok: false}));
  return {created};
}

/**
 * The one VEVENT of a pushed calendar object, parsed back, so assertions
 * read properties rather than match strings ical.js is free to re-fold.
 *
 * @param {String} iCalString the object as it left the connector
 * @returns {Object} the VEVENT as an ICAL.Component
 */
function veventOf(iCalString) {
  return new ICAL.Component(ICAL.parse(iCalString)).getFirstSubcomponent('vevent');
}

describe('the identities a pushed event carries', () => {
  beforeEach(() => {
    jest.resetAllMocks();
    window.eXo = {env: {portal: {userIdentityId: CURRENT_USER_IDENTITY_ID, portalName: 'dw', context: '/portal'}}};
  });

  it('names the user as ORGANIZER on an event they created', async () => {
    const {created} = stubPush({ok: true, status: 201});
    await caldavConnector.saveEvent(eventBy(ME, [{identity: ME, response: 'ACCEPTED'}]), SETTINGS);

    expect(created).toHaveLength(1);
    const vevent = veventOf(created[0].iCalString);
    const organizer = vevent.getFirstProperty('organizer');
    expect(organizer).toBeTruthy();
    expect(organizer.getFirstValue()).toBe('mailto:john@example.test');
    expect(organizer.getParameter('cn')).toBe('John Doe');
    // the pusher IS the organizer: this is their own copy, no scheduling
    // agent needs silencing towards themselves
    expect(organizer.getParameter('schedule-agent')).toBeUndefined();
  });

  it('names the true organizer, not the connected account, on an accepted meeting', async () => {
    const {created} = stubPush({ok: true, status: 201});
    const meeting = eventBy(ALICE, [
      {identity: ALICE, response: 'ACCEPTED'},
      {identity: ME, response: 'ACCEPTED'},
    ]);
    await caldavConnector.saveEvent(meeting, SETTINGS);

    const vevent = veventOf(created[0].iCalString);
    const organizer = vevent.getFirstProperty('organizer');
    expect(organizer.getFirstValue()).toBe('mailto:alice@example.test');
    expect(organizer.getFirstValue()).not.toContain('john@');
    // an attendee's copy: the server must not send scheduling messages to
    // the organizer on the user's behalf
    expect(organizer.getParameter('schedule-agent')).toBe('CLIENT');
    const attendees = vevent.getAllProperties('attendee');
    const addresses = attendees.map(attendee => attendee.getFirstValue());
    expect(addresses).toContain('mailto:john@example.test');
    attendees.forEach(attendee => {
      expect(attendee.getParameter('partstat')).toBe('ACCEPTED');
      expect(attendee.getParameter('schedule-agent')).toBe('CLIENT');
    });
  });

  it('invents no address: a hidden organizer email omits ORGANIZER and ATTENDEE alike', async () => {
    const {created} = stubPush({ok: true, status: 201});
    const hiddenOrganizer = {id: '9', profile: {fullname: 'Alice Martin'}};
    const meeting = eventBy(hiddenOrganizer, [{identity: ME, response: 'ACCEPTED'}]);
    await caldavConnector.saveEvent(meeting, SETTINGS);

    const vevent = veventOf(created[0].iCalString);
    expect(vevent.getFirstProperty('organizer')).toBeNull();
    // ATTENDEE is only defined in group-scheduled components, which
    // ORGANIZER is what marks — without it the attendees stay off too
    expect(vevent.getFirstProperty('attendee')).toBeNull();
    // and the copy still carries its identity: dedup keys on the UID
    expect(vevent.getFirstPropertyValue('uid')).toBe('uid-fixed-1');
  });
});

describe('a refused write names itself', () => {
  beforeEach(() => {
    jest.resetAllMocks();
    window.eXo = {env: {portal: {userIdentityId: CURRENT_USER_IDENTITY_ID, portalName: 'dw', context: '/portal'}}};
  });

  it('logs and carries the status and body of a failed PUT', async () => {
    const consoleError = jest.spyOn(console, 'error').mockImplementation(() => null);
    try {
      stubPush({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
        url: `${MIRROR_URL}uid-fixed-1.ics`,
        text: () => Promise.resolve('BlueMind: organizer is mandatory'),
      });
      let thrown;
      await caldavConnector.saveEvent(eventBy(ME, []), SETTINGS).catch(e => thrown = e);

      expect(thrown).toBeTruthy();
      expect(thrown.code).toBe('caldav.error.save');
      expect(thrown.status).toBe(500);
      expect(thrown.body).toBe('BlueMind: organizer is mandatory');
      const logged = consoleError.mock.calls.flat();
      expect(logged).toContain(500);
      expect(logged).toContain('BlueMind: organizer is mandatory');
      // the log must never carry what could authenticate a replay
      expect(JSON.stringify(logged)).not.toContain('secret');
      expect(JSON.stringify(logged).toLowerCase()).not.toContain('authorization');
    } finally {
      consoleError.mockRestore();
    }
  });

  it('logs and carries the status and body of a failed DELETE', async () => {
    const consoleError = jest.spyOn(console, 'error').mockImplementation(() => null);
    try {
      mockConnectedClient({
        fetchCalendars: jest.fn(() => Promise.resolve([{url: MIRROR_URL}])),
        deleteCalendarObject: jest.fn(() => Promise.resolve({
          ok: false,
          status: 500,
          statusText: 'Internal Server Error',
          url: `${MIRROR_URL}uid-fixed-1.ics`,
          text: () => Promise.resolve('BlueMind refused the deletion'),
        })),
      });
      global.fetch = jest.fn(() => Promise.resolve({
        ok: true,
        status: 200,
        headers: {get: () => '"etag-1"'},
        text: () => Promise.resolve('BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n'),
      }));
      let thrown;
      await caldavConnector.removeEvent({remoteId: 'uid-fixed-1'}, SETTINGS).catch(e => thrown = e);

      expect(thrown).toBeTruthy();
      expect(thrown.code).toBe('caldav.error.save');
      expect(thrown.status).toBe(500);
      expect(thrown.body).toBe('BlueMind refused the deletion');
      const logged = consoleError.mock.calls.flat();
      expect(logged).toContain(500);
      expect(logged).toContain('BlueMind refused the deletion');
    } finally {
      consoleError.mockRestore();
    }
  });
});

describe('the existence probe that runs before a push', () => {
  beforeEach(() => {
    jest.resetAllMocks();
    window.eXo = {env: {portal: {userIdentityId: CURRENT_USER_IDENTITY_ID, portalName: 'dw', context: '/portal'}}};
  });

  it('creates the event when the server errors instead of answering 404', async () => {
    const {created} = stubPush({ok: true, status: 201});
    // BlueMind answers 500, not 404, for an .ics that is simply not there.
    global.fetch = jest.fn(() => Promise.resolve({
      status: 500,
      ok: false,
      statusText: 'Internal Server Error',
      text: () => Promise.resolve('java.lang.NullPointerException'),
    }));
    const consoleWarn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      const result = await caldavConnector.saveEvent(eventBy(ME, [{identity: ME, response: 'ACCEPTED'}]), SETTINGS);

      expect(created).toHaveLength(1);
      expect(result).toBeTruthy();
      expect(consoleWarn.mock.calls.flat()).toContain(500);
    } finally {
      consoleWarn.mockRestore();
    }
  });

  it('still refuses the push when the probe is rejected for permission', async () => {
    const {created} = stubPush({ok: true, status: 201});
    global.fetch = jest.fn(() => Promise.resolve({
      status: 403,
      ok: false,
      statusText: 'Forbidden',
      text: () => Promise.resolve('not your calendar'),
    }));
    const consoleError = jest.spyOn(console, 'error').mockImplementation(() => {});
    try {
      let thrown;
      await caldavConnector.saveEvent(eventBy(ME, [{identity: ME, response: 'ACCEPTED'}]), SETTINGS)
        .catch(e => thrown = e);

      expect(thrown).toBeTruthy();
      expect(thrown.status).toBe(403);
      expect(created).toHaveLength(0);
    } finally {
      consoleError.mockRestore();
    }
  });
});
