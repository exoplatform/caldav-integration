<!--
Copyright (C) 2023 eXo Platform SAS.

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
    id="caldavSettingsDrawer"
    ref="caldavSettingsDrawer"
    body-classes="hide-scroll decrease-z-index-more"
    :right="!$vuetify.rtl"
    @closed="cancelConnection"
    disable-pull-to-refresh>
    <template slot="title">
      {{ $t('agenda.caldavCalendar.settings.connect.drawer.title') }}
    </template>
    <template slot="content">
      <v-form ref="form1" class="pa-2 ms-2 mt-4">
        <div class="d-flex flex-column flex-grow-1">
          <div class="d-flex flex-column mb-2">
            <label class="d-flex flex-row font-weight-bold my-2">{{ $t('agenda.caldavCalendar.settings.connect.username.label') }}</label>
            <div class="d-flex flex-row">
              <v-text-field
                v-model="account"
                type="text"
                name="account"
                :error-messages="accountErrorMessage"
                :placeholder="$t('agenda.caldavCalendar.settings.connect.username.placeholder')"
                class="input-block-level ignore-vuetify-classes pa-0"
                outlined
                required
                dense />
            </div>
          </div>
          <div class="d-flex flex-column mb-2">
            <label class="d-flex flex-row font-weight-bold my-2">{{ $t('agenda.caldavCalendar.settings.connect.password.label') }}</label>
            <div class="d-flex flex-row">
              <v-text-field
                v-model="password"
                :type="toggleFieldType"
                name="password"
                :append-icon="displayPasswordIcon"
                :placeholder="$t('agenda.caldavCalendar.settings.connect.password.placeholder') "
                maxlength="100"
                class="input-block-level ignore-vuetify-classes pa-0"
                required
                outlined
                dense
                @click:append="showPassWord = !showPassWord" />
            </div>
          </div>
        </div>
      </v-form>
    </template>
    <template slot="footer">
      <div class="d-flex">
        <v-spacer />
        <v-btn
          class="btn me-2"
          @click="cancelConnection">
          {{ $t('agenda.caldavCalendar.settings.connect.actions.cancel') }}
        </v-btn>
        <v-btn
          :loading="saving"
          :disabled="disableConnectButton"
          class="btn btn-primary"
          @click="saveSettings">
          {{ $t('agenda.caldavCalendar.settings.connect.actions.connect') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>

export default {

  props: {
    /**
     * The declared server this connection is being made to, or null for the
     * legacy single-server connector: {serverId, serverUrl, name}.
     */
    server: {
      type: Object,
      default: () => null,
    },
  },
  data: () => ({
    account: '',
    password: '',
    showPassWord: false,
    connectionSuccess: false,
    saving: false,
    error: '',
    accountErrorMessage: ''
  }),
  computed: {
    displayPasswordIcon() {
      return this.showPassWord ? 'mdi-eye': 'mdi-eye-off';
    },
    toggleFieldType() {
      return this.showPassWord ? 'text': 'password';
    },
    /**
     * Whether Connect is refused: nothing typed yet, or an attempt already
     * under way.
     *
     * `saving` belongs here and not only on `:loading`. Vuetify's v-btn
     * renders the spinner for `loading` but writes `disabled` from `disabled`
     * alone, so a button that is merely loading is still clickable and still
     * fires its handler. Each such click sends the typed credentials to the
     * verify endpoint, which sends one authenticated PROPFIND to the CalDAV
     * server; a user pressing an apparently-busy button while a refusal is in
     * flight therefore multiplied the burst one-for-one — five presses, five
     * credential-bearing requests in nineteen seconds, which is how a source
     * address earns a persistent ban from a server that counts them
     * (EXO-89806).
     *
     * @returns {Boolean} true when the Connect button must not act
     */
    disableConnectButton() {
      return this.saving || this.account === '' || this.account.length < 3 || this.password === '';
    }
  },
  watch: {
    account() {
      if (this.account.length<3) {
        this.accountErrorMessage = this.$t('agenda.caldavCalendar.settings.connect.username.error');
      } else {
        this.accountErrorMessage = '';
      }
    },
    saving() {
      if (this.saving) {
        this.$refs.caldavSettingsDrawer.startLoading();
      } else {
        this.$refs.caldavSettingsDrawer.endLoading();
      }
    },
  },
  methods: {
    /**
     * Opens the drawer holding the CalDAV account form.
     *
     * @returns {void}
     */
    openCaldavDrawer() {
      this.$refs.caldavSettingsDrawer.open();
    },
    /**
     * Closes the drawer holding the CalDAV account form.
     *
     * @returns {void}
     */
    closeCaldavDrawer() {
      this.$refs.caldavSettingsDrawer.close();
    },
    /**
     * Abandons the connection attempt: tells the pending connect() of the
     * agenda connector that nothing was connected, and closes the drawer.
     *
     * @returns {void}
     */
    cancelConnection() {
      document.dispatchEvent(new CustomEvent('test-connection'));
      this.closeCaldavDrawer();
    },
    /**
     * Connects the typed CalDAV account: asks the server itself whether it
     * accepts the credentials, and only then stores them and declares the
     * connector connected.
     *
     * The order matters. Storing first would leave a refused credential
     * behind, half-connecting the account: every later request would answer
     * 401, surfacing as an empty agenda or as the browser's own credentials
     * dialog. So nothing is stored until the server has said yes; on a
     * refusal the drawer stays open with what the user typed, under a message
     * naming the actual failure — credentials refused, server unreachable, or
     * a URL that reaches something that is not a CalDAV calendar.
     *
     * One attempt at a time, and the guard below is what makes that true:
     * `disableConnectButton` now includes `saving`, so a second press while a
     * verification is in flight neither reaches this method nor sends a second
     * credential-bearing request to the calendar server (EXO-89806).
     *
     * @returns {void}
     */
    saveSettings() {
      if (!this.disableConnectButton) {
        this.saving = true;
        // The verification happens on the platform, which is the only party
        // that reaches the CalDAV server now: the typed credentials travel
        // once to the verify endpoint — naming the clicked server by its
        // registration id, never by a URL — and are stored only on
        // acceptance, so nothing half-connected is ever left behind.
        const serverId = this.server && this.server.serverId || null;
        this.$agendaCaldavService.verifyCaldavAccount(serverId, this.account, this.password)
          .then(() => this.$agendaCaldavService.createCaldavSetting({
            'username': this.account,
            'password': this.password,
            'serverId': serverId,
          }))
          .then(() => {
            this.$emit('display-alert', this.$t('agenda.caldavCalendar.settings.connection.successMessage'));
            // The payload deliberately carries the username alone: the
            // pending connect() resolves with it, and no listener has any
            // business receiving the password — the platform holds it now.
            document.dispatchEvent(new CustomEvent('test-connection', {
              detail: {
                username: this.account,
              },
            }));
            this.reset();
            this.closeCaldavDrawer();
          })
          .catch(error => {
            // The drawer stays open, keeping what the user typed: a refusal
            // is something to correct, not a form to fill again.
            this.$emit('display-alert', this.$t(this.errorMessageKey(error)), 'error');
          })
          .finally(() => {
            window.setTimeout(() => {
              this.saving = false;
            }, 200);
          });
      }
    },
    /**
     * Resolves the message shown for a failed connection attempt. The probe
     * rejects with an error carrying one of the stable caldav.error.* codes,
     * which double as translation keys; anything else — the settings REST
     * failing, an unexpected shape — falls back to the generic message.
     *
     * @param {Error} error the failure the connection attempt rejected with
     * @returns {String} the translation key of the message to display
     */
    errorMessageKey(error) {
      // caldav.error.cors is gone with the message it named: the probe runs
      // server-side and CaldavProbeResult has no such outcome, so the code could
      // never arrive and the string told administrators to satisfy a browser
      // constraint that no longer exists.
      const knownCodes = ['caldav.error.credentials', 'caldav.error.connection', 'caldav.error.notCaldav'];
      if (error && knownCodes.includes(error.code)) {
        return error.code;
      }
      return 'agenda.caldavCalendar.settings.connection.errorMessage';
    },
    /**
     * Empties the form, once its content has been stored or abandoned.
     *
     * @returns {void}
     */
    reset() {
      this.account ='';
      this.password = '';
    }
  }

};
</script>