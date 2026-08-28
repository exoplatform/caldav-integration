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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.model.MirrorVerification;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The tallies an administrator reads to know whether a change of destination
 * took effect (EXO-89762).
 *
 * <p>
 * The point of this store is that it makes a measurement checkable by somebody
 * without log access, so what is pinned here is the properties that decide
 * whether a number on that screen means anything: one row per user and the
 * LATEST one, a bound that cannot grow without limit, and the fact that an
 * unresolvable identity costs a name and never a row.
 */
@ExtendWith(MockitoExtension.class)
class CaldavMirrorReportServiceTest {

  @Mock
  private IdentityManager            identityManager;

  @InjectMocks
  private CaldavMirrorReportService  caldavMirrorReportService;

  /** A pass that compared ten copies and rewrote one. */
  private static MirrorVerification verification() {
    return new MirrorVerification(10, 1, 2, 0, 1, 0);
  }

  /** A pass that moved copies to a new destination. */
  private static MirrorRelocation relocation() {
    return new MirrorRelocation("/dav/cal/joe/main/", 4, 1, 0, 0);
  }

  /**
   * Makes the identity manager answer for one user.
   *
   * @param userIdentityId identity to answer for
   * @param remoteId the login
   * @param fullName the display name
   */
  private void identity(long userIdentityId, String remoteId, String fullName) {
    Identity identity = new Identity(String.valueOf(userIdentityId));
    identity.setRemoteId(remoteId);
    Profile profile = new Profile(identity);
    profile.setProperty(Profile.FULL_NAME, fullName);
    identity.setProfile(profile);
    lenient().when(identityManager.getIdentity(String.valueOf(userIdentityId))).thenReturn(identity);
  }

  @Test
  void keepsTheLastPassOfEachUserAndNamesThem() {
    identity(1L, "joe", "Joe Doe");

    caldavMirrorReportService.record(1L, verification(), relocation());

    List<MirrorPassReport> reports = caldavMirrorReportService.getReports();
    assertEquals(1, reports.size());
    assertEquals(1L, reports.get(0).userIdentityId());
    assertEquals("joe", reports.get(0).username());
    assertEquals("Joe Doe", reports.get(0).fullName());
    assertEquals(10, reports.get(0).verification().checked());
    assertEquals(4, reports.get(0).relocation().moved());
  }

  @Test
  void replacesAUsersPreviousPassRatherThanPilingHistoryUp() {
    // The question the screen answers is "is it right NOW". A history would
    // answer a question nobody asked, and would push the current state off the
    // top of the list on the accounts that synchronise most.
    identity(1L, "joe", "Joe Doe");

    caldavMirrorReportService.record(1L, verification(), relocation());
    caldavMirrorReportService.record(1L, new MirrorVerification(3, 0, 0, 0, 0, 0), MirrorRelocation.deferred());

    List<MirrorPassReport> reports = caldavMirrorReportService.getReports();
    assertEquals(1, reports.size());
    assertEquals(3, reports.get(0).verification().checked());
    assertFalse(reports.get(0).relocated());
  }

  @Test
  void tellsAPassThatMovedCopiesFromAPassThatOwedNoMove() {
    // The ordinary pass owes no change of destination, so its relocation is
    // deferred and every count is zero. Reported as "0 moved" on every row it
    // would bury the accounts where a change is genuinely still working through
    // - which is the only thing this screen exists to show.
    identity(1L, "joe", "Joe Doe");
    identity(2L, "ann", "Ann Roe");

    caldavMirrorReportService.record(1L, verification(), relocation());
    caldavMirrorReportService.record(2L, verification(), MirrorRelocation.deferred());

    List<MirrorPassReport> reports = caldavMirrorReportService.getReports();
    assertTrue(reports.stream().filter(report -> report.userIdentityId() == 1L).allMatch(MirrorPassReport::relocated));
    assertFalse(reports.stream().filter(report -> report.userIdentityId() == 2L).allMatch(MirrorPassReport::relocated));
  }

  @Test
  void keepsTheRowOfAUserWhoseIdentityCannotBeResolved() {
    // Dropping it would hide a problem rather than show one: the tally is still
    // true, and an account whose identity has gone is exactly the sort of thing
    // an administrator wants to see on this screen.
    when(identityManager.getIdentity(anyString())).thenReturn(null);

    caldavMirrorReportService.record(9L, verification(), relocation());

    List<MirrorPassReport> reports = caldavMirrorReportService.getReports();
    assertEquals(1, reports.size());
    assertEquals(9L, reports.get(0).userIdentityId());
    assertNull(reports.get(0).username());
    assertNull(reports.get(0).fullName());
  }

  @Test
  void survivesAnIdentityManagerThatThrows() {
    when(identityManager.getIdentity(anyString())).thenThrow(new IllegalStateException("no container"));

    caldavMirrorReportService.record(9L, verification(), relocation());

    assertEquals(1, caldavMirrorReportService.getReports().size());
  }

  @Test
  void forgetsWhatItKnewAboutAnAccountThatDisconnected() {
    identity(1L, "joe", "Joe Doe");
    caldavMirrorReportService.record(1L, verification(), relocation());

    caldavMirrorReportService.forget(1L);

    assertTrue(caldavMirrorReportService.getReports().isEmpty());
  }

  @Test
  void neverGrowsPastItsBound() {
    // An observability aid on a deployment with tens of thousands of connected
    // accounts must not become a heap problem. Past the bound the oldest report
    // goes, which is also the one least worth reading.
    lenient().when(identityManager.getIdentity(anyString())).thenReturn(null);

    for (long user = 1; user <= CaldavMirrorReportService.MAX_REPORTS + 50; user++) {
      caldavMirrorReportService.record(user, verification(), MirrorRelocation.deferred());
    }

    List<MirrorPassReport> reports = caldavMirrorReportService.getReports();
    assertEquals(CaldavMirrorReportService.MAX_REPORTS, reports.size());
    // And it is the newest that survived: the very last account recorded is
    // still there.
    assertTrue(reports.stream()
                      .anyMatch(report -> report.userIdentityId() == CaldavMirrorReportService.MAX_REPORTS + 50L));
  }

  @Test
  void recordsNothingWhenThereIsNothingToRecord() {
    caldavMirrorReportService.record(1L, null, relocation());
    caldavMirrorReportService.record(1L, verification(), null);

    assertTrue(caldavMirrorReportService.getReports().isEmpty());
  }
}
