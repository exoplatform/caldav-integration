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
    <div class="text-title mb-1">
      {{ $t('caldav.admin.sync.title') }}
    </div>
    <div class="text-subtitle mb-4">
      {{ $t('caldav.admin.sync.description') }}
    </div>
    <v-form ref="form">
      <div
        v-for="field in fields"
        :key="field.key"
        class="d-flex align-center mb-3">
        <div class="flex-grow-1 pe-4">
          <div>{{ $t(`caldav.admin.sync.${field.key}.label`) }}</div>
          <div class="text-subtitle">{{ $t(`caldav.admin.sync.${field.key}.help`) }}</div>
        </div>
        <v-text-field
          v-model.number="tuning[field.key]"
          :suffix="$t(`caldav.admin.sync.unit.${field.unit}`)"
          :disabled="saving"
          type="number"
          class="caldav-sync-field ignore-vuetify-classes flex-grow-0 pa-0"
          style="max-width: 11rem"
          outlined
          dense
          hide-details />
      </div>
      <div class="d-flex align-center mt-4">
        <v-btn
          :loading="saving"
          :disabled="saving"
          class="btn btn-primary"
          @click="save">
          {{ $t('caldav.admin.sync.save') }}
        </v-btn>
        <span v-if="errorMessage" class="text-subtitle error--text ms-4">
          {{ errorMessage }}
        </span>
      </div>
    </v-form>
  </div>
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
  props: {
    /**
     * Agenda user settings, handed by the admin page to every section. Unused
     * here — this section is about the deployment, not about the reader — but
     * declared so the prop does not land in $attrs.
     */
    settings: {
      type: Object,
      default: () => null,
    },
  },
  data: () => ({
    tuning: {
      throttleMinutes: 15,
      pastDays: 60,
      futureDays: 365,
      sweepStaleMinutes: 30,
      sweepBatchSize: 50,
    },
    saving: false,
    errorMessage: '',
  }),
  computed: {
    fields() {
      return FIELDS;
    },
  },
  created() {
    this.retrieveTuning();
  },
  methods: {
    /**
     * Reads the values in force.
     *
     * A failure leaves the coded defaults on screen rather than an empty form:
     * the administrator has to see numbers to know what they are changing, and
     * these are the ones the engine falls back to anyway.
     *
     * @returns {Promise} resolves once the values have been read or given up on
     */
    retrieveTuning() {
      return caldavConnectorService.getSyncTuning()
        .then(tuning => this.tuning = Object.assign({}, this.tuning, tuning))
        .catch(error => console.error('cannot read the CalDAV synchronisation tuning', error));
    },
    /**
     * Records the values.
     *
     * The screen shows back what the server returned rather than what was
     * typed: the service is what decides, and a value it refused or adjusted
     * must not keep sitting in the form as though it had been accepted.
     *
     * @returns {Promise} resolves once the values have been stored
     */
    save() {
      this.saving = true;
      this.errorMessage = '';
      return caldavConnectorService.saveSyncTuning(this.tuning)
        .then(stored => {
          this.tuning = Object.assign({}, this.tuning, stored);
          this.$root.$emit('alert-message', this.$t('caldav.admin.sync.saved'), 'success');
        })
        .catch(error => {
          const code = error && error.message || '';
          // The body of a refusal is the message code, so the administrator is
          // told which value was out of range rather than that something went
          // wrong.
          this.errorMessage = this.$te(code) ? this.$t(code) : this.$t('caldav.admin.sync.saveFailed');
        })
        .finally(() => this.saving = false);
    },
  },
};
</script>
