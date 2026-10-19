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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
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
 * members of a space invited to a meeting (EXO-91031). One thread and one
 * place for the claims of both, so that two of them never write the same
 * account's copies at once: the claim is what keeps a sweep, a burst and a space
 * invitation from each reading "no copy yet" and writing the same meeting
 * twice. A bean of its own because the synchronisation already depends on the
 * propagation, and the propagation cannot depend back on it.
 *
 * <p>
 * Two kinds of claim, because the writers do not all run on this thread. The
 * sweep runs on its own job's thread, beside this one, and a burst is queued
 * here: both claim the account's outward work ({@link #claim},
 * {@link #claimForQueue}). A space invitation's task claims a member's account
 * for one write ({@link #claimMemberWrite}), and runs here too. The sweep
 * honours both kinds. A burst honours only the first: a member's write is made
 * by a task on this thread, so a burst queued while it is held runs once that
 * task has ended, never alongside the write.
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
   *
   * <p>
   * Read and changed under this object's lock only, with
   * {@link #memberWrites}: a claim reads one set and adds to the other, and
   * two claims made from two threads must not each read the other's set before
   * either adds to its own.
   */
  private final Set<Long>  inFlight            = new HashSet<>();

  /**
   * The accounts a space invitation's task is writing one member's copy to
   * (EXO-91031), held for that write only.
   *
   * <p>
   * Under this object's lock, as {@link #inFlight} is.
   */
  private final Set<Long>  memberWrites        = new HashSet<>();

  /**
   * Claims an account's outward work for a caller that runs beside this
   * thread: the sweep.
   *
   * <p>
   * Refused while anybody holds the account, a space invitation's write to it
   * included: the sweep would write alongside it.
   *
   * @param userIdentityId the account's user
   * @return true when the caller now holds it; false when somebody else does
   */
  public synchronized boolean claim(long userIdentityId) {
    return !memberWrites.contains(userIdentityId) && inFlight.add(userIdentityId);
  }

  /**
   * Claims an account's outward work for a task about to be handed to this
   * thread: a burst.
   *
   * <p>
   * Refused while a burst is already queued or running for the account, or a
   * sweep is doing its outward work. Not refused by a space invitation's write
   * to it: that write is made by a task on this thread, so the task queued now
   * runs once it has ended. Turned away there, a burst asked for by a
   * connection or a <i>Synchronise now</i> would lose its owed rewrites and
   * removals, its seeding and its verification until the sweep finds the
   * account due, for a write it could never have run alongside.
   *
   * @param userIdentityId the account's user
   * @return true when the caller now holds it; false when another burst or a
   *         sweep does
   */
  public synchronized boolean claimForQueue(long userIdentityId) {
    return inFlight.add(userIdentityId);
  }

  /**
   * Gives back an account's outward work claimed by {@link #claim} or
   * {@link #claimForQueue}.
   *
   * @param userIdentityId the account's user
   */
  public synchronized void release(long userIdentityId) {
    inFlight.remove(userIdentityId);
  }

  /**
   * Claims a member's account for one write of a space invitation's task, which
   * runs on this thread (EXO-91031).
   *
   * <p>
   * Refused while a burst is queued or running for the account, a sweep is
   * doing its outward work, or another such write holds it.
   *
   * @param userIdentityId the member
   * @return true when the caller now holds the account for the write; false
   *         when somebody else holds it
   */
  public synchronized boolean claimMemberWrite(long userIdentityId) {
    return !inFlight.contains(userIdentityId) && memberWrites.add(userIdentityId);
  }

  /**
   * Gives back a member's account claimed by {@link #claimMemberWrite}.
   *
   * @param userIdentityId the member
   */
  public synchronized void releaseMemberWrite(long userIdentityId) {
    memberWrites.remove(userIdentityId);
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
