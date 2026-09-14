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

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.AccessControlEntry;
import org.exoplatform.caldav.client.AccessControlEntry.AcePrincipal;
import org.exoplatform.caldav.client.AclWriteResult;
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
 * mechanism is not verified; an entry somebody else made survives a grant
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
                                             identityManager);
    lenient().when(agendaCalendarService.getCalendarById(CALENDAR)).thenReturn(calendar(CALENDAR, ALICE, ANCHOR));
    lenient().when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(connectedTo(STALWART));
    lenient().when(caldavSyncStorage.getPairByLocalCalendar(ALICE, STALWART, ANCHOR)).thenReturn(exoPair());
    lenient().when(calDavClient.endpoint(STALWART, "alice")).thenReturn(endpoint);
    lenient().when(calDavClient.options(endpoint, COLLECTION)).thenReturn(stalwartOptions());
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
    verify(calDavClient, never()).options(any(), anyString());
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
    verify(calDavClient, never()).options(any(), anyString());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
  }

  /**
   * BlueMind's captured header selects Apple sharing, which is not offered:
   * nothing is read, nothing is written.
   */
  @Test
  public void aServerWhoseMechanismIsNotVerifiedIsNotOffered() {
    when(calDavClient.options(endpoint, COLLECTION)).thenReturn(DavOptions.of(List.of("1, access-control, calendar-access, calendarserver-sharing"),
                                                                              List.of("PROPFIND, REPORT, ACL, POST")));

    CaldavShareException refused = assertThrows(CaldavShareException.class, () -> service.grant(ALICE, "alice", CALENDAR, "bob"));
    CaldavShareException listing = assertThrows(CaldavShareException.class, () -> service.listShares(ALICE, "alice", CALENDAR));

    assertEquals(CaldavCalendarShareService.NOT_SUPPORTED, refused.getCode());
    assertEquals(CaldavCalendarShareService.NOT_SUPPORTED, listing.getCode());
    verify(calDavClient, never()).readAcl(any(), anyString());
    verify(calDavClient, never()).writeAcl(any(), any(), anyList());
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
    doThrow(new CalDavAuthenticationException("401")).when(calDavClient).options(endpoint, COLLECTION);
    assertEquals(CaldavCalendarShareService.CREDENTIALS,
                 assertThrows(CaldavShareException.class, () -> service.listShares(ALICE, "alice", CALENDAR)).getCode());

    doThrow(new CalDavUnreachableException("down")).when(calDavClient).options(endpoint, COLLECTION);
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
   * The calendars offered "Share…" are the owned ones bound to a collection
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

    when(calDavClient.options(endpoint, COLLECTION)).thenReturn(DavOptions.of(List.of("1, access-control, calendarserver-sharing"),
                                                                              List.of("ACL")));
    assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"));

    doThrow(new CalDavUnreachableException("down")).when(calDavClient).options(endpoint, COLLECTION);
    assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"));

    when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(null);
    assertEquals(List.of(), service.shareableCalendarIds(ALICE, "alice"));
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
