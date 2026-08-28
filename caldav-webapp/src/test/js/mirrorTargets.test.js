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
  MIRROR_TARGETS,
  DEFAULT_MIRROR_TARGET,
  MIRROR_TARGET_DEDICATED_CALENDAR,
  MIRROR_TARGET_MAIN_CALENDAR,
  MIRROR_TARGET_USER_CHOICE,
  mirrorTargetOf,
} from '../../main/webapp/vue-app/caldav/js/mirrorTargets.js';

describe('the three destinations a server can write its meeting copies to', () => {

  it('spells the three values exactly as the registry stores them', () => {
    // Not cosmetic. The value travels to the registry as a string and is read
    // back by MirrorTargetKind.of, which answers DEDICATED_CALENDAR for
    // anything it does not recognise - so a typo here would not fail, it would
    // silently re-point somebody's destination.
    expect(MIRROR_TARGETS.map(target => target.value)).toEqual([
      'DEDICATED_CALENDAR',
      'MAIN_CALENDAR',
      'USER_CHOICE',
    ]);
  });

  it('carries a consequence key for every option, and no sentence of its own', () => {
    // Keys, never translated text: a helper that took $t would sooner or later
    // be handed it torn off its receiver, which is precisely how a drawer in
    // this add-on once shipped broken while every unit test stayed green.
    MIRROR_TARGETS.forEach(target => {
      expect(target.labelKey).toMatch(/^caldav\.admin\.servers\.mirrorTarget\./);
      expect(target.consequenceKey).toMatch(/^caldav\.admin\.servers\.mirrorTarget\./);
      expect(target.consequenceKey).not.toBe(target.labelKey);
    });
  });

  it('reads each stored value back as itself', () => {
    expect(mirrorTargetOf(MIRROR_TARGET_DEDICATED_CALENDAR)).toBe(MIRROR_TARGET_DEDICATED_CALENDAR);
    expect(mirrorTargetOf(MIRROR_TARGET_MAIN_CALENDAR)).toBe(MIRROR_TARGET_MAIN_CALENDAR);
    expect(mirrorTargetOf(MIRROR_TARGET_USER_CHOICE)).toBe(MIRROR_TARGET_USER_CHOICE);
  });

  it('never answers nothing, whatever it is handed', () => {
    // This is the whole point of the helper. The storage keeps the stored
    // destination when a save carries none, a tolerance that existed only
    // because no drawer carried the control; from the moment one does, an
    // absent value must never leave this module.
    expect(mirrorTargetOf(null)).toBe(DEFAULT_MIRROR_TARGET);
    expect(mirrorTargetOf(undefined)).toBe(DEFAULT_MIRROR_TARGET);
    expect(mirrorTargetOf('')).toBe(DEFAULT_MIRROR_TARGET);
    expect(mirrorTargetOf('  ')).toBe(DEFAULT_MIRROR_TARGET);
    expect(mirrorTargetOf(7)).toBe(DEFAULT_MIRROR_TARGET);
    // A value a later version of the registry might write, which this one does
    // not know: the same reading MirrorTargetKind.of gives it.
    expect(mirrorTargetOf('SOMETHING_ELSE')).toBe(DEFAULT_MIRROR_TARGET);
  });

  it('defaults to the calendar of eXo\'s own making, which is what every deployment already ran', () => {
    expect(DEFAULT_MIRROR_TARGET).toBe(MIRROR_TARGET_DEDICATED_CALENDAR);
  });
});
