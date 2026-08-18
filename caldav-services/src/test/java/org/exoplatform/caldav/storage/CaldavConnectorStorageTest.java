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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doReturn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.utils.CaldavConnectorUtils;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.data.Context;

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
