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
   *
   * <p><b>Unreachable through this endpoint, and pinned anyway.</b>
   * `CaldavServerStorage.fromEntity` already resolves the stored string
   * through `MirrorTargetKind.of` before the DTO is built, so what
   * `createCaldavConnector` receives is a canonical enum name and never a
   * withdrawn one. This is a guard on `mirrorTargetOf`'s contract — the same
   * function the admin drawer reads its own unsaved form state through, where
   * these values very much do arrive — held here so that a future caller
   * handing the descriptor an unnormalised registration cannot quietly flip
   * the flag.</p>
   */
  it('can, on a value the drawer no longer offers, exactly as the registry reads it', () => {
    expect(descriptorFor('USER_CHOICE').canCreateCalendar).toBe(true);
    expect(descriptorFor('SOMETHING_ELSE').canCreateCalendar).toBe(true);
  });

  /**
   * The destination is read the way `mirrorTargetOf` reads it everywhere else,
   * not by an identity test of its own — which is what this pin is for: an
   * identity test passes every other pin in this file and fails only here.
   *
   * <p>Like the one above, the shape is unreachable from
   * `GET /caldav/rest/servers` today (`CaldavServerStorage.fromEntity`
   * normalises through `MirrorTargetKind.of` first, and
   * `CaldavServerWireShapeTest` pins that the canonical name is what travels).
   * The tolerance is real where the value has not been through the storage —
   * the admin drawer's form state — and the point of pinning it on the
   * descriptor is that the two must not drift apart.</p>
   */
  it('reads the destination the way the rest of the add-on reads it', () => {
    expect(descriptorFor('  main_calendar  ').canCreateCalendar).toBe(false);
  });

  /**
   * The fallback registered when the registry could not be read — a REST call
   * that failed, or a registry with no active row.
   *
   * <p><b>It does front a registration</b>, contrary to what this pin first
   * said: the descriptor carries the seed row's provider name verbatim
   * (`agenda.caldavCalendar`, `CaldavServerService.CALDAV_PROVIDER_NAME`), an
   * account connected through it stores no `serverId`, and the push resolves
   * that account back to the same row (`resolveServer(null)` →
   * `getServerByProviderName`), so the seed row's `mirrorTarget` is what
   * governs its copies. What the branch lacks is not a registration but a
   * reading of one — which is why the flag cannot be derived here, and why it
   * fails OPEN.</p>
   *
   * <p>That leaves one corner the delivery does not close: a seed row an
   * administrator set to `MAIN_CALENDAR`, connected during an outage of
   * `GET /caldav/rest/servers`. The shipped seed is `DEDICATED_CALENDAR` and
   * the `MAIN_CALENDAR` seed (BlueMind) is stored under
   * `agenda.caldavCalendar.<id>`, so it is never the row this path resolves.
   * Flipping this default to false would be the unsafe direction — it strands
   * the copies of every deployment whose seed row really is dedicated.</p>
   */
  it('can, on the legacy descriptor, whose registration could not be read', () => {
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
