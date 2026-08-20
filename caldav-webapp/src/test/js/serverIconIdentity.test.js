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
import {resolveServerImage, DEFAULT_SERVER_IMAGE} from '../../main/webapp/vue-app/caldav/js/serverIconIdentity.js';

/**
 * The identity rule every surface shares: uploaded image, else the
 * admin-chosen font icon, else the packaged CalDAV image. The admin drawer
 * preview once broke ranks by showing a fa-calendar-alt placeholder for a
 * server that had nothing persisted, while the list and the connect drawer
 * showed the packaged image for the same server — two identities on one
 * screen. These tests pin the single rule that prevents that divergence.
 */
describe('resolveServerImage', () => {

  it('renders the uploaded image when one exists, even when a font icon is also set', () => {
    expect(resolveServerImage('/rest/images/42', 'fa-calendar-check')).toBe('/rest/images/42');
    expect(resolveServerImage('/rest/images/42', null)).toBe('/rest/images/42');
  });

  it('renders the font icon (null image) when an icon is chosen and no image uploaded', () => {
    expect(resolveServerImage(null, 'fa-calendar-check')).toBeNull();
    expect(resolveServerImage('', 'fa-calendar-check')).toBeNull();
  });

  it('falls back to the packaged CalDAV image when nothing is configured — never a placeholder glyph', () => {
    expect(resolveServerImage(null, null)).toBe(DEFAULT_SERVER_IMAGE);
    expect(resolveServerImage('', '')).toBe(DEFAULT_SERVER_IMAGE);
    expect(resolveServerImage(undefined, undefined)).toBe(DEFAULT_SERVER_IMAGE);
  });

  it('keeps the packaged default at the path the connector descriptor ships as avatar', () => {
    expect(DEFAULT_SERVER_IMAGE).toBe('/caldav/skin/image/caldav.png');
  });
});
