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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.CalendarHome;
import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.MkCalendarResult;
import org.exoplatform.caldav.client.PropPatchResult;
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

  /**
   * The account on the CalDAV server, deliberately NOT the eXo login: the two
   * are different identities, and a test where they share a string cannot tell
   * which one a call was made with.
   */
  private static final String        DAV_ACCOUNT = "john@dav.example";

  private static final String        HOME   = "/dav/calendars/john/";

  /** Who the account is on the server, as the discovery answers it (EXO-90243). */
  private static final String        ACCOUNT_PRINCIPAL = "/dav/principals/john%40dav.example/";

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

  @Mock
  private CaldavConnectionIdentityService caldavConnectionIdentityService;

  @InjectMocks
  private CaldavOutboundService      service;

  @BeforeEach
  public void connectAnAccount() {
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
    lenient().when(calDavClient.discoverHome(any())).thenReturn(new CalendarHome(ACCOUNT_PRINCIPAL, HOME));
    lenient().when(caldavSyncStorage.savePair(any())).thenAnswer(invocation -> invocation.getArgument(0));
    // The stand-in server takes every rename and, unless a test says
    // otherwise, cannot be read back — the pessimistic default, so that no
    // test passes on a read-back it did not arrange.
    lenient().when(calDavClient.setDisplayName(any(), anyString(), anyString()))
             .thenReturn(new PropPatchResult(207, List.of()));
  }

  @Test
  public void aPersonalCalendarGetsACollectionNamedAfterItsAnchor() throws Exception {
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    givenServerCalendars(List.of(), List.of(collection(WANTED)));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    List<CalendarSync> pairs = service.bindPersonalCalendars(USER, LOGIN);

    // The endpoint is asked for THIS user, by their eXo login: the account in
    // the URL comes from their credentials provider, and nothing else in this
    // suite would notice if it came from somewhere else.
    verify(calDavClient).endpoint(SERVER, LOGIN);

    ArgumentCaptor<String> href = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).mkCalendar(any(), href.capture(), anyString(), any());
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
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    service.bindPersonalCalendars(USER, LOGIN);

    ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).mkCalendar(any(), anyString(), displayName.capture(), any());
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
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    service.bindPersonalCalendars(USER, LOGIN);

    ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).mkCalendar(any(), anyString(), displayName.capture(), any());
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
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    service.bindPersonalCalendars(USER, LOGIN);

    ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).mkCalendar(any(), anyString(), displayName.capture(), any());
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

    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any());
    verify(caldavSyncStorage, never()).savePair(any());
    assertEquals(SyncOrigin.REMOTE, pairs.get(0).getOrigin());
  }

  /**
   * A calendar adopted from another eXo deployment's collection is not pushed
   * back out as a collection of its own — the loop the prefix skip was
   * written against does not follow from adopting it.
   */
  @Test
  public void aCalendarAdoptedFromAnotherDeploymentsCollectionIsNeverPushedBackOut() {
    // The safety argument behind EXO-90226, verified rather than trusted. The
    // materialisation used to refuse every collection under eXo's prefix so
    // that B materialising A's collection could not push the result out as a
    // new collection for A to materialise in turn. Adopting a collection
    // ANOTHER deployment minted produces a REMOTE-bound calendar, and this
    // guard is what stops the loop one layer down: the calendar exists
    // because a collection was materialised into it, and it is never given a
    // second collection — not even when the server lists the adopted one
    // right next to where eXo would create it.
    String foreign = "/dav/calendars/john/exo-cal-fd3fe75f-58f9-49e5-93d0-85f63b24a807/";
    Calendar adopted = calendar(1L, USER, ANCHOR, "Perso");
    givenPersonalCalendars(adopted);
    givenServerCalendars(List.of(collection(foreign, "Perso")));
    CalendarSync remote = new CalendarSync();
    remote.setId(9L);
    remote.setUserIdentityId(USER);
    remote.setServerId(SERVER);
    remote.setLocalCalendarSyncUid(ANCHOR);
    remote.setRemoteHref(foreign);
    remote.setOrigin(SyncOrigin.REMOTE);
    remote.setStatus(CalendarSyncStatus.ACTIVE);
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(remote);

    List<CalendarSync> pairs = service.bindPersonalCalendars(USER, LOGIN);

    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any());
    verify(calDavClient, never()).readCalendar(any(), anyString());
    verify(caldavSyncStorage, never()).savePair(any());
    assertEquals(1, pairs.size());
    assertEquals(SyncOrigin.REMOTE, pairs.get(0).getOrigin());
    assertEquals(foreign, pairs.get(0).getRemoteHref());
  }

  /**
   * The anchor is read from the slug alone, wherever the server lists the
   * collection.
   */
  @Test
  public void theAnchorIsReadFromTheSlugWhereverTheCollectionIsListed() {
    assertEquals(ANCHOR, CaldavOutboundService.anchorOf(WANTED));
    assertEquals(ANCHOR, CaldavOutboundService.anchorOf("/dav/calendars/john/exo-cal-c0ffee-uid"));
    // BlueMind republishes eXo's collections under another parent; the slug
    // is the part of the path that survives, and it is the only part read.
    assertEquals(ANCHOR, CaldavOutboundService.anchorOf("https://dav.example/dav/calendars/publish/exo-cal-c0ffee-uid/"));
    assertNull(CaldavOutboundService.anchorOf("/dav/calendars/john/private/"));
    assertNull(CaldavOutboundService.anchorOf("/dav/calendars/john/exo-meetings/"));
    // The prefix with nothing after it names no calendar.
    assertNull(CaldavOutboundService.anchorOf("/dav/calendars/john/exo-cal-/"));
    assertNull(CaldavOutboundService.anchorOf(null));
    assertNull(CaldavOutboundService.anchorOf(""));
    assertTrue(CaldavOutboundService.isExoCreated(WANTED));
    assertFalse(CaldavOutboundService.isExoCreated("/dav/calendars/john/exo-cal-/"));
  }

  /**
   * A collection is this deployment's when its slug is eXo's and a user here
   * holds the calendar the anchor names.
   */
  @Test
  public void aCollectionIsThisDeploymentsWhenAUserHereHoldsItsAnchor() {
    when(caldavSyncStorage.isExoCalendarOnServer(SERVER, ANCHOR)).thenReturn(true, false);

    assertTrue(service.isMintedByThisDeployment(SERVER, WANTED), "a colleague's calendar, or one's own");
    // The anchor answered, so the path is not asked: that arm walks the
    // table and exists for the collection the anchor cannot recognise.
    verify(caldavSyncStorage, never()).isExoCollectionOnServer(anyLong(), anyString());
    assertFalse(service.isMintedByThisDeployment(SERVER, WANTED), "another eXo deployment's");
    // A path that is not eXo's asks nothing: there is no anchor to ask about,
    // and no eXo records a collection it did not mint.
    assertFalse(service.isMintedByThisDeployment(SERVER, "/dav/calendars/john/private/"));
    verify(caldavSyncStorage, times(2)).isExoCalendarOnServer(anyLong(), anyString());
    verify(caldavSyncStorage, times(1)).isExoCollectionOnServer(anyLong(), anyString());
  }

  /**
   * A collection the server republished under a slug that is not its anchor
   * is still this deployment's when a pair here records that path.
   */
  @Test
  public void aCollectionRepublishedUnderAnotherSlugIsThisDeploymentsByItsRecordedPath() {
    // The shape EXO-89590 pinned against BlueMind, in the sweep's own suite:
    // the prefix kept, the slug replaced by a name of the server's own. The
    // slug then carries no anchor any pair here holds, and read by the anchor
    // alone the deployment would call its own collection another eXo's and
    // adopt it — a second calendar for one it already has. The path a pair
    // records is what still says whose it is. Asked account-wide like the
    // anchor, so a colleague's republished collection answers too.
    when(caldavSyncStorage.isExoCalendarOnServer(SERVER, "renamed-by-the-server")).thenReturn(false);
    when(caldavSyncStorage.isExoCollectionOnServer(SERVER, CaldavSyncServiceTest.RENAMED_BY_THE_SERVER)).thenReturn(true);

    assertTrue(service.isMintedByThisDeployment(SERVER, CaldavSyncServiceTest.RENAMED_BY_THE_SERVER),
               "the anchor is the server's word, the recorded path is still ours");

    verify(caldavSyncStorage).isExoCalendarOnServer(SERVER, "renamed-by-the-server");
    verify(caldavSyncStorage).isExoCollectionOnServer(SERVER, CaldavSyncServiceTest.RENAMED_BY_THE_SERVER);
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
    when(calDavClient.readCalendar(any(), anyString()))
                                                                                .thenReturn(collection(WANTED));

    List<CalendarSync> pairs = service.bindPersonalCalendars(USER, LOGIN);

    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any());
    assertEquals(CalendarSyncStatus.ACTIVE, pairs.get(0).getStatus());
  }

  @Test
  public void aCollectionThatIsGenuinelyGoneIsCreatedAgain() {
    // The guard must not become a reason never to recreate anything.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    givenServerCalendars(List.of(), List.of(collection(WANTED)));
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(activePair());
    when(calDavClient.readCalendar(any(), anyString())).thenReturn(null);
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    service.bindPersonalCalendars(USER, LOGIN);

    verify(calDavClient).mkCalendar(any(), anyString(), anyString(), any());
  }

  @Test
  public void aServerThatCannotBeAskedIsNotTakenAsAYes() {
    // An unreachable server is not evidence either way, and reading it as
    // "still there" would leave a binding pointing at something nobody has
    // confirmed.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    givenServerCalendars(List.of(), List.of(collection(WANTED)));
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(activePair());
    when(calDavClient.readCalendar(any(), anyString()))
                                                                                .thenThrow(new CalDavException("down"));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    service.bindPersonalCalendars(USER, LOGIN);

    verify(calDavClient).mkCalendar(any(), anyString(), anyString(), any());
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
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any()))
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
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any()))
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
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any()))
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
    when(calDavClient.listCalendars(any(), eq(HOME))).thenReturn(List.of());
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(refusedPair());

    service.bindPersonalCalendars(USER, LOGIN);

    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any());
  }

  @Test
  public void aBindingLostToARestoreIsFoundAgainByItsPath() {
    // The anchor lives in the collection path, so nothing stored is needed to
    // recognise it. A pair row lost to a restore rebinds instead of creating a
    // second collection beside the first.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    when(calDavClient.listCalendars(any(), eq(HOME))).thenReturn(List.of(collection(WANTED)));
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(null);

    CalendarSync pair = service.bindPersonalCalendars(USER, LOGIN).get(0);

    assertEquals(CalendarSyncStatus.ACTIVE, pair.getStatus());
    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any());
  }

  @Test
  public void aSpaceCalendarIsNotGivenACollectionInSomeonesAccount() {
    // Its events already travel to the dedicated mirror, and a shared calendar
    // filed in one member's personal account is visible to that member alone.
    givenPersonalCalendars(calendar(2L, 999L, "space-uid", "Marketing"));
    when(calDavClient.listCalendars(any(), eq(HOME))).thenReturn(List.of());

    assertTrue(service.bindPersonalCalendars(USER, LOGIN).isEmpty());
    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any());
  }

  @Test
  public void aCalendarWithNoAnchorIsSkippedRatherThanBoundWrongly() {
    // Binding on the calendar id instead would break the first time a restore
    // renumbers it, and a wrong binding writes one calendar's events into
    // another's collection.
    givenPersonalCalendars(calendar(1L, USER, null, "Work"));
    when(calDavClient.listCalendars(any(), eq(HOME))).thenReturn(List.of());

    assertTrue(service.bindPersonalCalendars(USER, LOGIN).isEmpty());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  @Test
  public void anAccountThatIsNotConnectedBindsNothing() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    assertTrue(service.bindPersonalCalendars(USER, LOGIN).isEmpty());
    verify(calDavClient, never()).discoverHome(any());
    verify(caldavConnectionIdentityService, never()).recordPrincipal(anyLong(), anyLong(), any());
  }

  @Test
  public void aServerThatCannotBeListedBindsNothingRatherThanFailing() {
    when(calDavClient.discoverHome(any()))
                                                                            .thenThrow(new org.exoplatform.caldav.client.CalDavException("unreachable"));

    assertTrue(service.bindPersonalCalendars(USER, LOGIN).isEmpty());
  }

  @Test
  public void anUnreachableServerIsHandedBackToTheCallerRatherThanAbsorbed() {
    // EXO-89806. Absorbed, this returned an empty list that reads exactly like
    // "this account has no calendars", so the connection went on to establish
    // the mirror, then synchronise, then read the mirror back — four more
    // credential-bearing requests, each rediscovering the same absent server.
    // A server that is not there is settled after one attempt, and the caller
    // is the only party that can act on it.
    when(calDavClient.discoverHome(any()))
                                                                            .thenThrow(new CalDavUnreachableException("gateway said 502"));

    assertThrows(CalDavUnreachableException.class, () -> service.bindPersonalCalendars(USER, LOGIN));
  }

  @Test
  public void refusedCredentialsAreHandedBackToTheCallerRatherThanAbsorbed() {
    // EXO-89806, and the older bug it uncovers: CalDavAuthenticationException
    // is a CalDavException, so absorbing the parent here swallowed it too —
    // the pass's own "pause this account rather than retry a stale password"
    // branch could never fire from the first step that meets the server.
    when(calDavClient.discoverHome(any()))
                                                                            .thenThrow(new CalDavAuthenticationException("401"));

    assertThrows(CalDavAuthenticationException.class, () -> service.bindPersonalCalendars(USER, LOGIN));
  }

  /**
   * The first discovery of a connection or a pass records who the account is
   * on its server, under the server key its pairs use, before the calendars
   * are even listed (EXO-90243).
   */
  @Test
  public void bindingRecordsWhoTheAccountIsOnItsServer() {
    givenPersonalCalendars(calendar(1L, USER, null, "Work"));
    when(calDavClient.listCalendars(any(), eq(HOME))).thenReturn(List.of());

    service.bindPersonalCalendars(USER, LOGIN);

    verify(caldavConnectionIdentityService).recordPrincipal(USER, SERVER, ACCOUNT_PRINCIPAL);
  }

  /**
   * An account attached before registrations existed is recorded under server
   * zero, the key its pairs carry.
   */
  @Test
  public void aLegacyAccountIsRecordedUnderServerZero() {
    CaldavUserSetting legacy = settings();
    legacy.setServerId(null);
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(legacy);
    when(calDavClient.endpoint(null, LOGIN)).thenReturn(endpoint);
    givenPersonalCalendars(calendar(1L, USER, null, "Work"));
    when(calDavClient.listCalendars(any(), eq(HOME))).thenReturn(List.of());

    service.bindPersonalCalendars(USER, LOGIN);

    verify(caldavConnectionIdentityService).recordPrincipal(USER, 0L, ACCOUNT_PRINCIPAL);
  }

  /**
   * Who the account is does not depend on whether its calendars can be
   * listed: the principal is recorded even when the listing then fails.
   */
  @Test
  public void thePrincipalIsRecordedEvenWhenTheListingThenFails() {
    when(calDavClient.listCalendars(any(), eq(HOME))).thenThrow(new org.exoplatform.caldav.client.CalDavException("500"));

    assertTrue(service.bindPersonalCalendars(USER, LOGIN).isEmpty());

    verify(caldavConnectionIdentityService).recordPrincipal(USER, SERVER, ACCOUNT_PRINCIPAL);
  }

  /**
   * A discovery that failed recorded nobody: nothing was learnt about the
   * account.
   */
  @Test
  public void aDiscoveryThatFailedRecordsNothing() {
    when(calDavClient.discoverHome(any())).thenThrow(new org.exoplatform.caldav.client.CalDavException("unreachable"));

    service.bindPersonalCalendars(USER, LOGIN);

    verify(caldavConnectionIdentityService, never()).recordPrincipal(anyLong(), anyLong(), any());
  }

  @Test
  public void aCalendarRenamedInExoRenamesItsCollectionAndTheNameIsReadBack() {
    // Samuel's report on EXO-89528: renamed in eXo, still the old name on
    // BlueMind. The name was written once, inside MKCALENDAR, and nothing
    // ever wrote it again — a sweep found the collection by path, re-recorded
    // the pair and left the name alone.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work, renamed"));
    givenServerCalendars(List.of(collection(WANTED, "Work")));
    when(calDavClient.readCalendar(any(), eq(WANTED))).thenReturn(collection(WANTED,
                                                                                                       "Work, renamed"));

    List<CalendarSync> pairs = service.bindPersonalCalendars(USER, LOGIN);

    InOrder wire = inOrder(calDavClient);
    wire.verify(calDavClient).setDisplayName(any(), eq(WANTED), eq("Work, renamed"));
    // The status is never the proof: the name is read back after the write.
    wire.verify(calDavClient).readCalendar(any(), eq(WANTED));
    assertEquals(CalendarSyncStatus.ACTIVE, pairs.get(0).getStatus());
    assertEquals(WANTED, pairs.get(0).getRemoteHref(), "the name is data; the path stays the identity");
  }

  @Test
  public void aCollectionAlreadyUnderTheCalendarsNameIsLeftAlone() {
    // The common case, every sweep, for every calendar nobody renamed: the
    // listing already carries the name, so it costs no request at all.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    givenServerCalendars(List.of(collection(WANTED, "Work")));

    service.bindPersonalCalendars(USER, LOGIN);

    verify(calDavClient, never()).setDisplayName(any(), anyString(), anyString());
    verify(calDavClient, never()).readCalendar(any(), anyString());
  }

  @Test
  public void aBindingRecoveredByPathAfterAReconnectGetsTheCalendarsCurrentName() {
    // The second half of the report: deleting and re-adding the account did
    // not help either. The binding is recovered by path, which is right —
    // and finds the same collection under the same stale name, which the
    // recovery must now put right rather than inherit.
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(null);
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work, renamed"));
    givenServerCalendars(List.of(collection(WANTED, "Work")));
    when(calDavClient.readCalendar(any(), eq(WANTED))).thenReturn(collection(WANTED,
                                                                                                       "Work, renamed"));

    List<CalendarSync> pairs = service.bindPersonalCalendars(USER, LOGIN);

    verify(calDavClient).setDisplayName(any(), eq(WANTED), eq("Work, renamed"));
    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any());
    assertEquals(CalendarSyncStatus.ACTIVE, pairs.get(0).getStatus());
  }

  @Test
  public void aRenameTheServerAcceptedButDidNotApplyLeavesTheBindingStanding() {
    // A 207 with a granting propstat over a name that did not change — the
    // MKCALENDAR lesson applied to the rename. The read-back tells the
    // administrator; the binding is untouched, because the name is data and
    // an unrenamed calendar that syncs beats a renamed one that does not.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work, renamed"));
    givenServerCalendars(List.of(collection(WANTED, "Work")));
    when(calDavClient.readCalendar(any(), eq(WANTED))).thenReturn(collection(WANTED, "Work"));

    List<CalendarSync> pairs = service.bindPersonalCalendars(USER, LOGIN);

    verify(calDavClient).readCalendar(any(), eq(WANTED));
    assertEquals(CalendarSyncStatus.ACTIVE, pairs.get(0).getStatus());
    assertEquals(WANTED, pairs.get(0).getRemoteHref());
  }

  @Test
  public void aRenameTheServerWillNotTakeIsNotAnError() {
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work, renamed"));
    givenServerCalendars(List.of(collection(WANTED, "Work")));
    when(calDavClient.setDisplayName(any(), eq(WANTED), anyString()))
                                                                                             .thenThrow(new CalDavException("403"));

    List<CalendarSync> pairs = service.bindPersonalCalendars(USER, LOGIN);

    assertEquals(CalendarSyncStatus.ACTIVE, pairs.get(0).getStatus());
    verify(calDavClient, never()).readCalendar(any(), anyString());
  }

  @Test
  public void aCollectionCreatedWithoutItsNameIsNamedOnTheSpot() {
    // MKCALENDAR carried the name; the listing that confirms the creation
    // says the server kept something else. What the server holds is what
    // counts, so the same reconciliation runs on a collection just created.
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"));
    givenServerCalendars(List.of(), List.of(collection(WANTED, "Untitled")));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));
    when(calDavClient.readCalendar(any(), eq(WANTED))).thenReturn(collection(WANTED, "Work"));

    List<CalendarSync> pairs = service.bindPersonalCalendars(USER, LOGIN);

    verify(calDavClient).setDisplayName(any(), eq(WANTED), eq("Work"));
    assertEquals(CalendarSyncStatus.ACTIVE, pairs.get(0).getStatus());
  }

  @Test
  public void severalPersonalCalendarsEachGetTheirOwn() {
    givenPersonalCalendars(calendar(1L, USER, ANCHOR, "Work"), calendar(2L, USER, "second-uid", "Private"));
    givenServerCalendars(List.of(),
                         List.of(collection(WANTED)),
                         List.of(collection(WANTED)),
                         List.of(collection(WANTED), collection("/dav/calendars/john/exo-cal-second-uid/")));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    assertEquals(2, service.bindPersonalCalendars(USER, LOGIN).size());
    verify(calDavClient, times(2)).mkCalendar(any(), anyString(), anyString(), any());
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
    var stub = when(calDavClient.listCalendars(any(), eq(HOME)));
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
    return collection(href, "listed");
  }

  /**
   * A listed collection, as the server names it.
   *
   * @param href its path
   * @param displayName what the server says it is called
   * @return the collection
   */
  private CalendarCollection collection(String href, String displayName) {
    return new CalendarCollection(href, displayName, null, null, null, true);
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
    setting.setUsername(DAV_ACCOUNT);
    setting.setPassword("secret");
    setting.setServerId(SERVER);
    return setting;
  }

  // ------------------------------------ whose a listed collection is, EXO-90234

  /** The account's own principal, as the server names it. */
  private static final String        PRINCIPAL = "/dav/principals/john/";

  /** A colleague's principal, as the server names it. */
  private static final String        ALICE     = "/dav/principals/alice/";

  /**
   * The BlueMind shape of EXO-90234: a colleague's eXo calendar the user
   * subscribed to, listed under the user's own home with the user named as
   * owner and the full privilege set — nothing the server says tells it from
   * the user's own calendar. The slug does: its anchor is a calendar this
   * deployment exported for another user, and none of this user's own.
   */
  @Test
  public void aColleaguesExoCalendarUnderTheUsersOwnHomeIsTheColleaguesByThisDeploymentsWord() {
    when(caldavSyncStorage.isExoCalendarOnServer(SERVER, ANCHOR)).thenReturn(true);

    CollectionOwnership ownership = service.ownershipOf(SERVER, PRINCIPAL, List.of(), owned(WANTED, PRINCIPAL, true, true));

    assertEquals(CollectionOwnership.COLLEAGUES_EXO_CALENDAR, ownership);
    assertTrue(ownership.isShared());
  }

  /**
   * The Stalwart shape: the same colleague's eXo calendar, listed at her
   * path with her as owner and read-only. Both witnesses speak; the
   * deployment is heard first, so the answer names the more useful fact — a
   * calendar of this deployment, another user's — rather than merely
   * "somebody else's".
   */
  @Test
  public void aColleaguesExoCalendarAtHerPathIsStillTheColleaguesByThisDeploymentsWord() {
    when(caldavSyncStorage.isExoCalendarOnServer(SERVER, ANCHOR)).thenReturn(true);

    CollectionOwnership ownership = service.ownershipOf(SERVER,
                                                        PRINCIPAL,
                                                        List.of(),
                                                        owned("/dav/calendars/alice/exo-cal-c0ffee-uid/", ALICE, true, false));

    assertEquals(CollectionOwnership.COLLEAGUES_EXO_CALENDAR, ownership);
  }

  /**
   * The user's own exported calendar, met again under a path none of their
   * pairs record — BlueMind republishes eXo's collections under another
   * parent. The anchor in the slug is one of their own EXO pairs', which is
   * answered from the pairs in hand: the database is never asked, and the
   * answer is "yours, already in eXo", not a share.
   */
  @Test
  public void theUsersOwnExportedCalendarUnderAnotherPathIsTheirOwnWithoutAskingTheDatabase() {
    CalendarSync exported = exportedPair(ANCHOR, WANTED);

    CollectionOwnership ownership = service.ownershipOf(SERVER,
                                                        PRINCIPAL,
                                                        List.of(exported),
                                                        owned("/dav/calendars/publish/exo-cal-c0ffee-uid/", PRINCIPAL, true, true));

    assertEquals(CollectionOwnership.OWN_EXO_CALENDAR, ownership);
    assertFalse(ownership.isShared());
    verify(caldavSyncStorage, never()).isExoCalendarOnServer(anyLong(), anyString());
    verify(caldavSyncStorage, never()).isExoCollectionOnServer(anyLong(), anyString());
  }

  /**
   * The other arm of "the user's own": the server republished their
   * collection under a slug that is not its anchor (EXO-89590), and the path
   * one of their EXO pairs records is what still says whose it is — the
   * shape {@link #aCollectionRepublishedUnderAnotherSlugIsThisDeploymentsByItsRecordedPath}
   * pins account-wide, answered here from the user's own pairs first.
   */
  @Test
  public void theUsersOwnCollectionRepublishedUnderAnotherSlugIsTheirOwnByItsRecordedPath() {
    CalendarSync exported = exportedPair("anchor-mine", CaldavSyncServiceTest.RENAMED_BY_THE_SERVER);

    CollectionOwnership ownership = service.ownershipOf(SERVER,
                                                        PRINCIPAL,
                                                        List.of(exported),
                                                        owned(CaldavSyncServiceTest.RENAMED_BY_THE_SERVER, PRINCIPAL, true, true));

    assertEquals(CollectionOwnership.OWN_EXO_CALENDAR, ownership);
    verify(caldavSyncStorage, never()).isExoCollectionOnServer(anyLong(), anyString());
  }

  /**
   * Provenance, not the path, says the user exported a collection. A REMOTE
   * pair of theirs at the very path — the binding an adopted collection gets
   * — is not an export, and does not make the collection their own eXo
   * calendar; the account-wide question is asked as for any other prefixed
   * collection. (The sweep and the read-through filter a bound collection
   * out before classifying, so this is the classifier's own contract, pinned
   * on its own.)
   */
  @Test
  public void aRemotePairAtThePathIsNotAnExport() {
    CalendarSync adopted = exportedPair(ANCHOR, WANTED);
    adopted.setOrigin(SyncOrigin.REMOTE);
    when(caldavSyncStorage.isExoCalendarOnServer(SERVER, ANCHOR)).thenReturn(false);
    when(caldavSyncStorage.isExoCollectionOnServer(SERVER, CaldavSyncStorage.canonicalHref(WANTED))).thenReturn(false);

    assertEquals(CollectionOwnership.OWN, service.ownershipOf(SERVER, PRINCIPAL, List.of(adopted), owned(WANTED, PRINCIPAL, true, true)));
  }

  /**
   * A collection another eXo deployment minted into the account — its anchor
   * known to no pair here — is the user's own to this deployment, and stays
   * adopted as a remote calendar (EXO-90226), whatever the server says about
   * owner and privileges when it says nothing against it. The BlueMind facts
   * are on it on purpose: the user as owner, write granted, exactly what a
   * colleague's subscribed share also carries there, so the two are told
   * apart by the pair table alone.
   */
  @Test
  public void anotherDeploymentsExoCalendarIsTheUsersOwnToAdopt() {
    when(caldavSyncStorage.isExoCalendarOnServer(SERVER, ANCHOR)).thenReturn(false);
    when(caldavSyncStorage.isExoCollectionOnServer(SERVER, CaldavSyncStorage.canonicalHref(WANTED))).thenReturn(false);

    assertEquals(CollectionOwnership.OWN, service.ownershipOf(SERVER, PRINCIPAL, List.of(), owned(WANTED, PRINCIPAL, true, true)));
  }

  /**
   * The server's word is still heard for a prefixed collection this
   * deployment does not know: another deployment's calendar a colleague
   * shared read-only is a share by the server's word (EXO-90235), and is
   * classified so rather than dropped on its prefix.
   */
  @Test
  public void anotherDeploymentsExoCalendarTheServerSaysIsAnothersIsAShare() {
    when(caldavSyncStorage.isExoCalendarOnServer(SERVER, ANCHOR)).thenReturn(false);
    when(caldavSyncStorage.isExoCollectionOnServer(SERVER, CaldavSyncStorage.canonicalHref(WANTED))).thenReturn(false);

    assertEquals(CollectionOwnership.SHARED, service.ownershipOf(SERVER, PRINCIPAL, List.of(), owned(WANTED, ALICE, true, false)));
  }

  // ------------------------------------ who exported it, EXO-90237

  /**
   * The user behind a colleague's eXo calendar is named by the anchor arm
   * first — the same arm, in the same order, that made the boolean form say
   * yes — and the path arm is not asked when the anchor answered.
   */
  @Test
  public void theExportingUserIsNamedByTheAnchorFirst() {
    CalendarSync theirs = exportedPair(ANCHOR, WANTED);
    theirs.setUserIdentityId(6L);
    when(caldavSyncStorage.getExoCalendarPairOnServer(SERVER, ANCHOR)).thenReturn(theirs);

    assertEquals(6L, service.exportingUserOf(SERVER, "/dav/calendars/publish/exo-cal-c0ffee-uid/"),
                 "named by the anchor, under whatever parent the server lists it");
    verify(caldavSyncStorage, never()).getExoCollectionPairOnServer(anyLong(), anyString());
  }

  /**
   * When the slug is not the anchor, the user is named by the path the pair
   * records — the second arm, asked only after the first found nobody.
   */
  @Test
  public void theExportingUserIsNamedByTheRecordedPathWhenTheAnchorNamesNobody() {
    CalendarSync theirs = exportedPair("anchor-theirs", CaldavSyncServiceTest.RENAMED_BY_THE_SERVER);
    theirs.setUserIdentityId(6L);
    when(caldavSyncStorage.getExoCalendarPairOnServer(SERVER, "renamed-by-the-server")).thenReturn(null);
    when(caldavSyncStorage.getExoCollectionPairOnServer(SERVER, CaldavSyncServiceTest.RENAMED_BY_THE_SERVER)).thenReturn(theirs);

    assertEquals(6L, service.exportingUserOf(SERVER, CaldavSyncServiceTest.RENAMED_BY_THE_SERVER));
  }

  /**
   * Nobody is named for a path outside the prefix — the database is not
   * asked — nor for a prefixed collection no pair here stands behind.
   */
  @Test
  public void nobodyIsNamedForACollectionThisDeploymentDidNotMint() {
    assertNull(service.exportingUserOf(SERVER, "/dav/calendars/john/private/"), "not eXo's slug");
    verify(caldavSyncStorage, never()).getExoCalendarPairOnServer(anyLong(), anyString());
    verify(caldavSyncStorage, never()).getExoCollectionPairOnServer(anyLong(), anyString());

    // The path is handed to the storage as it came; the storage canonicalises.
    when(caldavSyncStorage.getExoCalendarPairOnServer(SERVER, ANCHOR)).thenReturn(null);
    when(caldavSyncStorage.getExoCollectionPairOnServer(SERVER, WANTED)).thenReturn(null);
    assertNull(service.exportingUserOf(SERVER, WANTED), "another deployment's: both arms found nobody");
  }

  /**
   * A collection outside eXo's prefix never costs the account-wide question:
   * the server's two signals are the whole of what decides it — a share when
   * the server says so, the user's own otherwise, and a silent server leaves
   * it the user's own.
   */
  @Test
  public void aCollectionOutsideExosPrefixIsDecidedByTheServerAlone() {
    assertEquals(CollectionOwnership.SHARED,
                 service.ownershipOf(SERVER, PRINCIPAL, List.of(), owned("/dav/calendars/alice/default/", ALICE, true, false)));
    assertEquals(CollectionOwnership.OWN,
                 service.ownershipOf(SERVER, PRINCIPAL, List.of(), owned("/dav/calendars/john/private/", PRINCIPAL, true, true)));
    assertEquals(CollectionOwnership.OWN,
                 service.ownershipOf(SERVER, PRINCIPAL, List.of(), collection("/dav/calendars/john/google/")),
                 "silence is not a signal: no owner, no privilege set, the user's own");
    // BlueMind's resource subscriptions — a pool vehicle, a room — are listed
    // as calendar:<uid> with a uid that is not the principal's, the user as
    // owner and the full set. Not read here, on purpose: whether such a
    // resource should be the user's calendar is an open product question,
    // and today it is materialised like any other collection.
    assertEquals(CollectionOwnership.OWN,
                 service.ownershipOf(SERVER,
                                     PRINCIPAL,
                                     List.of(),
                                     owned("/dav/calendars/john/calendar:7E3AE6F3-0000-0000-0000-000000000000/", PRINCIPAL, true, true)));
    verify(caldavSyncStorage, never()).isExoCalendarOnServer(anyLong(), anyString());
    verify(caldavSyncStorage, never()).isExoCollectionOnServer(anyLong(), anyString());
  }

  /**
   * @param href the collection path
   * @param owner the owner the server named, or null
   * @param privilegesAnswered whether the server answered a privilege set
   * @param writable whether that set grants write
   * @return a listed calendar with those ownership facts
   */
  private CalendarCollection owned(String href, String owner, boolean privilegesAnswered, boolean writable) {
    return new CalendarCollection(href, "listed", null, null, null, writable, Set.of("VEVENT"), owner, privilegesAnswered);
  }

  /**
   * @param anchor the calendar anchor the pair exports
   * @param href the collection path it records
   * @return an EXO pair of the user's on the server
   */
  private CalendarSync exportedPair(String anchor, String href) {
    CalendarSync pair = new CalendarSync();
    pair.setId(11L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setLocalCalendarSyncUid(anchor);
    pair.setRemoteHref(href);
    pair.setOrigin(SyncOrigin.EXO);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }
}
