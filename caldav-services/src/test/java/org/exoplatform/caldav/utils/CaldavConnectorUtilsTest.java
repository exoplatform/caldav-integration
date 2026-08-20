/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.caldav.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.web.security.codec.AbstractCodec;
import org.exoplatform.web.security.codec.CodecInitializer;
import org.exoplatform.web.security.security.TokenServiceInitializationException;

/**
 * The credential codec is the only thing standing between a user's CalDAV
 * password and a plaintext settings row, so what these tests pin down is the
 * round trip — whatever encode produced, decode gives the password back —
 * and the failure mode: a codec that cannot initialize yields null, never the
 * plaintext and never an exception that would take the settings read down.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavConnectorUtilsTest {

  @Mock
  private IdentityManager identityManager;

  /**
   * Clears the thread's conversation state, which the current-user tests set.
   */
  @AfterEach
  public void tearDown() {
    ConversationState.setCurrent(null);
  }

  /**
   * Opens a static mock of the codec lookup backed by a reversible fake:
   * encode wraps, decode unwraps, so a round trip restores the input.
   *
   * @return the static mock, to be closed by the caller
   * @throws Exception never, the codec is mocked
   */
  private MockedStatic<CommonsUtils> withReversibleCodec() throws Exception {
    AbstractCodec codec = mock(AbstractCodec.class);
    when(codec.encode(any())).thenAnswer(invocation -> "ENC(" + invocation.getArgument(0) + ")");
    when(codec.decode(any())).thenAnswer(invocation -> {
      String stored = invocation.getArgument(0);
      return stored.substring(4, stored.length() - 1);
    });
    CodecInitializer codecInitializer = mock(CodecInitializer.class);
    when(codecInitializer.getCodec()).thenReturn(codec);
    MockedStatic<CommonsUtils> commonsUtils = mockStatic(CommonsUtils.class);
    commonsUtils.when(() -> CommonsUtils.getService(CodecInitializer.class)).thenReturn(codecInitializer);
    return commonsUtils;
  }

  /**
   * A password survives the encode/decode round trip, and what encode stores
   * is NOT the plaintext — the whole point of encoding it.
   *
   * @throws Exception never, the codec is mocked
   */
  @Test
  public void shouldRoundTripAPassword() throws Exception {
    try (MockedStatic<CommonsUtils> commonsUtils = withReversibleCodec()) {
      String encoded = CaldavConnectorUtils.encode("s3cret");

      assertEquals("ENC(s3cret)", encoded);
      assertEquals("s3cret", CaldavConnectorUtils.decode(encoded));
    }
  }

  /**
   * The empty password round-trips too: encode hands it to the codec rather
   * than special-casing it, and decode restores it.
   *
   * @throws Exception never, the codec is mocked
   */
  @Test
  public void shouldRoundTripTheEmptyPassword() throws Exception {
    try (MockedStatic<CommonsUtils> commonsUtils = withReversibleCodec()) {
      String encoded = CaldavConnectorUtils.encode("");

      assertEquals("ENC()", encoded);
      assertEquals("", CaldavConnectorUtils.decode(encoded));
    }
  }

  /**
   * A null password is handed to the codec as-is — the codec's own null
   * answer comes back, and nothing throws on the way.
   *
   * @throws Exception never, the codec is mocked
   */
  @Test
  public void shouldPassANullPasswordThroughWithoutCrashing() throws Exception {
    AbstractCodec codec = mock(AbstractCodec.class);
    CodecInitializer codecInitializer = mock(CodecInitializer.class);
    when(codecInitializer.getCodec()).thenReturn(codec);
    try (MockedStatic<CommonsUtils> commonsUtils = mockStatic(CommonsUtils.class)) {
      commonsUtils.when(() -> CommonsUtils.getService(CodecInitializer.class)).thenReturn(codecInitializer);

      assertNull(CaldavConnectorUtils.encode(null));
      assertNull(CaldavConnectorUtils.decode(null));
    }
  }

  /**
   * A codec that cannot initialize makes encode AND decode answer null — not
   * the plaintext (which would store the password unprotected) and not an
   * exception (which would take the caller down with it).
   *
   * @throws Exception never, the codec lookup is mocked
   */
  @Test
  public void shouldAnswerNullWhenTheCodecCannotInitialize() throws Exception {
    CodecInitializer codecInitializer = mock(CodecInitializer.class);
    when(codecInitializer.getCodec()).thenThrow(new TokenServiceInitializationException("no keystore"));
    try (MockedStatic<CommonsUtils> commonsUtils = mockStatic(CommonsUtils.class)) {
      commonsUtils.when(() -> CommonsUtils.getService(CodecInitializer.class)).thenReturn(codecInitializer);

      assertNull(CaldavConnectorUtils.encode("s3cret"));
      assertNull(CaldavConnectorUtils.decode("ENC(s3cret)"));
    }
  }

  /**
   * The current user is read off the thread's conversation state.
   */
  @Test
  public void shouldNameTheCurrentUser() {
    ConversationState.setCurrent(new ConversationState(new org.exoplatform.services.security.Identity("root")));

    assertEquals("root", CaldavConnectorUtils.getCurrentUser());
  }

  /**
   * The current user's identity id is resolved through the identity manager;
   * a user the manager cannot resolve answers 0 rather than crashing the
   * caller.
   */
  @Test
  public void shouldResolveTheCurrentUserIdentityId() {
    ConversationState.setCurrent(new ConversationState(new org.exoplatform.services.security.Identity("root")));
    Identity identity = new Identity(OrganizationIdentityProvider.NAME, "root");
    identity.setId("42");
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, "root")).thenReturn(identity);

    assertEquals(42L, CaldavConnectorUtils.getCurrentUserIdentityId(identityManager));

    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, "root")).thenReturn(null);

    assertEquals(0L, CaldavConnectorUtils.getCurrentUserIdentityId(identityManager));
  }
}
