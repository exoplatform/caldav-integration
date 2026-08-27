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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.GregorianCalendar;
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

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.PeriodList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VTimeZone;

/**
 * Answers one question: does the calendar object a server holds still
 * <b>say</b> what eXo would write for this event now?
 *
 * <p>
 * <b>Why meaning and not bytes.</b> The mirror pass used to compare a digest of
 * the object against a digest recorded at push time. That rests on a CalDAV
 * server being a blob store, and it is not: a calendar object is a structured
 * document, and a server that parses it into its own model and re-serialises it
 * is behaving normally — RFC 4791 asks a server to preserve the object's
 * semantics, not its bytes. Against BlueMind no byte baseline could be captured
 * at all: recording what was sent made every copy look tampered with, and
 * reading the object back after the write captured a state the server was still
 * settling and then finished settling without moving the ETag. Both are attempts
 * to <i>learn</i> one server's serialisation. This class understands the content
 * instead, so there is nothing left to record: the baseline is regenerated from
 * the eXo event on every comparison, and no digest is stored anywhere.
 *
 * <p>
 * <b>What is compared, and why exactly that.</b> Only the single VEVENT eXo's
 * own render describes, matched by UID and RECURRENCE-ID, with its VALARMs. That
 * is not a convenience: it is the scope a <i>repair</i> can act on. A repair
 * writes through {@link IcsMerger}, which replaces that one component and
 * deliberately leaves the rest of the document exactly as it found it — the
 * enclosing VCALENDAR's own properties, its VTIMEZONEs, and any other VEVENT
 * another client authored. Reporting a difference outside that scope would
 * produce a repair that changes nothing, and a pass that reports the same
 * difference again five minutes later, for ever. Every property compared here is
 * one a repair actually rewrites.
 *
 * <p>
 * <b>Conservative by construction.</b> The failure that matters is declaring
 * "equal" over a change a user made, which loses their edit silently. So within
 * the compared component the recognised set is closed — it is exactly what
 * {@link IcsWriter} emits — and <b>anything outside it counts as a
 * difference</b>: an unknown property, a property eXo does not emit, a
 * parameter that is not a documented default. The relaxations are enumerated in
 * {@link #IGNORED_EVENT_PROPERTIES}, {@link #IGNORED_PARAMETERS} and
 * {@link #DEFAULT_STATEMENTS}, each with the reason it is safe, and each is a
 * statement about the record rather than about the meeting.
 *
 * <p>
 * <b>A document's own structural properties are ignored wherever they sit.</b>
 * {@code VERSION}, {@code PRODID} and {@code CALSCALE} describe the document,
 * not the meeting, and this comparison never looked at them at calendar level.
 * BlueMind writes {@code VERSION:2.0} inside the VEVENT, which is not
 * conformant and is still not a disagreement — so they are ignored by name
 * rather than by position. See {@link #STRUCTURAL_PROPERTIES}.
 *
 * <p>
 * <b>One exemption inside the attendee set, and it is narrow on purpose.</b> A
 * server may attach the calendar's own owner to an event that lands in their
 * calendar — BlueMind writes
 * {@code ATTENDEE;CN=FRANCOIS;DIR=bm://19d43...} on every copy — and that is
 * permitted behaviour of the same kind as re-ordering properties. So an
 * attendee line naming the owner, present on the server's copy and not on
 * eXo's render, and <b>stating no answer</b>, is not a difference. Everything
 * else about the roster stays strict: any other attendee added or removed is a
 * difference, an owner line eXo states and the copy has lost is a difference,
 * and every PARTSTAT — the owner's included — is compared as before. See
 * {@link #tolerated}.
 *
 * <p>
 * <b>What the parser settles before this sees it.</b> ical4j unfolds continued
 * lines, unescapes TEXT values, trims a value's surrounding whitespace and
 * canonicalises a duration, so none of those can register as a difference and
 * none of them is redone here. That is a dependency, not an accident:
 * {@code IcsEquivalenceTest} characterises all four, so an upgrade that changed
 * any of them would be caught here rather than on somebody's calendar.
 *
 * <p>
 * <b>Three answers, not two.</b> {@code DIFFERENT} and {@code EQUIVALENT} are
 * the judgements; {@code UNJUDGEABLE} is what comes back when eXo's own render
 * cannot be read as a calendar object holding one event. That is a defect on
 * this side, and the caller leaves the copy alone rather than overwriting a
 * user's calendar on the strength of it.
 */
@Component
public class IcsEquivalence {

