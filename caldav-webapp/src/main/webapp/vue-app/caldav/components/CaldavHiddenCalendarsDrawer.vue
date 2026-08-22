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
    id="caldavHiddenCalendarsDrawer"
    ref="caldavHiddenCalendarsDrawer"
    :right="!$vuetify.rtl"
    disable-pull-to-refresh>
    <template slot="title">
      {{ $t('caldav.hiddenCalendars.drawer.title') }}
    </template>
    <template slot="content">
      <div class="pa-4">
        <div class="text-subtitle mb-4">
          {{ $t('caldav.hiddenCalendars.drawer.description') }}
        </div>
        <v-list class="pa-0">
          <v-list-item
            v-for="calendar in calendars"
            :key="calendar.id"
            class="px-0">
            <v-list-item-content>
              <v-list-item-title class="d-flex align-center">
                <!--
                  A grey glyph rather than the calendar's colour: it has none
                  here any more, and inventing one would suggest the calendar
                  is back when it is not. This add-on ships no stylesheet, so
                  the muted tone comes from a Vuetify class, not a custom one.
                -->
                <v-icon size="16" class="me-2 disabled--text">fa-calendar</v-icon>
                <span class="text-truncate">{{ calendar.name }}</span>
              </v-list-item-title>
            </v-list-item-content>
            <v-list-item-action>
              <v-btn
                :aria-label="$t('caldav.hiddenCalendars.showAgain')"
                :loading="restoring === calendar.id"
                :disabled="restoring !== null"
                small
                text
                class="primary--text text-none"
                @click="showAgain(calendar)">
                {{ $t('caldav.hiddenCalendars.showAgain') }}
              </v-btn>
            </v-list-item-action>
          </v-list-item>
        </v-list>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
import * as caldavConnectorService from '../js/agendaCaldavService.js';

export default {
  props: {
    /**
     * The hidden calendars to offer back, read by the row that owns this
     * drawer so both show the same list at the same moment.
     */
    calendars: {
      type: Array,
      default: () => [],
    },
  },
  data: () => ({
    restoring: null,
  }),
  created() {
    this.$root.$on('open-caldav-hidden-calendars-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-caldav-hidden-calendars-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer.
     *
     * @returns {void}
     */
    open() {
      this.$refs.caldavHiddenCalendarsDrawer.open();
    },
    /**
     * Lifts the tombstone hiding one calendar.
     *
     * The calendar does not come back at once — dropping the tombstone lets
     * the next synchronisation find the collection again and recreate it — so
     * the message says so rather than leaving the user watching an agenda
     * that has not changed yet.
     *
     * One at a time: the buttons disable while a restore is in flight, since
     * two overlapping restores would each trigger a synchronisation of the
     * whole account.
     *
     * @param {Object} calendar the hidden calendar to show again
     * @returns {Promise} resolves once the binding has been lifted
     */
    showAgain(calendar) {
      this.restoring = calendar.id;
      return caldavConnectorService.showCalendarAgain(calendar.id)
        .then(() => {
          this.$root.$emit('alert-message',
            this.$t('caldav.hiddenCalendars.showAgainSuccess', {0: calendar.name}),
            'success');
          this.$emit('changed');
        })
        .catch(error => {
          console.error('cannot show the calendar again', error);
          this.$root.$emit('alert-message', this.$t('caldav.hiddenCalendars.showAgainError'), 'error');
        })
        .finally(() => this.restoring = null);
    },
  },
};
</script>
