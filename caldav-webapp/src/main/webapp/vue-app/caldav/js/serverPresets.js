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
 * What an administrator would otherwise have had to know before declaring a
 * CalDAV server: its name, the shape of its address, and the behaviours it is
 * known to have towards the copies eXo writes into it.
 *
 * A preset is a copy, never a link
 * -------------------------------
 * Choosing one writes its values into the row being declared and is then
 * finished with. The row carries the values, not a reference to this file, and
 * no later reading of this file can reach a server somebody already declared.
 * Two reasons, and the second settles it:
 *
 * - An administrator may disagree with any value here. Their BlueMind may sit
 *   behind a proxy that keeps what ours drops, or their address may not be
 *   shaped like ours. A value they can edit — and that stays edited — is a
 *   value they own.
 * - A link would let a later build change what a running deployment compares,
 *   on a server nobody asked us about. "The mirror stopped noticing something
 *   last Tuesday because we shipped a release" is not a sentence an
 *   administrator should ever have to discover.
 *
 * The consequence, stated rather than hidden
 * ------------------------------------------
 * When a later version learns a new quirk about a server listed here — as this
 * codebase learnt BlueMind's the expensive way, one repair loop at a time —
 * rows already declared DO NOT CHANGE. They keep exactly what was copied into
 * them on the day they were declared. The administrator meets the new
 * behaviour the way they would meet any other: the sweep observes it on their
 * server, the drawer offers it with its cost, and they excuse it if they want
 * to (EXO-89771). Nothing is repaired behind their back, and nothing is lost —
 * only deferred to the moment they can see it and decide.
 *
 * The quirk identifiers mirror the code-defined catalogue
 * ------------------------------------------------------
 * They are the ids and patterns of the server-side `ServerQuirk` enum, which is
 * where a behaviour gets its sentence and its cost. A preset invents no quirk;
 * it names ones the catalogue already carries, so that what a preset ticks and
 * what the drawer later shows a server doing are the same thing under the same
 * name.
 *
 * Three behaviours the presets deliberately do NOT carry
 * -----------------------------------------------------
 * - Everything BlueMind does that eXo already handles for every server: the
 *   URI it appends a second time in a description, the `VERSION` it writes
 *   inside the VEVENT, the CN/DIR it substitutes from its own directory, the
 *   calendar owner it re-attaches as an attendee, the whitespace it
 *   re-serialises. Those are settled in `IcsEquivalence` for everyone, so
 *   there is nothing left for a preset to excuse.
 * - `rewritesDescription`, ever, on any preset. Excusing the invitation text
 *   stops the text of every copy being compared, answer links included. That
 *   is a safety property an administrator gives up in front of evidence from
 *   their own deployment — never one a shortcut gives up for them.
 * - Anything about a server nobody here has run. The list below is what this
 *   codebase has characterised, and it is deliberately short.
 */

/**
 * The identifier of the option that fills nothing.
 *
 * Its wording is deliberately not "other supported server" or anything of that
 * family. The list below is what we have characterised; being absent from it
 * says nothing at all about whether a server works.
 */
export const PRESET_NONE = 'other';

/**
 * The URL shape shown when no preset has been chosen — a form to recognise
 * rather than a sentence to read.
 */
export const DEFAULT_URL_PLACEHOLDER = 'https://caldav.example.org/dav/cal/{username}/';

/**
 * The registration field holding the patterns a server is excused for ADDING
 * to the copies it stores.
 */
const IGNORED = 'ignoredProperties';

/**
 * The registration field holding the patterns a server is excused for NOT
 * KEEPING faithfully.
 */
const DROPPED = 'droppedProperties';

/**
 * The registration field holding what eXo LEAVES OUT of the copies it writes to
 * a server — the one list that changes the document landing in somebody's
 * calendar rather than what eXo notices about it.
 */
const OMITTED = 'omittedProperties';

