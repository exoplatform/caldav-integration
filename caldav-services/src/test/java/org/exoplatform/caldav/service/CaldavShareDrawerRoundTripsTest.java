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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.ChannelShares;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.AccessControlEntry;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CollectionAcl;
import org.exoplatform.caldav.client.DavOptions;
import org.exoplatform.caldav.client.bluemind.BlueMindAclClient;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.CalendarShares;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * What one opening of agenda's Share drawer costs the owner's calendar server
 * (EXO-90385) — the count, not the feeling.
 *
 * <h2>Why a count is the deliverable</h2>
 *
 * <p>
 * The drawer took three to six seconds to open on a calendar hosted on a
 * remote CalDAV server, and the cost was latency rather than volume: it did
 * not grow with the number of sharees, calendars or users. Measured on the
 * rig, one round trip to BlueMind costs 0.57 to 0.99 s and one to a local
 * Stalwart about 2 ms — so what the drawer costs is the <em>number</em> of
 * sequential asks, which is what this test pins.
 *
 * <h2>What is counted</h2>
 *
 * <p>
 * Every call this add-on makes that a server has to answer: the CalDAV client
 * (minus {@link CalDavClient#endpoint}, which mints an endpoint from the
 * registration without asking the calendar server), BlueMind's REST client, and the
 * push's {@code mirrorDestination}. That last one counts as <b>one ask and
 * three round trips</b>: it walks {@code discoverPrincipal}, the principal's
 * {@code calendar-home-set} and a listing of that home, and more when the
 * account's destination is its main calendar. It is a mock here, so the test
 * counts the ask; the report counts the walk.
 *
 * <h2>The numbers</h2>
 *
 * <p>
 * Before EXO-90385, on an RFC 3744 server, one {@code GET /calendars/{id}/shares}
 * cost <b>5 asks</b>: {@code capabilities}, {@code readAcl},
 * {@code discoverPrincipal}, and {@code mirrorDestination} <em>twice</em> —
 * once for the listing's own warning flag, once more because agenda asked
 * {@code holdsMeetingCopies} as a separate question. Plus one
 * {@code readDisplayName} per sharee who is no eXo user. EXO-90385 made it
 * <b>3</b>: the principal comes from eXo's record of the connection on the
 * read path, and the flag travels with the shares instead of being derived
 * again.
 *
 * <p>
 * It is <b>2</b> since EXO-90398 — {@code capabilities} and {@code readAcl} —
 * because the one remaining ask is answered from eXo's own record of where the
 * copies go. That ask was the expensive one: {@link #theMirrorWalkTheDrawerNoLongerMakes}
 * wires the real {@code CaldavPushService} and counts what it asked the client,
 * which on a server writing into the account's own default calendar is four
 * client calls and <b>eight PROPFINDs</b> ({@code HttpCalDavClient}:
 * {@code discoverCalendarHome} is two, {@code discoverDefaultCalendar} three,
 * and {@code discoverPrincipal} inside the tie-break one more, beside the home
 * listing). The record is read instead, and the same test pins that an account
 * with nothing recorded still walks it.
 *
 * <h2>Where this test stops</h2>
 *
 * <p>
 * The count here is taken at the channel plugin, not at the endpoint: it says
 * what one {@code listShares} costs the server. The claim that one
 * {@code GET /calendars/{id}/shares} costs no more than that rests on two
 * companion tests, in agenda — {@code AgendaCalendarShareRestTest}
 * {@code theListingCarriesExoAndExternalSharesApart}, which pins the endpoint
 * to a single {@code getChannelShares} and to neither of the two calls it
 * replaced, and {@code AgendaCalendarShareServiceTest}
 * {@code theDrawersAskIsOneCallPerChannel}, which pins that to one ask per
 * channel. Read as an end-to-end measurement on its own, this file would be
 * claiming more than it checks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class CaldavShareDrawerRoundTripsTest {

  private static final long    ALICE             = 5L;

  private static final long    BOB               = 9L;

  private static final long    STALWART          = 1L;

  private static final long    CALENDAR          = 12L;

  private static final String  ANCHOR            = "9f1c2d3e-4b5a-6c7d-8e9f-0a1b2c3d4e5f";

  private static final String  COLLECTION        = "/dav/cal/alice%40stalwart.local/exo-cal-" + ANCHOR + "/";

  private static final String  ALICE_PRINCIPAL   = "/dav/pal/alice@stalwart.local";

  private static final String  BOB_PRINCIPAL     = "/dav/pal/bob@stalwart.local";

  private static final String  STRANGER_PRINCIPAL = "/dav/pal/stranger@stalwart.local";

  private static final String  HOME              = "/dav/cal/alice%40stalwart.local";

  private static final String  STALWART_DAV      = "1, 2, 3, access-control, calendar-access, addressbook";

  /** What one opening of the drawer asks the server for, after EXO-90398. */
  private static final Map<String, Integer> ONE_OPENING = Map.of("capabilities", 1, "readAcl", 1);

  /** Where alice's meeting copies go, as eXo recorded it. */
  private static final String  MIRROR            = "/dav/cal/alice%40stalwart.local/exo-meetings/";

  @Mock
  private AgendaCalendarService             agendaCalendarService;

  @Mock
  private CaldavConnectorStorage            caldavConnectorStorage;

  @Mock
  private CaldavSyncStorage                 caldavSyncStorage;

  @Mock
  private CalDavClient                      calDavClient;

  @Mock
  private CaldavConnectionIdentityService   caldavConnectionIdentityService;

  @Mock
  private IdentityManager                   identityManager;

  @Mock
  private CalDavEndpoint                    endpoint;

  @Mock
  private BlueMindAclClient                 blueMindAclClient;

  @Mock
  private CaldavPushService                 caldavPushService;

  @Mock
  private CaldavShareSubscriptionService    caldavShareSubscriptionService;

  @Mock
  private CaldavServerOwnerService          caldavServerOwnerService;

  @Mock
  private CaldavServerService               caldavServerService;

  private CaldavCalendarShareService        service;

  private CaldavCalendarShareChannelPlugin  plugin;

  /**
   * Alice (identity 5) owns calendar 12, exported to Stalwart (server 1) as
   * {@code exo-cal-<anchor>} and shared read-only with bob, who is connected
   * to the same server. Her principal is recorded, as it is for any account
   * whose synchronisation has run once, and the copies of her eXo meetings go
   * elsewhere.
   *
   * @throws Exception when the plugin's collaborators cannot be set
   */
  @BeforeEach
  public void rig() throws Exception {
    // The add-on's single definition of "connected" lives in CaldavServerService
    // (EXO-90358); alice's account is a username and a password, the shape this
    // rig was written against
    lenient().when(caldavServerService.isConnected(org.mockito.ArgumentMatchers.any()))
             .thenAnswer(call -> call.getArgument(0) != null);
    service = new CaldavCalendarShareService(agendaCalendarService,
                                             caldavConnectorStorage,
                                             caldavSyncStorage,
                                             calDavClient,
                                             caldavConnectionIdentityService,
                                             identityManager,
                                             blueMindAclClient,
                                             caldavPushService,
                                             caldavShareSubscriptionService,
                                             caldavServerOwnerService,
                                             caldavServerService);
    plugin = new CaldavCalendarShareChannelPlugin();
    set(plugin, "caldavCalendarShareService", service);
    set(plugin, "agendaCalendarService", agendaCalendarService);
    set(plugin, "identityManager", identityManager);
    lenient().when(agendaCalendarService.getCalendarById(CALENDAR)).thenReturn(calendar());
    lenient().when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(connected());
    lenient().when(caldavSyncStorage.getPairByLocalCalendar(ALICE, STALWART, ANCHOR)).thenReturn(exoPair());
    lenient().when(calDavClient.endpoint(STALWART, "alice")).thenReturn(endpoint);
    lenient().when(calDavClient.capabilities(endpoint, COLLECTION))
             .thenReturn(DavOptions.of(List.of(STALWART_DAV), List.of("OPTIONS, PROPFIND, REPORT, ACL")));
    lenient().when(calDavClient.readAcl(endpoint, COLLECTION))
             .thenReturn(CollectionAcl.of(List.of(AccessControlEntry.readGrantTo(BOB_PRINCIPAL)), Set.of()));
    lenient().when(calDavClient.discoverPrincipal(endpoint)).thenReturn(ALICE_PRINCIPAL + "/");
    lenient().when(caldavConnectionIdentityService.principalOf(ALICE, STALWART)).thenReturn(ALICE_PRINCIPAL);
    lenient().when(caldavConnectionIdentityService.usersConnectedAs(STALWART, BOB_PRINCIPAL)).thenReturn(List.of(BOB));
    lenient().when(identityManager.getIdentity(BOB)).thenReturn(user(BOB, "bob", "Bob Test"));
    lenient().when(caldavPushService.mirrorDestination(ALICE, "alice"))
             .thenReturn(new MirrorTarget(MIRROR, false, "eXo meetings"));
    // What EXO-90398 reads instead: the destination recorded on the account and
    // the mirror pair, whose applied stamp is not behind its registration's
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.MIRROR)).thenReturn(List.of(mirrorPair(MIRROR)));
    lenient().when(caldavServerService.resolveServer(STALWART)).thenReturn(registration(null));
  }

  /**
   * Alice's mirror pair: the ledger of the collection her copies were written
   * into.
   *
   * @param href the collection the copies are in
   * @return the pair
   */
  private static CalendarSync mirrorPair(String href) {
    CalendarSync pair = new CalendarSync();
    pair.setId(7L);
    pair.setUserIdentityId(ALICE);
    pair.setServerId(STALWART);
    pair.setRemoteHref(href);
    pair.setOrigin(SyncOrigin.MIRROR);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  /**
   * Alice's server registration.
   *
   * @param copySettingsUpdated when an administrator last changed a setting
   *          governing the copies, or null when none ever was
   * @return the registration
   */
  private static CaldavServer registration(Date copySettingsUpdated) {
    CaldavServer server = new CaldavServer();
    server.setId(STALWART);
    server.setCopySettingsUpdated(copySettingsUpdated);
    return server;
  }

  /**
   * <b>The count.</b> One opening of the drawer — what agenda's
   * {@code GET /calendars/{id}/shares} asks this channel, through
   * {@code AgendaCalendarShareServiceImpl#getChannelShares} — asks the server
   * twice: what the collection advertises and its access list. Not three as it
   * did after EXO-90385, and not five as it did before: the caller's principal
   * is no longer discovered on this path, the meeting-copies flag is no longer
   * derived a second time, and since EXO-90398 it is answered from eXo's own
   * record rather than from a walk of the account.
   */
  @Test
  public void oneOpeningOfTheDrawerAsksTheServerTwice() {
    ChannelShares answer = plugin.listShares(CALENDAR, "alice", List.of());

    assertEquals(ONE_OPENING, asks(), "what one opening of the Share drawer costs the server");
    assertEquals(1, answer.shares().size(), "bob's grant, which agenda has no record of");
    assertEquals(BOB, answer.shares().get(0).getShareeIdentityId());
    assertFalse(answer.meetingCopies(), "the copies go to the mirror, not to this calendar");
  }

  /**
   * <b>The target is resolved once.</b> The calendar, the account and the pair
   * binding them are read once per opening, not three times as they were when
   * the channel asked {@code sharedCollectionOf}, {@code listShares} and
   * {@code holdsMeetingCopies} in turn. These are database reads rather than
   * remote ones, but they are the same resolution and the three-way split is
   * what made the third server conversation possible.
   */
  @Test
  public void theTargetIsResolvedOncePerOpening() {
    plugin.listShares(CALENDAR, "alice", List.of());

    assertEquals(1,
                 countOf(caldavSyncStorage, "getPairByLocalCalendar"),
                 "the pair binding the calendar to its collection, resolved once");
    assertEquals(1, countOf(caldavConnectorStorage, "getCaldavSetting"), "the owner's account, read once");
  }

  /**
   * The name of a sharee no eXo user is connected as is the one call that
   * grows with the list, and it is unchanged: one {@code readDisplayName} for
   * that row and none for bob, whom eXo can name itself. It is the only ask
   * left that grows with anything.
   */
  @Test
  public void onlyAShareeOutsideExoCostsARowOfItsOwn() {
    lenient().when(calDavClient.readAcl(endpoint, COLLECTION))
             .thenReturn(CollectionAcl.of(List.of(AccessControlEntry.readGrantTo(BOB_PRINCIPAL),
                                                  AccessControlEntry.readGrantTo(STRANGER_PRINCIPAL)),
                                          Set.of()));
    lenient().when(calDavClient.readDisplayName(endpoint, STRANGER_PRINCIPAL)).thenReturn("A Stranger");

    ChannelShares answer = plugin.listShares(CALENDAR, "alice", List.of());

    assertEquals(2, answer.shares().size());
    assertEquals(1, countOf(calDavClient, "readDisplayName"), "one name asked, for the sharee eXo cannot name");
    assertEquals(3, asks().values().stream().mapToInt(Integer::intValue).sum(), "the two asks plus that one name");
  }

  /**
   * A server whose access list cannot be read still owes the owner the
   * warning: the list is empty and the meeting-copies flag is answered on its
   * own, as it was before EXO-90385. A missed warning exposes the owner's
   * meetings; a false one costs a click.
   */
  @Test
  public void anUnreadableListStillAnswersTheMeetingCopiesWarning() {
    lenient().when(calDavClient.readAcl(endpoint, COLLECTION)).thenThrow(new CalDavException("down"));
    recordedDestinationIs(COLLECTION);

    ChannelShares answer = plugin.listShares(CALENDAR, "alice", List.of());

    assertTrue(answer.shares().isEmpty(), "nothing could be read, so nothing is listed");
    assertTrue(answer.meetingCopies(), "and the warning is still answered");
  }

  /**
   * <b>The warning still appears on a calendar that does hold the copies.</b>
   * Read from the record, with no ask of its own: the calendar being shared is
   * the one the account's record names.
   */
  @Test
  public void aCalendarThatHoldsTheCopiesIsStillWarnedAbout() {
    recordedDestinationIs(COLLECTION);

    ChannelShares answer = plugin.listShares(CALENDAR, "alice", List.of());

    assertTrue(answer.meetingCopies(), "this calendar is where the copies go");
    assertEquals(ONE_OPENING, asks(), "and the record answered it without an ask of its own");
  }

  /**
   * <b>An absent record asks the server.</b> Nothing recorded on the account
   * and no mirror pair is not "no copies here": the destination is resolved as
   * it was before EXO-90398, and the answer is the server's.
   */
  @Test
  public void nothingRecordedStillAsksTheServer() {
    CaldavUserSetting settings = connected();
    settings.setMirrorCalendarHref(null);
    lenient().when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(settings);
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.MIRROR)).thenReturn(List.of());
    lenient().when(caldavPushService.mirrorDestination(ALICE, "alice")).thenReturn(new MirrorTarget(COLLECTION, false, "Alice"));

    ChannelShares answer = plugin.listShares(CALENDAR, "alice", List.of());

    assertTrue(answer.meetingCopies(), "the server said so");
    assertEquals(Map.of("capabilities", 1, "readAcl", 1, "mirrorDestination", 1),
                 asks(),
                 "an account with nothing recorded is asked of the server, as before");
  }

  /**
   * <b>An administrator re-pointing the copies is not waited out.</b> The
   * registration carries a copy-settings stamp the account's mirror pair has
   * not applied, so the record may be about to move and the server is asked —
   * in the very request after the administrator's save, not after the next
   * sweep.
   */
  @Test
  public void aCopySettingChangeSendsTheDrawerBackToTheServer() {
    Date changed = new Date();
    lenient().when(caldavServerService.resolveServer(STALWART)).thenReturn(registration(changed));
    CalendarSync stale = mirrorPair(MIRROR);
    stale.setCopySettingsApplied(new Date(changed.getTime() - 60000L));
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.MIRROR)).thenReturn(List.of(stale));
    lenient().when(caldavPushService.mirrorDestination(ALICE, "alice")).thenReturn(new MirrorTarget(COLLECTION, false, "Alice"));

    ChannelShares answer = plugin.listShares(CALENDAR, "alice", List.of());

    assertTrue(answer.meetingCopies(), "the server, not the record, decided");
    assertEquals(1, countOf(caldavPushService, "mirrorDestination"), "the record was not believed");
  }

  /**
   * And the stamp stops being a reason once the pass has applied it: a pair
   * whose applied stamp matches the registration's is a pair whose destination
   * is settled, and the record answers again.
   */
  @Test
  public void anAppliedCopySettingLetsTheRecordAnswerAgain() {
    Date changed = new Date();
    lenient().when(caldavServerService.resolveServer(STALWART)).thenReturn(registration(changed));
    CalendarSync applied = mirrorPair(MIRROR);
    applied.setCopySettingsApplied(changed);
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.MIRROR)).thenReturn(List.of(applied));

    plugin.listShares(CALENDAR, "alice", List.of());

    assertEquals(ONE_OPENING, asks(), "nothing is owed, so nothing is asked");
  }

  /**
   * <b>The relocation is followed, not waited for.</b>
   * {@code CaldavMirrorRelocationService.repoint} moves the mirror pair onto
   * the new collection before a single copy is moved, and the account's own
   * record still names the old one. Both hold copies in that window, and both
   * are warned about.
   */
  @Test
  public void theCollectionTheCopiesAreBeingMovedIntoIsWarnedAbout() {
    Date changed = new Date();
    lenient().when(caldavServerService.resolveServer(STALWART)).thenReturn(registration(changed));
    CalendarSync repointed = mirrorPair(COLLECTION);
    repointed.setCopySettingsApplied(changed);
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.MIRROR)).thenReturn(List.of(repointed));

    ChannelShares answer = plugin.listShares(CALENDAR, "alice", List.of());

    assertTrue(answer.meetingCopies(), "the pair says the copies are on their way into this calendar");
    assertEquals(ONE_OPENING, asks(), "and no ask was needed to know it");
  }

  /**
   * <b>A path that writes an ACL never reads the record.</b> A grant has just
   * changed who may read the collection and reports what the server holds, so
   * where the copies go is asked of the server however fresh eXo's record is.
   *
   * @throws Exception when the grant is refused
   */
  @Test
  public void aGrantStillAsksTheServerWhereTheCopiesGo() throws Exception {
    lenient().when(caldavConnectionIdentityService.principalOf(BOB, STALWART)).thenReturn(BOB_PRINCIPAL);
    lenient().when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, "bob"))
             .thenReturn(user(BOB, "bob", "Bob Test"));

    service.grant(ALICE, "alice", CALENDAR, "bob", CalendarShares.ShareAccess.READ);

    assertEquals(1, countOf(caldavPushService, "mirrorDestination"), "a write path asks the server");
  }

  /**
   * Makes eXo's record say the copies go into the calendar being shared.
   *
   * @param href the collection the record is to name
   */
  private void recordedDestinationIs(String href) {
    CaldavUserSetting settings = connected();
    settings.setMirrorCalendarHref(href);
    lenient().when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(settings);
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.MIRROR)).thenReturn(List.of(mirrorPair(href)));
  }

  /**
   * The default of the SPI method — what a channel written before EXO-90385
   * gets — still makes the two calls it stands for, and answers the same
   * thing. What it costs the server is now the same as the override's, because
   * the second question it asks is the one EXO-90398 answers from eXo's own
   * record: it is the resolution of the calendar, not the server conversation,
   * that it duplicates.
   */
  @Test
  public void theSpiDefaultStillMakesTheTwoCallsItStandsFor() {
    CalendarShareChannelPluginDefault fallback = new CalendarShareChannelPluginDefault(plugin);

    ChannelShares answer = fallback.listShares(CALENDAR, "alice", List.of());

    assertEquals(1, answer.shares().size());
    assertFalse(answer.meetingCopies());
    assertEquals(ONE_OPENING, asks(), "the second question costs the server nothing now");
    assertEquals(2, countOf(caldavSyncStorage, "getPairByLocalCalendar"), "it still resolves the calendar twice");
  }

  /**
   * <b>The walk the drawer no longer makes, counted against the real thing.</b>
   * The push service is wired for real here — only the CalDAV client is a mock
   * — so what {@code mirrorDestination} costs is counted rather than asserted:
   * on an account whose registration writes into its own default calendar, four
   * client calls, which {@code HttpCalDavClient} turns into eight PROPFINDs
   * ({@code discoverCalendarHome} two, the home listing one,
   * {@code discoverDefaultCalendar} three, the tie-break's
   * {@code discoverPrincipal} one more, and the principal hop inside the home
   * discovery already counted).
   *
   * <p>
   * With a record, the drawer makes none of them. Without one, it makes all of
   * them — which is what stops an absent record being read as "no copies here".
   *
   * @throws Exception when the push service's collaborators cannot be set
   */
  @Test
  public void theMirrorWalkTheDrawerNoLongerMakes() throws Exception {
    CaldavPushService push = realPushService();
    service = shareServiceWith(push);
    set(plugin, "caldavCalendarShareService", service);

    plugin.listShares(CALENDAR, "alice", List.of());
    assertEquals(ONE_OPENING, asks(), "the record answers, and the account is not walked at all");

    CaldavUserSetting unrecorded = connected();
    unrecorded.setMirrorCalendarHref(null);
    lenient().when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(unrecorded);
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.MIRROR)).thenReturn(List.of());

    plugin.listShares(CALENDAR, "alice", List.of());
    assertEquals(Map.of("capabilities", 2,
                        "readAcl", 2,
                        "discoverCalendarHome", 1,
                        "listCalendars", 1,
                        "discoverDefaultCalendar", 1,
                        "discoverPrincipal", 1),
                 asks(),
                 "with nothing recorded, the account is walked exactly as it was before EXO-90398");
  }

  /**
   * The same walk on the commoner destination — a calendar of eXo's own — for
   * the record: three client calls, which {@code HttpCalDavClient} turns into
   * <b>four PROPFINDs</b> (the principal and the calendar home, then the home
   * listing). This is the floor of what the drawer used to pay for its
   * warning; the main-calendar account above is the ceiling.
   *
   * @throws Exception when the push service's collaborators cannot be set
   */
  @Test
  public void theDedicatedCalendarWalkIsThreeClientCalls() throws Exception {
    CaldavPushService push = realPushService();
    lenient().when(caldavServerService.resolveServer(STALWART)).thenReturn(registration(null));
    service = shareServiceWith(push);
    set(plugin, "caldavCalendarShareService", service);
    CaldavUserSetting unrecorded = connected();
    unrecorded.setMirrorCalendarHref(null);
    lenient().when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(unrecorded);
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.MIRROR)).thenReturn(List.of());

    plugin.listShares(CALENDAR, "alice", List.of());

    assertEquals(Map.of("capabilities", 1, "readAcl", 1, "discoverCalendarHome", 1, "listCalendars", 1),
                 asks(),
                 "the dedicated-calendar destination costs the home walk and the listing");
  }

  /**
   * The push service as the container builds it, with only the CalDAV client
   * and the two stores it needs for a mirror lookup.
   *
   * @return a real push service
   * @throws Exception when a field cannot be set
   */
  private CaldavPushService realPushService() throws Exception {
    CaldavPushService push = new CaldavPushService();
    set(push, "calDavClient", calDavClient);
    set(push, "caldavConnectorStorage", caldavConnectorStorage);
    set(push, "caldavServerService", caldavServerService);
    lenient().when(caldavServerService.resolveServer(STALWART))
             .thenReturn(mainCalendarRegistration());
    lenient().when(calDavClient.discoverCalendarHome(endpoint)).thenReturn(HOME);
    lenient().when(calDavClient.listCalendars(endpoint, HOME))
             .thenReturn(List.of(collection(HOME + "/calendar:Default:alice/", "Calendar"),
                                 collection(HOME + "/calendar:alice/", "Team room")));
    lenient().when(calDavClient.discoverDefaultCalendar(endpoint)).thenReturn(HOME + "/calendar");
    return push;
  }

  /**
   * The share service under test, wired with a given push service.
   *
   * @param push the push service to use
   * @return the service
   */
  private CaldavCalendarShareService shareServiceWith(CaldavPushService push) {
    return new CaldavCalendarShareService(agendaCalendarService,
                                          caldavConnectorStorage,
                                          caldavSyncStorage,
                                          calDavClient,
                                          caldavConnectionIdentityService,
                                          identityManager,
                                          blueMindAclClient,
                                          push,
                                          caldavShareSubscriptionService,
                                          caldavServerOwnerService,
                                          caldavServerService);
  }

  /**
   * A registration writing the copies into the account's own default calendar
   * — the destination whose resolution costs the most.
   *
   * @return the registration
   */
  private static CaldavServer mainCalendarRegistration() {
    CaldavServer server = registration(null);
    server.setMirrorTarget(org.exoplatform.caldav.model.MirrorTargetKind.MAIN_CALENDAR);
    return server;
  }

  /**
   * A collection as the server lists it.
   *
   * @param href its path
   * @param name its display name
   * @return the collection
   */
  private static org.exoplatform.caldav.client.CalendarCollection collection(String href, String name) {
    return new org.exoplatform.caldav.client.CalendarCollection(href, name, null, null, null, true, Set.of(), null, false);
  }

  /**
   * A channel that does not override {@code listShares}: the SPI's own
   * default, delegating to this add-on's two single-purpose methods.
   *
   * @param delegate the channel whose two methods the default calls
   */
  private record CalendarShareChannelPluginDefault(CaldavCalendarShareChannelPlugin delegate)
      implements org.exoplatform.agenda.plugin.CalendarShareChannelPlugin {

    /**
     * {@inheritDoc}
     */
    @Override
    public String id() {
      return delegate.id();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public org.exoplatform.agenda.model.ChannelDelivery deliver(org.exoplatform.agenda.model.CalendarShare share,
                                                                String ownerUsername) {
      return delegate.deliver(share, ownerUsername);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean withdraw(org.exoplatform.agenda.model.CalendarShare share, String ownerUsername) {
      return delegate.withdraw(share, ownerUsername);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<org.exoplatform.agenda.model.ExternalShare> listExternalShares(long calendarId,
                                                                               String ownerUsername,
                                                                               List<Long> recordedShareeIds) {
      return delegate.listExternalShares(calendarId, ownerUsername, recordedShareeIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeExternalShare(long calendarId, String externalId, String ownerUsername) {
      return delegate.removeExternalShare(calendarId, externalId, ownerUsername);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean holdsMeetingCopies(long calendarId, String ownerUsername) {
      return delegate.holdsMeetingCopies(calendarId, ownerUsername);
    }
  }

  /**
   * Every call made so far that a server has to answer, by name.
   *
   * @return the counts, in the order the calls were first made
   */
  private Map<String, Integer> asks() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Object mock : List.of(calDavClient, blueMindAclClient, caldavPushService)) {
      for (Invocation invocation : mockingDetails(mock).getInvocations()) {
        String name = invocation.getMethod().getName();
        // The endpoint is minted from the registration, locally: no server is
        // asked, and counting it would make a local resolution look remote
        if (!"endpoint".equals(name)) {
          counts.merge(name, 1, Integer::sum);
        }
      }
    }
    return counts;
  }

  /**
   * How many times one method of a mock was called.
   *
   * @param mock the mock
   * @param method the method's name
   * @return the number of calls
   */
  private static int countOf(Object mock, String method) {
    return (int) mockingDetails(mock).getInvocations()
                                     .stream()
                                     .filter(invocation -> invocation.getMethod().getName().equals(method))
                                     .count();
  }

  /**
   * Sets a field the container would inject.
   *
   * @param target the bean
   * @param name the field
   * @param value what to put in it
   * @throws Exception when there is no such field
   */
  private static void set(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true); // NOSONAR a test wiring what the container injects
    field.set(target, value);
  }

  /**
   * Alice's calendar.
   *
   * @return the calendar
   */
  private static Calendar calendar() {
    Calendar calendar = new Calendar();
    calendar.setId(CALENDAR);
    calendar.setOwnerId(ALICE);
    calendar.setSyncUid(ANCHOR);
    return calendar;
  }

  /**
   * Alice's connected account.
   *
   * @return the settings
   */
  private static CaldavUserSetting connected() {
    CaldavUserSetting settings = new CaldavUserSetting();
    settings.setUsername("alice@stalwart.local");
    settings.setPassword("secret");
    settings.setServerId(STALWART);
    settings.setMirrorCalendarHref(MIRROR);
    return settings;
  }

  /**
   * The pair binding alice's calendar to the collection eXo created for it.
   *
   * @return the pair
   */
  private static CalendarSync exoPair() {
    CalendarSync pair = new CalendarSync();
    pair.setId(3L);
    pair.setUserIdentityId(ALICE);
    pair.setServerId(STALWART);
    pair.setLocalCalendarSyncUid(ANCHOR);
    pair.setRemoteHref(COLLECTION);
    pair.setOrigin(SyncOrigin.EXO);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  /**
   * An enabled eXo user with a profile.
   *
   * @param id the identity id
   * @param login the login
   * @param fullName the full name
   * @return the identity
   */
  private static Identity user(long id, String login, String fullName) {
    Identity identity = new Identity(OrganizationIdentityProvider.NAME, login);
    identity.setId(String.valueOf(id));
    Profile profile = new Profile(identity);
    profile.setProperty(Profile.FULL_NAME, fullName);
    identity.setProfile(profile);
    return identity;
  }
}
