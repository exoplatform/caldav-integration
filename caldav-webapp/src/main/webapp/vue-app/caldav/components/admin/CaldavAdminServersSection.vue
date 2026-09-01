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
    <caldav-admin-managed-mode-drawer
      @saved="managedApplied"
      @cancelled="managedCancelled" />
  </div>
</template>

<script>
import * as caldavConnectorService from '../../js/agendaCaldavService.js';

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
     * {serverId, serverName, managedForMe}. Null until it has been read, so
     * the row can tell "not read yet" from "off".
     */
    managed: null,
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
  },
  created() {
    this.$root.$on('refresh-caldav-servers-list', this.refreshServers);
    this.refreshServers();
    this.retrieveTuning();
    this.retrieveManagedMode();
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
     * which way it went.
     *
     * On is a REQUEST, not a commit: it opens the drawer and stores nothing.
     * There is no honest way to turn managed mode on without naming a server,
     * and the switch cannot name one. Off is a commit, immediately and with no
     * confirmation dialog: it gives an affordance back rather than taking one
     * away, and nothing is severed by it — the accounts already connected go
     * on synchronising exactly as they did.
     *
     * @param {Boolean} on the position the switch was moved to
     * @returns {Promise} resolves once the drawer is open, or the mode cleared
     */
    flipManagedMode(on) {
      if (on) {
        this.openManagedDrawer();
        return Promise.resolve();
      }
      return caldavConnectorService.clearManagedMode()
        .then(managed => {
          this.managed = managed || null;
          this.$root.$emit('alert-message', this.$t('caldav.admin.managed.cleared'), 'success');
        })
        .catch(error => {
          console.error('cannot switch the CalDAV managed mode off', error);
          this.managedOn = true;
          this.$root.$emit('alert-message', this.$t('caldav.admin.managed.clearFailed'), 'error');
        });
    },
    /**
     * Opens the drawer on the rows this section already read and the mode in
     * force, so the choice is made against exactly what is on screen.
     *
     * @returns {void}
     */
    openManagedDrawer() {
      this.$root.$emit('open-caldav-managed-mode-drawer', {
        servers: this.servers,
        managed: this.managed,
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
