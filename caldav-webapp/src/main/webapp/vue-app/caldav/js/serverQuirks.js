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
 * One observed behaviour as the drawer shows it.
 *
 * <p>Here rather than in the component because three of its decisions are worth
 * pinning and none of them is presentation: which entries are one row, which
 * patterns a tick writes, and whether the count reads as a sentence.</p>
 *
 * <p><b>One row per behaviour.</b> A catalogue entry can cover a family, so its
 * key is the quirk id where there is one — three Outlook and Thunderbird
 * markers are one habit and one checkbox. A behaviour nothing describes is keyed
 * by its own property, because there each property genuinely is its own
 * thing.</p>
 *
 * <p><b>A tick writes the whole family.</b> The patterns come from the
 * catalogue, not from the properties that happened to be seen, so the next
 * marker the server invents is already excused rather than arriving as a fresh
 * unticked row. The observed properties are the fallback for a payload the
 * catalogue did not describe.</p>
 *
 * <p><b>It resolves no wording, on purpose.</b> It used to take the component's
 * <code>$t</code> and return finished sentences, and the drawer passed
 * <code>this.$t</code> — a method torn off its receiver, which threw
 * <code>Cannot read properties of undefined (reading '$i18n')</code> the moment
 * a server actually had a behaviour to show. Binding it would have fixed that
 * one call; returning <b>keys</b> instead means no function crosses this
 * boundary at all, so the next caller cannot make the same mistake. The
 * template resolves them, which is where <code>$t</code> already has its
 * receiver.</p>
 *
 * @param {Object} quirk the observed behaviour as the server sent it
 * @returns {Object} the behaviour, with its wording keys and its tick
 */
export function describeQuirk(quirk) {
  // The catalogue's own sentence when it has one, a sentence built from the
  // direction and the property name when it has not - so a server nobody here
  // has seen is still described, and an administrator is never blocked by the
  // catalogue being incomplete.
  const wording = quirk.quirkId || `generic.${(quirk.direction || 'ADDED').toLowerCase()}`;
  const properties = quirk.properties || [];
  const property = properties[0];
  return {
    key: quirk.quirkId || `${quirk.direction}:${property}`,
    patterns: quirk.patterns && quirk.patterns.length && quirk.patterns || properties,
    direction: quirk.direction,
    effect: quirk.effect,
    // Ticking this one changes the document eXo writes into somebody's calendar
    // rather than only what eXo notices about it.
    changesWhatIsWritten: quirk.effect === 'OMIT',
    count: quirk.count,
    // Written out rather than interpolated into one string: "Seen 1 times" was
    // on screen, and a plural that does not agree reads as a defect in the thing
    // it is counting.
    seenKey: quirk.count === 1 && 'caldav.admin.servers.quirks.seen.once'
      || 'caldav.admin.servers.quirks.seen.many',
    excused: !!quirk.excused,
    // The invitation text is the one entry that must not read as another
    // tick-box: excusing it stops the text of every copy being compared, answer
    // links included.
    warning: quirk.quirkId === 'rewritesDescription',
    labelKey: `caldav.admin.servers.quirks.${wording}.label`,
    costKey: `caldav.admin.servers.quirks.${wording}.cost`,
    // The argument the generic wording interpolates; harmless for a catalogue
    // sentence, which names no property.
    property,
    // Kept so a reader of the row - or of a bug report - can see which
    // properties an entry was built from, without it crowding the sentence.
    properties,
  };
}

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
