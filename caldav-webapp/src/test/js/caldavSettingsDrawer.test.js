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

import CaldavSettingsDrawer from '../../main/webapp/vue-app/caldav/components/CaldavSettingsDrawer.vue';

/**
 * The connect drawer, and how many times one attempt reaches the server.
 *
 * <p>EXO-89806. Measured on the rig: five credential-bearing PROPFINDs for one
 * account in nineteen seconds, every one of them answered by a proxy standing
 * in front of an unreachable Stalwart. They were five presses of Connect, not
 * five stages of one connection — nothing between the button and the server
 * bounded them. That matters beyond tidiness: a CalDAV server may auto-ban a
 * source address after repeated failed requests, and such bans are persistent
 * and silent.</p>
 *
 * <p>The assertion is deliberately on the number of calls that leave the
 * component, because that is the quantity the ban counts.</p>
 */
describe('CaldavSettingsDrawer', () => {

  Vue.config.ignoredElements = [/^v-/];

  /**
   * Mounts the drawer with a verification that never settles, so a test can
   * press Connect again while the first attempt is genuinely in flight.
   *
   * @param {Array} probes collects one entry per call reaching the platform
   * @returns {Object} the wrapper
   */
  function mountDrawer(probes) {
    return shallowMount(CaldavSettingsDrawer, {
      propsData: {server: {serverId: 8, serverUrl: 'http://dav.example.invalid/dav/cal/{username}/', name: 'Stalwart'}},
      mocks: {
        $agendaCaldavService: {
          verifyCaldavAccount: (serverId, username, password) => {
            probes.push({serverId, username, password});
            // Never resolves: exactly the window a user presses into.
            return new Promise(() => {});
          },
          createCaldavSetting: () => Promise.resolve(),
        },
        $t(key) {
          return `${this.$i18n.locale}:${key}`;
        },
        $i18n: {locale: 'en'},
        $vuetify: {rtl: false},
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
            startLoading() {
              this.loadingCalled = true;
            },
            endLoading() {
              this.loadingCalled = false;
            },
          },
        },
      },
    });
  }

  it('sends the credentials once however many times Connect is pressed', async () => {
    const probes = [];
    const wrapper = mountDrawer(probes);
    wrapper.setData({account: 'alice@stalwart.local', password: 'AlicePass123!'});
    await Vue.nextTick();

    wrapper.vm.saveSettings();
    await Vue.nextTick();
    // Four more presses, at the rhythm the rig recorded — 2 s, 3 s, 3 s, 11 s
    // apart — while the first attempt is still unanswered.
    wrapper.vm.saveSettings();
    wrapper.vm.saveSettings();
    wrapper.vm.saveSettings();
    wrapper.vm.saveSettings();
    await Vue.nextTick();

    expect(probes).toHaveLength(1);
  });

  it('refuses the Connect button itself while an attempt is unanswered', async () => {
    // Not only the handler. Vuetify's v-btn renders a spinner for `loading`
    // but writes `disabled` from `disabled` alone, so a merely-loading button
    // is still clickable — which is how the five presses got through.
    const wrapper = mountDrawer([]);
    wrapper.setData({account: 'alice@stalwart.local', password: 'AlicePass123!'});
    await Vue.nextTick();

    expect(wrapper.vm.disableConnectButton).toBe(false);

    wrapper.vm.saveSettings();
    await Vue.nextTick();

    expect(wrapper.vm.disableConnectButton).toBe(true);
  });

  it('still refuses a username too short to be one', async () => {
    // The guard read `this.account < 3` — a string against a number, which is
    // NaN, which is false, so the clause never fired and the error message
    // beside it contradicted the button.
    const wrapper = mountDrawer([]);
    wrapper.setData({account: 'ab', password: 'AlicePass123!'});
    await Vue.nextTick();

    expect(wrapper.vm.disableConnectButton).toBe(true);
  });
});
