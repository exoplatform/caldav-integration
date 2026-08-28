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

import * as caldavConnectorService from '../../main/webapp/vue-app/caldav/js/agendaCaldavService.js';
import CaldavAdminSyncDrawer from '../../main/webapp/vue-app/caldav/components/admin/CaldavAdminSyncDrawer.vue';

jest.mock('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js', () => ({
  getMirrorReports: jest.fn(),
  saveSyncTuning: jest.fn(),
}));

/**
 * The per-user tallies on the synchronisation drawer (EXO-89762).
 *
 * <p>They are the only thing that turns "measure before you move a server to
 * its main calendar" from advice into something a product owner can check —
 * without them the answer lives in the platform log, behind a permission the
 * person making the decision does not have. So what is pinned here is that the
 * numbers reach the screen, that a pass which moved nothing does not pretend to
 * have moved zero copies, and that a platform which cannot answer for them
 * leaves the five settings above perfectly usable.</p>
 */
describe('CaldavAdminSyncDrawer', () => {

  Vue.config.ignoredElements = [/^v-/];

  /** A user whose destination change moved copies, and one whose pass owed no move. */
  const reports = [
    {
      userIdentityId: 1,
      username: 'joe',
      fullName: 'Joe Doe',
      at: '2026-08-28T09:00:00.000Z',
      verification: {checked: 10, missing: 1, altered: 2, adopted: 0, repaired: 1, abandoned: 0},
      relocation: {destination: '/dav/cal/joe/main/', moved: 4, refused: 1, failed: 0, unmovable: 0},
    },
    {
      userIdentityId: 2,
      username: null,
      fullName: null,
      at: '2026-08-28T08:00:00.000Z',
      verification: {checked: 3, missing: 0, altered: 0, adopted: 0, repaired: 0, abandoned: 0},
      relocation: {destination: '/dav/cal/ann/exo-meetings/', moved: 0, refused: 0, failed: 0, unmovable: 0},
    },
  ];

  /**
   * Mounts the drawer with a $t that reads its receiver, as eXo's does.
   *
   * @returns {Object} the wrapper
   */
  function mountDrawer() {
    return shallowMount(CaldavAdminSyncDrawer, {
      mocks: {
        $t(key, args) {
          return args && `${this.$i18n.locale}:${key}(${args[0]})` || `${this.$i18n.locale}:${key}`;
        },
        $te: () => false,
        $i18n: {locale: 'en'},
      },
      stubs: {
        'exo-drawer': {
          template: '<div><slot name="title"></slot><slot name="content"></slot><slot name="footer"></slot></div>',
          methods: {
            open() {
              this.$emit('input', true);
            },
            close() {
              this.$emit('input', false);
            },
          },
        },
      },
    });
  }

  beforeEach(() => {
    caldavConnectorService.getMirrorReports.mockReset();
  });

  it('shows what the last pass found for each user, by a name an administrator recognises', async () => {
    caldavConnectorService.getMirrorReports.mockResolvedValue(reports);
    const wrapper = mountDrawer();

    await wrapper.vm.open({throttleMinutes: 5});
    await wrapper.vm.$nextTick();

    const text = wrapper.text();
    expect(text).toContain('caldav.admin.sync.reports.title');
    expect(text).toContain('caldav.admin.sync.reports.subtitle');
    expect(text).toContain('Joe Doe');
    // No name at all resolved: the identity is at least something to look up,
    // and dropping the row would hide the account rather than show it.
    expect(text).toContain('caldav.admin.sync.reports.unknownUser(2)');
    expect(text).toContain('caldav.admin.sync.reports.checked(10)');
  });

  it('states a move only for the pass that made one', async () => {
    // The ordinary pass owes no change of destination and its counts are all
    // zero. Printed as "0 moved" on every row it would bury the account where a
    // change is genuinely still working itself through, which is the only thing
    // this section exists to show.
    caldavConnectorService.getMirrorReports.mockResolvedValue(reports);
    const wrapper = mountDrawer();

    await wrapper.vm.open({});
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).toContain('caldav.admin.sync.reports.moved(4)');
    expect(wrapper.text()).toContain('caldav.admin.sync.reports.notMoved');
    expect(wrapper.vm.relocated(reports[0])).toBe(true);
    expect(wrapper.vm.relocated(reports[1])).toBe(false);
  });

  it('says an empty list means nothing has synchronised yet, not that nothing is supported', async () => {
    caldavConnectorService.getMirrorReports.mockResolvedValue([]);
    const wrapper = mountDrawer();

    await wrapper.vm.open({});
    await wrapper.vm.$nextTick();

    const text = wrapper.text();
    expect(text).toContain('caldav.admin.sync.reports.none');
    expect(text).not.toContain('caldav.admin.sync.reports.subtitle');
  });

  it('leaves the settings usable when the tallies cannot be read', async () => {
    // The drawer's reason to exist is the five values above; the tallies are an
    // aid beside them, and a platform that cannot answer for them must not take
    // the screen down with it.
    caldavConnectorService.getMirrorReports.mockRejectedValue(new Error('down'));
    const wrapper = mountDrawer();

    await wrapper.vm.open({throttleMinutes: 5});
    await wrapper.vm.$nextTick();

    const text = wrapper.text();
    expect(text).toContain('caldav.admin.sync.reports.loadFailed');
    expect(text).toContain('caldav.admin.sync.throttleMinutes.label');
    // And it is NOT reported as a refusal to save what the administrator typed.
    expect(wrapper.vm.errorMessage).toBe('');
  });
});
