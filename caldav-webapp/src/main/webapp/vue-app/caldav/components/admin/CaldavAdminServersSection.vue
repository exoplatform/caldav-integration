<!--
Copyright (C) 2026 eXo Platform SAS.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
-->
<template>
  <div class="mt-5">
    <div class="pb-5 d-flex align-center">
      <span class="text-title">{{ $t('caldav.admin.servers.title') }}</span>
      <v-spacer />
      <v-btn
        class="btn btn-primary"
        small
        @click="openDrawer()">
        {{ $t('caldav.admin.servers.add') }}
      </v-btn>
    </div>
    <v-divider />
    <v-data-table
      :headers="headers"
      :items="servers"
      :items-per-page="itemsPerPage"
      :hide-default-footer="hideFooter"
      :footer-props="{
        itemsPerPageText: `${$t('agenda.itemsPerPage')}:`,
      }"
      :no-data-text="$t('caldav.admin.servers.noServers')"
      :loading="loading"
      disable-sort>
      <template slot="item" slot-scope="props">
        <tr>
          <td>
            <div class="align-center text-truncate">
              {{ props.item.name }}
            </div>
          </td>
          <td>
            <div class="align-center text-truncate">
              {{ props.item.description }}
            </div>
          </td>
          <td>
            <div class="align-center text-truncate">
              {{ props.item.serverUrl }}
            </div>
          </td>
          <td>
            <div class="d-flex flex-column align-center">
              <v-switch
                v-model="props.item.active"
                :loading="props.item.loading"
                :ripple="false"
                color="primary"
                class="connectorSwitcher my-auto"
                @change="setServerActive(props.item)" />
            </div>
          </td>
          <td>
            <div class="d-flex justify-center">
              <v-btn
                icon
                small
                @click="openDrawer(props.item)">
                <v-icon size="16">
                  fas fa-edit
                </v-icon>
              </v-btn>
            </div>
          </td>
        </tr>
      </template>
    </v-data-table>
    <exo-drawer
      id="caldavAdminServerDrawer"
      ref="serverDrawer"
      :right="!$vuetify.rtl"
      disable-pull-to-refresh>
      <template slot="title">
        {{ editedServer.id ? $t('caldav.admin.servers.drawer.editTitle') : $t('caldav.admin.servers.drawer.addTitle') }}
      </template>
      <template slot="content">
        <v-form ref="serverForm" class="pa-2 ms-2 mt-4">
          <div class="d-flex flex-column flex-grow-1">
            <div class="d-flex flex-column mb-2">
              <label class="d-flex flex-row font-weight-bold my-2">{{ $t('caldav.admin.servers.name') }}</label>
              <v-text-field
                v-model="editedServer.name"
                type="text"
                name="caldavServerName"
                :placeholder="$t('caldav.admin.servers.name.placeholder')"
                class="input-block-level ignore-vuetify-classes pa-0"
                maxlength="250"
                outlined
                required
                dense />
            </div>
            <div class="d-flex flex-column mb-2">
              <label class="d-flex flex-row font-weight-bold my-2">{{ $t('caldav.admin.servers.description') }}</label>
              <v-text-field
                v-model="editedServer.description"
                type="text"
                name="caldavServerDescription"
                :placeholder="$t('caldav.admin.servers.description.placeholder')"
                class="input-block-level ignore-vuetify-classes pa-0"
                maxlength="500"
                outlined
                dense />
            </div>
            <div class="d-flex flex-column mb-2">
              <label class="d-flex flex-row font-weight-bold my-2">{{ $t('caldav.admin.servers.url') }}</label>
              <v-text-field
                v-model="editedServer.serverUrl"
                type="text"
                name="caldavServerUrl"
                :placeholder="$t('caldav.admin.servers.url.placeholder')"
                class="input-block-level ignore-vuetify-classes pa-0"
                maxlength="1000"
                outlined
                required
                dense />
              <span class="text-light-color text-caption">{{ $t('caldav.admin.servers.url.hint') }}</span>
            </div>
          </div>
        </v-form>
      </template>
      <template slot="footer">
        <div class="d-flex">
          <v-spacer />
          <v-btn
            class="btn me-2"
            @click="closeDrawer">
            {{ $t('agenda.caldavCalendar.settings.connect.actions.cancel') }}
          </v-btn>
          <v-btn
            :loading="saving"
            :disabled="disableSaveButton"
            class="btn btn-primary"
            @click="saveServer">
            {{ $t('caldav.admin.servers.drawer.save') }}
          </v-btn>
        </div>
      </template>
    </exo-drawer>
  </div>
