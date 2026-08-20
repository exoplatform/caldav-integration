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
  <div class="mt-8">
    <div class="text-title mb-3">
      {{ $t('caldav.admin.servers.title') }}
    </div>
    <div class="mb-4">
      <v-btn
        :aria-label="$t('caldav.admin.servers.add')"
        class="btn btn-primary"
        @click="$root.$emit('open-caldav-server-drawer')">
        <v-icon size="18">fa-plus</v-icon>
        <span class="text-none ms-2">{{ $t('caldav.admin.servers.add') }}</span>
      </v-btn>
    </div>
    <caldav-admin-server-list :servers="servers" />
    <caldav-admin-server-drawer />
  </div>
</template>

<script>
export default {
  props: {
    /**
     * Agenda user settings, handed by the admin page to every section. This
     * section reads its rows from the CalDAV registry instead, but keeps the
     * prop so its contract matches the other sections.
     */
    settings: {
      type: Object,
      default: () => null,
    },
  },
  data: () => ({
    servers: [],
  }),
  created() {
    this.$root.$on('refresh-caldav-servers-list', this.refreshServers);
    this.refreshServers();
  },
  methods: {
    /**
     * Reloads the declared servers from the registry.
     *
     * @returns {Promise} resolves once the table holds the current rows
     */
    refreshServers() {
      return this.$agendaCaldavService.getCaldavServers()
        .then(servers => this.servers = servers || [])
        .catch(() => this.servers = []);
    },
  }
};
</script>
