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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Date;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.DateList;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.model.property.ExDate;
import net.fortuna.ical4j.model.property.RecurrenceId;
import net.fortuna.ical4j.model.property.TzId;

/**
 * Splices an event into the calendar object a server already holds.
 *
 * <p>
 * A calendar object is not ours alone. Another client may have written
 * overrides into the same object, with its own properties and its own
 * VTIMEZONE, and a push that replaced the whole document would silently
 * destroy them. So the object is read, the parts this event owns are replaced,
 * and everything else is left exactly as it was found.
 *
 * <p>
 * Two rules decide what "the parts this event owns" means, and both were
 * learned the hard way. Pushing a master replaces the master — and also prunes
 * any override whose instance the master's EXDATEs now exclude, because an
 * override contradicting an EXDATE keeps a deleted occurrence visible on the
 * client. Pushing an override replaces only the override for that instance.
 */
@Component
public class IcsMerger {

  /**
   * Merges one component into the object a server holds.
   *
   * @param existing the calendar object as fetched from the server
   * @param component the object this engine just built, holding one VEVENT
   * @param occurrence whether the component amends one instance rather than
   *          being the master
   * @return the object to write back
   * @throws IcsParseException when either document is not readable iCalendar
   */
  public String merge(String existing, String component, boolean occurrence) {
    Calendar target = parse(existing);
    Calendar source = parse(component);
    VEvent incoming = source.getComponent(net.fortuna.ical4j.model.Component.VEVENT);
    if (incoming == null) {
      return existing;
    }
    carryTimeZones(source, target);
    for (VEvent replaced : replacedEvents(target, incoming, occurrence)) {
      target.getComponents().remove(replaced);
    }
    target.getComponents().add(incoming);
    return target.toString();
  }

  /**
   * Copies over the zone definitions the incoming object brings and the target
   * does not already carry, matched on TZID.
   *
   * <p>
   * Matched on the identifier rather than on the component's content on
   * purpose: two definitions of the same zone written by two clients differ in
   * their rules, and a document carrying both leaves a reader to pick. Keeping
   * the one already in the object means the other client's events keep
   * resolving exactly as they did.
   *
   * @param source the object just built
   * @param target the object the server holds
   */
  private void carryTimeZones(Calendar source, Calendar target) {
    List<String> known = new ArrayList<>();
    for (Object existing : target.getComponents(net.fortuna.ical4j.model.Component.VTIMEZONE)) {
      known.add(tzid((VTimeZone) existing));
    }
    for (Object candidate : source.getComponents(net.fortuna.ical4j.model.Component.VTIMEZONE)) {
      VTimeZone timeZone = (VTimeZone) candidate;
      if (!known.contains(tzid(timeZone))) {
        target.getComponents().add(timeZone);
      }
    }
  }

  /**
   * The events the incoming component supersedes.
   *
   * @param target the object the server holds
   * @param incoming the component just built
   * @param occurrence whether the component amends one instance
   * @return the events to remove before splicing
   */
  private List<VEvent> replacedEvents(Calendar target, VEvent incoming, boolean occurrence) {
    List<VEvent> replaced = new ArrayList<>();
    List<Date> exceptionDates = occurrence ? List.of() : exceptionDates(incoming);
    Date incomingInstance = instanceOf(incoming);
    for (Object candidate : target.getComponents(net.fortuna.ical4j.model.Component.VEVENT)) {
      VEvent event = (VEvent) candidate;
      Date instance = instanceOf(event);
      if (occurrence) {
        if (isSameInstance(instance, incomingInstance)) {
          replaced.add(event);
        }
      } else if (instance == null) {
        // The master itself, whichever client last wrote it.
        replaced.add(event);
      } else if (exceptionDates.stream().anyMatch(exception -> isSameInstance(instance, exception))) {
        // An override for an instance the incoming master now excludes. Left
        // in place it would keep showing an occurrence the series no longer
        // produces — the deletion would appear not to have worked.
        replaced.add(event);
      }
    }
    return replaced;
  }

