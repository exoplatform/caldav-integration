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
 * Through which door eXo writes and removes the meeting copies of a server, as
 * the values the registry stores — `WriteChannel` on the Java side, spelled
 * exactly (EXO-90307).
 *
 * <p>Names, not indexes, for the reason `mirrorTargets.js` gives: the value
 * travels to the registry as a string and is read back by `WriteChannel.of`.</p>
 */
export const WRITE_CHANNEL_CALDAV = 'CALDAV';

/** BlueMind's own ICS import API, whose every change carries no notification. */
export const WRITE_CHANNEL_BLUEMIND_IMPORT = 'BLUEMIND_IMPORT';

/**
 * What a registration resolves to when it states nothing — the door every
 * deployment used before the setting existed, and the model's own default.
 */
export const DEFAULT_WRITE_CHANNEL = WRITE_CHANNEL_CALDAV;

/**
 * The two options in the order an administrator meets them: the standard
 * protocol first, the one server-specific door next. Keys, never sentences,
 * for the reason `mirrorTargets.js` gives.
 */
export const WRITE_CHANNELS = [
  {
    value: WRITE_CHANNEL_CALDAV,
    labelKey: 'caldav.admin.servers.writeChannel.caldav.label',
    consequenceKey: 'caldav.admin.servers.writeChannel.caldav.consequence',
  },
  {
    value: WRITE_CHANNEL_BLUEMIND_IMPORT,
    labelKey: 'caldav.admin.servers.writeChannel.bluemindImport.label',
    consequenceKey: 'caldav.admin.servers.writeChannel.bluemindImport.consequence',
  },
];

/**
 * Reads anything into one of the offered values, never answering null — the
 * same guard `mirrorTargetOf` is: the storage keeps the stored channel when a
 * save omits the field, so the drawer states it on every save, and an unknown
 * or absent value resolves the way the registry itself resolves it.
 *
 * @param {String} value whatever the row, the form or a preset carried
 * @returns {String} one of the offered values, never null
 */
export function writeChannelOf(value) {
  const named = typeof value === 'string' && value.trim().toUpperCase() || '';
  return WRITE_CHANNELS.some(channel => channel.value === named) && named || DEFAULT_WRITE_CHANNEL;
}
