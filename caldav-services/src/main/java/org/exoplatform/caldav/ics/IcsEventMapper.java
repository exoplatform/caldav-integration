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

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import net.fortuna.ical4j.model.Recur;

import org.exoplatform.agenda.constant.EventRecurrenceFrequency;
import org.exoplatform.agenda.constant.EventRecurrenceType;
import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.EventRecurrence;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Turns a parsed calendar object into the agenda event that will stand for it.
 *
 * <p>
 * Kept apart from {@link IcsParser} on purpose: the parser answers "what does
 * this object say", this answers "what does agenda hold for it". The first is
 * about a protocol, the second about a platform, and mixing them is how a
 * quirk of one ends up encoded in the other.
 */
@Component
public class IcsEventMapper {

  private static final Log LOG = ExoLogger.getLogger(IcsEventMapper.class);

  /**
   * The agenda event standing for one parsed object.
   *
   * <p>
   * Identity is deliberately absent: no id, no calendar, no creator. Those
   * belong to the caller placing the event, not to the object being read, and
   * an event carrying an id it invented would overwrite whatever holds it.
   *
   * @param source the parsed object
   * @param calendarId the eXo calendar the event belongs in
   * @return the event to create or update
   */
  public Event toEvent(IcsEvent source, long calendarId) {
    Event event = new Event();
    event.setCalendarId(calendarId);
    // The object's UID is deliberately not set on the event: agenda has no
    // field for it, and the binding lives in the CALDAV_OBJECT_SYNC ledger
    // where it can outlive an event agenda renumbers.
    event.setSummary(StringUtils.defaultIfBlank(source.getSummary(), ""));
    event.setDescription(source.getDescription());
    event.setLocation(source.getLocation());
    event.setAllDay(source.isAllDay());
    ZoneId zone = zoneOf(source.getTimeZoneId());
    event.setTimeZoneId(zone);
    event.setStart(source.getStart() == null ? null : source.getStart().atZone(zone));
    event.setEnd(source.getEnd() == null ? null : source.getEnd().atZone(zone));
    // Every imported event is CONFIRMED. An object reaching this point is a
    // scheduled meeting on the user's own server; agenda spells a date poll
    // TENTATIVE, and borrowing that word for a real meeting would show it as
    // something nobody has confirmed.
    event.setStatus(EventStatus.CONFIRMED);
    event.setRecurrence(recurrenceOf(source, zone));
    return event;
  }

  /**
   * The recurrence agenda will hold, or null for a single event.
   *
   * <p>
   * Filling the structured fields is not optional, and the reason is easy to
   * miss: agenda derives them <em>from</em> the rule when reading a stored
   * event, but on creation it goes the other way and rebuilds the rule
   * <em>from</em> them. An event created with only the raw RRULE set would
   * have its rule quietly rebuilt from empty fields — a series that arrives as
   * something else entirely, with nothing failing on the way in.
   *
   * @param source the parsed object
   * @param zone the zone the event is anchored on
   * @return the recurrence, or null when the object carries no rule
   */
  private EventRecurrence recurrenceOf(IcsEvent source, ZoneId zone) {
    String rule = source.getRecurrenceRule();
    if (StringUtils.isBlank(rule)) {
      return null;
    }
    Recur recur;
    try {
      recur = new Recur(rule);
    } catch (Exception e) {
      // A rule this parser cannot read would otherwise become a rule agenda
      // invents. The event is still worth having as a single occurrence; the
      // series is what is lost, and losing it loudly beats inventing one.
      LOG.warn("A recurrence rule that could not be read is dropped, and the event is kept as a single one: {}", rule);
      return null;
    }
    if (recur.getFrequency() == null) {
      LOG.warn("A recurrence rule with no frequency is dropped: {}", rule);
      return null;
    }
    EventRecurrence recurrence = new EventRecurrence();
    recurrence.setRrule(rule);
    recurrence.setType(EventRecurrenceType.CUSTOM);
    recurrence.setFrequency(frequencyOf(recur));
    // RFC 5545 makes INTERVAL default to 1, and ical4j answers -1 when the
    // rule omits it. Passed through, that becomes an interval agenda cannot
    // use.
    recurrence.setInterval(recur.getInterval() > 0 ? recur.getInterval() : 1);
    recurrence.setCount(recur.getCount() > 0 ? recur.getCount() : 0);
    if (recur.getUntil() != null) {
      // Agenda holds UNTIL as a date, so a rule ending at a time of day ends
      // that day instead. Widening by hours at most, never narrowing, which is
      // the safe direction: an occurrence too many is visible, one silently
      // missing is not.
      recurrence.setUntil(recur.getUntil().toInstant().atZone(zone).toLocalDate());
    }
    recurrence.setByDay(weekDays(recur));
    recurrence.setBySecond(numbers(recur.getSecondList()));
    recurrence.setByMinute(numbers(recur.getMinuteList()));
    recurrence.setByHour(numbers(recur.getHourList()));
    recurrence.setByMonthDay(numbers(recur.getMonthDayList()));
    recurrence.setByYearDay(numbers(recur.getYearDayList()));
    recurrence.setByWeekNo(numbers(recur.getWeekNoList()));
    recurrence.setByMonth(numbers(recur.getMonthList()));
    recurrence.setBySetPos(numbers(recur.getSetPosList()));
    return recurrence;
  }

