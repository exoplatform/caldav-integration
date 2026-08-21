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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.RemoteEvent;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.MkCalendarResult;
import org.exoplatform.caldav.client.PutResult;
import org.exoplatform.caldav.ics.IcsMerger;
import org.exoplatform.caldav.ics.IcsWriter;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * What moving the push server-side is actually for.
 *
 * <p>
 * The load-bearing test here is {@link #aCreatedMirrorIsProvenByReadingBack()}
 * — that a mirror calendar reported as created really exists. Nobody could
 * write that assertion while the work happened in a page, and its absence is
 * how a server answering 201 while creating nothing went undiagnosed for
 * three rounds. The rest pins the failure codes the browser still renders, and
 * the conditional-write discipline that turns a concurrent edit into something
 * the caller can act on instead of a silent overwrite.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavPushServiceTest {

  private static final long          USER   = 42L;

  private static final long          SERVER = 7L;

  private static final String        HOME   = "/dav/calendars/john/";

  private static final String        MIRROR = "/dav/calendars/john/exo-meetings/";

  /** The same collection as the storage records it: no trailing slash. */
  private static final String        CANONICAL_MIRROR = "/dav/calendars/john/exo-meetings";

  @Mock
  private CalDavClient               calDavClient;

  @Mock
  private CaldavConnectorStorage     caldavConnectorStorage;

  @Mock
  private CaldavSyncStorage          caldavSyncStorage;

  @Mock
  private IcsWriter                  icsWriter;

  @Mock
  private IcsMerger                  icsMerger;

  @Mock
  private AgendaEventService         agendaEventService;

  @Mock
  private AgendaEventIcsMapper       agendaEventIcsMapper;

  @Mock
  private AgendaRemoteEventService   agendaRemoteEventService;

  @Mock
  private CalDavEndpoint             endpoint;

  @InjectMocks
  private CaldavPushService          service;

  @BeforeEach
  public void connectAnAccount() {
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, "john")).thenReturn(endpoint);
    lenient().when(calDavClient.discoverCalendarHome(any(), anyString(), anyString())).thenReturn(HOME);
    lenient().when(icsWriter.write(any())).thenReturn("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");
  }

  @Test
  public void anExistingMirrorIsReusedRatherThanRecreated() {
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(calendar(MIRROR,
                                                                                                            "eXo Meetings")));

    MirrorTarget target = service.ensureMirror(USER);

    assertEquals(MIRROR, target.href());
    assertFalse(target.adopted());
    // Asking twice for the same calendar must ask for the same collection: a
    // second MKCALENDAR is how a reconnecting user collected a new calendar
    // on the server every time.
    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString());
  }

  @Test
  public void aCreatedMirrorIsProvenByReadingBack() {
    // The assertion this whole PR exists to make possible.
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(),
                                                                                          List.of(calendar(MIRROR,
                                                                                                           "eXo Meetings")));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    MirrorTarget target = service.ensureMirror(USER);

    assertEquals(MIRROR, target.href());
    assertFalse(target.adopted());
    verify(caldavConnectorStorage).saveMirrorCalendarHref(MIRROR, USER);
  }

  @Test
  public void aTwoZeroOneThatCreatedNothingIsNotSuccess() {
    // BlueMind answers 201 while creating nothing when a request omits the
    // supported component set. Believing the status cost three rounds of
    // wrong diagnosis; the read-back is what decides.
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString()))
                                                                              .thenReturn(List.of(calendar("/dav/calendars/john/personal/",
                                                                                                           "Personal")));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    MirrorTarget target = service.ensureMirror(USER);

    // Not reported as created — adopted instead, and said so.
    assertTrue(target.adopted());
    assertEquals("/dav/calendars/john/personal/", target.href());
    assertEquals("Personal", target.name());
  }

  @Test
  public void anAccountWithNoCalendarAtAllIsARefusal() {
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of());
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(403,
                                                                                                                                   List.of()));

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.ensureMirror(USER));

    assertEquals(CaldavPushService.CREATION_REFUSED, failure.getCode());
  }

  @Test
  public void aFirstPushIsConditionalOnTheObjectNotExisting() {
    givenAMirror();
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"etag-1\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ObjectSync mapping = service.pushEvent(USER, event("evt-1"));

    ArgumentCaptor<String> href = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).putObject(any(), href.capture(), anyString(), anyString(), anyString());
    // The filename convention the browser push has always used, so objects
    // written before the migration are found rather than duplicated.
    assertEquals(MIRROR + "evt-1.ics", href.getValue());
    assertEquals("\"etag-1\"", mapping.getEtag());
    assertNotNull(mapping.getPushedHash());
    verify(calDavClient, never()).updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  public void aLaterPushMergesIntoWhatTheServerHolds() {
    givenAMirror();
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("evt-1"))).thenReturn(mapped("\"etag-1\""));
    when(calDavClient.fetchObject(any(), anyString(), anyString(), anyString()))
                                                                                .thenReturn(new CalendarObject(MIRROR + "evt-1.ics",
                                                                                                               "\"etag-1\"",
                                                                                                               "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"));
    when(icsMerger.merge(anyString(), anyString(), eq(false))).thenReturn("MERGED");
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                           .thenReturn(new PutResult(204,
                                                                                                                                     "\"etag-2\"",
                                                                                                                                     null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ObjectSync mapping = service.pushEvent(USER, event("evt-1"));

    // Merged, never replaced: another client's overrides live in the same
    // object, and a wholesale replacement would destroy them.
    verify(calDavClient).updateObject(any(), anyString(), eq("MERGED"), eq("\"etag-1\""), anyString(), anyString());
    assertEquals("\"etag-2\"", mapping.getEtag());
  }

  @Test
  public void aConcurrentEditSurfacesAsAConflict() {
    givenAMirror();
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(412,
                                                                                                                    null,
                                                                                                                    null));

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.pushEvent(USER, event("evt-1")));

    assertEquals(CaldavPushService.CONFLICT, failure.getCode());
    // Never retried blindly — deciding what to do about a concurrent edit is
    // the caller's, and that is the whole point of the conditional write.
    verify(caldavSyncStorage, never()).saveObject(any());
  }

  @Test
  public void rejectedCredentialsKeepTheirOwnCode() {
    givenAMirror();
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenThrow(new CalDavAuthenticationException("refused"));

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.pushEvent(USER, event("evt-1")));

    assertEquals(CaldavPushService.CREDENTIALS, failure.getCode());
  }

  @Test
  public void anAccountThatIsNotConnectedCannotBePushedTo() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.pushEvent(USER, event("evt-1")));

    assertEquals(CaldavPushService.NOT_CONNECTED, failure.getCode());
  }

  @Test
  public void deletingSomethingThatIsAlreadyGoneSucceeds() {
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("evt-gone"))).thenReturn(null);

    service.deleteEvent(USER, "evt-gone");

    // The end state the caller asked for is the end state that holds.
    verify(calDavClient, never()).deleteObject(any(), anyString(), any(), anyString(), anyString());
  }

  @Test
  public void anEventAgendaAlreadyPushedKeepsItsIdentifier() throws Exception {
    // Migrated users are exactly the ones with events already on the server,
    // so minting a fresh UID here would give every one of them a duplicate.
    givenAMirror();
    givenAnAgendaEvent(101L, 0L);
    RemoteEvent known = new RemoteEvent();
    known.setRemoteId("uuid-written-by-the-browser");
    when(agendaRemoteEventService.findRemoteEvent(101L, USER)).thenReturn(known);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("uuid-written-by-the-browser"));
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"e\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.pushAgendaEvent(USER, 101L, "https://exo.test/event/101");

    ArgumentCaptor<String> uid = ArgumentCaptor.forClass(String.class);
    verify(agendaEventIcsMapper).toIcsEvent(any(), uid.capture(), any(), anyLong());
    assertEquals("uuid-written-by-the-browser", uid.getValue());
    verify(agendaRemoteEventService, never()).saveRemoteEvent(anyLong(), any(), anyLong());
  }

  @Test
  public void aFirstPushRecordsItsIdentifierBeforeWriting() throws Exception {
    givenAMirror();
    givenAnAgendaEvent(102L, 0L);
    when(agendaRemoteEventService.findRemoteEvent(102L, USER)).thenReturn(null);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("minted"));
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"e\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.pushAgendaEvent(USER, 102L, "https://exo.test/event/102");

    // Recorded before the write: an interrupted push leaves an identifier
    // pointing at an object that may not exist, which the next push
    // reconciles. The reverse leaves an object nothing points at.
    verify(agendaRemoteEventService).saveRemoteEvent(eq(102L), any(), eq(USER));
  }

  @Test
  public void anOccurrenceSharesItsSeriesIdentifier() throws Exception {
    givenAMirror();
    givenAnAgendaEvent(103L, 99L);
    RemoteEvent known = new RemoteEvent();
    known.setRemoteId("series-uid");
    when(agendaRemoteEventService.findRemoteEvent(99L, USER)).thenReturn(known);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("series-uid"));
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"e\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.pushAgendaEvent(USER, 103L, null);

    // A series and its overrides live in one object, so they share one UID —
    // an override under its own UID would appear as a second meeting.
    verify(agendaRemoteEventService).findRemoteEvent(99L, USER);
  }

  @Test
  public void theMappingRecordsWhichExoEventItStandsFor() throws Exception {
    // Left null, nothing could ever go from an eXo event back to its remote
    // object — which is what deletion detection and mirror verification both
    // start from. A live push wrote a NULL here before this was fixed, and no
    // unit test noticed because none of them looked at the column.
    givenAMirror();
    givenAnAgendaEvent(104L, 0L);
    when(agendaRemoteEventService.findRemoteEvent(104L, USER)).thenReturn(null);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("uid-104"));
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"e\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ObjectSync mapping = service.pushAgendaEvent(USER, 104L, null);

    assertEquals(104L, mapping.getLocalEventId());
  }

  @Test
  public void aPushThatDoesNotKnowTheEventIdKeepsTheOneAlreadyRecorded() {
    // A sweep or a repair pushes without an agenda event in hand. Clearing
    // the link it does not know about would quietly undo the first push's
    // work.
    givenAMirror();
    ObjectSync known = mapped("\"etag-1\"");
    known.setLocalEventId(104L);
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("evt-1"))).thenReturn(known);
    when(calDavClient.fetchObject(any(), anyString(), anyString(), anyString())).thenReturn(null);
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                           .thenReturn(new PutResult(204,
                                                                                                                                     "\"etag-2\"",
                                                                                                                                     null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ObjectSync mapping = service.pushEvent(USER, event("evt-1"));

    assertEquals(104L, mapping.getLocalEventId());
  }

  /**
   * An event the caller may not see is never copied into their calendar.
   * Reading it through agenda's own service is what applies its ACL, so a
   * refusal has to stay a refusal: swallowed here, anyone could file a
   * confidential meeting into their own calendar by guessing its id.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void anEventTheCallerMayNotSeeIsNeverCopied() throws Exception {
    when(agendaEventService.getEventById(eq(105L), any(), eq(USER))).thenThrow(new IllegalAccessException("not a member"));

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.pushAgendaEvent(USER, 105L, null));

    assertEquals(CaldavPushService.SAVE, failure.getCode());
    verify(calDavClient, never()).putObject(any(), anyString(), anyString(), anyString(), anyString());
  }

  /**
   * An event that no longer exists stops the push before an identifier is
   * minted for it. Minting first would leave agenda pointing at a remote object
   * for an event nobody can push, which every later reconciliation would try
   * and fail to line up.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void anEventThatIsGoneStopsBeforeAnIdentifierIsMinted() throws Exception {
    when(agendaEventService.getEventById(eq(106L), any(), eq(USER))).thenReturn(null);

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.pushAgendaEvent(USER, 106L, null));

    assertEquals(CaldavPushService.SAVE, failure.getCode());
    verify(agendaRemoteEventService, never()).saveRemoteEvent(anyLong(), any(), anyLong());
  }

  /**
   * A user who never pushed anything has no mirror pair, and deleting from that
   * account is a no-op rather than a failure — and, above all, does not create
   * the pair on the way. A deletion that establishes a destination would leave
   * a user who only ever removed a meeting owning a mirror calendar.
   */
  @Test
  public void deletingFromAnAccountThatNeverPushedCreatesNothing() {
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of());

    service.deleteEvent(USER, "evt-1");

    verify(calDavClient, never()).deleteObject(any(), anyString(), any(), anyString(), anyString());
    verify(caldavSyncStorage, never()).savePair(any());
    verify(caldavSyncStorage, never()).saveObject(any());
  }

  /**
   * The object is removed at the href we recorded and conditionally on the tag
   * we last saw, and the mapping keeps its row while losing everything the
   * remote side owned. The row is what says this event was once pushed; dropping
   * it would make the next push write a second object, and keeping the href
   * would make a later read chase an object that is gone.
   */
  @Test
  public void aRemovalConditionalOnTheKnownTagClearsTheRemoteIdentity() {
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
    when(caldavSyncStorage.getObjectByUid(1L, "evt-1")).thenReturn(mapped("\"etag-1\""));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.deleteEvent(USER, "evt-1");

    verify(calDavClient).deleteObject(endpoint, MIRROR + "evt-1.ics", "\"etag-1\"", "john", "secret");
    ArgumentCaptor<ObjectSync> cleared = ArgumentCaptor.forClass(ObjectSync.class);
    verify(caldavSyncStorage).saveObject(cleared.capture());
    assertEquals("evt-1", cleared.getValue().getIcsUid());
    assertNull(cleared.getValue().getRemoteHref());
    assertNull(cleared.getValue().getEtag());
    assertNull(cleared.getValue().getPushedHash());
  }

  /**
   * A mapping that holds no href points at nothing, so there is nothing to
   * remove. Deriving one from the UID and deleting that instead would remove
   * whatever else happens to sit at the conventional filename.
   */
  @Test
  public void aMappingPointingAtNothingIsNothingToRemove() {
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
    ObjectSync unbound = mapped("\"etag-1\"");
    unbound.setRemoteHref(" ");
    when(caldavSyncStorage.getObjectByUid(1L, "evt-1")).thenReturn(unbound);

    service.deleteEvent(USER, "evt-1");

    verify(calDavClient, never()).deleteObject(any(), anyString(), any(), anyString(), anyString());
  }

  /**
   * Credentials rejected on a removal keep their own code, exactly as on a
   * write: it is the one failure the user resolves alone, and folding it into
   * the generic save error would tell them to try again later instead of asking
   * for their password.
   */
  @Test
  public void credentialsRejectedOnARemovalKeepTheirOwnCode() {
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
    when(caldavSyncStorage.getObjectByUid(1L, "evt-1")).thenReturn(mapped("\"etag-1\""));
    when(calDavClient.deleteObject(any(), anyString(), any(), anyString(), anyString()))
                                                                                        .thenThrow(new CalDavAuthenticationException("refused"));

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.deleteEvent(USER, "evt-1"));

    assertEquals(CaldavPushService.CREDENTIALS, failure.getCode());
  }

  /**
   * A removal the server refuses leaves the mapping as it was. Clearing the
   * href on a failed delete would make eXo believe the object is gone while it
   * still sits in the user's calendar, and nothing would ever look at it again.
   */
  @Test
  public void aRefusedRemovalLeavesTheMappingPointingAtTheObject() {
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
    when(caldavSyncStorage.getObjectByUid(1L, "evt-1")).thenReturn(mapped("\"etag-1\""));
    when(calDavClient.deleteObject(any(), anyString(), any(), anyString(), anyString()))
                                                                                        .thenThrow(new CalDavException("the server said no"));

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.deleteEvent(USER, "evt-1"));

    assertEquals(CaldavPushService.SAVE, failure.getCode());
    verify(caldavSyncStorage, never()).saveObject(any());
  }

  /**
   * The first push binds a pair to the mirror, active and marked as a mirror.
   * The origin is what separates these copies from a calendar the user actually
   * subscribed to, and a pair created without it would put eXo's own copies in
   * the path of the two-way sync.
   */
  @Test
  public void aFirstPushBindsAPairToTheMirrorItWritesUnder() {
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(calendar(MIRROR,
                                                                                                            "eXo Meetings")));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of());
    when(caldavSyncStorage.savePair(any())).thenAnswer(invocation -> {
      CalendarSync created = invocation.getArgument(0);
      created.setId(9L);
      return created;
    });
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"e\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ObjectSync mapping = service.pushEvent(USER, event("evt-1"));

    ArgumentCaptor<CalendarSync> created = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(created.capture());
    assertEquals(SyncOrigin.MIRROR, created.getValue().getOrigin());
    assertEquals(CalendarSyncStatus.ACTIVE, created.getValue().getStatus());
    assertEquals(MIRROR, created.getValue().getRemoteHref());
    assertEquals(SERVER, created.getValue().getServerId());
    assertEquals(9L, mapping.getCalendarSyncId());
  }

  /**
   * A mirror that moved — renamed, recreated, adopted after the server refused
   * a collection — is re-bound on the pair the user already has, not doubled by
   * a second one. Two pairs for one user's mirror is a state the database
   * cannot refuse, since a null anchor sits outside the unique index.
   */
  @Test
  public void aMirrorThatMovedIsReboundRatherThanDoubled() {
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(calendar(MIRROR,
                                                                                                            "eXo Meetings")));
    CalendarSync moved = pair();
    moved.setRemoteHref("/dav/calendars/john/meetings-as-they-were");
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(moved));
    when(caldavSyncStorage.savePair(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"e\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.pushEvent(USER, event("evt-1"));

    ArgumentCaptor<CalendarSync> rebound = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(rebound.capture());
    assertEquals(1L, rebound.getValue().getId());
    assertEquals(MIRROR, rebound.getValue().getRemoteHref());
  }

  /**
   * A user who somehow holds two mirror pairs keeps working on the first rather
   * than having the push fail or fork: the copies stay in one place, and the
   * duplicate is a data state to repair, not a reason to stop pushing.
   */
  @Test
  public void aDuplicatedMirrorPairDoesNotForkWhereTheCopiesGo() {
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(calendar(MIRROR,
                                                                                                            "eXo Meetings")));
    CalendarSync duplicate = pair();
    duplicate.setId(2L);
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair(), duplicate));
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"e\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ObjectSync mapping = service.pushEvent(USER, event("evt-1"));

    assertEquals(1L, mapping.getCalendarSyncId());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * A stored account missing half its credentials is not a connected account.
   * Letting it through would send an unauthenticated request and surface as a
   * credentials error, telling the user their password was rejected when in
   * fact none was ever stored.
   */
  @Test
  public void anAccountMissingHalfItsCredentialsIsNotConnected() {
    CaldavUserSetting halfStored = settings();
    halfStored.setPassword(" ");
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(halfStored);

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.ensureMirror(USER));

    assertEquals(CaldavPushService.NOT_CONNECTED, failure.getCode());
    verify(calDavClient, never()).discoverCalendarHome(any(), anyString(), anyString());
  }

  /**
   * An account whose server registration is gone — deleted, deactivated — is
   * reported as not connected rather than as a save failure, because that is
   * what the user has to fix. The drawer sends them to reconnect on the 409; a
   * 502 would have them wait for a server that will never answer.
   */
  @Test
  public void anAccountWhoseServerNoLongerResolvesIsNotConnected() {
    when(calDavClient.endpoint(SERVER, "john")).thenThrow(new CalDavException("no server row"));

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.ensureMirror(USER));

    assertEquals(CaldavPushService.NOT_CONNECTED, failure.getCode());
  }

  /**
   * A write the server refuses for anything other than credentials or a
   * concurrent edit keeps the generic save code, and records nothing. A mapping
   * saved for an object that was never written is what makes the next push
   * conditional on a tag the server never issued.
   */
  @Test
  public void aWriteTheServerRefusesRecordsNothing() {
    givenAMirror();
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenThrow(new CalDavException("507 insufficient storage"));

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.pushEvent(USER, event("evt-1")));

    assertEquals(CaldavPushService.SAVE, failure.getCode());
    verify(caldavSyncStorage, never()).saveObject(any());
  }

  /**
   * A mapping row that exists but holds no tag describes an object we never
   * managed to write, so the push is conditional on the object not existing
   * again — not on a tag we do not have. Sending an empty precondition instead
   * would either overwrite whatever is there or fail every time.
   */
  @Test
  public void aMappingWithoutATagIsPushedAsIfItWereTheFirstTime() {
    givenAMirror();
    ObjectSync interrupted = new ObjectSync();
    interrupted.setId(5L);
    interrupted.setCalendarSyncId(1L);
    interrupted.setIcsUid("evt-1");
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("evt-1"))).thenReturn(interrupted);
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"etag-1\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ObjectSync mapping = service.pushEvent(USER, event("evt-1"));

    // The href comes back from the filename convention, since the row that
    // would have carried one never got that far.
    assertEquals(MIRROR + "evt-1.ics", mapping.getRemoteHref());
    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
    verify(calDavClient, never()).updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  /**
   * An object that is present but empty has nothing to preserve, so what we
   * built is sent as it stands. Merging into emptiness would be asking the
   * merger to reconcile a calendar that holds no components, and what comes out
   * of that is not something the user's client has to survive.
   */
  @Test
  public void anEmptyObjectOnTheServerIsWrittenOverRatherThanMergedInto() {
    givenAMirror();
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("evt-1"))).thenReturn(mapped("\"etag-1\""));
    when(calDavClient.fetchObject(any(), anyString(), anyString(), anyString()))
                                                                                .thenReturn(new CalendarObject(MIRROR + "evt-1.ics",
                                                                                                               "\"etag-1\"",
                                                                                                               "  "));
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                           .thenReturn(new PutResult(204,
                                                                                                                                     "\"etag-2\"",
                                                                                                                                     null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.pushEvent(USER, event("evt-1"));

    verify(icsMerger, never()).merge(anyString(), anyString(), anyBoolean());
    verify(calDavClient).updateObject(any(),
                                      anyString(),
                                      eq("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"),
                                      eq("\"etag-1\""),
                                      anyString(),
                                      anyString());
  }

  /**
   * An identifier recorded without a value is no identifier: a fresh one is
   * minted and stored, rather than writing the object under a blank UID that no
   * calendar server would keep apart from any other.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void anIdentifierRecordedWithoutAValueIsMintedAgain() throws Exception {
    givenAMirror();
    givenAnAgendaEvent(107L, 0L);
    RemoteEvent blank = new RemoteEvent();
    blank.setRemoteId(" ");
    when(agendaRemoteEventService.findRemoteEvent(107L, USER)).thenReturn(blank);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("minted"));
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"e\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.pushAgendaEvent(USER, 107L, null);

    verify(agendaRemoteEventService).saveRemoteEvent(eq(107L), any(), eq(USER));
  }

  /**
   * A calendar a server lists without an href is skipped instead of being taken
   * for the mirror. Some servers enumerate placeholder collections that way,
   * and adopting one would have every copy written to a destination that
   * resolves to nothing.
   */
  @Test
  public void aCalendarListedWithoutAnHrefIsNotMistakenForTheMirror() {
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString()))
                                                                              .thenReturn(List.of(calendar(null, "Placeholder"),
                                                                                                  calendar(MIRROR, "eXo Meetings")));

    MirrorTarget target = service.ensureMirror(USER);

    assertEquals(MIRROR, target.href());
    assertFalse(target.adopted());
  }

  /**
   * An account connected before its server was registered still resolves to a
   * single pair, anchored on zero. Letting a null through as a distinct server
   * would give the same user a second mirror pair the moment the registration
   * landed, and their copies would fork between the two.
   */
  @Test
  public void anAccountWithNoRegisteredServerStillHasOnePair() {
    CaldavUserSetting unregistered = settings();
    unregistered.setServerId(null);
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(unregistered);
    when(calDavClient.endpoint(null, "john")).thenReturn(endpoint);
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(calendar(MIRROR,
                                                                                                            "eXo Meetings")));
    when(caldavSyncStorage.getPairsByOrigin(USER, 0L, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"e\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.pushEvent(USER, event("evt-1"));

    verify(caldavSyncStorage).getPairsByOrigin(USER, 0L, SyncOrigin.MIRROR);
  }

  @Test
  public void theEventIsReadInItsOwnZoneAndNoOther() throws Exception {
    // For a timed event any zone yields the same instant, which makes this
    // look like a free choice. It is not: agenda re-anchors an all-day event's
    // covered days at midnight in whatever zone is asked for, so reading in
    // UTC moves an all-day event of a user west of Greenwich to 20:00 the
    // previous day — and the copy is written one day early, silently, for
    // exactly the users whose zone caused it.
    givenAMirror();
    givenAnAgendaEvent(105L, 0L);
    when(agendaRemoteEventService.findRemoteEvent(105L, USER)).thenReturn(null);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("uid-105"));
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"e\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.pushAgendaEvent(USER, 105L, null);

    // null is how agenda is asked for the event's own zone.
    verify(agendaEventService).getEventById(105L, null, USER);
  }

  /**
   * An agenda event the service can read.
   *
   * @param eventId the event
   * @param parentId its series, or 0 when it is not an occurrence
   * @throws Exception when the stub cannot be set
   */
  private void givenAnAgendaEvent(long eventId, long parentId) throws Exception {
    Event event = new Event();
    event.setId(eventId);
    event.setParentId(parentId);
    when(agendaEventService.getEventById(eq(eventId), isNull(), eq(USER))).thenReturn(event);
  }

  /**
   * A mirror pair and collection already in place.
   */
  private void givenAMirror() {
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(calendar(MIRROR,
                                                                                                            "eXo Meetings")));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
  }

  /**
   * @return a connected account
   */
  private CaldavUserSetting settings() {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("john");
    setting.setPassword("secret");
    setting.setServerId(SERVER);
    return setting;
  }

  /**
   * @param href the collection href
   * @param name its display name
   * @return a listed calendar
   */
  private CalendarCollection calendar(String href, String name) {
    return new CalendarCollection(href, name, null, null, null, false);
  }

  /**
   * @return the mirror pair, already bound
   */
  private CalendarSync pair() {
    CalendarSync pair = new CalendarSync();
    pair.setId(1L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setRemoteHref(CANONICAL_MIRROR);
    pair.setOrigin(SyncOrigin.MIRROR);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  /**
   * @param etag the tag last seen
   * @return an object already pushed once
   */
  private ObjectSync mapped(String etag) {
    ObjectSync mapping = new ObjectSync();
    mapping.setId(5L);
    mapping.setCalendarSyncId(1L);
    mapping.setIcsUid("evt-1");
    mapping.setRemoteHref(MIRROR + "evt-1.ics");
    mapping.setEtag(etag);
    return mapping;
  }

  /**
   * @param uid the iCalendar UID
   * @return an event to push
   */
  private IcsEvent event(String uid) {
    return IcsEvent.builder()
                   .uid(uid)
                   .summary("Steering point")
                   .start(Instant.parse("2026-09-08T09:00:00Z"))
                   .end(Instant.parse("2026-09-08T10:00:00Z"))
                   .timeZoneId("Europe/Paris")
                   .build();
  }
}