  /**
   * The instance an event amends, or null when it is a master.
   *
   * @param event the event to read
   * @return the RECURRENCE-ID value, or null
   */
  private Date instanceOf(VEvent event) {
    RecurrenceId recurrenceId = (RecurrenceId) event.getProperty(net.fortuna.ical4j.model.Property.RECURRENCE_ID);
    return recurrenceId == null ? null : recurrenceId.getDate();
  }

  /**
   * Every instance the component excludes.
   *
   * @param event the component to read
   * @return the EXDATE values, flattened
   */
  private List<Date> exceptionDates(VEvent event) {
    List<Date> dates = new ArrayList<>();
    for (Object property : event.getProperties(net.fortuna.ical4j.model.Property.EXDATE)) {
      DateList list = ((ExDate) property).getDates();
      dates.addAll(list);
    }
    return dates;
  }

  /**
   * Whether two values denote the same instance.
   *
   * <p>
   * A date and a date-time are compared on the calendar day alone: RFC 5545
   * matches instances by identical value, and an all-day series identifies its
   * occurrences by date while agenda hands an instant for the same thing.
   * Comparing them as instants would match nothing.
   *
   * @param left one instance value, possibly null
   * @param right the other, possibly null
   * @return true when both denote the same occurrence
   */
  private boolean isSameInstance(Date left, Date right) {
    if (left == null || right == null) {
      return false;
    }
    boolean eitherIsDate = !(left instanceof net.fortuna.ical4j.model.DateTime)
        || !(right instanceof net.fortuna.ical4j.model.DateTime);
    if (eitherIsDate) {
      return left.toString().substring(0, 8).equals(right.toString().substring(0, 8));
    }
    return left.compareTo(right) == 0;
  }

  /**
   * The identifier of a zone definition.
   *
   * @param timeZone the definition
   * @return its TZID, or null when it carries none
   */
  private String tzid(VTimeZone timeZone) {
    TzId property = (TzId) timeZone.getProperty(net.fortuna.ical4j.model.Property.TZID);
    return property == null ? null : property.getValue();
  }

  /**
   * Reads a calendar object.
   *
   * @param ics the document
   * @return the parsed calendar
   */
  private Calendar parse(String ics) {
    try {
      return new CalendarBuilder().build(new StringReader(ics));
    } catch (Exception e) {
      throw new IcsParseException("The calendar object could not be read as iCalendar", e);
    }
  }

  /**
   * Rewrites a calendar object so that it no longer produces one occurrence.
   *
   * <p>
   * Not a deletion of the object: RFC 4791 puts every component of a series in
   * one object, so removing it would remove the whole series. The override
   * carrying that instance is dropped, and the master gains an EXDATE for it —
   * <b>in the value type the master's own DTSTART uses</b>, because RFC 5545
   * matches instances by identical value. An EXDATE written as a date-time
   * against a date-valued series matches no instance at all, and the deleted
   * occurrence simply reappears.
   *
   * <p>
   * Answering null rather than an object means nothing is left: the caller
   * deletes the object instead of writing back a VCALENDAR with no VEVENT in
   * it, which some servers accept and then serve to clients that choke on it.
   *
   * @param existing the calendar object as fetched from the server
   * @param occurrence the instance to exclude
   * @return the object to write back, or null when nothing remains of it
   * @throws IcsParseException when the document is not readable iCalendar
   */
  public String excludeOccurrence(String existing, Instant occurrence) {
    Calendar target = parse(existing);
    VEvent master = null;
    for (Object component : target.getComponents(net.fortuna.ical4j.model.Component.VEVENT)) {
      VEvent event = (VEvent) component;
      if (event.getProperty(net.fortuna.ical4j.model.Property.RECURRENCE_ID) == null) {
        master = master == null ? event : master;
      }
    }
    // No master, only overrides: the series lives elsewhere and this object
    // holds detached instances. Removing the matching one is still the right
    // answer, and if it was the only one the object has nothing left in it.
    Date excluded = master == null ? utc(occurrence) : sameShapeAs(master, occurrence);

    // A master with no repetition rule produces no occurrences to exclude.
    // Reaching here means the caller believes in a series the object does not
    // have — a race, or a rule removed remotely. The object is returned
    // untouched rather than given an EXDATE: RFC 5545 defines EXDATE against a
    // recurrence set, and a lenient client handed one on a single event may
    // hide the meeting entirely. Changing nothing beats hiding something.
    if (master != null && master.getProperty(net.fortuna.ical4j.model.Property.RRULE) == null) {
      return existing;
    }

    List<VEvent> overrides = new ArrayList<>();
    for (Object component : target.getComponents(net.fortuna.ical4j.model.Component.VEVENT)) {
      VEvent event = (VEvent) component;
      Date instance = instanceOf(event);
      if (isSameInstance(instance, excluded)) {
        overrides.add(event);
      }
    }
    overrides.forEach(event -> target.getComponents().remove(event));

    if (target.getComponents(net.fortuna.ical4j.model.Component.VEVENT).isEmpty()) {
      return null;
    }
    if (master != null && !alreadyExcluded(master, excluded)) {
      master.getProperties().add(exDateFor(master, excluded));
    }
    return target.toString();
  }

