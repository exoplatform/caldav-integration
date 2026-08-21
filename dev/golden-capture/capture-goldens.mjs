#!/usr/bin/env node
/*
 * THROWAWAY CAPTURE DRIVER — EXO-89521 (golden-file harness, plan §7).
 *
 * Drives the CURRENT browser connector (caldav-webapp/.../caldavConnector.js,
 * unmodified) against the local containerised Stalwart rig, and records what
 * it writes (the .ics objects as the server stores them, plus the exact PUT
 * bodies it sent) and what it reads (the event JSON retrieveEvents produces).
 * The recordings land in caldav-services/src/test/resources/caldav/golden/,
 * which is what PR3's ICS engine is judged against.
 *
 * This file ships in NO artifact: dev/ belongs to no Maven module, so neither
 * the caldav-services JAR, nor the caldav-webapp WAR, nor the packaging ZIP
 * ever contains it. It exists so the capture is reproducible and reviewable.
 *
 * Prerequisites:
 *   - the Stalwart dev rig answering on http://localhost:8090/dav/
 *   - `npm ci` run once in caldav-webapp (jsdom comes with the dev deps)
 *
 * Run from the repository root:
 *   TZ=Europe/Paris node dev/golden-capture/capture-goldens.mjs
 *
 * Overrides: CALDAV_RIG_URL, CALDAV_RIG_USER, CALDAV_RIG_PASSWORD.
 * TZ is pinned to Europe/Paris in the invocation because the connector's
 * USER_TIMEZONE_ID fallback and ical.js's floating-time reads depend on the
 * runtime zone; the fixtures set explicit zones, but the r04 read golden
 * deliberately pins the floating-time behaviour and needs a stable runtime.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..');
const WEBAPP = path.join(REPO, 'caldav-webapp');
const GOLDEN = path.join(REPO, 'caldav-services', 'src', 'test', 'resources', 'caldav', 'golden');
const TRANSCRIPTS = path.join(REPO, 'caldav-services', 'src', 'test', 'resources', 'caldav', 'transcripts');
const CONNECTOR_COMMIT = 'cd78b8b';

const RIG_URL = process.env.CALDAV_RIG_URL || 'http://localhost:8090/dav/cal/{username}/';
const RIG_USER = process.env.CALDAV_RIG_USER || 'alice@stalwart.local';
const RIG_PASSWORD = process.env.CALDAV_RIG_PASSWORD || 'AlicePass123!';
const AUTH = `Basic ${Buffer.from(`${RIG_USER}:${RIG_PASSWORD}`).toString('base64')}`;
const HOME = RIG_URL.replace('{username}', RIG_USER);
const READ_WINDOW_START = '2026-10-01T00:00:00';
const READ_WINDOW_END = '2026-11-30T23:59:59';

// ---------------------------------------------------------------------------
// Browser environment the connector expects, built before importing it.
// ---------------------------------------------------------------------------
const require = createRequire(path.join(WEBAPP, 'package.json'));
const { JSDOM } = require('jsdom');
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://exo.example.test/' });
global.window = dom.window;
global.document = dom.window.document;
global.eXo = dom.window.eXo = {
  env: { portal: { context: '/portal', portalName: 'dw', rest: 'rest', userIdentityId: '5' } },
};
// jsdom's fetch is not wired to the network; keep Node's own undici fetch,
// but wrap it so every PUT body the connector sends is recorded next to what
// the server ends up storing.
const nativeFetch = globalThis.fetch;
const sentBodies = new Map();
globalThis.fetch = dom.window.fetch = (input, init) => {
  const url = typeof input === 'string' ? input : input.url;
  const method = (init && init.method || (typeof input === 'object' && input.method) || 'GET').toUpperCase();
  if (method === 'PUT' && init && typeof init.body === 'string') {
    sentBodies.set(decodeURIComponent(new URL(url).pathname), init.body);
  }
  return nativeFetch(input, init);
};

const connector = (await import(
  path.join(WEBAPP, 'src', 'main', 'webapp', 'vue-app', 'caldav', 'caldav-connector', 'caldavConnector.js')
)).default;

// ---------------------------------------------------------------------------
// Raw DAV helpers (rig plumbing only; everything under test goes through the
// connector itself).
// ---------------------------------------------------------------------------
/** One raw authenticated DAV request against the rig. */
async function dav(method, url, headers = {}, body = undefined) {
  const response = await nativeFetch(url, { method, headers: { Authorization: AUTH, ...headers }, body });
  return response;
}

