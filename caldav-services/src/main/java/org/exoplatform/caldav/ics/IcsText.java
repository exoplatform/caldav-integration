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
package org.exoplatform.caldav.ics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

/**
 * The small conversions the ICS engine needs, kept apart so the writer reads
 * as the shape of a calendar object rather than as string handling.
 *
 * <p>
 * Property-value escaping is deliberately absent: ical4j escapes on
 * serialisation, and a second pass would double every backslash. What remains
 * here is what no library can decide for us — what a fragment of HTML means as
 * plain text, what an agenda response means as a PARTSTAT, and what a reminder
 * means in minutes.
 */
public final class IcsText {

  /** PARTSTAT values agenda can produce that RFC 5545 also defines. */
  private static final Set<String> KNOWN_PART_STATS = Set.of("ACCEPTED", "DECLINED", "TENTATIVE");

  /** The PARTSTAT default, both what RFC 5545 assumes and what agenda calls NEEDS_ACTION. */
  private static final String      NEEDS_ACTION     = "NEEDS-ACTION";

  /** Tags that end a line of text when they close. */
  private static final Pattern     BLOCK_END        = Pattern.compile("(?i)<\\s*/\\s*(p|div|li|tr|h[1-6])\\s*>");

  /** Line breaks, in every spelling a browser accepts. */
  private static final Pattern     LINE_BREAK       = Pattern.compile("(?i)<\\s*br\\s*/?\\s*>");

  /** Any remaining markup, once the structural tags have become newlines. */
  private static final Pattern     ANY_TAG          = Pattern.compile("<[^>]*>");

  /** Three or more consecutive newlines, collapsed to a paragraph break. */
  private static final Pattern     EXTRA_BREAKS     = Pattern.compile("\\n{3,}");

  /** Minutes in an hour, for the reminder conversion. */
  private static final long        MINUTES_PER_HOUR = 60;

  /** Hours in a day, for the reminder conversion. */
  private static final long        HOURS_PER_DAY    = 24;

  /** Days in a week, for the reminder conversion. */
  private static final long        DAYS_PER_WEEK    = 7;

  private IcsText() {
    // utility class
  }

  /**
   * A description as plain text: the structural tags become line breaks, the
   * rest of the markup is dropped and the entities are resolved.
   *
   * <p>
   * The browser did this by assigning to innerHTML and reading textContent,
   * which is a parser. There is none here, so the same three transformations
   * are applied explicitly — break tags, closing block tags, then everything
   * else — and entities are unescaped afterwards rather than before, so that
   * an escaped {@code &lt;} in the source does not become a tag that the tag
   * removal then eats.
   *
   * @param html the description as agenda holds it
   * @return the text, with paragraph breaks preserved and trimmed
   */
  public static String htmlToText(String html) {
    if (StringUtils.isBlank(html)) {
      return "";
    }
    String text = LINE_BREAK.matcher(html).replaceAll("\n");
    text = BLOCK_END.matcher(text).replaceAll("\n");
    text = ANY_TAG.matcher(text).replaceAll("");
    text = StringEscapeUtils.unescapeHtml4(text);
    return EXTRA_BREAKS.matcher(text).replaceAll("\n\n").trim();
  }

  /**
   * The PARTSTAT token for an agenda attendee response.
   *
   * <p>
   * Agenda's own values are the RFC 5545 tokens already, up to the underscore
   * spelling the enum constant uses. Anything else becomes NEEDS-ACTION, the
   * RFC default, rather than an invalid token a strict reader would reject.
   *
   * @param response the attendee's response as agenda holds it
   * @return a valid PARTSTAT value
   */
  public static String partStat(String response) {
    String token = StringUtils.upperCase(StringUtils.defaultString(response)).replace('_', '-');
    return KNOWN_PART_STATS.contains(token) ? token : NEEDS_ACTION;
  }

  /**
   * The agenda response an iCalendar PARTSTAT stands for — the exact inverse
   * of {@link #partStat(String)}, needed since a copy became something the
   * user can answer on (EXO-89681).
   *
   * <p>
   * Deliberately narrower than the write direction. Writing maps anything
   * unknown to the RFC default, because an object has to say <i>something</i>;
   * reading maps only the four tokens agenda has a word for, and answers null
   * for the rest — DELEGATED, or a vendor extension, is not an answer eXo can
   * record, and guessing one would put words in the user's mouth.
   *
   * @param partStat the PARTSTAT token as the client wrote it
   * @return the agenda response name ({@code ACCEPTED}, {@code DECLINED},
   *         {@code TENTATIVE} or {@code NEEDS_ACTION}), or null when the token
   *         maps to nothing agenda can hold
   */
  public static String agendaResponse(String partStat) {
    String token = StringUtils.upperCase(StringUtils.trimToEmpty(partStat)).replace('_', '-');
    if (KNOWN_PART_STATS.contains(token)) {
      return token;
    }
    return NEEDS_ACTION.equals(token) ? "NEEDS_ACTION" : null;
  }

  /**
   * One agenda reminder as minutes before the start.
   *
   * @param reminder the reminder as agenda holds it
   * @return the minutes, or null when the reminder is unusable
   */
  public static Long reminderMinutes(org.exoplatform.caldav.model.IcsReminder reminder) {
    if (reminder == null || reminder.getBefore() < 0) {
      return null;
    }
    long before = reminder.getBefore();
    return switch (StringUtils.lowerCase(StringUtils.defaultString(reminder.getBeforePeriodType()))) {
    case "hour" -> before * MINUTES_PER_HOUR;
    case "day" -> before * MINUTES_PER_HOUR * HOURS_PER_DAY;
    case "week" -> before * MINUTES_PER_HOUR * HOURS_PER_DAY * DAYS_PER_WEEK;
    default -> before;
    };
  }

  /**
   * An instant from the two shapes agenda supplies: an ISO instant, or a plain
   * date which is read as midnight UTC.
   *
   * @param value the value to parse
   * @return the instant, or null when the value is neither
   */
  public static Instant parseInstant(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    String trimmed = value.trim();
    try {
      if (!trimmed.contains("T")) {
        return LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant();
      }
      return Instant.parse(trimmed);
    } catch (DateTimeParseException e) {
      try {
        return java.time.OffsetDateTime.parse(trimmed).toInstant();
      } catch (DateTimeParseException nested) {
        return null;
      }
    }
  }
}
