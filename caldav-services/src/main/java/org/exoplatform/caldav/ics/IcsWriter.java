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

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import org.exoplatform.agenda.util.EventIcsBuilder;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.IcsPerson;
import org.exoplatform.caldav.model.IcsReminder;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.DateList;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryFactory;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.Cn;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.parameter.ScheduleAgent;
import net.fortuna.ical4j.model.parameter.Value;
import net.fortuna.ical4j.model.property.Action;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.CalScale;
import net.fortuna.ical4j.model.property.Conference;
import net.fortuna.ical4j.model.property.Created;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStamp;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.ExDate;
import net.fortuna.ical4j.model.property.LastModified;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.Organizer;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.RRule;
import net.fortuna.ical4j.model.property.RecurrenceId;
import net.fortuna.ical4j.model.property.Status;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Transp;
import net.fortuna.ical4j.model.property.Trigger;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.Url;
import net.fortuna.ical4j.model.property.Version;

/**
 * Builds the iCalendar object for one eXo event.
 *
 * <p>
 * The Java counterpart of the browser connector's write path, on ical4j. Every
 * rule below was learned from a defect and is documented where it is applied
 * rather than in a changelog nobody reads — the golden corpus (EXO-89521) is
 * what proves this engine still obeys them.
 *
 * <p>
 * One thing this port deliberately does <i>not</i> carry over: the connector's
 * derivation of a VTIMEZONE from the browser's Intl data, which reads one
 * year's behaviour and projects it forward as yearly rules. ical4j's registry
 * carries the real historical record, so the derivation is replaced rather
 * than translated. Its own comments call that registry "the authoritative
 * source".
 */
@Component
public class IcsWriter {

  /** Logger for the decisions worth tracing: a declined zone, an unparseable rule. */
  private static final Log                   LOG          = ExoLogger.getLogger(IcsWriter.class);

  /** Identifies eXo as the producer, unchanged from the browser connector. */
  public static final String            PROD_ID      = "-//Exo Platform//NONSGML v1.0//EN";

  /** ical4j's own zone registry: the authoritative record the browser derivation approximated. */
  private static final TimeZoneRegistry      REGISTRY     = TimeZoneRegistryFactory.getInstance().createRegistry();

  /** Marks a copy as one nobody performs scheduling for; see addPeople. */
  private static final String                CLIENT_AGENT = "CLIENT";

  /** The YYYYMMDD form an iCalendar date value is written in. */
  private static final java.time.format.DateTimeFormatter DATE_FORMAT = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");

  /** Length of the leading date in an ISO value, for the date-only branch. */
  private static final int                   DATE_LENGTH  = 10;

  /**
   * The whole iCalendar object for one event, ready to be pushed.
   *
   * @param event the event to write, with identities already resolved
   * @return the VCALENDAR object as text
   */
  public String write(IcsEvent event) {
    Calendar calendar = new Calendar();
    calendar.getProperties().add(Version.VERSION_2_0);
    calendar.getProperties().add(new ProdId(PROD_ID));
    calendar.getProperties().add(CalScale.GREGORIAN);

    // A VTIMEZONE is written only for a recurring timed event, exactly as the
    // browser connector decides. A single event needs no rule projected into
    // the future, and an all-day one carries no time a transition could move.
    boolean occurrence = StringUtils.isNotBlank(event.getOccurrenceId());
    boolean recurring = StringUtils.isNotBlank(event.getRecurrenceRule()) || occurrence;
    TimeZone timeZone = !event.isAllDay() && recurring ? resolveTimeZone(event.getTimeZoneId()) : null;
    if (timeZone != null) {
      calendar.getComponents().add(timeZone.getVTimeZone());
    }

    VEvent vEvent = new VEvent(new net.fortuna.ical4j.model.PropertyList(), alarms(event));
    vEvent.getProperties().add(new Summary(StringUtils.defaultString(event.getSummary())));
    vEvent.getProperties().add(new Uid(event.getUid()));
    vEvent.getProperties().add(new DtStamp());
    addSchedule(vEvent, event, timeZone);
    addDescription(vEvent, event);
    addStamps(vEvent, event);
    addPeople(vEvent, event);

    // CONFIRMED for every event pushed, deliberately, rather than a mapping of
    // the agenda status: eXo spells a date poll TENTATIVE, which in RFC 5545
    // means "provisionally scheduled".
    //
    // A poll no longer reaches this engine at all — CaldavPushService refuses
    // to write a copy of one (EXO-89863) — but the constant stays, and stays
    // stated rather than mapped, for two reasons. It is a claim about what a
    // copy IS: everything this engine writes is a scheduled meeting, and
    // reading the status through would make that depend on a field rather than
    // on the rule. And an entry spelled TENTATIVE is hidden outright by some
    // clients, so a status mapping that ever let one through would produce a
    // copy nobody can see — a failure with no symptom.
    //
    // The sentence that used to stand here, that a poll's own author pushed
    // their copy of it from the browser while the fan-out refused everyone
    // else, is what EXO-89863 ended: the refusal now lives in the one core
    // every writer comes through, the author's browser included.
    //
    // CANCELLED is the one exception, and it is not a status mapping either:
    // it is the whole point of writing the copy again after a meeting is
    // called off. It has to be the copy's own word rather than the copy's
    // absence, because a client shows a cancelled meeting struck through where
    // it shows a removed one not at all — and "not at all" is what a failed
    // synchronisation looks like too.
    //
    // TRANSP:OPAQUE is the RFC default and is written for explicitness — the
    // comparison treats it as equal to its own absence, so a server dropping
    // it as redundant is not an edit. TRANSPARENT is not a default and does
    // not get that tolerance, which is the point: it is a statement the copy
    // has to keep carrying while its owner means it, and one it must lose
    // again the moment they stop.
    vEvent.getProperties().add(event.isCancelled() ? Status.VEVENT_CANCELLED : Status.VEVENT_CONFIRMED);
    vEvent.getProperties().add(event.isTransparent() ? Transp.TRANSPARENT : Transp.OPAQUE);

    addRecurrence(vEvent, event, timeZone, occurrence);

    calendar.getComponents().add(vEvent);
    return calendar.toString();
  }

