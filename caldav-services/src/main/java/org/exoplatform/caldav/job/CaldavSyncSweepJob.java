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
package org.exoplatform.caldav.job;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.meeds.common.ContainerTransactional;

import org.exoplatform.caldav.service.CaldavSyncService;
import org.exoplatform.caldav.service.CaldavTuningService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Keeps connected CalDAV accounts fresh without waiting for their owner.
 *
 * <p>
 * Until this ran, synchronisation only ever happened because someone opened
 * their agenda — and it happened <i>while</i> they waited, since the page
 * talks to a server outside the platform before it can list anything. The
 * throttle kept that off every render, at the price of making the first open
 * after a quiet stretch the slow one; and an account whose events changed
 * elsewhere stayed stale until its owner happened to look.
 *
 * <p>
 * The job holds no logic of its own: it reads its two bounds from properties
 * and hands the work to the service, as a scheduled task should.
 *
 * <p>
 * Setting the cron to {@code -} turns the sweep off entirely, which is the
 * right answer for an instance with a handful of CalDAV users: their own page
 * loads already synchronise them.
 */
@Component
public class CaldavSyncSweepJob {

  private static final Log  LOG = ExoLogger.getExoLogger(CaldavSyncSweepJob.class);

  @Autowired
  private CaldavSyncService caldavSyncService;

  @Autowired
  private CaldavTuningService caldavTuningService;

  /**
   * Synchronises the accounts that have gone longest without one.
   *
   * <p>
   * <b>{@code @ContainerTransactional}, not the deprecated
   * {@code @ExoTransactional}.</b> They are not two spellings of one thing. The
   * legacy aspect <i>requires</i> a container already bound to the thread and
   * throws when there is none; this one <i>establishes</i> it — it reads the
   * current container, falls back to the portal container, and runs the request
   * lifecycle around the call. A scheduler thread is exactly the case with
   * nothing bound, which makes the legacy annotation the wrong one on a job by
   * construction and the right one on nothing new at all.
   */
  @Scheduled(cron = "${exo.agenda.caldav.sync.sweep.cron:0 */5 * * * ?}")
  @ContainerTransactional
  public void sweep() {
    long start = System.currentTimeMillis();
    // Read at each run, not captured in a field: an administrator changing
    // these from the administration screen must see the next run behave
    // differently, not the next restart.
    int swept = caldavSyncService.sweepDueAccounts(caldavTuningService.getSweepStaleMinutes(),
                                                   caldavTuningService.getSweepBatchSize());
    if (swept > 0) {
      LOG.info("Swept {} CalDAV account(s) in {} ms", swept, System.currentTimeMillis() - start);
    }
  }
}
