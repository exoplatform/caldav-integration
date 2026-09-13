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
import caldavConnector from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';

/*
 * EXO-90237. `GET /caldav/rest/calendars` says, beside `readOnly`, whether a
 * calendar is `shared` and by whom (`ownerIdentityId`, `ownerUsername`,
 * `ownerDisplayName`); agenda groups the shares under "Shared with me" and
 * shows the owner. The connector is the one hop between the two, and its
 * contract is to hand the entries through unchanged: every field the
 * endpoint answers reaches agenda as answered, null and absent alike, and
 * the fields every caller already knew are kept. A mapping that rebuilt each
 * entry from a fixed list of fields would pass every older test and drop the
 * owner on the floor — which is what these pins are for.
 */

/**
 * Answers the next fetch with a 200 carrying the given body.
 *
 * @param {Object|Array} body what the endpoint returns
 * @returns {void}
 */
function respondWith(body) {
  window.fetch = jest.fn(() => Promise.resolve({
    ok: true,
    status: 200,
    json: () => Promise.resolve(body),
  }));
}

/** CAL2 as eric receives it: a colleague's eXo calendar, owned by root. */
const CAL2 = {
  id: '/dav/calendars/eric/exo-cal-959b5529-ea4c-4ae4-a793-a2c201c3af9f/',
  name: 'CAL2',
  color: '#4a90d9',
  readOnly: true,
  shared: true,
  ownerIdentityId: 1,
  ownerUsername: 'root',
  ownerDisplayName: 'Root Root',
};

/** Alice's default as bob receives it: a share the server reported. */
const ALICES = {
  id: '/dav/cal/alice%40stalwart.local/default/',
  name: 'Alice',
  color: '#b8e986',
  readOnly: true,
  shared: true,
  ownerIdentityId: null,
  ownerUsername: null,
  ownerDisplayName: 'Alice',
};

/** The user's own calendar, read-only by the server's word and nobody's share. */
const OWN = {
  id: '/dav/calendars/eric/work/',
  name: 'Work',
  color: '#112233',
  readOnly: true,
  shared: false,
  ownerIdentityId: null,
  ownerUsername: null,
  ownerDisplayName: null,
};

describe('listCalendars hands the shared-calendar owner through to agenda', () => {

  it('passes shared and the three owner fields through unchanged', () => {
    respondWith({calendars: [CAL2, ALICES, OWN], failed: false});
    return caldavConnector.listCalendars().then(calendars => {
      expect(calendars).toHaveLength(3);
      expect(calendars[0]).toEqual(CAL2);
      expect(calendars[1]).toEqual(ALICES);
      expect(calendars[2]).toEqual(OWN);
    });
  });

  it('keeps shared distinct from readOnly', () => {
    respondWith({calendars: [OWN, CAL2], failed: false});
    return caldavConnector.listCalendars().then(calendars => {
      expect(calendars[0].readOnly).toBe(true);
      expect(calendars[0].shared).toBe(false);
      expect(calendars[1].readOnly).toBe(true);
      expect(calendars[1].shared).toBe(true);
    });
  });

  it('keeps null owner fields null rather than dropping or inventing them', () => {
    respondWith({calendars: [ALICES], failed: false});
    return caldavConnector.listCalendars().then(calendars => {
      expect(calendars[0]).toHaveProperty('ownerIdentityId', null);
      expect(calendars[0]).toHaveProperty('ownerUsername', null);
      expect(calendars[0].ownerDisplayName).toBe('Alice');
    });
  });

  it('tolerates a stale services jar that answers none of the four fields', () => {
    // The webapp bundle and the services jar ship together, but a redeploy
    // is known to serve one before the other: an entry without the fields
    // must reach agenda as it came, absent staying absent, never a crash.
    respondWith({calendars: [{id: '/dav/calendars/eric/work/', name: 'Work', color: '#112233', readOnly: false}], failed: false});
    return caldavConnector.listCalendars().then(calendars => {
      expect(calendars).toHaveLength(1);
      expect(calendars[0]).toEqual({id: '/dav/calendars/eric/work/', name: 'Work', color: '#112233', readOnly: false});
      expect(calendars[0]).not.toHaveProperty('shared');
    });
  });
});
