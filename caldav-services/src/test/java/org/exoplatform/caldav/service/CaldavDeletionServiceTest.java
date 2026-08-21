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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

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
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarDeletionPlan;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
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
  private CalDavEndpoint             endpoint;

  @InjectMocks
  private CaldavDeletionService      service;

  @BeforeEach
  public void connectAnAccount() {
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
    lenient().when(calDavClient.discoverCalendarHome(any(), anyString(), anyString())).thenReturn("/dav/calendars/john/");
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

    service.deleteRemoteCounterpart(USER, CALENDAR);

    // Agenda deletes the calendar itself, after this returns without throwing.
    // What has to be true first is that the collection is gone AND the binding
    // that described it has been dropped — in that order, so a failure between
    // the two leaves a binding pointing at nothing rather than nothing
    // pointing at a collection.
    InOrder order = inOrder(calDavClient, caldavSyncStorage);
    order.verify(calDavClient).deleteCollection(any(), any(), anyString(), anyString());
    order.verify(caldavSyncStorage).deletePair(3L);
    verify(agendaCalendarService, never()).deleteCalendarById(anyLong(), anyString());
  }

  @Test
  public void thePairIsTakenOutOfSyncBeforeAnythingIsTouched() {
    // A background sweep finding this pair mid-deletion would push events into
    // a collection that is about to stop existing.
    givenBoundCalendar();
    givenCollectionGoneAfterDelete();

    service.deleteRemoteCounterpart(USER, CALENDAR);

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
    when(calDavClient.listCalendars(any(), anyString(), anyString(), anyString())).thenReturn(List.of(collection(HREF)));

    CaldavPushException failure = assertThrows(CaldavPushException.class,
                                               () -> service.deleteRemoteCounterpart(USER, CALENDAR));

    assertEquals(CaldavDeletionService.NOTHING_DELETED, failure.getCode());
    verify(agendaCalendarService, never()).deleteCalendarById(anyLong(), anyString());
  }

  @Test
  public void aServerThatCannotBeReachedLeavesBothSidesUntouched() throws Exception {
    givenBoundCalendar();
    when(calDavClient.deleteCollection(any(), any(), anyString(), anyString()))
                                                                               .thenThrow(new CalDavException("unreachable"));

    CaldavPushException failure = assertThrows(CaldavPushException.class,
                                               () -> service.deleteRemoteCounterpart(USER, CALENDAR));

    assertEquals(CaldavDeletionService.NOTHING_DELETED, failure.getCode());
    verify(agendaCalendarService, never()).deleteCalendarById(anyLong(), anyString());
  }

  @Test
  public void aFailedDeletionPutsTheBindingBackToWorking() {
    // Left in DELETING the pair would be excluded from every future sync, so a
    // transient network failure would quietly stop synchronising a calendar
    // nobody deleted.
    givenBoundCalendar();
    when(calDavClient.deleteCollection(any(), any(), anyString(), anyString()))
                                                                               .thenThrow(new CalDavException("unreachable"));

    assertThrows(CaldavPushException.class, () -> service.deleteRemoteCounterpart(USER, CALENDAR));

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage, org.mockito.Mockito.times(2)).savePair(saved.capture());
    assertEquals(CalendarSyncStatus.ACTIVE, saved.getAllValues().get(1).getStatus());
  }

  @Test
  public void aCalendarTheUserMadeElsewhereIsNeverDeletedOnTheServer() throws Exception {
    // A REMOTE pair's collection is the user's own, made in their own client.
    // Deleting it in eXo is a decision about eXo.
    givenBoundCalendar(SyncOrigin.REMOTE, CalendarSyncStatus.ACTIVE);

    service.deleteRemoteCounterpart(USER, CALENDAR);

    verify(calDavClient, never()).deleteCollection(any(), any(), anyString(), anyString());
  }

  @Test
  public void aLocalOnlyDeletionLeavesATombstoneSoSyncDoesNotUndoIt() {
    // Without it the next sweep sees a remote collection eXo has no calendar
    // for and materialises it straight back, undoing the deletion in front of
    // the user.
    givenBoundCalendar(SyncOrigin.REMOTE, CalendarSyncStatus.ACTIVE);

    service.deleteRemoteCounterpart(USER, CALENDAR);

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
    verify(calDavClient, never()).deleteCollection(any(), any(), anyString(), anyString());
  }

  @Test
  public void aSuccessfulDeletionLeavesNoBindingBehind() {
    givenBoundCalendar();
    givenCollectionGoneAfterDelete();

    service.deleteRemoteCounterpart(USER, CALENDAR);

    // Both sides are gone; the binding has nothing left to describe.
    verify(caldavSyncStorage).deleteObjects(3L);
    verify(caldavSyncStorage).deletePair(3L);
  }

  @Test
  public void disconnectingAnAccountDeletesNothingAtAll() {
    // A user unlinking their account is saying "stop syncing", not "destroy
    // what is on my server". The bindings are kept so reconnecting finds its
    // collections again instead of creating a second set beside them.
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair(SyncOrigin.EXO, CalendarSyncStatus.ACTIVE)));

    service.freezeOnDisconnect(USER, SERVER);

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    assertEquals(CalendarSyncStatus.PAUSED, saved.getValue().getStatus());
    verify(calDavClient, never()).deleteCollection(any(), any(), anyString(), anyString());
    verify(caldavSyncStorage, never()).deletePair(anyLong());
  }

  @Test
  public void aPairAlreadyInATombstoneStateIsNotWokenUpByADisconnect() {
    when(caldavSyncStorage.getPairs(USER, SERVER))
                                                  .thenReturn(List.of(pair(SyncOrigin.EXO,
                                                                           CalendarSyncStatus.EXO_ORPHANED)));

    service.freezeOnDisconnect(USER, SERVER);

    verify(caldavSyncStorage, never()).savePair(any());
  }

  @Test
  public void aCalendarThatWasNeverBoundIsSimplyDeletedInExo() throws Exception {
    // No binding, nothing to propagate. The connector must not become a
    // reason a plain local deletion fails.
    when(caldavSyncStorage.getPairByLocalCalendar(USER, SERVER, ANCHOR)).thenReturn(null);

    service.deleteRemoteCounterpart(USER, CALENDAR);

    verify(calDavClient, never()).deleteCollection(any(), any(), anyString(), anyString());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  @Test
  public void aCalendarCarryingNoAnchorIsDeletedWithoutLookingForABinding() throws Exception {
    Calendar unanchored = calendar();
    unanchored.setSyncUid(null);
    when(agendaCalendarService.getCalendarById(CALENDAR)).thenReturn(unanchored);

    service.deleteRemoteCounterpart(USER, CALENDAR);

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

    service.deleteRemoteCounterpart(USER, CALENDAR);

    verify(calDavClient, never()).deleteCollection(any(), any(), anyString(), anyString());
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

    service.freezeOnDisconnect(USER, SERVER);

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
    when(calDavClient.listCalendars(any(), anyString(), anyString(), anyString())).thenReturn(List.of());
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

  /**
   * @return a connected account
   */
  private CaldavUserSetting settings() {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername(LOGIN);
    setting.setPassword("secret");
    setting.setServerId(SERVER);
    setting.setCaldavUrl(URL);
    return setting;
  }
}