</template>

<script>
export default {
  props: {
    /**
     * Agenda user settings, handed by the admin page to every section. This
     * section reads its rows from the CalDAV registry instead, but keeps the
     * prop so its contract matches the other sections.
     */
    settings: {
      type: Object,
      default: () => null,
    },
  },
  data: () => ({
    servers: [],
    headers: [],
    editedServer: {},
    itemsPerPage: 10,
    loading: false,
    saving: false,
  }),
  computed: {
    hideFooter() {
      return this.servers && this.servers.length <= this.itemsPerPage;
    },
    disableSaveButton() {
      return !this.editedServer.name || !this.editedServer.serverUrl;
    },
  },
  created() {
    this.headers = [
      { text: this.$t('agenda.name'), align: 'center' },
      { text: this.$t('agenda.description'), align: 'center' },
      { text: this.$t('caldav.admin.servers.url'), align: 'center', width: '40%' },
      { text: this.$t('agenda.active'), align: 'center' },
      { text: this.$t('caldav.admin.servers.actions'), align: 'center' },
    ];
    this.refreshServers();
  },
  methods: {
    /**
     * Reloads the declared servers from the registry.
     *
     * @returns {Promise} resolves once the table holds the current rows
     */
    refreshServers() {
      this.loading = true;
      return this.$agendaCaldavService.getCaldavServers()
        .then(servers => this.servers = (servers || []).map(server => Object.assign({loading: false}, server)))
        .catch(() => this.servers = [])
        .finally(() => this.loading = false);
    },
    /**
     * Opens the drawer, empty for a declaration, prefilled for an edit. The
     * edit works on a copy so an abandoned drawer leaves the table untouched.
     *
     * @param {Object} server the row to edit, or nothing to declare a new one
     * @returns {void}
     */
    openDrawer(server) {
      this.editedServer = server && Object.assign({}, server) || {active: true};
      this.$refs.serverDrawer.open();
    },
    /**
     * Closes the drawer, dropping whatever was typed.
     *
     * @returns {void}
     */
    closeDrawer() {
      this.$refs.serverDrawer.close();
    },
    /**
     * Creates or updates the drawer's server, then reloads the table and
     * tells the agenda apps that the connectors changed.
     *
     * @returns {void}
     */
    saveServer() {
      this.saving = true;
      const save = this.editedServer.id
        ? this.$agendaCaldavService.updateCaldavServer(this.editedServer)
        : this.$agendaCaldavService.createCaldavServer(this.editedServer);
      save
        .then(() => {
          this.closeDrawer();
          return this.refreshServers();
        })
        .then(() => document.dispatchEvent(new CustomEvent('agenda-connectors-refresh')))
        .catch(() => this.$root.$emit('alert-message', this.$t('caldav.admin.servers.saveError'), 'error'))
        .finally(() => this.saving = false);
    },
    /**
     * Propagates the activation switch of a row: to the registry, and through
     * it to the agenda remote provider users' connector lists read.
     *
     * @param {Object} server the row whose switch was flipped
     * @returns {void}
     */
    setServerActive(server) {
      server.loading = true;
      this.$agendaCaldavService.setCaldavServerStatus(server.id, server.active)
        .then(updatedServer => Object.assign(server, updatedServer))
        .then(() => document.dispatchEvent(new CustomEvent('agenda-connectors-refresh')))
        .catch(() => server.active = !server.active)
        .finally(() => server.loading = false);
    },
  }
};
</script>
