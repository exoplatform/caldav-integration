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
    window.eXo = {env: {portal: {language: 'en'}}};
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

  // How the question is asked.

  it('asks the server about the calendar agenda named, over the session already open', async () => {
    givenPlan({claimed: true, propagates: true, server: 'https://s.test/dav/'});

    await caldavConnector.describeCalendarDeletion({id: 21});

    const [url, options] = global.fetch.mock.calls[0];
    expect(url).toContain('/caldav/rest/push/calendars/21/deletion-plan');
    // No method: a read. And no credentials of its own — the user's CalDAV
    // password never reaches the browser, so the cookie is what authenticates.
    expect(options.method).toBeUndefined();
    expect(options.credentials).toBe('include');
  });

  it('asks nothing at all about a calendar carrying no id', async () => {
    global.fetch = jest.fn();

    const description = await caldavConnector.describeCalendarDeletion({});

    expect(description).toEqual({claims: false, warning: ''});
    expect(global.fetch).not.toHaveBeenCalled();
  });

  // What the dialog is told when the question cannot be answered.

  it('claims nothing when the plan cannot be read', async () => {
    // A connector that cannot say what would happen must not claim that
    // something would: the dialog then asks agenda's plain question, and the
    // deletion of a calendar this connector may not even mirror is not blocked
    // by its own failure.
    const logged = jest.spyOn(console, 'error').mockImplementation(() => {});
    global.fetch = jest.fn(() => Promise.resolve({ok: false, status: 500}));

    const description = await caldavConnector.describeCalendarDeletion({id: 22});

    expect(description).toEqual({claims: false, warning: ''});
    // Degraded, not silent: the status is logged so a failing plan endpoint is
    // still diagnosable from a user's console.
    expect(logged).toHaveBeenCalled();
    logged.mockRestore();
  });

  it('claims nothing when the answer is not the plan it asked for', async () => {
    global.fetch = jest.fn(() => Promise.resolve({
      ok: true, status: 200, json: () => Promise.reject(new Error('not json')),
    }));

    const description = await caldavConnector.describeCalendarDeletion({id: 23});

    expect(description).toEqual({claims: false, warning: ''});
  });

  it('still asks for a confirmation when the sentence cannot be rendered', async () => {
    // A missing bundle costs the explanation, never the confirmation: falling
    // back to claims:false here would delete a mirrored calendar with no
    // warning at all, which is the one outcome this whole feature exists to
    // prevent.
    //
    // The bundle is starved at the endpoint the connector actually reads.
    // This used to empty window.eXo.env.portal.i18n, a page global that is
    // never populated at all — so the test passed while proving nothing, and
    // went on passing after the labels moved to the i18n endpoint.
    //
    // A fresh copy of the module, because the labels are fetched once and
    // remembered for the life of the page — which is what we want in a
    // browser and what makes an earlier test's bundle leak into this one.
    givenPlanWithoutBundle({claimed: true, propagates: true, server: 'https://s.test/dav/'});
    let starved;
    jest.isolateModules(() => {
      starved = require('../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js').default;
    });

    const description = await starved.describeCalendarDeletion({id: 24});

    expect(description.claims).toBe(true);
    expect(description.warning).toBe('');
  });

  // What is said before the whole account is unlinked.

  it('names the server when warning about disconnecting the account', async () => {
    givenPlan({});

    const warning = await caldavConnector.disconnectWarning.call({serverUrl: 'https://webmail.example.test/dav/'});

    expect(warning).toContain('webmail.example.test');
  });

  it('answers with nothing when the sentence cannot be rendered', async () => {
    // Pinned because of what the caller must do with it, not for its own
    // sake. This resolves empty on any locale the sentence has not reached
    // yet — Crowdin lags the _en source by design — and on a platform serving
    // a stale bundle, which is neither rare nor visible.
    //
    // The drawer used to read an empty answer as "nothing to confirm" and
    // disconnect on the single click. One missing translation then silently
    // removed every calendar the account had materialised. It now falls back
    // to agenda's own generic sentence and always asks: the explanation is
    // what a missing string may cost, never the confirmation.
    givenPlanWithoutBundle({});
    let starved;
    jest.isolateModules(() => {
      starved = require('../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js').default;
    });

    const warning = await starved.disconnectWarning.call({serverUrl: 'https://webmail.example.test/dav/'});

    expect(warning).toBe('');
  });

  // How the server is named in the sentence.

  it('names the server by its host when its address is not a full url', async () => {
    // A href stored before this connector spoke to a relay is not a parseable
    // URL. What identifies the account to the user is the host either way.
    givenPlan({claimed: true, propagates: true, server: 'webmail.example.test/dav/john/'});

    const description = await caldavConnector.describeCalendarDeletion({id: 25});

    expect(description.warning).toContain('webmail.example.test');
    expect(description.warning).not.toContain('/dav/');
  });

  it('leaves the server out of the sentence rather than printing the placeholder', async () => {
    givenPlan({claimed: true, propagates: true, server: null});

    const description = await caldavConnector.describeCalendarDeletion({id: 26});

    expect(description.claims).toBe(true);
    expect(description.warning).not.toContain('{0}');
  });

  // What confirming does.

  it('confirms over the same session rather than with credentials of its own', async () => {
    givenPlan({claimed: true, propagates: true, server: 'https://s.test/dav/'});
    await caldavConnector.describeCalendarDeletion({id: 27});
    global.fetch = jest.fn(() => Promise.resolve({ok: true, status: 204}));

    await caldavConnector.deleteCalendar({id: 27});

    expect(global.fetch.mock.calls[0][1].credentials).toBe('include');
  });

  it('deletes nothing for a calendar the server claimed nothing about', async () => {
    givenPlan({claimed: false, propagates: false, server: null});
    await caldavConnector.describeCalendarDeletion({id: 28});
    global.fetch = jest.fn();

    await caldavConnector.deleteCalendar({id: 28});

    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('forgets the plan once the deletion is through', async () => {
    // The plan holds one answer for the length of one confirmation. Kept past
    // it, a later calendar reusing the id would be deleted on the strength of
    // an answer about a different one.
    givenPlan({claimed: true, propagates: true, server: 'https://s.test/dav/'});
    await caldavConnector.describeCalendarDeletion({id: 29});
    global.fetch = jest.fn(() => Promise.resolve({ok: true, status: 204}));
    await caldavConnector.deleteCalendar({id: 29});
    global.fetch = jest.fn();

    await caldavConnector.deleteCalendar({id: 29});

    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('keeps the plan when the server refused, so the user can try again', async () => {
    // Nothing was deleted on either side, so the answer the dialog was given
    // still holds — and dropping it would turn the retry into a silent no-op
    // that leaves the collection standing while agenda deletes the calendar.
    givenPlan({claimed: true, propagates: true, server: 'https://s.test/dav/'});
    await caldavConnector.describeCalendarDeletion({id: 30});
    global.fetch = jest.fn(() => Promise.resolve({
      ok: false, status: 502, text: () => Promise.resolve('caldav.error.deleteFailed'),
    }));
    await expect(caldavConnector.deleteCalendar({id: 30})).rejects.toBeDefined();
    global.fetch = jest.fn(() => Promise.resolve({ok: true, status: 204}));

    await caldavConnector.deleteCalendar({id: 30});

    expect(global.fetch.mock.calls[0][0]).toContain('/caldav/rest/push/calendars/30/remote');
  });

  it('carries the status of a refusal alongside its code', async () => {
    givenPlan({claimed: true, propagates: true, server: 'https://s.test/dav/'});
    await caldavConnector.describeCalendarDeletion({id: 31});
    global.fetch = jest.fn(() => Promise.resolve({
      ok: false, status: 403, text: () => Promise.resolve('caldav.error.credentials'),
    }));

    await expect(caldavConnector.deleteCalendar({id: 31}))
      .rejects.toMatchObject({code: 'caldav.error.credentials', status: 403});
  });

  it('falls back to a generic code when the refusal names none', async () => {
    // Agenda renders a message from the code. An empty one would render
    // nothing at all, leaving the user with a deletion that stopped for no
    // stated reason.
    givenPlan({claimed: true, propagates: true, server: 'https://s.test/dav/'});
    await caldavConnector.describeCalendarDeletion({id: 32});
    global.fetch = jest.fn(() => Promise.resolve({ok: false, status: 500, text: () => Promise.resolve('  ')}));

    await expect(caldavConnector.deleteCalendar({id: 32})).rejects.toMatchObject({code: 'caldav.error.save'});
  });

  /**
   * The server's answer about what deleting a calendar would do.
   *
   * @param {Object} plan the deletion plan to answer with
   */
  /**
   * Answers the deletion plan, and the label bundle the connector reads its
   * sentence from.
   *
   * The bundle is served by the platform's i18n endpoint, not carried on a
   * page global — an earlier version of these tests faked a global nothing
   * populates, so they passed while the dialog showed nothing at all.
   */
  function givenPlan(plan) {
    global.fetch = jest.fn(url => {
      if (String(url).includes('/i18n/bundle/')) {
        return Promise.resolve({ok: true, status: 200, json: () => Promise.resolve(BUNDLE)});
      }
      return Promise.resolve({ok: true, status: 200, json: () => Promise.resolve(plan)});
    });
  }

  /**
   * Answers the deletion plan, but no labels: the sentence cannot be built.
   *
   * @param {Object} plan what the server says deleting would do
   */
  function givenPlanWithoutBundle(plan) {
    global.fetch = jest.fn(url => {
      if (String(url).includes('/i18n/bundle/')) {
        return Promise.resolve({ok: false, status: 404, json: () => Promise.reject(new Error('no bundle'))});
      }
      return Promise.resolve({ok: true, status: 200, json: () => Promise.resolve(plan)});
    });
  }

  const BUNDLE = {
    'agenda.caldavCalendar.calendarDelete.propagates': 'also on {0}, deleted there too',
    'agenda.caldavCalendar.calendarDelete.keepsRemote': 'the calendar on {0} is not touched',
    'agenda.caldavCalendar.disconnect.warning': 'the calendars from {0} will be removed',
  };
});
