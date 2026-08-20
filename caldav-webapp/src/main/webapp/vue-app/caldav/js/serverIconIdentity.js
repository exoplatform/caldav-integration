/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * The packaged image a registered server falls back to when the
 * administrator configured neither an uploaded image nor a font icon —
 * the same default the agenda connector descriptor ships as `avatar`.
 */
export const DEFAULT_SERVER_IMAGE = '/caldav/skin/image/caldav.png';

/**
 * The single rule deciding which image identifies a registered CalDAV
 * server, shared by every surface that renders one (admin list, admin
 * drawer preview): the uploaded image wins, else — when no font icon was
 * chosen either — the packaged CalDAV default. It returns null exactly
 * when the admin-chosen font icon is the identity to render, so a preview
 * can never show a glyph that is not actually persisted.
 *
 * @param {String} imageUrl the URL of the uploaded image, if any
 * @param {String} icon the admin-chosen font icon, if any
 * @returns {String} the image URL to render, or null to render the font icon
 */
export function resolveServerImage(imageUrl, icon) {
  return imageUrl || (!icon && DEFAULT_SERVER_IMAGE) || null;
}
