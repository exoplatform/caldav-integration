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
package org.exoplatform.caldav.model;

/**
 * What a login answered and a session carries: the key, and who BlueMind
 * says the session belongs to.
 *
 * @param key the session key, sent in {@code X-BM-ApiKey}
 * @param userUid the directory entry uid of the authenticated user
 *          ({@code LoginResponse.authUser.uid}), or null when the answer
 *          named none
 * @param domainUid the uid of their domain
 *          ({@code LoginResponse.authUser.domainUid}), or null when the
 *          answer named none
 */
public record BlueMindLogin(String key, String userUid, String domainUid) {

  /**
   * Names who the session belongs to, never its key.
   *
   * @return the user and domain uids
   */
  @Override
  public String toString() {
    return "BlueMindLogin[userUid=" + userUid + ", domainUid=" + domainUid + "]";
  }
}