  /**
   * An instant as a UTC date-time, for an object that carries no master to
   * take its shape from.
   *
   * @param occurrence the instance
   * @return the value
   */
  private Date utc(Instant occurrence) {
    DateTime dateTime = new DateTime(java.util.Date.from(occurrence));
    dateTime.setUtc(true);
    return dateTime;
  }

  /**
   * The instant expressed the way the master's DTSTART is: a date for an
   * all-day series, a date-time anchored on the same zone otherwise.
   *
   * @param master the series
   * @param occurrence the instance to express
   * @return the value to compare and to exclude by
   */
  private Date sameShapeAs(VEvent master, Instant occurrence) {
    Date start = master.getStartDate() == null ? null : master.getStartDate().getDate();
    if (start != null && !(start instanceof DateTime)) {
      java.time.LocalDate day = occurrence.atZone(java.time.ZoneOffset.UTC).toLocalDate();
      try {
        return new Date(day.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
      } catch (java.text.ParseException e) {
        throw new IcsParseException("An occurrence identifier failed to format as a date: " + occurrence, e);
      }
    }
    DateTime dateTime = new DateTime(java.util.Date.from(occurrence));
    if (start instanceof DateTime startTime && startTime.getTimeZone() != null) {
      dateTime.setTimeZone(startTime.getTimeZone());
    } else {
      dateTime.setUtc(true);
    }
    return dateTime;
  }

  /**
   * Whether the master already excludes this instance.
   *
   * @param master the series
   * @param excluded the instance
   * @return true when an EXDATE already names it
   */
  private boolean alreadyExcluded(VEvent master, Date excluded) {
    return exceptionDates(master).stream().anyMatch(date -> isSameInstance(date, excluded));
  }

  /**
   * An EXDATE carrying one instance, in the master's own form.
   *
   * @param master the series
   * @param excluded the instance to exclude
   * @return the property to add
   */
  private ExDate exDateFor(VEvent master, Date excluded) {
    net.fortuna.ical4j.model.ParameterList parameters = new net.fortuna.ical4j.model.ParameterList();
    if (!(excluded instanceof DateTime)) {
      DateList dates = new DateList(net.fortuna.ical4j.model.parameter.Value.DATE);
      dates.add(excluded);
      parameters.add(net.fortuna.ical4j.model.parameter.Value.DATE);
      return new ExDate(parameters, dates);
    }
    DateTime dateTime = (DateTime) excluded;
    DateList dates = new DateList(net.fortuna.ical4j.model.parameter.Value.DATE_TIME);
    if (dateTime.getTimeZone() != null) {
      dates.setTimeZone(dateTime.getTimeZone());
      parameters.add(new net.fortuna.ical4j.model.parameter.TzId(dateTime.getTimeZone().getID()));
    } else {
      dates.setUtc(true);
    }
    dates.add(dateTime);
    return new ExDate(parameters, dates);
  }
}
