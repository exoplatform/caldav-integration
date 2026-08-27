/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.caldav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.constant.ReminderPeriodType;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.EventAttendee;
import org.exoplatform.agenda.model.EventAttendeeList;
import org.exoplatform.agenda.model.EventConference;
import org.exoplatform.agenda.model.EventOccurrence;
import org.exoplatform.agenda.model.EventReminder;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.service.AgendaEventConferenceService;
import org.exoplatform.agenda.service.AgendaEventReminderService;
import org.exoplatform.agenda.util.NotificationUtils;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.identity.provider.SpaceIdentityProvider;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;

/**
 * The seam between agenda's identities-as-numbers and an iCalendar object's
 * need for a name and an address.
 *
 * <p>
 * What matters most here is what the mapper refuses to do. RFC 5545 makes a
 * calendar user's value a CAL-ADDRESS, and any client acting on the copy may
 * use it as a reply-to — so an address that does not exist must not be
 * invented, and a person without a visible one is left off the copy entirely.
 * That rule failing silently would put fabricated addresses on real meetings,
 * which is why it is pinned here rather than left to the corpus.
 */
@ExtendWith(MockitoExtension.class)
public class AgendaEventIcsMapperTest {

  private static final long                 PUSHER    = 5L;

  private static final long                 SOMEONE   = 9L;

  /** The link agenda mints for the event, of the shape NotificationUtils returns. */
  private static final String               EVENT_LINK  = "http://localhost:8080/portal/dw/agenda?eventId=1";

  /** The one it mints for the series an override belongs to. */
  private static final String               SERIES_LINK = "http://localhost:8080/portal/dw/agenda?eventId=77";

  private static final long                 SPACE_OWNER = 77L;

  @Mock
  private AgendaEventAttendeeService         agendaEventAttendeeService;

  @Mock
  private AgendaEventConferenceService       agendaEventConferenceService;

  @Mock
  private AgendaEventReminderService         agendaEventReminderService;

  @Mock
  private IdentityManager                    identityManager;

  @Mock
  private AgendaCalendarService               agendaCalendarService;

  @Mock
  private SpaceService                        spaceService;

  @InjectMocks
  private AgendaEventIcsMapper               mapper;

  @BeforeEach
  public void noExtras() {
    lenient().when(agendaEventAttendeeService.getEventAttendees(1L)).thenReturn(EventAttendeeList.EMPTY_ATTENDEE_LIST);
    lenient().when(agendaEventConferenceService.getEventConferences(1L)).thenReturn(List.of());
    lenient().when(agendaEventReminderService.getEventReminders(1L, PUSHER)).thenReturn(List.of());
  }

  @Test
  public void anEventCarriesItsOwnZoneAndInstants() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");

    IcsEvent ics = mapper.toIcsEvent(event(), "uid-1", PUSHER);

