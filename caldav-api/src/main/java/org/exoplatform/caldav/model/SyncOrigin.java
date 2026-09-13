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
 * Which side of the pair created the collection, and therefore which side owns
 * its existence. The engine reads this before every structural decision — what
 * may be created, what may be deleted, and what the inbound sweep must ignore
 * — so it is recorded once, at binding time, and never inferred afterwards.
 */
public enum SyncOrigin {

  /**
   * eXo created the remote collection from a local personal calendar.
   * <b>Materialisation</b> must skip these: they are already represented
   * locally, and materialising one again would create a second eXo calendar,
   * which the outbound half would then push as a third collection, and so on.
   * The inbound sweep, on the other hand, reads events out of them like any
   * other calendar binding — it excludes the mirror ledger alone
   * ({@code CaldavSyncService.importRemoteEvents}), because to the user this is
   * simply a calendar of theirs, and an edit made on a phone in it belongs in
   * eXo whichever side minted the collection.
   */
  EXO,

  /**
   * The collection existed on the server and eXo materialised a personal
   * calendar for it. eXo may write events into it, but never delete the
   * collection: it is the user's, created outside eXo and outliving it.
   *
   * <p>
   * Also the origin of a pair in {@link CalendarSyncStatus#HIDDEN_SHARE}
   * (EXO-90239), which records that the user hid a calendar shared with them
   * and stands for no eXo calendar at all. The collection is somebody else's,
   * made on the server, and eXo minted nothing for it — so of the three
   * origins this is the only one that tells the truth about it: {@link #EXO}
   * would make the ownership classification read the pair as the user's own
   * export, and {@link #MIRROR} would make it the copies' ledger. What such a
   * pair asks of the engine is what a REMOTE pair already gets — never
   * created, never deleted on the server — with the status doing the rest.
   */
  REMOTE,

  /**
   * The single dedicated collection space events are copied into. Not a
   * personal calendar on either side, excluded from the inbound sweep, and the
   * only pair whose local calendar anchor is absent.
   */
  MIRROR

}
