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
import caldavConnector, {createCaldavConnector} from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';

/*
 * EXO-90331. The contract agenda's share mark is drawn against:
 * `calendarShares()` answers {calendarId: {sharees, actionId}} from what other
 * eXo users' homes were observed to list, names the action that opens the
 * drawer so agenda hardcodes none of this connector's ids, and never rejects.
 * Every per-server descriptor inherits it.
 */

/**
 * Answers fetches by URL.
 *
 * @param {Object} routes url fragment → response, or a function returning one
 * @returns {jest.Mock} the fetch mock
 */
function route(routes) {
  global.fetch = jest.fn(url => {
    const key = Object.keys(routes).find(fragment => url.includes(fragment));
    if (!key) {
      return Promise.reject(new Error(`unexpected fetch ${url}`));
    }
    const answer = routes[key];
    return typeof answer === 'function' ? answer() : Promise.resolve(answer);
  });
  return global.fetch;
}

describe('the share mark of the CalDAV connector', () => {

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('answers a count and the action that opens the drawer, per calendar', async () => {
    const fetch = route({
      '/caldav/rest/calendars/observed-shares': {ok: true, json: () => Promise.resolve({sharedWith: {12: 3, 14: 1}})},
    });

    const shares = await caldavConnector.calendarShares();

    expect(shares).toEqual({
      12: {sharees: 3, actionId: 'caldavShareCalendar'},
      14: {sharees: 1, actionId: 'caldavShareCalendar'},
    });
    // The id it names is the one calendarActions() offers, so a click on the
    // mark reaches runCalendarAction and the drawer through the same path the
    // menu entry does.
    expect(fetch.mock.calls[0][1].credentials).toBe('include');
  });

  it('marks nothing when no calendar of the user is seen by anyone', async () => {
    route({'/caldav/rest/calendars/observed-shares': {ok: true, json: () => Promise.resolve({sharedWith: {}})}});

    await expect(caldavConnector.calendarShares()).resolves.toEqual({});
  });

  it('drops a calendar counted at zero rather than marking it', async () => {
    // A zero is not a share. The platform answers only calendars with at least
    // one sharee, and the guard here is what keeps a future zero from drawing a
    // mark that says "shared with 0 of your colleagues".
    route({'/caldav/rest/calendars/observed-shares': {ok: true, json: () => Promise.resolve({sharedWith: {12: 0, 14: 2}})}});

    await expect(caldavConnector.calendarShares()).resolves.toEqual({14: {sharees: 2, actionId: 'caldavShareCalendar'}});
  });

  it('never rejects: a platform that cannot answer marks nothing', async () => {
    route({'/caldav/rest/calendars/observed-shares': {ok: false, status: 500}});

    await expect(caldavConnector.calendarShares()).resolves.toEqual({});

    route({'/caldav/rest/calendars/observed-shares': () => Promise.reject(new Error('offline'))});

    await expect(caldavConnector.calendarShares()).resolves.toEqual({});
  });

  it('is inherited by every per-server descriptor', async () => {
    route({'/caldav/rest/calendars/observed-shares': {ok: true, json: () => Promise.resolve({sharedWith: {12: 2}})}});
    const descriptor = createCaldavConnector({id: 5, providerName: 'agenda.caldavCalendar.5', serverUrl: 'https://dav.x'}, 1, null);

    await expect(descriptor.calendarShares()).resolves.toEqual({12: {sharees: 2, actionId: 'caldavShareCalendar'}});
  });

  it('asks once for every caller asking at the same moment', async () => {
    // Agenda asks each registered connector on every panel refresh, and the
    // add-on registers one per declared server: without the shared request one
    // refresh is one identical call per server.
    const fetch = route({
      '/caldav/rest/calendars/observed-shares': {ok: true, json: () => Promise.resolve({sharedWith: {12: 2}})},
    });

    const [first, second] = await Promise.all([caldavConnector.calendarShares(), caldavConnector.calendarShares()]);

    expect(first).toEqual(second);
    expect(fetch.mock.calls.filter(([url]) => url.includes('observed-shares'))).toHaveLength(1);
  });
});
