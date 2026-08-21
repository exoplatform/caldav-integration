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
 * Every MKCALENDAR this connector sends must declare
 * {@code supported-calendar-component-set} — and these tests run the REAL
 * tsdav and assert on the XML actually put on the wire, because a mock that
 * accepts any props is exactly what let this class of bug through three
 * times.
 *
 * The defect: the request body carried only the display name (plus the
 * optional colour/description). BlueMind derives the created collection's
 * KIND from the {@code <comp>} elements of the component set; without any,
 * the derivation fails internally, the failure is swallowed, and the server
 * answers 201 anyway — claiming a creation that never happened. Every
 * attempt then fell through to adopting an existing calendar, which read,
 * three times over, as "BlueMind refuses MKCALENDAR". Proven live in the
 * browser on 2026-08-20, through the relay, against the real account:
 * displayname-only body → 201 and the collection ABSENT from the next
 * Depth:1 PROPFIND; the same body plus
 * {@code <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set>}
 * → 201 and the collection listed.
 *
 * The fetch stub below replays that exact behaviour: MKCALENDAR under the
 * home ALWAYS answers 201, but the collection only ever appears in later
 * listings when the request body genuinely nested a caldav-namespace
 * {@code comp} inside a caldav-namespace component set. Serialisation
 * matters as much as intent — a flat string prop value makes xml-js emit
 * the component names as text content instead of {@code <comp>} elements,
 * which BlueMind's parser ignores just the same — so the stub resolves the
 * body's own xmlns declarations rather than trusting any prefix.
 *
 * Negative controls (each verified by reintroducing the break):
 * - dropping the component set from minimalProps in createCalendar fails
 *   both creation tests — the stub never lists the mirror, the connector
 *   falls back to adopting the account's first calendar, and the exact
 *   {id} expectation catches the adopted result (and 'declares the
 *   component set as a nested element' fails on the body itself);
 * - flattening the prop to the string 'VEVENT' fails the same way: 201
 *   still comes back, but the body carries no <comp> element and the stub,
 *   like BlueMind, creates nothing.
 */
jest.mock('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js', () => ({
  getCaldavSetting: jest.fn(),
  saveMirrorCalendarHref: jest.fn(),
}));

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
const MIRROR_PATH = `${HOME_PATH}exo-meetings/`;
const CALDAV_NS = 'urn:ietf:params:xml:ns:caldav';

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
 * Whether an MKCALENDAR body genuinely declares the component set the way
 * BlueMind's namespace-aware parser must find it: a caldav-namespace
 * {@code comp} ELEMENT nested inside a caldav-namespace
 * {@code supported-calendar-component-set}. The prefixes are resolved from
 * the body's own xmlns declarations — a right-looking prefix bound to the
 * wrong namespace, or component names serialised as text content instead of
 * elements, must not pass, because they would not create anything on the
 * real server either.
 *
 * @param {String} body the MKCALENDAR request body as sent
 * @returns {Boolean} true when the body would let BlueMind derive the kind
 */
function declaresComponentSet(body) {
  const declarations = [...body.matchAll(/xmlns(?::([\w-]+))?="([^"]*)"/g)];
  const caldavPrefixes = declarations.filter(([, , uri]) => uri === CALDAV_NS)
    .map(([, prefix]) => prefix || '');
  return caldavPrefixes.some(prefix => {
    const name = prefix ? `${prefix}:` : '';
    const set = body.match(new RegExp(`<${name}supported-calendar-component-set>(.*?)</${name}supported-calendar-component-set>`, 's'));
    return Boolean(set) && caldavPrefixes.some(compPrefix => {
      const compName = compPrefix ? `${compPrefix}:` : '';
      return new RegExp(`<${compName}comp\\s[^>]*name="V[A-Z]+"`).test(set[1]);
    });
  });
}

/**
 * Installs a fetch stub replaying BlueMind behind the dev proxy — with the
 * kind-derivation quirk proven live on 2026-08-20: MKCALENDAR under the
 * home ALWAYS answers 201, but the collection only appears in later
 * listings when the request body declared the component set.
 *
 * @returns {Object} `{requests, bodies, state}` — every `METHOD url` seen,
 *          every MKCALENDAR body as sent, and `state.created` flipping only
 *          on an MKCALENDAR the server would really have honoured
 */
function replayBluemindKindDerivation() {
  const requests = [];
  const bodies = [];
  const state = {created: false};
  const principal = throughProxy(transcriptBody('bluemind-principal.captured.xml'));
  const homeSet = throughProxy(transcriptBody('bluemind-calendar-home.captured.xml'));
  const depth1 = throughProxy(transcriptBody('bluemind-propfind-home-depth1.captured.xml'));
  const mirrorListed = `<d:response><d:href>${MIRROR_PATH}</d:href><d:propstat>`
    + '<d:status>HTTP/1.1 200 OK</d:status><d:prop>'
    + '<d:resourcetype><d:collection/><cal:calendar/></d:resourcetype>'
    + '<d:displayname>eXo Meetings</d:displayname></d:prop></d:propstat></d:response>';
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
      const listing = state.created
        ? depth1.replace('</d:multistatus>', `${mirrorListed}</d:multistatus>`)
        : depth1;
      return davResponse(207, listing);
    }
    if (method === 'PROPFIND' && requestPath.startsWith(HOME_PATH)) {
      const escaped = decodeURI(requestPath).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const listing = state.created
        ? depth1.replace('</d:multistatus>', `${mirrorListed}</d:multistatus>`)
        : depth1;
      const single = listing.match(new RegExp(`<d:response><d:href>${escaped}</d:href>.*?</d:response>`, 's'));
      return single
        ? davResponse(207, '<?xml version="1.0" encoding="UTF-8"?><d:multistatus xmlns:d="DAV:" '
            + `xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cso="http://calendarserver.org/ns/">${single[0]}</d:multistatus>`)
        : davResponse(404, '', {'content-type': 'text/plain'});
    }
    if (method === 'MKCALENDAR' && requestPath === MIRROR_PATH) {
      const body = init && init.body || '';
      bodies.push(body);
      // the quirk itself: 201 EITHER WAY — only the component set decides
      // whether anything was actually created
      state.created = state.created || declaresComponentSet(body);
      return davResponse(201, '', {'content-type': 'text/plain'});
    }
    return davResponse(404, '', {'content-type': 'text/plain'});
  });
  return {requests, bodies, state};
}

