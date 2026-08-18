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
import CaldavAgendaConnectors from './components/CaldavAgendaConnectors.vue';
import CaldavAgendaConnectorsAlert from './components/CaldavAgendaConnectorsAlert.vue';
import CaldavSettingsDrawer from './components/CaldavSettingsDrawer.vue';
import CaldavAdminServersSection from './components/admin/CaldavAdminServersSection.vue';

const components = {
  'caldav-settings-drawer': CaldavSettingsDrawer,
  'caldav-agenda-connectors': CaldavAgendaConnectors,
  'caldav-agenda-connectors-alert': CaldavAgendaConnectorsAlert,
  'caldav-admin-servers-section': CaldavAdminServersSection
};

for (const key in components) {
  Vue.component(key, components[key]);
}