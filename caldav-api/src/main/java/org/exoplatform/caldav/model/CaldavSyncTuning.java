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

/**
 * How often and how widely eXo synchronises CalDAV accounts.
 *
 * <p>
 * Every value has a property behind it. The property is the default; what an
 * administrator saves here is the value. Nothing is captured at bean creation,
 * because a screen that writes a setting nobody re-reads until the next
 * restart is worse than no screen at all — it looks like it worked.
 *
 * @param throttleMinutes how long a synchronisation triggered by opening the
 *          agenda stays valid before another one is allowed
 * @param pastDays how far back the imported window reaches
 * @param futureDays how far forward the imported window reaches
 * @param sweepStaleMinutes how long since a successful synchronisation makes a
 *          binding worth picking up in the background sweep
 * @param sweepBatchSize how many stale bindings one sweep looks at
 */
public record CaldavSyncTuning(long throttleMinutes,
                               long pastDays,
                               long futureDays,
                               long sweepStaleMinutes,
                               int sweepBatchSize) {
}
