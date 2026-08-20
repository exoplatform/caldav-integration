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
  <v-app>
    <caldav-settings-drawer
      ref="caldavSettingsDrawer"
      :server="server"
      @display-alert="displayAlert" />
    <caldav-agenda-connectors-alert />
  </v-app>
</template>

<script>

export default {
  props: {
    /**
     * The declared server the clicked connector fronts, or null for the
     * legacy single-server connector.
     */
    server: {
      type: Object,
      default: () => null,
    },
  },
  mounted() {
    this.openCaldavDrawer();
  },
  methods: {
    openCaldavDrawer() {
      this.$refs.caldavSettingsDrawer.openCaldavDrawer();
    },
    closeCaldavDrawer() {
      this.$refs.caldavSettingsDrawer.closeCaldavDrawer();
    },
    displayAlert(message, type) {
      this.$root.$emit('connectors-connection-alert', {
        message,
        type: type || 'success',
      });
    },
  }
};
</script>
