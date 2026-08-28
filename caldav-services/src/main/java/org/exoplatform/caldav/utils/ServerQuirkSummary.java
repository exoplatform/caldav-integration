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
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.caldav.model.ServerQuirkDirection;

/**
 * Reads and writes the rolling summary of what a server has been seen doing,
 * stored as one string on its registration row.
 *
 * <p>
 * <b>The format is deliberately trivial</b> —
 * {@code DIRECTION:PROPERTY=COUNT}, entries separated by {@code ;}. It has to
 * be read by whoever opens the row in a database client while diagnosing a
 * server, and a JSON blob in a column is read by nobody. It also has to survive
 * being written by an older build and read by a newer one, which it does the
 * only way a format this small can: an entry that cannot be read is dropped and
 * the rest of the summary still parses.
 *
 * <p>
 * <b>Bounded, because a column is not a log.</b> At most
 * {@link #MAX_ENTRIES} entries survive a write, the largest counts first, so a
 * server producing a new proprietary property on every copy cannot grow the row
 * without limit. Property names are truncated to {@link #MAX_PROPERTY} — a name
 * longer than that is not a property name anybody will act on, and the column
 * has a length.
 */
public final class ServerQuirkSummary {

  /** How many observations one server's summary keeps. */
  public static final int    MAX_ENTRIES  = 20;

  /** How much of a property name is kept. */
  public static final int    MAX_PROPERTY = 60;

  /** Separates the entries of a summary. */
  private static final String ENTRY_SEPARATOR = ";";

  /** Separates an entry's direction from its property name. */
  private static final String KEY_SEPARATOR   = ":";

  /** Separates an entry's key from its count. */
  private static final String COUNT_SEPARATOR = "=";

  /**
   * Not instantiated: a codec with no state of its own.
   */
  private ServerQuirkSummary() {
  }

  /**
   * One thing a server was seen doing, as the summary keys it.
   *
   * @param direction which way the divergence pointed
   * @param property the property name it was seen on, upper-cased so two
   *          spellings of one property cannot become two entries
   */
  public record Observation(ServerQuirkDirection direction, String property) {

    /**
     * Builds an observation with its property name in the one spelling the
     * summary stores.
     *
     * @param direction which way the divergence pointed
     * @param property the property name, in any case
     * @return the observation, or null when the property name is blank
     */
    public static Observation of(ServerQuirkDirection direction, String property) {
      if (direction == null || StringUtils.isBlank(property)) {
        return null;
      }
      return new Observation(direction, StringUtils.truncate(property.trim().toUpperCase(Locale.ROOT), MAX_PROPERTY));
    }
  }

  /**
   * Reads a stored summary.
   *
   * <p>
   * Never throws: this parses a value a previous build wrote, and a summary
   * that cannot be read must leave the drawer showing less rather than leave
   * the registration unreadable. An unreadable entry is dropped on its own.
   *
   * @param summary the stored value, may be null or blank
   * @return the observations and their counts, in the order they were stored,
   *         never null
   */
  public static Map<Observation, Long> parse(String summary) {
    Map<Observation, Long> observations = new LinkedHashMap<>();
    if (StringUtils.isBlank(summary)) {
      return observations;
    }
    for (String entry : StringUtils.split(summary, ENTRY_SEPARATOR)) {
      Map.Entry<Observation, Long> parsed = parseEntry(entry);
      if (parsed != null) {
        observations.merge(parsed.getKey(), parsed.getValue(), Long::sum);
      }
    }
    return observations;
  }

  /**
   * Writes a summary back, largest counts first and bounded in both directions.
   *
   * @param observations the observations and their counts
   * @return the value to store, null when there is nothing to store
   */
  public static String format(Map<Observation, Long> observations) {
    if (observations == null || observations.isEmpty()) {
      return null;
    }
    String formatted = observations.entrySet()
                                   .stream()
                                   .filter(entry -> entry.getKey() != null && entry.getValue() != null
                                       && entry.getValue() > 0)
                                   .sorted(Comparator.<Map.Entry<Observation, Long>, Long>comparing(Map.Entry::getValue)
                                                     .reversed()
                                                     .thenComparing(entry -> entry.getKey().property()))
                                   .limit(MAX_ENTRIES)
                                   .map(entry -> entry.getKey().direction().name() + KEY_SEPARATOR
                                       + entry.getKey().property() + COUNT_SEPARATOR + entry.getValue())
                                   .collect(Collectors.joining(ENTRY_SEPARATOR));
    return StringUtils.defaultIfBlank(formatted, null);
  }

  /**
   * Reads one {@code DIRECTION:PROPERTY=COUNT} entry.
   *
   * @param entry the stored entry
   * @return the observation and its count, or null when the entry cannot be
   *         read
   */
  private static Map.Entry<Observation, Long> parseEntry(String entry) {
    int count = entry.lastIndexOf(COUNT_SEPARATOR);
    int key = entry.indexOf(KEY_SEPARATOR);
    if (count < 0 || key < 0 || key > count) {
      return null;
    }
    ServerQuirkDirection direction = direction(entry.substring(0, key));
    Observation observation = Observation.of(direction, entry.substring(key + KEY_SEPARATOR.length(), count));
    Long seen = count(entry.substring(count + COUNT_SEPARATOR.length()));
    if (observation == null || seen == null) {
      return null;
    }
    return Map.entry(observation, seen);
  }

  /**
   * Reads a direction name, tolerating one this build does not know.
   *
   * @param name the stored direction name
   * @return the direction, or null when this build does not know it
   */
  private static ServerQuirkDirection direction(String name) {
    for (ServerQuirkDirection direction : ServerQuirkDirection.values()) {
      if (direction.name().equalsIgnoreCase(name.trim())) {
        return direction;
      }
    }
    return null;
  }

  /**
   * Reads an entry's count.
   *
   * @param value the stored count
   * @return the count, or null when it is not a positive number
   */
  private static Long count(String value) {
    try {
      long seen = Long.parseLong(value.trim());
      return seen > 0 ? seen : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
