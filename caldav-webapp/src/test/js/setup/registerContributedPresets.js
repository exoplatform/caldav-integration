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
 * the two calls the drawer's modules make, and BlueMind's server preset
 * registered through it the way its module's init() registers it on a page
 * (EXO-90730). Without it, the suite's BlueMind assertions would measure a
 * page on which the BlueMind preset is not installed.
 */
import {init as registerBlueMindPreset} from '../../../main/webapp/vue-app/caldav-bluemind-preset/main.js';

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

registerBlueMindPreset();