/** Creates a scratch calendar collection and returns its URL. */
async function mkScratch(name, displayName) {
  const url = new URL(`${name}/`, HOME).href;
  const response = await dav('MKCALENDAR', url, { 'Content-Type': 'application/xml; charset=utf-8' },
    '<?xml version="1.0" encoding="utf-8" ?>'
    + '<C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">'
    + `<D:set><D:prop><D:displayname>${displayName}</D:displayname></D:prop></D:set>`
    + '</C:mkcalendar>');
  if (response.status >= 300) {
    throw new Error(`MKCALENDAR ${url} answered ${response.status}`);
  }
  return url;
}

/** GETs one stored calendar object, returning its text and ETag. */
async function getObject(collectionUrl, filename) {
  const url = new URL(filename, collectionUrl).href;
  const response = await dav('GET', url);
  if (!response.ok) {
    throw new Error(`GET ${url} answered ${response.status}`);
  }
  return { url, etag: response.headers.get('etag'), ics: await response.text() };
}

/** The decoded, slash-trimmed path of a URL — the connector's own identity rule. */
function pathOf(url) {
  return decodeURIComponent(new URL(url).pathname).replace(/\/+$/, '');
}

/** Writes a JSON file with a stable layout. */
function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n');
  console.log('wrote', path.relative(REPO, file));
}

/** Strips driver-only keys from a fixture event before handing it to the connector. */
function cloneEvent(event) {
  return structuredClone(event);
}

/** Common provenance block for every captured artifact of this run. */
function provenance(extra) {
  return {
    kind: 'captured',
    capturedFrom: `caldavConnector.js @ ${CONNECTOR_COMMIT}, driven unmodified through dev/golden-capture/capture-goldens.mjs`,
    server: 'Stalwart v0.16 dev rig (docker stalwart-dev behind caldav-test-proxy nginx), http://localhost:8090/dav/',
    capturedOn: new Date().toISOString().slice(0, 10),
    runtimeZone: process.env.TZ || Intl.DateTimeFormat().resolvedOptions().timeZone,
    ...extra,
  };
}

// ---------------------------------------------------------------------------
// Write-path capture.
// ---------------------------------------------------------------------------
const stamp = Date.now().toString(36);
const writeCol = await mkScratch(`golden-write-${stamp}`, 'Golden write capture');
const readCol = await mkScratch(`golden-read-${stamp}`, 'Golden read capture');
console.log('scratch collections:', writeCol, readCol);

const settings = {
  username: RIG_USER,
  password: RIG_PASSWORD,
  caldavUrl: RIG_URL,
  mirrorCalendarHref: writeCol,
};

const fixtureFiles = fs.readdirSync(path.join(GOLDEN, 'events')).filter(f => f.endsWith('.json')).sort();
for (const file of fixtureFiles) {
  const fixture = JSON.parse(fs.readFileSync(path.join(GOLDEN, 'events', file), 'utf8'));
  const uid = fixture.event.remoteId;
  const filename = `${uid}.ics`;
  if (fixture.seedObjectFile) {
    const seed = fs.readFileSync(path.join(GOLDEN, 'events', fixture.seedObjectFile), 'utf8');
    const put = await dav('PUT', new URL(filename, writeCol).href,
      { 'Content-Type': 'text/calendar; charset=utf-8' }, seed);
    if (put.status >= 300) {
      throw new Error(`seeding ${filename} answered ${put.status}`);
    }
    console.log(`seeded ${filename} (${put.status})`);
  }
  await connector.saveEvent(cloneEvent(fixture.event), settings);
  const stored = await getObject(writeCol, filename);
  writeJson(path.join(GOLDEN, 'write', `${fixture.name}.golden.json`), {
    provenance: provenance({
      input: `caldav/golden/events/${file}`,
      steps: fixture.seedObjectFile
        ? [`raw PUT of ${fixture.seedObjectFile} (reconstructed foreign object)`, 'saveEvent(event)']
        : ['saveEvent(event)'],
      invariant: fixture._provenance.invariant,
    }),
    sent: sentBodies.get(pathOf(stored.url)) || null,
    ics: stored.ics,
  });
  for (const followUp of fixture.followUps || []) {
    if (followUp.action === 'save') {
      await connector.saveEvent(cloneEvent(followUp.event), settings);
    } else if (followUp.action === 'removeOccurrence') {
      await connector.removeEvent(cloneEvent(followUp.event), settings);
    } else {
      throw new Error(`unknown follow-up action ${followUp.action}`);
    }
    const after = await getObject(writeCol, filename);
    writeJson(path.join(GOLDEN, 'write', `${fixture.name}.${followUp.suffix}.golden.json`), {
      provenance: provenance({
        input: `caldav/golden/events/${file}`,
        steps: [`state after follow-up "${followUp.suffix}" (${followUp.action})`],
        invariant: fixture._provenance.invariant,
      }),
      sent: sentBodies.get(pathOf(after.url)) || null,
      ics: after.ics,
    });
  }
}

