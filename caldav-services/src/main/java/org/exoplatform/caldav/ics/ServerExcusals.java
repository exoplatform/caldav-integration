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
package org.exoplatform.caldav.ics;

import java.util.Arrays;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.caldav.model.ServerQuirk;

/**
 * What one CalDAV server has been declared to do, and therefore what a
 * divergence on it is not evidence of.
 *
 * <p>
 * <b>Per server, and read on every comparison.</b> The two lists arrive with
 * the call rather than being read once at boot, which is the whole point of
 * EXO-89771: an administrator ticking a box in the drawer changes what the
 * <i>next sweep</i> concludes, with no restart, and a deployment running
 * BlueMind alongside a well-behaved server no longer has to blind itself to
 * both to accommodate one.
 *
 * <p>
 * <b>Null and empty are different answers.</b> Null is "this server has never
 * been asked", and the deployment-wide property decides for it — which is what
 * makes upgrading to this change nothing at all. An empty string is an
 * administrator's own answer of "excuse nothing on this server", and it must
 * override a global list, or unticking the last box in the drawer would do
 * nothing.
 *
 * @param ignored patterns for a property the server adds that eXo never writes
 * @param dropped patterns for a property eXo writes that the server does not
 *          keep faithfully
 */
record ServerExcusals(String ignored, String dropped) {

  /**
   * The excusals in force for one server.
   *
   * @param serverIgnored the registration's ignored list, null when it has
   *          never been asked
   * @param serverDropped the registration's dropped list, null when it has
   *          never been asked
   * @param globalIgnored the deployment-wide ignored property
   * @param globalDropped the deployment-wide dropped property
   * @return the excusals to compare with
   */
  static ServerExcusals of(String serverIgnored, String serverDropped, String globalIgnored, String globalDropped) {
    return new ServerExcusals(serverIgnored == null ? globalIgnored : serverIgnored,
                              serverDropped == null ? globalDropped : serverDropped);
  }

  /**
   * Whether the administrator of this server has excused a divergence.
   *
   * <p>
   * <b>One method, both lists, because they are one decision seen from two
   * sides.</b> A property the server <i>adds</i> is excused through the ignored
   * list; a property eXo writes and the copy does not carry is excused through
   * the dropped list. Neither can excuse a value: two sides stating a property
   * differently is a rewrite, and a rewrite stays a difference —
   * <b>except</b> for the one catalogue entry that declares itself
   * {@code REWRITTEN}, the invitation text, whose whole
   * point is that BlueMind rewrites it. That exception is opt-in per entry and
   * not granted to every excused property, so a server excused for dropping a
   * conference link still reports a client that <i>changed</i> one — which is
   * exactly what that entry's wording promises.
   *
   * <p>
   * <b>Guarded so it can only ever excuse, never blind.</b> An excused rewrite
   * requires eXo to state the property at all: a property eXo never writes,
   * appearing on the copy, is a client addition and no setting of the dropped
   * list can hide it. And the dropped list is restricted to the properties eXo
   * actually writes, which is what keeps {@code OWNER-ATTENDEE} and an embedded
   * {@code VALARM{…}} statement outside it — neither is an event property, so
   * no list can be pointed at one to hide somebody's answer.
   *
   * @param statement the canonical statement that diverged
   * @param onServer how many times the server's copy states it
   * @param inExo how many times eXo's render states it
   * @param exoProperties the property names eXo's render states at all
   * @param recognised the closed set of properties eXo writes
   * @return true when this server is excused for it
   */
  boolean excuse(String statement, int onServer, int inExo, Set<String> exoProperties, Set<String> recognised) {
    String unrecognised = IcsStatement.unrecognisedNameOf(statement);
    if (unrecognised != null) {
      return onServer > inExo && excusesAdding(unrecognised);
    }
    String name = IcsStatement.propertyNameOf(statement);
    if (!recognised.contains(name) || !matches(dropped, name)) {
      return false;
    }
    if (inExo > onServer) {
      return true;
    }
    return exoProperties.contains(name) && ServerQuirk.rewriteExcusable(name);
  }

  /**
   * Whether this server is excused for adding a property eXo never writes.
   *
   * @param name the upper-cased property name
   * @return true when a pattern in the ignored list names it
   */
  boolean excusesAdding(String name) {
    return matches(ignored, name);
  }

  /**
   * Whether a comma-separated list of patterns names a property.
   *
   * <p>
   * The matching is {@link ServerQuirk#patternMatches}, the one the drawer's
   * ticking writes for and the catalogue reads with, so a family stored as
   * {@code X-MOZ-*} means the same thing wherever it is read.
   *
   * @param patterns the stored list, may be null or blank
   * @param name the upper-cased property name
   * @return true when one of the patterns names it
   */
  private boolean matches(String patterns, String name) {
    if (StringUtils.isBlank(patterns)) {
      return false;
    }
    return Arrays.stream(patterns.split(",")).anyMatch(pattern -> ServerQuirk.patternMatches(pattern, name));
  }
}
