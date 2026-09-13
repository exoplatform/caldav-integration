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
package org.exoplatform.caldav.service;

/**
 * Whose a listed calendar collection is, to the eXo user whose account listed
 * it — the one answer the sweep, the calendar list and the event read-through
 * all act on, so that they cannot disagree about a collection.
 *
 * <p>
 * Two witnesses are heard, and the answer names which one spoke. The
 * <b>server</b> says a collection is somebody else's by naming another owner
 * or by granting no write ({@code CalendarCollection#isSharedWith}, EXO-90235).
 * <b>This deployment</b> says so by recognising, in the slug eXo mints, the
 * anchor of a calendar it exported for <em>another</em> of its users
 * ({@code CaldavOutboundService#ownershipOf}, EXO-90234). The second witness
 * exists because the first is silent on BlueMind: a calendar a colleague
 * shared and the user subscribed to is listed under the user's own home, with
 * the user named as owner and the full privilege set — indistinguishable from
 * their own calendar by anything the server answers, and distinguishable by
 * the one fact only eXo holds, which is whose calendar it pushed out under
 * that slug.
 *
 * <p>
 * Before this existed the three paths asked three different questions of the
 * same collection and answered three different ways: the sweep skipped a
 * colleague's eXo calendar without a word, the list dropped it, and the
 * read-through served its events — a calendar the user could see the events
 * of but never the calendar itself (EXO-90234). The three now ask the one
 * question below and act on the one answer: a share is never materialised,
 * is listed read-only, and — being unbound — has its events served.
 *
 * <p>
 * Deliberately services-local, unlike its siblings {@code SyncOrigin} and
 * {@code CalendarSyncStatus} in the API module's {@code model} package: it is
 * an answer computed from a listing and the pair table for the three services
 * that consume it, persisted nowhere and exposed to no other addon, so it is
 * kept out of the add-on's API surface until something outside this module
 * needs it.
 */
public enum CollectionOwnership {

  /**
   * The user's own, as far as anybody can tell: the server named no other
   * owner and withheld no write, and the slug names no calendar this
   * deployment exported for anyone. Materialised by the sweep as a personal
   * calendar, and until then offered under Remote as writable when the
   * server grants write and the slug is not eXo's. A collection
   * <em>another</em> eXo deployment minted into the account answers this too
   * — its anchor is known to nobody here, and it is an ordinary remote
   * calendar to this deployment (EXO-90226) — but, wearing eXo's slug, it is
   * neither listed nor left unmaterialised: the sweep adopts it on its next
   * pass and the binding then keeps it out of the list.
   */
  OWN,

  /**
   * The user's own eXo calendar, met again on the server: the slug carries
   * the anchor of a calendar this user exported, or the path is one an EXO
   * pair of theirs records, while the listing spelled it under a path no
   * pair of theirs matched — BlueMind republishes eXo's collections under a
   * parent other than the one they were created at. Already in eXo, so
   * neither materialised nor listed; nothing to say about it either, since
   * it is exactly what the sweep put there.
   */
  OWN_EXO_CALENDAR,

  /**
   * Somebody else's, by the server's word: another principal named as owner,
   * or a privilege set that grants no write (EXO-90235). Never materialised,
   * listed read-only, events served.
   */
  SHARED,

  /**
   * Somebody else's, by this deployment's word: a collection eXo minted for
   * a calendar of <em>another</em> user of this deployment, and none of the
   * current user's own, wherever the server lists it and whatever it says
   * about owner and privileges (EXO-90234). The colleague shared their eXo
   * calendar on the server, and eXo already holds the original — so it is
   * treated exactly as {@link #SHARED}: never materialised, which would push
   * the user's edits into the colleague's calendar; listed read-only; events
   * served.
   */
  COLLEAGUES_EXO_CALENDAR;

  /**
   * Whether the collection is somebody else's, whichever witness said so.
   *
   * <p>
   * The one test the three paths branch on: a shared collection is never
   * bound, is offered read-only, and is served. Which witness spoke matters
   * only to the line that explains the skip.
   *
   * @return true for {@link #SHARED} and {@link #COLLEAGUES_EXO_CALENDAR}
   */
  public boolean isShared() {
    return this == SHARED || this == COLLEAGUES_EXO_CALENDAR;
  }
}
