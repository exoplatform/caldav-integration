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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.service.CaldavSyncService;
import org.exoplatform.caldav.service.CaldavTuningService;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;

/**
 * The scheduled sweep, which is glue and must stay glue.
 *
 * <p>
 * There is one behaviour worth pinning here and it is not the sweeping: that
 * the job reads its bounds <i>at each run</i> rather than holding them. Held
 * in fields they would be captured when the bean was built, and an
 * administrator changing them from the administration screen would see the
 * next restart behave differently, not the next run.
 *
 * <p>
 * <b>Why a container is stated here at all.</b> {@code sweep()} carries
 * {@code @ContainerTransactional}, and that aspect is woven into the class under
 * test: it reads the current container and, finding the JVM-wide root one a unit
 * test ends up with, calls {@code PortalContainer.getInstance()} — which in a
 * unit test tries to build a real portal and dies on the first optional add-on
 * missing from this module's classpath. That is the annotation working, not
 * failing: establishing the container is exactly what it is for, and exactly
 * what the deprecated {@code @ExoTransactional} it replaced could not do on a
 * scheduler thread. So the condition is <i>stated</i> — a container that is not
 * the root one — with the same {@code mockStatic} discipline
 * {@code EventPropagationWiringTest} uses, scoped to this class and to this
 * thread.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavSyncSweepJobTest {

  @Mock
  private CaldavSyncService     caldavSyncService;

  @Mock
  private CaldavTuningService   caldavTuningService;

  @Mock
  private ExoContainer          container;

  @InjectMocks
  private CaldavSyncSweepJob    job;

  private MockedStatic<ExoContainerContext> containerContext;

  /**
   * States a container the woven aspect can work with, so that these tests
   * exercise the job rather than the platform's boot.
   */
  @BeforeEach
  public void establishAContainer() {
    containerContext = mockStatic(ExoContainerContext.class);
    containerContext.when(ExoContainerContext::getCurrentContainer).thenReturn(container);
  }

  /**
   * Takes the stated container away again: it is scoped to one test, and a
   * static left mocked would be read by whatever runs next in this fork.
   */
  @AfterEach
  public void forgetTheContainer() {
    containerContext.close();
  }

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
