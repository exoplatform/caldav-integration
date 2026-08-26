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

import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.RecurrenceId;
import net.fortuna.ical4j.model.property.Trigger;

import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.IcsPerson;
import org.exoplatform.caldav.model.IcsReminder;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Reads a calendar object into the neutral {@link IcsEvent} model — the exact
 * inverse of {@link IcsWriter}.
 *
 * <p>
 * This is not {@link IcsReader}, and the difference matters. {@code IcsReader}
 * expands a recurring object into the occurrences that fall inside a window,
 * which is what a display needs. This parser keeps the object as it was
 * written: one master carrying its rule and its exceptions, plus any overrides
 * as separate events. Materialising a series from expanded occurrences would
 * produce a hundred unrelated events where the user has one weekly meeting.
 *
 * <p>
 * Sharing {@link IcsEvent} with the writer is deliberate: it makes the round
 * trip testable. Anything the writer can say, this can read back, and a golden
 * that survives {@code write(parse(ics))} unchanged has proven both halves at
 * once.
 */
@Component
public class IcsParser {

  private static final Log LOG = ExoLogger.getLogger(IcsParser.class);

  /**
   * Reads every VEVENT of one calendar object.
   *
   * <p>
   * The master comes first when there is one, so a caller that creates before
   * it overrides does not have to sort. An object with no VEVENT at all — a
   * lone VTODO, or a body a server sent in error — yields an empty list rather
   * than an exception: one unreadable object must not stop a synchronisation
   * that has other objects to bring in.
   *
   * @param ics the calendar object as the server sent it
   * @return the events it carries, master first; empty when it carries none
   */
  public List<IcsEvent> parse(String ics) {
    if (StringUtils.isBlank(ics)) {
      return List.of();
    }
    Calendar calendar;
    try {
      calendar = new CalendarBuilder().build(new StringReader(ics));
    } catch (Exception e) {
      // TEMPORARY (EXO-89681): raised from debug because a copy a client had
      // answered came back unparseable and the silence hid it. Back to debug
      // once the round trip is proven.
      LOG.warn("PARSE-DIAG a calendar object could not be parsed and is skipped", e);
      return List.of();
    }
    List<IcsEvent> masters = new ArrayList<>();
    List<IcsEvent> overrides = new ArrayList<>();
    for (Object component : calendar.getComponents(net.fortuna.ical4j.model.Component.VEVENT)) {
      VEvent vEvent = (VEvent) component;
      IcsEvent event = toEvent(vEvent);
      if (event == null) {
        continue;
      }
      if (StringUtils.isBlank(event.getOccurrenceId())) {
        masters.add(event);
      } else {
        overrides.add(event);
      }
    }
    List<IcsEvent> events = new ArrayList<>(masters);
    events.addAll(overrides);
    return events;
  }

  /**
   * Maps one VEVENT onto the neutral model.
   *
   * @param vEvent the component to read
   * @return the event, or null when it carries no UID or no start
   */
  private IcsEvent toEvent(VEvent vEvent) {
    String uid = text(vEvent, Property.UID);
    DtStart start = vEvent.getStartDate();
    if (StringUtils.isBlank(uid) || start == null) {
      // Without a UID there is nothing to bind the event to across syncs, and
      // without a start there is no event. Skipped rather than imported into a
      // shape nothing can update later.
      LOG.debug("A VEVENT without a uid or a start date is skipped");
      return null;
    }
    IcsEvent event = new IcsEvent();
    event.setUid(uid);
    event.setSummary(text(vEvent, Property.SUMMARY));
    event.setLocation(text(vEvent, Property.LOCATION));
    event.setDescription(text(vEvent, Property.DESCRIPTION));
    event.setEventUrl(text(vEvent, Property.URL));
    event.setAllDay(isAllDay(start));
    event.setTimeZoneId(zoneOf(start));
    readSchedule(event, vEvent, start);
    event.setCreated(instantOrNull(vEvent, Property.CREATED));
    event.setUpdated(instantOrNull(vEvent, Property.LAST_MODIFIED));
    event.setRecurrenceRule(text(vEvent, Property.RRULE));
    event.setExceptionDates(exceptionDates(vEvent));
    event.setOccurrenceId(occurrenceId(vEvent));
    event.setOrganizer(person(vEvent.getProperty(Property.ORGANIZER)));
    event.setAttendees(attendees(vEvent));
    event.setReminders(reminders(vEvent));
    return event;
  }

