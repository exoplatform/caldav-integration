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
  <div class="mt-8">
    <div class="text-title mb-2">
      {{ $t('caldav.admin.servers.title') }}
    </div>
    <!--
      What this whole section is for, in one sentence: the host page renders no
      title of its own, so a section that paints only a two-word heading leaves
      an administrator to guess what declaring a server here actually buys
      their users. It says both directions explicitly, because they differ —
      the user's own calendars come and go both ways, their eXo meetings only
      leave.
    -->
    <div class="text-subtitle mb-4">
      {{ $t('caldav.admin.servers.subtitle') }}
    </div>
    <!--
      Who chooses the server, before how often it is read: this row decides
      whether the users of this instance are offered the choice at all, so
      everything below it — the tuning, the list — is read differently
      depending on what it says. The switch sits at the right edge, and the
      pencil beside it appears only once there is a choice to revisit.
    -->
    <div class="d-flex align-center mb-4">
      <div class="flex-grow-1 text-start">
        <div>{{ $t('caldav.admin.managed.title') }}</div>
        <div class="text-subtitle">{{ managedSummary }}</div>
      </div>
      <v-btn
        v-if="managedOn"
        :aria-label="$t('caldav.admin.managed.title')"
        :title="$t('caldav.admin.managed.title')"
        icon
        @click="openManagedDrawer">
        <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
      </v-btn>
      <v-switch
        v-model="managedOn"
        :aria-label="$t('caldav.admin.managed.title')"
        class="ma-0 ms-2 pa-0"
        hide-details
        @change="flipManagedMode" />
    </div>
    <!--
      How the servers declared below are read, on one line above the list that
      declares them: it is the same subject seen from the other end, and an
      administrator who has just added a server is exactly who wonders how
      often it will be talked to. The values themselves live in a drawer — five
      numbers whose consequences need a sentence each are not something to
      leave open on a page nobody came here to read.
    -->
    <div class="d-flex align-center mb-4">
      <!--
        text-start explicitly: in this skin the align-center helper also sets
        text-align, so a row laid out with it silently centres its own text.
      -->
      <div class="flex-grow-1 text-start">
        <div>{{ $t('caldav.admin.sync.title') }}</div>
        <div class="text-subtitle">{{ tuningSummary }}</div>
      </div>
      <v-btn
        :aria-label="$t('caldav.admin.sync.title')"
        :title="$t('caldav.admin.sync.title')"
        icon
        @click="$root.$emit('open-caldav-sync-tuning-drawer', tuning)">
        <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
      </v-btn>
    </div>
    <div class="mb-4">
      <v-btn
        :aria-label="$t('caldav.admin.servers.add')"
        class="btn btn-primary"
        @click="$root.$emit('open-caldav-server-drawer')">
        <v-icon size="18">fa-plus</v-icon>
        <span class="text-none ms-2">{{ $t('caldav.admin.servers.add') }}</span>
      </v-btn>
    </div>
    <caldav-admin-server-list :servers="servers" />
    <caldav-admin-server-drawer />
    <caldav-admin-sync-drawer @saved="tuning = $event" />
    <!--
      The drawer is the platform's, shared with the mail connector: this section
      hands it the declared servers as candidates, the eligibility answers, and
      its own save. What is CalDAV here is the icon a row is drawn with and the
      way out of the empty state, so both come in through slots.
    -->
    <managed-connector-drawer
      ref="managedDrawer"
      :candidates="managedCandidates"
      :connection-requirements="connectionRequirements"
      :save="saveManagedMode"
      @saved="managedApplied"
      @cancelled="managedCancelled">
      <template #icon="{candidate}">
        <caldav-server-icon
          :image-url="candidate.imageUrl"
          :icon="candidate.icon"
          icon-size="24" />
      </template>
      <template #empty-action>
        <v-btn
          :aria-label="$t('caldav.admin.servers.add')"
          class="btn btn-primary"
          @click="addServerFromManagedDrawer">
          <v-icon size="18">fa-plus</v-icon>
          <span class="text-none ms-2">{{ $t('caldav.admin.servers.add') }}</span>
        </v-btn>
      </template>
    </managed-connector-drawer>
    <!--
      Off is not an ordinary flip either: it is an instance-wide change, so the
      switch asks before it commits - and says what happens today: users choose
      again, the accounts already connected keep syncing. What becomes of the
      users managed mode attached is EXO-89654's, and its wording will change
      when that lands.
    -->
    <confirm-dialog
      ref="managedOffConfirm"
      :title="$t('caldav.admin.managed.off.confirm.title')"
      :message="$t('caldav.admin.managed.off.confirm.message', {0: managed && managed.serverName || ''})"
      :ok-label="$t('caldav.admin.managed.off.confirm.ok')"
      :cancel-label="$t('caldav.admin.managed.off.confirm.cancel')"
      @ok="clearManagedMode"
      @closed="managedOffDeclined" />
  </div>
