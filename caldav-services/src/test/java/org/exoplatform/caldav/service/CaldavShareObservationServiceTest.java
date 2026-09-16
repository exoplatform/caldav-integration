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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavShareObservationStorage;

/**
 * The owner's side of the share mark (EXO-90331): turning sightings keyed by a
 * calendar's anchor into counts keyed by the agenda calendar id a row in the
 * left panel knows itself by, and never failing while doing it.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavShareObservationServiceTest {

  /** Eric, the owner asking about his own calendars. */
  private static final long             ERIC   = 41L;

  /** Eric's login. */
  private static final String           LOGIN  = "eric";

  /** The declared server registration his account names. */
  private static final long             SERVER = 5L;

  /** The anchor of his CAL2, as the collection's slug carries it. */
  private static final String           CAL2   = "959b5529-ea4c-4ae4-a793-a2c201c3af9f";

  /** The anchor of another calendar of his, seen by nobody. */
  private static final String           CAL3   = "c434ba2a-3f58-4d9c-9a0a-2b2f8e1f7a10";

  @Mock
  private CaldavShareObservationStorage caldavShareObservationStorage;

  @Mock
  private CaldavConnectorStorage        caldavConnectorStorage;

  @Mock
  private AgendaCalendarService         agendaCalendarService;

  @InjectMocks
  private CaldavShareObservationService service;

  /**
   * The anchors the table counts become the calendar ids the panel draws, and
   * a calendar nobody sees is absent rather than present with a zero.
   *
   * @throws Exception never, everything is mocked
   */
  @Test
  public void theCountsAreTranslatedFromAnchorsToAgendaCalendarIds() throws Exception {
    givenConnected();
    when(caldavShareObservationStorage.countShareesByAnchor(ERIC, SERVER)).thenReturn(Map.of(CAL2, 3L));
    givenCalendars(calendar(12L, ERIC, CAL2), calendar(14L, ERIC, CAL3));

    assertEquals(Map.of(12L, 3L), service.shareeCountsByCalendar(ERIC, LOGIN));
  }

  /**
   * A count for an anchor the user no longer holds a calendar for — deleted
   * since, or restored away — names no row and is dropped rather than keyed by
   * the anchor, which agenda would not recognise.
   *
   * @throws Exception never, everything is mocked
   */
  @Test
  public void aCountForACalendarTheUserNoLongerHoldsNamesNoRow() throws Exception {
    givenConnected();
    when(caldavShareObservationStorage.countShareesByAnchor(ERIC, SERVER)).thenReturn(Map.of(CAL2, 3L));
    givenCalendars(calendar(14L, ERIC, CAL3));

    assertTrue(service.shareeCountsByCalendar(ERIC, LOGIN).isEmpty());
  }

  /**
   * A calendar of somebody else's, or one deleted, or one bound to nothing, is
   * not a row of this user's panel and is never counted for them.
   *
   * @throws Exception never, everything is mocked
   */
  @Test
  public void onlyTheUsersOwnLiveBoundCalendarsAreCounted() throws Exception {
    givenConnected();
    when(caldavShareObservationStorage.countShareesByAnchor(ERIC, SERVER)).thenReturn(Map.of(CAL2, 3L));
    Calendar deleted = calendar(12L, ERIC, CAL2);
    deleted.setDeleted(true);
    Calendar someoneElses = calendar(13L, 99L, CAL2);
    Calendar unbound = calendar(15L, ERIC, null);
    givenCalendars(deleted, someoneElses, unbound);

    assertTrue(service.shareeCountsByCalendar(ERIC, LOGIN).isEmpty());
  }

  /**
   * An account that is not connected asks the table nothing: there is no
   * server registration to scope the sightings by, and a mark drawn from
   * another account's server would name the wrong exposure.
   */
  @Test
  public void anAccountThatIsNotConnectedIsAskedNothing() {
    when(caldavConnectorStorage.getCaldavSetting(ERIC)).thenReturn(null);

    assertTrue(service.shareeCountsByCalendar(ERIC, LOGIN).isEmpty());
    verify(caldavShareObservationStorage, never()).countShareesByAnchor(anyLong(), anyLong());
  }

  /**
   * Nothing observed means agenda is not asked for a calendar listing at all —
   * the cheap path on a panel refresh where no calendar of the user's is
   * shared, which is most of them.
   *
   * @throws Exception never, everything is mocked
   */
  @Test
  public void nothingObservedCostsNoCalendarListing() throws Exception {
    givenConnected();
    when(caldavShareObservationStorage.countShareesByAnchor(ERIC, SERVER)).thenReturn(Map.of());

    assertTrue(service.shareeCountsByCalendar(ERIC, LOGIN).isEmpty());
    verify(agendaCalendarService, never()).getCalendarsByOwnerIds(any(), any());
  }

  /**
   * <b>Never fails.</b> A state indicator that cannot be computed is one that
   * is not drawn — it does not turn a panel refresh into an error.
   */
  @Test
  public void anythingThatGoesWrongDrawsNoMarkRatherThanFailing() {
    givenConnected();
    when(caldavShareObservationStorage.countShareesByAnchor(ERIC, SERVER)).thenThrow(new IllegalStateException("down"));

    assertTrue(assertDoesNotThrow(() -> service.shareeCountsByCalendar(ERIC, LOGIN)).isEmpty());
  }

  /**
   * The write path hands the whole listing on, and absorbs its own failure: a
   * synchronisation must not fail because a state indicator could not be
   * updated.
   */
  @Test
  public void recordingAListingAbsorbsItsOwnFailure() {
    when(caldavShareObservationStorage.reconcile(anyLong(), anyLong(), any())).thenThrow(new IllegalStateException("down"));

    assertDoesNotThrow(() -> service.observed(42L, SERVER, Map.of(CAL2, ERIC)));
  }

  /**
   * And so does forgetting, for the same reason: connecting and disconnecting
   * must succeed whatever happens here.
   */
  @Test
  public void forgettingAbsorbsItsOwnFailure() {
    when(caldavShareObservationStorage.forgetSharee(42L)).thenThrow(new IllegalStateException("down"));

    assertDoesNotThrow(() -> service.forgetObservationsOf(42L));
  }

  /**
   * An anchor the column cannot hold faithfully is not recorded rather than cut
   * down into another calendar's — a truncated anchor could equal one.
   */
  @Test
  public void anAnchorTheColumnCannotHoldIsNotRecordable() {
    assertTrue(CaldavShareObservationService.isRecordableAnchor("959b5529-ea4c-4ae4-a793-a2c201c3af9f"));
    assertFalse(CaldavShareObservationService.isRecordableAnchor(null));
    assertFalse(CaldavShareObservationService.isRecordableAnchor("  "));
    assertFalse(CaldavShareObservationService.isRecordableAnchor("a".repeat(251)));
    // Outside the Basic Multilingual Plane, which the MySQL column refuses or,
    // out of strict mode, truncates.
    assertFalse(CaldavShareObservationService.isRecordableAnchor("cal-📅"));
  }

  /**
   * An account connected to the rig's server.
   */
  private void givenConnected() {
    CaldavUserSetting settings = new CaldavUserSetting();
    settings.setUsername(LOGIN);
    settings.setPassword("secret");
    settings.setServerId(SERVER);
    lenient().when(caldavConnectorStorage.getCaldavSetting(ERIC)).thenReturn(settings);
  }

  /**
   * The calendars agenda answers for the owner.
   *
   * @param calendars what it returns
   * @throws Exception never, everything is mocked
   */
  private void givenCalendars(Calendar... calendars) throws Exception {
    lenient().when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ERIC), LOGIN)).thenReturn(List.of(calendars));
  }

  /**
   * One agenda calendar.
   *
   * @param id its agenda id
   * @param ownerId whose it is
   * @param syncUid the anchor it is bound under, may be null
   * @return the calendar
   */
  private static Calendar calendar(long id, long ownerId, String syncUid) {
    Calendar calendar = new Calendar();
    calendar.setId(id);
    calendar.setOwnerId(ownerId);
    calendar.setSyncUid(syncUid);
    return calendar;
  }
}