  /**
   * DTSTART and DTEND: dates for an all-day event, wall clock anchored on the
   * zone when the object carries its VTIMEZONE, and UTC otherwise.
   *
   * @param vEvent the component being built
   * @param event the event being written
   * @param timeZone the resolved zone, or null when the object is UTC-anchored
   */
  private void addSchedule(VEvent vEvent, IcsEvent event, TimeZone timeZone) {
    if (event.isAllDay()) {
      // The VALUE=DATE parameter is NOT added by hand: an ical4j Date already
      // makes the property carry it, and adding it too wrote it twice —
      // "VALUE=DATE;VALUE=DATE", which is malformed and which a strict server
      // may reject, with nothing to show for it but an event that did not
      // arrive.
      DtStart start = new DtStart(icsDate(event.getStart(), event.getTimeZoneId()));
      // RFC 5545 makes an all-day DTEND exclusive: a one-day event ends on the
      // day after it. Agenda holds the last day the event covers, so a day is
      // added here — its absence is why an all-day event pushed to CalDAV came
      // out a day short.
      DtEnd end = new DtEnd(icsDate(localDate(event.getEnd(), event.getTimeZoneId()).plusDays(1)));
      vEvent.getProperties().add(start);
      vEvent.getProperties().add(end);
    } else if (timeZone != null) {
      vEvent.getProperties().add(new DtStart(zonedDateTime(event.getStart(), timeZone)));
      vEvent.getProperties().add(new DtEnd(zonedDateTime(event.getEnd(), timeZone)));
    } else {
      vEvent.getProperties().add(new DtStart(utcDateTime(event.getStart())));
      vEvent.getProperties().add(new DtEnd(utcDateTime(event.getEnd())));
    }
  }

