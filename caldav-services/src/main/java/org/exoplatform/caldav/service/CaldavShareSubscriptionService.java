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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavForbiddenException;
import org.exoplatform.caldav.client.CalDavNotFoundException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.bluemind.BlueMindContainerNaming;
import org.exoplatform.caldav.client.bluemind.BlueMindSubjectMismatchException;
import org.exoplatform.caldav.client.bluemind.BlueMindSubscriptionClient;
import org.exoplatform.caldav.client.bluemind.BlueMindSubscriptionClient.Subscriptions;
import org.exoplatform.caldav.model.PendingSubscription;
import org.exoplatform.caldav.model.PendingSubscriptionKind;
import org.exoplatform.caldav.storage.CaldavPendingSubscriptionStorage;
import org.exoplatform.caldav.utils.CaldavConnectorUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Makes a calendar shared from eXo on BlueMind appear to the colleague it was
 * shared with, by subscribing their BlueMind account to it as they would
 * themselves — and removes that subscription when the share is revoked
 * (EXO-90277).
 *
 * <p>
 * <b>Why eXo does it.</b> On BlueMind a grant writes an access entry and
 * nothing else; the colleague's DAV home and webmail list
 * <em>subscriptions</em>, so until they click "Add" in BlueMind the calendar
 * appears nowhere — not in BlueMind, not under "Shared with me" in eXo. The
 * PO decided (2026-09-16) that eXo takes that step for them at grant time,
 * with their own stored credentials; BlueMind's access-change mail is their
 * notice. The reverse on revoke is load-bearing: BlueMind auto-unsubscribes on
 * an access removal for mailboxes only, and a dangling calendar subscription
 * keeps the href in the colleague's listing — a dead calendar shown, and a
 * hidden share ({@code HIDDEN_SHARE}, EXO-90239) never retired, since that
 * record is dropped only when the href leaves the listing.
 *
 * <p>
 * <b>Never in the owner's way.</b> Both entry points are called from inside
 * the owner's grant or revoke, after the access list read back confirmed the
 * change, and they never throw: the owner's share succeeded on the server
 * whatever happens to the colleague's subscription. A change that does not
 * land is recorded as owed ({@code CALDAV_PENDING_SUBSCRIPTION}) and drained
 * later; a stale colleague password is a {@code WARN} here and a row, never a
 * failure of the grant and never a pause of the colleague's own account —
 * their own pass is where that verdict belongs.
 *
 * <p>
 * <b>Where the drain runs, and why it is table-driven.</b> The sweep selects
 * accounts holding an ACTIVE pair and only its background pass runs outbound
 * work; a colleague who merely received shares holds no pair for them and is
 * never swept. So the owed rows are read from their own table — every sharee,
 * oldest first, one session per sharee — from the sweep job, and the
 * colleague's own rows are drained at the top of their own synchronisation
 * pass when they open their agenda (PO decision 4). The attempt bound is the
 * meeting-copy one, {@code exo.agenda.caldav.push.maxAttempts}, on the PO's
 * decision that no new property is wanted.
 *
 * <p>
 * <b>What is given up on at once, and what is argued with.</b> A server that
 * cannot be reached, a login refused, and any other unexplained answer are
 * counted and retried up to the bound. A session BlueMind authenticated as
 * somebody other than the recorded colleague, a 403, a container BlueMind
 * does not hold, a colleague no longer connected to that server, credentials
 * that are not a login, and a login eXo cannot resolve any more are final:
 * the row spends its whole budget and stays as the record that eXo gave up.
 *
 * <p>
 * <b>What is left alone by design.</b> A colleague who unsubscribes in
 * BlueMind afterwards is not re-subscribed: eXo acts at grant and at revoke,
 * never on the listing. Hiding the calendar in eXo ({@code HIDDEN_SHARE})
 * covers "subscribed for me, I do not want it shown" exactly as for a hand
 * subscription. The one bounded edge is a row drained after they unsubscribed
 * by hand — the drain re-subscribes them — which lasts at most
 * {@code maxAttempts} sweep periods after the grant.
 */
@Service
public class CaldavShareSubscriptionService {

  /** How many owed rows the colleague's own pass drains before it lists anything. */
  public static final int                          OWN_DRAIN_BATCH = 10;

  private static final Log                         LOG             = ExoLogger.getLogger(CaldavShareSubscriptionService.class);

