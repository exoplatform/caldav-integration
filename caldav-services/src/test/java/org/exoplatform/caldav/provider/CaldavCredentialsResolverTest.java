/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

import org.exoplatform.caldav.LogRecorder;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsContext;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsProvider;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.HttpConnectorCredentials;

/**
 * The context this resolver builds is the whole reason it exists: a channel or a
 * connector kind drifting here fails at runtime as a wrong provider resolved, not as
 * a compile error. So the context is read back and asserted field by field, on both
 * questions the resolver answers. Mutation-verified: {@code HTTP} changed to
 * {@code IMAP} in {@code context(...)} fails the two channel assertions.
 */
@ExtendWith(MockitoExtension.class)
class CaldavCredentialsResolverTest {

  private static final Long   SERVER_ID = 7L;

  private static final String PROVIDER  = "personal";

  private static final String LOGIN     = "alice";

  private static final String HEADER    = "Bearer produced-by-the-provider";

  @Mock
  private ConnectorCredentialsService connectorCredentialsService;

  private CaldavCredentialsResolver resolver() {
    return new CaldavCredentialsResolver(connectorCredentialsService);
  }

  @Test
  void targetAccountAsksOnTheHttpChannelForTheCaldavKindAndTheLoginItWasHanded() throws Exception {
    when(connectorCredentialsService.resolveTargetIdentity(any())).thenReturn("alice@example.com");
    ArgumentCaptor<ConnectorCredentialsContext> context = ArgumentCaptor.forClass(ConnectorCredentialsContext.class);

    assertEquals("alice@example.com", resolver().targetAccount(SERVER_ID, PROVIDER, LOGIN));

    verify(connectorCredentialsService).resolveTargetIdentity(context.capture());
    assertContext(context.getValue(), SERVER_ID);
  }

  @Test
  void authorizationAsksOnTheHttpChannelAndReturnsTheHeaderVerbatim() throws Exception {
    when(connectorCredentialsService.produce(any())).thenReturn(new HttpConnectorCredentials(HEADER, null));
    ArgumentCaptor<ConnectorCredentialsContext> context = ArgumentCaptor.forClass(ConnectorCredentialsContext.class);

    assertSame(HEADER, resolver().authorization(SERVER_ID, PROVIDER, LOGIN), "never rebuilt, never trimmed");

    verify(connectorCredentialsService).produce(context.capture());
    assertContext(context.getValue(), SERVER_ID);
    verify(connectorCredentialsService, never()).invalidate(any());
  }

  /**
   * A server row that predates the registry carries no id, and the contract wants
   * a connector id rather than a null.
   */
  @Test
  void aLegacyServerWithoutAnIdIsAddressedAsConnectorZero() throws Exception {
    when(connectorCredentialsService.produce(any())).thenReturn(new HttpConnectorCredentials(HEADER, null));
    ArgumentCaptor<ConnectorCredentialsContext> context = ArgumentCaptor.forClass(ConnectorCredentialsContext.class);

    resolver().authorization(null, PROVIDER, LOGIN);

    verify(connectorCredentialsService).produce(context.capture());
    assertContext(context.getValue(), 0L);
  }

  @Test
  void aProviderThatCannotAnswerFailsAsACalDavFailureNamingIt() throws Exception {
    when(connectorCredentialsService.produce(any())).thenThrow(new ConnectorCredentialsException("no material"));
    when(connectorCredentialsService.resolveTargetIdentity(any())).thenThrow(new ConnectorCredentialsException("no account"));
    CaldavCredentialsResolver resolver = resolver();

    CalDavException produced = assertThrows(CalDavException.class, () -> resolver.authorization(SERVER_ID, PROVIDER, LOGIN));
    CalDavException named = assertThrows(CalDavException.class, () -> resolver.targetAccount(SERVER_ID, PROVIDER, LOGIN));

    assertEquals(true, produced.getMessage().contains(PROVIDER) && named.getMessage().contains(PROVIDER),
                 "both messages name the provider that could not answer");
    assertNotNull(produced.getCause());
    assertNotNull(named.getCause());
  }