  /**
   * Everything a reader shows as text: where it is, what it is about, where it
   * lives in eXo, and the conference it can be joined through.
   *
   * @param vEvent the component being built
   * @param event the event being written
   */
  private void addDescription(VEvent vEvent, IcsEvent event) {
    if (StringUtils.isNotBlank(event.getLocation())) {
      vEvent.getProperties().add(new Location(event.getLocation()));
    }
    String conferenceUrl = StringUtils.trimToNull(event.getConferenceUrl());
    // The description arrives as the plain text RFC 5545 3.8.1.5 defines it,
    // already carrying the attribution and the conference line: since
    // EXO-89732 it is composed by agenda's EventIcsBuilder, the same code that
    // writes the description of the mailed document, so that the two channels
    // describe one meeting in one set of words. Rendering it a second time
    // here would be a second implementation of exactly the drift that change
    // removed — and appending the conference again would print the link twice.
    if (StringUtils.isNotBlank(event.getDescription())) {
      vEvent.getProperties().add(new Description(event.getDescription()));
    }
    if (StringUtils.isNotBlank(event.getEventUrl())) {
      vEvent.getProperties().add(new Url(URI.create(event.getEventUrl())));
    }
    if (conferenceUrl != null) {
      // In addition to the line the description already carries, never instead
      // of it: support for this property is patchy — Apple mostly recognises
      // known providers by sniffing the description, Thunderbird handles it
      // partially — so the description line stays the one thing every client
      // can show. A single feature, not the VIDEO,AUDIO list RFC 7986 allows:
      // a parameter value holding a comma gets quoted into one value that a
      // strict reader then ignores. One correct token beats two read as none.
      net.fortuna.ical4j.model.ParameterList conferenceParameters = new net.fortuna.ical4j.model.ParameterList();
      conferenceParameters.add(Value.URI);
      conferenceParameters.add(new net.fortuna.ical4j.model.parameter.Feature("VIDEO"));
      vEvent.getProperties().add(new Conference(conferenceParameters, conferenceUrl));
    }
  }

  /**
   * CREATED and LAST-MODIFIED, when the event carries them.
   *
   * @param vEvent the component being built
   * @param event the event being written
   */
  private void addStamps(VEvent vEvent, IcsEvent event) {
    if (event.getCreated() != null) {
      vEvent.getProperties().add(new Created(utcDateTime(event.getCreated())));
    }
    if (event.getUpdated() != null) {
      vEvent.getProperties().add(new LastModified(utcDateTime(event.getUpdated())));
    }
  }

  /**
   * The scheduling identities of the copy: who called the meeting, and who is
   * expected in it.
   *
   * <p>
   * Written truthfully rather than expediently. ORGANIZER is the eXo event's
   * organizer, never the connected CalDAV account: for a meeting the user
   * merely accepted, naming the account owner would put a subtly false event
   * in their calendar, and their own client could then offer to send
   * invitations on their behalf for a meeting somebody else called.
   *
   * <p>
   * An address is never invented, so when the organizer has none the
   * properties are omitted entirely, ATTENDEE included: RFC 5545 section
   * 3.8.4.1 defines ATTENDEE only in group-scheduled components, which the
   * ORGANIZER property is what marks.
   *
   * <p>
   * Every ATTENDEE carries SCHEDULE-AGENT=CLIENT, and so does ORGANIZER when
   * the pushing user is not the organizer (RFC 6638 section 7.1). The copy
   * mirrors scheduling that already happened in eXo; without the parameter a
   * scheduling-aware server would take the PUT as an instruction to run that
   * scheduling itself — mailing invitations to every attendee, or a reply to
   * the organizer.
   *
   * <p>
   * CLIENT rather than NONE, and the difference was measured rather than
   * reasoned. NONE reads as "no agent acts on this event at all", and a
   * client honours it by declining to record an answer anywhere: macOS
   * Calendar offered Accept and Decline on a copy written that way, edited
   * the object when the user pressed one — and left PARTSTAT untouched, so
   * the verification pass found the copy altered with no answer in it, every
   * pass. CLIENT says the client is the agent, which is what makes it write
   * the answer into the object where the pass reads it back.
   *
   * <p>
   * The price is that a conforming client may also email an iMIP reply to the
   * organizer, and nothing ingests those yet — so an organizer can receive a
   * reply eXo does not act on. That is a mailbox oddity; NONE was silent data
   * loss, which is worse.
   *
   * @param vEvent the component being built
   * @param event the event being written
   */
  private void addPeople(VEvent vEvent, IcsEvent event) {
    IcsPerson organizer = event.getOrganizer();
    if (organizer == null || StringUtils.isBlank(organizer.getEmail())) {
      return;
    }
    Organizer organizerProperty = new Organizer(mailto(organizer.getEmail()));
    addCn(organizerProperty.getParameters(), organizer.getDisplayName());
    if (!event.isOrganizerIsPusher()) {
      organizerProperty.getParameters().add(new ScheduleAgent(CLIENT_AGENT));
    }
    vEvent.getProperties().add(organizerProperty);

    for (IcsPerson attendee : guests(event, organizer)) {
      Attendee attendeeProperty = new Attendee(mailto(attendee.getEmail()));
      addCn(attendeeProperty.getParameters(), attendee.getDisplayName());
      attendeeProperty.getParameters().add(new PartStat(IcsText.partStat(attendee.getResponse())));
      attendeeProperty.getParameters().add(new ScheduleAgent(CLIENT_AGENT));
      vEvent.getProperties().add(attendeeProperty);
    }
  }

