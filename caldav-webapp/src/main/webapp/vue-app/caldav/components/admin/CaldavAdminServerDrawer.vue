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
              :placeholder="$t('caldav.admin.servers.url.placeholder')"
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
              {{ $t('caldav.admin.servers.url.corsHint') }}
            </div>
          </v-list-item-content>
        </v-list-item>
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
export default {
  data: () => ({
    caldavServerDrawer: false,
    loading: false,
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
    }
  }),
  computed: {
    disabled() {
      return !this.server.name || !this.server.serverUrl;
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
      this.$refs.caldavServerDrawer.open();
    },
    /**
     * Closes the drawer, dropping whatever was typed.
     *
     * @returns {void}
     */
    close() {
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
      };
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
      const isNew = !this.server.id;
      try {
        if (isNew) {
          await this.$agendaCaldavService.createCaldavServer(this.server);
        } else {
          await this.$agendaCaldavService.updateCaldavServer(this.server);
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
