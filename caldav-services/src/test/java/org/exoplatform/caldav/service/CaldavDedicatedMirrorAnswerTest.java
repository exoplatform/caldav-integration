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
package org.exoplatform.caldav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.SyncCollectionResult;
import org.exoplatform.caldav.ics.IcsEventMapper;
import org.exoplatform.caldav.ics.IcsParser;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * <h2>An answer given on a copy in the default destination reaches eXo, and
 * only an answer does (EXO-89814).</h2>
 *
 * <p>
 * <b>What this class pins that no other one does.</b>
 * {@code CaldavMirrorAnswerServiceTest} holds the new pass against a
 * <i>mocked</i> {@link CaldavAnswerAdoptionService}, which is the right test
 * for when the pass asks and how much it reads — and is exactly the test that
 * cannot see the two things that matter most here. A mock adoption service
 * accepts a NEEDS-ACTION copy as readily as a real answer, and it never touches
 * agenda at all, so neither the refusal nor the "no event is ever created" line
 * is visible through it. The two services are therefore composed here, against
 * the real adoption, and the assertions are made where agenda would record
 * something.
 *
 * <p>
 * <b>Why the NEEDS-ACTION refusal is load-bearing on this path in
 * particular.</b> Every copy eXo pushes into the mirror carries NEEDS-ACTION
 * until somebody answers it, and this pass meets those copies — the collection's
 * sync report names eXo's own writes as changes just as it names a client's. A
 * pass that adopted NEEDS-ACTION would therefore walk an account erasing every
 * answer its owner had given in eXo, one sweep after each push.
 *
 * <p>
 * <b>And why "no duplicate events" is asserted rather than assumed.</b> The
 * dedicated collection is excluded from materialisation and from the inbound
 * loop for a real reason: it is eXo's own projection, and importing it would
 * show the user a second, personal copy of every space meeting they already
 * have. This pass reads that collection. What keeps the exclusion intact is
 * that it reads one field and nothing else — no calendar is created, no event
 * is saved, no occurrence is materialised — and that is what is pinned below.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavDedicatedMirrorAnswerTest {

  private static final long                USER       = 13L;

  private static final long                SERVER     = 8L;

  private static final long                PAIR       = 195L;

  /** The meeting the answered copy stands for. */
  private static final long                ANSWERED   = 1014L;

  /** The meeting beside it that nobody has answered yet. */
  private static final long                UNANSWERED = 1013L;

  private static final String              LOGIN      = "alice@stalwart.local";

  private static final String              COLLECTION = "/dav/cal/alice@stalwart.local/exo-meetings";

  private static final String              ANSWERED_HREF = COLLECTION + "/40fa2ac7.ics";

  private static final String              FRESH_HREF    = COLLECTION + "/f879f6c0.ics";

  @Mock
  private CalDavClient                     calDavClient;

  @Mock
  private CaldavSyncStorage                caldavSyncStorage;

  @Mock
  private CaldavConnectorStorage           caldavConnectorStorage;

  @Mock
  private IdentityManager                  identityManager;

  @Mock
  private AgendaEventService               agendaEventService;

  @Mock
  private AgendaEventAttendeeService       agendaEventAttendeeService;

  /**
   * Never used by this path, and that is the assertion: creating a calendar
   * for the dedicated collection is the duplicate-calendar failure the
   * exclusion exists to prevent.
   */
  @Mock
  private AgendaCalendarService            agendaCalendarService;

  @Mock
  private CalDavEndpoint                   endpoint;

  @Spy
  private IcsParser                        icsParser;

  @Spy
  private IcsEventMapper                   icsEventMapper;

  /** The real thing, not a mock. Everything this class is about lives in what it refuses. */
  @InjectMocks
  private CaldavAnswerAdoptionService      caldavAnswerAdoptionService;

  @InjectMocks
  private CaldavMirrorAnswerService        service;

  /**
   * Composes the two real services over one account whose copies land in the
   * dedicated collection, with two of them reported changed: one carrying the
   * owner's TENTATIVE, one carrying the NEEDS-ACTION eXo wrote.
   *
   * <p>
   * The adoption service is set by hand because Mockito injects mocks, never
   * another {@code @InjectMocks} instance — and a mock here would defeat the
   * point of the class.
   */
  @BeforeEach
  public void composeTheTwoHalves() {
    ReflectionTestUtils.setField(service, "caldavAnswerAdoptionService", caldavAnswerAdoptionService);
    ReflectionTestUtils.setField(service, "maxObjectsPerPass", 100);
    lenient().when(identityManager.getIdentity(USER)).thenReturn(owner());
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
    lenient().when(caldavSyncStorage.getPair(PAIR)).thenReturn(mirror());
    // No binding reads exo-meetings: the account's own calendar is elsewhere.
    // This IS the DEDICATED_CALENDAR shape, stated as the pass reads it.
    lenient().when(caldavSyncStorage.getPairs(USER, SERVER))
             .thenReturn(List.of(pair(194L, SyncOrigin.REMOTE, "/dav/cal/alice@stalwart.local/default"),
                                 mirror()));
    lenient().when(caldavSyncStorage.getObjects(eq(PAIR), anyInt(), anyInt()))
             .thenAnswer(invocation -> invocation.getArgument(1, Integer.class) == 0
                                                                                     ? new PageImpl<>(List.of(mapping(ANSWERED_HREF,
                                                                                                                      ANSWERED),
                                                                                                              mapping(FRESH_HREF,
                                                                                                                      UNANSWERED)))
                                                                                     : new PageImpl<>(List.of()));
    lenient().when(agendaEventService.getEventById(ANSWERED)).thenReturn(event(ANSWERED));
    lenient().when(agendaEventService.getEventById(UNANSWERED)).thenReturn(event(UNANSWERED));
    when(calDavClient.syncCollection(endpoint, COLLECTION + "/", "token-a"))
                                                                                            .thenReturn(new SyncCollectionResult(true,
                                                                                                                                 "token-b",
                                                                                                                                 List.of(named(ANSWERED_HREF),
                                                                                                                                         named(FRESH_HREF)),
                                                                                                                                 List.of()));
    when(calDavClient.multiget(any(), anyString(), anyList()))
                                                                                       .thenReturn(List.of(copy(ANSWERED_HREF,
                                                                                                                "TENTATIVE"),
                                                                                                           copy(FRESH_HREF,
                                                                                                                "NEEDS-ACTION")));
  }

  @Test
  public void theAnswerOnTheCopyIsRecordedInAgenda() throws Exception {
    // The defect, stated end to end and against the real adoption: on the
    // default destination this is the only pass that ever meets this copy.
    when(agendaEventAttendeeService.getEventResponse(ANSWERED, null, USER)).thenReturn(EventAttendeeResponse.NEEDS_ACTION);

    assertEquals(1, service.readAnswers(USER, LOGIN, settings(), mirror()));

    verify(agendaEventAttendeeService).sendEventResponse(ANSWERED, USER, EventAttendeeResponse.TENTATIVE);
  }

  @Test
  public void theUnansweredCopyBesideItRecordsNothing() throws Exception {
    // The copy eXo itself wrote a moment ago, which this pass meets on every
    // sweep that follows a push. Adopting its NEEDS-ACTION would erase the
    // answer the user gave in eXo — one account walked, one answer lost per
    // meeting, once a sweep.
    lenient().when(agendaEventAttendeeService.getEventResponse(ANSWERED, null, USER))
             .thenReturn(EventAttendeeResponse.NEEDS_ACTION);

    service.readAnswers(USER, LOGIN, settings(), mirror());

    verify(agendaEventAttendeeService, never()).sendEventResponse(eq(UNANSWERED), anyLong(), any());
  }

  @Test
  public void neitherCopyBecomesAnEventOfItsOwn() throws Exception {
    // The line the exclusion draws, and the reason it was drawn: the dedicated
    // collection holds copies of meetings agenda already has. Reading answers
    // off them must create no calendar, no event and no occurrence — or the
    // user sees every space meeting twice, which is what excluding the
    // collection prevents and what this pass must not undo by reading it.
    when(agendaEventAttendeeService.getEventResponse(ANSWERED, null, USER)).thenReturn(EventAttendeeResponse.NEEDS_ACTION);

    service.readAnswers(USER, LOGIN, settings(), mirror());

    verify(agendaCalendarService, never()).createCalendar(any(), anyString());
    verify(agendaEventService, never()).createEvent(any(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), anyLong());
    verify(agendaEventService, never()).saveEventExceptionalOccurrence(anyLong(), any());
    verify(agendaEventService, never()).updateEvent(any(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), anyLong());
  }

  /**
   * @param href the copy's path
   * @param partstat the owner's participation status on it
   * @return the copy as the server holds it
   */
  private CalendarObject copy(String href, String partstat) {
    return new CalendarObject(href,
                              "\"unmoved\"",
                              "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:" + href + "\r\n"
                                  + "DTSTART:20260901T100000Z\r\nDTEND:20260901T110000Z\r\n"
                                  + "ATTENDEE;PARTSTAT=" + partstat + ":mailto:" + LOGIN + "\r\n"
                                  + "END:VEVENT\r\nEND:VCALENDAR\r\n");
  }

  /**
   * @param href the path the report names
   * @return the object as a report carries it: a path and a version, no body
   */
  private CalendarObject named(String href) {
    return new CalendarObject(href, "\"unmoved\"", null);
  }

  /**
   * @param href the copy's path
   * @param eventId the agenda event it stands for
   * @return the mirror's mapping row for it
   */
  private ObjectSync mapping(String href, long eventId) {
    ObjectSync object = new ObjectSync();
    object.setId(eventId);
    object.setCalendarSyncId(PAIR);
    object.setLocalEventId(eventId);
    object.setIcsUid(href);
    object.setRemoteHref(href);
    return object;
  }

  /**
   * @param id the agenda identifier
   * @return a single, non-recurring event
   */
  private Event event(long id) {
    Event event = new Event();
    event.setId(id);
    event.setParentId(0);
    return event;
  }

  /**
   * @return the binding recording where the copies are written
   */
  private CalendarSync mirror() {
    CalendarSync pair = pair(PAIR, SyncOrigin.MIRROR, COLLECTION);
    pair.setSyncToken("token-a");
    return pair;
  }

  /**
   * @param id technical identifier
   * @param origin what the pair stands for
   * @param href the collection it names
   * @return the pair
   */
  private CalendarSync pair(long id, SyncOrigin origin, String href) {
    CalendarSync pair = new CalendarSync();
    pair.setId(id);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setOrigin(origin);
    pair.setRemoteHref(href);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  /**
   * @return the connected account
   */
  private CaldavUserSetting settings() {
    CaldavUserSetting settings = new CaldavUserSetting();
    settings.setServerId(SERVER);
    settings.setUsername(LOGIN);
    settings.setPassword("secret");
    return settings;
  }

  /**
   * @return the account owner, named by the address their copies name them by
   */
  private Identity owner() {
    Identity identity = new Identity(String.valueOf(USER));
    Profile profile = new Profile(identity);
    profile.setProperty(Profile.EMAIL, LOGIN);
    identity.setProfile(profile);
    return identity;
  }
}
