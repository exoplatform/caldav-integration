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

import CaldavAdminServerDrawer from '../../main/webapp/vue-app/caldav/components/admin/CaldavAdminServerDrawer.vue';
import CaldavAdminServerMirrorTargetSelect
  from '../../main/webapp/vue-app/caldav/components/admin/CaldavAdminServerMirrorTargetSelect.vue';

/**
 * The admin drawer, opened on a real registration.
 *
 * <p>This layer was missing, and its absence shipped a regression. The module
 * tests beside it pinned every decision the entries are built from - the
 * grouping, the patterns a tick writes, the plural - and all of them stayed
 * green while the drawer refused to open at all for the one server that had
 * anything to show. What broke was not a decision but the wiring between the
 * component and the module: `describeQuirk` was handed `this.$t`, a method torn
 * off its receiver, so the first entry threw
 * "Cannot read properties of undefined (reading '$i18n')". A server with no
 * observed behaviour mapped over nothing and opened fine, which is exactly why
 * a test that opens a drawer with an EMPTY list would have proved nothing.</p>
 *
 * <p>So the case here is deliberately the populated one, and the assertion is
 * deliberately that the entries reached the screen - not that some function
 * returned the right shape.</p>
 */
describe('CaldavAdminServerDrawer', () => {

  // Vuetify registers these globally in the running app; here they are rendered
  // as plain elements, which keeps their slot content — and therefore every
  // sentence this asserts on — in the output.
  Vue.config.ignoredElements = [/^v-/];

  /** A server the sweep has seen four behaviours on, as the REST layer sends it. */
  const bluemind = {
    id: 7,
    name: 'Bluemind',
    serverUrl: 'https://caldav.example.invalid/dav/',
    active: true,
    answerLinksInCopy: true,
    ignoredProperties: '',
    droppedProperties: '',
    omittedProperties: '',
    observedQuirks: [
      {quirkId: 'addsCompatibilityMarkers', properties: ['X-MICROSOFT-CDO-BUSYSTATUS', 'X-MOZ-LASTACK'],
        direction: 'ADDED', effect: 'TOLERATE', count: 3, excused: false, patterns: ['X-MICROSOFT-*', 'X-MOZ-*']},
      {quirkId: 'dropsConference', properties: ['CONFERENCE'], direction: 'DROPPED', effect: 'TOLERATE', count: 1,
        excused: false, patterns: ['CONFERENCE']},
      {quirkId: 'omitsSoloOrganizer', properties: ['SOLO-ORGANIZER'], direction: 'DROPPED', effect: 'OMIT', count: 1,
        excused: false, patterns: ['SOLO-ORGANIZER']},
      {quirkId: null, properties: ['X-BM-FOO'], direction: 'ADDED', effect: 'TOLERATE', count: 1, excused: false,
        patterns: ['X-BM-FOO']},
    ],
  };

  /**
   * Mounts the drawer with the platform's own pieces stubbed, and a $t that
   * reads its receiver exactly as eXo's does — which is the whole point: a
   * translator torn off its instance must break this test, because that is what
   * broke the drawer.
   *
   * @returns {Object} the wrapper
   */
  function mountDrawer(saved) {
    return shallowMount(CaldavAdminServerDrawer, {
      mocks: {
        $agendaCaldavService: {
          createCaldavServer: payload => {
            saved && saved.push({method: 'create', payload});
            return Promise.resolve(payload);
          },
          updateCaldavServer: payload => {
            saved && saved.push({method: 'update', payload});
            return Promise.resolve(payload);
          },
        },
        $t(key, args) {
          // Deliberately depends on `this`. eXo's $t reads this.$i18n, and a
          // reference passed without its receiver is what produced the live
          // TypeError.
          return args && `${this.$i18n.locale}:${key}(${args[0]})` || `${this.$i18n.locale}:${key}`;
        },
        $i18n: {locale: 'en'},
      },
      stubs: {
        'exo-drawer': {
          template: '<div><slot name="title"></slot><slot name="content"></slot><slot name="footer"></slot></div>',
          methods: {
            // The real drawer drives its own v-model; the content sits behind
            // it, so a stub that does not would render nothing and every
            // assertion below would pass against a blank page.
            open() {
              this.$emit('input', true);
            },
            close() {
              this.$emit('input', false);
            },
          },
        },
        'caldav-admin-server-image-input': true,
        // Only rendered on a declaration, and it has its own test file.
        'caldav-admin-server-preset-select': true,
        // The real one, not a stub. The destination control is where this
        // change lives, and the failure it has to survive - a translator torn
        // off its receiver - only shows when the component actually renders
        // inside the drawer that hosts it.
        'caldav-admin-server-mirror-target-select': CaldavAdminServerMirrorTargetSelect,
      },
    });
  }

  it('opens on a server that has observed behaviours, and renders one entry each', async () => {
    const wrapper = mountDrawer();

    wrapper.vm.open({...bluemind});
    await wrapper.vm.$nextTick();

    expect(wrapper.vm.observedQuirks).toHaveLength(4);
    const text = wrapper.text();
    expect(text).toContain('caldav.admin.servers.quirks.addsCompatibilityMarkers.label');
    expect(text).toContain('caldav.admin.servers.quirks.dropsConference.label');
    expect(text).toContain('caldav.admin.servers.quirks.omitsSoloOrganizer.label');
    expect(text).toContain('caldav.admin.servers.quirks.generic.added.label(X-BM-FOO)');
  });

  it('renders each entry with its cost and a count that agrees with itself', async () => {
    const wrapper = mountDrawer();

    wrapper.vm.open({...bluemind});
    await wrapper.vm.$nextTick();

    const text = wrapper.text();
    expect(text).toContain('caldav.admin.servers.quirks.addsCompatibilityMarkers.cost');
    expect(text).toContain('caldav.admin.servers.quirks.seen.many(3)');
    expect(text).toContain('caldav.admin.servers.quirks.seen.once');
  });

  it('says on the payload-changing entry, and only there, that it changes what eXo writes', async () => {
    const wrapper = mountDrawer();

    wrapper.vm.open({...bluemind});
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).toContain('caldav.admin.servers.quirks.changesWhatIsWritten');
    expect(wrapper.vm.observedQuirks.filter(quirk => quirk.changesWhatIsWritten)).toHaveLength(1);
  });

  it('keeps the same shape for a server that has shown nothing, and says what the emptiness means', async () => {
    // The Stalwart case. The section used to vanish entirely, and an
    // administrator could not tell whether the feature did not apply, was
    // unsupported, or simply had not happened yet - absence teaches nothing. It
    // is now always there, with wording saying that nothing seen is the good
    // outcome and not a pending one.
    const wrapper = mountDrawer();

    wrapper.vm.open({...bluemind, observedQuirks: []});
    await wrapper.vm.$nextTick();

    const text = wrapper.text();
    expect(text).toContain('caldav.admin.servers.copies.title');
    expect(text).toContain('caldav.admin.servers.url.reachabilityHint');
    expect(text).toContain('caldav.admin.servers.quirks.title');
    expect(text).toContain('caldav.admin.servers.quirks.none');
    expect(text).not.toContain('caldav.admin.servers.quirks.subtitle');
  });

  it('replaces the empty wording with the instruction once there is something to tick', async () => {
    const wrapper = mountDrawer();

    wrapper.vm.open({...bluemind});
    await wrapper.vm.$nextTick();

    const text = wrapper.text();
    expect(text).toContain('caldav.admin.servers.quirks.subtitle');
    expect(text).not.toContain('caldav.admin.servers.quirks.none');
  });

  it('writes the ticks back into the lists the server is saved with', async () => {
    // The round trip the drawer exists for, through the component rather than
    // through the module: tick the family and the payload entry, and the two
    // kinds of decision land in their own lists.
    const wrapper = mountDrawer();

    wrapper.vm.open({...bluemind});
    await wrapper.vm.$nextTick();
    wrapper.vm.observedQuirks.find(quirk => quirk.key === 'addsCompatibilityMarkers').excused = true;
    wrapper.vm.observedQuirks.find(quirk => quirk.key === 'omitsSoloOrganizer').excused = true;
    wrapper.vm.applyTicks();

    expect(wrapper.vm.server.ignoredProperties).toBe('X-MICROSOFT-*,X-MOZ-*');
    expect(wrapper.vm.server.omittedProperties).toBe('SOLO-ORGANIZER');
    expect(wrapper.vm.server.droppedProperties).toBe('');
  });
  it('offers the two destinations, each with its consequence, inside the drawer that hosts them', async () => {
    // Through the component, not through the module: what a module test cannot
    // see is a control that never renders at all.
    const wrapper = mountDrawer();

    wrapper.vm.open({...bluemind});
    await wrapper.vm.$nextTick();

    const text = wrapper.text();
    expect(text).toContain('caldav.admin.servers.mirrorTarget.appliesTo');
    expect(text).toContain('caldav.admin.servers.mirrorTarget.dedicated.consequence');
    expect(text).toContain('caldav.admin.servers.mirrorTarget.main.consequence');
    // And no third one. Dropped from the UI by a product review; the enum still
    // carries it, so this is the assertion that keeps it out of the drawer.
    expect(text).not.toContain('caldav.admin.servers.mirrorTarget.userChoice.label');
    expect(text).not.toContain('caldav.admin.servers.mirrorTarget.userChoice.consequence');
  });

  it('says the existing copies will move only once the destination actually changes', async () => {
    const wrapper = mountDrawer();

    wrapper.vm.open({...bluemind, mirrorTarget: 'DEDICATED_CALENDAR'});
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).not.toContain('caldav.admin.servers.mirrorTarget.moves');

    wrapper.vm.server.mirrorTarget = 'MAIN_CALENDAR';
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toContain('caldav.admin.servers.mirrorTarget.moves');
  });

  it('never announces a move on a declaration, whatever destination is chosen', async () => {
    const wrapper = mountDrawer();

    wrapper.vm.open();
    wrapper.vm.server.mirrorTarget = 'MAIN_CALENDAR';
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).not.toContain('caldav.admin.servers.mirrorTarget.moves');
  });

  it('sends a real destination on every edit, including a row that was stored without one', async () => {
    // THE regression this change can cause. CaldavServerStorage.updateServer
    // treats an absent mirrorTarget as "not stated" and keeps the stored value
    // - a guard that existed for exactly one reason: no drawer carried the
    // control. From the moment this one does, that guard protects nothing, and
    // a save that omitted the field would reset an administrator's destination
    // on an edit that had nothing to do with it. So the payload states it,
    // always.
    const saved = [];
    const wrapper = mountDrawer(saved);

    // A row as the registry sends it before anyone opened this control: no
    // mirrorTarget at all.
    wrapper.vm.open({...bluemind});
    await wrapper.vm.$nextTick();
    await wrapper.vm.saveServer();

    expect(saved).toHaveLength(1);
    expect(saved[0].method).toBe('update');
    expect(saved[0].payload.mirrorTarget).toBe('DEDICATED_CALENDAR');
  });

  it('sends the destination the administrator chose, not the one the row arrived with', async () => {
    const saved = [];
    const wrapper = mountDrawer(saved);

    wrapper.vm.open({...bluemind, mirrorTarget: 'DEDICATED_CALENDAR'});
    await wrapper.vm.$nextTick();
    wrapper.vm.server.mirrorTarget = 'MAIN_CALENDAR';
    await wrapper.vm.saveServer();

    expect(saved[0].payload.mirrorTarget).toBe('MAIN_CALENDAR');
  });

  it('opens a row stored as the destination we stopped offering on the dedicated calendar', async () => {
    // The row exists: the option was on offer until a product review dropped
    // it, and MirrorTargetKind still stores the value. The drawer must show a
    // real option rather than an empty radio group, and must not tell the
    // administrator their copies are about to move when they open the row and
    // save it unchanged.
    const saved = [];
    const wrapper = mountDrawer(saved);

    wrapper.vm.open({...bluemind, mirrorTarget: 'USER_CHOICE'});
    await wrapper.vm.$nextTick();

    expect(wrapper.vm.server.mirrorTarget).toBe('DEDICATED_CALENDAR');
    expect(wrapper.text()).not.toContain('caldav.admin.servers.mirrorTarget.moves');

    await wrapper.vm.saveServer();
    expect(saved[0].payload.mirrorTarget).toBe('DEDICATED_CALENDAR');
  });

  it('states a destination on a declaration too, rather than leaving the registry to guess', async () => {
    const saved = [];
    const wrapper = mountDrawer(saved);

    wrapper.vm.open();
    wrapper.vm.server.name = 'A server';
    wrapper.vm.server.serverUrl = 'https://caldav.example.invalid/dav/';
    await wrapper.vm.saveServer();

    expect(saved[0].method).toBe('create');
    expect(saved[0].payload.mirrorTarget).toBe('DEDICATED_CALENDAR');
  });
});
