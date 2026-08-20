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
  <div class="d-flex flex-nowrap align-center">
    <caldav-server-icon
      :image-url="iconUrl"
      :icon="icon"
      class="flex-grow-0 flex-shrink-0 me-4" />
    <font-icon-input
      v-model="icon"
      class="my-auto me-4"
      button-label="caldav.admin.servers.drawer.button.chooseIcon"
      no-label
      no-icon />
    <div class="position-relative overflow-hidden">
      <v-file-input
        v-if="!resetInput"
        id="caldavIconFileInput"
        ref="iconFileInput"
        accept="image/*"
        class="position-absolute t-0 l-0 full-width pa-0 ma-0"
        prepend-icon=""
        hide-details
        hide-input
        @change="uploadFile" />
      <v-btn
        :loading="sending"
        class="position-relative z-index-two btn primary"
        border
        outlined
        @click="openFileUpload">
        {{ $t('caldav.admin.servers.drawer.upload') }}
      </v-btn>
    </div>
    <v-btn
      v-if="!sending && hasCustomImage"
      :title="$t('caldav.admin.servers.drawer.resetDefault')"
      class="ms-2"
      icon
      @click="reset">
      <v-icon size="24" class="icon-default-color">fa-undo</v-icon>
    </v-btn>
  </div>
</template>
<script>
export default {
  props: {
    /**
     * The server being edited: {icon, imageUrl, imageFileId} drive the
     * preview, `input` events carry the fresh imageUploadId upwards.
     */
    server: {
      type: Object,
      default: null,
    },
  },
  data: () => ({
    imageData: null,
    maxFileSize: 102400,
    sending: false,
    resetInput: false,
    icon: null,
    initialized: false,
  }),
  computed: {
    iconUrl() {
      if (this.imageData) {
        return this.$utils.convertImageDataAsSrc(this.imageData);
      } else {
        return this.server?.imageUrl;
      }
    },
    hasCustomImage() {
      return !!this.imageData || !!this.server?.imageUrl;
    },
  },
  watch: {
    icon() {
      if (this.initialized) {
        if (this.icon) {
          this.reset();
        }
        this.$emit('icon', this.icon);
      }
    },
  },
  created() {
    if (this.server.imageUrl) {
      this.$emit('icon', null);
    }
  },
  async mounted() {
    this.icon = this.server.icon;
    await this.$nextTick();
    this.initialized = true;
  },
  methods: {
    /**
     * Drops the pending upload and the stored image alike, going back to the
     * font icon.
     *
     * @returns {void}
     */
    reset() {
      this.$emit('reset');
      this.sending = false;
      if (this.$refs.iconFileInput) {
        this.$emit('input', null);
        this.imageData = null;
        this.resetInput = true;
        this.$nextTick().then(() => this.resetInput = false);
      }
    },
    /**
     * Opens the hidden file input behind the Upload button.
     *
     * @returns {void}
     */
    openFileUpload() {
      this.$refs.iconFileInput.$el.querySelector('input').click();
    },
    /**
     * Sends the chosen file to the upload service and previews it, emitting
     * the uploadId the save will persist.
     *
     * @param {File} file the image the administrator picked
     * @returns {Promise|undefined} resolves once uploaded, nothing when refused
     */
    uploadFile(file) {
      this.$root.$emit('close-alert-message');
      if (file && file.size) {
        if (file.size > this.maxFileSize) {
          this.$root.$emit('alert-message', this.$t('caldav.admin.servers.drawer.tooBigFile'), 'error');
          return;
        }
        this.sending = true;
        const self = this;
        return this.$uploadService.upload(file)
          .then(uploadId => {
            if (uploadId) {
              this.$emit('input', uploadId);
              const reader = new FileReader();
              reader.onload = (e) => {
                self.imageData = e.target.result;
                self.$forceUpdate();
              };
              reader.readAsDataURL(file);
            } else {
              this.$root.$emit('alert-message', this.$t('caldav.admin.servers.drawer.uploadingError'), 'error');
            }
          })
          .catch(error => this.$root.$emit('alert-message', this.$t(String(error)), 'error'))
          .finally(() => this.sending = false);
      }
    },
  },
};
</script>
