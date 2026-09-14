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
  showCalendarAgain: jest.fn(() => Promise.resolve()),
}));

import Vue from 'vue';
import {shallowMount} from '@vue/test-utils';

import CaldavHiddenCalendarsDrawer from '../../main/webapp/vue-app/caldav/components/CaldavHiddenCalendarsDrawer.vue';
import * as caldavConnectorService from '../../main/webapp/vue-app/caldav/js/agendaCaldavService.js';

/**
 * The hidden-calendars drawer, now that it lists two kinds (EXO-90239).
 *
 * <p>The row is the same for both — a name and a Show again button — and
 * what tells them apart is the line under the name and the message after
 * the button: a calendar deleted here comes back at the next
 * synchronisation, a share comes back under Shared with me at once. Getting
 * either wrong is silent, so both are pinned on the keys the drawer resolves,
 * with their arguments.</p>
 */
describe('CaldavHiddenCalendarsDrawer', () => {

  Vue.config.ignoredElements = [/^v-/];

  /** A calendar of the user's own, deleted here and kept on the account. */
  const PRIVATE = {id: 9, name: 'Private', shared: false, ownerDisplayName: null};

  /** A share whose owner the platform could name. */
  const ALICES = {id: 12, name: 'Alice', shared: true, ownerDisplayName: 'Alice Martin'};

  /** A share whose owner nobody could name. */
  const UNNAMED = {id: 13, name: 'Room 4', shared: true, ownerDisplayName: null};

  /**
   * Mounts the drawer over a fixed list, with a `$t` that echoes the key and
   * its arguments so a test reads exactly what the drawer resolved.
   *
   * @param {Array} calendars the hidden calendars to offer back
   * @returns {Object} the wrapper
   */
  function mountDrawer(calendars) {
    return shallowMount(CaldavHiddenCalendarsDrawer, {
      propsData: {calendars},
      mocks: {
        $t: (key, params) => (params ? `${key}(${Object.values(params).join(',')})` : key),
        $vuetify: {rtl: false},
      },
      stubs: {
        'exo-drawer': {
          template: '<div><slot name="title"></slot><slot name="content"></slot></div>',
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

  /**
   * Records what the drawer says on the root, where the alert and the agenda
   * refreshes travel: `wrapper.emitted` sees only the component's own events,
   * and the root of a mounted component is not the component.
   *
   * @param {Object} wrapper the mounted drawer
   * @returns {Function} the arguments of every root event of one name
   */
  function recordRootEvents(wrapper) {
    const spy = jest.spyOn(wrapper.vm.$root, '$emit');
    return name => spy.mock.calls.filter(call => call[0] === name).map(call => call.slice(1));
  }

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders both kinds under the neutral description, each with its own line', () => {
    const wrapper = mountDrawer([PRIVATE, ALICES, UNNAMED]);
    const text = wrapper.text();

    // The neutral sentence, not the older one about calendars "you deleted".
    expect(text).toContain('caldav.hiddenCalendars.drawer.about');
    expect(text).not.toContain('caldav.hiddenCalendars.drawer.description');
    expect(text).toContain('Private');
    expect(text).toContain('caldav.hiddenCalendars.deletedHere');
    expect(text).toContain('Alice');
    expect(text).toContain('caldav.hiddenCalendars.sharedBy(Alice Martin)');
    expect(text).toContain('Room 4');
    expect(text).toContain('caldav.hiddenCalendars.sharedWithYou');
  });

  it('keeps the same muted glyph on a share as on a deleted calendar', () => {
    const wrapper = mountDrawer([PRIVATE, ALICES]);

    const glyphs = wrapper.findAll('v-icon');
    expect(glyphs.length).toBe(2);
    glyphs.wrappers.forEach(glyph => expect(glyph.classes()).toContain('disabled--text'));
  });

  it('says a share is back under Shared with me once shown again, and asks the agenda to re-read', async () => {
    const wrapper = mountDrawer([ALICES]);
    const rootEvents = recordRootEvents(wrapper);
    const documentEvents = [];
    const listener = event => documentEvents.push(event.type);
    document.addEventListener('agenda-refresh-personal-calendars', listener);

    await wrapper.vm.showAgain(ALICES);

    document.removeEventListener('agenda-refresh-personal-calendars', listener);
    expect(caldavConnectorService.showCalendarAgain).toHaveBeenCalledWith(12);
    expect(rootEvents('alert-message')).toEqual([['caldav.hiddenCalendars.showAgainSharedSuccess(Alice)', 'success']]);
    // The Remote section re-reads on these two root events; the settings
    // row re-reads on `changed`.
    expect(rootEvents('agenda-refresh-personal-calendars')).toHaveLength(1);
    expect(rootEvents('agenda-refresh')).toHaveLength(1);
    expect(wrapper.emitted('changed')).toHaveLength(1);
    expect(documentEvents).toEqual(['agenda-refresh-personal-calendars']);
  });

  it('keeps telling a deleted calendar it comes back at the next synchronisation', async () => {
    const wrapper = mountDrawer([PRIVATE]);
    const rootEvents = recordRootEvents(wrapper);

    await wrapper.vm.showAgain(PRIVATE);

    expect(caldavConnectorService.showCalendarAgain).toHaveBeenCalledWith(9);
    expect(rootEvents('alert-message')).toEqual([['caldav.hiddenCalendars.showAgainSuccess(Private)', 'success']]);
  });

  it('reports a refused show-again without pretending anything changed', async () => {
    caldavConnectorService.showCalendarAgain.mockImplementationOnce(() => Promise.reject(new Error('403')));
    jest.spyOn(console, 'error').mockImplementation(() => {});
    const wrapper = mountDrawer([ALICES]);
    const rootEvents = recordRootEvents(wrapper);

    await wrapper.vm.showAgain(ALICES);

    expect(rootEvents('alert-message')).toEqual([['caldav.hiddenCalendars.showAgainError', 'error']]);
    expect(rootEvents('agenda-refresh')).toEqual([]);
    expect(wrapper.emitted('changed')).toBeUndefined();
    expect(wrapper.vm.restoring).toBeNull();
    console.error.mockRestore();
  });
});
