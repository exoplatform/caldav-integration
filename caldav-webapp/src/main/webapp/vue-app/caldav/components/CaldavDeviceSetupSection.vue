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
  <v-list-item v-if="connected">
    <v-list-item-content>
      <!-- text-color, matching the E-mail and calendar rows of this page -->
      <v-list-item-title class="text-color">
        {{ $t('caldav.deviceSetup.title') }}
      </v-list-item-title>
      <!--
        No vertical margin, like its sibling rows: in the healthy case this is
        the ONLY nested row under My Calendars — its siblings only appear when
        something needs attention — so it has to sit like a native row of the
        page, not like the survivor of a list.
      -->
      <v-list-item-subtitle>
        <span>
          {{ $t('caldav.deviceSetup.subtitle') }}
        </span>
      </v-list-item-subtitle>
    </v-list-item-content>
    <v-list-item-action>
      <v-btn
        :aria-label="$t('caldav.deviceSetup.action')"
        :title="$t('caldav.deviceSetup.action')"
        small
        text
        class="primary--text text-none"
        @click="$root.$emit('open-caldav-device-setup-drawer')">
        {{ $t('caldav.deviceSetup.action') }}
      </v-btn>
    </v-list-item-action>
    <caldav-device-setup-drawer
      :server-url="deviceUrl"
      :username="setting.username" />
  </v-list-item>
</template>

<script>
import * as caldavConnectorService from '../js/agendaCaldavService.js';
import {deviceCaldavUrl} from '../js/deviceSetup.js';

export default {
  data: () => ({
    setting: null,
  }),
  computed: {
    /**
     * The address a device must be pointed at: the real server URL with the
     * `{username}` placeholder filled — never the relay, which only exists to
     * get this browser past CORS.
     *
     * @returns {String} the URL to show, or an empty string when none can be
     *          built
     */
    deviceUrl() {
      return deviceCaldavUrl(this.setting && this.setting.caldavUrl, this.setting && this.setting.username);
    },
    /**
     * Whether there is anything to set up: an account is connected and a URL
     * a device can actually use exists. Without both, the row says nothing
     * actionable and stays out of the page.
     *
     * @returns {Boolean} true when the row has something to offer
     */
    connected() {
      return !!(this.setting && this.setting.username && this.deviceUrl);
    },
  },
  created() {
    // Also on the agenda-wide refresh: connecting or unlinking the account
    // happens on this very page, and the row has to appear or go with it —
    // not wait for the page to be opened again.
    this.$root.$on('agenda-settings-refresh', this.retrieveSetting);
    this.retrieveSetting();
  },
  beforeDestroy() {
    this.$root.$off('agenda-settings-refresh', this.retrieveSetting);
  },
  methods: {
    /**
     * Reads the CalDAV settings of the current user — the username of the
     * connected account and the resolved URL of the server it speaks to.
     *
     * A failure leaves the row absent rather than showing it broken: a setup
     * row that cannot say where to point the device offers nothing, and the
     * settings page must not be held up by it — the user came for the other
     * rows.
     *
     * @returns {Promise} resolves once the settings have been read or given
     *          up on
     */
    retrieveSetting() {
      return caldavConnectorService.getCaldavSetting()
        .then(setting => this.setting = setting || null)
        .catch(error => {
          console.error('cannot read the caldav account settings', error);
          this.setting = null;
        });
    },
  },
};
</script>
