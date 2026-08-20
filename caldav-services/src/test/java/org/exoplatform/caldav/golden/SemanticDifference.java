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
package org.exoplatform.caldav.golden;

/**
 * One semantic divergence found between two calendar objects: where it is,
 * what kind of statement diverged, and what each side says. Cosmetic
 * differences (folding, property order, DTSTAMP, PRODID) never become one of
 * these; a shifted instant, a dropped attendee or a changed rule always does.
 */
public final class SemanticDifference {

  /** The families of divergence the comparator reports. */
  public enum Kind {
    /** A component (an event, an override) one side holds and the other does not. */
    COMPONENT,
    /** A property statement differing inside a component both sides hold. */
    PROPERTY,
    /** The recurrence sets expand to different occurrence instants. */
    EXPANSION,
    /** A calendar-level statement (such as METHOD) differing. */
    CALENDAR
  }

  private final Kind   kind;

  private final String location;

  private final String left;

  private final String right;

  /**
   * Builds one reported divergence.
   *
   * @param kind the family of divergence
   * @param location the component or property the divergence sits in
   * @param left what the left-hand object states, or null when it states nothing
   * @param right what the right-hand object states, or null when it states nothing
   */
  public SemanticDifference(Kind kind, String location, String left, String right) {
    this.kind = kind;
    this.location = location;
    this.left = left;
    this.right = right;
  }

  /**
   * The family of divergence.
   *
   * @return the kind
   */
  public Kind getKind() {
    return kind;
  }

  /**
   * The component or property the divergence sits in.
   *
   * @return the location label
   */
  public String getLocation() {
    return location;
  }

  /**
   * What the left-hand object states at that location.
   *
   * @return the left-hand statement, or null when it states nothing there
   */
  public String getLeft() {
    return left;
  }

  /**
   * What the right-hand object states at that location.
   *
   * @return the right-hand statement, or null when it states nothing there
   */
  public String getRight() {
    return right;
  }

  /**
   * A one-line, human-readable account of the divergence, the line a failing
   * golden test prints so the broken invariant names itself.
   *
   * @return the divergence as one line
   */
  @Override
  public String toString() {
    return kind + " at " + location + ": left=" + (left == null ? "(nothing)" : left) + " | right="
        + (right == null ? "(nothing)" : right);
  }
}
