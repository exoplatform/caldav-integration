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
 * Outcome of probing a CalDAV account server-side, before anything is stored:
 * a stable code the connect drawer translates — the same codes the browser
 * probe historically produced, so the drawer's messages did not change when
 * the probe moved behind the platform (where BlueMind's missing CORS headers
 * stop mattering).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CaldavProbeResult {

  /** The probe accepted the account. */
  public static final String OK          = "ok";

  /** The server answered, refusing the username or password. */
  public static final String CREDENTIALS = "caldav.error.credentials";

  /** The server could not be reached at all. */
  public static final String CONNECTION  = "caldav.error.connection";

  /** The URL reaches something that is not a CalDAV collection. */
  public static final String NOT_CALDAV  = "caldav.error.notCaldav";

  /** One of the stable outcome codes above. */
  private String  result;

  /** HTTP status that produced the outcome, when the server answered. */
  private Integer status;
}
