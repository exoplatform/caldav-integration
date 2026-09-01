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
import CaldavAdminManagedModeDrawer from '../../main/webapp/vue-app/caldav/components/admin/CaldavAdminManagedModeDrawer.vue';
import CaldavAdminServersSection from '../../main/webapp/vue-app/caldav/components/admin/CaldavAdminServersSection.vue';

jest.mock('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js', () => ({
  getManagedMode: jest.fn(),
  saveManagedMode: jest.fn(),
  clearManagedMode: jest.fn(),
  getSyncTuning: jest.fn(),
  getMirrorReports: jest.fn(),
}));

/**
 * Managed mode's admin surface (EXO-89900).
 *
 * <p>The whole commit story lives here, and it is the part a regression would
 * break silently: flipping the switch on stores NOTHING — it asks the
 * administrator which server — and only Apply turns the mode on. Getting that
 * wrong does not throw: it leaves an instance whose switch says managed and
 * whose setting says otherwise, which nobody can diagnose by looking at the
 * screen.</p>
 */
describe('CalDAV managed mode, admin side', () => {

  Vue.config.ignoredElements = [/^v-/];

  /** Two active candidates and one deactivated row that must never be offered. */
  const servers = [
    {id: 5, name: 'Stalwart', description: null, serverUrl: 'http://stalwart.example.org:8888/dav/', active: true},
    {id: 6, name: 'Bluemind', description: 'Team server', serverUrl: 'https://bluemind.example.org/dav/', active: true},
    {id: 7, name: 'Retired', description: null, serverUrl: 'https://old.example.org/dav/', active: false},
  ];

  /**
   * Mounts the drawer with a $t that reads its arguments, as eXo's does.
   *
   * @returns {Object} the wrapper
   */
  function mountDrawer() {
    return shallowMount(CaldavAdminManagedModeDrawer, {
      mocks: {
        $t: (key, args) => args && `${key}(${args[0]})` || key,
        $te: () => false,
      },
      stubs: {
        'exo-drawer': {
          template: '<div><slot name="title"></slot><slot name="content"></slot><slot name="footer"></slot></div>',
          methods: {
            open() {
              this.$emit('input', true);
            },
            close() {
              this.$emit('closed');
            },
          },
        },
        'caldav-server-icon': true,
        // Rendered rather than ignored, because what this drawer owes the
        // administrator is the server's NAME at the moment they commit: a
        // radio whose label slot never renders would let that regress unseen.
        'v-radio-group': {template: '<div><slot></slot></div>'},
        'v-radio': {template: '<div><slot name="label"></slot></div>'},
      },
    });
  }

  /**
   * Mounts the section, whose row carries the switch.
   *
   * @returns {Object} the wrapper
   */
  function mountSection() {
    const wrapper = shallowMount(CaldavAdminServersSection, {
      mocks: {
        $t: (key, args) => args && `${key}(${args[0]})` || key,
        $agendaCaldavService: {
          getCaldavServers: () => Promise.resolve(servers),
        },
      },
      stubs: {
        'caldav-admin-server-list': true,
        'caldav-admin-server-drawer': true,
        'caldav-admin-sync-drawer': true,
        'caldav-admin-managed-mode-drawer': true,
      },
    });
    // Spied after mounting rather than replaced: $root is set on the instance
    // itself, so a mock never reaches it, and created() has already run its own
    // wiring through the real bus by the time a spy could be installed.
    wrapper.rootEmit = jest.spyOn(wrapper.vm.$root, '$emit');
    return wrapper;
  }

  beforeEach(() => {
    caldavConnectorService.getManagedMode.mockReset();
    caldavConnectorService.saveManagedMode.mockReset();
    caldavConnectorService.clearManagedMode.mockReset();
    caldavConnectorService.getSyncTuning.mockReset();
    caldavConnectorService.getMirrorReports.mockReset();
    caldavConnectorService.getManagedMode.mockResolvedValue({serverId: null, serverName: null, managedForMe: false});
    caldavConnectorService.getSyncTuning.mockResolvedValue({});
  });

  /**
   * The commit story. Flipping the switch on opens the drawer and stores
   * nothing: there is no honest way to turn managed mode on without naming a
   * server, and the switch cannot name one.
   */
  it('stores nothing when the switch is flipped on', async () => {
    const wrapper = mountSection();
    await wrapper.vm.$nextTick();

    wrapper.vm.managedOn = true;
    await wrapper.vm.flipManagedMode(true);

    expect(caldavConnectorService.saveManagedMode).not.toHaveBeenCalled();
    expect(wrapper.rootEmit).toHaveBeenCalledWith('open-caldav-managed-mode-drawer', expect.anything());
    expect(wrapper.vm.managed.serverId).toBeNull();
  });

  /**
   * And a drawer closed without applying puts the switch back where the
   * setting is — otherwise the row shows managed mode as on while the platform
   * says off, which is the one state an administrator cannot recover from by
   * looking.
   */
  it('reverts the switch when the drawer is closed without applying', async () => {
    const wrapper = mountSection();
    await wrapper.vm.$nextTick();
    wrapper.vm.managedOn = true;

    wrapper.vm.managedCancelled();

    expect(wrapper.vm.managedOn).toBe(false);
  });

  /**
   * Cancelling a drawer opened to CHANGE the managed server does not switch
   * the mode off: the switch goes back to what is stored, not to false.
   */
  it('reverts to what is stored, not to off', async () => {
    caldavConnectorService.getManagedMode.mockResolvedValue({serverId: 6, serverName: 'Bluemind', managedForMe: true});
    const wrapper = mountSection();
    await wrapper.vm.$nextTick();
    await wrapper.vm.$nextTick();

    expect(wrapper.vm.managedOn).toBe(true);
    wrapper.vm.managedCancelled();

    expect(wrapper.vm.managedOn).toBe(true);
  });

  /**
   * Applying is what turns the mode on, and the row then shows what the
   * platform answered rather than what was clicked.
   */
  it('turns the mode on only once Apply succeeded', async () => {
    const wrapper = mountSection();
    await wrapper.vm.$nextTick();

    wrapper.vm.managedApplied({serverId: 6, serverName: 'Bluemind', managedForMe: true});

    expect(wrapper.vm.managedOn).toBe(true);
    expect(wrapper.vm.managedSummary).toBe('caldav.admin.managed.on(Bluemind)');
  });

  /**
   * Flipping off is immediate and needs no confirmation: it gives an
   * affordance back rather than taking one away, and nothing is severed by it.
   */
  it('switches off immediately, with one call and no dialog', async () => {
    caldavConnectorService.getManagedMode.mockResolvedValue({serverId: 6, serverName: 'Bluemind', managedForMe: true});
    caldavConnectorService.clearManagedMode.mockResolvedValue({serverId: null, serverName: null, managedForMe: false});
    const wrapper = mountSection();
    await wrapper.vm.$nextTick();

    await wrapper.vm.flipManagedMode(false);

    expect(caldavConnectorService.clearManagedMode).toHaveBeenCalledTimes(1);
    expect(wrapper.vm.managed.serverId).toBeNull();
    expect(wrapper.rootEmit).toHaveBeenCalledWith('alert-message', 'caldav.admin.managed.cleared', 'success');
  });

  /**
   * A failed switch-off puts the switch back on rather than leaving the row
   * claiming a state the platform never accepted.
   */
  it('puts the switch back when switching off failed', async () => {
    jest.spyOn(console, 'error').mockImplementation(() => {});
    caldavConnectorService.getManagedMode.mockResolvedValue({serverId: 6, serverName: 'Bluemind', managedForMe: true});
    caldavConnectorService.clearManagedMode.mockRejectedValue(new Error('down'));
    const wrapper = mountSection();
    await wrapper.vm.$nextTick();
    wrapper.vm.managedOn = false;

    await wrapper.vm.flipManagedMode(false);

    expect(wrapper.vm.managedOn).toBe(true);
    console.error.mockRestore();
  });

  /**
   * The summary says which of the three states the instance is in. "Nothing
   * declared yet" is not the same answer as "users connect their own account":
   * there is nothing for them to connect to.
   */
  it('says which of the three states the instance is in', async () => {
    const wrapper = mountSection();
    await wrapper.vm.$nextTick();
    await wrapper.vm.$nextTick();

    expect(wrapper.vm.managedSummary).toBe('caldav.admin.managed.off');

    wrapper.setData({servers: []});
    await wrapper.vm.$nextTick();
    expect(wrapper.vm.managedSummary).toBe('caldav.admin.managed.noServers');

    wrapper.setData({managed: {serverId: 6, serverName: 'Bluemind', managedForMe: true}});
    await wrapper.vm.$nextTick();
    expect(wrapper.vm.managedSummary).toBe('caldav.admin.managed.on(Bluemind)');
  });

  /**
   * Only ACTIVE servers are candidates. A deactivated row is exactly the one
   * nobody can connect to, so it is absent rather than greyed — greying it
   * invites "why not this one?" on a screen whose answer is a different
   * screen.
   */
  it('offers active servers only', async () => {
    const wrapper = mountDrawer();

    wrapper.vm.open({servers, managed: null});
    await wrapper.vm.$nextTick();

    expect(wrapper.vm.activeServers.map(server => server.id)).toEqual([5, 6]);
  });

  /**
   * Exactly one candidate is preselected — one click to Apply — but the row is
   * still rendered and named. Turning managed mode on removes affordances from
   * every user's screen, and the administrator should read the server's name
   * at the moment they commit to that.
   */
  it('preselects the only candidate without skipping the choice', async () => {
    const wrapper = mountDrawer();

    wrapper.vm.open({servers: [servers[1], servers[2]], managed: null});
    await wrapper.vm.$nextTick();

    expect(wrapper.vm.selectedServerId).toBe(6);
    expect(wrapper.text()).toContain('Bluemind');
  });

  /**
   * With no candidate the drawer says so and offers the Add button. A disabled
   * switch with a tooltip would hide the feature from the administrator who
   * has not declared a server yet; this does not.
   */
  it('says why there is nothing to choose, and offers the way out', async () => {
    const wrapper = mountDrawer();

    wrapper.vm.open({servers: [servers[2]], managed: null});
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).toContain('caldav.admin.managed.drawer.noServers');
    expect(wrapper.text()).toContain('caldav.admin.servers.add');
    expect(wrapper.vm.selectedServerId).toBeNull();
  });

  /**
   * Apply is what stores the choice, and a cancelled drawer tells the row to
   * revert.
   */
  it('emits saved on Apply and cancelled on a close that stored nothing', async () => {
    caldavConnectorService.saveManagedMode.mockResolvedValue({serverId: 6, serverName: 'Bluemind', managedForMe: true});
    const wrapper = mountDrawer();
    wrapper.vm.open({servers, managed: null});
    wrapper.vm.selectedServerId = 6;

    await wrapper.vm.apply();

    expect(caldavConnectorService.saveManagedMode).toHaveBeenCalledWith(6);
    expect(wrapper.emitted().saved).toBeTruthy();
    expect(wrapper.emitted().cancelled).toBeFalsy();

    const cancelled = mountDrawer();
    cancelled.vm.open({servers, managed: null});
    cancelled.vm.close();

    expect(caldavConnectorService.saveManagedMode).toHaveBeenCalledTimes(1);
    expect(cancelled.emitted().cancelled).toBeTruthy();
  });

  /**
   * A refusal keeps the drawer open on the choice and carries its reason. The
   * body of a 400 is the message code, so the administrator is told which rule
   * was hit rather than that something went wrong.
   */
  it('keeps the drawer open on a refusal, carrying its reason', async () => {
    caldavConnectorService.saveManagedMode.mockRejectedValue(new Error('caldav.managed.serverNotEligible'));
    const wrapper = mountDrawer();
    wrapper.vm.open({servers, managed: null});
    wrapper.vm.selectedServerId = 6;

    await wrapper.vm.apply();

    expect(wrapper.vm.errorMessage).toBe('caldav.admin.managed.saveFailed');
    expect(wrapper.emitted().saved).toBeFalsy();
    expect(wrapper.vm.applied).toBe(false);
  });
});
