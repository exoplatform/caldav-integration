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

/**
 * createCalendar must report what the server DID, not what it failed to
 * complain about. The defect these tests pin: MKCALENDAR is atomic (RFC 4791
 * section 5.3.1) — a server rejecting any one property answers 207 and
 * creates nothing — yet 207 is a 2xx, so deriving success from the absence
 * of a `ok === false` response showed the green "calendar created" message
 * over a calendar that does not exist (seen live against BlueMind). Success
 * is now the presence of the collection in a fresh listing, never the
 * absence of a refusal.
 */
jest.mock('tsdav', () => ({
  createDAVClient: jest.fn(),
  DAVNamespaceShort: {
    DAV: 'd',
    CALDAV: 'c',
    CALDAV_APPLE: 'ca',
  },
}));
jest.mock('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js', () => ({
  getCaldavSetting: jest.fn(),
  saveMirrorCalendarHref: jest.fn(),
}));

import * as tsdav from 'tsdav';
import * as caldavConnectorService from '../../main/webapp/vue-app/caldav/js/agendaCaldavService.js';
import caldavConnector from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';

const HOME_URL = 'https://server.test/dav/cal/john/';
const MIRROR_URL = 'https://server.test/dav/cal/john/exo-meetings/';
const NAME = 'eXo Meetings';
const REQUEST = {
  name: NAME,
  color: '#3f8487',
  description: 'Meetings accepted in eXo',
};

/**
 * A connected-client stub whose MKCALENDAR answers and successive calendar
 * listings are scripted per test. `fetchCalendars` consumes `listings` one
 * per call (the first serves the pre-existence check, the next ones the
 * post-MKCALENDAR verification), holding the last one once exhausted.
 *
 * @param {Array} makeResults what each makeCalendar call resolves with
 * @param {Array} listings what each fetchCalendars call resolves with
 * @returns {Object} the tsdav-client stub
 */
function stubClient(makeResults, listings) {
  let makeCall = 0;
  let fetchCall = 0;
  return {
    account: {homeUrl: HOME_URL, rootUrl: HOME_URL},
    makeCalendar: jest.fn(() => Promise.resolve(makeResults[Math.min(makeCall++, makeResults.length - 1)])),
    fetchCalendars: jest.fn(() => Promise.resolve(listings[Math.min(fetchCall++, listings.length - 1)])),
  };
}

/**
 * Wires the service and tsdav mocks around one scripted client, so each test
 * reads as: given these MKCALENDAR answers and these listings, what does
 * createCalendar tell the user?
 *
 * @param {Array} makeResults what each makeCalendar call resolves with
 * @param {Array} listings what each fetchCalendars call resolves with
 * @returns {Object} the tsdav-client stub, for call assertions
 */
function givenServer(makeResults, listings) {
  const client = stubClient(makeResults, listings);
  tsdav.createDAVClient.mockResolvedValue(client);
  caldavConnectorService.getCaldavSetting.mockResolvedValue({
    username: 'john',
    password: 'secret',
    caldavUrl: 'https://server.test/dav/cal/{username}/',
    mirrorCalendarHref: null,
  });
  caldavConnectorService.saveMirrorCalendarHref.mockResolvedValue(200);
  return client;
}

/**
 * The 207 multistatus of an atomic MKCALENDAR that rejected one property, as
 * tsdav 2.2.x hands it back: `ok` is derived from the status and 207 is a
 * 2xx, so the response CLAIMS ok while the failed propstat — kept only in
 * `raw` — says nothing was created.
 *
 * @param {String} rejectedProp parsed (camelCased) name of the rejected property
 * @returns {Object} one tsdav response object
 */
function multistatusRejecting(rejectedProp) {
  return {
    href: MIRROR_URL,
    status: 207,
    statusText: 'Multi-Status',
    ok: true,
    raw: {
      multistatus: {
        response: {
          href: {_text: '/dav/cal/john/exo-meetings/'},
          propstat: [
            {prop: {displayname: {}}, status: 'HTTP/1.1 424 Failed Dependency'},
            {prop: {[rejectedProp]: {}}, status: 'HTTP/1.1 403 Forbidden'},
          ],
        },
      },
    },
  };
}

const CREATED_201 = {href: MIRROR_URL, status: 201, statusText: 'Created', ok: true, raw: ''};
const MIRROR_LISTED = [{url: MIRROR_URL, displayName: NAME}];
const OTHER_CALENDARS = [{url: `${HOME_URL}default/`, displayName: 'John Doe'}];

