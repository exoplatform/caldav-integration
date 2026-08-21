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
package org.exoplatform.caldav.golden;

import java.io.StringReader;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.PeriodList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.util.CompatibilityHints;

/**
 * Compares two iCalendar objects on <b>meaning, never bytes</b> — the fidelity
 * judge of the golden-file harness (EXO-89521, plan section 7): PR3's Java ICS
 * engine is right when, for every golden, this comparator finds no difference
 * between what the engine produces and what the browser connector produced.
 * <p>
 * What deliberately does <b>not</b> register as a difference: line folding and
 * CRLF handling (the parser unfolds), property and parameter order, DTSTAMP,
 * PRODID, VERSION and CALSCALE, default-equivalent statements
 * ({@code TRANSP:OPAQUE}, {@code SEQUENCE:0}, {@code CLASS:PUBLIC} equal their
 * own absence), a trigger written {@code -PT60M} versus {@code -PT1H}, a CN
 * quoted versus bare, and — the load-bearing one — the <i>form</i> a date-time
 * is stated in: a property anchored in UTC and the same property anchored on a
 * TZID wall clock are equal when they denote the same instant, each side
 * resolved through the VTIMEZONE its own object carries (that is how a phone
 * reads it), falling back to the IANA zone when the object defines none.
 * <p>
 * What always registers: a shifted instant, a dropped or added attendee, a
 * changed PARTSTAT, a changed RRULE, a lost EXDATE or override, changed text,
 * a lost alarm, an all-day end off by one day — and, through the recurrence
 * expansion, the drift that instant-equality alone cannot see: a series
 * anchored in UTC and one anchored on a zone agree on every instant before a
 * DST transition and diverge after it, so masters carrying an RRULE are
 * additionally expanded over a probe window and their occurrence instants
 * compared one by one.
 * <p>
 * VTIMEZONE components are excluded from the structural comparison on
 * purpose: the Intl-derived component the browser writes and the ical4j
 * registry component PR3 writes legitimately differ as text while describing
 * the same behaviour — so the behaviour is what is compared, through the
 * instant resolution and the expansion above, not the rule text.
 */
public final class IcsSemanticComparator {

  /** Property names whose presence or value carries no meaning for a reader of the copy. */
  private static final Set<String>         IGNORED_PROPERTIES  =
                                                              Set.of("DTSTAMP", "PRODID", "VERSION", "CALSCALE");

  /** Statements equal to their own absence, per the RFC 5545 defaults. */
  private static final Map<String, String> DEFAULT_STATEMENTS  = Map.of("TRANSP",
                                                                        "OPAQUE",
                                                                        "SEQUENCE",
                                                                        "0",
                                                                        "CLASS",
                                                                        "PUBLIC");

  /** Properties whose value is a date or date-time, normalised to an instant or a calendar day. */
  private static final Set<String>         DATE_PROPERTIES     = Set.of("DTSTART",
                                                                        "DTEND",
                                                                        "DUE",
                                                                        "RECURRENCE-ID",
                                                                        "EXDATE",
                                                                        "RDATE",
                                                                        "CREATED",
                                                                        "LAST-MODIFIED",
                                                                        "COMPLETED",
                                                                        "UNTIL");

  /** Properties whose value is a duration, normalised to signed seconds. */
  private static final Set<String>         DURATION_PROPERTIES = Set.of("TRIGGER", "DURATION");

  /** A date-time value in the iCalendar basic format, with or without the UTC marker. */
  private static final Pattern             DATE_TIME_VALUE     = Pattern.compile("^(\\d{8})T(\\d{6})(Z?)$");

  /** A date value in the iCalendar basic format. */
  private static final Pattern             DATE_VALUE          = Pattern.compile("^\\d{8}$");

  /** An iCalendar duration value, per RFC 5545 section 3.3.6. */
  private static final Pattern             DURATION_VALUE      =
                                                          Pattern.compile("^([+-]?)P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$");

  /** How far past the series start the recurrence expansion probes, in days. */
  private static final int                 EXPANSION_DAYS      = 450;

  /** How many occurrences the expansion compares at most. */
  private static final int                 EXPANSION_CAP       = 120;

  /**
   * Never instantiated: the comparator is a set of pure functions.
   */
  private IcsSemanticComparator() {
  }