  /**
   * How many refusals are argued with before a change is given up on: the
   * meeting-copy bound, reused (PO decision 4, 2026-09-16). It counts the
   * retries and not the attempt that failed first, as the push bound does.
   */
  @Value("${exo.agenda.caldav.push.maxAttempts:5}")
  private int                                      maxAttempts     = 5;

  @Autowired
  private BlueMindSubscriptionClient               blueMindSubscriptionClient;

  @Autowired
  private CaldavPendingSubscriptionStorage         caldavPendingSubscriptionStorage;

  @Autowired
  private CalDavClient                             calDavClient;

  @Autowired
  private CaldavConnectionIdentityService          caldavConnectionIdentityService;

  @Autowired
  private IdentityManager                          identityManager;

  /**
   * A share as the owner's grant or revoke knows it, with everything the
   * colleague's subscription needs and the audit line names.
   *
   * @param ownerUsername the owner's login, for the audit line
   * @param shareeIdentityId the colleague's social identity
   * @param shareeUsername the colleague's eXo login, which the credentials
   *          provider maps to their account on the server
   * @param shareeUid the directory entry uid eXo recorded for the colleague
   * @param serverId the server key, zero for the legacy property
   * @param containerUid the shared calendar's BlueMind container uid
   */
  public record ShareeSubscription(String ownerUsername,
                                   long shareeIdentityId,
                                   String shareeUsername,
                                   String shareeUid,
                                   long serverId,
                                   String containerUid) {
  }

  /**
   * How one attempt ended, and what to do with the row.
   */
  private enum Outcome {
    /** BlueMind accepted the change. */
    LANDED,
    /** The answer may change by asking again: counted, retried. */
    RETRY,
    /** The answer will not change by asking again: given up on. */
    FINAL
  }

  /**
   * Subscribes the colleague to the calendar just shared with them, as them.
   * Never throws.
   *
   * @param share the share as granted
   */
  public void subscribeSharee(ShareeSubscription share) {
    change(share, PendingSubscriptionKind.SUBSCRIBE);
  }

  /**
   * Unsubscribes the colleague from the calendar no longer shared with them,
   * as them. Never throws.
   *
   * @param share the share as revoked
   */
  public void unsubscribeSharee(ShareeSubscription share) {
    change(share, PendingSubscriptionKind.UNSUBSCRIBE);
  }

  /**
   * Drains the changes owed to anybody, oldest first, one session per
   * colleague and server: what the sweep job calls (hole 2 of the brief).
   *
   * @param batch how many owed rows one run looks at
   * @return how many changes landed this run
   */
  public int retryOwed(int batch) {
    List<PendingSubscription> owed;
    try {
      owed = caldavPendingSubscriptionStorage.attemptable(maxAttempts, batch);
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The subscription changes eXo owes on BlueMind could not be read; nothing is retried this run", e);
      return 0;
    }
    return drain(owed);
  }

  /**
   * Drains the changes owed to one colleague: what their own synchronisation
   * pass calls first, so a calendar shared with them is subscribed before
   * that pass lists their home.
   *
   * @param userIdentityId the colleague
   * @param batch how many owed rows to look at
   * @return how many changes landed
   */
  public int retryOwed(long userIdentityId, int batch) {
    List<PendingSubscription> owed;
    try {
      owed = caldavPendingSubscriptionStorage.attemptable(userIdentityId, maxAttempts, batch);
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The subscription changes eXo owes user {} on BlueMind could not be read; nothing is retried", userIdentityId, e);
      return 0;
    }
    return drain(owed);
  }

  /**
   * One change tried at grant or revoke time: landed is audited and settles
   * any row; anything else is one WARN and a row for the drain.
   *
   * @param share the share
   * @param kind the change
   */
  private void change(ShareeSubscription share, PendingSubscriptionKind kind) {
    try {
      Attempt attempt = attempt(kind, share.serverId(), share.shareeUsername(), share.shareeUid(), share.containerUid());
      if (attempt.outcome() == Outcome.LANDED) {
        LOG.info("CalDAV share followed on BlueMind: user {} (entry {}) {} calendar container {} shared by {} on server {}",
                 share.shareeUsername(),
                 share.shareeUid(),
                 kind == PendingSubscriptionKind.SUBSCRIBE ? "subscribed to" : "unsubscribed from",
                 share.containerUid(),
                 share.ownerUsername(),
                 share.serverId());
        caldavPendingSubscriptionStorage.settled(share.shareeIdentityId(), share.serverId(), share.containerUid());
        return;
      }
      LOG.warn("User {} could not be {} calendar container {} on server {} (shared by {}); recorded, the sweep retries it: {}",
               share.shareeUsername(),
               kind == PendingSubscriptionKind.SUBSCRIBE ? "subscribed to" : "unsubscribed from",
               share.containerUid(),
               share.serverId(),
               share.ownerUsername(),
               attempt.reason());
      caldavPendingSubscriptionStorage.owe(share.shareeIdentityId(), share.serverId(), share.containerUid(), kind);
    } catch (RuntimeException | LinkageError e) {
      // The owner's share has already been applied and confirmed; nothing
      // about the colleague's subscription may turn it into a failure.
      LOG.warn("The subscription of user {} to calendar container {} on server {} could not be recorded",
               share.shareeUsername(),
               share.containerUid(),
               share.serverId(),
               e);
    }
  }