  /**
   * The contract's staleness protocol, first half: material handed back already
   * expired is never sent - it is invalidated and produced once more, and the
   * second answer is what travels. Mutation-verified: with the {@code isExpired()}
   * branch removed, the stale header is returned and nothing is invalidated.
   */
  @Test
  void expiredMaterialIsInvalidatedAndProducedOnceMoreBeforeItIsSent() throws Exception {
    HttpConnectorCredentials stale = new HttpConnectorCredentials("Bearer stale", System.currentTimeMillis() - 1_000L);
    HttpConnectorCredentials fresh = new HttpConnectorCredentials("Bearer fresh", null);
    when(connectorCredentialsService.produce(any())).thenReturn(stale, fresh);

    assertEquals("Bearer fresh", resolver().authorization(SERVER_ID, PROVIDER, LOGIN));

    InOrder order = inOrder(connectorCredentialsService);
    order.verify(connectorCredentialsService).produce(any());
    order.verify(connectorCredentialsService).invalidate(any());
    order.verify(connectorCredentialsService).produce(any());
    verify(connectorCredentialsService, times(2)).produce(any());
  }

  /**
   * The second half: a refusal by the calendar server is told to the provider through
   * the same context the material was produced with, so a caching provider forgets
   * exactly that entry.
   */
  @Test
  void invalidateTellsTheProviderThroughTheSameContext() {
    ArgumentCaptor<ConnectorCredentialsContext> context = ArgumentCaptor.forClass(ConnectorCredentialsContext.class);

    resolver().invalidate(SERVER_ID, PROVIDER, LOGIN);

    verify(connectorCredentialsService).invalidate(context.capture());
    assertContext(context.getValue(), SERVER_ID);
  }

  /**
   * A provider an add-on contributes is missing while the add-on is not
   * installed: the resolver says so once per name at WARN, without a stack,
   * however many times it is asked; a second missing name is said once too.
   */
  @Test
  void aMissingProviderIsReportedOncePerName() {
    ConnectorCredentialsProvider contributed = mock(ConnectorCredentialsProvider.class);
    when(contributed.getName()).thenReturn("acme-sudo");
    when(connectorCredentialsService.getProviders()).thenReturn(List.of(contributed));
    CaldavCredentialsResolver resolver = resolver();

    try (LogRecorder log = new LogRecorder(CaldavCredentialsResolver.class)) {
      assertTrue(resolver.isProviderMissing("bluemind-sudo"));
      assertTrue(resolver.isProviderMissing("bluemind-sudo"));
      assertTrue(resolver.isProviderMissing("other-sudo"));

      List<ILoggingEvent> warnings = log.events().stream().filter(event -> event.getLevel() == Level.WARN).toList();
      assertEquals(2, warnings.size(), warnings.toString());
      assertTrue(warnings.get(0).getFormattedMessage().contains("'bluemind-sudo'"));
      assertTrue(warnings.get(1).getFormattedMessage().contains("'other-sudo'"));
      assertNull(warnings.get(0).getThrowableProxy(), "an expected state, said without a stack");
    }
  }

  /**
   * An installed provider is not missing and nothing is said; nor is a blank
   * name, the legacy registration, nor the platform's own Personal provider,
   * neither of which lists the providers; nor is any name when the providers
   * cannot be listed, so that the provider is asked as before.
   */
  @Test
  void anInstalledProviderABlankNameAndAnUnreadableListAreNotMissing() {
    ConnectorCredentialsProvider contributed = mock(ConnectorCredentialsProvider.class);
    when(contributed.getName()).thenReturn("acme-sudo");
    when(connectorCredentialsService.getProviders()).thenReturn(List.of(contributed));
    CaldavCredentialsResolver resolver = resolver();

    try (LogRecorder log = new LogRecorder(CaldavCredentialsResolver.class)) {
      assertFalse(resolver.isProviderMissing("acme-sudo"));
      assertFalse(resolver.isProviderMissing(PROVIDER));
      assertFalse(resolver.isProviderMissing(" "));
      assertFalse(resolver.isProviderMissing(null));
      assertTrue(log.events().stream().noneMatch(event -> event.getLevel() == Level.WARN));
    }
    verify(connectorCredentialsService, times(1)).getProviders();

    when(connectorCredentialsService.getProviders()).thenThrow(new IllegalStateException("no registry"));
    assertFalse(resolver.isProviderMissing("bluemind-sudo"));
  }

  private static void assertContext(ConnectorCredentialsContext context, Long connectorId) {
    assertEquals(connectorId, context.getConnectorId());
    assertEquals(PROVIDER, context.getConnectorCredentialsProviderName());
    assertEquals(LOGIN, context.getUsername(), "the eXo login handed in, never a DAV account");
    assertEquals(ConnectorCredentialsChannel.HTTP, context.getChannel(), "CalDAV speaks HTTP and nothing else");
    assertEquals(CaldavCredentialsResolver.CONNECTOR_KIND, context.getConnectorKind());
  }

}
