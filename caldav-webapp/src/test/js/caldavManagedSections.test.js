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
 * Which rows this add-on nests under My Calendars, and which of them managed
 * mode takes away (EXO-89900).
 *
 * <p>The gate is on the REGISTRATION rather than on each component's own
 * `displayed`, so this is the level the behaviour has to be pinned at: a row
 * that is never registered cannot render, cannot fire a request of its own,
 * and does not need a second condition inside it. Getting the set wrong is
 * silent — the page simply has fewer rows than it should, which is exactly how
 * hiding the whole container took device setup away without anybody
 * noticing.</p>
 */
describe('the rows registered under My Calendars', () => {

  /** Every registration the module made, in order. */
  let registered;

  /**
   * Loads main.js against a platform answering this managed verdict, and
   * returns the ids it registered on the user-settings extension point.
   *
   * @param {Object} managed what GET /servers/managed answers
   * @returns {Promise<Array>} the registered user-settings row ids
   */
  function loadWith(managed) {
    registered = [];
    let mainModule;
    jest.isolateModules(() => {
      jest.doMock('../../main/webapp/vue-app/caldav/initComponents.js', () => ({}), {virtual: false});
      jest.doMock('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js', () => ({
        getManagedMode: () => Promise.resolve(managed),
        getCaldavServers: () => Promise.resolve([]),
      }));
      mainModule = require('../../main/webapp/vue-app/caldav/main.js');
    });
    // Two ticks of the microtask queue: the registrations hang off the i18n
    // promise, then off the managed one.
    return Promise.resolve(mainModule)
      .then(() => new Promise(resolve => setTimeout(resolve, 0)))
      .then(() => registered
        .filter(entry => entry.point === 'agenda-user-settings')
        .map(entry => entry.id));
  }

  beforeEach(() => {
    registered = [];
    global.Vue = {
      prototype: {},
      component: () => {},
      createApp: () => {},
      options: {components: new Proxy({}, {get: (target, name) => ({name})})},
    };
    global.Vuetify = function Vuetify() {};
    global.eXo = {env: {portal: {language: 'en', context: '/portal', rest: 'rest', vuetifyPreset: {}}}};
    global.extensionRegistry = {
      loadComponents: () => [],
      registerExtension: (point, type, descriptor) => registered.push({point, type, id: descriptor.id}),
    };
    global.exoi18n = {
      loadLanguageAsync: () => Promise.resolve({mergeLocaleMessage: () => {}}),
    };
  });

  /**
   * Unmanaged: all four rows, exactly as before managed mode existed.
   */
  it('registers every row when the user chooses their own server', async () => {
    const ids = await loadWith({serverId: null, serverName: null, managedForMe: false});

    expect(ids.sort()).toEqual([
      'caldavCalendarStates',
      'caldavDeviceSetup',
      'caldavHiddenCalendars',
      'caldavPendingCopies',
    ]);
  });

  /**
   * Managed: the two the user can still act on stay, the two diagnostics go.
   *
   * <p>Device setup is the one that made hiding the whole container visibly
   * wrong — the instance chose the server, the user still has to point their
   * phone at it — and hidden calendars is a preference about their own
   * calendars, which has nothing to do with who owns the connection.</p>
   */
  it('keeps device setup and hidden calendars, and drops the two diagnostics, when managed', async () => {
    const ids = await loadWith({serverId: 6, serverName: 'Bluemind', managedForMe: true});

    expect(ids.sort()).toEqual(['caldavDeviceSetup', 'caldavHiddenCalendars']);
    expect(ids).not.toContain('caldavPendingCopies');
    expect(ids).not.toContain('caldavCalendarStates');
  });

  /**
   * The verdict is the PER-VIEWER one. An instance that chose a server for
   * other people leaves this user's rows alone — which is what group
   * exclusions will rely on.
   */
  it('reads the per-viewer verdict and not the instance choice', async () => {
    const ids = await loadWith({serverId: 6, serverName: 'Bluemind', managedForMe: false});

    expect(ids.sort()).toEqual([
      'caldavCalendarStates',
      'caldavDeviceSetup',
      'caldavHiddenCalendars',
      'caldavPendingCopies',
    ]);
  });

  /**
   * A platform that cannot answer leaves every row in place. Removing rows on
   * a failed read would take a user's device-setup instructions away because
   * one request did not come back.
   */
  it('registers every row when the verdict cannot be read', async () => {
    const ids = await loadWith(null);

    expect(ids.sort()).toEqual([
      'caldavCalendarStates',
      'caldavDeviceSetup',
      'caldavHiddenCalendars',
      'caldavPendingCopies',
    ]);
  });

  /**
   * The admin servers section is registered on a different extension point and
   * is unaffected: it is the administration screen, not a row under a user's
   * My Calendars.
   */
  it('always registers the admin section, on its own extension point', async () => {
    await loadWith({serverId: 6, serverName: 'Bluemind', managedForMe: true});

    expect(registered.filter(entry => entry.point === 'agenda-admin-settings').map(entry => entry.id))
      .toEqual(['caldavServers']);
  });
});
