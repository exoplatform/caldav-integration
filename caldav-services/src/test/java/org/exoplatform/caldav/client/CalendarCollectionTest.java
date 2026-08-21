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
}
