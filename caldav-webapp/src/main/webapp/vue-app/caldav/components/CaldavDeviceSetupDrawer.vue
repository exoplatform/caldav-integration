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
    id="caldavDeviceSetupDrawer"
    ref="caldavDeviceSetupDrawer"
    :right="!$vuetify.rtl"
    disable-pull-to-refresh>
    <template slot="title">
      {{ $t('caldav.deviceSetup.drawer.title') }}
    </template>
    <template slot="content">
      <div class="pa-4">
        <div class="text-subtitle mb-4">
          {{ $t('caldav.deviceSetup.drawer.description') }}
        </div>
        <div class="mb-4">
          <div class="text-subtitle-2 text-color">
            {{ $t('caldav.deviceSetup.serverAddress') }}
          </div>
          <div class="d-flex align-center">
            <!--
              A selectable text node, not an input: when the clipboard API is
              unavailable or refused, the copy button falls back to selecting
              this very node — the value must never be locked behind a dead
              button.
            -->
            <span
              ref="serverAddress"
              class="text-break flex-grow-1 text-subtitle">
              {{ serverUrl }}
            </span>
            <v-btn
              :aria-label="$t('caldav.deviceSetup.copy', {0: $t('caldav.deviceSetup.serverAddress')})"
              :title="$t('caldav.deviceSetup.copy', {0: $t('caldav.deviceSetup.serverAddress')})"
              icon
              small
              @click="copy(serverUrl, 'serverAddress', $t('caldav.deviceSetup.serverAddress'))">
              <v-icon size="16" class="icon-default-color">fa-copy</v-icon>
            </v-btn>
          </div>
        </div>
        <div class="mb-4">
          <div class="text-subtitle-2 text-color">
            {{ $t('caldav.deviceSetup.username') }}
          </div>
          <div class="d-flex align-center">
            <span
              ref="username"
              class="text-break flex-grow-1 text-subtitle">
              {{ username }}
            </span>
            <v-btn
              :aria-label="$t('caldav.deviceSetup.copy', {0: $t('caldav.deviceSetup.username')})"
              :title="$t('caldav.deviceSetup.copy', {0: $t('caldav.deviceSetup.username')})"
              icon
              small
              @click="copy(username, 'username', $t('caldav.deviceSetup.username'))">
              <v-icon size="16" class="icon-default-color">fa-copy</v-icon>
            </v-btn>
          </div>
        </div>
        <div class="text-subtitle mb-6">
          {{ $t('caldav.deviceSetup.passwordNote') }}
        </div>
        <div
          v-for="platform in platforms"
          :key="platform"
          class="mb-4">
          <div class="text-subtitle-2 text-color">
            {{ $t(`caldav.deviceSetup.steps.${platform}.title`) }}
          </div>
          <div class="text-subtitle">
            {{ $t(`caldav.deviceSetup.steps.${platform}.steps`) }}
          </div>
        </div>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  props: {
    /**
     * The address a device must be pointed at: the real server URL, already
     * resolved and with the `{username}` placeholder filled by the row that
     * owns this drawer.
     */
    serverUrl: {
      type: String,
      default: '',
    },
    /**
     * The username of the connected CalDAV account, exactly as the user
     * typed it when connecting — what the device will authenticate as.
     */
    username: {
      type: String,
      default: '',
    },
  },
  data: () => ({
    // In the order a user is most likely to come for: phones first.
    platforms: ['ios', 'android', 'macos', 'thunderbird'],
  }),
  created() {
    this.$root.$on('open-caldav-device-setup-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-caldav-device-setup-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer.
     *
     * @returns {void}
     */
    open() {
      this.$refs.caldavDeviceSetupDrawer.open();
    },
    /**
     * Copies one of the two values to the clipboard, degrading rather than
     * dying: the clipboard API needs a secure context and can be refused, so
     * when it is missing or says no, the value's own text node is selected
     * and the user is told to press the copy shortcut — the button never
     * silently does nothing.
     *
     * @param {String} value the text to copy
     * @param {String} ref name of the template ref holding the value's text
     *          node, selected when the clipboard cannot be written
     * @param {String} label the value's translated label, named in the
     *          confirmation so copying the address and copying the username
     *          do not confirm identically
     * @returns {Promise} resolves once copied or once the fallback selection
     *          is in place
     */
    copy(value, ref, label) {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        return navigator.clipboard.writeText(value)
          .then(() => this.$root.$emit('alert-message', this.$t('caldav.deviceSetup.copied', {0: label}), 'success'))
          .catch(() => this.selectValue(ref));
      }
      return Promise.resolve(this.selectValue(ref));
    },
    /**
     * Selects the text node holding one of the values, so the user can copy
     * it with the keyboard when the clipboard API could not — and says so,
     * because a selection appearing without a word reads as a glitch, not as
     * an instruction.
     *
     * @param {String} ref name of the template ref holding the value's text
     *          node
     * @returns {void}
     */
    selectValue(ref) {
      const node = this.$refs[ref];
      if (node && window.getSelection) {
        const range = document.createRange();
        range.selectNodeContents(node);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
      }
      this.$root.$emit('alert-message', this.$t('caldav.deviceSetup.copyFallback'), 'info');
    },
  },
};
</script>
