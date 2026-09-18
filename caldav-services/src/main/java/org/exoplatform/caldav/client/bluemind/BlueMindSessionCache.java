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
package org.exoplatform.caldav.client.bluemind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.exoplatform.caldav.client.bluemind.BlueMindRestSession.Login;

/**
 * The BlueMind REST session each account currently holds, kept in this node's
 * own memory for a few minutes so that a burst of reads costs one login
 * rather than one login per read (EXO-90397).
 *
 * <p>
 * <b>Why a session is kept at all.</b> {@link BlueMindRestSession#call} used
 * to log in, make its one call and log out — three requests for one answer,
 * measured at 0.2 to 0.4 s each against the demo rig. Every BlueMind REST
 * read this add-on makes paid it: the Share drawer's access list, the
 * subscription listing behind the calendar-owner witness, the ICS import
 * channel. With the technical-account provider it is worse than three,
 * because producing the account's credentials is itself two BlueMind calls
 * — the technical login, then the sudo — and that provider has no cache yet
 * (EXO-89647), so the login removed here also removes the credential
 * production that precedes it.
 *
 * <p>
 * <b>Why here, and not in a store.</b> A session key is a live credential
 * that acts <i>as a user</i>, with a lifetime in minutes.
 * <ul>
 * <li><b>Not the database.</b> Persisting it would mean encrypting it under
 * the instance codec key, migrating it and cleaning it up, for a value that
 * expires long before any of that matters.</li>
 * <li><b>Not the platform's cache layer.</b> Anything registered with the
 * Kernel's {@code CacheService} is reachable by name from any other code in
 * the container, and a cache is a thing a deployment may one day replicate.
 * A credential that authenticates one person's mailbox must not travel
 * between nodes, so it lives in a field of this bean and nowhere else.</li>
 * <li><b>Node-local.</b> Each node mints its own session per account, which
 * costs one login; losing them all on a restart costs one login each. That
 * is the whole price of not replicating a credential.</li>
 * </ul>
 *
 * <p>
 * <b>The key is who the session acts as, field by field.</b> Handed to
 * another account, a session key is not a stale answer — it is somebody
 * else's identity. So the key carries three explicit, {@code equals}-compared
 * fields and never a folded hash ({@link Key}): the declared server, the eXo
 * user the credentials are produced for, and the account those credentials
 * address on that server. The third is what the technical-account provider
 * resolves before it sudoes
 * ({@code BluemindSudoCredentialsProvider#resolveTargetIdentity}, the same
 * derivation {@code produce} itself uses), so the key says who the session
 * <i>acts as</i> and not merely who asked for it. Two users cannot land on
 * one entry, and one user cannot land on an entry minted for another
 * mailbox.
 *
 * <p>
 * <b>Bounded in both directions.</b> An entry lives
 * {@code exo.agenda.caldav.bluemind.session.ttlSeconds} seconds and there are
 * at most {@code exo.agenda.caldav.bluemind.session.maxAccounts} of them;
 * beyond that the session is used but not kept, so the map cannot grow with
 * the user base of an instance nobody sized it for.
 */
@Component
public class BlueMindSessionCache {

  /** Default entry lifetime, in seconds. */
  static final int                      DEFAULT_TTL_SECONDS  = 300;

  /** Default number of accounts kept per node. */
  static final int                      DEFAULT_MAX_ACCOUNTS = 1000;

  private final Map<Key, Entry>         entries              = new ConcurrentHashMap<>();

  private final long                    ttlMillis;

  private final int                     maxAccounts;

  /** Where "now" comes from — a seam, so expiry is tested without waiting. */
  private final LongSupplier            clock;

  /**
   * The account a session belongs to. Three fields, all compared, none
   * folded into a hash.
   *
   * @param serverId the declared server registration, zero for the legacy
   *          deployment property
   * @param exoLogin the eXo login whose credentials opened the session
   * @param actsAs the account those credentials address on that server — the
   *          user's own for the Personal provider, the impersonated target
   *          for the technical-account one; null when the configured provider
   *          names none
   */
  public record Key(long serverId, String exoLogin, String actsAs) {
  }

  /**
   * One kept session and the moment it stops being kept.
   *
   * @param login the session
   * @param expiresAt epoch millis after which the entry is gone
   */
  private record Entry(Login login, long expiresAt) {
  }

  /**
   * The cache Spring builds.
   *
   * @param ttlSeconds how long an entry is kept
   * @param maxAccounts how many accounts are kept at once
   */
  @Autowired
  public BlueMindSessionCache(@Value("${exo.agenda.caldav.bluemind.session.ttlSeconds:" + DEFAULT_TTL_SECONDS + "}")
  int ttlSeconds, @Value("${exo.agenda.caldav.bluemind.session.maxAccounts:" + DEFAULT_MAX_ACCOUNTS + "}") int maxAccounts) {
    this(ttlSeconds, maxAccounts, System::currentTimeMillis);
  }

