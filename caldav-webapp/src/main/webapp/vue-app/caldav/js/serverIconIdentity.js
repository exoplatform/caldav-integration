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
 * The font icon a registered server falls back to when the administrator
 * configured neither an uploaded image nor a font icon of their own — the
 * same default the agenda connector descriptor ships as `icon`.
 *
 * <p>A calendar glyph, and the one the agenda already draws for itself
 * (AgendaSwitchView, AgendaHeader, the event form): what these events are is
 * calendar entries, and a marker that reads as part of the app says that,
 * where the packaged CalDAV logo said "CalDAV" — a protocol name, to a user
 * who never chose a protocol.
 */
export const DEFAULT_SERVER_ICON = 'fas fa-calendar-alt';

/**
 * The packaged CalDAV image. No longer a default: it identifies a server only
 * where an administrator deliberately picks it, and is kept as the file the
 * add-on ships for that.
 */
export const DEFAULT_SERVER_IMAGE = '/caldav/skin/image/caldav.png';

/**
 * The single rule deciding which image identifies a registered CalDAV
 * server, shared by every surface that renders one (admin list, admin
 * drawer preview): the uploaded image, and nothing else. It returns null
 * exactly when a font icon is the identity to render, so a preview can never
 * show an image where the other surfaces show a glyph.
 *
 * @param {String} imageUrl the URL of the uploaded image, if any
 * @returns {String} the image URL to render, or null to render the font icon
 */
export function resolveServerImage(imageUrl) {
  return imageUrl || null;
}

/**
 * The counterpart rule: which font icon to render once
 * {@link resolveServerImage} has answered that no image identifies the
 * server — the one the administrator chose, else the packaged default.
 *
 * <p>Held here rather than left to each surface's own `||` so that the admin
 * preview, the connect drawer, the timeline and the event header cannot end
 * up defaulting to different glyphs.
 *
 * @param {String} icon the admin-chosen font icon, if any
 * @returns {String} the font icon to render
 */
export function resolveServerIcon(icon) {
  return icon || DEFAULT_SERVER_ICON;
}
