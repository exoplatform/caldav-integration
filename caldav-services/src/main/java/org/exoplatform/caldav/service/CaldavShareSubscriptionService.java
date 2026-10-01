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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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
import org.exoplatform.caldav.client.CalDavProviderMissingException;
import org.exoplatform.caldav.client.CalDavSubjectMismatchException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.constant.SubscriptionOutcome;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncPauseReason;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.PendingSubscription;
import org.exoplatform.caldav.model.PendingSubscriptionKind;
import org.exoplatform.caldav.model.ShareeSubscription;
import org.exoplatform.caldav.model.SubscriptionAttempt;
import org.exoplatform.caldav.plugin.CalendarSubscriptionChannel;
import org.exoplatform.caldav.plugin.SubscriptionEdits;
import org.exoplatform.caldav.storage.CaldavPendingSubscriptionStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
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
 * failure of the grant and never a pause of the colleague's own account at
 * grant time — the drain, like their own pass, is where that verdict belongs.
 *
 * <p>
 * <b>Where the drain runs, and why it is table-driven.</b> The sweep selects
 * accounts holding an ACTIVE pair and only its background pass runs outbound
 * work; a colleague who merely received shares holds no pair for them and is
 * never swept. So the owed rows are read from their own table — every sharee,
 * oldest first, one session per sharee — from the sweep job, and the
 * colleague's own rows are drained at the top of their own synchronisation
 * pass when they open their agenda (the PO's decision of 2026-09-16). The
 * attempt bound is the
 * meeting-copy one, {@code exo.agenda.caldav.push.maxAttempts}, on the PO's
 * decision that no new property is wanted.
 *
 * <p>
 * <b>What is given up on at once, what is argued with, and what waits.</b> A
 * server that cannot be reached and any other unexplained answer are counted
 * and retried up to the bound. A login refused to the drain pauses the
 * colleague's active pairs on that server, as their own synchronisation pass
 * does at its first refusal, and their rows wait uncounted until the account
 * is put back to work; the drain does not log in as a colleague whose account
 * is paused. A colleague holding no pair on the server has nothing to pause,
 * and a refused login counts against their rows like any retry. A session
 * BlueMind authenticated as
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
 * sweep periods after the grant.
 *
 * <p>
 * <b>A drain and a grant or revoke of the same obligation do not
 * interleave, on one node.</b> The grant's or the revoke's attempt and settle
 * and the drain's re-read, post and settle of a row each run under the lock
 * of the colleague, the server and the container. So a revoke cannot land
 * between a drain's subscribe being posted and its settle, and a drain does
 * not post a row a grant or a revoke has decided again since the drain read
 * it. Every write the drain makes about a row is one statement that matches
 * the kind it read, so a row renewed with the other change is never charged
 * with the old change's verdict.
 *
 * <p>
 * <b>Six limits, named because each is a decision somebody may want to
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
 * the grant itself already costs, and up to 30 seconds more when a drain is
 * posting the same obligation, whose lock the grant then waits for — and with
 * it every other share hashing to the same one of the share service's 64 lock
 * stripes, which is one share in 64 on that node, of any calendar on any
 * server, not only of this one.
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
 * sweep's, is the Architect's and Ops' call. The claim EXO-90920 adds bounds
 * the sweep to one node per period.</li>
 * <li><b>Nodes share no lock.</b> The lock above is this node's: a drain on
 * one node and a revoke on another can still interleave, and the revoke's
 * unsubscribe can be followed by the drain's subscribe landing, because
 * BlueMind performs no access check on a subscribe. What it leaves is one
 * stale calendar in that colleague's listing — not access to anything they
 * could not already see, since BlueMind enforces its own ACL on the contents
 * ({@code CalendarService} checks {@code Verb.Read} on the container for every
 * read, subscribed or not). Closing it across nodes needs a claim or a version
 * column on {@code CALDAV_PENDING_SUBSCRIPTION}, an Ops-visible schema
 * change, like the lease the third limit lacks: EXO-90920.</li>
 * </ul>
 */
@Service
public class CaldavShareSubscriptionService {

  /** How many owed rows the colleague's own pass drains before it lists anything. */
  public static final int                          OWN_DRAIN_BATCH = 10;

  /**
   * How many pages of owed rows one global drain reads at most, looking past the
   * rows of servers whose credentials provider is not installed. Beyond it, the
   * rows further back wait for their colleague's own pass, or for the provider to
   * be installed: a later global run reads the same pages.
   */
  static final int                                 MAX_PAGES_PER_DRAIN = 20;

  private static final Log                         LOG             = ExoLogger.getLogger(CaldavShareSubscriptionService.class);

  /** How many locks the obligations are striped over. */
  private static final int                         LOCK_STRIPES    = 64;

  /**
   * The locks serialising, on this node, everything that decides or settles
   * one colleague's obligation about one container: the grant's or the
   * revoke's attempt and its settle, and the drain's re-read, post and
   * settle of the same row.
   */
  private final Lock[]                             locks           = newLocks();

  /**
   * How many refusals are argued with before a change is given up on: the
   * meeting-copy bound, reused (the PO's decision of 2026-09-16). It counts the
   * retries and not the attempt that failed first, as the push bound does.
   */
  @Value("${exo.agenda.caldav.push.maxAttempts:5}")
  private int                                      maxAttempts     = 5;

  /**
   * The installed subscription channels (EXO-90730): BlueMind's REST
   * subscription API among them. Optional: without one, a change eXo owes is
   * given up with a reason at the next drain rather than retried.
   */
  @Autowired(required = false)
  private CalendarSubscriptionChannelRegistry      calendarSubscriptionChannelRegistry;

  @Autowired
  private CaldavPendingSubscriptionStorage         caldavPendingSubscriptionStorage;

  @Autowired
  private CaldavSyncStorage                        caldavSyncStorage;

  @Autowired
  private CalDavClient                             calDavClient;

  @Autowired
  private CaldavConnectionIdentityService          caldavConnectionIdentityService;

  @Autowired
  private IdentityManager                          identityManager;

  @Autowired
  private CaldavServerOwnerService                 caldavServerOwnerService;

  @Autowired
  private CaldavServerService                      caldavServerService;

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
   * colleague and server: what the sweep job calls, because a colleague who
   * holds no pair is never reached by the account sweep.
   *
   * <p>
   * The rows of a server whose credentials provider is not installed are left
   * where they are, uncounted, and the run reads on past them, up to
   * {@value #MAX_PAGES_PER_DRAIN} pages: the rows owed on the other servers
   * within those pages are drained, whatever waits ahead of them.
   *
   * @param batch how many owed rows one run drains at most
   * @return how many changes landed this run
   */
  public int retryOwed(int batch) {
    List<PendingSubscription> owed;
    try {
      owed = attemptableOnUsableServers(batch);
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The subscription changes eXo owes on calendar servers could not be read; nothing is retried this run", e);
      return 0;
    }
    return drain(owed);
  }

  /**
   * The oldest owed rows on servers that can be talked to, up to one batch: the
   * pages are read in order, and the rows of a server whose credentials provider
   * is not installed are skipped, not counted.
   *
   * @param batch how many rows to collect at most, and the page size
   * @return the rows to drain this run, oldest first
   */
  private List<PendingSubscription> attemptableOnUsableServers(int batch) {
    List<PendingSubscription> usable = new ArrayList<>();
    Map<Long, Boolean> waiting = new HashMap<>();
    int skipped = 0;
    for (int page = 0; page < MAX_PAGES_PER_DRAIN && usable.size() < batch; page++) {
      List<PendingSubscription> rows = caldavPendingSubscriptionStorage.attemptable(maxAttempts, page, batch);
      skipped += collectUsable(rows, usable, waiting, batch);
      if (rows.size() < batch) {
        break;
      }
    }
    if (skipped > 0) {
      LOG.debug("{} owed subscription change(s) wait for their server's credentials provider", skipped);
    }
    return usable;
  }

  /**
   * Adds to the collection the rows of one page whose server can be talked to,
   * until it holds one batch.
   *
   * @param rows the page, oldest first
   * @param usable the rows collected so far, added to
   * @param waiting whether each server met so far waits for its provider, filled
   *          as servers are met
   * @param batch how many rows to collect at most
   * @return how many rows of the page were skipped as waiting
   */
  private int collectUsable(List<PendingSubscription> rows,
                            List<PendingSubscription> usable,
                            Map<Long, Boolean> waiting,
                            int batch) {
    int skipped = 0;
    for (PendingSubscription row : rows) {
      if (usable.size() >= batch) {
        break;
      }
      if (Boolean.TRUE.equals(waiting.computeIfAbsent(row.getServerId(), this::waitsForItsProvider))) {
        skipped++;
      } else {
        usable.add(row);
      }
    }
    return skipped;
  }

  /**
   * Whether a server's credentials provider is not installed, so nothing owed on
   * it can be attempted yet.
   *
   * @param serverId the server key, zero for the legacy property
   * @return true when its registration names a provider that is not installed
   */
  private boolean waitsForItsProvider(long serverId) {
    return caldavServerService != null && caldavServerService.missingProviderOf(serverId == 0L ? null : serverId) != null;
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
      owed = caldavPendingSubscriptionStorage.attemptableOf(userIdentityId, maxAttempts, batch);
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The subscription changes eXo owes user {} on calendar servers could not be read; nothing is retried", userIdentityId, e);
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
    Lock lock = lockOf(share.shareeIdentityId(), share.serverId(), share.containerUid());
    lock.lock();
    try {
      SubscriptionAttempt attempt = attempt(kind, share.serverId(), share.shareeUsername(), share.shareeUid(), share.containerUid());
      if (attempt.outcome() == SubscriptionOutcome.LANDED) {
        // The sharee's mailbox now sees the calendar (or no longer does): what
        // eXo remembers of its owners is stale (EXO-90347).
        caldavServerOwnerService.evict(share.shareeIdentityId(), share.serverId());
        LOG.info("CalDAV share followed by a calendar subscription: user {} (entry {}) {} calendar container {} shared by {} on server {}",
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
    } finally {
      lock.unlock();
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
    CalendarSubscriptionChannel channel = channels().channelOf(principal);
    String shareeUid = channel.userUidOf(principal);
    if (shareeUid == null) {
      return giveUpAll(rows, reasonNoUidFor(principal));
    }
    List<CalendarSync> pairs = caldavSyncStorage.getPairs(userIdentityId, serverId);
    if (pausedAccount(pairs)) {
      // Their own account is paused by a refused login: a login as them now
      // may be refused again, and counted against a server that may lock the
      // account. The rows wait, uncounted, until the account is put back to
      // work. A pause for failing imports says nothing about the login and
      // does not reach here.
      LOG.info("The CalDAV account of user {} on server {} is paused; the {} subscription change(s) owed to them wait",
               userIdentityId,
               serverId,
               rows.size());
      return 0;
    }
    CalDavEndpoint endpoint;
    try {
      endpoint = endpointOf(serverId, login);
    } catch (CalDavProviderMissingException e) {
      // The server's credentials provider is not installed yet, which the
      // resolver has said once for its name. No attempt is counted against
      // the rows: they stay owed as they are, and land at the first drain
      // after the provider appears instead of running out of attempts first.
      LOG.debug("The subscription changes owed to user {} on server {} wait for their credentials provider: {}",
                userIdentityId,
                serverId,
                e.getMessage());
      return 0;
    } catch (CalDavException e) {
      return retryAll(rows, "their endpoint could not be minted: " + e.getMessage());
    }
    int[] landed = { 0 };
    try {
      channel.asSubscriber(endpoint, shareeUid, edits -> {
        for (PendingSubscription row : rows) {
          SubscriptionAttempt attempt = attemptUnderLock(edits, row);
          if (attempt == null) {
            continue;
          }
          if (attempt.outcome() == SubscriptionOutcome.LANDED) {
            landed[0]++;
          }
          if (attempt.outcome() == SubscriptionOutcome.RETRY && attempt.sessionLost()) {
            // The session itself was refused or the server went away: the
            // rows not yet tried are left as they are for the next run.
            break;
          }
        }
        return null;
      });
    } catch (CalDavSubjectMismatchException | UnsupportedOperationException e) {
      return giveUpAll(rows, e.getMessage());
    } catch (CalDavAuthenticationException e) {
      // The login itself was refused. Like the sync pass, the account is
      // paused at the first refusal, and the rows wait uncounted; a colleague
      // who holds no pair on the server has nothing to pause, and the rows
      // spend their bounded budget instead.
      if (pauseActive(userIdentityId, serverId)) {
        LOG.warn("The CalDAV account of user {} on server {} refused its stored credentials; its synchronisation is paused"
            + " and the subscription changes owed to it wait", userIdentityId, serverId);
        return 0;
      }
      return retryAll(rows, e.getMessage());
    } catch (CalDavException e) {
      // The login itself: unreachable, or an unexplained answer.
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
  private SubscriptionAttempt attempt(PendingSubscriptionKind kind, long serverId, String shareeUsername, String shareeUid, String containerUid) {
    try {
      CalDavEndpoint endpoint = endpointOf(serverId, shareeUsername);
      return channels().primary().asSubscriber(endpoint, shareeUid, edits -> attemptInSession(edits, kind, containerUid));
    } catch (CalDavSubjectMismatchException | UnsupportedOperationException e) {
      return new SubscriptionAttempt(SubscriptionOutcome.FINAL, e.getMessage(), false);
    } catch (CalDavException e) {
      return new SubscriptionAttempt(SubscriptionOutcome.RETRY, e.getMessage(), true);
    } catch (RuntimeException e) {
      // Not a server answer: eXo's own machinery failed on the way - a
      // credentials provider that did not produce what its channel promised,
      // a session key the transport will not put in a header. It is still an
      // obligation the colleague is owed, and the line above this one is the
      // reason it must be classified rather than thrown: an unclassified
      // escape leaves NO row, and an obligation with no row is never retried
      // and never seen again.
      LOG.warn("The calendar subscription of user {} failed before any answer was read", shareeUsername, e);
      return new SubscriptionAttempt(SubscriptionOutcome.RETRY, String.valueOf(e), true);
    }
  }

  /**
   * One drained row tried and settled under its obligation's lock, after
   * reading it again: a grant or a revoke of the same container that ran since
   * the drain read its rows has already decided, and the row it left — none,
   * or one asking for the other change — is what stands.
   *
   * @param edits the open session's edits
   * @param row the row as the drain read it
   * @return how it ended, or null when the row no longer asks for its change
   */
  private SubscriptionAttempt attemptUnderLock(SubscriptionEdits edits, PendingSubscription row) {
    Lock lock = lockOf(row.getUserIdentityId(), row.getServerId(), row.getContainerUid());
    lock.lock();
    try {
      if (!caldavPendingSubscriptionStorage.stillAsking(row.getId(), row.getKind())) {
        LOG.debug("Owed calendar {} for user {} and calendar container {} on server {} was decided again since it was read;"
            + " not attempted", row.getKind(), row.getUserIdentityId(), row.getContainerUid(), row.getServerId());
        return null;
      }
      SubscriptionAttempt attempt = attemptInSession(edits, row.getKind(), row.getContainerUid());
      settle(row, attempt);
      return attempt;
    } finally {
      lock.unlock();
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
  private static SubscriptionAttempt attemptInSession(SubscriptionEdits edits, PendingSubscriptionKind kind, String containerUid) {
    try {
      if (kind == PendingSubscriptionKind.UNSUBSCRIBE) {
        edits.unsubscribe(containerUid);
      } else {
        edits.subscribe(containerUid);
      }
      return new SubscriptionAttempt(SubscriptionOutcome.LANDED, null, false);
    } catch (CalDavForbiddenException | CalDavNotFoundException e) {
      return new SubscriptionAttempt(SubscriptionOutcome.FINAL, e.getMessage(), false);
    } catch (CalDavAuthenticationException | CalDavUnreachableException e) {
      return new SubscriptionAttempt(SubscriptionOutcome.RETRY, e.getMessage(), true);
    } catch (CalDavException e) {
      return new SubscriptionAttempt(SubscriptionOutcome.RETRY, e.getMessage(), false);
    } catch (RuntimeException e) {
      // Same reason as at grant time, with a different cost: an escape here
      // leaves the row exactly as it was, and a row that is never counted is
      // handed out again at the head of every sweep - one login as the
      // colleague per run, for ever. Counted, it spends its budget like any
      // other refusal and stops.
      return new SubscriptionAttempt(SubscriptionOutcome.RETRY, String.valueOf(e), false);
    }
  }

  /**
   * Writes one drained row's outcome and says so, once per row.
   *
   * @param row the row
   * @param attempt how its change ended
   */
  private void settle(PendingSubscription row, SubscriptionAttempt attempt) {
    switch (attempt.outcome()) {
    case LANDED -> {
      caldavPendingSubscriptionStorage.settledIfStillAsking(row.getUserIdentityId(),
                                                          row.getServerId(),
                                                          row.getContainerUid(),
                                                          row.getKind());
      LOG.info("Owed calendar {} landed: user {} and calendar container {} on server {}",
               row.getKind(),
               row.getUserIdentityId(),
               row.getContainerUid(),
               row.getServerId());
    }
    case FINAL -> {
      caldavPendingSubscriptionStorage.abandoned(row.getId(), row.getKind(), maxAttempts);
      LOG.info("Owed calendar {} given up on: user {} and calendar container {} on server {}: {}",
               row.getKind(),
               row.getUserIdentityId(),
               row.getContainerUid(),
               row.getServerId(),
               attempt.reason());
    }
    case RETRY -> {
      caldavPendingSubscriptionStorage.refused(row.getId(), row.getKind());
      LOG.info("Owed calendar {} refused ({} of {} retries): user {} and calendar container {} on server {}: {}",
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
      settle(row, new SubscriptionAttempt(SubscriptionOutcome.FINAL, reason, false));
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
      settle(row, new SubscriptionAttempt(SubscriptionOutcome.RETRY, reason, true));
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
   * Whether an account is paused for its credentials: it holds pairs on the
   * server, none is active, and one of them was paused by a refused login. A
   * pair paused for failing imports does not count: it says nothing about the
   * login, and an account whose every pair failed its imports keeps its
   * subscriptions moving. A pause recorded before the reason was
   * ({@code null}) counts as a credential pause: waiting on it costs a sweep,
   * logging in through it may cost the account.
   *
   * @param pairs the account's pairs on the server
   * @return true when the drain must not log in as this colleague
   */
  private static boolean pausedAccount(List<CalendarSync> pairs) {
    return pairs.stream().noneMatch(pair -> pair.getStatus() == CalendarSyncStatus.ACTIVE)
        && pairs.stream().anyMatch(CaldavShareSubscriptionService::pausedForCredentials);
  }

  private static boolean pausedForCredentials(CalendarSync pair) {
    return pair.getStatus() == CalendarSyncStatus.PAUSED
        && (pair.getPauseReason() == null || pair.getPauseReason() == CalendarSyncPauseReason.CREDENTIALS);
  }

  /**
   * Records a refused login on an account's pairs, as the sync pass does,
   * reading them again at pause time: the login took a round trip, and a
   * concurrent pass may have written them meanwhile. Every active pair is
   * paused for its credentials, and a pair paused for another reason is
   * re-attributed to them, since the login was just refused. An account that
   * holds pairs waits whatever state the fresh read found them in; only a
   * colleague with no pair on the server at all is retried, and counted.
   *
   * @param userIdentityId the colleague
   * @param serverId the server key
   * @return true when the account holds pairs on the server
   */
  private boolean pauseActive(long userIdentityId, long serverId) {
    List<CalendarSync> pairs = caldavSyncStorage.getPairs(userIdentityId, serverId);
    for (CalendarSync pair : pairs) {
      boolean active = pair.getStatus() == CalendarSyncStatus.ACTIVE;
      boolean pausedOtherwise = pair.getStatus() == CalendarSyncStatus.PAUSED
          && pair.getPauseReason() != CalendarSyncPauseReason.CREDENTIALS;
      if (active || pausedOtherwise) {
        pair.setStatus(CalendarSyncStatus.PAUSED);
        pair.setPauseReason(CalendarSyncPauseReason.CREDENTIALS);
        caldavSyncStorage.savePair(pair);
      }
    }
    return !pairs.isEmpty();
  }

  /**
   * The lock of one colleague's obligation about one container.
   *
   * @param shareeIdentityId the colleague
   * @param serverId the server key
   * @param containerUid the container
   * @return the lock
   */
  private Lock lockOf(long shareeIdentityId, long serverId, String containerUid) {
    String key = shareeIdentityId + "@" + serverId + ":" + containerUid;
    return locks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
  }

  private static Lock[] newLocks() {
    Lock[] created = new Lock[LOCK_STRIPES];
    for (int i = 0; i < LOCK_STRIPES; i++) {
      created[i] = new ReentrantLock();
    }
    return created;
  }

  /**
   * Why no uid can be read for a colleague's recorded principal: they are no
   * longer connected, no subscription channel is installed, or the installed
   * ones address no such principal.
   *
   * @param principal the recorded principal, may be null
   * @return the reason, for the abandonment line
   */
  private String reasonNoUidFor(String principal) {
    if (principal == null) {
      return "they are no longer connected to that server";
    }
    return channels().isEmpty() ? "no subscription channel is installed for that server"
                                : "their recorded principal is not one the installed subscription channel names";
  }

  /**
   * The installed subscription channels, or none.
   *
   * @return the registry, never null
   */
  private CalendarSubscriptionChannelRegistry channels() {
    return calendarSubscriptionChannelRegistry == null ? CalendarSubscriptionChannelRegistry.of(List.of())
                                                       : calendarSubscriptionChannelRegistry;
  }
}
