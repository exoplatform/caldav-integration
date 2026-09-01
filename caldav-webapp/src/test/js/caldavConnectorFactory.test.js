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
import caldavConnector, {createCaldavConnector, createLegacyCaldavConnector, serverHost} from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';

/**
 * The contract agenda's left panel holds every connector to. My Calendars
 * renders only for descriptors satisfying ALL of canListCalendars, connected
 * and a listCalendars function — `connected` being computed by agenda from
 * the descriptor's NAME matching the user's connectedRemoteProvider. The
 * factory rewrite silently broke that section once; these tests pin the
 * descriptor half of the contract for every shape the factory produces.
 */
describe('createCaldavConnector', () => {

  const seedServer = {
    id: 5,
    providerName: 'agenda.caldavCalendar',
    name: 'Stalwart',
    description: null,
    serverUrl: 'http://localhost:8888/dav/cal/{username}/',
    active: true,
    icon: null,
    imageUrl: null,
  };

  const declaredServer = {
    id: 6,
    providerName: 'agenda.caldavCalendar.6',
    name: 'Bluemind',
    description: 'Team server',
    serverUrl: 'https://caldav.example.invalid/dav/',
    active: true,
    icon: 'fa-server',
    imageUrl: null,
  };

  /**
   * The left-panel contract, for the seed server a legacy user (stored
   * connectedRemoteProvider = agenda.caldavCalendar, no CaldavServerId)
   * resolves through: the descriptor keeps the calendar-listing capability
   * and the FIXED provider name that legacy connection matches on.
   */
  it('keeps the My Calendars contract on the seed descriptor', () => {
    const descriptor = createCaldavConnector(seedServer, 0);

    expect(descriptor.canListCalendars).toBe(true);
    expect(typeof descriptor.listCalendars).toBe('function');
    expect(typeof descriptor.connect).toBe('function');
    expect(typeof descriptor.getEvents).toBe('function');
    // the name is what agenda matches connectedRemoteProvider against: a
    // legacy account stores exactly this fixed name
    expect(descriptor.name).toBe('agenda.caldavCalendar');
    expect(descriptor.serverId).toBe(5);
    expect(descriptor.serverUrl).toBe(seedServer.serverUrl);
  });

  /**
   * The same contract for a per-server descriptor a per-server user (stored
   * connectedRemoteProvider = agenda.caldavCalendar.<id>, CaldavServerId set)
   * resolves through.
   */
  it('keeps the My Calendars contract on a per-server descriptor', () => {
    const descriptor = createCaldavConnector(declaredServer, 1);

    expect(descriptor.canListCalendars).toBe(true);
    expect(typeof descriptor.listCalendars).toBe('function');
    expect(typeof descriptor.connect).toBe('function');
    expect(descriptor.name).toBe('agenda.caldavCalendar.6');
    expect(descriptor.serverId).toBe(6);
  });

  /**
   * isCaldav is what agenda reads to place a connector's calendars: a CalDAV
   * account's collections are materialised as the user's own, so they belong
   * under My Calendars and the account under "Your calendars", while a remote
   * connector gets its own named section. The flag is declared once on the
   * seed descriptor and reaches the per-server ones only through the
   * factory's Object.assign — so the inheritance, not just the declaration,
   * is what has to hold. A per-server descriptor losing it would scatter a
   * user's own calendars into a remote section.
   */
  it('declares isCaldav on every descriptor shape the factory produces', () => {
    expect(caldavConnector.isCaldav).toBe(true);
    expect(createCaldavConnector(seedServer, 0).isCaldav).toBe(true);
    expect(createCaldavConnector(declaredServer, 1).isCaldav).toBe(true);
  });

  /**
   * The legacy fallback descriptor — registered when the registry answers
   * nothing — carries the same contract itself.
   */
  it('keeps the My Calendars contract on the legacy fallback descriptor', () => {
    expect(caldavConnector.canListCalendars).toBe(true);
    expect(typeof caldavConnector.listCalendars).toBe('function');
    expect(caldavConnector.name).toBe('agenda.caldavCalendar');
  });

  /**
   * Visual identity precedence, as the admin configured it: an uploaded
   * image wins, else the chosen font icon, else the packaged default avatar.
   * `avatar` must stay an image URL in every case — several agenda spots
   * render it straight into an img tag.
   */
  it('resolves the visual identity image over icon over default', () => {
    const withImage = createCaldavConnector(Object.assign({}, declaredServer, {
      imageUrl: '/caldav/rest/servers/6/image?v=1',
    }), 1);
    expect(withImage.avatar).toBe('/caldav/rest/servers/6/image?v=1');
    expect(withImage.imageUrl).toBe('/caldav/rest/servers/6/image?v=1');

    const withIcon = createCaldavConnector(declaredServer, 1);
    expect(withIcon.icon).toBe('fa-server');
    expect(withIcon.imageUrl).toBeNull();
    expect(withIcon.avatar).toBe(caldavConnector.avatar);

    const bare = createCaldavConnector(seedServer, 0);
    expect(bare.icon).toBeNull();
    expect(bare.avatar).toBe(caldavConnector.avatar);
  });

  /**
   * EXO-89900. The managed verdict agenda reads off every descriptor to decide
   * whether to offer connecting and disconnecting at all.
   *
   * What is pinned is that the stamp follows `managedForMe` — the PER-VIEWER
   * half of the platform's answer — and not `serverId`, which is what the
   * INSTANCE decided and is the same for everybody. The two cannot disagree
   * today; group exclusions are exactly what will make them, and a descriptor
   * stamped from the global half would then hide the connect button from the
   * very users an exclusion exists to let connect.
   */
  it('stamps managed from the per-viewer verdict and not from the instance choice', () => {
    const forThisViewer = {serverId: 6, serverName: 'Bluemind', managedForMe: true};
    const chosenButNotForThisViewer = {serverId: 6, serverName: 'Bluemind', managedForMe: false};

    expect(createCaldavConnector(declaredServer, 1, forThisViewer).managed).toBe(true);
    expect(createCaldavConnector(declaredServer, 1, forThisViewer).managedServerName).toBe('Bluemind');

    expect(createCaldavConnector(declaredServer, 1, chosenButNotForThisViewer).managed).toBe(false);
    expect(createCaldavConnector(declaredServer, 1, chosenButNotForThisViewer).managedServerName).toBeNull();
  });

  /**
   * The stamp reaches EVERY CalDAV descriptor, not only the chosen server's:
   * managed mode governs the CalDAV family as a whole, and a user offered to
   * connect a DIFFERENT CalDAV server would be offered exactly the act the
   * mode exists to take away.
   */
  it('stamps every CalDAV descriptor, including the servers that were not chosen', () => {
    const managedElsewhere = {serverId: 999, serverName: 'Bluemind', managedForMe: true};

    expect(createCaldavConnector(seedServer, 0, managedElsewhere).managed).toBe(true);
    expect(createCaldavConnector(declaredServer, 1, managedElsewhere).managed).toBe(true);
    expect(createLegacyCaldavConnector(managedElsewhere).managed).toBe(true);
  });

  /**
   * The property EXISTS on every descriptor shape, false rather than
   * undefined: agenda reads it to remove affordances, and `undefined` and
   * `false` read alike right up to the day somebody writes `!== false`.
   */
  it('declares managed false rather than leaving it undefined', () => {
    expect(caldavConnector.managed).toBe(false);
    expect(createCaldavConnector(seedServer, 0).managed).toBe(false);
    expect(createCaldavConnector(declaredServer, 1, null).managed).toBe(false);
    expect(createLegacyCaldavConnector(null).managed).toBe(false);
    expect(createLegacyCaldavConnector(undefined).managedServerName).toBeNull();
  });

  /**
   * The legacy fallback is a COPY. It is registered when the registry answers
   * nothing, and stamping the shared singleton in place would leak the verdict
   * into the object every other descriptor is built from.
   */
  it('never mutates the shared descriptor when stamping the fallback', () => {
    createLegacyCaldavConnector({serverId: 6, serverName: 'Bluemind', managedForMe: true});

    expect(caldavConnector.managed).toBe(false);
    expect(caldavConnector.managedServerName).toBeNull();
  });

  /**
   * Distinct ranks keep the servers ordered and never collide two descriptors
   * on one rank.
   */
  it('gives each server its own rank', () => {
    expect(createCaldavConnector(seedServer, 0).rank).not.toBe(createCaldavConnector(declaredServer, 1).rank);
  });

  /**
   * The connect drawer resolves a descriptor's secondary line through the
   * i18n key `<providerName>.description` that main.js merges — the
   * descriptor must therefore point its `description` at exactly that key,
   * for every shape the factory produces.
   */
  it('keys the description on the provider name', () => {
    expect(createCaldavConnector(seedServer, 0).description).toBe('agenda.caldavCalendar.description');
    expect(createCaldavConnector(declaredServer, 1).description).toBe('agenda.caldavCalendar.6.description');
  });
});

/**
 * The host shown as the connect drawer's secondary line when the admin typed
 * no description. It must never leak the URL's path — that is where the raw
 * `{username}` placeholder lives — and must survive inputs that are not
 * parseable URLs, because the field is admin-typed free text.
 */
describe('serverHost', () => {

  /**
   * A full CalDAV base URL: host and port survive, scheme and path (with its
   * placeholder) do not.
   */
  it('keeps host and port, drops scheme and path', () => {
    expect(serverHost('http://localhost:8888/dav/cal/{username}/')).toBe('localhost:8888');
    expect(serverHost('https://caldav.example.invalid/dav/')).toBe('caldav.example.invalid');
  });

  /**
   * A bare host, as an admin may well type it, is kept as it is rather than
   * discarded by a failed URL parse.
   */
  it('keeps an unparseable input up to its first slash', () => {
    expect(serverHost('calendar.example.com')).toBe('calendar.example.com');
    expect(serverHost('calendar.example.com/dav/')).toBe('calendar.example.com');
  });

  /**
   * No URL yields no line, not a crash.
   */
  it('yields an empty string for an empty input', () => {
    expect(serverHost(null)).toBe('');
    expect(serverHost('')).toBe('');
  });
});
