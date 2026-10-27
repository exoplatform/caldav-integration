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
package org.exoplatform.caldav.client;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * One overall time budget for a sequence of CalDAV calls made on one thread —
 * a landing of a mail's invitation, which holds the user's click: every request
 * {@link HttpCalDavClient} sends while it is open is sent with the smaller of
 * its own timeout and the time left, a request asked for once nothing is left
 * is refused before it leaves ({@link CalDavDeadlineException}), and a response
 * body still being read when the time runs out is cut. The request timeout
 * bounds the connection and the wait for the response's headers; the body read
 * after them is bounded here.
 * <p>
 * Opened with {@link #start}, closed with {@link #close} — a try-with-resources
 * — and per thread: a call made on another thread is not bounded by it. Opening
 * one inside another keeps the earlier end when it is the sooner.
 */
public final class CalDavDeadline implements AutoCloseable {

  private static final Log                          LOG     = ExoLogger.getLogger(CalDavDeadline.class);

  /** The open budget of the current thread, null when none is open. */
  private static final ThreadLocal<CalDavDeadline> CURRENT = new ThreadLocal<>();

  /** Cuts the body reads still running when their budget runs out. */
  private static volatile ScheduledThreadPoolExecutor watchdog;

  /** When the budget runs out, in {@link System#nanoTime()} terms. */
  private final long                                endNanos;

  /** The budget open on this thread before this one, restored on close. */
  private final CalDavDeadline                      previous;

  /** Whether a request was refused or cut because the budget ran out. */
  private boolean                                   spent;

  /**
   * @param endNanos when the budget runs out
   * @param previous the budget it replaces on this thread
   */
  private CalDavDeadline(long endNanos, CalDavDeadline previous) {
    this.endNanos = endNanos;
    this.previous = previous;
  }

  /**
   * Opens a budget on the current thread.
   *
   * @param budget the time the calls made until {@link #close} may take
   *          altogether
   * @return the budget, to close
   */
  public static CalDavDeadline start(Duration budget) {
    CalDavDeadline previous = CURRENT.get();
    long end = System.nanoTime() + Math.max(0L, budget.toNanos());
    if (previous != null && previous.endNanos - end < 0) {
      end = previous.endNanos;
    }
    CalDavDeadline deadline = new CalDavDeadline(end, previous);
    CURRENT.set(deadline);
    return deadline;
  }

  /**
   * The timeout a request sends with: its own, or the time left of the open
   * budget when that is shorter.
   *
   * @param timeout the request's own timeout
   * @param what the request, for the refusal
   * @return the timeout
   * @throws CalDavDeadlineException when the open budget has run out
   */
  static Duration bound(Duration timeout, String what) {
    CalDavDeadline deadline = CURRENT.get();
    if (deadline == null) {
      return timeout;
    }
    long left = deadline.endNanos - System.nanoTime();
    if (left <= 0) {
      deadline.spent = true;
      throw new CalDavDeadlineException("No time is left for " + what);
    }
    return timeout.toNanos() <= left ? timeout : Duration.ofNanos(left);
  }

  /**
   * The time left of the budget open on the current thread, for a write
   * channel that does not send through {@link HttpCalDavClient} — one another
   * add-on contributes for an account ({@code CalendarObjectWriters}) — so
   * that it bounds its own calls by it.
   *
   * @return the time left, {@link Duration#ZERO} once it has run out (the
   *         budget is then spent), null when no budget is open
   */
  public static Duration remaining() {
    CalDavDeadline deadline = CURRENT.get();
    if (deadline == null) {
      return null;
    }
    long left = deadline.endNanos - System.nanoTime();
    if (left <= 0) {
      deadline.spent = true;
      return Duration.ZERO;
    }
    return Duration.ofNanos(left);
  }

  /**
   * Whether the open budget has run out: a failed request then failed for it.
   *
   * @return false when no budget is open
   */
  static boolean ranOut() {
    CalDavDeadline deadline = CURRENT.get();
    if (deadline == null || deadline.endNanos - System.nanoTime() > 0) {
      return false;
    }
    deadline.spent = true;
    return true;
  }

  /**
   * Closes a response body when the open budget runs out, so a read blocked on
   * a server that sends its body slowly ends then.
   *
   * @param body the body being read
   * @return the watch, to cancel once the read ended; null when no budget is
   *         open
   */
  static Future<?> closeWhenSpent(Closeable body) {
    CalDavDeadline deadline = CURRENT.get();
    if (deadline == null) {
      return null;
    }
    return watchdog().schedule(() -> {
      try {
        body.close();
      } catch (IOException e) {
        LOG.debug("A response body cut at its deadline could not be closed", e);
      }
    }, Math.max(0L, deadline.endNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
  }

  /**
   * Fails when the budget has run out, or a request was refused or cut for it
   * — a call that swallows such a failure (a read that leaves "those days as
   * they are") would otherwise let the sequence go on as if it had read.
   *
   * @param what the step about to run, for the refusal
   * @throws CalDavDeadlineException when it has run out
   */
  public void checkNotSpent(String what) {
    if (spent || endNanos - System.nanoTime() <= 0) {
      spent = true;
      throw new CalDavDeadlineException("No time is left for " + what);
    }
  }

  /**
   * Closes the budget: the thread's previous one, if any, applies again.
   */
  @Override
  public void close() {
    if (previous == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(previous);
    }
  }

  /**
   * The thread that cuts late body reads, started on first use: one daemon
   * thread, cancelled watches removed at once.
   *
   * @return the scheduler
   */
  private static ScheduledThreadPoolExecutor watchdog() {
    ScheduledThreadPoolExecutor current = watchdog;
    if (current == null) {
      synchronized (CalDavDeadline.class) {
        current = watchdog;
        if (current == null) {
          current = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "caldav-deadline");
            thread.setDaemon(true);
            return thread;
          });
          current.setRemoveOnCancelPolicy(true);
          watchdog = current;
        }
      }
    }
    return current;
  }
}
