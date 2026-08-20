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
 * The read window handed to the CalDAV server must be complete before any
 * request goes out — and these tests run the REAL tsdav, because the defect
 * they pin lived in the connector→tsdav contract and a mocked tsdav
 * performs no validation to fail.
 *
 * The defect: the agenda's first remote read can fire from its connector
 * watcher before the calendar has emitted a period. Agenda.vue starts with
 * period {start: new Date(), end: null}, the event-form calendar with {},
 * and toRFC3339(null) answers null — so retrieveEvents built
 * timeRange {start: <valid>, end: null}, which tsdav's validateTimeRange
 * rejects with "invalid timeRange format, not in ISO8601" once per
 * collection, and the agenda displayed no remote event at all. Observed
 * live against BlueMind through the relay on 2026-08-20 — the same three
 * collections as in ./fixtures — and latent long before: BlueMind never
 * got past the connection until the relay existed, and on servers that did
 * connect the failure only struck when the connector won the race against
 * the calendar's first period event, leaving nothing but a console line.
 *
 * Negative controls (each verified by reintroducing the break):
 * - passing the raw toRFC3339 values through again (reverting the
 *   timeRangeBound normalisation) fails 'reads from the start onwards when
 *   the period has no end yet' — every collection read is rejected before
 *   any REPORT is issued;
 * - removing the no-window guard fails 'reads nothing at all without a
 *   usable start' — the connector would query the server about a window it
 *   cannot name.
 */
jest.mock('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js', () => {
  const actual = jest.requireActual('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js');
  return {...actual, getCaldavSetting: jest.fn(), saveMirrorCalendarHref: jest.fn()};
});

import fs from 'fs';
import path from 'path';

// tsdav checks for a global fetch when it loads, so the trampoline must be
// installed at module scope — before the describe body requires the
// connector (and with it the real tsdav).
let currentFetch = () => Promise.reject(new Error('no fetch replay installed'));
global.fetch = (...args) => currentFetch(...args);

const ORIGIN = 'http://localhost:8888';
const UID = '9F3C1A20-4D5E-4B7A-8C61-2E0D7A4B9C13';
const HOME_PATH = `/bluemind/calendars/__uids__/${UID}/`;

/**
 * The body of one captured transcript: everything after the header/blank
 * line the capture script writes before it.
 *
 * @param {String} name transcript file name under ./fixtures
 * @returns {String} the captured response body
 */
function transcriptBody(name) {
  return fs.readFileSync(path.join(__dirname, 'fixtures', name), 'utf8').split('\n\n').slice(1).join('\n\n');
}

/**
 * The href rewrite the dev proxy applies to every BlueMind response body,
 * mirroring its nginx sub_filter configuration.
 *
 * @param {String} body a raw BlueMind response body
 * @returns {String} the body as the browser receives it through the proxy
 */
function throughProxy(body) {
  return body.split('>/dav/').join('>/bluemind/')
    .split('https://caldav.example.invalid/dav/').join('/bluemind/');
}

/**
 * A minimal fetch Response for the stub server.
 *
 * @param {Number} status HTTP status
 * @param {String} body response body
 * @param {Object} headers response headers, lower-cased names
 * @returns {Object} the Response-shaped object tsdav consumes
 */
function davResponse(status, body, headers = {}) {
  const all = {'content-type': 'application/xml; charset="utf-8"', ...headers};
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: String(status),
    url: '',
    headers: {get: name => all[name.toLowerCase()] ?? null},
    text: () => Promise.resolve(body || ''),
  };
}

/**
 * Installs a fetch stub replaying BlueMind behind the dev proxy — discovery,
 * listing, and empty calendar-query REPORTs — and returns the request log.
 *
 * @returns {Array} every `METHOD url` the connector sent
 */
