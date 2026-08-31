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

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.SyncCollectionResult;
import org.exoplatform.caldav.ics.IcsEventMapper;
import org.exoplatform.caldav.ics.IcsParser;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * <h2>An answer given while adoption was broken still comes home (EXO-89810).</h2>
 *
 * <p>
 * <b>The defect this exists for.</b> EXO-89807 made the inbound pass read the
 * owner's answer off one of eXo's own copies at the moment the collection's
 * sync report names it as changed. That works, and it is forward-looking only:
 * a sync report names an object <i>once</i> — the report consumes the token it
 * was given and hands back a new one — so a change eXo was told about while it
 * could not act on it is never mentioned again. Measured on the rig: BlueMind
 * reported root's ACCEPTED twice, the token moved past both, and deploying the
 * fix afterwards changed nothing at all. Disconnecting and reconnecting did not
 * help either, because reconnecting thaws the paused bindings and therefore
 * their tokens.
 *
 * <p>
 * It is unreachable from the mirror's side too: the verification pass may adopt
 * an answer, but only off a copy whose ETag has moved away from the one eXo
 * recorded, and BlueMind records an answer without moving that value — which is
 * the whole reason EXO-89807 was needed.
 *
 * <p>
 * <b>What heals it, and why nothing new was built.</b> The window is re-read in
 * full once a day, for a reason that predates all of this — a token says what
 * changed, never that a day has slid into range — and that read asks the
 * collection for its objects rather than asking the token what changed. Every
 * object in the window is met again, the copies among them, and the adoption of
 * EXO-89807 sits on that path already. So the heal is the pass that already
 * runs, and the work is to hold it: measured on the rig on 31 August, the daily
 * full read walked all four of the user's mirror copies at 01:04:59, the object
 * carrying the answer among them.
 *
 * <p>
 * <b>What this class pins that no other test does.</b>
 * {@code CaldavInboundServiceTest} holds the adoption against a mocked
 * {@link CaldavAnswerAdoptionService}, and {@code CaldavSyncServiceTest} holds
 * the once-a-day decision against a mocked {@link CaldavInboundService}. Both
 * are the right tests for what they assert and neither can see this: the two
 * halves are composed <i>here</i>, against the real adoption service, so that
 * "the answer is recorded" is asserted where agenda would record it rather than
 * as a call to a mock that would happily accept a NEEDS-ACTION too.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavAnswerHealOnFullReadTest {

  private static final long                   USER     = 7L;

  private static final long                   SERVER   = 3L;

  private static final long                   PAIR     = 11L;

  private static final long                   CALENDAR = 42L;

  /** The agenda event the mirror copy stands for. */
  private static final long                   EVENT    = 777L;

  private static final String                 LOGIN    = "john@example.test";

  private static final String                 HREF     = "/dav/calendars/john/calendar:Default/";

  private static final String                 UID      = "uid-heal@example.test";

  /**
   * The version eXo recorded for the copy, and the one the server still
   * publishes. Identical on purpose: it is the state that closes the mirror
   * verification's gate, so an answer arriving here arrives on the strength of
   * the full read alone.
   */
  private static final String                 ETAG     = "etag-unmoved";

  @Mock
  private CalDavClient                        calDavClient;

  @Mock
  private CaldavSyncStorage                   caldavSyncStorage;

  @Mock
  private CaldavConnectorStorage              caldavConnectorStorage;

  @Mock
  private AgendaEventService                  agendaEventService;

  @Mock
  private AgendaEventAttendeeService          agendaEventAttendeeService;

  @Mock
  private AgendaRemoteEventService            agendaRemoteEventService;

  @Mock
  private IdentityManager                     identityManager;

  @Mock
  private CalDavEndpoint                      endpoint;

  @Spy
  private IcsParser                           icsParser;

  @Spy
  private IcsEventMapper                      icsEventMapper;

  /**
   * The real thing, not a mock. Everything this class is about lives in what
   * it refuses.
   */
  @InjectMocks
  private CaldavAnswerAdoptionService         caldavAnswerAdoptionService;

  @InjectMocks
  private CaldavInboundService                service;

  /**
   * Composes the two real services and connects an account.
   *
   * <p>
   * The adoption service is set by hand because Mockito injects mocks, never
   * another {@code @InjectMocks} instance — and a mock here would defeat the
   * point of the class.
   */
  @BeforeEach
  public void composeTheTwoHalves() {
    ReflectionTestUtils.setField(service, "caldavAnswerAdoptionService", caldavAnswerAdoptionService);
    ReflectionTestUtils.setField(service, "sliceDays", 400L);
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
    lenient().when(identityManager.getIdentity(USER)).thenReturn(owner());
    // The state a user whose answer was reported before the fix is actually
    // in, stated rather than implied: the token is perfectly valid and the
    // server, asked with it, reports nothing at all — the change was reported
    // once, days ago, and the token moved past it. Stubbed even though the
    // full read is not supposed to ask, because an UNSTUBBED sync report
    // answers null, which this service correctly reads as "the report could
    // not be made" and falls back to reading the window in full. That fallback
    // would make a pass that wrongly consulted the token pass this test anyway,
    // for a reason that has nothing to do with the heal.
    lenient().when(calDavClient.syncCollection(any(), anyString(), anyString(), anyString(), anyString()))
             .thenReturn(new SyncCollectionResult(true, "token-fresh", List.of(), List.of()));
    // The reconciliation half of the pass, which has nothing to do with this.
    lenient().when(caldavSyncStorage.getObjects(eq(PAIR), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));
  }

  /**
   * The answer arrives on the daily full read, with no change report at all.
   */
  @Test
  public void anAnswerNoChangeReportWillEverMentionAgainIsStillRecorded() throws Exception {
    // The heal, end to end. The copy's ETag is exactly the one eXo recorded —
    // so the mirror verification would call it untouched and read nothing —
    // and no sync report is asked for, standing in for the report that was
    // spent while adoption was broken. What is left is the window being read
    // in full, which is the once-a-day pass, and the answer arrives on it.
    givenTheCopyCarries("ACCEPTED");
    when(agendaEventAttendeeService.getEventResponse(EVENT, null, USER)).thenReturn(EventAttendeeResponse.NEEDS_ACTION);

    service.syncContents(USER, pair(), calendar(), from(), to(), true);

    verify(agendaEventAttendeeService).sendEventResponse(EVENT, USER, EventAttendeeResponse.ACCEPTED);
    // And not by being told. The copy was met because the window was walked,
    // never because a report named it: nothing was fetched by path, which is
    // the one route EXO-89807 travels and the route that is closed to a user
    // whose answer was reported before the fix shipped. The reconciliation
    // that follows may still spend the token — that is its own job and costs
    // one request — but no object arrived that way.
    verify(calDavClient).calendarQuery(any(), anyString(), any(), any(), anyString(), anyString());
    verify(calDavClient, never()).multiget(any(), anyString(), anyList(), anyString(), anyString());
  }

  /**
   * A copy nobody has answered records nothing, on this path as on every other.
   */
  @Test
  public void aCopyNobodyAnsweredNeverOverwritesTheAnswerAgendaHolds() throws Exception {
    // The refusal the heal must inherit, and the reason it is asserted through
    // the real adoption service rather than against a mock. Every copy eXo
    // pushes carries NEEDS-ACTION until somebody answers it, and the daily
    // full read meets ALL of them — not the handful a report named. A heal
    // that adopted this would walk an account once a day erasing every answer
    // its owner had given in eXo, one copy at a time, which is a far worse
    // defect than the one being fixed.
    givenTheCopyCarries("NEEDS-ACTION");
    // Agenda holds a real answer, given in eXo. It is what the un-answered
    // copy would overwrite.
    lenient().when(agendaEventAttendeeService.getEventResponse(EVENT, null, USER))
             .thenReturn(EventAttendeeResponse.ACCEPTED);

    service.syncContents(USER, pair(), calendar(), from(), to(), true);

    verify(agendaEventAttendeeService, never()).sendEventResponse(anyLong(), anyLong(), any());
  }

  /**
   * Reading the answer off the copy is still not importing the copy.
   */
  @Test
  public void healingAnAnswerStillNeverImportsTheCopyAsAnEvent() throws Exception {
    // EXO-89802's guarantee, held on the healing path too. The full read meets
    // every copy in the window rather than one named object, so if adoption
    // ever became a reason to let a copy through, this pass would hand the
    // user a personal duplicate of every space meeting they have — once a day,
    // and on the pass nobody is watching.
    givenTheCopyCarries("ACCEPTED");
    lenient().when(agendaEventAttendeeService.getEventResponse(EVENT, null, USER))
             .thenReturn(EventAttendeeResponse.NEEDS_ACTION);
    // Agenda ready to create the event, deliberately. Without this stub a pass
    // that DID import the copy would die on the null a mock returns, several
    // lines before reaching the assertion below — and the test would go red
    // for a reason that has nothing to do with what it claims to hold. A
    // refusal is only pinned if the code that ignores it gets far enough to be
    // caught refusing nothing.
    lenient().when(agendaEventService.createEvent(any(), any(), any(), any(), any(), any(), anyBoolean(), anyLong()))
             .thenReturn(event());

    service.syncContents(USER, pair(), calendar(), from(), to(), true);

    verify(agendaEventService, never()).createEvent(any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    anyBoolean(),
                                                    anyLong());
    verify(agendaEventService, never()).updateEvent(any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    any(),
                                                    anyBoolean(),
                                                    anyLong());
  }

  /**
   * States what the account holds: one copy eXo wrote, carrying an answer.
   *
   * @param partStat the participation status on the owner's own line
   */
  private void givenTheCopyCarries(String partStat) {
    when(calDavClient.calendarQuery(any(), anyString(), any(), any(), anyString(), anyString()))
                                                                                               .thenReturn(List.of(new CalendarObject(HREF
                                                                                                   + "copy.ics",
                                                                                                                                      ETAG,
                                                                                                                                      copy(partStat))));
    when(caldavSyncStorage.isMirrorOwned(USER, SERVER, UID)).thenReturn(true);
    when(caldavSyncStorage.getMirrorEventId(USER, SERVER, UID)).thenReturn(EVENT);
    lenient().when(agendaEventService.getEventById(EVENT)).thenReturn(event());
  }

  /**
   * A copy eXo wrote, spelled the way the live server hands it back — an
   * uppercase scheme, the parameters BlueMind attaches, and the address the
   * account answers to.
   *
   * @param partStat the participation status on the owner's line
   * @return the object's iCalendar body
   */
  private String copy(String partStat) {
    return """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//eXo//caldav//EN
        BEGIN:VEVENT
        UID:%s
        DTSTAMP:20261001T080000Z
        DTSTART:20261012T090000Z
        DTEND:20261012T100000Z
        SUMMARY:Sprint review
        ORGANIZER;CN=Space:mailto:space@example.test
        ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=%s;CN=FRANCOIS:MAILTO:%s
        END:VEVENT
        END:VCALENDAR
        """.formatted(UID, partStat, LOGIN);
  }

  /**
   * @return the agenda event the copy stands for, a single meeting
   */
  private Event event() {
    Event event = new Event();
    event.setId(EVENT);
    event.setParentId(0);
    return event;
  }

  /**
   * @return the account's owner, exposing the address their copies name them by
   */
  private Identity owner() {
    Identity identity = new Identity(String.valueOf(USER));
    Profile profile = new Profile(identity);
    profile.setProperty(Profile.EMAIL, LOGIN);
    identity.setProfile(profile);
    return identity;
  }

  /**
   * @return the binding the collection is read through, which is never the
   *         mirror's own
   */
  private CalendarSync pair() {
    CalendarSync pair = new CalendarSync();
    pair.setId(PAIR);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setRemoteHref(HREF);
    pair.setOrigin(SyncOrigin.REMOTE);
    // A token it holds and is not asked with, which is the whole point.
    pair.setSyncToken("token-already-spent");
    return pair;
  }

  /**
   * @return the eXo calendar standing for the collection
   */
  private Calendar calendar() {
    Calendar calendar = new Calendar();
    calendar.setId(CALENDAR);
    return calendar;
  }

  /**
   * @return a connected account
   */
  private CaldavUserSetting settings() {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername(LOGIN);
    setting.setPassword("secret");
    setting.setServerId(SERVER);
    return setting;
  }

  /**
   * @return the window's start
   */
  private Instant from() {
    return Instant.parse("2026-10-01T00:00:00Z");
  }

  /**
   * @return the window's end
   */
  private Instant to() {
    return Instant.parse("2026-11-01T00:00:00Z");
  }
}
