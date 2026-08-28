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
        {{ $t('caldav.admin.servers.preset.label') }}
      </v-list-item-title>
      <v-select
        id="caldavServerPreset"
        v-model="presetId"
        :items="items"
        :aria-label="$t('caldav.admin.servers.preset.label')"
        class="pt-3"
        item-text="text"
        item-value="value"
        name="caldavServerPreset"
        outlined
        dense
        hide-details
        @change="choose" />
      <div class="text-caption text-sub-title mt-2">
        {{ summary }}
      </div>
      <!--
        A preset that changes what eXo WRITES into somebody's calendar says so
        on its own line, in the same words the quirk check-boxes use. Most of
        what a preset carries only relaxes a comparison and is reversible with
        no trace; this one alters the document that lands in a user's calendar,
        and a shortcut that does that must not read like one that does not.
      -->
      <div
        v-if="changesWhatIsWritten"
        class="text-caption font-weight-bold mt-1">
        <v-icon class="me-1" size="14">fas fa-pen</v-icon>
        {{ $t('caldav.admin.servers.preset.changesWhatIsWritten') }}
      </div>
    </v-list-item-content>
  </v-list-item>
</template>
<script>
import {SERVER_PRESETS, PRESET_NONE, presetValues, presetUrlPlaceholder, presetChangesWhatIsWritten} from '../../js/serverPresets.js';

export default {
  data: () => ({
    /**
     * The chosen preset. Starts on the option that fills nothing, so opening
     * the drawer never writes anything into the form on its own — a
     * declaration begins blank whether or not this control exists.
     */
    presetId: PRESET_NONE,
  }),
  computed: {
    /**
     * The options, in the order the catalogue declares them: the servers we
     * have characterised first, the one we have not last.
     *
     * @returns {Array} the select's items
     */
    items() {
      return SERVER_PRESETS.map(preset => ({
        value: preset.id,
        text: this.$t(`caldav.admin.servers.preset.${preset.id}.label`),
      }));
    },
    /**
     * What choosing the current option filled in, or — for the option that
     * fills nothing — why an absent server is not an unsupported one.
     *
     * @returns {String} the sentence shown under the select
     */
    summary() {
      return this.$t(`caldav.admin.servers.preset.${this.presetId}.summary`);
    },
    /**
     * Whether the current option carries a behaviour that changes what eXo
     * writes into somebody's calendar rather than only what it notices.
     *
     * @returns {Boolean} true when it does
     */
    changesWhatIsWritten() {
      return presetChangesWhatIsWritten(this.presetId);
    },
  },
  methods: {
    /**
     * Announces the values the chosen preset fills in, and the address shape
     * to show beside the one field it cannot fill.
     *
     * The values are handed over whole, every preset-owned field included —
     * the empty ones too — so that choosing a second preset replaces the first
     * in the form rather than merging with it.
     *
     * @returns {void}
     */
    choose() {
      this.$emit('preset', {
        values: presetValues(this.presetId),
        urlPlaceholder: presetUrlPlaceholder(this.presetId),
      });
    },
  },
};
</script>
