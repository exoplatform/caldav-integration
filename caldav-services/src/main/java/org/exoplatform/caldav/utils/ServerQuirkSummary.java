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
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.caldav.model.ServerQuirkDirection;

/**
 * Reads and writes the rolling summary of what a server has been seen doing,
 * stored as one string on its registration row.
 *
 * <p>
 * <b>The format is deliberately trivial</b> —
 * {@code DIRECTION:PROPERTY=COUNT@LASTSEEN}, entries separated by {@code ;},
 * where the stamp is an epoch day. It has to
 * be read by whoever opens the row in a database client while diagnosing a
 * server, and a JSON blob in a column is read by nobody. It also has to survive
 * being written by an older build and read by a newer one, which it does the
 * only way a format this small can: an entry that cannot be read is dropped and
 * the rest of the summary still parses. An entry written before the stamp
 * existed simply has none, and {@link Tally#UNKNOWN_DAY} says so rather than
 * pretending to a date.
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

  /** Separates an entry's count from the day it was last seen. */
  private static final String DAY_SEPARATOR   = "@";

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
   * How often a behaviour has been seen, and when it was last seen.
   *
   * <p>
   * <b>The stamp is what lets a summary forget.</b> Without it the row only ever
   * grew: a behaviour a server stopped exhibiting stayed on the screen for ever,
   * and — worse — a behaviour the comparison learned to classify differently
   * went on being offered under its old, broader description beside its new one.
   * A live BlueMind account showed exactly that, listing both "drops the
   * organizer of an event with no other participants" and "does not keep
   * ORGANIZER", the second of which would have written an over-broad excusal if
   * anybody had ticked it.
   *
   * @param count how many times it has been seen — a rolling tally,
   *          deliberately approximate
   * @param lastSeenDay the epoch day it was last observed, or
   *          {@link #UNKNOWN_DAY} for an entry stored before the stamp existed
   */
  public record Tally(long count, long lastSeenDay) {

    /** The stamp of an entry written by a build that did not record one. */
    public static final long UNKNOWN_DAY = -1;

    /**
     * Whether this tally has gone unseen for longer than a deployment is
     * willing to keep it.
     *
     * <p>
     * An entry with no stamp is never stale: it was written before the stamp
     * existed, and dropping somebody's history on upgrade to enforce a rule that
     * did not apply when it was recorded would be the wrong bias. The first
     * write after the upgrade gives it one, and the ordinary rule takes over
     * from there.
     *
     * @param today the current epoch day
     * @param retentionDays how long an unseen behaviour is kept
     * @return true when it should be forgotten
     */
    public boolean staleOn(long today, long retentionDays) {
      return lastSeenDay != UNKNOWN_DAY && notSeenWithin(today, retentionDays);
    }

    /**
     * Whether nothing has been seen of this behaviour within a number of days.
     *
     * <p>
     * <b>An entry with no stamp answers true here and false in
     * {@link #staleOn}, and the difference is not an inconsistency.</b> They ask
     * different questions of the same silence. Ageing asks "has this server
     * stopped doing it", where an unknown date must not be allowed to condemn a
     * record nobody has evidence about. Supersession asks "is this record older
     * than the way the comparison now describes the behaviour", and there an
     * unknown date is not missing evidence — it is the evidence: a record with
     * no stamp was written before this mechanism existed, so it cannot be an
     * observation of anything current.
     *
     * @param today the current epoch day
     * @param days the number of days
     * @return true when it has not been seen within them
     */
    public boolean notSeenWithin(long today, long days) {
      return lastSeenDay == UNKNOWN_DAY || today - lastSeenDay > days;
    }

    /**
     * The same tally, seen again today.
     *
     * @param times how many times it was seen
     * @param today the current epoch day
     * @return the updated tally
     */
    public Tally seen(long times, long today) {
      return new Tally(count + times, today);
    }

    /**
     * The same tally with a stamp, for an entry that had none.
     *
     * <p>
     * <b>The day given must be in the past, and the caller is what decides how
     * far.</b> Stamping a record with today says it was seen today, and nothing
     * observed it — the record was merely carried across a write. Doing so once
     * froze every entry written before stamping existed: each looked current,
     * {@link #notSeenWithin} answered false for all of them, and both
     * supersession and ageing were held off for the whole window, on exactly the
     * records that had been sitting longest. It also contradicted the reading
     * {@link #notSeenWithin} is built on, that a record with no stamp predates
     * the mechanism and so cannot be evidence of anything current.
     *
     * <p>
     * The migration therefore dates such a record far enough back to stay
     * superseded-eligible at once, and no further, so what remains of its
     * ageing window is preserved.
     *
     * @param migratedDay the epoch day to attribute to an entry that has no
     *          stamp; must already be back-dated by the caller
     * @return the tally, stamped
     */
    public Tally stamped(long migratedDay) {
      return lastSeenDay == UNKNOWN_DAY ? new Tally(count, migratedDay) : this;
    }
  }

  /**
   * What a summary is allowed to forget on one write.
   *
   * <p>
   * Built by the service, which knows the deployment's windows and the
   * catalogue, and applied by the storage, which knows the row. The rules live
   * here, together, because reading them apart is how the gap below went
   * unnoticed.
   *
   * <p>
   * <b>Two strengths of supersession, and they are not interchangeable.</b> A
   * case <i>observed in this very pass</i> replaces the older record of the same
   * property at once: that is contemporaneous evidence, and the property is
   * demonstrably not being reported under its own name in the same breath. A
   * case merely <i>excused</i> on the server is a permanent statement rather
   * than an observation — somebody decided it, and it stays decided — so it
   * cannot be allowed to erase a record on sight. It replaces the older record
   * only once that record has itself gone quiet for
   * {@link #settledGraceDays}.
   *
   * <p>
   * <b>Why the second strength had to exist at all.</b> Supersession originally
   * fired only from observation, and an excusal is precisely what stops the case
   * being observed: eXo acts on it, the copies converge, and the case falls
   * silent. So the older record could only ever be cleaned up while the problem
   * still existed — fix the problem and the stale entry outlived the fix by the
   * whole retention window, all the while offering an administrator a broader,
   * more dangerous excusal for something already solved. Every reclassification
   * shipped from now on would have had the same shape.
   *
   * <p>
   * <b>Why the grace period is what keeps that safe.</b> An excusal never
   * expires, so without it a genuine, current record of the superseded property
   * would be wiped on the first pass that did not happen to see it — and on a
   * server excused for dropping the organizer of an appointment, that record is
   * exactly the organizer vanishing from a real meeting, which EXO-89775 went
   * out of its way to keep visible. With it, a behaviour that is still happening
   * refreshes its stamp and stays; one frozen since before the reclassification
   * does not.
   *
   * @param supersededNow properties whose records a case observed in this pass
   *          replaces, at once
   * @param supersededWhenSettled properties whose records a case excused on this
   *          server replaces, once they have gone quiet
   * @param settledGraceDays how long a record survives after the case that
   *          replaces it was excused
   * @param retentionDays how long any behaviour is kept after it was last seen
   * @param today the current epoch day
   */
  public record Retention(Set<String> supersededNow,
                          Set<String> supersededWhenSettled,
                          long settledGraceDays,
                          long retentionDays,
                          long today) {

    /**
     * Whether one stored observation no longer belongs in the summary.
     *
     * <p>
     * Says nothing about an excusal in force: that exemption is the row's, and
     * the caller applies it before asking — see the storage's own merge.
     *
     * @param observation what was seen
     * @param tally how often, and when last
     * @return true when the record should go
     */
    public boolean forgets(Observation observation, Tally tally) {
      String property = observation.property();
      if (supersededNow != null && supersededNow.contains(property)) {
        return true;
      }
      if (supersededWhenSettled != null && supersededWhenSettled.contains(property)
          && tally.notSeenWithin(today, settledGraceDays)) {
        return true;
      }
      return tally.staleOn(today, retentionDays);
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
  public static Map<Observation, Tally> parse(String summary) {
    Map<Observation, Tally> observations = new LinkedHashMap<>();
    if (StringUtils.isBlank(summary)) {
      return observations;
    }
    for (String entry : StringUtils.split(summary, ENTRY_SEPARATOR)) {
      Map.Entry<Observation, Tally> parsed = parseEntry(entry);
      if (parsed != null) {
        observations.merge(parsed.getKey(),
                           parsed.getValue(),
                           (first, second) -> new Tally(first.count() + second.count(),
                                                        Math.max(first.lastSeenDay(), second.lastSeenDay())));
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
  public static String format(Map<Observation, Tally> observations) {
    if (observations == null || observations.isEmpty()) {
      return null;
    }
    String formatted = observations.entrySet()
                                   .stream()
                                   .filter(entry -> entry.getKey() != null && entry.getValue() != null
                                       && entry.getValue().count() > 0)
                                   .sorted(Comparator.comparingLong((Map.Entry<Observation, Tally> entry) -> entry.getValue()
                                                                                                                  .count())
                                                     .reversed()
                                                     .thenComparing(entry -> entry.getKey().property()))
                                   .limit(MAX_ENTRIES)
                                   .map(ServerQuirkSummary::formatEntry)
                                   .collect(Collectors.joining(ENTRY_SEPARATOR));
    return StringUtils.defaultIfBlank(formatted, null);
  }

  /**
   * Writes one entry, its stamp omitted when there is none to write.
   *
   * @param entry the observation and its tally
   * @return the stored entry
   */
  private static String formatEntry(Map.Entry<Observation, Tally> entry) {
    String written = entry.getKey().direction().name() + KEY_SEPARATOR + entry.getKey().property() + COUNT_SEPARATOR
        + entry.getValue().count();
    return entry.getValue().lastSeenDay() == Tally.UNKNOWN_DAY ? written
                                                               : written + DAY_SEPARATOR + entry.getValue().lastSeenDay();
  }

  /**
   * Reads one {@code DIRECTION:PROPERTY=COUNT@LASTSEEN} entry, stamp optional.
   *
   * @param entry the stored entry
   * @return the observation and its tally, or null when the entry cannot be
   *         read
   */
  private static Map.Entry<Observation, Tally> parseEntry(String entry) {
    int count = entry.lastIndexOf(COUNT_SEPARATOR);
    int key = entry.indexOf(KEY_SEPARATOR);
    if (count < 0 || key < 0 || key > count) {
      return null;
    }
    ServerQuirkDirection direction = direction(entry.substring(0, key));
    Observation observation = Observation.of(direction, entry.substring(key + KEY_SEPARATOR.length(), count));
    String tail = entry.substring(count + COUNT_SEPARATOR.length());
    int day = tail.indexOf(DAY_SEPARATOR);
    Long seen = count(day < 0 ? tail : tail.substring(0, day));
    if (observation == null || seen == null) {
      return null;
    }
    Long lastSeen = day < 0 ? null : count(tail.substring(day + DAY_SEPARATOR.length()));
    return Map.entry(observation, new Tally(seen, lastSeen == null ? Tally.UNKNOWN_DAY : lastSeen));
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
