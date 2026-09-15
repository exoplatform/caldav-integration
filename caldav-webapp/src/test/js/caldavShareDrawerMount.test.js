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
 * How main.js opens the share drawer (EXO-90253): one app, mounted on the
 * first request and kept, and a request that still opens it when the Caldav
 * bundle could not be fetched or the first mount failed.
 *
 * <p>The drawer is reached only through a document event the connector
 * dispatches from agenda's calendar menu, so a promise cached in a rejected
 * state is silent: the menu entry is there and clicking it does nothing, for
 * the life of the page.</p>
 */
describe('the share drawer app', () => {

  /** How many times an app was created, and the drawer each one exposes. */
  let created;

  /** The calendars the drawer was opened on. */
  let openedOn;

  /**
   * The document listeners main.js registered while a test loaded it. Each
   * test loads a fresh copy of the module, and a listener left behind by one
   * copy would answer the next test's requests with its own cached app.
   */
  let listeners;

  /** The document's own addEventListener, restored after each test. */
  const addEventListener = document.addEventListener.bind(document);

  /**
   * Loads main.js against a bundle fetch and an app factory of the test's
   * choosing.
   *
   * @param {Function} loadLanguageAsync what exoi18n.loadLanguageAsync does
   * @param {Function} createApp what Vue.createApp does
   * @returns {Promise} resolves once the module's own promises settled
   */
  function loadWith(loadLanguageAsync, createApp) {
    global.exoi18n = {loadLanguageAsync, i18n: {shared: true}};
    global.Vue.createApp = createApp;
    jest.isolateModules(() => {
      jest.doMock('../../main/webapp/vue-app/caldav/initComponents.js', () => ({}), {virtual: false});
      jest.doMock('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js', () => ({
        getManagedMode: () => Promise.resolve(null),
        getCaldavServers: () => Promise.resolve([]),
      }));
      require('../../main/webapp/vue-app/caldav/main.js');
    });
    return settle();
  }

  /**
   * Lets every pending promise settle.
   *
   * @returns {Promise} resolves on the next macrotask
   */
  function settle() {
    return new Promise(resolve => setTimeout(resolve, 0));
  }

  /**
   * Asks for the drawer the way the connector does.
   *
   * @param {Object} calendar the event detail
   * @returns {Promise} resolves once the request settled
   */
  function requestDrawer(calendar) {
    document.dispatchEvent(new CustomEvent('open-caldav-share-calendar-drawer', {detail: calendar}));
    return settle();
  }

  /**
   * An app factory that mounts successfully.
   *
   * @param {Object} params the app options
   * @returns {Object} an app whose drawer records what it is opened on
   */
  function mountingApp(params) {
    created.push(params);
    return {$refs: {drawer: {open: calendar => openedOn.push(calendar)}}};
  }

  beforeEach(() => {
    created = [];
    openedOn = [];
    listeners = [];
    document.addEventListener = (type, listener, options) => {
      listeners.push([type, listener, options]);
      addEventListener(type, listener, options);
    };
    global.Vue = {
      prototype: {},
      component: () => {},
      createApp: () => {},
      options: {components: new Proxy({}, {get: (target, name) => ({name})})},
    };
    global.Vuetify = function Vuetify() {};
    global.eXo = {env: {portal: {language: 'en', context: '/portal', rest: 'rest', vuetifyPreset: {}}}};
    global.extensionRegistry = {loadComponents: () => [], registerExtension: () => {}};
    jest.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    listeners.forEach(([type, listener, options]) => document.removeEventListener(type, listener, options));
    document.addEventListener = addEventListener;
    console.error.mockRestore();
    document.body.innerHTML = '';
  });

  it('mounts one app on the first request and reuses it', async () => {
    await loadWith(() => Promise.resolve({bundle: true}), mountingApp);

    await requestDrawer({id: 12, name: 'Work'});
    await requestDrawer({id: 14, name: 'Family'});

    expect(created.length).toBe(1);
    expect(created[0].i18n).toEqual({bundle: true});
    expect(openedOn).toEqual([{id: 12, name: 'Work'}, {id: 14, name: 'Family'}]);
    expect(document.querySelectorAll('#caldavShareCalendarDrawerApp').length).toBe(1);
  });

  it('still opens the drawer when the Caldav bundle could not be fetched', async () => {
    await loadWith(() => Promise.reject(new Error('offline')), mountingApp);

    await requestDrawer({id: 12, name: 'Work'});

    expect(created.length).toBe(1);
    expect(created[0].i18n).toEqual({shared: true});
    expect(openedOn).toEqual([{id: 12, name: 'Work'}]);
  });

  it('tries again after a mount that failed', async () => {
    let attempts = 0;
    await loadWith(() => Promise.resolve({bundle: true}), params => {
      attempts++;
      return attempts === 1 ? undefined : mountingApp(params);
    });

    await requestDrawer({id: 12, name: 'Work'});
    await requestDrawer({id: 12, name: 'Work'});

    expect(attempts).toBe(2);
    expect(openedOn).toEqual([{id: 12, name: 'Work'}]);
  });

  it('opens nothing for a request that names no calendar', async () => {
    await loadWith(() => Promise.resolve({bundle: true}), mountingApp);

    await requestDrawer(null);
    await requestDrawer({name: 'no id'});

    expect(created.length).toBe(0);
    expect(openedOn).toEqual([]);
  });
});
