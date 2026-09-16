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
import Vue from 'vue';
import {shallowMount} from '@vue/test-utils';

/*
 * EXO-90331. What the drawer owes the agenda's calendar list after it has
 * changed a share: one event on the document, and nothing else.
 *
 * The mark is never drawn from what the drawer just did. An optimistic update
 * would be right only in the session that acted, would be gone on the next
 * reload, would say nothing to another device or another viewer, and would
 * claim a share whose subscription is still owed. The drawer's whole job is to
 * make the panel ask again — which is why these tests assert the event and
 * assert that the drawer touches no share state of its own.
 *
 * On the document rather than on $root, because the drawer lives in the
 * add-on's own Vue app and the list lives in agenda's: a $root event never
 * crosses that boundary. `agenda-connectors-refresh` rather than
 * `agenda-refresh-personal-calendars`, because the set of calendars has not
 * changed — only what a connector says about one of them — and
 * AgendaPersonalCalendarList binds its share read to both while binding its
 * calendar read to the other alone.
 */

jest.mock('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js', () => ({
  getCalendarShares: jest.fn(),
  getShareCandidates: jest.fn(),
  shareCalendar: jest.fn(),
  unshareCalendar: jest.fn(),
}));

const caldavConnectorService = require('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js');
const CaldavShareCalendarDrawer = require('../../main/webapp/vue-app/caldav/components/CaldavShareCalendarDrawer.vue').default;

describe('the share drawer, after it has changed a share', () => {

  // Vuetify's own components are not registered here, and the drawer's
  // template is full of them; rendered as unknown elements they cost nothing
  // and none of these assertions is about the markup. Same idiom as agenda's
  // sharedCalendarMark.test.js.
  Vue.config.ignoredElements = [/^v-/, /^exo-/];

  /** Every event the drawer dispatched on the document. */
  let dispatched;

  /** The document's own dispatchEvent, restored after each test. */
  const dispatchEvent = document.dispatchEvent.bind(document);

  /**
   * The drawer, mounted shallow on alice's calendar 12 with bob already
   * sharing it, and with the pieces it reaches for outside its own script
   * stubbed.
   *
   * @param {Object} shares what the drawer holds before the act
   * @returns {Object} the wrapper
   */
  function drawer(shares) {
    const wrapper = shallowMount(CaldavShareCalendarDrawer, {
      mocks: {
        $t: key => key,
        $root: {$emit: jest.fn()},
        $vuetify: {rtl: false, breakpoint: {}},
      },
      stubs: {'exo-drawer': true, 'exo-confirm-dialog': true},
    });
    wrapper.setData({
      calendar: {id: 12, name: 'Work'},
      sharees: shares.sharees || [],
      candidates: [{username: 'bob', fullName: 'Bob Test'}],
      selected: shares.selected || null,
      meetingCopies: false,
    });
    return wrapper;
  }

  /** The events of one name the drawer dispatched. */
  const refreshes = () => dispatched.filter(event => event.type === 'agenda-connectors-refresh');

  beforeEach(() => {
    jest.clearAllMocks();
    dispatched = [];
    document.dispatchEvent = jest.fn(event => {
      dispatched.push(event);
      return true;
    });
  });

  afterEach(() => {
    document.dispatchEvent = dispatchEvent;
  });

  it('asks the calendar list to refresh after a grant', async () => {
    caldavConnectorService.shareCalendar.mockResolvedValue({calendarId: 12, sharees: [{principal: '/p/bob', users: [{username: 'bob'}]}]});
    const wrapper = drawer({selected: {remoteId: 'bob'}});

    await wrapper.vm.doShare();

    expect(caldavConnectorService.shareCalendar).toHaveBeenCalledWith(12, 'bob');
    expect(refreshes()).toHaveLength(1);
  });

  it('asks the calendar list to refresh after a revoke', async () => {
    caldavConnectorService.unshareCalendar.mockResolvedValue({calendarId: 12, sharees: []});
    const wrapper = drawer({sharees: [{principal: '/p/bob', users: [{username: 'bob'}]}]});

    await wrapper.vm.unshare({principal: '/p/bob', users: [{username: 'bob'}]});

    expect(caldavConnectorService.unshareCalendar).toHaveBeenCalledWith(12, 'bob');
    expect(refreshes()).toHaveLength(1);
  });

  it('asks nothing when the server refused the change', async () => {
    // The mark must not be re-read as if something had happened: the panel
    // would answer the same thing it already shows, and a refresh on a failed
    // act is how a drawer starts looking like it half-worked.
    caldavConnectorService.shareCalendar.mockRejectedValue(new Error('caldav.share.serverUnavailable'));
    const wrapper = drawer({selected: {remoteId: 'bob'}});

    await wrapper.vm.doShare();

    expect(refreshes()).toHaveLength(0);
  });

  it('asks all the same when the drawer has moved to another calendar meanwhile', async () => {
    // Not subject to the guard the sharee list applies. That guard stops a
    // slow answer painting calendar 12's sharees under calendar 14's name; the
    // panel has no such confusion to avoid, and the grant did happen — a panel
    // not told keeps a mark that no longer matches the server until some
    // unrelated refresh comes along.
    let resolve;
    caldavConnectorService.shareCalendar.mockReturnValue(new Promise(done => resolve = done));
    const wrapper = drawer({selected: {remoteId: 'bob'}});

    const acting = wrapper.vm.doShare();
    wrapper.setData({calendar: {id: 14, name: 'Other'}});
    resolve({calendarId: 12, sharees: []});
    await acting;

    expect(refreshes()).toHaveLength(1);
    // and the drawer it moved to is not repainted with calendar 12's answer
    expect(wrapper.vm.sharees).toEqual([]);
  });

  it('draws no count of its own: the panel is told to ask, never told the answer', async () => {
    caldavConnectorService.shareCalendar.mockResolvedValue({calendarId: 12, sharees: [{principal: '/p/bob', users: [{username: 'bob'}]}]});
    const wrapper = drawer({selected: {remoteId: 'bob'}});

    await wrapper.vm.doShare();

    // One bare event and nothing else: no detail carrying a calendar id or a
    // count that a listener could draw a mark from without re-asking.
    expect(refreshes()[0].detail).toBeNull();
    expect(dispatched).toHaveLength(1);
  });
});
