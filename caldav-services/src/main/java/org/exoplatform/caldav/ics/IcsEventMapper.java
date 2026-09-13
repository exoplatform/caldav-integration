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
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import net.fortuna.ical4j.model.Recur;

import org.exoplatform.agenda.constant.EventRecurrenceFrequency;
import org.exoplatform.agenda.constant.EventRecurrenceType;
import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.EventRecurrence;
import org.exoplatform.agenda.util.InvitationText;
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

  private static final Log     LOG           = ExoLogger.getLogger(IcsEventMapper.class);

  /**
   * The whole of a {@code URL} property that is an eXo event address — the one
   * thing on a calendar object that says eXo composed it.
   *
   * <p>
   * The gate of {@link #descriptionOf(IcsEvent)}, and it reads a single URI
   * value rather than free text: {@code URL} carries exactly one address, so
   * the value is matched end to end and a value with anything else in it is
   * not an eXo event address. Both carriers of this address — the property and
   * the labelled line inside the description — are written from one value
   * derived in one place ({@code AgendaEventIcsMapper.eventUrl}, EXO-89751),
   * so a description that carries the line carries this property too, and a
   * render with no address to write writes neither: verified against the
   * installed builder, {@code EventIcsBuilder.description} with a null link
   * emits no event-link line at all, which is a block
   * {@link InvitationText#stripFrom(String)} does not recognise in the first
   * place. That is what makes the gate free of false negatives on eXo's own
   * copies rather than merely cheap.
   *
   * <p>
   * <b>Deliberately not {@code CaldavInboundService.EXO_EVENT_LINK}</b>, which
   * scans free text and reads the authority out of the address, and therefore
   * has to refuse a link served under a path lest it name the wrong
   * deployment. This one reads nothing off the address and must accept that
   * shape: a deployment whose base URL carries a path composes copies like any
   * other, and refusing them here would leave its users with the block in
   * their stored descriptions for ever. It accepts a scheme-less address for
   * the same reason — costing nothing, since nothing is read out of it.
   *
   * <p>
   * <b>A different deployment's address passes on purpose.</b> A copy naming
   * an eXo other than this one is still a copy eXo composed (EXO-89824), and
   * its block is exactly as unwanted in a stored description as a local one.
   */
  private static final Pattern EXO_EVENT_URL = Pattern.compile("(?:https?://)?[^\\s<>\"']+/portal/[^/\\s<>?]+/agenda\\?eventId=\\d+",
                                                               Pattern.CASE_INSENSITIVE);

  /**
   * The agenda event standing for one parsed object.
   *
   * <p>
   * Identity is deliberately absent: no id, no calendar, no creator. Those
   * belong to the caller placing the event, not to the object being read, and
   * an event carrying an id it invented would overwrite whatever holds it.
   *
   * <p>
   * The description is what the organiser typed, not what the object says —
   * see {@link #descriptionOf(IcsEvent)} for what is taken off it, on which
   * objects, and what that can cost.
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
    event.setDescription(descriptionOf(source));
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
   * The description agenda stores: the organiser's own text, with an
   * invitation block eXo composed taken off the front of it.
   *
   * <p>
   * A copy eXo wrote carries agenda's invitation text before the organiser's
   * words — the pusher's name, the space, the event's own link and, since
   * EXO-89753, that user's tokenised answer links. Storing that text as the
   * imported event's description is what made the block stack one per edit on
   * the object (EXO-90227), and what showed another user's name, event id and
   * answer links in the event drawer, in the body of the notification mail and
   * in search for ever. Agenda's builder now writes one block whatever the
   * description already carries, which stops the growth on every channel it
   * renders; this is the other half, for everything that reads the
   * <em>stored</em> description directly. The recognition is agenda's
   * ({@link InvitationText}), spelled once, beside the builder whose layout it
   * reads.
   *
   * <p>
   * <b>Gated on the object carrying an eXo event address as its {@code URL}
   * property</b> ({@link #EXO_EVENT_URL}), and the gate is the whole reason
   * this is safe to write at all. {@code InvitationText} recognises a block by
   * the <em>shape</em> of the text — a line, then a line that is a short label
   * and an eXo event link and nothing else, then one line that itself has the
   * shape of a label, which is where the block ends and the organiser's text
   * begins. That shape is one a person can type, and the joint that decides it
   * is whether the lines around the link have a label's shape. So
   * {@code Weekly sync.} / {@code Agenda: <an eXo event link>} /
   * {@code Notes:} / {@code bring the deck.} reduces to
   * {@code bring the deck.}, while {@code Hi all,} / {@code see <the same
   * link>} / {@code Thanks,} / {@code Bob} comes back untouched.
   *
   * <p>
   * <b>Which shapes reduce is agenda's to decide, and it has already moved
   * twice</b> — EXO-90227 bounded the label and required the closing line to
   * look like one, EXO-90228 required the same of the line carrying the link.
   * So no example here is a contract, and the pins in
   * {@code IcsEventMapperTest} deliberately do not rest on one: they use the
   * block {@code EventIcsBuilder} itself composes, which is the one input the
   * recogniser must go on recognising however narrow it becomes. On the render
   * side a misreading costs a paragraph of one document and the next push
   * composes the text again; here it would be a write.
   *
   * <p>
   * <b>Why the {@code URL} property is the signal.</b> Not because no client
   * could ever write one — that is not known, and the very client
   * {@code r06} was captured from (macOS Calendar 26.5.1) re-saved an object
   * without dropping it — but because it is not part of the text a person types
   * into a description, and because the servers this add-on is validated
   * against keep what eXo wrote there. Both fixtures carrying an invitation
   * block <em>and</em> the address that says who composed it are <b>Stalwart</b>
   * bodies — {@code golden/read/objects/r06-macos-answer-internal-domain.ics},
   * where the address survived that macOS re-save, and
   * {@code r07-exo-reminder-repaired-onto-stalwart.ics} — and on BlueMind the
   * property is kept so faithfully that it comes back <em>twice</em>, which
   * {@code IcsEquivalence.isServerSideRepetition} exists to tolerate.
   *
   * <p>
   * <b>The residual, stated rather than implied:</b> a client or a server that
   * dropped {@code URL} while keeping the description would close this gate on a
   * copy eXo really did compose, and its block would stay in the stored
   * description. It <em>stays</em> and no longer <em>grows</em> — agenda's
   * builder takes any block off before composing its own — so the cost of that
   * false negative is one stale paragraph, not one per edit.
   *
   * <p>
   * <b>What this can still destroy, and nothing restores it.</b> An object
   * that carries an eXo event address as its {@code URL} and a description
   * this recogniser misreads loses every line down to what it takes for the
   * organiser's text, and the loss is final on both sides: agenda keeps no
   * history of an event's description, this add-on keeps no copy of the
   * object's body — the one digest column was dropped from the schema on
   * purpose — and the next eXo-side edit of the event replaces the master
   * {@code VEVENT} wholesale ({@code IcsMerger}), so the server's copy of
   * those words goes with it.
   *
   * <p>
   * <b>Two routes reach it, and neither needs anybody to set a {@code URL}.</b>
   * This class is not closed, and the Javadoc above must not be read as though
   * it were:
   * <ol>
   * <li><b>eXo's own push, then import.</b> {@code EventIcsBuilder.description}
   * strips a leading block <em>ungated</em> while composing, so a description a
   * person wrote in a shape the recogniser misreads is already shortened in the
   * copy eXo pushes. That copy legitimately carries eXo's {@code URL}; the
   * server stamps a new {@code LAST-MODIFIED}; the import opens this gate and
   * stores the shortened text over the event's own description.</li>
   * <li><b>A person editing a copy eXo composed.</b> Rewriting the description
   * in a calendar client keeps the {@code URL} the copy carries, so their text
   * meets the recogniser on the way back in.</li>
   * </ol>
   * The cause of both is the builder's ungated strip, and narrowing it is
   * agenda's side of this defect, tracked as <b>EXO-90228</b> — not fixed here,
   * and this import amplifies rather than creates it. What the gate does buy is
   * that neither route is reachable for an object that has nothing to do with
   * eXo, which is the narrowest statement this class can make on its own.
   *
   * <p>
   * <b>The read-through preview is deliberately untouched.</b> Browsing the
   * account through {@code CaldavReadService} shows every object as the server
   * holds it, block included: it is a preview of the object, not of an eXo
   * event, and whether it should hide the block is a product decision nobody
   * has taken. Only what agenda <em>stores</em> is decided here.
   *
   * <p>
   * <b>On a gated object the organiser's own margins go too.</b> The
   * {@code trimToNull} that turns a block-only description into no description
   * at all also takes the blank lines and spaces off either end of what the
   * recogniser left — and off a gated description the recogniser did
   * <em>not</em> recognise, which is returned whole but trimmed. Harmless
   * (leading and trailing whitespace carries nothing a reader sees, and the
   * recogniser already strips the block's own margins) but not nothing, so it
   * is written down rather than left for the next reader to discover: an object
   * with no eXo {@code URL} keeps its margins, one with an eXo {@code URL} does
   * not.
   *
   * @param source the parsed object
   * @return the description to store, null when the object carries none and
   *         null when a block was all it carried; on a gated object, trimmed of
   *         leading and trailing whitespace
   */
  private String descriptionOf(IcsEvent source) {
    String description = source.getDescription();
    String url = StringUtils.trimToNull(source.getEventUrl());
    if (url == null || !EXO_EVENT_URL.matcher(url).matches()) {
      // Not a copy eXo composed, as far as anything on the object says. Stored
      // byte for byte, whatever shape its text happens to have.
      return description;
    }
    // Blank rather than empty when the block was the whole description: agenda
    // already holds null for an object carrying no DESCRIPTION at all, and an
    // event whose description is now nothing is the same thing. Storing "" put
    // a value every reader has to treat as absent without being told to.
    return StringUtils.trimToNull(InvitationText.stripFrom(description));
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
    // A bare date first, and by its shape rather than by whether the instant
    // parser refuses it: an all-day series excludes a DAY, and ical4j's
    // DateTime accepts "20261012" happily — as midnight in whatever zone the
    // JVM runs in. On a server east of UTC that shifted the exclusion onto the
    // day before, so the occurrence the user deleted stayed and its neighbour
    // vanished. It also made the behaviour depend on which test had run first,
    // which is how it was found.
    if (trimmed.length() == 8 && trimmed.chars().allMatch(Character::isDigit)) {
      try {
        return java.time.LocalDate.parse(trimmed, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                                  .atStartOfDay(zone);
      } catch (Exception e) {
        LOG.debug("An excluded date that could not be read is ignored: {}", value);
        return null;
      }
    }
    try {
      return new net.fortuna.ical4j.model.DateTime(trimmed).toInstant().atZone(zone);
    } catch (Exception e) {
      LOG.debug("An excluded date that could not be read is ignored: {}", value);
      return null;
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
