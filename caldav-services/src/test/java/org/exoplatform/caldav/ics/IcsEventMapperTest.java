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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.ZonedDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import net.fortuna.ical4j.model.Recur;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.util.EventIcsBuilder;
import org.exoplatform.agenda.util.InvitationText;
import org.exoplatform.agenda.util.Utils;
import org.exoplatform.caldav.model.IcsEvent;

/**
 * What agenda ends up holding for a calendar object it did not write.
 *
 * <p>
 * The recurrence tests run the mapped event back through agenda's own
 * {@code getICalendarRecur} — the method that rebuilds a rule on creation.
 * Asserting the fields alone would prove they were set; asserting what agenda
 * rebuilds from them proves they were set to something agenda can use, which
 * is the part that silently fails.
 */
public class IcsEventMapperTest {

  private static final long    CALENDAR        = 42L;

  /**
   * The address of an event in eXo, which is both what the composed block names
   * and what the object's {@code URL} carries — one value in production
   * ({@code AgendaEventIcsMapper.toIcsEvent} passes the same {@code link} to
   * both), so one constant here.
   */
  private static final String  EXO_EVENT_URL   = "http://localhost:8080/portal/dw/agenda?eventId=87";

  /** Where the tokenised answer links a real copy offers point. */
  private static final String  ANSWER_URL      =
                                          "http://localhost:8080/portal/rest/v1/agenda/events/87/response/send?response=";

  /** The organiser's own words: the one thing a composed block must leave. */
  private static final String  ORGANISERS_TEXT = "Bring the deck.";

  private final IcsEventMapper mapper          = new IcsEventMapper();

  /**
   * A weekly rule survives agenda's rebuild.
   */
  @Test
  public void aWeeklyRuleIsStillWeeklyAfterAgendaRebuildsIt() {
    // The crux of this mapper. Agenda derives the structured fields from the
    // rule when reading a stored event, but on creation it goes the other way
    // and rebuilds the rule from them — so an event carrying only the raw
    // RRULE arrives as something else, with nothing failing on the way in.
    Event event = mapper.toEvent(recurring("FREQ=WEEKLY;BYDAY=MO"), CALENDAR);

    Recur rebuilt = Utils.getICalendarRecur(event.getRecurrence(), event.getTimeZoneId());

    assertEquals("WEEKLY", rebuilt.getFrequency().name());
    assertEquals("MO", String.valueOf(rebuilt.getDayList()));
  }

  /**
   * An ordinal day keeps its ordinal.
   */
  @Test
  public void theLastSundayOfTheMonthDoesNotBecomeEverySunday() {
    // "-1SU" is one meeting a month. Dropping the ordinal makes it four, and
    // the user sees three meetings that do not exist.
    Event event = mapper.toEvent(recurring("FREQ=MONTHLY;BYDAY=-1SU"), CALENDAR);

    Recur rebuilt = Utils.getICalendarRecur(event.getRecurrence(), event.getTimeZoneId());

    assertEquals("-1SU", String.valueOf(rebuilt.getDayList()));
  }

  /**
   * A rule that omits INTERVAL gets the RFC default, not ical4j's answer.
   */
  @Test
  public void anOmittedIntervalBecomesOneRatherThanMinusOne() {
    // RFC 5545 defaults INTERVAL to 1; ical4j answers -1 when the rule omits
    // it. Passed through, that is an interval agenda cannot use.
    Event event = mapper.toEvent(recurring("FREQ=DAILY"), CALENDAR);

    assertEquals(1, event.getRecurrence().getInterval());
  }

  /**
   * A bounded rule keeps its bound.
   */
  @Test
  public void aRuleThatEndsKeepsItsEnd() {
    Event event = mapper.toEvent(recurring("FREQ=WEEKLY;UNTIL=20261231T235959Z"), CALENDAR);

    assertEquals(LocalDate.of(2026, 12, 31), event.getRecurrence().getUntil());
  }

