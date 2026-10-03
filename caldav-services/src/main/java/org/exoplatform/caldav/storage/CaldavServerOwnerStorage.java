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

import java.io.Serializable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.bluemind.BlueMindCalendarOwners;
import org.exoplatform.caldav.client.bluemind.BlueMindSubscriptionClient;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The owner of every calendar an account sees, as BlueMind's subscription
 * listing reports it, kept across passes (EXO-90347).
 *
 * <p>
 * <b>Why a cache, and why here.</b> The listing costs a REST login, a GET
 * and a logout of the user's own BlueMind session. Read only when a pass
 * meets an eXo-shaped collection nobody here recognises, that is still
 * every sweep — every five minutes, on every node — for every account that
 * holds one such calendar, again each time the user opens their agenda,
 * and once more when the hidden-calendars drawer describes a bound
 * eXo-shaped calendar ({@code CaldavReadService#describeCollections}),
 * where the listing is what tells a calendar adopted before EXO-90347 —
 * a colleague's, and kept — from the user's own. Owners almost never
 * change, so the answer is kept for
 * {@code meeds.cache.caldav.server.owners.ttl} seconds (one hour by
 * default, {@code caldav.properties}) and at most
 * {@code meeds.cache.caldav.server.owners.max} accounts, through the
 * platform's Spring cache adapter over the Kernel's {@code ExoCache} — the
 * storage-like layer caching belongs to, tunable per deployment, and a
 * single-flight load under {@link Cacheable#sync()}: concurrent misses on
 * one account open one session, not two.
 *
 * <p>
 * <b>The key is the account and nothing less.</b> A listing says which
 * calendars one person's mailbox can see; served to another person it is
 * a leak. So the key carries the two discriminators the answer depends on
 * — the declared server and the eXo user whose stored credentials opened
 * the session — as explicit, {@code equals}-compared fields ({@link Key}),
 * never a folded hash: two users on one server, and one user on two
 * servers, hold separate entries by construction. The principal uid the
 * pass discovered is not part of the key, because it is derived from the
 * same credentials; the listing carries the uid the session was
 * authenticated as, and a reader that finds it differing from the pass's
 * principal — credentials re-pointed at another mailbox — asks for a
 * refresh rather than believing the entry.
 *
 * <p>
 * <b>What is not cached.</b> A fetch that throws caches nothing: Spring
 * writes no entry for a loader that failed, so an unreachable or refusing
 * server costs one attempt per pass, as it did before the cache, and the
 * next pass tries again. A listing that does not name a collection is
 * cached as it came; the reader's one forced {@link #refresh} per pass is
 * what keeps a share made after the entry was written from waiting out the
 * TTL.
 *
 * <p>
 * <b>Node-local.</b> Each node of a cluster holds its own entries and
 * expires them on its own clock; an owner change made outside eXo — a
 * share granted in BlueMind's webmail — is seen by a node within the TTL at
 * the latest, and sooner on the node whose pass meets the new collection,
 * since that is a miss it refreshes on. A change eXo itself makes is
 * evicted at once on the node that made it ({@link #evict}), and reaches
 * the others within the TTL.
 */
@Component
public class CaldavServerOwnerStorage {

  /** The cache, and the {@code meeds.cache.<name>.*} tuning prefix. */
  public static final String         CACHE_NAME = "caldav.server.owners";

  private static final Log           LOG        = ExoLogger.getLogger(CaldavServerOwnerStorage.class);

  @Autowired
  private BlueMindSubscriptionClient blueMindSubscriptionClient;

  /**
   * The account a listing belongs to: the declared server and the eXo user
   * whose stored credentials open the session. Both fields compared, none
   * folded.
   *
   * @param serverId the declared server registration, zero for the legacy
   *          property
   * @param userIdentityId the social identity of the connected user
   */
  public record Key(long serverId, long userIdentityId) implements Serializable {
  }

  /**
   * The account's listing, from the cache when it holds one, else read from
   * the server and kept.
   *
   * @param key the account
   * @param endpoint the account's DAV endpoint, minted from the registry for
   *          the user's eXo login — the session is opened with its
   *          credentials; not part of the key, which the two identifiers
   *          already determine
   * @return the listing
   * @throws UnsupportedOperationException when the account's configured
   *           credentials are not a login and password; nothing is cached
   * @throws org.exoplatform.caldav.client.CalDavException when the server
   *           refuses, cannot be reached, or answers the wrong shape;
   *           nothing is cached
   */
  @Cacheable(cacheNames = CACHE_NAME, key = "#p0", sync = true)
  public BlueMindCalendarOwners listing(Key key, CalDavEndpoint endpoint) {
    LOG.debug("Reading the calendar owners of CalDAV account {} from the server; the cache held no entry", key);
    return blueMindSubscriptionClient.ownersOf(endpoint);
  }

  /**
   * The account's listing read from the server now, replacing whatever the
   * cache held: a pass that meets a collection the cached listing does not
   * name asks this once, so a share made after the entry was written is
   * classified in the pass that first sees it rather than after the TTL.
   *
   * @param key the account
   * @param endpoint the account's DAV endpoint
   * @return the fresh listing, now cached
   * @throws UnsupportedOperationException as {@link #listing}; the stale
   *           entry is left as it was
   * @throws org.exoplatform.caldav.client.CalDavException as
   *           {@link #listing}; the stale entry is left as it was
   */
  @CachePut(cacheNames = CACHE_NAME, key = "#p0")
  public BlueMindCalendarOwners refresh(Key key, CalDavEndpoint endpoint) {
    LOG.debug("Re-reading the calendar owners of CalDAV account {} from the server; the cached listing did not settle a collection", key);
    return blueMindSubscriptionClient.ownersOf(endpoint);
  }

  /**
   * Drops the account's entry: eXo itself changed what the account can see
   * — a share it granted or revoked, a subscription it followed — or the
   * account itself changed hands, on connect, disconnect or new
   * credentials. A no-op when there is no entry.
   *
   * @param key the account
   */
  @CacheEvict(cacheNames = CACHE_NAME, key = "#p0")
  public void evict(Key key) {
    LOG.debug("The cached calendar owners of CalDAV account {} are dropped", key);
  }
}
