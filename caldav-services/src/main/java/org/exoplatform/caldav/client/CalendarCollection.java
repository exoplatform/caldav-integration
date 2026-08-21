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

/**
 * One calendar collection as a PROPFIND listed it: everything the sync
 * engine binds a pair on, and nothing it does not need. Hrefs are answered
 * as server-absolute raw paths — exactly the shape they travel back out in,
 * so a round trip never re-encodes them.
 *
 * @param href the collection's server-absolute raw path, trailing slash as
 *          the server sent it
 * @param displayName the collection's display name, or null — data, never
 *          identity: nothing may key on it
 * @param ctag the CalendarServer ctag, or null when the server has none
 * @param syncToken the RFC 6578 sync token, or null when unsupported
 * @param color the Apple calendar-color, or null
 * @param writable whether the current user's privilege set carries write —
 *          false as well when the server answered no privilege set at all
 */
import java.util.Set;

public record CalendarCollection(String href,
                                 String displayName,
                                 String ctag,
                                 String syncToken,
                                 String color,
                                 boolean writable,
                                 Set<String> components) {

  /**
   * A collection whose server declared no component set.
   * <p>
   * The six-argument form every caller used before the set was read: an
   * undeclared set is an empty one, which {@link #holdsEvents()} reads as
   * "the server did not say" — the RFC 4791 default of supporting everything.
   *
   * @param href the collection path
   * @param displayName the name the server gives it
   * @param ctag the collection change tag
   * @param syncToken the RFC 6578 sync token
   * @param color the colour the server carries
   * @param writable whether the caller may write to it
   */
  public CalendarCollection(String href,
                            String displayName,
                            String ctag,
                            String syncToken,
                            String color,
                            boolean writable) {
    this(href, displayName, ctag, syncToken, color, writable, Set.of());
  }

  /**
   * Whether this collection holds events at all.
   * <p>
   * RFC 4791 §5.2.3 makes {@code supported-calendar-component-set} optional,
   * and a collection that omits it supports every component. An empty set is
   * therefore a server that did not say, not a server that said "nothing" —
   * both answer true. Only an explicit set without VEVENT answers false, which
   * is what keeps a task list from becoming a calendar.
   *
   * @return true unless the server explicitly excluded events
   */
  public boolean holdsEvents() {
    return components == null || components.isEmpty() || components.contains("VEVENT");
  }
}
