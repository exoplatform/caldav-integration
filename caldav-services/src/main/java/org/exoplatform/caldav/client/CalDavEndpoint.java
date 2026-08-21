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
package org.exoplatform.caldav.client;

import java.net.URI;

/**
 * Where a CalDAV conversation is allowed to go: the scheme and authority of
 * one administrator-declared server, plus the account's base collection path
 * on it. This class is the client's registry-only containment made
 * structural — its only constructor is package-private, so the single place
 * an endpoint can be minted is {@link HttpCalDavClient#endpoint(Long, String)},
 * which resolves the target exclusively from the server registry (and its
 * legacy deployment-property fallback). No caller outside this package can
 * hand the client a URL of its own choosing, which is the same containment
 * style as the relay resolving targets only from registry rows.
 */
public final class CalDavEndpoint {

  private final Long serverId;

  private final URI  baseUri;

  private final String basePath;

  /**
   * An endpoint, minted by the client from a resolved registration.
   *
   * @param serverId identifier of the registry row the URL came from, or
   *          null when the legacy deployment property resolved it
   * @param baseUri the resolved account base URI, absolute
   */
  CalDavEndpoint(Long serverId, URI baseUri) {
    this.serverId = serverId;
    this.baseUri = baseUri;
    this.basePath = baseUri.getRawPath() == null || baseUri.getRawPath().isEmpty() ? "/" : baseUri.getRawPath();
  }

  /**
   * The registry row this endpoint was resolved from.
   *
   * @return the row id, or null when the legacy property resolved the URL
   */
  public Long getServerId() {
    return serverId;
  }

  /**
   * The account's base URI on the declared server — where discovery starts.
   *
   * @return the absolute base URI
   */
  public URI getBaseUri() {
    return baseUri;
  }

  /**
   * The server-absolute path of the account base — hrefs the server answers
   * (BlueMind roots its own at {@code /dav/}) are resolved against this
   * endpoint's authority, never against a caller-supplied one.
   *
   * @return the raw base path, always starting with a slash
   */
  public String getBasePath() {
    return basePath;
  }

  /**
   * Names the endpoint without ever naming credentials — safe in logs.
   *
   * @return the endpoint described by server id and base URI
   */
  @Override
  public String toString() {
    return "CalDavEndpoint[serverId=" + serverId + ", baseUri=" + baseUri + "]";
  }
}
