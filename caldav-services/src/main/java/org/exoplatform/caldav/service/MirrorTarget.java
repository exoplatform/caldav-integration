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
package org.exoplatform.caldav.service;

/**
 * Where a user's space events are copied, and how that destination came to be.
 *
 * @param href the collection the copies are written into, canonical
 * @param adopted true when the server refused to create a collection and an
 *          existing calendar was taken as the destination instead
 * @param name the calendar's display name — only meaningful when adopted, so
 *          the settings can name the calendar genuinely receiving the copies
 *          rather than announcing one that was never created
 */
public record MirrorTarget(String href, boolean adopted, String name) {
}
