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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doAnswer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * Bringing the two halves together, and the ways they can feed each other.
 *
 * <p>
 * The test that matters most here is that a collection eXo created is not
 * materialised back as a second eXo calendar. Both halves behave correctly on
 * their own; run in the wrong order, or without the exclusion, they multiply
 * calendars on both sides indefinitely — and each step looks like the feature
 * working.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavSyncServiceTest {

  private static final long          USER   = 42L;

  private static final long          SERVER = 7L;

  private static final String        LOGIN  = "john";

  private static final String        HOME   = "/dav/calendars/john/";

  @Mock
  private CalDavClient               calDavClient;

  @Mock
  private CaldavConnectorStorage     caldavConnectorStorage;

  @Mock
  private CaldavSyncStorage          caldavSyncStorage;

  @Mock
  private CaldavOutboundService      caldavOutboundService;

  @Mock
  private CaldavInboundService       caldavInboundService;

  @Mock
  private AgendaCalendarService      agendaCalendarService;

  @Mock
  private IdentityManager            identityManager;

  @Mock
  private CaldavTuningService        caldavTuningService;

  @Mock
  private CaldavMirrorVerificationService caldavMirrorVerificationService;

  @Mock
  private CalDavEndpoint             endpoint;

  @InjectMocks
  private CaldavSyncService          service;

  @BeforeEach
  public void connectAnAccount() {
    // The engine reads its tuning where it uses it rather than capturing it at
    // bean creation, which is what lets an administrator change it without a
    // restart — so the test stubs the reader, not a field.
    // The reconciliation runs on every pass and a mock answers null, which the
    // pass then reads as a failure. Stubbed to "nothing to clean" so each test
    // exercises what it is actually about.
    lenient().when(caldavInboundService.removeVanishedObjects(anyLong(), any()))
             .thenReturn(CaldavInboundService.VanishedCleanup.nothing());
    lenient().when(caldavTuningService.getThrottleMinutes()).thenReturn(15L);
    lenient().when(caldavTuningService.getPastDays()).thenReturn(60L);
    lenient().when(caldavTuningService.getFutureDays()).thenReturn(365L);
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
    lenient().when(calDavClient.discoverCalendarHome(any(), anyString(), anyString())).thenReturn(HOME);
  }

  @Test
  public void aRemoteCalendarBecomesAnExoCalendar() throws Exception {
    givenServerCalendars(collection("/dav/calendars/john/private/", "Private"));
    givenNoKnownPairs();
    givenAgendaCreates("new-anchor");

    service.syncNow(USER, LOGIN);

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    assertEquals(SyncOrigin.REMOTE, saved.getValue().getOrigin());
    // The anchor agenda minted, not the id: a binding meant to outlive both
    // sides cannot rest on a number a restore may renumber.
    assertEquals("new-anchor", saved.getValue().getLocalCalendarSyncUid());
  }

  @Test
  public void aCollectionExoCreatedIsNeverMaterialisedBack() throws Exception {
    // The loop. Without this exclusion the collection becomes a second eXo
    // calendar, which the outward pass pushes as a third collection, and so on
    // — every step looking like the feature working.
    givenServerCalendars(collection("/dav/calendars/john/exo-cal-mine/", "Work"));
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(exoPair("/dav/calendars/john/exo-cal-mine")));

    service.syncNow(USER, LOGIN);

    verify(agendaCalendarService, never()).createCalendar(any(), anyString());
  }

  @Test
  public void aCollectionAnotherExoUserPushedIsNeverMaterialised() throws Exception {
    // The pair check cannot catch this one: pairs are read for one user, and a
    // CalDAV account can be shared. Two eXo users on the same account would
    // each materialise the other's pushed collections, push the results back
    // as new ones, and multiply calendars without either behaving wrongly.
    // Observed live before this guard existed.
    givenServerCalendars(collection("/dav/calendars/john/exo-cal-946eec40-e9bd-4cd1-89f2-bddfed786d75/", "Someone else's"));
    givenNoKnownPairs();

    service.syncNow(USER, LOGIN);

    verify(agendaCalendarService, never()).createCalendar(any(), anyString());
  }

  @Test
  public void aCollectionWithNoPathIsNeverMaterialised() throws Exception {
    // Server-controlled content becoming platform data: a collection with a
    // blank path can be neither bound to, named, nor found again, and would
    // arrive as a calendar whose name is the blank path.
    givenServerCalendars(collection("   ", "Nameless"));
    givenNoKnownPairs();

    service.syncNow(USER, LOGIN);

    verify(agendaCalendarService, never()).createCalendar(any(), anyString());
  }

  @Test
  public void theEventsOfAMaterialisedCollectionAreImported() throws Exception {
    givenServerCalendars();
    CalendarSync bound = remotePair("/dav/calendars/john/private/", "anchor-1");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(bound));
    givenUserCalendars(calendarWithAnchor(77L, "anchor-1"));

    service.syncNow(USER, LOGIN);

    verify(caldavInboundService).importInto(eq(USER), eq(bound), any(), any(), any());
  }

  @Test
  public void theMirrorIsNeverReadBackIn() throws Exception {
    // The one collection that stays one-way. It is not a calendar of the
    // user's at all — it is eXo's projection of meetings they accepted
    // elsewhere — so reading it back would let a copy overwrite the event it
    // is a copy of, and show every one of those meetings twice.
    givenServerCalendars();
    CalendarSync mirror = mirrorPair("/dav/calendars/john/exo-meetings/");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(mirror));

    service.syncNow(USER, LOGIN);

    verify(caldavInboundService, never()).importInto(anyLong(), any(), any(), any(), any());
  }

  @Test
  public void aCalendarExoCreatedIsNotDeclaredGoneWhenTheListingOmitsIt() throws Exception {
    // "No longer on the account" is a statement about the user's own calendar
    // disappearing from their server, and it earns a warning in the settings.
    // A collection eXo created is absent from the listing for a duller reason
    // — the server reporting it under a name other than the one it was
    // created at, which BlueMind does — and calling that gone flagged the
    // user's own calendars as broken until a later pass revived them.
    //
    // The check only ever saw materialised bindings until the import widened
    // to carry every calendar the user has on their devices. Its sibling
    // above, aCollectionTheAccountNoLongerHoldsIsMarkedGone, is the control:
    // the same omission on a materialised binding must still be marked.
    givenServerCalendars(collection("/dav/calendars/john/other/", "Other"));
    givenAgendaCreates("other-anchor");
    CalendarSync mine = exoPair("/dav/calendars/john/exo-cal-renamed-by-the-server/");
    mine.setLocalCalendarSyncUid("anchor-mine");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(mine));
    givenAgendaHasCalendar("anchor-mine");

    service.syncNow(USER, LOGIN);

    assertEquals(CalendarSyncStatus.ACTIVE, mine.getStatus());
  }

  @Test
  public void aCalendarExoCreatedOnTheAccountIsReadBackIn() throws Exception {
    // The user's own calendar, which happens to have been created from this
    // side. They see it on their devices exactly as they see a materialised
    // one, so an event they edit on a phone belongs back here — who created
    // the collection is eXo's bookkeeping, not a fact about their calendar.
    //
    // The import used to select REMOTE-origin pairs alone, which made the
    // direction depend on that bookkeeping: two calendars sitting side by side
    // under Personal, looking identical, one round-tripping and one silently
    // not.
    givenServerCalendars();
    CalendarSync mine = exoPair("/dav/calendars/john/exo-cal-mine/");
    mine.setLocalCalendarSyncUid("anchor-mine");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(mine));
    givenUserCalendars(calendarWithAnchor(88L, "anchor-mine"));

    service.syncNow(USER, LOGIN);

    verify(caldavInboundService).importInto(eq(USER), eq(mine), any(), any(), any());
  }

  @Test
  public void aCalendarDeletedInExoIsNotFilledBackUp() throws Exception {
    // The tombstone says the user deleted it. Reading its collection and
    // recreating the events is precisely what they asked not to happen.
    givenServerCalendars();
    CalendarSync tombstone = remotePair("/dav/calendars/john/private/", "anchor-1");
    tombstone.setStatus(CalendarSyncStatus.LOCALLY_DELETED);
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(tombstone));

    service.syncNow(USER, LOGIN);

    verify(caldavInboundService, never()).importInto(anyLong(), any(), any(), any(), any());
  }

  @Test
  public void aBindingWhoseCalendarIsGoneIsSkippedRatherThanRecreated() throws Exception {
    // Recreating the calendar here would undo a deletion.
    givenServerCalendars();
    CalendarSync orphan = remotePair("/dav/calendars/john/private/", "anchor-missing");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(orphan));
    givenUserCalendars(calendarWithAnchor(77L, "another-anchor"));

    service.syncNow(USER, LOGIN);

    verify(caldavInboundService, never()).importInto(anyLong(), any(), any(), any(), any());
  }

  @Test
  public void oneCollectionThatFailsToImportDoesNotCostTheOthers() throws Exception {
    givenServerCalendars();
    CalendarSync first = remotePair("/dav/calendars/john/a/", "anchor-1");
    CalendarSync second = remotePair("/dav/calendars/john/b/", "anchor-2");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(first, second));
    givenUserCalendars(calendarWithAnchor(77L, "anchor-1"), calendarWithAnchor(78L, "anchor-2"));
    when(caldavInboundService.importInto(anyLong(), eq(first), any(), any(), any()))
                                                                                    .thenThrow(new IllegalStateException("down"));

    service.syncNow(USER, LOGIN);

    verify(caldavInboundService).importInto(eq(USER), eq(second), any(), any(), any());
  }

  /**
   * @param href the collection this pair is bound to
   * @param anchor the eXo calendar's sync uid
   * @return a binding the user's own client created
   */
  /**
   * The mirror binding: eXo's own collection on the account.
   *
   * @param href where it lives
   * @return the pair
   */
  private CalendarSync mirrorPair(String href) {
    CalendarSync pair = new CalendarSync();
    pair.setId(3L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setRemoteHref(href);
    pair.setOrigin(SyncOrigin.MIRROR);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  private CalendarSync remotePair(String href, String anchor) {
    CalendarSync pair = new CalendarSync();
    pair.setId(2L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setRemoteHref(href);
    pair.setLocalCalendarSyncUid(anchor);
    pair.setOrigin(SyncOrigin.REMOTE);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  /**
   * @param id the calendar identifier
   * @param anchor its sync uid
   * @return a calendar the user owns
   */
  private Calendar calendarWithAnchor(long id, String anchor) {
    Calendar calendar = new Calendar();
    calendar.setId(id);
    calendar.setOwnerId(USER);
    calendar.setSyncUid(anchor);
    return calendar;
  }

  /**
   * @param calendars what agenda answers for this user
   * @throws Exception when the stub cannot be set
   */
  private void givenUserCalendars(Calendar... calendars) throws Exception {
    when(agendaCalendarService.getCalendars(eq(0), anyInt(), eq(LOGIN))).thenReturn(List.of(calendars));
  }

  @Test
  public void aBindingWithNoCalendarBehindItIsPrunedBeforeMaterialising() throws Exception {
    // Order is the whole point. Such a binding is exactly what makes
    // materialisation skip a collection, so healing it afterwards leaves the
    // user waiting a whole throttle window for a calendar that could have
    // come back in this same pass.
    givenServerCalendars(collection("/dav/calendars/john/private/", "Private"));
    CalendarSync orphan = remotePair("/dav/calendars/john/private/", "anchor-gone");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of());
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(orphan));
    givenUserCalendars(calendarWithAnchor(77L, "another-anchor"));
    givenAgendaCreates("anchor-new");

    service.syncNow(USER, LOGIN);

    InOrder order = inOrder(caldavSyncStorage, agendaCalendarService);
    order.verify(caldavSyncStorage).deletePair(orphan.getId());
    order.verify(agendaCalendarService).createCalendar(any(), eq(LOGIN));
  }

  @Test
  public void aTombstoneIsNeverPruned() throws Exception {
    // Its whole purpose is to keep the collection out. Pruning it would bring
    // back exactly what the user asked to be rid of.
    givenServerCalendars(collection("/dav/calendars/john/private/", "Private"));
    CalendarSync tombstone = remotePair("/dav/calendars/john/private/", "anchor-gone");
    tombstone.setStatus(CalendarSyncStatus.LOCALLY_DELETED);
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(tombstone));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(tombstone));
    givenUserCalendars(calendarWithAnchor(77L, "another-anchor"));

    service.syncNow(USER, LOGIN);

    verify(caldavSyncStorage, never()).deletePair(anyLong());
    verify(agendaCalendarService, never()).createCalendar(any(), anyString());
  }

  @Test
  public void calendarsThatCannotBeReadPruneNothing() throws Exception {
    // Every binding would look like an orphan. Pruning them would throw away
    // bindings whose calendars are perfectly well — the worst possible
    // reading of a read failure.
    givenServerCalendars();
    CalendarSync bound = remotePair("/dav/calendars/john/private/", "anchor-1");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(bound));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(bound));
    when(agendaCalendarService.getCalendars(eq(0), anyInt(), eq(LOGIN))).thenThrow(new IllegalStateException("down"));

    service.syncNow(USER, LOGIN);

    verify(caldavSyncStorage, never()).deletePair(anyLong());
  }

  @Test
  public void theMirrorIsNeverMaterialised() throws Exception {
    // Its contents are copies of events eXo already shows.
    givenServerCalendars(collection("/dav/calendars/john/exo-meetings/", "eXo Meetings"));
    givenNoKnownPairs();

    service.syncNow(USER, LOGIN);

    verify(agendaCalendarService, never()).createCalendar(any(), anyString());
  }

  @Test
  public void aCalendarDeletedInExoDoesNotComeStraightBack() throws Exception {
    // The tombstone is what makes the deletion stick. Without it the next sync
    // sees a collection eXo has no calendar for and recreates it in front of
    // the user.
    givenServerCalendars(collection("/dav/calendars/john/private/", "Private"));
    CalendarSync tombstone = exoPair("/dav/calendars/john/private");
    tombstone.setOrigin(SyncOrigin.REMOTE);
    tombstone.setStatus(CalendarSyncStatus.LOCALLY_DELETED);
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(tombstone));

    service.syncNow(USER, LOGIN);

    verify(agendaCalendarService, never()).createCalendar(any(), anyString());
  }

  @Test
  public void theOutwardHalfRunsFirst() throws Exception {
    // Binding marks the user's own collections ORIGIN=EXO, which is exactly
    // what the inward pass then skips. The other order would materialise every
    // collection eXo is about to create.
    givenServerCalendars();
    givenNoKnownPairs();

    service.syncNow(USER, LOGIN);

    InOrder order = inOrder(caldavOutboundService, calDavClient);
    order.verify(caldavOutboundService).bindPersonalCalendars(USER, LOGIN);
    order.verify(calDavClient).listCalendars(any(), eq(HOME), anyString(), anyString());
  }

  @Test
  public void openingTheAgendaAgainDoesNotTalkToTheServerAgain() throws Exception {
    givenServerCalendars();
    givenNoKnownPairs();

    service.syncIfDue(USER, LOGIN);
    service.syncIfDue(USER, LOGIN);
    service.syncIfDue(USER, LOGIN);

    // A page load must not wait on a calendar server that has nothing new to
    // say, and three loads in a minute are three page loads, not three
    // reasons to sync.
    verify(caldavOutboundService, times(1)).bindPersonalCalendars(USER, LOGIN);
  }

  @Test
  public void syncNowIgnoresTheThrottle() throws Exception {
    // A user pressing it has a reason the throttle cannot know: they just
    // changed something on their phone.
    givenServerCalendars();
    givenNoKnownPairs();

    service.syncIfDue(USER, LOGIN);
    service.syncNow(USER, LOGIN);

    verify(caldavOutboundService, times(2)).bindPersonalCalendars(USER, LOGIN);
  }

  @Test
  public void anAccountThatIsNotConnectedSyncsNothing() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    service.syncNow(USER, LOGIN);

    verify(caldavOutboundService, never()).bindPersonalCalendars(anyLong(), anyString());
  }

  @Test
  public void aFailingSyncIsLoggedRatherThanThrownAtThePage() {
    // The page that triggered it has its own events to show; a calendar server
    // being down is not a reason to fail rendering an agenda.
    when(caldavOutboundService.bindPersonalCalendars(USER, LOGIN)).thenThrow(new IllegalStateException("down"));

    assertDoesNotThrow(() -> service.syncNow(USER, LOGIN));

    // And the failure was reached rather than avoided: without this the test
    // would pass just as well if the sync never got as far as the call that
    // throws, which would prove nothing about swallowing it.
    verify(caldavOutboundService).bindPersonalCalendars(USER, LOGIN);
  }

  @Test
  public void aFailedSyncIsRetriedAtTheNextTrigger() {
    // The throttle is only stamped on success, so a failure does not buy the
    // server fifteen minutes of silence it did not earn.
    when(caldavOutboundService.bindPersonalCalendars(USER, LOGIN)).thenThrow(new IllegalStateException("down"));

    service.syncIfDue(USER, LOGIN);
    service.syncIfDue(USER, LOGIN);

    verify(caldavOutboundService, times(2)).bindPersonalCalendars(USER, LOGIN);
  }

  /**
   * The collections the server lists.
   *
   * @param collections what the listing answers
   */
  private void givenServerCalendars(CalendarCollection... collections) {
    lenient().when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString()))
             .thenReturn(List.of(collections));
  }

  /**
   * A user with no bindings yet.
   */
  private void givenNoKnownPairs() {
    lenient().when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of());
  }

  /**
   * Agenda creating a calendar and minting its anchor.
   *
   * @param anchor the sync uid agenda mints
   * @throws Exception when the stub cannot be set
   */
  private void givenAgendaCreates(String anchor) throws Exception {
    Calendar created = new Calendar();
    created.setId(99L);
    created.setSyncUid(anchor);
    when(agendaCalendarService.createCalendar(any(), eq(LOGIN))).thenReturn(created);
  }

  @Test
  public void aTaskListIsNotACalendarAndIsNeverMaterialised() throws Exception {
    // A CalDAV home holds more than calendars. BlueMind publishes the
    // account's task list beside them, and it answers a PROPFIND for calendars
    // exactly as a calendar does — so the only thing separating the two is the
    // component set it declares. Materialising it hands the user an eXo
    // calendar that can never hold an event.
    givenServerCalendars(collectionOf("/dav/calendars/john/todolist:default/", "Mes taches", Set.of("VTODO")));
    givenNoKnownPairs();

    service.syncNow(USER, LOGIN);

    verify(agendaCalendarService, never()).createCalendar(any(), anyString());
  }

  @Test
  public void aCollectionDeclaringNoComponentSetIsStillACalendar() throws Exception {
    // RFC 4791 makes the property optional, and its absence means every
    // component is supported. Reading "undeclared" as "holds no events" would
    // silently drop the calendars of every server that omits it.
    givenServerCalendars(collectionOf("/dav/calendars/john/private/", "Private", Set.of()));
    givenNoKnownPairs();
    givenAgendaCreates("anchor-1");

    service.syncNow(USER, LOGIN);

    verify(agendaCalendarService).createCalendar(any(), eq(LOGIN));
  }

  @Test
  public void aCollectionDeclaringEventsAmongOthersIsACalendar() throws Exception {
    // A collection may hold several component types. Requiring VEVENT alone
    // would exclude an ordinary calendar that also accepts todos.
    givenServerCalendars(collectionOf("/dav/calendars/john/mixed/", "Mixed", Set.of("VEVENT", "VTODO")));
    givenNoKnownPairs();
    givenAgendaCreates("anchor-2");

    service.syncNow(USER, LOGIN);

    verify(agendaCalendarService).createCalendar(any(), eq(LOGIN));
  }

  @Test
  public void aMaterialisedCalendarIsNamedAfterTheCollection() throws Exception {
    // Agenda persists getName(); getTitle() is a display field it computes,
    // and a calendar left nameless falls back to its owner's identity. Setting
    // the title alone is why two materialised collections both came out named
    // after their owner instead of after themselves.
    givenServerCalendars(collectionOf("/dav/calendars/john/private/", "Holidays", Set.of("VEVENT")));
    givenNoKnownPairs();
    givenAgendaCreates("anchor-3");

    service.syncNow(USER, LOGIN);

    ArgumentCaptor<Calendar> created = ArgumentCaptor.forClass(Calendar.class);
    verify(agendaCalendarService).createCalendar(created.capture(), eq(LOGIN));
    assertEquals("Holidays", created.getValue().getName());
  }

  @Test
  public void aMaterialisedCalendarKeepsTheColourItHadOnTheServer() throws Exception {
    // The user recognises their calendars by colour before they read their
    // names. Materialising one in a colour the platform picked would show them
    // the same calendar twice over in two different colours.
    givenServerCalendars(new CalendarCollection("/dav/calendars/john/private/",
                                                "Holidays",
                                                "ctag-1",
                                                "token-1",
                                                "#0088FF",
                                                true,
                                                Set.of("VEVENT")));
    givenNoKnownPairs();
    givenAgendaCreates("anchor-4");

    service.syncNow(USER, LOGIN);

    ArgumentCaptor<Calendar> created = ArgumentCaptor.forClass(Calendar.class);
    verify(agendaCalendarService).createCalendar(created.capture(), eq(LOGIN));
    assertEquals("#0088FF", created.getValue().getColor());
  }

  @Test
  public void theMaterialisedPairRecordsWhereItCameFrom() throws Exception {
    // The path is how the binding finds its collection again, and ACTIVE is
    // what separates a live binding from a tombstone.
    //
    // This used to assert the ctag and the sync token here too, on the
    // reasoning that they are what let the next pass ask "anything new?"
    // instead of reading the whole collection back. That reasoning is right
    // about the ctag and wrong about when to record it: written before the
    // collection has been read, it says the binding is up to date with a
    // collection it has never opened, and the import step believes it. The
    // ctag is recorded in importRemoteEvents, after a read that succeeded —
    // which is the only moment it is true. See
    // aFreshlyMaterialisedBindingClaimsNothingItHasNotRead.
    givenServerCalendars(collectionOf("/dav/calendars/john/private/", "Private", Set.of("VEVENT")));
    givenNoKnownPairs();
    givenAgendaCreates("anchor-5");

    service.syncNow(USER, LOGIN);

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    assertEquals("/dav/calendars/john/private/", saved.getValue().getRemoteHref());
    assertEquals(CalendarSyncStatus.ACTIVE, saved.getValue().getStatus());
  }

  @Test
  public void aCollectionTheServerNamedNothingIsNamedAfterItsPath() throws Exception {
    // A calendar left with no name at all falls back to its owner's identity
    // in agenda, so the path — ugly but unique — beats letting that happen.
    givenServerCalendars(collectionOf("/dav/calendars/john/private/", "  ", Set.of("VEVENT")));
    givenNoKnownPairs();
    givenAgendaCreates("anchor-6");

    service.syncNow(USER, LOGIN);

    ArgumentCaptor<Calendar> created = ArgumentCaptor.forClass(Calendar.class);
    verify(agendaCalendarService).createCalendar(created.capture(), eq(LOGIN));
    assertEquals("/dav/calendars/john/private/", created.getValue().getName());
  }

  @Test
  public void aCollectionWithNoPathAtAllIsSkippedRatherThanBoundToNothing() throws Exception {
    // A pair keyed on a null href binds nothing and can never be matched
    // again, so the next pass would materialise the collection all over.
    givenServerCalendars(collectionOf(null, "Nameless", Set.of("VEVENT")));
    givenNoKnownPairs();

    service.syncNow(USER, LOGIN);

    verify(agendaCalendarService, never()).createCalendar(any(), anyString());
  }

  @Test
  public void aCollectionTheUserDesignatedAsTheMirrorIsNeverMaterialised() throws Exception {
    // The mirror is not always the collection eXo minted: a user may point it
    // at one of their own, whose path carries none of eXo's markers. The
    // recorded href is then the only thing identifying it, and materialising
    // it would show every pushed meeting a second time.
    CaldavUserSetting designated = settings();
    designated.setMirrorCalendarHref("/dav/calendars/john/my-own-mirror/");
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(designated);
    givenServerCalendars(collectionOf("/dav/calendars/john/my-own-mirror/", "Meetings", Set.of("VEVENT")));
    givenNoKnownPairs();

    service.syncNow(USER, LOGIN);

    verify(agendaCalendarService, never()).createCalendar(any(), anyString());
  }

  @Test
  public void aCalendarAgendaRefusesToCreateLeavesNoPairBehind() throws Exception {
    // A pair pointing at a calendar that was never created is worse than no
    // pair: the collection then counts as accounted for, and the user never
    // gets it — quietly, on every later sync.
    givenServerCalendars(collectionOf("/dav/calendars/john/private/", "Private", Set.of("VEVENT")));
    givenNoKnownPairs();
    when(agendaCalendarService.createCalendar(any(), eq(LOGIN))).thenThrow(new IllegalAccessException("refused"));

    service.syncNow(USER, LOGIN);

    verify(caldavSyncStorage, never()).savePair(any());
  }

  @Test
  public void anAccountWhoseCalendarsCannotBeListedMaterialisesNothingAndDoesNotFail() {
    // The outward half already ran and its work stands; a listing that failed
    // says nothing about what the account holds, and inventing calendars from
    // an empty answer would delete-by-omission on the next pass.
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenThrow(new CalDavException("unreachable"));

    service.syncNow(USER, LOGIN);

    verify(caldavOutboundService).bindPersonalCalendars(USER, LOGIN);
    verify(caldavSyncStorage, never()).savePair(any());
  }

  @Test
  public void aSyncAlreadyRunningForTheUserIsNotStartedAgainBeneathItself() {
    // Two page loads a second apart would otherwise both create the same
    // calendar, and the second would find the first's collection listed and
    // bind a duplicate to it. Re-entering from inside the running pass is the
    // same race, made deterministic.
    when(caldavOutboundService.bindPersonalCalendars(USER, LOGIN)).thenAnswer(invocation -> {
      service.syncNow(USER, LOGIN);
      return List.of();
    });
    givenServerCalendars();
    givenNoKnownPairs();

    service.syncNow(USER, LOGIN);

    verify(caldavOutboundService, times(1)).bindPersonalCalendars(USER, LOGIN);
  }

  @Test
  public void aSuccessfulSyncBuysTheServerItsQuietPeriod() {
    // The stamp is written at the end of a pass that worked, which is what
    // makes the next page load cost nothing.
    givenServerCalendars();
    givenNoKnownPairs();

    service.syncNow(USER, LOGIN);
    service.syncIfDue(USER, LOGIN);

    verify(caldavOutboundService, times(1)).bindPersonalCalendars(USER, LOGIN);
  }

  @Test
  public void aSyncThatHasGoneStaleIsDueAgain() {
    // The other side of the throttle, and the one a test that only ever runs
    // in a few milliseconds never reaches by itself: a stamp older than the
    // window must not keep the account frozen. A throttle that never expired
    // would look identical for fifteen minutes and then never sync again.
    when(caldavTuningService.getThrottleMinutes()).thenReturn(0L);
    givenServerCalendars();
    givenNoKnownPairs();

    service.syncIfDue(USER, LOGIN);
    service.syncIfDue(USER, LOGIN);

    verify(caldavOutboundService, times(2)).bindPersonalCalendars(USER, LOGIN);
  }

  @Test
  public void anAccountWithNoPasswordSyncsNothing() {
    // Half an account is not an account: asking the server with a blank
    // password earns a credential refusal on every page load.
    CaldavUserSetting halfConnected = settings();
    halfConnected.setPassword("  ");
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(halfConnected);

    service.syncNow(USER, LOGIN);

    verify(caldavOutboundService, never()).bindPersonalCalendars(anyLong(), anyString());
  }

  @Test
  public void anAccountWithNoUsernameSyncsNothing() {
    CaldavUserSetting halfConnected = settings();
    halfConnected.setUsername(null);
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(halfConnected);

    service.syncNow(USER, LOGIN);

    verify(caldavOutboundService, never()).bindPersonalCalendars(anyLong(), anyString());
  }

  @Test
  public void anAccountConnectedBeforeServersWereDeclaredStillPairs() throws Exception {
    // Accounts predate the server registry, and theirs is null. Reading it
    // straight into a primitive would throw and cost those users the whole
    // inward sync rather than one column's worth of it.
    CaldavUserSetting undeclared = settings();
    undeclared.setServerId(null);
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(undeclared);
    when(caldavSyncStorage.getPairs(USER, 0L)).thenReturn(List.of());
    givenServerCalendars(collectionOf("/dav/calendars/john/private/", "Private", Set.of("VEVENT")));
    givenAgendaCreates("anchor-7");

    service.syncNow(USER, LOGIN);

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    assertEquals(0L, saved.getValue().getServerId());
  }

  /**
   * @param href the collection path
   * @param name its display name
   * @param components the component types it declares
   * @return a listed collection
   */
  private CalendarCollection collectionOf(String href, String name, Set<String> components) {
    return new CalendarCollection(href, name, "ctag-1", "token-1", null, true, components);
  }

  /**
   * @param href the collection path
   * @param name its display name
   * @return a listed collection
   */
  private CalendarCollection collection(String href, String name) {
    return new CalendarCollection(href, name, "ctag-1", "token-1", null, true);
  }

  /**
   * @param href the collection this pair is bound to
   * @return a binding eXo created
   */
  private CalendarSync exoPair(String href) {
    CalendarSync pair = new CalendarSync();
    pair.setId(1L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setRemoteHref(href);
    pair.setOrigin(SyncOrigin.EXO);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  /**
   * @return a connected account
   */
  /**
   * A successful import moves the time the settings row shows.
   */
  @Test
  public void animportedCollectionStampsTheBinding() {
    // The defect this pins: the field was written only when a binding was
    // created, so an account whose calendars were all already bound reported
    // the day it was connected however often it synchronised — and a user
    // pressing Sync now saw nothing move.
    givenServerCalendars(collection("/dav/calendars/john/private/", "Private"));
    CalendarSync pair = activeRemotePair("/dav/calendars/john/private/", "anchor-1");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(pair));
    givenAgendaHasCalendar("anchor-1");

    service.syncNow(USER, LOGIN);

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage, atLeastOnce()).savePair(saved.capture());
    assertTrue(saved.getAllValues().stream().anyMatch(p -> p.getLastSyncEnd() != null),
               "a collection that imported must stamp when it finished");
  }

  /**
   * A collection that failed does not claim to have synchronised.
   */
  @Test
  public void aCollectionThatFailedIsNotStamped() {
    // Stamping on failure would be worse than not stamping at all: the line
    // exists to say when eXo last got through, and a user whose account has
    // been unreachable for a day would be told it just synchronised.
    givenServerCalendars(collection("/dav/calendars/john/private/", "Private"));
    CalendarSync pair = activeRemotePair("/dav/calendars/john/private/", "anchor-1");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(pair));
    givenAgendaHasCalendar("anchor-1");
    doThrow(new IllegalStateException("server down")).when(caldavInboundService)
                                                     .importInto(anyLong(), any(), any(), any(), any());

    service.syncNow(USER, LOGIN);

    assertEquals(null, pair.getLastSyncEnd());
  }

  /**
   * @param href the collection the binding points at
   * @param anchor the local calendar's anchor
   * @return an active binding of remote origin
   */
  private CalendarSync activeRemotePair(String href, String anchor) {
    CalendarSync pair = new CalendarSync();
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setRemoteHref(href);
    pair.setLocalCalendarSyncUid(anchor);
    pair.setOrigin(SyncOrigin.REMOTE);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  /**
   * @param anchor the anchor agenda's calendar carries
   */
  private void givenAgendaHasCalendar(String anchor) {
    Calendar calendar = new Calendar();
    calendar.setId(1L);
    calendar.setOwnerId(USER);
    calendar.setSyncUid(anchor);
    try {
      when(agendaCalendarService.getCalendars(anyInt(), anyInt(), anyString())).thenReturn(List.of(calendar));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Several stale bindings of one account cost one pass, not one each.
   */
  @Test
  public void anAccountWithSeveralStaleCalendarsIsSweptOnce() {
    // Synchronisation is per account: a user with five stale calendars would
    // otherwise be synchronised five times in a row, each pass doing the work
    // of all five.
    CalendarSync one = activeRemotePair("/dav/calendars/john/a/", "anchor-a");
    CalendarSync two = activeRemotePair("/dav/calendars/john/b/", "anchor-b");
    givenDuePairs(one, two);
    givenServerCalendars();
    givenNoKnownPairs();

    assertEquals(1, service.sweepDueAccounts(30L, 50));
  }

  /**
   * An account whose login cannot be resolved is left for the next run.
   */
  @Test
  public void anAccountWithoutAResolvableLoginIsSkipped() {
    // Agenda's ACL needs the login and a binding does not carry it. Guessing
    // one would be worse than waiting.
    givenDuePairs(activeRemotePair("/dav/calendars/john/a/", "anchor-a"));
    when(identityManager.getIdentity(anyString())).thenReturn(null);

    assertEquals(0, service.sweepDueAccounts(30L, 50));
  }

  /**
   * One account failing does not cost the rest of the page.
   */
  @Test
  public void oneFailingAccountDoesNotStopTheSweep() {
    CalendarSync mine = activeRemotePair("/dav/calendars/john/a/", "anchor-a");
    CalendarSync theirs = activeRemotePair("/dav/calendars/jane/a/", "anchor-b");
    theirs.setUserIdentityId(USER + 1);
    givenDuePairs(mine, theirs);
    givenServerCalendars();
    givenNoKnownPairs();
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenThrow(new IllegalStateException("boom"));

    // The failing one is logged and left stale; it comes back at the top of
    // the next run because the page is ordered by how long it has waited.
    assertEquals(1, service.sweepDueAccounts(30L, 50));
  }

  /**
   * Nothing due costs nothing.
   */
  @Test
  public void aSweepWithNothingDueDoesNoWork() {
    when(caldavSyncStorage.getDuePairs(eq(CalendarSyncStatus.ACTIVE), any(), anyInt(), anyInt()))
                                                                                                .thenReturn(Page.empty());

    assertEquals(0, service.sweepDueAccounts(30L, 50));
    verify(caldavConnectorStorage, never()).getCaldavSetting(anyLong());
  }

  /**
   * @param pairs what the sweep should find due
   */
  private void givenDuePairs(CalendarSync... pairs) {
    when(caldavSyncStorage.getDuePairs(eq(CalendarSyncStatus.ACTIVE), any(), anyInt(), anyInt()))
                                                                                                .thenReturn(new PageImpl<>(List.of(pairs)));
    Identity identity = new Identity(String.valueOf(USER));
    identity.setRemoteId(LOGIN);
    lenient().when(identityManager.getIdentity(anyString())).thenReturn(identity);
  }

  /**
   * A refused password stops the account rather than being retried.
   */
  @Test
  public void aRefusedPasswordPausesTheAccountAtOnce() {
    // Retried on every page load it is a login attempt every few minutes
    // against a server that may well be counting them. Locking the account is
    // a worse outcome than not synchronising.
    CalendarSync bound = activeRemotePair("/dav/calendars/john/private/", "anchor-1");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(bound));
    when(calDavClient.discoverCalendarHome(any(), anyString(), anyString()))
                                                                           .thenThrow(new CalDavAuthenticationException("401"));

    service.syncNow(USER, LOGIN);

    assertEquals(CalendarSyncStatus.PAUSED, bound.getStatus());
  }

  /**
   * A collection the account no longer lists stops receiving events.
   */
  @Test
  public void aCollectionTheAccountNoLongerHoldsIsMarkedGone() throws Exception {
    givenServerCalendars(collection("/dav/calendars/john/other/", "Other"));
    givenAgendaCreates("other-anchor");
    CalendarSync bound = activeRemotePair("/dav/calendars/john/private/", "anchor-1");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(bound));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(bound));
    givenAgendaHasCalendar("anchor-1");

    service.syncNow(USER, LOGIN);

    assertEquals(CalendarSyncStatus.REMOTE_GONE, bound.getStatus());
    verify(caldavInboundService, never()).importInto(anyLong(), any(), any(), any(), any());
  }

  /**
   * An empty listing is not taken as every calendar having been deleted.
   */
  @Test
  public void anEmptyListingDoesNotMarkEverythingGone() {
    // A server briefly answering with nothing is far likelier than a user
    // deleting every calendar they have, and the second conclusion would mark
    // the whole account gone in a single pass.
    givenServerCalendars();
    CalendarSync bound = activeRemotePair("/dav/calendars/john/private/", "anchor-1");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(bound));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(bound));
    givenAgendaHasCalendar("anchor-1");

    service.syncNow(USER, LOGIN);

    assertEquals(CalendarSyncStatus.ACTIVE, bound.getStatus());
  }

  /**
   * A collection that comes back starts receiving events again.
   */
  @Test
  public void aCollectionThatComesBackIsRevived() {
    // Materialisation skips a collection that already has a binding, so
    // without this a calendar deleted and restored on the user's own client
    // would never fill again.
    givenServerCalendars(collection("/dav/calendars/john/private/", "Private"));
    CalendarSync gone = activeRemotePair("/dav/calendars/john/private/", "anchor-1");
    gone.setStatus(CalendarSyncStatus.REMOTE_GONE);
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(gone));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(gone));
    givenAgendaHasCalendar("anchor-1");

    service.syncNow(USER, LOGIN);

    assertEquals(CalendarSyncStatus.ACTIVE, gone.getStatus());
  }

  /**
   * A collection failing every time is eventually left alone.
   */
  @Test
  public void aCollectionThatKeepsFailingIsPaused() {
    givenServerCalendars(collection("/dav/calendars/john/private/", "Private"));
    CalendarSync bound = activeRemotePair("/dav/calendars/john/private/", "anchor-1");
    bound.setConsecutiveFailures(4);
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(bound));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(bound));
    givenAgendaHasCalendar("anchor-1");
    doThrow(new IllegalStateException("boom")).when(caldavInboundService)
                                              .importInto(anyLong(), any(), any(), any(), any());

    service.syncNow(USER, LOGIN);

    assertEquals(CalendarSyncStatus.PAUSED, bound.getStatus());
  }

  /**
   * One success clears what earlier failures had counted.
   */
  @Test
  public void oneSuccessForgivesTheFailuresBeforeIt() {
    // Otherwise a collection that fails now and then would reach the limit
    // eventually, however well it works in between.
    givenServerCalendars(collection("/dav/calendars/john/private/", "Private"));
    CalendarSync bound = activeRemotePair("/dav/calendars/john/private/", "anchor-1");
    bound.setConsecutiveFailures(4);
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(bound));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(bound));
    givenAgendaHasCalendar("anchor-1");

    service.syncNow(USER, LOGIN);

    assertEquals(0, bound.getConsecutiveFailures());
    assertEquals(CalendarSyncStatus.ACTIVE, bound.getStatus());
  }

  /**
   * A collection whose ctag has not moved is not read again.
   */
  @Test
  public void anUnchangedCollectionIsNotReadAgain() {
    // The whole point: an account with a handful of calendars and a year-wide
    // window pays for the full walk on every agenda open, most of it for
    // nothing.
    givenServerCalendars(collection("/dav/calendars/john/private/", "Private"));
    CalendarSync pair = activeRemotePair("/dav/calendars/john/private/", "anchor-1");
    pair.setCtag("ctag-1");
    pair.setLastSyncEnd(new Date());
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(pair));
    givenAgendaHasCalendar("anchor-1");

    service.syncNow(USER, LOGIN);

    verify(caldavInboundService, never()).importInto(anyLong(), any(), any(), any(), any());
  }

  /**
   * A collection whose ctag moved is read.
   */
  @Test
  public void aChangedCollectionIsRead() {
    givenServerCalendars(collection("/dav/calendars/john/private/", "Private"));
    CalendarSync pair = activeRemotePair("/dav/calendars/john/private/", "anchor-1");
    pair.setCtag("ctag-0");
    pair.setLastSyncEnd(new Date());
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(pair));
    givenAgendaHasCalendar("anchor-1");

    service.syncNow(USER, LOGIN);

    verify(caldavInboundService).importInto(anyLong(), any(), any(), any(), any());
  }

  /**
   * A server publishing no ctag is read in full rather than assumed quiet.
   */
  @Test
  public void aCollectionWithoutACtagIsAlwaysRead() {
    // Silence is not "nothing changed". Reading it as such would stop
    // importing entirely from any server that does not publish the property.
    givenServerCalendars(new CalendarCollection("/dav/calendars/john/private/", "Private", null, null, null, true));
    CalendarSync pair = activeRemotePair("/dav/calendars/john/private/", "anchor-1");
    pair.setLastSyncEnd(new Date());
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(pair));
    givenAgendaHasCalendar("anchor-1");

    service.syncNow(USER, LOGIN);

    verify(caldavInboundService).importInto(anyLong(), any(), any(), any(), any());
  }

  /**
   * A matching ctag read before today is not enough to skip.
   */
  @Test
  public void aCollectionLastReadBeforeTodayIsReadAgain() {
    // The window is cut at a day boundary and reaches a fixed number of days
    // forward. Yesterday's read covered a range that stops one day short of
    // today's, so an event in that last day would never be imported however
    // quiet the collection has been.
    givenServerCalendars(collection("/dav/calendars/john/private/", "Private"));
    CalendarSync pair = activeRemotePair("/dav/calendars/john/private/", "anchor-1");
    pair.setCtag("ctag-1");
    pair.setLastSyncEnd(Date.from(Instant.now().minus(Duration.ofDays(2))));
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(pair));
    givenAgendaHasCalendar("anchor-1");

    service.syncNow(USER, LOGIN);

    verify(caldavInboundService).importInto(anyLong(), any(), any(), any(), any());
  }

  /**
   * The ctag is recorded only once the import went through.
   */
  @Test
  public void aFailedImportDoesNotRecordTheCtag() {
    // Recording it on the way in would make one failed collection look
    // unchanged for ever: the next pass would compare the same ctag, find it
    // equal, and never retry what it missed.
    givenServerCalendars(collection("/dav/calendars/john/private/", "Private"));
    CalendarSync pair = activeRemotePair("/dav/calendars/john/private/", "anchor-1");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pair));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.REMOTE)).thenReturn(List.of(pair));
    givenAgendaHasCalendar("anchor-1");
    doThrow(new IllegalStateException("server down")).when(caldavInboundService)
                                                     .importInto(anyLong(), any(), any(), any(), any());

    service.syncNow(USER, LOGIN);

    assertEquals(null, pair.getCtag());
  }

  /**
   * The state the settings row shows is the latest across the bindings.
   */
  @Test
  public void theLastSyncIsTheLatestAcrossBindings() {
    // The question the line answers is "when did eXo last speak to my
    // account", so one binding that has been failing on its own must not drag
    // the whole line back to its own last success.
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pairSyncedAt(new Date(1_000L)),
                                                                     pairSyncedAt(new Date(9_000L)),
                                                                     pairSyncedAt(new Date(5_000L))));

    assertEquals(new Date(9_000L), service.lastSyncEnd(USER));
  }

  /**
   * A binding that has never finished is skipped rather than counted as now.
   */
  @Test
  public void aBindingThatNeverFinishedIsNotATime() {
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pairSyncedAt(null),
                                                                     pairSyncedAt(new Date(3_000L))));

    assertEquals(new Date(3_000L), service.lastSyncEnd(USER));
  }

  /**
   * An account whose every binding is new has no time to show.
   */
  @Test
  public void anAccountThatNeverSynchronisedHasNoTime() {
    // Null rather than the epoch: the row has to tell "not yet" apart from a
    // date, and any date it invented would read as a real one.
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(pairSyncedAt(null)));

    assertEquals(null, service.lastSyncEnd(USER));
  }

  /**
   * No connected account is asked nothing of the storage.
   */
  @Test
  public void anAccountThatIsNotConnectedIsNotLookedUp() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    assertEquals(null, service.lastSyncEnd(USER));
    verify(caldavSyncStorage, never()).getPairs(anyLong(), anyLong());
  }

  /**
   * @param lastSyncEnd when this binding last finished, null when never
   * @return a binding carrying that time
   */
  private CalendarSync pairSyncedAt(Date lastSyncEnd) {
    CalendarSync pair = new CalendarSync();
    pair.setLastSyncEnd(lastSyncEnd);
    return pair;
  }


  @Test
  public void aCallerToldTheSyncRanWaitsForThePassActuallyRunningIt() throws Exception {
    // The whole reason connecting looked unstable. A second caller used to be
    // handed an immediate "done" while the first pass was still materialising
    // collections, so the display it refreshed on the strength of that answer
    // showed calendars eXo had not finished taking in yet — and kept showing
    // them until the page was reloaded.
    CountDownLatch passStarted = new CountDownLatch(1);
    CountDownLatch releasePass = new CountDownLatch(1);
    when(caldavOutboundService.bindPersonalCalendars(USER, LOGIN)).thenAnswer(invocation -> {
      passStarted.countDown();
      releasePass.await(5, TimeUnit.SECONDS);
      return null;
    });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> first = pool.submit(() -> service.syncNow(USER, LOGIN));
      assertTrue(passStarted.await(5, TimeUnit.SECONDS), "the first pass never started");

      AtomicBoolean returned = new AtomicBoolean(false);
      Thread waiter = new Thread(() -> {
        service.syncNowAndWait(USER, LOGIN);
        returned.set(true);
      });
      waiter.start();
      Thread.sleep(300);
      assertFalse(returned.get(), "the caller was told the sync had run while the pass was still running");

      releasePass.countDown();
      waiter.join(5000);
      first.get(5, TimeUnit.SECONDS);
      assertTrue(returned.get(), "the caller was never released when the pass finished");
    } finally {
      releasePass.countDown();
      pool.shutdownNow();
    }
  }


  @Test
  public void aCollectionMaterialisedWithEventsAlreadyInItIsReadInTheSamePass() throws Exception {
    // The ordinary first connect, and it was the one case that failed. The
    // binding used to be stamped with the collection's ctag and a sync time
    // the moment it was created, before a single event had been read out of
    // it — so the import step, which skips a collection whose ctag still
    // matches and whose last sync is from today, agreed there was nothing to
    // read. The calendar arrived empty and stayed empty until somebody
    // changed it on the server and moved the ctag.
    //
    // The storage is modelled rather than stubbed flat, because the defect
    // only shows across the two steps of one pass: what materialisation saves
    // is what the import then reads back.
    List<CalendarSync> stored = new ArrayList<>();
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenAnswer(invocation -> List.copyOf(stored));
    doAnswer(invocation -> {
      CalendarSync saved = invocation.getArgument(0);
      if (!stored.contains(saved)) {
        stored.add(saved);
      }
      return saved;
    }).when(caldavSyncStorage).savePair(any());
    givenServerCalendars(collection("/dav/calendars/john/holidays/", "Holidays"));
    givenAgendaCreates("anchor-new");
    givenUserCalendars(calendarWithAnchor(99L, "anchor-new"));

    service.syncNow(USER, LOGIN);

    verify(caldavInboundService).importInto(eq(USER), any(), any(), any(), any());
  }

  @Test
  public void aFreshlyMaterialisedBindingClaimsNothingItHasNotRead() throws Exception {
    // The invariant behind the test above, asserted directly so a future
    // change that re-stamps any of the three is caught at the point it is
    // made rather than through its downstream effect.
    givenServerCalendars(collection("/dav/calendars/john/holidays/", "Holidays"));
    givenAgendaCreates("anchor-new");
    when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of());

    service.syncNow(USER, LOGIN);

    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    assertNull(saved.getValue().getCtag(), "a binding that has read nothing must not carry a ctag");
    assertNull(saved.getValue().getSyncToken(), "a binding that has read nothing must not carry a sync token");
    assertNull(saved.getValue().getLastSyncEnd(), "a binding that has read nothing must not claim a sync time");
  }

  private CaldavUserSetting settings() {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername(LOGIN);
    setting.setPassword("secret");
    setting.setServerId(SERVER);
    return setting;
  }
}
