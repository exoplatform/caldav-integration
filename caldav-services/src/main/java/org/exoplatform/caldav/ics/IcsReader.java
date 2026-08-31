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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.PeriodList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.RRule;
import net.fortuna.ical4j.model.property.RecurrenceId;

import org.exoplatform.caldav.model.RemoteIcsEvent;

/**
 * Reads a remote calendar object into the occurrences it produces over a
 * window.
 *
 * <p>
 * Two shapes arrive here and both must be handled, because which one a
 * deployment sees is the server's choice, not ours. A server honouring
 * CALDAV:expand answers one VEVENT per occurrence, times rewritten to UTC and
 * the rule gone — the shape the browser connector has always parsed, since
 * Stalwart expands. A server that ignores expand answers the stored object,
 * rule and all, and the occurrences have to be produced here.
 *
 * <p>
 * The second branch is the one that matters for the migration: whether a given
 * server expands is unproven for BlueMind, and an engine that only worked
 * against expanding servers would fail silently — not by erroring, but by
 * showing a single event where a weekly series should be.
 */
@Component
public class IcsReader {

  /**
   * The occurrences a calendar object produces within a window.
   *
   * @param ics the calendar object as the server returned it
   * @param from start of the window, inclusive
   * @param to end of the window, exclusive
   * @return the occurrences, in the order the object presents them
   * @throws IcsParseException when the document is not readable iCalendar
   */
  public List<RemoteIcsEvent> read(String ics, Instant from, Instant to) {
    Calendar calendar = parse(ics);
    List<VEvent> events = new ArrayList<>();
    for (Object component : calendar.getComponents(net.fortuna.ical4j.model.Component.VEVENT)) {
      events.add((VEvent) component);
    }
    if (events.isEmpty()) {
      return List.of();
    }
    return isExpanded(events) ? readExpanded(events) : expand(events, from, to);
  }

  /**
   * Whether the server already did the expanding.
   *
   * <p>
   * Recognised by every component carrying a RECURRENCE-ID and none carrying a
   * rule — which is precisely what CALDAV:expand produces. A stored object with
   * overrides carries both shapes at once, and its master has no RECURRENCE-ID,
   * so the two cannot be confused.
   *
   * @param events the components of the object
   * @return true when the object is a server expansion
   */
  private boolean isExpanded(List<VEvent> events) {
    return events.stream()
                 .allMatch(event -> event.getProperty(Property.RECURRENCE_ID) != null
                     && event.getProperty(Property.RRULE) == null);
  }

  /**
   * A server expansion, taken as it stands: each component is already one
   * occurrence.
   *
   * @param events the expanded components
   * @return one occurrence per component
   */
  private List<RemoteIcsEvent> readExpanded(List<VEvent> events) {
    List<RemoteIcsEvent> occurrences = new ArrayList<>();
    for (VEvent event : events) {
      occurrences.add(toOccurrence(event, uidOf(event), startOf(event), endOf(event), true));
    }
    return occurrences;
  }

  /**
   * The stored object, expanded here.
   *
   * <p>
   * Overrides are applied by matching their RECURRENCE-ID against the instance
   * the rule produced — the same identity RFC 5545 uses — so an amended
   * occurrence shows the override's own summary and times, and an occurrence
   * the object excludes is simply never produced.
   *
   * @param events the components of the stored object
   * @param from start of the window, inclusive
   * @param to end of the window, exclusive
   * @return the occurrences within the window
   */
  private List<RemoteIcsEvent> expand(List<VEvent> events, Instant from, Instant to) {
    VEvent master = null;
    Map<String, VEvent> overrides = new LinkedHashMap<>();
    for (VEvent event : events) {
      RecurrenceId recurrenceId = (RecurrenceId) event.getProperty(Property.RECURRENCE_ID);
      if (recurrenceId == null) {
        master = master == null ? event : master;
      } else {
        overrides.put(recurrenceId.getDate().toString(), event);
      }
    }
    if (master == null) {
      // Overrides with no master: nothing generates instances, so each stands
      // on its own rather than being dropped.
      return readExpanded(events);
    }
    List<RemoteIcsEvent> occurrences = new ArrayList<>();
    if (master.getProperty(Property.RRULE) == null) {
      Instant start = startOf(master);
      if (start != null && !start.isBefore(from) && start.isBefore(to)) {
        occurrences.add(toOccurrence(master, uidOf(master), start, endOf(master), false));
      }
      return occurrences;
    }
    Period window = new Period(new DateTime(java.util.Date.from(from)), new DateTime(java.util.Date.from(to)));
    PeriodList periods = master.calculateRecurrenceSet(window);
    for (Object element : periods) {
      Period period = (Period) element;
      VEvent source = overrides.getOrDefault(period.getStart().toString(), master);
      Instant start = Instant.ofEpochMilli(period.getStart().getTime());
      Instant end = Instant.ofEpochMilli(period.getEnd().getTime());
      if (source != master) {
        start = startOf(source);
        end = endOf(source);
      }
      occurrences.add(toOccurrence(source, uidOf(master), start, end, true));
    }
    return occurrences;
  }

  /**
   * One occurrence, in the shape agenda displays.
   *
   * @param event the component the values come from
   * @param uid the series identifier
   * @param start when the occurrence starts
   * @param end when it ends
   * @param recurring whether it belongs to a series
   * @return the occurrence
   */
  private RemoteIcsEvent toOccurrence(VEvent event, String uid, Instant start, Instant end, boolean recurring) {
    boolean allDay = isDateValued(event);
    return RemoteIcsEvent.builder()
                         .uid(uid)
                         .recurringEventId(recurring ? uid : null)
                         .summary(valueOf(event, Property.SUMMARY))
                         .location(valueOf(event, Property.LOCATION))
                         .description(valueOf(event, Property.DESCRIPTION))
                         .allDay(allDay)
                         .start(start)
                         .end(allDay ? null : end)
                         .build();
  }

  /**
   * Whether the component's DTSTART carries a date rather than a date-time,
   * which is what makes an event all-day.
   *
   * @param event the component to read
   * @return true for an all-day component
   */
  private boolean isDateValued(VEvent event) {
    return event.getStartDate() != null && !(event.getStartDate().getDate() instanceof DateTime);
  }

  /**
   * The component's start as an instant.
   *
   * @param event the component to read
   * @return the instant, or null when it carries no start
   */
  private Instant startOf(VEvent event) {
    return event.getStartDate() == null ? null : Instant.ofEpochMilli(event.getStartDate().getDate().getTime());
  }

  /**
   * The component's end as an instant.
   *
   * @param event the component to read
   * @return the instant, or null when it carries no end
   */
  private Instant endOf(VEvent event) {
    return event.getEndDate() == null ? null : Instant.ofEpochMilli(event.getEndDate().getDate().getTime());
  }

  /**
   * The component's UID.
   *
   * @param event the component to read
   * @return the identifier, or null
   */
  private String uidOf(VEvent event) {
    return valueOf(event, Property.UID);
  }

  /**
   * A property's value, or null when the component does not carry it.
   *
   * @param event the component to read
   * @param name the property name
   * @return the value, or null
   */
  private String valueOf(VEvent event, String name) {
    net.fortuna.ical4j.model.Property property = event.getProperty(name);
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
      return IcsCompatibility.newCalendarBuilder().build(new StringReader(ics));
    } catch (Exception e) {
      throw new IcsParseException("The calendar object could not be read as iCalendar", e);
    }
  }
}
