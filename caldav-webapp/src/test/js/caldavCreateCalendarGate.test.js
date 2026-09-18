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
import {createCaldavConnector, createLegacyCaldavConnector} from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';
import {MIRROR_TARGET_DEDICATED_CALENDAR, MIRROR_TARGET_MAIN_CALENDAR} from '../../main/webapp/vue-app/caldav/js/mirrorTargets.js';

/**
 * Whether there is a calendar for eXo to create on this server (EXO-90396).
 *
 * <p>`canCreateCalendar` is the single gate every surface offering the
 * creation step reads: the step agenda offers right after connecting, the
 * drawer's own guard, the destination row in the user settings. It used to be
 * a hard-coded true on every CalDAV descriptor, while the registration next to
 * it already said where the copies go — so on a server writing them into the
 * account's own default calendar, a user who connected was offered to create
 * a calendar the push then refused to create, and pressing Apply reported a
 * success for a collection that was never made.</p>
 *
 * <p><b>What is pinned.</b> The flag against the registration's
 * `mirrorTarget`, in each of the shapes a registration reaches the browser in
 * — the two values the admin drawer offers, a value the drawer no longer
 * offers but the registry still stores, and a row that states nothing at all,
 * which must keep the behaviour every deployment had before the setting
 * existed. The resolution is `mirrorTargetOf`'s, the same function the admin
 * drawer saves through, so the flag cannot drift from what the registry reads
 * back.</p>
 */
describe('Whether a CalDAV descriptor can create a calendar', () => {

  const server = {
    id: 6,
    providerName: 'agenda.caldavCalendar.6',
    name: 'Bluemind',
    description: 'Team server',
    serverUrl: 'https://caldav.example.invalid/dav/',
    active: true,
  };

  /**
   * The descriptor a declared server carrying that destination produces.
   *
   * @param {*} mirrorTarget whatever the registration carries, if anything
   * @returns {Object} the connector descriptor
   */
  function descriptorFor(mirrorTarget) {
    return createCaldavConnector(Object.assign({}, server, {mirrorTarget}), 0, null, {});
  }

  /**
   * The destination the PO met the defect on: the copies go to the account's
   * own default calendar, so there is nothing for eXo to create, and
   * `CaldavPushService.ensureMirror` refuses to create one.
   */
  it('cannot, on a server writing the copies into the account main calendar', () => {
    expect(descriptorFor(MIRROR_TARGET_MAIN_CALENDAR).canCreateCalendar).toBe(false);
  });

  it('can, on a server writing them into a calendar of its own', () => {
    expect(descriptorFor(MIRROR_TARGET_DEDICATED_CALENDAR).canCreateCalendar).toBe(true);
  });

  /**
   * A registration that names no destination: a row written before the
   * setting existed, or a REST answer that did not carry the field. It
   * resolves the way the registry itself resolves it — the dedicated calendar
   * — so the step stays offered, which is the behaviour every deployment
   * already had and the safer of the two ways to be wrong: a drawer the user
   * closes costs a click, copies with nowhere to go cost the feature.
   */
  it('can, when the registration names no destination at all', () => {
    expect(descriptorFor(undefined).canCreateCalendar).toBe(true);
    expect(descriptorFor(null).canCreateCalendar).toBe(true);
    expect(descriptorFor('').canCreateCalendar).toBe(true);
    expect(createCaldavConnector(server, 0, null, {}).canCreateCalendar).toBe(true);
  });

  /**
   * `USER_CHOICE` was withdrawn from the drawer but rows still carry it, and
   * the registry reads it back as the dedicated calendar
   * (`MirrorTargetKind.of`). The flag must agree with the push rather than
   * with the spelling.
   */
  it('can, on a value the drawer no longer offers, exactly as the registry reads it', () => {
    expect(descriptorFor('USER_CHOICE').canCreateCalendar).toBe(true);
    expect(descriptorFor('SOMETHING_ELSE').canCreateCalendar).toBe(true);
  });

  /**
   * The value travels as a string, and a hand-written row or an older client
   * may not have spelled it the way the enum does. It is read the way
   * `mirrorTargetOf` reads it everywhere else, not by an identity test of its
   * own.
   */
  it('reads the destination the way the rest of the add-on reads it', () => {
    expect(descriptorFor('  main_calendar  ').canCreateCalendar).toBe(false);
  });

  /**
   * The fallback registered when the registry answers nothing — an empty
   * registry, or a REST call that failed. It fronts no declared server, so it
   * has no destination to read: its account predates registrations and its
   * copies have always gone to a dedicated calendar.
   */
  it('can, on the legacy descriptor, which fronts no registration', () => {
    expect(createLegacyCaldavConnector(null).canCreateCalendar).toBe(true);
    expect(createLegacyCaldavConnector({managedForMe: true, serverName: 'Bluemind'}).canCreateCalendar).toBe(true);
  });

  /**
   * The flag is per descriptor, not per family: two servers declared side by
   * side are offered the step or not on their own registration. Stamped onto
   * the singleton instead, the last server registered would decide for every
   * other one.
   */
  it('is decided per server, not for the CalDAV family', () => {
    const main = createCaldavConnector(Object.assign({}, server, {mirrorTarget: MIRROR_TARGET_MAIN_CALENDAR}), 0, null, {});
    const dedicated = createCaldavConnector(Object.assign({}, server, {
      id: 7,
      providerName: 'agenda.caldavCalendar.7',
      mirrorTarget: MIRROR_TARGET_DEDICATED_CALENDAR,
    }), 1, null, {});

    expect(main.canCreateCalendar).toBe(false);
    expect(dedicated.canCreateCalendar).toBe(true);
  });

});