/**
 * The catalogue entries a preset may name, with the list each writes into and
 * the patterns it writes — the same ids and patterns as the server-side
 * `ServerQuirk` enum, so a preset and the drawer's own check-boxes cannot mean
 * different things by the same name.
 *
 * `omitsSoloOrganizer` is the only one here that changes what eXo writes; the
 * wording beside a preset that carries it says so, because a box that alters
 * somebody's calendar must not look like a box that only alters a comparison.
 */
const QUIRKS = {
  dropsConference: {list: DROPPED, patterns: ['CONFERENCE']},
  addsCompatibilityMarkers: {list: IGNORED, patterns: ['X-MICROSOFT-*', 'X-MOZ-*']},
  addsFormattedDescription: {list: IGNORED, patterns: ['X-ALT-DESC']},
  omitsSoloOrganizer: {list: OMITTED, patterns: ['SOLO-ORGANIZER']},
};

/**
 * The servers this codebase has characterised, plus the option for one it has
 * not.
 *
 * No preset fills an icon. The only icon a preset could fill is a generic font
 * glyph, and `serverIconIdentity.js` already settled that a generic glyph is a
 * worse identity than the packaged CalDAV image. What a user would actually
 * recognise is the vendor's own logo, which is an image an administrator
 * uploads. The field is carried here so that a preset shipping a packaged logo
 * one day sets it and nothing else has to change.
 */
export const SERVER_PRESETS = [
  {
    /*
     * BlueMind, characterised on a live account across EXO-89716 to EXO-89775.
     *
     * Its address is the DAV root. That is where BlueMind answers — its `/dav/`
     * returns 401 Basic realm="bm.basic.auth.v2" while the bare host only
     * redirects — and it needs no `{username}`: the server's own
     * current-user-principal discovery finds the account's calendars, whose
     * real hrefs are GUID-based and could not have been typed anyway.
     *
     * The four behaviours are what kept copies of a live account in a permanent
     * repair loop until each was recognised — `CONFERENCE` alone was proved
     * dropped 399 times in one day, five copies rewritten every five minutes.
     */
    id: 'bluemind',
    name: 'BlueMind',
    icon: null,
    urlPlaceholder: 'https://bluemind.example.org/dav/',
    quirks: ['dropsConference', 'addsCompatibilityMarkers', 'addsFormattedDescription', 'omitsSoloOrganizer'],
  },
  {
    /*
     * Stalwart, the server the golden corpus and the seed registration are
     * built on. Its address carries a `{username}`: the seed row's own default
     * is `.../dav/cal/{username}/`, and the device-setup helper substitutes it
     * the same way the platform's client does.
     *
     * An empty quirk set, and it is an answer rather than a gap. Nothing in
     * this codebase records Stalwart adding, dropping or rewriting a property
     * on a copy eXo wrote; what it does change — quoting a CN parameter,
     * ordering RRULE parts, re-folding long lines — is serialisation the
     * comparison settles for every server. It is the server the tests call
     * byte-stable, and against which the re-serialisation guard "was silent".
     *
     * The empty list is WRITTEN to the row rather than left unset, which is the
     * whole point of characterising a server: a deployment-wide
     * `droppedProperties` set for somebody else's BlueMind does not get to
     * blind a Stalwart that keeps everything.
     */
    id: 'stalwart',
    name: 'Stalwart',
    icon: null,
    urlPlaceholder: 'https://stalwart.example.org/dav/cal/{username}/',
    quirks: [],
  },
  {
    /*
     * A server we have not characterised. Fills nothing, and — unlike the two
     * above — leaves the three excusal lists UNSET, so the deployment-wide
     * settings go on deciding for it exactly as they did before this option
     * existed. Not knowing a server is not the same statement as knowing it has
     * nothing to excuse, and a row must not confuse the two.
     */
    id: PRESET_NONE,
    name: '',
    icon: null,
    urlPlaceholder: DEFAULT_URL_PLACEHOLDER,
    quirks: null,
  },
];

/**
 * The preset an identifier names, falling back to the one that fills nothing so
 * an unknown identifier can never quietly apply somebody else's settings.
 *
 * @param {String} presetId the identifier of a preset
 * @returns {Object} the preset, never null
 */
