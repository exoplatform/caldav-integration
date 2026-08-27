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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.caldav.provider;

import org.springframework.stereotype.Component;

import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsContext;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.HttpConnectorCredentials;

/**
 * The CalDAV side of the shared credentials contract: the one place that knows
 * how this connector addresses a provider, so that the two questions a caller
 * ever asks — <i>which account would the material address</i> and <i>what
 * header do I send</i> — are answered from a single context.
 * <p>
 * It exists because there are three callers, not one: the client mints an
 * endpoint's target and then authorizes every request on it, and the relay
 * authorizes the requests it forwards on its own transport. Each building its
 * own context is the divergence the contract was designed to remove — a
 * connector kind or a channel drifting in one of them fails as a wrong
 * provider resolved, not as a compile error.
 */
@Component
public class CaldavCredentialsResolver {

  /**
   * The kind this connector is known by platform-wide, which is how a provider
   * shared across connectors finds its CalDAV-specific adapter.
   */
  public static final String                CONNECTOR_KIND = "caldav";

  private final ConnectorCredentialsService connectorCredentialsService;

  public CaldavCredentialsResolver(ConnectorCredentialsService connectorCredentialsService) {
    this.connectorCredentialsService = connectorCredentialsService;
  }

  /**
   * The account a conversation addresses, as the configured provider names it —
   * the user's own for Personal, someone else's for a provider that
   * authenticates as a technical account.
   *
   * @param serverId registration the account references, or null for the
   *          legacy property
   * @param providerName provider the registration is configured with
   * @param exoLogin the eXo login the target is resolved for
   * @return the account name to place in the URL, or null when the provider has
   *         none to offer
   * @throws CalDavException when no provider of that name can answer
   */
  public String targetAccount(Long serverId, String providerName, String exoLogin) {
    try {
      return connectorCredentialsService.resolveTargetIdentity(context(serverId, providerName, exoLogin));
    } catch (ConnectorCredentialsException e) {
      throw new CalDavException("No credentials provider named " + providerName + " could resolve the CalDAV account", e);
    }
  }

  /**
   * The {@code Authorization} value for that account, as the configured
   * provider produced it — Basic today, whatever a future provider answers
   * tomorrow. Never assembled here.
   * <p>
   * Callers resolve it per request rather than once per endpoint: material can
   * expire between two requests of one operation, and a scheme whose header
   * depends on the method and the URI could not be served by a value frozen
   * earlier. Providers cache their own production, so the repetition costs a
   * lookup.
   * <p>
   * A failure here is deliberately a plain {@link CalDavException} and never a
   * {@link org.exoplatform.caldav.client.CalDavAuthenticationException}: the
   * latter means the calendar server refused a credential, which the sync
   * engine answers by pausing that user's account. Failing to <i>produce</i> a
   * credential is a different thing — a technical account misconfigured, a
   * provider unable to reach its own authority — and it is the connector's
   * fault, not this user's. Pausing them one by one would bury an
   * administrator's problem under user-level noise.
   *
   * @param serverId registration the account references, or null for the
   *          legacy property
   * @param providerName provider the registration is configured with
   * @param exoLogin the eXo login the credentials are produced for
   * @return the header value to send
   * @throws CalDavException when the configured provider cannot produce
   *           credentials for the account
   */
  public String authorization(Long serverId, String providerName, String exoLogin) {
    try {
      // The cast holds because the resolution service refuses a provider that
      // does not declare the requested channel, and an HTTP-declaring provider
      // answers HTTP material. A failure here is a provider breaking its own
      // contract, which is why it is not caught into something friendlier.
      return ((HttpConnectorCredentials) connectorCredentialsService.produce(context(serverId, providerName,
                                                                                    exoLogin))).getAuthorizationHeaderValue();
    } catch (ConnectorCredentialsException e) {
      throw new CalDavException("The credentials provider " + providerName
          + " could not produce credentials for this CalDAV account", e);
    }
  }

  /**
   * The one CalDAV credentials context of this addon. A server row that
   * predates the registry carries no id, and the contract expects a connector
   * id rather than a null.
   *
   * @param serverId registration the account references, or null
   * @param providerName provider the registration is configured with
   * @param exoLogin the eXo login being served
   * @return the context to hand the resolution service
   */
  private ConnectorCredentialsContext context(Long serverId, String providerName, String exoLogin) {
    return new ConnectorCredentialsContext(serverId == null ? 0L : serverId,
                                          providerName,
                                          exoLogin,
                                          ConnectorCredentialsChannel.HTTP,
                                          CONNECTOR_KIND);
  }

}
