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
jest.mock('tsdav', () => ({
  DAVClient: jest.fn(),
  DAVNamespaceShort: {DAV: 'd', CALDAV: 'c', CALDAV_APPLE: 'ca'},
}));
jest.mock('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js', () => ({
  getCaldavSetting: jest.fn(),
  saveMirrorCalendarHref: jest.fn(),
}));

import caldavConnector from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';

/**
 * The write path after it moved server-side. What these pin is not that a
 * request is made — it is that the page stopped deciding anything: no
 * credentials are read, no iCalendar is built, and the only thing it still
 * owns is turning a coded refusal into something a user can act on.
 */
describe('the browser write path is a shim', () => {

  beforeEach(() => {
    jest.clearAllMocks();
    window.eXo = {env: {portal: {context: '/portal', portalName: 'dw'}}};
  });

  it('posts the event id and never reads the stored credentials', async () => {
    global.fetch = jest.fn(() => Promise.resolve({ok: true, status: 200, json: () => Promise.resolve({id: 9})}));

    await caldavConnector.pushEvent({id: 101, remoteId: 'uid-101'});

    const [url, options] = global.fetch.mock.calls[0];
    expect(url).toContain('/caldav/rest/push/events/101');
    expect(options.method).toBe('POST');
    expect(options.credentials).toBe('include');
    // The settings hold the password; a shim that still fetched them would
    // have put it back in the page for no reason.
    expect(require('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js').getCaldavSetting).not.toHaveBeenCalled();
  });

  it('carries the eXo back-link so the copy can point home', async () => {
    global.fetch = jest.fn(() => Promise.resolve({ok: true, status: 200, json: () => Promise.resolve({})}));

    await caldavConnector.pushEvent({id: 101});

    expect(decodeURIComponent(global.fetch.mock.calls[0][0])).toContain('agenda?eventId=101');
  });

  it('keeps the refusal code the page already knows how to render', async () => {
    global.fetch = jest.fn(() => Promise.resolve({
      ok: false, status: 409, text: () => Promise.resolve('caldav.error.conflict'),
    }));

    await expect(caldavConnector.pushEvent({id: 101})).rejects.toMatchObject({code: 'caldav.error.conflict'});
  });

  it('falls back to a generic failure rather than inventing a code', async () => {
    global.fetch = jest.fn(() => Promise.resolve({ok: false, status: 502, text: () => Promise.resolve('')}));

    await expect(caldavConnector.pushEvent({id: 101})).rejects.toMatchObject({code: 'caldav.error.save'});
  });

  it('deletes a whole event through the server', async () => {
    global.fetch = jest.fn(() => Promise.resolve({ok: true, status: 204}));

    await caldavConnector.deleteEvent({id: 101, remoteId: 'uid-101'});

    const [url, options] = global.fetch.mock.calls[0];
    expect(url).toContain('/caldav/rest/push/objects/uid-101');
    expect(options.method).toBe('DELETE');
  });

  it('excludes one occurrence without touching the rest of the series', async () => {
    // A rewrite on the server, not a delete: every component of a series lives
    // in one calendar object, so removing the object would cancel every
    // meeting of the series to cancel one. The path says objects rather than
    // events because the identifier is the iCalendar UID, not an eXo id.
    global.fetch = jest.fn(() => Promise.resolve({ok: true, status: 204}));

    await caldavConnector.deleteEvent({
      id: 102,
      occurrence: {id: '2026-09-15T07:00:00.000Z'},
      parent: {remoteId: 'series-uid'},
    });

    const [url, options] = global.fetch.mock.calls[0];
    expect(url).toContain('/caldav/rest/push/objects/series-uid/occurrences/');
    expect(decodeURIComponent(url)).toContain('2026-09-15T07:00:00.000Z');
    expect(options.method).toBe('DELETE');
  });

  it('does nothing for an occurrence whose series was never pushed', async () => {
    global.fetch = jest.fn();

    await expect(caldavConnector.deleteEvent({id: 102, occurrence: {id: '2026-09-15T07:00:00.000Z'}, parent: {}}))
      .resolves.toBeNull();

    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('does nothing for an event that was never pushed', async () => {
    global.fetch = jest.fn();

    await expect(caldavConnector.deleteEvent({id: 101})).resolves.toBeNull();

    expect(global.fetch).not.toHaveBeenCalled();
  });
});
