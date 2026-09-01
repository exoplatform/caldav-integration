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
import {createCaldavConnector, createLegacyCaldavConnector, serverHost} from './caldav-connector/caldavConnector.js';

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

// Whether this instance chose the user's CalDAV server, fetched ONCE and
// shared by the two things that need it: which nested rows this module
// registers on the agenda settings page, and how the connector descriptors are
// stamped. Two fetches would be two answers that can disagree.
//
// Never rejects. A deployment whose managed setting cannot be read still has to
// offer its users their calendars and their rows, so a failure resolves to null
// — "not managed" — which restores affordances rather than removing them: the
// recoverable direction, and the honest one when nothing is known.
const managedPromise = i18nPromise
  .catch(() => null)
  .then(() => agendaCaldavService.getManagedMode())
  .catch(error => {
    console.error('cannot read whether this instance manages the CalDAV account', error);
    return null;
  });

/**
 * Whether the CalDAV account of the user looking at this page is the
 * instance's to decide.
 *
 * @param {Object} managed what the platform answered, null when unknown
 * @returns {Boolean} true when managed mode governs this viewer
 */
function managedForViewer(managed) {
  return !!(managed && managed.managedForMe);
}

// The CalDAV servers section of the agenda administration page, and the rows
// nested under My Calendars. Registered once the Caldav bundle is merged (a
// failed bundle fetch still registers — raw keys beat a missing section) and
// once the managed verdict is known, because that verdict decides which of the
// nested rows exist at all; then the refresh event the pages listen to, since
// the module may run before or after the app is created and whichever side
// arrives second finds the other.
managedPromise.then(managed => {
  // Ranked first on the page, ahead of agenda's own built-in sections (the
  // connectors table at 20, the embed-map settings at 30): the connectors
  // table's CalDAV rows are DERIVED from this registry, so declaring the
  // servers comes before enabling them. This rank only orders anything
  // because agenda stopped hardcoding its two sections before the extension
  // loop and gave them ranks of their own (EXO-89757) — before that, this
  // section was sorted against nothing and always landed last.
  extensionRegistry.registerExtension('agenda-admin-settings', 'sections', {
    id: 'caldavServers',
    rank: 20,
    vueComponent: Vue.options.components['caldav-admin-servers-section'],
  });
  document.dispatchEvent(new CustomEvent('agenda-admin-sections-refresh'));

  // The user-settings row, ranked to land between the connected account and
  // the copy switch: what it offers back is a calendar of that account, so it
  // reads as part of it rather than as a fourth unrelated setting. The row
  // draws nothing when no calendar is hidden, which is the usual case.
  //
  // Registered under managed mode too. Which of their own calendars a user
  // syncs is their preference and has nothing to do with who owns the
  // connection: the instance chose the server, not what the user keeps in
  // their agenda.
  extensionRegistry.registerExtension('agenda-user-settings', 'sections', {
    id: 'caldavHiddenCalendars',
    rank: 30,
    vueComponent: Vue.options.components['caldav-hidden-calendars-section'],
  });
  // Just above the hidden calendars, and for the same reason it sits where it
  // does: a calendar that cannot synchronise is a problem with the account
  // the row above it describes, and the user reads down from there.
  //
  // Not registered at all under managed mode. It is a diagnostic, and a
  // managed connection is the administrator's to repair — telling a user their
  // calendar cannot synchronise, when the account is not theirs and they have
  // no remedy, names a problem and offers nothing. The gate is on the
  // REGISTRATION and not on the component's own `displayed`, deliberately: the
  // row then does not exist rather than existing and hiding, so it fires no
  // request of its own, and the condition stays in the one file that already
  // knows the verdict. Agenda never learns the names of these rows.
  if (!managedForViewer(managed)) {
    extensionRegistry.registerExtension('agenda-user-settings', 'sections', {
      id: 'caldavCalendarStates',
      rank: 29,
      vueComponent: Vue.options.components['caldav-calendar-states-section'],
    });
  }
  // Above the calendars that cannot synchronise, and for the same reason that
  // one sits above the hidden calendars: this row says a meeting the user
  // created is not on their server yet, which is the most urgent of the three
  // and the one they are most likely to have come to the page about. Absent
  // whenever nothing is outstanding, which is the usual case (EXO-89803).
  //
  // Gone under managed mode, for the same reason as the row above: it reports
  // on a mechanism the user does not own and cannot repair.
  if (!managedForViewer(managed)) {
    extensionRegistry.registerExtension('agenda-user-settings', 'sections', {
      id: 'caldavPendingCopies',
      rank: 28,
      vueComponent: Vue.options.components['caldav-pending-copies-section'],
    });
  }
  // Just under the hidden calendars: pointing a phone at the account is an
  // offer, not a problem, so it comes after the rows that name problems. In
  // the healthy case both problem rows are absent and this is the only
  // nested row under My Calendars. Shown only when an account is connected —
  // there is nothing to set up otherwise.
  //
  // Registered under managed mode too, and it is the row that made hiding the
  // whole section visibly wrong: the instance chose the server, but the user
  // still has to point their phone or desktop client at it themselves. If
  // anything this is MORE useful once they cannot manage the account, because
  // it is the only thing left they can act on.
  extensionRegistry.registerExtension('agenda-user-settings', 'sections', {
    id: 'caldavDeviceSetup',
    rank: 31,
    vueComponent: Vue.options.components['caldav-device-setup-section'],
  });
  document.dispatchEvent(new CustomEvent('agenda-user-sections-refresh'));
});

// One agenda connector per ACTIVE declared server, its label merged into the
// shared i18n instance under the provider name the agenda UIs translate.
// When the registry answers nothing — an empty registry with no legacy
// property, or the REST failing — the single legacy connector is registered
// exactly as before this section existed.
//
// This is the ONE writer of the CalDAV descriptors agenda reads, which is why
// the managed verdict is read here and stamped here: every screen that hides a
// connect or disconnect affordance reads it off the descriptor, so a second
// place asking the platform the same question is a second answer that can
// disagree with this one.
//
// The verdict comes from the SHARED promise above rather than a fetch of its
// own — one question, one answer, whether it is deciding which rows exist or
// how a descriptor is stamped. The mode is read once, when this module loads:
// an administrator switching it on does not reach into a session already open,
// which is why hiding a control was never the control (the server-side refusal
// is its own ticket).
let managedMode = null;
managedPromise
  .then(managed => managedMode = managed)
  .then(() => agendaCaldavService.getCaldavServers())
  .then(servers => {
    const activeServers = (servers || []).filter(server => server.active);
    if (!activeServers.length) {
      extensionRegistry.registerExtension('agenda', 'connectors', createLegacyCaldavConnector(managedMode));
      return;
    }
    const labels = {};
    activeServers.forEach((server, index) => {
      extensionRegistry.registerExtension('agenda', 'connectors', createCaldavConnector(server, index, managedMode));
      labels[server.providerName] = server.name;
      // The secondary line of the connect-drawer row: the admin's words when
      // there are some, else the host — always present, and the thing that
      // actually tells two look-alike CalDAV rows apart.
      labels[`${server.providerName}.description`] = server.description || serverHost(server.serverUrl);
    });
    return i18nPromise.then(i18n => i18n.mergeLocaleMessage(lang, labels));
  })
  .catch(() => extensionRegistry.registerExtension('agenda', 'connectors', createLegacyCaldavConnector(managedMode)))
  .finally(() => document.dispatchEvent(new CustomEvent('agenda-connectors-refresh')));

