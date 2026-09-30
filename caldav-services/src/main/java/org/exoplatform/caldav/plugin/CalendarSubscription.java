/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.caldav.plugin;

/**
 * A subscription a collection's name revealed: whose calendar it is.
 *
 * @param ownerUid the uid of the person or resource the calendar belongs to
 * @param resource true when the calendar is a resource's
 * @param ownerPrincipal the owner's principal path, in the account
 *          principal's own spelling
 */
public record CalendarSubscription(String ownerUid, boolean resource, String ownerPrincipal) {
}
