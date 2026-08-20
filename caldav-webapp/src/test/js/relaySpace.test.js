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
import * as caldavConnectorService from '../../main/webapp/vue-app/caldav/js/agendaCaldavService.js';
import caldavConnector from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';

/**
 * The relay-space contract of the connector, shipped with the EXO-89522
 * relay. What these tests pin, and why each would be a live defect:
 *
 * - Requests of a registry-resolved account go to the platform's relay
 *   (`/caldav/rest/dav/{serverId}/...`) and carry NO Authorization header —
 *   the whole point of the relay is that the page never holds the password.
 *   A regression here is a browser talking to a CalDAV origin again, which
 *   BlueMind (no CORS headers) answers with an opaque failure.
 * - A stored mirror href predating the relay (rooted at the CalDAV server
 *   itself) keeps designating the same collection as its relay-space
 *   enumeration: otherwise every pre-relay account loses its mirror — the
 *   copies land in the first calendar the account lists — the day the relay
 *   ships.
 * - The id `getMirrorCalendarId` answers lives in the SAME space as the ids
 *   `listCalendars` answers, because agenda compares the two by decoded path
 *   to keep the dedicated mirror off calendar lists.
 */
describe('relay space', () => {

  /** The declared server the account references. */
  const SERVER_ID = 3;

  /** Where the platform relays this account's DAV requests. */
  const RELAY_ROOT = `http://localhost/caldav/rest/dav/${SERVER_ID}`;

  /** Mirror href as accounts connected BEFORE the relay stored it. */
  const LEGACY_MIRROR = 'https://server.test/dav/cal/john/exo-meetings/';

  /** The same collection, as the relay now enumerates it. */
  const RELAY_MIRROR = `${RELAY_ROOT}/dav/cal/john/exo-meetings/`;

  /** Settings as the relay-era GET serves them: serverId, NO password. */
  const SETTINGS = {
    username: 'john',
    caldavUrl: 'https://server.test/dav/cal/{username}/',
    mirrorCalendarHref: LEGACY_MIRROR,
    serverId: SERVER_ID,
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('probes and discovers through the relay, with no Authorization header', async () => {
    global.fetch = jest.fn(() => Promise.resolve({status: 207, ok: true}));
    tsdav.DAVClient.mockImplementation(() => ({
      login: jest.fn(() => Promise.resolve()),
      fetchCalendars: jest.fn(() => Promise.resolve([])),
    }));
    caldavConnectorService.getCaldavSetting.mockResolvedValue(SETTINGS);

    await caldavConnector.listCalendars();

    const [probeUrl, probeOptions] = global.fetch.mock.calls[0];
    expect(probeUrl).toBe(`${RELAY_ROOT}/dav/cal/john/`);
    expect(probeOptions.credentials).toBe('include');
    expect(Object.keys(probeOptions.headers).map(name => name.toLowerCase())).not.toContain('authorization');

    const clientParams = tsdav.DAVClient.mock.calls[0][0];
    expect(clientParams.serverUrl).toBe(`${RELAY_ROOT}/dav/cal/john/`);
    expect(clientParams.authMethod).toBe('Custom');
    await expect(clientParams.authFunction()).resolves.toEqual({});
  });

  it('keeps addressing the server directly when no declared server resolves', async () => {
    // The pre-registry deployment: no serverId, the password still served.
    global.fetch = jest.fn(() => Promise.resolve({status: 207, ok: true}));
    tsdav.DAVClient.mockImplementation(() => ({
      login: jest.fn(() => Promise.resolve()),
      fetchCalendars: jest.fn(() => Promise.resolve([])),
    }));
    caldavConnectorService.getCaldavSetting.mockResolvedValue({
      username: 'john',
      password: 'secret',
      caldavUrl: 'https://server.test/dav/cal/{username}/',
    });

    await caldavConnector.listCalendars();

    const [probeUrl, probeOptions] = global.fetch.mock.calls[0];
    expect(probeUrl).toBe('https://server.test/dav/cal/john/');
    expect(probeOptions.headers.Authorization).toMatch(/^Basic /);
  });

  it('matches a pre-relay stored mirror href against its relay-space enumeration', async () => {
    const listed = {url: RELAY_MIRROR, displayName: 'eXo Meetings'};
    const client = {fetchCalendars: jest.fn(() => Promise.resolve([listed]))};

    const destination = await caldavConnector.getDestinationCalendar(client, SETTINGS);

    expect(destination).toBe(listed);
  });

  it('folds an unlisted stored href into relay space rather than targeting the CalDAV origin', async () => {
    const client = {fetchCalendars: jest.fn(() => Promise.resolve([]))};

    const destination = await caldavConnector.getDestinationCalendar(client, SETTINGS);

    expect(destination.url).toBe(RELAY_MIRROR);
  });

  it('answers getMirrorCalendarId in the same space as the calendar ids', async () => {
    caldavConnectorService.getCaldavSetting.mockResolvedValue(SETTINGS);

    await expect(caldavConnector.getMirrorCalendarId()).resolves.toBe(RELAY_MIRROR);
  });

  it('still recognises the dedicated mirror through its relay-space href', () => {
    expect(caldavConnector.isDedicatedMirrorCalendar(RELAY_MIRROR)).toBe(true);
    expect(caldavConnector.isDedicatedMirrorCalendar(`${RELAY_ROOT}/dav/cal/john/personal/`)).toBe(false);
  });
});
