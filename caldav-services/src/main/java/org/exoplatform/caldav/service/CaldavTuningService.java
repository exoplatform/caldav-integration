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

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.model.CaldavSyncTuning;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;

/**
 * How often and how widely the engine synchronises, and who gets to say.
 *
 * <p>
 * The values used to be {@code @Value}-injected fields. That is read <b>once,
 * at bean creation</b>: an administration screen writing them would change
 * nothing until the next restart, which is worse than having no screen — it
 * looks like it worked. So the property became the default and the stored
 * setting became the value, and every value is read where it is used rather
 * than captured in a field.
 *
 * <p>
 * The precedence is deliberate and in this order: what an administrator saved,
 * else what the deployment set as a property, else the coded default. An
 * operator who manages the instance through {@code exo.properties} keeps
 * working exactly as before until someone saves the screen.
 *
 * <p>
 * The sweep's cron is <i>not</i> here. A cron cannot be changed at runtime —
 * Spring reads it when the schedule is built — so offering it on a screen
 * would be the same lie this class exists to avoid.
 */
@Service
public class CaldavTuningService {

  /** Where the saved values live: global, not per user. */
  public static final Scope    TUNING_SCOPE   = Scope.APPLICATION.id("CaldavSyncTuning");

  private static final String  THROTTLE_KEY   = "throttleMinutes";

  private static final String  PAST_KEY       = "pastDays";

  private static final String  FUTURE_KEY     = "futureDays";

  private static final String  SWEEP_KEY      = "sweepStaleMinutes";

  private static final String  BATCH_KEY      = "sweepBatchSize";

  /**
   * The widest window an administrator may ask for, in days. A calendar with
   * ten years of history is a full download on a page load, and a number typed
   * into a form must not be able to make every agenda open cost that.
   */
  private static final long    MAX_WINDOW     = 3650L;

  /** The largest page one sweep may take. */
  private static final int     MAX_BATCH      = 500;

  @Autowired
  private SettingService       settingService;

  @Value("${exo.agenda.caldav.sync.throttleMinutes:15}")
  private long                 defaultThrottleMinutes;

  @Value("${exo.agenda.caldav.sync.pastDays:60}")
  private long                 defaultPastDays;

  @Value("${exo.agenda.caldav.sync.futureDays:365}")
  private long                 defaultFutureDays;

  @Value("${exo.agenda.caldav.sync.sweep.staleMinutes:30}")
  private long                 defaultSweepStaleMinutes;

  @Value("${exo.agenda.caldav.sync.sweep.batchSize:50}")
  private int                  defaultSweepBatchSize;

  /**
   * Every value as it stands now.
   *
   * @return the tuning in force
   */
  public CaldavSyncTuning getTuning() {
    return new CaldavSyncTuning(getThrottleMinutes(),
                                getPastDays(),
                                getFutureDays(),
                                getSweepStaleMinutes(),
                                getSweepBatchSize());
  }

  /**
   * Records what an administrator chose.
   *
   * <p>
   * Every value is bounded here rather than in the screen: a form validates
   * what someone types, a service validates what reaches it. A window of ten
   * years is refused because it turns every first page load of the day into a
   * full download of a decade of history.
   *
   * @param tuning what to store
   * @throws IllegalArgumentException when a value is out of range, carrying
   *           the message code the screen shows
   */
  public void saveTuning(CaldavSyncTuning tuning) {
    if (tuning == null) {
      throw new IllegalArgumentException("caldav.tuning.mandatory");
    }
    require(tuning.throttleMinutes() >= 0 && tuning.throttleMinutes() <= 1440, "caldav.tuning.throttleOutOfRange");
    require(tuning.pastDays() >= 0 && tuning.pastDays() <= MAX_WINDOW, "caldav.tuning.pastDaysOutOfRange");
    require(tuning.futureDays() >= 1 && tuning.futureDays() <= MAX_WINDOW, "caldav.tuning.futureDaysOutOfRange");
    require(tuning.sweepStaleMinutes() >= 1 && tuning.sweepStaleMinutes() <= 10080, "caldav.tuning.sweepStaleOutOfRange");
    require(tuning.sweepBatchSize() >= 1 && tuning.sweepBatchSize() <= MAX_BATCH, "caldav.tuning.sweepBatchOutOfRange");
    store(THROTTLE_KEY, tuning.throttleMinutes());
    store(PAST_KEY, tuning.pastDays());
    store(FUTURE_KEY, tuning.futureDays());
    store(SWEEP_KEY, tuning.sweepStaleMinutes());
    store(BATCH_KEY, tuning.sweepBatchSize());
  }

  /**
   * @return how long a synchronisation triggered by opening the agenda stays
   *         valid
   */
  public long getThrottleMinutes() {
    return stored(THROTTLE_KEY, defaultThrottleMinutes);
  }

  /**
   * @return how far back the imported window reaches, in days
   */
  public long getPastDays() {
    return stored(PAST_KEY, defaultPastDays);
  }

  /**
   * @return how far forward the imported window reaches, in days
   */
  public long getFutureDays() {
    return stored(FUTURE_KEY, defaultFutureDays);
  }

  /**
   * @return how long since a successful sync makes a binding worth sweeping
   */
  public long getSweepStaleMinutes() {
    return stored(SWEEP_KEY, defaultSweepStaleMinutes);
  }

  /**
   * @return how many stale bindings one sweep looks at
   */
  public int getSweepBatchSize() {
    return (int) stored(BATCH_KEY, defaultSweepBatchSize);
  }

  /**
   * One value, saved or defaulted.
   *
   * @param key which value
   * @param fallback the property behind it
   * @return the value in force
   */
  private long stored(String key, long fallback) {
    SettingValue<?> value = settingService.get(Context.GLOBAL, TUNING_SCOPE, key);
    if (value == null || value.getValue() == null || StringUtils.isBlank(value.getValue().toString())) {
      return fallback;
    }
    try {
      return Long.parseLong(value.getValue().toString());
    } catch (NumberFormatException e) {
      // Something that is not a number was written into the setting. The
      // property is a better answer than a failure: this is read on the path
      // of every synchronisation.
      return fallback;
    }
  }

  /**
   * @param key which value
   * @param value what to store
   */
  private void store(String key, long value) {
    settingService.set(Context.GLOBAL, TUNING_SCOPE, key, SettingValue.create(String.valueOf(value)));
  }

  /**
   * @param condition what must hold
   * @param messageCode what the screen shows when it does not
   */
  private void require(boolean condition, String messageCode) {
    if (!condition) {
      throw new IllegalArgumentException(messageCode);
    }
  }
}
