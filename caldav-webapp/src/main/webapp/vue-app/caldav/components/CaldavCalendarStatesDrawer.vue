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
    id="caldavCalendarStatesDrawer"
    ref="caldavCalendarStatesDrawer"
    :right="!$vuetify.rtl"
    disable-pull-to-refresh
    @closed="opened = false">
    <template slot="title">
      {{ $t('caldav.calendarStates.drawer.title') }}
    </template>
    <template slot="content">
      <div class="pa-4">
        <div class="text-subtitle mb-4">
          {{ $t('caldav.calendarStates.drawer.description') }}
        </div>
        <div
          v-for="state in states"
          :key="state.id"
          class="mb-5">
          <div class="d-flex align-center mb-1">
            <v-icon size="16" :class="`me-2 ${colourOf(state)}--text`">{{ iconOf(state) }}</v-icon>
            <span class="font-weight-bold text-truncate">{{ state.name }}</span>
          </div>
          <!--
            One sentence per state, saying what happened and what — if
            anything — the user can do. A status name on its own tells someone
            reading their own calendar list nothing at all.
          -->
          <div class="text-subtitle">{{ explain(state) }}</div>
          <div v-if="state.lastSyncEnd" class="text-subtitle">
            {{ $t('caldav.calendarStates.lastSynchronised', {0: sinceLabel(state)}) }}
          </div>
        </div>
        <v-btn
          :loading="syncing"
          :disabled="syncing"
          class="btn btn-primary mt-2"
          @click="syncNow">
          {{ $t('caldav.calendarStates.syncNow') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
import * as caldavConnectorService from '../js/agendaCaldavService.js';

/**
 * What each state means to the user, and how loudly to say it.
 *
 * REMOTE_CREATE_REFUSED is not a failure: the server simply does not let eXo
 * create calendars on it, which is a permanent property of that server and
 * something the user should know rather than something they should fix.
 */
const PRESENTATION = {
  REMOTE_CREATE_REFUSED: {icon: 'fa-info-circle', colour: 'info'},
  PAUSED: {icon: 'fa-exclamation-triangle', colour: 'warning'},
  EXO_ORPHANED: {icon: 'fa-info-circle', colour: 'info'},
  REMOTE_GONE: {icon: 'fa-exclamation-triangle', colour: 'warning'},
};

export default {
  data: () => ({
    states: [],
    opened: false,
    syncing: false,
  }),
  created() {
    this.$root.$on('open-caldav-calendar-states-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-caldav-calendar-states-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer on the states the row is showing.
     *
     * @param {Array} states the states to explain
     * @returns {void}
     */
    open(states) {
      this.states = states || [];
      this.opened = true;
      this.$refs.caldavCalendarStatesDrawer.open();
    },
    /**
     * The sentence for one state.
     *
     * @param {Object} state the state to explain
     * @returns {String} what happened, and what can be done about it
     */
    explain(state) {
      const key = `caldav.calendarStates.explain.${state.status}`;
      return this.$te(key) ? this.$t(key) : this.$t('caldav.calendarStates.explain.unknown');
    },
    /**
     * @param {Object} state the state
     * @returns {String} the icon standing beside it
     */
    iconOf(state) {
      return (PRESENTATION[state.status] || PRESENTATION.PAUSED).icon;
    },
    /**
     * @param {Object} state the state
     * @returns {String} the colour of that icon
     */
    colourOf(state) {
      return (PRESENTATION[state.status] || PRESENTATION.PAUSED).colour;
    },
    /**
     * How long ago this calendar last synchronised, in words.
     *
     * @param {Object} state the state
     * @returns {String} the phrase, empty when it never has
     */
    sinceLabel(state) {
      const phrase = this.$remoteEventConnector
        && this.$remoteEventConnector.lastSyncPhrase(new Date(state.lastSyncEnd));
      return phrase && this.$t(phrase.key, {0: phrase.count}) || '';
    },
    /**
     * Synchronises now, which is the one action that can change any of these.
     *
     * @returns {Promise} resolves once the synchronisation has run
     */
    syncNow() {
      this.syncing = true;
      return caldavConnectorService.syncNow()
        .then(() => {
          this.$root.$emit('alert-message', this.$t('caldav.calendarStates.synchronised'), 'success');
          document.dispatchEvent(new CustomEvent('agenda-refresh-personal-calendars'));
          this.$emit('changed');
          this.$refs.caldavCalendarStatesDrawer.close();
        })
        .catch(error => {
          console.error('cannot synchronise the connected account', error);
          this.$root.$emit('alert-message', this.$t('caldav.calendarStates.syncFailed'), 'error');
        })
        .finally(() => this.syncing = false);
    },
  },
};
</script>
