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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ThreadPoolExecutor;

import jakarta.annotation.PreDestroy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class CaldavOutboundWorkTest {

  /**
   * How deep the queue is, mirrored from the engine so the bound is asserted
   * rather than assumed.
   */
  private static final int         SEEDING_QUEUE_DEPTH = 64;

  private final CaldavOutboundWork work                = new CaldavOutboundWork();

  @AfterEach
  public void stopTheThread() {
    work.stop();
  }

  /**
   * The outward work is written from one thread, and there is a bound on how
   * many tasks may be waiting for it.
   *
   * <p>
   * The pacing, asserted rather than described. Twenty people connecting after
   * a migration, each on a thread of their own, is what a calendar server reads
   * as an attack — the rig's own proxy was banned by Stalwart's fail2ban for
   * exactly that, and the ban outlives a container restart. Serialised, twenty
   * connects cost the server the same instantaneous rate as one.
   */
  @Test
  public void theOutwardWorkIsWrittenFromOneBoundedThread() {
    ThreadPoolExecutor executor = assertInstanceOf(ThreadPoolExecutor.class, work.executor);

    assertEquals(1, executor.getCorePoolSize(), "one thread, so bursts queue behind each other rather than pile on");
    assertEquals(1, executor.getMaximumPoolSize(), "and it must stay one under load, which is when it matters");
    assertEquals(SEEDING_QUEUE_DEPTH,
                 executor.getQueue().remainingCapacity(),
                 "the queue is bounded: an account turned away is seeded by the sweep, an unbounded queue is never bounded at all");
  }

  /**
   * An account is claimed by one writer at a time, and free again once given
   * back; claiming one account leaves the others free.
   */
  @Test
  public void anAccountIsClaimedByOneWriterAtATime() {
    assertTrue(work.claim(1L));
    assertFalse(work.claim(1L), "a second writer is turned away while the first holds the account");
    assertTrue(work.claim(2L), "another account is not held by the first claim");

    work.release(1L);

    assertTrue(work.claim(1L), "given back, the account can be claimed again");
  }

  /**
   * A burst and a sweep keep each other out, whichever claims first: the same
   * outward work on one account, which run together would list one collection
   * twice (EXO-89821).
   */
  @Test
  void aBurstAndASweepKeepEachOtherOut() {
    assertTrue(work.claimForQueue(1L));
    assertFalse(work.claim(1L), "a sweep leaves an account whose burst is queued or running");
    assertFalse(work.claimForQueue(1L), "a second burst joins the first");

    assertTrue(work.claim(2L));
    assertFalse(work.claimForQueue(2L), "a burst is not queued while a sweep does the account's outward work");
  }

  /**
   * A burst asked for while a space invitation writes one member's copy is
   * queued, not turned away (EXO-91031): that write is made by a task on the
   * outward thread, so the burst queued now runs once it has ended. Turned
   * away, the burst would lose its owed removals until the sweep finds the
   * account due.
   */
  @Test
  void aBurstIsQueuedWhileAMembersWriteHoldsTheAccount() {
    assertTrue(work.claimMemberWrite(1L));

    assertTrue(work.claimForQueue(1L), "a burst asked for during a member's write is queued");
  }

  /**
   * A sweep leaves an account a space invitation is writing one member's copy
   * to, and claims it once the write gives it back (EXO-91031): the sweep runs
   * beside the outward thread, so it would write alongside.
   */
  @Test
  void aSweepLeavesAnAccountAMembersWriteHolds() {
    assertTrue(work.claimMemberWrite(1L));

    assertFalse(work.claim(1L), "a sweep leaves an account held for a member's write");

    work.releaseMemberWrite(1L);

    assertTrue(work.claim(1L), "given back, the account is the sweep's again");
  }

  /**
   * A member's write is not made while a burst is queued or running for the
   * account, a sweep does its outward work, or another member's write holds
   * it (EXO-91031).
   */
  @Test
  void aMembersWriteWaitsForEveryOtherClaim() {
    assertTrue(work.claimForQueue(1L));
    assertFalse(work.claimMemberWrite(1L), "not while a burst is queued or running for the account");
    work.release(1L);
    assertTrue(work.claimMemberWrite(1L), "the burst gone, the write may claim the account");
    assertFalse(work.claimMemberWrite(1L), "not while another member's write holds it");

    assertTrue(work.claim(2L));
    assertFalse(work.claimMemberWrite(2L), "not while a sweep does the account's outward work");
  }

  /**
   * The thread is stopped by the container, through {@code @PreDestroy}. A test
   * calling {@code stop()} by hand cannot see the annotation removed, so this
   * one reads it.
   */
  @Test
  public void theThreadIsStoppedWhenThePlatformGoesDown() throws Exception {
    assertTrue(CaldavOutboundWork.class.getMethod("stop").isAnnotationPresent(PreDestroy.class));
  }
}
