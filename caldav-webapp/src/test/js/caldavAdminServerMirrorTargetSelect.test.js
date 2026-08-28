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

import CaldavAdminServerMirrorTargetSelect
  from '../../main/webapp/vue-app/caldav/components/admin/CaldavAdminServerMirrorTargetSelect.vue';

/**
 * The control an administrator picks a destination with.
 *
 * <p>The assertions are deliberately on what reaches the SCREEN rather than on
 * what a function returned. The option list is a plain module and could be
 * pinned on its own; what a module test cannot see is a sentence that never
 * renders — and a control whose consequence lines are missing is exactly a
 * control that has stopped doing its job, while still passing every test about
 * its values.</p>
 */
describe('CaldavAdminServerMirrorTargetSelect', () => {

  // Vuetify registers these globally in the running app; here they render as
  // plain elements, which keeps their slot content — and therefore every
  // sentence asserted below — in the output.
  Vue.config.ignoredElements = [/^v-/];

  /**
   * Mounts the control with a $t that reads its receiver exactly as eXo's does.
   *
   * A translator torn off its instance must break this test, because that is
   * what once broke a drawer in this add-on while its module tests stayed
   * green.
   *
   * @param {Object} propsData the value and the stored value
   * @returns {Object} the wrapper
   */
  function mountControl(propsData) {
    return shallowMount(CaldavAdminServerMirrorTargetSelect, {
      propsData,
      mocks: {
        $t(key, args) {
          return args && `${this.$i18n.locale}:${key}(${args[0]})` || `${this.$i18n.locale}:${key}`;
        },
        $i18n: {locale: 'en'},
      },
    });
  }

  it('offers the two destinations, each with the one line of consequence it carries', () => {
    const wrapper = mountControl({value: 'DEDICATED_CALENDAR', storedValue: null});

    const text = wrapper.text();
    expect(text).toContain('caldav.admin.servers.mirrorTarget.dedicated.label');
    expect(text).toContain('caldav.admin.servers.mirrorTarget.dedicated.consequence');
    expect(text).toContain('caldav.admin.servers.mirrorTarget.main.label');
    expect(text).toContain('caldav.admin.servers.mirrorTarget.main.consequence');
    expect(wrapper.vm.options).toHaveLength(2);
  });

  it('offers no third destination, so putting one back has to be meant', () => {
    // "A calendar each user picks" was dropped by a product review. The
    // registry's enum still carries the value, so nothing under this control
    // stops the radio coming back; this is what stops it coming back unnoticed.
    const wrapper = mountControl({value: 'DEDICATED_CALENDAR', storedValue: null});

    const text = wrapper.text();
    expect(text).not.toContain('caldav.admin.servers.mirrorTarget.userChoice.label');
    expect(text).not.toContain('caldav.admin.servers.mirrorTarget.userChoice.consequence');
    expect(wrapper.vm.options.map(option => option.value)).toEqual(['DEDICATED_CALENDAR', 'MAIN_CALENDAR']);
  });

  it('states the condition on the field, where it cannot be read as a per-user admin control', () => {
    // In the section subtitle this sentence would imply an administrator can
    // decide per user, and there is no such control: a user turning meeting
    // copies off in their own settings is the only thing that takes them out.
    const wrapper = mountControl({value: 'DEDICATED_CALENDAR', storedValue: null});

    expect(wrapper.text()).toContain('caldav.admin.servers.mirrorTarget.appliesTo');
  });

  it('says nothing about moving copies while a server is being declared', () => {
    // A declaration has no copies to move, whatever destination is chosen.
    const wrapper = mountControl({value: 'MAIN_CALENDAR', storedValue: null});

    expect(wrapper.text()).not.toContain('caldav.admin.servers.mirrorTarget.moves');
  });

  it('says nothing about moving copies when an existing server keeps its destination', () => {
    const wrapper = mountControl({value: 'MAIN_CALENDAR', storedValue: 'MAIN_CALENDAR'});

    expect(wrapper.text()).not.toContain('caldav.admin.servers.mirrorTarget.moves');
  });

  it('says the copies will move, and that the old calendar is left behind, only on a real change', () => {
    const wrapper = mountControl({value: 'MAIN_CALENDAR', storedValue: 'DEDICATED_CALENDAR'});

    expect(wrapper.text()).toContain('caldav.admin.servers.mirrorTarget.moves');
  });

  it('treats a stored destination the registry never stated as the one it resolves to', () => {
    // A row saved before this control existed carries nothing. It resolves to
    // the dedicated calendar, so choosing that one is not a change and must not
    // announce a move that will not happen.
    const unchanged = mountControl({value: 'DEDICATED_CALENDAR', storedValue: 'DEDICATED_CALENDAR'});
    expect(unchanged.vm.changing).toBe(false);

    const changed = mountControl({value: 'MAIN_CALENDAR', storedValue: 'DEDICATED_CALENDAR'});
    expect(changed.vm.changing).toBe(true);
  });

  it('shows the dedicated calendar for a row stored as the destination we stopped offering', () => {
    // A row written while "a calendar each user picks" was still on offer, or
    // written by hand: MirrorTargetKind still accepts it. With no radio of its
    // own it would leave the group with nothing selected - an administrator
    // reads that as a setting that has been lost, and the first save would
    // state whatever they then clicked. It shows the dedicated calendar
    // instead, which is what a save from here would send.
    const wrapper = mountControl({value: 'USER_CHOICE', storedValue: 'USER_CHOICE'});

    expect(wrapper.vm.selected).toBe('DEDICATED_CALENDAR');
    expect(wrapper.find('v-radio-group').attributes('value')).toBe('DEDICATED_CALENDAR');
    // And it is not a change, so the drawer must not promise a move that the
    // save is not going to make.
    expect(wrapper.vm.changing).toBe(false);
    expect(wrapper.text()).not.toContain('caldav.admin.servers.mirrorTarget.moves');
  });

  it('announces a stored value and never a blank one', () => {
    const wrapper = mountControl({value: 'DEDICATED_CALENDAR', storedValue: 'DEDICATED_CALENDAR'});

    wrapper.vm.choose('MAIN_CALENDAR');
    wrapper.vm.choose(null);
    // A value the control no longer offers can only arrive from outside it, and
    // leaves as the one the registry resolves it to - never as itself.
    wrapper.vm.choose('USER_CHOICE');

    expect(wrapper.emitted().input).toEqual([
      ['MAIN_CALENDAR'],
      ['DEDICATED_CALENDAR'],
      ['DEDICATED_CALENDAR'],
    ]);
  });
});
