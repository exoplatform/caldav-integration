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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.AccessControlEntry;
import org.exoplatform.caldav.client.AccessControlEntry.AcePrincipal;
import org.exoplatform.caldav.client.AclWriteResult;
import org.exoplatform.caldav.client.bluemind.BlueMindAclClient;
import org.exoplatform.caldav.client.bluemind.BlueMindAclClient.BlueMindAce;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavForbiddenException;
import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.CalendarHome;
import org.exoplatform.caldav.client.CollectionAcl;
import org.exoplatform.caldav.client.DavOptions;
import org.apache.commons.lang3.StringUtils;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarShares;
import org.exoplatform.caldav.model.CalendarShares.CalendarSharee;
import org.exoplatform.caldav.model.CalendarShares.PublishedLinkMode;
import org.exoplatform.caldav.model.CalendarShares.ShareAccess;
import org.exoplatform.caldav.model.CalendarShares.ShareUser;
import org.exoplatform.caldav.model.CalendarShares.ShareeKind;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Every check between a browser's "share my calendar with bob" and the
 * {@code ACL} request that grants it (EXO-90253), on the rig's shape: alice
 * (identity 5) owns calendar 12, exported to Stalwart (server 1) as
 * {@code exo-cal-<anchor>}; bob (9) and carol (10) are connected to the same
 * server, alice2 (6) shares alice's login, dave (11) is connected to another
 * server.
 *
 * <p>
 * The pins that matter most: the collection is resolved from alice's own
 * pair and never from the request; nothing is granted on a server whose
 * changes eXo cannot confirm; an entry somebody else made survives a grant
 * and a revoke; and a grant the server does not hold on read-back is not
 * reported as done.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavCalendarShareServiceTest {

  private static final long   ALICE            = 5L;

  private static final long   ALICE2           = 6L;

  private static final long   BOB              = 9L;

  private static final long   CAROL            = 10L;

  private static final long   DAVE             = 11L;

  private static final long   STALWART         = 1L;

  private static final long   CALENDAR         = 12L;

  private static final String ANCHOR           = "9f1c2d3e-4b5a-6c7d-8e9f-0a1b2c3d4e5f";

  private static final String COLLECTION       = "/dav/cal/alice%40stalwart.local/exo-cal-" + ANCHOR + "/";

  private static final String ALICE_PRINCIPAL  = "/dav/pal/alice@stalwart.local";

  private static final String BOB_PRINCIPAL    = "/dav/pal/bob@stalwart.local";

  private static final String CAROL_PRINCIPAL  = "/dav/pal/carol@stalwart.local";

  private static final String STALWART_DAV     = "1, 2, 3, access-control, calendar-access, addressbook";

  /** Alice's own default calendar on Stalwart, imported into agenda (canonical, as a pair stores it). */
  private static final String STALWART_IMPORTED = "/dav/cal/alice@stalwart.local/default";

  /** Alice's calendar home on Stalwart. */
  private static final String ALICE_HOME       = "/dav/cal/alice%40stalwart.local/";

  /** A colleague's uid on BlueMind, whose default alice subscribed to. */
  private static final String CAMILLE_UID      = "2D4F6A80-1B3C-4E5D-8F70-9A1B2C3D4E5F";

  /** FRANCOIS — alice on the BlueMind rig — as a BlueMind directory entry. */
  private static final String FRANCOIS_UID     = "9F3C1A20-4D5E-4B7A-8C61-2E0D7A4B9C13";

  /** eric/MEYER — bob on the BlueMind rig. */
  private static final String ERIC_UID         = "6B2E4F10-8A3C-4D7E-9B51-0C2D4E6F8A17";

  /** A colleague given write access from BlueMind itself — carol on the rig. */
  private static final String WRITER_UID       = "D41A7C22-3E5B-4F60-8A19-2B7C9D0E1F35";

  /** Somebody nobody in eXo is connected as. */
  private static final String STRANGER_UID     = "0C5D7E91-2F4A-4B38-9D6E-7A1B3C5D7E9F";

  private static final String BM_COLLECTION    = "/dav/calendars/__uids__/" + FRANCOIS_UID + "/exo-cal-" + ANCHOR + "/";

  private static final String BM_CONTAINER     = "exo-cal-" + ANCHOR;

  private static final String FRANCOIS_PRINCIPAL = "/dav/principals/__uids__/" + FRANCOIS_UID;

  private static final String ERIC_PRINCIPAL   = "/dav/principals/__uids__/" + ERIC_UID;

  private static final String WRITER_PRINCIPAL = "/dav/principals/__uids__/" + WRITER_UID;

  /** eric's address in BlueMind — not his eXo profile e-mail. */
  private static final String ERIC_ADDRESS     = "eric.meyer@bm.example.com";

  /**
   * BlueMind's DAV classes as {@code capabilities} answers them: from the DAV
   * header of its PROPFIND answers (bluemind-principal.captured.xml:10,
   * abridged to its sharing tokens), its OPTIONS being a bare 204 with no
   * methods, as observed on the rig.
   */
  private static final String BLUEMIND_DAV     = "1, access-control, calendar-access, calendar-proxy, calendarserver-sharing, addressbook";

  @Mock
  private AgendaCalendarService           agendaCalendarService;

  @Mock
  private CaldavConnectorStorage          caldavConnectorStorage;

  @Mock
  private CaldavSyncStorage               caldavSyncStorage;

  @Mock
  private CalDavClient                    calDavClient;

  @Mock
  private CaldavConnectionIdentityService caldavConnectionIdentityService;

  @Mock
  private IdentityManager                 identityManager;

  @Mock
  private CalDavEndpoint                  endpoint;

  @Mock
  private BlueMindAclClient               blueMindAclClient;


  @Mock
  private CaldavPushService               caldavPushService;

  @Mock
  private CaldavShareSubscriptionService  caldavShareSubscriptionService;

  @Mock
  private CaldavServerOwnerService        caldavServerOwnerService;

  @Mock
  private CaldavServerService             caldavServerService;

  private CaldavCalendarShareService      service;

  /**
   * Alice connected to Stalwart, owning calendar 12 exported to her account,
   * with bob and carol known to eXo.
   */
  @BeforeEach
  public void rig() {
    // The addon's single definition of "connected" now lives in CaldavServerService.
    // Reproducing here the rule this class was written against keeps every assertion
    // measuring what it measured; the provider-backed shape has its own test below.
    org.mockito.Mockito.lenient()
                       .when(caldavServerService.isConnected(org.mockito.ArgumentMatchers.any()))
                       .thenAnswer(call -> {
                         CaldavUserSetting account = call.getArgument(0);
                         return account != null && StringUtils.isNotBlank(account.getUsername())
                             && StringUtils.isNotBlank(account.getPassword());
                       });
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
                                             caldavServerService,
                                             Duration.ZERO,
                                             () -> 0L);
    lenient().when(agendaCalendarService.getCalendarById(CALENDAR)).thenReturn(calendar(CALENDAR, ALICE, ANCHOR));
    lenient().when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(connectedTo(STALWART));
    lenient().when(caldavSyncStorage.getPairByLocalCalendar(ALICE, STALWART, ANCHOR)).thenReturn(exoPair());
    lenient().when(calDavClient.endpoint(STALWART, "alice")).thenReturn(endpoint);
    lenient().when(calDavClient.capabilities(endpoint, COLLECTION)).thenReturn(stalwartOptions());
    lenient().when(calDavClient.discoverPrincipal(endpoint)).thenReturn("/dav/pal/alice%40stalwart.local/");
    lenient().when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, "bob")).thenReturn(user(BOB, "bob", "Bob Test"));
    lenient().when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, "alice")).thenReturn(user(ALICE, "alice", "Alice Test"));
    lenient().when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, "alice2")).thenReturn(user(ALICE2, "alice2", "Alice Two"));
    lenient().when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, "dave")).thenReturn(user(DAVE, "dave", "Dave Test"));
    lenient().when(identityManager.getIdentity(BOB)).thenReturn(user(BOB, "bob", "Bob Test"));
    lenient().when(identityManager.getIdentity(CAROL)).thenReturn(user(CAROL, "carol", "Carol Test"));
    lenient().when(identityManager.getIdentity(ALICE2)).thenReturn(user(ALICE2, "alice2", "Alice Two"));
    lenient().when(caldavConnectionIdentityService.principalOf(BOB, STALWART)).thenReturn(BOB_PRINCIPAL);
    lenient().when(caldavConnectionIdentityService.principalOf(ALICE2, STALWART)).thenReturn(ALICE_PRINCIPAL);
    lenient().when(caldavConnectionIdentityService.principalOf(ALICE, STALWART)).thenReturn(ALICE_PRINCIPAL);
    lenient().when(caldavConnectionIdentityService.usersConnectedAs(STALWART, BOB_PRINCIPAL)).thenReturn(List.of(BOB));
    lenient().when(caldavConnectionIdentityService.usersConnectedAs(STALWART, CAROL_PRINCIPAL)).thenReturn(List.of(CAROL));
  }

  // ---------------------------------------------------------------- who may share what

  /**
   * A calendar that does not exist, or was deleted, is a 404 — before
   * anything else is asked.
   */
  @Test
  public void anUnknownOrDeletedCalendarIsNotFound() {
    Calendar deleted = calendar(13L, ALICE, "other");
    deleted.setDeleted(true);
    when(agendaCalendarService.getCalendarById(13L)).thenReturn(deleted);
    when(agendaCalendarService.getCalendarById(99L)).thenReturn(null);

    assertThrows(ObjectNotFoundException.class, () -> service.listShares(ALICE, "alice", 99L));
    assertThrows(ObjectNotFoundException.class, () -> service.grant(ALICE, "alice", 13L, "bob"));
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  /**
   * Only the owner shares: a colleague's calendar and a space calendar —
   * owned by the space's identity — are refused, whatever the caller can see
   * or edit in agenda, and the server is never asked.
   */
  @Test
  public void onlyTheOwnerMayShare() {
    when(agendaCalendarService.getCalendarById(20L)).thenReturn(calendar(20L, BOB, "bobs"));
    when(agendaCalendarService.getCalendarById(21L)).thenReturn(calendar(21L, 777L, "space"));

    IllegalAccessException colleagues = assertThrows(IllegalAccessException.class, () -> service.grant(ALICE, "alice", 20L, "bob"));
    assertThrows(IllegalAccessException.class, () -> service.listShares(ALICE, "alice", 21L));
    assertThrows(IllegalAccessException.class, () -> service.candidates(ALICE, "alice", 21L, null));

    assertEquals(CaldavCalendarShareService.NOT_OWNER, colleagues.getMessage());
    verify(calDavClient, never()).capabilities(any(), anyString());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  /**
   * Without a connected account there is nothing to share on.
   */
  @Test
  public void anAccountThatIsNotConnectedCannotShare() {
    when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(null);

    CaldavShareException refused = assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));

    assertEquals(CaldavCalendarShareService.NOT_CONNECTED, refused.getCode());
  }

  /**
   * Only a collection eXo created for this calendar, or an active imported one
   * that is not the meetings mirror, can be shared; everything else is refused
   * before any request: a hidden share, a paused import, the meetings mirror
   * bound as an import, a mirror pair, an unanchored import, a paused eXo
   * pair, an eXo pair whose collection is not the derived slug, and a calendar
   * never bound.
   */
  @Test
  public void onlyAnExportedOrActiveImportedCollectionIsShareable() {
    CalendarSync hidden = importedPair(STALWART_IMPORTED);
    hidden.setStatus(CalendarSyncStatus.HIDDEN_SHARE);
    CalendarSync pausedImport = importedPair(STALWART_IMPORTED);
    pausedImport.setStatus(CalendarSyncStatus.PAUSED);
    CalendarSync mirrorImport = importedPair("/dav/cal/alice@stalwart.local/exo-meetings");
    CalendarSync mirror = importedPair(STALWART_IMPORTED);
    mirror.setOrigin(SyncOrigin.MIRROR);
    CalendarSync unanchored = importedPair(STALWART_IMPORTED);
    unanchored.setLocalCalendarSyncUid(" ");
    CalendarSync paused = exoPair();
    paused.setStatus(CalendarSyncStatus.PAUSED);
    CalendarSync elsewhere = exoPair();
    elsewhere.setRemoteHref("/dav/cal/alice%40stalwart.local/default/");
    for (CalendarSync pair : java.util.Arrays.asList(hidden, pausedImport, mirrorImport, mirror, unanchored, paused, elsewhere, null)) {
      when(caldavSyncStorage.getPairByLocalCalendar(ALICE, STALWART, ANCHOR)).thenReturn(pair);

      IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                                                       () -> service.grant(ALICE, "alice", CALENDAR, "bob"),
                                                       String.valueOf(pair));

      assertEquals(CaldavCalendarShareService.CALENDAR_NOT_ON_SERVER, refused.getMessage());
    }
    verify(calDavClient, never()).capabilities(any(), anyString());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  /**
   * BlueMind's sharing token on a collection outside BlueMind's layout selects
   * Apple sharing, which is not offered: nothing is read, nothing is written,
   * and BlueMind's REST API is never called.
   */
  /**
   * A client answering no capabilities at all is refused as not supported, and
   * saying so never dereferences the missing answer (Sonar javabugs:S2259 on
   * the not-offered log line).
   */
  @Test
  public void aServerAnsweringNoCapabilitiesIsNotOfferedWithoutFailing() {
    when(calDavClient.capabilities(endpoint, COLLECTION)).thenReturn(null);

    CaldavShareException refused = assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));

    assertEquals(CaldavCalendarShareService.NOT_SUPPORTED, refused.getCode());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  @Test
  public void appleSharingOutsideBlueMindsLayoutIsNotOffered() {
    when(calDavClient.capabilities(endpoint, COLLECTION)).thenReturn(DavOptions.of(List.of("1, access-control, calendar-access, calendarserver-sharing"),
                                                                              List.of("PROPFIND, REPORT, ACL, POST")));

    CaldavShareException refused = assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));
    CaldavShareException listing = assertThrows(CaldavShareException.class, () -> service.listShares(ALICE, "alice", CALENDAR));

    assertEquals(CaldavCalendarShareService.NOT_SUPPORTED, refused.getCode());
    assertEquals(CaldavCalendarShareService.NOT_SUPPORTED, listing.getCode());
    verify(calDavClient, never()).readAcl(any(), anyString());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
    verify(blueMindAclClient, never()).readAcl(any(), anyString());
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean(), anyBoolean());
  }

  // ---------------------------------------------------------------- BlueMind

  /**
   * On BlueMind a grant is one {@code CS:share} naming eric by the mailto his
   * own principal publishes — third in the set, and never his eXo profile
   * e-mail — confirmed on the REST access list read back. The owner's own
   * rights are not a sharee, the answer says a colleague must subscribe, and
   * no RFC 3744 request is made.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindAGrantIsACsShareConfirmedOnTheRestAccessList() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(owner(), acl(owner(), expanded(ERIC_UID, "Read")));

    CalendarShares shares = service.grant(ALICE, "alice", CALENDAR, "bob");

    verify(calDavClient).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(false), anyBoolean());
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), eq("bob@exo.example.com"), anyBoolean(), anyBoolean());
    verify(calDavClient, never()).readAcl(any(), anyString());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
    assertTrue(shares.subscriptionRequired());
    assertEquals(1, shares.sharees().size());
    CalendarSharee eric = shares.sharees().get(0);
    assertEquals(ERIC_PRINCIPAL + "/", eric.principal());
    assertEquals(ShareeKind.EXO_USERS, eric.kind());
    assertEquals(ShareAccess.READ, eric.access());
    assertTrue(eric.removable());
    assertEquals("bob", eric.users().get(0).username());
  }

  /**
   * BlueMind carries an edit share as {@code CS:read-write} (EXO-90378), which
   * its {@code SharingProtocol} stores as the verb {@code Write}: the access
   * list read back says so, and the sharee is answered
   * {@link ShareAccess#WRITE} and stays removable.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindAnEditShareIsARequestForReadWrite() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(owner(), acl(owner(), expanded(ERIC_UID, "Write")));

    CalendarShares shares = service.grant(ALICE, "alice", CALENDAR, "bob", ShareAccess.WRITE);

    verify(calDavClient).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(false), eq(true));
    assertEquals(ShareAccess.WRITE, shares.sharees().get(0).access());
    assertTrue(shares.sharees().get(0).removable(), "a share eXo wrote is a share eXo takes back");
  }

  /**
   * A view share stays {@code CS:read} (EXO-90378): the boolean the client
   * takes has no third value, so no level can reach BlueMind's permissive
   * fallback, which grants <b>write</b> for an access element it does not
   * recognise.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindAViewShareIsARequestForReadAndNothingElse() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(owner(), acl(owner(), expanded(ERIC_UID, "Read")));

    CalendarShares shares = service.grant(ALICE, "alice", CALENDAR, "bob", ShareAccess.READ);

    verify(calDavClient).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(false), eq(false));
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean(), eq(true));
    assertEquals(ShareAccess.READ, shares.sharees().get(0).access());
  }

  /**
   * Levelling reconciles on BlueMind too (EXO-90378): a read grant is widened
   * to a write one and back, and granting the level already held sends
   * nothing.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindLevellingWidensAndNarrowsAndRepeatsNothing() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(acl(owner(), expanded(ERIC_UID, "Read")),
                                                                       acl(owner(), expanded(ERIC_UID, "Write")));
    service.grant(ALICE, "alice", CALENDAR, "bob", ShareAccess.WRITE);
    verify(calDavClient).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(false), eq(true));

    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(acl(owner(), expanded(ERIC_UID, "Write")),
                                                                       acl(owner(), expanded(ERIC_UID, "Read")));
    service.grant(ALICE, "alice", CALENDAR, "bob", ShareAccess.READ);
    verify(calDavClient).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(false), eq(false));

    org.mockito.Mockito.clearInvocations(calDavClient);
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(acl(owner(), expanded(ERIC_UID, "Write")));
    service.grant(ALICE, "alice", CALENDAR, "bob", ShareAccess.WRITE);
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean(), anyBoolean());
  }

  /**
   * BlueMind answers 200 even when the share did nothing — an unresolved
   * {@code CS:href} is logged and skipped, and its handler swallows every
   * failure — so a 200 is not proof of the level (EXO-90378). A write share
   * whose read-back says reading only is reported as <b>read</b>, not as a
   * success and not as a failure: the colleague reads the calendar there and
   * edits it in eXo, and agenda logs the difference.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindAWriteShareThatReadsBackAsReadingIsReportedAsReading() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(owner(), acl(owner(), expanded(ERIC_UID, "Read")));

    CalendarShares shares = service.grant(ALICE, "alice", CALENDAR, "bob", ShareAccess.WRITE);

    assertEquals(ShareAccess.READ,
                 shares.sharees().get(0).access(),
                 "the level the server holds, which the channel reports back to agenda");
  }

  /**
   * A write grant eXo wrote is eXo's to take back on BlueMind too, and a verb
   * eXo does not write — {@code Manage} — is still refused rather than
   * overwritten: a {@code CS:set} overwrites a subject's verb, so refusing is
   * what keeps eXo from taking away access the owner granted in BlueMind's own
   * interface.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindAWriteGrantIsRevocableAndAManagerIsNotRewritten() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(acl(owner(), expanded(ERIC_UID, "Write")),
                                                                       acl(owner()));

    service.revoke(ALICE, "alice", CALENDAR, "bob");
    verify(calDavClient).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(true), anyBoolean());

    org.mockito.Mockito.clearInvocations(calDavClient);
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(acl(owner(), expanded(ERIC_UID, "Manage")));
    assertEquals(CaldavCalendarShareService.NOT_READ_ONLY,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.grant(ALICE, "alice", CALENDAR, "bob", ShareAccess.WRITE)).getMessage());
    assertEquals(CaldavCalendarShareService.NOT_READ_ONLY,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.revoke(ALICE, "alice", CALENDAR, "bob")).getMessage());
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean(), anyBoolean());
  }

  /**
   * {@code All} expands to a set that <b>contains</b> {@code Write} — plus
   * {@code Manage} and {@code ReadExtended} — and is not a write share
   * (EXO-90378): recognising an eXo write grant by "holds Write" alone would
   * make eXo rewrite and revoke a colleague the owner made a manager of the
   * container in BlueMind's own interface. The whole set has to be inside the
   * write closure.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindASubjectHoldingAllIsNotAWriteShare() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(acl(owner(), expanded(ERIC_UID, "All")));

    assertEquals(CaldavCalendarShareService.NOT_READ_ONLY,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.grant(ALICE, "alice", CALENDAR, "bob", ShareAccess.WRITE)).getMessage());
    assertEquals(CaldavCalendarShareService.NOT_READ_ONLY,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.revoke(ALICE, "alice", CALENDAR, "bob")).getMessage());
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean(), anyBoolean());

    CalendarShares listed = service.listShares(ALICE, "alice", CALENDAR);
    CalendarSharee eric = listed.sharees().stream().filter(sharee -> sharee.principal().equals(ERIC_PRINCIPAL + "/")).findFirst().orElseThrow();
    assertEquals(ShareAccess.MORE, eric.access());
    assertFalse(eric.removable());
  }

  /**
   * BlueMind answers 200 to a share it did not apply — an address its
   * directory does not match, a failure it swallowed. A grant the access list
   * read back does not hold is not applied.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindAShareTheServerAnsweredButDoesNotHoldIsNotApplied() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(owner(), owner());
    when(calDavClient.postCalendarServerShare(any(), any(), anyString(), anyBoolean(), anyBoolean())).thenReturn(200);

    CaldavShareException refused = assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));

    assertEquals(CaldavCalendarShareService.NOT_APPLIED, refused.getCode());
    verify(calDavClient).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(false), anyBoolean());
  }

  /**
   * BlueMind's handler rewrites every entry of the sharee, so a colleague
   * already holding more than reading is refused before anything is sent —
   * for a grant and for a revoke — and so is a revoke of a colleague holding
   * only free/busy. Somebody else's write access is listed as more access given
   * outside eXo, not removable, and a grant to eric leaves it alone.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindAccessBeyondReadingIsNeverRewritten() throws Exception {
    onBlueMind();
    // Manage, not Write: since EXO-90378 a plain Write is a share eXo itself
    // writes, and what must never be rewritten is a verb it does not
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(acl(owner(), expanded(ERIC_UID, "Manage")),
                                                                        acl(owner(), expanded(ERIC_UID, "Manage")),
                                                                        acl(owner(), expanded(ERIC_UID, "Freebusy")),
                                                                        acl(owner(), expanded(WRITER_UID, "Manage")),
                                                                        acl(owner(), expanded(WRITER_UID, "Manage"), expanded(ERIC_UID, "Read")));

    IllegalArgumentException grant = assertThrows(IllegalArgumentException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));
    IllegalArgumentException revoke = assertThrows(IllegalArgumentException.class, () -> service.revoke(ALICE, "alice", CALENDAR, "bob"));
    IllegalArgumentException freeBusy = assertThrows(IllegalArgumentException.class,
                                                     () -> service.revoke(ALICE, "alice", CALENDAR, "bob"));
    assertEquals(CaldavCalendarShareService.NOT_READ_ONLY, grant.getMessage());
    assertEquals(CaldavCalendarShareService.NOT_READ_ONLY, revoke.getMessage());
    assertEquals(CaldavCalendarShareService.NOT_READ_ONLY, freeBusy.getMessage());
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean(), anyBoolean());

    CalendarShares shares = service.grant(ALICE, "alice", CALENDAR, "bob");

    verify(calDavClient).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(false), anyBoolean());
    CalendarSharee writer = shares.sharees().stream().filter(sharee -> sharee.principal().equals(WRITER_PRINCIPAL + "/")).findFirst().orElseThrow();
    assertEquals(ShareAccess.MORE, writer.access());
    assertFalse(writer.removable());
    assertEquals("carol", writer.users().get(0).username());
  }

  /**
   * A colleague given only access below viewing in BlueMind — free/busy, an
   * invitation right, visibility — is not shared with from eXo: the share
   * would turn it into a read entry BlueMind stores exactly like one eXo
   * made, and stopping the share would then erase access eXo never gave.
   * Nothing is sent.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindAccessBelowReadingGivenOutsideEXoIsNeverReplaced() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(acl(owner(), expanded(ERIC_UID, "Freebusy")),
                                                                        acl(owner(), expanded(ERIC_UID, "Visible")));

    for (int attempt = 0; attempt < 2; attempt++) {
      IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));
      assertEquals(CaldavCalendarShareService.SHAREE_HAS_OTHER_ACCESS, refused.getMessage());
    }
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean(), anyBoolean());
  }

  /**
   * A subject nobody in eXo is connected as is listed as someone outside eXo
   * by the name its principal gives, never removable; a subject holding only
   * free/busy cannot view the calendar and is not listed; and the owner's
   * rights are left out by the collection's own path even when the owner's
   * principal is not known.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindASubjectOutsideEXoIsListedButNotRemovable() throws Exception {
    onBlueMind();
    when(caldavConnectionIdentityService.principalOf(ALICE, STALWART)).thenReturn(null);
    lenient().when(calDavClient.discoverPrincipal(endpoint)).thenReturn(null);
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(acl(owner(),
                                                                           expanded(STRANGER_UID, "Read"),
                                                                           expanded("bm.example.com", "Freebusy")));
    when(calDavClient.readDisplayName(endpoint, "/dav/principals/__uids__/" + STRANGER_UID + "/")).thenReturn("Zoé Stranger");

    CalendarShares shares = service.listShares(ALICE, "alice", CALENDAR);

    assertTrue(shares.subscriptionRequired());
    assertEquals(1, shares.sharees().size(), String.valueOf(shares.sharees()));
    CalendarSharee stranger = shares.sharees().get(0);
    assertEquals(ShareeKind.OUTSIDE_EXO, stranger.kind());
    assertEquals("Zoé Stranger", stranger.displayName());
    assertEquals(ShareAccess.READ, stranger.access());
    assertFalse(stranger.removable());
  }

  /**
   * The access entries BlueMind's calendar publishing adds, one per link and
   * whose subject is the secret part of the link's URL, are listed as one row
   * per mode, private then public, next to the colleague the calendar is
   * shared with. They are never someone outside eXo and never removable, and
   * the secret is nowhere: not in the answer, not in its JSON, not in a
   * request eXo sends, not in what it logs.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindAPublishedLinkIsListedByItsModeAndNeverByItsSecret() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(derivedAcl(PUBLISHED_LINKS));
    Logger logger = (Logger) LoggerFactory.getLogger(CaldavCalendarShareService.class);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> logged = new ListAppender<>();
    logged.start();
    logger.addAppender(logged);
    logger.setLevel(Level.DEBUG);
    CalendarShares shares;
    try {
      shares = service.listShares(ALICE, "alice", CALENDAR);
    } finally {
      logger.detachAppender(logged);
      logger.setLevel(previousLevel);
    }

    assertEquals(List.of(ShareeKind.EXO_USERS, ShareeKind.PUBLISHED_LINK, ShareeKind.PUBLISHED_LINK),
                 shares.sharees().stream().map(CalendarSharee::kind).toList(),
                 "two private links make one row: " + shares.sharees());
    assertEquals("bob", shares.sharees().get(0).users().get(0).username());
    CalendarSharee privateLink = shares.sharees().get(1);
    CalendarSharee publicLink = shares.sharees().get(2);
    assertEquals(PublishedLinkMode.PRIVATE, privateLink.publishedLink());
    assertEquals(PublishedLinkMode.PUBLIC, publicLink.publishedLink());
    for (CalendarSharee link : List.of(privateLink, publicLink)) {
      assertEquals(ShareAccess.READ, link.access());
      assertFalse(link.removable(), "eXo never removes a published link");
      assertTrue(link.users().isEmpty());
      assertNull(link.displayName());
    }
    assertNotEquals(privateLink.principal(), publicLink.principal(), "the drawer keys its rows by principal");
    assertNull(shares.sharees().get(0).publishedLink());
    assertNoPublishedSecret(String.valueOf(shares));
    assertNoPublishedSecret(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(shares));
    logged.list.forEach(event -> assertNoPublishedSecret(event.getFormattedMessage()));
    verify(calDavClient, never()).readDisplayName(any(), org.mockito.ArgumentMatchers.contains("x-calendar"));
    verify(caldavConnectionIdentityService, never()).usersConnectedAs(anyLong(), org.mockito.ArgumentMatchers.contains("x-calendar"));
  }

  /**
   * A grant and a revoke on a calendar BlueMind published leave its links as
   * the access list read back holds them: eXo sends only a {@code CS:share}
   * naming the colleague, whose handler replaces that colleague's entries
   * alone ({@code SharingProtocol}, l.61-82), and never writes the list. The
   * links are nobody's other access and block nothing, and a list read back
   * that lost one is warned about without its secret.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindAGrantAndARevokeLeavePublishedLinksAsTheyWere() throws Exception {
    onBlueMind();
    List<BlueMindAce> shared = derivedAcl(PUBLISHED_LINKS);
    List<BlueMindAce> unshared = shared.stream().filter(ace -> !ace.subject().equals(ERIC_UID)).toList();
    List<BlueMindAce> sharedLosingThePublicLink = shared.stream().filter(ace -> !ace.subject().startsWith("x-calendar-public-")).toList();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(unshared, shared, shared, unshared, unshared, sharedLosingThePublicLink);
    Logger logger = (Logger) LoggerFactory.getLogger(CaldavCalendarShareService.class);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> logged = new ListAppender<>();
    logged.start();
    logger.addAppender(logged);
    logger.setLevel(Level.DEBUG);
    CalendarShares granted;
    CalendarShares revoked;
    List<ILoggingEvent> warnedBefore;
    try {
      granted = service.grant(ALICE, "alice", CALENDAR, "bob");
      revoked = service.revoke(ALICE, "alice", CALENDAR, "bob");
      warnedBefore = logged.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
      service.grant(ALICE, "alice", CALENDAR, "bob");
    } finally {
      logger.detachAppender(logged);
      logger.setLevel(previousLevel);
    }

    assertEquals(List.of(ShareeKind.EXO_USERS, ShareeKind.PUBLISHED_LINK, ShareeKind.PUBLISHED_LINK),
                 granted.sharees().stream().map(CalendarSharee::kind).toList());
    assertTrue(granted.sharees().get(0).removable());
    assertEquals(List.of(ShareeKind.PUBLISHED_LINK, ShareeKind.PUBLISHED_LINK), revoked.sharees().stream().map(CalendarSharee::kind).toList());
    verify(calDavClient, org.mockito.Mockito.times(2)).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(false), anyBoolean());
    verify(calDavClient).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(true), anyBoolean());
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), org.mockito.ArgumentMatchers.contains("x-calendar"), anyBoolean());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
    assertTrue(warnedBefore.isEmpty(), "the links read back as they were: " + warnedBefore);
    assertEquals(1, logged.list.stream().filter(event -> event.getLevel() == Level.WARN).count(), "a list that lost a link is warned about");
    logged.list.forEach(event -> assertNoPublishedSecret(event.getFormattedMessage()));
  }

  /**
   * The sharee's address is the mailto of their own principal's address set,
   * read through the owner's endpoint. A principal publishing none, or one that
   * cannot be read, is refused with its own code and nothing is sent — the
   * address is never taken from the eXo profile.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindAShareeWithoutAPublishedAddressIsRefused() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(owner());
    when(calDavClient.readCalendarUserAddresses(endpoint, ERIC_PRINCIPAL + "/")).thenReturn(List.of(ERIC_PRINCIPAL + "/", "urn:uuid:" + ERIC_UID),
                                                                                              List.of("mailto:"),
                                                                                              List.of("mailto:eric@bm.example.com</D:href>"))
                                                                                  .thenThrow(new CalDavException("404"));

    for (int attempt = 0; attempt < 4; attempt++) {
      CaldavShareException refused = assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));
      assertEquals(CaldavCalendarShareService.SHAREE_ADDRESS_UNKNOWN, refused.getCode());
    }
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean(), anyBoolean());
  }

  /**
   * A revoke is a {@code CS:share} remove, confirmed by the access list no
   * longer naming eric; still named is not applied; and nothing to revoke
   * sends nothing.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindARevokeIsARemoveConfirmedOnTheRestAccessList() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(acl(owner(), expanded(ERIC_UID, "Read")),
                                                                        owner(),
                                                                        acl(owner(), expanded(ERIC_UID, "Read")),
                                                                        acl(owner(), expanded(ERIC_UID, "Read")),
                                                                        owner());

    CalendarShares revoked = service.revoke(ALICE, "alice", CALENDAR, "bob");
    CaldavShareException notApplied = assertThrows(CaldavShareException.class, () -> service.revoke(ALICE, "alice", CALENDAR, "bob"));
    CalendarShares nothing = service.revoke(ALICE, "alice", CALENDAR, "bob");

    assertTrue(revoked.sharees().isEmpty());
    assertEquals(CaldavCalendarShareService.NOT_APPLIED, notApplied.getCode());
    assertTrue(nothing.sharees().isEmpty());
    verify(calDavClient, org.mockito.Mockito.times(2)).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(true), anyBoolean());
  }

  /**
   * EXO-90277: once the access list read back confirms eric reads, and
   * before the grant is audited, the colleague's own account is subscribed
   * to the calendar - named by alice's login, bob's identity and login,
   * eric's directory entry uid, the server and the container uid. A grant
   * the server did not apply, and a colleague already reading, subscribe
   * nobody: eXo takes the step only for a share it made.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindAGrantSubscribesTheColleagueAfterTheReadBackConfirmsIt() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(owner(), acl(owner(), expanded(ERIC_UID, "Read")));

    service.grant(ALICE, "alice", CALENDAR, "bob");

    ArgumentCaptor<CaldavShareSubscriptionService.ShareeSubscription> subscribed =
                                                                                 ArgumentCaptor.forClass(CaldavShareSubscriptionService.ShareeSubscription.class);
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(blueMindAclClient, calDavClient, caldavShareSubscriptionService);
    order.verify(calDavClient).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(false), anyBoolean());
    order.verify(blueMindAclClient).readAcl(endpoint, BM_CONTAINER);
    order.verify(caldavShareSubscriptionService).subscribeSharee(subscribed.capture());
    assertEquals(new CaldavShareSubscriptionService.ShareeSubscription("alice", BOB, "bob", ERIC_UID, STALWART, BM_CONTAINER),
                 subscribed.getValue());
    verify(caldavShareSubscriptionService, never()).unsubscribeSharee(any());

    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(owner(), owner());
    assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(acl(owner(), expanded(ERIC_UID, "Read")));
    service.grant(ALICE, "alice", CALENDAR, "bob");
    verify(caldavShareSubscriptionService, org.mockito.Mockito.times(1)).subscribeSharee(any());
    // What bob's mailbox sees changed by eXo's hand: his cached owner listing
    // is dropped with the one grant that was applied and followed — not on
    // the refused one, nor on the one that found him already reading
    // (EXO-90347).
    verify(caldavServerOwnerService, org.mockito.Mockito.times(1)).evict(BOB, STALWART);
  }

  /**
   * EXO-90277: a revoke the access list confirms unsubscribes the colleague;
   * one the server did not apply, and one with nothing to revoke, do not.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindARevokeUnsubscribesTheColleagueAfterTheReadBackConfirmsIt() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(acl(owner(), expanded(ERIC_UID, "Read")),
                                                                        owner(),
                                                                        acl(owner(), expanded(ERIC_UID, "Read")),
                                                                        acl(owner(), expanded(ERIC_UID, "Read")),
                                                                        owner());

    service.revoke(ALICE, "alice", CALENDAR, "bob");
    assertThrows(CaldavShareException.class, () -> service.revoke(ALICE, "alice", CALENDAR, "bob"));
    service.revoke(ALICE, "alice", CALENDAR, "bob");
    // The one revoke that was applied and followed drops bob's cached owner
    // listing too; the refused one, and the one with nothing left to revoke,
    // do not (EXO-90347).
    verify(caldavServerOwnerService, org.mockito.Mockito.times(1)).evict(BOB, STALWART);

    verify(caldavShareSubscriptionService, org.mockito.Mockito.times(1))
                                                                        .unsubscribeSharee(new CaldavShareSubscriptionService.ShareeSubscription("alice",
                                                                                                                                                 BOB,
                                                                                                                                                 "bob",
                                                                                                                                                 ERIC_UID,
                                                                                                                                                 STALWART,
                                                                                                                                                 BM_CONTAINER));
    verify(caldavShareSubscriptionService, never()).subscribeSharee(any());
  }

  /**
   * EXO-90277: the colleague's subscription never fails the owner's action.
   * The subscription service does not throw by contract; should it, the
   * grant still returns the access list as read back and is still audited,
   * and so is the revoke.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindTheGrantAndTheRevokeStandWhenTheSubscriptionSeamThrows() throws Exception {
    onBlueMind();
    // A CalDavAuthenticationException on purpose, and not any old failure:
    // this seam is lexically inside onServer, whose job is to turn exactly
    // that exception into a CaldavShareException(CREDENTIALS) and fail the
    // caller's action. The colleague's stale password is the likeliest thing
    // to come out of here, and routing it through onServer would fail the
    // OWNER's share over the colleague's credentials - the one thing the
    // brief says must never happen.
    doThrow(new CalDavAuthenticationException("the colleague's password is stale")).when(caldavShareSubscriptionService)
                                                                                   .subscribeSharee(any());
    doThrow(new IllegalStateException("subscription bean broke")).when(caldavShareSubscriptionService).unsubscribeSharee(any());
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(owner(),
                                                                        acl(owner(), expanded(ERIC_UID, "Read")),
                                                                        acl(owner(), expanded(ERIC_UID, "Read")),
                                                                        owner());
    Logger logger = (Logger) LoggerFactory.getLogger(CaldavCalendarShareService.class);
    Level previousLevel = logger.getLevel();
    logger.setLevel(Level.INFO);
    ListAppender<ILoggingEvent> logged = new ListAppender<>();
    logged.start();
    logger.addAppender(logged);
    try {
      CalendarShares granted = service.grant(ALICE, "alice", CALENDAR, "bob");
      CalendarShares revoked = service.revoke(ALICE, "alice", CALENDAR, "bob");

      assertEquals(1, granted.sharees().size());
      assertEquals(ShareAccess.READ, granted.sharees().get(0).access());
      assertTrue(revoked.sharees().isEmpty());
      List<String> lines = logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
      assertTrue(lines.stream().anyMatch(line -> line.startsWith("CalDAV share granted")), lines.toString());
      assertTrue(lines.stream().anyMatch(line -> line.startsWith("CalDAV share revoked")), lines.toString());
      assertEquals(2, logged.list.stream().filter(event -> event.getLevel() == Level.WARN).count(), "one WARN per seam failure");
    } finally {
      logger.detachAppender(logged);
      logger.setLevel(previousLevel);
    }
  }

  /**
   * Credentials BlueMind's REST API cannot take make sharing not offered; an
   * account the API refuses the list to cannot read it.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindAnAccessListThatCannotBeReadIsRefused() throws Exception {
    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenThrow(new UnsupportedOperationException("not a login"),
                                                                       new CalDavForbiddenException("403"));

    assertEquals(CaldavCalendarShareService.NOT_SUPPORTED,
                 assertThrows(CaldavShareException.class, () -> service.listShares(ALICE, "alice", CALENDAR)).getCode());
    assertEquals(CaldavCalendarShareService.ACL_UNREADABLE,
                 assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob")).getCode());
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean(), anyBoolean());
  }

  // ---------------------------------------------------------------- who can be a sharee

  /**
   * Each sharee that cannot be named on the server is refused with its own
   * code, before the server is written to: nobody named, an unknown login,
   * the owner herself, a colleague not connected to this server (dave, on
   * another one), and alice2 — on alice's own login.
   */
  @Test
  public void shareesTheServerCannotBeToldApartAreRefusedWithTheirCode() {
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, "ghost")).thenReturn(null);
    when(caldavConnectionIdentityService.principalOf(DAVE, STALWART)).thenReturn(null);
    lenient().when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(), Set.of()));

    assertEquals(CaldavCalendarShareService.SHAREE_REQUIRED, refusal(" "));
    assertEquals(CaldavCalendarShareService.SHAREE_UNKNOWN, refusal("ghost"));
    assertEquals(CaldavCalendarShareService.SHAREE_IS_OWNER, refusal("alice"));
    assertEquals(CaldavCalendarShareService.SHAREE_NOT_CONNECTED, refusal("dave"));
    assertEquals(CaldavCalendarShareService.SAME_PRINCIPAL, refusal("alice2"));
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  /**
   * A disabled or deleted eXo user is not a sharee.
   */
  @Test
  public void aDisabledUserIsUnknown() {
    Identity disabled = user(BOB, "bob", "Bob Test");
    disabled.setEnable(false);
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, "bob")).thenReturn(disabled);

    assertEquals(CaldavCalendarShareService.SHAREE_UNKNOWN, refusal("bob"));
  }

  /**
   * With no principal from the server and none recorded — just after the
   * account was connected again — alice2, on alice's own login, cannot be told
   * from a colleague: the grant and the candidates are refused rather than
   * checked against nothing, and nothing is written.
   */
  @Test
  public void anOwnerWhosePrincipalNobodyNamesIsRefusedRatherThanComparedWithNothing() throws Exception {
    when(calDavClient.discoverPrincipal(endpoint)).thenThrow(new org.exoplatform.caldav.client.CalDavException("no principal"));
    when(caldavConnectionIdentityService.principalOf(ALICE, STALWART)).thenReturn(null);

    CaldavShareException granted = assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "alice2"));
    CaldavShareException revoked = assertThrows(CaldavShareException.class, () -> service.revoke(ALICE, "alice", CALENDAR, "alice2"));
    CaldavShareException offered = assertThrows(CaldavShareException.class, () -> service.candidates(ALICE, "alice", CALENDAR, null));

    assertEquals(CaldavCalendarShareService.OWNER_UNKNOWN, granted.getCode());
    assertEquals(CaldavCalendarShareService.OWNER_UNKNOWN, revoked.getCode());
    assertEquals(CaldavCalendarShareService.OWNER_UNKNOWN, offered.getCode());
    verify(calDavClient, never()).readAcl(any(), anyString());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  // ------------------------------------------------- whose principal, and asked of whom (EXO-90385)

  /**
   * <b>The read path does not ask the server who the caller is.</b> Listing
   * the sharees uses the principal eXo recorded for alice's connection, which
   * is written by every pass of her account and removed the moment the account
   * is connected again or disconnected — so it names these credentials or
   * nothing. It costs one remote round trip per opening of the drawer, and
   * here it decides one thing only: that alice's own entry is not a sharee.
   */
  @Test
  public void listingTheShareesUsesTheRecordedPrincipalAndDoesNotAskTheServer() throws Exception {
    AccessControlEntry hers = AccessControlEntry.readGrantTo("/dav/pal/alice%40stalwart.local/");
    AccessControlEntry bobs = AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/");
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(hers, bobs), Set.of()));

    CalendarShares shares = service.listShares(ALICE, "alice", CALENDAR);

    assertEquals(1, shares.sharees().size(), "alice's own entry is not a sharee");
    assertEquals(ShareeKind.EXO_USERS, shares.sharees().get(0).kind());
    assertEquals(List.of("bob"), shares.sharees().get(0).users().stream().map(ShareUser::username).toList());
    verify(calDavClient, never()).discoverPrincipal(any());
  }

  /**
   * With nothing recorded — the account was just connected again, and no pass
   * has run since — the read path asks the server, exactly as it always did.
   * The fallback is the whole reason the reversal is safe: the record is
   * either right or absent.
   */
  @Test
  public void listingAsksTheServerWhenNoPrincipalIsRecorded() throws Exception {
    when(caldavConnectionIdentityService.principalOf(ALICE, STALWART)).thenReturn(null);
    AccessControlEntry hers = AccessControlEntry.readGrantTo("/dav/pal/alice%40stalwart.local/");
    AccessControlEntry bobs = AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/");
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(hers, bobs), Set.of()));

    CalendarShares shares = service.listShares(ALICE, "alice", CALENDAR);

    assertEquals(List.of("bob"), shares.sharees().get(0).users().stream().map(ShareUser::username).toList());
    verify(calDavClient, org.mockito.Mockito.times(1)).discoverPrincipal(endpoint);
  }

  /**
   * <b>A stale record cannot make a sharee look like the owner, nor the owner
   * look like a sharee.</b> A grant and a revoke ask the server who the caller
   * is and compare against that, whatever the record says — this is the ACL
   * comparison that tells a colleague on the caller's own login (EXO-90190)
   * from a colleague on another, and it is the one place a wrong principal
   * would grant or refuse the wrong thing.
   *
   * <p>
   * Here the record is bob's principal, which is what a record left behind by
   * a login alice no longer uses would look like at its worst. The server says
   * alice is alice: bob is granted, not refused as "sharing with yourself".
   * And alice2, who really is on alice's login, is still refused.
   */
  @Test
  public void aGrantAsksTheServerWhoTheCallerIsWhateverIsRecorded() throws Exception {
    // Never read, and that is the pin: the write path asks the server
    lenient().when(caldavConnectionIdentityService.principalOf(ALICE, STALWART)).thenReturn(BOB_PRINCIPAL);
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(), Set.of()),
                                                                CollectionAcl.of(List.of(AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/")),
                                                                                 Set.of()));
    when(calDavClient.writeAcl(eq(endpoint), any(), anyList())).thenReturn(new AclWriteResult(200, List.of(), List.of()));

    CalendarShares granted = service.grant(ALICE, "alice", CALENDAR, "bob");

    assertEquals(1, granted.sharees().size(), "bob is a sharee, not mistaken for alice herself");
    verify(calDavClient, org.mockito.Mockito.atLeastOnce()).discoverPrincipal(endpoint);
    assertEquals(CaldavCalendarShareService.SAME_PRINCIPAL,
                 assertThrows(IllegalArgumentException.class, () -> service.grant(ALICE, "alice", CALENDAR, "alice2")).getMessage(),
                 "alice2, who is on alice's own login as the server names it, is still refused");
  }

  /**
   * The revoke asks the server who the caller is too: the same comparison, on
   * the other half of the ACL-writing path.
   */
  @Test
  public void aRevokeAsksTheServerWhoTheCallerIsWhateverIsRecorded() throws Exception {
    // Never read, and that is the pin: the write path asks the server
    lenient().when(caldavConnectionIdentityService.principalOf(ALICE, STALWART)).thenReturn(BOB_PRINCIPAL);
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(), Set.of()));

    CalendarShares after = service.revoke(ALICE, "alice", CALENDAR, "bob");

    assertEquals(List.of(), after.sharees(), "bob held nothing; nothing is written");
    verify(calDavClient, org.mockito.Mockito.atLeastOnce()).discoverPrincipal(endpoint);
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  // ---------------------------------------------------------------- the grant

  /**
   * The grant keeps carol's entry — made outside eXo — and adds bob's, in
   * Stalwart's spelling, to the pair's own collection; protected and
   * inherited entries are not sent. The answer is the list as read back, with
   * both named as eXo users.
   */
  @Test
  public void aGrantKeepsEveryOtherEntryAndAddsOneReadGrant() throws Exception {
    AccessControlEntry carols = AccessControlEntry.readGrantTo("/dav/pal/carol%40stalwart.local/");
    AccessControlEntry protectedOwner = new AccessControlEntry(AcePrincipal.property("DAV:", "owner"), false, false,
                                                               Set.of("{DAV:}all"), true, null);
    AccessControlEntry bobs = AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/");
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(protectedOwner, carols), Set.of()),
                                                                CollectionAcl.of(List.of(protectedOwner, carols, bobs), Set.of()));
    ArgumentCaptor<CalendarSync> pair = ArgumentCaptor.forClass(CalendarSync.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AccessControlEntry>> written = ArgumentCaptor.forClass(List.class);
    when(calDavClient.writeAcl(eq(endpoint), pair.capture(), written.capture())).thenReturn(new AclWriteResult(200, List.of(), List.of()));

    CalendarShares shares = service.grant(ALICE, "alice", CALENDAR, "bob");

    assertEquals(List.of(carols, bobs), written.getValue(), "carol's entry kept as read, bob's added, the protected one not sent");
    assertEquals("/dav/pal/bob%40stalwart.local/", written.getValue().get(1).principal().href());
    assertEquals(COLLECTION, pair.getValue().getRemoteHref(), "the collection comes from alice's own pair");
    assertEquals(CALENDAR, shares.calendarId());
    assertEquals(List.of("carol", "bob"),
                 shares.sharees().stream().map(sharee -> sharee.users().get(0).username()).toList());
    assertTrue(shares.sharees().stream().allMatch(CalendarSharee::removable));
  }

  /**
   * A "can edit" share (EXO-90378) writes one entry granting {@code DAV:read}
   * and {@code DAV:write}, keeps everybody else's, and reads back as
   * {@link ShareAccess#WRITE} — removable, because eXo wrote it.
   *
   * @throws Exception never
   */
  @Test
  public void anEditGrantWritesReadAndWriteAndReadsBackAsWrite() throws Exception {
    AccessControlEntry carols = AccessControlEntry.readGrantTo("/dav/pal/carol%40stalwart.local/");
    AccessControlEntry bobsEdit = AccessControlEntry.editGrantTo("/dav/pal/bob%40stalwart.local/");
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(carols), Set.of()),
                                                                CollectionAcl.of(List.of(carols, bobsEdit), Set.of()));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AccessControlEntry>> written = ArgumentCaptor.forClass(List.class);
    when(calDavClient.writeAcl(eq(endpoint), any(), written.capture())).thenReturn(new AclWriteResult(200, List.of(), List.of()));

    CalendarShares shares = service.grant(ALICE, "alice", CALENDAR, "bob", ShareAccess.WRITE);

    assertEquals(List.of(carols, bobsEdit), written.getValue());
    assertEquals(Set.of("{DAV:}read", "{DAV:}write"),
                 written.getValue().get(1).privileges(),
                 "read is written beside write, which is what tells an eXo edit grant from a JMAP right");
    CalendarSharee bob = shares.sharees().stream().filter(sharee -> "bob".equals(sharee.users().get(0).username())).findFirst().orElseThrow();
    assertEquals(ShareAccess.WRITE, bob.access());
    assertTrue(bob.removable(), "a share eXo wrote is a share eXo takes back");
  }

  /**
   * The grant Stalwart really stores, read back the way Stalwart really
   * reports it (EXO-90378 — the defect the PO hit on the rig).
   *
   * <p>
   * eXo writes {@code {DAV:read, DAV:write}}. Stalwart v0.16 stores that as
   * its own {@code Read, ReadItems, Modify, Delete, AddItems, ModifyItems,
   * RemoveItems} and reports it back through {@code current_user_privilege_set}
   * as {@code DAV:read} + {@code DAV:read-current-user-privilege-set} (from
   * Read/ReadItems), {@code DAV:write} (from Delete/RemoveItems),
   * {@code DAV:write-properties} (from Modify) and {@code DAV:write-content}
   * (from ModifyItems) — {@code crates/dav/src/common/acl.rs},
   * stalwartlabs/stalwart v0.16.0.
   *
   * <p>
   * The grant had landed; eXo did not recognise its own entry coming back and
   * reported the share as not applied, leaving the record undelivered. The
   * read-back must accept the aggregate's members beside the aggregate.
   *
   * @throws Exception never
   */
  @Test
  public void anEditGrantIsRecognisedInStalwartsOwnReadBackShape() throws Exception {
    AccessControlEntry asStalwartReportsIt =
                                           new AccessControlEntry(AcePrincipal.href("/dav/pal/bob%40stalwart.local/"),
                                                                  false,
                                                                  false,
                                                                  Set.of("{DAV:}read",
                                                                         "{DAV:}read-current-user-privilege-set",
                                                                         "{DAV:}write",
                                                                         "{DAV:}write-properties",
                                                                         "{DAV:}write-content"),
                                                                  false,
                                                                  null);
    assertTrue(asStalwartReportsIt.grantsEditOnly(), "the entry eXo wrote, as Stalwart reports it");
    assertTrue(asStalwartReportsIt.grantsExoShape());
    assertFalse(asStalwartReportsIt.grantsReadOnly(), "and it is not a read share");

    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(), Set.of()),
                                                                CollectionAcl.of(List.of(asStalwartReportsIt), Set.of()));
    when(calDavClient.writeAcl(eq(endpoint), any(), anyList())).thenReturn(new AclWriteResult(200, List.of(), List.of()));

    CalendarShares shares = service.grant(ALICE, "alice", CALENDAR, "bob", ShareAccess.WRITE);

    CalendarSharee bob = shares.sharees().stream().filter(sharee -> !sharee.users().isEmpty()).findFirst().orElseThrow();
    assertEquals(ShareAccess.WRITE, bob.access(), "the level the server holds, not a refusal");
    assertTrue(bob.removable(), "and eXo may take back what it wrote");
  }

  /**
   * Accepting the aggregate's members widens nothing (EXO-90378): a bare
   * {@code DAV:write} with no read — a right given through Stalwart's JMAP —
   * is still not an eXo grant, a partial write is not the whole, and anything
   * outside {@code DAV:write} still stops the write.
   */
  @Test
  public void theMembersOfWriteDoNotWidenWhatAnExoGrantIs() {
    AcePrincipal who = AcePrincipal.href("/dav/pal/carol%40stalwart.local/");
    AccessControlEntry jmapRight = new AccessControlEntry(who, false, false, Set.of("{DAV:}write"), false, null);
    assertFalse(jmapRight.grantsEditOnly(), "a JMAP right reads back as a bare write and is still not ours");
    assertFalse(jmapRight.grantsExoShape());

    AccessControlEntry membersOnly = new AccessControlEntry(who, false, false,
                                                            Set.of("{DAV:}read", "{DAV:}write-content", "{DAV:}bind"),
                                                            false, null);
    assertFalse(membersOnly.grantsEditOnly(), "the members without the aggregate are not the whole of DAV:write");

    AccessControlEntry manages = new AccessControlEntry(who, false, false,
                                                        Set.of("{DAV:}read", "{DAV:}write", "{DAV:}write-acl"),
                                                        false, null);
    assertFalse(manages.grantsEditOnly(), "managing the access list is outside an edit share");
    assertFalse(manages.grantsExoShape());

    AccessControlEntry all = new AccessControlEntry(who, false, false, Set.of("{DAV:}all"), false, null);
    assertFalse(all.grantsExoShape());
  }

  /**
   * Levelling reconciles in both directions (EXO-90378): a read grant is
   * widened to an edit grant, and an edit grant is narrowed back to a read
   * grant — one entry each time, never two.
   *
   * @throws Exception never
   */
  @Test
  public void levellingWidensAReadGrantAndNarrowsAnEditGrant() throws Exception {
    AccessControlEntry bobsRead = AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/");
    AccessControlEntry bobsEdit = AccessControlEntry.editGrantTo("/dav/pal/bob%40stalwart.local/");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AccessControlEntry>> written = ArgumentCaptor.forClass(List.class);
    when(calDavClient.writeAcl(eq(endpoint), any(), written.capture())).thenReturn(new AclWriteResult(200, List.of(), List.of()));

    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(bobsRead), Set.of()),
                                                                CollectionAcl.of(List.of(bobsEdit), Set.of()));
    service.grant(ALICE, "alice", CALENDAR, "bob", ShareAccess.WRITE);
    assertEquals(List.of(bobsEdit), written.getValue(), "the read grant is replaced, not added to");

    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(bobsEdit), Set.of()),
                                                                CollectionAcl.of(List.of(bobsRead), Set.of()));
    service.grant(ALICE, "alice", CALENDAR, "bob", ShareAccess.READ);
    assertEquals(List.of(bobsRead), written.getValue(), "and a downgrade narrows it back");
  }

  /**
   * Granting the level a colleague already holds writes nothing, at either
   * level (EXO-90378).
   *
   * @throws Exception never
   */
  @Test
  public void grantingTheLevelAlreadyHeldWritesNothing() throws Exception {
    when(calDavClient.readAcl(endpoint, COLLECTION))
                                                    .thenReturn(CollectionAcl.of(List.of(AccessControlEntry.editGrantTo("/dav/pal/bob%40stalwart.local/")),
                                                                                 Set.of()));

    service.grant(ALICE, "alice", CALENDAR, "bob", ShareAccess.WRITE);

    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  /**
   * An edit grant eXo wrote for <b>another</b> colleague does not stop a later
   * grant or revoke (EXO-90378): it is a shape eXo writes, so carrying it back
   * returns that colleague exactly the rights the owner gave them. Without
   * this, the first edit share on a collection would freeze every later change
   * to its access list.
   *
   * @throws Exception never
   */
  @Test
  public void anotherPrincipalsEditGrantDoesNotStopTheWrite() throws Exception {
    AccessControlEntry carolsEdit = AccessControlEntry.editGrantTo("/dav/pal/carol%40stalwart.local/");
    AccessControlEntry bobs = AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/");
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(carolsEdit), Set.of()),
                                                                CollectionAcl.of(List.of(carolsEdit, bobs), Set.of()));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AccessControlEntry>> written = ArgumentCaptor.forClass(List.class);
    when(calDavClient.writeAcl(eq(endpoint), any(), written.capture())).thenReturn(new AclWriteResult(200, List.of(), List.of()));

    service.grant(ALICE, "alice", CALENDAR, "bob");

    assertEquals(List.of(carolsEdit, bobs), written.getValue(), "carol's edit grant is carried back unchanged");
  }

  /**
   * Carol was given more than read access outside eXo — what Stalwart shows as
   * {@code DAV:write} for a JMAP "may delete" grant, which written back becomes
   * full write. Sharing with bob, or stopping, must not rewrite her access: the
   * list is not written, whatever the change asked for.
   */
  @Test
  public void anotherPrincipalWithMoreThanReadStopsTheWrite() {
    AccessControlEntry bobs = AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/");
    AccessControlEntry carolWrites = new AccessControlEntry(AcePrincipal.href("/dav/pal/carol%40stalwart.local/"), false, false,
                                                            Set.of("{DAV:}write"), false, null);
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(carolWrites), Set.of()),
                                                                CollectionAcl.of(List.of(bobs, carolWrites), Set.of()));

    CaldavShareException granting = assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));
    CaldavShareException revoking = assertThrows(CaldavShareException.class, () -> service.revoke(ALICE, "alice", CALENDAR, "bob"));

    assertEquals(CaldavCalendarShareService.FOREIGN_ACCESS_NOT_PRESERVED, granting.getCode());
    assertEquals(CaldavCalendarShareService.FOREIGN_ACCESS_NOT_PRESERVED, revoking.getCode());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  /**
   * Bob already holds a right beyond reading that DAV shows as {@code write}
   * alone — a JMAP "may delete" grant on Stalwart. Sharing read-only with him
   * would write that entry back beside the read grant, and Stalwart would
   * store it as full write: the grant is refused and nothing is written.
   */
  @Test
  public void aGrantNeverWidensTheShareesOwnEntry() {
    AccessControlEntry bobMayDelete = new AccessControlEntry(AcePrincipal.href("/dav/pal/bob%40stalwart.local/"), false, false,
                                                             Set.of("{DAV:}write"), false, null);
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(bobMayDelete), Set.of()));

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));

    assertEquals(CaldavCalendarShareService.NOT_READ_ONLY, refused.getMessage());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  /**
   * Every other shape that could come back different stops the write too: an
   * access-control privilege, a deny, an inverted entry.
   */
  @Test
  public void anyOtherEntryBeyondAPlainReadGrantStopsTheWrite() {
    for (AccessControlEntry foreign : List.of(new AccessControlEntry(AcePrincipal.href("/dav/pal/carol/"), false, false,
                                                                     Set.of("{DAV:}read", "{DAV:}write-acl"), false, null),
                                              new AccessControlEntry(AcePrincipal.href("/dav/pal/carol/"), false, true,
                                                                     Set.of("{DAV:}read"), false, null),
                                              new AccessControlEntry(AcePrincipal.href("/dav/pal/bob%40stalwart.local/"), true, false,
                                                                     Set.of("{DAV:}read"), false, null))) {
      when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(foreign), Set.of()));

      assertEquals(CaldavCalendarShareService.FOREIGN_ACCESS_NOT_PRESERVED,
                   assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob")).getCode(),
                   String.valueOf(foreign));
    }
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  /**
   * A plain read-only grant to somebody else — read, or free-busy alone — is
   * written back as it was: those never widen.
   */
  @Test
  public void aPlainReadOnlyGrantToSomebodyElseIsWrittenBack() throws Exception {
    AccessControlEntry freeBusy = new AccessControlEntry(AcePrincipal.href("/dav/pal/carol%40stalwart.local/"), false, false,
                                                         Set.of("{urn:ietf:params:xml:ns:caldav}read-free-busy"), false, null);
    AccessControlEntry bobs = AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/");
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(freeBusy), Set.of()),
                                                                CollectionAcl.of(List.of(freeBusy, bobs), Set.of()));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AccessControlEntry>> written = ArgumentCaptor.forClass(List.class);
    when(calDavClient.writeAcl(eq(endpoint), any(), written.capture())).thenReturn(new AclWriteResult(200, List.of(), List.of()));

    service.grant(ALICE, "alice", CALENDAR, "bob");

    assertEquals(List.of(freeBusy, bobs), written.getValue());
  }

  /**
   * A colleague who can already read the calendar is left alone: nothing is
   * written.
   */
  @Test
  public void aGrantAlreadyInPlaceWritesNothing() throws Exception {
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(AccessControlEntry.readGrantTo("/dav/pal/bob@stalwart.local")),
                                                                                 Set.of()));

    CalendarShares shares = service.grant(ALICE, "alice", CALENDAR, "bob");

    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
    assertEquals(1, shares.sharees().size());
  }

  /**
   * A server answering 200 whose list, read again, does not hold the grant
   * has not granted anything — the BlueMind lesson, applied everywhere.
   */
  @Test
  public void aGrantNotVisibleOnReadBackIsNotApplied() {
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(), Set.of()));
    when(calDavClient.writeAcl(eq(endpoint), any(), anyList())).thenReturn(new AclWriteResult(200, List.of(), List.of()));

    CaldavShareException refused = assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));

    assertEquals(CaldavCalendarShareService.NOT_APPLIED, refused.getCode());
  }

  /**
   * A list that cannot be read back after the write confirms nothing either.
   */
  @Test
  public void aListUnreadableAfterTheWriteIsNotApplied() {
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(), Set.of()), CollectionAcl.unreadable(Set.of()));
    when(calDavClient.writeAcl(eq(endpoint), any(), anyList())).thenReturn(new AclWriteResult(200, List.of(), List.of()));

    assertEquals(CaldavCalendarShareService.NOT_APPLIED,
                 assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob")).getCode());
  }

  /**
   * The server's refusal is carried as it said it: the preconditions and the
   * missing privilege.
   */
  @Test
  public void aRefusalCarriesWhatTheServerSaid() {
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(), Set.of()));
    when(calDavClient.writeAcl(eq(endpoint), any(), anyList())).thenReturn(new AclWriteResult(403, List.of("need-privileges"), List.of("write-acl")));

    CaldavShareException refused = assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));

    assertEquals(CaldavCalendarShareService.SERVER_REFUSED, refused.getCode());
    assertEquals(List.of("need-privileges"), refused.getPreconditions());
    assertEquals(List.of("write-acl"), refused.getMissingPrivileges());
  }

  /**
   * A list that cannot be read, or holds an entry the client could not
   * parse, is never written: writing it back would delete what was not
   * understood.
   */
  @Test
  public void aListThatCannotBeWrittenBackFaithfullyIsNotWritten() {
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.unreadable(Set.of("{DAV:}read", "{DAV:}write")));
    CaldavShareException unreadable = assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));
    assertEquals(CaldavCalendarShareService.ACL_UNREADABLE, unreadable.getCode());
    assertEquals(List.of("read-acl"), unreadable.getMissingPrivileges());

    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.notUnderstood(Set.of(), "an entry outside RFC 3744 grammar"));
    CaldavShareException notUnderstood = assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));
    assertEquals(CaldavCalendarShareService.ACL_NOT_UNDERSTOOD, notUnderstood.getCode());

    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  /**
   * The protocol's failures become the contract's: credentials refused, and
   * a server not reached.
   */
  @Test
  public void protocolFailuresBecomeTheirCodes() {
    doThrow(new CalDavAuthenticationException("401")).when(calDavClient).capabilities(endpoint, COLLECTION);
    assertEquals(CaldavCalendarShareService.CREDENTIALS,
                 assertThrows(CaldavShareException.class, () -> service.listShares(ALICE, "alice", CALENDAR)).getCode());

    doThrow(new CalDavUnreachableException("down")).when(calDavClient).capabilities(endpoint, COLLECTION);
    assertEquals(CaldavCalendarShareService.SERVER_UNAVAILABLE,
                 assertThrows(CaldavShareException.class, () -> service.listShares(ALICE, "alice", CALENDAR)).getCode());
  }

  // ---------------------------------------------------------------- the revoke

  /**
   * A revoke removes bob's read grant and keeps carol's.
   */
  @Test
  public void aRevokeRemovesThatColleaguesGrantAndNothingElse() throws Exception {
    AccessControlEntry carols = AccessControlEntry.readGrantTo("/dav/pal/carol%40stalwart.local/");
    AccessControlEntry bobs = AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/");
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(bobs, carols), Set.of()),
                                                                CollectionAcl.of(List.of(carols), Set.of()));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AccessControlEntry>> written = ArgumentCaptor.forClass(List.class);
    when(calDavClient.writeAcl(eq(endpoint), any(), written.capture())).thenReturn(new AclWriteResult(200, List.of(), List.of()));

    CalendarShares shares = service.revoke(ALICE, "alice", CALENDAR, "bob");

    assertEquals(List.of(carols), written.getValue());
    assertEquals(List.of("carol"), shares.sharees().stream().map(sharee -> sharee.users().get(0).username()).toList());
  }

  /**
   * A grant of a shape eXo does not write — made outside eXo — is not eXo's
   * to take away: the revoke is refused and nothing is written. The two
   * shapes that matter are a bare {@code DAV:write}, which is how a right
   * given through Stalwart's JMAP reads back, and a privilege beyond writing
   * events.
   */
  @Test
  public void aRevokeOfAGrantExoDoesNotWriteIsRefused() {
    AccessControlEntry jmapRight = new AccessControlEntry(AcePrincipal.href("/dav/pal/bob%40stalwart.local/"), false, false,
                                                          Set.of("{DAV:}write"), false, null);
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(jmapRight), Set.of()));

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.revoke(ALICE, "alice", CALENDAR, "bob"));

    assertEquals(CaldavCalendarShareService.NOT_READ_ONLY, refused.getMessage());

    AccessControlEntry manages = new AccessControlEntry(AcePrincipal.href("/dav/pal/bob%40stalwart.local/"), false, false,
                                                        Set.of("{DAV:}read", "{DAV:}write", "{DAV:}write-acl"), false, null);
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(manages), Set.of()));

    assertEquals(CaldavCalendarShareService.NOT_READ_ONLY,
                 assertThrows(IllegalArgumentException.class, () -> service.revoke(ALICE, "alice", CALENDAR, "bob")).getMessage());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  /**
   * An edit grant eXo itself wrote (EXO-90378) <b>is</b> eXo's to take back:
   * revoking removes it as it removes a read grant, and leaves everybody
   * else's entries alone.
   *
   * @throws Exception never
   */
  @Test
  public void aRevokeRemovesAnEditGrantExoWrote() throws Exception {
    AccessControlEntry carols = AccessControlEntry.readGrantTo("/dav/pal/carol%40stalwart.local/");
    AccessControlEntry bobsEdit = AccessControlEntry.editGrantTo("/dav/pal/bob%40stalwart.local/");
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(bobsEdit, carols), Set.of()),
                                                                CollectionAcl.of(List.of(carols), Set.of()));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AccessControlEntry>> written = ArgumentCaptor.forClass(List.class);
    when(calDavClient.writeAcl(eq(endpoint), any(), written.capture())).thenReturn(new AclWriteResult(200, List.of(), List.of()));

    service.revoke(ALICE, "alice", CALENDAR, "bob");

    assertEquals(List.of(carols), written.getValue());
  }

  /**
   * Revoking a colleague who holds nothing changes nothing.
   */
  @Test
  public void aRevokeOfNobodyWritesNothing() throws Exception {
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(), Set.of()));

    CalendarShares shares = service.revoke(ALICE, "alice", CALENDAR, "bob");

    assertTrue(shares.sharees().isEmpty());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  /**
   * A revoked grant still listed after the write has not been revoked.
   */
  @Test
  public void aRevokeStillVisibleOnReadBackIsNotApplied() {
    AccessControlEntry bobs = AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/");
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(bobs), Set.of()));
    when(calDavClient.writeAcl(eq(endpoint), any(), anyList())).thenReturn(new AclWriteResult(200, List.of(), List.of()));

    assertEquals(CaldavCalendarShareService.NOT_APPLIED,
                 assertThrows(CaldavShareException.class, () -> service.revoke(ALICE, "alice", CALENDAR, "bob")).getCode());
  }

  // ---------------------------------------------------------------- the listing

  /**
   * The listing names every principal granted something, never hiding one:
   * bob as an eXo user, a principal no eXo user is connected as by what the
   * server calls it, everyone-grants as such, a grant of more than read as not
   * removable — and the owner's own principal and deny entries not at all.
   */
  @Test
  public void theListingNamesEveryGrantAndHidesNone() throws Exception {
    AccessControlEntry ownersOwn = AccessControlEntry.readGrantTo("/dav/pal/alice%40stalwart.local/");
    AccessControlEntry bobs = AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/");
    AccessControlEntry outsiders = AccessControlEntry.readGrantTo("/dav/pal/zoe%40partner.example/");
    AccessControlEntry everyone = new AccessControlEntry(AcePrincipal.of(AcePrincipal.Kind.AUTHENTICATED), false, false,
                                                         Set.of("{DAV:}read"), false, null);
    // A shape eXo does not write — managing the collection's own access —
    // which is what MORE means since EXO-90378; a plain {read, write} is an
    // edit share, and is answered WRITE by the test below
    AccessControlEntry carolWrites = new AccessControlEntry(AcePrincipal.href("/dav/pal/carol%40stalwart.local/"), false, false,
                                                            Set.of("{DAV:}read", "{DAV:}write", "{DAV:}write-acl"), false, null);
    AccessControlEntry denied = new AccessControlEntry(AcePrincipal.href("/dav/pal/mallory/"), false, true,
                                                       Set.of("{DAV:}read"), false, null);
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(ownersOwn, bobs, outsiders, everyone, carolWrites, denied),
                                                                                 Set.of()));
    when(caldavConnectionIdentityService.usersConnectedAs(STALWART, "/dav/pal/zoe@partner.example")).thenReturn(List.of());
    when(calDavClient.readDisplayName(endpoint, "/dav/pal/zoe%40partner.example/")).thenReturn("Zoé Partner");

    List<CalendarSharee> sharees = service.listShares(ALICE, "alice", CALENDAR).sharees();

    assertEquals(4, sharees.size());
    CalendarSharee bob = sharees.get(0);
    assertEquals(ShareeKind.EXO_USERS, bob.kind());
    assertEquals(new ShareUser(BOB, "bob", "Bob Test", "/avatar/bob"), bob.users().get(0));
    assertEquals(ShareAccess.READ, bob.access());
    assertTrue(bob.removable());
    CalendarSharee zoe = sharees.get(1);
    assertEquals(ShareeKind.OUTSIDE_EXO, zoe.kind());
    assertEquals("Zoé Partner", zoe.displayName());
    assertFalse(zoe.removable());
    CalendarSharee authenticated = sharees.get(2);
    assertEquals(ShareeKind.EVERYONE, authenticated.kind());
    assertEquals("{DAV:}authenticated", authenticated.principal());
    CalendarSharee carol = sharees.get(3);
    assertEquals(ShareAccess.MORE, carol.access());
    assertFalse(carol.removable());
  }

  /**
   * A principal that will not say its name is named by the decoded last
   * segment of its path.
   */
  @Test
  public void anUnnamedOutsiderIsNamedByItsPath() throws Exception {
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(AccessControlEntry.readGrantTo("/dav/pal/zoe%40partner.example/")),
                                                                                 Set.of()));
    when(caldavConnectionIdentityService.usersConnectedAs(STALWART, "/dav/pal/zoe@partner.example")).thenReturn(List.of());
    when(calDavClient.readDisplayName(any(), anyString())).thenThrow(new org.exoplatform.caldav.client.CalDavException("403"));

    CalendarSharee zoe = service.listShares(ALICE, "alice", CALENDAR).sharees().get(0);

    assertEquals("zoe@partner.example", zoe.displayName());
    assertNull(zoe.users().isEmpty() ? null : zoe.users());
  }

  /**
   * The shape Stalwart actually reads back for a read grant — {@code DAV:read}
   * with {@code read-current-user-privilege-set} ({@code acl.rs} 558-561), for
   * a grant eXo made and for one made elsewhere alike. Bob (eXo's) and carol
   * are listed as viewers eXo may remove, and sharing with a third colleague
   * writes both back beside the new grant instead of refusing: if that pair
   * stopped counting as read-only, every second share and every removal on
   * Stalwart would be refused.
   */
  @Test
  public void stalwartsReadGrantShapeIsReadOnlyForTheListingAndTheWriteBack() throws Exception {
    Set<String> stalwartRead = Set.of("{DAV:}read", "{DAV:}read-current-user-privilege-set");
    AccessControlEntry bobs = new AccessControlEntry(AcePrincipal.href("/dav/pal/bob%40stalwart.local/"), false, false, stalwartRead, false,
                                                     null);
    AccessControlEntry carols = new AccessControlEntry(AcePrincipal.href("/dav/pal/carol%40stalwart.local/"), false, false, stalwartRead,
                                                       false, null);
    AccessControlEntry daves = AccessControlEntry.readGrantTo("/dav/pal/dave%40stalwart.local/");
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, "dave")).thenReturn(user(DAVE, "dave", "Dave Test"));
    when(caldavConnectionIdentityService.principalOf(DAVE, STALWART)).thenReturn("/dav/pal/dave@stalwart.local");
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(bobs, carols), Set.of()),
                                                                CollectionAcl.of(List.of(bobs, carols), Set.of()),
                                                                CollectionAcl.of(List.of(bobs, carols, daves), Set.of()));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AccessControlEntry>> written = ArgumentCaptor.forClass(List.class);
    when(calDavClient.writeAcl(eq(endpoint), any(), written.capture())).thenReturn(new AclWriteResult(200, List.of(), List.of()));

    List<CalendarSharee> listed = service.listShares(ALICE, "alice", CALENDAR).sharees();
    service.grant(ALICE, "alice", CALENDAR, "dave");

    assertEquals(List.of(ShareAccess.READ, ShareAccess.READ), listed.stream().map(CalendarSharee::access).toList());
    assertTrue(listed.stream().allMatch(CalendarSharee::removable));
    assertEquals(List.of(bobs, carols, AccessControlEntry.readGrantTo("/dav/pal/dave%40stalwart.local/")), written.getValue());
  }

  /**
   * A server refusing, as a credential failure, to say what somebody else's
   * principal is called does not fail the listing — nor a change it already
   * applied: the name falls back to the principal's path.
   */
  @Test
  public void aPrincipalsNameRefusedAsCredentialsFallsBackToItsPath() throws Exception {
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(AccessControlEntry.readGrantTo("/dav/pal/zoe%40partner.example/")),
                                                                                 Set.of()));
    when(caldavConnectionIdentityService.usersConnectedAs(STALWART, "/dav/pal/zoe@partner.example")).thenReturn(List.of());
    when(calDavClient.readDisplayName(any(), anyString())).thenThrow(new CalDavAuthenticationException("403 on a read"));

    CalendarSharee zoe = service.listShares(ALICE, "alice", CALENDAR).sharees().get(0);

    assertEquals("zoe@partner.example", zoe.displayName());
  }

  /**
   * On a server where eXo offers no sharing, the colleagues connected to it are
   * not listed either — the same check the listing, the grant and the revoke
   * make.
   */
  @Test
  public void candidatesAreNotListedWhereSharingIsNotOffered() {
    when(calDavClient.capabilities(endpoint, COLLECTION)).thenReturn(DavOptions.of(List.of("1, access-control, calendarserver-sharing"),
                                                                              List.of("ACL")));

    CaldavShareException refused = assertThrows(CaldavShareException.class, () -> service.candidates(ALICE, "alice", CALENDAR, null));

    assertEquals(CaldavCalendarShareService.NOT_SUPPORTED, refused.getCode());
    verify(caldavConnectionIdentityService, never()).principalsOn(anyLong());
  }

  // ---------------------------------------------------------------- candidates

  /**
   * The candidates are the eXo users connected to the same server under
   * another login: alice herself and alice2 (her login) are left out, a user
   * the registry no longer knows too, and the query filters by login or
   * name, by full name order.
   */
  @Test
  public void candidatesAreColleaguesOnTheSameServerUnderAnotherLogin() throws Exception {
    Map<Long, String> connected = new LinkedHashMap<>();
    connected.put(ALICE, ALICE_PRINCIPAL);
    connected.put(ALICE2, ALICE_PRINCIPAL);
    connected.put(BOB, BOB_PRINCIPAL);
    connected.put(CAROL, CAROL_PRINCIPAL);
    connected.put(42L, "/dav/pal/gone@stalwart.local");
    when(caldavConnectionIdentityService.principalsOn(STALWART)).thenReturn(connected);
    when(identityManager.getIdentity(42L)).thenReturn(null);

    assertEquals(List.of("bob", "carol"), service.candidates(ALICE, "alice", CALENDAR, null).stream().map(ShareUser::username).toList());
    assertEquals(List.of("carol"), service.candidates(ALICE, "alice", CALENDAR, "CAR").stream().map(ShareUser::username).toList());
    assertEquals(List.of("bob"), service.candidates(ALICE, "alice", CALENDAR, "bob test").stream().map(ShareUser::username).toList());
    verify(calDavClient, never()).discoverPrincipal(any());
  }

  // ---------------------------------------------------------------- the menu entry

  /**
   * The calendars offered "Share" are the owned ones bound to a collection
   * eXo created, on a server whose mechanism is offered; anything failing
   * offers none.
   */
  @Test
  public void shareableCalendarsAreTheExportedOnesOnAnOfferedServer() throws Exception {
    CalendarSync unbound = exoPair();
    unbound.setLocalCalendarSyncUid("other");
    unbound.setRemoteHref("/dav/cal/alice%40stalwart.local/default/");
    when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.EXO)).thenReturn(List.of(exoPair(), unbound));
    when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice")).thenReturn(List.of(calendar(CALENDAR, ALICE, ANCHOR),
                                                                                                  calendar(14L, ALICE, "other"),
                                                                                                  calendar(21L, 777L, ANCHOR)));

    assertEquals(List.of(CALENDAR), service.shareableCalendarIds(ALICE, "alice"));
    // Asked by owner: the space-wide listing reads every space calendar through
    // an ACL check, on every refresh of the panel, for nothing kept.
    verify(agendaCalendarService, never()).getCalendars(anyInt(), anyInt(), anyString());

    when(calDavClient.capabilities(endpoint, COLLECTION)).thenReturn(DavOptions.of(List.of("1, access-control, calendarserver-sharing"),
                                                                              List.of("ACL")));
    assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"));

    doThrow(new CalDavUnreachableException("down")).when(calDavClient).capabilities(endpoint, COLLECTION);
    assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"));

    when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(null);
    assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"));
    verify(blueMindAclClient, never()).acceptsCredentials(any());
  }

  /**
   * On BlueMind the menu offers "Share" only when the owner's credentials are
   * a login and password BlueMind's REST API can take, so a registration whose
   * provider produces a token never shows an action every click of which
   * would answer "not supported". Deciding it calls no server.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindShareIsOfferedOnlyWithCredentialsTheRestApiTakes() throws Exception {
    onBlueMind();
    CalendarSync pair = exoPair();
    pair.setRemoteHref(BM_COLLECTION);
    when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.EXO)).thenReturn(List.of(pair));
    when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice")).thenReturn(List.of(calendar(CALENDAR, ALICE, ANCHOR)));
    when(blueMindAclClient.acceptsCredentials(endpoint)).thenReturn(true, false);

    assertEquals(List.of(CALENDAR), service.shareableCalendarIds(ALICE, "alice"));
    assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"));
    verify(blueMindAclClient, never()).readAcl(any(), anyString());
  }

  /**
   * An unknown failure — an NPE in the ownership code, say — still answers no
   * calendar, since the menu must never fail; but an empty answer hides Share
   * on every calendar, so it is reported at WARN once per server, not left to
   * DEBUG. An expected refusal (the server unreachable) stays at DEBUG.
   *
   * @throws Exception never
   */
  @Test
  public void anUnknownFailureOfTheShareableListIsReportedAtWarnOncePerServer() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(CaldavCalendarShareService.class);
    Level previous = logger.getLevel();
    ListAppender<ILoggingEvent> logged = new ListAppender<>();
    logged.start();
    logger.addAppender(logged);
    logger.setLevel(Level.DEBUG);
    try {
      when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.EXO)).thenReturn(List.of(exoPair()));
      // Expected first, on a server nothing has been reported for yet: DEBUG only.
      when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice")).thenReturn(List.of(calendar(CALENDAR, ALICE, ANCHOR)));
      doThrow(new CalDavUnreachableException("down")).when(calDavClient).capabilities(endpoint, COLLECTION);
      assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"));
      assertTrue(logged.list.stream().noneMatch(event -> event.getLevel() == Level.WARN), String.valueOf(logged.list));

      when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice")).thenThrow(new NullPointerException("owner"));
      assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"));
      assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"));

      List<ILoggingEvent> warnings = logged.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
      assertEquals(1, warnings.size(), String.valueOf(logged.list));
      assertTrue(warnings.get(0).getFormattedMessage().contains("calendar server " + STALWART), warnings.get(0).getFormattedMessage());
    } finally {
      logger.detachAppender(logged);
      logger.setLevel(previous);
    }
  }

  /**
   * A server whose collection advertises no sharing mechanism — a BlueMind
   * collection whose PROPFIND answer carries no {@code DAV} header either
   * (stripped by a proxy, say) — is reported at INFO the first
   * time this node meets it, with what it advertised, and at debug afterwards:
   * the refusal is no longer silent, and a panel refreshed every minute does
   * not flood the log.
   *
   * @throws Exception never
   */
  @Test
  public void aServerOfferingNoSharingIsReportedAtInfoOncePerServer() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(CaldavCalendarShareService.class);
    Level previous = logger.getLevel();
    ListAppender<ILoggingEvent> logged = new ListAppender<>();
    logged.start();
    logger.addAppender(logged);
    logger.setLevel(Level.DEBUG);
    try {
      when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.EXO)).thenReturn(List.of(exoPair()));
      when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice")).thenReturn(List.of(calendar(CALENDAR, ALICE, ANCHOR)));
      when(calDavClient.capabilities(endpoint, COLLECTION)).thenReturn(DavOptions.of(List.of(), List.of()));

      assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"));
      assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"));
      assertEquals(CaldavCalendarShareService.NOT_SUPPORTED,
                   assertThrows(CaldavShareException.class, () -> service.listShares(ALICE, "alice", CALENDAR)).getCode());

      List<ILoggingEvent> infos = logged.list.stream().filter(event -> event.getLevel() == Level.INFO).toList();
      assertEquals(1, infos.size(), String.valueOf(logged.list));
      assertTrue(infos.get(0).getFormattedMessage().contains("calendar server " + STALWART), infos.get(0).getFormattedMessage());
      assertTrue(infos.get(0).getFormattedMessage().contains("NONE"), infos.get(0).getFormattedMessage());
      assertEquals(2, logged.list.stream().filter(event -> event.getLevel() == Level.DEBUG
          && event.getFormattedMessage().contains("which eXo does not offer")).count());
    } finally {
      logger.detachAppender(logged);
      logger.setLevel(previous);
    }
  }

  // ---------------------------------------------------------------- one rule for both verbs

  /**
   * The guard grant applies — a colleague recorded under the owner's own
   * principal is refused, not compared — holds for revoke too: alice2, on
   * alice's login, cannot be "revoked" either, which would strip the owner's
   * own entries. Nothing is read from the server before the refusal, on
   * either mechanism.
   *
   * @throws Exception never
   */
  @Test
  public void revokeRefusesAColleagueOnTheOwnersOwnPrincipalAsGrantDoes() throws Exception {
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                                                    () -> service.revoke(ALICE, "alice", CALENDAR, "alice2"));
    assertEquals(CaldavCalendarShareService.SAME_PRINCIPAL, refused.getMessage());
    verify(calDavClient, never()).readAcl(any(), anyString());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());

    onBlueMind();
    when(caldavConnectionIdentityService.principalOf(ALICE2, STALWART)).thenReturn(FRANCOIS_PRINCIPAL);
    IllegalArgumentException refusedOnBlueMind = assertThrows(IllegalArgumentException.class,
                                                              () -> service.revoke(ALICE, "alice", CALENDAR, "alice2"));
    assertEquals(CaldavCalendarShareService.SAME_PRINCIPAL, refusedOnBlueMind.getMessage());
    verify(blueMindAclClient, never()).readAcl(any(), anyString());
  }


  /**
   * When BlueMind answers the share and the list read back does not hold the
   * grant, the entries of others that moved meanwhile are still reported: the
   * not-applied case is exactly where a third party's entries are most likely
   * to have changed, and a warning raised after the refusal would never be
   * logged.
   *
   * @throws Exception never
   */
  @Test
  public void onBlueMindEntriesOfOthersThatMovedAreReportedEvenWhenTheGrantIsNotApplied() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(CaldavCalendarShareService.class);
    Level previous = logger.getLevel();
    ListAppender<ILoggingEvent> logged = new ListAppender<>();
    logged.start();
    logger.addAppender(logged);
    logger.setLevel(Level.DEBUG);
    try {
      onBlueMind();
      when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(acl(owner(), expanded(WRITER_UID, "Write")), owner());

      CaldavShareException notApplied = assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));

      assertEquals(CaldavCalendarShareService.NOT_APPLIED, notApplied.getCode());
      assertTrue(logged.list.stream()
                            .anyMatch(event -> event.getLevel() == Level.WARN
                                && event.getFormattedMessage().contains("differ after the change eXo asked for")),
                 String.valueOf(logged.list));
    } finally {
      logger.detachAppender(logged);
      logger.setLevel(previous);
    }
  }

  // ---------------------------------------------------------------- the menu's probe is remembered

  /**
   * Agenda asks which calendars are shareable on every refresh of its panel
   * and every open of a calendar's menu. The server's answer stands for the
   * memo: a second ask within it probes nothing, one after it probes again.
   *
   * @throws Exception never
   */
  @Test
  public void theServersAnswerIsRememberedBetweenTwoOpensOfTheMenu() throws Exception {
    AtomicLong clock = new AtomicLong();
    CaldavCalendarShareService memoised = memoised(clock);
    when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.EXO)).thenReturn(List.of(exoPair()));
    when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice")).thenReturn(List.of(calendar(CALENDAR, ALICE, ANCHOR)));

    assertEquals(List.of(CALENDAR), memoised.shareableCalendarIds(ALICE, "alice"));
    assertEquals(List.of(CALENDAR), memoised.shareableCalendarIds(ALICE, "alice"));
    verify(calDavClient, times(1)).capabilities(endpoint, COLLECTION);

    clock.addAndGet(Duration.ofSeconds(301).toNanos());
    assertEquals(List.of(CALENDAR), memoised.shareableCalendarIds(ALICE, "alice"));
    verify(calDavClient, times(2)).capabilities(endpoint, COLLECTION);
  }

  /**
   * A server that offers no sharing is remembered as such for the memo — the
   * steady state that was re-probed on every open — while a probe that failed
   * is not an answer and is asked again at once.
   *
   * @throws Exception never
   */
  @Test
  public void aServerOfferingNoSharingIsRememberedAndAFailedProbeIsNot() throws Exception {
    CaldavCalendarShareService memoised = memoised(new AtomicLong());
    when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.EXO)).thenReturn(List.of(exoPair()));
    when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice")).thenReturn(List.of(calendar(CALENDAR, ALICE, ANCHOR)));
    when(calDavClient.capabilities(endpoint, COLLECTION)).thenThrow(new CalDavException("gone"))
                                                          .thenThrow(new CalDavException("gone"))
                                                          .thenReturn(DavOptions.of(List.of(), List.of()));

    assertEquals(List.of(), memoised.shareableCalendarIds(ALICE, "alice"), "failed");
    assertEquals(List.of(), memoised.shareableCalendarIds(ALICE, "alice"), "failed again: not remembered");
    verify(calDavClient, times(2)).capabilities(endpoint, COLLECTION);

    assertEquals(List.of(), memoised.shareableCalendarIds(ALICE, "alice"), "not offered");
    assertEquals(List.of(), memoised.shareableCalendarIds(ALICE, "alice"), "not offered: remembered");
    verify(calDavClient, times(3)).capabilities(endpoint, COLLECTION);
  }

  /**
   * Which imported collections alice owns is remembered for the set of
   * imported calendars it was computed for: opening the menu twice lists her
   * calendar home once, importing another calendar lists it again at once.
   *
   * @throws Exception never
   */
  @Test
  public void theOwnedImportedAnswerIsRememberedUntilAnotherCalendarIsImported() throws Exception {
    CaldavCalendarShareService memoised = memoised(new AtomicLong());
    CalendarSync pair = onStalwartImported(STALWART_IMPORTED);
    CalendarCollection listed = collection(ALICE_HOME + "default/", "/dav/pal/alice%40stalwart.local/", true);
    // Lenient, as in anImportedCalendarAliceOwnsOnStalwartIsShared: the listing reads the eXo-created pairs first.
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.REMOTE)).thenReturn(List.of(pair));
    when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice")).thenReturn(List.of(calendar(CALENDAR, ALICE, ANCHOR)));
    when(calDavClient.listCalendars(endpoint, ALICE_HOME)).thenReturn(List.of(listed));

    assertEquals(List.of(CALENDAR), memoised.shareableCalendarIds(ALICE, "alice"));
    assertEquals(List.of(CALENDAR), memoised.shareableCalendarIds(ALICE, "alice"));
    verify(calDavClient, times(1)).listCalendars(endpoint, ALICE_HOME);

    CalendarSync second = importedPair("/dav/cal/alice@stalwart.local/second");
    second.setLocalCalendarSyncUid("second");
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.REMOTE)).thenReturn(List.of(pair, second));
    when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice")).thenReturn(List.of(calendar(CALENDAR, ALICE, ANCHOR),
                                                                                                  calendar(15L, ALICE, "second")));

    assertEquals(List.of(CALENDAR), memoised.shareableCalendarIds(ALICE, "alice"));
    verify(calDavClient, times(2)).listCalendars(endpoint, ALICE_HOME);
  }

  // ---------------------------------------------------------------- imported calendars

  /**
   * An imported calendar alice owns on Stalwart — its {@code DAV:owner} is her
   * recorded principal, she may write it, and it sits under her calendar home
   * — is offered in the menu and can be shared: its sharees and candidates are
   * read after that check, each confirming ownership again, and nothing
   * refuses it.
   *
   * @throws Exception never
   */
  @Test
  public void anImportedCalendarAliceOwnsOnStalwartIsShared() throws Exception {
    CalendarSync pair = onStalwartImported(STALWART_IMPORTED);
    when(calDavClient.readCalendar(endpoint, STALWART_IMPORTED + "/")).thenReturn(collection(ALICE_HOME + "default/", "/dav/pal/alice%40stalwart.local/", true));
    // Lenient: the listing reads eXo-created pairs before imported ones, and a strict stub on the second query alone
    // would make that first, unstubbed query a stubbing problem the listing swallows into an empty answer.
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.REMOTE)).thenReturn(List.of(pair));
    when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice")).thenReturn(List.of(calendar(CALENDAR, ALICE, ANCHOR)));
    when(calDavClient.listCalendars(endpoint, ALICE_HOME)).thenReturn(List.of(collection(ALICE_HOME + "default/", "/dav/pal/alice%40stalwart.local/", true)));

    when(calDavClient.readAcl(endpoint, STALWART_IMPORTED + "/")).thenReturn(CollectionAcl.of(List.of(), Set.of()));
    assertEquals(List.of(CALENDAR), service.shareableCalendarIds(ALICE, "alice"));
    service.candidates(ALICE, "alice", CALENDAR, null);
    CalendarShares shares = service.listShares(ALICE, "alice", CALENDAR);

    assertTrue(shares.sharees().isEmpty());
    verify(calDavClient, org.mockito.Mockito.times(2)).readCalendar(endpoint, STALWART_IMPORTED + "/");
    verify(calDavClient).readAcl(endpoint, STALWART_IMPORTED + "/");
  }

  /**
   * On Stalwart an imported calendar is never shared unless all three hold,
   * whichever fails: a colleague's calendar listed in alice's home (another
   * {@code DAV:owner}), one read-only for her, one outside her calendar home,
   * one the server does not describe, and any imported calendar when her
   * principal was never recorded (an eXo-created calendar beside it stays
   * offered). Each is left out of the menu and refused before its access list
   * is read or anything is written.
   *
   * @throws Exception never
   */
  @Test
  public void anImportedCalendarAliceDoesNotOwnOnStalwartIsNeverShared() throws Exception {
    String bobs = "/dav/pal/bob%40stalwart.local/";
    String alices = "/dav/pal/alice%40stalwart.local/";
    Object[][] cases = {
        { STALWART_IMPORTED, collection(ALICE_HOME + "default/", bobs, true), "a colleague's calendar in her home" },
        { STALWART_IMPORTED, collection(ALICE_HOME + "default/", alices, false), "read-only for her" },
        { "/dav/cal/bob@stalwart.local/default", collection("/dav/cal/bob%40stalwart.local/default/", alices, true), "outside her home" },
        { STALWART_IMPORTED, null, "not described" }, };
    for (Object[] kase : cases) {
      String href = (String) kase[0];
      CalendarCollection listed = (CalendarCollection) kase[1];
      CalendarSync pair = onStalwartImported(href);
      lenient().when(calDavClient.readCalendar(endpoint, href + "/")).thenReturn(listed);
      lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.REMOTE)).thenReturn(List.of(pair));
      lenient().when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice")).thenReturn(List.of(calendar(CALENDAR, ALICE, ANCHOR)));
      lenient().when(calDavClient.listCalendars(endpoint, ALICE_HOME)).thenReturn(listed == null ? List.of() : List.of(listed));

      assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"), (String) kase[2]);
      assertEquals(CaldavCalendarShareService.NOT_OWNED_ON_SERVER,
                   assertThrows(CaldavShareException.class, () -> service.candidates(ALICE, "alice", CALENDAR, null)).getCode(),
                   (String) kase[2]);
      assertEquals(CaldavCalendarShareService.NOT_OWNED_ON_SERVER,
                   assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob")).getCode(),
                   (String) kase[2]);
    }
    // The empty menus above are the ownership rule's answer, not a listing that failed earlier and was swallowed:
    // every case reached the home listing and the collection read.
    verify(calDavClient, org.mockito.Mockito.atLeast(cases.length)).listCalendars(endpoint, ALICE_HOME);
    verify(calDavClient, org.mockito.Mockito.atLeast(2 * cases.length)).readCalendar(eq(endpoint), anyString());
    onStalwartImported(STALWART_IMPORTED);
    lenient().when(calDavClient.readCalendar(endpoint, STALWART_IMPORTED + "/"))
             .thenReturn(collection(ALICE_HOME + "default/", "/dav/pal/alice%40stalwart.local/", true));
    when(caldavConnectionIdentityService.principalOf(ALICE, STALWART)).thenReturn(null);
    assertEquals(CaldavCalendarShareService.NOT_OWNED_ON_SERVER,
                 assertThrows(CaldavShareException.class, () -> service.listShares(ALICE, "alice", CALENDAR)).getCode(),
                 "no recorded principal");
    // An eXo-created calendar beside an import, and a listing that would offer the import had the principal been
    // recorded: with none, the import is left out and the eXo-created calendar stays, so the answer cannot come from the
    // whole menu failing.
    CalendarSync importedElsewhere = importedPair(STALWART_IMPORTED);
    importedElsewhere.setLocalCalendarSyncUid("imported");
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.EXO)).thenReturn(List.of(exoPair()));
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.REMOTE)).thenReturn(List.of(importedElsewhere));
    lenient().when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice"))
             .thenReturn(List.of(calendar(14L, ALICE, "imported"), calendar(CALENDAR, ALICE, ANCHOR)));
    lenient().when(calDavClient.listCalendars(endpoint, ALICE_HOME))
             .thenReturn(List.of(collection(ALICE_HOME + "default/", "/dav/pal/alice%40stalwart.local/", true)));
    assertEquals(List.of(CALENDAR), service.shareableCalendarIds(ALICE, "alice"),
                 "no recorded principal, in the menu: the import is left out, the eXo-created calendar stays");
    verify(calDavClient, never()).readAcl(any(), anyString());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  /**
   * On BlueMind an imported calendar under alice's own uid — her default,
   * {@code calendar:Default:<her uid>}, one she created through BlueMind,
   * {@code calendar:UserCreated:<her uid>:<uuid>}, or a bare container — is
   * offered without any REST call, and shared once BlueMind's access list
   * gives her every verb, as it lists a container's owner.
   *
   * @throws Exception never
   */
  @Test
  public void anImportedBlueMindCalendarAliceOwnsIsShared() throws Exception {
    for (String container : List.of("calendar:Default:" + FRANCOIS_UID,
                                     "calendar:UserCreated:" + FRANCOIS_UID + ":6F1A2B3C-4D5E-4F60-8A7B-9C0D1E2F3A4B",
                                     "3B8E5C71-2A4D-4F6B-9C1E-7D5A3B2C1F09")) {
      String href = "/dav/calendars/__uids__/" + FRANCOIS_UID + "/" + container;
      CalendarSync pair = onBlueMindImported(href);
      lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.REMOTE)).thenReturn(List.of(pair));
      lenient().when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice")).thenReturn(List.of(calendar(CALENDAR, ALICE, ANCHOR)));
      lenient().when(blueMindAclClient.acceptsCredentials(endpoint)).thenReturn(true);
      lenient().when(blueMindAclClient.readAcl(endpoint, container)).thenReturn(owner());

      assertEquals(List.of(CALENDAR), service.shareableCalendarIds(ALICE, "alice"), container);
      verify(blueMindAclClient, never()).readAcl(any(), anyString());

      CalendarShares shares = service.listShares(ALICE, "alice", CALENDAR);

      assertTrue(shares.sharees().isEmpty(), container);
      verify(blueMindAclClient).readAcl(endpoint, container);
      org.mockito.Mockito.clearInvocations(blueMindAclClient);
    }
  }

  /**
   * A subscription to someone else's BlueMind calendar is never shared, although
   * BlueMind lists it under alice's own uid and names her its owner: a
   * {@code calendar:<resource uid>} resource, a colleague's
   * {@code calendar:Default:<their uid>}, one a colleague created
   * ({@code calendar:UserCreated:<their uid>:<uuid>}), and a malformed
   * {@code calendar:UserCreated:<her uid>:} with no calendar id are left out of
   * the menu and refused before any REST call.
   *
   * @throws Exception never
   */
  @Test
  public void aBlueMindSubscriptionIsNeverShared() throws Exception {
    for (String container : List.of("calendar:7E3AE6F3-5B2C-4D1E-9A8F-6C0B3D2E1F4A",
                                     "calendar:Default:" + CAMILLE_UID,
                                     "calendar:UserCreated:" + CAMILLE_UID + ":6F1A2B3C-4D5E-4F60-8A7B-9C0D1E2F3A4B",
                                     "calendar:UserCreated:" + FRANCOIS_UID + ":")) {
      String href = "/dav/calendars/__uids__/" + FRANCOIS_UID + "/" + container;
      CalendarSync pair = onBlueMindImported(href);
      lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.REMOTE)).thenReturn(List.of(pair));
      lenient().when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice")).thenReturn(List.of(calendar(CALENDAR, ALICE, ANCHOR)));
      lenient().when(blueMindAclClient.acceptsCredentials(endpoint)).thenReturn(true);

      assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"), container);
      assertEquals(CaldavCalendarShareService.NOT_OWNED_ON_SERVER,
                   assertThrows(CaldavShareException.class, () -> service.listShares(ALICE, "alice", CALENDAR)).getCode(),
                   container);
    }
    // Each empty menu is the path rule's answer: the listing got as far as the capability check every time.
    verify(calDavClient, org.mockito.Mockito.atLeast(8)).capabilities(eq(endpoint), anyString());
    verify(blueMindAclClient, never()).readAcl(any(), anyString());
  }

  /**
   * An imported BlueMind calendar whose access list gives alice neither
   * {@code All} nor {@code Manage} — someone else's calendar she only reads,
   * under a bare container uid — is refused on reading its sharees and on a
   * grant, and so is one whose access list BlueMind refuses to read at all,
   * which is what a mere subscriber gets (only a manager may read it); no
   * {@code CS:share} is ever posted.
   *
   * @throws Exception never
   */
  @Test
  public void aBlueMindCalendarAliceCannotManageIsNeverShared() throws Exception {
    String container = "5C7E9A12-3B4D-4E6F-8A1B-2C3D4E5F6A7B";
    onBlueMindImported("/dav/calendars/__uids__/" + FRANCOIS_UID + "/" + container);
    when(blueMindAclClient.readAcl(endpoint, container)).thenReturn(acl(expanded(CAMILLE_UID, "All"), expanded(FRANCOIS_UID, "Read")))
                                                        .thenReturn(acl(expanded(CAMILLE_UID, "All"), expanded(FRANCOIS_UID, "Read")))
                                                        .thenThrow(new CalDavForbiddenException("403"));

    assertEquals(CaldavCalendarShareService.NOT_OWNED_ON_SERVER,
                 assertThrows(CaldavShareException.class, () -> service.listShares(ALICE, "alice", CALENDAR)).getCode());
    assertEquals(CaldavCalendarShareService.NOT_OWNED_ON_SERVER,
                 assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob")).getCode());
    assertEquals(CaldavCalendarShareService.NOT_OWNED_ON_SERVER,
                 assertThrows(CaldavShareException.class, () -> service.listShares(ALICE, "alice", CALENDAR)).getCode(),
                 "a subscriber's refused access-list read");
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean(), anyBoolean());
  }

  /**
   * The calendar eXo writes meeting copies into is flagged, through the push's
   * own resolution: an imported calendar named by {@code mirrorDestination} carries
   * the flag even when the push spells its href percent-encoded, one the push
   * does not name does not, and an eXo-created calendar the push adopted as its
   * destination is flagged too.
   *
   * @throws Exception never
   */
  @Test
  public void theCalendarReceivingMeetingCopiesIsFlagged() throws Exception {
    String container = "calendar:Default:" + FRANCOIS_UID;
    String href = "/dav/calendars/__uids__/" + FRANCOIS_UID + "/" + container;
    onBlueMindImported(href);
    when(blueMindAclClient.readAcl(endpoint, container)).thenReturn(owner());
    when(caldavPushService.mirrorDestination(ALICE, "alice"))
        .thenReturn(new MirrorTarget("/dav/calendars/__uids__/" + FRANCOIS_UID + "/calendar%3ADefault%3A" + FRANCOIS_UID + "/", false, null),
                    new MirrorTarget("/dav/calendars/__uids__/" + FRANCOIS_UID + "/exo-meetings", false, null));

    assertTrue(service.listShares(ALICE, "alice", CALENDAR).meetingCopies(), "named by the push, percent-encoded");
    assertFalse(service.listShares(ALICE, "alice", CALENDAR).meetingCopies(), "the push names its dedicated collection");

    onBlueMind();
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(owner());
    when(caldavPushService.mirrorDestination(ALICE, "alice")).thenReturn(new MirrorTarget(BM_COLLECTION, true, "eXo"));
    assertTrue(service.listShares(ALICE, "alice", CALENDAR).meetingCopies(), "an eXo-created calendar the push adopted");
  }

  /**
   * When where the copies go cannot be asked, the warning errs towards being
   * shown: the destination the push last recorded decides when there is one,
   * and with none recorded the calendar is flagged.
   *
   * @throws Exception never
   */
  @Test
  public void aMeetingCopiesLookupThatFailsWarnsUnlessTheRecordedDestinationIsElsewhere() throws Exception {
    String container = "calendar:Default:" + FRANCOIS_UID;
    String href = "/dav/calendars/__uids__/" + FRANCOIS_UID + "/" + container;
    onBlueMindImported(href);
    when(blueMindAclClient.readAcl(endpoint, container)).thenReturn(owner());
    when(caldavPushService.mirrorDestination(ALICE, "alice")).thenThrow(new CalDavUnreachableException("down"));
    CaldavUserSetting settings = connectedTo(STALWART);
    when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(settings);

    settings.setMirrorCalendarHref(href + "/");
    assertTrue(service.listShares(ALICE, "alice", CALENDAR).meetingCopies(), "recorded destination is this calendar");
    settings.setMirrorCalendarHref("/dav/calendars/__uids__/" + FRANCOIS_UID + "/exo-meetings/");
    assertFalse(service.listShares(ALICE, "alice", CALENDAR).meetingCopies(), "recorded destination is elsewhere");
    settings.setMirrorCalendarHref(null);
    assertTrue(service.listShares(ALICE, "alice", CALENDAR).meetingCopies(), "no destination known");
  }

  /**
   * A grant on an imported Stalwart calendar alice owns goes all the way: the
   * ownership check passes and the RFC 3744 write is sent for her imported
   * pair, then read back.
   *
   * @throws Exception never
   */
  @Test
  public void aGrantOnAnImportedCalendarAliceOwnsReachesTheWrite() throws Exception {
    onStalwartImported(STALWART_IMPORTED);
    when(calDavClient.readCalendar(endpoint, STALWART_IMPORTED + "/"))
        .thenReturn(collection(ALICE_HOME + "default/", "/dav/pal/alice%40stalwart.local/", true));
    AccessControlEntry bobs = AccessControlEntry.readGrantTo("/dav/pal/bob%40stalwart.local/");
    when(calDavClient.readAcl(endpoint, STALWART_IMPORTED + "/")).thenReturn(CollectionAcl.of(List.of(), Set.of()),
                                                                             CollectionAcl.of(List.of(bobs), Set.of()));
    ArgumentCaptor<CalendarSync> pair = ArgumentCaptor.forClass(CalendarSync.class);
    when(calDavClient.writeAcl(eq(endpoint), pair.capture(), anyList())).thenReturn(new AclWriteResult(200, List.of(), List.of()));

    service.grant(ALICE, "alice", CALENDAR, "bob");

    assertEquals(SyncOrigin.REMOTE, pair.getValue().getOrigin());
    assertEquals(STALWART_IMPORTED, pair.getValue().getRemoteHref());
  }

  /**
   * A grant on an imported BlueMind calendar alice owns goes all the way: the
   * path rule and the access list pass, the {@code CS:share} is posted for her
   * imported pair, and the grant is confirmed on the access list read back.
   *
   * @throws Exception never
   */
  @Test
  public void aGrantOnAnImportedBlueMindCalendarAliceOwnsReachesTheShare() throws Exception {
    String container = "calendar:Default:" + FRANCOIS_UID;
    onBlueMindImported("/dav/calendars/__uids__/" + FRANCOIS_UID + "/" + container);
    when(blueMindAclClient.readAcl(endpoint, container)).thenReturn(owner(), acl(owner(), expanded(ERIC_UID, "Read")));

    service.grant(ALICE, "alice", CALENDAR, "bob");

    ArgumentCaptor<CalendarSync> pair = ArgumentCaptor.forClass(CalendarSync.class);
    verify(calDavClient).postCalendarServerShare(eq(endpoint), pair.capture(), eq(ERIC_ADDRESS), eq(false), anyBoolean());
    assertEquals(SyncOrigin.REMOTE, pair.getValue().getOrigin());
  }

  /**
   * The menu probes a collection eXo created before an imported one, whatever
   * order agenda lists the calendars in, and a collection failing on its own —
   * gone, or answering an error status other than 401, 403, 407 and a gateway
   * status — tries the next calendar, so it does not hide Share on the user's
   * other calendars. Refused credentials and an unreachable server stop the
   * listing instead (pinned by the next test).
   *
   * @throws Exception never
   */
  @Test
  public void theMenuProbesAnExportedCollectionFirstAndSurvivesOneThatFails() throws Exception {
    CalendarSync imported = importedPair(STALWART_IMPORTED);
    imported.setLocalCalendarSyncUid("imported");
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.EXO)).thenReturn(List.of(exoPair()));
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.REMOTE)).thenReturn(List.of(imported));
    lenient().when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice"))
             .thenReturn(List.of(calendar(14L, ALICE, "imported"), calendar(CALENDAR, ALICE, ANCHOR)));
    lenient().when(calDavClient.discoverHome(endpoint)).thenReturn(new CalendarHome("/dav/pal/alice%40stalwart.local/", ALICE_HOME));
    lenient().when(calDavClient.listCalendars(endpoint, ALICE_HOME)).thenReturn(List.of());

    assertEquals(List.of(CALENDAR), service.shareableCalendarIds(ALICE, "alice"));
    verify(calDavClient, never()).capabilities(endpoint, STALWART_IMPORTED + "/");

    when(calDavClient.capabilities(endpoint, COLLECTION)).thenThrow(new CalDavException("gone"));
    lenient().when(calDavClient.capabilities(endpoint, STALWART_IMPORTED + "/")).thenReturn(stalwartOptions());

    assertEquals(List.of(CALENDAR), service.shareableCalendarIds(ALICE, "alice"),
                 "the exported calendar is still offered once another collection answered the probe");
    verify(calDavClient).capabilities(endpoint, STALWART_IMPORTED + "/");
  }

  /**
   * The menu's probe stops at once on refused credentials and on an unreachable
   * server, which are known after one attempt: no other calendar is asked, and
   * no calendar is offered. Only a collection failing on its own moves on.
   *
   * @throws Exception never
   */
  @Test
  public void theMenuStopsAtRefusedCredentialsOrAnUnreachableServer() throws Exception {
    CalendarSync imported = importedPair(STALWART_IMPORTED);
    imported.setLocalCalendarSyncUid("imported");
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.EXO)).thenReturn(List.of(exoPair()));
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.REMOTE)).thenReturn(List.of(imported));
    lenient().when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice"))
             .thenReturn(List.of(calendar(14L, ALICE, "imported"), calendar(CALENDAR, ALICE, ANCHOR)));
    when(calDavClient.capabilities(endpoint, COLLECTION)).thenThrow(new CalDavAuthenticationException("401"),
                                                                    new CalDavUnreachableException("down"));
    // Everything a retry would need to succeed: had the listing moved on to the imported collection, it would answer,
    // and the exported calendar would be offered — so an empty menu below is the stop, not a later failure swallowed.
    lenient().when(calDavClient.capabilities(endpoint, STALWART_IMPORTED + "/")).thenReturn(stalwartOptions());
    lenient().when(calDavClient.discoverHome(endpoint)).thenReturn(new CalendarHome("/dav/pal/alice%40stalwart.local/", ALICE_HOME));
    lenient().when(calDavClient.listCalendars(endpoint, ALICE_HOME)).thenReturn(List.of());

    assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"), "refused credentials");
    verify(calDavClient, never()).capabilities(endpoint, STALWART_IMPORTED + "/");
    assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"), "an unreachable server");
    verify(calDavClient, never()).capabilities(endpoint, STALWART_IMPORTED + "/");
    verify(calDavClient, org.mockito.Mockito.times(2)).capabilities(eq(endpoint), anyString());
  }

  /**
   * An entry of the home listing with no href this client can use — none, or
   * one on another host — is never taken for a calendar alice owns, and does
   * not hide Share on her other calendars: the eXo-created calendar stays
   * offered.
   *
   * @throws Exception never
   */
  @Test
  public void aHomeListingEntryWithoutAUsableHrefDoesNotHideShareOnOtherCalendars() throws Exception {
    CalendarSync imported = importedPair(STALWART_IMPORTED);
    imported.setLocalCalendarSyncUid("imported");
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.EXO)).thenReturn(List.of(exoPair()));
    lenient().when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.REMOTE)).thenReturn(List.of(imported));
    lenient().when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice"))
             .thenReturn(List.of(calendar(14L, ALICE, "imported"), calendar(CALENDAR, ALICE, ANCHOR)));
    lenient().when(calDavClient.discoverHome(endpoint)).thenReturn(new CalendarHome("/dav/pal/alice%40stalwart.local/", ALICE_HOME));
    lenient().when(calDavClient.listCalendars(endpoint, ALICE_HOME))
             .thenReturn(List.of(collection(null, "/dav/pal/alice%40stalwart.local/", true)));

    assertEquals(List.of(CALENDAR), service.shareableCalendarIds(ALICE, "alice"),
                 "an entry without a usable href: the import is not offered, the eXo-created calendar stays");
  }

  // ---------------------------------------------------------------- helpers

  /**
   * The code a grant to one login is refused with.
   *
   * @param login the sharee login
   * @return the message code
   */
  /**
   * The service with the production memo (300 s) over a clock the test moves.
   *
   * @param clock nanoseconds, moved by the test
   * @return the service
   */
  private CaldavCalendarShareService memoised(AtomicLong clock) {
    return new CaldavCalendarShareService(agendaCalendarService,
                                          caldavConnectorStorage,
                                          caldavSyncStorage,
                                          calDavClient,
                                          caldavConnectionIdentityService,
                                          identityManager,
                                          blueMindAclClient,
                                          caldavPushService,
                                          caldavShareSubscriptionService,
                                          caldavServerOwnerService,
                                          caldavServerService,
                                          Duration.ofSeconds(300),
                                          clock::get);
  }

  private String refusal(String login) {
    return assertThrows(IllegalArgumentException.class, () -> service.grant(ALICE, "alice", CALENDAR, login)).getMessage();
  }

  /**
   * Alice's pair for calendar 12.
   *
   * @return a fresh pair
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
   * The rig on BlueMind: alice (FRANCOIS) owns a collection in BlueMind's
   * layout answering BlueMind's header; bob is connected as eric, carol as the
   * writer; bob's principal publishes eric's BlueMind address, and bob's eXo
   * profile carries a different e-mail.
   */
  private void onBlueMind() {
    CalendarSync pair = exoPair();
    pair.setRemoteHref(BM_COLLECTION);
    lenient().when(caldavSyncStorage.getPairByLocalCalendar(ALICE, STALWART, ANCHOR)).thenReturn(pair);
    lenient().when(calDavClient.capabilities(endpoint, BM_COLLECTION))
             .thenReturn(DavOptions.of(List.of(BLUEMIND_DAV), List.of()));
    lenient().when(calDavClient.discoverPrincipal(endpoint)).thenReturn(FRANCOIS_PRINCIPAL + "/");
    lenient().when(caldavConnectionIdentityService.principalOf(ALICE, STALWART)).thenReturn(FRANCOIS_PRINCIPAL);
    lenient().when(caldavConnectionIdentityService.principalOf(BOB, STALWART)).thenReturn(ERIC_PRINCIPAL);
    lenient().when(caldavConnectionIdentityService.usersConnectedAs(STALWART, ERIC_PRINCIPAL)).thenReturn(List.of(BOB));
    lenient().when(caldavConnectionIdentityService.usersConnectedAs(STALWART, WRITER_PRINCIPAL)).thenReturn(List.of(CAROL));
    lenient().when(calDavClient.readCalendarUserAddresses(endpoint, ERIC_PRINCIPAL + "/"))
             .thenReturn(List.of(ERIC_PRINCIPAL + "/", "urn:uuid:" + ERIC_UID, "mailto:" + ERIC_ADDRESS));
    Identity bob = user(BOB, "bob", "Bob Test");
    bob.getProfile().setProperty(Profile.EMAIL, "bob@exo.example.com");
    lenient().when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, "bob")).thenReturn(bob);
  }

  /**
   * Alice's calendar bound to an imported Stalwart collection: its pair, the
   * server's answer for any collection, and her calendar home.
   *
   * @param href the canonical collection href
   * @return the pair
   */
  private CalendarSync onStalwartImported(String href) {
    CalendarSync pair = importedPair(href);
    lenient().when(caldavSyncStorage.getPairByLocalCalendar(ALICE, STALWART, ANCHOR)).thenReturn(pair);
    lenient().when(calDavClient.capabilities(eq(endpoint), anyString())).thenReturn(stalwartOptions());
    lenient().when(calDavClient.discoverHome(endpoint)).thenReturn(new CalendarHome("/dav/pal/alice%40stalwart.local/", ALICE_HOME));
    return pair;
  }

  /**
   * Alice (FRANCOIS) on BlueMind with her calendar bound to an imported
   * collection.
   *
   * @param href the canonical collection href
   * @return the pair
   */
  private CalendarSync onBlueMindImported(String href) {
    onBlueMind();
    CalendarSync pair = importedPair(href);
    lenient().when(caldavSyncStorage.getPairByLocalCalendar(ALICE, STALWART, ANCHOR)).thenReturn(pair);
    lenient().when(calDavClient.capabilities(eq(endpoint), anyString())).thenReturn(DavOptions.of(List.of(BLUEMIND_DAV), List.of()));
    return pair;
  }

  /**
   * An active imported pair of alice's calendar.
   *
   * @param href the collection it binds
   * @return a fresh pair
   */
  private static CalendarSync importedPair(String href) {
    CalendarSync pair = exoPair();
    pair.setOrigin(SyncOrigin.REMOTE);
    pair.setRemoteHref(href);
    return pair;
  }

  /**
   * A calendar collection as a PROPFIND describes it.
   *
   * @param href its href
   * @param owner its DAV:owner
   * @param writable whether the privileges let the user write
   * @return the collection
   */
  private static CalendarCollection collection(String href, String owner, boolean writable) {
    return new CalendarCollection(href, "Default", null, null, null, writable, Set.of("VEVENT"), owner, true);
  }

  /**
   * The owner's rights as BlueMind's expanded list carries them
   * ({@code AclService.get}: {@code addOwnerRights}, mail delegation verbs
   * filtered out).
   *
   * @return the owner's entries
   */
  private static List<BlueMindAce> owner() {
    return expanded(FRANCOIS_UID, "All");
  }

  /**
   * One subject's entries as the REST API lists a stored verb: expanded along
   * {@code Verb.java}, mail delegation verbs left out.
   *
   * @param subject the directory entry uid
   * @param verb the stored verb
   * @return the entries
   */
  private static List<BlueMindAce> expanded(String subject, String verb) {
    List<String> verbs = switch (verb) {
    case "All" -> List.of("All", "Write", "Manage", "ReadExtended", "Read", "Freebusy", "Invitation", "Visible");
    case "Write" -> List.of("Write", "Read", "Freebusy", "Invitation", "Visible");
    case "Read" -> List.of("Read", "Freebusy", "Invitation", "Visible");
    case "Freebusy" -> List.of("Freebusy", "Invitation");
    default -> List.of(verb);
    };
    return verbs.stream().map(name -> new BlueMindAce(subject, name)).toList();
  }

  /**
   * Access lists joined.
   *
   * @param parts the lists
   * @return one list
   */
  @SafeVarargs
  private static List<BlueMindAce> acl(List<BlueMindAce>... parts) {
    return java.util.Arrays.stream(parts).flatMap(List::stream).toList();
  }

  /** The DERIVED access list of a calendar shared with eric and published as two private links and a public one. */
  private static final String       PUBLISHED_LINKS   = "bluemind-rest-acl-published-links.derived.json";

  /** The secret parts of the published links in {@link #PUBLISHED_LINKS}, and the prefix every link subject starts with. */
  private static final List<String> PUBLISHED_SECRETS = List.of("x-calendar",
                                                                "PUBLISH_PRIVATE",
                                                                "5ec2e7f0a1b24c3d9e5f60718293a4b5",
                                                                "7b1d9c3ea4f2468b8c0d1e2f3a4b5c6d",
                                                                "0ABB3E9C71D24F5A8B6C0D1E2F3A4B5C");

  /**
   * A DERIVED BlueMind access list fixture, its {@code //} header lines
   * removed, read entry by entry as the REST client reads the answer.
   *
   * @param name the file name
   * @return the entries, in the fixture's order
   */
  private static List<BlueMindAce> derivedAcl(String name) {
    try (java.io.InputStream stream = CaldavCalendarShareServiceTest.class.getResourceAsStream("/caldav/transcripts/" + name)) {
      String json = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                                                                                              .filter(line -> !line.startsWith("//"))
                                                                                              .collect(java.util.stream.Collectors.joining("\n"));
      List<BlueMindAce> aces = new java.util.ArrayList<>();
      for (tools.jackson.databind.JsonNode entry : tools.jackson.databind.json.JsonMapper.builder().build().readTree(json)) {
        aces.add(new BlueMindAce(entry.get("subject").asText(), entry.get("verb").asText()));
      }
      return aces;
    } catch (java.io.IOException | NullPointerException e) {
      throw new IllegalStateException("missing fixture " + name, e);
    }
  }

  /**
   * Asserts a text carries no part of a published link's secret.
   *
   * @param text the text, may be null
   */
  private static void assertNoPublishedSecret(String text) {
    PUBLISHED_SECRETS.forEach(secret -> assertFalse(text != null && text.contains(secret), "a published link's secret leaked: " + text));
  }

  /**
   * Stalwart's answer to OPTIONS.
   *
   * @return the options
   */
  private static DavOptions stalwartOptions() {
    return DavOptions.of(List.of(STALWART_DAV), List.of("OPTIONS, PROPFIND, REPORT, ACL"));
  }

  /**
   * An account connected to a server.
   *
   * @param serverId the server
   * @return the settings
   */
  private static CaldavUserSetting connectedTo(long serverId) {
    CaldavUserSetting settings = new CaldavUserSetting();
    settings.setUsername("alice@stalwart.local");
    settings.setPassword("secret");
    settings.setServerId(serverId);
    return settings;
  }

  /**
   * An agenda calendar.
   *
   * @param id its id
   * @param ownerId its owner identity
   * @param syncUid its anchor
   * @return the calendar
   */
  private static Calendar calendar(long id, long ownerId, String syncUid) {
    Calendar calendar = new Calendar();
    calendar.setId(id);
    calendar.setOwnerId(ownerId);
    calendar.setSyncUid(syncUid);
    return calendar;
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
    profile.setAvatarUrl("/avatar/" + login);
    identity.setProfile(profile);
    return identity;
  }

  /**
   * A connection the platform authenticates stores no password, so reading
   * "connected" as a username <b>and</b> a password hides every shareable calendar
   * of a BlueMind account - the panel comes back empty with nothing to explain it.
   * The addon has one definition; this service asks it.
   */
  @Test
  public void offersTheCalendarsOfAProviderBackedAccount() throws Exception {
    CaldavUserSetting providerBacked = new CaldavUserSetting();
    providerBacked.setUsername("alice@stalwart.local");
    providerBacked.setPassword("");
    providerBacked.setServerId(STALWART);
    when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(providerBacked);
    when(caldavServerService.isConnected(providerBacked)).thenReturn(true);
    when(caldavSyncStorage.getPairsByOrigin(ALICE, STALWART, SyncOrigin.EXO)).thenReturn(List.of(exoPair()));
    when(agendaCalendarService.getCalendarsByOwnerIds(List.of(ALICE), "alice"))
        .thenReturn(List.of(calendar(CALENDAR, ALICE, ANCHOR)));

    assertEquals(List.of(CALENDAR), service.shareableCalendarIds(ALICE, "alice"));
  }
}