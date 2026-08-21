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
 * A calendar pair as the service layer handles it.
 *
 * <p>
 * Flat on purpose: it holds the pair's own state and the identifier of its
 * server, never the server object and never its objects. A DTO that is cached
 * has to be cheap to invalidate, and a nested collection makes every change to
 * a child a reason to reason about the parent's cache entry.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CalendarSync {

  /** Technical identifier of the pair; null before it is first persisted. */
  private Long               id;

  /** The user the pair belongs to; pairs are never shared between users. */
  private long               userIdentityId;

  /** The declared server registration the remote side lives on. */
  private long               serverId;

  /** Agenda's immutable calendar anchor; null for the mirror pair alone. */
  private String             localCalendarSyncUid;

  /** Canonical collection path: percent-decoded, no trailing slash. */
  private String             remoteHref;

  /** Which side created the collection, and therefore which side owns its existence. */
  private SyncOrigin         origin;

  /** RFC 6578 token from the last successful sync-collection REPORT, when the server offers one. */
  private String             syncToken;

  /** Collection change tag from the last read: the cheap "did anything move here" probe. */
  private String             ctag;

  /** The pair's lifecycle state. */
  private CalendarSyncStatus status;

  /** When the last synchronisation started; a start without an end is a run that never finished. */
  private Date               lastSyncStart;

  /** When the last synchronisation completed, successfully or not. */
  private Date               lastSyncEnd;

  /** Failures in a row, reset on success: what turns a transient failure into a deliberate pause. */
  private int                consecutiveFailures;

}