  /**
   * The structural properties of an iCalendar <i>document</i>, which are never
   * compared — at any level, wherever a server chooses to put them.
   *
   * <p>
   * These three are calendar-level by the RFC and {@link IcsWriter} emits them
   * there. The comparison already ignores them there, for the reason the scope
   * paragraph gives: a repair replaces one component and leaves the enclosing
   * VCALENDAR alone, so a difference on them could never be repaired away.
   *
   * <p>
   * BlueMind writes {@code VERSION:2.0} <b>inside the VEVENT</b>. That is not
   * conformant, and it is also not a disagreement: the property says "this
   * document is iCalendar 2.0" wherever it sits, eXo's own render says exactly
   * the same thing one level up, and neither statement is about the meeting.
   * Ignoring them by name rather than by position says that plainly, and keeps
   * the operator's {@code ignoredProperties} lever for what it was built for —
   * proprietary hints nobody could have anticipated.
   *
   * <p>
   * Nothing can hide here. {@code VERSION} is a fixed token, {@code CALSCALE}
   * names the calendar system, and {@code PRODID} names the software that wrote
   * the document; none of the three can express a fact about a meeting, so no
   * user edit can be spelled in one.
   */
  private static final Set<String>         STRUCTURAL_PROPERTIES    = Set.of("VERSION", "PRODID", "CALSCALE");

  /**
   * Properties of the compared component that are read but never compared,
   * because each states something about the record rather than about the
   * meeting.
   *
   * <p>
   * {@code DTSTAMP} is written as "now" by {@link IcsWriter} on every render, so
   * comparing it would make every object differ from itself. {@code CREATED} and
   * {@code LAST-MODIFIED} are timestamps a server sets or refreshes when it
   * stores; neither can be authored as an intent, and any real edit necessarily
   * moves something else that <i>is</i> compared. {@code URL} is the link back
   * into eXo, which is carried on the push request and never stored — a repair
   * cannot reconstruct it, so comparing it would report every copy altered
   * exactly once and then strip the very link it complained about.
   */
  private static final Set<String>         IGNORED_EVENT_PROPERTIES = Set.of("DTSTAMP", "CREATED", "LAST-MODIFIED", "URL");

  /**
   * The VEVENT properties {@link IcsWriter} emits — the closed recognised set.
   * Anything else inside the compared component is a difference.
   */
  private static final Set<String>         EVENT_PROPERTIES         = Set.of("UID",
                                                                             "SUMMARY",
                                                                             "DTSTART",
                                                                             "DTEND",
                                                                             "LOCATION",
                                                                             "DESCRIPTION",
                                                                             "CONFERENCE",
                                                                             "ORGANIZER",
                                                                             "ATTENDEE",
                                                                             "STATUS",
                                                                             "TRANSP",
                                                                             "RECURRENCE-ID",
                                                                             "RRULE",
                                                                             "EXDATE");

  /** The VALARM properties {@link IcsWriter} emits. */
  private static final Set<String>         ALARM_PROPERTIES         = Set.of("ACTION", "DESCRIPTION", "TRIGGER");

  /**
   * Properties of a VALARM that are read but never compared.
   *
   * <p>
   * {@code UID} names the alarm (RFC 9074) without saying anything about when or
   * how it fires. {@code ACKNOWLEDGED} records that somebody dismissed the
   * reminder — per-viewer state, not a property of the meeting; treating it as a
   * rewrite would have eXo resurrect a reminder the user has just dismissed.
   */
  private static final Set<String>         IGNORED_ALARM_PROPERTIES = Set.of("UID", "ACKNOWLEDGED");

  /**
   * Statements equal to their own absence, per the RFC 5545 defaults. eXo writes
   * {@code TRANSP:OPAQUE} explicitly and a server is free to drop it as
   * redundant; it writes none of the other three, and a server is free to add
   * them at their default. A non-default value of any of them is not here, so it
   * still registers — {@code SEQUENCE:1} means a client edited the object.
   */
  private static final Map<String, String> DEFAULT_STATEMENTS       = Map.of("TRANSP",
                                                                             "OPAQUE",
                                                                             "SEQUENCE",
                                                                             "0",
                                                                             "CLASS",
                                                                             "PUBLIC",
                                                                             "PRIORITY",
                                                                             "0");

  /**
   * Parameters that are read but never compared.
   *
   * <p>
   * {@code TZID} and {@code VALUE} are folded into the normalised value, so
   * comparing them again would count one statement twice and make a re-spelled
   * zone identifier — {@code Europe/Paris} against
   * {@code /freeassociation.sourceforge.net/Europe/Paris} — a difference even
   * when both resolve to the same instant. The {@code SCHEDULE-*} family is
   * RFC 6638 scheduling control: an instruction to the server about how to
   * process the write, and a result the server writes back — never something a
   * user authors. eXo sends {@code SCHEDULE-AGENT=CLIENT} on every attendee, and
   * a scheduling-aware server that consumes it would otherwise leave every copy
   * permanently "altered".
   *
   * <p>
   * {@code DIR} (RFC 5545 section 3.2.6) is a URI pointing at the server's own
   * directory entry for a calendar user — {@code bm://19d43...} on BlueMind. It
   * says who the server thinks the person is, in the server's own namespace,
   * and nothing whatever about the meeting. It cannot hide an attendee change
   * either: the address and the PARTSTAT are compared regardless.
   */
  private static final Set<String>         IGNORED_PARAMETERS       = Set.of("TZID",
                                                                             "VALUE",
                                                                             "DIR",
                                                                             "SCHEDULE-AGENT",
                                                                             "SCHEDULE-STATUS",
                                                                             "SCHEDULE-FORCE-SEND");

