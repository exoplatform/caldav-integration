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
  getCalendarShares,
  getShareCandidates,
  getShareableCalendars,
  hideCalendar,
  shareCalendar,
  unshareCalendar,
} from '../../main/webapp/vue-app/caldav/js/agendaCaldavService.js';

/*
 * EXO-90253. The requests the share drawer makes: the calendar id and the
 * colleague's login are the only things sent, a refusal rejects with its code
 * and with what the calendar server said — the preconditions and the missing
 * privileges the platform's body carries — and every other refusal keeps the
 * shape it had.
 */

/**
 * Answers every fetch as given.
 *
 * @param {Object} response what fetch resolves with
 * @returns {jest.Mock} the fetch mock
 */
function respondWith(response) {
  global.fetch = jest.fn(() => Promise.resolve(response));
  return global.fetch;
}

describe('the share requests', () => {

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('posts the colleague login alone to the calendar shares', async () => {
    const fetch = respondWith({ok: true, json: () => Promise.resolve({calendarId: 12, sharees: []})});

    await expect(shareCalendar(12, 'bob')).resolves.toEqual({calendarId: 12, sharees: []});

    const [url, options] = fetch.mock.calls[0];
    expect(url).toBe('http://localhost/caldav/rest/calendars/12/shares');
    expect(options.method).toBe('POST');
    expect(options.credentials).toBe('include');
    expect(JSON.parse(options.body)).toEqual({username: 'bob'});
  });

  it('deletes by login, encoded for its path position', async () => {
    const fetch = respondWith({ok: true, json: () => Promise.resolve({calendarId: 12, sharees: []})});

    await unshareCalendar(12, 'jean.dupont');

    expect(fetch.mock.calls[0][0]).toBe('http://localhost/caldav/rest/calendars/12/shares/jean.dupont');
    expect(fetch.mock.calls[0][1].method).toBe('DELETE');
  });

  it('reads the sharees, the candidates and the shareable ids', async () => {
    let fetch = respondWith({ok: true, json: () => Promise.resolve({calendarId: 12, sharees: [{principal: '/p/'}]})});
    await expect(getCalendarShares(12)).resolves.toEqual({calendarId: 12, sharees: [{principal: '/p/'}]});
    expect(fetch.mock.calls[0][0]).toBe('http://localhost/caldav/rest/calendars/12/shares');

    fetch = respondWith({ok: true, json: () => Promise.resolve([{username: 'bob'}])});
    await expect(getShareCandidates(12)).resolves.toEqual([{username: 'bob'}]);
    expect(fetch.mock.calls[0][0]).toBe('http://localhost/caldav/rest/calendars/12/share-candidates');

    respondWith({ok: true, json: () => Promise.resolve({calendarIds: [12]})});
    await expect(getShareableCalendars()).resolves.toEqual([12]);
    respondWith({ok: true, json: () => Promise.resolve({})});
    await expect(getShareableCalendars()).resolves.toEqual([]);
  });

  it('rejects a refusal with its code and what the server said', async () => {
    respondWith({
      ok: false,
      status: 409,
      text: () => Promise.resolve(JSON.stringify({
        status: 409,
        message: 'caldav.share.serverRefused',
        preconditions: ['need-privileges'],
        missingPrivileges: ['write-acl'],
      })),
    });

    await expect(shareCalendar(12, 'bob')).rejects.toMatchObject({
      code: 'caldav.share.serverRefused',
      status: 409,
      preconditions: ['need-privileges'],
      missingPrivileges: ['write-acl'],
    });
  });

  it('keeps the older refusals as they were, with nothing the server said', async () => {
    respondWith({ok: false, status: 409, text: () => Promise.resolve('caldav.error.noCalendar')});

    await expect(hideCalendar('/dav/cal/alice/default/')).rejects.toMatchObject({
      code: 'caldav.error.noCalendar',
      preconditions: [],
      missingPrivileges: [],
    });
  });
});
