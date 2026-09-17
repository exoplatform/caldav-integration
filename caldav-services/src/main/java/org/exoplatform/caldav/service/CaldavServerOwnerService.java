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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.bluemind.BlueMindCalendarOwners;
import org.exoplatform.caldav.client.bluemind.BlueMindContainerNaming;
import org.exoplatform.caldav.client.bluemind.BlueMindSubscriptionClient;
import org.exoplatform.caldav.storage.CaldavServerOwnerStorage;
import org.exoplatform.caldav.storage.CaldavServerOwnerStorage.Key;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Asks the server whose each calendar in an account's view is — once per
 * account per pass at most, and mostly from a cache (EXO-90347).
 *
 * <p>
 * <b>BlueMind only, deliberately.</b> The owner is read from BlueMind's
 * subscription listing through its own REST API
 * ({@link BlueMindSubscriptionClient#ownersOf}, kept by
 * {@link CaldavServerOwnerStorage}), because BlueMind is the server on
 * which nothing over CalDAV tells a subscribed colleague's calendar from
 * the account's own ({@link BlueMindContainerNaming}). The RFC analogue
 * would be {@code DAV:owner} (RFC 3744 §5.1) read per collection, which
 * Stalwart answers truthfully and the classifier already reads through
 * {@code CalendarCollection#isSharedWith}; a server that answers it wrongly
 * has, so far, been BlueMind alone, so no second implementation is made
 * here, and a server of any other shape gets {@link AccountCalendarOwners#silent()}
 * — its behaviour is unchanged by this witness.
 *
 * <p>
 * <b>Which servers are BlueMind's</b>: an account whose principal has
 * BlueMind's {@code …/principals/__uids__/<uid>/} spelling
 * ({@link BlueMindContainerNaming#userUidOf}), the same rule the naming
 * witness is read under. A server of that spelling that is not BlueMind —
 * Apple's retired Calendar Server used it — would be asked and fail to
 * answer, and its eXo-shaped collections that nobody here recognises would
 * then wait rather than be adopted; no such server is known to be in use.
 *
 * <p>
 * <b>What one pass costs.</b> The witness handed out is deferred: nothing
 * is read until a collection needs it. Its first question is answered from
 * the storage's cache — one hour by default, per account — and reaches the
 * server only on a miss. A calendar the cached listing does not name is
 * the one thing that forces a read within the TTL: once per account per
 * pass, however many such calendars the pass meets, so a share made after
 * the entry was written is classified in the pass that first sees it. A
 * session BlueMind opened as an entry other than the account's recorded
 * principal — credentials re-pointed since the entry was written — spends
 * that same one refresh; if the fresh listing still names another entry,
 * the witness is unavailable.
 *
 * <p>
 * <b>Fail closed, and say so once.</b> A listing that cannot be had — no
 * login and password to open the REST session with, a refused or
 * unreachable server, a mismatched entry, an answer of the wrong shape — is
 * {@link AccountCalendarOwners#unavailable()} for the pass, on which the
 * classifier adopts nothing it does not already recognise; nothing is
 * cached for it, so the next pass tries again. Said once per account per
 * process at warn and at debug after that: the condition can last, and a
 * line every five minutes per user would bury the one that mattered.
 *
 * <p>
 * <b>Evicted when eXo knows better.</b> A share eXo grants or revokes and a
 * subscription it follows change what the sharee's mailbox sees, and the
 * account itself changes hands on connect, disconnect or new credentials;
 * each drops the entry ({@link #evict}) on the node that made the change.
 * The cache is node-local: another node learns of it within the TTL, or
 * sooner through the miss-refresh above.
 *
 * <p>
 * The product using the user's stored credentials during their own pass is
 * what every DAV request of that pass already does; nothing here stores,
 * logs or forwards them, and the session is closed by the client.
 */
@Component
public class CaldavServerOwnerService {

  private static final Log         LOG             = ExoLogger.getLogger(CaldavServerOwnerService.class);

  @Autowired
  private CaldavServerOwnerStorage caldavServerOwnerStorage;

  /**
   * The accounts already said, at warn, to have no readable owner listing —
   * keyed by server and user, as the cache is.
   */
  private final Set<Key>           unavailableSaid = ConcurrentHashMap.newKeySet();

  /**
   * The server's word on the owners of an account's calendars, to be heard
   * at most once per pass and read from the server at most once more.
   *
   * <p>
   * Deferred: nothing is fetched here. The listing is read the first time
   * the classifier asks about a calendar the other witnesses could not
   * settle — from the cache when it holds the account, else from the server
   * — and kept for the rest of the pass; a pass that never asks costs no
   * request ({@link AccountCalendarOwners#deferred(Supplier, Supplier)}).
   *
   * @param userIdentityId the connected user, whose stored credentials open
   *          the session and whose entry the cache keeps
   * @param endpoint the account's DAV endpoint, minted from the registry for
   *          that user's eXo login
   * @param principal the account's own {@code current-user-principal}, as
   *          the discovery walk answered it; null when the server named none
   * @return the witness: silent for a server that is not BlueMind's, deferred
   *         otherwise
   */
  public AccountCalendarOwners ownersOf(long userIdentityId, CalDavEndpoint endpoint, String principal) {
    String principalUid = BlueMindContainerNaming.userUidOf(principal);
    if (principalUid == null || endpoint == null) {
      return AccountCalendarOwners.silent();
    }
    Pass pass = new Pass(keyOf(userIdentityId, endpoint), endpoint, principalUid);
    return AccountCalendarOwners.deferred(pass::first, pass::again);
  }

  /**
   * Drops what the cache holds for one account, because eXo itself changed
   * what that account sees or who it is.
   *
   * @param userIdentityId the connected user
   * @param serverId the declared server registration, zero for the legacy
   *          property
   */
  public void evict(long userIdentityId, long serverId) {
    caldavServerOwnerStorage.evict(new Key(serverId, userIdentityId));
  }

  /**
   * The cache key of an account: its server and its user.
   *
   * @param userIdentityId the connected user
   * @param endpoint the account's endpoint, which names the server
   * @return the key
   */
  private static Key keyOf(long userIdentityId, CalDavEndpoint endpoint) {
    return new Key(endpoint.getServerId() == null ? 0L : endpoint.getServerId(), userIdentityId);
  }

  /**
   * One pass's reads of one account's listing: the first from the cache or
   * the server, and at most one more from the server.
   */
  private final class Pass {

    private final Key            key;

    private final CalDavEndpoint endpoint;

    private final String         principalUid;

    /** Whether this pass has spent its one read from the server. */
    private boolean              refreshed;

    /**
     * A pass over one account.
     *
     * @param key the account's cache key
     * @param endpoint the account's endpoint
     * @param principalUid the uid of the account's recorded principal, which
     *          the session must have been authenticated as
     */
    private Pass(Key key, CalDavEndpoint endpoint, String principalUid) {
      this.key = key;
      this.endpoint = endpoint;
      this.principalUid = principalUid;
    }

    /**
     * The first read: the cache, else the server. Every failure is the
     * unavailable witness, cached nowhere.
     *
     * @return the listing, or {@link AccountCalendarOwners#unavailable()}
     */
    private AccountCalendarOwners first() {
      try {
        return accept(caldavServerOwnerStorage.listing(key, endpoint));
      } catch (UnsupportedOperationException e) {
        sayUnavailable("its configured credentials are not a login and password the REST API takes", null);
        return AccountCalendarOwners.unavailable();
      } catch (RuntimeException e) {
        sayUnavailable("the listing could not be read", e);
        return AccountCalendarOwners.unavailable();
      }
    }

    /**
     * The one read from the server this pass may make beyond the first:
     * for a calendar the listing in hand does not name.
     *
     * @return the fresh listing, or null when the pass has already spent
     *         its read or the server did not answer — the listing in hand
     *         then stands
     */
    private AccountCalendarOwners again() {
      if (refreshed) {
        return null;
      }
      refreshed = true;
      try {
        BlueMindCalendarOwners owners = caldavServerOwnerStorage.refresh(key, endpoint);
        return StringUtils.equalsIgnoreCase(owners.accountUid(), principalUid) ? AccountCalendarOwners.of(owners.accountUid(),
                                                                                                          owners.ownerByContainerUid())
                                                                               : null;
      } catch (RuntimeException e) {
        LOG.debug("The calendar owners of CalDAV account {} could not be re-read; the listing in hand stands", key, e);
        return null;
      }
    }

    /**
     * A listing read for this pass, believed only when the session it came
     * from was the account's own.
     *
     * <p>
     * A session BlueMind opened as somebody else — a principal recorded from
     * a stale discovery, credentials re-pointed since the entry was cached —
     * lists somebody else's calendars, and comparing owners against them
     * would classify this account's calendars by another account's view.
     * The pass's one server read is spent on a fresh listing first, since a
     * cached entry is the likely cause; a fresh listing that still names
     * another entry is not believed.
     *
     * @param owners the listing, from the cache or the server
     * @return the witness, or {@link AccountCalendarOwners#unavailable()}
     */
    private AccountCalendarOwners accept(BlueMindCalendarOwners owners) {
      if (!StringUtils.equalsIgnoreCase(owners.accountUid(), principalUid)) {
        AccountCalendarOwners fresh = again();
        if (fresh != null) {
          unavailableSaid.remove(key);
          return fresh;
        }
        sayUnavailable("the calendar server authenticated the session as entry " + owners.accountUid()
            + " rather than the account's principal " + principalUid, null);
        return AccountCalendarOwners.unavailable();
      }
      unavailableSaid.remove(key);
      return AccountCalendarOwners.of(owners.accountUid(), owners.ownerByContainerUid());
    }

    /**
     * Says, once at warn and then at debug, that the account's owner listing
     * cannot be had and what that costs.
     *
     * @param why what went wrong, with nothing secret in it
     * @param cause the failure, or null
     */
    private void sayUnavailable(String why, Exception cause) {
      if (unavailableSaid.add(key)) {
        LOG.warn("The calendar owners of CalDAV account {} could not be read from the server: {}. Until they can, a calendar"
            + " under eXo's own naming that this deployment does not recognise is left where it is rather than adopted as"
            + " the user's own", key, why, cause);
      } else {
        LOG.debug("The calendar owners of CalDAV account {} still cannot be read: {}", key, why, cause);
      }
    }
  }
}
