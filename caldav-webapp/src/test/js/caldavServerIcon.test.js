/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
import Vue from 'vue';
import {mount} from '@vue/test-utils';

import CaldavServerIcon from '../../main/webapp/vue-app/caldav/components/admin/CaldavServerIcon.vue';

/**
 * What the three administration surfaces actually draw for a server's identity
 * (EXO-90393): the admin list, the drawer preview and the managed-mode drawer
 * all mount this component, and all three now fall back to a calendar glyph
 * rather than the packaged CalDAV logo.
 *
 * <p>The rule itself is pinned as a pure function in
 * <code>serverIconIdentity.test.js</code>. What those tests cannot see is
 * whether the component renders what the rule answers: the template read
 * <code>{{ icon }}</code> — the raw prop, null for a server with nothing
 * configured — where it now reads <code>{{ iconName }}</code>. Reverting that
 * one interpolation leaves every function-level test green and every admin
 * screen showing a blank 40px square, which is the gap this file closes.</p>
 *
 * <p><b>Harness.</b> Vuetify is not part of this build, so its tags are left as
 * plain elements; the pins are on the glyph and the image src this component
 * emits.</p>
 */
describe('The server identity icon', () => {

  Vue.config.ignoredElements = [/^v-/];

  /**
   * Mounts the icon over what a server has configured.
   *
   * @param {Object} props the imageUrl / icon the server carries
   * @returns {Object} the wrapper
   */
  function mountWith(props) {
    return mount(CaldavServerIcon, {propsData: props || {}});
  }

  it('draws the packaged calendar glyph for a server with nothing configured', () => {
    const wrapper = mountWith({});

    expect(wrapper.find('v-icon').text()).toBe('fas fa-calendar-alt');
    expect(wrapper.find('v-img').exists()).toBe(false);
  });

  it('draws the glyph the administrator chose, rather than the default', () => {
    const wrapper = mountWith({icon: 'fa-server'});

    expect(wrapper.find('v-icon').text()).toBe('fa-server');
    expect(wrapper.find('v-img').exists()).toBe(false);
  });

  it('draws the uploaded image and no glyph, whatever icon is also set', () => {
    const wrapper = mountWith({imageUrl: '/caldav/rest/servers/6/image?v=1', icon: 'fa-server'});

    expect(wrapper.find('v-img').attributes('src')).toBe('/caldav/rest/servers/6/image?v=1');
    expect(wrapper.find('v-icon').exists()).toBe(false);
  });

  /**
   * The packaged CalDAV logo is no longer anybody's default: it identifies a
   * server only where an administrator uploaded it.
   */
  it('never falls back to the packaged CalDAV image', () => {
    expect(mountWith({}).html()).not.toContain('caldav.png');
    expect(mountWith({icon: 'fa-server'}).html()).not.toContain('caldav.png');
  });

  it('colours the glyph the way the surface asks, the platform default when it asks nothing', () => {
    expect(mountWith({}).find('v-icon').classes()).toContain('icon-default-color');
    expect(mountWith({iconClass: 'white--text'}).find('v-icon').classes()).toContain('white--text');
  });

});