  /**
   * Drains owed rows grouped by colleague and server, one session each.
   *
   * @param owed the rows, oldest first
   * @return how many landed
   */
  private int drain(List<PendingSubscription> owed) {
    if (owed.isEmpty()) {
      return 0;
    }
    Map<String, List<PendingSubscription>> bySharee = new LinkedHashMap<>();
    for (PendingSubscription row : owed) {
      bySharee.computeIfAbsent(row.getUserIdentityId() + "@" + row.getServerId(), key -> new ArrayList<>()).add(row);
    }
    int landed = 0;
    for (List<PendingSubscription> rows : bySharee.values()) {
      try {
        landed += drainSharee(rows);
      } catch (RuntimeException | LinkageError e) {
        // One colleague must not cost the rest of the run; their rows come
        // back at the top of the next one.
        LOG.warn("The subscription changes owed to user {} on server {} could not be drained this run",
                 rows.get(0).getUserIdentityId(),
                 rows.get(0).getServerId(),
                 e);
      }
    }
    return landed;
  }

  /**
   * Drains one colleague's rows on one server in one session opened as
   * them.
   *
   * @param rows their rows, oldest first, all on one server
   * @return how many landed
   */
  private int drainSharee(List<PendingSubscription> rows) {
    long userIdentityId = rows.get(0).getUserIdentityId();
    long serverId = rows.get(0).getServerId();
    String login = CaldavConnectorUtils.loginOf(identityManager, userIdentityId);
    if (login == null) {
      return giveUpAll(rows, "their eXo login cannot be resolved");
    }
    String principal = caldavConnectionIdentityService.principalOf(userIdentityId, serverId);
    String shareeUid = BlueMindContainerNaming.userUidOf(principal);
    if (shareeUid == null) {
      return giveUpAll(rows, principal == null ? "they are no longer connected to that server"
                                               : "their recorded principal is not a BlueMind user");
    }
    CalDavEndpoint endpoint;
    try {
      endpoint = endpointOf(serverId, login);
    } catch (CalDavException e) {
      return retryAll(rows, "their endpoint could not be minted: " + e.getMessage());
    }
    int[] landed = { 0 };
    try {
      blueMindSubscriptionClient.asSharee(endpoint, shareeUid, edits -> {
        for (PendingSubscription row : rows) {
          Attempt attempt = attemptInSession(edits, row.getKind(), row.getContainerUid());
          if (attempt.outcome() == Outcome.LANDED) {
            landed[0]++;
          }
          settle(row, attempt);
          if (attempt.outcome() == Outcome.RETRY && attempt.sessionLost()) {
            // The session itself was refused or the server went away: the
            // rows not yet tried are left as they are for the next run.
            break;
          }
        }
        return null;
      });
    } catch (BlueMindSubjectMismatchException | UnsupportedOperationException e) {
      return giveUpAll(rows, e.getMessage());
    } catch (CalDavException e) {
      // The login itself: refused, unreachable, or an unexplained answer.
      return retryAll(rows, e.getMessage());
    }
    return landed[0];
  }

  /**
   * One change tried in a session of its own, at grant or revoke time.
   *
   * @param kind the change
   * @param serverId the server key
   * @param shareeUsername the colleague's eXo login
   * @param shareeUid the colleague's recorded directory entry uid
   * @param containerUid the container
   * @return how it ended
   */
  private Attempt attempt(PendingSubscriptionKind kind, long serverId, String shareeUsername, String shareeUid, String containerUid) {
    try {
      CalDavEndpoint endpoint = endpointOf(serverId, shareeUsername);
      return blueMindSubscriptionClient.asSharee(endpoint, shareeUid, edits -> attemptInSession(edits, kind, containerUid));
    } catch (BlueMindSubjectMismatchException | UnsupportedOperationException e) {
      return new Attempt(Outcome.FINAL, e.getMessage(), false);
    } catch (CalDavException e) {
      return new Attempt(Outcome.RETRY, e.getMessage(), true);
    }
  }

