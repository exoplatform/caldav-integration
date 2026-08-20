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
 * One DAV request the browser asks the platform to relay to a declared CalDAV
 * server. Deliberately carries no target URL: the target is resolved
 * server-side from the registry row the {@code serverId} names, which is the
 * SSRF boundary of the relay — a client can choose <b>which declared server</b>
 * it talks to, never <b>where</b> a request goes.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CaldavRelayRequest {

  /** User the relay acts for; the stored credentials injected are theirs. */
  private String              username;

  /** Identifier of the declared server registration the request targets. */
  private long                serverId;

  /** HTTP method of the DAV request (PROPFIND, REPORT, PUT...). */
  private String              method;

  /**
   * Raw (still percent-encoded) path of the resource on the upstream server,
   * rooted at the upstream host: the suffix after the relay prefix, exactly as
   * the hrefs the server advertises spell it.
   */
  private String              davPath;

  /** Raw query string of the request, or null when there is none. */
  private String              query;

  /**
   * Request headers as the browser sent them, lower-cased names. Only the
   * relay's allow-listed subset is ever forwarded; in particular any
   * Authorization or Cookie header in here is dropped, never relayed.
   */
  private Map<String, String> headers;

  /** Request body bytes, empty when the request carries none. */
  private byte[]              body;

  /**
   * The relay's own path prefix for this server as the browser reached it
   * (e.g. {@code /caldav/rest/dav/3}): what advertised hrefs are rewritten
   * onto, so every href a response carries stays inside relay space.
   */
  private String              relayPrefix;
}
