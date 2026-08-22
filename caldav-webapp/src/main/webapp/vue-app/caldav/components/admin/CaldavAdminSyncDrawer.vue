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
