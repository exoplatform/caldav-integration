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
import caldavConnector, {createCaldavConnector} from '../../main/webapp/vue-app/caldav/caldav-connector/caldavConnector.js';

/**
 * EXO-90357: agenda's own Share drawer words where a share was also
 * delivered by asking each connector for its `channelLabel`. This connector
 * answers its server's host for a delivery its own server made, and nothing
 * for another channel — a wrong claim would put one server's name on
 * another's share.
 */
describe('the channel label of a CalDAV delivery', () => {

  const STALWART = createCaldavConnector({id: 1, providerName: 'stalwart', name: 'Stalwart', serverUrl: 'https://mail.example.org/dav/'}, 0, false);

  const BLUEMIND = createCaldavConnector({id: 2, providerName: 'bluemind', name: 'BlueMind', serverUrl: 'https://bm.example.org'}, 1, false);

  it('names its own server by host for a delivery that server made', () => {
    expect(STALWART.channelLabel('caldav:1')).toBe('mail.example.org');
    expect(BLUEMIND.channelLabel('caldav:2')).toBe('bm.example.org');
  });

  it('claims nothing for another server, another channel or no channel', () => {
    expect(STALWART.channelLabel('caldav:2')).toBe('');
    expect(STALWART.channelLabel('matrix:1')).toBe('');
    expect(STALWART.channelLabel('')).toBe('');
    expect(STALWART.channelLabel(undefined)).toBe('');
  });

  it('claims nothing from the base descriptor, which serves no server of its own', () => {
    expect(caldavConnector.channelLabel('caldav:1')).toBe('');
  });

  it('no longer offers a Share of its own on the calendar menu: agenda owns the share', () => {
    expect(caldavConnector.calendarActions).toBeUndefined();
    expect(caldavConnector.runCalendarAction).toBeUndefined();
  });
});
