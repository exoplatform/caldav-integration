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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
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

  /** Eric's own principal, which BlueMind names as CAL2's owner. */
  private static final String        ERIC        = "/dav/principals/eric/";

  /** Alice's principal as Stalwart spells it, login percent-encoded. */
  private static final String        ALICE       = "/dav/pal/alice%40stalwart.local/";

  private static final String        ALICES      = "/dav/cal/alice%40stalwart.local/default/";

  @Mock
  private CaldavOutboundService      caldavOutboundService;

  @Mock
  private IdentityManager            identityManager;

  @Mock
  private CalDavClient               calDavClient;

  @Mock
  private CalDavEndpoint             endpoint;

  private CaldavCalendarOwnerService service;

  private Map<String, String>        principalNames;

  @BeforeEach
  public void build() {
    service = new CaldavCalendarOwnerService(caldavOutboundService, identityManager, calDavClient);
    principalNames = new HashMap<>();
  }

  // ------------------------------------ a colleague's eXo calendar

  /**
   * CAL2: the owner is root, named by identity, login and full name, from
   * the pair this deployment holds — and the server, which names eric as
   * owner, is not asked.
   */
  @Test
  public void aColleaguesExoCalendarNamesTheColleagueFromThePairNotFromTheServer() {
    when(caldavOutboundService.exportingUserOf(SERVER, CAL2)).thenReturn(ROOT);
    when(identityManager.getIdentity("1")).thenReturn(user("1", "root", "Root Root"));

    CalendarOwner owner = service.ownerOf(SERVER, endpoint, CollectionOwnership.COLLEAGUES_EXO_CALENDAR, listed(CAL2, ERIC), principalNames);

    assertEquals(ROOT, owner.identityId());
    assertEquals("root", owner.username());
    assertEquals("Root Root", owner.displayName());
    verify(calDavClient, never()).readDisplayName(any(), anyString());
  }

  /**
   * A user the registry no longer knows names nobody: the calendar stays a
   * share, with no person to show for it — and the server's owner is not a
   * fallback, since on BlueMind it is the viewer.
   */
  @Test
  public void aColleagueTheRegistryNoLongerKnowsNamesNobody() {
    when(caldavOutboundService.exportingUserOf(SERVER, CAL2)).thenReturn(ROOT);
    when(identityManager.getIdentity("1")).thenReturn(null);

    assertSame(CalendarOwner.NONE,
               service.ownerOf(SERVER, endpoint, CollectionOwnership.COLLEAGUES_EXO_CALENDAR, listed(CAL2, ERIC), principalNames));
    verify(calDavClient, never()).readDisplayName(any(), anyString());
  }

  /**
   * A deleted user names nobody either: their login must not be shown.
   */
  @Test
  public void aDeletedColleagueNamesNobody() {
    when(caldavOutboundService.exportingUserOf(SERVER, CAL2)).thenReturn(ROOT);
    Identity gone = user("1", "root", "Root Root");
    gone.setDeleted(true);
    when(identityManager.getIdentity("1")).thenReturn(gone);

    assertSame(CalendarOwner.NONE,
               service.ownerOf(SERVER, endpoint, CollectionOwnership.COLLEAGUES_EXO_CALENDAR, listed(CAL2, ERIC), principalNames));
  }

  /**
   * A pair that vanished between the classification and this question names
   * nobody, quietly.
   */
  @Test
  public void aPairThatVanishedNamesNobody() {
    when(caldavOutboundService.exportingUserOf(SERVER, CAL2)).thenReturn(null);

    assertSame(CalendarOwner.NONE,
               service.ownerOf(SERVER, endpoint, CollectionOwnership.COLLEAGUES_EXO_CALENDAR, listed(CAL2, ERIC), principalNames));
    verify(identityManager, never()).getIdentity(anyString());
  }

  /**
   * A user with no full name on their profile is still named, by their
   * login: better a login than a blank line under a share.
   */
  @Test
  public void aColleagueWithNoFullNameIsNamedByTheirLogin() {
    when(caldavOutboundService.exportingUserOf(SERVER, CAL2)).thenReturn(ROOT);
    when(identityManager.getIdentity("1")).thenReturn(user("1", "root", " "));

    CalendarOwner owner = service.ownerOf(SERVER, endpoint, CollectionOwnership.COLLEAGUES_EXO_CALENDAR, listed(CAL2, ERIC), principalNames);

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

    CalendarOwner owner = service.ownerOf(SERVER, endpoint, CollectionOwnership.SHARED, listed(ALICES, ALICE), principalNames);

    assertNull(owner.identityId());
    assertNull(owner.username());
    assertEquals("Alice", owner.displayName());
    verify(caldavOutboundService, never()).exportingUserOf(anyLong(), anyString());
    verify(identityManager, never()).getIdentity(anyString());
  }

  /**
   * A principal that states no display name is named by the last segment of
   * its path, decoded: {@code alice@stalwart.local}, not
   * {@code alice%40stalwart.local}.
   */
  @Test
  public void aPrincipalWithNoDisplayNameIsNamedByItsDecodedLastSegment() {
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenReturn(null);

    assertEquals("alice@stalwart.local",
                 service.ownerOf(SERVER, endpoint, CollectionOwnership.SHARED, listed(ALICES, ALICE), principalNames).displayName());
  }

  /**
   * A blank display name is no display name.
   */
  @Test
  public void aBlankDisplayNameFallsBackToThePathToo() {
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenReturn("  ");

    assertEquals("alice@stalwart.local",
                 service.ownerOf(SERVER, endpoint, CollectionOwnership.SHARED, listed(ALICES, ALICE), principalNames).displayName());
  }

  /**
   * A principal that cannot be asked — refused, unreachable, or anything
   * else — is named by its path, and the listing goes on.
   */
  @Test
  public void aPrincipalThatCannotBeAskedIsNamedByItsPathAndTheListingGoesOn() {
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenThrow(new CalDavUnreachableException("down", null));
    assertEquals("alice@stalwart.local",
                 service.ownerOf(SERVER, endpoint, CollectionOwnership.SHARED, listed(ALICES, ALICE), principalNames).displayName());

    // Re-stubbed with doThrow: a when(...) on a mock already stubbed to throw
    // would throw from inside the when.
    Map<String, String> fresh = new HashMap<>();
    doThrow(new CalDavException("403")).when(calDavClient).readDisplayName(endpoint, ALICE);
    assertEquals("alice@stalwart.local",
                 service.ownerOf(SERVER, endpoint, CollectionOwnership.SHARED, listed(ALICES, ALICE), fresh).displayName());

    Map<String, String> another = new HashMap<>();
    doThrow(new IllegalStateException("unexpected")).when(calDavClient).readDisplayName(endpoint, ALICE);
    assertEquals("alice@stalwart.local",
                 service.ownerOf(SERVER, endpoint, CollectionOwnership.SHARED, listed(ALICES, ALICE), another).displayName());
  }

  /**
   * One PROPFIND per distinct owner within a listing: Alice's three shares
   * ask her name once, and Bob's ask his once — the memo is per principal,
   * not per listing.
   */
  @Test
  public void onePrincipalIsAskedOncePerListingHoweverManyCalendarsItOwns() {
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenReturn("Alice");
    when(calDavClient.readDisplayName(endpoint, "/dav/pal/bob%40stalwart.local/")).thenReturn("Bob");

    service.ownerOf(SERVER, endpoint, CollectionOwnership.SHARED, listed(ALICES, ALICE), principalNames);
    service.ownerOf(SERVER, endpoint, CollectionOwnership.SHARED, listed("/dav/cal/alice%40stalwart.local/work/", ALICE), principalNames);
    service.ownerOf(SERVER, endpoint, CollectionOwnership.SHARED, listed("/dav/cal/alice%40stalwart.local/home/", ALICE), principalNames);
    CalendarOwner bob = service.ownerOf(SERVER,
                                        endpoint,
                                        CollectionOwnership.SHARED,
                                        listed("/dav/cal/bob%40stalwart.local/default/", "/dav/pal/bob%40stalwart.local/"),
                                        principalNames);

    verify(calDavClient, times(1)).readDisplayName(endpoint, ALICE);
    verify(calDavClient, times(1)).readDisplayName(endpoint, "/dav/pal/bob%40stalwart.local/");
    assertEquals("Bob", bob.displayName());
  }

  /**
   * A principal that failed once is not asked again for the next share it
   * owns: the fallback is memoised like a name.
   */
  @Test
  public void aPrincipalThatFailedOnceIsNotAskedAgainInTheSameListing() {
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenThrow(new CalDavException("refused"));

    service.ownerOf(SERVER, endpoint, CollectionOwnership.SHARED, listed(ALICES, ALICE), principalNames);
    service.ownerOf(SERVER, endpoint, CollectionOwnership.SHARED, listed("/dav/cal/alice%40stalwart.local/work/", ALICE), principalNames);

    verify(calDavClient, times(1)).readDisplayName(endpoint, ALICE);
  }

  /**
   * A share the server reported by privilege alone — no owner named — has
   * nobody to name, and asks the server nothing.
   */
  @Test
  public void aShareWithNoOwnerPrincipalNamesNobody() {
    assertSame(CalendarOwner.NONE,
               service.ownerOf(SERVER, endpoint, CollectionOwnership.SHARED, listed(ALICES, null), principalNames));
    verify(calDavClient, never()).readDisplayName(any(), anyString());
  }

  // ------------------------------------ the user's own

  /**
   * The user's own calendars name nobody and cost nothing: not a query, not
   * a PROPFIND.
   */
  @Test
  public void theUsersOwnCalendarsNameNobodyAndAskNobody() {
    assertSame(CalendarOwner.NONE, service.ownerOf(SERVER, endpoint, CollectionOwnership.OWN, listed(ALICES, ALICE), principalNames));
    assertSame(CalendarOwner.NONE,
               service.ownerOf(SERVER, endpoint, CollectionOwnership.OWN_EXO_CALENDAR, listed(CAL2, ERIC), principalNames));

    verify(calDavClient, never()).readDisplayName(any(), anyString());
    verify(caldavOutboundService, never()).exportingUserOf(anyLong(), anyString());
    verify(identityManager, never()).getIdentity(anyString());
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