  /**
   * Parameter values equal to their own absence, per the RFC 5545 and RFC 5545
   * §3.8.6.3 defaults. A server filling in what the RFC already implies is not a
   * rewrite; a value that is <i>not</i> the default — an attendee who has
   * accepted, a trigger related to the end — is not here and still registers.
   */
  private static final Map<String, String> DEFAULT_PARAMETERS       = Map.of("ROLE",
                                                                             "REQ-PARTICIPANT",
                                                                             "CUTYPE",
                                                                             "INDIVIDUAL",
                                                                             "RSVP",
                                                                             "FALSE",
                                                                             "PARTSTAT",
                                                                             "NEEDS-ACTION",
                                                                             "RELATED",
                                                                             "START");

  /** Properties whose value is a date or date-time, normalised to an instant or a calendar day. */
  private static final Set<String>         DATE_PROPERTIES          = Set.of("DTSTART",
                                                                             "DTEND",
                                                                             "RECURRENCE-ID",
                                                                             "EXDATE");

  /** A date-time value in the iCalendar basic format, with or without the UTC marker. */
  private static final Pattern             DATE_TIME_VALUE          = Pattern.compile("^(\\d{8})T(\\d{6})(Z?)$");

  /** A date value in the iCalendar basic format. */
  private static final Pattern             DATE_VALUE               = Pattern.compile("^\\d{8}$");

  /** A calendar day anywhere inside a canonical value, for the probe window. */
  private static final Pattern             CALENDAR_DAY             = Pattern.compile("(\\d{4})(\\d{2})(\\d{2})");

  /** Where a probe window starts when the anchor cannot be read at all. */
  private static final Instant             UNREADABLE_ANCHOR        = Instant.parse("2000-01-01T00:00:00Z");

  /** How far past the series start the recurrence expansion probes, in days. */
  private static final int                 EXPANSION_DAYS           = 450;

  /** How many occurrences the expansion compares at most. */
  private static final int                 EXPANSION_CAP            = 120;

  /** Seconds in a day, for the expansion window. */
  private static final long                DAY_SECONDS              = 86400L;

  /**
   * The canonical statement an attendee naming the account's own owner is
   * reduced to.
   *
   * <p>
   * Deliberately without an address. A copy names its owner two ways — the
   * address their CalDAV account answers to, and their eXo profile address —
   * and the two differ in practice. Every other place that has to recognise
   * this person accepts both ({@code CaldavPushService.addressesNaming},
   * {@code CaldavAnswerAdoptionService.adoptAnswer}); an exemption that checked
   * only one of them would miss exactly the way EXO-89715 missed, so identity
   * here is "the owner", not a particular spelling of them.
   *
   * <p>
   * What survives on it is the PARTSTAT, and only the PARTSTAT: their answer is
   * the one thing about their own line that is theirs rather than the server's.
   */
  private static final String              OWNER_ATTENDEE           = "OWNER-ATTENDEE";

  /** How many divergences are named in the reported detail before it is cut short. */
  private static final int                 REPORTED_DIVERGENCES     = 3;

  /**
   * How much of one statement a reported divergence carries. A description or a
   * proprietary property can hold kilobytes, and this text goes into a log line
   * on every pass that finds the copy altered.
   */
  private static final int                 REPORTED_STATEMENT       = 120;

  /**
   * Property names the operator has declared uninteresting when a server adds
   * them.
   *
   * <p>
   * The one lever this design leaves for a server nobody here has seen: a
   * proprietary property re-added inside the VEVENT on every store would
   * otherwise make its copies permanently altered, and each pass would rewrite
   * them. Deliberately <b>narrow</b>: it can only suppress an
   * <i>unrecognised</i> property, never a mismatch on a property eXo emits, so
   * no configuration of it can make a changed summary or a moved start time look
   * equal. Empty by default — nothing is ignored until somebody has read a log
   * line naming what to ignore.
   */
  @Value("${exo.agenda.caldav.mirror.ignoredProperties:}")
  private String                           ignoredProperties        = "";

