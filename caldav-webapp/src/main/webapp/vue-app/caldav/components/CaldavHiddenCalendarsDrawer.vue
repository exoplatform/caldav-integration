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
    disable-pull-to-refresh
    @closed="opened = false">
    <template slot="title">
      {{ $t('caldav.hiddenCalendars.drawer.title') }}
    </template>
    <template slot="content">
      <div class="pa-4">
        <!--
          The neutral sentence, not the older one about calendars "you
          deleted": since EXO-90239 the list also holds calendars shared with
          the user, and a description that names only one kind is wrong in
          front of the other. The older key stays in the bundle untouched —
          existing keys are Crowdin's to change.
        -->
        <div class="text-subtitle mb-4">
          {{ $t('caldav.hiddenCalendars.drawer.about') }}
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
                  The same glyph for both kinds: what the row is about is the
                  calendar, and the line under it says which kind.
                -->
                <v-icon size="16" class="me-2 disabled--text">fa-calendar</v-icon>
                <span class="text-truncate">{{ calendar.name }}</span>
              </v-list-item-title>
              <v-list-item-subtitle>
                {{ kindOf(calendar) }}
              </v-list-item-subtitle>
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
  watch: {
    calendars(current) {
      // Nothing left to offer: the drawer has said all it can, and leaving an
      // empty panel open makes the user close it themselves to find out
      // whether anything happened.
      if (this.opened && !current.length) {
        this.$refs.caldavHiddenCalendarsDrawer.close();
      }
    },
  },
  data: () => ({
    restoring: null,
    opened: false,
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
      this.opened = true;
      this.$refs.caldavHiddenCalendarsDrawer.open();
    },
    /**
     * What kind of hidden calendar a row is, in the user's words.
     *
     * A share names whoever shared it when the platform could tell — the
     * colleague's name, or what the server calls the owner — and says only
     * that it was shared when it could not: an unnamed owner is not a reason
     * to say nothing about why the calendar is read-only and not theirs. A
     * calendar of the user's own says it was deleted here and kept on the
     * account, which is what the older description used to say of every row.
     *
     * @param {Object} calendar the hidden calendar
     * @returns {String} the line under its name
     */
    kindOf(calendar) {
      if (!calendar.shared) {
        return this.$t('caldav.hiddenCalendars.deletedHere');
      }
      return calendar.ownerDisplayName
        ? this.$t('caldav.hiddenCalendars.sharedBy', {0: calendar.ownerDisplayName})
        : this.$t('caldav.hiddenCalendars.sharedWithYou');
    },
    /**
     * Lifts the tombstone hiding one calendar.
     *
     * The calendar does not come back at once — dropping the tombstone lets
     * the next synchronisation find the collection again and recreate it — so
     * the message says so rather than leaving the user watching an agenda
     * that has not changed yet. A share is the other way round (EXO-90239):
     * nothing is synchronised and it is back under Shared with me on the next
     * listing, so its message says that instead.
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
            this.$t(calendar.shared
              ? 'caldav.hiddenCalendars.showAgainSharedSuccess'
              : 'caldav.hiddenCalendars.showAgainSuccess', {0: calendar.name}),
            'success');
          // The calendar is already back by the time this resolves — the
          // server synchronises before answering, and a share is unbound
          // again the moment its record is gone — so everything showing
          // calendars has to be told. Without this the only visible change
          // was inside this drawer, and the user had to reload the page to
          // see the calendar they had just asked for. The Remote section,
          // where a share comes back, re-reads on the same two root events
          // the personal list does (agenda-refresh-personal-calendars,
          // agenda-refresh), so one set of signals covers both kinds.
          document.dispatchEvent(new CustomEvent('agenda-refresh-personal-calendars'));
          this.$root.$emit('agenda-refresh-personal-calendars');
          this.$root.$emit('agenda-refresh');
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
