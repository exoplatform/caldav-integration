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
  <exo-drawer
    id="caldavManagedModeDrawer"
    ref="caldavManagedModeDrawer"
    v-model="opened"
    :loading="saving"
    right
    @closed="cancel">
    <template #title>
      <span>{{ $t('caldav.admin.managed.drawer.title') }}</span>
    </template>
    <template v-if="opened" #content>
      <div class="mx-5 mt-5">
        <div class="text-subtitle mb-6">
          {{ $t('caldav.admin.managed.drawer.description') }}
        </div>
        <!--
          The empty state is a sentence and a way out, never a disabled switch
          in the row behind. A dead switch with a tooltip hides the feature
          from the administrator who has not declared a server yet; a drawer
          that says why and offers the Add button in the same breath does not.
        -->
        <div v-if="!activeServers.length">
          <div class="text-subtitle mb-4">
            {{ $t('caldav.admin.managed.drawer.noServers') }}
          </div>
          <v-btn
            :aria-label="$t('caldav.admin.servers.add')"
            class="btn btn-primary"
            @click="addServer">
            <v-icon size="18">fa-plus</v-icon>
            <span class="text-none ms-2">{{ $t('caldav.admin.servers.add') }}</span>
          </v-btn>
        </div>
        <div v-else>
          <label class="font-weight-bold d-block mb-2">
            {{ $t('caldav.admin.managed.drawer.serverLabel') }}
          </label>
          <!--
            Only ACTIVE servers. A deactivated registration is not a candidate
            the administrator should have to reason about: it is exactly the
            row nobody can connect to, so it is absent rather than greyed —
            greying it would invite the question "why not this one?" on a
            screen whose answer is a different screen.

            Even with a single candidate the list is rendered and preselected
            rather than skipped: turning managed mode on removes affordances
            from every user's screen, and the administrator should read the
            server's name at the moment they commit to that.
          -->
          <v-radio-group
            v-model="selectedServerId"
            class="mt-0 pt-0"
            hide-details>
            <v-radio
              v-for="server in activeServers"
              :key="server.id"
              :value="server.id"
              class="mb-4">
              <template #label>
                <div class="d-flex align-center min-width-0">
                  <!-- The identity the administrator configured, resolved by
                       the same component the server list uses, so the row here
                       and the row there cannot come to look like two different
                       servers. -->
                  <caldav-server-icon
                    :image-url="server.imageUrl"
                    :icon="server.icon"
                    icon-size="24"
                    class="flex-grow-0 flex-shrink-0 me-3" />
                  <div class="min-width-0 text-start">
                    <div class="text-truncate">{{ server.name }}</div>
                    <div class="text-subtitle text-truncate">{{ serverSubtitle(server) }}</div>
                  </div>
                </div>
              </template>
            </v-radio>
          </v-radio-group>
        </div>
        <v-alert
          v-if="errorMessage"
          type="error"
          class="mt-4"
          dense
          text>
          {{ errorMessage }}
        </v-alert>
      </div>
    </template>
    <template #footer>
      <div class="d-flex">
        <v-spacer />
        <v-btn
          class="btn"
          @click="close">
          {{ $t('caldav.admin.managed.drawer.cancel') }}
        </v-btn>
        <v-btn
          :disabled="saving || !selectedServerId"
          class="btn btn-primary ms-5"
          @click="apply">
          {{ $t('caldav.admin.managed.drawer.apply') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
import * as caldavConnectorService from '../../js/agendaCaldavService.js';
import {serverHost} from '../../caldav-connector/caldavConnector.js';

export default {
  data: () => ({
    opened: false,
    saving: false,
    /**
     * Whether Apply actually stored something during this opening. It is what
     * tells a close apart from a commit: the drawer's `closed` event fires in
     * both cases, and only one of them must leave the switch in the row on.
     */
    applied: false,
    servers: [],
    selectedServerId: null,
    errorMessage: '',
  }),
  computed: {
    /**
     * The registrations that are candidates at all.
     *
     * @returns {Array} the active declared servers
     */
    activeServers() {
      return (this.servers || []).filter(server => server.active);
    },
  },
  created() {
    this.$root.$on('open-caldav-managed-mode-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-caldav-managed-mode-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer on the servers the section has already read, preselected
     * on whatever is in force.
     *
     * The rows are handed in rather than fetched here: the section behind this
     * drawer already holds them, and a second listing could disagree with the
     * one the administrator is looking at.
     *
     * @param {Object} options {servers, managed} — the declared registrations
     *          and the mode in force
     * @returns {void}
     */
    open(options) {
      const opts = options || {};
      this.servers = opts.servers || [];
      const managed = opts.managed || {};
      this.applied = false;
      this.errorMessage = '';
      // Preselected on what is in force, else — the ordinary case of switching
      // the mode on — on the only candidate when there is exactly one. A single
      // server still gets a rendered, named row; it just costs one click
      // instead of two.
      this.selectedServerId = managed.serverId
        || (this.activeServers.length === 1 && this.activeServers[0].id)
        || null;
      this.opened = true;
      this.$refs.caldavManagedModeDrawer.open();
    },
    /**
     * The line under a candidate's name: what the administrator typed about
     * that server, else its host — the same precedence the connect drawer
     * shows users, so the two name the same server the same way.
     *
     * @param {Object} server the declared registration of the row
     * @returns {String} the secondary line
     */
    serverSubtitle(server) {
      return server.description || serverHost(server.serverUrl);
    },
    /**
     * Opens the declaration form, from the empty state.
     *
     * This drawer closes on the way: the two are drawers on the same side, and
     * leaving this one under the other would put the administrator back in
     * front of a list that was empty when they left it.
     *
     * @returns {void}
     */
    addServer() {
      this.close();
      this.$root.$emit('open-caldav-server-drawer');
    },
    /**
     * Closes the drawer without storing anything.
     *
     * @returns {void}
     */
    close() {
      this.opened = false;
      this.$refs.caldavManagedModeDrawer.close();
    },
    /**
     * What a close that stored nothing owes the row behind it: the switch goes
     * back off.
     *
     * Flipping the switch on is a request to choose a server, not the choice
     * itself — the mode is on only once Apply succeeded. Leaving the switch on
     * after a cancelled drawer would show managed mode as enabled while the
     * setting says otherwise, which is the one state an administrator cannot
     * recover from by looking.
     *
     * @returns {void}
     */
    cancel() {
      this.opened = false;
      if (!this.applied) {
        this.$emit('cancelled');
      }
    },
    /**
     * Stores the chosen server: this, and nothing before it, is what turns
     * managed mode on.
     *
     * What the server answers is what the section then shows — a value it
     * refused must not sit on screen as though it had been accepted — and a
     * refusal keeps the drawer open on the choice, carrying the reason.
     *
     * @returns {Promise} resolves once the choice has been stored
     */
    apply() {
      if (!this.selectedServerId) {
        return Promise.resolve();
      }
      this.saving = true;
      this.errorMessage = '';
      return caldavConnectorService.saveManagedMode(this.selectedServerId)
        .then(managed => {
          this.applied = true;
          this.$emit('saved', managed);
          const named = this.$t('caldav.admin.managed.saved', {0: managed && managed.serverName || ''});
          this.$root.$emit('alert-message', named, 'success');
          this.close();
        })
        .catch(error => {
          // The body of a refusal is the message code, so the administrator is
          // told which rule was hit rather than that something went wrong.
          const code = error && error.message || '';
          this.errorMessage = this.$te(code) ? this.$t(code) : this.$t('caldav.admin.managed.saveFailed');
        })
        .finally(() => this.saving = false);
    },
  },
};
</script>
