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
import {
  SERVER_PRESETS,
  PRESET_NONE,
  DEFAULT_URL_PLACEHOLDER,
  applyPreset,
  presetById,
  presetValues,
  presetUrlPlaceholder,
  presetChangesWhatIsWritten,
} from '../../main/webapp/vue-app/caldav/js/serverPresets.js';

/**
 * The form an administrator has in front of them when declaring a server,
 * before anything is chosen — the drawer's own initial state, minus the fields
 * a preset has no business touching.
 *
 * @returns {Object} a blank declaration
 */
function blankDeclaration() {
  return {
    id: '',
    name: '',
    description: '',
    serverUrl: '',
    active: true,
    icon: '',
    answerLinksInCopy: true,
  };
}

describe('choosing a preset fills what an administrator could not have known', () => {

  it('fills BlueMind\'s name and every behaviour this codebase established for it', () => {
    const server = applyPreset(blankDeclaration(), 'bluemind');

    expect(server.name).toBe('BlueMind');
    // Dropped: eXo writes it, the copy comes back without it. CONFERENCE was
    // proved dropped 399 times in one day before anyone knew to look.
    expect(server.droppedProperties).toBe('CONFERENCE');
    // Added: proprietary markers eXo never writes. The tick covers the whole
    // family, not the one marker a deployment happened to meet first.
    expect(server.ignoredProperties).toBe('X-MICROSOFT-*,X-MOZ-*,X-ALT-DESC');
    // Omitted: the one entry that changes what eXo WRITES rather than what it
    // notices, and it is a case rather than a property name on purpose.
    expect(server.omittedProperties).toBe('SOLO-ORGANIZER');
  });

  it('fills Stalwart as a server with nothing to excuse, which is an answer and not a gap', () => {
    const server = applyPreset(blankDeclaration(), 'stalwart');

    expect(server.name).toBe('Stalwart');
    // Empty, not null: Stalwart has been characterised and keeps what eXo
    // writes, so a deployment-wide list set for somebody else's BlueMind must
    // not go on blinding it.
    expect(server.ignoredProperties).toBe('');
    expect(server.droppedProperties).toBe('');
    expect(server.omittedProperties).toBe('');
  });

  it('shows each server\'s address shape, which is the one field a preset cannot fill', () => {
    // BlueMind answers at the DAV root and discovers the account's calendars
    // itself; Stalwart's seed shape templates the account into the path.
    expect(presetUrlPlaceholder('bluemind')).toBe('https://bluemind.example.org/dav/');
    expect(presetUrlPlaceholder('stalwart')).toBe('https://stalwart.example.org/dav/cal/{username}/');
    expect(presetUrlPlaceholder(PRESET_NONE)).toBe(DEFAULT_URL_PLACEHOLDER);
  });

  it('says which preset changes what eXo writes rather than only what it compares', () => {
    expect(presetChangesWhatIsWritten('bluemind')).toBe(true);
    expect(presetChangesWhatIsWritten('stalwart')).toBe(false);
    expect(presetChangesWhatIsWritten(PRESET_NONE)).toBe(false);
  });

  it('never excuses the invitation text, on any preset', () => {
    // Excusing the description stops the text of every copy being compared,
    // answer links included (EXO-89752/89753). That is given up in front of
    // evidence from a deployment, never by a shortcut on its behalf.
    SERVER_PRESETS.forEach(preset => {
      expect(preset.quirks || []).not.toContain('rewritesDescription');
      const values = presetValues(preset.id);
      expect(values.droppedProperties || '').not.toContain('DESCRIPTION');
    });
  });
});

