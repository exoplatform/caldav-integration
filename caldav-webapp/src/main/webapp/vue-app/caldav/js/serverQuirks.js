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

/**
 * A stored comma-separated pattern list as its entries, blanks dropped.
 *
 * @param {String} list the stored value, may be null
 * @returns {Array} the entries
 */
export function splitList(list) {
  return (list || '').split(',').map(entry => entry.trim()).filter(entry => entry.length);
}

/**
 * The list with one behaviour's patterns present, or absent.
 *
 * <p>Removal is case-insensitive and addition writes the patterns exactly as
 * the catalogue declares them, so unticking a box always removes what ticking
 * it wrote — whatever spelling reached the row from an older build or from an
 * operator's own property file.</p>
 *
 * @param {Array} list the entries in force
 * @param {Array} patterns the patterns the behaviour is excused through
 * @param {Boolean} excused whether it should be excused
 * @returns {Array} the new entries
 */
export function withPatterns(list, patterns, excused) {
  const removed = patterns.map(pattern => pattern.toUpperCase());
  const kept = list.filter(entry => !removed.includes(entry.toUpperCase()));
  return excused && kept.concat(patterns) || kept;
}

/**
 * Rewrites a registration's two excusal lists from the drawer's ticks.
 *
 * <p><b>Only when there is something to express.</b> A registration nothing has
 * ever been observed on keeps whatever it arrived with — null, meaning the
 * deployment-wide setting still decides for it — so declaring a server, or
 * renaming one, never quietly freezes today's global list onto it.</p>
 *
 * <p><b>The effect decides which list, and only then the direction.</b> A
 * behaviour whose answer is that eXo stops WRITING something goes into the
 * omission list; one whose answer is that eXo stops NOTICING something goes
 * into the ignored list (a property the server adds) or the dropped list (one
 * it does not keep, or rewrites), exactly as the comparison reads them. The two
 * kinds are stored apart on purpose: a payload decision recorded in a tolerance
 * list would be a decision nobody reading that list could recognise.</p>
 *
 * @param {Object} server the registration being saved
 * @param {Array} quirks the behaviours as the drawer shows them, each carrying
 *        its patterns, its direction, its effect and its tick
 * @returns {Object} the same registration
 */
export function applyExcusals(server, quirks) {
  if (!quirks || !quirks.length) {
    return server;
  }
  let ignored = splitList(server.ignoredProperties);
  let dropped = splitList(server.droppedProperties);
  let omitted = splitList(server.omittedProperties);
  quirks.forEach(quirk => {
    if (quirk.effect === 'OMIT') {
      omitted = withPatterns(omitted, quirk.patterns, quirk.excused);
    } else if (quirk.direction === 'ADDED') {
      ignored = withPatterns(ignored, quirk.patterns, quirk.excused);
    } else {
      dropped = withPatterns(dropped, quirk.patterns, quirk.excused);
    }
  });
  server.ignoredProperties = ignored.join(',');
  server.droppedProperties = dropped.join(',');
  server.omittedProperties = omitted.join(',');
  return server;
}