  /**
   * Compares the object a server holds against the object eXo would write for
   * the same event now.
   *
   * @param serverIcs the calendar object as the server holds it
   * @param exoIcs the calendar object eXo's engine renders for the event now
   * @param ownerAddresses every address a copy on this account may name its own
   *          owner by — the address the CalDAV account answers to and the eXo
   *          profile address, which differ in practice. Both are accepted, for
   *          the reason {@link #OWNER_ATTENDEE} records. May be null or empty,
   *          which simply means no attendee is treated as the owner.
   * @return the judgement, carrying what diverged when it is
   *         {@link Verdict#DIFFERENT}
   */
  public Judgement compare(String serverIcs, String exoIcs, Collection<String> ownerAddresses) {
    Set<String> owner = ownerAddresses == null ? Set.of()
                                               : ownerAddresses.stream()
                                                               .filter(StringUtils::isNotBlank)
                                                               .map(this::bareAddress)
                                                               .collect(Collectors.toSet());
    Calendar exo;
    try {
      exo = parse(exoIcs);
    } catch (IcsParseException e) {
      // eXo's own render is unreadable. That is a defect here, never evidence
      // about the user's calendar, so nothing is concluded from it.
      return new Judgement(Verdict.UNJUDGEABLE, "the object eXo renders cannot be read: " + e.getMessage());
    }
    VEvent owned = singleEvent(exo);
    if (owned == null) {
      return new Judgement(Verdict.UNJUDGEABLE, "the object eXo renders carries no single event");
    }
    Calendar server;
    try {
      server = parse(serverIcs);
    } catch (IcsParseException e) {
      // The copy is there and cannot be read as iCalendar. Bounded rather than
      // silent: the repair will fail on the same parse and the pass gives up
      // after a few attempts, saying so — which is the honest outcome.
      return new Judgement(Verdict.DIFFERENT, "the object the server holds cannot be read: " + e.getMessage());
    }
    String key = identityOf(owned, exo);
    VEvent counterpart = eventWithIdentity(server, key);
    if (counterpart == null) {
      return new Judgement(Verdict.DIFFERENT, "the component " + key + " is not in the object any more");
    }
    List<String> divergences = divergences(counterpart, server, owned, exo, owner);
    if (divergences.isEmpty()) {
      return new Judgement(Verdict.EQUIVALENT, null);
    }
    return new Judgement(Verdict.DIFFERENT, String.join("; ", divergences));
  }

  /**
   * Every way the two paired components disagree: their statements, and — when
   * either repeats — the instants their series produces.
   *
   * @param serverEvent the component the server holds
   * @param serverCalendar the object it belongs to, for its zone definitions
   * @param exoEvent the component eXo renders
   * @param exoCalendar the object it belongs to, for its zone definitions
   * @return the divergences, capped, empty when the two say the same thing
   */
  private List<String> divergences(VEvent serverEvent,
                                   Calendar serverCalendar,
                                   VEvent exoEvent,
                                   Calendar exoCalendar,
                                   Set<String> ownerAddresses) {
    List<String> divergences = new ArrayList<>();
    diff(statementsOf(serverEvent, serverCalendar, EVENT_PROPERTIES, IGNORED_EVENT_PROPERTIES, ownerAddresses),
         statementsOf(exoEvent, exoCalendar, EVENT_PROPERTIES, IGNORED_EVENT_PROPERTIES, ownerAddresses),
         divergences);
    if (divergences.isEmpty()) {
      diffExpansions(serverCalendar, serverEvent, exoCalendar, exoEvent, divergences);
    }
    return divergences;
  }

  /**
   * Parses one calendar object.
   *
   * <p>
   * With ical4j's own defaults, deliberately: {@link IcsMerger} parses the very
   * same document when a repair rewrites it, so an object this cannot read is an
   * object the repair cannot merge into either. Relaxing the parser here alone
   * would make the pass judge documents it cannot then act on.
   *
   * @param ics the calendar object as text
   * @return the parsed calendar
   * @throws IcsParseException when the text is not readable iCalendar
   */
  private Calendar parse(String ics) {
    if (StringUtils.isBlank(ics)) {
      throw new IcsParseException("the calendar object is empty", null);
    }
    try {
      return new CalendarBuilder().build(new StringReader(ics));
    } catch (Exception e) {
      throw new IcsParseException("The calendar object could not be read as iCalendar", e);
    }
  }

  /**
   * The one VEVENT of the object eXo renders.
   *
   * <p>
   * {@link IcsWriter} writes exactly one, and the scope of this comparison is
   * that component. An object carrying none — or several — is not something to
   * guess about.
   *
   * @param calendar the parsed object eXo renders
   * @return the component, or null when there is not exactly one
   */
  private VEvent singleEvent(Calendar calendar) {
    List<CalendarComponent> events = new ArrayList<>(calendar.getComponents(net.fortuna.ical4j.model.Component.VEVENT));
    return events.size() == 1 ? (VEvent) events.get(0) : null;
  }

  /**
   * The identity of a component within its object: its UID and the instance it
   * amends, so a master and each of its overrides are told apart.
   *
   * @param event the component
   * @param calendar the object it belongs to, for zone resolution
   * @return the identity key
   */
  private String identityOf(VEvent event, Calendar calendar) {
    Property uid = event.getProperty(Property.UID);
    Property recurrenceId = event.getProperty(Property.RECURRENCE_ID);
    return (uid == null ? "(no uid)" : uid.getValue()) + "#"
        + (recurrenceId == null ? "master" : normaliseDateValue(recurrenceId.getValue(), tzidOf(recurrenceId), calendar));
  }

