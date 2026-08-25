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
        <div class="text-header mb-4">
          {{ $t('caldav.deviceSetup.drawer.description') }}
        </div>
        <!--
          The MCP server settings drawer's idiom for a value the user has to
          carry elsewhere: a read-only outlined field with the copy button
          inside it. The field is also what keeps the fallback honest — when
          the clipboard is unavailable or refused, the value is still an
          input the user can select and copy by hand, never text locked
          behind a dead button.
        -->
        <div class="text-header mb-1">
          {{ $t('caldav.deviceSetup.serverAddress') }}
        </div>
        <v-card
          class="border-box-sizing mb-4"
          min-width="180"
          max-width="100%"
          flat>
          <v-text-field
            ref="serverAddress"
            :value="serverUrl"
            class="pa-0 ma-0"
            outlined
            readonly
            dense>
            <template #append>
              <div class="pt-2px me-n1">
                <v-btn
                  :aria-label="$t('caldav.deviceSetup.copy', {0: $t('caldav.deviceSetup.serverAddress')})"
                  :title="$t('caldav.deviceSetup.copy', {0: $t('caldav.deviceSetup.serverAddress')})"
                  class="mt-n1"
                  icon
                  small
                  @click="copy(serverUrl, 'serverAddress', $t('caldav.deviceSetup.serverAddress'))">
                  <v-icon size="18" class="icon-default-color">fa-copy</v-icon>
                </v-btn>
              </div>
            </template>
          </v-text-field>
        </v-card>
        <div class="text-header mb-1">
          {{ $t('caldav.deviceSetup.username') }}
        </div>
        <v-card
          class="border-box-sizing mb-4"
          min-width="180"
          max-width="100%"
          flat>
          <v-text-field
            ref="username"
            :value="username"
            class="pa-0 ma-0"
            outlined
            readonly
            dense>
            <template #append>
              <div class="pt-2px me-n1">
                <v-btn
                  :aria-label="$t('caldav.deviceSetup.copy', {0: $t('caldav.deviceSetup.username')})"
                  :title="$t('caldav.deviceSetup.copy', {0: $t('caldav.deviceSetup.username')})"
                  class="mt-n1"
                  icon
                  small
                  @click="copy(username, 'username', $t('caldav.deviceSetup.username'))">
                  <v-icon size="18" class="icon-default-color">fa-copy</v-icon>
                </v-btn>
              </div>
            </template>
          </v-text-field>
        </v-card>
        <div class="text-header">
          {{ $t('caldav.deviceSetup.passwordNote') }}
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
     * Selects the value in its field, so the user can copy it with the
     * keyboard when the clipboard API could not — and says so, because a
     * selection appearing without a word reads as a glitch rather than as an
     * instruction.
     *
     * The value lives in a read-only input, so the selection is the input's
     * own: reaching for its DOM node rather than the component, because a
     * range over a component is not a thing the browser can select.
     *
     * @param {String} ref name of the template ref holding the value's field
     * @returns {void}
     */
    selectValue(ref) {
      const field = this.$refs[ref];
      const input = field && field.$el && field.$el.querySelector('input');
      if (input) {
        input.focus();
        input.select();
      }
      this.$root.$emit('alert-message', this.$t('caldav.deviceSetup.copyFallback'), 'info');
    },
  },
};
</script>
