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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.service.CaldavShareObservationService.ImportedSighting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavShareObservationStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

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

  @Mock
  private CaldavSyncStorage             caldavSyncStorage;

  @Mock
  private CaldavConnectionIdentityService caldavConnectionIdentityService;

  @InjectMocks
  private CaldavShareObservationService service;

  // ---------------------------------------------------------------- an imported calendar's owner, one rule for both writers

  private static final long   ALICE              = 5L;

  private static final long   ALICE2             = 6L;

  private static final long   BOB                = 9L;

  private static final long   DAVE               = 11L;

  private static final long   STALWART           = 1L;

  private static final long   BLUEMIND           = 2L;

  private static final String ALICE_PRINCIPAL    = "/dav/pal/alice@stalwart.local";

  private static final String BOB_PRINCIPAL      = "/dav/pal/bob@stalwart.local";

  private static final String CAROL_PRINCIPAL    = "/dav/pal/carol@stalwart.local";

  /** Alice's default calendar, the pair's canonical path (rig pair 11). */
  private static final String ALICE_DEFAULT      = "/dav/cal/alice@stalwart.local/default";

  /** The same collection as bob's listing spells it. */
  private static final String ALICE_DEFAULT_LISTED = "/dav/cal/alice%40stalwart.local/default/";

  private static final String CAROL_DEFAULT      = "/dav/cal/carol@stalwart.local/default";

  /** The anchor agenda minted for alice's imported default calendar. */
  private static final String ALICE_DEFAULT_ANCHOR = "1e0ab9c9-49f6-4463-b7e6-d5405ca70679";

  private static final String ROOT_UID           = "751E6D1A-7FDB-49B2-B668-B569E9A5A42D";

  private static final String ERIC_UID           = "4C60FEDD-0562-4903-A524-E95E1CCBCDE0";

  private static final String ROOT_PRINCIPAL     = "/dav/principals/__uids__/" + ROOT_UID;

  private static final String ERIC_PRINCIPAL     = "/dav/principals/__uids__/" + ERIC_UID;

  /** Eric's default calendar under his own home, the pair's path (rig pair 4). */
  private static final String ERIC_DEFAULT       = "/dav/calendars/__uids__/" + ERIC_UID + "/calendar:Default:" + ERIC_UID;

  /** The same container as root's home lists it once eric shared it (rig log 09:50:02). */
  private static final String ERIC_DEFAULT_IN_ROOTS_HOME = "/dav/calendars/__uids__/" + ROOT_UID + "/calendar:Default:" + ERIC_UID + "/";

  private static final String ERIC_DEFAULT_ANCHOR = "4492edd2-0c02-419f-80cf-a0f78963f6a3";

  /**
   * <b>The agreement, on an RFC 3744 server.</b> Alice's default calendar is
   * a REMOTE pair — imported, not exported — and the grant used to record
   * nothing for it (observed on the rig on 2026-09-17: a share of calendar
   * 11 wrote no row, and no icon appeared). Bob's home lists the very path
   * alice's pair records, with alice's principal as owner; the grant knows
   * the path and alice's recorded principal. Both entrances must name the
   * same owner and the same anchor — the pair's, which is the calendar's
   * sync uid — or the row the grant writes is erased on bob's next pass.
   */
  @Test
  public void onAnRfcServerTheGrantAndTheSweepFileAnImportedDefaultCalendarUnderOneKey() {
    givenImportedPairsAt(STALWART, ALICE_DEFAULT, remotePair(ALICE, STALWART, ALICE_DEFAULT_ANCHOR, ALICE_DEFAULT, CalendarSyncStatus.ACTIVE));
    givenPrincipal(ALICE, STALWART, ALICE_PRINCIPAL);
    givenPrincipal(BOB, STALWART, BOB_PRINCIPAL);

    ImportedSighting sweep = service.importedSightingOf(STALWART, BOB, BOB_PRINCIPAL + "/",
                                                        listed(ALICE_DEFAULT_LISTED, "/dav/pal/alice%40stalwart.local/"));
    String grant = service.observableImportedAnchorOf(STALWART, ALICE, ALICE_DEFAULT_LISTED);

    assertNotNull(sweep, "bob's pass names alice as the owner of what his home lists at her path");
    assertEquals(ALICE, sweep.ownerIdentityId());
    assertEquals(ALICE_DEFAULT_ANCHOR, sweep.anchor(), "filed under the pair's anchor, the calendar's sync uid");
    assertEquals(sweep.anchor(), grant, "the grant files it under the same key, so the row survives bob's reconciliation");
  }

  /**
   * <b>The agreement, on BlueMind.</b> The server's owner is the subscriber,
   * so it says nothing; the container uid does. Root's home lists eric's
   * default calendar as {@code …/__uids__/<root>/calendar:Default:<eric>},
   * eric's own pair records it as {@code …/__uids__/<eric>/calendar:Default:<eric>}
   * (rig pairs 1 and 4, rig log 09:50:02), and the owner is the one user of
   * that path whose principal carries the container's uid. The
   * {@code UserCreated} shape carries the uid too and resolves the same way.
   */
  @Test
  public void onBlueMindTheGrantAndTheSweepFileAnImportedDefaultCalendarUnderOneKey() {
    givenImportedPairsAt(BLUEMIND, ERIC_DEFAULT, remotePair(8L, BLUEMIND, ERIC_DEFAULT_ANCHOR, ERIC_DEFAULT, CalendarSyncStatus.ACTIVE));
    givenPrincipal(8L, BLUEMIND, ERIC_PRINCIPAL);
    givenPrincipal(1L, BLUEMIND, ROOT_PRINCIPAL);

    ImportedSighting sweep = service.importedSightingOf(BLUEMIND, 1L, ROOT_PRINCIPAL + "/",
                                                        listed(ERIC_DEFAULT_IN_ROOTS_HOME, ROOT_PRINCIPAL + "/"));
    String grant = service.observableImportedAnchorOf(BLUEMIND, 8L, ERIC_DEFAULT + "/");

    assertNotNull(sweep, "root's pass names eric from the container's uid, not from the owner BlueMind states");
    assertEquals(8L, sweep.ownerIdentityId());
    assertEquals(ERIC_DEFAULT_ANCHOR, sweep.anchor());
    assertEquals(sweep.anchor(), grant);

    String created = "/dav/calendars/__uids__/" + ERIC_UID + "/calendar:UserCreated:" + ERIC_UID + ":6F1A2B3C-4D5E-4F60-8A7B-9C0D1E2F3A4B";
    givenImportedPairsAt(BLUEMIND, created, remotePair(8L, BLUEMIND, "created-anchor", created, CalendarSyncStatus.ACTIVE));
    ImportedSighting createdSweep = service.importedSightingOf(BLUEMIND, 1L, ROOT_PRINCIPAL + "/",
                                                               listed(created.replace(ERIC_UID + "/calendar", ROOT_UID + "/calendar") + "/",
                                                                      ROOT_PRINCIPAL + "/"));
    assertNotNull(createdSweep, "a calendar eric created carries his uid too");
    assertEquals("created-anchor", createdSweep.anchor());
    assertEquals("created-anchor", service.observableImportedAnchorOf(BLUEMIND, 8L, created + "/"));
  }

  /**
   * <b>The trap.</b> Alice and bob each imported carol's calendar before
   * shares were skipped (EXO-89530), so two REMOTE pairs sit at carol's path.
   * "Any pair at this path" would make each of them look like the owner —
   * the mistake EXO-90347 corrected for adoption. Carol is not an eXo user,
   * so nobody's recorded principal is the owner the server states, and
   * dave's pass files nothing. Carol <em>as</em> an eXo user, with her own
   * pair beside the two copies, is named — and only she.
   *
   * <p>
   * The grant's entrance is not driven here on purpose: it compares the
   * pairs against the <em>owner's own</em> principal, presuming what
   * {@code CaldavCalendarShareService#requireImportedOwned} has already had
   * the server confirm — that the sharer owns the collection there. Alice
   * sharing carol's calendar is refused by that check before the anchor is
   * ever asked ({@code NOT_OWNED_ON_SERVER}), which is where that half of
   * the trap is pinned.
   */
  @Test
  public void twoUsersWhoImportedTheSameThirdPartysCalendarAreNeitherOfThemItsOwner() {
    givenImportedPairsAt(STALWART, CAROL_DEFAULT,
                         remotePair(ALICE, STALWART, "alices-copy", CAROL_DEFAULT, CalendarSyncStatus.ACTIVE),
                         remotePair(BOB, STALWART, "bobs-copy", CAROL_DEFAULT, CalendarSyncStatus.ACTIVE));
    givenPrincipal(ALICE, STALWART, ALICE_PRINCIPAL);
    givenPrincipal(BOB, STALWART, BOB_PRINCIPAL);
    CalendarCollection carols = listed("/dav/cal/carol%40stalwart.local/default/", CAROL_PRINCIPAL + "/");

    assertNull(service.importedSightingOf(STALWART, DAVE, "/dav/pal/dave@stalwart.local", carols),
               "neither alice nor bob is the owner the server states");

    long carol = 10L;
    givenImportedPairsAt(STALWART, CAROL_DEFAULT,
                         remotePair(ALICE, STALWART, "alices-copy", CAROL_DEFAULT, CalendarSyncStatus.ACTIVE),
                         remotePair(carol, STALWART, "carols-anchor", CAROL_DEFAULT, CalendarSyncStatus.ACTIVE),
                         remotePair(BOB, STALWART, "bobs-copy", CAROL_DEFAULT, CalendarSyncStatus.ACTIVE));
    givenPrincipal(carol, STALWART, CAROL_PRINCIPAL);

    ImportedSighting sweep = service.importedSightingOf(STALWART, DAVE, "/dav/pal/dave@stalwart.local", carols);
    assertNotNull(sweep);
    assertEquals(carol, sweep.ownerIdentityId(), "the copies do not make their holders owners");
    assertEquals("carols-anchor", sweep.anchor());
    assertEquals("carols-anchor", service.observableImportedAnchorOf(STALWART, carol, CAROL_DEFAULT));
  }

  /**
   * A login two eXo users share (alice and alice2 on the rig) makes both the
   * stated owner of the same path, and then nobody is named: a mark on the
   * wrong row is worse than none, and the two entrances agree on that too.
   */
  @Test
  public void aLoginTwoUsersShareNamesNoOwner() {
    givenImportedPairsAt(STALWART, ALICE_DEFAULT,
                         remotePair(ALICE, STALWART, ALICE_DEFAULT_ANCHOR, ALICE_DEFAULT, CalendarSyncStatus.ACTIVE),
                         remotePair(ALICE2, STALWART, "alice2s-anchor", ALICE_DEFAULT, CalendarSyncStatus.PAUSED));
    givenPrincipal(ALICE, STALWART, ALICE_PRINCIPAL);
    givenPrincipal(ALICE2, STALWART, ALICE_PRINCIPAL);

    assertNull(service.importedSightingOf(STALWART, BOB, BOB_PRINCIPAL, listed(ALICE_DEFAULT_LISTED, ALICE_PRINCIPAL)));
    assertNull(service.observableImportedAnchorOf(STALWART, ALICE, ALICE_DEFAULT));
  }

  /**
   * A second user's pair at the same path who is <em>not</em> the stated
   * owner — alice2 with no recorded principal, as on the rig (pair 13) — does
   * not make alice ambiguous; nor does alice's own locally deleted pair
   * beside the active one (rig pairs 6 and 8 have that shape). The owner is
   * counted in users, not in rows, and the active pair is the one named.
   */
  @Test
  public void otherRowsAtThePathThatAreNotTheOwnersDoNotMakeTheOwnerAmbiguous() {
    givenImportedPairsAt(STALWART, ALICE_DEFAULT,
                         remotePair(ALICE, STALWART, ALICE_DEFAULT_ANCHOR, ALICE_DEFAULT, CalendarSyncStatus.ACTIVE),
                         remotePair(ALICE, STALWART, "the-anchor-before-she-deleted-it", ALICE_DEFAULT, CalendarSyncStatus.LOCALLY_DELETED),
                         remotePair(ALICE2, STALWART, "alice2s-anchor", ALICE_DEFAULT, CalendarSyncStatus.PAUSED),
                         remotePair(BOB, STALWART, CaldavDeletionService.hiddenShareAnchor(ALICE_DEFAULT), ALICE_DEFAULT,
                                    CalendarSyncStatus.HIDDEN_SHARE));
    givenPrincipal(ALICE, STALWART, ALICE_PRINCIPAL);
    givenPrincipal(BOB, STALWART, BOB_PRINCIPAL);
    when(caldavConnectionIdentityService.principalOf(ALICE2, STALWART)).thenReturn(null);

    ImportedSighting sweep = service.importedSightingOf(STALWART, BOB, BOB_PRINCIPAL, listed(ALICE_DEFAULT_LISTED, ALICE_PRINCIPAL));

    assertNotNull(sweep);
    assertEquals(ALICE, sweep.ownerIdentityId());
    assertEquals(ALICE_DEFAULT_ANCHOR, sweep.anchor(), "the active pair, not the deleted one");
    assertEquals(ALICE_DEFAULT_ANCHOR, service.observableImportedAnchorOf(STALWART, ALICE, ALICE_DEFAULT));
  }

  /**
   * <b>What stays excluded on BlueMind</b>: a container whose name carries no
   * uid — a bare uuid, an {@code exo-cal-*} another deployment minted. The
   * owner is not derivable locally (the server's own owner listing,
   * EXO-90347, is what would settle it), so the grant records nothing rather
   * than a row the sharee's pass would erase, and it asks the pairs nothing.
   * A BlueMind path is never read the RFC way either, whatever owner the
   * server states.
   */
  @Test
  public void aBlueMindContainerNamedByNoUidStaysExcludedOnBothEntrances() {
    givenPrincipal(8L, BLUEMIND, ERIC_PRINCIPAL);
    givenPrincipal(1L, BLUEMIND, ROOT_PRINCIPAL);
    for (String container : List.of("3B8E5C71-2A4D-4F6B-9C1E-7D5A3B2C1F09", "exo-cal-1f116bca-15f3-43ae-acc5-208cd3a8b9ef",
                                     "calendar:7E3AE6F3-98DF-43D9-B071-AAB477AC2CD8")) {
      String erics = "/dav/calendars/__uids__/" + ERIC_UID + "/" + container;
      givenImportedPairsAt(BLUEMIND, erics, remotePair(8L, BLUEMIND, "erics-anchor", erics, CalendarSyncStatus.ACTIVE));

      assertNull(service.observableImportedAnchorOf(BLUEMIND, 8L, erics + "/"), container);
      assertNull(service.importedSightingOf(BLUEMIND, 1L, ROOT_PRINCIPAL,
                                            listed("/dav/calendars/__uids__/" + ROOT_UID + "/" + container + "/", ERIC_PRINCIPAL + "/")),
                 container);
    }
    verify(caldavSyncStorage, never()).getImportedPairsOnServer(anyLong(), anyString());
  }

  /**
   * A collection an RFC server lists with no owner, or with the viewer as
   * owner, names nobody: there is no stated owner to compare a principal to.
   */
  @Test
  public void aCollectionListedWithNoOtherOwnerNamesNoOwner() {
    lenient().when(caldavSyncStorage.getImportedPairsOnServer(STALWART, ALICE_DEFAULT))
             .thenReturn(List.of(remotePair(ALICE, STALWART, ALICE_DEFAULT_ANCHOR, ALICE_DEFAULT, CalendarSyncStatus.ACTIVE)));
    givenPrincipal(ALICE, STALWART, ALICE_PRINCIPAL);

    assertNull(service.importedSightingOf(STALWART, BOB, BOB_PRINCIPAL, listed(ALICE_DEFAULT_LISTED, null)));
    assertNull(service.importedSightingOf(STALWART, BOB, BOB_PRINCIPAL, listed(ALICE_DEFAULT_LISTED, BOB_PRINCIPAL + "/")));
    assertNull(service.importedSightingOf(STALWART, ALICE, BOB_PRINCIPAL, listed(ALICE_DEFAULT_LISTED, ALICE_PRINCIPAL)),
               "the viewer is never the owner of what their own home lists as somebody else's");
  }

  /**
   * An owner with no recorded principal cannot be the one a pass names, so
   * the grant records nothing; and a lookup that throws names nobody rather
   * than failing the grant or the pass.
   */
  @Test
  public void anOwnerWithNoRecordedPrincipalAndAFailingLookupBothNameNobody() {
    when(caldavConnectionIdentityService.principalOf(ALICE, STALWART)).thenReturn(null);
    assertNull(service.observableImportedAnchorOf(STALWART, ALICE, ALICE_DEFAULT));

    when(caldavConnectionIdentityService.principalOf(ALICE, STALWART)).thenReturn(ALICE_PRINCIPAL);
    when(caldavSyncStorage.getImportedPairsOnServer(STALWART, ALICE_DEFAULT)).thenThrow(new IllegalStateException("down"));
    assertNull(service.observableImportedAnchorOf(STALWART, ALICE, ALICE_DEFAULT));
    assertNull(service.importedSightingOf(STALWART, BOB, BOB_PRINCIPAL, listed(ALICE_DEFAULT_LISTED, ALICE_PRINCIPAL)));
  }

  /**
   * The imported pairs the storage answers for one path on one server.
   *
   * @param serverId the server
   * @param href the canonical path
   * @param pairs the rows, in the storage's order
   */
  private void givenImportedPairsAt(long serverId, String href, CalendarSync... pairs) {
    lenient().when(caldavSyncStorage.getImportedPairsOnServer(serverId, href)).thenReturn(List.of(pairs));
  }

  /**
   * A user's recorded server identity.
   *
   * @param userIdentityId the user
   * @param serverId the server
   * @param principal the canonical principal recorded for them
   */
  private void givenPrincipal(long userIdentityId, long serverId, String principal) {
    lenient().when(caldavConnectionIdentityService.principalOf(userIdentityId, serverId)).thenReturn(principal);
  }

  /**
   * A REMOTE pair.
   *
   * @param userIdentityId whose
   * @param serverId on which server
   * @param anchor the local calendar's sync uid
   * @param href the collection path, canonical
   * @param status its state
   * @return the pair
   */
  private static CalendarSync remotePair(long userIdentityId, long serverId, String anchor, String href, CalendarSyncStatus status) {
    CalendarSync pair = new CalendarSync();
    pair.setUserIdentityId(userIdentityId);
    pair.setServerId(serverId);
    pair.setLocalCalendarSyncUid(anchor);
    pair.setRemoteHref(href);
    pair.setOrigin(SyncOrigin.REMOTE);
    pair.setStatus(status);
    return pair;
  }

  /**
   * A collection as a sharee's listing describes it: read-only, with the
   * owner the server states.
   *
   * @param href the path as listed
   * @param owner the {@code DAV:owner}, null when the server states none
   * @return the collection
   */
  private static CalendarCollection listed(String href, String owner) {
    return new CalendarCollection(href, "Default", null, null, null, false, Set.of("VEVENT"), owner, true);
  }

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
   * A grant records its one sighting, and only that one (EXO-90331).
   *
   * <p>
   * Through {@code record} and never through {@code reconcile}: the
   * reconciliation makes a home's whole stored set equal to what it is given,
   * so a grant handed on as a one-entry map would erase every other mark that
   * colleague's home feeds.
   */
  @Test
  public void aGrantRecordsOneSightingAndNeverReconciles() {
    service.granted(ERIC, 42L, SERVER, CAL2);

    verify(caldavShareObservationStorage).record(ERIC, 42L, SERVER, CAL2);
    verify(caldavShareObservationStorage, never()).reconcile(anyLong(), anyLong(), any());
  }

  /**
   * A revoke removes the one sighting, and never a whole home's worth
   * (EXO-90331).
   */
  @Test
  public void aRevokeForgetsOneSightingAndNeverAWholeHome() {
    service.revoked(42L, SERVER, CAL2);

    verify(caldavShareObservationStorage).forget(42L, SERVER, CAL2);
    verify(caldavShareObservationStorage, never()).forgetSharee(anyLong());
    verify(caldavShareObservationStorage, never()).reconcile(anyLong(), anyLong(), any());
  }

  /**
   * An anchor the column cannot hold faithfully reaches the storage from
   * neither act (EXO-90331) — the same rule the pass already applies, applied
   * before the write rather than after it.
   */
  @Test
  public void anUnrecordableAnchorIsWrittenByNeitherAct() {
    service.granted(ERIC, 42L, SERVER, "cal-📅");
    service.revoked(42L, SERVER, "  ");

    verify(caldavShareObservationStorage, never()).record(anyLong(), anyLong(), anyLong(), anyString());
    verify(caldavShareObservationStorage, never()).forget(anyLong(), anyLong(), anyString());
  }

  /**
   * Both acts absorb their own failure (EXO-90331).
   *
   * <p>
   * The server has already accepted the share by the time either runs, so
   * neither may undo a grant or a revoke because a state indicator could not
   * be written. The colleague's next pass reconciles what was missed.
   */
  @Test
  public void bothActsAbsorbTheirOwnFailure() {
    when(caldavShareObservationStorage.record(ERIC, 42L, SERVER, CAL2)).thenThrow(new IllegalStateException("down"));
    when(caldavShareObservationStorage.forget(42L, SERVER, CAL2)).thenThrow(new IllegalStateException("down"));

    assertDoesNotThrow(() -> service.granted(ERIC, 42L, SERVER, CAL2));
    assertDoesNotThrow(() -> service.revoked(42L, SERVER, CAL2));
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
