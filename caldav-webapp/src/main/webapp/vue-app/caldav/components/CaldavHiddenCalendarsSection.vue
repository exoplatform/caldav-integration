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
  <v-list-item v-if="hidden.length">
    <v-list-item-content>
      <v-list-item-title class="text-header">
        {{ $t('caldav.hiddenCalendars.title') }}
      </v-list-item-title>
      <!--
        No vertical margin: the other settings rows on this page sit their
        summary straight under their header, and a row that breathes more than
        its neighbours reads as belonging to another list.
      -->
      <v-list-item-subtitle>
        <span class="text-subtitle">
          {{ $t('caldav.hiddenCalendars.subtitle', {0: hidden.length}) }}
        </span>
      </v-list-item-subtitle>
    </v-list-item-content>
    <v-list-item-action>
      <v-btn
        :aria-label="$t('caldav.hiddenCalendars.manage')"
        :title="$t('caldav.hiddenCalendars.manage')"
        icon
        @click="$root.$emit('open-caldav-hidden-calendars-drawer')">
        <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
      </v-btn>
    </v-list-item-action>
    <caldav-hidden-calendars-drawer
      :calendars="hidden"
      @changed="retrieveHidden" />
  </v-list-item>
</template>

<script>
import * as caldavConnectorService from '../js/agendaCaldavService.js';

export default {
  data: () => ({
    hidden: [],
  }),
  created() {
    // Also on the agenda-wide refresh: showing a calendar again is answered by
    // the next synchronisation, and the row has to disappear once the last one
    // has come back — not wait for the page to be opened again.
    this.$root.$on('agenda-settings-refresh', this.retrieveHidden);
    this.retrieveHidden();
  },
  beforeDestroy() {
    this.$root.$off('agenda-settings-refresh', this.retrieveHidden);
  },
  methods: {
    /**
     * Reads the calendars the user deleted here while keeping them on the
     * server.
     *
     * A failure leaves the row absent rather than showing it empty: this row
     * exists to offer a way back, and one that cannot list anything offers
     * nothing. The settings page must not be held up by it either — the user
     * came for the other rows.
     *
     * @returns {Promise} resolves once the list has been read or given up on
     */
    retrieveHidden() {
      return caldavConnectorService.getHiddenCalendars()
        .then(calendars => this.hidden = calendars || [])
        .catch(error => {
          console.error('cannot read the calendars hidden from this agenda', error);
          this.hidden = [];
        });
    },
  },
};
</script>
