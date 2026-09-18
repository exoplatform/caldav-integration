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
  <v-avatar
    :height="iconSize"
    :min-width="iconSize"
    :width="iconSize"
    tile>
    <v-img
      v-if="imageSrc"
      :src="imageSrc"
      :max-height="iconSize"
      :height="iconSize"
      :max-width="iconSize"
      contain
      eager />
    <v-icon
      v-else
      :size="iconSize"
      :class="iconClass">
      {{ iconName }}
    </v-icon>
  </v-avatar>
</template>
<script>
import {resolveServerIcon, resolveServerImage} from '../../js/serverIconIdentity.js';

export default {
  props: {
    iconSize: {
      type: String,
      default: '40',
    },
    imageUrl: {
      type: String,
      default: null,
    },
    icon: {
      type: String,
      default: null,
    },
    iconClass: {
      type: String,
      default: 'icon-default-color',
    },
  },
  computed: {
    /**
     * The image identifying the server, resolved by the shared rule: the
     * uploaded image and nothing else. Null exactly when a font icon renders,
     * so this preview always shows the identity the other surfaces show.
     *
     * @returns {String} the image URL to render, or null to render the font icon
     */
    imageSrc() {
      return resolveServerImage(this.imageUrl);
    },
    /**
     * The glyph to render when no image identifies the server: the one the
     * administrator chose, else the packaged calendar default — the same
     * fallback the agenda surfaces draw, resolved by the same rule.
     *
     * @returns {String} the font icon to render
     */
    iconName() {
      return resolveServerIcon(this.icon);
    },
  },
};
</script>
