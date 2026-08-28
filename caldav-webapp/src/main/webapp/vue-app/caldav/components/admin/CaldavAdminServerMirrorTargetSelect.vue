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
        {{ $t('caldav.admin.servers.mirrorTarget.label') }}
      </v-list-item-title>
      <!--
        The condition sits on the FIELD, not in the section subtitle. Said one
        level up it would read as a per-user administrative control, and there
        is none: a user turning meeting copies off in their own agenda settings
        is the only thing that takes them out of this, and that is a sentence
        about who the choice reaches, not about who makes it.
      -->
      <div class="text-caption text-sub-title mt-1 mb-2">
        {{ $t('caldav.admin.servers.mirrorTarget.appliesTo') }}
      </div>
      <!--
        One consequence line per option, and deliberately a consequence rather
        than a description of the mechanism: which of these is right depends on
        what the server behind this registration does with a calendar that is
        not the account's default, and that cannot be enumerated here. So the
        drawer states what each choice costs where the choice is made, and lets
        the administrator decide - no warning dialog, no combination refused.
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
      <!--
        Only while an EXISTING registration is being moved, and only while the
        choice actually differs from the stored one. On a declaration there are
        no copies to move, and on a row reopened without touching this control
        the sentence would be describing something that is not about to happen.
      -->
      <div
        v-if="changing"
        class="text-caption font-weight-bold mt-1">
        <v-icon class="me-1" size="14">fas fa-people-carry</v-icon>
        {{ $t('caldav.admin.servers.mirrorTarget.moves') }}
      </div>
    </v-list-item-content>
  </v-list-item>
</template>
<script>
import {MIRROR_TARGETS, mirrorTargetOf} from '../../js/mirrorTargets.js';

export default {
  props: {
    /**
     * The destination currently chosen in the form. Whatever it carries, the
     * radio group is driven by `selected` rather than by this directly, so a
     * row that states nothing — or states a value this control no longer
     * offers — still lands on a real option rather than on an empty group.
     */
    value: {
      type: String,
      default: null,
    },
    /**
     * The destination the registration is stored with, or null when this is a
     * declaration. It is what tells a change from a first statement — the only
     * thing that decides whether copies are about to be moved.
     */
    storedValue: {
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
      return MIRROR_TARGETS;
    },
    /**
     * The option the radio group actually shows as chosen.
     *
     * <p>Normalised rather than bound straight through, because the registry's
     * `MirrorTargetKind` still carries a value this control no longer offers:
     * a row stored as `USER_CHOICE` would match no radio here and would render
     * as a group with nothing selected, which reads to an administrator as a
     * setting that has been lost. It shows the dedicated calendar instead —
     * what `mirrorTargetOf` resolves anything unoffered to, and what a save
     * from this drawer would state.</p>
     *
     * @returns {String} one of the offered values, never null
     */
    selected() {
      return mirrorTargetOf(this.value);
    },
    /**
     * Whether saving would move the copies already written for every user of
     * this server.
     *
     * @returns {Boolean} true when an existing registration's destination is
     *          being changed
     */
    changing() {
      return !!this.storedValue && mirrorTargetOf(this.value) !== mirrorTargetOf(this.storedValue);
    },
  },
  methods: {
    /**
     * Announces the chosen destination, always as one of the offered stored
     * values.
     *
     * @param {String} chosen the value the radio group produced
     * @returns {void}
     */
    choose(chosen) {
      this.$emit('input', mirrorTargetOf(chosen));
    },
  },
};
</script>