  /**
   * The seam the tests use: a cache whose clock they hold.
   *
   * @param ttlSeconds how long an entry is kept
   * @param maxAccounts how many accounts are kept at once
   * @param clock where "now" comes from, in epoch millis
   */
  BlueMindSessionCache(int ttlSeconds, int maxAccounts, LongSupplier clock) {
    this.ttlMillis = Math.max(0, ttlSeconds) * 1000L;
    this.maxAccounts = Math.max(0, maxAccounts);
    this.clock = clock;
  }

  /**
   * A cache that keeps nothing: every call opens its own session and closes
   * it, which is what this add-on did before EXO-90397 and what it still does
   * for an endpoint that cannot be keyed.
   *
   * @return a cache of no capacity
   */
  public static BlueMindSessionCache unpooled() {
    return new BlueMindSessionCache(0, 0, System::currentTimeMillis);
  }

  /**
   * Whether this cache keeps anything at all.
   *
   * @return true when it has both a lifetime and room
   */
  boolean keeps() {
    return ttlMillis > 0 && maxAccounts > 0;
  }

  /**
   * The session kept for an account, if one is and it has not expired. An
   * expired entry is dropped here rather than answered: its key is one
   * BlueMind may already have forgotten, and the caller opens another.
   *
   * @param key the account
   * @return the session, or null when nothing usable is kept
   */
  Login held(Key key) {
    Entry entry = entries.get(key);
    if (entry == null) {
      return null;
    }
    if (entry.expiresAt() <= clock.getAsLong()) {
      entries.remove(key, entry);
      return null;
    }
    return entry.login();
  }

  /**
   * Keeps a session for an account, unless one is already kept — in which
   * case the one already there is answered and the caller decides what to do
   * with the one it minted. Two threads that missed together therefore end up
   * on one session rather than silently leaking the loser's.
   *
   * @param key the account
   * @param session the freshly minted session
   * @return the session already kept, or null when this one is now the entry
   *         (or when there was no room for it)
   */
  Login keep(Key key, Login session) {
    if (!keeps()) {
      return null;
    }
    Entry fresh = new Entry(session, clock.getAsLong() + ttlMillis);
    Entry existing = entries.putIfAbsent(key, fresh);
    if (existing != null && existing.expiresAt() > clock.getAsLong()) {
      return existing.login();
    }
    if (existing != null) {
      // Expired under us: this session takes its place.
      entries.put(key, fresh);
    }
    bound(key);
    return null;
  }

  /**
   * Makes a session the account's entry whatever was there.
   *
   * @param key the account
   * @param session the session to keep
   */
  void replace(Key key, Login session) {
    if (!keeps()) {
      return;
    }
    entries.put(key, new Entry(session, clock.getAsLong() + ttlMillis));
    bound(key);
  }

  /**
   * Drops and answers every session kept for one eXo user on one server —
   * every account those credentials have addressed there — so the caller can
   * close them.
   *
   * @param serverId the declared server registration, zero for the legacy
   *          deployment property
   * @param exoLogin the eXo login
   * @return the sessions that were kept, in no particular order
   */
  List<Login> forget(long serverId, String exoLogin) {
    List<Login> dropped = new ArrayList<>();
    entries.entrySet().removeIf(entry -> {
      if (entry.getKey().serverId() == serverId && Objects.equals(entry.getKey().exoLogin(), exoLogin)) {
        dropped.add(entry.getValue().login());
        return true;
      }
      return false;
    });
    return dropped;
  }

  /**
   * Drops every entry: the declared servers changed under the sessions, so
   * none of them describes a registration that still stands.
   *
   * <p>
   * The sessions are not answered and so not closed — an administrator's save
   * must not turn into a burst of logout requests, one per account of the
   * instance — and they expire on BlueMind's own clock instead.
   */
  void forgetAll() {
    entries.clear();
  }

  /**
   * How many accounts are kept right now, expired entries included.
   *
   * @return the number of entries
   */
  int size() {
    return entries.size();
  }

  /**
   * Keeps the map inside its bound: expired entries first, and if that is not
   * enough the one just added goes rather than any other — a session in use
   * is worth more than one that has not been asked for yet, and dropping the
   * newcomer costs its owner nothing but the login it has already made.
   *
   * @param added the key just written, the one given up when nothing expired
   */
  private void bound(Key added) {
    if (entries.size() <= maxAccounts) {
      return;
    }
    long now = clock.getAsLong();
    entries.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    if (entries.size() > maxAccounts) {
      entries.remove(added);
    }
  }
}
