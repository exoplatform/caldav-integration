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

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.model.MirrorVerification;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * <h2>The last pass over each user's copies, kept so somebody can look.</h2>
 *
 * <p>
 * <b>What it is for.</b> An administrator changing where meeting copies are
 * written (EXO-89762) has, until this exists, exactly one way of finding out
 * whether the change took effect: read the platform log. That makes the
 * add-on's own advice — move to the account's main calendar <i>only once copies
 * synchronise cleanly on this server</i> — an instruction nobody outside
 * operations can follow. One tally per user, on the screen where the
 * synchronisation is already tuned, is what makes it checkable.
 *
 * <p>
 * <b>In memory, and bounded.</b> Beside the verification pass's own repair
 * counts and the relocation service's reported set, for the reason they give:
 * this records what is happening right now, not a fact about an account worth a
 * table and a migration. A restart loses it, and the next synchronisation of
 * each account puts it back. The cap exists so that a deployment with tens of
 * thousands of connected accounts cannot turn an observability aid into a heap
 * problem — past it the oldest report goes, which is also the one least worth
 * reading.
 *
 * <p>
 * <b>It records, it never judges.</b> Nothing here decides anything: no pass
 * reads these reports, no stamp depends on them, and losing every one of them
 * changes no behaviour at all. That is deliberate — an observability store that
 * something depends on stops being safe to drop.
 */
@Service
public class CaldavMirrorReportService {

  private static final Log                    LOG         = ExoLogger.getExoLogger(CaldavMirrorReportService.class);

  /**
   * How many users' reports are kept.
   *
   * <p>
   * A screen nobody scrolls past the first dozen rows of, and a number small
   * enough that resolving every identity when it is opened is a handful of
   * lookups rather than a query storm.
   */
  protected static final int                  MAX_REPORTS = 200;

  @Autowired
  private IdentityManager                     identityManager;

  /** The last pass of each user, keyed by identity. */
  private final Map<Long, MirrorPassReport>   reports     = new ConcurrentHashMap<>();

  /**
   * Records what one pass found and moved for one user, replacing whatever that
   * user's previous pass left.
   *
   * <p>
   * The last pass and only the last: a history would answer a question nobody
   * asked ("what was it like an hour ago") at the cost of the one that is
   * asked, which is whether things are right <i>now</i>.
   *
   * @param userIdentityId identity of the user the pass ran for
   * @param verification what the comparison found, never null
   * @param relocation what the move did, never null
   */
  public void record(long userIdentityId, MirrorVerification verification, MirrorRelocation relocation) {
    if (verification == null || relocation == null) {
      return;
    }
    // Only when the map is both full and about to grow: an account that already
    // has a report replaces it, which is the overwhelmingly common case and must
    // not pay for a scan.
    if (reports.size() >= MAX_REPORTS && !reports.containsKey(userIdentityId)) {
      evictOldest();
    }
    reports.put(userIdentityId, new MirrorPassReport(userIdentityId, null, null, new Date(), verification, relocation));
  }

  /**
   * Forgets what is known about one user's copies.
   *
   * <p>
   * For the account that disconnects: a tally naming a user who no longer has a
   * connected account is not a stale number, it is a wrong one.
   *
   * @param userIdentityId identity of the user
   */
  public void forget(long userIdentityId) {
    reports.remove(userIdentityId);
  }

  /**
   * The last pass of every user that has had one, newest first, each carrying
   * the identity of the user it is about.
   *
   * <p>
   * The identities are resolved here rather than when the report was recorded,
   * because recording happens on every synchronisation of every account and
   * reading happens when somebody opens a drawer. A user whose identity cannot
   * be resolved keeps their report and loses only their name — the tally is
   * still true, and dropping the row would hide a problem rather than show one.
   *
   * @return the reports, newest first, never null
   */
  public List<MirrorPassReport> getReports() {
    return reports.values()
                  .stream()
                  .sorted(Comparator.comparing(MirrorPassReport::at, Comparator.nullsLast(Comparator.reverseOrder())))
                  .map(this::named)
                  .toList();
  }

  /**
   * The same report with its user's login and display name filled in.
   *
   * @param report a recorded report
   * @return the report, named when the identity could be resolved
   */
  private MirrorPassReport named(MirrorPassReport report) {
    try {
      Identity identity = identityManager.getIdentity(String.valueOf(report.userIdentityId()));
      if (identity == null) {
        return report;
      }
      String fullName = identity.getProfile() == null ? null : identity.getProfile().getFullName();
      return report.named(StringUtils.trimToNull(identity.getRemoteId()), StringUtils.trimToNull(fullName));
    } catch (RuntimeException e) {
      LOG.debug("The identity of user {} could not be resolved for the mirror report", report.userIdentityId(), e);
      return report;
    }
  }

  /**
   * Drops the report that has been sitting there longest.
   *
   * <p>
   * Approximate on purpose: two passes finishing at the same instant may evict
   * one report more than needed, and that costs a row on a screen. A lock
   * around the whole map to make it exact would cost every synchronisation in
   * the deployment.
   */
  private void evictOldest() {
    reports.values()
           .stream()
           .min(Comparator.comparing(MirrorPassReport::at, Comparator.nullsFirst(Comparator.naturalOrder())))
           .ifPresent(oldest -> reports.remove(oldest.userIdentityId()));
  }
}
