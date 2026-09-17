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
// jest hoists the factory above the imports, so the doubles it closes over must
// carry the `mock` prefix - the only names it allows through.
const mockConnectThroughProvider = jest.fn();
const mockGetCaldavSetting = jest.fn();

jest.mock('../../main/webapp/vue-app/caldav/js/agendaCaldavService.js', () => ({
  connectThroughProvider: (...args) => mockConnectThroughProvider(...args),
  getCaldavSetting: (...args) => mockGetCaldavSetting(...args),
}));

import {createCaldavConnector, createLegacyCaldavConnector} from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';

/**
 * A connector whose provider asks the user for nothing must connect in one
 * click: no drawer, no form. What it must NOT do is connect silently when
 * nobody could say whether the user has something to supply — an absent
 * requirement, an unnamed provider, a failed fetch. Those all send the user to
 * the drawer, which is the behaviour every connector had before this existed.
 */
describe('one-click connect', () => {

  const server = {
    id: 5,
    providerName: 'agenda.caldavCalendar',
    name: 'BlueMind',
    serverUrl: 'https://bm.example.org/dav/',
    active: true,
    authProviderName: 'bluemind-sudo',
  };

  beforeEach(() => {
    mockConnectThroughProvider.mockReset();
    mockGetCaldavSetting.mockReset();
    document.dispatchEvent = jest.fn();
  });

  it('connects without opening the drawer and resolves with the account the provider named', async () => {
    mockConnectThroughProvider.mockResolvedValue({result: 'ok', status: 207});
    mockGetCaldavSetting.mockResolvedValue({username: 'eric@bm.example.org'});

    const connector = createCaldavConnector(server, 0, false, {'bluemind-sudo': false});

    expect(connector.requiresUserAction).toBe(false);
    await expect(connector.connect()).resolves.toBe('eric@bm.example.org');
    expect(mockConnectThroughProvider).toHaveBeenCalledWith(5);
    // The drawer is what asks for credentials; nothing must have asked for it.
    expect(document.dispatchEvent).not.toHaveBeenCalled();
  });

  it('rejects with the server verdict rather than reporting a connection', async () => {
    mockConnectThroughProvider.mockResolvedValue({result: 'caldav.error.credentials', status: 401});

    const connector = createCaldavConnector(server, 0, false, {'bluemind-sudo': false});

    await expect(connector.connect()).rejects.toBe('caldav.error.credentials');
    expect(mockGetCaldavSetting).not.toHaveBeenCalled();
  });

  it('opens the drawer whenever nobody said the provider asks for nothing', () => {
    // A provider declaring it asks, an unnamed provider, requirements that could
    // not be fetched: three ways to know nothing, one behaviour.
    expect(createCaldavConnector(server, 0, false, {'bluemind-sudo': true}).requiresUserAction).toBe(true);
    expect(createCaldavConnector(server, 0, false, {}).requiresUserAction).toBe(true);
    expect(createCaldavConnector(server, 0, false, null).requiresUserAction).toBe(true);
    expect(createCaldavConnector({...server, authProviderName: null}, 0, false, {'bluemind-sudo': false})
      .requiresUserAction).toBe(true);

    createCaldavConnector(server, 0, false, {}).connect();

    expect(mockConnectThroughProvider).not.toHaveBeenCalled();
    expect(document.dispatchEvent).toHaveBeenCalled();
  });

  /**
   * The legacy connector never went through the factory and carries no such flag
   * at all. It must still open its drawer - and only a strict comparison against
   * false says so: a truthiness test reads `undefined` as "asks nothing" and would
   * connect, in one click, a connector nobody configured a provider for.
   */
  it('opens the drawer for the legacy connector, which carries no flag', () => {
    const legacy = createLegacyCaldavConnector(false);

    expect(legacy.requiresUserAction).toBeUndefined();

    legacy.connect();

    expect(mockConnectThroughProvider).not.toHaveBeenCalled();
    expect(document.dispatchEvent).toHaveBeenCalled();
  });
});
