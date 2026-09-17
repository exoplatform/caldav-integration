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
import caldavConnector, {createCaldavConnector} from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';

/**
 * EXO-90357: agenda's "Shared with me" draws a share delivered to a server
 * through its eXo row and asks this connector which of its rows is the
 * server's copy, to leave it out. The channel recorded the collection as
 * the owner's account spells it; the colleague's listing spells it the same
 * on Stalwart and under the colleague's own home on BlueMind, where only
 * the container uid is shared. A wrong yes hides a colleague's calendar; a
 * wrong no draws one twice.
 */
describe('which listed calendar is the server copy of a delivered share', () => {

  const STALWART = createCaldavConnector({id: 1, providerName: 'stalwart', name: 'Stalwart', serverUrl: 'https://mail.example.org/dav/'}, 0, false);

  const BLUEMIND = createCaldavConnector({id: 2, providerName: 'bluemind', name: 'BlueMind', serverUrl: 'https://bm.example.org'}, 1, false);

  it('matches the collection at the owner\'s path, whatever the encoding, as Stalwart lists a share', () => {
    const delivery = {deliveredTo: 'caldav:1', deliveryRef: '/dav/cal/alice@stalwart.local/default/'};
    expect(STALWART.isDeliveryOf({id: '/dav/cal/alice%40stalwart.local/default/'}, delivery)).toBe(true);
    expect(STALWART.isDeliveryOf({id: '/dav/cal/alice@stalwart.local/default'}, delivery)).toBe(true);
  });

  it('never takes another account\'s calendar of the same name for the delivery', () => {
    const delivery = {deliveredTo: 'caldav:1', deliveryRef: '/dav/cal/alice@stalwart.local/default/'};
    expect(STALWART.isDeliveryOf({id: '/dav/cal/carol%40stalwart.local/default/'}, delivery)).toBe(false);
  });

  it('matches the container under the colleague\'s own home, as BlueMind lists a subscribed share', () => {
    const delivery = {deliveredTo: 'caldav:2', deliveryRef: '/dav/calendars/__uids__/ALICE-UID/exo-cal-959b5529-1/'};
    expect(BLUEMIND.isDeliveryOf({id: '/dav/calendars/__uids__/BOB-UID/exo-cal-959b5529-1/'}, delivery)).toBe(true);
    expect(BLUEMIND.isDeliveryOf({id: '/dav/calendars/__uids__/BOB-UID/calendar%3ADefault%3AALICE/'},
      {deliveredTo: 'caldav:2', deliveryRef: '/dav/calendars/__uids__/ALICE-UID/calendar:Default:ALICE/'})).toBe(true);
    expect(BLUEMIND.isDeliveryOf({id: '/dav/calendars/__uids__/BOB-UID/exo-cal-other/'}, delivery)).toBe(false);
  });

  it('claims nothing for a delivery another server made, an undelivered share or no calendar', () => {
    const onStalwart = {deliveredTo: 'caldav:1', deliveryRef: '/dav/calendars/__uids__/ALICE-UID/exo-cal-1/'};
    expect(BLUEMIND.isDeliveryOf({id: '/dav/calendars/__uids__/BOB-UID/exo-cal-1/'}, onStalwart)).toBe(false);
    expect(STALWART.isDeliveryOf({id: '/dav/cal/alice@stalwart.local/default/'}, {deliveredTo: null, deliveryRef: null})).toBe(false);
    expect(STALWART.isDeliveryOf(null, {deliveredTo: 'caldav:1', deliveryRef: '/dav/cal/alice@stalwart.local/default/'})).toBe(false);
    expect(STALWART.isDeliveryOf({id: ''}, {deliveredTo: 'caldav:1', deliveryRef: '/dav/cal/alice@stalwart.local/default/'})).toBe(false);
  });

  it('matches on the path alone from the base descriptor, which serves no server of its own', () => {
    expect(caldavConnector.isDeliveryOf({id: '/dav/cal/alice/default/'}, {deliveredTo: 'caldav:9', deliveryRef: '/dav/cal/alice/default'})).toBe(true);
  });
});
