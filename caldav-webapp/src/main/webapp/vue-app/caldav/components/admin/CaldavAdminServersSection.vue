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
    <div class="text-title mb-3">
      {{ $t('caldav.admin.servers.title') }}
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
      <div class="flex-grow-1">
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
  },
  created() {
    this.$root.$on('refresh-caldav-servers-list', this.refreshServers);
    this.refreshServers();
    this.retrieveTuning();
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
  }
};
</script>
