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
import caldavConnector, {createCaldavConnector} from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';

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
    serverUrl: 'https://webmail.demo3.livecollab.fr/dav/',
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
   * Distinct ranks keep the servers ordered and never collide two descriptors
   * on one rank.
   */
  it('gives each server its own rank', () => {
    expect(createCaldavConnector(seedServer, 0).rank).not.toBe(createCaldavConnector(declaredServer, 1).rank);
  });
});