  /**
   * The people this component names as attendees: everyone with a visible
   * address, <b>except the organizer</b>.
   *
   * <p>
   * Attendees without a visible address — spaces, and users whose profile
   * hides their email — are left off for the no-invented-address reason
   * {@link #addPeople} records. The roster on the phone may therefore be
   * shorter than the one in eXo, which the {@code URL} property links to in
   * full.
   *
   * <p>
   * <b>Why the organizer is not among them, and it was measured rather than
   * reasoned (EXO-89768).</b> agenda puts the person who called a meeting on
   * its own attendee list, so eXo used to write them twice: once as
   * {@code ORGANIZER} and once as an {@code ATTENDEE} carrying their answer.
   * A server whose model holds an organizer and a list of attendees that
   * excludes them cannot store that second line, and BlueMind does not: read
   * back over CalDAV, a copy eXo had just written as
   *
   * <pre>
   * ORGANIZER;CN=Root Root:mailto:anais.francois&#64;example.org
   * ATTENDEE;CN=Root Root;PARTSTAT=ACCEPTED;SCHEDULE-AGENT=CLIENT:mailto:anais.francois&#64;example.org
   * </pre>
   *
   * came back holding the {@code ORGANIZER} alone. The answer on the dropped
   * line was then a statement eXo made and the copy did not, so the mirror
   * called the copy rewritten, repaired it, and the server dropped the line
   * again — a loop no repair could ever close, ending in the copy being
   * abandoned and so no longer watched at all.
   *
   * <p>
   * <b>Only the duplicate goes, and the ORGANIZER stays even when nothing is
   * left beside it.</b> That second question is settled by the golden corpus,
   * not by taste: its write cases are events with an organizer and no
   * attendees at all, captured from the browser connector and stored by a real
   * server, so an eXo render that dropped the {@code ORGANIZER} there would
   * contradict what the connector is pinned to produce. A server that declines
   * to keep an organizer with nobody to organize is a different behaviour from
   * this one and is not this method's business.
   *
   * <p>
   * <b>What this does not give up.</b> The person is still named, as the
   * organizer, which is what they are; no client offers to answer a meeting
   * you called yourself, so there is no answer of theirs a copy could carry
   * that anybody could change. And it is the duplicate that goes, not the
   * roster: on the same live account, fifteen copies whose owner is an
   * ordinary invitee carry the {@code PARTSTAT=ACCEPTED} eXo wrote, kept by
   * the same server — so an answer <i>does</i> reach a copy, and this is only
   * the one line no server with that model was ever going to keep.
   *
   * @param event the event being written
   * @param organizer the person who called the meeting, address non-blank
   * @return the attendees to write, in order, possibly empty
   */
  private List<IcsPerson> guests(IcsEvent event, IcsPerson organizer) {
    List<IcsPerson> guests = new ArrayList<>();
    if (event.getAttendees() == null) {
      return guests;
    }
    String organizerAddress = bareAddress(organizer.getEmail());
    for (IcsPerson attendee : event.getAttendees()) {
      if (attendee == null || StringUtils.isBlank(attendee.getEmail())
          || organizerAddress.equals(bareAddress(attendee.getEmail()))) {
        continue;
      }
      guests.add(attendee);
    }
    return guests;
  }

  /**
   * A calendar address reduced to what two spellings of the same person
   * compare as: no {@code mailto:} scheme, no casing.
   *
   * <p>
   * The local part of a mailbox is technically case-sensitive; no calendar
   * server this connector writes to treats it as such, and the comparison
   * that matters here is the server's own — it is the one deciding whether an
   * attendee line names its organizer.
   *
   * @param email an address, possibly carrying a scheme, never null here
   * @return the comparable form
   */
  private String bareAddress(String email) {
    return StringUtils.removeStart(StringUtils.trimToEmpty(email).toLowerCase(Locale.ROOT), "mailto:");
  }

