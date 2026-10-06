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
package org.exoplatform.caldav.rest.model;

import java.util.List;

/**
 * What the administration drawer sends to point the whole instance at one
 * declared server: the server, and the groups the choice must not reach.
 *
 * <p>
 * One body for the two facts, on purpose: they are applied by one click and
 * stored by one service call, and two requests would leave a moment where
 * the designation stands without its exclusions.
 *
 * @param serverId technical identifier of the registration
 * @param excludedGroups the eXo group ids whose members keep choosing their
 *          own server, null or empty for none
 */
public record CaldavManagedModeRequest(Long serverId, List<String> excludedGroups) {
}
