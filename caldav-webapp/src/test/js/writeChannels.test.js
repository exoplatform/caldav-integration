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
import {
  WRITE_CHANNEL_BLUEMIND_IMPORT,
  WRITE_CHANNEL_CALDAV,
  offersWriteChannel,
  writeChannelOf,
  writeChannelToSave,
} from '../../main/webapp/vue-app/caldav/js/writeChannels.js';
import {presetValues} from '../../main/webapp/vue-app/caldav/js/serverPresets.js';

/**
 * The write channel is a BlueMind-only choice (EXO-90307, PO decision of
 * 2026-09-16): the drawer offers the radio to a BlueMind registration, and a
 * save for anything else states CalDAV explicitly rather than omitting the
 * key, which would keep a stale stored channel.
 */
describe('offersWriteChannel', () => {

  it('offers the radio when the name says BlueMind, the seed spelling and the preset spelling alike', () => {
    expect(offersWriteChannel({name: 'Bluemind', writeChannel: WRITE_CHANNEL_CALDAV})).toBe(true);
    expect(offersWriteChannel({name: 'BlueMind', writeChannel: null})).toBe(true);
    expect(offersWriteChannel({name: 'Our BLUEMIND at Lyon'})).toBe(true);
  });

  it('hides it for Stalwart and for a server nobody has characterised', () => {
    expect(offersWriteChannel({name: 'Stalwart', writeChannel: WRITE_CHANNEL_CALDAV})).toBe(false);
    expect(offersWriteChannel({name: '', writeChannel: null})).toBe(false);
    expect(offersWriteChannel(null)).toBe(false);
  });

  it('keeps it visible for a row already on the import channel whatever its name, so a save cannot reset it unseen', () => {
    expect(offersWriteChannel({name: 'Mail server', writeChannel: WRITE_CHANNEL_BLUEMIND_IMPORT})).toBe(true);
  });
});

describe('writeChannelToSave', () => {

  it('states the form value where the radio is offered and CalDAV explicitly everywhere else', () => {
    expect(writeChannelToSave({name: 'BlueMind', writeChannel: WRITE_CHANNEL_BLUEMIND_IMPORT})).toBe(WRITE_CHANNEL_BLUEMIND_IMPORT);
    expect(writeChannelToSave({name: 'BlueMind', writeChannel: WRITE_CHANNEL_CALDAV})).toBe(WRITE_CHANNEL_CALDAV);
    expect(writeChannelToSave({name: 'Stalwart', writeChannel: null})).toBe(WRITE_CHANNEL_CALDAV);
    expect(writeChannelToSave({name: 'Stalwart', writeChannel: WRITE_CHANNEL_CALDAV})).toBe(WRITE_CHANNEL_CALDAV);
    // A non-BlueMind row already on the import channel keeps the radio and
    // states what the radio shows: the registry refuses it with a code the
    // administrator can read, which beats a reset nobody sees.
    expect(writeChannelToSave({name: 'Stalwart', writeChannel: WRITE_CHANNEL_BLUEMIND_IMPORT})).toBe(WRITE_CHANNEL_BLUEMIND_IMPORT);
  });
});

describe('the presets and the channel', () => {

  it('BlueMind chooses the import channel, Stalwart and the uncharacterised server state CalDAV rather than omitting the key', () => {
    expect(presetValues('bluemind').writeChannel).toBe(WRITE_CHANNEL_BLUEMIND_IMPORT);
    expect(presetValues('stalwart').writeChannel).toBe(WRITE_CHANNEL_CALDAV);
    expect(presetValues('other').writeChannel).toBe(WRITE_CHANNEL_CALDAV);
    expect(Object.prototype.hasOwnProperty.call(presetValues('other'), 'writeChannel')).toBe(true);
  });

  it('reads any spelling into an offered value and nothing else', () => {
    expect(writeChannelOf(' bluemind_import ')).toBe(WRITE_CHANNEL_BLUEMIND_IMPORT);
    expect(writeChannelOf('SOMETHING_ELSE')).toBe(WRITE_CHANNEL_CALDAV);
    expect(writeChannelOf(null)).toBe(WRITE_CHANNEL_CALDAV);
  });
});