describe('MKCALENDAR declares the component set BlueMind needs to actually create', () => {
  // required AFTER the fetch stub exists: tsdav resolves its fetch at call
  // time from the global, and the connector must load the real tsdav here —
  // asserting on a mock's arguments is what hid this bug three times
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

  it('creates the mirror on a server that answers 201 whether or not it created anything', async () => {
    const {bodies} = replayBluemindKindDerivation();

    // toEqual is exact on purpose: without the component set the stub, like
    // BlueMind, lists nothing and the connector would resolve the account's
    // FIRST calendar with the adopted flag instead — the exact wrong outcome
    // this fix removes
    await expect(caldavConnector.createCalendar({name: 'eXo Meetings', color: '#3f8487', description: 'copies'}))
      .resolves.toEqual({id: `${ORIGIN}${MIRROR_PATH}`});

    expect(bodies.length).toBeGreaterThan(0);
    expect(caldavConnectorService.saveMirrorCalendarHref).toHaveBeenCalledWith(`${ORIGIN}${MIRROR_PATH}`);
  });

  it('declares the component set as a nested element on the wire, on every attempt', async () => {
    const {bodies} = replayBluemindKindDerivation();

    await caldavConnector.createCalendar({name: 'eXo Meetings', color: '#3f8487', description: 'copies'});

    // the XML actually sent, not a mock's arguments: a caldav-namespace
    // <comp name="VEVENT"/> ELEMENT nested in the component set — the shape
    // proven to create live, where a flat text value proved not to
    expect(bodies.length).toBeGreaterThan(0);
    for (const body of bodies) {
      expect(declaresComponentSet(body)).toBe(true);
      expect(body).toMatch(/<(\w+):supported-calendar-component-set>\s*<\1:comp name="VEVENT"\/>\s*<\/\1:supported-calendar-component-set>/);
    }
  });

  it('keeps the component set on the displayname-only retry', async () => {
    // the retry that runs when the richer request cannot be confirmed must
    // strip ONLY the optional extras — stripped of the component set too, it
    // would reproduce the silent non-creation on the very attempt meant to
    // recover from it. Forcing the retry here: the first attempt is made
    // unconfirmable by never creating on a body that carries a colour.
    const replay = replayBluemindKindDerivation();
    const honest = declaresComponentSet;
    const gate = body => !body.includes('calendar-color') && honest(body);
    const {bodies} = replay;
    // rewire the stub's creation decision through the gate
    const original = currentFetch;
    currentFetch = async (url, init) => {
      if (init && init.method === 'MKCALENDAR' && init.body && init.body.includes('calendar-color')) {
        bodies.push(init.body);
        return davResponse(201, '', {'content-type': 'text/plain'});
      }
      if (init && init.method === 'MKCALENDAR') {
        replay.state.created = replay.state.created || gate(init.body || '');
        bodies.push(init.body || '');
        return davResponse(201, '', {'content-type': 'text/plain'});
      }
      return original(url, init);
    };

    await expect(caldavConnector.createCalendar({name: 'eXo Meetings', color: '#3f8487', description: 'copies'}))
      .resolves.toEqual({id: `${ORIGIN}${MIRROR_PATH}`});

    expect(bodies.length).toBe(2);
    expect(bodies[1]).not.toContain('calendar-color');
    expect(bodies[1]).not.toContain('calendar-description');
    expect(declaresComponentSet(bodies[1])).toBe(true);
  });
});
