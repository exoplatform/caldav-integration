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
import java.util.List;
import java.time.ZonedDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import net.fortuna.ical4j.model.Recur;

import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.model.Event;
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

  private static final long   CALENDAR = 42L;

  private final IcsEventMapper mapper   = new IcsEventMapper();

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
}
