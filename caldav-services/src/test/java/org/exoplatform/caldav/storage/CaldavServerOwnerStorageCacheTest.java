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
package org.exoplatform.caldav.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.bluemind.BlueMindCalendarOwners;
import org.exoplatform.caldav.client.bluemind.BlueMindSubscriptionClient;
import org.exoplatform.caldav.service.AccountCalendarOwners;
import org.exoplatform.caldav.service.AccountCalendarOwners.Word;
import org.exoplatform.caldav.service.CaldavServerOwnerService;
import org.exoplatform.caldav.storage.CaldavServerOwnerStorage.Key;

/**
 * The owner listing's cache, against a real cache manager (EXO-90347).
 *
 * <p>
 * Mocks cannot see a cache: a {@code @Cacheable} whose key drifted, or
 * whose annotation went missing, answers a mock exactly as before. So the
 * storage runs here under Spring's cache proxy over a
 * {@link ConcurrentMapCacheManager}, with the BlueMind client the only
 * mock — the shape the org's cached-bean tests take. The TTL and the size
 * bound are the platform adapter's ({@code meeds.cache.caldav.server.owners.ttl}
 * / {@code .max}), read at cache creation; a map cache has neither, so
 * they are not exercised here and this suite does not claim them.
 */
public class CaldavServerOwnerStorageCacheTest {

  private static final long             SERVER    = 2L;

  private static final long             ROOT      = 1L;

  private static final long             ERIC      = 8L;

  private static final String           ROOT_UID  = "751E6D1A-7FDB-49B2-B668-B569E9A5A42D";

  private static final String           ERIC_UID  = "4C60FEDD-0562-4903-A524-E95E1CCBCDE0";

  private static final String           ROOT_PRINCIPAL = "/dav/principals/__uids__/" + ROOT_UID + "/";

  private static final String           PERSO     = "exo-cal-fd3fe75f-58f9-49e5-93d0-85f63b24a807";

  private static final String           PERSONNEL = "exo-cal-5c7e51bf-d8c5-47ef-bc00-e976249331bc";

  private AnnotationConfigApplicationContext context;

  private BlueMindSubscriptionClient    client;

  private CaldavServerOwnerStorage      storage;

  private CaldavServerOwnerService      service;

  private CalDavEndpoint                rootOnBlueMind;

  private CalDavEndpoint                ericOnBlueMind;

  private CalDavEndpoint                rootOnStalwart;

  /**
   * A cached storage over a map cache, with the client mocked.
   */
  @Configuration
  @EnableCaching
  static class CacheConfiguration {

    /**
     * @return a map-backed manager holding the one cache
     */
    @Bean
    CacheManager cacheManager() {
      return new ConcurrentMapCacheManager(CaldavServerOwnerStorage.CACHE_NAME);
    }

    /**
     * @return the client, a mock the tests script
     */
    @Bean
    BlueMindSubscriptionClient blueMindSubscriptionClient() {
      return mock(BlueMindSubscriptionClient.class);
    }

    /**
     * @return the storage under the cache proxy
     */
    @Bean
    CaldavServerOwnerStorage caldavServerOwnerStorage() {
      return new CaldavServerOwnerStorage();
    }

    /**
     * @return the service the sweep and the calendar list both use
     */
    @Bean
    CaldavServerOwnerService caldavServerOwnerService() {
      return new CaldavServerOwnerService();
    }
  }

  /**
   * Boots the context and mints three endpoints: root and eric on BlueMind
   * (server 2), root on another server (1).
   */
  @BeforeEach
  public void boot() {
    context = new AnnotationConfigApplicationContext(CacheConfiguration.class);
    client = context.getBean(BlueMindSubscriptionClient.class);
    storage = context.getBean(CaldavServerOwnerStorage.class);
    service = context.getBean(CaldavServerOwnerService.class);
    rootOnBlueMind = endpoint(SERVER, "root");
    ericOnBlueMind = endpoint(SERVER, "eric");
    rootOnStalwart = endpoint(1L, "root");
  }