// ---------------------------------------------------------------------------
// Read-path capture: foreign-shaped objects (plus one round-trip of our own
// pushed object) PUT into a dedicated collection, then retrieveEvents.
// ---------------------------------------------------------------------------
const readObjects = fs.readdirSync(path.join(GOLDEN, 'read', 'objects')).filter(f => f.endsWith('.ics')).sort();
for (const file of readObjects) {
  const ics = fs.readFileSync(path.join(GOLDEN, 'read', 'objects', file), 'utf8');
  const uid = /UID:([^\r\n]+)/.exec(ics)[1];
  const put = await dav('PUT', new URL(`${uid}.ics`, readCol).href,
    { 'Content-Type': 'text/calendar; charset=utf-8' }, ics);
  if (put.status >= 300) {
    throw new Error(`PUT of ${file} answered ${put.status}`);
  }
  console.log(`stored read object ${file} (${put.status})`);
}
// Round trip: the DST-spanning object exactly as the write capture stored it.
const roundTrip = await getObject(writeCol, 'golden-06-dst-span.ics');
await dav('PUT', new URL('r05-roundtrip-dst-span.ics', readCol).href,
  { 'Content-Type': 'text/calendar; charset=utf-8' },
  roundTrip.ics.replace(/UID:golden-06-dst-span/g, 'UID:r05-roundtrip-dst-span'));

const readEvents = await connector.retrieveEvents(settings, READ_WINDOW_START, READ_WINDOW_END);
const readColPath = pathOf(readCol);
const normalized = readEvents
  .filter(event => pathOf(event.calendarId) === readColPath)
  .map(event => ({
    uid: event.uid,
    id: event.id,
    recurringEventId: event.recurringEventId ?? null,
    summary: event.summary ?? null,
    location: event.location ?? null,
    description: event.description ?? null,
    allDay: !!event.allDay,
    start: event.start instanceof Date ? event.start.toISOString() : event.start ?? null,
    end: event.end instanceof Date ? event.end.toISOString() : event.end ?? null,
    type: event.type,
  }))
  .sort((a, b) => (a.uid + a.start).localeCompare(b.uid + b.start));
const byUid = new Map();
for (const event of normalized) {
  const key = event.uid.replace(/@.*$/, '');
  if (!byUid.has(key)) {
    byUid.set(key, []);
  }
  byUid.get(key).push(event);
}
for (const [key, events] of byUid) {
  const source = key === 'r05-roundtrip-dst-span'
    ? 'caldav/golden/write/06-dst-span.golden.json (round trip of the object the write capture stored, UID renamed)'
    : `caldav/golden/read/objects/${readObjects.find(f => f.startsWith(key.slice(0, 3))) || key}`;
  writeJson(path.join(GOLDEN, 'read', `${key}.read-golden.json`), {
    provenance: provenance({
      input: source,
      steps: [
        'raw PUT of the object into a scratch collection',
        `retrieveEvents(settings, '${READ_WINDOW_START}', '${READ_WINDOW_END}') with TZ=${process.env.TZ || 'unset'}`,
        'output filtered to the scratch collection; etag/url/calendarId/color dropped (run- and position-dependent); Date values as ISO instants; sorted by uid+start',
      ],
    }),
    events,
  });
}

