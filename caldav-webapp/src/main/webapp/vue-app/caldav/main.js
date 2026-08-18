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
import caldavConnector, {createCaldavConnector} from './caldav-connector/caldavConnector.js';

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

exoi18n.loadLanguageAsync(lang, url);

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
// synchronously — this module may run before or after the admin app is
// created, so the registration is followed by the refresh event the admin
// page listens to; whichever side arrives second finds the other.
extensionRegistry.registerExtension('agenda-admin-settings', 'sections', {
  id: 'caldavServers',
  rank: 20,
  vueComponent: Vue.options.components['caldav-admin-servers-section'],
});
document.dispatchEvent(new CustomEvent('agenda-admin-sections-refresh'));

// One agenda connector per ACTIVE declared server, its label merged into the
// shared i18n instance under the provider name the agenda UIs translate.
// When the registry answers nothing — an empty registry with no legacy
// property, or the REST failing — the single legacy connector is registered
// exactly as before this section existed.
agendaCaldavService.getCaldavServers()
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
      labels[`${server.providerName}.description`] = server.description || server.serverUrl;
    });
    return exoi18n.loadLanguageAsync(lang, url).then(i18n => i18n.mergeLocaleMessage(lang, labels));
  })
  .catch(() => extensionRegistry.registerExtension('agenda', 'connectors', caldavConnector))
  .finally(() => document.dispatchEvent(new CustomEvent('agenda-connectors-refresh')));

