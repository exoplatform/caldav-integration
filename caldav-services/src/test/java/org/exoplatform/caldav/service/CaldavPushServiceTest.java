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
import static org.mockito.Mockito.times;
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
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
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
  private AgendaCalendarService      agendaCalendarService;

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
  public void theCurrentMirrorIsReadWithItsNameFromTheServer() {
    // The settings screen asks this on every render. It used to look the
    // destination up in the calendar listing, which hides this very collection
    // on purpose — so the answer was always "no destination", the switch went
    // back off in front of the user who had just chosen one, and the name
    // never appeared.
    CaldavUserSetting stored = settings();
    stored.setMirrorCalendarHref(MIRROR);
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(stored);
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(calendar(MIRROR,
                                                                                                           "Mes reunions eXo")));

    MirrorTarget target = service.currentMirror(USER);

    assertEquals(MIRROR, target.href());
    // The name comes from the server, not from anything stored: a user who
    // renamed the calendar in their own client must see the name they gave it.
    assertEquals("Mes reunions eXo", target.name());
  }

  @Test
  public void readingTheCurrentMirrorNeverCreatesOne() {
    // A read on every render must not make a calendar on someone's account.
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());

    assertNull(service.currentMirror(USER));

    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString());
  }

  @Test
  public void aMirrorLeftBehindByADisconnectIsRecognisedAgain() {
    // Disconnecting clears the recorded href but leaves the collection on the
    // server, so a reconnected account has a destination it does not
    // remember. Answering "none" here made eXo offer to create a calendar
    // that already existed — and invite a second one beside it.
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(calendar(MIRROR,
                                                                                                           "eXo Meetings")));

    MirrorTarget target = service.currentMirror(USER);

    assertNotNull(target);
    assertEquals(MIRROR, target.href());
    // Recognised, never re-made: this is a read.
    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString());
  }

  @Test
  public void anAccountWithNoMirrorAnywhereStillReadsAsNone() {
    // The other half of the rediscovery: recognising a collection at the
    // derived path must not turn "nothing there" into a false positive.
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(calendar("/dav/calendars/john/personal/",
                                                                                                           "Personal")));

    assertNull(service.currentMirror(USER));
  }

  @Test
  public void anUnreachableServerReadsAsNoneWhenNothingWasRecorded() {
    // The rediscovery must not make every settings render report a problem
    // the user does not have: with nothing recorded, nothing is claimed lost,
    // and an account that cannot be reached simply has no known destination.
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    when(calDavClient.discoverCalendarHome(any(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("the calendar server could not be reached"));

    assertNull(service.currentMirror(USER));
  }

  @Test
  public void anUnreachableServerStillRaisesForARecordedMirror() {
    // The user chose this destination, so the screen must say the account is
    // unreachable rather than quietly report having none.
    CaldavUserSetting stored = settings();
    stored.setMirrorCalendarHref(MIRROR);
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(stored);
    when(calDavClient.discoverCalendarHome(any(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("the calendar server could not be reached"));

    assertThrows(IllegalStateException.class, () -> service.currentMirror(USER));
  }

  @Test
  public void aMirrorRecordedButGoneReadsAsNone() {
    // Deleted from the user's own client. Saying "no destination" is right;
    // naming a calendar that is not there would be worse than saying nothing.
    CaldavUserSetting stored = settings();
    stored.setMirrorCalendarHref("/dav/cal/john/gone/");
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(stored);
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of());

    assertNull(service.currentMirror(USER));
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
  public void theMintedIdentifierIsRecordedInAFormAgendaWillKeep() throws Exception {
    // The identifier is only useful if it survives the push that minted it.
    // agenda stores the mapping between an eXo event and its remote object
    // only when the record names a provider — given neither a provider id nor
    // a provider name, saveRemoteEvent reads the call as "delete this
    // mapping" and throws the identifier away.
    //
    // This connector left both unset, so every push minted a fresh identifier
    // and wrote a fresh object: editing an event duplicated it on the server
    // and orphaned the original, and deleting it searched for an identifier
    // the server had never seen. The provider is registered by this add-on in
    // caldav-configuration.xml, so naming it is all that is needed — and
    // asserting the name here is what stops it being dropped again.
    givenAMirror();
    givenAnAgendaEvent(130L, 0L);
    when(agendaRemoteEventService.findRemoteEvent(130L, USER)).thenReturn(null);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("uid-130"));
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"e\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.pushAgendaEvent(USER, 130L, null);

    ArgumentCaptor<RemoteEvent> recorded = ArgumentCaptor.forClass(RemoteEvent.class);
    verify(agendaRemoteEventService).saveRemoteEvent(eq(130L), recorded.capture(), eq(USER));
    assertEquals("agenda.caldavCalendar", recorded.getValue().getRemoteProviderName());
    assertNotNull(recorded.getValue().getRemoteId());
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
   * A repair writes over the drifted copy instead of asking the server's
   * permission first.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void aRepairWritesWithoutTheConditionalGuard() throws Exception {
    // The guarded write carries the etag this connector last recorded, and
    // the server refuses it when the object has moved on — which is the
    // definition of the case being repaired. Guarded, no repair could ever
    // succeed: every pass reported "altered: 1, re-pushed: 0".
    givenAMirror();
    givenAnAgendaEvent(120L, 0L);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("evt-1"));
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("evt-1"))).thenReturn(mapped("\"etag-1\""));
    when(calDavClient.fetchObject(any(), anyString(), anyString(), anyString()))
                                                                               .thenReturn(new CalendarObject(MIRROR + "evt-1.ics",
                                                                                                              "\"etag-9\"",
                                                                                                              "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"));
    when(icsMerger.merge(anyString(), anyString(), anyBoolean())).thenReturn("MERGED");
    when(calDavClient.overwriteObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                                 .thenReturn(new PutResult(204,
                                                                                                                           "\"etag-10\"",
                                                                                                                           null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.rewriteAgendaEvent(USER, 120L);

    verify(calDavClient).overwriteObject(any(), anyString(), eq("MERGED"), anyString(), anyString());
    // Neither of the guarded writes: If-Match is refused when the object has
    // drifted, and If-None-Match — which putObject sends — is refused because
    // the object exists. Both refuse exactly the case being repaired.
    verify(calDavClient, never()).updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString());
    verify(calDavClient, never()).putObject(any(), anyString(), anyString(), anyString(), anyString());
  }

  /**
   * A repair with no mapping recorded still overwrites rather than trying to
   * create.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void aRepairWithNothingRecordedStillOverwrites() throws Exception {
    // The case a live account was stuck in: the row named an href the server
    // no longer had, its UID matched no row, so the push had nothing to go
    // on and fell to the create-only path — which the server refused, because
    // the object was there all along under the href being repaired. Refused
    // every pass, for ever.
    givenAMirror();
    givenAnAgendaEvent(122L, 0L);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("evt-1"));
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("evt-1"))).thenReturn(null);
    when(calDavClient.overwriteObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                                .thenReturn(new PutResult(204,
                                                                                                                          "\"etag-11\"",
                                                                                                                          null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.rewriteAgendaEvent(USER, 122L);

    verify(calDavClient).overwriteObject(any(), anyString(), anyString(), anyString(), anyString());
    verify(calDavClient, never()).putObject(any(), anyString(), anyString(), anyString(), anyString());
  }

  /**
   * A first push with nothing recorded still insists on creating.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void aFirstPushWithNothingRecordedStillInsistsOnCreating() throws Exception {
    // If-None-Match: * is what stops a first push from silently overwriting
    // an object somebody else put at that href. Only a repair may drop it.
    givenAMirror();
    givenAnAgendaEvent(123L, 0L);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("evt-1"));
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("evt-1"))).thenReturn(null);
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"etag-12\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.pushAgendaEvent(USER, 123L, null);

    verify(calDavClient).putObject(any(), anyString(), anyString(), anyString(), anyString());
    verify(calDavClient, never()).overwriteObject(any(), anyString(), anyString(), anyString(), anyString());
  }

  /**
   * An ordinary push still asks first.
   *
   * @throws Exception never, the service is mocked
   */
  @Test
  public void anOrdinaryPushStillCarriesTheConditionalGuard() throws Exception {
    // The overwrite is the repair's privilege alone. A normal push that found
    // the object changed underneath it must still stop rather than clobber an
    // edit nobody has looked at.
    givenAMirror();
    givenAnAgendaEvent(121L, 0L);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("evt-1"));
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("evt-1"))).thenReturn(mapped("\"etag-1\""));
    when(calDavClient.fetchObject(any(), anyString(), anyString(), anyString()))
                                                                               .thenReturn(new CalendarObject(MIRROR + "evt-1.ics",
                                                                                                              "\"etag-9\"",
                                                                                                              "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"));
    when(icsMerger.merge(anyString(), anyString(), anyBoolean())).thenReturn("MERGED");
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                          .thenReturn(new PutResult(204,
                                                                                                                                    "\"etag-10\"",
                                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.pushAgendaEvent(USER, 121L, null);

    verify(calDavClient).updateObject(any(), anyString(), eq("MERGED"), eq("\"etag-1\""), anyString(), anyString());
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

  @Test
  public void excludingAnOccurrenceRewritesTheObjectInsteadOfDeletingIt() {
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
    ObjectSync known = mapped("\"etag-1\"");
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("series-uid"))).thenReturn(known);
    when(calDavClient.fetchObject(any(), anyString(), anyString(), anyString()))
                                                                                .thenReturn(new CalendarObject(known.getRemoteHref(),
                                                                                                               "\"etag-1\"",
                                                                                                               "BEGIN:VCALENDAR"));
    when(icsMerger.excludeOccurrence(anyString(), any())).thenReturn("REWRITTEN");
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                           .thenReturn(new PutResult(204,
                                                                                                                                     "\"etag-2\"",
                                                                                                                                     null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.excludeOccurrence(USER, "series-uid", Instant.parse("2026-09-15T07:00:00Z"));

    // Every component of a series lives in one object: deleting it would
    // cancel every meeting of the series to cancel one.
    verify(calDavClient).updateObject(any(), anyString(), eq("REWRITTEN"), eq("\"etag-1\""), anyString(), anyString());
    verify(calDavClient, never()).deleteObject(any(), anyString(), any(), anyString(), anyString());
  }

  @Test
  public void anObjectLeftEmptyByAnExclusionIsDeleted() {
    // Writing back a VCALENDAR with no VEVENT is accepted by some servers and
    // then served to clients that choke on it.
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("series-uid"))).thenReturn(mapped("\"etag-1\""));
    when(calDavClient.fetchObject(any(), anyString(), anyString(), anyString()))
                                                                                .thenReturn(new CalendarObject("/h",
                                                                                                               "\"etag-1\"",
                                                                                                               "BEGIN:VCALENDAR"));
    when(icsMerger.excludeOccurrence(anyString(), any())).thenReturn(null);
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.excludeOccurrence(USER, "series-uid", Instant.parse("2026-09-15T07:00:00Z"));

    verify(calDavClient).deleteObject(any(), anyString(), any(), anyString(), anyString());
  }

  @Test
  public void aSeriesChangedElsewhereSurfacesAsAConflict() {
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("series-uid"))).thenReturn(mapped("\"etag-1\""));
    when(calDavClient.fetchObject(any(), anyString(), anyString(), anyString()))
                                                                                .thenReturn(new CalendarObject("/h",
                                                                                                               "\"etag-1\"",
                                                                                                               "BEGIN:VCALENDAR"));
    when(icsMerger.excludeOccurrence(anyString(), any())).thenReturn("REWRITTEN");
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                           .thenReturn(new PutResult(412,
                                                                                                                                     null,
                                                                                                                                     null));

    CaldavPushException failure = assertThrows(CaldavPushException.class,
                                               () -> service.excludeOccurrence(USER,
                                                                               "series-uid",
                                                                               Instant.parse("2026-09-15T07:00:00Z")));

    assertEquals(CaldavPushService.CONFLICT, failure.getCode());
  }

  @Test
  public void excludingFromASeriesThatWasNeverPushedDoesNothing() {
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("unknown"))).thenReturn(null);

    service.excludeOccurrence(USER, "unknown", Instant.parse("2026-09-15T07:00:00Z"));

    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
  }

  @Test
  public void anEventOfTheUserOwnCalendarGoesIntoThatCalendarCollection() throws Exception {
    // Where an event goes is decided from the calendar it lives in. A personal
    // event filed among space copies would mix two calendars in one
    // collection, with nothing recording which came from where.
    givenAnAgendaEvent(110L, 0L);
    givenPersonalCalendar(7L, "cal-anchor");
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, "cal-anchor")).thenReturn(boundPersonalPair());
    when(agendaRemoteEventService.findRemoteEvent(110L, USER)).thenReturn(null);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("uid-110"));
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"e\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ObjectSync mapping = service.pushAgendaEvent(USER, 110L, null);

    assertEquals(9L, mapping.getCalendarSyncId());
    // The mirror is never established for a personal event: doing so would
    // create a collection the user did not ask for.
    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString());
  }

  @Test
  public void aPersonalCalendarWithNoUsableCollectionSendsNothingToTheMirror() throws Exception {
    // Outbound simply stays unavailable for that calendar. Diverting the event
    // into the mirror as a consolation is exactly the mixing this refuses.
    //
    // This test asserted the opposite of its own name until the personal
    // calendars began pushing for real: it pinned the fall-through to the
    // mirror, which is what the code did, while the name and the service's
    // own javadoc both said that must never happen. Nobody noticed because
    // nothing reached this path — the browser never offered a personal event.
    givenAnAgendaEvent(111L, 0L);
    givenPersonalCalendar(8L, "cal-anchor");
    CalendarSync refused = boundPersonalPair();
    refused.setStatus(CalendarSyncStatus.REMOTE_CREATE_REFUSED);
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, "cal-anchor")).thenReturn(refused);
    when(agendaRemoteEventService.findRemoteEvent(111L, USER)).thenReturn(null);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("uid-111"));

    ObjectSync mapping = service.pushAgendaEvent(USER, 111L, null);

    // Nothing was copied, and nothing was written anywhere.
    assertNull(mapping);
    verify(calDavClient, never()).putObject(any(), anyString(), anyString(), anyString(), anyString());
    verify(calDavClient, never()).updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString());
    // Not even established: falling back to the mirror would have created a
    // collection the user never asked for, to hold an event that is not a
    // space meeting.
    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString());
  }

  @Test
  public void aCopyInAPersonalCollectionCanBeRemovedFromIt() {
    // The other half of writing there. A removal that looked only in the
    // mirror found nothing, succeeded quietly, and left the object on the
    // server for ever — in the one collection the user actually reads.
    // The mirror's identifier is deliberately past 127. getPairs returns every
    // pair including the mirror, so the walk has to skip the one it already
    // searched — and these identifiers are Long, so a skip written with ==
    // compares references and only appears to work while the value is small
    // enough for Java to have cached it. Anything a real database issues is
    // past the cache, which is what makes the assertion below meaningful.
    CalendarSync mirror = pair();
    mirror.setId(5001L);
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(mirror));
    when(caldavSyncStorage.getObjectByUid(5001L, "uid-113")).thenReturn(null);
    CalendarSync personal = boundPersonalPair();
    personal.setId(5002L);
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pairOf(5001L), personal));
    ObjectSync inPersonal = mapped("\"etag-1\"");
    inPersonal.setCalendarSyncId(5002L);
    inPersonal.setRemoteHref(MIRROR + "in-personal.ics");
    when(caldavSyncStorage.getObjectByUid(5002L, "uid-113")).thenReturn(inPersonal);
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.deleteEvent(USER, "uid-113");

    verify(calDavClient).deleteObject(any(), eq(MIRROR + "in-personal.ics"), anyString(), anyString(), anyString());
    // Once, not twice: the mirror was searched before the walk began.
    verify(caldavSyncStorage, times(1)).getObjectByUid(5001L, "uid-113");
  }


  @Test
  public void anEventMovedToAnotherCalendarLeavesNoCopyBehind() throws Exception {
    // Found by hand: move an event between two personal calendars and it
    // appears twice on the server, once in each collection. The mapping lookup
    // is scoped to the destination, so after a move the event looks new there
    // while its old mapping — and its object — are still sitting in the
    // calendar it left. Nothing on the push path noticed a calendar change.
    //
    // The old copy is not merely untidy: a later edit in eXo updates only the
    // new collection, so the one left behind goes on looking like a real event
    // on the user's phone while quietly diverging from it.
    //
    // The identifiers are past 127 deliberately: they are Long, so a skip
    // written with == compares references and only appears to work while the
    // value is small enough for Java to have cached it.
    givenAnAgendaEvent(112L, 0L);
    givenPersonalCalendar(9L, "cal-anchor");
    CalendarSync destination = boundPersonalPair();
    destination.setId(6001L);
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, "cal-anchor")).thenReturn(destination);
    when(agendaRemoteEventService.findRemoteEvent(112L, USER)).thenReturn(null);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("uid-112"));
    // nothing in the destination — this looks like a create
    when(caldavSyncStorage.getObjectByUid(6001L, "uid-112")).thenReturn(null);
    // but the event was in another calendar of the same account a moment ago
    CalendarSync origin = boundPersonalPair();
    origin.setId(6002L);
    origin.setRemoteHref("/dav/calendars/john/exo-cal-old-anchor");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(destination, origin));
    ObjectSync stray = mapped("\"etag-old\"");
    stray.setId(7777L);
    stray.setCalendarSyncId(6002L);
    stray.setRemoteHref("/dav/calendars/john/exo-cal-old-anchor/uid-112.ics");
    when(caldavSyncStorage.getObjectByUid(6002L, "uid-112")).thenReturn(stray);
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new PutResult(201, "\"etag-new\"", null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.pushAgendaEvent(USER, 112L, null);

    // the copy left behind is removed, conditionally on what eXo last saw
    verify(calDavClient).deleteObject(any(),
                                     eq("/dav/calendars/john/exo-cal-old-anchor/uid-112.ics"),
                                     eq("\"etag-old\""),
                                     anyString(),
                                     anyString());
    verify(caldavSyncStorage).deleteObject(7777L);
  }

  @Test
  public void anOrdinaryFirstWriteRemovesNothing() throws Exception {
    // The control. An event that was never anywhere else must not send a
    // delete to the account just because it is being written for the first
    // time — the search for a previous home must come back empty and stay
    // harmless.
    givenAnAgendaEvent(113L, 0L);
    givenPersonalCalendar(10L, "cal-anchor");
    CalendarSync destination = boundPersonalPair();
    destination.setId(6001L);
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, "cal-anchor")).thenReturn(destination);
    when(agendaRemoteEventService.findRemoteEvent(113L, USER)).thenReturn(null);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("uid-113"));
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("uid-113"))).thenReturn(null);
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(destination));
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new PutResult(201, "\"etag-new\"", null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.pushAgendaEvent(USER, 113L, null);

    verify(calDavClient, never()).deleteObject(any(), anyString(), anyString(), anyString(), anyString());
    verify(caldavSyncStorage, never()).deleteObject(anyLong());
  }


  @Test
  public void aMovedEventIsFoundEvenWhenItsUidWasMintedAfresh() throws Exception {
    // The UID is supposed to survive a move — it is adopted from agenda's
    // remote-event mapping — but that mapping has been lost before in this
    // codebase, and when it is, the push mints a new one. A search keyed on the
    // UID then finds nothing and the copy is left behind exactly as before,
    // with no sign anything went wrong. The event's own identifier does not
    // depend on that mapping.
    givenAnAgendaEvent(112L, 0L);
    givenPersonalCalendar(9L, "cal-anchor");
    CalendarSync destination = boundPersonalPair();
    destination.setId(6001L);
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, "cal-anchor")).thenReturn(destination);
    when(agendaRemoteEventService.findRemoteEvent(112L, USER)).thenReturn(null);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("uid-minted-anew"));
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("uid-minted-anew"))).thenReturn(null);
    CalendarSync origin = boundPersonalPair();
    origin.setId(6002L);
    origin.setRemoteHref("/dav/calendars/john/exo-cal-old-anchor");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(destination, origin));
    // the old mapping carries the OLD uid, so only the event id can find it
    ObjectSync stray = mapped("\"etag-old\"");
    stray.setId(7778L);
    stray.setCalendarSyncId(6002L);
    stray.setRemoteHref("/dav/calendars/john/exo-cal-old-anchor/uid-original.ics");
    when(caldavSyncStorage.getObjectByEvent(6002L, 112L)).thenReturn(stray);
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new PutResult(201, "\"etag-new\"", null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.pushAgendaEvent(USER, 112L, null);

    verify(calDavClient).deleteObject(any(),
                                     eq("/dav/calendars/john/exo-cal-old-anchor/uid-original.ics"),
                                     eq("\"etag-old\""),
                                     anyString(),
                                     anyString());
    verify(caldavSyncStorage).deleteObject(7778L);
  }

  /**
   * The mirror pair under a chosen identifier.
   *
   * @param id the identifier it carries
   * @return the pair
   */
  private CalendarSync pairOf(long id) {
    CalendarSync pair = pair();
    pair.setId(id);
    return pair;
  }

  @Test
  public void aCopyInTheMirrorIsStillRemovedWithoutSearchingFurther() {
    // The common case stays one lookup: the mirror holds most copies, and
    // walking every pair for them would cost a query per calendar.
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
    ObjectSync inMirror = mapped("\"etag-1\"");
    // Read before the call: a successful removal clears the href on this very
    // object, so asking for it afterwards asks for null.
    String href = inMirror.getRemoteHref();
    when(caldavSyncStorage.getObjectByUid(1L, "uid-114")).thenReturn(inMirror);
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.deleteEvent(USER, "uid-114");

    verify(calDavClient).deleteObject(any(), eq(href), anyString(), anyString(), anyString());
    verify(caldavSyncStorage, never()).getPairs(anyLong(), anyLong());
  }

  @Test
  public void aSpaceEventStillGoesToTheMirror() throws Exception {
    givenAMirror();
    givenAnAgendaEvent(112L, 0L);
    // Owned by a space, not by this user.
    givenSpaceCalendar(9L);
    when(agendaRemoteEventService.findRemoteEvent(112L, USER)).thenReturn(null);
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event("uid-112"));
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"e\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    assertEquals(1L, service.pushAgendaEvent(USER, 112L, null).getCalendarSyncId());
    verify(caldavSyncStorage, never()).getPairByLocalCalendar(anyLong(), anyLong(), anyString());
  }

  /**
   * A calendar of this user's own, carrying an anchor.
   *
   * @param calendarId the calendar the event lives in
   * @param anchor its immutable sync uid
   */
  private void givenPersonalCalendar(long calendarId, String anchor) {
    Calendar calendar = new Calendar();
    calendar.setId(calendarId);
    calendar.setOwnerId(USER);
    calendar.setSyncUid(anchor);
    when(agendaCalendarService.getCalendarById(calendarId)).thenReturn(calendar);
  }

  /**
   * A calendar owned by a space rather than by this user.
   *
   * @param calendarId the calendar the event lives in
   */
  private void givenSpaceCalendar(long calendarId) {
    Calendar calendar = new Calendar();
    calendar.setId(calendarId);
    calendar.setOwnerId(999L);
    when(agendaCalendarService.getCalendarById(calendarId)).thenReturn(calendar);
  }

  /**
   * A personal calendar already bound to its own collection.
   *
   * @return the pair
   */
  private CalendarSync boundPersonalPair() {
    CalendarSync pair = new CalendarSync();
    pair.setId(9L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setLocalCalendarSyncUid("cal-anchor");
    pair.setRemoteHref("/dav/calendars/john/exo-cal-cal-anchor");
    pair.setOrigin(SyncOrigin.EXO);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
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
    event.setCalendarId(eventId >= 110L ? eventId - 103L : 0L);
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
