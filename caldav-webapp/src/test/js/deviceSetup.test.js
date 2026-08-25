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
import {deviceCaldavUrl} from '../../main/webapp/vue-app/caldav/js/deviceSetup.js';

/**
 * The URL the drawer hands to a phone. A wrong answer here is silent — the
 * page renders, the user types the URL, and their device just never finds a
 * calendar — so what the backend does to the configured URL when it builds
 * its own endpoint (substitute every {username}, percent-encoded, and
 * nothing else) is pinned as what the drawer publishes.
 */
describe('deviceCaldavUrl', () => {

  it('substitutes the {username} placeholder — the Stalwart seed shape', () => {
    expect(deviceCaldavUrl('http://mail.example.com:8888/dav/cal/{username}/', 'jdoe'))
      .toBe('http://mail.example.com:8888/dav/cal/jdoe/');
  });

  it('substitutes every occurrence, like the backend replace does', () => {
    expect(deviceCaldavUrl('https://dav.example.com/{username}/cal/{username}/', 'jdoe'))
      .toBe('https://dav.example.com/jdoe/cal/jdoe/');
  });

  it('percent-encodes the username so it stays one path segment', () => {
    expect(deviceCaldavUrl('https://dav.example.com/cal/{username}/', 'jdoe@example.com'))
      .toBe('https://dav.example.com/cal/jdoe%40example.com/');
    expect(deviceCaldavUrl('https://dav.example.com/cal/{username}/', 'a/b'))
      .toBe('https://dav.example.com/cal/a%2Fb/');
  });

  it('returns a placeholder-free URL untouched — the BlueMind/declared shape', () => {
    expect(deviceCaldavUrl('https://caldav.example.com/dav/', 'jdoe'))
      .toBe('https://caldav.example.com/dav/');
  });

  it('keeps the configured path and trailing slash as they are — devices run their own discovery from it', () => {
    expect(deviceCaldavUrl('https://caldav.example.com/dav', 'jdoe'))
      .toBe('https://caldav.example.com/dav');
  });

  it('trims the configured URL and the username, like the backend endpoint does', () => {
    expect(deviceCaldavUrl('  https://dav.example.com/cal/{username}/  ', ' jdoe '))
      .toBe('https://dav.example.com/cal/jdoe/');
  });

  it('answers nothing when there is no URL to point a device at', () => {
    expect(deviceCaldavUrl(null, 'jdoe')).toBe('');
    expect(deviceCaldavUrl('', 'jdoe')).toBe('');
    expect(deviceCaldavUrl('   ', 'jdoe')).toBe('');
  });

  it('answers nothing rather than a URL still holding {username} when the account is unknown', () => {
    expect(deviceCaldavUrl('https://dav.example.com/cal/{username}/', null)).toBe('');
    expect(deviceCaldavUrl('https://dav.example.com/cal/{username}/', '')).toBe('');
    expect(deviceCaldavUrl('https://dav.example.com/cal/{username}/', '  ')).toBe('');
  });
});
