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
package org.exoplatform.caldav.model;

import org.exoplatform.caldav.constant.SubscriptionOutcome;

/**
 * How one attempt at a colleague's BlueMind subscription change ended.
 *
 * @param outcome what to do with the row
 * @param reason why, for the line; null when it landed
 * @param sessionLost whether the session itself is no longer usable, so
 *          the rows after this one in the same session are left untried
 */
public record SubscriptionAttempt(SubscriptionOutcome outcome, String reason, boolean sessionLost) {
}
