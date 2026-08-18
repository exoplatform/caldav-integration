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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A CalDAV server registration, as an administrator declared it: where the
 * server lives and how it is presented to users. Deliberately credential-free
 * — per-user secrets stay in the per-user settings storage — so this object
 * can travel through any REST response without leaking anything.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CaldavServer {

  /**
   * Technical identifier of the registration row.
   */
  private long    id;

  /**
   * Name of the agenda remote provider this registration is bridged to:
   * {@code agenda.caldavCalendar} for the seed registration,
   * {@code agenda.caldavCalendar.<id>} for the others. System-derived, never
   * user-typed, and unique.
   */
  private String  providerName;

  /**
   * Display name of the server, as users see it in the connectors list.
   */
  private String  name;

  /**
   * Optional description shown beside the name.
   */
  private String  description;

  /**
   * Base URL of the CalDAV server, optionally holding a {username}
   * placeholder. Not a secret: the browser itself needs it to speak WebDAV.
   */
  private String  serverUrl;

  /**
   * Whether users may connect to this server. Deactivation propagates to the
   * agenda remote provider, which is what actually hides the connector.
   */
  private boolean active;
}
