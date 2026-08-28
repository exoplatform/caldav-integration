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
  mirrorTargetOf,
} from '../../main/webapp/vue-app/caldav/js/mirrorTargets.js';

describe('the destinations a server can write its meeting copies to', () => {

  it('offers exactly two, spelled exactly as the registry stores them', () => {
    // Not cosmetic. The value travels to the registry as a string and is read
    // back by MirrorTargetKind.of, which answers DEDICATED_CALENDAR for
    // anything it does not recognise - so a typo here would not fail, it would
    // silently re-point somebody's destination.
    //
    // Exactly two, and the list written out: there were three until a product
    // review dropped "a calendar each user picks". MirrorTargetKind still
    // carries USER_CHOICE, so nothing downstream stops it reappearing here by
    // accident - this assertion is what makes putting it back a deliberate act
    // rather than a merge nobody read.
    expect(MIRROR_TARGETS.map(target => target.value)).toEqual([
      'DEDICATED_CALENDAR',
      'MAIN_CALENDAR',
    ]);
  });

  it('does not offer the destination that hands the decision to each user', () => {
    // Dropped from the UI only. The enum value, the pending-choice path and the
    // endpoints behind it are merged code and are not this control's business;
    // what is this control's business is that no radio offers it.
    expect(MIRROR_TARGETS.some(target => target.value === 'USER_CHOICE')).toBe(false);
    expect(MIRROR_TARGETS.map(target => target.labelKey)).not.toContain(
      'caldav.admin.servers.mirrorTarget.userChoice.label');
    expect(MIRROR_TARGETS.map(target => target.consequenceKey)).not.toContain(
      'caldav.admin.servers.mirrorTarget.userChoice.consequence');
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

  it('reads each offered value back as itself', () => {
    expect(mirrorTargetOf(MIRROR_TARGET_DEDICATED_CALENDAR)).toBe(MIRROR_TARGET_DEDICATED_CALENDAR);
    expect(mirrorTargetOf(MIRROR_TARGET_MAIN_CALENDAR)).toBe(MIRROR_TARGET_MAIN_CALENDAR);
  });

  it('resolves a row stored as the destination we stopped offering, rather than leaving it blank', () => {
    // MirrorTargetKind still has USER_CHOICE and a row may already carry it -
    // written before the option was dropped, or by hand. It has no radio here
    // any more, so the control would show a group with nothing selected, which
    // an administrator reads as a lost setting. It resolves to the dedicated
    // calendar: a real option they can see and change.
    expect(mirrorTargetOf('USER_CHOICE')).toBe(MIRROR_TARGET_DEDICATED_CALENDAR);
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