  /**
   * One change made through an open session, its answer classified.
   *
   * @param edits the open session's edits
   * @param kind the change
   * @param containerUid the container
   * @return how it ended
   */
  private static Attempt attemptInSession(Subscriptions edits, PendingSubscriptionKind kind, String containerUid) {
    try {
      if (kind == PendingSubscriptionKind.UNSUBSCRIBE) {
        edits.unsubscribe(containerUid);
      } else {
        edits.subscribe(containerUid);
      }
      return new Attempt(Outcome.LANDED, null, false);
    } catch (CalDavForbiddenException | CalDavNotFoundException e) {
      return new Attempt(Outcome.FINAL, e.getMessage(), false);
    } catch (CalDavAuthenticationException | CalDavUnreachableException e) {
      return new Attempt(Outcome.RETRY, e.getMessage(), true);
    } catch (CalDavException e) {
      return new Attempt(Outcome.RETRY, e.getMessage(), false);
    }
  }

  /**
   * Writes one drained row's outcome and says so, once per row.
   *
   * @param row the row
   * @param attempt how its change ended
   */
  private void settle(PendingSubscription row, Attempt attempt) {
    switch (attempt.outcome()) {
    case LANDED -> {
      caldavPendingSubscriptionStorage.settled(row.getUserIdentityId(), row.getServerId(), row.getContainerUid());
      LOG.info("Owed BlueMind {} landed: user {} and calendar container {} on server {}",
               row.getKind(),
               row.getUserIdentityId(),
               row.getContainerUid(),
               row.getServerId());
    }
    case FINAL -> {
      caldavPendingSubscriptionStorage.abandoned(row.getId(), maxAttempts);
      LOG.info("Owed BlueMind {} given up on: user {} and calendar container {} on server {}: {}",
               row.getKind(),
               row.getUserIdentityId(),
               row.getContainerUid(),
               row.getServerId(),
               attempt.reason());
    }
    case RETRY -> {
      caldavPendingSubscriptionStorage.refused(row.getId());
      LOG.info("Owed BlueMind {} refused ({} of {} retries): user {} and calendar container {} on server {}: {}",
               row.getKind(),
               row.getAttempts() + 1,
               maxAttempts,
               row.getUserIdentityId(),
               row.getContainerUid(),
               row.getServerId(),
               attempt.reason());
    }
    }
  }

  /**
   * Gives up every row of a colleague for one reason that applies to all.
   *
   * @param rows the rows
   * @param reason why
   * @return zero landed
   */
  private int giveUpAll(List<PendingSubscription> rows, String reason) {
    for (PendingSubscription row : rows) {
      settle(row, new Attempt(Outcome.FINAL, reason, false));
    }
    return 0;
  }

  /**
   * Counts a refusal against every row of a colleague, for one reason that
   * applies to all.
   *
   * @param rows the rows
   * @param reason why
   * @return zero landed
   */
  private int retryAll(List<PendingSubscription> rows, String reason) {
    for (PendingSubscription row : rows) {
      settle(row, new Attempt(Outcome.RETRY, reason, true));
    }
    return 0;
  }

  /**
   * The colleague's own endpoint: minted from the registry for <em>their</em>
   * eXo login, so the session opens with their stored credentials.
   *
   * @param serverId the server key, zero for the legacy property
   * @param shareeUsername the colleague's eXo login
   * @return the endpoint
   */
  private CalDavEndpoint endpointOf(long serverId, String shareeUsername) {
    if (StringUtils.isBlank(shareeUsername)) {
      throw new CalDavException("The sharee's login is required to mint their endpoint");
    }
    return calDavClient.endpoint(serverId == 0L ? null : serverId, shareeUsername);
  }

  /**
   * How one attempt ended.
   *
   * @param outcome what to do with the row
   * @param reason why, for the line; null when it landed
   * @param sessionLost whether the session itself is no longer usable, so
   *          the rows after this one in the same session are left untried
   */
  private record Attempt(Outcome outcome, String reason, boolean sessionLost) {
  }
}