  /**
   * What ties the component to a series: the occurrence it amends, or the rule
   * the master repeats by, plus the instances removed from it.
   *
   * <p>
   * An override has to denote its instance in the same form the master's
   * DTSTART uses — RFC 5545 requires the same value type — so the three cases
   * mirror {@link #addSchedule} exactly. Keying off whether the occurrence
   * identifier happens to contain a time instead wrote a date-time reference
   * into a date-valued series, which matches no instance at all.
   *
   * @param vEvent the component being built
   * @param event the event being written
   * @param timeZone the resolved zone, or null when the object is UTC-anchored
   * @param occurrence whether this component amends one instance
   */
  private void addRecurrence(VEvent vEvent, IcsEvent event, TimeZone timeZone, boolean occurrence) {
    if (occurrence) {
      Instant instant = IcsText.parseInstant(event.getOccurrenceId());
      if (event.isAllDay()) {
        vEvent.getProperties().add(new RecurrenceId(icsDate(instant, event.getTimeZoneId())));
      } else if (timeZone != null) {
        vEvent.getProperties().add(new RecurrenceId(zonedDateTime(instant, timeZone)));
      } else {
        vEvent.getProperties().add(new RecurrenceId(utcDateTime(instant)));
      }
      return;
    }
    if (StringUtils.isNotBlank(event.getRecurrenceRule())) {
      // COUNT=0 is agenda's way of saying "no limit", and RFC 5545 gives it
      // the opposite meaning: a series that never occurs.
      String rule = event.getRecurrenceRule().trim().replaceFirst("COUNT=0;?", "");
      if (StringUtils.isNotBlank(rule)) {
        try {
          vEvent.getProperties().add(new RRule(new Recur(rule)));
        } catch (IllegalArgumentException | java.text.ParseException e) {
          // Better a single event than a series repeating by a rule nobody
          // wrote: an unparseable RRULE is dropped, not guessed at.
          LOG.warn("Ignoring an unparseable recurrence rule on event {}: {}", event.getUid(), rule, e);
        }
      }
    }
    addExceptions(vEvent, event, timeZone);
  }

  /**
   * The instances deleted from a series, written by the master only, in the
   * same form its DTSTART uses: an exclusion only removes an occurrence it
   * matches.
   *
   * @param vEvent the component being built
   * @param event the event being written
   * @param timeZone the resolved zone, or null when the object is UTC-anchored
   */
  private void addExceptions(VEvent vEvent, IcsEvent event, TimeZone timeZone) {
    if (event.getExceptionDates() == null || event.getExceptionDates().isEmpty()) {
      return;
    }
    for (String exception : event.getExceptionDates()) {
      if (StringUtils.isBlank(exception)) {
        continue;
      }
      if (!exception.contains("T")) {
        DateList dates = new DateList(Value.DATE);
        dates.add(icsDate(LocalDate.parse(exception.substring(0, DATE_LENGTH))));
        vEvent.getProperties().add(new ExDate(dates));
      } else {
        Instant instant = IcsText.parseInstant(exception);
        DateList dates = new DateList(Value.DATE_TIME);
        net.fortuna.ical4j.model.ParameterList parameters = new net.fortuna.ical4j.model.ParameterList();
        if (timeZone != null) {
          dates.setTimeZone(timeZone);
          dates.add(zonedDateTime(instant, timeZone));
          parameters.add(new net.fortuna.ical4j.model.parameter.TzId(timeZone.getID()));
        } else {
          dates.setUtc(true);
          dates.add(utcDateTime(instant));
        }
        vEvent.getProperties().add(new ExDate(parameters, dates));
      }
    }
  }

  /**
   * One VALARM per reminder, so that a mirrored meeting alerts on the device
   * the way it does in eXo. Without them the copy is silent, which is the
   * difference between an entry in a calendar and a reminder to attend.
   *
   * @param event the event being written
   * @return the alarm components, empty when the event carries no usable reminder
   */
  private net.fortuna.ical4j.model.ComponentList<VAlarm> alarms(IcsEvent event) {
    net.fortuna.ical4j.model.ComponentList<VAlarm> alarms = new net.fortuna.ical4j.model.ComponentList<>();
    if (event.getReminders() == null) {
      return alarms;
    }
    for (IcsReminder reminder : event.getReminders()) {
      Long minutes = IcsText.reminderMinutes(reminder);
      if (minutes == null) {
        continue;
      }
      VAlarm alarm = new VAlarm();
      alarm.getProperties().add(Action.DISPLAY);
      alarm.getProperties().add(new Description(StringUtils.defaultString(event.getSummary())));
      alarm.getProperties().add(new Trigger(Duration.ofMinutes(-minutes)));
      alarms.add(alarm);
    }
    return alarms;
  }

