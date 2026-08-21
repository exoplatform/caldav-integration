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

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the relay answers the browser with: the upstream server's status and
 * body — DAV logic like 207 multistatus parsing or the 412 conflict discipline
 * lives in the caller, so statuses pass through untouched — under a
 * response-header allow-list, with advertised hrefs rewritten into relay space
 * and the credential-related statuses already translated (an upstream 401
 * means the <b>stored</b> CalDAV credentials are stale, never that the eXo
 * user is unauthenticated, so it must not travel as a 401).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CaldavRelayedResponse {

  /** HTTP status the browser receives. */
  private int                 status;

  /** Response headers to send back, allow-listed and rewritten. */
  private Map<String, String> headers;

  /** Response body bytes, hrefs rewritten when the body is a DAV XML one. */
  private byte[]              body;
}