describe('the option for a server we have not characterised', () => {

  it('fills nothing at all', () => {
    const blank = blankDeclaration();
    const server = applyPreset(blankDeclaration(), PRESET_NONE);

    expect(server.name).toBe(blank.name);
    expect(server.serverUrl).toBe(blank.serverUrl);
  });

  it('leaves the excusal lists unset, so the deployment-wide settings still decide', () => {
    const server = applyPreset(blankDeclaration(), PRESET_NONE);

    // Null, not empty. "Nobody has asked" and "we looked, there is nothing"
    // are different statements and the row must not confuse them.
    expect(server.ignoredProperties).toBeNull();
    expect(server.droppedProperties).toBeNull();
    expect(server.omittedProperties).toBeNull();
  });

  it('is what an unknown identifier falls back to, so nothing applies somebody else\'s settings', () => {
    expect(presetById('a-server-nobody-added').id).toBe(PRESET_NONE);
    expect(applyPreset(blankDeclaration(), undefined).droppedProperties).toBeNull();
  });
});

describe('a preset is a starting point, not a lock', () => {

  it('keeps an administrator\'s edits made after choosing', () => {
    const server = applyPreset(blankDeclaration(), 'bluemind');

    // What the administrator does next, in the drawer, before saving.
    server.name = 'Group calendars';
    server.serverUrl = 'https://calendar.acme.example/dav/';
    server.droppedProperties = '';

    // The payload the drawer sends is exactly what they left behind. Nothing
    // re-applies the preset, because nothing remembers it was chosen.
    expect(server.name).toBe('Group calendars');
    expect(server.serverUrl).toBe('https://calendar.acme.example/dav/');
    expect(server.droppedProperties).toBe('');
  });

  it('replaces the previous preset rather than merging with it', () => {
    const server = applyPreset(blankDeclaration(), 'bluemind');
    applyPreset(server, 'stalwart');

    expect(server.name).toBe('Stalwart');
    // Not one pattern of the server chosen a moment ago survives.
    expect(server.droppedProperties).toBe('');
    expect(server.ignoredProperties).toBe('');
    expect(server.omittedProperties).toBe('');
  });

  it('goes back to nothing when the administrator changes their mind to an uncharacterised server', () => {
    const server = applyPreset(blankDeclaration(), 'bluemind');
    applyPreset(server, PRESET_NONE);

    expect(server.name).toBe('');
    expect(server.droppedProperties).toBeNull();
    expect(server.ignoredProperties).toBeNull();
    expect(server.omittedProperties).toBeNull();
  });
});

describe('a preset is copied into the row, never linked to it', () => {

  it('writes no reference to the preset it came from', () => {
    const server = applyPreset(blankDeclaration(), 'bluemind');

    // A row that named its preset would be a row a later build could reach.
    // The only trace a preset leaves is the values themselves.
    expect(Object.keys(server).sort()).toEqual([
      'active',
      'answerLinksInCopy',
      'description',
      'droppedProperties',
      'icon',
      'id',
      'ignoredProperties',
      'name',
      'omittedProperties',
      'serverUrl',
    ]);
  });

  it('does not change a row already declared when we later learn something new about that server', () => {
    const declaredLastYear = applyPreset(blankDeclaration(), 'stalwart');
    const asDeclared = {...declaredLastYear};

    // An upgrade teaching us something new about Stalwart: the catalogue entry
    // grows. This is the whole reason the question "copy or link?" had to be
    // answered — a link would let this reach a running deployment, changing
    // what somebody's mirror compares without anybody asking.
    const stalwart = SERVER_PRESETS.find(preset => preset.id === 'stalwart');
    const asShipped = stalwart.quirks;
    try {
      stalwart.quirks = asShipped.concat(['dropsConference']);

      // Nothing happens to the row. The administrator meets the new behaviour
      // the way they meet any other: the sweep observes it on their own server,
      // the drawer offers it with its cost, and they decide.
      expect(declaredLastYear).toEqual(asDeclared);
      // And a row declared after the upgrade does carry it — the catalogue is
      // read at the moment of declaring, and never again.
      expect(applyPreset(blankDeclaration(), 'stalwart').droppedProperties).toBe('CONFERENCE');
    } finally {
      stalwart.quirks = asShipped;
    }
  });
});
