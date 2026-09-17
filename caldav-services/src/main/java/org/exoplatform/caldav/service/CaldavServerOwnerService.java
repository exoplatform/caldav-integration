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

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.bluemind.BlueMindCalendarOwners;
import org.exoplatform.caldav.client.bluemind.BlueMindContainerNaming;
import org.exoplatform.caldav.client.bluemind.BlueMindSubscriptionClient;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Asks the server whose each calendar in an account's view is, once per
 * account per pass (EXO-90347).
 *
 * <p>
 * <b>BlueMind only, deliberately.</b> The owner is read from BlueMind's
 * subscription listing through its own REST API
 * ({@link BlueMindSubscriptionClient#ownersOf}), because BlueMind is the
 * server on which nothing over CalDAV tells a subscribed colleague's calendar
 * from the account's own ({@link BlueMindContainerNaming}). The RFC analogue
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
 * <b>Fail closed, and say so once.</b> A listing that cannot be had — no
 * login and password to open the REST session with, a refused or
 * unreachable server, a session BlueMind authenticated as an entry other
 * than the account's principal, an answer of the wrong shape — is
 * {@link AccountCalendarOwners#unavailable()}, on which the classifier adopts
 * nothing it does not already recognise. Said once per account per process
 * at warn and at debug after that: the condition can last, and a line every
 * five minutes per user would bury the one that mattered.
 *
 * <p>
 * The product using the user's stored credentials during their own pass is
 * what every DAV request of that pass already does; nothing here stores,
 * logs or forwards them, and the session is closed by the client.
 */
@Component
public class CaldavServerOwnerService {

  private static final Log           LOG             = ExoLogger.getLogger(CaldavServerOwnerService.class);

  @Autowired
  private BlueMindSubscriptionClient blueMindSubscriptionClient;

  /**
   * The accounts already said, at warn, to have no readable owner listing —
   * keyed by server and eXo login, which is what an endpoint is minted from.
   */
  private final Set<String>          unavailableSaid = ConcurrentHashMap.newKeySet();

  /**
   * The server's word on the owners of an account's calendars, to be heard
   * at most once per pass.
   *
   * <p>
   * Deferred: nothing is fetched here. The listing is read the first time
   * the classifier asks about a calendar the other witnesses could not
   * settle, and kept for the rest of the pass; a pass that never asks costs
   * no request ({@link AccountCalendarOwners#deferred}).
   *
   * @param endpoint the account's DAV endpoint, minted from the registry for
   *          the user's eXo login
   * @param principal the account's own {@code current-user-principal}, as
   *          the discovery walk answered it; null when the server named none
   * @return the witness: silent for a server that is not BlueMind's, deferred
   *         otherwise
   */
  public AccountCalendarOwners ownersOf(CalDavEndpoint endpoint, String principal) {
    String principalUid = BlueMindContainerNaming.userUidOf(principal);
    if (principalUid == null || endpoint == null) {
      return AccountCalendarOwners.silent();
    }
    return AccountCalendarOwners.deferred(() -> fetch(endpoint, principalUid));
  }

  /**
   * One read of the listing, every failure turned into the unavailable
   * witness.
   *
   * @param endpoint the account's endpoint
   * @param principalUid the uid of the account's recorded principal, which
   *          the session must have been authenticated as
   * @return the listing, or {@link AccountCalendarOwners#unavailable()}
   */
  private AccountCalendarOwners fetch(CalDavEndpoint endpoint, String principalUid) {
    String key = endpoint.getServerId() + ":" + endpoint.getExoLogin();
    try {
      BlueMindCalendarOwners owners = blueMindSubscriptionClient.ownersOf(endpoint);
      if (!StringUtils.equalsIgnoreCase(owners.accountUid(), principalUid)) {
        // A session BlueMind opened as somebody else — a principal recorded
        // from a stale discovery, credentials re-pointed since — lists
        // somebody else's calendars, and comparing owners against them would
        // classify this account's calendars by another account's view.
        sayUnavailable(key, "the calendar server authenticated the session as entry " + owners.accountUid()
            + " rather than the account's principal " + principalUid, null);
        return AccountCalendarOwners.unavailable();
      }
      unavailableSaid.remove(key);
      return AccountCalendarOwners.of(owners.accountUid(), owners.ownerByContainerUid());
    } catch (UnsupportedOperationException e) {
      sayUnavailable(key, "its configured credentials are not a login and password the REST API takes", null);
      return AccountCalendarOwners.unavailable();
    } catch (RuntimeException e) {
      sayUnavailable(key, "the listing could not be read", e);
      return AccountCalendarOwners.unavailable();
    }
  }

  /**
   * Says, once at warn and then at debug, that an account's owner listing
   * cannot be had and what that costs.
   *
   * @param key the account, as server and eXo login
   * @param why what went wrong, with nothing secret in it
   * @param cause the failure, or null
   */
  private void sayUnavailable(String key, String why, Exception cause) {
    if (unavailableSaid.add(key)) {
      LOG.warn("The calendar owners of CalDAV account {} could not be read from the server: {}. Until they can, a calendar"
          + " under eXo's own naming that this deployment does not recognise is left where it is rather than adopted as"
          + " the user's own", key, why, cause);
    } else {
      LOG.debug("The calendar owners of CalDAV account {} still cannot be read: {}", key, why, cause);
    }
  }
}
