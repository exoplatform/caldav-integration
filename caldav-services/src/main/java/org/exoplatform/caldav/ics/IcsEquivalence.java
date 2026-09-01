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

import org.exoplatform.caldav.model.IcsDivergence;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

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
 * <b>Names are the server's to spell.</b> {@code CN} and {@code DIR} are
 * dropped from every compared statement: a server substituting its directory's
 * own name and pointer for a person says nothing about the meeting, and the
 * address — which is who they actually are — is compared regardless.
 *
 * <p>
 * <b>And so is the server's own index of them.</b> {@code EMAIL} restates the
 * address the property's value already carries, for a server that wants one
 * without parsing a URI; macOS Calendar writes it on the organizer and attendee
 * lines and {@link IcsWriter} never writes it at all. Compared, it made one
 * organizer two statements — the copy's and eXo's, each missing from the other
 * side — and every copy a macOS client had touched was repaired for it
 * (EXO-89826).
 *
 * <p>
 * <b>And an alarm the client stamps its own identifier on.</b> An alarm is
 * compared as one folded statement of the event that carries it, so a single
 * property inside it that one side does not state makes the whole reminder a
 * different reminder. macOS Calendar keeps the reminder eXo pushes and writes
 * {@code X-WR-ALARMUID} on it — Apple's spelling of the RFC 9074 {@code UID}
 * that was already ignored — and the copy was read as carrying one alarm eXo
 * had never written while missing the one eXo had. See
 * {@link #IGNORED_ALARM_PROPERTIES} (EXO-89828).
 *
 * <p>
 * <b>And an attendee the server did not keep.</b> BlueMind discards attendees
 * whose addresses are not in its directory, so a copy carries fewer people than
 * eXo sent and no repair can change that. Tolerated for the same reason and in
 * the opposite direction — which is why the two rules are stated separately and
 * each names its own side. The cost is recorded where it belongs: the copy
 * understates the guest list, and an attendee the server dropped cannot answer
 * from it.
 *
 * <p>
 * <b>One guest is one statement, however each side spells them.</b> A server
 * that keeps an attendee but not their answer states that person on a line eXo
 * does not carry, while eXo's own line for them is carried on no copy — two
 * divergences, one each way, about one person. Read as two they are read
 * wrongly, and the tolerance above is what does it: it absorbs eXo's half and
 * leaves the copy's half to be reported as <i>an attendee the server added</i>,
 * which is a different event in the world and the wrong thing to tell an
 * administrator (EXO-89829). So a person both copies name is folded into one
 * statement about them <b>before</b> any tolerance rule sees either half —
 * exactly one decision is taken for the pair, and the rules below only ever
 * meet somebody one side does not name at all. See
 * {@link #pairAttendeeStatements}.
 *
 * <p>
 * <b>Free text is compared for its words, not its layout.</b> A TEXT value is
 * the one place where the document's line discipline and the content are
 * spelled in the same characters, and a server re-serialising a multi-line
 * description has to fold it. BlueMind returns eXo's blank line between two
 * paragraphs as a newline and a continuation space, which left every copy on a
 * live account permanently altered. Runs of whitespace are collapsed in
 * {@link #TEXT_PROPERTIES} before comparison; every other character still
 * counts, so the tokenised answer links the description carries since
 * EXO-89753 stay guarded.
 *
 * <p>
 * <b>And a link a server wrote out twice.</b> BlueMind auto-linkifies: it
 * appends every URI in a description a second time, in angle brackets,
 * immediately after the one already there — the conference link, the event link
 * and all three answer links at once. A bracketed URI is dropped only when it
 * <b>exactly repeats the URI immediately before it</b>; anything else about the
 * text is compared as before. It is a backreference, not a rule about angle
 * brackets and not a relaxation of URI equality, so a link that differs by one
 * character — a rewritten answer token above all — is still an edit. See
 * {@link #LINKIFIED_URI_REPEAT}, and {@link #LINKIFIED_PROPERTIES} for why only
 * the description is covered.
 *
 * <p>
 * <b>And a property a server stored twice.</b> The same server keeps two
 * identical {@code URL} lines where eXo wrote one. That is not a value
 * difference but a cardinality one, so it gets its own rule: a statement eXo
 * <i>also</i> makes, surplus on the server's side, on a property
 * {@link IcsWriter} emits, is the server repeating itself. Every distinct
 * statement is still compared exactly — a copy holding eXo's link twice and a
 * link of its own still reports the second one. See
 * {@link #isServerSideRepetition}.
 *
 * <p>
 * <b>Two excusals, pointing opposite ways, declared per server.</b> One excuses
 * an <i>unrecognised</i> property a server adds; the other a property eXo emits
 * that a server declines to store — BlueMind keeps no {@code CONFERENCE}. Both
 * are empty by default and neither can make two differing values compare equal,
 * bar the one catalogue entry that says so. See {@link ServerExcusals}; the
 * fields below are only the deployment-wide fallback.
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

  private static final Log                 LOG                      = ExoLogger.getExoLogger(IcsEquivalence.class);

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
   * moves something else that <i>is</i> compared.
   *
   * <p>
   * <b>{@code URL} used to be here and no longer is</b> (EXO-89751). The
   * exemption was correct for the world it was written in: the link back into
   * eXo arrived on the push request, so only a browser push carried one and
   * every sweep rendered the object without it. Comparing a property one side
   * never renders reports every copy altered exactly once and then strips the
   * link it had just complained about — the exemption was the only thing
   * standing between that and a repair loop.
   *
   * <p>
   * That world is gone. The link is now derived from the event by agenda's
   * shared builder, so every render of the same event produces the same
   * {@code URL} — a browser push, a sweep and a repair alike. Once eXo renders
   * it every time, leaving it exempt would ship a link nobody is watching: a
   * client could rewrite it, or drop it, and the mirror would go on calling
   * that copy untouched. It is compared like any other property the writer
   * emits.
   */
  /**
   * The invitation text, named once because four separate rules reach for it:
   * it is a recognised event property, it is carried on an alarm, it is
   * compared as text, and it is the only property whose links a server is
   * known to rewrite. Naming it keeps those four in step - a rename that
   * missed one would silently change what is compared.
   */
  private static final String              DESCRIPTION              = "DESCRIPTION";

  private static final Set<String>         IGNORED_EVENT_PROPERTIES = Set.of("DTSTAMP", "CREATED", "LAST-MODIFIED");

  /**
   * The VEVENT properties {@link IcsWriter} emits — the closed recognised set.
   * Anything else inside the compared component is a difference.
   */
  private static final Set<String>         EVENT_PROPERTIES         = Set.of("UID",
                                                                             "SUMMARY",
                                                                             "DTSTART",
                                                                             "DTEND",
                                                                             "LOCATION",
                                                                             DESCRIPTION,
                                                                             "URL",
                                                                             "CONFERENCE",
                                                                             "ORGANIZER",
                                                                             "ATTENDEE",
                                                                             "STATUS",
                                                                             "TRANSP",
                                                                             "RECURRENCE-ID",
                                                                             "RRULE",
                                                                             "EXDATE");

  /** The VALARM properties {@link IcsWriter} emits. */
  private static final Set<String>         ALARM_PROPERTIES         = Set.of("ACTION", DESCRIPTION, "TRIGGER");

  /**
   * Properties of a VALARM that are read but never compared.
   *
   * <p>
   * {@code UID} names the alarm (RFC 9074) without saying anything about when or
   * how it fires. {@code ACKNOWLEDGED} records that somebody dismissed the
   * reminder — per-viewer state, not a property of the meeting; treating it as a
   * rewrite would have eXo resurrect a reminder the user has just dismissed.
   *
   * <p>
   * <b>{@code X-WR-ALARMUID} is {@code UID} under Apple's own name</b>, and the
   * reason EXO-89828 was opened. Apple's calendar stack has stamped an
   * identifier on every alarm it writes since long before RFC 9074 gave the
   * property a standard spelling, and macOS Calendar 26.5.1 still writes that
   * one: it keeps the reminder eXo pushed, adds its identifier to it, and
   * leaves {@code ACTION}, {@code DESCRIPTION} and {@code TRIGGER} exactly as
   * they were. Because an alarm is compared as a single folded statement of the
   * event that carries it, one unequal property inside it makes the whole alarm
   * a different alarm: the rig read one reminder as two, one missing from the
   * copy and one missing from eXo's render, judged the copy altered and
   * rewrote it — and the client stamped its identifier back on the next time it
   * touched the object (2026-08-31 14:14:35, both copies a macOS client had
   * answered).
   *
   * <p>
   * <b>The admission test is EXO-89826's, not "did we fail to parse it".</b>
   * The question is whether the client writes the property for its own
   * bookkeeping or whether a user authored it. An alarm's identifier is the
   * former in its purest form — it names the alarm and says nothing about when
   * or how it fires — which is precisely why {@code UID} was already here.
   * {@code X-} is not what admits it, and being unparsed is not what admits it:
   * the same sweep reported {@code PRIORITY} and {@code SEQUENCE} through the
   * same unrecognised bucket, and both are refused. See
   * {@link #DEFAULT_STATEMENTS} for what they are and why.
   */
  private static final Set<String>         IGNORED_ALARM_PROPERTIES = Set.of("UID", "ACKNOWLEDGED", "X-WR-ALARMUID");

  /**
   * Statements equal to their own absence, per the RFC 5545 defaults. eXo writes
   * {@code TRANSP:OPAQUE} explicitly and a server is free to drop it as
   * redundant; it writes none of the other three, and a server is free to add
   * them at their default. A non-default value of any of them is not here, so it
   * still registers — {@code SEQUENCE:1} means a client edited the object.
   *
   * <p>
   * <b>And that last sentence was measured, not assumed (EXO-89828).</b> The
   * sweep of 2026-08-31 12:45:01 reported {@code UNRECOGNISED:PRIORITY=5} and
   * {@code UNRECOGNISED:SEQUENCE=1} on one BlueMind copy — in the same
   * comparison that reported {@code SUMMARY=test12} against
   * {@code SUMMARY=test121}, a moved {@code DTEND} and an attendee the copy had
   * gained. Somebody had edited that meeting in a client, and those two
   * statements are the fingerprint of the edit: {@code PRIORITY} is a fact
   * about the meeting a person set, and {@code SEQUENCE} is the organizer's own
   * count of how many times the meeting has changed. Neither is a client
   * indexing itself, so neither is admitted anywhere — not to
   * {@link #IGNORED_EVENT_PROPERTIES}, not to
   * {@link #IGNORED_ALARM_PROPERTIES}, and not by any rule about the
   * unrecognised bucket they happen to arrive in. Their <i>defaults</i> are
   * excused here and that is the whole of the tolerance they get: a server
   * spelling out {@code PRIORITY:0} or {@code SEQUENCE:0} states nothing, and
   * any other value states an edit that must be seen.
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
   * and nothing whatever about the meeting.
   *
   * <p>
   * {@code CN} is the same thing in words. This was deliberately kept compared
   * at first, on the grounds that a display name <i>arguably</i> carries
   * something a person authored where a directory pointer carries nothing; the
   * evidence settled it. BlueMind substitutes its directory's own name for
   * everybody — {@code CN=FRANCOIS} where eXo writes {@code CN=Root Root} for
   * the same address — and the copies were reaching {@code maxRepairs} over it.
   * A server substituting its own name for a person is the same class of
   * normalisation as substituting its own pointer to them. What is given up is
   * only eXo's opinion of how to spell somebody, against the server's.
   *
   * <p>
   * {@code EMAIL} (RFC 6047 section 2, as Apple and Microsoft clients write it)
   * is the third of the same kind, and the one EXO-89826 was opened for. It
   * restates, as a parameter, the very address the property's own value already
   * carries, so that a server which indexes calendar users by mail address does
   * not have to parse the CAL-ADDRESS URI to find one. macOS Calendar writes it
   * on the organizer and attendee lines whenever its user answers an invitation
   * — {@code ORGANIZER;EMAIL=anais.francois@…:mailto:anais.francois@…} — and
   * {@link IcsWriter} never emits it, on any property, so a compared statement
   * can only ever carry it because the server put it there. Left compared, the
   * one organizer was counted twice: the copy's spelling missing from eXo's
   * render and eXo's spelling missing from the copy, judged altered, repaired,
   * and diverging again the moment the client touched the object.
   *
   * <p>
   * None of the three can hide who is on the event: the <b>address in the
   * property's value</b> is the identity and it is compared regardless, on
   * ORGANIZER and ATTENDEE alike, as is every PARTSTAT. That is what bounds the
   * {@code EMAIL} relaxation in particular — a server that replaced the value
   * with an opaque handle of its own and moved the address into {@code EMAIL}
   * would be rewriting the identity, and the value it left behind
   * ({@code urn:uuid:…} against {@code mailto:…}) still registers as the
   * difference it is.
   *
   * <p>
   * <b>What must never join this set.</b> {@code PARTSTAT} is a parameter too,
   * and it is the one parameter that states a fact about the meeting rather
   * than about the directory: it is a person's answer. EXO-89807 and EXO-89814
   * both turn on a PARTSTAT difference being seen — an answer given in a client
   * is read off the copy precisely because the comparison notices it. So the
   * test for admission here is not "is it a parameter" but "does the server
   * write it for its own bookkeeping": a name, a pointer, a scheduling
   * instruction, a restated address. An answer is none of those.
   */
  private static final Set<String>         IGNORED_PARAMETERS       = Set.of("TZID",
                                                                             "VALUE",
                                                                             "CN",
                                                                             "DIR",
                                                                             "EMAIL",
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

  /**
   * Properties carrying a free-text value, whose whitespace is normalised before
   * it is compared.
   *
   * <p>
   * A TEXT value is the one place in a calendar object where the document's own
   * line discipline and the content are spelled in the same characters. A server
   * re-serialising a multi-line description has to fold it, and folding is
   * whitespace: BlueMind returns eXo's {@code Chemistry.\n\nEvent link: …} as
   * {@code Chemistry.\n Event link: …} — the blank line between the two
   * paragraphs comes back as a newline and a continuation space. Nothing about
   * the meeting changed; one whitespace character became another.
   *
   * <p>
   * These three are exactly the TEXT properties {@link IcsWriter} emits —
   * {@code SUMMARY}, {@code DESCRIPTION} and {@code LOCATION}, the last also
   * standing for the alarm description, which is rendered from the summary.
   * {@code URL} and {@code CONFERENCE} are deliberately absent: they carry URIs,
   * where whitespace is not layout but corruption, and folding them is already
   * undone by the parser.
   *
   * <p>
   * <b>Why this cannot hide an edit.</b> Only runs of whitespace are collapsed;
   * every other character is compared exactly as before. Since EXO-89753 the
   * description carries the tokenised answer links, and a token is not
   * whitespace — a client rewriting one, dropping the paragraph that holds it,
   * or changing a single letter of the text still registers. What is given up is
   * the ability to notice that somebody re-indented a description, which is not
   * a fact about a meeting.
   */
  private static final Set<String>         TEXT_PROPERTIES          = Set.of("SUMMARY", DESCRIPTION, "LOCATION");

  /** A run of whitespace of any kind, including the line breaks a server folds on. */
  private static final Pattern             WHITESPACE_RUN           = Pattern.compile("\\s+");

  /**
   * The TEXT properties whose value a server is known to auto-linkify, and the
   * only ones {@link #LINKIFIED_URI_REPEAT} is applied to.
   *
   * <p>
   * <b>Only {@code DESCRIPTION}, deliberately.</b> BlueMind appends every URI it
   * finds in a description a second time, in angle brackets, immediately after
   * the one that is already there — captured on the rig on 2026-08-28, on the
   * conference link, the event link and all three tokenised answer links at
   * once:
   *
   * <pre>
   * eXo writes  : Event link: http://host/portal/dw/agenda?eventId=981
   * BlueMind has: Event link: http://host/portal/dw/agenda?eventId=981 &lt;http://host/portal/dw/agenda?eventId=981&gt;
   * </pre>
   *
   * eXo judged the copy rewritten, repaired it, and the server linkified the
   * repair — five copies, every five-minute sweep, for ever.
   *
   * <p>
   * {@code SUMMARY} and {@code LOCATION} are the other two TEXT properties
   * {@link IcsWriter} emits and are <b>not</b> here. Nothing has been observed
   * linkifying either, and the exemption is not free: every property it covers
   * is a property where a bracketed repetition of a link stops being reported.
   * The two also differ in kind from the description — eXo <i>composes</i> the
   * description and puts the links in it itself, so it carries a URI on every
   * single event, where a summary or a location carries one only if a person
   * typed it. Widening this set is one word, and wants the same thing that
   * bought this entry: a divergence report naming the property.
   */
  private static final Set<String>         LINKIFIED_PROPERTIES     = Set.of(DESCRIPTION);

  /**
   * A URI immediately followed by a bracketed repetition of <b>itself</b>, as an
   * auto-linkifying server appends it.
   *
   * <p>
   * Every part of this is a restriction, and each one is what keeps the
   * exemption from meaning more than it says:
   *
   * <ul>
   * <li><b>A backreference, not a second URI.</b> The bracketed text must be
   * character-for-character the token before it. {@code A &lt;B&gt;} matches
   * nothing and is reported, so a client that swapped a link for another one
   * inside the brackets is still an edit — and so, in particular, is a
   * rewritten <i>token</i> in one of the answer links EXO-89753 writes, which
   * is the case that must never pass: those links answer on somebody's behalf.
   * </li>
   * <li><b>Adjacency.</b> The repetition must sit immediately after the
   * original, one space between them, which is what linkifying produces. A
   * bracketed URI anywhere else in the text is left alone and compared.</li>
   * <li><b>A URI, not any token.</b> The value must carry a scheme, so this
   * cannot collapse {@code word &lt;word&gt;} in prose somebody wrote.</li>
   * <li><b>The original survives.</b> Only the bracketed copy is dropped, so a
   * copy that kept the brackets and lost the link — {@code Event link:
   * &lt;http://…&gt;} — does not match and is reported.</li>
   * </ul>
   *
   * <p>
   * What it deliberately does not cover: angle brackets in general, a URI that
   * differs from its neighbour in any way at all, and any relaxation of URI
   * equality. Two links that are not the same string are still two different
   * links here, exactly as before.
   */
  private static final Pattern             LINKIFIED_URI_REPEAT     =
                                                                Pattern.compile("(^| )([a-zA-Z][a-zA-Z0-9+.\\-]*:[^\\s<>]+) <\\2>");

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
   *
   * <p>
   * <b>The fallback, not the lever, since EXO-89771.</b> Each server carries its
   * own list now and this value decides only for a registration that has never
   * been asked, which is what makes the change behaviour-neutral on upgrade. It
   * stays read from configuration rather than being deleted: an operator who set
   * it must not have it silently stop applying.
   */
  @Value("${exo.agenda.caldav.mirror.ignoredProperties:}")
  private String                           ignoredProperties        = "";

  /**
   * Property names the operator has declared this deployment's servers may
   * decline to store.
   *
   * <p>
   * The second lever, and it answers the opposite case to the first.
   * {@link #ignoredProperties} covers a property the server <b>adds</b> that eXo
   * has never heard of; this one covers a property eXo <b>emits</b> and the
   * server does not keep at all. BlueMind does that with {@code CONFERENCE}: eXo
   * writes the video link on every push, the copy comes back without it, and no
   * repair can change that — the sweep proved it 399 times in one day, rewriting
   * five copies every five minutes to have the same property dropped again.
   * Repairing something the server will undo on every write is not a repair, and
   * that is the same reasoning the dropped-attendee rule already records in
   * {@link #tolerated}.
   *
   * <p>
   * <b>One direction only, and that is what keeps it safe.</b> It excuses a
   * statement eXo makes and the copy does not carry — an <i>absence</i>. It can
   * never excuse a statement the copy makes: if a client changed the conference
   * link rather than the server dropping it, eXo's value is excused as an
   * absence but the client's value arrives as a surplus on the server's side,
   * which no rule here covers, and the copy is still judged different. So no
   * setting of this can make two differing values look equal.
   *
   * <p>
   * <b>What it does cost, stated plainly.</b> On a server named here, a client
   * <i>deleting</i> the property goes unnoticed — it is indistinguishable from
   * the server declining to store it, and on such a server that information
   * genuinely is not available to eXo.
   *
   * <p>
   * <b>It is no longer global, and that was the interim this replaces.</b> A
   * deployment running BlueMind alongside a server that stores
   * {@code CONFERENCE} faithfully used to give up the detection on both
   * (EXO-89771); this value is now only the fallback {@link #ignoredProperties}
   * describes.
   *
   * <p>
   * Restricted to {@link #EVENT_PROPERTIES}, so it can only ever name a property
   * eXo actually writes, and empty by default — nothing is excused until an
   * operator has read a log line naming what to excuse.
   */
  @Value("${exo.agenda.caldav.mirror.droppedProperties:}")
  private String                           droppedProperties        = "";

  /**
   * The fallback for a registration that has never been asked, adding side.
   *
   * @return the configured value, never null
   */
  public String getGlobalIgnoredProperties() {
    return ignoredProperties;
  }

  /**
   * The fallback for a registration that has never been asked, dropping side.
   *
   * @return the configured value, never null
   */
  public String getGlobalDroppedProperties() {
    return droppedProperties;
  }

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
   *         {@link IcsJudgement.Verdict#DIFFERENT}
   */
  public IcsJudgement compare(String serverIcs, String exoIcs, Collection<String> ownerAddresses) {
    return compare(serverIcs, exoIcs, ownerAddresses, null, null);
  }

  /**
   * Compares the object a server holds against the object eXo would write for
   * the same event now, excusing what <i>this</i> server has been declared to
   * do.
   *
   * <p>
   * The two lists arrive as arguments rather than being read once at boot,
   * which is the whole point of EXO-89771: an administrator ticking a box in
   * the drawer changes what the <i>next sweep</i> concludes, with no restart.
   * {@link ServerExcusals} holds what each of them excuses, and why null and an
   * empty string are different answers.
   *
   * @param serverIcs the calendar object as the server holds it
   * @param exoIcs the calendar object eXo's engine renders for the event now
   * @param ownerAddresses every address a copy on this account may name its own
   *          owner by; may be null or empty
   * @param serverIgnoredProperties patterns this server is excused for adding,
   *          or null to fall back to the deployment-wide property
   * @param serverDroppedProperties patterns this server is excused for not
   *          keeping, or null to fall back to the deployment-wide property
   * @return the judgement, carrying what diverged when it is
   *         {@link IcsJudgement.Verdict#DIFFERENT}
   */
  public IcsJudgement compare(String serverIcs,
                           String exoIcs,
                           Collection<String> ownerAddresses,
                           String serverIgnoredProperties,
                           String serverDroppedProperties) {
    ServerExcusals excusals = ServerExcusals.of(serverIgnoredProperties,
                                               serverDroppedProperties,
                                               ignoredProperties,
                                               droppedProperties);
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
      return new IcsJudgement(IcsJudgement.Verdict.UNJUDGEABLE, "the object eXo renders cannot be read: " + e.getMessage());
    }
    VEvent owned = singleEvent(exo);
    if (owned == null) {
      return new IcsJudgement(IcsJudgement.Verdict.UNJUDGEABLE, "the object eXo renders carries no single event");
    }
    Calendar server;
    try {
      server = parse(serverIcs);
    } catch (IcsParseException e) {
      // The copy is there and cannot be read as iCalendar. Bounded rather than
      // silent: the repair will fail on the same parse and the pass gives up
      // after a few attempts, saying so — which is the honest outcome.
      return new IcsJudgement(IcsJudgement.Verdict.DIFFERENT, "the object the server holds cannot be read: " + e.getMessage());
    }
    String key = identityOf(owned, exo);
    VEvent counterpart = eventWithIdentity(server, key);
    if (counterpart == null) {
      return new IcsJudgement(IcsJudgement.Verdict.DIFFERENT, "the component " + key + " is not in the object any more");
    }
    List<IcsDivergence> observed = new ArrayList<>();
    List<String> reported = divergences(counterpart, server, owned, exo, owner, excusals, observed);
    List<IcsDivergence> behaviours = IcsStatement.collapse(observed);
    if (reported.isEmpty()) {
      return new IcsJudgement(IcsJudgement.Verdict.EQUIVALENT, null, behaviours);
    }
    return new IcsJudgement(IcsJudgement.Verdict.DIFFERENT, String.join("; ", reported), behaviours);
  }

  /**
   * Every way the two paired components disagree: their statements, and — when
   * either repeats — the instants their series produces.
   *
   * @param serverEvent the component the server holds
   * @param serverCalendar the object it belongs to, for its zone definitions
   * @param exoEvent the component eXo renders
   * @param exoCalendar the object it belongs to, for its zone definitions
   * @param ownerAddresses the bare addresses this account's owner may be named
   *          by, carried down so an attendee line naming them is compared as
   *          the owner rather than as whichever spelling each side chose
   * @param excusals what this server has been declared to do
   * @param observed the accumulator one entry per diverging property is added to
   * @return the divergences worth reporting, capped
   */
  private List<String> divergences(VEvent serverEvent,
                                   Calendar serverCalendar,
                                   VEvent exoEvent,
                                   Calendar exoCalendar,
                                   Set<String> ownerAddresses,
                                   ServerExcusals excusals,
                                   List<IcsDivergence> observed) {
    List<String> divergences = new ArrayList<>();
    // The two sides' attendee lines are indexed by the person they name before
    // anything is compared, because that is the only place both the parsed
    // property and the statement it normalises to are in hand at once. Merging
    // the two indexes is safe: a statement determines its own person — an
    // ordinary line carries the address in its value, and every spelling of the
    // owner collapses to the same token.
    Map<String, AttendeeLine> attendees = attendeeLines(serverEvent, serverCalendar, ownerAddresses, excusals);
    attendees.putAll(attendeeLines(exoEvent, exoCalendar, ownerAddresses, excusals));
    diff(eventStatements(serverEvent, serverCalendar, ownerAddresses, excusals),
         eventStatements(exoEvent, exoCalendar, ownerAddresses, excusals),
         attendees,
         divergences,
         observed,
         excusals);
    if (divergences.isEmpty()) {
      diffExpansions(serverCalendar, serverEvent, exoCalendar, exoEvent, divergences);
    }
    return divergences;
  }

  /**
   * The statements of one compared component, at the top level.
   *
   * @param event the component
   * @param calendar the object it belongs to, for its zone definitions
   * @param ownerAddresses the bare addresses naming the account's owner
   * @param excusals what this server has been declared to do
   * @return the statements, by canonical line and count
   */
  private Map<String, Integer> eventStatements(VEvent event,
                                               Calendar calendar,
                                               Set<String> ownerAddresses,
                                               ServerExcusals excusals) {
    return statementsOf(event, calendar, EVENT_PROPERTIES, IGNORED_EVENT_PROPERTIES, ownerAddresses, excusals, false);
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
      return IcsCompatibility.newCalendarBuilder().build(new StringReader(ics));
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
   * repeated statement still counts. Whether a repetition then <i>matters</i> is
   * decided later and separately, in {@link #isServerSideRepetition}: the
   * multiset is built faithfully here so that the rule has something to read.
   *
   * @param component the component to normalise
   * @param calendar the object it belongs to, for zone resolution
   * @param recognised the closed set of property names this level emits
   * @param ignored the property names read but never compared at this level
   * @param ownerAddresses the bare addresses naming the account's owner, passed
   *          on to each property and into every VALARM, so the owner exemption
   *          holds at every depth rather than only at the top level
   * @param excusals what this server has been declared to do
   * @param nested whether these are the statements of a component embedded in
   *          another — a VALARM. An excused property is dropped here when it is,
   *          and left for {@link #diff} when it is not; see
   *          {@link #normaliseProperty} for why the two levels differ
   * @return the statements, by canonical line and count
   */
  private Map<String, Integer> statementsOf(net.fortuna.ical4j.model.Component component,
                                            Calendar calendar,
                                            Set<String> recognised,
                                            Set<String> ignored,
                                            Set<String> ownerAddresses,
                                            ServerExcusals excusals,
                                            boolean nested) {
    Map<String, Integer> statements = new TreeMap<>();
    for (Property property : component.getProperties()) {
      for (String statement : normaliseProperty(property, calendar, recognised, ignored, ownerAddresses, excusals, nested)) {
        statements.merge(statement, 1, Integer::sum);
      }
    }
    if (component instanceof VEvent event) {
      for (VAlarm alarm : event.getAlarms()) {
        Map<String, Integer> inner = statementsOf(alarm,
                                                  calendar,
                                                  ALARM_PROPERTIES,
                                                  IGNORED_ALARM_PROPERTIES,
                                                  ownerAddresses,
                                                  excusals,
                                                  true);
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
   * @param ownerAddresses the bare addresses naming the account's owner: an
   *          ATTENDEE matching one of them collapses to {@link #OWNER_ATTENDEE},
   *          so the spelling the server chose for them cannot read as a change
   * @param excusals what this server has been declared to do
   * @param nested whether the component this property belongs to is embedded in
   *          another
   * @return the canonical statements, possibly empty
   */
  private List<String> normaliseProperty(Property property,
                                         Calendar calendar,
                                         Set<String> recognised,
                                         Set<String> ignored,
                                         Set<String> ownerAddresses,
                                         ServerExcusals excusals,
                                         boolean nested) {
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
      if (nested && excusals.excusesAdding(name)) {
        // Inside a VALARM the component is folded into one statement, so there
        // is nothing left for diff to excuse — Thunderbird's X-MOZ-LASTACK and
        // X-MOZ-SNOOZE-TIME live here. At the top level the statement stands and
        // diff excuses it there, which is what lets one divergence be both
        // excused and still shown as observed.
        return List.of();
      }
      return List.of(IcsStatement.UNRECOGNISED + name + "=" + value);
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
    if (TEXT_PROPERTIES.contains(name)) {
      // Folding is whitespace, and a server re-serialising a multi-line text
      // value has to fold it. See TEXT_PROPERTIES for why collapsing runs
      // cannot hide anything a user wrote.
      String collapsed = WHITESPACE_RUN.matcher(value).replaceAll(" ").trim();
      if (!LINKIFIED_PROPERTIES.contains(name)) {
        return collapsed;
      }
      // Runs are collapsed first, on purpose: what a linkifying server appends
      // is separated by whatever whitespace its own folding produced, and
      // "immediately after" can only be read once that is one space.
      return LINKIFIED_URI_REPEAT.matcher(collapsed).replaceAll("$1$2");
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
    String answer = answerOf(property);
    return answer == null ? OWNER_ATTENDEE : OWNER_ATTENDEE + ";PARTSTAT=" + answer;
  }

  /**
   * The answer an attendee line states, or null when it states none.
   *
   * <p>
   * NEEDS-ACTION is the RFC 5545 default and reads the same as saying nothing —
   * it is what a server writes for somebody it has attached to an event they
   * have not replied to yet — so it is returned as no answer at all. That is
   * the same reduction {@link #DEFAULT_PARAMETERS} performs on the compared
   * suffix, kept in step with it deliberately: two readers of the same fact
   * that disagreed about NEEDS-ACTION would make a person's answer depend on
   * which of them looked.
   *
   * <p>
   * Read here rather than off the canonical statement on purpose. The statement
   * joins a sorted parameter suffix and a value with the same {@code =} the
   * parameters themselves use, so recovering a field from it means guessing
   * where the suffix stops; the parsed property still has each field on its own.
   *
   * @param property the ATTENDEE property
   * @return the upper-cased PARTSTAT, or null when the line states no answer
   */
  private String answerOf(Property property) {
    Parameter partStat = property.getParameter("PARTSTAT");
    String answer = partStat == null ? null : StringUtils.trimToNull(partStat.getValue());
    if (answer == null || answer.equalsIgnoreCase(DEFAULT_PARAMETERS.get("PARTSTAT"))) {
      return null;
    }
    return answer.toUpperCase(Locale.ROOT);
  }

  /**
   * Who each attendee statement of one component names, and what that line
   * answers for them.
   *
   * <p>
   * The statements are produced by the very same {@link #normaliseProperty}
   * call {@link #statementsOf} makes, so the index cannot describe a line the
   * comparison spells differently: one call, one grammar, two readers.
   *
   * <p>
   * <b>The owner is keyed by {@link #OWNER_ATTENDEE}, not by an address.</b> An
   * account owns two spellings of its owner and they differ in practice, so a
   * pair matched on one of the two would miss exactly the way EXO-89715 missed
   * — silently, and on half the copies.
   *
   * @param event the component
   * @param calendar the object it belongs to, for its zone definitions
   * @param ownerAddresses the bare addresses naming the account's owner
   * @param excusals what this server has been declared to do
   * @return the person and the answer behind each attendee statement
   */
  private Map<String, AttendeeLine> attendeeLines(VEvent event,
                                                  Calendar calendar,
                                                  Set<String> ownerAddresses,
                                                  ServerExcusals excusals) {
    Map<String, AttendeeLine> lines = new TreeMap<>();
    for (Property property : event.getProperties()) {
      if (!"ATTENDEE".equalsIgnoreCase(property.getName())) {
        continue;
      }
      String address = bareAddress(property.getValue());
      AttendeeLine line = new AttendeeLine(ownerAddresses.contains(address) ? OWNER_ATTENDEE : address, answerOf(property));
      for (String statement : normaliseProperty(property,
                                                calendar,
                                                EVENT_PROPERTIES,
                                                IGNORED_EVENT_PROPERTIES,
                                                ownerAddresses,
                                                excusals,
                                                false)) {
        lines.put(statement, line);
      }
    }
    return lines;
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
   * log line stays a log line — and, at DEBUG, uncapped and unabbreviated.
   *
   * <p>
   * <b>Why the second, fuller report exists.</b> The capped line is what runs on
   * every pass and it has to stay short. But a copy that keeps being judged
   * altered is diagnosed by reading exactly what the two sides say, and the cap
   * hides it twice over: the fourth divergence becomes "and 1 more", and the
   * first three are abbreviated at {@link #REPORTED_STATEMENT} characters —
   * which, for a description, cuts the line long before the paragraph that
   * differs. Diagnosing the BlueMind whitespace divergence this way meant
   * reading two truncated statements out of six and inferring the rest. At DEBUG
   * both sides are stated in full, so the answer is read rather than deduced.
   *
   * <p>
   * <b>Two answers per divergence, not one.</b> A divergence the operator's own
   * list excuses is still <i>observed</i> — otherwise the very quirk an
   * administrator excused would vanish from the list they excused it in. One a
   * built-in rule tolerates is not: an owner line the server attaches, an
   * attendee it declines to carry and a statement it repeats are normal CalDAV
   * behaviour, and offering them as decisions would bury the ones that are.
   *
   * <p>
   * <b>Pairing runs first, and the order is the fix.</b> Two statements about
   * one guest are one disagreement, and a loop that meets them one at a time
   * decides each on its own — which is how a tolerance for an attendee the
   * server did not keep came to swallow the half of a pair it recognised and
   * report the other half as something else entirely. See
   * {@link #pairAttendeeStatements}.
   *
   * @param serverStatements what the server's component says
   * @param exoStatements what eXo's component says
   * @param attendees the person and the answer behind each attendee statement,
   *          so a guest both copies name can be folded into one statement
   *          before the tolerance rules see either half
   * @param divergences the accumulator the reported findings are added to
   * @param observed the accumulator the server's behaviours are added to
   * @param excusals what this server has been declared to do
   */
  private void diff(Map<String, Integer> serverStatements,
                    Map<String, Integer> exoStatements,
                    Map<String, AttendeeLine> attendees,
                    List<String> divergences,
                    List<IcsDivergence> observed,
                    ServerExcusals excusals) {
    Set<String> exoProperties = exoStatements.keySet()
                                             .stream()
                                             .map(IcsStatement::observedPropertyOf)
                                             .collect(Collectors.toSet());
    // Whether eXo's render names anybody but the account's own owner, which is
    // what tells an organizer somebody deleted from an ordinary meeting apart
    // from the shape a server gives an appointment. Read off the render rather
    // than off agenda, because the render is what the copy is compared against.
    boolean soloEvent = exoStatements.keySet().stream().noneMatch(this::isAttendee);
    // Both of those are read before the pairing, which consumes statements from
    // the two maps: what a copy is judged against is eXo's render as it stands,
    // not what is left of it once the pairs have been taken out.
    List<String> drifts = pairAttendeeStatements(serverStatements, exoStatements, attendees, observed, soloEvent);
    Set<String> statements = new TreeSet<>(serverStatements.keySet());
    statements.addAll(exoStatements.keySet());
    int reported = 0;
    for (String drift : drifts) {
      // First, and deliberately: a person spelled two ways is the finding an
      // administrator can act on, and the cap must not spend its three lines on
      // the alphabetically luckier statements before reaching it.
      if (reported++ < REPORTED_DIVERGENCES) {
        divergences.add(drift);
      }
    }
    for (String statement : statements) {
      int onServer = serverStatements.getOrDefault(statement, 0);
      int inExo = exoStatements.getOrDefault(statement, 0);
      if (onServer == inExo) {
        continue;
      }
      boolean excused = excusals.excuse(statement, onServer, inExo, exoProperties, EVENT_PROPERTIES);
      boolean rule = tolerated(statement, onServer, inExo);
      if (excused || !rule) {
        IcsStatement.observe(statement, onServer, inExo, observed, soloEvent);
      }
      if (excused || rule) {
        continue;
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Divergence {}: server {}, eXo {}, statement [{}]", reported + 1, onServer, inExo, statement);
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
   * Folds each guest both copies name, spelled differently on each, into one
   * statement about that person — before any tolerance rule sees either half.
   *
   * <p>
   * <b>The defect this exists for</b> (EXO-89829). On the rig's BlueMind
   * account, eXo rendered {@code ATTENDEE;PARTSTAT=DECLINED:mailto:bob@…} for a
   * guest whose answer the server does not keep: its copy carried the same
   * person on a line with no {@code PARTSTAT}, or with an {@code RSVP=TRUE} of
   * its own. That is one disagreement — <i>the copy does not state this
   * person's answer</i> — and it arrived at {@link #tolerated} as two
   * statements, one surplus each way. The rule for an attendee the server did
   * not keep recognised eXo's half and swallowed it; the copy's half was left,
   * and was reported as <b>the server names an attendee eXo omits</b>. An
   * administrator reading that cannot tell an answer drift from a guest a
   * client actually added: the line is not merely terse, it names the wrong
   * event in the world.
   *
   * <p>
   * <b>And the loop guard half-applied.</b> That rule exists to stop a copy
   * being re-pushed for ever when a server will not carry something eXo holds
   * about a guest. Absorbing one half of a pair and leaving the other to drive
   * the repair gives its protection to one side only: on the rig the re-push
   * stuck, but a server that keeps stripping a guest's {@code PARTSTAT} would
   * be rewritten every five minutes for ever — precisely the case the rule was
   * written to prevent. One statement gets one decision, so it cannot half-hold
   * any more.
   *
   * <p>
   * <b>Why here and not as a third rule beside the other two.</b> A third rule
   * would patch this spelling of the defect and leave its shape — a rule that
   * recognises one side of a matched pair and not its neighbour — standing for
   * the next one. Pairing is a statement about the <i>grammar</i>: two lines
   * naming one person are one statement about that person, and the rules that
   * follow are then free to be what they always claimed to be, rules about a
   * guest one side does not name at all.
   *
   * <p>
   * <b>Three outcomes, and the direction decides between them.</b>
   * <ul>
   * <li><b>The copy states an answer eXo's line does not</b> — somebody replied
   * from their own client. Reported, always: this is the divergence EXO-89807
   * and EXO-89814 read an answer back from, and it is now reported as the one
   * thing it is rather than as an attendee added and an attendee dropped.</li>
   * <li><b>eXo states an answer and the copy's line states none</b> — the
   * server did not keep the answer. Tolerated, for the reason the whole guest
   * is tolerated in {@link #tolerated}: re-pushing what a server declines to
   * store is not a repair, it is a loop. This is <b>narrower</b> than the rule
   * it completes, which gives up the guest entirely; here the copy still names
   * them and understates only their reply.</li>
   * <li><b>The two lines agree on the answer and differ otherwise</b> — a role,
   * a type, a parameter nobody expected. Reported, as before, and now as one
   * finding naming the person rather than as two halves.</li>
   * </ul>
   *
   * <p>
   * <b>What it cannot hide.</b> Pairing needs the <i>same</i> person on both
   * sides: a guest only the copy names is untouched by it and is still reported
   * as somebody a client added, and a guest only eXo names still meets the rule
   * written for them. A changed address is two different people to this method,
   * so it survives as a surplus each way and the copy's surplus is reported.
   * The cost is stated plainly: on a paired guest whose answer the copy has
   * dropped, a client that also changed their role in the same breath goes
   * unnoticed — one notch less than the rule this completes already gives up,
   * which is that guest's entire line.
   *
   * @param serverStatements what the server's component says; the paired halves
   *          are consumed from it
   * @param exoStatements what eXo's component says; likewise
   * @param attendees the person and the answer behind each attendee statement
   * @param observed the accumulator the server's behaviours are added to
   * @param soloEvent whether eXo's render names no participant other than the
   *          account's own owner, read before any pair was consumed
   * @return one reportable finding per pair worth reporting, in the order the
   *         people are named
   */
  private List<String> pairAttendeeStatements(Map<String, Integer> serverStatements,
                                              Map<String, Integer> exoStatements,
                                              Map<String, AttendeeLine> attendees,
                                              List<IcsDivergence> observed,
                                              boolean soloEvent) {
    Map<String, List<String>> serverSurplus = surplusByPerson(serverStatements, exoStatements, attendees);
    Map<String, List<String>> exoSurplus = surplusByPerson(exoStatements, serverStatements, attendees);
    List<String> drifts = new ArrayList<>();
    for (Map.Entry<String, List<String>> person : serverSurplus.entrySet()) {
      List<String> inExoLines = exoSurplus.get(person.getKey());
      if (inExoLines == null) {
        continue;
      }
      List<String> onServerLines = person.getValue();
      for (int index = 0; index < Math.min(onServerLines.size(), inExoLines.size()); index++) {
        String onServer = onServerLines.get(index);
        String inExo = inExoLines.get(index);
        // Consumed from both maps, so the loop that follows never sees either
        // half. A surplus of two lines against one leaves the third line where
        // it was: the server really does name that person twice, and the line
        // it has over and above the pair is a surplus like any other.
        cancel(serverStatements, onServer);
        cancel(exoStatements, inExo);
        String serverAnswer = attendees.get(onServer).answer();
        String exoAnswer = attendees.get(inExo).answer();
        if (serverAnswer == null && exoAnswer != null) {
          // Tolerated, and therefore not observed either: what a built-in rule
          // excuses is never offered to an administrator as a decision. Said at
          // DEBUG all the same, because a copy that keeps understating an answer
          // is diagnosed by reading what the two sides say.
          LOG.debug("The copy does not carry the answer eXo states for {}: [{}] against [{}]. "
              + "Left alone, as an answer a server declines to store cannot be repaired into it", person.getKey(), onServer, inExo);
          continue;
        }
        // Both halves are observed, so IcsStatement.collapse folds them into the
        // one behaviour they are: this server REWRITES the line, which is what
        // an administrator has to be shown rather than a bare addition.
        IcsStatement.observe(onServer, 1, 0, observed, soloEvent);
        IcsStatement.observe(inExo, 0, 1, observed, soloEvent);
        drifts.add(describeDrift(person.getKey(), onServer, inExo, serverAnswer, exoAnswer));
      }
    }
    return drifts;
  }

  /**
   * The attendee statements one side makes over and above the other, grouped by
   * the person each names and repeated once per surplus.
   *
   * <p>
   * A surplus rather than a presence: a line both sides state the same number
   * of times is not a disagreement about anybody, and must not be paired with
   * one that is.
   *
   * @param statements the side being read
   * @param other the side it is read against
   * @param attendees the person behind each attendee statement; a statement
   *          absent from it is not an attendee line and is skipped
   * @return the surplus statements by person, each repeated as often as it is
   *         surplus
   */
  private Map<String, List<String>> surplusByPerson(Map<String, Integer> statements,
                                                    Map<String, Integer> other,
                                                    Map<String, AttendeeLine> attendees) {
    Map<String, List<String>> surplus = new TreeMap<>();
    for (Map.Entry<String, Integer> statement : statements.entrySet()) {
      AttendeeLine line = attendees.get(statement.getKey());
      if (line == null) {
        continue;
      }
      int extra = statement.getValue() - other.getOrDefault(statement.getKey(), 0);
      for (int index = 0; index < extra; index++) {
        surplus.computeIfAbsent(line.identity(), person -> new ArrayList<>()).add(statement.getKey());
      }
    }
    return surplus;
  }

  /**
   * Takes one occurrence of a statement out of a side's multiset, removing the
   * entry when nothing is left of it.
   *
   * <p>
   * Removed rather than left at zero so that the union the comparison iterates
   * carries only statements somebody still makes.
   *
   * @param statements the multiset
   * @param statement the statement one occurrence of which has been accounted
   *          for
   */
  private void cancel(Map<String, Integer> statements, String statement) {
    statements.merge(statement, -1, (count, decrement) -> count + decrement <= 0 ? null : count + decrement);
  }

  /**
   * How a paired guest is reported: one finding, naming the person, saying what
   * each side states about them and saying in so many words that this is one
   * guest rather than one added and one dropped.
   *
   * <p>
   * <b>The wording is part of the fix.</b> The line it replaces —
   * {@code ATTENDEE=mailto:bob@… (server 1, eXo 0)} — is true of an answer
   * drift and of a guest a client added, and those call for opposite actions
   * from whoever reads the log. Naming the person once, and both answers beside
   * each other, is what lets the two be told apart at a glance.
   *
   * @param identity the person both copies name
   * @param onServer the statement the copy makes about them
   * @param inExo the statement eXo's render makes about them
   * @param serverAnswer what the copy says they answered, or null for none
   * @param exoAnswer what eXo says they answered, or null for none
   * @return the finding, bounded like every other reported statement
   */
  private String describeDrift(String identity, String onServer, String inExo, String serverAnswer, String exoAnswer) {
    String who = OWNER_ATTENDEE.equals(identity) ? "the calendar's own owner"
                                                 : StringUtils.abbreviate(identity, REPORTED_STATEMENT);
    if (!StringUtils.equals(serverAnswer, exoAnswer)) {
      return "the answer for " + who + " differs: " + StringUtils.defaultString(serverAnswer, "no answer") + " on the server, "
          + StringUtils.defaultString(exoAnswer, "no answer") + " in eXo (one attendee, not one added and one dropped)";
    }
    return "the line for " + who + " differs: [" + StringUtils.abbreviate(onServer, REPORTED_STATEMENT) + "] on the server, ["
        + StringUtils.abbreviate(inExo, REPORTED_STATEMENT) + "] in eXo (one attendee, not one added and one dropped)";
  }

  /**
   * What one attendee statement says: the person it names, and the answer it
   * states for them.
   *
   * @param identity the person, as a bare address — or {@link #OWNER_ATTENDEE}
   *          for the account's own owner, who has two spellings and one identity
   * @param answer the answer the line states, or null when it states none
   */
  private record AttendeeLine(String identity, String answer) {
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
   * <p>
   * <b>The mirror image: an attendee the server did not keep.</b> BlueMind
   * discards attendees whose addresses are not in its own directory, so a copy
   * legitimately carries fewer people than eXo sent. Repairing that achieves
   * nothing — eXo re-pushes the whole roster, the server drops the same
   * addresses again, and the next pass reports the same difference for ever. So
   * for the question this pass asks, <i>did a client rewrite this copy</i>, an
   * attendee the server declined to carry is not evidence of one.
   *
   * <p>
   * <b>The two tolerances point opposite ways and must never meet.</b> The
   * owner's covers a surplus on the <b>server's</b> side; this one covers a
   * surplus on <b>eXo's</b>. Written as "compare only the attendees both sides
   * share" they would collapse into tolerating every roster difference there
   * is, which would blind this pass to the thing it exists for. They are two
   * rules over one statement each, and each names its own direction — so a
   * PARTSTAT change always leaves a server-side surplus that neither rule
   * covers, and a client adding somebody is a server-side surplus of a
   * non-owner, which neither covers either.
   *
   * <p>
   * <b>And the second rule only ever meets a guest the copy does not name at
   * all</b>, since EXO-89829. It used to meet half a matched pair as well: a
   * server that keeps an attendee but not their answer states that person on a
   * line of its own, and eXo's answered line for them arrived here as a surplus
   * this rule swallowed — leaving the copy's line to be reported as an attendee
   * the server had added, and leaving the loop guard applying to one side of
   * one disagreement. {@link #pairAttendeeStatements} folds such a pair into
   * one statement before anything reaches this method, so what it decides is
   * again what it was written for.
   *
   * <p>
   * The owner is deliberately outside the second rule. The architect's reason
   * for it — that a repair would be undone on the next write — does not hold
   * for them: the owner is in the server's own directory by construction, so
   * re-pushing their line sticks. An owner the copy has lost stays a
   * difference.
   *
   * <p>
   * <b>The third rule is about how many times, not about what.</b> A server
   * that states something eXo also states, and then states it again, has said
   * nothing eXo did not — see {@link #isServerSideRepetition}. It points the
   * server's way like the first rule, and it is the only one of the three that
   * turns on the counts rather than on the statement.
   *
   * <p>
   * <b>The operator's own rule is deliberately not here.</b> It used to be, as a
   * fourth clause; it moved to {@link ServerExcusals#excuse} because
   * {@link #diff} has to tell the two apart — what these three tolerate is never
   * offered to an administrator, what an excusal covers must still be.
   *
   * @param statement the canonical statement that diverged
   * @param onServer how many times the server's copy states it
   * @param inExo how many times eXo's render states it
   * @return true when the divergence is not a rewrite
   */
  private boolean tolerated(String statement, int onServer, int inExo) {
    if (OWNER_ATTENDEE.equals(statement)) {
      return onServer > inExo;
    }
    if (isAttendee(statement) && inExo > onServer) {
      return true;
    }
    return isServerSideRepetition(statement, onServer, inExo);
  }

  /**
   * Whether the divergence is the server holding a statement eXo also holds,
   * and holding it more than once.
   *
   * <p>
   * BlueMind stores {@code URL} twice, identically, where eXo wrote it once —
   * {@code URL=… (server 2, eXo 1)} on every copy of a live account, captured on
   * the rig on 2026-08-28. It is not a value difference, so neither the
   * normalisation nor any of the other three rules touches it; it is a
   * cardinality difference, and it kept five copies permanently "altered" on its
   * own.
   *
   * <p>
   * <b>Repeating a statement states nothing new.</b> Among the properties
   * {@link IcsWriter} emits, none carries meaning in how many times it is
   * written: the single-valued ones ({@code URL}, {@code SUMMARY},
   * {@code STATUS}, …) may appear once by RFC 5545 and a reader takes one, and
   * the repeatable ones name a set — the same person is one attendee however
   * many lines carry them, the same date is one exclusion however many EXDATEs
   * carry it. So a second identical copy of a line is the same class of
   * server-side noise as re-ordering properties or filling in an RFC default.
   *
   * <p>
   * <b>Three conditions, and each is load-bearing.</b>
   * <ul>
   * <li><b>eXo states it too</b> ({@code inExo > 0}). This can only ever excuse
   * a <i>repetition</i>, never an appearance: a statement eXo does not make at
   * all is a client addition and stays a difference however many times the
   * server writes it.</li>
   * <li><b>The surplus is the server's.</b> eXo stating something twice that the
   * copy states once is a deletion on the copy's side, and deletions are not
   * this rule's business.</li>
   * <li><b>The name is one {@link IcsWriter} emits.</b> Same guard as
   * {@link ServerExcusals#excuse}, and it is what keeps
   * {@link #OWNER_ATTENDEE} and the embedded {@code VALARM{…}} statements
   * outside: neither is an event property, so a repeated alarm or a repeated
   * owner line is still reported.</li>
   * </ul>
   *
   * <p>
   * What it cannot hide: every <i>distinct</i> statement is still compared
   * exactly as before. A copy carrying eXo's link twice <b>and</b> a link of its
   * own leaves that second link as a surplus of a statement eXo never made,
   * which no rule here covers.
   *
   * @param statement the canonical statement that diverged
   * @param onServer how many times the server's copy states it
   * @param inExo how many times eXo's render states it
   * @return true when the server has merely repeated itself
   */
  private boolean isServerSideRepetition(String statement, int onServer, int inExo) {
    return inExo > 0 && onServer > inExo && EVENT_PROPERTIES.contains(IcsStatement.propertyNameOf(statement));
  }

  /**
   * Whether a canonical statement is an ordinary attendee line — not the
   * owner's, whose statement is {@link #OWNER_ATTENDEE} and is governed by its
   * own rule above.
   *
   * @param statement the canonical statement
   * @return true when it states an attendee
   */
  private boolean isAttendee(String statement) {
    return statement.startsWith("ATTENDEE;") || statement.startsWith("ATTENDEE=");
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

}
