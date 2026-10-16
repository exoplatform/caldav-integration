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

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

/**
 * The thread outward copies are written from, and the accounts whose outward
 * work is under way.
 *
 * <p>
 * Shared by the synchronisation, whose bursts and sweep write and verify an
 * account's copies, and by the propagation, which writes the copies of the
 * members of a space invited to a meeting (EXO-91031). One thread and one set
 * of claims for both, so that two of them never write the same account's
 * copies at once: the claim is what keeps a sweep, a burst and a space
 * invitation from each reading "no copy yet" and writing the same meeting
 * twice. A bean of its own because the synchronisation already depends on the
 * propagation, and the propagation cannot depend back on it.
 */
@Component
public class CaldavOutboundWork {

  /**
   * How many tasks may be waiting for the thread before the next one is
   * turned away.
   *
   * <p>
   * A bound, not a buffer. Turning an account away costs it nothing it cannot
   * recover: it holds a binding by the time it gets here, so the sweep can see
   * it and will seed it within the account-due interval. A meeting whose space
   * members are turned away reaches them through the same sweep, but only once
   * it is within the sweep's window ({@code seedDays}). Holding an unbounded
   * queue of them, on the other hand, costs memory and turns a morning where
   * everyone connects at once into an hour of writes nobody asked for.
   */
  private static final int SEEDING_QUEUE_DEPTH = 64;

  /**
   * The single thread every seeded copy is written from.
   *
   * <p>
   * One thread, platform-wide, on purpose. A fresh account can be up to
   * {@code seedLimit} events per question and two questions per pass, so one
   * connect is already a few hundred writes; twenty people connecting after a
   * migration, each on their own thread, is what a calendar server reads as an
   * attack, and the test rig's proxy was in fact banned by Stalwart's fail2ban
   * for exactly that — a ban that outlives a container restart. Serialised,
   * twenty connects cost the server the same instantaneous rate as one, and
   * they cost it in the order people connected.
   *
   * <p>
   * Package-visible so a test can put a recording executor here and say what
   * happened on the caller's thread and what was merely handed over — a
   * distinction a same-thread executor cannot make. Nothing outside this
   * package replaces it.
   */
  ExecutorService          executor            = newOutboundExecutor();

  /**
   * The accounts whose outward work is already queued or already running,
   * wherever it is being run from.
   *
   * <p>
   * Pressing a button twice queues one burst and not two, and a sweep
   * verifying while a burst verifies would be two passes listing one
   * collection, both counting the same drift as a repair (EXO-89821). One key,
   * honoured by every writer, is what keeps them apart.
   */
  private final Set<Long>  inFlight            = ConcurrentHashMap.newKeySet();

  /**
   * Claims an account's outward work for the caller.
   *
   * @param userIdentityId the account's user
   * @return true when the caller now holds it; false when somebody else does
   */
  public boolean claim(long userIdentityId) {
    return inFlight.add(userIdentityId);
  }

  /**
   * Gives an account's outward work back.
   *
   * @param userIdentityId the account's user
   */
  public void release(long userIdentityId) {
    inFlight.remove(userIdentityId);
  }

  /**
   * Hands a task to the thread.
   *
   * @param task the work
   * @throws RejectedExecutionException when the queue is full or the platform
   *           is going down
   */
  public void execute(Runnable task) {
    executor.execute(task);
  }

  /**
   * Stops accepting outward work when the platform is going down.
   *
   * <p>
   * {@code shutdown} and not {@code shutdownNow}: a copy half written is a copy
   * the verification pass has to reconcile later, so the task in flight is let
   * finish rather than interrupted. The tasks already queued keep running
   * until the JVM exits, the thread being a daemon; those it does not reach
   * are dropped, and the sweep writes their copies after the next start, for
   * the meetings within its window ({@code seedDays}).
   */
  @PreDestroy
  public void stop() {
    executor.shutdown();
  }

  /**
   * The one thread seeded copies are written from.
   *
   * <p>
   * A single thread with a bounded queue, and an overflow that is refused
   * rather than dropped in silence: the caller turns a refusal into a line at
   * debug and leaves the account to the sweep. Daemon, because a burst of
   * copies is never a reason to hold a shutdown open.
   *
   * @return the executor the outward work runs on
   */
  private static ExecutorService newOutboundExecutor() {
    return new ThreadPoolExecutor(1,
                                  1,
                                  0L,
                                  TimeUnit.MILLISECONDS,
                                  new ArrayBlockingQueue<>(SEEDING_QUEUE_DEPTH),
                                  runnable -> {
                                    // Named for what it was first built to do, and left named
                                    // that way on purpose: it is what a running deployment's log
                                    // lines and thread dumps are already read by.
                                    Thread thread = new Thread(runnable, "caldav-seeding");
                                    thread.setDaemon(true);
                                    return thread;
                                  });
  }
}
