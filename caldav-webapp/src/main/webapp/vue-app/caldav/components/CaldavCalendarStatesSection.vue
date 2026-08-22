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
  <v-list-item v-if="states.length">
    <v-list-item-content>
      <v-list-item-title class="text-color">
        {{ $t('caldav.calendarStates.title') }}
      </v-list-item-title>
      <v-list-item-subtitle>
        <span>{{ $t('caldav.calendarStates.subtitle', {0: states.length}) }}</span>
      </v-list-item-subtitle>
    </v-list-item-content>
    <v-list-item-action class="d-flex flex-row align-center">
      <v-btn
        :aria-label="$t('caldav.calendarStates.manage')"
        :title="$t('caldav.calendarStates.manage')"
        icon
        @click="$root.$emit('open-caldav-calendar-states-drawer', states)">
        <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
      </v-btn>
    </v-list-item-action>
    <caldav-calendar-states-drawer @changed="retrieveStates" />
  </v-list-item>
</template>

<script>
import * as caldavConnectorService from '../js/agendaCaldavService.js';

export default {
  data: () => ({
    states: [],
  }),
  created() {
    // Also on the agenda-wide refresh: a synchronisation is what changes these,
    // and pressing "Sync now" must be able to make the row disappear.
    this.$root.$on('agenda-settings-refresh', this.retrieveStates);
    this.retrieveStates();
  },
  beforeDestroy() {
    this.$root.$off('agenda-settings-refresh', this.retrieveStates);
  },
  methods: {
    /**
     * Reads the calendars whose synchronisation needs attention.
     *
     * A failure leaves the row absent rather than showing it empty: this row
     * exists to name a problem, and one that cannot name any is noise on a
     * page the user came to for something else.
     *
     * @returns {Promise} resolves once the states have been read or given up on
     */
    retrieveStates() {
      return caldavConnectorService.getCalendarSyncStates()
        .then(states => this.states = states || [])
        .catch(error => {
          console.error('cannot read the calendar synchronisation states', error);
          this.states = [];
        });
    },
  },
};
</script>
