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
    id="caldavSyncTuningDrawer"
    ref="caldavSyncTuningDrawer"
    v-model="opened"
    :loading="saving"
    right
    @closed="close">
    <template #title>
      <span>{{ $t('caldav.admin.sync.title') }}</span>
    </template>
    <template v-if="opened" #content>
      <form
        ref="tuningForm"
        class="mx-5 mt-5"
        @submit.stop.prevent="0">
        <div class="text-subtitle mb-6">
          {{ $t('caldav.admin.sync.description') }}
        </div>
        <!--
          One field per line, label above and the reason under it: these are
          five numbers whose consequences are not guessable from their names,
          and an administrator setting them once a year should not have to
          remember what a wide window costs.
        -->
        <div
          v-for="field in fields"
          :key="field.key"
          class="mb-6">
          <label class="font-weight-bold d-block mb-1">
            {{ $t(`caldav.admin.sync.${field.key}.label`) }}
          </label>
          <div class="text-subtitle mb-2">
            {{ $t(`caldav.admin.sync.${field.key}.help`) }}
          </div>
          <v-text-field
            v-model.number="tuning[field.key]"
            :suffix="$t(`caldav.admin.sync.unit.${field.unit}`)"
            :disabled="saving"
            type="number"
            class="input-block-level ignore-vuetify-classes pa-0"
            outlined
            dense
            hide-details />
        </div>
        <v-alert
          v-if="errorMessage"
          type="error"
          dense
          text>
          {{ errorMessage }}
        </v-alert>
        <!--
          Always rendered, empty or not, for the reason the observed-behaviour
          section of the server drawer is: a section that vanishes when there is
          nothing in it reads as "not supported here", and an administrator who
          has just changed where meeting copies go cannot tell that from "no
          account has synchronised yet". The empty state says which.
        -->
        <v-list-item-title class="pa-0 mt-7 mb-2 text-header">
          {{ $t('caldav.admin.sync.reports.title') }}
        </v-list-item-title>
        <div class="text-caption text-sub-title mb-4">
          {{ reports.length && $t('caldav.admin.sync.reports.subtitle')
            || $t('caldav.admin.sync.reports.none') }}
        </div>
        <div
          v-if="reportsError"
          class="text-caption error--text mb-4">
          {{ $t('caldav.admin.sync.reports.loadFailed') }}
        </div>
        <div
          v-for="report in reports"
          :key="report.userIdentityId"
          class="mb-4">
          <div class="font-weight-bold">{{ reportName(report) }}</div>
          <div class="text-caption text-sub-title">{{ reportDate(report) }}</div>
          <div class="text-caption text-sub-title mt-1">
            {{ $t('caldav.admin.sync.reports.checked', checkedArgs(report)) }}
          </div>
          <!--
            The move is stated only when there was one. The ordinary pass owes
            no change of destination and its tally is all zeros; printing
            "0 moved" on every row would bury the accounts where a change is
            genuinely still working itself through.
          -->
          <div
            v-if="relocated(report)"
            class="text-caption mt-1 font-weight-bold">
            {{ $t('caldav.admin.sync.reports.moved', movedArgs(report)) }}
          </div>
          <div
            v-else
            class="text-caption text-sub-title mt-1">
            {{ $t('caldav.admin.sync.reports.notMoved') }}
          </div>
        </div>
      </form>
    </template>
    <template #footer>
      <div class="d-flex">
        <v-spacer />
        <v-btn
          class="btn"
          @click="close">
          {{ $t('caldav.admin.sync.cancel') }}
        </v-btn>
        <v-btn
          :disabled="saving"
          class="btn btn-primary ms-5"
          @click="save">
          {{ $t('caldav.admin.sync.save') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
import * as caldavConnectorService from '../../js/agendaCaldavService.js';

/**
 * The values, in the order they matter to someone tuning a deployment: how
 * often first, then how wide, then the background sweep. Each carries the unit
 * it is expressed in, so the field says what it means without a sentence.
 *
 * The sweep's cron is deliberately absent. It cannot be changed at runtime —
 * Spring reads it when the schedule is built — so a field for it would be
 * exactly the lie this whole screen exists to avoid.
 */
const FIELDS = [
  {key: 'throttleMinutes', unit: 'minutes'},
  {key: 'pastDays', unit: 'days'},
  {key: 'futureDays', unit: 'days'},
  {key: 'sweepStaleMinutes', unit: 'minutes'},
  {key: 'sweepBatchSize', unit: 'calendars'},
];

export default {
  data: () => ({
    opened: false,
    tuning: {},
    saving: false,
    errorMessage: '',
    /**
     * The last pass over each connected user's meeting copies. Read when the
     * drawer opens rather than kept up to date: it is a reading taken now, and
     * a number that refreshes under somebody comparing two rows is worse than
     * one that plainly belongs to the moment they opened the screen.
     */
    reports: [],
    /**
     * Whether the tallies could not be read. Its own flag rather than the save
     * error above: a failure to READ the tallies must not look like a refusal
     * to save the values the administrator just typed.
     */
    reportsError: false,
  }),
  computed: {
    fields() {
      return FIELDS;
    },
  },
  created() {
    this.$root.$on('open-caldav-sync-tuning-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-caldav-sync-tuning-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer on a copy of the values the section is showing.
     *
     * A copy, so that closing without saving leaves the section displaying
     * what is actually in force rather than what someone half typed.
     *
     * @param {Object} tuning the values in force
     * @returns {void}
     */
    open(tuning) {
      this.tuning = Object.assign({}, tuning);
      this.errorMessage = '';
      this.opened = true;
      this.$refs.caldavSyncTuningDrawer.open();
      this.loadReports();
    },
    /**
     * Reads the per-user tallies, and never lets their absence keep the values
     * off the screen.
     *
     * The drawer's reason to exist is the five settings above; the tallies are
     * an aid beside them. A platform that cannot answer for them leaves a line
     * saying so and everything else usable.
     *
     * @returns {Promise} resolves once the tallies have been read, or given up on
     */
    loadReports() {
      this.reports = [];
      this.reportsError = false;
      return caldavConnectorService.getMirrorReports()
        .then(reports => this.reports = reports || [])
        .catch(() => this.reportsError = true);
    },
    /**
     * The user a tally is about, by the name an administrator would recognise:
     * their display name, else their login, else the identity behind it.
     *
     * Never blank. A row with no name at all is a tally an administrator cannot
     * act on, and the identity — however technical — is at least something they
     * can look up.
     *
     * @param {Object} report one user's tally
     * @returns {String} how to name that user
     */
    reportName(report) {
      return report.fullName || report.username
        || this.$t('caldav.admin.sync.reports.unknownUser', {0: report.userIdentityId});
    },
    /**
     * When the pass ran, in the reader's own locale.
     *
     * @param {Object} report one user's tally
     * @returns {String} the moment the pass ended, or an empty string
     */
    reportDate(report) {
      return report.at && new Date(report.at).toLocaleString() || '';
    },
    /**
     * What the comparison found, as the numbered arguments its sentence takes.
     *
     * @param {Object} report one user's tally
     * @returns {Object} the arguments of the comparison sentence
     */
    checkedArgs(report) {
      const verification = report.verification || {};
      return {
        0: verification.checked || 0,
        1: verification.missing || 0,
        2: verification.altered || 0,
        3: verification.repaired || 0,
        4: verification.abandoned || 0,
      };
    },
    /**
     * What the move did, as the numbered arguments its sentence takes.
     *
     * @param {Object} report one user's tally
     * @returns {Object} the arguments of the move sentence
     */
    movedArgs(report) {
      const relocation = report.relocation || {};
      return {
        0: relocation.moved || 0,
        1: relocation.refused || 0,
        2: relocation.failed || 0,
      };
    },
    /**
     * Whether this pass actually moved copies between calendars.
     *
     * Computed here rather than read off the payload: the counts are what the
     * platform states, and a derived flag would be a second answer to the same
     * question that could disagree with the numbers printed beside it.
     *
     * @param {Object} report one user's tally
     * @returns {Boolean} true when the pass tried to move at least one copy
     */
    relocated(report) {
      const relocation = report.relocation || {};
      return !!(relocation.moved || relocation.refused || relocation.failed || relocation.unmovable);
    },
    /**
     * Closes the drawer, discarding whatever was typed.
     *
     * @returns {void}
     */
    close() {
      this.opened = false;
      this.$refs.caldavSyncTuningDrawer.close();
    },
    /**
     * Records the values.
     *
     * What the server returns is what the section then shows: the service is
     * what decides, and a value it refused must not sit on screen as though it
     * had been accepted.
     *
     * @returns {Promise} resolves once the values have been stored
     */
    save() {
      this.saving = true;
      this.errorMessage = '';
      return caldavConnectorService.saveSyncTuning(this.tuning)
        .then(stored => {
          this.$emit('saved', stored);
          this.$root.$emit('alert-message', this.$t('caldav.admin.sync.saved'), 'success');
          this.close();
        })
        .catch(error => {
          // The body of a refusal is the message code, so the administrator is
          // told which value was out of range rather than that something went
          // wrong. The drawer stays open on what they typed.
          const code = error && error.message || '';
          this.errorMessage = this.$te(code) ? this.$t(code) : this.$t('caldav.admin.sync.saveFailed');
        })
        .finally(() => this.saving = false);
    },
  },
};
</script>