  /**
   * The component of an object carrying a given identity.
   *
   * @param calendar the parsed object the server holds
   * @param key the identity to look for
   * @return the component, or null when the object no longer carries it
   */
  private VEvent eventWithIdentity(Calendar calendar, String key) {
    for (CalendarComponent component : calendar.getComponents(net.fortuna.ical4j.model.Component.VEVENT)) {
      VEvent event = (VEvent) component;
      if (key.equals(identityOf(event, calendar))) {
        return event;
      }
    }
    return null;
  }

  /**
   * The statements a component makes, as a multiset of canonical lines: one per
   * meaningful property occurrence, plus one per VALARM, so property order,
   * parameter order and line folding cannot register as a difference while a
   * repeated statement still counts.
   *
   * @param component the component to normalise
   * @param calendar the object it belongs to, for zone resolution
   * @param recognised the closed set of property names this level emits
   * @param ignored the property names read but never compared at this level
   * @return the statements, by canonical line and count
   */
  private Map<String, Integer> statementsOf(net.fortuna.ical4j.model.Component component,
                                            Calendar calendar,
                                            Set<String> recognised,
                                            Set<String> ignored,
                                            Set<String> ownerAddresses) {
    Map<String, Integer> statements = new TreeMap<>();
    for (Property property : component.getProperties()) {
      for (String statement : normaliseProperty(property, calendar, recognised, ignored, ownerAddresses)) {
        statements.merge(statement, 1, Integer::sum);
      }
    }
    if (component instanceof VEvent event) {
      for (VAlarm alarm : event.getAlarms()) {
        Map<String, Integer> inner = statementsOf(alarm, calendar, ALARM_PROPERTIES, IGNORED_ALARM_PROPERTIES, ownerAddresses);
        statements.merge("VALARM{" + String.join("&", flatten(inner)) + "}", 1, Integer::sum);
      }
    }
    return statements;
  }

  /**
   * Flattens a statement multiset into sorted, counted lines, so a VALARM can be
   * embedded as a single statement of the component that carries it.
   *
   * @param statements the multiset to flatten
   * @return one line per distinct statement, count included when above one
   */
  private List<String> flatten(Map<String, Integer> statements) {
    return statements.entrySet()
                     .stream()
                     .map(entry -> entry.getValue() == 1 ? entry.getKey() : entry.getKey() + "(x" + entry.getValue() + ")")
                     .collect(Collectors.toList());
  }

  /**
   * One property as canonical statements: none when it carries nothing to
   * compare, several for a multi-valued date list, one otherwise — and, for a
   * name outside the recognised set, a statement that names it as unrecognised
   * so that it can only ever compare unequal against a side that does not carry
   * it.
   *
   * @param property the parsed property
   * @param calendar the object it belongs to, for zone resolution
   * @param recognised the closed set of property names this level emits
   * @param ignored the property names read but never compared at this level
   * @return the canonical statements, possibly empty
   */
  private List<String> normaliseProperty(Property property,
                                         Calendar calendar,
                                         Set<String> recognised,
                                         Set<String> ignored,
                                         Set<String> ownerAddresses) {
    String name = property.getName().toUpperCase(Locale.ROOT);
    if (ignored.contains(name) || STRUCTURAL_PROPERTIES.contains(name)) {
      return List.of();
    }
    String value = property.getValue();
    if (StringUtils.isBlank(value)) {
      // An empty value states nothing a reader can show, so it equals the
      // property's own absence. Safe in the one direction that matters: this
      // can only ever collapse empty against absent, never empty against a
      // value somebody wrote.
      return List.of();
    }
    if (value.equals(DEFAULT_STATEMENTS.get(name))) {
      return List.of();
    }
    if (!recognised.contains(name)) {
      if (isIgnoredByConfiguration(name)) {
        return List.of();
      }
      return List.of("UNRECOGNISED:" + name + "=" + value);
    }
    if ("ATTENDEE".equals(name) && ownerAddresses.contains(bareAddress(value))) {
      return List.of(ownerStatement(property));
    }
    String suffix = normaliseParameters(property);
    if (DATE_PROPERTIES.contains(name)) {
      String tzid = tzidOf(property);
      return Arrays.stream(value.split(","))
                   .map(token -> name + suffix + "=" + normaliseDateValue(token.trim(), tzid, calendar))
                   .collect(Collectors.toList());
    }
    return List.of(name + suffix + "=" + normaliseValue(name, value, calendar));
  }

  /**
   * Whether a property name the recognised set does not carry has been declared
   * uninteresting for this deployment.
   *
   * @param name the upper-cased property name
   * @return true when the operator asked for it to be ignored
   */
  private boolean isIgnoredByConfiguration(String name) {
    if (StringUtils.isBlank(ignoredProperties)) {
      return false;
    }
    return Arrays.stream(ignoredProperties.split(","))
                 .map(entry -> entry.trim().toUpperCase(Locale.ROOT))
                 .anyMatch(name::equals);
  }

