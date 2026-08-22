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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.service.CaldavSyncService;
import org.exoplatform.caldav.service.CaldavTuningService;

/**
 * The scheduled sweep, which is glue and must stay glue.
 *
 * <p>
 * There is one behaviour worth pinning here and it is not the sweeping: that
 * the job reads its bounds <i>at each run</i> rather than holding them. Held
 * in fields they would be captured when the bean was built, and an
 * administrator changing them from the administration screen would see the
 * next restart behave differently, not the next run.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavSyncSweepJobTest {

  @Mock
  private CaldavSyncService   caldavSyncService;

  @Mock
  private CaldavTuningService caldavTuningService;

  @InjectMocks
  private CaldavSyncSweepJob  job;

  @Test
  public void theBoundsAreReadAtEachRun() {
    when(caldavTuningService.getSweepStaleMinutes()).thenReturn(30L, 45L);
    when(caldavTuningService.getSweepBatchSize()).thenReturn(50, 20);

    job.sweep();
    job.sweep();

    verify(caldavSyncService).sweepDueAccounts(30L, 50);
    verify(caldavSyncService).sweepDueAccounts(45L, 20);
  }

  @Test
  public void aRunThatSweepsNothingIsSilent() {
    // Most runs on most instances. A line per run would drown the log in
    // notices that nothing happened.
    when(caldavTuningService.getSweepStaleMinutes()).thenReturn(30L);
    when(caldavTuningService.getSweepBatchSize()).thenReturn(50);
    when(caldavSyncService.sweepDueAccounts(30L, 50)).thenReturn(0);

    assertDoesNotThrow(() -> job.sweep());
  }

  @Test
  public void theJobDelegatesAndHoldsNoLogicOfItsOwn() {
    // A scheduled task resolves what to do and hands it to the service. If
    // this ever needs more than one call, the decision has moved into the job
    // and belongs back in the service.
    when(caldavTuningService.getSweepStaleMinutes()).thenReturn(30L);
    when(caldavTuningService.getSweepBatchSize()).thenReturn(50);
    when(caldavSyncService.sweepDueAccounts(30L, 50)).thenReturn(3);

    job.sweep();

    verify(caldavSyncService).sweepDueAccounts(30L, 50);
  }
}
