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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

import org.exoplatform.caldav.LogRecorder;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.bluemind.BlueMindCalendarOwners;
import org.exoplatform.caldav.service.AccountCalendarOwners.Word;
import org.exoplatform.caldav.storage.CaldavServerOwnerStorage;
import org.exoplatform.caldav.storage.CaldavServerOwnerStorage.Key;

/**
 * The server's own word on who owns an account's calendars (EXO-90347):
 * asked of BlueMind alone, read from the storage's cache, re-read from the
 * server at most once per pass, and read as "unknown" whenever it cannot be
 * trusted.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavServerOwnerServiceTest {

  private static final long          SERVER         = 2L;

  private static final long          ROOT           = 1L;

  private static final Key           ROOTS_KEY      = new Key(SERVER, ROOT);

  private static final String        ROOT_UID       = "751E6D1A-7FDB-49B2-B668-B569E9A5A42D";

  private static final String        ERIC_UID       = "4C60FEDD-0562-4903-A524-E95E1CCBCDE0";

  private static final String        ROOT_PRINCIPAL = "/dav/principals/__uids__/" + ROOT_UID + "/";

  private static final String        PERSO          = "exo-cal-fd3fe75f-58f9-49e5-93d0-85f63b24a807";

  private static final String        PERSONNEL      = "exo-cal-5c7e51bf-d8c5-47ef-bc00-e976249331bc";

  /** A share made after root's listing was cached. */
  private static final String        NEW_SHARE      = "exo-cal-96f6f3c2-08cb-4109-84ba-730ac035a607";

  @Mock
  private CaldavServerOwnerStorage   caldavServerOwnerStorage;

  @Mock
  private CalDavEndpoint             endpoint;

  @InjectMocks
  private CaldavServerOwnerService   service;

  /**
   * An endpoint minted for root on server 2, which is what the key is made
   * of.
   */
  @BeforeEach
  public void mintAnEndpoint() {
    lenient().when(endpoint.getServerId()).thenReturn(SERVER);
    lenient().when(endpoint.getExoLogin()).thenReturn("root");
  }

  /**
   * A principal of any shape but BlueMind's is not asked: the witness is
   * silent, the storage is never touched, and the classification stays
   * what it was on that server.
   */
  @Test
  public void aServerThatIsNotBlueMindsIsNotAsked() {
    AccountCalendarOwners stalwart = service.ownersOf(ROOT, endpoint, "/dav/pal/alice%40stalwart.local/");
    AccountCalendarOwners none = service.ownersOf(ROOT, endpoint, null);

    assertEquals(Word.SILENT, stalwart.ownerOf(PERSO).word());
    assertEquals(Word.SILENT, none.ownerOf(PERSO).word());
    verifyNoInteractions(caldavServerOwnerStorage);
  }

  /**
   * The listing is read from the storage on the first question and once
   * only, however many calendars are asked about; before any question
   * nothing is read. Owners compare against the uid the session was opened
   * as, ignoring case.
   */
  @Test
  public void theListingIsReadOnTheFirstQuestionAndOnceOnly() {
    when(caldavServerOwnerStorage.listing(ROOTS_KEY, endpoint)).thenReturn(new BlueMindCalendarOwners(ROOT_UID,
                                                                                                        Map.of(PERSO,
                                                                                                               ERIC_UID,
                                                                                                               PERSONNEL,
                                                                                                               ROOT_UID.toLowerCase())));

    AccountCalendarOwners owners = service.ownersOf(ROOT, endpoint, ROOT_PRINCIPAL);
    verifyNoInteractions(caldavServerOwnerStorage);

    assertEquals(new AccountCalendarOwners.Verdict(Word.ANOTHERS, ERIC_UID), owners.ownerOf(PERSO));
    assertEquals(Word.ACCOUNTS_OWN, owners.ownerOf(PERSONNEL).word(), "compared ignoring case, as the naming does");
    assertEquals(Word.UNKNOWN, owners.ownerOf(null).word());
    verify(caldavServerOwnerStorage, times(1)).listing(ROOTS_KEY, endpoint);
    verify(caldavServerOwnerStorage, never()).refresh(any(), any());
  }

  /**
   * <b>A miss does not wait out the TTL.</b> A calendar the cached listing
   * does not name — a share made after the entry was written — makes the
   * pass read the server once, and the calendar is then classified from
   * the fresh listing; a second such calendar in the same pass costs no
   * second read, and neither does asking about the first again.
   */
  @Test
  public void aCalendarAbsentFromTheCachedListingTriggersExactlyOneRefreshInThePass() {
    when(caldavServerOwnerStorage.listing(ROOTS_KEY, endpoint)).thenReturn(new BlueMindCalendarOwners(ROOT_UID, Map.of(PERSONNEL, ROOT_UID)));
    when(caldavServerOwnerStorage.refresh(ROOTS_KEY, endpoint)).thenReturn(new BlueMindCalendarOwners(ROOT_UID,
                                                                                                        Map.of(PERSONNEL,
                                                                                                               ROOT_UID,
                                                                                                               NEW_SHARE,
                                                                                                               ERIC_UID)));

    AccountCalendarOwners owners = service.ownersOf(ROOT, endpoint, ROOT_PRINCIPAL);

    assertEquals(Word.ACCOUNTS_OWN, owners.ownerOf(PERSONNEL).word(), "settled by the cached listing, no refresh");
    verify(caldavServerOwnerStorage, never()).refresh(any(), any());
    assertEquals(new AccountCalendarOwners.Verdict(Word.ANOTHERS, ERIC_UID), owners.ownerOf(NEW_SHARE), "classified from the fresh listing");
    assertEquals(Word.UNKNOWN, owners.ownerOf("exo-cal-still-unknown").word(), "a second absent calendar costs no second read");
    assertEquals(Word.ANOTHERS, owners.ownerOf(NEW_SHARE).word());
    verify(caldavServerOwnerStorage, times(1)).refresh(ROOTS_KEY, endpoint);
    verify(caldavServerOwnerStorage, times(1)).listing(ROOTS_KEY, endpoint);
  }

  /**
   * A refresh the server does not answer leaves the listing in hand: the
   * calendars it names stay classified, the absent one stays unknown, and
   * nothing is asked again in the pass.
   */
  @Test
  public void aRefreshTheServerDoesNotAnswerLeavesTheListingInHand() {
    when(caldavServerOwnerStorage.listing(ROOTS_KEY, endpoint)).thenReturn(new BlueMindCalendarOwners(ROOT_UID, Map.of(PERSO, ERIC_UID)));
    when(caldavServerOwnerStorage.refresh(ROOTS_KEY, endpoint)).thenThrow(new CalDavUnreachableException("down"));

    AccountCalendarOwners owners = service.ownersOf(ROOT, endpoint, ROOT_PRINCIPAL);

    assertEquals(Word.UNKNOWN, owners.ownerOf(NEW_SHARE).word());
    assertEquals(Word.ANOTHERS, owners.ownerOf(PERSO).word(), "what the listing in hand names is still answered");
    assertEquals(Word.UNKNOWN, owners.ownerOf(NEW_SHARE).word());
    verify(caldavServerOwnerStorage, times(1)).refresh(ROOTS_KEY, endpoint);
  }

  /**
   * A listing that cannot be read is unknown for every calendar, is not
   * refreshed in the pass — one attempt per pass, as before the cache — and
   * is said once at warn; the next pass, with the same failure, says it at
   * debug and tries again.
   */
  @Test
  public void aListingThatFailsIsUnknownForThePassAndSaidOnce() {
    when(caldavServerOwnerStorage.listing(ROOTS_KEY, endpoint)).thenThrow(new CalDavUnreachableException("down"));

    List<ILoggingEvent> warned;
    try (LogRecorder log = new LogRecorder(CaldavServerOwnerService.class)) {
      AccountCalendarOwners first = service.ownersOf(ROOT, endpoint, ROOT_PRINCIPAL);
      assertEquals(Word.UNKNOWN, first.ownerOf(PERSO).word());
      assertEquals(Word.UNKNOWN, first.ownerOf(PERSONNEL).word());
      AccountCalendarOwners second = service.ownersOf(ROOT, endpoint, ROOT_PRINCIPAL);
      assertEquals(Word.UNKNOWN, second.ownerOf(PERSO).word());
      warned = log.events().stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    verify(caldavServerOwnerStorage, times(2)).listing(ROOTS_KEY, endpoint);
    verify(caldavServerOwnerStorage, never()).refresh(any(), any());
    assertEquals(1, warned.size(), "once per account per process");
    assertTrue(warned.get(0).getFormattedMessage().contains("Key[serverId=2, userIdentityId=1]"), warned.get(0).getFormattedMessage());
    assertTrue(warned.get(0).getFormattedMessage().contains("rather than adopted"), warned.get(0).getFormattedMessage());
  }

  /**
   * Credentials the REST API cannot take — anything but a login and
   * password — leave the listing unknown: nothing is adopted on a witness
   * that cannot be heard.
   */
  @Test
  public void credentialsTheRestApiCannotTakeLeaveTheListingUnknown() {
    when(caldavServerOwnerStorage.listing(ROOTS_KEY, endpoint)).thenThrow(new UnsupportedOperationException("not Basic"));

    assertEquals(Word.UNKNOWN, service.ownersOf(ROOT, endpoint, ROOT_PRINCIPAL).ownerOf(PERSO).word());
  }

  /**
   * The trust boundary, with a cache in front of it: an entry read as
   * another directory entry than the account's recorded principal —
   * credentials re-pointed since it was written — spends the pass's one
   * server read on a fresh listing, which is believed when it is the
   * account's own and refused when it still is not.
   */
  @Test
  public void anEntryOpenedAsAnotherEntryIsRefreshedOnceAndBelievedOnlyAsTheAccountsOwn() {
    when(caldavServerOwnerStorage.listing(ROOTS_KEY, endpoint)).thenReturn(new BlueMindCalendarOwners(ERIC_UID, Map.of(PERSO, ERIC_UID)));
    when(caldavServerOwnerStorage.refresh(ROOTS_KEY, endpoint)).thenReturn(new BlueMindCalendarOwners(ROOT_UID, Map.of(PERSO, ERIC_UID)))
                                                               .thenReturn(new BlueMindCalendarOwners(ERIC_UID, Map.of(PERSO, ERIC_UID)));

    AccountCalendarOwners repointed = service.ownersOf(ROOT, endpoint, ROOT_PRINCIPAL);
    assertEquals(Word.ANOTHERS, repointed.ownerOf(PERSO).word(), "the fresh listing is root's own and is believed");
    assertEquals(Word.UNKNOWN, repointed.ownerOf(NEW_SHARE).word(), "the pass's one read is spent");
    verify(caldavServerOwnerStorage, times(1)).refresh(ROOTS_KEY, endpoint);

    AccountCalendarOwners stillNotRoot = service.ownersOf(ROOT, endpoint, ROOT_PRINCIPAL);
    assertEquals(Word.UNKNOWN, stillNotRoot.ownerOf(PERSO).word());
    assertNull(stillNotRoot.ownerOf(PERSO).ownerUid());
    verify(caldavServerOwnerStorage, times(2)).refresh(ROOTS_KEY, endpoint);
  }

  /**
   * An eviction names the account by the same key the cache is read under.
   */
  @Test
  public void anEvictionNamesTheAccountByItsKey() {
    service.evict(ROOT, SERVER);
    service.evict(ROOT, 0L);

    verify(caldavServerOwnerStorage).evict(ROOTS_KEY);
    verify(caldavServerOwnerStorage).evict(new Key(0L, ROOT));
  }

  /**
   * A fetch that throws inside the witness itself is read as unavailable
   * rather than ending the pass, whatever the caller did about it.
   */
  @Test
  public void aWitnessWhoseFetchThrowsIsUnavailable() {
    AccountCalendarOwners owners = AccountCalendarOwners.deferred(() -> {
      throw new IllegalStateException("boom");
    });

    assertEquals(Word.UNKNOWN, owners.ownerOf(PERSO).word());
    assertEquals(Word.UNKNOWN, AccountCalendarOwners.of(" ", Map.of(PERSO, ERIC_UID)).ownerOf(PERSO).word(),
                 "a listing read as nobody compares against nobody");
  }
}
