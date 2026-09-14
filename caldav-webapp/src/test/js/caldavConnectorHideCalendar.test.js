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
 * EXO-90239. The connector contract the agenda half builds its "Hide" action
 * against: `hideCalendar(calendarId)` posts the id of a `listCalendars()`
 * entry to the platform as a JSON body, resolves on success, rejects with the
 * platform's coded error otherwise, and on success dispatches the
 * personal-calendars refresh on the document. Every per-server descriptor
 * inherits it.
 */

/** Alice's calendar as listCalendars answered it: encoded login, trailing slash. */
const ALICES = '/dav/cal/alice%40stalwart.local/default/';

/**
 * Answers the next fetch as given.
 *
 * @param {Object} response what fetch resolves with
 * @returns {jest.Mock} the fetch mock
 */
function respondWith(response) {
  global.fetch = jest.fn(() => Promise.resolve(response));
  return global.fetch;
}

/**
 * Collects the document events of one name dispatched during a call.
 *
 * @param {String} name the event type to collect
 * @param {Function} act the call to make
 * @returns {Promise<Array>} the event types seen
 */
async function documentEventsDuring(name, act) {
  const seen = [];
  const listener = event => seen.push(event.type);
  document.addEventListener(name, listener);
  try {
    await act();
  } finally {
    document.removeEventListener(name, listener);
  }
  return seen;
}

describe('hideCalendar on the CalDAV connector', () => {

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('posts the calendar id as a JSON body, untouched', async () => {
    const fetch = respondWith({ok: true, status: 204});

    await caldavConnector.hideCalendar(ALICES);

    expect(fetch).toHaveBeenCalledTimes(1);
    const [url, options] = fetch.mock.calls[0];
    expect(url).toBe('http://localhost/caldav/rest/hidden-calendars');
    expect(options.method).toBe('POST');
    expect(options.credentials).toBe('include');
    expect(options.headers['Content-Type']).toBe('application/json');
    // As listed: the percent-encoded login and the trailing slash are the
    // platform's to canonicalise, not this module's to touch.
    expect(JSON.parse(options.body)).toEqual({calendarId: ALICES});
  });

  it('asks the agenda to re-read its calendars once the platform has hidden it', async () => {
    respondWith({ok: true, status: 204});

    const events = await documentEventsDuring('agenda-refresh-personal-calendars', () => caldavConnector.hideCalendar(ALICES));

    expect(events).toEqual(['agenda-refresh-personal-calendars']);
  });

  it('rejects with the code a refusal carries, and refreshes nothing', async () => {
    respondWith({
      ok: false,
      status: 400,
      text: () => Promise.resolve('{"timestamp":"2026-09-14T10:00:00Z","status":400,"message":"caldav.hiddenCalendars.notAShare"}'),
    });

    let refused;
    const events = await documentEventsDuring('agenda-refresh-personal-calendars',
      () => caldavConnector.hideCalendar('/dav/calendars/john/work/').catch(error => refused = error));

    expect(refused).toBeInstanceOf(Error);
    expect(refused.code).toBe('caldav.hiddenCalendars.notAShare');
    expect(refused.status).toBe(400);
    expect(events).toEqual([]);
  });

  it('reads the bare code a push failure answers with', async () => {
    // No connected account is refused by the handler that answers push
    // failures, whose body is the code alone rather than a JSON object.
    respondWith({ok: false, status: 409, text: () => Promise.resolve('caldav.error.noCalendar')});

    await expect(caldavConnector.hideCalendar(ALICES)).rejects.toMatchObject({code: 'caldav.error.noCalendar', status: 409});
  });

  it('still rejects when the refusal carries no readable body', async () => {
    respondWith({ok: false, status: 502, text: () => Promise.reject(new Error('no body'))});

    await expect(caldavConnector.hideCalendar(ALICES)).rejects.toMatchObject({code: null, status: 502});
  });

  it('is inherited by every per-server descriptor, beside everything they already had', () => {
    const descriptor = createCaldavConnector({id: 6, providerName: 'agenda.caldavCalendar.6', name: 'Bluemind',
      description: null, serverUrl: 'https://caldav.example.invalid/dav/', active: true}, 1, null);

    expect(typeof descriptor.hideCalendar).toBe('function');
    expect(descriptor.hideCalendar).toBe(caldavConnector.hideCalendar);
    expect(descriptor.canListCalendars).toBe(true);
    expect(typeof descriptor.listCalendars).toBe('function');
    expect(descriptor.isCaldav).toBe(true);
    expect(descriptor.name).toBe('agenda.caldavCalendar.6');
  });
});
