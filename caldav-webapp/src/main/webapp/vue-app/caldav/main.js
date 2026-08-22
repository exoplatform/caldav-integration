/*
 * Copyright (C) 2023 eXo Platform SAS.
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
import './initComponents.js';
import * as agendaCaldavService from './js/agendaCaldavService.js';
import caldavConnector, {createCaldavConnector, serverHost} from './caldav-connector/caldavConnector.js';

if (!Vue.prototype.$agendaCaldavService) {
  window.Object.defineProperty(Vue.prototype, '$agendaCaldavService', {
    value: agendaCaldavService,
  });
}

// getting language of the PLF
const lang = eXo.env.portal.language || 'en';
// init Vue app when locale resources are ready
const url = `${eXo.env.portal.context}/${eXo.env.portal.rest}/i18n/bundle/locale.portlet.Caldav-${lang}.json`;

if (extensionRegistry) {
  const components = extensionRegistry.loadComponents('AgendaConnectors');
  if (components && components.length > 0) {
    components.forEach(cmp => {
      Vue.component(cmp.componentName, cmp.componentOptions);
    });
  }
}
const vuetify = new Vuetify(eXo.env.portal.vuetifyPreset);

// The Caldav bundle, merged into the SHARED VueI18n instance. Named rather
// than fired-and-forgotten: this module's admin section renders inside the
// AGENDA admin portlet, whose portlet.xml declares only the Agenda bundle —
// caldav's keys reach that page through this merge and nothing else. The
// section (below) waits on it, because its table headers are resolved once,
// in created(), and a header translated after that stays a raw key forever.
const i18nPromise = exoi18n.loadLanguageAsync(lang, url);

document.addEventListener('open-caldav-connector-settings-drawer',function(event) {
  const appId = 'agendaConnectorSettingsDrawer';
  const appElement = document.getElementById(appId);
  appElement.appendChild(document.createElement('div'));
  exoi18n.loadLanguageAsync(lang, url).then(i18n => {
    Vue.createApp({
      // Which declared server the clicked connector fronts: the drawer probes
      // the typed credentials against ITS URL and stores ITS id beside them.
      // A legacy connector dispatches no detail and the drawer behaves as it
      // always has.
      data: () => ({server: event && event.detail || null}),
      template: '<caldav-agenda-connectors :server="server" />',
      vuetify,
      i18n
    }, `#${appId}>div`, 'Agenda Connectors Settings');
  });
});

// The CalDAV servers section of the agenda administration page. Registered
// only once the Caldav bundle is merged (finally: a failed bundle fetch
// still registers — raw keys beat a missing section), and followed by the
// refresh event the admin page listens to: the module may run before or
// after the admin app is created, and whichever side arrives second finds
// the other.
i18nPromise.finally(() => {
  extensionRegistry.registerExtension('agenda-admin-settings', 'sections', {
    id: 'caldavServers',
    rank: 20,
    vueComponent: Vue.options.components['caldav-admin-servers-section'],
  });
  // Below the servers, because tuning is what you reach for once the servers
  // are declared — and there is nothing to tune on an instance with none.
  extensionRegistry.registerExtension('agenda-admin-settings', 'sections', {
    id: 'caldavSyncTuning',
    rank: 21,
    vueComponent: Vue.options.components['caldav-admin-sync-section'],
  });
  document.dispatchEvent(new CustomEvent('agenda-admin-sections-refresh'));

  // The user-settings row, ranked to land between the connected account and
  // the copy switch: what it offers back is a calendar of that account, so it
  // reads as part of it rather than as a fourth unrelated setting. The row
  // draws nothing when no calendar is hidden, which is the usual case.
  extensionRegistry.registerExtension('agenda-user-settings', 'sections', {
    id: 'caldavHiddenCalendars',
    rank: 30,
    vueComponent: Vue.options.components['caldav-hidden-calendars-section'],
  });
  document.dispatchEvent(new CustomEvent('agenda-user-sections-refresh'));
});

// One agenda connector per ACTIVE declared server, its label merged into the
// shared i18n instance under the provider name the agenda UIs translate.
// When the registry answers nothing — an empty registry with no legacy
// property, or the REST failing — the single legacy connector is registered
// exactly as before this section existed.
i18nPromise
  .catch(() => null)
  .then(() => agendaCaldavService.getCaldavServers())
  .then(servers => {
    const activeServers = (servers || []).filter(server => server.active);
    if (!activeServers.length) {
      extensionRegistry.registerExtension('agenda', 'connectors', caldavConnector);
      return;
    }
    const labels = {};
    activeServers.forEach((server, index) => {
      extensionRegistry.registerExtension('agenda', 'connectors', createCaldavConnector(server, index));
      labels[server.providerName] = server.name;
      // The secondary line of the connect-drawer row: the admin's words when
      // there are some, else the host — always present, and the thing that
      // actually tells two look-alike CalDAV rows apart.
      labels[`${server.providerName}.description`] = server.description || serverHost(server.serverUrl);
    });
    return i18nPromise.then(i18n => i18n.mergeLocaleMessage(lang, labels));
  })
  .catch(() => extensionRegistry.registerExtension('agenda', 'connectors', caldavConnector))
  .finally(() => document.dispatchEvent(new CustomEvent('agenda-connectors-refresh')));