  /**
   * Compares two calendar objects and reports every semantic divergence, in a
   * stable order, or an empty list when the two objects mean the same thing.
   *
   * @param leftIcs one calendar object, as raw ICS text
   * @param rightIcs the other calendar object, as raw ICS text
   * @return the divergences found, empty when semantically equal
   */
  public static List<SemanticDifference> compare(String leftIcs, String rightIcs) {
    Calendar left = parse(leftIcs);
    Calendar right = parse(rightIcs);
    List<SemanticDifference> differences = new ArrayList<>();
    compareCalendarStatements(left, right, differences);
    Map<String, Component> leftEvents = eventsByKey(left);
    Map<String, Component> rightEvents = eventsByKey(right);
    Set<String> keys = new TreeSet<>(leftEvents.keySet());
    keys.addAll(rightEvents.keySet());
    for (String key : keys) {
      Component leftEvent = leftEvents.get(key);
      Component rightEvent = rightEvents.get(key);
      if (leftEvent == null || rightEvent == null) {
        differences.add(new SemanticDifference(SemanticDifference.Kind.COMPONENT,
                                               key,
                                               leftEvent == null ? null : "present",
                                               rightEvent == null ? null : "present"));
        continue;
      }
      compareStatements(key, statementsOf(leftEvent, left), statementsOf(rightEvent, right), differences);
      compareExpansions(key, left, leftEvent, right, rightEvent, differences);
    }
    return differences;
  }

