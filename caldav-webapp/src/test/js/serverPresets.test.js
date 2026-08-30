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
import fs from 'fs';
import path from 'path';

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
    // The drawer's own initial state for the two copy settings: links on, and
    // a destination stated rather than absent.
    answerLinksInCopy: true,
    mirrorTarget: 'DEDICATED_CALENDAR',
  };
}

/**
 * The English bundle the drawer reads its sentences from, as a map.
 *
 * <p>Read from the file rather than mocked, because the assertion below is
 * about the SENTENCE an administrator gets shown — a mock of it would assert
 * that this test file says what this test file says.</p>
 *
 * @returns {Object} every key of the _en bundle against its value
 */
function englishBundle() {
  const bundle = fs.readFileSync(
    path.join(__dirname, '../../main/resources/locale/portlet/Caldav_en.properties'), 'utf8');
  const messages = {};
  bundle.split('\n').forEach(line => {
    const separator = line.indexOf('=');
    if (separator > 0 && !line.startsWith('#')) {
      messages[line.slice(0, separator).trim()] = line.slice(separator + 1);
    }
  });
  return messages;
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
    // Omitted: empty, and it is an answer rather than a gap. The one entry that
    // changes what eXo WRITES used to be pre-ticked here; since EXO-89805 eXo
    // names no organizer on an event with nobody but its creator on it, on
    // every server, so the box would buy nothing and the preset does not carry
    // one that changes nothing.
    expect(server.omittedProperties).toBe('');
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
    // No preset carries such a behaviour today (EXO-89805 removed the only one),
    // and the question is still asked of every preset rather than answered by
    // hand: the next one that does must light the warning without anybody
    // remembering to.
    SERVER_PRESETS.forEach(preset => {
      const carriesOne = (preset.quirks || []).includes('omitsSoloOrganizer');
      expect(presetChangesWhatIsWritten(preset.id)).toBe(carriesOne);
    });
    expect(presetChangesWhatIsWritten('bluemind')).toBe(false);
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

  it('keeps an administrator\'s disagreement with the two copy settings', () => {
    // The same rule as the excusal lists, and it matters more here: these two
    // change the document landing in a user's calendar and where it lands. An
    // administrator who overrules BlueMind's destination, or turns Stalwart's
    // answer links back on, is not corrected by anything.
    const bluemind = applyPreset(blankDeclaration(), 'bluemind');
    bluemind.mirrorTarget = 'DEDICATED_CALENDAR';
    expect(bluemind.mirrorTarget).toBe('DEDICATED_CALENDAR');

    const stalwart = applyPreset(blankDeclaration(), 'stalwart');
    stalwart.answerLinksInCopy = true;
    expect(stalwart.answerLinksInCopy).toBe(true);
  });

  it('replaces one characterised server\'s copy settings with the other\'s', () => {
    const server = applyPreset(blankDeclaration(), 'bluemind');
    applyPreset(server, 'stalwart');

    expect(server.mirrorTarget).toBe('DEDICATED_CALENDAR');
    expect(server.answerLinksInCopy).toBe(false);
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

describe('a preset states the two copy settings a server has a known answer for', () => {

  it('turns Stalwart\'s answer links off, and keeps the dedicated calendar', () => {
    const server = applyPreset(blankDeclaration(), 'stalwart');

    // Stalwart accepts an answer sent from any calendar, so the calendar
    // client's own Accept and Decline already work on a copy wherever it sits.
    // eXo's links would add text to every copy and buy nothing.
    expect(server.answerLinksInCopy).toBe(false);
    // Nothing here records Stalwart treating a dedicated calendar differently
    // from any other, so the destination stays the one eXo makes for itself.
    expect(server.mirrorTarget).toBe('DEDICATED_CALENDAR');
  });

  it('sends BlueMind\'s copies to the account main calendar, and keeps the answer links on', () => {
    const server = applyPreset(blankDeclaration(), 'bluemind');

    // BlueMind's dedicated calendar is known deficient: excluded from the
    // account's free/busy, so colleagues booking around the user see eXo
    // meeting times as free, and carrying no answer buttons.
    expect(server.mirrorTarget).toBe('MAIN_CALENDAR');
    // On BlueMind the answer buttons are the default calendar's only, so the
    // links in the description are what covers anything else.
    expect(server.answerLinksInCopy).toBe(true);
  });

  it('states them as values, not as a truthiness that would swallow the one that is false', () => {
    // The trap this guards: `false` is a statement here, and a preset field
    // tested for truth rather than for presence would turn Stalwart's answer
    // links from "off" into "unstated" - which the drawer would then leave on.
    const stalwart = presetValues('stalwart');
    expect(Object.prototype.hasOwnProperty.call(stalwart, 'answerLinksInCopy')).toBe(true);
    expect(stalwart.answerLinksInCopy).toBe(false);
  });
});

describe('a server we have not characterised gets no opinion about its copies either', () => {

  it('writes neither copy setting, so what the form carried survives', () => {
    // The same reasoning that leaves the excusal lists unset. And here it has
    // to be the KEYS that are absent, not nulls: a null answerLinksInCopy reads
    // as "no links" and a null mirrorTarget as the default destination, so
    // stating either would be an opinion about a server nobody has run.
    const values = presetValues(PRESET_NONE);

    expect(Object.prototype.hasOwnProperty.call(values, 'answerLinksInCopy')).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(values, 'mirrorTarget')).toBe(false);
  });

  it('leaves what the administrator already chose exactly where it was', () => {
    // The assertion the key test above cannot make on its own: an undefined
    // value assigned over a field still overwrites it.
    const server = blankDeclaration();
    server.answerLinksInCopy = false;
    server.mirrorTarget = 'MAIN_CALENDAR';

    applyPreset(server, PRESET_NONE);

    expect(server.answerLinksInCopy).toBe(false);
    expect(server.mirrorTarget).toBe('MAIN_CALENDAR');
  });

  it('does not put back what a characterised preset chose a moment earlier', () => {
    // Deliberate, and the asymmetry is the point: what BlueMind stated stays on
    // a control the administrator can read and change, which beats a shortcut
    // silently restoring a destination nobody chose either.
    const server = applyPreset(blankDeclaration(), 'bluemind');
    applyPreset(server, PRESET_NONE);

    expect(server.name).toBe('');
    expect(server.mirrorTarget).toBe('MAIN_CALENDAR');
  });
});

describe('a preset says what it chose about the copies', () => {

  const messages = englishBundle();

  it('states both copy settings in the summary of every preset that states them', () => {
    // A shortcut that silently turns something off, or silently picks the more
    // demanding destination, is worse than one that touches neither: the
    // administrator meets the choice later, as a symptom, with nothing to
    // connect it to. So a preset may only state these where its summary says
    // so - which is what this loop enforces for any preset added later too.
    SERVER_PRESETS.forEach(preset => {
      const values = presetValues(preset.id);
      const states = Object.prototype.hasOwnProperty.call(values, 'mirrorTarget')
          || Object.prototype.hasOwnProperty.call(values, 'answerLinksInCopy');
      if (states) {
        const summary = messages[`caldav.admin.servers.preset.${preset.id}.summary`];
        expect(summary).toBeDefined();
        expect(summary).toMatch(/answer links/i);
        expect(summary).toMatch(/calendar/i);
      }
    });
  });

  it('says that BlueMind gets the main calendar, why, and that it is still worth watching', () => {
    // The honest tension. The main-calendar option's own consequence line says
    // to choose it only once copies synchronise cleanly, and this preset
    // chooses it up front - so the summary is where the two are reconciled,
    // rather than by softening a caution that is right for the general case.
    const summary = messages['caldav.admin.servers.preset.bluemind.summary'];

    expect(summary).toMatch(/main calendar/i);
    expect(summary).toMatch(/availability/i);
    expect(summary).toMatch(/no answer buttons/i);
    expect(summary).toMatch(/watch the first synchronisations/i);
    expect(summary).toMatch(/answer links are left on/i);
  });

  it('says that Stalwart\'s answer links are off, and why that costs nothing', () => {
    const summary = messages['caldav.admin.servers.preset.stalwart.summary'];

    expect(summary).toMatch(/answer links are turned off/i);
    expect(summary).toMatch(/any calendar/i);
    expect(summary).toMatch(/dedicated eXo Meetings calendar/i);
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
      'mirrorTarget',
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
