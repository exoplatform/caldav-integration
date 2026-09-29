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
 * The `server-preset` extension point of the CalDAV server drawer (EXO-90730):
 * where a server product's add-on registers what the drawer fills in for it.
 *
 * <p>A contribution is a gatein module whose NAME contains
 * {@link SERVER_PRESET_MODULE_SUFFIX} — `includeExtensions` discovers modules
 * by name, not by load group, so a module named otherwise is never run — and
 * whose `init()` calls
 * `extensionRegistry.registerExtension('caldav', 'server-preset', preset)`.
 * A preset is data only:</p>
 * <ul>
 * <li>`id`, `name`, `icon`, `urlPlaceholder`, `rank` (lower first; Stalwart,
 * built in, is 20);</li>
 * <li>`quirks`: ids of the host's quirk catalogue, `answerLinksInCopy`,
 * `mirrorTarget`, `writeChannel` — what choosing it fills in;</li>
 * <li>`nameMarker`: what a registration's name carries when it stands for the
 * product, the same marker the server-side flavour recognises;</li>
 * <li>`writeChannelOption`: `{value, labelKey, consequenceKey}`, the door the
 * product adds to the write-channel radio, if any.</li>
 * </ul>
 * <p>Its label and summary are the keys `caldav.admin.servers.preset.<id>.label`
 * and `.summary`, which the contribution ships in a bundle named like the
 * host's (`locale.portlet.Caldav`).</p>
 */

/** The application the presets are registered under. */
export const SERVER_PRESET_APP = 'caldav';

/** The extension type of a preset. */
export const SERVER_PRESET_EXTENSION = 'server-preset';

/**
 * What the gatein module name of a contribution contains: the suffix the host
 * passes to `includeExtensions`.
 */
export const SERVER_PRESET_MODULE_SUFFIX = 'CaldavServerPresetExtension';

/**
 * The presets registered by contributions, in rank order, each checked to be
 * usable: an object with an id. A page, or a test, with no extension registry
 * has none.
 *
 * @returns {Array} the registered presets, possibly empty
 */
export function registeredPresets() {
  const registry = typeof extensionRegistry !== 'undefined' && extensionRegistry || null;
  if (!registry || typeof registry.loadExtensions !== 'function') {
    return [];
  }
  return (registry.loadExtensions(SERVER_PRESET_APP, SERVER_PRESET_EXTENSION) || [])
    .filter(preset => preset && typeof preset.id === 'string' && preset.id);
}

/**
 * Runs every contributed preset module on this page, so that its presets are
 * registered before the drawer reads them. Never fails: a page on which the
 * modules cannot be run shows the built-in presets.
 *
 * @returns {Promise} resolved once every contribution has run
 */
export function includeServerPresets() {
  const utils = typeof Vue !== 'undefined' && Vue.prototype && Vue.prototype.$utils || null;
  if (!utils || typeof utils.includeExtensions !== 'function') {
    return Promise.resolve();
  }
  return Promise.resolve()
    .then(() => utils.includeExtensions(SERVER_PRESET_MODULE_SUFFIX))
    .catch(() => null);
}
