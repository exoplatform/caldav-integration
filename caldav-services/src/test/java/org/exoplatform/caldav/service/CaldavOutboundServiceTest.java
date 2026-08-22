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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.MkCalendarResult;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * Giving a user's own calendars a collection on their own server.
 *
 * <p>
 * The rule that separates this from the space mirror is the one worth reading
 * first: <b>a personal calendar is never adopted</b>. When a server refuses to
 * create a collection, the mirror falls back to an existing calendar, because
 * a copy of a space event filed somewhere unexpected is a compromise the user
 * can see and undo. Doing the same here would write one calendar's events into
 * another, with nothing recording which came from where — corruption dressed
 * as resilience.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavOutboundServiceTest {

  private static final long          USER   = 42L;

  private static final long          SERVER = 7L;

  private static final String        LOGIN  = "john";

  private static final String        HOME   = "/dav/calendars/john/";

  private static final String        ANCHOR = "c0ffee-uid";

  private static final String        WANTED = "/dav/calendars/john/exo-cal-c0ffee-uid/";

  @Mock
  private CalDavClient               calDavClient;

  @Mock
  private CaldavConnectorStorage     caldavConnectorStorage;

  @Mock
  private CaldavSyncStorage          caldavSyncStorage;

  @Mock
  private AgendaCalendarService      agendaCalendarService;

  @Mock
  private CalDavEndpoint             endpoint;

  @InjectMocks
  private CaldavOutboundService      service;

  @BeforeEach
  public void connectAnAccount() {
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
    lenient().when(calDavClient.discoverCalendarHome(any(), anyString(), anyString())).thenReturn(HOME);
    lenient().when(caldavSyncStorage.savePair(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  public void aPersonalCalendarGetsACollectionNamedAfterItsAnchor() throws Exception {
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    givenServerCalendars(List.of(), List.of(collection(WANTED)));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    List<CalendarSync> pairs = service.bindPersonalCalendars(USER, LOGIN);

    ArgumentCaptor<String> href = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).mkCalendar(any(), href.capture(), anyString(), any(), anyString(), anyString());
    // The anchor is in the path, which is what makes the binding recoverable
    // from the server alone.
    assertEquals(WANTED, href.getValue());
    assertEquals(CalendarSyncStatus.ACTIVE, pairs.get(0).getStatus());
  }

  @Test
  public void theCollectionIsCreatedUnderTheCalendarsOwnName() {
    // What the user reads in their own client. Naming it after the sync uid
    // put "eXo c434ba2a-3f58-…" in front of them, which tells them nothing
    // about which of their calendars it is.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    givenServerCalendars(List.of(), List.of(collection(WANTED)));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    service.bindPersonalCalendars(USER, LOGIN);

    ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).mkCalendar(any(), anyString(), displayName.capture(), any(), anyString(), anyString());
    assertEquals("Work", displayName.getValue());
  }

  @Test
  public void theCalendarsOwnNameBeatsTheTitleAgendaComputes() {
    // Observed against a live server: a collection came back called
    // "benjamin mestrallet" rather than the calendar's name, because
    // getTitle() is computed and resolves to the owner for a personal
    // calendar. Preferring it names every collection after the user.
    Calendar named = calendar(1L, USER, ANCHOR, "benjamin mestrallet");
    named.setName("Holidays");
    givenPersonalCalendars(named);
    givenServerCalendars(List.of(), List.of(collection(WANTED)));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    service.bindPersonalCalendars(USER, LOGIN);

    ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).mkCalendar(any(), anyString(), displayName.capture(), any(), anyString(), anyString());
    assertEquals("Holidays", displayName.getValue());
  }

  @Test
  public void aCalendarWithNothingToBeCalledFallsBackToItsAnchor() {
    // A nameless calendar still has to be called something on the far side,
    // and the uid is the only thing left that identifies it.
    Calendar nameless = calendar(1L, USER, ANCHOR, null);
    nameless.setName(null);
    givenPersonalCalendars(nameless);
    givenServerCalendars(List.of(), List.of(collection(WANTED)));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    service.bindPersonalCalendars(USER, LOGIN);

    ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).mkCalendar(any(), anyString(), displayName.capture(), any(), anyString(), anyString());
    assertEquals("eXo " + ANCHOR, displayName.getValue());
  }

  @Test
  public void aCalendarMaterialisedFromARemoteCollectionIsNeverPushedBackOut() {
    // Observed live: the outbound pass saw a materialised calendar as an
    // ordinary personal one, re-bound it, and record() relabelled its
    // collection ORIGIN=EXO. Two harms from that one lie — the inbound pass
    // then skips the collection so its events stop arriving, and eXo believes
    // it may delete a calendar it never created.
    Calendar materialised = calendar(1L, USER, ANCHOR, "FRANCOIS");
    givenPersonalCalendars(materialised);
    CalendarSync remote = new CalendarSync();
    remote.setId(9L);
    remote.setUserIdentityId(USER);
    remote.setServerId(SERVER);
    remote.setLocalCalendarSyncUid(ANCHOR);
    remote.setRemoteHref("/dav/calendars/john/7985AD30-3998-4662-9220-7C1EE899CB72");
    remote.setOrigin(SyncOrigin.REMOTE);
    remote.setStatus(CalendarSyncStatus.ACTIVE);
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(remote);

    List<CalendarSync> pairs = service.bindPersonalCalendars(USER, LOGIN);

    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString());
    verify(caldavSyncStorage, never()).savePair(any());
    assertEquals(SyncOrigin.REMOTE, pairs.get(0).getOrigin());
  }

  @Test
  public void aListingThatOmitsAKnownCollectionIsNotProofItIsGone() {
    // Observed live: a collection vanished from an account's home for a
    // quarter of an hour and came back. The answer to "it is not there" is to
    // create it, and on a server that keeps both the user ends up with two
    // calendars where they had one.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    givenServerCalendars(List.of(), List.of());
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(activePair());
    when(calDavClient.readCalendar(any(), anyString(), anyString(), anyString()))
                                                                                .thenReturn(collection(WANTED));

    List<CalendarSync> pairs = service.bindPersonalCalendars(USER, LOGIN);

    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString());
    assertEquals(CalendarSyncStatus.ACTIVE, pairs.get(0).getStatus());
  }

  @Test
  public void aCollectionThatIsGenuinelyGoneIsCreatedAgain() {
    // The guard must not become a reason never to recreate anything.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    givenServerCalendars(List.of(), List.of(collection(WANTED)));
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(activePair());
    when(calDavClient.readCalendar(any(), anyString(), anyString(), anyString())).thenReturn(null);
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    service.bindPersonalCalendars(USER, LOGIN);

    verify(calDavClient).mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString());
  }

  @Test
  public void aServerThatCannotBeAskedIsNotTakenAsAYes() {
    // An unreachable server is not evidence either way, and reading it as
    // "still there" would leave a binding pointing at something nobody has
    // confirmed.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    givenServerCalendars(List.of(), List.of(collection(WANTED)));
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(activePair());
    when(calDavClient.readCalendar(any(), anyString(), anyString(), anyString()))
                                                                                .thenThrow(new CalDavException("down"));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    service.bindPersonalCalendars(USER, LOGIN);

    verify(calDavClient).mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString());
  }

  /**
   * @return an active binding at the derived path
   */
  private CalendarSync activePair() {
    CalendarSync pair = new CalendarSync();
    pair.setId(3L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setLocalCalendarSyncUid(ANCHOR);
    pair.setRemoteHref(WANTED);
    pair.setOrigin(SyncOrigin.EXO);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  @Test
  public void everyCollectionCreatedHereIsMarkedAsExoOwn() {
    // ORIGIN=EXO is what tells the inbound sweep to leave the collection
    // alone. Without it each one is materialised back as a second eXo
    // calendar, which this service pushes out as a third collection, and so
    // on — two features behaving correctly and feeding each other.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    givenServerCalendars(List.of(), List.of(collection(WANTED)));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    assertEquals(SyncOrigin.EXO, service.bindPersonalCalendars(USER, LOGIN).get(0).getOrigin());
  }

  @Test
  public void aTwoZeroOneThatCreatedNothingIsRefusalNotSuccess() {
    // One server answers 201 while creating nothing. Only reading the home
    // back settles it, and believing the status cost three rounds of wrong
    // diagnosis earlier in this migration.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    givenServerCalendars(List.of(), List.of());
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    assertEquals(CalendarSyncStatus.REMOTE_CREATE_REFUSED, service.bindPersonalCalendars(USER, LOGIN).get(0).getStatus());
  }

  @Test
  public void aRefusedServerIsNeverOfferedAnExistingCalendarInstead() {
    // The rule that separates this from the mirror.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    givenServerCalendars(List.of(collection("/dav/calendars/john/personal/")),
                         List.of(collection("/dav/calendars/john/personal/")));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(403,
                                                                                                                                   List.of()));

    CalendarSync pair = service.bindPersonalCalendars(USER, LOGIN).get(0);

    assertEquals(CalendarSyncStatus.REMOTE_CREATE_REFUSED, pair.getStatus());
    // Never bound to the calendar the user already had: its events and this
    // calendar's would mix, with nothing recording which came from where.
    assertEquals(WANTED, pair.getRemoteHref());
  }

  @Test
  public void aServerThatSaidNoIsNotAskedAgainOnEverySync() {
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of());
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(refusedPair());

    service.bindPersonalCalendars(USER, LOGIN);

    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString());
  }

  @Test
  public void aBindingLostToARestoreIsFoundAgainByItsPath() {
    // The anchor lives in the collection path, so nothing stored is needed to
    // recognise it. A pair row lost to a restore rebinds instead of creating a
    // second collection beside the first.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(collection(WANTED)));
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(null);

    CalendarSync pair = service.bindPersonalCalendars(USER, LOGIN).get(0);

    assertEquals(CalendarSyncStatus.ACTIVE, pair.getStatus());
    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString());
  }

  @Test
  public void aSpaceCalendarIsNotGivenACollectionInSomeonesAccount() {
    // Its events already travel to the dedicated mirror, and a shared calendar
    // filed in one member's personal account is visible to that member alone.
    givenPersonalCalendars(calendar(2L, 999L, "space-uid", "Marketing"));
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of());

    assertTrue(service.bindPersonalCalendars(USER, LOGIN).isEmpty());
    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString());
  }

  @Test
  public void aCalendarWithNoAnchorIsSkippedRatherThanBoundWrongly() {
    // Binding on the calendar id instead would break the first time a restore
    // renumbers it, and a wrong binding writes one calendar's events into
    // another's collection.
    givenPersonalCalendars(calendar(1L, USER, null, "Work"));
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of());

    assertTrue(service.bindPersonalCalendars(USER, LOGIN).isEmpty());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  @Test
  public void anAccountThatIsNotConnectedBindsNothing() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    assertTrue(service.bindPersonalCalendars(USER, LOGIN).isEmpty());
    verify(calDavClient, never()).discoverCalendarHome(any(), anyString(), anyString());
  }

  @Test
  public void aServerThatCannotBeListedBindsNothingRatherThanFailing() {
    when(calDavClient.discoverCalendarHome(any(), anyString(), anyString()))
                                                                            .thenThrow(new org.exoplatform.caldav.client.CalDavException("unreachable"));

    assertTrue(service.bindPersonalCalendars(USER, LOGIN).isEmpty());
  }

  @Test
  public void severalPersonalCalendarsEachGetTheirOwn() {
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"), calendar(2L, USER, "second-uid", "Private"));
    givenServerCalendars(List.of(),
                         List.of(collection(WANTED)),
                         List.of(collection(WANTED)),
                         List.of(collection(WANTED), collection("/dav/calendars/john/exo-cal-second-uid/")));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    assertEquals(2, service.bindPersonalCalendars(USER, LOGIN).size());
    verify(calDavClient, times(2)).mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString());
  }

  /**
   * The calendars agenda answers with for this user.
   *
   * @param calendars what agenda holds
   */
  @SuppressWarnings("unchecked")
  private void givenPersonalCalendars(Calendar... calendars) {
    try {
      when(agendaCalendarService.getCalendars(anyInt(), anyInt(), eq(LOGIN))).thenReturn(List.of(calendars));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The successive answers of the server's calendar listing, one per call.
   *
   * @param answers what each listing returns, in order
   */
  @SafeVarargs
  private void givenServerCalendars(List<CalendarCollection>... answers) {
    var stub = when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString()));
    for (List<CalendarCollection> answer : answers) {
      stub = stub.thenReturn(answer);
    }
  }

  /**
   * An int matcher, kept here so the stubs above read as statements.
   *
   * @return any int
   */
  private int anyInt() {
    return org.mockito.ArgumentMatchers.anyInt();
  }

  /**
   * A listed collection.
   *
   * @param href its path
   * @return the collection
   */
  private CalendarCollection collection(String href) {
    return new CalendarCollection(href, "listed", null, null, null, true);
  }

  /**
   * An eXo calendar.
   *
   * @param id its technical identifier
   * @param ownerId whose it is
   * @param syncUid its immutable anchor
   * @param title what the user called it
   * @return the calendar
   */
  private Calendar calendar(long id, long ownerId, String syncUid, String title) {
    Calendar calendar = new Calendar();
    calendar.setId(id);
    calendar.setOwnerId(ownerId);
    calendar.setSyncUid(syncUid);
    calendar.setTitle(title);
    return calendar;
  }

  /**
   * A pair a server has already refused to create.
   *
   * @return the pair
   */
  private CalendarSync refusedPair() {
    CalendarSync pair = new CalendarSync();
    pair.setId(3L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setLocalCalendarSyncUid(ANCHOR);
    pair.setRemoteHref(WANTED);
    pair.setOrigin(SyncOrigin.EXO);
    pair.setStatus(CalendarSyncStatus.REMOTE_CREATE_REFUSED);
    return pair;
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
}