// ---------------------------------------------------------------------------
// Stalwart protocol transcripts: sync-collection (tier-1 evidence for the
// plan's §4.2) and the 412 a stale If-Match PUT answers (conflict discipline).
// ---------------------------------------------------------------------------
fs.mkdirSync(TRANSCRIPTS, { recursive: true });
const tokenProbe = await dav('PROPFIND', writeCol, { Depth: '0', 'Content-Type': 'application/xml' },
  '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop><D:sync-token/></D:prop></D:propfind>');
const tokenXml = await tokenProbe.text();
const syncToken = />(urn:[^<]+)</.exec(tokenXml)?.[1];
const syncReport = await dav('REPORT', writeCol, { Depth: '0', 'Content-Type': 'application/xml' },
  '<?xml version="1.0" encoding="utf-8" ?>'
  + '<D:sync-collection xmlns:D="DAV:">'
  + '<D:sync-token/>'
  + '<D:sync-level>1</D:sync-level>'
  + '<D:prop><D:getetag/></D:prop>'
  + '</D:sync-collection>');
const syncBody = await syncReport.text();
fs.writeFileSync(path.join(TRANSCRIPTS, 'stalwart-sync-collection-report.xml'),
  '<?xml version="1.0" encoding="UTF-8"?>\n'
  + '<!--\n'
  + `  CAPTURED live on ${new Date().toISOString().slice(0, 10)} against the Stalwart v0.16 dev rig\n`
  + '  (docker stalwart-dev behind caldav-test-proxy, http://localhost:8090/dav/) by\n'
  + '  dev/golden-capture/capture-goldens.mjs: an RFC 6578 sync-collection REPORT\n'
  + '  with an empty sync-token (initial sync) on a collection freshly populated\n'
  + `  by the golden write capture. HTTP status was ${syncReport.status}; the current\n`
  + `  collection sync-token from PROPFIND was ${syncToken || '(none)'}. Collection\n`
  + '  path and ETags are the server\'s own. Tier-1 evidence for the plan\'s §4.2.\n'
  + '-->\n'
  + syncBody.replace(/^<\?xml[^>]*\?>/, '') + '\n');
console.log('wrote', path.relative(REPO, path.join(TRANSCRIPTS, 'stalwart-sync-collection-report.xml')));

const staleUrl = new URL('golden-01-simple-timed.ics', writeCol).href;
const stale = await dav('PUT', staleUrl,
  { 'Content-Type': 'text/calendar; charset=utf-8', 'If-Match': '"golden-stale-etag"' },
  roundTrip.ics);
const staleBody = await stale.text();
const staleHeaders = [...stale.headers.entries()].map(([k, v]) => `${k}: ${v}`).join('\n');
fs.writeFileSync(path.join(TRANSCRIPTS, 'stalwart-put-if-match-412.http'),
  `# CAPTURED live on ${new Date().toISOString().slice(0, 10)} against the Stalwart v0.16 dev rig\n`
  + '# (http://localhost:8090/dav/) by dev/golden-capture/capture-goldens.mjs: a PUT of a\n'
  + '# valid calendar object with a deliberately stale If-Match ("golden-stale-etag") on an\n'
  + '# existing object. The 412 is the conflict signal the connector\'s ensureAccepted maps\n'
  + '# to caldav.error.conflict, and the signal the server-side engine\'s conditional writes\n'
  + '# (plan §4.3) rely on. Authorization header not recorded.\n'
  + `HTTP/1.1 ${stale.status} ${stale.statusText}\n${staleHeaders}\n\n${staleBody}\n`);
console.log('wrote', path.relative(REPO, path.join(TRANSCRIPTS, 'stalwart-put-if-match-412.http')),
  `(status ${stale.status})`);

// ---------------------------------------------------------------------------
// Cleanup: the scratch collections are deleted so reruns start clean.
// ---------------------------------------------------------------------------
for (const col of [writeCol, readCol]) {
  const gone = await dav('DELETE', col);
  console.log('cleanup DELETE', col, gone.status);
}
console.log('capture complete.');
