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
import fs from 'fs';
import path from 'path';

import {connectorsBelongOnThisPage} from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';

/**
 * The CalDAV connect icon does not belong on a space's agenda (EXO-90383).
 *
 * <p>The plug agenda draws in the toolbar connects the <b>viewer's</b> calendar
 * account, and a space's agenda shows the space's calendars. Agenda decides
 * what to draw from the descriptors this module registers
 * (AgendaConnectToRemoteButton: no connector, no button), so the fix is to
 * register none inside a space — which is also why this pin is on the
 * registration, not on the icon.</p>
 */
describe('The CalDAV connectors and the page they are on', () => {

  afterEach(() => {
    delete global.eXo;
  });

  it('belong on the personal agenda', () => {
    global.eXo = {env: {portal: {spaceId: null, userName: 'eric'}}};

    expect(connectorsBelongOnThisPage()).toBe(true);
  });

  it('do not belong on a space agenda', () => {
    global.eXo = {env: {portal: {spaceId: '10', userName: 'eric'}}};

    expect(connectorsBelongOnThisPage()).toBe(false);
  });

  it('belong on a page that says nothing about a space', () => {
    expect(connectorsBelongOnThisPage()).toBe(true);
  });

  /**
   * Every path that registers a descriptor is guarded: the declared servers,
   * the legacy fallback when none is active, and the legacy fallback of the
   * failure branch. That last one is the one a rewrite forgets, and forgetting
   * it puts the plug back in every space.
   */
  it('is asked on every path that registers a descriptor', () => {
    const main = fs.readFileSync(path.resolve(__dirname, '../../main/webapp/vue-app/caldav/main.js'), 'utf8');
    const registrations = main.match(/registerExtension\('agenda', 'connectors'/g) || [];
    const answered = main.slice(main.indexOf('.then(([servers, requirements]) => {'), main.indexOf('.finally('));
    const guard = answered.indexOf('if (!connectorsBelongOnThisPage()) {');
    const inTheAnswer = (answered.match(/registerExtension\('agenda', 'connectors'/g) || []).length;

    expect(registrations).toHaveLength(3);
    expect(guard).toBeGreaterThan(-1);
    expect(answered.slice(0, guard)).not.toContain('registerExtension');
    expect(inTheAnswer).toBe(3);
    expect(main).toMatch(/if \(!connectorsBelongOnThisPage\(\)\) \{\s*\n\s*return null;/);
    expect(main).toMatch(/\.catch\(\(\) => connectorsBelongOnThisPage\(\)\s*\n\s*&& extensionRegistry\.registerExtension\('agenda', 'connectors'/);
  });

});