  /**
   * Closes the context, cache included.
   */
  @AfterEach
  public void shutdown() {
    context.close();
  }

  /**
   * The second read of one account within the TTL costs no request: the
   * sweep's next pass, and the calendar list opened in between, are served
   * the same entry.
   */
  @Test
  public void theSecondPassWithinTheTtlMakesNoListingCall() {
    when(client.ownersOf(rootOnBlueMind)).thenReturn(rootsListing());

    BlueMindCalendarOwners first = storage.listing(new Key(SERVER, ROOT), rootOnBlueMind);
    BlueMindCalendarOwners second = storage.listing(new Key(SERVER, ROOT), rootOnBlueMind);

    assertSame(first, second, "the very entry, not a re-read");
    verify(client, times(1)).ownersOf(any());
  }

  /**
   * The read path reuses what the sweep cached: two witnesses of one
   * account, handed out as the sweep and the calendar list are, resolve
   * from one request.
   */
  @Test
  public void theReadPathReusesTheSweepsCachedMap() {
    when(client.ownersOf(rootOnBlueMind)).thenReturn(rootsListing());

    AccountCalendarOwners sweep = service.ownersOf(ROOT, rootOnBlueMind, ROOT_PRINCIPAL);
    AccountCalendarOwners list = service.ownersOf(ROOT, rootOnBlueMind, ROOT_PRINCIPAL);

    assertEquals(Word.ANOTHERS, sweep.ownerOf(PERSO).word());
    assertEquals(Word.ACCOUNTS_OWN, list.ownerOf(PERSONNEL).word());
    assertEquals(Word.ANOTHERS, list.ownerOf(PERSO).word());
    verify(client, times(1)).ownersOf(any());
  }

  /**
   * <b>Key isolation.</b> Two accounts on one server, and one user on two
   * servers, never share an entry: each is read on its own, and each is
   * served its own listing — what a mailbox can see is never served to
   * another.
   */
  @Test
  public void twoAccountsOnOneServerAndOneUserOnTwoServersNeverShareAnEntry() {
    when(client.ownersOf(rootOnBlueMind)).thenReturn(rootsListing());
    when(client.ownersOf(ericOnBlueMind)).thenReturn(new BlueMindCalendarOwners(ERIC_UID, Map.of(PERSO, ERIC_UID)));
    when(client.ownersOf(rootOnStalwart)).thenReturn(new BlueMindCalendarOwners("root-elsewhere", Map.of()));

    BlueMindCalendarOwners root = storage.listing(new Key(SERVER, ROOT), rootOnBlueMind);
    BlueMindCalendarOwners eric = storage.listing(new Key(SERVER, ERIC), ericOnBlueMind);
    BlueMindCalendarOwners rootElsewhere = storage.listing(new Key(1L, ROOT), rootOnStalwart);

    assertEquals(ROOT_UID, root.accountUid());
    assertEquals(ERIC_UID, eric.accountUid(), "eric is not served root's mailbox");
    assertEquals("root-elsewhere", rootElsewhere.accountUid(), "root's other server is not served this one's");
    assertNotEquals(new Key(SERVER, ROOT), new Key(SERVER, ERIC));
    assertNotEquals(new Key(SERVER, ROOT), new Key(1L, ROOT));
    assertEquals(new Key(SERVER, ROOT), new Key(SERVER, ROOT), "compared by its fields");
    verify(client, times(3)).ownersOf(any());
    // Served again from the cache, each its own.
    assertSame(eric, storage.listing(new Key(SERVER, ERIC), ericOnBlueMind));
    assertSame(root, storage.listing(new Key(SERVER, ROOT), rootOnBlueMind));
    verify(client, times(3)).ownersOf(any());
  }

