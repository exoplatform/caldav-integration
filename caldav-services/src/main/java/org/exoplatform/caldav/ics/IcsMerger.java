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
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Date;
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
}
