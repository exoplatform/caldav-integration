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
import {applyExcusals, splitList, withPatterns} from '../../main/webapp/vue-app/caldav/js/serverQuirks.js';

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