  /**
   * A fetch that fails caches nothing: the next read asks the server again,
   * and is served the answer it then gives.
   */
  @Test
  public void aFailedFetchIsNotCached() {
    when(client.ownersOf(rootOnBlueMind)).thenThrow(new CalDavUnreachableException("down")).thenReturn(rootsListing());

    assertThrows(CalDavUnreachableException.class, () -> storage.listing(new Key(SERVER, ROOT), rootOnBlueMind));
    BlueMindCalendarOwners recovered = storage.listing(new Key(SERVER, ROOT), rootOnBlueMind);

    assertEquals(ROOT_UID, recovered.accountUid());
    verify(client, times(2)).ownersOf(any());
    assertSame(recovered, storage.listing(new Key(SERVER, ROOT), rootOnBlueMind), "the answer that came is what is kept");
  }

  /**
   * A refresh reads the server and replaces the entry; a failed refresh
   * leaves the entry as it was.
   */
  @Test
  public void aRefreshReplacesTheEntryAndAFailedOneLeavesIt() {
    BlueMindCalendarOwners fresher = new BlueMindCalendarOwners(ROOT_UID, Map.of(PERSO, ERIC_UID, PERSONNEL, ROOT_UID, "exo-cal-new", ERIC_UID));
    when(client.ownersOf(rootOnBlueMind)).thenReturn(rootsListing())
                                         .thenReturn(fresher)
                                         .thenThrow(new CalDavUnreachableException("down"));

    BlueMindCalendarOwners stale = storage.listing(new Key(SERVER, ROOT), rootOnBlueMind);
    BlueMindCalendarOwners refreshed = storage.refresh(new Key(SERVER, ROOT), rootOnBlueMind);

    assertSame(fresher, refreshed);
    assertSame(fresher, storage.listing(new Key(SERVER, ROOT), rootOnBlueMind), "the refresh replaced the entry");
    assertNotEquals(stale, refreshed);
    assertThrows(CalDavUnreachableException.class, () -> storage.refresh(new Key(SERVER, ROOT), rootOnBlueMind));
    assertSame(fresher, storage.listing(new Key(SERVER, ROOT), rootOnBlueMind), "a failed refresh left the entry");
    verify(client, times(3)).ownersOf(any());
  }

  /**
   * An eviction drops one account's entry and no other's.
   */
  @Test
  public void anEvictionDropsOneAccountsEntryAndNoOthers() {
    when(client.ownersOf(rootOnBlueMind)).thenReturn(rootsListing());
    when(client.ownersOf(ericOnBlueMind)).thenReturn(new BlueMindCalendarOwners(ERIC_UID, Map.of()));
    BlueMindCalendarOwners root = storage.listing(new Key(SERVER, ROOT), rootOnBlueMind);
    BlueMindCalendarOwners eric = storage.listing(new Key(SERVER, ERIC), ericOnBlueMind);

    service.evict(ROOT, SERVER);

    assertSame(eric, storage.listing(new Key(SERVER, ERIC), ericOnBlueMind), "eric's entry survives root's eviction");
    verify(client, times(2)).ownersOf(any());
    storage.listing(new Key(SERVER, ROOT), rootOnBlueMind);
    verify(client, times(3)).ownersOf(any());
    verify(client, times(2)).ownersOf(rootOnBlueMind);
    assertNotEquals(0, root.ownerByContainerUid().size());
  }

  /**
   * @return root's listing as captured on the rig: Personnel is root's,
   *         Perso is eric's
   */
  private static BlueMindCalendarOwners rootsListing() {
    return new BlueMindCalendarOwners(ROOT_UID, Map.of(PERSO, ERIC_UID, PERSONNEL, ROOT_UID));
  }

  /**
   * @param serverId the declared server
   * @param login the eXo login
   * @return an endpoint answering both, and equal only to itself
   */
  private static CalDavEndpoint endpoint(long serverId, String login) {
    CalDavEndpoint endpoint = mock(CalDavEndpoint.class);
    when(endpoint.getServerId()).thenReturn(serverId);
    when(endpoint.getExoLogin()).thenReturn(login);
    return endpoint;
  }
}
