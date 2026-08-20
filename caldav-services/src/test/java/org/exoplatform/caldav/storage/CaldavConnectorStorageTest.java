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
package org.exoplatform.caldav.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.utils.CaldavConnectorUtils;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.web.security.codec.AbstractCodec;
import org.exoplatform.web.security.codec.CodecInitializer;

/**
 * The mirror calendar href is stored beside the credentials of the CalDAV
 * account, in the same scope and for the same user, so that disconnecting an
 * account takes it away with the rest — a mirror surviving its account is how
 * a reconnected user ends up pushing into a collection they no longer own.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavConnectorStorageTest {

  private static final long          USER_IDENTITY_ID = 42L;

  private static final String        MIRROR_HREF      = "/dav/root/exo-mirror/";

  @Mock
  private SettingService             settingService;

  @Captor
  private ArgumentCaptor<SettingValue<String>> settingValueCaptor;

  @InjectMocks
  private CaldavConnectorStorage     caldavConnectorStorage;

  /**
   * The href is written under the user's own context, in the connector scope.
   */
  @Test
  public void shouldSaveMirrorCalendarHrefForTheUser() {
    caldavConnectorStorage.saveMirrorCalendarHref(MIRROR_HREF, USER_IDENTITY_ID);

    // SettingValue carries no equals(), so the value itself is what gets compared
    verify(settingService).set(eq(Context.USER.id(String.valueOf(USER_IDENTITY_ID))),
                               eq(CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE),
                               eq(CaldavConnectorUtils.CALDAV_MIRROR_CALENDAR_KEY),
                               settingValueCaptor.capture());
    assertEquals(MIRROR_HREF, settingValueCaptor.getValue().getValue());
  }

  /**
   * A stored href is read back onto the setting.
   */
  @Test
  public void shouldReadTheMirrorCalendarHrefBack() {
    doReturn(null).when(settingService).get(any(), any(), any());
    doReturn(SettingValue.create(MIRROR_HREF)).when(settingService)
                                              .get(Context.USER.id(String.valueOf(USER_IDENTITY_ID)),
                                                   CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                                   CaldavConnectorUtils.CALDAV_MIRROR_CALENDAR_KEY);

    CaldavUserSetting setting = caldavConnectorStorage.getCaldavSetting(USER_IDENTITY_ID);

    assertEquals(MIRROR_HREF, setting.getMirrorCalendarHref());
  }

  /**
   * An account that never chose a mirror reads back without one, rather than
   * with an empty string that later code would take for a real collection.
   */
  @Test
  public void shouldReadNoMirrorCalendarHrefWhenNoneWasStored() {
    doReturn(null).when(settingService).get(any(), any(), any());

    CaldavUserSetting setting = caldavConnectorStorage.getCaldavSetting(USER_IDENTITY_ID);

    assertNull(setting.getMirrorCalendarHref());
  }

  /**
   * Opens a static mock of the codec lookup so encode/decode run against a
   * predictable reversible codec: encode wraps, decode unwraps.
   *
   * @return the static mock, to be closed by the caller
   * @throws Exception never, the codec is mocked
   */
  private MockedStatic<CommonsUtils> withReversibleCodec() throws Exception {
    AbstractCodec codec = mock(AbstractCodec.class);
    lenient().when(codec.encode(any())).thenAnswer(invocation -> "ENC(" + invocation.getArgument(0) + ")");
    lenient().when(codec.decode(any())).thenAnswer(invocation -> {
      String stored = invocation.getArgument(0);
      return stored.substring(4, stored.length() - 1);
    });
    CodecInitializer codecInitializer = mock(CodecInitializer.class);
    lenient().when(codecInitializer.getCodec()).thenReturn(codec);
    MockedStatic<CommonsUtils> commonsUtils = mockStatic(CommonsUtils.class);
    commonsUtils.when(() -> CommonsUtils.getService(CodecInitializer.class)).thenReturn(codecInitializer);
    return commonsUtils;
  }

  /**
   * A connection made through a declared server stores the username, the
   * ENCODED password — never the plaintext the user typed — and the server
   * reference, all under the user's own context.
   *
   * @throws Exception never, the codec is mocked
   */
  @Test
  public void shouldStoreEncodedCredentialsAndServerReference() throws Exception {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("root");
    setting.setPassword("s3cret");
    setting.setServerId(7L);

    try (MockedStatic<CommonsUtils> commonsUtils = withReversibleCodec()) {
      caldavConnectorStorage.createCaldavSetting(setting, USER_IDENTITY_ID);
    }

    Context userContext = Context.USER.id(String.valueOf(USER_IDENTITY_ID));
    verify(settingService).set(eq(userContext), eq(CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE),
                               eq(CaldavConnectorUtils.CALDAV_USERNAME_KEY), settingValueCaptor.capture());
    assertEquals("root", settingValueCaptor.getValue().getValue());
    verify(settingService).set(eq(userContext), eq(CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE),
                               eq(CaldavConnectorUtils.CALDAV_PASSWORD_KEY), settingValueCaptor.capture());
    assertEquals("ENC(s3cret)", settingValueCaptor.getValue().getValue());
    verify(settingService).set(eq(userContext), eq(CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE),
                               eq(CaldavConnectorUtils.CALDAV_SERVER_ID_KEY), settingValueCaptor.capture());
    assertEquals("7", settingValueCaptor.getValue().getValue());
  }

  /**
   * A connection made OUTSIDE any registration (the legacy single-server
   * path) removes a previously stored server reference instead of leaving a
   * stale one silently pointing the new credentials at the old server.
   *
   * @throws Exception never, the codec is mocked
   */
  @Test
  public void shouldClearTheStaleServerReferenceOnLegacyConnection() throws Exception {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("root");
    setting.setPassword("s3cret");
    setting.setServerId(null);

    try (MockedStatic<CommonsUtils> commonsUtils = withReversibleCodec()) {
      caldavConnectorStorage.createCaldavSetting(setting, USER_IDENTITY_ID);
    }

    Context userContext = Context.USER.id(String.valueOf(USER_IDENTITY_ID));
    verify(settingService).remove(userContext, CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                  CaldavConnectorUtils.CALDAV_SERVER_ID_KEY);
    verify(settingService, never()).set(eq(userContext), eq(CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE),
                                        eq(CaldavConnectorUtils.CALDAV_SERVER_ID_KEY), any());
  }

  /**
   * The settings read back whole: username as stored, password DECODED back
   * to what the user typed (the connector authenticates with it), and the
   * server reference parsed to its number.
   *
   * @throws Exception never, the codec is mocked
   */
  @Test
  public void shouldReadCredentialsBackDecoded() throws Exception {
    Context userContext = Context.USER.id(String.valueOf(USER_IDENTITY_ID));
    doReturn(null).when(settingService).get(any(), any(), any());
    doReturn(SettingValue.create("root")).when(settingService).get(userContext,
                                                                   CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                                                   CaldavConnectorUtils.CALDAV_USERNAME_KEY);
    doReturn(SettingValue.create("ENC(s3cret)")).when(settingService).get(userContext,
                                                                          CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                                                          CaldavConnectorUtils.CALDAV_PASSWORD_KEY);
    doReturn(SettingValue.create("7")).when(settingService).get(userContext,
                                                                CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                                                CaldavConnectorUtils.CALDAV_SERVER_ID_KEY);

    CaldavUserSetting setting;
    try (MockedStatic<CommonsUtils> commonsUtils = withReversibleCodec()) {
      setting = caldavConnectorStorage.getCaldavSetting(USER_IDENTITY_ID);
    }

    assertEquals("root", setting.getUsername());
    assertEquals("s3cret", setting.getPassword());
    assertEquals(Long.valueOf(7L), setting.getServerId());
  }

  /**
   * An unreadable stored server reference behaves exactly like no reference —
   * the URL resolution then falls back to the seed registration, then the
   * legacy property — instead of crashing every settings read of that user.
   */
  @Test
  public void shouldTreatAnUnreadableServerReferenceAsNone() {
    Context userContext = Context.USER.id(String.valueOf(USER_IDENTITY_ID));
    doReturn(null).when(settingService).get(any(), any(), any());
    doReturn(SettingValue.create("not-a-number")).when(settingService).get(userContext,
                                                                           CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                                                           CaldavConnectorUtils.CALDAV_SERVER_ID_KEY);

    CaldavUserSetting setting = caldavConnectorStorage.getCaldavSetting(USER_IDENTITY_ID);

    assertNull(setting.getServerId());

    // a stored holder whose value is null is just as unreadable
    doReturn(SettingValue.create((String) null)).when(settingService).get(userContext,
                                                                          CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                                                          CaldavConnectorUtils.CALDAV_SERVER_ID_KEY);

    assertNull(caldavConnectorStorage.getCaldavSetting(USER_IDENTITY_ID).getServerId());
  }

  /**
   * Disconnecting an account removes the mirror href along with the
   * credentials, so a later reconnection starts from a clean state.
   */
  @Test
  public void shouldRemoveTheMirrorCalendarHrefWithTheAccount() {
    caldavConnectorStorage.deleteCaldavSetting(USER_IDENTITY_ID);

    verify(settingService).remove(Context.USER.id(String.valueOf(USER_IDENTITY_ID)),
                                  CaldavConnectorUtils.CALDAV_CONNECTOR_SETTING_SCOPE,
                                  CaldavConnectorUtils.CALDAV_MIRROR_CALENDAR_KEY);
  }
}
