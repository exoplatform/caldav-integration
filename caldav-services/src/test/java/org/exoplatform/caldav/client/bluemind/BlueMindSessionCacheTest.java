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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.exoplatform.caldav.client.bluemind.BlueMindRestSession.Login;
import org.exoplatform.caldav.client.bluemind.BlueMindSessionCache.Key;

/**
 * The store on its own, and in particular the answers it gives when two
 * callers reach it at once.
 *
 * <p>
 * {@link BlueMindSessionReuseTest} drives the store through a real client and
 * a real cache, which is what pins the key and the lifetime. It cannot reach
 * the lost-race answers: a call only mints a session after {@code held}
 * returned nothing, so single-threaded the slot is always empty when
 * {@code keep} runs and every race branch is dead (review round 1 measured
 * this — zero executions across the whole module's suite). Those branches
 * decide <i>which</i> session a caller ends up holding and which one is left
 * open on BlueMind, so they are exercised here directly instead.
 */
class BlueMindSessionCacheTest {

  private static final long   SERVER = 1L;

  private static final String ROOT   = "https://bm.example.com:8443";

  private static final String MOVED  = "https://bm2.example.com:8443";

  private static final Key    KEY    = new Key(SERVER, "root", "root@bm.example.com");

  private long                now    = 1_700_000_000_000L;

  /**
   * A cache whose clock this test holds.
   *
   * @param ttlSeconds the entry lifetime
   * @param maxAccounts the bound
   * @return the cache
   */
  private BlueMindSessionCache cache(int ttlSeconds, int maxAccounts) {
    return new BlueMindSessionCache(ttlSeconds, maxAccounts, () -> now);
  }

  /**
   * A session at an address.
   *
   * @param apiRoot the REST root it was opened at
   * @param key the session key
   * @return the session
   */
  private static Login session(String apiRoot, String key) {
    return new Login(apiRoot, key, "uid-" + key, "bm.example.com");
  }

  /**
   * <b>The loser of a race is told who won.</b> Two callers missed together
   * and both logged in; the first to reach the store keeps its session, and
   * the second is answered with that one rather than silently overwriting it
   * — which is what lets the second close the session it opened for nothing
   * instead of leaving it open on BlueMind.
   */
  @Test
  void aSecondSessionForOneAccountIsToldWhichOneIsKept() {
    BlueMindSessionCache cache = cache(300, 10);
    Login first = session(ROOT, "key-1");
    Login second = session(ROOT, "key-2");

    assertNull(cache.keep(KEY, first), "the first one becomes the entry");
    assertSame(first, cache.keep(KEY, second), "the second is told the first is the entry");
    assertTrue(cache.holds(KEY, first));
    assertFalse(cache.holds(KEY, second), "the loser is not kept, so its opener closes it");
    assertEquals(1, cache.size());
  }

  /**
   * <b>A session that expired under a caller is replaced, not deferred to.</b>
   * The entry was still there when {@code putIfAbsent} looked, but its
   * lifetime had run out — answering it would hand back a key BlueMind may
   * already have forgotten, so the fresh session takes its place.
   */
  @Test
  void aSessionThatExpiredUnderTheCallerIsReplacedByTheFreshOne() {
    BlueMindSessionCache cache = cache(300, 10);
    Login stale = session(ROOT, "key-stale");
    Login fresh = session(ROOT, "key-fresh");
    cache.keep(KEY, stale);

    now += 301_000L;

    assertNull(cache.keep(KEY, fresh), "the expired entry is not answered; this session is now the entry");
    assertTrue(cache.holds(KEY, fresh));
    assertFalse(cache.holds(KEY, stale));
    assertEquals(1, cache.size());
  }

  /**
   * <b>Past the bound, nothing is kept — and the store says so.</b> The
   * newcomer is the one given up, so an entry already in use is never taken
   * from under its caller; and {@code holds} is what tells the caller that
   * the session it just opened is its own to close.
   */
  @Test
  void theNewcomerIsTheOneGivenUpWhenThereIsNoRoom() {
    BlueMindSessionCache cache = cache(300, 2);
    Login kept1 = session(ROOT, "key-1");
    Login kept2 = session(ROOT, "key-2");
    Login turnedAway = session(ROOT, "key-3");
    Key key1 = new Key(SERVER, "a", "a@bm.example.com");
    Key key2 = new Key(SERVER, "b", "b@bm.example.com");
    Key key3 = new Key(SERVER, "c", "c@bm.example.com");
    cache.keep(key1, kept1);
    cache.keep(key2, kept2);

    assertNull(cache.keep(key3, turnedAway), "no winner is named: there simply was no room");
    assertFalse(cache.holds(key3, turnedAway), "and the store did not keep it, which is how its opener knows to close it");
    assertTrue(cache.holds(key1, kept1), "the accounts already kept are left alone");
    assertTrue(cache.holds(key2, kept2));
    assertEquals(2, cache.size());
  }