  /**
   * A counted rule keeps its count.
   */
  @Test
  public void aRuleThatRunsAFixedNumberOfTimesKeepsTheCount() {
    Event event = mapper.toEvent(recurring("FREQ=WEEKLY;COUNT=10"), CALENDAR);

    assertEquals(10, event.getRecurrence().getCount());
    assertNull(event.getRecurrence().getUntil());
  }

  /**
   * A rule that cannot be read is dropped rather than invented.
   */
  @Test
  public void anUnreadableRuleLeavesASingleEventRatherThanAnInventedSeries() {
    // Losing the series loudly beats showing the user a series nobody wrote.
    // The event itself is still worth having.
    Event event = mapper.toEvent(recurring("FREQ=NOT_A_FREQUENCY;GARBAGE"), CALENDAR);

    assertNotNull(event);
    assertNull(event.getRecurrence());
  }

  /**
   * An object with no rule is a single event.
   */
  @Test
  public void anObjectWithNoRuleCarriesNoRecurrence() {
    IcsEvent source = base();
    source.setRecurrenceRule(null);

    assertNull(mapper.toEvent(source, CALENDAR).getRecurrence());
  }

  /**
   * The event lands in the calendar it was given, and nowhere else.
   */
  @Test
  public void theEventCarriesNoIdentityItInvented() {
    // No id, no creator: those belong to the caller placing the event. An
    // event carrying an id it invented would overwrite whatever holds it.
    Event event = mapper.toEvent(base(), CALENDAR);

    assertEquals(CALENDAR, event.getCalendarId());
    assertEquals(0, event.getId());
    assertEquals(0, event.getCreatorId());
  }

  /**
   * Times are anchored on the zone the object named.
   */
  @Test
  public void theEventIsAnchoredOnTheZoneTheObjectNamed() {
    IcsEvent source = base();
    source.setTimeZoneId("Europe/Paris");

    Event event = mapper.toEvent(source, CALENDAR);

    assertEquals(ZoneId.of("Europe/Paris"), event.getTimeZoneId());
    assertEquals(source.getStart(), event.getStart().toInstant());
  }

  /**
   * A zone this platform does not know does not lose the event.
   */
  @Test
  public void anUnknownZoneAnchorsOnUtcRatherThanDroppingTheEvent() {
    IcsEvent source = base();
    source.setTimeZoneId("Mars/Olympus_Mons");

    Event event = mapper.toEvent(source, CALENDAR);

    assertEquals(ZoneOffset.UTC, event.getTimeZoneId());
    assertEquals(source.getStart(), event.getStart().toInstant());
  }

  /**
   * An imported event is a confirmed one.
   */
  @Test
  public void anImportedEventIsConfirmedRatherThanTentative() {
    // Agenda spells a date poll TENTATIVE. Borrowing that word for a real
    // meeting would show it as something nobody has confirmed.
    assertEquals(EventStatus.CONFIRMED, mapper.toEvent(base(), CALENDAR).getStatus());
  }

  /**
   * An all-day object stays an all-day event.
   */
  @Test
  public void anAllDayObjectStaysAllDay() {
    IcsEvent source = base();
    source.setAllDay(true);

    assertTrue(mapper.toEvent(source, CALENDAR).isAllDay());
  }

  /**
   * A summary the object left empty does not become null.
   */
  @Test
  public void anObjectWithNoSummaryGetsAnEmptyOneNotANull() {
    // Agenda validates the summary on create; a null would fail the write for
    // an event that is otherwise perfectly importable.
    IcsEvent source = base();
    source.setSummary(null);

    assertEquals("", mapper.toEvent(source, CALENDAR).getSummary());
  }

  /**
   * An override names the occurrence it amends.
   */
  @Test
  public void anOverrideNamesTheOccurrenceItAmends() {
    IcsEvent source = base();
    source.setOccurrenceId("20261012T090000Z");

    assertEquals(Instant.parse("2026-10-12T09:00:00Z"), mapper.occurrenceOf(source).toInstant());
  }

