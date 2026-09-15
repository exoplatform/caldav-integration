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
 * Mints endpoints for tests that live outside this package.
 *
 * <p>
 * {@link CalDavEndpoint}'s constructor is package-private on purpose — in
 * production only {@link HttpCalDavClient#endpoint} may mint one, from the
 * registry — and that containment is worth keeping. A test in another package
 * ({@code client.bluemind}) still needs a concrete endpoint to drive a client
 * against canned answers, so this helper, test-scoped and in the owning
 * package, is the one door left open, and it opens onto nothing but a URI the
 * test wrote itself.
 */
public final class TestEndpoints {

  /**
   * Not instantiable.
   */
  private TestEndpoints() {
  }

  /**
   * An endpoint as the registry would have minted it.
   *
   * @param serverId the registry row id, or null for the legacy property
   * @param baseUri the account's absolute base URI
   * @param authProviderName the credentials provider name
   * @param exoLogin the eXo login the endpoint is minted for
   * @return the endpoint
   */
  public static CalDavEndpoint endpoint(Long serverId, URI baseUri, String authProviderName, String exoLogin) {
    return new CalDavEndpoint(serverId, baseUri, authProviderName, exoLogin);
  }
}
