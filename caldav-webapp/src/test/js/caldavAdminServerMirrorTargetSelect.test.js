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

  it('offers the three destinations, each with the one line of consequence it carries', () => {
    const wrapper = mountControl({value: 'DEDICATED_CALENDAR', storedValue: null});

    const text = wrapper.text();
    expect(text).toContain('caldav.admin.servers.mirrorTarget.dedicated.label');
    expect(text).toContain('caldav.admin.servers.mirrorTarget.dedicated.consequence');
    expect(text).toContain('caldav.admin.servers.mirrorTarget.main.label');
    expect(text).toContain('caldav.admin.servers.mirrorTarget.main.consequence');
    expect(text).toContain('caldav.admin.servers.mirrorTarget.userChoice.label');
    expect(text).toContain('caldav.admin.servers.mirrorTarget.userChoice.consequence');
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

    const changed = mountControl({value: 'USER_CHOICE', storedValue: 'DEDICATED_CALENDAR'});
    expect(changed.vm.changing).toBe(true);
  });

  it('announces a stored value and never a blank one', () => {
    const wrapper = mountControl({value: 'DEDICATED_CALENDAR', storedValue: 'DEDICATED_CALENDAR'});

    wrapper.vm.choose('USER_CHOICE');
    wrapper.vm.choose(null);

    expect(wrapper.emitted().input).toEqual([['USER_CHOICE'], ['DEDICATED_CALENDAR']]);
  });
});
