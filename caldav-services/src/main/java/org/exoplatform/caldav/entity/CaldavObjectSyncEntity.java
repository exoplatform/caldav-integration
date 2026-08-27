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
package org.exoplatform.caldav.entity;

import java.util.Date;

import io.meeds.common.persistence.PortableSequence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One event's identity across the two sides: the eXo event, the iCalendar
 * object it corresponds to, and what was last written or read.
 *
 * <p>
 * This row is what makes verification and deletion detection possible without
 * keeping a second copy of the calendar. Knowing the href, the server's ETag
 * and the hash of what we last pushed is enough to answer the three questions
 * the engine actually asks — is our copy still there, has anyone else changed
 * it, and is what we would push any different from what is already stored.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "CaldavObjectSyncEntity")
@Table(name = "CALDAV_OBJECT_SYNC")
public class CaldavObjectSyncEntity {

  @Id
  @PortableSequence(name = "SEQ_CALDAV_OBJECT_SYNC_ID")
  @Column(name = "ID")
  private Long   id;

  /** The calendar pair this object belongs to. */
  @Column(name = "CALENDAR_SYNC_ID")
  private long   calendarSyncId;

  /**
   * The eXo event, when there is one. Null while an object exists remotely and
   * has not been materialised locally yet — the inbound half fills it in.
   */
  @Column(name = "LOCAL_EVENT_ID")
  private Long   localEventId;

  /**
   * The iCalendar UID, which is the identity both sides agree on. Hrefs change
   * between servers and even between collections; the UID is what a calendar
   * object is.
   */
  @Column(name = "ICS_UID")
  private String icsUid;

  /** Where the object sits in the collection, canonical like the pair's href. */
  @Column(name = "REMOTE_HREF")
  private String remoteHref;

  /**
   * The server's entity tag for the object as we last saw it. Every write is
   * conditional on it, which is what turns a concurrent edit into a 412 the
   * engine can resolve rather than into a silent overwrite.
   */
  @Column(name = "ETAG")
  private String etag;

  /** When this object was last written or verified. */
  @Column(name = "LAST_SYNC")
  private Date   lastSync;
}
