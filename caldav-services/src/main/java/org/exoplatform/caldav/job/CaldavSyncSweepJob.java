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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.exoplatform.caldav.service.CaldavSyncService;
import org.exoplatform.commons.api.persistence.ExoTransactional;
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

  /**
   * How long since a successful synchronisation makes a binding worth
   * sweeping. Deliberately longer than the on-access throttle: a binding
   * younger than that would have been refused by the throttle anyway, and
   * sweeping it would only race the owner's own page load.
   */
  @Value("${exo.agenda.caldav.sync.sweep.staleMinutes:30}")
  private long              staleMinutes;

  /**
   * How many stale bindings one run looks at. Bindings, not accounts: several
   * of one account collapse into a single pass, so a run covers at least this
   * many bindings and at most this many accounts.
   */
  @Value("${exo.agenda.caldav.sync.sweep.batchSize:50}")
  private int               batchSize;

  /**
   * Synchronises the accounts that have gone longest without one.
   */
  @Scheduled(cron = "${exo.agenda.caldav.sync.sweep.cron:0 */5 * * * ?}")
  @ExoTransactional
  public void sweep() {
    long start = System.currentTimeMillis();
    int swept = caldavSyncService.sweepDueAccounts(staleMinutes, batchSize);
    if (swept > 0) {
      LOG.info("Swept {} CalDAV account(s) in {} ms", swept, System.currentTimeMillis() - start);
    }
  }
}
