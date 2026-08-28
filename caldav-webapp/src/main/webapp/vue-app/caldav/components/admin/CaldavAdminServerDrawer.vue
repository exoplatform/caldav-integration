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
    id="caldavServerDrawer"
    ref="caldavServerDrawer"
    v-model="caldavServerDrawer"
    :loading="loading"
    right
    allow-expand
    @closed="close">
    <template #title>
      <span>{{ drawerTitle }}</span>
    </template>
    <template v-if="caldavServerDrawer" #content>
      <form
        ref="adminServerForm"
        class="mx-5 mt-5"
        @submit.stop.prevent="0">
        <!--
          Offered on a declaration only. Editing a row is not the moment to
          overwrite what somebody already decided about it with what we happen
          to know about a server of that name.
        -->
        <caldav-admin-server-preset-select
          v-if="!server.id"
          @preset="applyPreset" />
        <div class="mb-3">
          {{ $t('caldav.admin.servers.drawer.updateTheIcon') }}
        </div>
        <caldav-admin-server-image-input
          v-model="server.imageUploadId"
          :server="server"
          class="mb-7"
          @icon="server.icon = $event"
          @reset="resetImage" />
        <v-label for="caldavServerName">
          {{ $t('caldav.admin.servers.name') }}
        </v-label>
        <v-text-field
          id="caldavServerName"
          v-model="server.name"
          :placeholder="$t('caldav.admin.servers.name.placeholder')"
          :aria-label="$t('caldav.admin.servers.name')"
          class="width-auto flex-grow-1 mt-3 mb-7 pt-0"
          name="caldavServerName"
          type="text"
          maxlength="250"
          required="required"
          outlined
          dense />
        <v-list-item-title class="pa-0 mt-7 mb-4 text-header">
          {{ $t('caldav.admin.servers.drawer.caldavSettings') }}
        </v-list-item-title>
        <v-list-item class="pa-0 mb-5" dense>
          <v-list-item-content class="py-0">
            <v-list-item-title class="my-0">
              {{ $t('caldav.admin.servers.url') }}
            </v-list-item-title>
            <v-text-field
              v-model="server.serverUrl"
              :aria-label="$t('caldav.admin.servers.url')"
              :placeholder="urlPlaceholder"
              class="pt-3"
              type="text"
              maxlength="1000"
              required="required"
              outlined
              dense />
            <div class="text-caption text-sub-title mb-2">
              {{ $t('caldav.admin.servers.url.hint') }}
            </div>
            <div class="text-caption text-sub-title mb-2">
              {{ $t('caldav.admin.servers.url.reachabilityHint') }}
            </div>
          </v-list-item-content>
        </v-list-item>
        <v-list-item class="pa-0" dense>
          <v-list-item-content class="py-0">
            <v-list-item-title class="my-0">
              {{ $t('caldav.admin.servers.description') }}
            </v-list-item-title>
            <v-text-field
              v-model="server.description"
              :aria-label="$t('caldav.admin.servers.description')"
              :placeholder="$t('caldav.admin.servers.description.placeholder')"
              class="pt-3"
              type="text"
              maxlength="500"
              outlined
              dense />
          </v-list-item-content>
        </v-list-item>
        <v-list-item-title class="pa-0 mt-7 mb-4 text-header">
          {{ $t('caldav.admin.servers.copies.title') }}
        </v-list-item-title>
        <v-list-item class="pa-0 mb-5" dense>
          <v-list-item-content class="py-0">
            <div class="d-flex align-center">
              <div class="flex-grow-1 text-start">
                {{ $t('caldav.admin.servers.answerLinks.label') }}
              </div>
              <v-switch
                v-model="server.answerLinksInCopy"
                :aria-label="$t('caldav.admin.servers.answerLinks.label')"
                class="ma-0 pa-0"
                hide-details />
            </div>
            <!--
              The hint says what to weigh, not what the switch does: whether a
              client shows its own Accept button is the client's decision and
              can never be derived here, so the administrator is the only one
              who can answer it — and is told what happens when neither side
              offers anything.
            -->
            <div class="text-caption text-sub-title mt-2">
              {{ $t('caldav.admin.servers.answerLinks.hint') }}
            </div>
          </v-list-item-content>
        </v-list-item>
        <!--
          Where the copies land, under the same heading as what they carry:
          both are decisions about the document eXo writes into somebody's
          calendar, and an administrator weighing one is weighing the other.
        -->
        <caldav-admin-server-mirror-target-select
          v-model="server.mirrorTarget"
          :stored-value="storedMirrorTarget" />
        <!--
          Always rendered, empty or not. Two drawers with different SHAPES teach
          an administrator nothing: on a server that has shown nothing, a missing
          section reads as "unsupported here" or "not implemented" rather than as
          the good news it is. The empty state says which.
        -->
        <v-list-item-title class="pa-0 mt-7 mb-2 text-header">
          {{ $t('caldav.admin.servers.quirks.title') }}
        </v-list-item-title>
        <div class="text-caption text-sub-title mb-4">
          {{ observedQuirks.length && $t('caldav.admin.servers.quirks.subtitle')
            || $t('caldav.admin.servers.quirks.none') }}
        </div>
        <v-list-item
          v-for="quirk in observedQuirks"
          :key="quirk.key"
          class="pa-0 mb-4"
          dense>
          <v-list-item-content class="py-0">
            <div class="d-flex align-start">
              <v-checkbox
                v-model="quirk.excused"
                :aria-label="$t(quirk.labelKey, {0: quirk.property})"
                class="ma-0 pa-0 me-2"
                hide-details />
              <div class="flex-grow-1 text-start">
                <div :class="quirk.warning && 'error--text font-weight-bold' || ''">
                  <v-icon
                    v-if="quirk.warning"
                    class="me-1"
                    color="error"
                    size="16">
                    fas fa-exclamation-triangle
                  </v-icon>
                  {{ $t(quirk.labelKey, {0: quirk.property}) }}
                </div>
                <div
                  :class="quirk.warning && 'error--text' || 'text-sub-title'"
                  class="text-caption mt-1">
                  {{ $t(quirk.costKey, {0: quirk.property}) }}
                </div>
                <!--
                  A quirk that changes what eXo WRITES says so, in its own
                  line, above the count. Most boxes here only change what eXo
                  notices and are reversible with no trace; this one alters the
                  document that lands in somebody's calendar, and a box that
                  does that must not look like a box that does not.
                -->
                <div
                  v-if="quirk.changesWhatIsWritten"
                  class="text-caption font-weight-bold mt-1">
                  <v-icon class="me-1" size="14">fas fa-pen</v-icon>
                  {{ $t('caldav.admin.servers.quirks.changesWhatIsWritten') }}
                </div>
                <div class="text-caption text-sub-title mt-1">
                  {{ $t(quirk.seenKey, {0: quirk.count}) }}
                </div>
              </div>
            </div>
          </v-list-item-content>
        </v-list-item>
      </form>
    </template>
    <template #footer>
      <div class="d-flex">
        <v-spacer />
        <v-btn
          class="btn"
          @click="close">
          {{ $t('caldav.admin.servers.drawer.cancel') }}
        </v-btn>
        <v-btn
          :disabled="disabled"
          class="btn btn-primary ms-5"
          @click="saveServer">
          {{ drawerButtonLabel }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
import {applyExcusals, describeQuirk} from '../../js/serverQuirks.js';
import {DEFAULT_MIRROR_TARGET, mirrorTargetOf} from '../../js/mirrorTargets.js';

export default {
  data: () => ({
    caldavServerDrawer: false,
    loading: false,
    /**
     * The destination the registration was opened on, or null on a
     * declaration. Kept beside the edited value rather than derived from it,
     * because a change is only visible against what is stored — and it is what
     * decides whether the drawer says the copies are about to move.
     */
    storedMirrorTarget: null,
    // The address shape the chosen preset expects. Null until one is chosen,
    // and back to null on every close, so the generic shape is what an
    // untouched declaration shows.
    presetUrlPlaceholder: null,
    server: {
      id: '',
      name: '',
      description: '',
      serverUrl: '',
      active: true,
      icon: '',
      imageUploadId: null,
      imageFileId: null,
      imageUrl: null,
      // On by default, matching the registry: a server declared here writes
      // the answer links unless its administrator says otherwise.
      answerLinksInCopy: true,
      ignoredProperties: null,
      droppedProperties: null,
      omittedProperties: null,
      // Stated, never absent. The registry reads a missing destination as
      // "not stated" and keeps whatever the row already had, which was the
      // right reading only while no drawer carried the control.
      mirrorTarget: DEFAULT_MIRROR_TARGET,
      observedQuirks: [],
    },
    // The behaviours this server has been seen doing, as the drawer edits
    // them. Copied out of the row on open so an abandoned drawer leaves the
    // registration untouched, exactly like every other field here.
    observedQuirks: [],
  }),
  computed: {
    disabled() {
      return !this.server.name || !this.server.serverUrl;
    },
    /**
     * The address shape shown in the URL field: the chosen preset's, else the
     * generic one. The shape is the hint — an administrator recognises the
     * form of their own address faster than they read a sentence about it.
     *
     * @returns {String} the placeholder for the server URL field
     */
    urlPlaceholder() {
      return this.presetUrlPlaceholder || this.$t('caldav.admin.servers.url.placeholder');
    },
    drawerTitle() {
      return this.server.id && this.$t('caldav.admin.servers.drawer.edit.title', {
        0: this.server.name,
      })
        || this.$t('caldav.admin.servers.drawer.add.title');
    },
    drawerButtonLabel() {
      return this.server.id && this.$t('caldav.admin.servers.drawer.save')
        || this.$t('caldav.admin.servers.drawer.add');
    }
  },
  created() {
    this.$root.$on('open-caldav-server-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer, empty for a declaration, on a copy of the row for an
     * edit — an abandoned drawer leaves the table untouched.
     *
     * @param {Object} server the row to edit, or nothing to declare a new one
     * @returns {void}
     */
    open(server) {
      if (server) {
        this.server = { ...server };
      }
      // Normalised on the way in, and remembered as it was stored. A row saved
      // before this control existed carries no destination at all; showing it
      // as three empty radios would let a save state a destination the
      // administrator never chose.
      this.server.mirrorTarget = mirrorTargetOf(this.server.mirrorTarget);
      this.storedMirrorTarget = this.server.id && this.server.mirrorTarget || null;
      this.observedQuirks = (this.server.observedQuirks || []).map(describeQuirk);
      this.$refs.caldavServerDrawer.open();
    },
    /**
     * Folds the drawer's ticks back into the lists the registration is saved
     * with.
     *
     * <p>A method of its own so the round trip can be exercised through the
     * component - the layer where the last regression lived - and not only
     * through the module underneath it.</p>
     *
     * @returns {void}
     */
    applyTicks() {
      applyExcusals(this.server, this.observedQuirks);
    },
    /**
     * Closes the drawer, dropping whatever was typed.
     *
     * @returns {void}
     */
    close() {
      this.presetUrlPlaceholder = null;
      this.server = {
        id: '',
        name: '',
        description: '',
        serverUrl: '',
        active: true,
        icon: '',
        imageUploadId: null,
        imageFileId: null,
        imageUrl: null,
        answerLinksInCopy: true,
        ignoredProperties: null,
        droppedProperties: null,
        omittedProperties: null,
        mirrorTarget: DEFAULT_MIRROR_TARGET,
        observedQuirks: [],
      };
      this.observedQuirks = [];
      this.storedMirrorTarget = null;
      this.$refs.caldavServerDrawer.close();
    },
    /**
     * Forgets the stored image so the save removes it, going back to the
     * font icon.
     *
     * @returns {void}
     */
    resetImage() {
      this.server.imageUrl = null;
      this.server.imageFileId = null;
    },
    /**
     * Copies a chosen preset's values into the declaration being written.
     *
     * A copy, not a link: what lands here is the row's own value from now on.
     * The administrator may change any of it before saving and it stays
     * changed, and a later build that learns something new about that server
     * never reaches a row already declared — it reaches the administrator
     * instead, through what the sweep observes on their own server.
     *
     * The whole set of preset-owned fields is assigned every time, empty ones
     * included, so choosing a second preset replaces the first rather than
     * leaving half of it behind.
     *
     * @param {Object} preset the chosen preset's values and address shape
     * @returns {void}
     */
    applyPreset(preset) {
      this.presetUrlPlaceholder = preset.urlPlaceholder;
      this.server = Object.assign({}, this.server, preset.values);
    },
    /**
     * Creates or updates the drawer's server, then refreshes the table and
     * tells the agenda apps that the connectors changed. A server whose
     * administrator chose neither a font icon nor an image is saved with
     * none: its identity stays the packaged CalDAV image everywhere, and
     * a plain rename can never silently swap it for a generic glyph.
     *
     * @returns {Promise} resolves once saved and announced
     */
    async saveServer() {
      this.loading = true;
      this.applyTicks();
      const isNew = !this.server.id;
      // What the sweep observed is not an administrator's input and the server
      // keeps its own record of it, so it is not sent back: a payload carrying
      // it would be asking the registry to accept the evidence it produced.
      const payload = {...this.server};
      delete payload.observedQuirks;
      // Always a real value, on every save. See mirrorTargets.mirrorTargetOf:
      // the storage's tolerance for an absent destination protected rows only
      // while this drawer had nothing to say about it, and a save that left it
      // out now would be relying on a guard that no longer guards anything.
      payload.mirrorTarget = mirrorTargetOf(this.server.mirrorTarget);
      try {
        if (isNew) {
          await this.$agendaCaldavService.createCaldavServer(payload);
        } else {
          await this.$agendaCaldavService.updateCaldavServer(payload);
        }
        if (isNew) {
          this.$root.$emit('alert-message', this.$t('caldav.admin.servers.drawer.add.success'), 'success');
        } else {
          this.$root.$emit('alert-message', this.$t('caldav.admin.servers.drawer.edit.success'), 'success');
        }
        this.$root.$emit('refresh-caldav-servers-list');
        document.dispatchEvent(new CustomEvent('agenda-connectors-refresh'));
        this.close();
      } catch (e) {
        if (isNew) {
          this.$root.$emit('alert-message', this.$t('caldav.admin.servers.drawer.add.error'), 'error');
        } else {
          this.$root.$emit('alert-message', this.$t('caldav.admin.servers.drawer.edit.error'), 'error');
        }
      } finally {
        this.loading = false;
      }
    }
  }
};
</script>