describe('createCalendar reports what the server did', () => {

  beforeEach(() => jest.clearAllMocks());

  it('does not report success on a 207 whose failed propstat means nothing was created', async () => {
    // both attempts answer 207 rejecting the displayname itself, and no
    // listing ever carries the calendar: the BlueMind false-success shape
    givenServer(
      [[multistatusRejecting('displayname')]],
      [OTHER_CALENDARS],
    );

    await expect(caldavConnector.createCalendar(REQUEST))
      .rejects.toMatchObject({calendarCreationRefused: true, status: 403});
    expect(caldavConnectorService.saveMirrorCalendarHref).not.toHaveBeenCalled();
  });

  it('does not report success on an empty response array', async () => {
    givenServer([[]], [OTHER_CALENDARS]);

    await expect(caldavConnector.createCalendar(REQUEST))
      .rejects.toMatchObject({calendarCreationRefused: true});
    expect(caldavConnectorService.saveMirrorCalendarHref).not.toHaveBeenCalled();
  });

  it('does not report success when MKCALENDAR claims 201 but the server does not list the calendar', async () => {
    givenServer([[CREATED_201]], [OTHER_CALENDARS]);

    await expect(caldavConnector.createCalendar(REQUEST))
      .rejects.toMatchObject({calendarCreationRefused: true});
    expect(caldavConnectorService.saveMirrorCalendarHref).not.toHaveBeenCalled();
  });

  it('retries with the display name alone when an optional property is rejected, and succeeds', async () => {
    // first attempt: 207 rejecting the colour, nothing created; minimal
    // retry: 201, and the listing then carries the calendar
    const client = givenServer(
      [[multistatusRejecting('calendarColor')], [CREATED_201]],
      [OTHER_CALENDARS, OTHER_CALENDARS, MIRROR_LISTED],
    );

    await expect(caldavConnector.createCalendar(REQUEST)).resolves.toEqual({id: MIRROR_URL});
    expect(client.makeCalendar).toHaveBeenCalledTimes(2);
    // the retry asks for the identity alone — no colour, no description
    expect(Object.keys(client.makeCalendar.mock.calls[1][0].props)).toEqual(['d:displayname']);
    expect(caldavConnectorService.saveMirrorCalendarHref).toHaveBeenCalledWith(MIRROR_URL);
  });

  it('still reports a refusal the user can understand when the server genuinely refuses', async () => {
    givenServer(
      [[{href: MIRROR_URL, status: 403, statusText: 'Forbidden', ok: false, raw: ''}]],
      [OTHER_CALENDARS],
    );

    await expect(caldavConnector.createCalendar(REQUEST))
      .rejects.toMatchObject({calendarCreationRefused: true, status: 403});
    expect(caldavConnectorService.saveMirrorCalendarHref).not.toHaveBeenCalled();
  });

  it('still adopts the collection a 405 says already sits at that URL', async () => {
    const client = givenServer(
      [[{href: MIRROR_URL, status: 405, statusText: 'Method Not Allowed', ok: false, raw: ''}]],
      [OTHER_CALENDARS, MIRROR_LISTED],
    );

    await expect(caldavConnector.createCalendar(REQUEST)).resolves.toEqual({id: MIRROR_URL});
    // adopted on the verification listing: no second MKCALENDAR needed
    expect(client.makeCalendar).toHaveBeenCalledTimes(1);
    expect(caldavConnectorService.saveMirrorCalendarHref).toHaveBeenCalledWith(MIRROR_URL);
  });

  it('still succeeds on a server that genuinely creates the collection', async () => {
    // the Stalwart shape: 201 with an empty body, calendar listed afterwards
    const client = givenServer(
      [[CREATED_201]],
      [OTHER_CALENDARS, MIRROR_LISTED],
    );

    await expect(caldavConnector.createCalendar(REQUEST)).resolves.toEqual({id: MIRROR_URL});
    expect(client.makeCalendar).toHaveBeenCalledTimes(1);
    expect(caldavConnectorService.saveMirrorCalendarHref).toHaveBeenCalledWith(MIRROR_URL);
  });

  it('leaves the outcome unknown — a plain error, not a refusal — when the verification listing fails', async () => {
    // the calendar may well exist: claiming a refusal (whose message promises
    // the copies go to the first calendar) would be as untrue as claiming a
    // success — the user retries, and the retry adopts what was created
    const client = givenServer([[CREATED_201]], [OTHER_CALENDARS]);
    client.fetchCalendars
      .mockImplementationOnce(() => Promise.resolve(OTHER_CALENDARS))
      .mockImplementationOnce(() => Promise.reject(new Error('network down')));

    await expect(caldavConnector.createCalendar(REQUEST)).rejects.toThrow('network down');
    expect(caldavConnectorService.saveMirrorCalendarHref).not.toHaveBeenCalled();
  });
});
