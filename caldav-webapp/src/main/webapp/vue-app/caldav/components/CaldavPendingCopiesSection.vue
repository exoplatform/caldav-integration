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
<!--
  What eXo owes this account's calendar and has not managed to write.

  The row exists because the backstop that records those writes was, until
  now, addressed to whoever reads the server log: a copy that could not be
  written left a row, a later pass settled it, and the person whose calendar
  it was saw only a meeting that was not there — which is indistinguishable
  from the feature being broken, and is exactly how it was reported.

  Drawn only while something is outstanding, like the two rows beside it: a
  line confirming that nothing is wrong is noise on a page the user came to
  for something else.
-->
<template>
  <v-list-item v-if="pending > 0">
    <v-list-item-content>
      <v-list-item-title class="text-color">
        {{ $t('caldav.pendingCopies.title') }}
      </v-list-item-title>
      <v-list-item-subtitle>
        <span>{{ $t('caldav.pendingCopies.subtitle', {0: pending}) }}</span>
      </v-list-item-subtitle>
    </v-list-item-content>
    <v-list-item-action class="d-flex flex-row align-center">
      <v-icon size="20" class="icon-default-color">fa-clock</v-icon>
    </v-list-item-action>
  </v-list-item>
</template>

<script>
import * as caldavConnectorService from '../js/agendaCaldavService.js';

export default {
  data: () => ({
    pending: 0,
  }),
  created() {
    // Also on the agenda-wide refresh: a synchronisation is what settles these,
    // so pressing "Sync now" must be able to make the row disappear.
    this.$root.$on('agenda-settings-refresh', this.retrievePending);
    this.retrievePending();
  },
  beforeDestroy() {
    this.$root.$off('agenda-settings-refresh', this.retrievePending);
  },
  methods: {
    /**
     * Reads how many copies eXo is still trying to write.
     *
     * A failure leaves the row absent rather than showing a number nobody can
     * trust: this row exists to say that something is outstanding, and one
     * that cannot say how much is worse than none at all.
     *
     * @returns {Promise} resolves once the count has been read or given up on
     */
    retrievePending() {
      return caldavConnectorService.getOwedCopies()
        .then(pending => this.pending = Number(pending) || 0)
        .catch(error => {
          console.error('cannot read the meeting copies eXo still owes', error);
          this.pending = 0;
        });
    },
  },
};
</script>
