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
import {applyExcusals, describeQuirk, splitList, withPatterns} from '../../main/webapp/vue-app/caldav/js/serverQuirks.js';

/**
 * What ticking a box actually writes into the registration. This is the one
 * place a mistake is silent: a pattern written into the wrong list, or left
 * behind when a box is unticked, leaves the drawer saying one thing and the
 * sweep doing another — and the sweep is the one nobody is watching.
 */
describe('applyExcusals', () => {

  const conference = {direction: 'DROPPED', patterns: ['CONFERENCE'], excused: false};
  const markers = {direction: 'ADDED', patterns: ['X-MICROSOFT-*', 'X-MOZ-*'], excused: false};
  const description = {direction: 'REWRITTEN', patterns: ['DESCRIPTION'], excused: false};

  const soloOrganizer = {direction: 'DROPPED', effect: 'OMIT', patterns: ['SOLO-ORGANIZER'], excused: false};

  it('writes a payload-changing behaviour into the omission list and into neither tolerance', () => {
    // The two kinds of decision are stored apart on purpose: one stops eXo
    // noticing something, the other stops eXo writing it into somebody's
    // calendar, and a reader of either list must be able to tell which is which.
    const server = {ignoredProperties: '', droppedProperties: '', omittedProperties: ''};

    applyExcusals(server, [{...soloOrganizer, excused: true}]);

    expect(server.omittedProperties).toBe('SOLO-ORGANIZER');
    expect(server.droppedProperties).toBe('');
    expect(server.ignoredProperties).toBe('');
  });

  it('does not let the direction alone route a payload-changing behaviour', () => {
    // Its direction is DROPPED, like the conference entry; only its effect
    // separates them. Routing on direction would file a payload decision in a
    // tolerance list, where the writer never reads it and the box would do
    // nothing at all.
    const server = {ignoredProperties: '', droppedProperties: '', omittedProperties: ''};

    applyExcusals(server, [{...soloOrganizer, excused: true}, {...conference, excused: true}]);

    expect(server.omittedProperties).toBe('SOLO-ORGANIZER');
    expect(server.droppedProperties).toBe('CONFERENCE');
  });

  it('writes every pattern a grouped behaviour covers, not just the property seen first', () => {
    // The markers entry is one checkbox standing for a family. Live on the rig
    // BlueMind stamped three of them; ticking it must excuse the family the
    // sentence names, or the next marker the server invents comes straight back
    // as a fresh unticked row.
    const server = {ignoredProperties: '', droppedProperties: '', omittedProperties: ''};

    applyExcusals(server, [{...markers, excused: true}]);

    expect(server.ignoredProperties).toBe('X-MICROSOFT-*,X-MOZ-*');
  });

  it('writes a ticked behaviour into the list its direction belongs to', () => {
    const server = {ignoredProperties: '', droppedProperties: ''};

    applyExcusals(server, [{...conference, excused: true}, {...markers, excused: true}]);

    expect(server.droppedProperties).toBe('CONFERENCE');
    expect(server.ignoredProperties).toBe('X-MICROSOFT-*,X-MOZ-*');
  });

  it('writes a rewritten behaviour into the dropped list, like an absence', () => {
    const server = {ignoredProperties: '', droppedProperties: ''};

    applyExcusals(server, [{...description, excused: true}]);

    expect(server.droppedProperties).toBe('DESCRIPTION');
    expect(server.ignoredProperties).toBe('');
  });

  it('removes what unticking a box had written, whatever its spelling', () => {
    const server = {ignoredProperties: '', droppedProperties: 'conference , DESCRIPTION'};

    applyExcusals(server, [conference, description]);

    expect(server.droppedProperties).toBe('');
  });

  it('leaves an entry nothing on the list is about alone', () => {
    // An operator's own property file may name something the sweep has never
    // seen. Saving the drawer must not quietly drop it.
    const server = {ignoredProperties: 'X-SOMETHING-ELSE', droppedProperties: 'LOCATION'};

    applyExcusals(server, [{...conference, excused: true}]);

    expect(server.ignoredProperties).toBe('X-SOMETHING-ELSE');
    expect(server.droppedProperties).toBe('LOCATION,CONFERENCE');
  });

  it('turns the last untick into an empty list and not into a null one', () => {
    // Null means "never asked" and falls back to the deployment-wide setting.
    // Unticking the last box must be an answer, not a return to the fallback,
    // or the box would tick itself again on the next open.
    const server = {ignoredProperties: null, droppedProperties: 'CONFERENCE'};

    applyExcusals(server, [conference]);

    expect(server.droppedProperties).toBe('');
    expect(server.ignoredProperties).toBe('');
  });

  it('leaves a registration nothing has been observed on exactly as it arrived', () => {
    // Declaring a server, or renaming one, must not freeze today's
    // deployment-wide list onto it.
    const server = {ignoredProperties: null, droppedProperties: null, omittedProperties: null};

    applyExcusals(server, []);

    expect(server.ignoredProperties).toBeNull();
    expect(server.droppedProperties).toBeNull();
    expect(server.omittedProperties).toBeNull();
  });
});