function replayBluemindBehindProxy() {
  const requests = [];
  const principal = throughProxy(transcriptBody('bluemind-principal.captured.xml'));
  const homeSet = throughProxy(transcriptBody('bluemind-calendar-home.captured.xml'));
  const depth1 = throughProxy(transcriptBody('bluemind-propfind-home-depth1.captured.xml'));
  currentFetch = jest.fn(async (url, init) => {
    const method = init && init.method || 'GET';
    requests.push(`${method} ${url}`);
    const requestPath = new URL(url, ORIGIN).pathname;
    if (requestPath === '/.well-known/caldav') {
      return davResponse(307, '', {location: '/dav/cal', 'content-type': 'text/plain'});
    }
    if (requestPath.startsWith('/dav/')) {
      return davResponse(401, '', {'content-type': 'text/plain'});
    }
    if (method === 'PROPFIND' && requestPath === '/bluemind/') {
      return davResponse(207, principal);
    }
    if (method === 'PROPFIND' && requestPath === `/bluemind/principals/__uids__/${UID}/`) {
      return davResponse(207, homeSet);
    }
    if (method === 'PROPFIND' && requestPath === HOME_PATH) {
      return davResponse(207, depth1);
    }
    if (method === 'PROPFIND' && requestPath.startsWith(HOME_PATH)) {
      const escaped = decodeURI(requestPath).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const single = depth1.match(new RegExp(`<d:response><d:href>${escaped}</d:href>.*?</d:response>`, 's'));
      return single
        ? davResponse(207, '<?xml version="1.0" encoding="UTF-8"?><d:multistatus xmlns:d="DAV:" '
            + `xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cso="http://calendarserver.org/ns/">${single[0]}</d:multistatus>`)
        : davResponse(404, '', {'content-type': 'text/plain'});
    }
    if (method === 'REPORT') {
      return davResponse(207, '<?xml version="1.0" encoding="UTF-8"?><d:multistatus xmlns:d="DAV:"></d:multistatus>');
    }
    return davResponse(404, '', {'content-type': 'text/plain'});
  });
  return requests;
}

describe('the read window sent to the server is always complete', () => {
  const caldavConnectorService = require('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js');
  const caldavConnector = require('../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js').default;

  beforeEach(() => {
    jest.clearAllMocks();
    caldavConnectorService.getCaldavSetting.mockResolvedValue({
      username: 'user@demo3.livecollab.fr',
      password: 'secret',
      caldavUrl: `${ORIGIN}/bluemind/`,
      mirrorCalendarHref: null,
    });
  });

  it('reads from the start onwards when the period has no end yet', async () => {
    // Agenda.vue's initial period is {start: new Date(), end: null}, and its
    // connector watcher can fire the first remote read in that state
    const requests = replayBluemindBehindProxy();
    const consoleError = jest.spyOn(console, 'error').mockImplementation(() => null);
    try {
      const start = caldavConnectorService.toRFC3339(new Date('2026-08-17T10:00:00'), false, true);

      await expect(caldavConnector.getEvents(start, null)).resolves.toEqual([]);

      // every collection was genuinely queried — none was rejected by
      // tsdav's time-range validation before the request went out
      expect(requests.filter(request => request.startsWith('REPORT ')).length).toBe(3);
      expect(consoleError).not.toHaveBeenCalled();
    } finally {
      consoleError.mockRestore();
    }
  });

  it('reads nothing at all without a usable start', async () => {
    // the event-form calendar starts with period {} — there is no window to
    // ask any server about, so no request must be made
    const requests = replayBluemindBehindProxy();

    await expect(caldavConnector.getEvents(null, null)).resolves.toEqual([]);

    expect(requests).toEqual([]);
  });

  it('treats a bound that does not parse into a real date as missing', async () => {
    // an Invalid Date formats into "NaN-NaN-NaN…", which is not a window
    // either — sent through, the server-side rejection would be identical
    const requests = replayBluemindBehindProxy();

    await expect(caldavConnector.getEvents(new Date('not a date'), null)).resolves.toEqual([]);

    expect(requests).toEqual([]);
  });
});
