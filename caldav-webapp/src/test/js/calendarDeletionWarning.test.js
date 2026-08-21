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
jest.mock('tsdav', () => ({DAVNamespaceShort: {DAV: 'd', CALDAV: 'c', CALDAV_APPLE: 'ca'}}), {virtual: true});
jest.mock('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js', () => ({
  getCaldavSetting: jest.fn(),
  saveMirrorCalendarHref: jest.fn(),
}));

import caldavConnector from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';

/**
 * What a user is told before they delete a calendar eXo mirrors, and what
 * happens when they confirm.
 *
 * The sentence is the only part of this feature no test can validate — whether
 * it makes someone stop and think is not something an assertion knows. What is
 * pinned here is that the right one is chosen, that it names the server, and
 * that confirming does what the sentence said it would.
 */
describe('deleting a calendar eXo mirrors', () => {

  beforeEach(() => {
    jest.clearAllMocks();
    window.eXo = {env: {portal: {i18n: {
      'agenda.caldavCalendar.calendarDelete.propagates': 'also on {0}, deleted there too',
      'agenda.caldavCalendar.calendarDelete.keepsRemote': 'the calendar on {0} is not touched',
    }}}};
  });

  it('warns that the remote calendar and its events go too', async () => {
    givenPlan({claimed: true, propagates: true, server: 'https://webmail.example.test/dav/'});

    const description = await caldavConnector.describeCalendarDeletion({id: 11});

    expect(description.claims).toBe(true);
    // The server is named: a user with two accounts must know which one.
    expect(description.warning).toContain('webmail.example.test');
    expect(description.warning).toContain('deleted there too');
  });

  it('says plainly when the remote calendar is left alone', async () => {
    // Worth as much as the other sentence: without it the user assumes the
    // worst and keeps a calendar they meant to remove from eXo.
    givenPlan({claimed: true, propagates: false, server: 'https://webmail.example.test/dav/'});

    const description = await caldavConnector.describeCalendarDeletion({id: 11});

    expect(description.warning).toContain('is not touched');
  });

  it('claims nothing for a calendar it does not mirror', async () => {
    givenPlan({claimed: false, propagates: false, server: null});

    const description = await caldavConnector.describeCalendarDeletion({id: 11});

    expect(description.claims).toBe(false);
    expect(description.warning).toBe('');
  });

  it('deletes the remote collection when that is what was announced', async () => {
    givenPlan({claimed: true, propagates: true, server: 'https://s.test/dav/'});
    await caldavConnector.describeCalendarDeletion({id: 11});
    global.fetch = jest.fn(() => Promise.resolve({ok: true, status: 204}));

    await caldavConnector.deleteCalendar({id: 11});

    const [url, options] = global.fetch.mock.calls[0];
    expect(url).toContain('/caldav/rest/push/calendars/11/remote');
    expect(options.method).toBe('DELETE');
  });

  it('records the choice when the remote calendar is kept', async () => {
    // Not a no-op: without telling the server, the next sweep materialises the
    // remote calendar straight back and undoes the deletion in front of them.
    givenPlan({claimed: true, propagates: false, server: 'https://s.test/dav/'});
    await caldavConnector.describeCalendarDeletion({id: 11});
    global.fetch = jest.fn(() => Promise.resolve({ok: true, status: 204}));

    await caldavConnector.deleteCalendar({id: 11});

    const [url, options] = global.fetch.mock.calls[0];
    expect(url).toContain('/caldav/rest/push/calendars/11/keep-remote');
    expect(options.method).toBe('POST');
  });

  it('does nothing for a calendar nobody asked about', async () => {
    global.fetch = jest.fn();

    await caldavConnector.deleteCalendar({id: 99});

    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('refuses the whole deletion when the server refuses', async () => {
    // The rejection is what stops agenda deleting locally. Swallowing it here
    // would strand the collection after the calendar is gone.
    givenPlan({claimed: true, propagates: true, server: 'https://s.test/dav/'});
    await caldavConnector.describeCalendarDeletion({id: 11});
    global.fetch = jest.fn(() => Promise.resolve({
      ok: false, status: 502, text: () => Promise.resolve('caldav.error.deleteFailed'),
    }));

    await expect(caldavConnector.deleteCalendar({id: 11})).rejects.toMatchObject({code: 'caldav.error.deleteFailed'});
  });

  /**
   * The server's answer about what deleting a calendar would do.
   *
   * @param {Object} plan the deletion plan to answer with
   */
  function givenPlan(plan) {
    global.fetch = jest.fn(() => Promise.resolve({ok: true, status: 200, json: () => Promise.resolve(plan)}));
  }
});
