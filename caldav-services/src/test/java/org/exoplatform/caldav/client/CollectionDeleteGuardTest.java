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
package org.exoplatform.caldav.client;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.SyncOrigin;

/**
 * The guard on the one call that destroys data eXo may not have authored.
 *
 * <p>
 * Removing a collection removes every event in it, including events the user
 * added from their own phone. The method therefore takes the <b>binding</b>
 * rather than an href: an href parameter would let any caller name any
 * collection, and the guard would live in whoever remembered to write it.
 *
 * <p>
 * Each test below is one way of asking for a deletion that must not happen. It
 * fails as an {@link IllegalArgumentException} rather than a checked refusal
 * on purpose — there is no correct way for a caller to recover from asking to
 * delete a collection it may not delete. The only correct outcome is that the
 * request was never built, and no HTTP call is made in any of these cases
 * because the guard runs before the request exists.
 */
public class CollectionDeleteGuardTest {

  /**
   * Built with a transport that would fail loudly if it were ever reached, and
   * a null registry: the guard runs before either is touched, and a test that
   * needed them working would not be testing the guard.
   */
  private final HttpCalDavClient client = new HttpCalDavClient(java.net.http.HttpClient.newHttpClient(), null, null);

  @Test
  public void aCollectionEXoDidNotCreateCannotBeDeleted() {
    // The whole point. A REMOTE pair's collection is the user's own, made in
    // their own client, holding events eXo never saw.
    CalendarSync pair = pair(SyncOrigin.REMOTE, "cal-anchor", "/dav/calendars/john/exo-cal-cal-anchor");

    assertRefused(pair, "REMOTE");
  }

  @Test
  public void theMirrorItselfCannotBeDeletedThroughThisMethod() {
    // No delete gesture reaches the mirror, ever — it is not a calendar the
    // user made and not one they can remove from the agenda.
    CalendarSync pair = pair(SyncOrigin.MIRROR, null, "/dav/calendars/john/exo-meetings");

    assertRefused(pair, "MIRROR");
  }

  @Test
  public void aPairWithNoAnchorAuthorisesNothing() {
    // Without an anchor there is nothing to check the path against, so the
    // second half of the guard could not run at all.
    CalendarSync pair = pair(SyncOrigin.EXO, null, "/dav/calendars/john/exo-cal-something");

    assertRefused(pair, "anchor");
  }

  @Test
  public void aPairWhoseHrefDriftedFromItsAnchorAuthorisesNothing() {
    // A bad migration, a hand-edited row, a bug in binding: the pair says EXO
    // but now points at a collection eXo did not derive for this calendar.
    // Without this half of the guard the deletion would go wherever the row
    // happens to point.
    CalendarSync pair = pair(SyncOrigin.EXO, "cal-anchor", "/dav/calendars/john/personal");

    assertRefused(pair, "not the one eXo derives");
  }

  @Test
  public void anotherCalendarsCollectionAuthorisesNothing() {
    // The subtler drift: a real eXo collection, but not this pair's.
    CalendarSync pair = pair(SyncOrigin.EXO, "cal-anchor", "/dav/calendars/john/exo-cal-other-anchor");

    assertRefused(pair, "not the one eXo derives");
  }

  @Test
  public void aPathThatMerelyContainsTheAnchorIsNotTheCollection() {
    // endsWith on the segment, not contains: a collection nested under the
    // right-looking name is a different collection.
    CalendarSync pair = pair(SyncOrigin.EXO, "cal-anchor", "/dav/calendars/john/exo-cal-cal-anchor/sub");

    assertRefused(pair, "not the one eXo derives");
  }

  @Test
  public void noPairAtAllAuthorisesNothing() {
    assertRefused(null, "absent");
  }

  /**
   * Asserts the guard refuses, and that the refusal says why.
   *
   * @param pair the binding offered as authorisation
   * @param expectedInMessage a fragment the message must carry, so a refusal
   *          for the wrong reason does not pass as the right one
   */
  private void assertRefused(CalendarSync pair, String expectedInMessage) {
    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> client.deleteCollection(null, pair));

    assertTrue(refusal.getMessage().contains(expectedInMessage),
               () -> "expected a refusal mentioning '" + expectedInMessage + "' but got: " + refusal.getMessage());
  }

  /**
   * A binding offered as authorisation for a deletion.
   *
   * @param origin which side created the collection
   * @param anchor the calendar's sync uid, or null
   * @param href where the collection is said to live
   * @return the pair
   */
  private CalendarSync pair(SyncOrigin origin, String anchor, String href) {
    CalendarSync pair = new CalendarSync();
    pair.setOrigin(origin);
    pair.setLocalCalendarSyncUid(anchor);
    pair.setRemoteHref(href);
    return pair;
  }
}
