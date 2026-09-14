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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.exoplatform.caldav.client.BlueMindAclClient;
import org.exoplatform.caldav.client.BlueMindAclClient.BlueMindAce;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavForbiddenException;
import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.CollectionAcl;
import org.exoplatform.caldav.client.DavOptions;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarShares;
import org.exoplatform.caldav.model.CalendarShares.CalendarSharee;
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
   * methods.
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

  private CaldavCalendarShareService      service;

  /**
   * Alice connected to Stalwart, owning calendar 12 exported to her account,
   * with bob and carol known to eXo.
   */
  @BeforeEach
  public void rig() {
    service = new CaldavCalendarShareService(agendaCalendarService,
                                             caldavConnectorStorage,
                                             caldavSyncStorage,
                                             calDavClient,
                                             caldavConnectionIdentityService,
                                             identityManager,
                                             blueMindAclClient);
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
   * Only a collection eXo created for this calendar is shareable: a calendar
   * materialised from the server, a pair not active, a pair whose collection
   * is not the derived slug (the user's own default, say), and a calendar
   * never bound are each refused, and no request is made.
   */
  @Test
  public void onlyTheCollectionEXoCreatedForTheCalendarIsShareable() {
    CalendarSync materialised = exoPair();
    materialised.setOrigin(SyncOrigin.REMOTE);
    CalendarSync paused = exoPair();
    paused.setStatus(CalendarSyncStatus.PAUSED);
    CalendarSync elsewhere = exoPair();
    elsewhere.setRemoteHref("/dav/cal/alice%40stalwart.local/default/");
    for (CalendarSync pair : java.util.Arrays.asList(materialised, paused, elsewhere, null)) {
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
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean());
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

    verify(calDavClient).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(false));
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), eq("bob@exo.example.com"), anyBoolean());
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
    when(calDavClient.postCalendarServerShare(any(), any(), anyString(), anyBoolean())).thenReturn(200);

    CaldavShareException refused = assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));

    assertEquals(CaldavCalendarShareService.NOT_APPLIED, refused.getCode());
    verify(calDavClient).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(false));
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
    when(blueMindAclClient.readAcl(endpoint, BM_CONTAINER)).thenReturn(acl(owner(), expanded(ERIC_UID, "Write")),
                                                                        acl(owner(), expanded(ERIC_UID, "Write")),
                                                                        acl(owner(), expanded(ERIC_UID, "Freebusy")),
                                                                        acl(owner(), expanded(WRITER_UID, "Write")),
                                                                        acl(owner(), expanded(WRITER_UID, "Write"), expanded(ERIC_UID, "Read")));

    IllegalArgumentException grant = assertThrows(IllegalArgumentException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));
    IllegalArgumentException revoke = assertThrows(IllegalArgumentException.class, () -> service.revoke(ALICE, "alice", CALENDAR, "bob"));
    IllegalArgumentException freeBusy = assertThrows(IllegalArgumentException.class,
                                                     () -> service.revoke(ALICE, "alice", CALENDAR, "bob"));
    assertEquals(CaldavCalendarShareService.NOT_READ_ONLY, grant.getMessage());
    assertEquals(CaldavCalendarShareService.NOT_READ_ONLY, revoke.getMessage());
    assertEquals(CaldavCalendarShareService.NOT_READ_ONLY, freeBusy.getMessage());
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean());

    CalendarShares shares = service.grant(ALICE, "alice", CALENDAR, "bob");

    verify(calDavClient).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(false));
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
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean());
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
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean());
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
    verify(calDavClient, org.mockito.Mockito.times(2)).postCalendarServerShare(eq(endpoint), any(CalendarSync.class), eq(ERIC_ADDRESS), eq(true));
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
    verify(calDavClient, never()).postCalendarServerShare(any(), any(), anyString(), anyBoolean());
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
    CaldavShareException offered = assertThrows(CaldavShareException.class, () -> service.candidates(ALICE, "alice", CALENDAR, null));

    assertEquals(CaldavCalendarShareService.OWNER_UNKNOWN, granted.getCode());
    assertEquals(CaldavCalendarShareService.OWNER_UNKNOWN, offered.getCode());
    verify(calDavClient, never()).readAcl(any(), anyString());
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
   * A grant giving bob more than read — made outside eXo — is not eXo's to
   * take away: the revoke is refused and nothing is written.
   */
  @Test
  public void aRevokeOfMoreThanReadIsRefused() {
    AccessControlEntry bobWrites = new AccessControlEntry(AcePrincipal.href("/dav/pal/bob%40stalwart.local/"), false, false,
                                                          Set.of("{DAV:}read", "{DAV:}write"), false, null);
    when(calDavClient.readAcl(endpoint, COLLECTION)).thenReturn(CollectionAcl.of(List.of(bobWrites), Set.of()));

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.revoke(ALICE, "alice", CALENDAR, "bob"));

    assertEquals(CaldavCalendarShareService.NOT_READ_ONLY, refused.getMessage());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
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
    AccessControlEntry carolWrites = new AccessControlEntry(AcePrincipal.href("/dav/pal/carol%40stalwart.local/"), false, false,
                                                            Set.of("{DAV:}read", "{DAV:}write"), false, null);
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
   * A server whose collection advertises no sharing mechanism — BlueMind's
   * bare 204 before its PROPFIND was read, say — is reported at INFO the first
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

  // ---------------------------------------------------------------- helpers

  /**
   * The code a grant to one login is refused with.
   *
   * @param login the sharee login
   * @return the message code
   */
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
}
