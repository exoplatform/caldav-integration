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
import {resolveServerIcon, resolveServerImage, DEFAULT_SERVER_ICON, DEFAULT_SERVER_IMAGE} from '../../main/webapp/vue-app/caldav/js/serverIconIdentity.js';

/**
 * The identity rule every surface shares: uploaded image, else the
 * admin-chosen font icon, else — since EXO-90393 — a packaged calendar glyph.
 * The admin drawer preview once broke ranks by showing a placeholder glyph for
 * a server that had nothing persisted while the list and the connect drawer
 * showed the packaged CalDAV image for the same server: two identities on one
 * screen. The default has moved, the single rule has not — these tests pin it
 * in its two halves, one answering "which image", the other "which glyph".
 */
describe('resolveServerImage', () => {

  it('renders the uploaded image when one exists, even when a font icon is also set', () => {
    expect(resolveServerImage('/rest/images/42')).toBe('/rest/images/42');
  });

  it('renders a font icon (null image) whenever nothing was uploaded', () => {
    expect(resolveServerImage(null)).toBeNull();
    expect(resolveServerImage('')).toBeNull();
    expect(resolveServerImage(undefined)).toBeNull();
  });

  it('no longer falls back to the packaged CalDAV image, which named a protocol rather than a calendar', () => {
    expect(resolveServerImage(null)).not.toBe(DEFAULT_SERVER_IMAGE);
  });
});

describe('resolveServerIcon', () => {

  it('renders the glyph the administrator chose', () => {
    expect(resolveServerIcon('fa-server')).toBe('fa-server');
  });

  it('falls back to the packaged calendar glyph when the administrator chose none', () => {
    expect(resolveServerIcon(null)).toBe(DEFAULT_SERVER_ICON);
    expect(resolveServerIcon('')).toBe(DEFAULT_SERVER_ICON);
    expect(resolveServerIcon(undefined)).toBe(DEFAULT_SERVER_ICON);
  });

  it('keeps the default on the agenda\'s own calendar icon, the one AgendaSwitchView draws', () => {
    expect(DEFAULT_SERVER_ICON).toBe('fas fa-calendar-alt');
  });

  it('keeps the packaged CalDAV image shipped, for an administrator who deliberately picks it', () => {
    expect(DEFAULT_SERVER_IMAGE).toBe('/caldav/skin/image/caldav.png');
  });
});