export function presetById(presetId) {
  return SERVER_PRESETS.find(preset => preset.id === presetId)
      || SERVER_PRESETS.find(preset => preset.id === PRESET_NONE);
}

/**
 * One excusal list as the registration stores it.
 *
 * Written out rather than folded into an expression because the empty case is
 * the one that matters and it is easy to get wrong: an empty array is truthy in
 * JavaScript, so a short-circuit here would quietly hand back the array instead
 * of the empty string, and the difference between an empty string and a null is
 * the difference between "we looked, there is nothing" and "nobody has asked".
 *
 * @param {Array} list the patterns a characterised preset carries, or null for
 *          a server nobody has characterised
 * @returns {String} the comma-separated list, or null when there is none
 */
function storedList(list) {
  if (list === null) {
    return null;
  }
  return list.join(',');
}

/**
 * The values a preset writes into a registration.
 *
 * Every preset-owned field is always present, including the empty ones. That is
 * what makes choosing a second preset REPLACE the first rather than merge with
 * it: a field the new preset says nothing about is written back to nothing, so
 * no pattern of the server chosen a moment ago survives into the row of the
 * server chosen now.
 *
 * Null and empty are different answers and both are used. A characterised
 * server writes an empty list where it has nothing to excuse — "we looked,
 * there is nothing" — which stops a deployment-wide list applying to it. The
 * uncharacterised option writes null — "nobody has asked" — which leaves the
 * deployment-wide list in force, exactly as before.
 *
 * @param {String} presetId the identifier of the chosen preset
 * @returns {Object} the fields to copy into the registration
 */
export function presetValues(presetId) {
  const preset = presetById(presetId);
  const lists = {};
  [IGNORED, DROPPED, OMITTED].forEach(field => {
    // A characterised server starts from an empty list it may add to; one
    // nobody has characterised keeps no list at all.
    lists[field] = preset.quirks && [] || null;
  });
  (preset.quirks || []).forEach(quirkId => {
    const quirk = QUIRKS[quirkId];
    if (quirk) {
      lists[quirk.list] = lists[quirk.list].concat(quirk.patterns);
    }
  });
  return {
    name: preset.name,
    icon: preset.icon,
    [IGNORED]: storedList(lists[IGNORED]),
    [DROPPED]: storedList(lists[DROPPED]),
    [OMITTED]: storedList(lists[OMITTED]),
  };
}

/**
 * Copies a preset's values into a registration being declared.
 *
 * A copy and nothing else: the registration keeps no memory of which preset it
 * came from, so nothing the administrator changes afterwards is ever corrected
 * back, and no later build reaches the row.
 *
 * @param {Object} server the registration being declared
 * @param {String} presetId the identifier of the chosen preset
 * @returns {Object} the same registration
 */
export function applyPreset(server, presetId) {
  return Object.assign(server, presetValues(presetId));
}

/**
 * Whether a preset carries a behaviour that changes what eXo WRITES into
 * somebody's calendar, rather than only what eXo notices about it.
 *
 * The drawer says so beside such a preset, in the same words the quirk
 * check-boxes use, because a shortcut that alters the document landing in a
 * user's calendar must not read like a shortcut that only relaxes a comparison.
 *
 * @param {String} presetId the identifier of the chosen preset
 * @returns {Boolean} true when one of its quirks changes what eXo writes
 */
export function presetChangesWhatIsWritten(presetId) {
  return (presetById(presetId).quirks || []).some(quirkId => QUIRKS[quirkId] && QUIRKS[quirkId].list === OMITTED);
}

/**
 * The URL shape to show in the address field for a preset — the one field a
 * preset cannot fill, shown as a form to copy rather than described in prose.
 *
 * @param {String} presetId the identifier of the chosen preset
 * @returns {String} the placeholder to show
 */
export function presetUrlPlaceholder(presetId) {
  return presetById(presetId).urlPlaceholder;
}
