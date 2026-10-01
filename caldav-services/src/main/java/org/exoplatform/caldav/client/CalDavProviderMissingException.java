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

/**
 * The registration names a credentials provider that is not installed on this
 * platform, so no conversation with its server is started: nothing was sent.
 *
 * <p>
 * Distinguished from its parent {@link CalDavException} because the caller
 * reacts differently. A provider an add-on contributes is absent while that
 * add-on is not installed, or has not started yet: that is a state of the
 * platform, already said once per provider name by
 * {@code CaldavCredentialsResolver#isProviderMissing}, and not an incident of
 * this call. A caller therefore says nothing more than a debug line about it,
 * and records nothing about the account either: neither a pause, which is for
 * a server that refused the credentials, nor a failure, so that the account
 * works again by itself the moment the provider is installed.
 */
public class CalDavProviderMissingException extends CalDavException {

  private static final long serialVersionUID = 4127730918264519305L;

  /**
   * A refusal naming the provider that is not installed.
   *
   * @param message what was refused, naming the provider
   */
  public CalDavProviderMissingException(String message) {
    super(message);
  }
}
