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
 * The mirror calendar must be created under the calendar home the server
 * DISCLOSED, never at the configured URL — and these tests run the real
 * tsdav to prove it, because the defect they pin was invisible to any test
 * that mocks tsdav.
 *
 * The defect: the connector opened its client through tsdav.createDAVClient,
 * which runs account discovery but keeps the account in a closure — the
 * object it returns has NO `account` property. `clientCaldav.account &&
 * clientCaldav.account.homeUrl`, the derivation's primary branch, was
 * therefore dead code on every server, and the mocked-tsdav tests kept
 * passing because their client stub invented the very property the real
 * library never exposes. With the discovered home unreachable, an account
 * listing no calendar fell through to the configured server URL — the DAV
 * root, on servers registered by their root — where an MKCALENDAR does not
 * answer a refusal: against BlueMind it answers 504 from the gateway
 * (captured live 2026-08-20), which the connector then reported as "your
 * calendar server does not allow creating a calendar" although the same
 * MKCALENDAR under the calendar home answers 201 Created (same capture).
 *
 * The fixtures in ./fixtures are those live captures, taken by
 * dev/golden-capture/capture-bluemind.sh (credentials scrubbed). The fetch
 * stub below replays them behind an emulation of the local dev proxy
 * (nginx `location /bluemind/` with its `sub_filter '>/dav/' '>/bluemind/'`
 * href rewrite, `/.well-known/caldav` 307-redirecting to ANOTHER DAV
 * server's root, as measured live on the proxy) — so the whole discovery
 * chain tsdav really performs is exercised, not a shortcut of it.
 *
 * Negative controls (each verified by reintroducing the break):
 * - reverting createClient to tsdav.createDAVClient fails
 *   'creates the mirror under the discovered home even when the account
 *   lists no calendar' — the target degrades to the server root and the 504
 *   turns the outcome into a false refusal;
 * - answering 201 to a root-aimed MKCALENDAR instead of 504 does NOT save
 *   these tests when the fix is reverted, because they assert the exact URL
 *   the request was sent to, not just the outcome.
 */
jest.mock('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js', () => ({
  getCaldavSetting: jest.fn(),
  saveMirrorCalendarHref: jest.fn(),
}));

import fs from 'fs';
import path from 'path';

// tsdav checks for a global fetch when it loads, so the trampoline must be
// installed at module scope — before the describe body requires the
// connector (and with it the real tsdav). Each test then swaps the
// implementation the trampoline forwards to.
let currentFetch = () => Promise.reject(new Error('no fetch replay installed'));
global.fetch = (...args) => currentFetch(...args);

const ORIGIN = 'http://localhost:8888';
const UID = '9F3C1A20-4D5E-4B7A-8C61-2E0D7A4B9C13';
const HOME_PATH = `/bluemind/calendars/__uids__/${UID}/`;
const MIRROR_PATH = `${HOME_PATH}exo-meetings/`;

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
 * The href rewrite the dev proxy applies to every BlueMind response body —
 * nginx `sub_filter '>/dav/' '>/bluemind/'` — so the /dav/-rooted hrefs
 * BlueMind reports resolve inside the prefix the proxy serves it under.
 *
 * @param {String} body a raw BlueMind response body
 * @returns {String} the body as the browser receives it through the proxy
 */
function throughProxy(body) {
  return body.split('>/dav/').join('>/bluemind/')
    .split('https://caldav.example.invalid/dav/').join('/bluemind/');
}

/**
 * The depth-1 home listing without its calendar collections — the state of
 * an account holding no calendar at all, which is exactly the state that
 * used to fall through to the configured server URL. Built from the real
 * capture by dropping every response whose resourcetype carries
 * <cal:calendar/>, so inbox, outbox, notification and freebusy remain, as
 * they would on a real account.
 *
 * @param {String} depth1 the full depth-1 home listing body
 * @returns {String} the same listing with no calendar collection in it
 */
function withoutCalendars(depth1) {
  return depth1.replace(/<d:response>(?:(?!<\/d:response>).)*?<cal:calendar\/>(?:(?!<\/d:response>).)*?<\/d:response>/gs, '');
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
 * Installs a fetch stub replaying BlueMind behind the dev proxy and returns
 * the request log plus the mutable server state.
 *
 * @param {String} homeListing the depth-1 home listing to serve
 * @returns {Object} `{requests, state}` — every `METHOD url` seen, and
 *          `state.created` flipping once MKCALENDAR under the home succeeds
 */
function replayBluemindBehindProxy(homeListing) {
  const requests = [];
  const state = {created: false};
  const principal = throughProxy(transcriptBody('bluemind-principal.captured.xml'));
  const homeSet = throughProxy(transcriptBody('bluemind-calendar-home.captured.xml'));
  const mirrorListed = `<d:response><d:href>${MIRROR_PATH}</d:href><d:propstat>`
    + '<d:status>HTTP/1.1 200 OK</d:status><d:prop>'
    + '<d:resourcetype><d:collection/><cal:calendar/></d:resourcetype>'
    + '<d:displayname>eXo Meetings</d:displayname></d:prop></d:propstat></d:response>';
  currentFetch = jest.fn(async (url, init) => {
    const method = init && init.method || 'GET';
    requests.push(`${method} ${url}`);
    const requestPath = new URL(url, ORIGIN).pathname;
    if (requestPath === '/.well-known/caldav') {
      // measured live on the proxy: the origin's well-known belongs to the
      // OTHER DAV server it fronts, and 307-redirects to that server's root
      return davResponse(307, '', {location: '/dav/cal', 'content-type': 'text/plain'});
    }
    if (requestPath.startsWith('/dav/')) {
      // the other server does not know this account
      return davResponse(401, '', {'content-type': 'text/plain'});
    }
    if (method === 'PROPFIND' && requestPath === '/bluemind/') {
      return davResponse(207, principal);
    }
    if (method === 'PROPFIND' && requestPath === `/bluemind/principals/__uids__/${UID}/`) {
      return davResponse(207, homeSet);
    }
    if (method === 'PROPFIND' && requestPath === HOME_PATH) {
      const listing = state.created
        ? homeListing.replace('</d:multistatus>', `${mirrorListed}</d:multistatus>`)
        : homeListing;
      return davResponse(207, listing);
    }
    if (method === 'PROPFIND' && requestPath.startsWith(HOME_PATH)) {
      // per-collection PROPFIND (supported-report-set): serve the matching
      // response element of the home listing as its own multistatus
      const escaped = decodeURI(requestPath).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const listing = state.created
        ? homeListing.replace('</d:multistatus>', `${mirrorListed}</d:multistatus>`)
        : homeListing;
      const single = listing.match(new RegExp(`<d:response><d:href>${escaped}</d:href>.*?</d:response>`, 's'));
      return single
        ? davResponse(207, '<?xml version="1.0" encoding="UTF-8"?><d:multistatus xmlns:d="DAV:" '
            + `xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cso="http://calendarserver.org/ns/">${single[0]}</d:multistatus>`)
        : davResponse(404, '', {'content-type': 'text/plain'});
    }
    if (method === 'MKCALENDAR') {
      if (requestPath === MIRROR_PATH) {
        // captured live: MKCALENDAR under the calendar home answers 201
        state.created = true;
        return davResponse(201, '', {'content-type': 'text/plain'});
      }
      // captured live: MKCALENDAR outside the home — at the DAV root — is
      // answered by the gateway with 504, not by the server with a refusal
      return davResponse(504, '<html>gateway timeout</html>', {'content-type': 'text/html'});
    }
    return davResponse(404, '', {'content-type': 'text/plain'});
  });
  return {requests, state};
}

describe('the mirror calendar targets the calendar home the server disclosed', () => {
  // required AFTER the fetch stub exists: tsdav resolves its fetch at call
  // time from the global, and the connector must load the real tsdav here —
  // mocking it is what hid this defect from every other suite
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
    caldavConnectorService.saveMirrorCalendarHref.mockResolvedValue(200);
  });

  it('creates the mirror under the discovered home when the account lists calendars', async () => {
    const listing = throughProxy(transcriptBody('bluemind-propfind-home-depth1.captured.xml'));
    const {requests} = replayBluemindBehindProxy(listing);

    await expect(caldavConnector.createCalendar({name: 'eXo Meetings', color: '#3f8487', description: 'copies'}))
      .resolves.toEqual({id: `${ORIGIN}${MIRROR_PATH}`});

    expect(requests).toContain(`MKCALENDAR ${ORIGIN}${MIRROR_PATH}`);
    expect(requests.filter(r => r.startsWith('MKCALENDAR') && !r.includes(MIRROR_PATH))).toEqual([]);
    expect(caldavConnectorService.saveMirrorCalendarHref).toHaveBeenCalledWith(`${ORIGIN}${MIRROR_PATH}`);
  });

  it('creates the mirror under the discovered home even when the account lists no calendar', async () => {
    // the state that used to fall through to the configured server URL: with
    // the discovered account unreachable and no calendar to derive a parent
    // from, MKCALENDAR went to the DAV root and its 504 was reported as the
    // server refusing calendar creation
    const listing = withoutCalendars(throughProxy(transcriptBody('bluemind-propfind-home-depth1.captured.xml')));
    const {requests} = replayBluemindBehindProxy(listing);

    await expect(caldavConnector.createCalendar({name: 'eXo Meetings', color: '#3f8487', description: 'copies'}))
      .resolves.toEqual({id: `${ORIGIN}${MIRROR_PATH}`});

    expect(requests).toContain(`MKCALENDAR ${ORIGIN}${MIRROR_PATH}`);
    expect(requests.filter(r => r.startsWith('MKCALENDAR') && !r.includes(MIRROR_PATH))).toEqual([]);
  });

  // The account exposed by login() mattered because listCalendars read it in
  // the page. Listing moved to the server with EXO-89527; the two tests above
  // still cover the mirror creation that remains browser-side, and the home
  // derivation is exercised there.
});
