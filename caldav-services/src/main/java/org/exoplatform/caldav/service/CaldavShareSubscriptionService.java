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
 * subscription. One edge here is bounded: a row drained after they
 * unsubscribed by hand re-subscribes them, for at most {@code maxAttempts}
 * sweep periods after the grant. The seventh limit below is the other one,
 * and it is not bounded at all.
 *
 * <p>
 * <b>Seven limits, named because each is a decision somebody may want to
 * revisit rather than an oversight.</b>
 * <ul>
 * <li><b>Only a revoke made from eXo unsubscribes.</b> An owner who removes
 * the access entry in BlueMind's own webmail never runs this service, and
 * BlueMind does not auto-unsubscribe for a calendar — so that colleague keeps
 * the dangling subscription this service exists to prevent. Covering it would
 * mean noticing, at the colleague's own pass, a subscribed collection they can
 * no longer read; the natural seam is
 * {@code CaldavSyncService.forgetRevokedShares}, which already watches the
 * listing. Out of scope until the PO asks for it.</li>
 * <li><b>The grant-time attempt is synchronous, on the owner's thread, inside
 * the share's stripe lock.</b> It is one login, one POST and one logout, each
 * bounded by the REST session's 30-second timeout, so a stalled BlueMind can
 * hold the owner's Share or Unshare for up to a minute and a half beyond what
 * the grant itself already costs — and with it every other share hashing to
 * the same one of the share service's 64 lock stripes, which is one share in
 * 64 on that node, of any calendar on any server, not only of this one.
 * It buys immediacy in BlueMind's own webmail and on the colleague's devices;
 * the drain alone would put the calendar in their very next eXo pass anyway.
 * The trade is the PO's and the Architect's, not this class's.</li>
 * <li><b>The attempt bound is per node.</b> {@code @Scheduled} is node-local
 * and every node fires; the owed rows carry no claim and no lease, so on an
 * N-node cluster each sweep period runs N drains of the same rows — N logins
 * as the colleague, N counted refusals — and the bound is reached in about
 * {@code maxAttempts / N} periods rather than {@code maxAttempts}. The
 * account sweep beside it converges through its own last-sync column; this
 * table has no equivalent, and adding one is an Ops-visible change.</li>
 * <li><b>A row given up on is retired by spending the configured bound, not
 * by a terminal marker</b> ({@code CaldavPendingPushDAO}'s pattern, reused).
 * Raising {@code exo.agenda.caldav.push.maxAttempts} — a property shared with
 * the meeting-copy push, so raised for reasons that have nothing to do with
 * subscriptions — therefore makes every previously abandoned row attemptable
 * again. For a meeting copy that is a harmless re-push; here it can
 * re-subscribe a colleague who unsubscribed by hand.</li>
 * <li><b>The drain spends the account sweep's batch on sessions, not on
 * rows.</b> The job hands it {@code exo.agenda.caldav.sync.sweep.batchSize}
 * (50 by default), and those 50 rows can be 50 distinct colleagues on 50
 * servers — 50 logins, each up to the session's three 30-second timeouts —
 * on the scheduler's single thread, against a cron that fires every five
 * minutes. A backlog on a server that swallows connections therefore delays
 * this add-on's own account sweep for as many periods as it takes, and it is
 * the larger of the two costs named here. Self-limiting after
 * {@code maxAttempts} periods; a bound of its own, rather than the account
 * sweep's, is the Architect's and Ops' call.</li>
 * <li><b>The drain counts attempts by row id, and a renewed row keeps that
 * id.</b> Settling is kind-aware for exactly that reason, but
 * {@code refused} and {@code abandoned} are not: if a revoke is recorded
 * over a pending subscribe while the drain's session is open, the drain's
 * verdict on the <em>old</em> subscribe is written against the <em>new</em>
 * removal — a spent budget retires a removal nobody attempted, a counted
 * refusal costs it one of its five. It needs a genuinely concurrent revoke,
 * and it cannot leave a dangling subscription — the two verdicts it concerns
 * are RETRY and FINAL, which by definition mean the change did not land. The
 * LANDED case of the same window is a different animal and is the seventh
 * limit, below. So this one is recorded rather than fixed: making both writes
 * match on KIND as well is an Architect's call on an N1 surface.</li>
 * <li><b>A revoke landing inside a drain's open session leaves a dangling
 * subscription that nothing records and nothing retries: documented, not
 * closed.</b> Unlike the hand-unsubscribe edge above, this one is not bounded.
 * The drain and the share service share no lock and no lease — the stripe lock
 * is taken inside {@code CaldavCalendarShareService} only, and the drain reads
 * its rows, opens the colleague's session and posts without it. So: a grant
 * whose subscribe failed leaves a pending SUBSCRIBE; a drain opens the session
 * and is about to post it; the owner revokes from eXo in that window; the
 * revoke's {@code _unsubscribe} lands and clears the row, as it must; the
 * drain's {@code _subscribe} then lands too, because BlueMind performs no
 * access check on a subscribe; and the drain's own settle finds no row and
 * does nothing. Two success lines in the log, and
 * {@code CaldavSyncService.forgetRevokedShares} cannot heal it either, because
 * the dangling subscription is exactly what keeps the href in their listing.
 * <p>
 * <b>What it costs, which is why the decision was to document it.</b> The
 * window needs a revoke to land between the drain opening the colleague's
 * session and its subscribe returning. What it leaves is one stale calendar in
 * that colleague's listing — not lost data, and not access to anything they
 * could not already see: a subscription only makes a calendar appear for them,
 * and BlueMind still enforces its own ACL on the contents
 * ({@code CalendarService} checks {@code Verb.Read} on the container for every
 * read, subscribed or not), so the calendar is listed and answers nothing.
 * <p>
 * <b>What closing it would take.</b> A version or a claim column on
 * {@code CALDAV_PENDING_SUBSCRIPTION}, so that a drain finding no row could
 * tell "the owner revoked while I was in flight" from "a later grant settled
 * it" — a distinction nothing in the table can make today, and the reason the
 * obvious repair is wrong: having the drain re-record an UNSUBSCRIBE whenever
 * it finds no row would, in that second case, unsubscribe a calendar that is
 * legitimately shared. That column is deliberately left to a follow-up rather
 * than added to this branch. None of it is about how settling is spelled: the
 * same interleaving reaches the same end state whichever of the two settle
 * methods the drain uses.</li>
 * </ul>
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

  @Autowired
  private CaldavServerOwnerService                 caldavServerOwnerService;

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
        // The sharee's mailbox now sees the calendar (or no longer does): what
        // eXo remembers of its owners is stale (EXO-90347).
        caldavServerOwnerService.evict(share.shareeIdentityId(), share.serverId());
        LOG.info("CalDAV share followed on BlueMind: user {} (entry {}) {} calendar container {} shared by {} on server {}",
                 share.shareeUsername(),
                 share.shareeUid(),
                 kind == PendingSubscriptionKind.SUBSCRIBE ? "subscribed to" : "unsubscribed from",
                 share.containerUid(),
                 share.ownerUsername(),
                 share.serverId());
        // Unconditional, and settledIfStillAsking is deliberately NOT used
        // here: this instruction was decided in this call, inside the share's
        // stripe lock and after the read-back, and owe() - the only thing that
        // records an instruction at all - has exactly this one call site. So
        // whatever row stands for the container is older than what just
        // landed. Guarding it would leave a pending SUBSCRIBE alive past a
        // revoke, and the next drain would re-subscribe the colleague to a
        // calendar whose access entry is gone.
        caldavPendingSubscriptionStorage.settledWhateverWasOwed(share.shareeIdentityId(), share.serverId(), share.containerUid());
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
    } catch (RuntimeException e) {
      // Anything eXo's own machinery threw on the way, counted rather than
      // dropped: a row this run left untouched is the same row at the head of
      // the next run, which is a login as this colleague every sweep period
      // and a batch the rest of the backlog never gets. Applied to the whole
      // list, not only the untried tail: a row that landed in this pass was
      // deleted and counting it is a no-op, while a row already counted in it
      // is counted twice - two of maxAttempts for one run. What can bring us
      // here is settle()'s own writes and the session's machinery (a
      // credentials provider that did not produce what its channel promised,
      // a transport that will not take the key); attemptInSession swallows
      // everything else. On a run failing that way, spending a second attempt
      // is the cheaper of the two errors.
      return retryAll(rows, String.valueOf(e));
    }
    if (landed[0] > 0) {
      // The colleague's mailbox sees more, or less, than what eXo remembers
      // of its owners (EXO-90347).
      caldavServerOwnerService.evict(userIdentityId, serverId);
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
    } catch (RuntimeException e) {
      // Not a server answer: eXo's own machinery failed on the way - a
      // credentials provider that did not produce what its channel promised,
      // a session key the transport will not put in a header. It is still an
      // obligation the colleague is owed, and the line above this one is the
      // reason it must be classified rather than thrown: an unclassified
      // escape leaves NO row, and an obligation with no row is never retried
      // and never seen again.
      LOG.warn("The BlueMind subscription of user {} failed before any answer was read", shareeUsername, e);
      return new Attempt(Outcome.RETRY, String.valueOf(e), true);
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
    } catch (RuntimeException e) {
      // Same reason as at grant time, with a different cost: an escape here
      // leaves the row exactly as it was, and a row that is never counted is
      // handed out again at the head of every sweep - one login as the
      // colleague per run, for ever. Counted, it spends its budget like any
      // other refusal and stops.
      return new Attempt(Outcome.RETRY, String.valueOf(e), false);
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
      caldavPendingSubscriptionStorage.settledIfStillAsking(row.getUserIdentityId(),
                                                          row.getServerId(),
                                                          row.getContainerUid(),
                                                          row.getKind());
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
