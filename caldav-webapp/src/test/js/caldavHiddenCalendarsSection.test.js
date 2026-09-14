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
  getHiddenCalendars: jest.fn(),
}));

import Vue from 'vue';
import {shallowMount} from '@vue/test-utils';

import CaldavHiddenCalendarsSection from '../../main/webapp/vue-app/caldav/components/CaldavHiddenCalendarsSection.vue';
import * as caldavConnectorService from '../../main/webapp/vue-app/caldav/js/agendaCaldavService.js';

/**
 * The hidden-calendars settings row and its drawer (EXO-90239).
 *
 * <p>The row disappears once nothing is hidden, and the last "Show again" is
 * that moment. The drawer must outlive the row: destroyed while open,
 * exo-drawer leaves its page overlay behind and the user cannot close it
 * until they reload. Pinned on the drawer still being mounted after the list
 * empties, which is what lets it close itself.</p>
 */
describe('CaldavHiddenCalendarsSection', () => {

  Vue.config.ignoredElements = [/^v-/];

  /** A share the user hid, the last one left. */
  const LAST = {id: 12, name: 'Alice', shared: true, ownerDisplayName: 'Alice Martin'};

  /**
   * Mounts the section with the drawer stubbed, so a test reads whether the
   * drawer component is still part of the page.
   *
   * @returns {Object} the wrapper
   */
  function mountSection() {
    return shallowMount(CaldavHiddenCalendarsSection, {
      mocks: {
        $t: key => key,
      },
      stubs: {
        'caldav-hidden-calendars-drawer': {
          name: 'CaldavHiddenCalendarsDrawer',
          props: ['calendars'],
          template: '<div class="hidden-calendars-drawer-stub"></div>',
        },
      },
    });
  }

  /**
   * Resolves the pending listing promises and re-renders.
   *
   * @returns {Promise} settled once the section has re-rendered
   */
  async function settle() {
    await new Promise(resolve => setTimeout(resolve, 0));
    await Vue.nextTick();
  }

  test('keeps its drawer mounted when the last hidden calendar is shown again', async () => {
    caldavConnectorService.getHiddenCalendars.mockResolvedValueOnce([LAST]);
    const wrapper = mountSection();
    await settle();
    expect(wrapper.find('.hidden-calendars-drawer-stub').exists()).toBe(true);

    caldavConnectorService.getHiddenCalendars.mockResolvedValueOnce([]);
    await wrapper.vm.retrieveHidden();
    await settle();

    expect(wrapper.vm.hidden).toEqual([]);
    expect(wrapper.find('.hidden-calendars-drawer-stub').exists()).toBe(true);
    expect(wrapper.findComponent({name: 'CaldavHiddenCalendarsDrawer'}).props('calendars')).toEqual([]);
  });

  test('draws no row when nothing is hidden, but still holds the drawer', async () => {
    caldavConnectorService.getHiddenCalendars.mockResolvedValueOnce([]);
    const wrapper = mountSection();
    await settle();

    expect(wrapper.find('v-list-item').exists()).toBe(false);
    expect(wrapper.find('.hidden-calendars-drawer-stub').exists()).toBe(true);
  });
});