  /**
   * Start and end, undoing the two conversions the writer applies.
   *
   * <p>
   * An all-day DTEND is exclusive in RFC 5545 — a one-day event ends on the
   * day after it — while agenda holds the last day the event covers, so a day
   * comes back off here. It is the same asymmetry that once made a pushed
   * all-day event arrive a day short, in the other direction.
   *
   * <p>
   * An object with no DTEND is not malformed: RFC 5545 lets DURATION stand in,
   * and a dateless all-day event lasts one day.
   *
   * @param event the event being filled
   * @param vEvent the component being read
   * @param start its start property
   */
  private void readSchedule(IcsEvent event, VEvent vEvent, DtStart start) {
    Instant from = Instant.ofEpochMilli(start.getDate().getTime());
    event.setStart(from);
    DtEnd end = vEvent.getEndDate(false);
    Instant to = end == null ? null : Instant.ofEpochMilli(end.getDate().getTime());
    if (to == null) {
      net.fortuna.ical4j.model.property.Duration duration =
                                                          (net.fortuna.ical4j.model.property.Duration) vEvent.getProperty(Property.DURATION);
      if (duration != null && duration.getDuration() != null) {
        to = from.plus(Duration.parse(duration.getDuration().toString()));
      } else {
        to = event.isAllDay() ? from.plus(Duration.ofDays(1)) : from;
      }
    }
    if (event.isAllDay()) {
      ZoneId zone = zoneIdOf(event.getTimeZoneId());
      LocalDate last = LocalDate.ofInstant(to, zone).minusDays(1);
      LocalDate first = LocalDate.ofInstant(from, zone);
      // A malformed object whose exclusive end is not after its start would
      // otherwise produce an event ending before it begins.
      event.setEnd(last.isBefore(first) ? from : last.atStartOfDay(zone).toInstant());
    } else {
      event.setEnd(to);
    }
  }

  /**
   * Whether the object is an all-day event.
   *
   * @param start its start property
   * @return true when DTSTART carries a date rather than a date-time
   */
  private boolean isAllDay(DtStart start) {
    Parameter value = start.getParameter(Parameter.VALUE);
    return value != null && "DATE".equalsIgnoreCase(value.getValue());
  }

  /**
   * The zone the object anchors its times on.
   *
   * @param start its start property
   * @return the TZID it carries, or UTC when it carries none
   */
  private String zoneOf(DtStart start) {
    Parameter tzid = start.getParameter(Parameter.TZID);
    if (tzid != null && StringUtils.isNotBlank(tzid.getValue())) {
      return tzid.getValue();
    }
    return ZoneOffset.UTC.getId();
  }

  /**
   * Resolves a zone name, falling back rather than throwing.
   *
   * @param zoneId the name the object carried
   * @return the zone, or UTC when the name is absent or unknown here
   */
  private ZoneId zoneIdOf(String zoneId) {
    if (StringUtils.isBlank(zoneId)) {
      return ZoneOffset.UTC;
    }
    try {
      return ZoneId.of(zoneId);
    } catch (Exception e) {
      // A zone this JVM does not know is not a reason to drop the event; the
      // day boundaries are then computed in UTC, which is what an object
      // carrying no zone would have given anyway.
      LOG.debug("Unknown time zone {} in a calendar object; reading it as UTC", zoneId);
      return ZoneOffset.UTC;
    }
  }

  /**
   * The RECURRENCE-ID this object overrides, if any.
   *
   * @param vEvent the component being read
   * @return the identifier as the writer spells it, or null
   */
  private String occurrenceId(VEvent vEvent) {
    RecurrenceId recurrenceId = (RecurrenceId) vEvent.getProperty(Property.RECURRENCE_ID);
    return recurrenceId == null ? null : recurrenceId.getValue();
  }

