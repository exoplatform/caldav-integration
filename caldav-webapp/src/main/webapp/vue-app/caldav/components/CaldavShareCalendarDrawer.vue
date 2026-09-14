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
    id="caldavShareCalendarDrawer"
    ref="caldavShareCalendarDrawer"
    :right="!$vuetify.rtl"
    disable-pull-to-refresh
    @closed="opened = false">
    <template slot="title">
      {{ $t('caldav.share.drawer.title', {0: calendarName}) }}
    </template>
    <template slot="content">
      <div class="pa-4">
        <div class="text-subtitle mb-4">
          {{ $t('caldav.share.drawer.about') }}
        </div>
        <!--
          The platform's identity suggester, restricted to the colleagues the
          platform computed: only an eXo user connected to the same calendar
          server can be named there, so a free search over every user would
          offer people a share could only refuse. Those already shared with are
          left out of the list rather than offered twice.
        -->
        <div class="d-flex align-center">
          <identity-suggester
            ref="shareeSuggester"
            v-model="selected"
            :labels="suggesterLabels"
            :items="candidateItems"
            :disabled="loading || busy"
            name="caldavShareSuggester"
            class="flex-grow-1"
            include-only-items
            include-users
            ignore-cache />
          <v-btn
            :disabled="!selectedUsername || busy"
            :loading="saving"
            class="btn btn-primary ms-2"
            @click="share">
            {{ $t('caldav.share.add') }}
          </v-btn>
        </div>
        <div class="caption text-sub-title mt-1">
          {{ $t('caldav.share.drawer.sameServerOnly') }}
        </div>
        <!--
          A refusal is shown as the server stated it: the sentence for its
          code, and the privilege it said was missing when it named one. A
          generic failure would leave the owner guessing whether to try again.
        -->
        <v-alert
          v-if="errorMessage"
          type="error"
          class="mt-4 mb-0"
          dense
          outlined>
          {{ errorMessage }}
        </v-alert>
        <div class="text-header-title mt-6 mb-2">
          {{ $t('caldav.share.sharees.title') }}
        </div>
        <div v-if="loading" class="d-flex justify-center py-4">
          <v-progress-circular
            color="primary"
            size="20"
            width="2"
            indeterminate />
        </div>
        <div v-else-if="!sharees.length" class="text-sub-title">
          {{ $t('caldav.share.sharees.none') }}
        </div>
        <v-list
          v-else
          class="pa-0"
          dense>
          <v-list-item
            v-for="sharee in sharees"
            :key="sharee.principal"
            class="px-0">
            <!--
              The platform's user-avatar for a colleague, in its picture-only
              mode with its popover, as agenda's Shared with me rows show an
              owner: it renders a disabled or deleted account, which a bare
              image would not. It looks the user up once per row, which a list
              of the few people a calendar is shared with can afford. A
              principal eXo cannot name, and everyone, keep a plain icon.
            -->
            <v-list-item-avatar size="32" class="me-3">
              <user-avatar
                v-if="avatarUserOf(sharee)"
                :profile-id="avatarUserOf(sharee).username"
                :avatar-url="avatarUserOf(sharee).avatarUrl"
                :name="avatarUserOf(sharee).fullName"
                :size="32"
                avatar
                popover />
              <v-icon
                v-else
                size="18"
                class="disabled--text">
                {{ sharee.kind === 'EVERYONE' ? 'fa-users' : 'fa-user' }}
              </v-icon>
            </v-list-item-avatar>
            <v-list-item-content>
              <v-list-item-title class="text-truncate">
                {{ nameOf(sharee) }}
              </v-list-item-title>
              <v-list-item-subtitle>
                {{ accessOf(sharee) }}
              </v-list-item-subtitle>
            </v-list-item-content>
            <v-list-item-action v-if="sharee.removable" class="my-0">
              <v-btn
                :title="$t('caldav.share.remove')"
                :aria-label="$t('caldav.share.removeFrom', {0: nameOf(sharee)})"
                :loading="removing === sharee.principal"
                :disabled="busy"
                icon
                small
                @click="unshare(sharee)">
                <v-icon size="16">fa-times</v-icon>
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
  data: () => ({
    opened: false,
    calendar: null,
    sharees: [],
    candidates: [],
    selected: null,
    loading: false,
    saving: false,
    removing: null,
    errorMessage: '',
  }),
  computed: {
    /**
     * The calendar's name, for the title.
     *
     * @returns {String} the name, empty before the drawer was opened
     */
    calendarName() {
      return this.calendar && this.calendar.name || '';
    },
    /**
     * Whether a change is in flight: one at a time, since each answers the
     * whole list as read back and two would race to draw it.
     *
     * @returns {Boolean} true while sharing or removing
     */
    busy() {
      return this.saving || this.removing !== null;
    },
    /**
     * The logins already shared with, left out of the picker.
     *
     * @returns {Array} the usernames
     */
    sharedUsernames() {
      return this.sharees.flatMap(sharee => (sharee.users || []).map(user => user.username));
    },
    /**
     * The colleagues offered, in the shape the identity suggester draws.
     *
     * @returns {Array} identity-shaped items
     */
    candidateItems() {
      return this.candidates
        .filter(candidate => !this.sharedUsernames.includes(candidate.username))
        .map(candidate => ({
          id: `organization:${candidate.username}`,
          providerId: 'organization',
          remoteId: candidate.username,
          profile: {
            fullName: candidate.fullName || candidate.username,
            avatarUrl: candidate.avatarUrl,
          },
        }));
    },
    /**
     * The login of the colleague picked, whichever shape the suggester answers.
     *
     * @returns {String} the login, or null
     */
    selectedUsername() {
      const selected = Array.isArray(this.selected) ? this.selected[0] : this.selected;
      return selected && selected.remoteId || null;
    },
    /**
     * The suggester's labels.
     *
     * @returns {Object} the labels
     */
    suggesterLabels() {
      return {
        placeholder: this.$t('caldav.share.picker.placeholder'),
        noDataLabel: this.$t('caldav.share.picker.noData'),
      };
    },
  },
  methods: {
    /**
     * Opens the drawer on one calendar and reads its sharees and the
     * colleagues it can be shared with.
     *
     * @param {Object} calendar the agenda calendar, {id, name}
     * @returns {Promise} resolves once both are read
     */
    open(calendar) {
      this.calendar = calendar;
      this.sharees = [];
      this.candidates = [];
      this.selected = null;
      this.errorMessage = '';
      this.opened = true;
      this.$refs.caldavShareCalendarDrawer.open();
      return this.load();
    },
    /**
     * Reads the sharees from the server and the candidates from the platform.
     *
     * An answer for a calendar the drawer no longer shows is dropped: opening
     * it on another calendar before the first answered must not draw the first
     * one's sharees under the second one's name.
     *
     * @returns {Promise} resolves once both are read
     */
    load() {
      const calendarId = this.calendar && this.calendar.id;
      this.loading = true;
      return Promise.all([
        caldavConnectorService.getCalendarShares(calendarId),
        caldavConnectorService.getShareCandidates(calendarId),
      ]).then(([shares, candidates]) => {
        if (this.isShowing(calendarId)) {
          this.sharees = shares && shares.sharees || [];
          this.candidates = candidates || [];
        }
      }).catch(error => {
        if (this.isShowing(calendarId)) {
          this.showError(error);
        }
      }).finally(() => {
        if (this.isShowing(calendarId)) {
          this.loading = false;
        }
      });
    },
    /**
     * Shares the calendar with the colleague picked, then shows the list as
     * the server holds it afterwards.
     *
     * @returns {Promise} resolves once the server answered
     */
    share() {
      const username = this.selectedUsername;
      if (!username || this.busy) {
        return Promise.resolve();
      }
      const calendarId = this.calendar.id;
      const name = this.candidateName(username);
      this.saving = true;
      this.errorMessage = '';
      return caldavConnectorService.shareCalendar(calendarId, username)
        .then(shares => {
          if (!this.isShowing(calendarId)) {
            return;
          }
          this.sharees = shares && shares.sharees || [];
          this.selected = null;
          this.$root.$emit('alert-message', this.$t('caldav.share.added', {0: name}), 'success');
        })
        .catch(error => this.isShowing(calendarId) && this.showError(error))
        .finally(() => this.saving = false);
    },
    /**
     * Stops sharing the calendar with one sharee eXo can name.
     *
     * @param {Object} sharee the sharee row
     * @returns {Promise} resolves once the server answered
     */
    unshare(sharee) {
      const user = sharee && sharee.users && sharee.users[0];
      if (!user || this.busy) {
        return Promise.resolve();
      }
      const calendarId = this.calendar.id;
      const name = this.nameOf(sharee);
      this.removing = sharee.principal;
      this.errorMessage = '';
      return caldavConnectorService.unshareCalendar(calendarId, user.username)
        .then(shares => {
          if (!this.isShowing(calendarId)) {
            return;
          }
          this.sharees = shares && shares.sharees || [];
          this.$root.$emit('alert-message', this.$t('caldav.share.removed', {0: name}), 'success');
        })
        .catch(error => this.isShowing(calendarId) && this.showError(error))
        .finally(() => this.removing = null);
    },
    /**
     * Whether the drawer still shows a calendar.
     *
     * @param {Number} calendarId the calendar a request was made for
     * @returns {Boolean} true when it is the one shown
     */
    isShowing(calendarId) {
      return !!this.calendar && this.calendar.id === calendarId;
    },
    /**
     * Puts a refusal into words: the sentence for its code, the privilege the
     * server said was missing, and any other precondition it named.
     *
     * @param {Error} error the coded error agendaCaldavService rejects with
     * @returns {void}
     */
    showError(error) {
      const code = error && error.code;
      let message = this.$t(code && code.startsWith('caldav.share.') ? code : 'caldav.share.error.generic');
      const privileges = error && error.missingPrivileges || [];
      if (privileges.length) {
        message = `${message} ${this.$t('caldav.share.error.missingPrivileges', {0: privileges.join(', ')})}`;
      }
      // Only the preconditions this add-on can put into words. A raw RFC name,
      // or a vendor's own condition, means nothing to the owner; it is in the
      // platform's log line, where the server's refusal is written in full.
      const explained = (error && error.preconditions || [])
        .map(precondition => `caldav.share.precondition.${precondition}`)
        .filter(key => typeof this.$te === 'function' && this.$te(key))
        .map(key => this.$t(key));
      if (explained.length) {
        message = `${message} ${this.$t('caldav.share.error.preconditions', {0: explained.join('; ')})}`;
      }
      this.errorMessage = message;
    },
    /**
     * What a sharee row is called: the eXo users connected as the principal,
     * or what the server calls a principal no eXo user is, or everyone.
     *
     * @param {Object} sharee the sharee row
     * @returns {String} the name
     */
    nameOf(sharee) {
      if (sharee.kind === 'EVERYONE') {
        return this.$t('caldav.share.everyone');
      }
      if (sharee.users && sharee.users.length) {
        return sharee.users.map(user => user.fullName || user.username).join(', ');
      }
      return sharee.displayName || sharee.principal;
    },
    /**
     * The line under a sharee's name: what they can do, and for a principal
     * outside eXo, that eXo cannot name them.
     *
     * @param {Object} sharee the sharee row
     * @returns {String} the line
     */
    accessOf(sharee) {
      const access = this.$t(sharee.access === 'READ' ? 'caldav.share.access.read' : 'caldav.share.access.more');
      return sharee.kind === 'OUTSIDE_EXO' ? `${this.$t('caldav.share.outsideExo')} · ${access}` : access;
    },
    /**
     * The one eXo user a sharee row shows an avatar for: a principal a single
     * colleague is connected as. Several users on one login, a principal eXo
     * cannot name, and everyone get an icon instead.
     *
     * @param {Object} sharee the sharee row
     * @returns {Object} the user, or null
     */
    avatarUserOf(sharee) {
      return sharee.users && sharee.users.length === 1 && sharee.users[0] || null;
    },
    /**
     * A candidate's full name, for the confirmation.
     *
     * @param {String} username the login
     * @returns {String} the full name, else the login
     */
    candidateName(username) {
      const candidate = this.candidates.find(one => one.username === username);
      return candidate && candidate.fullName || username;
    },
  },
};
</script>
