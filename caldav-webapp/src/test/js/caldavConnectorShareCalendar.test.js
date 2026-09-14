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
 * EXO-90253. The contract agenda's calendar menu builds "Share…" against:
 * `calendarActions()` answers {calendarId: [{id, label}]} for the calendars
 * the platform says are shareable, with the label from the CalDAV bundle, and
 * never rejects; `runCalendarAction(id, calendar)` opens the share drawer by
 * a document event carrying the calendar, and claims no other action. Every
 * per-server descriptor inherits both.
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

describe('sharing a calendar through the CalDAV connector', () => {

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('offers Share on each shareable calendar, labelled from the bundle', async () => {
    const fetch = route({
      '/caldav/rest/calendars/shareable': {ok: true, json: () => Promise.resolve({calendarIds: [12, 14]})},
      'locale.portlet.Caldav': {ok: true, json: () => Promise.resolve({'caldav.share.menu': 'Partager…'})},
    });

    const actions = await caldavConnector.calendarActions();

    expect(actions).toEqual({
      12: [{id: 'caldavShareCalendar', label: 'Partager…', icon: 'fa-share-alt'}],
      14: [{id: 'caldavShareCalendar', label: 'Partager…', icon: 'fa-share-alt'}],
    });
    expect(fetch.mock.calls.find(([url]) => url.includes('/shareable'))[1].credentials).toBe('include');
  });

  it('offers nothing, and does not reject, when the platform cannot answer', async () => {
    route({
      '/caldav/rest/calendars/shareable': () => Promise.reject(new Error('offline')),
      'locale.portlet.Caldav': {ok: true, json: () => Promise.resolve({})},
    });
    await expect(caldavConnector.calendarActions()).resolves.toEqual({});

    route({
      '/caldav/rest/calendars/shareable': {ok: false, status: 500},
      'locale.portlet.Caldav': {ok: true, json: () => Promise.resolve({})},
    });
    await expect(caldavConnector.calendarActions()).resolves.toEqual({});
  });

  it('opens the share drawer with the calendar, by a document event', async () => {
    const seen = [];
    const listener = event => seen.push(event.detail);
    document.addEventListener('open-caldav-share-calendar-drawer', listener);
    try {
      await expect(caldavConnector.runCalendarAction('caldavShareCalendar', {id: 12, name: 'Work', color: '#fff'}))
        .resolves.toBe(true);
    } finally {
      document.removeEventListener('open-caldav-share-calendar-drawer', listener);
    }

    expect(seen).toEqual([{id: 12, name: 'Work'}]);
  });

  it('claims no other action, and no calendar without an id', async () => {
    const seen = [];
    const listener = event => seen.push(event.detail);
    document.addEventListener('open-caldav-share-calendar-drawer', listener);
    try {
      await expect(caldavConnector.runCalendarAction('icalLink', {id: 12})).resolves.toBe(false);
      await expect(caldavConnector.runCalendarAction('caldavShareCalendar', {id: 0})).resolves.toBe(false);
      await expect(caldavConnector.runCalendarAction('caldavShareCalendar', null)).resolves.toBe(false);
    } finally {
      document.removeEventListener('open-caldav-share-calendar-drawer', listener);
    }

    expect(seen).toEqual([]);
  });

  it('is inherited by every per-server descriptor', () => {
    const descriptor = createCaldavConnector({id: 3, providerName: 'agenda.caldavCalendar.3', serverUrl: 'https://s/'}, 0, null);

    expect(typeof descriptor.calendarActions).toBe('function');
    expect(typeof descriptor.runCalendarAction).toBe('function');
  });
});