  /**
   * A property value as its meaning, by the kind of value the property carries.
   *
   * @param name the upper-cased property name
   * @param value the raw value
   * @param calendar the object it belongs to, for the UNTIL zone of a rule
   * @return the canonical value
   */
  private String normaliseValue(String name, String value, Calendar calendar) {
    if ("RRULE".equals(name)) {
      return normaliseRecurrenceRule(value, calendar);
    }
    if ("ORGANIZER".equals(name) || "ATTENDEE".equals(name)) {
      // A calendar address is a URI: its scheme and its host are
      // case-insensitive, and servers do re-case them. Nobody edits a meeting
      // by changing the case of an address, so the whole value is folded
      // rather than parsed apart.
      return value.toLowerCase(Locale.ROOT).startsWith("mailto:") ? value.toLowerCase(Locale.ROOT) : value;
    }
    return value;
  }

  /**
   * A calendar address without its scheme or its casing, so that two spellings
   * of the same person compare equal.
   *
   * @param value a CAL-ADDRESS, a bare mail address, or null
   * @return the comparable form, never null
   */
  private String bareAddress(String value) {
    String trimmed = StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
    return StringUtils.removeStart(trimmed, "mailto:");
  }

  /**
   * The canonical statement for an attendee line naming the account's own
   * owner: the fact that it is theirs, plus their answer if it states one.
   *
   * <p>
   * Everything else on the line is dropped — the address spelling, the CN the
   * server renders from its own directory, the DIR pointer into it. A server
   * attaching the calendar's owner to an event that lands in their calendar is
   * normal behaviour, and it writes that line in its own terms; none of those
   * terms is a statement about the meeting.
   *
   * @param property the ATTENDEE property naming the owner
   * @return the canonical statement
   */
  private String ownerStatement(Property property) {
    Parameter partStat = property.getParameter("PARTSTAT");
    String answer = partStat == null ? null : StringUtils.trimToNull(partStat.getValue());
    if (answer == null || answer.equalsIgnoreCase(DEFAULT_PARAMETERS.get("PARTSTAT"))) {
      // No answer stated. NEEDS-ACTION is the RFC default and reads the same as
      // saying nothing, which is what a server writes when it attaches somebody
      // to an event they have not replied to yet.
      return OWNER_ATTENDEE;
    }
    return OWNER_ATTENDEE + ";PARTSTAT=" + answer.toUpperCase(Locale.ROOT);
  }

  /**
   * The parameters of a property as a canonical, sorted suffix: the ignored ones
   * dropped, the ones stating an RFC default dropped, the rest compared —
   * including any parameter neither side is expected to carry, which therefore
   * registers.
   *
   * @param property the parsed property
   * @return the canonical parameter suffix, empty when nothing remains
   */
  private String normaliseParameters(Property property) {
    List<String> parameters = new ArrayList<>();
    for (Parameter parameter : property.getParameters()) {
      String name = parameter.getName().toUpperCase(Locale.ROOT);
      String value = parameter.getValue();
      if (IGNORED_PARAMETERS.contains(name)) {
        continue;
      }
      if (value != null && value.equalsIgnoreCase(DEFAULT_PARAMETERS.get(name))) {
        continue;
      }
      parameters.add(name + "=" + StringUtils.defaultString(value));
    }
    if (parameters.isEmpty()) {
      return "";
    }
    return ";" + parameters.stream().sorted().collect(Collectors.joining(";"));
  }

  /**
   * One date or date-time value as its meaning: a calendar day stays a day, an
   * instant becomes the instant it denotes — each side resolved through the zone
   * definition its own object carries, which is how a client reads it, falling
   * back to the IANA zone of that name — and a wall clock no zone can resolve
   * stays floating, so a dangling zone reference never silently equals an
   * anchored time.
   *
   * <p>
   * This is what makes a re-spelled zone identifier a non-event: BlueMind's
   * {@code /freeassociation.sourceforge.net/Europe/Paris} and eXo's
   * {@code Europe/Paris} resolve through their own objects' VTIMEZONEs to the
   * same instant, and the identifier itself is never compared.
   *
   * @param value the raw property value token
   * @param tzid the TZID parameter of the property, or null
   * @param calendar the object the property belongs to, for its VTIMEZONEs
   * @return the canonical value
   */
  private String normaliseDateValue(String value, String tzid, Calendar calendar) {
    if (DATE_VALUE.matcher(value).matches()) {
      return "DATE:" + value;
    }
    Matcher dateTime = DATE_TIME_VALUE.matcher(value);
    if (!dateTime.matches()) {
      return "RAW:" + value;
    }
    java.util.TimeZone zone = null;
    if (!dateTime.group(3).isEmpty()) {
      zone = java.util.TimeZone.getTimeZone("UTC");
    } else if (tzid != null) {
      zone = zoneOf(tzid, calendar);
    }
    if (zone == null) {
      return "FLOATING:" + value;
    }
    GregorianCalendar resolved = new GregorianCalendar(zone);
    resolved.clear();
    resolved.set(Integer.parseInt(value.substring(0, 4)),
                 Integer.parseInt(value.substring(4, 6)) - 1,
                 Integer.parseInt(value.substring(6, 8)),
                 Integer.parseInt(value.substring(9, 11)),
                 Integer.parseInt(value.substring(11, 13)),
                 Integer.parseInt(value.substring(13, 15)));
    return "INSTANT:" + Instant.ofEpochMilli(resolved.getTimeInMillis());
  }

