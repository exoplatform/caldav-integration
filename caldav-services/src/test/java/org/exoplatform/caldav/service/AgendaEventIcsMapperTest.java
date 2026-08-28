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
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
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

  /** How eXo names the pusher — what an invitation token carries. */
  private static final String               PUSHER_REMOTE_ID  = "user5";

  /** And how it names the other attendee. */
  private static final String               SOMEONE_REMOTE_ID = "user9";

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

  @Mock
  private CaldavConnectorStorage              caldavConnectorStorage;

  @Mock
  private CaldavServerService                 caldavServerService;

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
   * The description offers the three answers, as links.
   *
   * <p>
   * They exist because some clients will never offer an RSVP control on this
   * calendar: BlueMind's web UI renders one only for the account's default
   * calendar, and a calendar created over CalDAV is never default (EXO-89753).
   */
  @Test
  public void theDescriptionOffersTheThreeAnswerLinks() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");

    try (MockedStatic<NotificationUtils> agendaLinks = mockStatic(NotificationUtils.class)) {
      agendaLinks.when(() -> NotificationUtils.getEventURL(1L)).thenReturn(EVENT_LINK);
      givenAnswerLinks(agendaLinks, 1L, PUSHER_REMOTE_ID, "pusher");

      String description = mapper.toIcsEvent(event(), "uid-1", PUSHER).getDescription();

      assertTrue(description.contains(answerLink(1L, "pusher", EventAttendeeResponse.ACCEPTED)),
                 "accept must be offered: " + description);
      assertTrue(description.contains(answerLink(1L, "pusher", EventAttendeeResponse.TENTATIVE)),
                 "tentative must be offered: " + description);
      assertTrue(description.contains(answerLink(1L, "pusher", EventAttendeeResponse.DECLINED)),
                 "decline must be offered: " + description);
    }
  }

  /**
   * <b>One attendee's token never reaches another attendee's copy.</b>
   *
   * <p>
   * The token in these links answers <i>as</i> the person named in it, so a
   * copy carrying somebody else's would hand over the ability to answer for
   * them - a defect strictly worse than not having the feature at all. The
   * mapper takes the recipient as an argument and the fan-out over attendees
   * happens above it, one full mapping pass per holder, so the two renders
   * below must disagree in exactly this way: each carries its own recipient's
   * links and none of the other's.
   */
  @Test
  public void aCopyCarriesOnlyItsOwnRecipientSAnswerLinks() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    givenIdentity(SOMEONE, "Jane Roe", "jane@example.test");
    lenient().when(agendaEventReminderService.getEventReminders(1L, SOMEONE)).thenReturn(List.of());

    try (MockedStatic<NotificationUtils> agendaLinks = mockStatic(NotificationUtils.class)) {
      agendaLinks.when(() -> NotificationUtils.getEventURL(1L)).thenReturn(EVENT_LINK);
      givenAnswerLinks(agendaLinks, 1L, PUSHER_REMOTE_ID, "pusher");
      givenAnswerLinks(agendaLinks, 1L, SOMEONE_REMOTE_ID, "someone");

      String forPusher = mapper.toIcsEvent(event(), "uid-1", PUSHER).getDescription();
      String forSomeone = mapper.toIcsEvent(event(), "uid-1", SOMEONE).getDescription();

      assertTrue(forPusher.contains(answerLink(1L, "pusher", EventAttendeeResponse.ACCEPTED)),
                 "the copy must carry its own recipient's link: " + forPusher);
      assertFalse(forPusher.contains("token=someone"),
                  "and must not carry the other attendee's token: " + forPusher);

      assertTrue(forSomeone.contains(answerLink(1L, "someone", EventAttendeeResponse.ACCEPTED)),
                 "the other copy must carry the other recipient's link: " + forSomeone);
      assertFalse(forSomeone.contains("token=pusher"),
                  "and must not carry the first attendee's token: " + forSomeone);
    }
  }

  /**
   * Two renders of the same copy are byte-identical.
   *
   * <p>
   * <b>The churn guard, and the highest-risk interaction in this change.</b>
   * The mirror compares DESCRIPTION and rewrites any copy whose description
   * differs, so a description that varied between renders would put every copy
   * into permanent churn on every five-minute sweep - which is precisely what
   * EXO-89716 was spent eliminating. It holds because the token is
   * {@code encode(eventId | user | response | expiry)} and, since EXO-89752,
   * the expiry is derived from the event rather than from the clock.
   */
  @Test
  public void twoRendersOfTheSameCopyProduceTheVerySameDescription() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");

    try (MockedStatic<NotificationUtils> agendaLinks = mockStatic(NotificationUtils.class)) {
      agendaLinks.when(() -> NotificationUtils.getEventURL(1L)).thenReturn(EVENT_LINK);
      givenAnswerLinks(agendaLinks, 1L, PUSHER_REMOTE_ID, "pusher");

      String pushed = mapper.toIcsEvent(event(), "uid-1", PUSHER).getDescription();
      String sweptAgain = mapper.toIcsEvent(event(), "uid-1", PUSHER).getDescription();

      assertEquals(pushed, sweptAgain, "a sweep must render the very same description, or every copy churns");
    }
  }

  /**
   * An override answers for its series, exactly as its link opens the series.
   *
   * <p>
   * A client showing no RSVP control offers no way to say "this occurrence
   * only", so a click on the copy of an override can only sensibly mean the
   * meeting as a whole - the same reading the UID and the link already take.
   */
  @Test
  public void anOverrideOffersAnswersForItsSeries() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    Event override = event();
    override.setParentId(77L);

    try (MockedStatic<NotificationUtils> agendaLinks = mockStatic(NotificationUtils.class)) {
      agendaLinks.when(() -> NotificationUtils.getEventURL(77L)).thenReturn(SERIES_LINK);
      givenAnswerLinks(agendaLinks, 77L, PUSHER_REMOTE_ID, "pusher");

      String description = mapper.toIcsEvent(override, "uid-1", PUSHER).getDescription();

      assertTrue(description.contains(answerLink(77L, "pusher", EventAttendeeResponse.ACCEPTED)),
                 "the override must offer answers for the series: " + description);
    }
  }

  /**
   * A link that could carry no token is not offered at all.
   *
   * <p>
   * {@code getResponseURL} substitutes an empty token rather than failing, so
   * without this the copy would show an Accept link that answers nothing and
   * reports nothing. Reachable since EXO-89752: no token is minted for an event
   * carrying no date to bound it by.
   */
  @Test
  public void anAnswerLinkWithNoTokenIsNotOffered() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");

    try (MockedStatic<NotificationUtils> agendaLinks = mockStatic(NotificationUtils.class)) {
      agendaLinks.when(() -> NotificationUtils.getEventURL(1L)).thenReturn(EVENT_LINK);
      for (EventAttendeeResponse response : List.of(EventAttendeeResponse.ACCEPTED,
                                                    EventAttendeeResponse.TENTATIVE,
                                                    EventAttendeeResponse.DECLINED)) {
        agendaLinks.when(() -> NotificationUtils.getResponseURL(agendaEventAttendeeService,
                                                                1L,
                                                                PUSHER_REMOTE_ID,
                                                                response))
                   .thenReturn("http://localhost:8080/portal/rest/v1/agenda/events/1/response/send?response="
                       + response.name() + "&token=&redirect=true");
      }

      String description = mapper.toIcsEvent(event(), "uid-1", PUSHER).getDescription();

      assertFalse(description.contains("response/send"), "a tokenless link must not be offered: " + description);
    }
  }

  /**
   * The answer a person has already given is never written into the
   * description.
   *
   * <p>
   * Actions in the description, state in PARTSTAT. The copy is rewritten within
   * seconds of a click, but the user's client only sees that at its own
   * refresh, so a description repeating the answer would read as the click
   * having failed and invite a second one.
   */
  @Test
  public void theDescriptionStatesNoAnswerOnlyOffersThem() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");

    try (MockedStatic<NotificationUtils> agendaLinks = mockStatic(NotificationUtils.class)) {
      agendaLinks.when(() -> NotificationUtils.getEventURL(1L)).thenReturn(EVENT_LINK);
      givenAnswerLinks(agendaLinks, 1L, PUSHER_REMOTE_ID, "pusher");

      // Asserted as an invariance rather than by looking for words: the links
      // themselves legitimately carry "response=ACCEPTED" in their query
      // string, so the property that actually matters is that the answer on
      // record makes no difference to the text.
      lenient().when(agendaEventAttendeeService.getEventAttendees(1L))
               .thenReturn(new EventAttendeeList(List.of(new EventAttendee(1L, 1L, PUSHER, EventAttendeeResponse.NEEDS_ACTION))));
      String beforeAnswering = mapper.toIcsEvent(event(), "uid-1", PUSHER).getDescription();

      lenient().when(agendaEventAttendeeService.getEventAttendees(1L))
               .thenReturn(new EventAttendeeList(List.of(new EventAttendee(1L, 1L, PUSHER, EventAttendeeResponse.ACCEPTED))));
      IcsEvent afterAccepting = mapper.toIcsEvent(event(), "uid-1", PUSHER);

      assertEquals(beforeAnswering,
                   afterAccepting.getDescription(),
                   "answering must not change the description - the answer lives in PARTSTAT");
      assertEquals(EventAttendeeResponse.ACCEPTED.name(),
                   afterAccepting.getAttendees().get(0).getResponse(),
                   "and it must be on the attendee line, which is where a client reads it");
    }
  }

  /**
   * Stubs the three answer links agenda would mint for one attendee.
   *
   * @param agendaLinks the static mock standing in for agenda's link builder
   * @param eventId the event the answers apply to
   * @param remoteId the attendee the token names
   * @param tokenMarker a token value unique to that attendee, so a link written
   *          into the wrong copy is visible
   */
  private void givenAnswerLinks(MockedStatic<NotificationUtils> agendaLinks,
                                long eventId,
                                String remoteId,
                                String tokenMarker) {
    for (EventAttendeeResponse response : List.of(EventAttendeeResponse.ACCEPTED,
                                                  EventAttendeeResponse.TENTATIVE,
                                                  EventAttendeeResponse.DECLINED)) {
      agendaLinks.when(() -> NotificationUtils.getResponseURL(agendaEventAttendeeService, eventId, remoteId, response))
                 .thenReturn(answerLink(eventId, tokenMarker, response));
    }
  }

  /**
   * The shape agenda's link builder returns, with a token naming one attendee.
   *
   * @param eventId the event the link answers for
   * @param tokenMarker the stand-in token value
   * @param response the answer the link records
   * @return the absolute address
   */
  private String answerLink(long eventId, String tokenMarker, EventAttendeeResponse response) {
    return "http://localhost:8080/portal/rest/v1/agenda/events/" + eventId + "/response/send?response=" + response.name()
        + "&token=" + tokenMarker + "&redirect=true";
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
    // The invitation token names an attendee by their remote id — a username
    // for an internal user, a mail address for a guest — so the copy's answer
    // links cannot be built without one (EXO-89753).
    identity.setRemoteId("user" + identityId);
    Profile profile = new Profile(identity);
    profile.setProperty(Profile.FULL_NAME, name);
    if (email != null) {
      profile.setProperty(Profile.EMAIL, email);
    }
    identity.setProfile(profile);
    lenient().when(identityManager.getIdentity(identityId)).thenReturn(identity);
  }

  /**
   * <b>An administrator can turn the answer links off, per server.</b>
   *
   * <p>
   * The gate lives in {@code rsvpLinks} and nowhere else, so it applies to
   * every render a copy can get — the browser push, the background sweep's
   * repair and the comparison baseline the mirror judges against — and those
   * three cannot come to different conclusions about one server (EXO-89757).
   *
   * <p>
   * The description is still asserted to carry the event link, so a passing
   * test means the text was really composed and the links were really left
   * out of it, rather than the whole description having failed to build.
   */
  @Test
  public void noAnswerLinkIsWrittenWhenTheServerWasToldToOfferNone() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    givenServer(7L, false);

    try (MockedStatic<NotificationUtils> agendaLinks = mockStatic(NotificationUtils.class)) {
      agendaLinks.when(() -> NotificationUtils.getEventURL(1L)).thenReturn(EVENT_LINK);
      givenAnswerLinks(agendaLinks, 1L, PUSHER_REMOTE_ID, "pusher");

      String description = mapper.toIcsEvent(event(), "uid-1", PUSHER).getDescription();

      assertFalse(description.contains("response/send"),
                  "the server was told to offer no answers: " + description);
      assertTrue(description.contains(EVENT_LINK),
                 "and the rest of the description must still be there: " + description);
    }
  }

  /**
   * The same server with the switch on writes them, which is the shipped
   * default and today's behaviour.
   */
  @Test
  public void theAnswerLinksAreWrittenWhenTheServerWantsThem() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    givenServer(7L, true);

    try (MockedStatic<NotificationUtils> agendaLinks = mockStatic(NotificationUtils.class)) {
      agendaLinks.when(() -> NotificationUtils.getEventURL(1L)).thenReturn(EVENT_LINK);
      givenAnswerLinks(agendaLinks, 1L, PUSHER_REMOTE_ID, "pusher");

      String description = mapper.toIcsEvent(event(), "uid-1", PUSHER).getDescription();

      assertTrue(description.contains(answerLink(1L, "pusher", EventAttendeeResponse.ACCEPTED)),
                 "accept must be offered: " + description);
    }
  }

  /**
   * <b>A registry that answers nothing keeps the links.</b>
   *
   * <p>
   * The failure modes are asymmetric, and the guard is written to fail toward
   * the default: a redundant Accept link beside a client's own button is a
   * mild annoyance, while a copy silently stripped of its links leaves a user
   * with no way to answer at all. So a deleted registration, an unstored
   * account, and a registry that throws all read as "on".
   */
  @Test
  public void theLinksSurviveARegistryThatAnswersNothing() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    CaldavUserSetting account = new CaldavUserSetting();
    account.setServerId(7L);
    lenient().when(caldavConnectorStorage.getCaldavSetting(PUSHER)).thenReturn(account);
    lenient().when(caldavServerService.resolveServer(7L)).thenReturn(null);

    try (MockedStatic<NotificationUtils> agendaLinks = mockStatic(NotificationUtils.class)) {
      agendaLinks.when(() -> NotificationUtils.getEventURL(1L)).thenReturn(EVENT_LINK);
      givenAnswerLinks(agendaLinks, 1L, PUSHER_REMOTE_ID, "pusher");

      String description = mapper.toIcsEvent(event(), "uid-1", PUSHER).getDescription();

      assertTrue(description.contains(answerLink(1L, "pusher", EventAttendeeResponse.ACCEPTED)),
                 "an unresolvable server must keep the links: " + description);
    }
  }

  /**
   * And a registry that fails outright keeps them too — whether a copy offers
   * an answer must never turn on whether a lookup succeeded.
   *
   * <p>
   * The failure is injected into the registry read, which is the lookup
   * EXO-89757 added and the only one this guard owns. The account read beside
   * it is deliberately left alone: it was already unguarded before this
   * change, and pretending otherwise here would pin a property the code does
   * not have.
   */
  @Test
  public void theLinksSurviveARegistryThatFails() {
    givenIdentity(PUSHER, "John Doe", "john@example.test");
    CaldavUserSetting account = new CaldavUserSetting();
    account.setServerId(7L);
    lenient().when(caldavConnectorStorage.getCaldavSetting(PUSHER)).thenReturn(account);
    lenient().when(caldavServerService.resolveServer(7L))
             .thenThrow(new IllegalStateException("the registry is not readable here"));

    try (MockedStatic<NotificationUtils> agendaLinks = mockStatic(NotificationUtils.class)) {
      agendaLinks.when(() -> NotificationUtils.getEventURL(1L)).thenReturn(EVENT_LINK);
      givenAnswerLinks(agendaLinks, 1L, PUSHER_REMOTE_ID, "pusher");

      String description = mapper.toIcsEvent(event(), "uid-1", PUSHER).getDescription();

      assertTrue(description.contains(answerLink(1L, "pusher", EventAttendeeResponse.ACCEPTED)),
                 "a failing registry must keep the links: " + description);
    }
  }

  /**
   * Stubs the registration the pusher's stored account resolves to.
   *
   * @param serverId identifier the account references
   * @param answerLinksInCopy whether that registration wants the answer links
   *          written into its copies
   */
  private void givenServer(long serverId, boolean answerLinksInCopy) {
    CaldavUserSetting account = new CaldavUserSetting();
    account.setServerId(serverId);
    lenient().when(caldavConnectorStorage.getCaldavSetting(PUSHER)).thenReturn(account);
    CaldavServer server = new CaldavServer();
    server.setId(serverId);
    server.setAnswerLinksInCopy(answerLinksInCopy);
    lenient().when(caldavServerService.resolveServer(serverId)).thenReturn(server);
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
