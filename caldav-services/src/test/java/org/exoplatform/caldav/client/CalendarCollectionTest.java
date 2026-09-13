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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The one judgement a listed collection makes about itself: whether it holds
 * events at all.
 *
 * <p>
 * Worth pinning on the record rather than only through the sync, because the
 * distinction it draws is not the obvious one. RFC 4791 §5.2.3 makes
 * {@code supported-calendar-component-set} optional, so "the server said
 * nothing" and "the server said no events" arrive as very different answers
 * and must not be read alike: reading silence as a refusal would drop every
 * calendar of every server that omits the property, and reading a refusal as
 * silence hands the user a task list dressed as a calendar.
 */
public class CalendarCollectionTest {

  private static final String HREF = "/dav/calendars/john/private/";

  /**
   * A collection that names VEVENT is a calendar, plainly.
   */
  @Test
  public void aCollectionDeclaringEventsHoldsEvents() {
    assertTrue(collectionOf(Set.of("VEVENT")).holdsEvents());
  }

  /**
   * A task list is the case this whole method exists for: BlueMind publishes
   * one beside the calendars and it answers a calendar PROPFIND exactly as a
   * calendar does, so its component set is the only thing telling them apart.
   */
  @Test
  public void aCollectionDeclaringOnlyTodosHoldsNoEvents() {
    assertFalse(collectionOf(Set.of("VTODO")).holdsEvents());
  }

  /**
   * A calendar that also accepts todos is still a calendar. Requiring VEVENT
   * to stand alone would exclude an ordinary collection for having said too
   * much.
   */
  @Test
  public void aCollectionDeclaringEventsAmongOthersHoldsEvents() {
    assertTrue(collectionOf(Set.of("VTODO", "VEVENT", "VJOURNAL")).holdsEvents());
  }

  /**
   * An explicit set naming neither events nor todos is still an explicit
   * refusal: the server spoke, and it did not name VEVENT.
   */
  @Test
  public void aCollectionDeclaringSomethingElseEntirelyHoldsNoEvents() {
    assertFalse(collectionOf(Set.of("VJOURNAL")).holdsEvents());
  }

  /**
   * The property is optional, and its absence means every component is
   * supported. An empty set is a server that did not say, not one that said
   * "nothing" — the opposite reading would silently drop the calendars of
   * every server that omits it.
   */
  @Test
  public void anUndeclaredComponentSetIsSilenceRatherThanARefusal() {
    assertTrue(collectionOf(Set.of()).holdsEvents());
  }

  /**
   * The same silence, arriving as a null rather than an empty set — a record
   * built by hand, or deserialised, must not answer differently from one the
   * parser produced.
   */
  @Test
  public void aNullComponentSetIsTheSameSilence() {
    assertTrue(collectionOf(null).holdsEvents());
  }

  /**
   * The six-argument form is what every caller used before the component set
   * was read, and it must keep meaning what it meant: a collection nobody
   * asked about the components of is a calendar.
   */
  @Test
  public void theSixArgumentFormLeavesTheSetEmptyAndTheCollectionACalendar() {
    CalendarCollection collection = new CalendarCollection(HREF, "Private", "ctag-1", "token-1", "#0088FF", true);

    assertEquals(Set.of(), collection.components(), "an undeclared set is empty, never null");
    assertTrue(collection.holdsEvents());
    // The other six fields still arrive where they were put: the added
    // component set must not have shifted anything beside it.
    assertEquals(HREF, collection.href());
    assertEquals("Private", collection.displayName());
    assertEquals("ctag-1", collection.ctag());
    assertEquals("token-1", collection.syncToken());
    assertEquals("#0088FF", collection.color());
    assertTrue(collection.writable());
  }

  /**
   * A listed collection declaring the given component set.
   *
   * @param components the component types the server declared, or null when it
   *          declared none
   * @return the collection
   */
  private CalendarCollection collectionOf(Set<String> components) {
    return new CalendarCollection(HREF, "Private", "ctag-1", "token-1", null, true, components);
  }

  // ------------------------------------ whose calendar it is, EXO-90235

  private static final String BOB   = "/dav/pal/bob%40stalwart.local/";

  private static final String ALICE = "/dav/pal/alice%40stalwart.local/";

  /**
   * The Stalwart shape that caused the defect: the colleague named as owner
   * and a read-only privilege set. Either signal alone is enough; this is
   * both.
   */
  @Test
  public void aCollectionOwnedByAnotherAndReadOnlyIsAShare() {
    assertTrue(collectionOf(ALICE, true, false).isSharedWith(BOB));
  }

  /**
   * Owner alone: a colleague's calendar the user may write into is still not
   * the user's own, and is not materialised as such — read-write sharing is a
   * later step, taken on purpose, not a side effect of a privilege set.
   */
  @Test
  public void aCollectionOwnedByAnotherIsAShareEvenWhenWritable() {
    assertTrue(collectionOf(ALICE, true, true).isSharedWith(BOB));
  }