  /**
   * The zone a TZID resolves through: the definition the object itself carries
   * for that identifier when there is one — a client reads the copy through that
   * definition, so it is the authoritative one — else the IANA zone of that
   * name, else nothing.
   *
   * @param tzid the TZID parameter value
   * @param calendar the object whose VTIMEZONEs are consulted
   * @return the resolved zone, or null when the reference dangles
   */
  private java.util.TimeZone zoneOf(String tzid, Calendar calendar) {
    for (CalendarComponent component : calendar.getComponents(net.fortuna.ical4j.model.Component.VTIMEZONE)) {
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
  private String tzidOf(Property property) {
    Parameter tzid = property.getParameter(Parameter.TZID);
    return tzid == null ? null : tzid.getValue();
  }

  /**
   * A recurrence rule canonicalised part by part: parts sorted by name, list
   * parts sorted internally, parts equal to their own default dropped, and an
   * UNTIL normalised like any other date value — so a server restating the same
   * rule in its own order is not a rewrite.
   *
   * @param value the raw RRULE value
   * @param calendar the object the rule belongs to, for the UNTIL zone
   * @return the canonical rule
   */
  private String normaliseRecurrenceRule(String value, Calendar calendar) {
    Map<String, String> parts = new TreeMap<>();
    for (String part : value.split(";")) {
      if (part.isBlank()) {
        continue;
      }
      String[] pair = part.split("=", 2);
      String name = pair[0].trim().toUpperCase(Locale.ROOT);
      String partValue = pair.length > 1 ? pair[1].trim().toUpperCase(Locale.ROOT) : "";
      if ("INTERVAL".equals(name) && "1".equals(partValue) || "WKST".equals(name) && "MO".equals(partValue)) {
        continue;
      }
      if (name.startsWith("BY")) {
        partValue = Arrays.stream(partValue.split(",")).map(String::trim).sorted().collect(Collectors.joining(","));
      }
      if ("UNTIL".equals(name)) {
        partValue = normaliseDateValue(partValue, null, calendar);
      }
      parts.put(name, partValue);
    }
    return parts.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(";"));
  }

  /**
   * Reports every statement one side makes and the other does not, capped so a
   * log line stays a log line.
   *
   * @param serverStatements what the server's component says
   * @param exoStatements what eXo's component says
   * @param divergences the accumulator the findings are added to
   */
  private void diff(Map<String, Integer> serverStatements,
                    Map<String, Integer> exoStatements,
                    List<String> divergences) {
    Set<String> statements = new TreeSet<>(serverStatements.keySet());
    statements.addAll(exoStatements.keySet());
    int reported = 0;
    for (String statement : statements) {
      int onServer = serverStatements.getOrDefault(statement, 0);
      int inExo = exoStatements.getOrDefault(statement, 0);
      if (onServer == inExo || tolerated(statement, onServer, inExo)) {
        continue;
      }
      if (reported++ < REPORTED_DIVERGENCES) {
        divergences.add(StringUtils.abbreviate(statement, REPORTED_STATEMENT) + " (server " + onServer + ", eXo " + inExo
            + ")");
      }
    }
    if (reported > REPORTED_DIVERGENCES) {
      divergences.add("and " + (reported - REPORTED_DIVERGENCES) + " more");
    }
  }

  /**
   * Whether a divergence is the one thing a server may add to a copy without
   * having rewritten it: the calendar's own owner, attached to an event that
   * landed in their calendar, with no answer stated.
   *
   * <p>
   * BlueMind does exactly this — {@code ATTENDEE;CN=FRANCOIS;DIR=bm://19d43...}
   * on every copy — and it is permitted, normal behaviour of the same kind as
   * re-ordering properties or filling in an RFC default. Left as a difference
   * it made all 20 copies of a live account altered and re-pushed on every
   * sweep, which the repair could never remove because the server puts the line
   * straight back.
   *
   * <p>
   * <b>Three conditions, and each is load-bearing.</b> The statement must be the
   * owner's own line, so no other attendee is covered — a client adding or
   * removing somebody is a real edit and this pass exists to catch it. It must
   * carry <b>no answer</b>: a surplus owner line saying ACCEPTED or DECLINED is
   * the user replying from their own client, which must still register so
   * EXO-89681 can read it off the copy. And the surplus must be on the
   * <b>server's</b> side: eXo stating a line the copy no longer carries is an
   * attendee somebody removed, which is a difference in the ordinary way.
   *
   * @param statement the canonical statement that diverged
   * @param onServer how many times the server's copy states it
   * @param inExo how many times eXo's render states it
   * @return true when the divergence is not a rewrite
   */
  private boolean tolerated(String statement, int onServer, int inExo) {
    return OWNER_ATTENDEE.equals(statement) && onServer > inExo;
  }

  /**
   * Compares the instants a repeating component produces, on both sides, over
   * the same probe window.
   *
   * <p>
   * The one divergence statement equality cannot see. A series anchored in UTC
   * and the same series anchored on a zone agree on every occurrence up to a
   * daylight-saving transition and part company after it: identical DTSTARTs,
   * different meetings. Each side is expanded through the zone definitions its
   * own object carries, which is how the client holding it will expand it.
   *
   * @param serverCalendar the object the server holds
   * @param serverEvent its component
   * @param exoCalendar the object eXo renders
   * @param exoEvent its component
   * @param divergences the accumulator the findings are added to
   */
  private void diffExpansions(Calendar serverCalendar,
                              VEvent serverEvent,
                              Calendar exoCalendar,
                              VEvent exoEvent,
                              List<String> divergences) {
    if (serverEvent.getProperty(Property.RRULE) == null && exoEvent.getProperty(Property.RRULE) == null) {
      return;
    }
    List<String> onServer = expand(serverCalendar, serverEvent);
    List<String> inExo = expand(exoCalendar, exoEvent);
    if (onServer.equals(inExo)) {
      return;
    }
    int limit = Math.min(onServer.size(), inExo.size());
    for (int index = 0; index < limit; index++) {
      if (!onServer.get(index).equals(inExo.get(index))) {
        divergences.add("occurrence " + (index + 1) + " (server " + onServer.get(index) + ", eXo " + inExo.get(index) + ")");
        return;
      }
    }
    divergences.add("occurrences over the probe window (server " + onServer.size() + ", eXo " + inExo.size() + ")");
  }

  /**
   * Expands one repeating component over the probe window and returns its
   * occurrence starts in order.
   *
   * @param calendar the object the component belongs to
   * @param event the component
   * @return the occurrence starts as instants, capped
   */
  private List<String> expand(Calendar calendar, VEvent event) {
    Property start = event.getProperty(Property.DTSTART);
    if (start == null) {
      return List.of();
    }
    String anchor = normaliseDateValue(start.getValue().split(",")[0], tzidOf(start), calendar);
    Instant from = anchorInstant(anchor);
    Period window = new Period(new DateTime(java.util.Date.from(from.minusSeconds(DAY_SECONDS))),
                               new DateTime(java.util.Date.from(from.plusSeconds(EXPANSION_DAYS * DAY_SECONDS))));
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
      // A series that cannot be expanded — a rule ical4j refuses, an end before
      // its start — is reported as that, so it registers against a healthy
      // counterpart instead of aborting the comparison.
      occurrences.add("unexpandable: " + e.getMessage());
    }
    return new ArrayList<>(occurrences);
  }

