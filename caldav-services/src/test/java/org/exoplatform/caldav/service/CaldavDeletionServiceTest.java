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

import static org.mockito.Mockito.verifyNoInteractions;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
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
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.caldav.model.HiddenCalendar;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.CalendarDeletionPlan;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarDeletionPlan;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncState;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.RemoteCalendar;
import org.exoplatform.caldav.model.RemoteCalendarsRead;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * The only irreversible outward-facing action in this design.
 *
 * <p>
 * Deleting a collection deletes every event in it, including events the user
 * added from their own phone that eXo never authored and cannot restore. Every
 * test here exists because of that, and the three that matter most are the
 * ordering, the atomicity of a failure, and the fact that disconnecting an
 * account deletes nothing at all.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavDeletionServiceTest {

  private static final long          USER     = 42L;

  private static final long          SERVER   = 7L;

  private static final String        LOGIN    = "john";

  /**
   * The account on the CalDAV server, deliberately NOT the eXo login: the two
   * are different identities, and a test where they share a string cannot tell
   * which one a call was made with.
   */
  private static final String        DAV_ACCOUNT = "john@dav.example";

  private static final long          CALENDAR = 11L;

  private static final String        ANCHOR   = "cal-anchor";

  private static final String        HREF     = "/dav/calendars/john/exo-cal-cal-anchor";

  /** The account's server, named in the warning so the user knows which one. */
  private static final String        URL      = "https://webmail.example.test/dav/";

  @Mock
  private CalDavClient               calDavClient;

  @Mock
  private CaldavConnectorStorage     caldavConnectorStorage;

  @Mock
  private CaldavSyncStorage          caldavSyncStorage;

  @Mock
  private AgendaCalendarService      agendaCalendarService;

  @Mock
  private CaldavServerService        caldavServerService;

  @Mock
  private CaldavSyncService          caldavSyncService;

  @Mock
  private CaldavReadService          caldavReadService;

  @Mock
  private CalDavEndpoint             endpoint;

  @InjectMocks
  private CaldavDeletionService      service;

  @BeforeEach
  public void connectAnAccount() {
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
    lenient().when(calDavClient.discoverCalendarHome(any())).thenReturn("/dav/calendars/john/");
    lenient().when(agendaCalendarService.getCalendarById(CALENDAR)).thenReturn(calendar());
  }

  @Test
  public void theRemoteSideGoesFirstAndTheLocalOneOnlyAfterIt() throws Exception {
    // The failable step runs while nothing has happened yet. The reverse order
    // can strand a collection on the server after the record that knew about
    // it is gone — an orphan nothing will ever find again, holding the user's
    // events.
    givenBoundCalendar();
    givenCollectionGoneAfterDelete();

    service.deleteRemoteCounterpart(USER, LOGIN, CALENDAR);

    // The endpoint is asked for THIS user, by their eXo login: the account in
    // the URL comes from their credentials provider, and nothing else in this
    // suite would notice if it came from somewhere else.
    verify(calDavClient).endpoint(SERVER, LOGIN);

    // Agenda deletes the calendar itself, after this returns without throwing.
    // What has to be true first is that the collection is gone AND the binding
    // that described it has been dropped — in that order, so a failure between
    // the two leaves a binding pointing at nothing rather than nothing
    // pointing at a collection.
    InOrder order = inOrder(calDavClient, caldavSyncStorage);
    order.verify(calDavClient).deleteCollection(any(), any());
    order.verify(caldavSyncStorage).deletePair(3L);
    verify(agendaCalendarService, never()).deleteCalendarById(anyLong(), anyString());
  }

  @Test
  public void thePairIsTakenOutOfSyncBeforeAnythingIsTouched() {
    // A background sweep finding this pair mid-deletion would push events into
    // a collection that is about to stop existing.
    givenBoundCalendar();
    givenCollectionGoneAfterDelete();

    service.deleteRemoteCounterpart(USER, LOGIN, CALENDAR);

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    assertEquals(CalendarSyncStatus.DELETING, saved.getValue().getStatus());
  }

  @Test
  public void aCollectionStillListedAfterwardsIsNotASuccessfulDeletion() throws Exception {
    // This migration has twice met a server that answers success while doing
    // nothing. A deletion that reported done while the collection still stood
    // would take the local calendar with it.
    givenBoundCalendar();
    when(calDavClient.listCalendars(any(), anyString())).thenReturn(List.of(collection(HREF)));

    CaldavPushException failure = assertThrows(CaldavPushException.class,
                                               () -> service.deleteRemoteCounterpart(USER, LOGIN, CALENDAR));

    assertEquals(CaldavDeletionService.NOTHING_DELETED, failure.getCode());
    verify(agendaCalendarService, never()).deleteCalendarById(anyLong(), anyString());
  }

  @Test
  public void aServerThatCannotBeReachedLeavesBothSidesUntouched() throws Exception {
    givenBoundCalendar();
    when(calDavClient.deleteCollection(any(), any()))
                                                                               .thenThrow(new CalDavException("unreachable"));

    CaldavPushException failure = assertThrows(CaldavPushException.class,
                                               () -> service.deleteRemoteCounterpart(USER, LOGIN, CALENDAR));

    assertEquals(CaldavDeletionService.NOTHING_DELETED, failure.getCode());
    verify(agendaCalendarService, never()).deleteCalendarById(anyLong(), anyString());
  }

  @Test
  public void aFailedDeletionPutsTheBindingBackToWorking() {
    // Left in DELETING the pair would be excluded from every future sync, so a
    // transient network failure would quietly stop synchronising a calendar
    // nobody deleted.
    givenBoundCalendar();
    when(calDavClient.deleteCollection(any(), any()))
                                                                               .thenThrow(new CalDavException("unreachable"));

    assertThrows(CaldavPushException.class, () -> service.deleteRemoteCounterpart(USER, LOGIN, CALENDAR));

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage, org.mockito.Mockito.times(2)).savePair(saved.capture());
    assertEquals(CalendarSyncStatus.ACTIVE, saved.getAllValues().get(1).getStatus());
  }

  @Test
  public void aCalendarTheUserMadeElsewhereIsNeverDeletedOnTheServer() throws Exception {
    // A REMOTE pair's collection is the user's own, made in their own client.
    // Deleting it in eXo is a decision about eXo.
    givenBoundCalendar(SyncOrigin.REMOTE, CalendarSyncStatus.ACTIVE);

    service.deleteRemoteCounterpart(USER, LOGIN, CALENDAR);

    verify(calDavClient, never()).deleteCollection(any(), any());
  }

  @Test
  public void aLocalOnlyDeletionLeavesATombstoneSoSyncDoesNotUndoIt() {
    // Without it the next sweep sees a remote collection eXo has no calendar
    // for and materialises it straight back, undoing the deletion in front of
    // the user.
    givenBoundCalendar(SyncOrigin.REMOTE, CalendarSyncStatus.ACTIVE);

    service.deleteRemoteCounterpart(USER, LOGIN, CALENDAR);

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    assertEquals(CalendarSyncStatus.LOCALLY_DELETED, saved.getValue().getStatus());
    verify(caldavSyncStorage, never()).deletePair(anyLong());
  }

  @Test
  public void choosingToKeepTheRemoteCalendarIsRecordedAsSuch() {
    // The escape hatch from the atomic rule: divergence between the two sides
    // is only ever chosen, named and recorded — never a side effect of a
    // failed deletion.
    givenBoundCalendar();

    service.keepRemoteCounterpart(USER, CALENDAR);

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    assertEquals(CalendarSyncStatus.EXO_ORPHANED, saved.getValue().getStatus());
    verify(calDavClient, never()).deleteCollection(any(), any());
  }

  @Test
  public void aSuccessfulDeletionLeavesNoBindingBehind() {
    givenBoundCalendar();
    givenCollectionGoneAfterDelete();

    service.deleteRemoteCounterpart(USER, LOGIN, CALENDAR);

    // Both sides are gone; the binding has nothing left to describe.
    verify(caldavSyncStorage).deleteObjects(3L);
    verify(caldavSyncStorage).deletePair(3L);
  }

  @Test
  public void disconnectingKeepsACalendarTheUserMadeInExo() {
    // The account was its destination, never its source. It stays, and only
    // its binding is paused: dropped, reconnecting the same account would
    // create a second collection beside the first.
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair(SyncOrigin.EXO, CalendarSyncStatus.ACTIVE)));

    service.freezeOnDisconnect(USER, SERVER, LOGIN);

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    assertEquals(CalendarSyncStatus.PAUSED, saved.getValue().getStatus());
    verify(caldavSyncStorage, never()).deletePair(anyLong());
  }

  @Test
  public void disconnectingKeepsACalendarExoMaterialisedAndOnlyPausesItsBinding() throws Exception {
    // EXO-89800, and the regression this whole change exists for. Disconnecting
    // used to delete the eXo calendar a collection was materialised into,
    // together with its events, its object mappings and its binding.
    // Reconnecting the same account then found no binding for the collection,
    // materialised it a SECOND time and imported every event again under new
    // identifiers — seen live as the same BlueMind collection becoming eXo
    // calendar 181 at 23:28 and eXo calendar 182 at 08:37 the next morning.
    //
    // The binding is paused instead, which is the rule a pushed calendar
    // already had, and for the reason its own comment gave: reconnecting the
    // same account must find its collection again instead of creating a second
    // one beside it.
    CalendarSync materialised = pair(SyncOrigin.REMOTE, CalendarSyncStatus.ACTIVE);
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(materialised));

    service.freezeOnDisconnect(USER, SERVER, LOGIN);

    verify(agendaCalendarService, never()).deleteCalendarById(anyLong(), anyString());
    verify(caldavSyncStorage, never()).deletePair(anyLong());
    verify(caldavSyncStorage, never()).deleteObjects(anyLong());
    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    assertEquals(CalendarSyncStatus.PAUSED, saved.getValue().getStatus());
  }

  @Test
  public void nothingOnTheServerIsTouchedByADisconnect() {
    // A user unlinking their account is saying "stop syncing", not "destroy
    // what is on my server" — whichever side made the calendar.
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair(SyncOrigin.REMOTE,
                                                                          CalendarSyncStatus.ACTIVE),
                                                                     pair(SyncOrigin.EXO,
                                                                          CalendarSyncStatus.ACTIVE)));

    service.freezeOnDisconnect(USER, SERVER, LOGIN);

    verify(calDavClient, never()).deleteCollection(any(), any());
  }

  @Test
  public void disconnectingNeverDeletesAnEventOrACalendarWhicheverSideMadeIt() throws Exception {
    // The stronger form of the pin above, over every origin at once: nothing
    // in eXo is destroyed by "stop syncing". A materialised calendar is an
    // ordinary agenda calendar — the user adds events to it and the connector
    // pushes those back — so deleting it took work of theirs that existed
    // nowhere else, and broke every activity, notification and link pointing
    // at the events it held.
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair(SyncOrigin.REMOTE,
                                                                          CalendarSyncStatus.ACTIVE),
                                                                     pair(SyncOrigin.EXO,
                                                                          CalendarSyncStatus.ACTIVE),
                                                                     pair(SyncOrigin.MIRROR,
                                                                          CalendarSyncStatus.ACTIVE)));

    service.freezeOnDisconnect(USER, SERVER, LOGIN);

    verify(agendaCalendarService, never()).deleteCalendarById(anyLong(), anyString());
    verify(caldavSyncStorage, never()).deletePair(anyLong());
    verify(caldavSyncStorage, never()).deleteObjects(anyLong());
    verify(caldavSyncStorage, times(3)).savePair(any());
  }

  @Test
  public void aPairAlreadyInATombstoneStateIsNotWokenUpByADisconnect() {
    when(caldavSyncStorage.getPairs(USER, SERVER))
                                                  .thenReturn(List.of(pair(SyncOrigin.EXO,
                                                                           CalendarSyncStatus.EXO_ORPHANED)));

    service.freezeOnDisconnect(USER, SERVER, LOGIN);

    verify(caldavSyncStorage, never()).savePair(any());
  }

  @Test
  public void aCalendarThatWasNeverBoundIsSimplyDeletedInExo() throws Exception {
    // No binding, nothing to propagate. The connector must not become a
    // reason a plain local deletion fails.
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(null);

    service.deleteRemoteCounterpart(USER, LOGIN, CALENDAR);

    verify(calDavClient, never()).deleteCollection(any(), any());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  @Test
  public void aCalendarCarryingNoAnchorIsDeletedWithoutLookingForABinding() throws Exception {
    Calendar unanchored = calendar();
    unanchored.setSyncUid(null);
    when(agendaCalendarService.getCalendarById(CALENDAR)).thenReturn(unanchored);

    service.deleteRemoteCounterpart(USER, LOGIN, CALENDAR);

    verify(caldavSyncStorage, never()).getPairByLocalCalendar(anyLong(), anyLong(), anyString());
  }


  @Test
  public void anAccountWhoseCredentialsAreGoneFallsBackToADeleteInExoOnly() throws Exception {
    // A known limitation, asserted rather than hidden. The binding is looked
    // up by (user, server, anchor), and without settings there is no server id
    // to look it up under — so a calendar that WAS bound reads as unbound and
    // its collection is left standing on the server.
    //
    // Acceptable because it is the safe direction: nothing remote is destroyed
    // on the strength of a lookup that could not be made, and agenda still
    // deletes the calendar. But it does mean a user who disconnects and then
    // deletes a calendar leaves a collection behind with no tombstone
    // recording it, which the settings cannot then surface. Worth closing when
    // the settings surface lands (PR11).
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    service.deleteRemoteCounterpart(USER, LOGIN, CALENDAR);

    verify(calDavClient, never()).deleteCollection(any(), any());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  @Test
  public void keepingTheRemoteCalendarOfSomethingExoNeverCreatedIsJustALocalDelete() throws Exception {
    // The explicit fallback on a REMOTE pair is not an orphan — eXo never
    // owned that collection — so the tombstone says locally deleted, not
    // orphaned.
    givenBoundCalendar(SyncOrigin.REMOTE, CalendarSyncStatus.ACTIVE);

    service.keepRemoteCounterpart(USER, CALENDAR);

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    assertEquals(CalendarSyncStatus.LOCALLY_DELETED, saved.getValue().getStatus());
  }

  @Test
  public void freezingAnAccountWithNoBindingsDoesNothing() {
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of());

    service.freezeOnDisconnect(USER, SERVER, LOGIN);

    verify(caldavSyncStorage, never()).savePair(any());
  }

  // What the confirmation dialog is told before the user commits to any of
  // the above. The sentence itself no assertion can judge; that the right
  // facts reach it, and that asking costs nothing, is what these pin.

  /**
   * A calendar nothing is bound to gives the dialog nothing to warn about, so
   * the user is asked the plain agenda question rather than a CalDAV one about
   * a server that holds none of their events.
   */
  @Test
  public void aCalendarWithNoBindingAtAllHasNothingToWarnAbout() {
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(null);

    CalendarDeletionPlan plan = service.describeDeletion(USER, CALENDAR);

    assertEquals(new CalendarDeletionPlan(false, false, null), plan);
  }

  /**
   * A collection eXo created goes with the calendar, and the dialog has to say
   * so: everything in it goes too, including events other devices added that
   * eXo never authored and cannot restore.
   */
  @Test
  public void aCollectionExoCreatedIsAnnouncedAsGoingWithTheCalendar() {
    givenBoundCalendar();

    CalendarDeletionPlan plan = service.describeDeletion(USER, CALENDAR);

    assertTrue(plan.claimed());
    assertTrue(plan.propagates());
    // The server is named: a user with two accounts must know which one this
    // deletion reaches.
    assertEquals(URL, plan.server());
  }

  /**
   * A calendar the user made in their own client is left standing, and the
   * dialog says that too — a warning promising to destroy something eXo will
   * not touch is worse than no warning.
   */
  @Test
  public void aCalendarTheUserMadeElsewhereIsAnnouncedAsStayingWhereItIs() {
    givenBoundCalendar(SyncOrigin.REMOTE, CalendarSyncStatus.ACTIVE);

    CalendarDeletionPlan plan = service.describeDeletion(USER, CALENDAR);

    // Still claimed — there IS a remote calendar, and the user is told what
    // becomes of it — but nothing propagates.
    assertTrue(plan.claimed());
    assertFalse(plan.propagates());
    assertEquals(URL, plan.server());
  }

  /**
   * A binding already tombstoned as locally deleted describes a calendar that
   * is on its way out of eXo already; there is nothing left to warn a second
   * confirmation about.
   */
  @Test
  public void aBindingAlreadyTombstonedAsLocallyDeletedClaimsNothing() {
    givenBoundCalendar(SyncOrigin.REMOTE, CalendarSyncStatus.LOCALLY_DELETED);

    assertEquals(new CalendarDeletionPlan(false, false, null), service.describeDeletion(USER, CALENDAR));
  }

  /**
   * Same for a collection the user already chose to keep: the divergence has
   * been recorded once and consented to, and warning about it again would
   * offer to delete a collection this pair no longer speaks for.
   */
  @Test
  public void aBindingAlreadyTombstonedAsOrphanedClaimsNothing() {
    givenBoundCalendar(SyncOrigin.EXO, CalendarSyncStatus.EXO_ORPHANED);

    assertEquals(new CalendarDeletionPlan(false, false, null), service.describeDeletion(USER, CALENDAR));
  }

  /**
   * Only the two tombstone states silence the warning. A pair merely paused —
   * the state a disconnected account leaves behind — still has a live
   * collection on the server, and a dialog that fell silent about it would let
   * the user delete it believing nothing remote was involved.
   */
  @Test
  public void aBindingMerelyPausedStillHasACollectionToWarnAbout() {
    givenBoundCalendar(SyncOrigin.EXO, CalendarSyncStatus.PAUSED);

    CalendarDeletionPlan plan = service.describeDeletion(USER, CALENDAR);

    assertTrue(plan.claimed());
    assertTrue(plan.propagates());
  }

  /**
   * A calendar carrying no anchor was never bound, and is not looked up: the
   * anchor is the only thing a binding is found by, and searching without one
   * would match on whatever a null key happens to find.
   */
  @Test
  public void aCalendarCarryingNoAnchorClaimsNothingWithoutLookingForABinding() {
    Calendar unanchored = calendar();
    unanchored.setSyncUid(null);
    when(agendaCalendarService.getCalendarById(CALENDAR)).thenReturn(unanchored);

    assertEquals(new CalendarDeletionPlan(false, false, null), service.describeDeletion(USER, CALENDAR));
    verify(caldavSyncStorage, never()).getPairByLocalCalendar(anyLong(), anyLong(), anyString());
  }

  /**
   * A calendar agenda no longer has claims nothing rather than failing: the
   * dialog asks before agenda deletes, but nothing stops it asking about a
   * calendar that vanished in between, and a failure there would block a
   * deletion the connector has no stake in.
   */
  @Test
  public void aCalendarAgendaNoLongerHasClaimsNothing() {
    when(agendaCalendarService.getCalendarById(CALENDAR)).thenReturn(null);

    assertEquals(new CalendarDeletionPlan(false, false, null), service.describeDeletion(USER, CALENDAR));
  }

  /**
   * With the account gone there is no server id to look a binding up under, so
   * the plan claims nothing — the same known limitation
   * {@link #anAccountWhoseCredentialsAreGoneFallsBackToADeleteInExoOnly()}
   * documents, seen from the dialog's side. Safe in the direction that
   * matters: a user is never warned that confirming destroys a collection eXo
   * has since become unable to reach.
   */
  @Test
  public void anAccountWhoseCredentialsAreGoneClaimsNothing() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    assertEquals(new CalendarDeletionPlan(false, false, null), service.describeDeletion(USER, CALENDAR));
  }

  /**
   * The defensive half of the same case: should a binding still be found with
   * no account behind it, the plan is returned without a server name rather
   * than failing on one. A dialog that threw here would block the deletion
   * outright.
   */
  @Test
  public void aBindingFoundWithNoAccountBehindItIsClaimedWithoutNamingAServer() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);
    when(caldavSyncStorage.getPairByLocalCalendar(USER, 0L, ANCHOR)).thenReturn(pair(SyncOrigin.EXO,
                                                                                     CalendarSyncStatus.ACTIVE));

    CalendarDeletionPlan plan = service.describeDeletion(USER, CALENDAR);

    assertTrue(plan.claimed());
    assertNull(plan.server());
  }

  /**
   * The binding is looked up under the caller's own identity, so a calendar id
   * naming somebody else's calendar finds nothing to claim — the request
   * carries no way to ask about another account's collections.
   */
  @Test
  public void theBindingIsLookedUpUnderTheCallerSOwnIdentity() {
    givenBoundCalendar();

    service.describeDeletion(USER, CALENDAR);

    verify(caldavSyncStorage).getPairByLocalCalendar(USER, SERVER, ANCHOR);
  }

  /**
   * Asking costs nothing on the wire. The dialog opens on this answer, so a
   * round trip to the calendar server would make an unreachable server stall
   * or fail a deletion it has no say in — and every fact the warning needs is
   * already held locally.
   */
  @Test
  public void theWarningIsWorkedOutWithoutEverContactingTheServer() {
    givenBoundCalendar();

    service.describeDeletion(USER, CALENDAR);

    verifyNoInteractions(calDavClient);
  }

  /**
   * Describing a deletion changes nothing. The user has not confirmed yet, and
   * a question that moved the pair towards deletion would take a calendar out
   * of sync on the strength of a dialog somebody opened and closed again.
   */
  @Test
  public void describingADeletionDoesNotTouchTheBinding() {
    givenBoundCalendar();

    service.describeDeletion(USER, CALENDAR);

    verify(caldavSyncStorage, never()).savePair(any());
    verify(caldavSyncStorage, never()).deletePair(anyLong());
    verify(caldavSyncStorage, never()).deleteObjects(anyLong());
  }

  /**
   * A calendar bound to a collection eXo created.
   */
  private void givenBoundCalendar() {
    givenBoundCalendar(SyncOrigin.EXO, CalendarSyncStatus.ACTIVE);
  }

  /**
   * A calendar bound to a collection of the given origin.
   *
   * @param origin which side created the collection
   * @param status the pair's state
   */
  private void givenBoundCalendar(SyncOrigin origin, CalendarSyncStatus status) {
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(pair(origin, status));
  }

  /**
   * A server that no longer lists the collection once it has been deleted.
   */
  private void givenCollectionGoneAfterDelete() {
    when(calDavClient.listCalendars(any(), anyString())).thenReturn(List.of());
  }

  /**
   * @param origin which side created the collection
   * @param status the pair's state
   * @return the binding
   */
  private CalendarSync pair(SyncOrigin origin, CalendarSyncStatus status) {
    CalendarSync pair = new CalendarSync();
    pair.setId(3L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setLocalCalendarSyncUid(ANCHOR);
    pair.setRemoteHref(HREF);
    pair.setOrigin(origin);
    pair.setStatus(status);
    return pair;
  }

  /**
   * @param href the collection path
   * @return a listed collection
   */
  private CalendarCollection collection(String href) {
    return new CalendarCollection(href, "listed", null, null, null, true);
  }

  /**
   * @return the eXo calendar being deleted
   */
  private Calendar calendar() {
    Calendar calendar = new Calendar();
    calendar.setId(CALENDAR);
    calendar.setOwnerId(USER);
    calendar.setSyncUid(ANCHOR);
    return calendar;
  }

  @Test
  public void aCalendarHiddenByAnotherUserIsNeverShownBack() throws Exception {
    // The binding carries whose it is, and that is the only thing standing
    // between one user and another user's calendars. An id travels through a
    // browser; ownership is checked here, not there.
    CalendarSync theirs = tombstone();
    theirs.setUserIdentityId(USER + 1);
    when(caldavSyncStorage.getPair(9L)).thenReturn(theirs);

    assertThrows(IllegalAccessException.class, () -> service.showAgain(USER, 9L, LOGIN));

    verify(caldavSyncStorage, never()).deletePair(anyLong());
  }

  @Test
  public void anIdThatNamesNoTombstoneIsNotFound() throws Exception {
    when(caldavSyncStorage.getPair(9L)).thenReturn(null);

    assertThrows(ObjectNotFoundException.class, () -> service.showAgain(USER, 9L, LOGIN));
  }

  @Test
  public void anActiveBindingIsNotSomethingToShowBack() throws Exception {
    // Only a tombstone is hidden. Lifting a live binding would drop the
    // record of a calendar that is on screen and working.
    CalendarSync live = tombstone();
    live.setStatus(CalendarSyncStatus.ACTIVE);
    when(caldavSyncStorage.getPair(9L)).thenReturn(live);

    assertThrows(ObjectNotFoundException.class, () -> service.showAgain(USER, 9L, LOGIN));

    verify(caldavSyncStorage, never()).deletePair(anyLong());
  }

  @Test
  public void showingACalendarAgainDropsItsBindingSoItIsMaterialisedAfresh() throws Exception {
    when(caldavSyncStorage.getPair(9L)).thenReturn(tombstone());

    service.showAgain(USER, 9L, LOGIN);

    verify(caldavSyncStorage).deleteObjects(9L);
    verify(caldavSyncStorage).deletePair(9L);
  }

  @Test
  public void nothingHiddenCostsNoRoundTrip() {
    // The common answer. A section that will not be shown must not make the
    // drawer wait on a server to discover it has nothing to say.
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(activeBinding()));

    assertTrue(service.listHidden(USER, LOGIN).isEmpty());

    verifyNoInteractions(calDavClient, caldavReadService);
  }

  @Test
  public void aHiddenCalendarIsOfferedUnderItsNameOnTheServerToday() {
    // Read now rather than stored: a calendar renamed in the user's own client
    // since they hid it should come back under the name they would recognise.
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(tombstone()));
    givenDescribed(new RemoteCalendar("/dav/calendars/john/private/", "Renamed since", "#112233", false));

    List<HiddenCalendar> hidden = service.listHidden(USER, LOGIN);

    assertEquals(1, hidden.size());
    assertEquals("Renamed since", hidden.get(0).name());
    assertEquals(9L, hidden.get(0).id());
    assertFalse(hidden.get(0).shared(), "a calendar of the user's own, deleted here");
    assertNull(hidden.get(0).ownerDisplayName());
    // The listing is asked for THIS user, by their eXo login, and for the
    // canonical paths of their hidden pairs alone: the account it reads comes
    // from their credentials provider, and nothing else in this suite would
    // notice if it came from somewhere else.
    verify(caldavReadService).describeCollections(USER, LOGIN, Set.of("/dav/calendars/john/private"));
  }

  @Test
  public void aCollectionTheServerNoLongerHasIsNotOffered() {
    // Offering to show something that is gone would be a promise nothing can
    // keep.
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(tombstone()));
    givenDescribed();

    assertTrue(service.listHidden(USER, LOGIN).isEmpty());
  }

  // ------------------------------------ hiding a calendar shared with the user, EXO-90239

  /** Alice's calendar as Stalwart lists it to the user: at her path. */
  private static final String        ALICES              = "/dav/cal/alice%40stalwart.local/default/";

  /** The same collection as every pair records it: decoded, no trailing slash. */
  private static final String        ALICES_CANONICAL    = "/dav/cal/alice@stalwart.local/default";

  /** A colleague's eXo calendar as BlueMind lists it after subscribing: under the user's own home. */
  private static final String        CAL2_UNDER_OWN_HOME = "/dav/calendars/john/exo-cal-959b5529-ea4c-4ae4-a793-a2c201c3af9f/";

  /**
   * Hiding a share the server reported (the Stalwart shape) records a pair
   * that stands for the user's choice and nothing else: REMOTE, because the
   * collection is somebody's on the server and eXo minted nothing for it;
   * HIDDEN_SHARE; no calendar anchor, since there is no eXo calendar and
   * never will be; and the path as the pair table keys every path. Nothing is
   * created in agenda and nothing is written to the server.
   */
  @Test
  public void hidingAShareTheServerReportedRecordsAHiddenSharePair() throws Exception {
    givenListing(share(ALICES, "Alice", "Alice"));

    service.hideShare(USER, LOGIN, ALICES);

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    assertEquals(USER, saved.getValue().getUserIdentityId());
    assertEquals(SERVER, saved.getValue().getServerId());
    assertEquals(ALICES_CANONICAL, saved.getValue().getRemoteHref());
    assertEquals(SyncOrigin.REMOTE, saved.getValue().getOrigin());
    assertEquals(CalendarSyncStatus.HIDDEN_SHARE, saved.getValue().getStatus());
    // No eXo calendar stands behind a hidden share, yet the anchor is not
    // null: derived from the path, so the unique index holds one row per
    // hidden calendar on every engine, Oracle and SQL Server included.
    assertEquals(CaldavDeletionService.hiddenShareAnchor(ALICES_CANONICAL), saved.getValue().getLocalCalendarSyncUid());
    assertNull(saved.getValue().getLastSyncEnd(), "nothing was synchronised");
    verifyNoInteractions(agendaCalendarService, calDavClient, caldavSyncService);
  }

  /**
   * The anchor a hidden share records is the same for the same path, differs
   * between paths, fits the 36-character column, and can never be one agenda
   * minted: agenda's are random, version 4, and this is name-based, version
   * 3. Were it null, Oracle and SQL Server would refuse a user's second hidden
   * share, or one beside their mirror pair, on the unique index.
   */
  @Test
  public void aHiddenSharesAnchorIsItsPathsAndNeverACalendars() {
    String anchor = CaldavDeletionService.hiddenShareAnchor(ALICES_CANONICAL);

    assertEquals(anchor, CaldavDeletionService.hiddenShareAnchor(ALICES_CANONICAL));
    assertNotEquals(anchor, CaldavDeletionService.hiddenShareAnchor("/dav/cal/bob@stalwart.local/default"));
    assertEquals(36, anchor.length());
    assertEquals(3, UUID.fromString(anchor).version());
    assertNotEquals(UUID.randomUUID().version(), UUID.fromString(anchor).version());
  }

  /**
   * Two hides of one share racing — a double click — both pass the lookup,
   * and the unique index refuses the second row. That refusal is the end
   * state the caller asked for, not a failure: the request succeeds.
   */
  @Test
  public void aHideRacingAnotherOfTheSameShareSucceeds() {
    givenListing(share(ALICES, "Alice", "Alice"));
    when(caldavSyncStorage.savePair(any())).thenThrow(new DataIntegrityViolationException("duplicate",
                                                                                         new SQLIntegrityConstraintViolationException("UQ_CALDAV_CALENDAR_SYNC_LOCAL",
                                                                                                                                      "23505")));

    assertDoesNotThrow(() -> service.hideShare(USER, LOGIN, ALICES));
  }

  /**
   * Any other failure to save is a failure: only the duplicate key means the
   * calendar is hidden already.
   */
  @Test
  public void aSaveFailingForAnotherReasonIsNotSwallowed() {
    givenListing(share(ALICES, "Alice", "Alice"));
    when(caldavSyncStorage.savePair(any())).thenThrow(new IllegalStateException("database down"));

    assertThrows(IllegalStateException.class, () -> service.hideShare(USER, LOGIN, ALICES));
  }

  /**
   * A colleague's eXo calendar subscribed on BlueMind is a share by this
   * deployment's word alone, and the calendar list already says so — the
   * hide reads the list's verdict, not the server's, so it is hidden the
   * same way.
   */
  @Test
  public void hidingAColleaguesExoCalendarRecordsTheSamePair() throws Exception {
    givenListing(new RemoteCalendar(CAL2_UNDER_OWN_HOME, "CAL2", "#4a90d9", true, true, 1L, "root", "Root Root"));

    service.hideShare(USER, LOGIN, CAL2_UNDER_OWN_HOME);

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    assertEquals(CalendarSyncStatus.HIDDEN_SHARE, saved.getValue().getStatus());
    assertEquals(SyncOrigin.REMOTE, saved.getValue().getOrigin());
    assertEquals(CaldavSyncStorage.canonicalHref(CAL2_UNDER_OWN_HOME), saved.getValue().getRemoteHref());
  }

  /**
   * A calendar of the user's own — listed, but not a share — is refused in
   * words the browser can render, and nothing is saved. Hiding one's own is
   * done by deleting it and keeping the remote copy, a different act.
   */
  @Test
  public void aCalendarOfTheUsersOwnCannotBeHiddenHere() {
    givenListing(new RemoteCalendar("/dav/calendars/john/work/", "Work", "#112233", false));

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                                                    () -> service.hideShare(USER, LOGIN, "/dav/calendars/john/work/"));

    assertEquals(CaldavDeletionService.NOT_A_SHARE, refused.getMessage());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * A read-only calendar of the user's own is still the user's own: the test
   * is {@code shared}, not {@code readOnly}, because a calendar the server
   * will not let them write is not thereby somebody else's.
   */
  @Test
  public void aReadOnlyCalendarOfTheUsersOwnIsNotAShareEither() {
    givenListing(new RemoteCalendar("/dav/calendars/john/work/", "Work", "#112233", true));

    assertThrows(IllegalArgumentException.class, () -> service.hideShare(USER, LOGIN, "/dav/calendars/john/work/"));

    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * An id the user's current listing does not hold is not found — a share
   * revoked between the listing and the click, or a path nobody listed to
   * this user. The id is trusted no further than that listing.
   */
  @Test
  public void anIdTheUsersListingDoesNotHoldIsNotFound() {
    givenListing(share(ALICES, "Alice", "Alice"));

    assertThrows(ObjectNotFoundException.class, () -> service.hideShare(USER, LOGIN, "/dav/cal/bob%40stalwart.local/default/"));

    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * The id is matched as a path, the way every pair is: percent-encoding and
   * the trailing slash are spellings, not identities, so the list's own
   * spelling and a decoded one name the same calendar.
   */
  @Test
  public void theIdIsMatchedCanonicallyAgainstTheListing() throws Exception {
    givenListing(share(ALICES, "Alice", "Alice"));

    service.hideShare(USER, LOGIN, ALICES_CANONICAL);

    verify(caldavSyncStorage).savePair(any());
  }

  /**
   * A collection already bound is an eXo calendar of the user's own — never
   * a share, whatever the listing might say — and the server is not asked.
   */
  @Test
  public void anAlreadyBoundCollectionIsRefusedWithoutAskingTheServer() {
    CalendarSync materialised = tombstone();
    materialised.setStatus(CalendarSyncStatus.ACTIVE);
    when(caldavSyncStorage.getPairByRemoteHref(USER, SERVER, "/dav/calendars/john/private")).thenReturn(materialised);

    assertThrows(IllegalArgumentException.class, () -> service.hideShare(USER, LOGIN, "/dav/calendars/john/private/"));

    verify(caldavSyncStorage, never()).savePair(any());
    verifyNoInteractions(caldavReadService);
  }

  /**
   * Hiding what is hidden already succeeds and changes nothing: the end state
   * the caller asked for holds, and a second row for one choice would give
   * the drawer two lines to lift for one calendar. The server is not asked.
   */
  @Test
  public void hidingAShareAlreadyHiddenIsIdempotent() throws Exception {
    when(caldavSyncStorage.getPairByRemoteHref(USER, SERVER, ALICES_CANONICAL)).thenReturn(hiddenShare());

    assertDoesNotThrow(() -> service.hideShare(USER, LOGIN, ALICES));

    verify(caldavSyncStorage, never()).savePair(any());
    verifyNoInteractions(caldavReadService);
  }

  /**
   * No calendar named, nothing to look for: refused before any account is
   * read.
   */
  @Test
  public void hidingNothingIsRefusedBeforeTheAccountIsRead() {
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                                                    () -> service.hideShare(USER, LOGIN, "  "));

    assertEquals(CaldavDeletionService.CALENDAR_REQUIRED, refused.getMessage());
    verifyNoInteractions(caldavReadService, caldavSyncStorage);
  }

  /**
   * No connected account is the same state the push endpoints report as
   * such, and it is not a 400 about the calendar: the calendar may well be a
   * share, and the user has to connect before anything can be said about it.
   */
  @Test
  public void hidingWithNoConnectedAccountIsRefusedAsNotConnected() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    CaldavPushException refused = assertThrows(CaldavPushException.class, () -> service.hideShare(USER, LOGIN, ALICES));

    assertEquals(CaldavPushService.NOT_CONNECTED, refused.getCode());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * An account that could not be listed is neither a share nor not one, and
   * saying "not found" would tell the user the calendar is gone when the
   * server merely did not answer. Its own code, mapped to a gateway failure.
   */
  @Test
  public void anAccountThatCannotBeListedHidesNothingAndSaysWhy() {
    when(caldavReadService.listCalendars(USER, LOGIN)).thenReturn(new RemoteCalendarsRead(List.of(), true));

    CaldavPushException refused = assertThrows(CaldavPushException.class, () -> service.hideShare(USER, LOGIN, ALICES));

    assertEquals(CaldavDeletionService.ACCOUNT_UNAVAILABLE, refused.getCode());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * An account whose endpoint cannot even be resolved — its server
   * registration gone, a declared URL that is not one — is the same answer
   * as one that did not reply: nothing hidden, said with the account's code,
   * never an unhandled failure the endpoint would answer as a 500.
   */
  @Test
  public void anAccountWhoseEndpointCannotBeResolvedHidesNothingAndSaysWhy() {
    when(caldavReadService.listCalendars(USER, LOGIN)).thenThrow(new CalDavException("No CalDAV server is declared to talk to"));

    CaldavPushException refused = assertThrows(CaldavPushException.class, () -> service.hideShare(USER, LOGIN, ALICES));

    assertEquals(CaldavDeletionService.ACCOUNT_UNAVAILABLE, refused.getCode());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * Both kinds are listed, told apart by {@code shared}: the tombstone named
   * from the server and naming nobody, the share named and naming whoever
   * shared it — the very owner the calendar list would name.
   */
  @Test
  public void hiddenCalendarsListBothKindsAndNameWhoSharedAShare() {
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(tombstone(), hiddenShare(), activeBinding()));
    givenDescribed(new RemoteCalendar("/dav/calendars/john/private/", "Private", "#112233", false),
                   share(ALICES, "Alice", "Alice Martin"));

    List<HiddenCalendar> hidden = service.listHidden(USER, LOGIN);

    assertEquals(2, hidden.size());
    assertEquals(new HiddenCalendar(9L, "Private", false, null), hidden.get(0));
    assertEquals(new HiddenCalendar(12L, "Alice", true, "Alice Martin"), hidden.get(1));
    verify(caldavReadService).describeCollections(USER, LOGIN, Set.of("/dav/calendars/john/private", ALICES_CANONICAL));
  }

  /**
   * A share whose owner nobody could name is still a share, with no owner:
   * the drawer says "shared with you" rather than inventing a name.
   */
  @Test
  public void aHiddenShareWhoseOwnerIsUnknownIsListedAsASharedWithNoOwner() {
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(hiddenShare()));
    givenDescribed(share(ALICES, "Alice", null));

    List<HiddenCalendar> hidden = service.listHidden(USER, LOGIN);

    assertEquals(1, hidden.size());
    assertTrue(hidden.get(0).shared());
    assertNull(hidden.get(0).ownerDisplayName());
  }

  /**
   * A share the server no longer lists — revoked by its owner — is left out,
   * and the listing writes nothing: dropping the record is the sweep's job
   * ({@code CaldavSyncService#forgetRevokedShares}), since a settings page is
   * read on every visit and must not delete on the way.
   */
  @Test
  public void aRevokedShareIsLeftOutAndTheListingDeletesNothing() {
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(hiddenShare()));
    givenDescribed(new RemoteCalendar("/dav/calendars/john/work/", "Work", "#112233", false));

    assertTrue(service.listHidden(USER, LOGIN).isEmpty());

    verify(caldavSyncStorage, never()).deletePair(anyLong());
  }

  /**
   * Showing a share again drops the pair and nothing else: no
   * synchronisation, because a share is never materialised and the
   * collection is unbound — listed — the moment its record is gone.
   */
  @Test
  public void showingAShareAgainDropsThePairWithoutSynchronising() throws Exception {
    when(caldavSyncStorage.getPair(12L)).thenReturn(hiddenShare());

    service.showAgain(USER, 12L, LOGIN);

    verify(caldavSyncStorage).deletePair(12L);
    verifyNoInteractions(caldavSyncService);
  }

  /**
   * A share hidden by another user is never shown back by this one: the
   * binding carries whose it is, and the check is the same as for a
   * tombstone.
   */
  @Test
  public void aShareHiddenByAnotherUserIsNeverShownBack() {
    CalendarSync theirs = hiddenShare();
    theirs.setUserIdentityId(USER + 1);
    when(caldavSyncStorage.getPair(12L)).thenReturn(theirs);

    assertThrows(IllegalAccessException.class, () -> service.showAgain(USER, 12L, LOGIN));

    verify(caldavSyncStorage, never()).deletePair(anyLong());
  }

  /**
   * @param href the collection href, as the calendar list spells it
   * @param name its display name
   * @param owner how the list named whoever shared it, or null
   * @return a calendar the list answers as a share the server reported
   */
  private RemoteCalendar share(String href, String name, String owner) {
    return new RemoteCalendar(href, name, "#b8e986", true, true, null, null, owner);
  }

  /**
   * @param calendars what the calendar list answers this user
   */
  private void givenListing(RemoteCalendar... calendars) {
    when(caldavReadService.listCalendars(USER, LOGIN)).thenReturn(new RemoteCalendarsRead(List.of(calendars), false));
  }

  /**
   * @param calendars what the account still lists among the hidden paths,
   *          as the calendar list would describe each
   */
  private void givenDescribed(RemoteCalendar... calendars) {
    when(caldavReadService.describeCollections(eq(USER), eq(LOGIN), any())).thenReturn(new RemoteCalendarsRead(List.of(calendars),
                                                                                                                false));
  }

  /**
   * @return the pair recording that the user hid Alice's calendar
   */
  private CalendarSync hiddenShare() {
    CalendarSync pair = new CalendarSync();
    pair.setId(12L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setLocalCalendarSyncUid(CaldavDeletionService.hiddenShareAnchor(ALICES_CANONICAL));
    pair.setRemoteHref(ALICES_CANONICAL);
    pair.setOrigin(SyncOrigin.REMOTE);
    pair.setStatus(CalendarSyncStatus.HIDDEN_SHARE);
    return pair;
  }

  /**
   * @return a binding the user hid
   */
  private CalendarSync tombstone() {
    CalendarSync pair = new CalendarSync();
    pair.setId(9L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setRemoteHref("/dav/calendars/john/private");
    pair.setOrigin(SyncOrigin.REMOTE);
    pair.setStatus(CalendarSyncStatus.LOCALLY_DELETED);
    return pair;
  }

  /**
   * @return a binding that is live
   */
  private CalendarSync activeBinding() {
    CalendarSync pair = tombstone();
    pair.setId(10L);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  /**
   * @return a connected account
   */
  @Test
  public void theWarningNamesTheServerTheUserPicked() throws Exception {
    // The dialog used to read "the matching calendar on  will not be touched",
    // with a blank where the name belongs: it was taken from the stored URL,
    // which only ever holds a value for an account attached the legacy way,
    // before servers were declared.
    CaldavServer server = new CaldavServer();
    server.setName("BlueMind");
    server.setProviderName("agenda.caldavCalendar.6");
    when(caldavServerService.getServerById(SERVER)).thenReturn(server);
    CalendarSync pair = pair(SyncOrigin.REMOTE, CalendarSyncStatus.ACTIVE);
    when(caldavSyncStorage.getPairByLocalCalendar(eq(USER), eq(SERVER), anyString())).thenReturn(pair);

    CalendarDeletionPlan plan = service.describeDeletion(USER, CALENDAR);

    assertEquals("BlueMind", plan.server());
  }

  @Test
  public void aRegistrationThatIsGoneFallsBackToTheStoredUrl() throws Exception {
    // A declared server removed while an account still points at it. The URL
    // is a poorer name, not a reason to say nothing.
    CaldavUserSetting legacy = settings();
    legacy.setCaldavUrl("https://dav.example.org/");
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(legacy);
    when(caldavServerService.getServerById(SERVER)).thenThrow(new ObjectNotFoundException("gone"));
    CalendarSync pair = pair(SyncOrigin.REMOTE, CalendarSyncStatus.ACTIVE);
    when(caldavSyncStorage.getPairByLocalCalendar(eq(USER), eq(SERVER), anyString())).thenReturn(pair);

    CalendarDeletionPlan plan = service.describeDeletion(USER, CALENDAR);

    assertEquals("https://dav.example.org/", plan.server());
  }

  @Test
  public void unHidingSynchronisesSoTheCalendarComesBackAtOnce() throws Exception {
    // Lifting the tombstone alone leaves the collection unbound, and unbound
    // is precisely what the Remote section lists — so the user who pressed
    // "Show again" watched their calendar reappear under the heading for
    // calendars eXo is NOT showing, and stay there until the throttle expired.
    CalendarSync tombstone = pair(SyncOrigin.REMOTE, CalendarSyncStatus.LOCALLY_DELETED);
    tombstone.setUserIdentityId(USER);
    when(caldavSyncStorage.getPair(9L)).thenReturn(tombstone);

    service.showAgain(USER, 9L, LOGIN);

    verify(caldavSyncService).syncNow(USER, LOGIN);
  }

  @Test
  public void aFailedSynchronisationDoesNotUndoTheUnHiding() throws Exception {
    // The tombstone is lifted either way and the next run will materialise
    // the collection. Reporting the sync failure here would tell the user the
    // un-hiding did not happen, which is false.
    CalendarSync tombstone = pair(SyncOrigin.REMOTE, CalendarSyncStatus.LOCALLY_DELETED);
    tombstone.setUserIdentityId(USER);
    when(caldavSyncStorage.getPair(9L)).thenReturn(tombstone);
    doThrow(new IllegalStateException("server down")).when(caldavSyncService).syncNow(USER, LOGIN);

    assertDoesNotThrow(() -> service.showAgain(USER, 9L, LOGIN));

    verify(caldavSyncStorage).deletePair(9L);
  }

  @Test
  public void aCalendarSynchronisingNormallyIsNotReported() {
    // The row exists to name a problem. A calendar that is working is not
    // news, and listing it would bury the ones that are not.
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair(SyncOrigin.REMOTE,
                                                                          CalendarSyncStatus.ACTIVE)));

    assertTrue(service.listSyncStates(USER, LOGIN).isEmpty());
  }

  @Test
  public void aCalendarTheUserHidIsNotReportedHere() {
    // A tombstone has its own listing, which offers to bring the calendar back
    // rather than to worry about it.
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair(SyncOrigin.REMOTE,
                                                                          CalendarSyncStatus.LOCALLY_DELETED)));

    assertTrue(service.listSyncStates(USER, LOGIN).isEmpty());
  }

  /**
   * A share the user hid (EXO-90239) is a choice, not a state to warn about:
   * it has its row among the hidden calendars, and the calendar-states row
   * neither lists it nor asks the server for a name it would never show.
   */
  @Test
  public void aShareTheUserHidIsNotReportedHere() {
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(hiddenShare()));

    assertTrue(service.listSyncStates(USER, LOGIN).isEmpty());

    verify(calDavClient, never()).listCalendars(any(), anyString());
  }

  @Test
  public void aPausedCalendarIsReportedWithItsName() {
    CalendarSync paused = pair(SyncOrigin.REMOTE, CalendarSyncStatus.PAUSED);
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(paused));
    givenAgendaCalendarNamed("Family");

    List<CalendarSyncState> states = service.listSyncStates(USER, LOGIN);

    assertEquals(1, states.size());
    assertEquals("Family", states.get(0).name());
    assertEquals(CALENDAR, states.get(0).calendarId());
    assertEquals(CalendarSyncStatus.PAUSED, states.get(0).status());
  }

  @Test
  public void aRefusedCalendarIsNamedFromTheServerWhenExoHasNone() {
    // A collection eXo was refused permission to create has no eXo calendar to
    // name it by, and a row with no name is a row the user cannot act on.
    CalendarSync refused = pair(SyncOrigin.EXO, CalendarSyncStatus.REMOTE_CREATE_REFUSED);
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(refused));
    givenNoAgendaCalendars();
    givenServerCollectionNamed("Work");

    List<CalendarSyncState> states = service.listSyncStates(USER, LOGIN);

    assertEquals(1, states.size());
    assertEquals("Work", states.get(0).name());
  }

  @Test
  public void aStateNobodyCanNameIsLeftOut() {
    // Neither eXo nor the server knows what to call it. "A calendar" is not
    // something a user can act on, so it is not offered.
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair(SyncOrigin.REMOTE,
                                                                          CalendarSyncStatus.PAUSED)));
    givenNoAgendaCalendars();
    givenServerListingFails();

    assertTrue(service.listSyncStates(USER, LOGIN).isEmpty());
  }

  @Test
  public void anAccountThatIsNotConnectedHasNoStates() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    assertTrue(service.listSyncStates(USER, LOGIN).isEmpty());
  }

  /**
   * @param name what agenda calls the calendar behind the binding
   */
  private void givenAgendaCalendarNamed(String name) {
    Calendar calendar = new Calendar();
    calendar.setId(CALENDAR);
    calendar.setOwnerId(USER);
    calendar.setSyncUid(ANCHOR);
    calendar.setName(name);
    try {
      when(agendaCalendarService.getCalendars(anyInt(), anyInt(), anyString())).thenReturn(List.of(calendar));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Agenda holds no calendar for this binding.
   */
  private void givenNoAgendaCalendars() {
    try {
      when(agendaCalendarService.getCalendars(anyInt(), anyInt(), anyString())).thenReturn(List.of());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * @param name what the server calls the collection the binding points at
   */
  private void givenServerCollectionNamed(String name) {
    when(calDavClient.listCalendars(any(), anyString()))
                                                                                 .thenReturn(List.of(new CalendarCollection(HREF,
                                                                                                                            name,
                                                                                                                            null,
                                                                                                                            null,
                                                                                                                            null,
                                                                                                                            true)));
  }

  /**
   * The server cannot be listed at all.
   */
  private void givenServerListingFails() {
    when(calDavClient.listCalendars(any(), anyString())).thenThrow(new IllegalStateException("down"));
  }


  @Test
  public void reconnectingLiftsThePauseADisconnectPutOn() {
    // The whole point of pausing rather than dropping. Left paused, the
    // binding is thawed only incidentally by the next sweep re-ensuring its
    // collection — and until then a connected account reports the user's own
    // calendar as failing.
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair(SyncOrigin.EXO, CalendarSyncStatus.PAUSED)));

    service.thawOnConnect(USER, SERVER);

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    assertEquals(CalendarSyncStatus.ACTIVE, saved.getValue().getStatus());
  }

  @Test
  public void reconnectingDoesNotReopenATombstoneOrAGoneCollection() {
    // A tombstone is a deletion the user made; a binding marked gone is a
    // claim about the server that only the server's own listing may withdraw.
    // Reviving either here would resurrect a calendar nobody asked for.
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair(SyncOrigin.EXO,
                                                                          CalendarSyncStatus.LOCALLY_DELETED),
                                                                     pair(SyncOrigin.REMOTE,
                                                                          CalendarSyncStatus.REMOTE_GONE)));

    service.thawOnConnect(USER, SERVER);

    verify(caldavSyncStorage, never()).savePair(any());
  }

  // ------------------------------------ a retired subscription's calendar, EXO-90275

  /**
   * The deletion hook leaves a retired subscription's binding exactly as it
   * is — no tombstone, no drop, no request to the server. The binding goes
   * only once agenda has really deleted the calendar, through the orphan
   * pruning: dropped here, an agenda deletion failing afterwards would leave a
   * calendar with no binding for the outbound half to export.
   */
  @Test
  public void deletingARetiredSubscriptionsCalendarLeavesItsBindingToThePruning() {
    givenBoundCalendar(SyncOrigin.REMOTE, CalendarSyncStatus.RETIRED_SUBSCRIPTION);

    service.deleteRemoteCounterpart(USER, LOGIN, CALENDAR);

    verify(caldavSyncStorage, never()).deleteObjects(anyLong());
    verify(caldavSyncStorage, never()).deletePair(anyLong());
    verify(caldavSyncStorage, never()).savePair(any());
    verify(calDavClient, never()).deleteCollection(any(), any());
  }

  /**
   * The keep hook — reached by a plan cached while the binding was still
   * active — leaves it the same way.
   */
  @Test
  public void keepingTheRemoteOfARetiredSubscriptionAlsoLeavesItsBindingToThePruning() {
    givenBoundCalendar(SyncOrigin.REMOTE, CalendarSyncStatus.RETIRED_SUBSCRIPTION);

    service.keepRemoteCounterpart(USER, CALENDAR);

    verify(caldavSyncStorage, never()).deleteObjects(anyLong());
    verify(caldavSyncStorage, never()).deletePair(anyLong());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * The dialog has nothing to warn about: the collection is neither deleted
   * nor kept by this deletion.
   */
  @Test
  public void aRetiredSubscriptionClaimsNothingInTheDeletionDialog() {
    givenBoundCalendar(SyncOrigin.REMOTE, CalendarSyncStatus.RETIRED_SUBSCRIPTION);

    assertEquals(new CalendarDeletionPlan(false, false, null), service.describeDeletion(USER, CALENDAR));
  }

  /**
   * A reconnection thaws a pause and nothing else: a retired subscription is
   * not woken up into a binding that reads and writes again.
   */
  @Test
  public void reconnectingDoesNotWakeARetiredSubscription() {
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair(SyncOrigin.REMOTE, CalendarSyncStatus.RETIRED_SUBSCRIPTION)));

    service.thawOnConnect(USER, SERVER);

    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * The user is told, among the calendars needing attention: only they can
   * delete the copy.
   */
  @Test
  public void aRetiredSubscriptionIsAStateTheUserIsToldAbout() {
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair(SyncOrigin.REMOTE, CalendarSyncStatus.RETIRED_SUBSCRIPTION)));
    givenAgendaCalendarNamed("Véhicule de pool 1");

    List<CalendarSyncState> states = service.listSyncStates(USER, LOGIN);

    assertEquals(1, states.size());
    assertEquals("Véhicule de pool 1", states.get(0).name());
    assertEquals(CalendarSyncStatus.RETIRED_SUBSCRIPTION, states.get(0).status());
  }

  private CaldavUserSetting settings() {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername(DAV_ACCOUNT);
    setting.setPassword("secret");
    setting.setServerId(SERVER);
    setting.setCaldavUrl(URL);
    return setting;
  }
}