</template>

<script>
import * as caldavConnectorService from '../../js/agendaCaldavService.js';
import {serverHost} from '../../caldav-connector/caldavConnector.js';

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
    tuning: null,
    /**
     * What the instance decided about who chooses the CalDAV server —
     * {serverId, serverName, excludedGroups, managedForMe}. Null until it has
     * been read, so the row can tell "not read yet" from "off".
     */
    managed: null,
    /**
     * Provider name to whether that provider asks the user for anything. What
     * decides which declared servers the drawer offers: only one whose
     * provider asks nothing can be designated for everybody.
     */
    connectionRequirements: {},
    /**
     * Whether the off confirmation was accepted during this opening. The
     * dialog's `closed` fires after OK and after Cancel alike, and only one
     * of them must leave the switch off.
     */
    managedOffConfirmed: false,
    /**
     * The switch's own state, which is deliberately NOT derived from
     * `managed`. Flipping it on is a request to choose a server, not the
     * choice: it runs ahead of the setting for as long as the drawer is open,
     * and goes back if the administrator closes without applying.
     */
    managedOn: false,
  }),
  computed: {
    /**
     * The tuning in one line, in the order it matters: how often, how wide,
     * and how the background sweep behaves.
     *
     * @returns {String} the summary, empty until the values have been read
     */
    tuningSummary() {
      if (!this.tuning) {
        return '';
      }
      return this.$t('caldav.admin.sync.summary', {
        0: this.tuning.throttleMinutes,
        1: this.tuning.pastDays,
        2: this.tuning.futureDays,
        3: this.tuning.sweepStaleMinutes,
      });
    },
    /**
     * Who chooses the CalDAV server, in one line.
     *
     * Three answers, not two: an instance with nothing declared yet is neither
     * "users connect their own account" — there is none to connect to — nor
     * managed. Saying so is what makes the switch worth pressing, because the
     * drawer behind it offers the way out.
     *
     * @returns {String} the summary line
     */
    managedSummary() {
      if (this.managed && this.managed.serverId) {
        const excluded = this.managed.excludedGroups && this.managed.excludedGroups.length || 0;
        if (excluded) {
          return this.$t('caldav.admin.managed.onExcept', {0: this.managed.serverName || '', 1: excluded});
        }
        return this.$t('caldav.admin.managed.on', {0: this.managed.serverName || ''});
      }
      if (!this.activeServers.length) {
        return this.$t('caldav.admin.managed.noServers');
      }
      return this.$t('caldav.admin.managed.off');
    },
    /**
     * The registrations managed mode could point at.
     *
     * @returns {Array} the active declared servers
     */
    activeServers() {
      return (this.servers || []).filter(server => server.active);
    },
    /**
     * The declared servers as the shared drawer reads them. The provider name
     * travels under the drawer's own key: it is what the drawer matches against
     * the requirements to keep only the rows that ask their users for nothing.
     *
     * @returns {Array} one candidate per declared server
     */
    managedCandidates() {
      return (this.servers || []).map(server => ({
        id: server.id,
        name: server.name,
        subtitle: server.description || serverHost(server.serverUrl),
        active: !!server.active,
        providerName: server.authProviderName,
        imageUrl: server.imageUrl,
        icon: server.icon,
      }));
    },
  },
  created() {
    this.$root.$on('refresh-caldav-servers-list', this.refreshServers);
    this.refreshServers();
    this.retrieveTuning();
    this.retrieveManagedMode();
    this.retrieveConnectionRequirements();
  },
  methods: {
    /**
     * Reads how often and how widely the declared servers are read.
     *
     * A failure leaves the line blank rather than showing invented numbers:
     * an administrator reading a summary has no way to tell a real value from
     * a placeholder.
     *
     * @returns {Promise} resolves once the values have been read or given up on
     */
    retrieveTuning() {
      return caldavConnectorService.getSyncTuning()
        .then(tuning => this.tuning = tuning)
        .catch(error => console.error('cannot read the CalDAV synchronisation tuning', error));
    },
    /**
     * Reloads the declared servers from the registry.
     *
     * @returns {Promise} resolves once the table holds the current rows
     */
    refreshServers() {
      return this.$agendaCaldavService.getCaldavServers()
        .then(servers => this.servers = servers || [])
        .catch(() => this.servers = []);
    },
    /**
     * Reads which providers ask their users for anything.
     *
     * A failure leaves the map empty, and an empty map makes every server
     * ineligible: the drawer then says none can be designated, which is the
     * conservative reading when the platform could not say otherwise.
     *
     * @returns {Promise} resolves once the answers have been read or given up on
     */
    retrieveConnectionRequirements() {
      return caldavConnectorService.getConnectionRequirements()
        .then(requirements => this.connectionRequirements = requirements || {})
        .catch(error => console.error('cannot read the CalDAV connection requirements', error));
    },
    /**
     * Reads whether the instance chooses the server for its users, and which.
     *
     * A failure leaves the row saying "off" rather than inventing a state: the
     * switch is a control, and one showing a position the platform never
     * confirmed is worse than a summary that reads conservatively.
     *
     * @returns {Promise} resolves once the mode has been read or given up on
     */
    retrieveManagedMode() {
      return caldavConnectorService.getManagedMode()
        .then(managed => {
          this.managed = managed || null;
          this.managedOn = !!(managed && managed.serverId);
        })
        .catch(error => console.error('cannot read the CalDAV managed mode', error));
    },
    /**
     * Reacts to the switch, which does two quite different things depending on
     * which way it went — and commits neither by itself.
     *
     * On is a request to choose a server: it opens the drawer and stores
     * nothing, because there is no honest way to turn managed mode on without
     * naming a server, and the switch cannot name one. Off is a request too:
     * an instance-wide change, so the switch asks first. Either
     * way the switch runs ahead of the setting until the administrator
     * answers, and goes back if they decline.
     *
     * @param {Boolean} on the position the switch was moved to
     * @returns {void}
     */
    flipManagedMode(on) {
      if (on) {
        this.openManagedDrawer();
        return;
      }
      this.managedOffConfirmed = false;
      this.$refs.managedOffConfirm.open();
    },
    /**
     * Switches managed mode off, once confirmed.
     *
     * @returns {Promise} resolves once the mode is cleared
     */
    clearManagedMode() {
      this.managedOffConfirmed = true;
      return caldavConnectorService.clearManagedMode()
        .then(managed => {
          this.managed = managed || null;
          this.managedOn = false;
          this.$root.$emit('alert-message', this.$t('caldav.admin.managed.cleared'), 'success');
        })
        .catch(error => {
          console.error('cannot switch the CalDAV managed mode off', error);
          this.managedOn = true;
          this.$root.$emit('alert-message', this.$t('caldav.admin.managed.clearFailed'), 'error');
        });
    },
    /**
     * Puts the switch back on when the off confirmation closed without OK.
     *
     * @returns {void}
     */
    managedOffDeclined() {
      if (!this.managedOffConfirmed) {
        this.managedCancelled();
      }
    },
    /**
     * Stores the choice the drawer collected, through this add-on's own
     * endpoint — the drawer is shared and knows no URL. The snackbar is this
     * section's too: the drawer does not know what a CalDAV server is called.
     *
     * @param {Number} serverId the chosen registration
     * @param {Array<String>} excludedGroups the eXo group ids the choice must not reach
     * @returns {Promise<Object>} the mode now in force
     */
    saveManagedMode(serverId, excludedGroups) {
      return caldavConnectorService.saveManagedMode(serverId, excludedGroups)
        .then(managed => {
          const named = this.$t('caldav.admin.managed.saved', {0: managed && managed.serverName || ''});
          this.$root.$emit('alert-message', named, 'success');
          return managed;
        });
    },
    /**
     * Opens the declaration form from the drawer's empty state. The drawer
     * closes on the way: the two are drawers on the same side, and leaving one
     * under the other would put the administrator back in front of a list
     * that was empty when they left it.
     *
     * @returns {void}
     */
    addServerFromManagedDrawer() {
      this.$refs.managedDrawer.close();
      this.$root.$emit('open-caldav-server-drawer');
    },
    /**
     * Opens the drawer on the mode in force, so the choice is made against
     * exactly what is stored.
     *
     * @returns {void}
     */
    openManagedDrawer() {
      const managed = this.managed || {};
      this.$refs.managedDrawer.open({
        connectorId: managed.serverId,
        excludedGroups: managed.excludedGroups || [],
      });
    },
    /**
     * Records what the drawer stored. This — and only this — is what puts the
     * row into its managed state.
     *
     * @param {Object} managed the mode now in force
     * @returns {void}
     */
    managedApplied(managed) {
      this.managed = managed || null;
      this.managedOn = !!(managed && managed.serverId);
    },
    /**
     * Puts the switch back where the setting says it is, after a drawer closed
     * without applying.
     *
     * Back to what was STORED, not to false: an administrator who opened the
     * drawer to change which server is managed, then closed it, has not
     * switched managed mode off.
     *
     * @returns {void}
     */
    managedCancelled() {
      this.managedOn = !!(this.managed && this.managed.serverId);
    },
  }
};
</script>