    assertEquals("uid-1", ics.getUid());
    assertEquals("Steering point", ics.getSummary());
    assertEquals("Europe/Paris", ics.getTimeZoneId());
    assertEquals(ZonedDateTime.parse("2026-09-08T09:00+02:00[Europe/Paris]").toInstant(), ics.getStart());
  }

  /**
   * The link back into eXo is <b>derived from the event</b>, so every render of
   * the same event produces the same one — a browser push, a five-minute sweep
   * and a repair alike (EXO-89751).
   *
   * <p>
   * That property is the whole point, and it is what was missing: the link used
   * to arrive on the push request, so only a browser push carried one and the
   * next repair stripped it. Two renders for two different pushers are asserted
   * to agree, because nothing about the recipient may enter the link — a
   * link that varied per render would put the churn straight back.
   */
  @Test
  public void theLinkIsDerivedFromTheEventSoEveryRenderAgrees() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    givenIdentity(SOMEONE, "Jane Roe", "jane@example.test");
    lenient().when(agendaEventAttendeeService.getEventAttendees(1L)).thenReturn(EventAttendeeList.EMPTY_ATTENDEE_LIST);
    lenient().when(agendaEventReminderService.getEventReminders(1L, SOMEONE)).thenReturn(List.of());

    try (MockedStatic<NotificationUtils> agendaLinks = mockStatic(NotificationUtils.class)) {
      agendaLinks.when(() -> NotificationUtils.getEventURL(1L)).thenReturn(EVENT_LINK);

      String pushed = mapper.toIcsEvent(event(), "uid-1", PUSHER).getEventUrl();
      String repaired = mapper.toIcsEvent(event(), "uid-1", SOMEONE).getEventUrl();

      assertEquals(EVENT_LINK, pushed, "the push must write the event's own link");
      assertEquals(pushed, repaired, "and every other render must write the very same one");
    }
  }

  /**
   * The link is also repeated in the description, on a labelled line beside the
   * conference one: many calendar clients never surface URL, and the
   * description is what a person reads.
   */
  @Test
  public void theLinkIsAlsoNamedInTheDescription() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");

    try (MockedStatic<NotificationUtils> agendaLinks = mockStatic(NotificationUtils.class)) {
      agendaLinks.when(() -> NotificationUtils.getEventURL(1L)).thenReturn(EVENT_LINK);

      String description = mapper.toIcsEvent(event(), "uid-1", PUSHER).getDescription();

      assertNotNull(description);
      assertTrue(description.contains(EVENT_LINK), "the description must carry the link too: " + description);
    }
  }

  /**
   * An override of a series links to the series, not to itself — the same rule
   * the UID already follows. The object carries RECURRENCE-ID to say which
   * instance it amends, and the parent id is the one agenda's screens open.
   */
  @Test
  public void anOverrideLinksToItsSeries() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    Event override = event();
    override.setParentId(77L);

    try (MockedStatic<NotificationUtils> agendaLinks = mockStatic(NotificationUtils.class)) {
      agendaLinks.when(() -> NotificationUtils.getEventURL(77L)).thenReturn(SERIES_LINK);

      assertEquals(SERIES_LINK, mapper.toIcsEvent(override, "uid-1", PUSHER).getEventUrl());
    }
  }

  /**
   * The address the copy names a person by is asked of this mapper, and of
   * nothing else. Propagating an answer outward has to find that person's
   * ATTENDEE line in an object already on the server, and a second opinion on
   * which address they answer to would match nothing — silently.
   */
  @Test
  public void theAddressTheCopyNamesSomeoneByIsAskedOfTheMapperItself() {
    givenIdentity(SOMEONE, "Alice", "alice@example.test");

    assertEquals("alice@example.test", mapper.addressOf(SOMEONE));
  }

  /**
   * A person with no visible address is left off the copy, so there is no
   * ATTENDEE line of theirs to rewrite either. The same rule, read from the
   * other end.
   */
  @Test
  public void someoneLeftOffTheCopyHasNoAddressToMatchOn() {
    givenIdentity(SOMEONE, "Alice", null);

    assertNull(mapper.addressOf(SOMEONE));
    assertNull(mapper.addressOf(404L));
  }

  @Test
  public void anOrganizerWithNoVisibleAddressIsNotNamedAtAll() {
    // Not given a plausible-looking address: a fabricated CAL-ADDRESS would be
    // forwarded as a reply-to by any client acting on the copy. With no
    // organizer the whole scheduling block is omitted downstream, which RFC
    // 5545 requires anyway — ATTENDEE is defined only where ORGANIZER is.
    givenIdentity(PUSHER, "John Doe", null);

    assertNull(mapper.toIcsEvent(event(), "uid-1", PUSHER).getOrganizer());
  }

  @Test
  public void anAttendeeWithNoVisibleAddressIsLeftOff() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    givenIdentity(SOMEONE, "Hidden Profile", null);
    givenAttendees(attendee(PUSHER, EventAttendeeResponse.ACCEPTED), attendee(SOMEONE, EventAttendeeResponse.DECLINED));

    List<org.exoplatform.caldav.model.IcsPerson> attendees = mapper.toIcsEvent(event(), "uid-1", PUSHER)
                                                                   .getAttendees();

    // The roster on the phone is shorter than the one in eXo, which the URL
    // property links back to in full. Shorter and truthful beats complete and
    // invented.
    assertEquals(1, attendees.size());
    assertEquals("john@example.test", attendees.get(0).getEmail());
    assertEquals("ACCEPTED", attendees.get(0).getResponse());
  }

  @Test
  public void aSpaceIsNotACalendarUser() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    // A space identity resolves, carries a profile, and has no mail address —
    // the same branch as a hidden profile, for a different reason.
    givenIdentity(SOMEONE, "Marketing Team", null);
    givenAttendees(attendee(SOMEONE, EventAttendeeResponse.NEEDS_ACTION));

    assertTrue(mapper.toIcsEvent(event(), "uid-1", PUSHER).getAttendees().isEmpty());
  }

  @Test
  public void thePusherBeingTheOrganizerDecidesTheSchedulingAgent() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");

    assertTrue(mapper.toIcsEvent(event(), "uid-1", PUSHER).isOrganizerIsPusher());
  }

  @Test
  public void amirroredMeetingSomeoneElseCalledIsNotClaimed() {
    // The copy of a meeting the user merely accepted must not name them as its
    // organizer, and must carry SCHEDULE-AGENT=CLIENT so their own client does
    // not offer to invite everyone on their behalf.
    givenIdentity(PUSHER, "John Doe", "john@example.test");

    assertFalse(mapper.toIcsEvent(event(), "uid-1", SOMEONE).isOrganizerIsPusher());
  }

  @Test
  public void onlyTheFirstConferenceIsWritten() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    when(agendaEventConferenceService.getEventConferences(1L)).thenReturn(List.of(conference("https://meet.test/a"),
                                                                                  conference("https://meet.test/b")));

    // A parameter value holding a comma gets quoted into one value a strict
    // reader ignores: one correct token beats two read as none.
    assertEquals("https://meet.test/a", mapper.toIcsEvent(event(), "uid-1", PUSHER).getConferenceUrl());
  }

  @Test
  public void remindersKeepAgendaOwnUnits() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    when(agendaEventReminderService.getEventReminders(1L, PUSHER)).thenReturn(List.of(reminder(2, ReminderPeriodType.HOUR)));

    IcsEvent ics = mapper.toIcsEvent(event(), "uid-1", PUSHER);

    assertEquals(2, ics.getReminders().get(0).getBefore());
    assertEquals("HOUR", ics.getReminders().get(0).getBeforePeriodType());
  }

  @Test
  public void anOccurrenceCarriesTheInstanceItAmends() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    Event event = event();
    EventOccurrence occurrence = new EventOccurrence();
    occurrence.setId(ZonedDateTime.parse("2026-09-15T09:00+02:00[Europe/Paris]"));
    event.setOccurrence(occurrence);

    assertEquals("2026-09-15T07:00:00Z", mapper.toIcsEvent(event, "uid-1", PUSHER).getOccurrenceId());
  }

  @Test
  public void exclusionsAreEmptyBecauseAgendaHasNone() {
    // Agenda's model exposes no list of excluded instances. An EXDATE for an
    // instance it never excluded would cancel a meeting nobody cancelled, so
    // the field stays empty rather than being guessed at.
    givenIdentity(PUSHER, "John Doe", "john@example.test");

    assertTrue(mapper.toIcsEvent(event(), "uid-1", PUSHER).getExceptionDates().isEmpty());
  }

  /**
   * @return an agenda event with the fields these tests read
   */
  /**
   * eXo hides a cancelled event from its own screens, so the copy is the only
   * place its attendees can still be told the meeting is off. The mapper is
   * what carries that across.
   */
  @Test
  public void aCancelledEventIsMappedAsCancelled() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    Event event = event();
    event.setStatus(EventStatus.CANCELLED);

    assertTrue(mapper.toIcsEvent(event, "uid-1", PUSHER).isCancelled());
  }

  /**
   * And an ordinary meeting is not, whichever of eXo's other statuses it
   * carries — TENTATIVE is eXo's word for a date poll, which is never pushed.
   */
  @Test
  public void anOrdinaryEventIsNotMappedAsCancelled() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    Event event = event();
    event.setStatus(EventStatus.CONFIRMED);

    assertFalse(mapper.toIcsEvent(event, "uid-1", PUSHER).isCancelled());
  }

  private Event event() {
    Event event = new Event();
    event.setId(1L);
    event.setCreatorId(PUSHER);
    event.setSummary("Steering point");
    event.setTimeZoneId(ZoneId.of("Europe/Paris"));
    event.setStart(ZonedDateTime.parse("2026-09-08T09:00+02:00[Europe/Paris]"));
    event.setEnd(ZonedDateTime.parse("2026-09-08T10:00+02:00[Europe/Paris]"));
    return event;
  }

  /**
   * The copy says where the meeting came from.
   *
   * <p>
   * A delivered copy was verified to carry SUMMARY and ORGANIZER and no
   * DESCRIPTION at all: nothing saying the event came from eXo, nothing naming
   * the space. The mail has said "Invitation sent by X in space Y" all along,
   * and since EXO-89732 both channels take that sentence from the same builder
   * in agenda — so the copy in the user's own calendar, where the attribution
   * matters most, finally carries it.
   */
  @Test
  public void theCopyIsAttributedToItsSenderAndSpace() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    givenSpaceCalendar("Chemistry");

    IcsEvent ics = mapper.toIcsEvent(event(), "uid-1", PUSHER);

    assertNotNull(ics.getDescription(), "a copy with no description says nothing about where it came from");
    assertTrue(ics.getDescription().contains("John Doe"), "the sender must be named: " + ics.getDescription());
    assertTrue(ics.getDescription().contains("Chemistry"), "the space must be named: " + ics.getDescription());
  }

  /**
   * An event on a personal calendar is attributed to its sender alone.
   *
   * <p>
   * The calendar owner is a user, not a space, so there is no space to name.
   * The clause is dropped rather than written with an empty or null value —
   * "in space null" in somebody's phone calendar being worse than no clause.
   */
  @Test
  public void anEventOnAPersonalCalendarNamesNoSpace() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    givenPersonalCalendar();

    IcsEvent ics = mapper.toIcsEvent(event(), "uid-1", PUSHER);

    assertNotNull(ics.getDescription());
    assertTrue(ics.getDescription().contains("John Doe"), "the sender must still be named: " + ics.getDescription());
    assertFalse(ics.getDescription().contains("null"),
                "an absent space must not be written as the word null: " + ics.getDescription());
  }

  /**
   * The event's own description reaches the copy as text, not as markup.
   *
   * <p>
   * DESCRIPTION is plain text by definition (RFC 5545 3.8.1.5), and since the
   * rendering moved here the mapper is what owes it.
   */
  @Test
  public void theEventOwnDescriptionIsRenderedOutOfItsMarkup() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    givenSpaceCalendar("Chemistry");
    Event event = event();
    event.setDescription("<p>Bring the <b>slides</b>.</p>");

    IcsEvent ics = mapper.toIcsEvent(event, "uid-1", PUSHER);

    assertTrue(ics.getDescription().contains("Bring the slides."),
               "the text must survive: " + ics.getDescription());
    assertFalse(ics.getDescription().contains("<"), "the markup must not: " + ics.getDescription());
  }

  /**
   * Puts the event on a calendar a space owns.
   *
   * @param displayName the space's display name
   */
  private void givenSpaceCalendar(String displayName) {
    Calendar calendar = new Calendar();
    calendar.setId(1L);
    calendar.setOwnerId(SPACE_OWNER);
    lenient().when(agendaCalendarService.getCalendarById(0L)).thenReturn(calendar);

    Identity spaceIdentity = new Identity(SpaceIdentityProvider.NAME, "chemistry");
    spaceIdentity.setId(String.valueOf(SPACE_OWNER));
    lenient().when(identityManager.getIdentity(SPACE_OWNER)).thenReturn(spaceIdentity);

    Space space = new Space();
    space.setDisplayName(displayName);
    space.setPrettyName("chemistry");
    lenient().when(spaceService.getSpaceByPrettyName("chemistry")).thenReturn(space);
  }

  /**
   * Puts the event on a calendar its own user owns, which names no space.
   */
  private void givenPersonalCalendar() {
    Calendar calendar = new Calendar();
    calendar.setId(1L);
    calendar.setOwnerId(PUSHER);
    lenient().when(agendaCalendarService.getCalendarById(0L)).thenReturn(calendar);
  }

  /**
   * @param identityId the identity to resolve
   * @param name its display name
   * @param email its address, or null when the profile hides one
   */
  private void givenIdentity(long identityId, String name, String email) {
    Identity identity = new Identity(String.valueOf(identityId));
    Profile profile = new Profile(identity);
    profile.setProperty(Profile.FULL_NAME, name);
    if (email != null) {
      profile.setProperty(Profile.EMAIL, email);
    }
    identity.setProfile(profile);
    lenient().when(identityManager.getIdentity(identityId)).thenReturn(identity);
  }

  /**
   * @param attendees the roster to answer with
   */
  private void givenAttendees(EventAttendee... attendees) {
    when(agendaEventAttendeeService.getEventAttendees(1L)).thenReturn(new EventAttendeeList(List.of(attendees)));
  }

  /**
   * @param identityId who is expected
   * @param response their answer
   * @return the attendee
   */
  private EventAttendee attendee(long identityId, EventAttendeeResponse response) {
    EventAttendee attendee = new EventAttendee();
    attendee.setIdentityId(identityId);
    attendee.setResponse(response);
    return attendee;
  }

  /**
   * @param url the joining link
   * @return the conference
   */
  private EventConference conference(String url) {
    EventConference conference = new EventConference();
    conference.setUrl(url);
    return conference;
  }

  /**
   * @param before the quantity
   * @param unit what it counts in
   * @return the reminder
   */
  private EventReminder reminder(int before, ReminderPeriodType unit) {
    EventReminder eventReminder = new EventReminder();
    eventReminder.setBefore(before);
    eventReminder.setBeforePeriodType(unit);
    return eventReminder;
  }
}