  /**
   * A master names no occurrence.
   */
  @Test
  public void aMasterNamesNoOccurrence() {
    assertNull(mapper.occurrenceOf(base()));
    assertNull(mapper.occurrenceOf(null));
  }

  /**
   * A recurrence identifier that cannot be read is ignored.
   */
  @Test
  public void anUnreadableOccurrenceIdentifierIsIgnored() {
    IcsEvent source = base();
    source.setOccurrenceId("last tuesday");

    assertNull(mapper.occurrenceOf(source));
  }

  @Test
  public void aRuleThatCannotBeReadLosesTheSeriesAndKeepsTheEvent() {
    // Losing the series loudly beats inventing one: a rule this parser cannot
    // read would otherwise become a rule agenda made up, repeating a meeting
    // on days nobody chose.
    Event event = mapper.toEvent(recurring("FREQ=NONSENSE;UNTIL=nope"), CALENDAR);

    assertNull(event.getRecurrence());
  }

  @Test
  public void numericRuleListsAreCarriedThrough() {
    Event event = mapper.toEvent(recurring("FREQ=MONTHLY;BYMONTHDAY=1,15;BYMONTH=3"), CALENDAR);

    assertEquals(List.of("1", "15"), event.getRecurrence().getByMonthDay());
    assertEquals(List.of("3"), event.getRecurrence().getByMonth());
  }

  @Test
  public void aRuleWithNoDayListLeavesTheDaysEmpty() {
    // An empty list, never null: agenda rebuilds the rule from these fields
    // and a null would be a NullPointerException at the moment of writing it
    // back out.
    Event event = mapper.toEvent(recurring("FREQ=DAILY;COUNT=5"), CALENDAR);

    assertNotNull(event.getRecurrence().getByDay());
    assertTrue(event.getRecurrence().getByDay().isEmpty());
  }

  @Test
  public void anUnreadableZoneFallsBackRatherThanThrowing() {
    // A server naming a zone this JVM does not know must not cost the event.
    IcsEvent source = base();
    source.setTimeZoneId("Mars/Olympus_Mons");

    assertNotNull(mapper.toEvent(source, CALENDAR).getStart());
  }

  @Test
  public void anExcludedInstantIsRead() {
    assertNotNull(mapper.occurrenceOf("20261012T070000Z", "Etc/UTC"));
  }

  @Test
  public void anExcludedAllDayDateIsReadAsTheStartOfThatDay() {
    // An all-day series excludes a day, not an instant. ical4j's DateTime
    // accepts "20261012" as midnight in the JVM's own zone, which on a server
    // east of UTC shifted the exclusion onto the day before: the occurrence
    // the user deleted stayed, and its neighbour vanished.
    ZonedDateTime excluded = mapper.occurrenceOf("20261012", "Etc/UTC");

    assertNotNull(excluded);
    assertEquals(2026, excluded.getYear());
    assertEquals(10, excluded.getMonthValue());
    assertEquals(12, excluded.getDayOfMonth());
    assertEquals(0, excluded.getHour());
  }

  @Test
  public void anExcludedDateThatMakesNoSenseIsIgnored() {
    assertNull(mapper.occurrenceOf("not-a-date", "Etc/UTC"));
    assertNull(mapper.occurrenceOf("", "Etc/UTC"));
    assertNull(mapper.occurrenceOf(null, "Etc/UTC"));
  }

  @Test
  public void anObjectWithNoOccurrenceIdIsNotAnOverride() {
    assertNull(mapper.occurrenceOf((IcsEvent) null));
    assertNull(mapper.occurrenceOf(base()));
  }

  /**
   * @param rule the recurrence rule to carry
   * @return a parsed object with that rule
   */
  private IcsEvent recurring(String rule) {
    IcsEvent source = base();
    source.setRecurrenceRule(rule);
    return source;
  }

  /**
   * @return a plain parsed object
   */
  private IcsEvent base() {
    IcsEvent source = new IcsEvent();
    source.setUid("uid-1@example.test");
    source.setSummary("Weekly");
    source.setStart(Instant.parse("2026-10-05T07:00:00Z"));
    source.setEnd(Instant.parse("2026-10-05T08:00:00Z"));
    source.setTimeZoneId("Etc/UTC");
    return source;
  }