  /**
   * The agenda frequency matching the rule's.
   *
   * @param recur the parsed rule
   * @return the frequency, defaulting to weekly when the name is unknown here
   */
  private EventRecurrenceFrequency frequencyOf(Recur recur) {
    try {
      return EventRecurrenceFrequency.valueOf(recur.getFrequency().name());
    } catch (IllegalArgumentException e) {
      // Every RFC 5545 frequency has a match, so this is a rule from the
      // future or a server improvising. Weekly is the least surprising thing
      // to show, and the raw rule is kept beside it.
      LOG.warn("Unknown recurrence frequency {}; the event is shown as weekly", recur.getFrequency());
      return EventRecurrenceFrequency.WEEKLY;
    }
  }

  /**
   * The BYDAY values, as agenda spells them.
   *
   * @param recur the parsed rule
   * @return the day tokens, empty when the rule names none
   */
  private List<String> weekDays(Recur recur) {
    List<String> days = new ArrayList<>();
    if (recur.getDayList() == null) {
      return days;
    }
    for (Object day : recur.getDayList()) {
      // The token keeps any ordinal prefix — "-1SU" is the last Sunday, and
      // dropping the -1 would turn one meeting a month into four.
      days.add(String.valueOf(day));
    }
    return days;
  }

  /**
   * A numeric rule list as the strings agenda holds.
   *
   * @param values the parsed list, possibly null
   * @return the values as strings, empty when there are none
   */
  private List<String> numbers(List<?> values) {
    List<String> numbers = new ArrayList<>();
    if (values == null) {
      return numbers;
    }
    for (Object value : values) {
      numbers.add(String.valueOf(value));
    }
    return numbers;
  }

  /**
   * Resolves a zone name, falling back rather than throwing.
   *
   * @param zoneId the name the object carried
   * @return the zone, or UTC when the name is absent or unknown here
   */
  private ZoneId zoneOf(String zoneId) {
    if (StringUtils.isBlank(zoneId)) {
      return ZoneOffset.UTC;
    }
    try {
      return ZoneId.of(zoneId);
    } catch (Exception e) {
      LOG.debug("Unknown time zone {} in a calendar object; the event is anchored on UTC", zoneId);
      return ZoneOffset.UTC;
    }
  }

  /**
   * A raw RFC 5545 date or date-time as the occurrence agenda names.
   *
   * <p>
   * EXDATE values arrive as the object spelled them — with a zone, in UTC, or
   * as a bare date for an all-day series — so they are read the same way a
   * RECURRENCE-ID is, and anchored on the event's own zone.
   *
   * @param value the raw value
   * @param zoneId the zone the event is anchored on
   * @return the occurrence identifier, or null when the value cannot be read
   */
  public ZonedDateTime occurrenceOf(String value, String zoneId) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    ZoneId zone = zoneOf(zoneId);
    String trimmed = value.trim();
    try {
      return new net.fortuna.ical4j.model.DateTime(trimmed).toInstant().atZone(zone);
    } catch (Exception e) {
      // A bare date: an all-day series excludes a day, not an instant.
      try {
        return java.time.LocalDate.parse(trimmed, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                                  .atStartOfDay(zone);
      } catch (Exception ignored) {
        LOG.debug("An excluded date that could not be read is ignored: {}", value);
        return null;
      }
    }
  }

  /**
   * The occurrence an override amends, in the event's own zone.
   *
   * @param source the parsed override
   * @return the occurrence identifier, or null when the object is not one
   */
  public ZonedDateTime occurrenceOf(IcsEvent source) {
    if (source == null || StringUtils.isBlank(source.getOccurrenceId())) {
      return null;
    }
    try {
      return new net.fortuna.ical4j.model.DateTime(source.getOccurrenceId()).toInstant()
                                                                            .atZone(zoneOf(source.getTimeZoneId()));
    } catch (Exception e) {
      LOG.debug("A recurrence identifier that could not be read is ignored: {}", source.getOccurrenceId());
      return null;
    }
  }
}
