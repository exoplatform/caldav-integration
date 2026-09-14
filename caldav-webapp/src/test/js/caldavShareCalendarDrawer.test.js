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
jest.mock('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js', () => ({
  getCalendarShares: jest.fn(),
  getShareCandidates: jest.fn(),
  shareCalendar: jest.fn(),
  unshareCalendar: jest.fn(),
}));

import Vue from 'vue';
import {shallowMount} from '@vue/test-utils';

import CaldavShareCalendarDrawer from '../../main/webapp/vue-app/caldav/components/CaldavShareCalendarDrawer.vue';
import * as caldavConnectorService from '../../main/webapp/vue-app/caldav/js/agendaCaldavService.js';

/**
 * The share drawer (EXO-90253): what it shows for each kind of sharee, what
 * it sends, and how it words a refusal.
 *
 * <p>The rows are pinned on the keys and arguments the drawer resolves, since
 * a wrong line under a sharee — "Can view" on a grant that gives more, a
 * remove button on someone eXo cannot name — is silent in the browser.</p>
 */
describe('CaldavShareCalendarDrawer', () => {

  Vue.config.ignoredElements = [/^v-/];

  const WORK = {id: 12, name: 'Work'};

  const BOB = {
    principal: '/dav/pal/bob%40stalwart.local/',
    kind: 'EXO_USERS',
    users: [{identityId: 9, username: 'bob', fullName: 'Bob Test', avatarUrl: '/avatar/bob'}],
    displayName: null,
    access: 'READ',
    removable: true,
  };

  const ZOE = {
    principal: '/dav/pal/zoe%40partner.example/',
    kind: 'OUTSIDE_EXO',
    users: [],
    displayName: 'Zoé Partner',
    access: 'MORE',
    removable: false,
  };

  const EVERYONE = {principal: '{DAV:}authenticated', kind: 'EVERYONE', users: [], displayName: null, access: 'READ', removable: false};

  const CANDIDATES = [
    {identityId: 9, username: 'bob', fullName: 'Bob Test', avatarUrl: '/avatar/bob'},
    {identityId: 10, username: 'carol', fullName: 'Carol Test', avatarUrl: null},
  ];

  let opened;

  let confirms;

  /**
   * Mounts the drawer with a `$t` echoing key and arguments.
   *
   * @returns {Object} the wrapper
   */
  function mountDrawer() {
    opened = 0;
    confirms = 0;
    return shallowMount(CaldavShareCalendarDrawer, {
      mocks: {
        $t: (key, params) => (params ? `${key}(${Object.values(params).join(',')})` : key),
        // The precondition sentences the bundle holds; any other key is unknown.
        $te: key => ['caldav.share.precondition.allowed-principal'].includes(key),
        $vuetify: {rtl: false},
      },
      stubs: {
        'identity-suggester': true,
        'user-avatar': true,
        'exo-confirm-dialog': {
          template: '<div></div>',
          methods: {
            open() {
              confirms++;
            },
          },
        },
        'exo-drawer': {
          template: '<div><slot name="title"></slot><slot name="content"></slot></div>',
          methods: {
            open() {
              opened++;
            },
            close() {
              this.$emit('closed');
            },
          },
        },
      },
    });
  }

  /**
   * Lets every pending promise settle.
   *
   * @returns {Promise} resolves on the next macrotask
   */
  function settle() {
    return new Promise(resolve => setTimeout(resolve));
  }

  beforeEach(() => {
    jest.clearAllMocks();
    caldavConnectorService.getCalendarShares.mockResolvedValue({calendarId: 12, sharees: [BOB, ZOE, EVERYONE]});
    caldavConnectorService.getShareCandidates.mockResolvedValue(CANDIDATES);
  });

  it('opens on a calendar and lists every sharee with what they can do', async () => {
    const wrapper = mountDrawer();

    await wrapper.vm.open(WORK);
    await settle();

    expect(opened).toBe(1);
    expect(caldavConnectorService.getCalendarShares).toHaveBeenCalledWith(12);
    expect(caldavConnectorService.getShareCandidates).toHaveBeenCalledWith(12);
    expect(wrapper.text()).toContain('caldav.share.drawer.title');
    expect(wrapper.text()).not.toContain('caldav.share.drawer.title(');
    expect(wrapper.find('.caldav-share-calendar-name').text()).toBe('Work');
    expect(wrapper.text()).toContain('caldav.share.drawer.sameServerOnly');
    expect(wrapper.vm.nameOf(BOB)).toBe('Bob Test');
    expect(wrapper.vm.accessOf(BOB)).toBe('caldav.share.access.read');
    expect(wrapper.vm.nameOf(ZOE)).toBe('Zoé Partner');
    expect(wrapper.vm.accessOf(ZOE)).toBe('caldav.share.outsideExo · caldav.share.access.more');
    expect(wrapper.vm.nameOf(EVERYONE)).toBe('caldav.share.everyone');
    expect(wrapper.vm.avatarUserOf(BOB)).toEqual(BOB.users[0]);
    expect(wrapper.vm.avatarUserOf(ZOE)).toBeNull();
    expect(wrapper.vm.avatarUserOf(EVERYONE)).toBeNull();
    // Only the sharee eXo can name and may remove carries the button.
    expect(wrapper.findAll('v-btn-stub, v-btn').filter(button => button.attributes('aria-label')).length).toBe(1);
    expect(wrapper.text()).not.toContain('caldav.share.sharees.none');
  });

  it('offers the colleagues not yet shared with, in the suggester\'s shape', async () => {
    const wrapper = mountDrawer();

    await wrapper.vm.open(WORK);
    await settle();

    expect(wrapper.vm.candidateItems).toEqual([{
      id: 'organization:carol',
      providerId: 'organization',
      remoteId: 'carol',
      profile: {fullName: 'Carol Test', avatarUrl: null},
    }]);
  });

  it('tells the owner a colleague must subscribe on a server that requires it, and only there', async () => {
    caldavConnectorService.getCalendarShares.mockResolvedValueOnce({calendarId: 12, sharees: [BOB], subscriptionRequired: true});
    const wrapper = mountDrawer();

    await wrapper.vm.open(WORK);
    await settle();

    expect(wrapper.vm.subscriptionRequired).toBe(true);
    expect(wrapper.text()).toContain('caldav.share.drawer.subscriptionRequired');

    await wrapper.vm.open({id: 14, name: 'Family'});
    await settle();

    expect(wrapper.vm.subscriptionRequired).toBe(false);
    expect(wrapper.text()).not.toContain('caldav.share.drawer.subscriptionRequired');
  });

  it('says so when the calendar is shared with nobody', async () => {
    caldavConnectorService.getCalendarShares.mockResolvedValue({calendarId: 12, sharees: []});
    const wrapper = mountDrawer();

    await wrapper.vm.open(WORK);
    await settle();

    expect(wrapper.text()).toContain('caldav.share.sharees.none');
  });

  it('shares with the colleague picked and shows the list the server answered', async () => {
    caldavConnectorService.shareCalendar.mockResolvedValue({calendarId: 12, sharees: [BOB, {...BOB, principal: '/dav/pal/carol/', users: [CANDIDATES[1]]}]});
    const wrapper = mountDrawer();
    const alerts = [];
    wrapper.vm.$root.$on('alert-message', (message, type) => alerts.push([message, type]));
    await wrapper.vm.open(WORK);
    await settle();

    wrapper.vm.selected = wrapper.vm.candidateItems[0];
    await wrapper.vm.share();

    expect(caldavConnectorService.shareCalendar).toHaveBeenCalledWith(12, 'carol');
    expect(wrapper.vm.sharees.length).toBe(2);
    expect(wrapper.vm.selected).toBeNull();
    expect(alerts).toEqual([['caldav.share.added(Carol Test)', 'success']]);
  });

  it('warns that a calendar holding meeting copies shows them, and shares only once the user confirms', async () => {
    caldavConnectorService.getCalendarShares.mockResolvedValue({calendarId: 12, sharees: [], meetingCopies: true});
    caldavConnectorService.shareCalendar.mockResolvedValue({calendarId: 12, sharees: [BOB], meetingCopies: true});
    const wrapper = mountDrawer();
    await wrapper.vm.open(WORK);
    await settle();

    expect(wrapper.text()).toContain('caldav.share.drawer.meetingCopies');
    wrapper.vm.selected = wrapper.vm.candidateItems[0];
    await wrapper.vm.share();

    expect(confirms).toBe(1);
    expect(caldavConnectorService.shareCalendar).not.toHaveBeenCalled();

    await wrapper.vm.doShare();

    expect(caldavConnectorService.shareCalendar).toHaveBeenCalledWith(12, 'bob');
    expect(wrapper.vm.meetingCopies).toBe(true);
    expect(wrapper.text()).toContain('caldav.share.drawer.meetingCopies');
  });

  it('shares without asking, and warns of nothing, on a calendar holding no meeting copies', async () => {
    caldavConnectorService.shareCalendar.mockResolvedValue({calendarId: 12, sharees: [BOB]});
    const wrapper = mountDrawer();
    await wrapper.vm.open(WORK);
    await settle();

    expect(wrapper.text()).not.toContain('caldav.share.drawer.meetingCopies');
    wrapper.vm.selected = wrapper.vm.candidateItems[0];
    await wrapper.vm.share();

    expect(confirms).toBe(0);
    // bob is already a sharee in the default answer, so the first colleague offered is carol.
    expect(caldavConnectorService.shareCalendar).toHaveBeenCalledWith(12, 'carol');
  });

  it('removes a sharee by their login', async () => {
    caldavConnectorService.unshareCalendar.mockResolvedValue({calendarId: 12, sharees: [ZOE]});
    const wrapper = mountDrawer();
    await wrapper.vm.open(WORK);
    await settle();

    await wrapper.vm.unshare(BOB);

    expect(caldavConnectorService.unshareCalendar).toHaveBeenCalledWith(12, 'bob');
    expect(wrapper.vm.sharees).toEqual([ZOE]);
    expect(wrapper.vm.removing).toBeNull();
  });

  it('never tries to remove a sharee eXo cannot name', async () => {
    const wrapper = mountDrawer();
    await wrapper.vm.open(WORK);
    await settle();

    await wrapper.vm.unshare(ZOE);

    expect(caldavConnectorService.unshareCalendar).not.toHaveBeenCalled();
  });

  it('words a refusal as the server stated it', async () => {
    caldavConnectorService.shareCalendar.mockRejectedValue(Object.assign(new Error('caldav.share.serverRefused'), {
      code: 'caldav.share.serverRefused',
      preconditions: ['need-privileges', 'allowed-principal', '{urn:stalwart}max-shares'],
      missingPrivileges: ['write-acl'],
    }));
    const wrapper = mountDrawer();
    await wrapper.vm.open(WORK);
    await settle();

    wrapper.vm.selected = wrapper.vm.candidateItems[0];
    await wrapper.vm.share();

    // The missing privilege is named; of the preconditions, only the one the
    // bundle can word is shown, never a raw RFC or vendor name.
    expect(wrapper.vm.errorMessage).toBe('caldav.share.serverRefused caldav.share.error.missingPrivileges(write-acl)'
      + ' caldav.share.error.preconditions(caldav.share.precondition.allowed-principal)');
    expect(wrapper.vm.saving).toBe(false);
  });

  it('falls back to the generic sentence for a failure that is not a share refusal', async () => {
    caldavConnectorService.getCalendarShares.mockRejectedValue(new Error('network'));
    const wrapper = mountDrawer();

    await wrapper.vm.open(WORK);
    await settle();

    expect(wrapper.vm.errorMessage).toBe('caldav.share.error.generic');
    expect(wrapper.vm.loading).toBe(false);
  });

  it('drops an answer for a calendar it no longer shows', async () => {
    let answerWork;
    caldavConnectorService.getCalendarShares
      .mockImplementationOnce(() => new Promise(resolve => answerWork = resolve))
      .mockResolvedValueOnce({calendarId: 14, sharees: [ZOE]});
    const wrapper = mountDrawer();

    wrapper.vm.open(WORK);
    await wrapper.vm.open({id: 14, name: 'Family'});
    answerWork({calendarId: 12, sharees: [BOB]});
    await settle();

    expect(wrapper.vm.calendarName).toBe('Family');
    expect(wrapper.vm.sharees).toEqual([ZOE]);
  });
});