  /**
   * The description agenda holds is the organiser's text, not the invitation
   * blurb eXo's own push composed in front of it (EXO-90227). Pinned here as
   * well as on the recogniser, because this is the one line that decides what
   * agenda stores: the recogniser can be right and this mapper still not call
   * it.
   */
  @Test
  public void anInvitationBlockOnACopyExoComposedIsNotHeldAsTheEventsDescription() {
    IcsEvent source = new IcsEvent();
    source.setUid("uid-1");
    source.setEventUrl("http://localhost:8080/portal/dw/agenda?eventId=87");
    source.setDescription("Invitation envoy\u00e9e par alice2.\n\nEvent link: http://localhost:8080/portal/dw/agenda?eventId=87"
        + "\n\nD\u00e9tails de l'\u00e9v\u00e9nement :\nBring cake.");

    assertEquals("Bring cake.", mapper.toEvent(source, CALENDAR).getDescription());
  }

  /**
   * <b>Half one of the gate's pin: the block eXo really composes is
   * recognised.</b> The input is not a shape typed out here but the render
   * {@link EventIcsBuilder#description} produces \u2014 the very call the push path
   * makes ({@code AgendaEventIcsMapper.description}) \u2014 so what is asserted is
   * that a copy eXo wrote leaves the organiser's words alone in the store.
   *
   * <p>
   * It is the half that keeps the other half honest: without it, a narrowing of
   * the recogniser that stopped recognising eXo's own block would leave the
   * verbatim pin below passing and asserting nothing.
   */
  @Test
  public void theBlockAgendasOwnBuilderComposesIsStrippedFromWhatIsStored() {
    IcsEvent source = new IcsEvent();
    source.setUid("uid-1");
    source.setEventUrl(EXO_EVENT_URL);
    source.setDescription(blockExoComposed());

    assertEquals(ORGANISERS_TEXT, mapper.toEvent(source, CALENDAR).getDescription());
  }

  /**
   * <b>Half two: the same block, on an object carrying no eXo event address, is
   * stored byte for byte.</b> This is the gate, and the pair above and here is
   * the whole of it \u2014 one input, two answers, decided by the {@code URL}
   * property alone. Nothing gives a user's words back, so this is the one place
   * that decides whether they survive the import.
   *
   * <p>
   * <b>Why the input is a real render and not a hand-written shape.</b> The
   * first spelling of this pin typed a shape a person might write \u2014 {@code Hi
   * all,} / a labelled eXo link / {@code Thanks,} / {@code Bob} \u2014 and agenda's
   * own narrowing of {@link InvitationText} (a label is now short and ends in a
   * colon, or is a bundle key of ours) made the recogniser return that text
   * untouched by itself. The pin then asserted what the recogniser already
   * guaranteed: the gate could be deleted outright and the whole suite stayed
   * green. A render the builder actually produces cannot rot that way \u2014 it is
   * the one input {@code InvitationText.stripFrom} must go on recognising
   * however narrow it becomes, since agenda pins the two against each other.
   */
  @Test
  public void theBlockAgendasOwnBuilderComposesIsStoredVerbatimWithNoExoEventUrl() {
    String composed = blockExoComposed();
    IcsEvent source = new IcsEvent();
    source.setUid("uid-1");
    source.setDescription(composed);

    assertEquals(composed, mapper.toEvent(source, CALENDAR).getDescription());
  }

  /**
   * A copy naming a <em>different</em> eXo deployment is still a copy eXo
   * composed (EXO-89824), and its block is as unwanted in a stored description
   * as a local one. The gate reads nothing off the address for exactly this
   * reason, and a deployment serving from a path passes it too.
   */
  @Test
  public void aCopyAnotherDeploymentComposedIsStrippedAllTheSame() {
    IcsEvent source = new IcsEvent();
    source.setUid("uid-1");
    source.setEventUrl("https://acceptance.example.test/intranet/portal/dw/agenda?eventId=4242");
    source.setDescription("Invitation sent by Ada in space Chem.\n\nEvent link: https://acceptance.example.test/intranet/portal/dw/agenda?eventId=4242"
        + "\n\nEvent detail:\nBring cake.");

    assertEquals("Bring cake.", mapper.toEvent(source, CALENDAR).getDescription());
  }