  /**
   * The zone as ical4j's registry describes it, or null when it names nothing
   * the registry knows.
   *
   * <p>
   * Declining rather than approximating: a wrong VTIMEZONE is harder to notice
   * than none, and no VTIMEZONE returns the event to the UTC form, which is
   * merely adrift across a transition rather than wrong about when it happens.
   *
   * @param timeZoneId IANA zone identifier, possibly absent or unknown
   * @return the zone, or null
   */
  private TimeZone resolveTimeZone(String timeZoneId) {
    if (StringUtils.isBlank(timeZoneId)) {
      return null;
    }
    TimeZone timeZone = REGISTRY.getTimeZone(timeZoneId);
    if (timeZone == null) {
      LOG.debug("No time zone definition for {}; the event is pushed in UTC", timeZoneId);
    }
    return timeZone;
  }

  /**
   * The calendar date of an instant, as seen in the event's own zone.
   *
   * <p>
   * The zone is explicit because neither shortcut works: converting to UTC and
   * truncating moves the day for everyone east of Greenwich — midnight in
   * Tunis is 23:00 UTC the day before — and reading the leading date off a
   * string has the same fault whenever the value is an instant.
   *
   * @param instant the value to read
   * @param timeZoneId the event's zone; the system zone when absent
   * @return the calendar date in that zone
   */
  private LocalDate localDate(Instant instant, String timeZoneId) {
    ZoneId zone = StringUtils.isBlank(timeZoneId) ? ZoneId.systemDefault() : ZoneId.of(timeZoneId);
    return ZonedDateTime.ofInstant(instant, zone).toLocalDate();
  }

  /**
   * The same calendar date as an iCalendar date value, which is what a
   * VALUE=DATE property carries.
   *
   * @param instant the value to read
   * @param timeZoneId the event's zone; the system zone when absent
   * @return the date, ready to be written
   */
  private net.fortuna.ical4j.model.Date icsDate(Instant instant, String timeZoneId) {
    return icsDate(localDate(instant, timeZoneId));
  }

  /**
   * A calendar date as an iCalendar date value.
   *
   * @param date the calendar date
   * @return the date, ready to be written
   */
  private net.fortuna.ical4j.model.Date icsDate(LocalDate date) {
    try {
      return new net.fortuna.ical4j.model.Date(date.format(DATE_FORMAT));
    } catch (java.text.ParseException e) {
      throw new IllegalStateException("A calendar date failed to format as an iCalendar date: " + date, e);
    }
  }

  /**
   * An instant as a zone-anchored date-time, so the written value is the wall
   * clock a TZID-anchored series produces.
   *
   * @param instant the value to convert
   * @param timeZone the zone to anchor on
   * @return the date-time, carrying its zone
   */
  private DateTime zonedDateTime(Instant instant, TimeZone timeZone) {
    DateTime dateTime = new DateTime(java.util.Date.from(instant));
    dateTime.setTimeZone(timeZone);
    return dateTime;
  }

  /**
   * An instant as a UTC date-time.
   *
   * @param instant the value to convert
   * @return the date-time, marked UTC
   */
  private DateTime utcDateTime(Instant instant) {
    DateTime dateTime = new DateTime(java.util.Date.from(instant));
    dateTime.setUtc(true);
    return dateTime;
  }

  /**
   * A mail address as the CAL-ADDRESS URI the RFC requires.
   *
   * <p>
   * Delegated to agenda's {@link EventIcsBuilder} so that both channels turn an
   * address into a calendar user address the same way (EXO-89732). It is not
   * the same as prefixing the scheme: an address that already carries it is
   * left alone, which matters here because the address written for the
   * account's own owner is whatever their CalDAV account answers to —
   * configuration rather than a profile field — and a value pasted in with its
   * scheme would otherwise become <code>mailto:mailto:...</code>, matching no
   * account and taking the RSVP controls with it.
   *
   * @param email the address
   * @return the mailto URI
   */
  private URI mailto(String email) {
    return EventIcsBuilder.calendarUserAddress(email);
  }

  /**
   * Adds a CN naming a calendar user, when there is a name to add. ical4j
   * quotes the value itself; what is removed here are the characters that
   * cannot appear inside a quoted string at all, and the line breaks that
   * would end the content line.
   *
   * @param parameters the property's parameter list
   * @param name display name, possibly absent
   */
  private void addCn(net.fortuna.ical4j.model.ParameterList parameters, String name) {
    String cleaned = name == null ? null : name.replaceAll("[\"\\r\\n]", "").trim();
    if (StringUtils.isNotBlank(cleaned)) {
      parameters.add(new Cn(cleaned));
    }
  }
}