  /**
   * Every EXDATE the object carries, flattened.
   *
   * <p>
   * A property may list several dates and an object may repeat the property;
   * both spellings are legal and both are read.
   *
   * @param vEvent the component being read
   * @return the excluded dates, empty when there are none
   */
  private List<String> exceptionDates(VEvent vEvent) {
    List<String> dates = new ArrayList<>();
    for (Object property : vEvent.getProperties(Property.EXDATE)) {
      String value = ((Property) property).getValue();
      if (StringUtils.isBlank(value)) {
        continue;
      }
      for (String one : StringUtils.split(value, ',')) {
        if (StringUtils.isNotBlank(one)) {
          dates.add(one.trim());
        }
      }
    }
    return dates;
  }

  /**
   * The attendees of the object.
   *
   * @param vEvent the component being read
   * @return the attendees, empty when there are none
   */
  private List<IcsPerson> attendees(VEvent vEvent) {
    List<IcsPerson> people = new ArrayList<>();
    for (Object property : vEvent.getProperties(Property.ATTENDEE)) {
      IcsPerson person = person((Attendee) property);
      if (person != null) {
        people.add(person);
      }
    }
    return people;
  }

  /**
   * Maps one CAL-ADDRESS property onto a person.
   *
   * @param property an ORGANIZER or ATTENDEE property, possibly null
   * @return the person, or null when it carries no address
   */
  private IcsPerson person(Property property) {
    if (property == null) {
      return null;
    }
    String email = StringUtils.removeStartIgnoreCase(StringUtils.trimToEmpty(property.getValue()), "mailto:");
    if (StringUtils.isBlank(email)) {
      return null;
    }
    IcsPerson person = new IcsPerson();
    person.setEmail(email);
    Parameter name = property.getParameter(Parameter.CN);
    person.setDisplayName(name == null ? null : name.getValue());
    Parameter partStat = property.getParameter(Parameter.PARTSTAT);
    person.setResponse(partStat == null ? null : partStat.getValue());
    return person;
  }

  /**
   * The reminders of the object, as minutes before its start.
   *
   * <p>
   * Only alarms triggering on a negative duration relative to the start are
   * read. An alarm anchored on the end, or set after the fact, has no place in
   * agenda's model, and inventing an offset for it would move the user's
   * reminder rather than drop it.
   *
   * @param vEvent the component being read
   * @return the reminders, empty when there are none agenda can hold
   */
  private List<IcsReminder> reminders(VEvent vEvent) {
    List<IcsReminder> reminders = new ArrayList<>();
    for (Object component : vEvent.getAlarms()) {
      Trigger trigger = ((VAlarm) component).getTrigger();
      if (trigger == null || trigger.getDuration() == null) {
        continue;
      }
      Duration duration;
      try {
        duration = Duration.parse(trigger.getDuration().toString());
      } catch (Exception e) {
        LOG.debug("An alarm trigger that is not a duration is skipped");
        continue;
      }
      long minutes = -duration.toMinutes();
      if (minutes <= 0) {
        continue;
      }
      IcsReminder reminder = new IcsReminder();
      reminder.setBefore(minutes);
      reminder.setBeforePeriodType("minute");
      reminders.add(reminder);
    }
    return reminders;
  }

  /**
   * The text of a property, or null.
   *
   * @param vEvent the component being read
   * @param name the property name
   * @return its value, or null when the property is absent
   */
  private String text(VEvent vEvent, String name) {
    Property property = vEvent.getProperty(name);
    return property == null ? null : StringUtils.trimToNull(property.getValue());
  }

  /**
   * A timestamp property, or null.
   *
   * @param vEvent the component being read
   * @param name the property name
   * @return the instant it carries, or null when absent or unreadable
   */
  private Instant instantOrNull(VEvent vEvent, String name) {
    Property property = vEvent.getProperty(name);
    if (property == null || StringUtils.isBlank(property.getValue())) {
      return null;
    }
    try {
      return new net.fortuna.ical4j.model.DateTime(property.getValue()).toInstant();
    } catch (Exception e) {
      LOG.debug("A timestamp {} that could not be read is left empty", name);
      return null;
    }
  }
}
