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
  <v-list-item class="pa-0 mb-5" dense>
    <v-list-item-content class="py-0">
      <v-list-item-title class="my-0">
        {{ $t('caldav.admin.servers.writeChannel.label') }}
      </v-list-item-title>
      <div class="text-caption text-sub-title mt-1 mb-2">
        {{ $t('caldav.admin.servers.writeChannel.hint') }}
      </div>
      <!--
        One consequence line per option, as the destination control does: the
        right door depends on what the server behind this registration does
        with a copy written over CalDAV, which the drawer cannot know. So it
        states what each choice costs and lets the administrator decide - and
        flipping it back is the whole rollback of the import door.
      -->
      <v-radio-group
        :value="selected"
        class="mt-0 pt-0"
        hide-details
        @change="choose">
        <div
          v-for="option in options"
          :key="option.value"
          class="mb-4">
          <div class="d-flex align-start">
            <v-radio
              :value="option.value"
              :aria-label="$t(option.labelKey)"
              class="ma-0 pa-0 me-2"
              hide-details />
            <div class="flex-grow-1 text-start">
              <div>{{ $t(option.labelKey) }}</div>
              <div class="text-caption text-sub-title mt-1">
                {{ $t(option.consequenceKey) }}
              </div>
            </div>
          </div>
        </div>
      </v-radio-group>
    </v-list-item-content>
  </v-list-item>
</template>
<script>
import {WRITE_CHANNELS, writeChannelOf} from '../../js/writeChannels.js';

export default {
  props: {
    /**
     * The door currently chosen in the form. Driven through `selected` rather
     * than bound straight through, so a row that states nothing still lands on
     * a real option rather than on an empty group.
     */
    value: {
      type: String,
      default: null,
    },
  },
  computed: {
    /**
     * The options offered, keys and all, in the order the module declares
     * them.
     *
     * @returns {Array} the options to render
     */
    options() {
      return WRITE_CHANNELS;
    },
    /**
     * The option the radio group actually shows as chosen, normalised the way
     * the registry itself reads the column.
     *
     * @returns {String} one of the offered values, never null
     */
    selected() {
      return writeChannelOf(this.value);
    },
  },
  methods: {
    /**
     * Announces the chosen door, always as one of the offered stored values.
     *
     * @param {String} chosen the value the radio group produced
     * @returns {void}
     */
    choose(chosen) {
      this.$emit('input', writeChannelOf(chosen));
    },
  },
};
</script>
