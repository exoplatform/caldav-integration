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

import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One calendar object's identity across the two sides, as the service layer
 * handles it. Flat, for the reason {@link CalendarSync} explains.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectSync {

  /** Technical identifier of the mapping; null before it is first persisted. */
  private Long   id;

  /** The calendar pair this object belongs to. */
  private long   calendarSyncId;

  /** The eXo event, or null while the object exists only remotely. */
  private Long   localEventId;

  /** The iCalendar UID: the identity both sides agree on. */
  private String icsUid;

  /** Canonical object path within the collection. */
  private String remoteHref;

  /** The server's entity tag as we last saw it; every write is conditional on it. */
  private String etag;

  /** When this object was last written or verified. */
  private Date   lastSync;

}
