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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Who a shared calendar is said to belong to, from each witness that said it
 * was shared (EXO-90237), and what is said when the witness cannot name
 * anybody.
 *
 * <p>
 * The two live shapes of 2026-09-13 are the fixtures. <b>CAL2 on BlueMind</b>:
 * eric sees {@code exo-cal-959b5529-…}, an eXo calendar of root (identity 1,
 * BlueMind principal FRANCOIS), under his own home with himself named as
 * owner — so the owner must come from eXo's pair, and the server's word
 * would name the viewer. <b>Alice's default on Stalwart</b>: bob sees
 * {@code /dav/cal/alice%40stalwart.local/default/} with {@code DAV:owner}
 * {@code /dav/pal/alice%40stalwart.local/}, whose display name is "Alice".
 */
@ExtendWith(MockitoExtension.class)
public class CaldavCalendarOwnerServiceTest {

  private static final long          SERVER      = 7L;

  /** The eXo user whose calendar CAL2 is: root, identity 1. */
  private static final long          ROOT        = 1L;

  private static final String        CAL2_ANCHOR = "959b5529-ea4c-4ae4-a793-a2c201c3af9f";

  /** CAL2 as BlueMind lists it to eric, under his own home. */
  private static final String        CAL2        = "/dav/calendars/eric/exo-cal-" + CAL2_ANCHOR + "/";

  /** CAL2's path as the classification asked about it: canonical. */
  private static final String        CAL2_CANONICAL = CaldavSyncStorage.canonicalHref(CAL2);

  /** Eric's own principal, which BlueMind names as CAL2's owner. */
  private static final String        ERIC        = "/dav/principals/eric/";

  /** Bob's own principal on Stalwart, the viewer of Alice's share. */
  private static final String        BOB         = "/dav/pal/bob%40stalwart.local/";

  /** Alice's principal as Stalwart spells it, login percent-encoded. */
  private static final String        ALICE       = "/dav/pal/alice%40stalwart.local/";

  private static final String        ALICES      = "/dav/cal/alice%40stalwart.local/default/";

  /** The viewer of every listing here: bob, identity 9 on the rig. */
  private static final long          VIEWER      = 9L;

  /** alice, identity 5, connected to Stalwart as Alice's principal. */
  private static final long          ALICE_USER  = 5L;

  /** alice2, identity 6, connected to the same Stalwart login. */
  private static final long          ALICE2_USER = 6L;

  @Mock
  private CaldavOutboundService      caldavOutboundService;

  @Mock
  private IdentityManager            identityManager;

  @Mock
  private CalDavClient               calDavClient;

  @Mock
  private CalDavEndpoint             endpoint;

  @Mock
  private CaldavConnectionIdentityService caldavConnectionIdentityService;

  private CaldavCalendarOwnerService service;

  private Map<String, CalendarOwner> owners;

  @BeforeEach
  public void build() {
    service = new CaldavCalendarOwnerService(caldavOutboundService, identityManager, calDavClient, caldavConnectionIdentityService);
    owners = new HashMap<>();
  }

  // ------------------------------------ a colleague's eXo calendar

  /**
   * CAL2: the owner is root, named by identity, login and full name, from
   * the pair this deployment holds — asked for by the canonical path the
   * classification used — and the server, which names eric as owner, is
   * not asked.
   */
  @Test
  public void aColleaguesExoCalendarNamesTheColleagueFromThePairNotFromTheServer() {
    when(caldavOutboundService.exportingUserOf(SERVER, CAL2_CANONICAL)).thenReturn(ROOT);
    when(identityManager.getIdentity(1L)).thenReturn(user("1", "root", "Root Root"));

    CalendarOwner owner = ownerOf(ERIC, CollectionOwnership.COLLEAGUES_EXO_CALENDAR, listed(CAL2, ERIC));

    assertEquals(ROOT, owner.identityId());
    assertEquals("root", owner.username());
    assertEquals("Root Root", owner.displayName());
    verify(calDavClient, never()).readDisplayName(any(), anyString());
    verify(caldavConnectionIdentityService, never()).usersConnectedAs(anyLong(), any());
  }

