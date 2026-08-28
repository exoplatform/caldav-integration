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
 * Where the meeting copies of a server are written, as the three values the
 * registry stores — `MirrorTargetKind` on the Java side, spelled exactly.
 *
 * <p>Names, not indexes: the value travels to the registry as a string and is
 * read back by `MirrorTargetKind.of`, so a renumbering here would silently
 * re-point somebody's destination.</p>
 */
export const MIRROR_TARGET_DEDICATED_CALENDAR = 'DEDICATED_CALENDAR';

/** The calendar the account itself calls its default. */
export const MIRROR_TARGET_MAIN_CALENDAR = 'MAIN_CALENDAR';

/** A calendar each user names, with nothing written until they have. */
export const MIRROR_TARGET_USER_CHOICE = 'USER_CHOICE';

/**
 * What a registration resolves to when it states nothing — the behaviour every
 * deployment had before the setting existed, and the same default the model's
 * own field initialiser carries.
 */
export const DEFAULT_MIRROR_TARGET = MIRROR_TARGET_DEDICATED_CALENDAR;

/**
 * The three options in the order an administrator meets them: what eXo does on
 * its own first, the account's own default next, and the one that hands the
 * decision away last.
 *
 * <p>Each carries KEYS rather than sentences. A helper that took a translate
 * function would be handed `this.$t` torn off its receiver sooner or later —
 * which is exactly how a drawer shipped broken here once, passing every unit
 * test on the way — so nothing in this module translates anything.</p>
 */
export const MIRROR_TARGETS = [
  {
    value: MIRROR_TARGET_DEDICATED_CALENDAR,
    labelKey: 'caldav.admin.servers.mirrorTarget.dedicated.label',
    consequenceKey: 'caldav.admin.servers.mirrorTarget.dedicated.consequence',
  },
  {
    value: MIRROR_TARGET_MAIN_CALENDAR,
    labelKey: 'caldav.admin.servers.mirrorTarget.main.label',
    consequenceKey: 'caldav.admin.servers.mirrorTarget.main.consequence',
  },
  {
    value: MIRROR_TARGET_USER_CHOICE,
    labelKey: 'caldav.admin.servers.mirrorTarget.userChoice.label',
    consequenceKey: 'caldav.admin.servers.mirrorTarget.userChoice.consequence',
  },
];

/**
 * Reads anything into one of the three values, never answering null.
 *
 * <p><b>This is the guard the storage used to be.</b> `CaldavServerStorage.
 * updateServer` leaves the stored destination alone when the body carries no
 * `mirrorTarget`, and that tolerance existed for exactly one reason: the drawer
 * did not carry the control. From the moment it does, a save that omitted the
 * field would no longer be protected by anything downstream — a row edited for
 * its name would have kept its destination by luck rather than by rule. So the
 * drawer states the field on every save, and an unknown or absent value
 * resolves the way the registry itself resolves it.</p>
 *
 * @param {String} value whatever the row, the form or a preset carried
 * @returns {String} one of the three stored values, never null
 */
export function mirrorTargetOf(value) {
  const named = typeof value === 'string' && value.trim().toUpperCase() || '';
  return MIRROR_TARGETS.some(target => target.value === named) && named || DEFAULT_MIRROR_TARGET;
}
