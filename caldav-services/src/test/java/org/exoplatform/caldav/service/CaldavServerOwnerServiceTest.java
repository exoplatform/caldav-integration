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
import org.exoplatform.caldav.client.bluemind.BlueMindSubscriptionClient;
import org.exoplatform.caldav.service.AccountCalendarOwners.Word;

/**
 * The server's own word on who owns an account's calendars (EXO-90347):
 * asked of BlueMind alone, once per pass at most, and read as "unknown"
 * whenever it cannot be trusted.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavServerOwnerServiceTest {

  private static final String        ROOT_UID       = "751E6D1A-7FDB-49B2-B668-B569E9A5A42D";

  private static final String        ERIC_UID       = "4C60FEDD-0562-4903-A524-E95E1CCBCDE0";

  private static final String        ROOT_PRINCIPAL = "/dav/principals/__uids__/" + ROOT_UID + "/";

  private static final String        PERSO          = "exo-cal-fd3fe75f-58f9-49e5-93d0-85f63b24a807";

  private static final String        PERSONNEL      = "exo-cal-5c7e51bf-d8c5-47ef-bc00-e976249331bc";

  @Mock
  private BlueMindSubscriptionClient blueMindSubscriptionClient;

  @Mock
  private CalDavEndpoint             endpoint;

  @InjectMocks
  private CaldavServerOwnerService   service;

  /**
   * An endpoint minted for root on server 2, which is what the said-once key
   * is made of.
   */
  @BeforeEach
  public void mintAnEndpoint() {
    lenient().when(endpoint.getServerId()).thenReturn(2L);
    lenient().when(endpoint.getExoLogin()).thenReturn("root");
  }

  /**
   * A principal of any shape but BlueMind's is not asked: the witness is
   * silent, the REST client is never touched, and the classification stays
   * what it was on that server.
   */
  @Test
  public void aServerThatIsNotBlueMindsIsNotAsked() {
    AccountCalendarOwners stalwart = service.ownersOf(endpoint, "/dav/pal/alice%40stalwart.local/");
    AccountCalendarOwners none = service.ownersOf(endpoint, null);

    assertEquals(Word.SILENT, stalwart.ownerOf(PERSO).word());
    assertEquals(Word.SILENT, none.ownerOf(PERSO).word());
    verifyNoInteractions(blueMindSubscriptionClient);
  }

  /**
   * The listing is fetched on the first question and once only, however
   * many calendars are asked about; before any question nothing is sent.
   * Owners compare against the uid the session was opened as.
   */
  @Test
  public void theListingIsFetchedOnTheFirstQuestionAndOnceOnly() {
    when(blueMindSubscriptionClient.ownersOf(endpoint)).thenReturn(new BlueMindCalendarOwners(ROOT_UID,
                                                                                               Map.of(PERSO,
                                                                                                      ERIC_UID,
                                                                                                      PERSONNEL,
                                                                                                      ROOT_UID.toLowerCase())));

    AccountCalendarOwners owners = service.ownersOf(endpoint, ROOT_PRINCIPAL);
    verifyNoInteractions(blueMindSubscriptionClient);

    assertEquals(new AccountCalendarOwners.Verdict(Word.ANOTHERS, ERIC_UID), owners.ownerOf(PERSO));
    assertEquals(Word.ACCOUNTS_OWN, owners.ownerOf(PERSONNEL).word(), "compared ignoring case, as the naming does");
    assertEquals(Word.UNKNOWN, owners.ownerOf("exo-cal-not-listed").word());
    assertEquals(Word.UNKNOWN, owners.ownerOf(null).word());
    verify(blueMindSubscriptionClient, times(1)).ownersOf(endpoint);
  }

  /**
   * A listing that cannot be read is unknown for every calendar, is not
   * fetched again within the pass, and is said once at warn — the second
   * pass with the same failure says it at debug.
   */
  @Test
  public void aListingThatFailsIsUnknownAndSaidOnce() {
    when(blueMindSubscriptionClient.ownersOf(endpoint)).thenThrow(new CalDavUnreachableException("down"));

    List<ILoggingEvent> warned;
    try (LogRecorder log = new LogRecorder(CaldavServerOwnerService.class)) {
      AccountCalendarOwners first = service.ownersOf(endpoint, ROOT_PRINCIPAL);
      assertEquals(Word.UNKNOWN, first.ownerOf(PERSO).word());
      assertEquals(Word.UNKNOWN, first.ownerOf(PERSONNEL).word());
      AccountCalendarOwners second = service.ownersOf(endpoint, ROOT_PRINCIPAL);
      assertEquals(Word.UNKNOWN, second.ownerOf(PERSO).word());
      warned = log.events().stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    verify(blueMindSubscriptionClient, times(2)).ownersOf(endpoint);
    assertEquals(1, warned.size(), "once per account per process");
    assertTrue(warned.get(0).getFormattedMessage().contains("2:root"), warned.get(0).getFormattedMessage());
    assertTrue(warned.get(0).getFormattedMessage().contains("rather than adopted"), warned.get(0).getFormattedMessage());
  }

  /**
   * Credentials the REST API cannot take — anything but a login and
   * password — leave the listing unknown: nothing is adopted on a witness
   * that cannot be heard.
   */
  @Test
  public void credentialsTheRestApiCannotTakeLeaveTheListingUnknown() {
    when(blueMindSubscriptionClient.ownersOf(endpoint)).thenThrow(new UnsupportedOperationException("not Basic"));

    assertEquals(Word.UNKNOWN, service.ownersOf(endpoint, ROOT_PRINCIPAL).ownerOf(PERSO).word());
  }

  /**
   * The trust boundary: a session BlueMind authenticated as an entry other
   * than the account's recorded principal lists somebody else's calendars,
   * and comparing owners against it would classify this account's calendars
   * by another account's view. Unknown, nothing adopted.
   */
  @Test
  public void aSessionOpenedAsAnotherEntryIsNotBelieved() {
    when(blueMindSubscriptionClient.ownersOf(endpoint)).thenReturn(new BlueMindCalendarOwners(ERIC_UID, Map.of(PERSO, ERIC_UID)));

    AccountCalendarOwners owners = service.ownersOf(endpoint, ROOT_PRINCIPAL);

    assertEquals(Word.UNKNOWN, owners.ownerOf(PERSO).word());
    assertNull(owners.ownerOf(PERSO).ownerUid());
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
