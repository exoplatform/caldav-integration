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
 * The lifecycle of one calendar pair. Every value other than {@link #ACTIVE}
 * is a state the user can be shown and, where it makes sense, act on — the
 * point of recording them rather than logging a failure and retrying blindly.
 */
public enum CalendarSyncStatus {

  /** Bound on both sides and synchronising. */
  ACTIVE,

  /**
   * The server refused to create the collection. Real on servers whose CalDAV
   * implementation does not offer MKCALENDAR at all — Google's is the usual
   * one. Not BlueMind's: it answers 201 while creating nothing when the
   * request omits the supported component set, which is a malformed request on
   * our side rather than a refusal, and is why creation is confirmed by a
   * read-back rather than by a status code.
   */
  REMOTE_CREATE_REFUSED,

  /**
   * The remote collection has disappeared. For an EXO pair the engine may
   * recreate it; for a REMOTE pair it means the user deleted their own
   * calendar, which propagates inward.
   */
  REMOTE_GONE,

  /**
   * Synchronisation is suspended and will not resume on its own. Set on
   * repeated failure, and immediately on an authentication rejection — a stale
   * password must never be retried in a loop against a server that may lock
   * the account.
   */
  PAUSED,

  /**
   * The local calendar is gone and the remote collection has not been dealt
   * with yet. The tombstone that keeps a deletion recoverable instead of
   * silently forgotten.
   */
  LOCALLY_DELETED,

  /**
   * An EXO pair whose local calendar no longer exists while its remote
   * collection does, and which no deletion accounted for. Surfaced rather than
   * cleaned up automatically: something disagreed, and guessing which side is
   * right is how data gets destroyed.
   */
  EXO_ORPHANED,

  /**
   * A deletion is in flight. Set before the remote call and cleared after, so
   * an interrupted deletion is visible as unfinished rather than indistinguishable
   * from one that never started.
   */
  DELETING

}
