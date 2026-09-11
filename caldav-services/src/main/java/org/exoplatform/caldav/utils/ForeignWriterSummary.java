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
package org.exoplatform.caldav.utils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

/**
 * How the deployments seen writing into a server's accounts are held on the
 * server's own row (EXO-89824).
 *
 * <p>
 * <b>One bounded string, for the reason the quirk summary gives.</b> It
 * survives a restart, because an empty section after one is an empty section
 * exactly when somebody is investigating; it stays cheap, because it is one
 * short column per server rather than a row per copy; and it costs no extra
 * query, because the drawer already fetches the registration. It is also the
 * only shape that works on more than one node — the detection happens on
 * whichever node ran the pass, and the drawer is served by whichever node took
 * the request.
 *
 * <p>
 * <b>Format</b>: {@code authority@day}, entries separated by {@code ;}, the day
 * an epoch day. {@code ai-contribution-ft.meeds.io@20342;host:8080@20339}. The
 * day is what lets the list forget: a deployment moved to another account stops
 * appearing, and its entry ages out rather than accusing an administrator for
 * ever of something they have already fixed.
 *
 * @see org.exoplatform.caldav.utils.ServerQuirkSummary the sibling this follows
 */
public class ForeignWriterSummary {

  /** What separates two deployments in the stored string. */
  private static final String ENTRY_SEPARATOR = ";";

  /** What separates a deployment from the day it was last seen. */
  private static final String DAY_SEPARATOR   = "@";

  /**
   * How many deployments one server's row holds at most.
   *
   * <p>
   * Five, and the number is a guard rather than a design: an account written by
   * a sixth deployment is an environment nobody should be running, and the
   * point of the column is to say "this is happening, and here is one of them",
   * not to inventory a estate. Keeping the most recently seen is what makes the
   * bound safe — a deployment still writing today is never pushed out by one
   * that stopped weeks ago.
   */
  private static final int    MAX_WRITERS     = 5;

  /**
   * Not instantiable: this class is a format, not a component.
   */
  private ForeignWriterSummary() {
  }

  /**
   * Reads a stored summary.
   *
   * <p>
   * Never throws on a malformed string. The column is evidence written by a
   * background pass and read by a drawer; a row a hand-edit or an older version
   * left unreadable must cost an administrator an entry, never the page. An
   * entry without a day, or with one that is not a number, is dropped rather
   * than guessed at — a wrong date here would age out a live finding or keep a
   * dead one.
   *
   * @param summary the stored value, possibly null or empty
   * @return the deployments and the epoch day each was last seen, most recently
   *         seen first, never null
   */
  public static Map<String, Long> parse(String summary) {
    Map<String, Long> writers = new LinkedHashMap<>();
    if (StringUtils.isBlank(summary)) {
      return writers;
    }
    for (String entry : StringUtils.split(summary, ENTRY_SEPARATOR)) {
      int day = entry.lastIndexOf(DAY_SEPARATOR);
      if (day <= 0) {
        continue;
      }
      String authority = entry.substring(0, day).trim();
      Long lastSeen = day(entry.substring(day + DAY_SEPARATOR.length()));
      if (StringUtils.isNotBlank(authority) && lastSeen != null) {
        writers.put(authority, lastSeen);
      }
    }
    return sorted(writers);
  }

  /**
   * Records that a deployment was seen writing here today, forgetting what has
   * not been seen for long enough.
   *
   * @param stored what the row holds, as {@link #parse} read it
   * @param authority the deployment just seen, or null to age the list without
   *          adding to it
   * @param today the current epoch day
   * @param retentionDays how long an unseen deployment stays on the list
   * @return the new list, most recently seen first, bounded to
   *         {@value #MAX_WRITERS}
   */
  public static Map<String, Long> record(Map<String, Long> stored, String authority, long today, long retentionDays) {
    Map<String, Long> writers = new LinkedHashMap<>(stored);
    if (isStorable(authority)) {
      writers.put(authority.toLowerCase(Locale.ROOT), today);
    }
    writers.entrySet().removeIf(entry -> today - entry.getValue() > retentionDays);
    Map<String, Long> ordered = sorted(writers);
    while (ordered.size() > MAX_WRITERS) {
      // The last one is the least recently seen, sorted() having just ordered
      // them: the one whose absence from the list costs an administrator least.
      String oldest = ordered.keySet().stream().reduce((first, second) -> second).orElse(null);
      ordered.remove(oldest);
    }
    return ordered;
  }

  /**
   * Writes a summary back to the shape the column holds.
   *
   * @param writers the deployments and the epoch day each was last seen
   * @return the value to store, or null when there is nothing to say — null
   *         rather than an empty string, so a server that has never seen one
   *         reads the same as a server whose last entry has just aged out
   */
  public static String format(Map<String, Long> writers) {
    if (writers == null || writers.isEmpty()) {
      return null;
    }
    return writers.entrySet()
                  .stream()
                  .map(entry -> entry.getKey() + DAY_SEPARATOR + entry.getValue())
                  .reduce((first, second) -> first + ENTRY_SEPARATOR + second)
                  .orElse(null);
  }

  /**
   * Whether a deployment's name can be stored as it stands.
   *
   * <p>
   * An authority carrying one of the separators would be read back as two
   * entries, or as an entry with no day — which is why it is refused here
   * rather than escaped: the value comes from a URL in somebody else's
   * calendar, the column is evidence, and a name that cannot survive the round
   * trip is better left out than stored wrong. A host and port never carry
   * either character; a URL with credentials in it would, and is not a shape
   * eXo's own event links take.
   *
   * @param authority the deployment name read off a copy
   * @return true when it is safe to store
   */
  private static boolean isStorable(String authority) {
    return StringUtils.isNotBlank(authority) && !StringUtils.containsAny(authority, ENTRY_SEPARATOR, DAY_SEPARATOR);
  }

  /**
   * The same list, most recently seen first.
   *
   * @param writers the deployments and their days
   * @return a new map in that order
   */
  private static Map<String, Long> sorted(Map<String, Long> writers) {
    Map<String, Long> ordered = new LinkedHashMap<>();
    writers.entrySet()
           .stream()
           .sorted(Map.Entry.<String, Long> comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
           .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
    return ordered;
  }

  /**
   * An epoch day as it was stored.
   *
   * @param written the text after the day separator
   * @return the day, or null when it is not a number
   */
  private static Long day(String written) {
    try {
      return Long.valueOf(written.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
