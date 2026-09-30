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

/*
 * The page's wiring, for the frozen jest suite: an extension registry with
 * the two calls the drawer's modules make, and a server product's preset
 * registered through it the way a contributing add-on's module registers one
 * on a page (EXO-90730) — the BlueMind add-on's, as it ships it. Without it,
 * the suite's BlueMind assertions would measure a page on which no product
 * preset is installed.
 */
const CONTRIBUTED_PRESET = Object.freeze({
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

const extensions = [];

global.extensionRegistry = {
  registerExtension(app, type, content) {
    const index = extensions.findIndex(ext => ext.app === app && ext.type === type && ext.content.id === content.id);
    if (index < 0) {
      extensions.push({app, type, content});
    } else {
      extensions.splice(index, 1, {app, type, content});
    }
  },
  loadExtensions(app, type) {
    return extensions.filter(ext => ext.app === app && ext.type === type)
      .map(ext => ext.content)
      .sort((a, b) => (a.rank || Number.MAX_SAFE_INTEGER) - (b.rank || Number.MAX_SAFE_INTEGER));
  },
};

global.extensionRegistry.registerExtension('caldav', 'server-preset', CONTRIBUTED_PRESET);
