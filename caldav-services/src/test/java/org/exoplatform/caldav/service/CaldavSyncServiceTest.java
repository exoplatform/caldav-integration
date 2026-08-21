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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
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
  private AgendaCalendarService      agendaCalendarService;

  @Mock
  private CalDavEndpoint             endpoint;

  @InjectMocks
  private CaldavSyncService          service;

  @BeforeEach
  public void connectAnAccount() {
    ReflectionTestUtils.setField(service, "throttleMinutes", 15L);
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

    service.syncNow(USER, LOGIN);
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
  private CaldavUserSetting settings() {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername(LOGIN);
    setting.setPassword("secret");
    setting.setServerId(SERVER);
    return setting;
  }
}