  /**
   * The instant a probe window starts from, read off a canonical DTSTART value.
   *
   * <p>
   * Never {@code Instant.now()} as a fallback, however unreachable that branch
   * looks: the two sides are expanded by two separate calls, so a clock read
   * would give them two different windows and the comparison could then answer
   * differently on two runs over the same pair of objects. A pass that says
   * "altered" only sometimes is worse than one that is wrong consistently.
   *
   * @param anchor the canonical DTSTART value
   * @return the instant to anchor the window on
   */
  private Instant anchorInstant(String anchor) {
    if (anchor.startsWith("INSTANT:")) {
      return Instant.parse(anchor.substring("INSTANT:".length()));
    }
    Matcher day = CALENDAR_DAY.matcher(anchor);
    if (day.find()) {
      return Instant.parse(day.group(1) + "-" + day.group(2) + "-" + day.group(3) + "T00:00:00Z");
    }
    return UNREADABLE_ANCHOR;
  }

  /** What a comparison can conclude. */
  public enum Verdict {
    /** The server's copy states what eXo would write. */
    EQUIVALENT,
    /** It does not, and the difference is one a repair would remove. */
    DIFFERENT,
    /** Nothing can be concluded, because eXo's own render is not usable. */
    UNJUDGEABLE
  }

  /**
   * A comparison's outcome.
   *
   * @param verdict what was concluded
   * @param detail what diverged, or why nothing could be concluded; null when
   *          the two objects state the same thing
   */
  public record Judgement(Verdict verdict, String detail) {

    /**
     * @return whether the server's copy is not what eXo would write
     */
    public boolean different() {
      return verdict == Verdict.DIFFERENT;
    }
  }
}
