/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */

/**
 * BlueMind's server preset, contributed through the drawer's `server-preset`
 * extension point (EXO-90730) the way any add-on would: this module's gatein
 * name contains `CaldavServerPresetExtension`, and the host runs it with
 * `includeExtensions` before the servers section is shown.
 *
 * <p>Data only, and self-contained — no import from the host's modules — so
 * that moving it into the BlueMind add-on is a file move.</p>
 */

/**
 * BlueMind, characterised on a live account across EXO-89716 to EXO-89775.
 *
 * Its address is the DAV root. That is where BlueMind answers — its `/dav/`
 * returns 401 Basic realm="bm.basic.auth.v2" while the bare host only
 * redirects — and it needs no `{username}`: the server's own
 * current-user-principal discovery finds the account's calendars, whose real
 * hrefs are GUID-based and could not have been typed anyway.
 *
 * The four behaviours are what kept copies of a live account in a permanent
 * repair loop until each was recognised — `CONFERENCE` alone was proved
 * dropped 399 times in one day, five copies rewritten every five minutes. The
 * ids are the host's quirk catalogue's, which `CaldavServerService`'s
 * BlueMind seed also spells.
 *
 * The main calendar, because BlueMind's dedicated one is known deficient: it
 * is excluded from the account's free/busy and carries no answer buttons.
 * Answer links on: BlueMind shows its own answer buttons on the default
 * calendar only, so eXo's links are what covers anything else.
 *
 * The import door (EXO-90307), because a copy written over CalDAV reaches
 * BlueMind's calendar service with notifications hard-coded on, and BlueMind
 * then schedules the meeting itself: same-server invitees get it twice, an
 * answer given on BlueMind's own object never reaches eXo, and external
 * attendees get BlueMind's mail on top of eXo's. BlueMind's ICS import applies
 * every change with notifications off. The radio beside the preset is the
 * rollback.
 */
export const BLUEMIND_PRESET = Object.freeze({
  id: 'bluemind',
  rank: 10,
  name: 'BlueMind',
  icon: null,
  urlPlaceholder: 'https://bluemind.example.org/dav/',
  quirks: ['dropsConference', 'addsCompatibilityMarkers', 'addsFormattedDescription', 'stampsDefaultPriority'],
  answerLinksInCopy: true,
  mirrorTarget: 'MAIN_CALENDAR',
  writeChannel: 'BLUEMIND_IMPORT',
  nameMarker: 'bluemind',
  writeChannelOption: Object.freeze({
    value: 'BLUEMIND_IMPORT',
    labelKey: 'caldav.admin.servers.writeChannel.bluemindImport.label',
    consequenceKey: 'caldav.admin.servers.writeChannel.bluemindImport.consequence',
  }),
});

/**
 * Registers the preset. Called by the host's `includeExtensions`.
 *
 * @returns {void}
 */
export function init() {
  if (typeof extensionRegistry !== 'undefined' && extensionRegistry) {
    extensionRegistry.registerExtension('caldav', 'server-preset', BLUEMIND_PRESET);
  }
}