  /**
   * <b>Dropping one session drops that session.</b> The narrow counterpart of
   * {@code forget}: it leaves the other accounts this user's credentials
   * address on the same server alone, because those sessions were not the one
   * BlueMind refused.
   */
  @Test
  void droppingOneSessionLeavesTheUsersOtherAccountsAlone() {
    BlueMindSessionCache cache = cache(300, 10);
    Key sameUserElsewhere = new Key(SERVER, "root", "root.other@bm.example.com");
    Login refused = session(ROOT, "key-refused");
    Login sibling = session(ROOT, "key-sibling");
    cache.keep(KEY, refused);
    cache.keep(sameUserElsewhere, sibling);

    assertTrue(cache.drop(KEY, refused), "the refused session is the one dropped");

    assertFalse(cache.holds(KEY, refused));
    assertTrue(cache.holds(sameUserElsewhere, sibling), "the account it did not name keeps its session");
    assertEquals(1, cache.size());
  }

  /**
   * <b>And it never drops the replacement somebody else just opened.</b> Two
   * callers meeting one refused key would otherwise evict each other's fresh
   * session in turn, each leaving a live one behind on BlueMind: the second
   * caller finds the entry already renewed and leaves it where it is.
   */
  @Test
  void droppingASessionThatHasAlreadyBeenReplacedChangesNothing() {
    BlueMindSessionCache cache = cache(300, 10);
    Login refused = session(ROOT, "key-refused");
    Login replacement = session(ROOT, "key-replacement");
    cache.keep(KEY, refused);
    cache.replace(KEY, replacement);

    assertFalse(cache.drop(KEY, refused), "the entry is no longer the one being complained about");

    assertTrue(cache.holds(KEY, replacement), "so the session another caller just opened survives");
    assertEquals(1, cache.size());
  }

  /**
   * <b>A store that keeps nothing keeps nothing.</b> The shape a call takes
   * when the deployment switched the lifetime or the bound off: every session
   * is its opener's to close, exactly as before EXO-90397.
   */
  @Test
  void aStoreWithNoLifetimeOrNoRoomKeepsNothing() {
    Login one = session(ROOT, "key-1");

    for (BlueMindSessionCache off : List.of(cache(0, 10), cache(300, 0), BlueMindSessionCache.unpooled())) {
      assertFalse(off.keeps());
      assertNull(off.keep(KEY, one));
      assertFalse(off.holds(KEY, one), "nothing keeps it, so its opener closes it");
      off.replace(KEY, one);
      assertEquals(0, off.size());
      assertNull(off.held(KEY));
    }
  }

  /**
   * <b>A session is answered only at the address it was opened at.</b> The
   * store itself does not compare addresses — that is the caller's check —
   * but it carries the address on the value so that the caller can, and so
   * that a session it gives up can be closed where it is known.
   */
  @Test
  void aKeptSessionCarriesTheAddressItWasOpenedAt() {
    BlueMindSessionCache cache = cache(300, 10);
    Login elsewhere = session(MOVED, "key-moved");
    cache.keep(KEY, elsewhere);

    assertEquals(MOVED, cache.held(KEY).apiRoot());
  }

  /**
   * <b>Forgetting an account answers what it dropped.</b> The broad
   * counterpart, used when eXo itself changed what the account is: every
   * session that user's credentials opened on that server, so the caller can
   * close each one at its own address.
   */
  @Test
  void forgettingAnAccountAnswersEverySessionItDropped() {
    BlueMindSessionCache cache = cache(300, 10);
    Key sameUserElsewhere = new Key(SERVER, "root", "root.other@bm.example.com");
    Key anotherUser = new Key(SERVER, "eric", "eric@bm.example.com");
    cache.keep(KEY, session(ROOT, "key-1"));
    cache.keep(sameUserElsewhere, session(ROOT, "key-2"));
    cache.keep(anotherUser, session(ROOT, "key-3"));

    List<Login> dropped = cache.forget(SERVER, "root");

    assertEquals(2, dropped.size(), "both accounts those credentials addressed, so both can be closed");
    assertEquals(1, cache.size(), "and nobody else's");
    assertTrue(cache.holds(anotherUser, cache.held(anotherUser)));
  }
}
