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

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One occurrence read from a remote calendar, in the shape agenda displays.
 *
 * <p>
 * An occurrence, not an event: a series read over a window yields one of these
 * per instance it produces there, all sharing the series' UID. What
 * distinguishes them is when they happen, which is why the instants are the
 * part that matters and the part the golden corpus judges.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteIcsEvent {

  /** The iCalendar UID, shared by every occurrence of a series. */
  private String  uid;

  /** The series this occurrence belongs to, or null for a standalone event. */
  private String  recurringEventId;

  /** Title, taken from the override when one amends this instance. */
  private String  summary;

  /** Where it takes place, or null. */
  private String  location;

  /** Free-text description, or null. */
  private String  description;

  /** Whether the occurrence covers whole days rather than a span of time. */
  private boolean allDay;

  /** When the occurrence starts. */
  private Instant start;

  /** When it ends; null for an all-day occurrence, which carries no time. */
  private Instant end;

}
