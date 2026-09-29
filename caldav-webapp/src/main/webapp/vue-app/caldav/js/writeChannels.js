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
import {registeredPresets} from './serverPresetRegistry.js';

/**
 * Through which door eXo writes and removes the meeting copies of a server, as
 * the values the registry stores — `WriteChannel` on the Java side, spelled
 * exactly (EXO-90307).
 *
 * <p>Names, not indexes, for the reason `mirrorTargets.js` gives: the value
 * travels to the registry as a string and is read back by `WriteChannel.of`.</p>
 */
export const WRITE_CHANNEL_CALDAV = 'CALDAV';

/**
 * BlueMind's own ICS import API, whose every change carries no notification.
 * A value of the stored column, like the Java enum constant it mirrors; the
 * door itself, and its option in the radio, are contributed by BlueMind's
 * preset (EXO-90730).
 */
export const WRITE_CHANNEL_BLUEMIND_IMPORT = 'BLUEMIND_IMPORT';

/**
 * Every value the registry's `WriteChannel` column can hold, spelled exactly.
 * A stored value is read into one of these and never into CalDAV merely
 * because this page offers no door for it: that would switch a server back to
 * CalDAV behind the administrator's back on the next save.
 */
const KNOWN_WRITE_CHANNELS = [WRITE_CHANNEL_CALDAV, WRITE_CHANNEL_BLUEMIND_IMPORT];

/**
 * What a registration resolves to when it states nothing — the door every
 * deployment used before the setting existed, and the model's own default.
 */
export const DEFAULT_WRITE_CHANNEL = WRITE_CHANNEL_CALDAV;

/**
 * The standard protocol, the one option every server has. Keys, never
 * sentences, for the reason `mirrorTargets.js` gives.
 */
const CALDAV_OPTION = {
  value: WRITE_CHANNEL_CALDAV,
  labelKey: 'caldav.admin.servers.writeChannel.caldav.label',
  consequenceKey: 'caldav.admin.servers.writeChannel.caldav.consequence',
};

/**
 * The option shown for a stored channel no installed add-on offers a door
 * for: kept on the row, and said to be unavailable, rather than silently
 * replaced.
 */
const UNAVAILABLE_KEYS = {
  labelKey: 'caldav.admin.servers.writeChannel.unavailable.label',
  consequenceKey: 'caldav.admin.servers.writeChannel.unavailable.consequence',
};

/**
 * The options in the order an administrator meets them: the standard
 * protocol first, then each door a contributed preset declares
 * (`writeChannelOption`), once each.
 *
 * @returns {Array} the options, CalDAV first
 */
export function writeChannelOptions() {
  const options = [CALDAV_OPTION];
  registeredPresets().forEach(preset => {
    const option = preset.writeChannelOption;
    if (option && KNOWN_WRITE_CHANNELS.includes(option.value) && !options.some(offered => offered.value === option.value)) {
      options.push(option);
    }
  });
  return options;
}

/**
 * The options to render for a registration: the offered ones, plus — when
 * the row is on a channel none of them is — that channel, said to be
 * unavailable, so the radio shows what is stored.
 *
 * @param {String} value the channel the form carries
 * @returns {Array} the options to render
 */
export function writeChannelOptionsFor(value) {
  const options = writeChannelOptions();
  const current = writeChannelOf(value);
  return options.some(option => option.value === current) ? options : options.concat([Object.assign({value: current}, UNAVAILABLE_KEYS)]);
}

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
  return KNOWN_WRITE_CHANNELS.includes(named) && named || DEFAULT_WRITE_CHANNEL;
}

/**
 * Whether the write-channel control is offered for a registration (EXO-90307,
 * PO decision of 2026-09-16: a choice of the server products that have a
 * door of their own).
 *
 * <p>Offered when the name carries a contributed preset's `nameMarker` — the
 * only product fact a row carries, a preset being a copy and never a link,
 * and the same marker the server-side flavour recognises (EXO-90730) — and
 * also when the row is already on a channel other than CalDAV whatever its
 * name says: hiding the control there would let a save silently put a server
 * back through CalDAV, and the registry refuses such a channel on a name no
 * flavour recognises anyway, so the administrator meets a refusal they can
 * read rather than a reset they cannot see.</p>
 *
 * @param {Object} server the registration as the form carries it
 * @returns {Boolean} true when the radio is shown and the form's own value is
 *          what the save states
 */
export function offersWriteChannel(server) {
  const name = server && typeof server.name === 'string' && server.name.toLowerCase() || '';
  const recognised = registeredPresets().some(preset => typeof preset.nameMarker === 'string' && preset.nameMarker
      && name.includes(preset.nameMarker.toLowerCase()));
  return recognised || writeChannelOf(server && server.writeChannel) !== WRITE_CHANNEL_CALDAV;
}

/**
 * The channel a save states for a registration: the form's own value where
 * the control is offered, CalDAV — explicitly, never an omitted key that would
 * keep a stale stored value — everywhere else.
 *
 * @param {Object} server the registration as the form carries it
 * @returns {String} one of the stored values, never null
 */
export function writeChannelToSave(server) {
  return offersWriteChannel(server) ? writeChannelOf(server.writeChannel) : WRITE_CHANNEL_CALDAV;
}