  /**
   * Privileges alone: a server that names no owner but grants no write has
   * still said the user may not write there.
   */
  @Test
  public void aReadOnlyCollectionIsAShareEvenWhenTheServerNamesNoOwner() {
    assertTrue(collectionOf(null, true, false).isSharedWith(BOB));
  }

  /**
   * The user's own calendar, as the server names it: owner equals principal,
   * write granted. Not a share, whatever else is true of it.
   */
  @Test
  public void theUsersOwnCollectionIsNotAShare() {
    assertFalse(collectionOf(BOB, true, true).isSharedWith(BOB));
  }

  /**
   * Silence is not a signal. Google answers no privilege set at all, and
   * reading that as read-only would turn every Google calendar into a share
   * — the reverse of the defect. No owner, no privilege set: the user's own.
   */
  @Test
  public void aCollectionTheServerSaidNothingAboutIsNotAShare() {
    assertFalse(collectionOf(null, false, false).isSharedWith(BOB));
  }

  /**
   * A principal the server did not name cannot be compared against, so the
   * owner signal stays off — an owner that is merely <em>unknown to be</em>
   * the user's must not count against the collection.
   */
  @Test
  public void anOwnerCannotBeComparedAgainstAnUnknownPrincipal() {
    assertFalse(collectionOf(ALICE, true, true).isSharedWith(null));
    assertFalse(collectionOf(ALICE, true, true).isSharedWith(" "));
  }

  /**
   * Compared as paths. Stalwart spells the principal segment percent-encoded;
   * a server answering the owner decoded, or one of the two without its
   * trailing slash, still names the same principal, and treating the
   * spellings as two people would hide the user's own calendars.
   */
  @Test
  public void anOwnerIsComparedAsAPathNotAsAString() {
    assertFalse(collectionOf("/dav/pal/bob@stalwart.local/", true, true).isSharedWith(BOB), "decoded against encoded");
    assertFalse(collectionOf("/dav/pal/bob%40stalwart.local", true, true).isSharedWith(BOB), "no trailing slash");
    assertFalse(collectionOf(BOB, true, true).isSharedWith("/dav/pal/bob@stalwart.local"), "both differences at once");
    assertTrue(collectionOf(ALICE, true, true).isSharedWith("/dav/pal/bob@stalwart.local"),
               "a genuinely different principal still differs once both are decoded");
  }

  /**
   * The six- and seven-argument forms describe a collection nothing beyond
   * its own properties was said about — no owner, no privilege set — so a
   * record built by hand never classifies as a share by accident, whatever
   * its {@code writable} flag says. That is what keeps every test that built
   * a read-only collection before ownership was read meaning what it meant.
   */
  @Test
  public void theLegacyFormsNameNoOwnerAndAnswerNoPrivilegeSet() {
    CalendarCollection six = new CalendarCollection(HREF, "Private", null, null, null, false);
    CalendarCollection seven = new CalendarCollection(HREF, "Private", null, null, null, false, Set.of("VEVENT"));

    assertNull(six.owner());
    assertFalse(six.privilegesAnswered());
    assertFalse(six.isSharedWith(BOB), "writable=false without an answered set is silence, not read-only");
    assertNull(seven.owner());
    assertFalse(seven.privilegesAnswered());
    assertFalse(seven.isSharedWith(BOB));
  }

  /**
   * @param owner the owner the server named, or null
   * @param privilegesAnswered whether it answered a privilege set
   * @param writable whether that set grants write
   * @return a calendar collection with those ownership facts
   */
  private CalendarCollection collectionOf(String owner, boolean privilegesAnswered, boolean writable) {
    return new CalendarCollection(HREF, "Private", "ctag-1", "token-1", null, writable, Set.of("VEVENT"), owner, privilegesAnswered);
  }

  // ------------------------------------ the owner worth naming, EXO-90237

  /**
   * The owner is answered as a path only when it is somebody else: the
   * user's own principal, however the server spells it, is not an owner to
   * name — a collection can be a share by the privilege signal while naming
   * the user as owner, and that owner must not be shown as "who shared it".
   * Unknown on either side names nobody.
   */
  @Test
  public void theOwnerIsNamedOnlyWhenItIsSomebodyElse() {
    assertEquals(ALICE, collectionOf(ALICE, true, false).ownerIfAnother(BOB), "another principal");
    assertNull(collectionOf(BOB, true, false).ownerIfAnother(BOB), "the user, read-only: a share by privilege, no owner to name");
    assertNull(collectionOf("/dav/pal/bob@stalwart.local", true, false).ownerIfAnother(BOB), "the user, spelled decoded and unslashed");
    assertNull(collectionOf(null, true, false).ownerIfAnother(BOB), "no owner named");
    assertNull(collectionOf(ALICE, true, false).ownerIfAnother(null), "no principal to compare against");
    assertNull(collectionOf(ALICE, true, false).ownerIfAnother(" "), "a blank principal is none");
  }
}