  /**
   * A user the registry no longer knows names nobody: the calendar stays a
   * share, with no person to show for it — and the server's owner is not a
   * fallback, since on BlueMind it is the viewer.
   */
  @Test
  public void aColleagueTheRegistryNoLongerKnowsNamesNobody() {
    when(caldavOutboundService.exportingUserOf(SERVER, CAL2_CANONICAL)).thenReturn(ROOT);
    when(identityManager.getIdentity(1L)).thenReturn(null);

    assertSame(CalendarOwner.NONE, ownerOf(ERIC, CollectionOwnership.COLLEAGUES_EXO_CALENDAR, listed(CAL2, ERIC)));
    verify(calDavClient, never()).readDisplayName(any(), anyString());
  }

  /**
   * A deleted user names nobody either: their login must not be shown.
   */
  @Test
  public void aDeletedColleagueNamesNobody() {
    when(caldavOutboundService.exportingUserOf(SERVER, CAL2_CANONICAL)).thenReturn(ROOT);
    Identity gone = user("1", "root", "Root Root");
    gone.setDeleted(true);
    when(identityManager.getIdentity(1L)).thenReturn(gone);

    assertSame(CalendarOwner.NONE, ownerOf(ERIC, CollectionOwnership.COLLEAGUES_EXO_CALENDAR, listed(CAL2, ERIC)));
  }

  /**
   * A pair that vanished between the classification and this question names
   * nobody, quietly.
   */
  @Test
  public void aPairThatVanishedNamesNobody() {
    when(caldavOutboundService.exportingUserOf(SERVER, CAL2_CANONICAL)).thenReturn(null);

    assertSame(CalendarOwner.NONE, ownerOf(ERIC, CollectionOwnership.COLLEAGUES_EXO_CALENDAR, listed(CAL2, ERIC)));
    verify(identityManager, never()).getIdentity(anyLong());
  }

  /**
   * A user with no full name on their profile is still named, by their
   * login: better a login than a blank line under a share.
   */
  @Test
  public void aColleagueWithNoFullNameIsNamedByTheirLogin() {
    when(caldavOutboundService.exportingUserOf(SERVER, CAL2_CANONICAL)).thenReturn(ROOT);
    when(identityManager.getIdentity(1L)).thenReturn(user("1", "root", " "));

    CalendarOwner owner = ownerOf(ERIC, CollectionOwnership.COLLEAGUES_EXO_CALENDAR, listed(CAL2, ERIC));

    assertEquals("root", owner.username());
    assertEquals("root", owner.displayName());
  }

  // ------------------------------------ a share the server reported