  /**
   * Parses one calendar object leniently: folding, unknown properties and
   * loose values must never make two objects incomparable — a parse failure
   * here is itself a finding about the object, so it surfaces as an error.
   *
   * @param ics the calendar object as raw ICS text
   * @return the parsed calendar
   */
  private static Calendar parse(String ics) {
    CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_UNFOLDING, true);
    CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_PARSING, true);
    CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_VALIDATION, true);
    try {
      return new CalendarBuilder().build(new StringReader(ics));
    } catch (Exception e) {
      throw new IllegalArgumentException("the calendar object cannot be parsed: " + e.getMessage(), e);
    }
  }

  /**
   * Compares the calendar-level statements that carry meaning — METHOD and any
   * non-ignored, non-default calendar property — leaving PRODID, VERSION and
   * CALSCALE out.
   *
   * @param left one parsed calendar
   * @param right the other parsed calendar
   * @param differences the accumulator the divergences are added to
   */
  private static void compareCalendarStatements(Calendar left, Calendar right, List<SemanticDifference> differences) {
    Map<String, Integer> leftStatements = new TreeMap<>();
    Map<String, Integer> rightStatements = new TreeMap<>();
    for (Property property : left.getProperties()) {
      addStatements(leftStatements, normaliseProperty(property, left));
    }
    for (Property property : right.getProperties()) {
      addStatements(rightStatements, normaliseProperty(property, right));
    }
    diffStatements("VCALENDAR", leftStatements, rightStatements, SemanticDifference.Kind.CALENDAR, differences);
  }

  /**
   * Indexes the VEVENT components of a calendar by their semantic identity:
   * the UID plus the normalised RECURRENCE-ID, so a master and each of its
   * overrides pair up with their counterparts regardless of the order the
   * object lists them in.
   *
   * @param calendar the parsed calendar
   * @return the events by identity key
   */
  private static Map<String, Component> eventsByKey(Calendar calendar) {
    Map<String, Component> events = new LinkedHashMap<>();
    for (CalendarComponent component : calendar.getComponents(Component.VEVENT)) {
      String uid = propertyValue(component, Property.UID);
      Property recurrenceId = component.getProperty(Property.RECURRENCE_ID);
      String key = (uid == null ? "(no uid)" : uid) + "#"
          + (recurrenceId == null ? "master" : normaliseDateValue(recurrenceId.getValue(), tzidOf(recurrenceId), calendar));
      events.put(key, component);
    }
    return events;
  }

  /**
   * The normalised statements a component makes: one canonical line per
   * property occurrence (ignored and default-equivalent ones dropped), plus
   * one canonical line per subcomponent such as a VALARM, all as a multiset so
   * repeated statements count.
   *
   * @param component the component to normalise
   * @param calendar the calendar it belongs to, for zone resolution
   * @return the statements as a multiset of canonical lines
   */
  private static Map<String, Integer> statementsOf(Component component, Calendar calendar) {
    Map<String, Integer> statements = new TreeMap<>();
    for (Property property : component.getProperties()) {
      addStatements(statements, normaliseProperty(property, calendar));
    }
    for (Component nested : nestedComponents(component)) {
      Map<String, Integer> inner = statementsOf(nested, calendar);
      addStatements(statements, List.of(nested.getName() + "{" + String.join("&", flatten(inner)) + "}"));
    }
    return statements;
  }

  /**
   * The subcomponents of a component — the VALARMs of a VEVENT — obtained
   * through serialisation-independent reflection over the component types
   * ical4j models: a VEvent exposes its alarms, other components none.
   *
   * @param component the component to look into
   * @return its nested components, possibly empty
   */
  private static List<Component> nestedComponents(Component component) {
    if (component instanceof net.fortuna.ical4j.model.component.VEvent vEvent) {
      return new ArrayList<>(vEvent.getAlarms());
    }
    return List.of();
  }

  /**
   * Flattens a statement multiset into sorted, counted lines, for embedding a
   * subcomponent's statements into one line of its parent.
   *
   * @param statements the multiset to flatten
   * @return one line per distinct statement, count included when above one
   */
  private static List<String> flatten(Map<String, Integer> statements) {
    return statements.entrySet()
                     .stream()
                     .map(entry -> entry.getValue() == 1 ? entry.getKey() : entry.getKey() + "(x" + entry.getValue() + ")")
                     .collect(Collectors.toList());
  }

  /**
   * Adds canonical statements to a multiset, ignoring the empty list the
   * normaliser returns for statements that carry no meaning.
   *
   * @param statements the multiset added to
   * @param newStatements the canonical lines, possibly empty
   */
  private static void addStatements(Map<String, Integer> statements, List<String> newStatements) {
    for (String statement : newStatements) {
      statements.merge(statement, 1, Integer::sum);
    }
  }

  /**
   * One property as canonical statements — usually one, several for a
   * multi-valued date list, none when the property carries no meaning (an
   * ignored name, or a value equal to the RFC default). Dates become instants
   * or calendar days, durations become seconds, a multi-valued EXDATE is
   * exploded so one line holding two dates equals two lines holding one each,
   * calendar addresses are lowercased, text is unescaped, RRULEs are
   * canonicalised part by part, and parameters are sorted with the TZID and
   * default VALUE parameters dropped — the value normalisation already
   * carries what they said.
   *
   * @param property the parsed property
   * @param calendar the calendar it belongs to, for zone resolution
   * @return the canonical statements, empty when there is nothing to state
   */
  private static List<String> normaliseProperty(Property property, Calendar calendar) {
    String name = property.getName().toUpperCase(Locale.ROOT);
    if (IGNORED_PROPERTIES.contains(name)) {
      return List.of();
    }
    String value = property.getValue();
    if (value != null && value.equals(DEFAULT_STATEMENTS.get(name))) {
      return List.of();
    }
    String suffix = normaliseParameters(property, name);
    if (DATE_PROPERTIES.contains(name)) {
      String tzid = tzidOf(property);
      return Arrays.stream(value.split(","))
                   .map(token -> name + suffix + "=" + normaliseDateValue(token.trim(), tzid, calendar))
                   .collect(Collectors.toList());
    }
    String normalisedValue;
    if (DURATION_PROPERTIES.contains(name)) {
      normalisedValue = normaliseDuration(value);
    } else if ("RRULE".equals(name)) {
      normalisedValue = normaliseRecurrenceRule(value, calendar);
    } else if ("ORGANIZER".equals(name) || "ATTENDEE".equals(name)) {
      normalisedValue = value.toLowerCase(Locale.ROOT).startsWith("mailto:") ? value.toLowerCase(Locale.ROOT) : value;
    } else {
      normalisedValue = unescapeText(value);
    }
    return List.of(name + suffix + "=" + normalisedValue);
  }

  /**
   * The parameters of a property as a canonical, sorted suffix, dropping the
   * ones the value normalisation already accounts for: TZID (folded into the
   * instant), VALUE (the normalised value states its own type), and the
   * TRIGGER defaults RELATED=START and VALUE=DURATION.
   *
   * @param property the parsed property
   * @param name the upper-cased property name
   * @return the canonical parameter suffix, empty when nothing remains
   */
  private static String normaliseParameters(Property property, String name) {
    List<String> parameters = new ArrayList<>();
    for (Parameter parameter : property.getParameters()) {
      String parameterName = parameter.getName().toUpperCase(Locale.ROOT);
      String parameterValue = parameter.getValue();
      if ("TZID".equals(parameterName) && DATE_PROPERTIES.contains(name)) {
        continue;
      }
      if ("VALUE".equals(parameterName)) {
        continue;
      }
      if ("TRIGGER".equals(name) && "RELATED".equals(parameterName) && "START".equalsIgnoreCase(parameterValue)) {
        continue;
      }
      parameters.add(parameterName + "=" + parameterValue);
    }
    if (parameters.isEmpty()) {
      return "";
    }
    return ";" + parameters.stream().sorted().collect(Collectors.joining(";"));
  }

  /**
   * One date or date-time value as its meaning: a calendar day stays a day
   * ({@code DATE:20260910}); an instant — stated in UTC, or as a wall clock
   * resolved through the zone the object itself defines for the TZID, else
   * through the IANA zone of that name — becomes the instant
   * ({@code INSTANT:2026-10-12T07:00:00Z}); a wall clock no zone can resolve
   * stays a floating value ({@code FLOATING:20261110T100000}), which is
   * exactly how a reader treats it, so a dangling TZID never silently equals
   * an anchored time.
   *
   * @param value the raw property value token
   * @param tzid the TZID parameter of the property, or null
   * @param calendar the calendar the property belongs to, for its VTIMEZONEs
   * @return the canonical value
   */
  private static String normaliseDateValue(String value, String tzid, Calendar calendar) {
    if (DATE_VALUE.matcher(value).matches()) {
      return "DATE:" + value;
    }
    Matcher dateTime = DATE_TIME_VALUE.matcher(value);
    if (!dateTime.matches()) {
      return "RAW:" + value;
    }
    int year = Integer.parseInt(value.substring(0, 4));
    int month = Integer.parseInt(value.substring(4, 6));
    int day = Integer.parseInt(value.substring(6, 8));
    int hour = Integer.parseInt(value.substring(9, 11));
    int minute = Integer.parseInt(value.substring(11, 13));
    int second = Integer.parseInt(value.substring(13, 15));
    java.util.TimeZone zone = null;
    if (!dateTime.group(3).isEmpty()) {
      zone = java.util.TimeZone.getTimeZone("UTC");
    } else if (tzid != null) {
      zone = zoneOf(tzid, calendar);
    }
    if (zone == null) {
      return "FLOATING:" + value;
    }
    GregorianCalendar instant = new GregorianCalendar(zone);
    instant.clear();
    instant.set(year, month - 1, day, hour, minute, second);
    return "INSTANT:" + Instant.ofEpochMilli(instant.getTimeInMillis());
  }

  /**
   * The zone a TZID resolves through: the VTIMEZONE the object itself carries
   * for that id when there is one — the copy is read by clients through its
   * own definition, so that definition is authoritative — else the IANA zone
   * of that name, else nothing.
   *
   * @param tzid the TZID parameter value
   * @param calendar the calendar whose VTIMEZONEs are consulted
   * @return the resolved zone, or null when the reference dangles
   */
  private static java.util.TimeZone zoneOf(String tzid, Calendar calendar) {
    for (CalendarComponent component : calendar.getComponents(Component.VTIMEZONE)) {
      VTimeZone vTimeZone = (VTimeZone) component;
      Property id = vTimeZone.getProperty(Property.TZID);
      if (id != null && tzid.equals(id.getValue())) {
        return new net.fortuna.ical4j.model.TimeZone(vTimeZone);
      }
    }
    if (ZoneId.getAvailableZoneIds().contains(tzid)) {
      return java.util.TimeZone.getTimeZone(tzid);
    }
    return null;
  }

  /**
   * The TZID parameter of a property, or null when it carries none.
   *
   * @param property the parsed property
   * @return the TZID value, or null
   */
  private static String tzidOf(Property property) {
    Parameter tzid = property.getParameter(Parameter.TZID);
    return tzid == null ? null : tzid.getValue();
  }

  /**
   * An iCalendar duration as signed seconds, so {@code -PT60M} and
   * {@code -PT1H} state the same thing. A value that does not parse as a
   * duration is kept raw rather than guessed at.
   *
   * @param value the raw duration value
   * @return the canonical duration, or the raw value marked as such
   */
  private static String normaliseDuration(String value) {
    Matcher matcher = DURATION_VALUE.matcher(value.trim());
    if (!matcher.matches()) {
      return "RAW:" + value;
    }
    long seconds = parsedNumber(matcher.group(2)) * 7L * 86400 + parsedNumber(matcher.group(3)) * 86400L
        + parsedNumber(matcher.group(4)) * 3600L + parsedNumber(matcher.group(5)) * 60L + parsedNumber(matcher.group(6));
    if ("-".equals(matcher.group(1))) {
      seconds = -seconds;
    }
    return "SECONDS:" + seconds;
  }

  /**
   * A captured number of a duration match, zero when the part is absent.
   *
   * @param group the captured group, possibly null
   * @return the number, or zero
   */
  private static long parsedNumber(String group) {
    return group == null ? 0 : Long.parseLong(group);
  }

  /**
   * An RRULE value canonicalised part by part: parts sorted by name, list
   * parts (BYDAY, BYMONTH...) sorted internally, defaults equal to their own
   * absence dropped ({@code INTERVAL=1}, {@code WKST=MO}), and an UNTIL
   * normalised like every other date value.
   *
   * @param value the raw RRULE value
   * @param calendar the calendar the rule belongs to, for the UNTIL zone
   * @return the canonical rule
   */
  private static String normaliseRecurrenceRule(String value, Calendar calendar) {
    Map<String, String> parts = new TreeMap<>();
    for (String part : value.split(";")) {
      if (part.isBlank()) {
        continue;
      }
      String[] pair = part.split("=", 2);
      String partName = pair[0].trim().toUpperCase(Locale.ROOT);
      String partValue = pair.length > 1 ? pair[1].trim().toUpperCase(Locale.ROOT) : "";
      if ("INTERVAL".equals(partName) && "1".equals(partValue) || "WKST".equals(partName) && "MO".equals(partValue)) {
        continue;
      }
      if (partName.startsWith("BY")) {
        partValue = Arrays.stream(partValue.split(",")).map(String::trim).sorted().collect(Collectors.joining(","));
      }
      if ("UNTIL".equals(partName)) {
        partValue = normaliseDateValue(partValue, null, calendar);
      }
      parts.put(partName, partValue);
    }
    return parts.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(";"));
  }

  /**
   * Undoes RFC 5545 TEXT escaping, so a comparison never hinges on whether a
   * parser handed the value back escaped or not. A value with no backslash
   * passes through untouched.
   *
   * @param value the property value as the parser returned it
   * @return the unescaped text
   */
  private static String unescapeText(String value) {
    if (value == null || value.indexOf('\\') < 0) {
      return value;
    }
    StringBuilder unescaped = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (current == '\\' && i + 1 < value.length()) {
        char next = value.charAt(++i);
        unescaped.append(next == 'n' || next == 'N' ? '\n' : next);
      } else {
        unescaped.append(current);
      }
    }
    return unescaped.toString();
  }

  /**
   * Diffs two statement multisets of one location and reports every line one
   * side states and the other does not.
   *
   * @param location the component the statements belong to
   * @param leftStatements the left-hand multiset
   * @param rightStatements the right-hand multiset
   * @param kind the kind the divergences are reported as
   * @param differences the accumulator the divergences are added to
   */
  private static void diffStatements(String location,
                                     Map<String, Integer> leftStatements,
                                     Map<String, Integer> rightStatements,
                                     SemanticDifference.Kind kind,
                                     List<SemanticDifference> differences) {
    Set<String> statements = new TreeSet<>(leftStatements.keySet());
    statements.addAll(rightStatements.keySet());
    for (String statement : statements) {
      int leftCount = leftStatements.getOrDefault(statement, 0);
      int rightCount = rightStatements.getOrDefault(statement, 0);
      if (leftCount != rightCount) {
        differences.add(new SemanticDifference(kind,
                                               location,
                                               leftCount == 0 ? null : statement + " (x" + leftCount + ")",
                                               rightCount == 0 ? null : statement + " (x" + rightCount + ")"));
      }
    }
  }

  /**
   * Compares the statement multisets of one paired component.
   *
   * @param key the component's identity key
   * @param leftStatements the left-hand statements
   * @param rightStatements the right-hand statements
   * @param differences the accumulator the divergences are added to
   */
  private static void compareStatements(String key,
                                        Map<String, Integer> leftStatements,
                                        Map<String, Integer> rightStatements,
                                        List<SemanticDifference> differences) {
    diffStatements(key, leftStatements, rightStatements, SemanticDifference.Kind.PROPERTY, differences);
  }

  /**
   * Compares the recurrence sets of a paired master: both sides expanded over
   * the same probe window — each through the zone definitions its own object
   * carries — and the occurrence instants compared one by one. This is what
   * catches the drift instant-equality cannot: two series equal on their
   * first occurrence that part ways across a DST transition.
   *
   * @param key the component's identity key
   * @param leftCalendar the left-hand calendar, whose zones expand the left side
   * @param leftEvent the left-hand master
   * @param rightCalendar the right-hand calendar, whose zones expand the right side
   * @param rightEvent the right-hand master
   * @param differences the accumulator the divergences are added to
   */
  private static void compareExpansions(String key,
                                        Calendar leftCalendar,
                                        Component leftEvent,
                                        Calendar rightCalendar,
                                        Component rightEvent,
                                        List<SemanticDifference> differences) {
    boolean leftRecurs = leftEvent.getProperty(Property.RRULE) != null;
    boolean rightRecurs = rightEvent.getProperty(Property.RRULE) != null;
    if (!leftRecurs && !rightRecurs) {
      return;
    }
    List<String> leftOccurrences = expand(leftCalendar, leftEvent);
    List<String> rightOccurrences = expand(rightCalendar, rightEvent);
    if (leftOccurrences.equals(rightOccurrences)) {
      return;
    }
    int limit = Math.min(leftOccurrences.size(), rightOccurrences.size());
    for (int index = 0; index < limit; index++) {
      if (!leftOccurrences.get(index).equals(rightOccurrences.get(index))) {
        differences.add(new SemanticDifference(SemanticDifference.Kind.EXPANSION,
                                               key + " occurrence #" + (index + 1),
                                               leftOccurrences.get(index),
                                               rightOccurrences.get(index)));
        return;
      }
    }
    differences.add(new SemanticDifference(SemanticDifference.Kind.EXPANSION,
                                           key + " occurrence count over the probe window",
                                           String.valueOf(leftOccurrences.size()),
                                           String.valueOf(rightOccurrences.size())));
  }

  /**
   * Expands one master's recurrence set over the probe window, resolving its
   * wall clocks through the zone definitions its own calendar carries — the
   * parser registered them while building the object — and returns the
   * occurrence start instants in order.
   *
   * @param calendar the calendar the master belongs to
   * @param event the master component
   * @return the occurrence starts as ISO instants, capped
   */
  private static List<String> expand(Calendar calendar, Component event) {
    String start = propertyValue(event, Property.DTSTART);
    if (start == null) {
      return List.of();
    }
    String anchor = normaliseDateValue(start.split(",")[0], tzidOf(event.getProperty(Property.DTSTART)), calendar);
    Instant windowStart;
    if (anchor.startsWith("INSTANT:")) {
      windowStart = Instant.parse(anchor.substring("INSTANT:".length()));
    } else {
      windowStart = Instant.now();
      Matcher day = Pattern.compile("(\\d{4})(\\d{2})(\\d{2})").matcher(anchor);
      if (day.find()) {
        windowStart = Instant.parse(day.group(1) + "-" + day.group(2) + "-" + day.group(3) + "T00:00:00Z");
      }
    }
    Period window = new Period(new DateTime(java.util.Date.from(windowStart.minusSeconds(86400))),
                               new DateTime(java.util.Date.from(windowStart.plusSeconds(EXPANSION_DAYS * 86400L))));
    Set<String> occurrences = new LinkedHashSet<>();
    try {
      PeriodList periods = event.calculateRecurrenceSet(window);
      for (Period period : periods) {
        occurrences.add(Instant.ofEpochMilli(period.getStart().getTime()).toString());
        if (occurrences.size() >= EXPANSION_CAP) {
          break;
        }
      }
    } catch (RuntimeException e) {
      // An object whose recurrence cannot be expanded (an end before its
      // start, a rule ical4j refuses) is semantically broken: the brokenness
      // is reported as the expansion result, so it registers as a difference
      // against any healthy counterpart instead of aborting the comparison.
      occurrences.add("unexpandable: " + e.getMessage());
    }
    return new ArrayList<>(occurrences);
  }

  /**
   * The value of a named property of a component, or null when absent.
   *
   * @param component the component to read
   * @param name the property name
   * @return the raw value, or null
   */
  private static String propertyValue(Component component, String name) {
    Property property = component.getProperty(name);
    return property == null ? null : property.getValue();
  }
}