describe('splitList and withPatterns', () => {

  it('reads a stored list without its blanks', () => {
    expect(splitList(' A , ,B ')).toEqual(['A', 'B']);
    expect(splitList(null)).toEqual([]);
    expect(splitList('')).toEqual([]);
  });

  it('adds each pattern once, however many times it was already there', () => {
    expect(withPatterns(['CONFERENCE', 'conference'], ['CONFERENCE'], true)).toEqual(['CONFERENCE']);
  });
});

describe('describeQuirk', () => {

  it('keys a described behaviour by its quirk, so a family is one checkbox', () => {
    // Live on the rig: three Outlook/Thunderbird markers rendered as three
    // identical rows. One key means one row.
    const rows = [
      {quirkId: 'addsCompatibilityMarkers', properties: ['X-MOZ-LASTACK'], direction: 'ADDED', count: 3,
        patterns: ['X-MICROSOFT-*', 'X-MOZ-*']},
      {quirkId: 'addsCompatibilityMarkers', properties: ['X-MICROSOFT-CDO-BUSYSTATUS'], direction: 'ADDED', count: 1,
        patterns: ['X-MICROSOFT-*', 'X-MOZ-*']},
    ].map(quirk => describeQuirk(quirk));

    expect(rows[0].key).toBe(rows[1].key);
  });

  it('keys a behaviour nothing describes by its own property', () => {
    const foo = describeQuirk({quirkId: null, properties: ['X-BM-FOO'], direction: 'ADDED', count: 1});
    const bar = describeQuirk({quirkId: null, properties: ['X-BM-BAR'], direction: 'ADDED', count: 1});

    expect(foo.key).not.toBe(bar.key);
  });

  it('ticks the whole family the catalogue names, not the property seen first', () => {
    const row = describeQuirk({quirkId: 'addsCompatibilityMarkers', properties: ['X-MOZ-LASTACK'], direction: 'ADDED',
      count: 3, patterns: ['X-MICROSOFT-*', 'X-MOZ-*']});

    expect(row.patterns).toEqual(['X-MICROSOFT-*', 'X-MOZ-*']);
  });

  it('falls back to the observed properties when no patterns arrived', () => {
    const row = describeQuirk({quirkId: null, properties: ['X-BM-FOO'], direction: 'ADDED', count: 1});

    expect(row.patterns).toEqual(['X-BM-FOO']);
  });

  it('chooses a count wording that agrees with itself', () => {
    // "Seen 1 times" was on screen; a plural that does not agree reads as a
    // defect in the thing it is counting. The key is chosen here and resolved in
    // the template, which is where $t still has its receiver.
    expect(describeQuirk({properties: ['X'], direction: 'ADDED', count: 1}).seenKey)
      .toBe('caldav.admin.servers.quirks.seen.once');
    expect(describeQuirk({properties: ['X'], direction: 'ADDED', count: 3}).seenKey)
      .toBe('caldav.admin.servers.quirks.seen.many');
  });

  it('resolves no wording itself, so no translator has to cross the boundary', () => {
    // The regression this shape exists to prevent: the drawer passed `this.$t`,
    // a method torn off its receiver, and every entry threw on $i18n. Returning
    // keys means there is no function to pass, correctly or otherwise.
    const row = describeQuirk({quirkId: 'dropsConference', properties: ['CONFERENCE'], direction: 'DROPPED', count: 1});

    expect(Object.values(row).some(value => typeof value === 'function')).toBe(false);
  });

  it('marks only a payload-changing behaviour as changing what eXo writes', () => {
    expect(describeQuirk({quirkId: 'omitsSoloOrganizer', properties: ['SOLO-ORGANIZER'], direction: 'DROPPED',
      effect: 'OMIT', count: 1}).changesWhatIsWritten).toBe(true);
    expect(describeQuirk({quirkId: 'dropsConference', properties: ['CONFERENCE'], direction: 'DROPPED',
      effect: 'TOLERATE', count: 1}).changesWhatIsWritten).toBe(false);
  });

  it('names an undescribed behaviour by its own property in the generic wording', () => {
    const row = describeQuirk({quirkId: null, properties: ['X-BM-FOO'], direction: 'ADDED', count: 1});

    expect(row.labelKey).toBe('caldav.admin.servers.quirks.generic.added.label');
    expect(row.property).toBe('X-BM-FOO');
  });
});
