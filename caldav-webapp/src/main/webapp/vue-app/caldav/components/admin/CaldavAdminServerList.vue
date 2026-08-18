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
  <div>
    <v-data-table
      :headers="headers"
      :items="servers"
      :no-data-text="$t('caldav.admin.servers.noServers')"
      hide-default-footer
      disable-pagination
      disable-filtering
      disable-sort
      dense>
      <template #[`item.icon`]="{ item }">
        <caldav-server-icon
          :image-url="item.imageUrl"
          :icon="item.icon"
          class="flex-grow-0 flex-shrink-0 py-1" />
      </template>
      <template #[`item.name`]="{ item }">
        <span>
          {{ item.name }}
        </span>
      </template>
      <template #[`item.active`]="{ item }">
        <div class="d-flex justify-center">
          <v-switch
            v-model="item.active"
            class="ma-0 pa-0"
            hide-details
            @change="activateItem(item)" />
        </div>
      </template>
      <template #[`item.actions`]="{ item }">
        <v-btn
          icon
          @click="editItem(item)">
          <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
        </v-btn>
        <v-btn
          icon
          color="error"
          @click="openDeleteConfirmDialog(item)">
          <v-icon size="20">fa-trash</v-icon>
        </v-btn>
      </template>
    </v-data-table>
    <confirm-dialog
      ref="deleteConfirmDialog"
      :title="$t('caldav.admin.servers.modal.delete.title')"
      :message="$t('caldav.admin.servers.modal.delete.message')"
      :ok-label="$t('caldav.admin.servers.modal.delete.confirmDelete')"
      :cancel-label="$t('caldav.admin.servers.modal.delete.cancelDelete')"
      @ok="deleteServer"
      @closed="serverToDelete = null" />
  </div>
</template>

<script>
export default {
  props: {
    servers: {
      type: Array,
      default: () => [],
    },
  },
  data: () => ({
    headers: [],
    serverToDelete: null,
  }),
  created() {
    this.headers = [
      { text: '', value: 'icon', width: '40px'},
      { text: this.$t('caldav.admin.servers.list.name'), value: 'name' },
      { text: this.$t('caldav.admin.servers.list.activate'), align: 'center', value: 'active', width: '80px' },
      { text: this.$t('caldav.admin.servers.list.actions'), align: 'center', value: 'actions', width: '80px' }
    ];
  },
  methods: {
    /**
     * Opens the drawer prefilled with the row to edit.
     *
     * @param {Object} item the registered server of the clicked row
     * @returns {void}
     */
    editItem(item) {
      this.$root.$emit('open-caldav-server-drawer', item);
    },
    /**
     * Propagates the activation switch of a row: to the registry, and through
     * it to the agenda remote provider users' connector lists read.
     *
     * @param {Object} item the registered server whose switch was flipped
     * @returns {void}
     */
    activateItem(item) {
      this.$agendaCaldavService.setCaldavServerStatus(item.id, item.active)
        .then(() => {
          this.$root.$emit('refresh-caldav-servers-list');
          document.dispatchEvent(new CustomEvent('agenda-connectors-refresh'));
          const successAlertMessage = item.active && 'caldav.admin.servers.activate.success' || 'caldav.admin.servers.deactivate.success';
          this.$root.$emit('alert-message', this.$t(`${successAlertMessage}`), 'success');
        })
        .catch(() => {
          item.active = !item.active;
          const errorAlertMessage = item.active && 'caldav.admin.servers.activate.error' || 'caldav.admin.servers.deactivate.error';
          this.$root.$emit('alert-message', this.$t(`${errorAlertMessage}`), 'error');
        });
    },
    /**
     * Deletes the row confirmed in the dialog. A 409 is not a failure to
     * hide: it names the number of accounts still connected to that server,
     * which is what the administrator must act on (deactivate, let users
     * disconnect) before the delete can go through.
     *
     * @returns {void}
     */
    deleteServer() {
      this.$agendaCaldavService.deleteCaldavServer(this.serverToDelete.id)
        .then(() => {
          this.$root.$emit('refresh-caldav-servers-list');
          document.dispatchEvent(new CustomEvent('agenda-connectors-refresh'));
          this.$root.$emit('alert-message', this.$t('caldav.admin.servers.delete.success'), 'success');
          this.serverToDelete = null;
        })
        .catch(error => {
          if (error && error.status === 409) {
            const count = error.referenceCount || '';
            this.$root.$emit('alert-message', this.$t('caldav.admin.servers.delete.referenced', {0: count}), 'error');
          } else {
            this.$root.$emit('alert-message', this.$t('caldav.admin.servers.delete.error'), 'error');
          }
        });
    },
    /**
     * Opens the delete confirmation for a row.
     *
     * @param {Object} item the registered server of the clicked row
     * @returns {void}
     */
    openDeleteConfirmDialog(item) {
      this.serverToDelete = item;
      this.$refs.deleteConfirmDialog.open();
    },
  }
};
</script>
