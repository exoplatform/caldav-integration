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
package org.exoplatform.caldav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.caldav.model.CaldavSyncTuning;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;

/**
 * Where the engine's tuning comes from, and what it refuses.
 *
 * <p>
 * The one thing these have to pin is the reason this class exists at all: the
 * values used to be {@code @Value} fields, read once at bean creation, so a
 * screen writing them changed nothing until the next restart while looking as
 * though it had worked.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavTuningServiceTest {

  @Mock
  private SettingService     settingService;

  @InjectMocks
  private CaldavTuningService service;

  @BeforeEach
  public void setTheProperties() {
    ReflectionTestUtils.setField(service, "defaultThrottleMinutes", 15L);
    ReflectionTestUtils.setField(service, "defaultPastDays", 60L);
    ReflectionTestUtils.setField(service, "defaultFutureDays", 365L);
    ReflectionTestUtils.setField(service, "defaultSweepStaleMinutes", 30L);
    ReflectionTestUtils.setField(service, "defaultSweepBatchSize", 50);
    lenient().when(settingService.get(any(), any(), any())).thenReturn(null);
  }

  @Test
  public void nothingSavedFallsBackToTheProperty() {
    // An operator managing the instance through exo.properties keeps working
    // exactly as before until someone saves the screen.
    assertEquals(15L, service.getThrottleMinutes());
    assertEquals(60L, service.getPastDays());
    assertEquals(365L, service.getFutureDays());
  }

  @Test
  public void whatWasSavedWinsOverTheProperty() {
    // doReturn rather than when(...).thenReturn: SettingService.get is
    // declared with a wildcard, and the fluent form cannot be typed to it.
    doReturn(SettingValue.create("90")).when(settingService)
                                       .get(eq(Context.GLOBAL), eq(CaldavTuningService.TUNING_SCOPE), eq("futureDays"));

    assertEquals(90L, service.getFutureDays());
  }

  @Test
  public void aValueReadAtEachCallRatherThanCaptured() {
    // The whole point. A value captured when the bean was built would make an
    // administration screen a lie: it would store something nothing re-reads
    // until the next restart.
    doReturn(SettingValue.create("15"), SettingValue.create("5")).when(settingService)
                                                                 .get(eq(Context.GLOBAL),
                                                                      eq(CaldavTuningService.TUNING_SCOPE),
                                                                      eq("throttleMinutes"));

    assertEquals(15L, service.getThrottleMinutes());
    assertEquals(5L, service.getThrottleMinutes());
  }

  @Test
  public void somethingThatIsNotANumberFallsBackRatherThanFailing() {
    // This is read on the path of every synchronisation. The property is a
    // better answer than an exception thrown at a user opening their agenda.
    doReturn(SettingValue.create("soon")).when(settingService)
                                         .get(eq(Context.GLOBAL), eq(CaldavTuningService.TUNING_SCOPE), eq("pastDays"));

    assertEquals(60L, service.getPastDays());
  }

  @Test
  public void savingStoresEveryValue() {
    service.saveTuning(new CaldavSyncTuning(5L, 30L, 90L, 60L, 20));

    verify(settingService).set(eq(Context.GLOBAL), eq(CaldavTuningService.TUNING_SCOPE), eq("throttleMinutes"), any());
    verify(settingService).set(eq(Context.GLOBAL), eq(CaldavTuningService.TUNING_SCOPE), eq("pastDays"), any());
    verify(settingService).set(eq(Context.GLOBAL), eq(CaldavTuningService.TUNING_SCOPE), eq("futureDays"), any());
    verify(settingService).set(eq(Context.GLOBAL), eq(CaldavTuningService.TUNING_SCOPE), eq("sweepStaleMinutes"), any());
    verify(settingService).set(eq(Context.GLOBAL), eq(CaldavTuningService.TUNING_SCOPE), eq("sweepBatchSize"), any());
  }

  @Test
  public void aWindowOfTenYearsIsRefused() {
    // Not a matter of taste: a calendar with a decade of history behind it
    // becomes a full download on the first page load of every day.
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                                                    () -> service.saveTuning(new CaldavSyncTuning(15L,
                                                                                                  60L,
                                                                                                  40000L,
                                                                                                  30L,
                                                                                                  50)));

    assertEquals("caldav.tuning.futureDaysOutOfRange", refused.getMessage());
    verify(settingService, never()).set(any(), any(), any(), any());
  }

  @Test
  public void aWindowOfNothingIsRefusedToo() {
    // Zero days forward is an agenda that never shows a remote event again,
    // which nobody means to ask for.
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                                                    () -> service.saveTuning(new CaldavSyncTuning(15L, 60L, 0L, 30L, 50)));

    assertEquals("caldav.tuning.futureDaysOutOfRange", refused.getMessage());
  }

  @Test
  public void aRefusalStoresNothingAtAll() {
    // Values are stored one key at a time, so validating after the first write
    // would leave the tuning half old and half new.
    assertThrows(IllegalArgumentException.class,
                 () -> service.saveTuning(new CaldavSyncTuning(15L, 60L, 365L, 30L, 9999)));

    verify(settingService, never()).set(any(), any(), any(), any());
  }
}