  /**
   * A copy whose description was nothing but the block leaves no description at
   * all, and agenda is told so the way it already is for an object carrying no
   * {@code DESCRIPTION}: null, not an empty string no reader was told to treat
   * as absent.
   */
  @Test
  public void aDescriptionThatWasNothingButTheBlockLeavesNone() {
    IcsEvent source = new IcsEvent();
    source.setUid("uid-1");
    source.setEventUrl("http://localhost:8080/portal/dw/agenda?eventId=87");
    source.setDescription("Invitation envoy\u00e9e par alice2.\n\nEvent link: http://localhost:8080/portal/dw/agenda?eventId=87");

    assertNull(mapper.toEvent(source, CALENDAR).getDescription());
  }

  /**
   * And a description nobody composed is held exactly as read, even on an
   * object that does carry an eXo event address: the recogniser declines a link
   * with words after it, and the gate does not make it stricter.
   */
  @Test
  public void aDescriptionAPersonTypedIsHeldAsRead() {
    IcsEvent source = new IcsEvent();
    source.setUid("uid-1");
    source.setEventUrl("http://localhost:8080/portal/dw/agenda?eventId=87");
    source.setDescription("See http://localhost:8080/portal/dw/agenda?eventId=87 for the agenda.\n\nBring cake.");

    assertEquals(source.getDescription(), mapper.toEvent(source, CALENDAR).getDescription());
  }

  /**
   * A {@code URL} that is not an eXo event address \u2014 the one a client or a
   * server may well carry \u2014 does not open the gate either, on the same input
   * the pair above uses: an address that says nothing about eXo is the same
   * answer as no address at all.
   */
  @Test
  public void aUrlThatIsNotAnExoEventAddressDoesNotOpenTheGate() {
    IcsEvent source = new IcsEvent();
    source.setUid("uid-1");
    source.setEventUrl("https://wiki.acme.com/meetings/weekly");
    source.setDescription(blockExoComposed());

    assertEquals(source.getDescription(), mapper.toEvent(source, CALENDAR).getDescription());
  }

  /**
   * An invitation block as eXo composes it, built by agenda's own builder rather
   * than typed out here.
   *
   * <p>
   * Every argument is what the push path passes for a real copy
   * ({@code AgendaEventIcsMapper.description}): the pusher's name, the space,
   * the link back to the event, and the three tokenised answer links a calendar
   * copy offers (EXO-89753). No Agenda bundle is readable from this suite, so
   * every label comes out as its resource-bundle key \u2014 a shape
   * {@link InvitationText} recognises on purpose, since it is what the builder
   * itself writes when no bundle can be read. The assertions do not depend on
   * that either way: they name the organiser's text, or this whole string.
   *
   * @return the description a copy eXo composed carries, block and organiser's
   *         text together
   */
  private String blockExoComposed() {
    Map<EventAttendeeResponse, String> rsvpLinks = new EnumMap<>(EventAttendeeResponse.class);
    rsvpLinks.put(EventAttendeeResponse.ACCEPTED, ANSWER_URL + "ACCEPTED&token=tok-a");
    rsvpLinks.put(EventAttendeeResponse.TENTATIVE, ANSWER_URL + "TENTATIVE&token=tok-t");
    rsvpLinks.put(EventAttendeeResponse.DECLINED, ANSWER_URL + "DECLINED&token=tok-d");
    return EventIcsBuilder.description(Locale.ENGLISH,
                                       "Alice Doe",
                                       "Chemistry",
                                       null,
                                       EXO_EVENT_URL,
                                       rsvpLinks,
                                       "<p>" + ORGANISERS_TEXT + "</p>");
  }
}
