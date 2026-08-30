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
package org.exoplatform.caldav;

import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Reads what one class actually wrote to the log, for the few assertions that
 * cannot be made any other way.
 *
 * <p>
 * <b>Use it sparingly and deliberately.</b> Asserting on log output is awkward
 * and brittle, and a suite that does it habitually ends up pinning wording
 * nobody meant to freeze. It exists here for one question — EXO-89798's, "is
 * this refusal recorded as a state or as an incident?" — where the level and
 * the presence of a stack trace <i>are</i> the behaviour under change, and
 * where no other observable distinguishes the two paths. Everything else about
 * that change is tested as ordinary logic: the classification is a pure
 * function with its own tests, and each call site is pinned on what it does.
 *
 * <p>
 * Reached through slf4j's factory rather than by naming a backend: eXo's
 * {@code ExoLogger} resolves to an slf4j logger, and logback is what backs
 * slf4j on this classpath. If that stops being true the cast fails loudly here,
 * rather than every test quietly asserting over an empty list.
 *
 * <p>
 * {@link AutoCloseable} so that a test uses it in try-with-resources and cannot
 * leave the logger turned down or an appender attached for whatever runs next.
 */
public class LogRecorder implements AutoCloseable {

  private final Logger                     logger;

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  private final Level                      restore;

  /**
   * Starts recording what the given class writes, at debug and above.
   *
   * @param recorded the class whose logger is read — the level is turned down
   *          to DEBUG for the duration, so that a line the production
   *          configuration would drop is still seen
   */
  public LogRecorder(Class<?> recorded) {
    logger = (Logger) LoggerFactory.getLogger(recorded);
    restore = logger.getLevel();
    appender.start();
    logger.setLevel(Level.DEBUG);
    logger.addAppender(appender);
  }

  /**
   * What has been written so far, in the order it was written.
   *
   * @return the recorded events, live — reading it again after more logging
   *         shows the later lines too
   */
  public List<ILoggingEvent> events() {
    return appender.list;
  }

  /**
   * The one event recorded, when a test means to assert there was exactly one.
   *
   * @return the only recorded event
   * @throws AssertionError when none or several were written, because a test
   *           that meant "one line" and got three has already failed
   */
  public ILoggingEvent only() {
    if (appender.list.size() != 1) {
      throw new AssertionError("expected exactly one log line, found " + appender.list.size() + ": " + appender.list);
    }
    return appender.list.get(0);
  }

  /**
   * Detaches the recorder and puts the logger back as it was.
   */
  @Override
  public void close() {
    logger.detachAppender(appender);
    appender.stop();
    logger.setLevel(restore);
  }
}