  /**
   * Alice's default on Stalwart: the owner is named by the principal's
   * display name, read once, and by nothing else — no identity, no login.
   */
  @Test
  public void aServerReportedShareNamesTheOwnerByThePrincipalsDisplayName() {
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenReturn("Alice");

    CalendarOwner owner = ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE));

    assertNull(owner.identityId());
    assertNull(owner.username());
    assertEquals("Alice", owner.displayName());
    verify(caldavOutboundService, never()).exportingUserOf(anyLong(), anyString());
    verify(identityManager, never()).getIdentity(anyLong());
  }

  /**
   * A principal that states no display name is named by the last segment of
   * its path, decoded: {@code alice@stalwart.local}, not
   * {@code alice%40stalwart.local}.
   */
  @Test
  public void aPrincipalWithNoDisplayNameIsNamedByItsDecodedLastSegment() {
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenReturn(null);

    assertEquals("alice@stalwart.local", ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE)).displayName());
  }

  /**
   * A blank display name is no display name.
   */
  @Test
  public void aBlankDisplayNameFallsBackToThePathToo() {
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenReturn("  ");

    assertEquals("alice@stalwart.local", ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE)).displayName());
  }

  /**
   * A principal that cannot be asked — refused, unreachable, or anything
   * else — is named by its path, and the listing goes on.
   */
  @Test
  public void aPrincipalThatCannotBeAskedIsNamedByItsPathAndTheListingGoesOn() {
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenThrow(new CalDavUnreachableException("down", null));
    assertEquals("alice@stalwart.local", ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE)).displayName());

    // Re-stubbed with doThrow: a when(...) on a mock already stubbed to throw
    // would throw from inside the when.
    owners = new HashMap<>();
    doThrow(new CalDavException("403")).when(calDavClient).readDisplayName(endpoint, ALICE);
    assertEquals("alice@stalwart.local", ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE)).displayName());

    owners = new HashMap<>();
    doThrow(new IllegalStateException("unexpected")).when(calDavClient).readDisplayName(endpoint, ALICE);
    assertEquals("alice@stalwart.local", ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE)).displayName());
  }

  /**
   * One PROPFIND per distinct owner within a listing: Alice's three shares
   * ask her name once, and Carol's ask hers once — the memo is per
   * principal, not per listing.
   */
  @Test
  public void onePrincipalIsAskedOncePerListingHoweverManyCalendarsItOwns() {
    String carol = "/dav/pal/carol%40stalwart.local/";
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenReturn("Alice");
    when(calDavClient.readDisplayName(endpoint, carol)).thenReturn("Carol");

    ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE));
    ownerOf(BOB, CollectionOwnership.SHARED, listed("/dav/cal/alice%40stalwart.local/work/", ALICE));
    ownerOf(BOB, CollectionOwnership.SHARED, listed("/dav/cal/alice%40stalwart.local/home/", ALICE));
    CalendarOwner carols = ownerOf(BOB, CollectionOwnership.SHARED, listed("/dav/cal/carol%40stalwart.local/default/", carol));

    verify(calDavClient, times(1)).readDisplayName(endpoint, ALICE);
    verify(calDavClient, times(1)).readDisplayName(endpoint, carol);
    assertEquals("Carol", carols.displayName());
  }

  /**
   * A principal that failed once is not asked again for the next share it
   * owns: the fallback is memoised like a name.
   */
  @Test
  public void aPrincipalThatFailedOnceIsNotAskedAgainInTheSameListing() {
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenThrow(new CalDavException("refused"));

    ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE));
    ownerOf(BOB, CollectionOwnership.SHARED, listed("/dav/cal/alice%40stalwart.local/work/", ALICE));

    verify(calDavClient, times(1)).readDisplayName(endpoint, ALICE);
  }

  /**
   * A share the server reported by privilege alone — no owner named — has
   * nobody to name, and asks the server nothing.
   */
  @Test
  public void aShareWithNoOwnerPrincipalNamesNobody() {
    assertSame(CalendarOwner.NONE, ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, null)));
    verify(calDavClient, never()).readDisplayName(any(), anyString());
    verify(caldavConnectionIdentityService, never()).usersConnectedAs(anyLong(), any());
  }

  /**
   * A share by privilege alone whose owner is the user themself — a
   * calendar under their own home the server will not let them write —
   * names nobody: "shared by yourself" is the one wrong answer, and the
   * user's own principal is never asked its name. Compared as paths, so a
   * server spelling the two differently does not make the user a stranger.
   */
  @Test
  public void aShareWhoseOwnerIsTheUserThemselfNamesNobody() {
    assertSame(CalendarOwner.NONE, ownerOf(BOB, CollectionOwnership.SHARED, listed("/dav/cal/bob%40stalwart.local/locked/", BOB)));
    assertSame(CalendarOwner.NONE,
               ownerOf("/dav/pal/bob@stalwart.local",
                       CollectionOwnership.SHARED,
                       listed("/dav/cal/bob%40stalwart.local/locked/", BOB)),
               "the same principal, decoded and without its slash");
    verify(calDavClient, never()).readDisplayName(any(), anyString());
    verify(caldavConnectionIdentityService, never()).usersConnectedAs(anyLong(), any());
  }

  /**
   * When the server named no principal for the account, a named owner
   * cannot be told from the user themself, and nobody is named rather than
   * possibly the user.
   */
  @Test
  public void anOwnerCannotBeNamedWhenTheAccountsOwnPrincipalIsUnknown() {
    assertSame(CalendarOwner.NONE, ownerOf(null, CollectionOwnership.SHARED, listed(ALICES, ALICE)));
    verify(calDavClient, never()).readDisplayName(any(), anyString());
  }

  // ------------------------------------ a share whose owner principal an eXo user is connected as, EXO-90243

  /**
   * Alice's default on Stalwart, once alice is the one eXo user connected as
   * Alice's principal: bob sees the share owned by alice — identity, login
   * and full name — and her principal is not asked its name.
   */
  @Test
  public void aShareOwnedByThePrincipalOneUserIsConnectedAsNamesThatUser() {
    when(caldavConnectionIdentityService.usersConnectedAs(SERVER, ALICE)).thenReturn(List.of(ALICE_USER));
    when(caldavConnectionIdentityService.activeUsersWithoutIdentityOn(SERVER)).thenReturn(0L);
    when(identityManager.getIdentity(ALICE_USER)).thenReturn(user("5", "alice", "Alice Liddell"));

    CalendarOwner owner = ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE));

    assertEquals(ALICE_USER, owner.identityId());
    assertEquals("alice", owner.username());
    assertEquals("Alice Liddell", owner.displayName());
    verify(calDavClient, never()).readDisplayName(any(), anyString());
  }

  /**
   * The rig's shared login: alice and alice2 are both connected as Alice's
   * principal, nothing tells them apart, and the share is named by the
   * principal alone — nobody is guessed.
   */
  @Test
  public void aShareOwnedByAPrincipalSeveralUsersAreConnectedAsNamesThePrincipalAlone() {
    when(caldavConnectionIdentityService.usersConnectedAs(SERVER, ALICE)).thenReturn(List.of(ALICE_USER, ALICE2_USER));
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenReturn("Alice");

    CalendarOwner owner = ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE));

    assertNull(owner.identityId());
    assertNull(owner.username());
    assertEquals("Alice", owner.displayName());
    verify(identityManager, never()).getIdentity(anyLong());
  }

  /**
   * <b>One recorded user is not the only user until the server's users are
   * all recorded.</b> Just after the upgrade alice has had her first pass and
   * alice2, on the same login, has not: the table says one user is connected
   * as Alice's principal, and naming alice would tell bob the login is hers
   * alone. Nobody is named while a user synchronising with the server has no
   * recorded identity; the principal's own name stands.
   */
  @Test
  public void aShareIsNamedByThePrincipalAloneWhileAUserOfTheServerIsNotRecordedYet() {
    when(caldavConnectionIdentityService.usersConnectedAs(SERVER, ALICE)).thenReturn(List.of(ALICE_USER));
    when(caldavConnectionIdentityService.activeUsersWithoutIdentityOn(SERVER)).thenReturn(1L);
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenReturn("Alice");

    CalendarOwner owner = ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE));

    assertNull(owner.identityId());
    assertNull(owner.username());
    assertEquals("Alice", owner.displayName());
    verify(identityManager, never()).getIdentity(anyLong());
  }

  /**
   * <b>The veto is said, once per server.</b> It can last for good — an
   * account left on another server keeps active pairs here and is never
   * recorded — so a line at debug alone would switch the owner mapping off
   * for a whole server without anybody being told. Said at info with how many
   * users are missing, once per server per process however many listings and
   * principals meet it.
   */
  @Test
  public void aServerWhoseUsersAreNotAllRecordedIsSaidOnceAtInfo() {
    String carol = "/dav/pal/carol%40stalwart.local/";
    when(caldavConnectionIdentityService.usersConnectedAs(SERVER, ALICE)).thenReturn(List.of(ALICE_USER));
    when(caldavConnectionIdentityService.usersConnectedAs(SERVER, carol)).thenReturn(List.of(10L));
    when(caldavConnectionIdentityService.activeUsersWithoutIdentityOn(SERVER)).thenReturn(2L);
    java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> said;
    try (org.exoplatform.caldav.LogRecorder log = new org.exoplatform.caldav.LogRecorder(CaldavCalendarOwnerService.class)) {
      ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE));
      owners = new HashMap<>();
      ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE));
      ownerOf(BOB, CollectionOwnership.SHARED, listed("/dav/cal/carol%40stalwart.local/default/", carol));
      said = log.events()
                .stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.INFO)
                .toList();
    }

    assertEquals(1, said.size(), "once per server per process");
    org.junit.jupiter.api.Assertions.assertTrue(said.get(0).getFormattedMessage().startsWith("2 eXo users synchronising with CalDAV server " + SERVER),
                                                said.get(0).getFormattedMessage());
    verify(identityManager, never()).getIdentity(anyLong());
  }

  /**
   * The viewer is never named as the owner of a share they see, even when a
   * record says they are connected as the owner principal — such a record is
   * stale by construction, and the principal's name stands.
   */
  @Test
  public void theViewerIsNeverNamedAsTheOwnerOfAShare() {
    when(caldavConnectionIdentityService.usersConnectedAs(SERVER, ALICE)).thenReturn(List.of(VIEWER));
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenReturn("Alice");

    CalendarOwner owner = ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE));

    assertNull(owner.identityId());
    assertEquals("Alice", owner.displayName());
    verify(identityManager, never()).getIdentity(anyLong());
  }

  /**
   * A connected user the registry no longer knows, or knows as deleted, is not
   * shown: the principal's own name stands instead.
   */
  @Test
  public void aConnectedUserTheRegistryDoesNotShowFallsBackToThePrincipalsName() {
    when(caldavConnectionIdentityService.usersConnectedAs(SERVER, ALICE)).thenReturn(List.of(ALICE_USER));
    when(caldavConnectionIdentityService.activeUsersWithoutIdentityOn(SERVER)).thenReturn(0L);
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenReturn("Alice");
    when(identityManager.getIdentity(ALICE_USER)).thenReturn(null);

    assertEquals(CalendarOwner.named("Alice"), ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE)));

    owners = new HashMap<>();
    Identity gone = user("5", "alice", "Alice Liddell");
    gone.setDeleted(true);
    when(identityManager.getIdentity(ALICE_USER)).thenReturn(gone);
    assertEquals(CalendarOwner.named("Alice"), ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE)));
  }

  /**
   * A lookup that fails costs the listing the identity and nothing else.
   */
  @Test
  public void aConnectedUserLookupThatFailsFallsBackToThePrincipalsName() {
    when(caldavConnectionIdentityService.usersConnectedAs(SERVER, ALICE)).thenThrow(new IllegalStateException("database down"));
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenReturn("Alice");

    assertEquals(CalendarOwner.named("Alice"), ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE)));
  }

  /**
   * One lookup per owner principal per listing: Alice's three shares ask
   * who is connected as her once, and the registry once.
   */
  @Test
  public void theConnectedUserIsLookedUpOncePerPrincipalPerListing() {
    when(caldavConnectionIdentityService.usersConnectedAs(SERVER, ALICE)).thenReturn(List.of(ALICE_USER));
    when(caldavConnectionIdentityService.activeUsersWithoutIdentityOn(SERVER)).thenReturn(0L);
    when(identityManager.getIdentity(ALICE_USER)).thenReturn(user("5", "alice", "Alice Liddell"));

    ownerOf(BOB, CollectionOwnership.SHARED, listed(ALICES, ALICE));
    ownerOf(BOB, CollectionOwnership.SHARED, listed("/dav/cal/alice%40stalwart.local/work/", ALICE));
    CalendarOwner third = ownerOf(BOB, CollectionOwnership.SHARED, listed("/dav/cal/alice%40stalwart.local/home/", ALICE));

    assertEquals("alice", third.username());
    verify(caldavConnectionIdentityService, times(1)).usersConnectedAs(SERVER, ALICE);
    verify(identityManager, times(1)).getIdentity(ALICE_USER);
  }

  // ------------------------------------ the user's own

  /**
   * The user's own calendars name nobody and cost nothing: not a query, not
   * a PROPFIND.
   */
  @Test
  public void theUsersOwnCalendarsNameNobodyAndAskNobody() {
    assertSame(CalendarOwner.NONE, ownerOf(BOB, CollectionOwnership.OWN, listed(ALICES, ALICE)));
    assertSame(CalendarOwner.NONE, ownerOf(ERIC, CollectionOwnership.OWN_EXO_CALENDAR, listed(CAL2, ERIC)));

    verify(calDavClient, never()).readDisplayName(any(), anyString());
    verify(caldavOutboundService, never()).exportingUserOf(anyLong(), anyString());
    verify(identityManager, never()).getIdentity(anyLong());
    verify(caldavConnectionIdentityService, never()).usersConnectedAs(anyLong(), any());
  }

  /**
   * @param principal the account's own principal, as the discovery walk
   *          answered it
   * @param ownership whose the collection is
   * @param collection the listed collection
   * @return the owner, resolved with this test's memo
   */
  private CalendarOwner ownerOf(String principal, CollectionOwnership ownership, CalendarCollection collection) {
    return service.ownerOf(VIEWER, SERVER, endpoint, principal, ownership, collection, owners);
  }

  /**
   * @param href the collection path
   * @param owner the owner the server named, or null
   * @return a listed, read-only calendar with that owner
   */
  private CalendarCollection listed(String href, String owner) {
    return new CalendarCollection(href, "listed", null, null, null, false, Set.of("VEVENT"), owner, true);
  }

  /**
   * @param id the identity id
   * @param login the eXo login
   * @param fullName the profile's full name
   * @return a user identity
   */
  private Identity user(String id, String login, String fullName) {
    Identity identity = new Identity(id);
    identity.setRemoteId(login);
    Profile profile = new Profile(identity);
    profile.setProperty(Profile.FULL_NAME, fullName);
    identity.setProfile(profile);
    return identity;
  }
}
