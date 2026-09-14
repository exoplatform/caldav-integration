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
  DELETING,

  /**
   * The user hid a calendar somebody shared with them (EXO-90239).
   *
   * <p>
   * The one pair that never had an eXo calendar behind it and never will: a
   * share is not materialised ({@code CaldavSyncService#skipShare}), and
   * before this state existed it therefore had no pair at all — which is
   * exactly why it could not be hidden, since being <em>unbound</em> is what
   * puts a collection under the remote calendars and serves its events. The
   * pair records the user's choice under the origin the collection has to
   * the engine, {@link SyncOrigin#REMOTE} — somebody made it on the server,
   * eXo owns nothing of it — with an anchor derived from its path rather than
   * a calendar's ({@code CaldavDeletionService#hiddenShareAnchor}: the pair
   * table's unique index would refuse a second null anchor on Oracle and SQL
   * Server), and every consumer that acts on a pair leaves it alone: the
   * sweep reads only {@link #ACTIVE} pairs, the outbound half binds by
   * agenda's own anchors, the orphan pruning drops only
   * {@link #ACTIVE} pairs, and the states worth telling the user about do not
   * include it. What it does do, by being a binding at all, is keep the
   * collection out of the calendar list and its events unserved, and keep
   * materialisation from ever considering it — the same mechanism that makes
   * a {@link #LOCALLY_DELETED} tombstone stick. Lifted by deleting the pair:
   * nothing has to be synchronised for the share to be listed again.
   */
  HIDDEN_SHARE,

  /**
   * A calendar materialised from a collection the user only subscribed to —
   * a BlueMind resource, a colleague's calendar — before the server's naming
   * was read, and retired since (EXO-90275).
   *
   * <p>
   * Inert, and that is the whole of its meaning: nothing is read from the
   * collection and nothing is written to it through this binding, not an
   * import, not a push, not a removal, not the cleanup a move leaves behind.
   * The sweep reads only {@link #ACTIVE} pairs and the push writes only
   * through them; the lookups that resolve a copy by its mapping whatever the
   * binding's state ({@code CaldavPushService#objectAnywhere},
   * {@code #mappingElsewhere}, {@code CaldavEventPropagationService#collectHolders})
   * skip this one by name; and a reconnection thaws {@link #PAUSED} alone, so
   * it cannot be woken up. The eXo calendar and its events are kept untouched
   * — eXo cannot tell the copies in it from events the user made there, since
   * agenda lists a calendar's events only by date window and confirmed
   * status — and the state is told to the user, who deletes that calendar.
   * Its binding is then dropped by the orphan pruning, once agenda has
   * really deleted the calendar — the deletion dialog claims nothing about it
   * and leaves it alone — and the collection is listed read-only under
   * "Shared with me".
   */
  RETIRED_SUBSCRIPTION

}
