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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import io.meeds.common.ContainerTransactional;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Keeps a user's eXo calendars and their remote collections in step, in both
 * directions.
 *
 * <p>
 * The outward half already exists: eXo calendars get collections of their own.
 * This adds the inward one — a calendar the user made in their own client
 * becomes an eXo personal calendar — and the entry point that runs both.
 */
@Service
public class CaldavSyncService {

  private static final Log            LOG        = ExoLogger.getLogger(CaldavSyncService.class);

  /**
   * When each user last synchronised, so opening the agenda repeatedly does not
   * mean talking to a calendar server repeatedly.
   *
   * <p>
   * In memory on purpose: a throttle is a courtesy to the server, not a fact
   * worth surviving a restart. Losing it costs one extra sync per user after a
   * restart, which is the cheap direction to be wrong in.
   */
  private final Map<Long, Instant>    lastSync   = new ConcurrentHashMap<>();

  /**
   * Users a sync is running for, so two page loads a second apart do not run
   * two syncs against the same account at once.
   */
  /**
   * How many times in a row one collection may fail before eXo stops reading
   * it. Enough to ride out a server having a bad minute, few enough that a
   * genuine breakage stops costing every page load.
   *
   * <p>
   * The count itself lives on the binding — the column has been there since
   * the schema landed and nothing had ever written to it — so it survives a
   * restart. That is the right bias here and the opposite of the throttle's:
   * a collection that has failed four times running has not been repaired by
   * the server being restarted.
   */
  private static final int            MAX_CONSECUTIVE_FAILURES = 5;

  /**
   * How long a caller that asked to be told when the sync had run will wait
   * for a pass already in flight. Generous enough to cover a first pass over
   * a real account, short enough that an HTTP thread is never held hostage by
   * one that has wedged.
   */
  private static final long           IN_FLIGHT_WAIT_SECONDS   = 20L;

  /**
   * The pieces of outward work a pass may do once the calendars themselves are
   * in step — each named, and each governed on its own.
   *
   * <p>
   * <b>Why an enumerated set and not a boolean.</b> These three used to sit
   * behind one parameter called {@code verifyMirror}, and a name describing one
   * of them governed all three. Connecting asked for no verification, which is
   * right — the check lists whole collections and was measured at 30 seconds —
   * and silently lost the two cheap ones with it, so an event a user had
   * created before connecting was copied by nothing until a sweep came round
   * a quarter of an hour later (EXO-89804). Nobody decided that; adjacency did.
   *
   * <p>
   * A second boolean beside the first would have reproduced the defect one
   * notch along: the fourth piece of outward work would arrive as a third
   * boolean, every call site would grow another unlabelled {@code false}, and
   * {@code sync(id, login, true, false, true)} says nothing at all about what
   * the pass will do. A set names each piece where the call is written —
   * {@code EnumSet.of(SETTLE_OWED_COPIES, SEED_UPCOMING_COPIES)} reads as the
   * decision it is — and a phase added tomorrow changes no existing signature
   * and no existing call: it is absent from every set that does not name it,
   * which is the only safe default for work that talks to somebody's server.
   *
   * <p>
   * <b>The order they are declared in is the order they are done in</b>
   * ({@link #runOutboundPhases(long, Set)} walks {@code values()}), so the
   * ordering is stated once and cannot drift between a sweep and a connection.
   */
  public enum OutboundPhase {
    /**
     * Writes the copies eXo already knows it owes and has not managed to make.
     * Cheap on a converged account: one index lookup answering no rows.
     */
    SETTLE_OWED_COPIES,
    /**
     * Copies the upcoming events the account has never been given — the
     * backfill a freshly connected account needs, and the only thing that
     * carries an event created before the account existed.
     */
    SEED_UPCOMING_COPIES,
    /**
     * Reads back every copy eXo has written and repairs what has drifted.
     * Lists whole collections, measured at a full 30-second request timeout
     * against one real account: the sweep's alone, never a user's.
     */
    VERIFY_MIRROR
  }

  /**
   * What a pass triggered by someone looking at a page does outward: nothing.
   * Opening the agenda and pressing a button are reads, and a read must not
   * turn into a burst of writes to a calendar server.
   */
  private static final Set<OutboundPhase> NO_OUTBOUND_PHASES =
                                                             Collections.unmodifiableSet(EnumSet.noneOf(OutboundPhase.class));

  /**
   * What the background sweep does outward: all of it. It is the pass nobody
   * is waiting for, which is what makes it the right home for the expensive
   * check.
   */
  private static final Set<OutboundPhase> SWEEP_PHASES       =
                                                             Collections.unmodifiableSet(EnumSet.allOf(OutboundPhase.class));

  /**
   * What connecting an account does outward: settles what is owed and seeds
   * what the account has never been given — and does <b>not</b> verify.
   *
   * <p>
   * Taking the verification off the user's path was a deliberate decision and
   * stays one. What was never decided is that the two cheap phases went with
   * it.
   */
  private static final Set<OutboundPhase> SEEDING_PHASES     =
                                                             Collections.unmodifiableSet(EnumSet.of(OutboundPhase.SETTLE_OWED_COPIES,
                                                                                                   OutboundPhase.SEED_UPCOMING_COPIES));

  /**
   * How many accounts may be waiting to be seeded before the next one is
   * turned away.
   *
   * <p>
   * A bound, not a buffer. Turning an account away costs it nothing it cannot
   * recover: it holds a binding by the time it gets here, so the sweep can see
   * it and will seed it within the account-due interval. Holding an unbounded
   * queue of them, on the other hand, costs memory and turns a morning where
   * everyone connects at once into an hour of writes nobody asked for.
   */
  private static final int                SEEDING_QUEUE_DEPTH = 64;

  /**
   * The single thread every seeded copy is written from.
   *
   * <p>
   * One thread, platform-wide, on purpose. A fresh account can be up to
   * {@code seedLimit} events per question and two questions per pass, so one
   * connect is already a few hundred writes; twenty people connecting after a
   * migration, each on their own thread, is what a calendar server reads as an
   * attack, and the test rig's proxy was in fact banned by Stalwart's fail2ban
   * for exactly that — a ban that outlives a container restart. Serialised,
   * twenty connects cost the server the same instantaneous rate as one, and
   * they cost it in the order people connected.
   *
   * <p>
   * The per-account bound is untouched and stays where it belongs: the seeding
   * pass writes at most {@code seedLimit} events per question and leaves the
   * rest to the next pass, and every give-up rule on an individual copy is the
   * propagation service's.
   *
   * <p>
   * Package-visible so a test can put a recording executor here and say what
   * happened on the caller's thread and what was merely handed over — a
   * distinction a same-thread executor cannot make. Nothing outside this
   * package replaces it.
   */
  ExecutorService                         outboundExecutor    = newOutboundExecutor();

  /**
   * The accounts already queued or being seeded, so pressing a button twice
   * queues one burst and not two.
   */
  private final Set<Long>                 seeding             = ConcurrentHashMap.newKeySet();

  /**
   * The pass running for a user, so two page loads a second apart do not run
   * two syncs against the same account at once — and so a caller who was
   * promised the sync had run can wait for the one that is actually doing it.
   *
   * @param done completes when the pass leaves its body, however it leaves it
   * @param threadId the thread running the pass, so a pass that re-enters
   *          itself through a listener is recognised and never waits on its
   *          own completion
   */
  private record SyncPass(CompletableFuture<Void> done, long threadId) {
  }

  private final Map<Long, SyncPass>   syncing    = new ConcurrentHashMap<>();

  /** How long a sync stays fresh; deployment-tunable. */
  /**
   * How far back events are imported. A calendar with ten years of history
   * behind it would otherwise cost a full download on a page load.
   */
  /**
   * How far ahead events are imported.
   */
  @Autowired
  private CalDavClient                calDavClient;

  @Autowired
  private CaldavConnectorStorage      caldavConnectorStorage;

  @Autowired
  private CaldavSyncStorage           caldavSyncStorage;

  @Autowired
  private CaldavOutboundService       caldavOutboundService;

  @Autowired
  private CaldavInboundService        caldavInboundService;

  @Autowired
  private IdentityManager             identityManager;

  @Autowired
  private CaldavTuningService         caldavTuningService;

  @Autowired
  private CaldavMirrorVerificationService caldavMirrorVerificationService;

  @Autowired
  private CaldavPendingInvitationService caldavPendingInvitationService;

  @Autowired
  private CaldavEventPropagationService caldavEventPropagationService;

  @Autowired
  private AgendaCalendarService       agendaCalendarService;

  @Autowired
  private CaldavPushService           caldavPushService;

  /**
   * Synchronises the accounts that have gone longest without one.
   *
   * <p>
   * Called by the sweep so that an agenda opened afterwards finds the work
   * already done, rather than waiting on a server outside the platform before
   * it can list anything.
   *
   * <p>
   * Bounded on purpose: one run takes a page of the stalest bindings and
   * stops. A sweep that tries to cover every account in one pass is one that
   * eventually cannot finish, and the accounts it never reached are exactly
   * the ones that needed it.
   *
   * <p>
   * Bindings are grouped by account before anything is synchronised —
   * synchronisation is per account, not per collection, and an account with
   * five stale calendars must cost one pass rather than five.
   *
   * @param staleMinutes how long since a successful sync makes a binding due
   * @param batchSize how many stale bindings one run looks at
   * @return how many accounts were synchronised
   */
  public int sweepDueAccounts(long staleMinutes, int batchSize) {
    Date before = Date.from(Instant.now().minus(Duration.ofMinutes(staleMinutes)));
    // Accounts, not bindings. Paging the bindings and folding them into
    // accounts afterwards meant a batch could be filled by one user's
    // collections: a user holding forty of them, all stale after an outage,
    // took every run and nobody else was swept at all — the log said "swept 1
    // account" and looked like throughput rather than starvation. A batch of
    // ten is now ten users, whatever each of them holds.
    List<Long> accounts = caldavSyncStorage.getDueAccounts(CalendarSyncStatus.ACTIVE, before, 0, batchSize)
                                           .getContent();
    if (accounts.isEmpty()) {
      return 0;
    }
    int swept = 0;
    for (Long userIdentityId : accounts) {
      String username = loginOf(userIdentityId);
      if (username == null) {
        continue;
      }
      try {
        // syncNow, not syncIfDue: these bindings were selected precisely
        // because they are stale, and the throttle would refuse the very
        // accounts the sweep exists to reach. The per-user guard still makes
        // this return at once when the owner's own page load is already
        // synchronising them.
        // The background entry: this is the one pass that also verifies the
        // copies eXo pushed, because it is the only one nobody is waiting for.
        syncInBackground(userIdentityId, username);
        swept++;
      } catch (RuntimeException | LinkageError e) {
        // One account must not cost the rest of the page. It stays stale and
        // comes back at the top of the next run.
        //
        // LinkageError is caught beside the exceptions on purpose. A missing
        // optional dependency of the iCalendar parser — ical4j builds its
        // EMAIL parameter through commons-validator — surfaced as a
        // NoClassDefFoundError while reading an object a client had written,
        // and being an Error it walked straight past a RuntimeException
        // guard: the sweep died mid-run, every run, and every account after
        // the failing one silently stopped synchronising. A pass that visits
        // accounts on everyone's behalf cannot let one object end the pass.
        LOG.warn("The CalDAV account of user {} could not be swept", userIdentityId, e);
      }
    }
    return swept;
  }

  /**
   * The login of an identity, which agenda's ACL needs and a binding does not
   * carry.
   *
   * @param userIdentityId identity of the user
   * @return the login, or null when it cannot be resolved
   */
  private String loginOf(long userIdentityId) {
    Identity identity = identityManager.getIdentity(String.valueOf(userIdentityId));
    if (identity == null || StringUtils.isBlank(identity.getRemoteId())) {
      LOG.debug("Identity {} has no resolvable login; its account is left for the next run", userIdentityId);
      return null;
    }
    return identity.getRemoteId();
  }

  /**
   * When the connected account was last synchronised through to the end.
   *
   * <p>
   * Read from the bindings rather than from the in-memory throttle: the
   * throttle is a courtesy to the server and is emptied by a restart, so a
   * user opening their settings after one would be told they had never
   * synchronised. {@code lastSyncEnd} is written where the work finished and
   * survives.
   *
   * <p>
   * The latest across bindings, not the earliest: what the user wants to know
   * is when eXo last spoke to their account. A binding that has been failing
   * on its own is a different question, and one this line would only muddle.
   *
   * @param userIdentityId whose account
   * @return the instant, or null when nothing has ever synchronised
   */
  public Date lastSyncEnd(long userIdentityId) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (settings == null || StringUtils.isBlank(settings.getUsername())) {
      return null;
    }
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    return caldavSyncStorage.getPairs(userIdentityId, serverId)
                            .stream()
                            .map(CalendarSync::getLastSyncEnd)
                            .filter(Objects::nonNull)
                            .max(Date::compareTo)
                            .orElse(null);
  }

  /**
   * Synchronises a user's calendars if they have not been synchronised
   * recently.
   *
   * <p>
   * The trigger for opening the agenda. Doing nothing is the common answer and
   * has to be cheap: a page load must not wait on a calendar server that has
   * nothing new to say.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login, which agenda's ACL reads
   */
  public void syncIfDue(long userIdentityId, String username) {
    Instant last = lastSync.get(userIdentityId);
    if (last != null && last.isAfter(Instant.now().minus(Duration.ofMinutes(caldavTuningService.getThrottleMinutes())))) {
      return;
    }
    sync(userIdentityId, username, false, NO_OUTBOUND_PHASES);
  }

  /**
   * Synchronises now, whatever the throttle says.
   *
   * <p>
   * What a "sync now" button calls. It bypasses the throttle because a user
   * pressing it has a reason the throttle cannot know — they just changed
   * something on their phone and want to see it.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   */
  public void syncNow(long userIdentityId, String username) {
    sync(userIdentityId, username, false, NO_OUTBOUND_PHASES);
  }

  /**
   * Synchronises in the background, where the slow housekeeping belongs.
   *
   * <p>
   * The only entry that verifies the copies eXo pushed. That check lists a
   * whole collection, which on a real calendar can take longer than the request
   * timeout allows — measured at a full 30 seconds against one account, every
   * pass. Nobody is waiting for a repair, so making a user wait for one is pure
   * cost: pressing <em>Synchronise now</em> took 25 seconds, almost all of it
   * spent on a listing whose result the user would never see.
   *
   * <p>
   * So the user's paths skip it and the sweep keeps it. Repairs happen exactly
   * as often as before — the sweep runs every five minutes — they simply stop
   * happening on someone's click.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   */
  public void syncInBackground(long userIdentityId, String username) {
    sync(userIdentityId, username, false, SWEEP_PHASES);
  }

  /**
   * Synchronises now and does not return until it has.
   *
   * <p>
   * The difference from {@link #syncNow(long, String)} is what happens when a
   * pass is already running for this user. Returning then — as every caller
   * used to — reports a synchronisation that has not happened yet, and a
   * caller that refreshes its display on the strength of that reads the
   * account mid-pass: collections not yet materialised still count as remote,
   * so they are shown under the heading for calendars eXo is not holding, and
   * stay there until the page is reloaded. Waiting for the pass in flight
   * makes the answer true when it is given.
   *
   * <p>
   * Bounded, and never waits on itself: a pass that re-enters through a
   * listener is recognised by its thread and returns immediately, as it
   * always did.
   *
   * <p>
   * <b>This is where connecting an account lands</b>, and it is why the
   * seeding is started from here. Agenda's connect sequence is: the drawer
   * stores the credentials, then agenda records the connected account in the
   * user's agenda settings, then it calls the connector's {@code sync()},
   * which is this. Only the last of the three runs with the account recorded —
   * and recorded is what {@code CaldavPendingInvitationService} reads to know
   * the user consented to copies at all, so a seeding hung on the earlier
   * steps would run, find no consent, and copy nothing. The user's own
   * <i>Synchronise now</i> reaches the same entry, and seeds too: on a
   * converged account that is one query answering "everything is already
   * copied".
   *
   * <p>
   * Started, never awaited: see {@code startSeedingCopies} below.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   */
  public void syncNowAndWait(long userIdentityId, String username) {
    sync(userIdentityId, username, true, NO_OUTBOUND_PHASES);
    // After the pass, not inside it: the copies are written into the
    // collections the pass has just bound, so starting them earlier would be
    // starting them without a destination.
    startSeedingCopies(userIdentityId);
  }

  /**
   * Starts, on a thread of its own, the copies a freshly connected account is
   * owed — and returns without waiting for a single one of them.
   *
   * <p>
   * <b>Started, not awaited, and that is the requirement.</b> A fresh account
   * can be a few hundred writes ({@code seedDays} reaches sixty days ahead by
   * default), each of them a round trip to somebody else's server. Whoever
   * pressed <i>Connect</i> is watching a spinner, and the answer they are
   * waiting for — "your account was accepted" — is already known by the time
   * this is reached.
   *
   * <p>
   * A plain executor thread is enough here, and deliberately so:
   * {@code @ContainerTransactional} <i>establishes</i> the container rather
   * than requiring one — it reads the current container, falls back to the
   * portal container and runs the request lifecycle around the call — so
   * {@link #seedCopies(long)} carries its own. The trap that annotation does
   * not cover is a thread running before the portal container is up, at
   * startup; this one is started by a request, long after. The legacy
   * {@code @ExoTransactional} would be the wrong annotation for exactly the
   * reason it is wrong on the sweep job: its aspect demands a container
   * already bound and throws when there is none.
   *
   * <p>
   * Nothing here may reach the caller. A connection that succeeded must not be
   * reported as failed because a queue was full or a thread pool was shutting
   * down — the same rule the destination resolution answers to.
   *
   * @param userIdentityId identity of the user whose account is to be seeded
   */
  private void startSeedingCopies(long userIdentityId) {
    if (!connected(caldavConnectorStorage.getCaldavSetting(userIdentityId))) {
      return;
    }
    if (!seeding.add(userIdentityId)) {
      // Already queued or already running. Pressing the button twice is one
      // burst, not two: the second would find every copy of the first already
      // mapped and write nothing, having asked the server about all of them.
      LOG.debug("The copies of user {} are already being seeded; this request joins that one", userIdentityId);
      return;
    }
    try {
      outboundExecutor.execute(() -> {
        try {
          seedCopies(userIdentityId);
        } finally {
          seeding.remove(userIdentityId);
        }
      });
    } catch (RejectedExecutionException e) {
      seeding.remove(userIdentityId);
      // Not an error, and not lost: the account holds a binding by now, so the
      // sweep can see it and seeds it within the account-due interval. Said at
      // debug because the queue filling is the bound working, not a fault.
      LOG.debug("The copies of user {} are left to the sweep; too many accounts are already waiting to be seeded",
                userIdentityId);
    } catch (RuntimeException | LinkageError e) {
      seeding.remove(userIdentityId);
      LOG.warn("The copies of user {} could not be started on connect; a sweep seeds them", userIdentityId, e);
    }
  }

  /**
   * Settles what eXo owes this account and copies what it has never been
   * given.
   *
   * <p>
   * The body of the background seeding, on its own method because it is what
   * carries the container: an executor thread has none bound, and
   * {@code @ContainerTransactional} is what establishes one around the calls
   * that read agenda and write the mapping rows.
   *
   * <p>
   * Public because the aspect is woven on method <i>execution</i> and the
   * platform weaves what it can see; and because it is the seam a test runs to
   * pin that connecting seeds, and does not verify. A test that runs it states
   * a container first, exactly as the sweep job's test does — the annotation
   * building a portal in a unit-test JVM is it working, not failing.
   *
   * @param userIdentityId identity of the user whose account receives copies
   */
  @ContainerTransactional
  public void seedCopies(long userIdentityId) {
    runOutboundPhases(userIdentityId, SEEDING_PHASES);
  }

  /**
   * Synchronises because a calendar has just been created.
   *
   * <p>
   * The engine needs the owner's login to satisfy agenda's ACL, and a listener
   * has only their identity — resolving it is this method's reason to exist,
   * and it keeps the listener the glue it is meant to be.
   *
   * <p>
   * Nothing happens for a user with no connected account, which is most of
   * them: a calendar created by someone who never set up CalDAV must not cost
   * an identity lookup and a wasted pass.
   *
   * @param userIdentityId identity of the calendar's owner
   */
  public void syncAfterCalendarCreated(long userIdentityId) {
    if (!connected(caldavConnectorStorage.getCaldavSetting(userIdentityId))) {
      return;
    }
    Identity identity = identityManager.getIdentity(String.valueOf(userIdentityId));
    if (identity == null || StringUtils.isBlank(identity.getRemoteId())) {
      LOG.debug("Calendar owner {} has no resolvable login; the next sync will carry the calendar", userIdentityId);
      return;
    }
    // syncNow rather than syncIfDue: the point is that the user does not wait.
    // The concurrency guard keeps this harmless when the calendar was itself
    // created by a sync that is still running — that pass returns immediately
    // rather than recursing into itself.
    syncNow(userIdentityId, identity.getRemoteId());
  }

  /**
   * Establishes, there and then, the collections this user's copies are
   * written into.
   *
   * <p>
   * <b>What connecting an account calls, and the half of it that was
   * missing.</b> Connecting used to store the credentials, thaw whatever
   * disconnecting had frozen, and forget the throttle — three pieces of
   * bookkeeping, none of which gives a copy anywhere to go. The destinations
   * were established later and elsewhere: the personal collections by the
   * first synchronisation, the mirror by the calendar step of the agenda
   * connector panel. Both are the browser's doing, and neither is guaranteed —
   * an account connected from a page that does not offer the step, or through
   * the REST API, ended up connected with no destination at all.
   *
   * <p>
   * Until one exists, every copy is refused in a way nobody can see:
   * {@link CaldavPushService#pushAgendaEvent(long, long)} answers null for an
   * event of a calendar with no collection — a 204 the page renders as
   * nothing — and refuses a space meeting with a known-state code the seeding
   * pass logs at debug. No error, no queue, no trace: exactly the "it does not
   * work" this is reported as.
   *
   * <p>
   * <b>And the wait was not merely long, it could be endless.</b> The sweep
   * picks its accounts from the bindings that already exist
   * ({@code findDueAccounts} selects user identities out of
   * {@code CALDAV_CALENDAR_SYNC}), so an account that never acquired one is
   * invisible to it for ever. The wait people saw — the account-due interval —
   * is what happens once a first binding exists; without one, nothing in the
   * platform was ever going to make it.
   *
   * <p>
   * Narrow on purpose: this binds the calendars and establishes the mirror,
   * and does <b>not</b> import events or seed the meetings the user already
   * has. Those are the expensive halves of a pass, they are what the first
   * synchronisation and the sweep are for, and nobody clicking <i>Connect</i>
   * should wait on a download to find out whether their account was accepted.
   * What this buys is that the next event they create has somewhere to go.
   *
   * <p>
   * Neither step is allowed to fail the connection, and they fail
   * independently: a server that refuses to create a collection for a personal
   * calendar may still hold a perfectly good mirror, and the user is better
   * off with the half that worked.
   *
   * <p>
   * <b>Independently, but not blindly.</b> Two failures are not properties of
   * the step that met them but of the server itself — its credentials refused,
   * or nothing there at all — and are therefore settled after one attempt.
   * Running the second step after either of them cannot succeed, and it is not
   * free: it is a second authenticated request against a calendar server that
   * may be counting failures and banning the source address for them
   * (EXO-89806). So on those two, and only those two, this stops after the
   * first step and says so once.
   *
   * @param userIdentityId identity of the user who has just connected
   */
  public void establishDestinations(long userIdentityId) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!connected(settings)) {
      return;
    }
    String username = loginOf(userIdentityId);
    if (username == null) {
      return;
    }
    if (bindOwnCalendarsNow(userIdentityId, username, settings)) {
      ensureMirrorNow(userIdentityId);
    }
  }

  /**
   * Gives each of the user's own calendars its collection, absorbing whatever
   * the server does about it.
   *
   * <p>
   * The one place the server-level refusal of a connection is written down, so
   * that it is written down <b>once</b>: the calendars step is the first thing
   * a connection asks the server, and everything after it would only rediscover
   * the same answer. The line names the server and the user, because an
   * administrator reading it has neither in front of them, and it distinguishes
   * the two cases the caller cannot act on alike — a refusal only the account's
   * owner can clear, versus a server that is not answering at all.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login, which agenda's ACL reads
   * @param settings the connected account, for the server it names
   * @return whether the connection may go on to establish the mirror — false
   *         when the server itself refused, true otherwise, the per-calendar
   *         refusals included
   */
  private boolean bindOwnCalendarsNow(long userIdentityId, String username, CaldavUserSetting settings) {
    try {
      caldavOutboundService.bindPersonalCalendars(userIdentityId, username);
      return true;
    } catch (CalDavAuthenticationException e) {
      LOG.warn("CalDAV server {} refused the credentials of user {} on connect; nothing else was asked of it",
               serverIdOf(settings),
               userIdentityId,
               e);
      return false;
    } catch (CalDavUnreachableException e) {
      LOG.warn("CalDAV server {} could not be reached for user {} on connect; nothing else was asked of it",
               serverIdOf(settings),
               userIdentityId,
               e);
      return false;
    } catch (Exception | LinkageError e) {
      LOG.warn("The personal calendars of user {} could not be bound on connect; the first sweep binds them", userIdentityId, e);
      return true;
    }
  }

  /**
   * The declared server an account speaks to, named the way a log line can
   * name it.
   *
   * @param settings the connected account
   * @return the registration id, or 0 for a legacy account referencing none
   */
  private long serverIdOf(CaldavUserSetting settings) {
    return settings == null || settings.getServerId() == null ? 0L : settings.getServerId();
  }

  /**
   * Establishes the calendar the copies of space meetings go into.
   *
   * <p>
   * A known state is said at debug and not at warning, the same way
   * {@link CaldavPendingInvitationService} says it: an account naming no
   * default calendar on a server configured to write into the main one is a
   * state only its owner or their administrator can clear, and no amount of
   * retrying changes it. Warning here would print a stack trace on a
   * successful connection, for a condition the settings screen already names
   * in words the user can act on.
   *
   * @param userIdentityId identity of the user
   */
  private void ensureMirrorNow(long userIdentityId) {
    try {
      caldavPushService.ensureMirror(userIdentityId);
    } catch (CaldavPushException e) {
      if (CaldavPushService.isKnownState(e.getCode())) {
        LOG.debug("The copies of user {} have no destination yet: {} ({})", userIdentityId, e.getMessage(), e.getCode());
      } else {
        LOG.warn("The calendar the copies of user {} go into could not be established on connect", userIdentityId, e);
      }
    } catch (Exception | LinkageError e) {
      LOG.warn("The calendar the copies of user {} go into could not be established on connect", userIdentityId, e);
    }
  }

  /**
   * Forgets when this user last synchronised.
   *
   * <p>
   * What connecting an account calls. Someone who has just entered their
   * credentials is owed their calendars now, not in a quarter of an hour —
   * and a throttle stamped by the previous account's run has nothing to say
   * about the new one.
   *
   * @param userIdentityId identity of the user
   */
  public void forgetThrottle(long userIdentityId) {
    lastSync.remove(userIdentityId);
  }

  /**
   * One synchronisation pass, outward then inward.
   *
   * <p>
   * Outward first, and it matters: binding the user's own calendars marks
   * their collections ORIGIN=EXO, which is precisely what the inward pass then
   * skips. Run the other way round on a fresh account and every collection eXo
   * is about to create would first be materialised as a second eXo calendar.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   * @param awaitPassInFlight whether to wait on a pass already running for this
   *          user instead of returning on the strength of it — what
   *          {@link #syncNowAndWait(long, String)} needs so the answer it gives
   *          is true when it is given. Never waits on the calling thread's own
   *          pass.
   * @param outboundPhases which pieces of outward work this pass does once the
   *          calendars are in step, each named rather than implied — see
   *          {@link OutboundPhase}. Empty for every pass somebody is waiting
   *          for; the whole set for the sweep, which is the only pass that may
   *          spend 30 seconds verifying.
   */
  private void sync(long userIdentityId, String username, boolean awaitPassInFlight, Set<OutboundPhase> outboundPhases) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!connected(settings)) {
      return;
    }
    // One sync per user at a time. Two page loads a second apart would
    // otherwise both create the same calendar, and the second would find the
    // first's collection listed and bind a duplicate to it.
    SyncPass pass = new SyncPass(new CompletableFuture<>(), Thread.currentThread().threadId());
    SyncPass running = syncing.putIfAbsent(userIdentityId, pass);
    if (running != null) {
      // Only a caller that was promised the sync had run waits, and never for
      // a pass it is itself inside: a calendar created by a sync notifies a
      // listener that syncs, and waiting there would be waiting on this very
      // thread to finish what it is in the middle of.
      if (awaitPassInFlight && running.threadId() != Thread.currentThread().threadId()) {
        awaitPass(userIdentityId, running);
      }
      return;
    }
    try {
      caldavOutboundService.bindPersonalCalendars(userIdentityId, username);
      // Before materialising, not after: a binding with nothing behind it is
      // exactly what makes materialisation skip a collection, so healing it
      // afterwards leaves the user waiting a whole throttle window for a
      // calendar that could have come back in this same pass.
      pruneOrphanBindings(userIdentityId, username, settings);
      List<CalendarCollection> collections = materialiseRemoteCalendars(userIdentityId, username, settings);
      importRemoteEvents(userIdentityId, username, settings, collections);
      runOutboundPhases(userIdentityId, outboundPhases);
      lastSync.put(userIdentityId, Instant.now());
    } catch (CalDavAuthenticationException e) {
      // Immediately, and before anything else is tried: a stale password
      // retried on every page load is a login attempt every few minutes
      // against a server that may well be counting them, and locking the
      // account is a worse outcome than not synchronising.
      LOG.warn("The CalDAV account of user {} refused its stored credentials; synchronisation is paused",
               userIdentityId,
               e);
      pauseAll(userIdentityId, settings);
    } catch (CalDavUnreachableException e) {
      // Named apart from the failure below, and stopping the pass here rather
      // than letting its five steps each rediscover the same absence: an
      // unreachable server is a property of the server, settled after one
      // attempt, and a burst of failed authenticated requests is how a source
      // address earns a persistent ban (EXO-89806). Not paused, unlike a
      // credential refusal — nobody has to do anything for a server to come
      // back, and the next pass is the one that finds out.
      LOG.warn("CalDAV server {} could not be reached for user {}; this pass asked it nothing more",
               serverIdOf(settings),
               userIdentityId,
               e);
    } catch (RuntimeException e) {
      // A sync that fails is not an error the caller can act on — the page it
      // was triggered from has its own events to show. It is logged and the
      // next trigger tries again.
      LOG.warn("Synchronising the CalDAV calendars of user {} failed", userIdentityId, e);
    } finally {
      syncing.remove(userIdentityId);
      // Whatever the pass did, anyone waiting on it is waiting for it to be
      // over, not for it to have succeeded.
      pass.done().complete(null);
    }
  }

  /**
   * Does the outward work this caller asked for, and only that.
   *
   * <p>
   * The one place the three phases are ordered, so the order is stated once
   * whether they are reached from a sweep or from a connection. Settle what is
   * owed, then seed what was never given, then verify: an edit whose push
   * failed must be written before anything judges the copy, or the pass judges
   * eXo's own arrears and reports a mirror needing attention that the very next
   * call would have put right (EXO-89773); and a copy seeded this round is read
   * back by the ordinary discipline from the next round on (EXO-89681).
   *
   * <p>
   * The order is {@link OutboundPhase}'s own declaration order, walked here —
   * so a phase cannot be run in one order by the sweep and another by a
   * connection, whatever set each of them names.
   *
   * @param userIdentityId identity of the user whose account receives copies
   * @param phases the pieces of work to do, in the set the caller named
   */
  private void runOutboundPhases(long userIdentityId, Set<OutboundPhase> phases) {
    for (OutboundPhase phase : OutboundPhase.values()) {
      if (phases.contains(phase)) {
        runOutboundPhase(userIdentityId, phase);
      }
    }
  }

  /**
   * Does one phase, and lets it fail on its own.
   *
   * <p>
   * Guarded per phase rather than around the three of them, and that is a
   * decision rather than a formatting choice. One guard around all three meant
   * an arrear the calendar server refused took the seeding and the verification
   * down with it for that round — the same shape as the defect this class was
   * fixed for, one level in: three things that are not the same thing, sharing
   * a fate nobody chose for them. They are independent, so a server that will
   * not accept a rewrite may still have every reason to accept a first copy.
   *
   * <p>
   * Never allowed to fail its caller either. Inside a pass, the copies eXo
   * pushed are its own projection of what agenda already holds, and trouble
   * with them must not cost the user the calendars they came for; on the
   * seeding thread there is no caller left to tell.
   *
   * @param userIdentityId identity of the user whose account receives copies
   * @param phase the piece of work to do
   */
  private void runOutboundPhase(long userIdentityId, OutboundPhase phase) {
    try {
      switch (phase) {
      case SETTLE_OWED_COPIES -> caldavEventPropagationService.retryOwedPushes(userIdentityId);
      case SEED_UPCOMING_COPIES -> caldavPendingInvitationService.pushUpcomingMeetings(userIdentityId);
      case VERIFY_MIRROR -> caldavMirrorVerificationService.verify(userIdentityId);
      }
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The {} of the copies of user {} did not complete this round", phase, userIdentityId, e);
    }
  }

  /**
   * The one thread seeded copies are written from.
   *
   * <p>
   * A single thread with a bounded queue, and an overflow that is refused
   * rather than dropped in silence: the caller turns a refusal into a line at
   * debug and leaves the account to the sweep. Daemon, because a burst of
   * copies is never a reason to hold a shutdown open.
   *
   * @return the executor the seeding runs on
   */
  private static ExecutorService newOutboundExecutor() {
    return new ThreadPoolExecutor(1,
                                  1,
                                  0L,
                                  TimeUnit.MILLISECONDS,
                                  new ArrayBlockingQueue<>(SEEDING_QUEUE_DEPTH),
                                  runnable -> {
                                    Thread thread = new Thread(runnable, "caldav-seeding");
                                    thread.setDaemon(true);
                                    return thread;
                                  });
  }

  /**
   * Stops accepting seeding work when the platform is going down.
   *
   * <p>
   * {@code shutdown} and not {@code shutdownNow}: a copy half written is a copy
   * the verification pass has to reconcile later, so the burst in flight is let
   * finish rather than interrupted. Anything still queued is dropped, which
   * costs nothing — the sweep seeds those accounts on the next start.
   */
  @PreDestroy
  public void stopSeeding() {
    outboundExecutor.shutdown();
  }

  /**
   * Waits for a pass already running, for a bounded time.
   *
   * @param userIdentityId identity of the user, for the log only
   * @param running the pass to wait for
   */
  private void awaitPass(long userIdentityId, SyncPass running) {
    try {
      running.done().get(IN_FLIGHT_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (TimeoutException | ExecutionException e) {
      // Not an error the caller can act on: the pass is still running and
      // will finish on its own. The caller reads a slightly older account
      // than it hoped for, which is exactly where it stood before it waited.
      LOG.debug("The synchronisation already running for user {} outlasted the wait", userIdentityId, e);
    }
  }

  /**
   * Drops bindings that have no eXo calendar behind them any more.
   *
   * <p>
   * Such a binding describes nothing, and keeping it costs the user the
   * collection for good: materialisation skips a collection that already has
   * one, so the calendar can never come back and nothing on screen says why.
   *
   * <p>
   * Only ACTIVE bindings are considered. A tombstone is a deliberate deletion
   * and its whole purpose is to keep the collection out — pruning those would
   * bring back exactly what the user asked to be rid of.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   * @param settings the connected account
   */
  private void pruneOrphanBindings(long userIdentityId, String username, CaldavUserSetting settings) {
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    List<CalendarSync> pairs = caldavSyncStorage.getPairsByOrigin(userIdentityId, serverId, SyncOrigin.REMOTE);
    if (pairs.isEmpty()) {
      return;
    }
    Map<String, Calendar> byAnchor = calendarsByAnchor(userIdentityId, username);
    if (byAnchor.isEmpty()) {
      // The calendars could not be read at all. Every binding would look like
      // an orphan, and pruning them would throw away bindings whose calendars
      // are perfectly well — the worst possible reading of a read failure.
      return;
    }
    for (CalendarSync pair : pairs) {
      if (pair.getStatus() == CalendarSyncStatus.ACTIVE && !byAnchor.containsKey(pair.getLocalCalendarSyncUid())) {
        LOG.info("Binding {} has no eXo calendar behind it; it is dropped so the collection can be materialised again",
                 pair.getId());
        caldavSyncStorage.deleteObjects(pair.getId());
        caldavSyncStorage.deletePair(pair.getId());
      }
    }
  }

  /**
   * Brings the events of every materialised collection into the calendar
   * standing for it.
   *
   * <p>
   * Only bindings eXo did <em>not</em> create are read. A collection eXo
   * pushed holds copies of events agenda already has, and importing them back
   * would show every one of the user's own meetings twice.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login, which agenda's ACL reads
   * @param settings the connected account
   * @param collections the account's listing as this pass read it, or null when
   *          it could not be read — the distinction is what keeps a server
   *          briefly answering with nothing from marking every collection gone
   */
  private void importRemoteEvents(long userIdentityId,
                                  String username,
                                  CaldavUserSetting settings,
                                  List<CalendarCollection> collections) {
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    // Every calendar binding, and never the mirror ledger. A calendar reads
    // back if the user has it on their devices as a calendar of their own —
    // whether eXo materialised it from the account or created it there makes
    // no difference to them: they see it, they edit it on a phone, and the
    // change belongs here.
    //
    // The mirror row is not a calendar binding: it names no eXo calendar, only
    // the collection eXo writes its copies into. It is excluded because
    // reading a collection *through it* would be reading eXo's own projection
    // back — a copy overwriting the event it is a copy of. This is not the
    // same as excluding the collection: where the copies go to the user's own
    // calendar, as a server set to MAIN_CALENDAR sends them, that collection is
    // read like any other, through its own binding, and each copy inside it is
    // skipped one object at a time by the mapping table.
    //
    // This selected REMOTE alone, which made the direction depend on who
    // created the collection — bookkeeping invisible to the user, since both
    // kinds sit under Personal and look identical. An event edited on a phone
    // in an eXo-created calendar simply never came home.
    List<CalendarSync> pairs = caldavSyncStorage.getPairs(userIdentityId, serverId)
                                                .stream()
                                                .filter(pair -> pair.getOrigin() != SyncOrigin.MIRROR)
                                                .toList();
    if (pairs.isEmpty()) {
      return;
    }
    Map<String, Calendar> byAnchor = calendarsByAnchor(userIdentityId, username);
    // A listing that failed is null and tells us nothing. An empty one that
    // succeeded tells us almost as little: a server briefly answering with
    // nothing is far likelier than a user deleting every calendar they have,
    // and concluding the second would mark the whole account gone in one
    // pass. So a collection is only called gone against a listing that
    // actually holds something.
    boolean listed = collections != null && !collections.isEmpty();
    Map<String, String> ctags = ctagsByHref(collections);
    // Anchored to the start of the day, not to the instant. A window hanging
    // off "now" slides forward on every pass, so a collection whose ctag has
    // not moved could still hold an event in a day that has only just come
    // into range — and skipping it on the ctag alone would never read it. Cut
    // at a day boundary, the window is the same all day, which is exactly
    // what makes "nothing changed" mean "nothing to read".
    Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
    Instant from = today.minus(Duration.ofDays(caldavTuningService.getPastDays()));
    Instant to = today.plus(Duration.ofDays(caldavTuningService.getFutureDays() + 1L));
    for (CalendarSync pair : pairs) {
      if (pair.getStatus() != CalendarSyncStatus.ACTIVE) {
        // A paused or tombstoned binding is not one to read from. A tombstone
        // in particular means the user deleted the calendar in eXo, and
        // filling it back up is precisely what they asked not to happen.
        continue;
      }
      Calendar calendar = byAnchor.get(pair.getLocalCalendarSyncUid());
      if (calendar == null) {
        // Pruned earlier in this pass, or created between the two steps.
        continue;
      }
      String ctag = ctags.get(CaldavSyncStorage.canonicalHref(pair.getRemoteHref()));
      // Only for a collection eXo materialised. "It is no longer on the
      // account" is a statement about the user's own calendar disappearing
      // from their server, and it earns the warning the settings then show.
      // A collection eXo created is a different thing: it is absent from the
      // listing whenever the server reports it under a name other than the
      // one it was created at — which BlueMind does — and marking it gone
      // flagged the user's own calendars as broken until a later pass revived
      // them. This check was written for materialised bindings and only ever
      // saw them until the import widened to carry every calendar the user
      // has on their devices.
      if (pair.getOrigin() == SyncOrigin.REMOTE
          && listed && ctag == null && !holdsHref(collections, pair.getRemoteHref())) {
        // The collection is not in a listing that succeeded: the user deleted
        // their own calendar on the account. Marked rather than deleted here —
        // what eXo already holds is theirs, and the binding is what lets the
        // calendar come back if the collection does.
        LOG.info("Collection {} is no longer on the account; its calendar stops receiving events", pair.getRemoteHref());
        pair.setStatus(CalendarSyncStatus.REMOTE_GONE);
        caldavSyncStorage.savePair(pair);
        continue;
      }
      if (nothingToRead(pair, ctag, today)) {
        // The collection has not changed since it was last read, and the
        // window has not moved since either. eXo did reach the account — the
        // ctag came from it — so the account counts as synchronised now.
        pair.setLastSyncEnd(new Date());
        caldavSyncStorage.savePair(pair);
        continue;
      }
      try {
        // Before the import, and that ordering is the fix rather than a
        // preference. Deleting an event in the same unit of work that has just
        // imported events fails at commit — Hibernate finds a persistent
        // instance referencing a transient EventEntity — while the identical
        // deletion succeeds on its own. Reconciling first means the deletions
        // run against a context the import has not touched.
        //
        // The cost is only churn: an object deleted and recreated between two
        // passes is removed here and imported back below, ending the pass
        // present, which is the state the account is in.
        // One conversation with the account, not two. A sync report says what
        // changed and what was removed in the same answer, and it consumes the
        // token it was given — so asking twice would let the second question
        // miss everything the first had already taken.
        //
        // The window is read in full when the binding has no token to ask with,
        // or when the window itself has moved. A token reports what changed; it
        // says nothing about days sliding into range, and an event a year out
        // that nobody touches must still appear when the window reaches it.
        // That is once a day, which is what the last sync time decides.
        //
        // It has a second job it was never designed for, and that job is now
        // the only one of its kind (EXO-89810). A sync report names an object
        // ONCE — it consumes the token it was given and hands back a new one —
        // so a change eXo was told about while it could not act on it is never
        // mentioned again, and no amount of waiting brings it back. Measured
        // on the rig: BlueMind reported an answer on one of eXo's own copies
        // twice, the token moved past both, and deploying the fix that reads
        // such answers (EXO-89807) changed nothing, because nothing ever
        // reported the object again. Disconnecting did not help either —
        // reconnecting thaws the paused bindings and therefore their tokens.
        // The mirror's own verification cannot see it: it adopts only off a
        // copy whose ETag moved, and this server answers without moving it.
        //
        // This line is what brings that answer home. Once a day the token is
        // not asked, the window is walked, and every object in range is met
        // again — the copies among them, where the adoption of EXO-89807 sits.
        // It costs nothing extra, it runs on the sweep with nobody waiting,
        // and it is bounded by being once a day rather than once a sweep.
        // Anyone reading "we hold a valid token, why re-read the window?" and
        // removing this is removing the only heal there is, for a defect they
        // will never connect to their change. Held by
        // CaldavSyncServiceTest#aCollectionWithAValidTokenIsStillReadInFullOnceADay
        // and, end to end, by CaldavAnswerHealOnFullReadTest.
        boolean fullRead = StringUtils.isBlank(pair.getSyncToken())
            || pair.getLastSyncEnd() == null
            || pair.getLastSyncEnd().toInstant().isBefore(today);
        CaldavInboundService.VanishedCleanup cleanup = caldavInboundService.syncContents(userIdentityId,
                                                                                        pair,
                                                                                        calendar,
                                                                                        from,
                                                                                        to,
                                                                                        fullRead);
        // Stamped on the way out, and only on the way out: until this line
        // the field was written when a binding was CREATED, so an account
        // whose calendars were all already bound kept reporting the day it
        // was connected however often it synchronised. A user pressing "Sync
        // now" and watching the time not move reads it as a broken button.
        //
        // Deliberately not stamped when the import threw: the point of the
        // field is to say when eXo last got through to the account, and a
        // collection that failed did not.
        pair.setLastSyncEnd(new Date());
        // Recorded only now, after the import went through. Storing it on the
        // way in would make one failed collection look unchanged for ever:
        // the next pass would compare the same ctag, find it equal, and never
        // retry what it missed.
        // Not when a deletion could not be carried out. The ctag is the claim
        // that this collection has been fully read; recording it after a
        // failed removal means the next pass compares an unchanged ctag,
        // concludes there is nothing to do, and never retries — so the event
        // the user deleted on their phone stays in eXo for good.
        if (StringUtils.isNotBlank(ctag) && cleanup.failed() == 0) {
          pair.setCtag(ctag);
        }
        caldavSyncStorage.savePair(pair);
        if (pair.getConsecutiveFailures() > 0) {
          pair.setConsecutiveFailures(0);
        }
      } catch (RuntimeException e) {
        // One collection must not cost the others. The next run tries again —
        // but not for ever: a collection failing every time is not going to
        // start working because it was asked once more, and each attempt
        // costs the user's page load.
        LOG.warn("The events of collection {} could not be imported", pair.getRemoteHref(), e);
        pair.setConsecutiveFailures(pair.getConsecutiveFailures() + 1);
        if (pair.getConsecutiveFailures() >= MAX_CONSECUTIVE_FAILURES) {
          LOG.warn("Collection {} has failed {} times in a row; its synchronisation is paused",
                   pair.getRemoteHref(),
                   pair.getConsecutiveFailures());
          pair.setStatus(CalendarSyncStatus.PAUSED);
          pair.setConsecutiveFailures(0);
        }
        caldavSyncStorage.savePair(pair);
      }
    }
  }

  /**
   * Suspends every binding of an account.
   *
   * <p>
   * A refused credential is not a property of one calendar, so pausing one
   * would leave the others retrying the same rejected password.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   */
  private void pauseAll(long userIdentityId, CaldavUserSetting settings) {
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    for (CalendarSync pair : caldavSyncStorage.getPairs(userIdentityId, serverId)) {
      if (pair.getStatus() == CalendarSyncStatus.ACTIVE) {
        pair.setStatus(CalendarSyncStatus.PAUSED);
        caldavSyncStorage.savePair(pair);
      }
    }
  }

  /**
   * Puts a binding back to work when its collection turns up again.
   *
   * <p>
   * Without this a collection marked gone stays gone for good: materialisation
   * skips a collection that already has a binding, so nothing would ever
   * notice it had come back — a calendar deleted and restored on the user's
   * own client would simply never fill again.
   *
   * @param known the bindings of this account
   * @param collection the collection the server just listed
   */
  private void reviveIfMarkedGone(List<CalendarSync> known, CalendarCollection collection) {
    String href = CaldavSyncStorage.canonicalHref(collection.href());
    for (CalendarSync pair : known) {
      if (pair.getStatus() == CalendarSyncStatus.REMOTE_GONE
          && StringUtils.equals(href, CaldavSyncStorage.canonicalHref(pair.getRemoteHref()))) {
        LOG.info("Collection {} is back on the account; its calendar receives events again", collection.href());
        pair.setStatus(CalendarSyncStatus.ACTIVE);
        caldavSyncStorage.savePair(pair);
      }
    }
  }

  /**
   * Whether a listing holds a path.
   *
   * @param collections what the account listed
   * @param href the path a binding records
   * @return true when the server still holds it
   */
  private boolean holdsHref(List<CalendarCollection> collections, String href) {
    String canonical = CaldavSyncStorage.canonicalHref(href);
    return collections != null
        && collections.stream().anyMatch(c -> StringUtils.equals(canonical, CaldavSyncStorage.canonicalHref(c.href())));
  }

  /**
   * Whether a collection can be skipped this pass.
   *
   * <p>
   * Three things must hold, and each is a way this optimisation can silently
   * stop importing if it is dropped:
   *
   * <ul>
   * <li>the server published a ctag — one that publishes none says nothing
   * about whether it changed, and must be read in full rather than assumed
   * quiet;</li>
   * <li>it matches the one stored when this collection was last read through
   * to the end;</li>
   * <li>that read happened with today's window. The window is cut at a day
   * boundary, so this is the same as asking whether it happened today; a read
   * from yesterday covered a range that no longer reaches as far forward.</li>
   * </ul>
   *
   * @param pair the binding
   * @param ctag the collection's ctag as the server reports it now
   * @param today the start of the day the current window is cut from
   * @return true when there is nothing this pass could read
   */
  private boolean nothingToRead(CalendarSync pair, String ctag, Instant today) {
    return StringUtils.isNotBlank(ctag)
        && ctag.equals(pair.getCtag())
        && pair.getLastSyncEnd() != null
        && !pair.getLastSyncEnd().toInstant().isBefore(today);
  }

  /**
   * The ctag of each collection, by the canonical form of its path.
   *
   * @param collections what the account's home listed, possibly empty
   * @return the ctags, keyed the way a binding records its href
   */
  private Map<String, String> ctagsByHref(List<CalendarCollection> collections) {
    Map<String, String> ctags = new HashMap<>();
    if (collections == null) {
      return ctags;
    }
    for (CalendarCollection collection : collections) {
      if (StringUtils.isNotBlank(collection.ctag())) {
        ctags.put(CaldavSyncStorage.canonicalHref(collection.href()), collection.ctag());
      }
    }
    return ctags;
  }

  /**
   * The user's calendars, keyed by the anchor a binding records.
   *
   * <p>
   * Agenda exposes no lookup by sync uid, so the calendars are listed once per
   * run and matched in memory rather than once per binding.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   * @return the calendars by anchor, empty when they cannot be read
   */
  private Map<String, Calendar> calendarsByAnchor(long userIdentityId, String username) {
    Map<String, Calendar> byAnchor = new HashMap<>();
    try {
      for (Calendar calendar : agendaCalendarService.getCalendars(0, Integer.MAX_VALUE, username)) {
        if (calendar.getOwnerId() == userIdentityId && !calendar.isDeleted()
            && StringUtils.isNotBlank(calendar.getSyncUid())) {
          byAnchor.put(calendar.getSyncUid(), calendar);
        }
      }
    } catch (Exception e) { // NOSONAR agenda declares a bare Exception here
      LOG.warn("The calendars of user {} could not be read; nothing is imported this round", userIdentityId, e);
    }
    return byAnchor;
  }

  /**
   * Gives every remote calendar an eXo personal calendar, except the ones eXo
   * put there itself.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   * @param settings the connected account
   */
  private List<CalendarCollection> materialiseRemoteCalendars(long userIdentityId,
                                                              String username,
                                                              CaldavUserSetting settings) {
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    CalDavEndpoint endpoint = calDavClient.endpoint(settings.getServerId(), settings.getUsername());
    List<CalendarCollection> collections;
    try {
      String home = calDavClient.discoverCalendarHome(endpoint, settings.getUsername(), settings.getPassword());
      collections = calDavClient.listCalendars(endpoint, home, settings.getUsername(), settings.getPassword());
    } catch (CalDavAuthenticationException | CalDavUnreachableException e) {
      // Not swallowed with the rest, and for the same reason in both cases:
      // neither is "this round did not work". A refused credential is an
      // account that must stop being retried, and an unreachable server is an
      // absence the rest of the pass would only rediscover — importing, then
      // settling, then seeding, each spending its own authenticated requests
      // on a server that is not there (EXO-89806). The decision belongs to the
      // caller, which is the one that can pause every binding at once.
      throw e;
    } catch (CalDavException e) {
      // null, not an empty list: an account whose listing failed and an
      // account with no calendars look identical otherwise, and the second
      // conclusion — "every collection is gone" — would mark the user's whole
      // set as vanished the moment their server had a bad minute.
      LOG.warn("The account's calendars could not be listed; nothing is materialised this round", e);
      return null;
    }
    List<CalendarSync> known = caldavSyncStorage.getPairs(userIdentityId, serverId);
    for (CalendarCollection collection : collections) {
      if (isAlreadyOurs(collection, known)) {
        reviveIfMarkedGone(known, collection);
        continue;
      }
      if (!collection.holdsEvents()) {
        // A CalDAV home holds more than calendars: BlueMind publishes the
        // account's task list beside them, and it answers a PROPFIND for
        // calendars just as a calendar would. Materialising it would give the
        // user an eXo calendar that can never hold an event.
        LOG.debug("Collection {} declares no VEVENT support and is not a calendar to materialise", collection.href());
        continue;
      }
      materialise(userIdentityId, username, serverId, collection);
    }
    // Handed on rather than listed a second time: the import needs each
    // collection's ctag to decide whether it has anything to read, and one
    // PROPFIND already carries them all.
    return collections;
  }

  /**
   * Whether a listed collection is one eXo already accounts for.
   *
   * <p>
   * Three reasons to skip, and the first is the one that keeps the two halves
   * from feeding each other. A collection eXo created carries an ORIGIN=EXO
   * pair; materialising it would produce a second eXo calendar, which the
   * outward pass would then push as a third collection, and so on. Two
   * features each behaving correctly, multiplying calendars on both sides.
   *
   * <p>
   * The <em>dedicated</em> mirror is skipped because its contents are copies
   * of events eXo already shows, and an existing <em>calendar binding</em>
   * means the collection is accounted for — including a tombstone, which is
   * exactly what stops a calendar the user deleted in eXo from coming straight
   * back.
   *
   * <p>
   * <b>A calendar binding, not any row.</b> The mirror pair is not one: it has
   * no local calendar behind it — the schema says so, {@code
   * LOCAL_CALENDAR_SYNC_UID} "is null for the MIRROR pair alone" — and it
   * exists to hold the mapping rows of the copies eXo writes. Counted here, it
   * reintroduced by another route exactly the regression
   * {@link #isDedicatedMirror(String)} was narrowed to end: point the copies at
   * the account's own default calendar, as a server set to
   * {@code MAIN_CALENDAR} does, and the mirror pair records that calendar's
   * path — so the user's primary calendar read as already accounted for and
   * was never materialised, nor its events ever read.
   *
   * @param collection the listed collection
   * @param known every pair this user holds on this server
   * @return true when nothing should be created for it
   */
  private boolean isAlreadyOurs(CalendarCollection collection, List<CalendarSync> known) {
    String href = CaldavSyncStorage.canonicalHref(collection.href());
    if (StringUtils.isBlank(href)) {
      // A collection with no path is nothing we can bind to, name, or find
      // again. Blank rather than null on purpose: a server answering an empty
      // <d:href/> would otherwise match no pair, fail the eXo-created test,
      // and be materialised as a calendar whose name is the blank path too.
      return true;
    }
    if (isDedicatedMirror(href)) {
      return true;
    }
    if (isExoCreated(href)) {
      return true;
    }
    return known.stream()
                .filter(CaldavSyncService::bindsACalendar)
                .anyMatch(pair -> href.equals(CaldavSyncStorage.canonicalHref(pair.getRemoteHref())));
  }

  /**
   * Whether a pair stands for an eXo calendar, rather than merely recording
   * where eXo writes its copies.
   *
   * <p>
   * The distinction the two roles need, and the reason one collection may
   * legitimately carry two rows. A <b>calendar binding</b> — ORIGIN=REMOTE or
   * ORIGIN=EXO — names one eXo calendar in {@code localCalendarSyncUid} and is
   * what makes that calendar exist, receive events and, when tombstoned, stay
   * deleted. The <b>mirror pair</b> names no calendar at all: it is the ledger
   * the copies of space meetings are mapped against, and the destination it
   * records may be any collection the administrator points it at, including
   * one the user keeps their own events in.
   *
   * <p>
   * Uniqueness follows the calendar, not the path: the schema's unique index
   * is on (user, server, {@code LOCAL_CALENDAR_SYNC_UID}), so one calendar has
   * one binding while a mirror row — whose anchor is null — sits outside it by
   * construction. A main calendar that is also the copy destination therefore
   * ends a pass with exactly one binding <em>and</em> one ledger row, which is
   * one row per thing rather than two rows for one thing.
   *
   * <p>
   * What keeps the copies out of the import is not this row's existence but
   * the mapping table, asked per object: the import reads the collection
   * through its binding and skips each object a mirror pair already maps
   * (see {@code CaldavInboundService#isMirrorOwned}).
   *
   * @param pair a pair of this user on this server
   * @return true when the pair binds an eXo calendar
   */
  private static boolean bindsACalendar(CalendarSync pair) {
    return pair.getOrigin() != SyncOrigin.MIRROR;
  }

  /**
   * Whether this collection is the dedicated calendar eXo copies space
   * meetings into.
   *
   * <p>
   * Judged from the path eXo mints — <code>exo-meetings</code> — and from
   * nothing else, deliberately. It used to also skip whatever collection the
   * account happened to have <em>recorded</em> as its mirror, which is a
   * different and much wider statement: point the mirror at an ordinary
   * calendar the user also synchronises, and that calendar stopped being
   * materialised at all. The user's own primary calendar simply never appeared
   * in eXo — the connector's core feature, lost to a guard meant to protect a
   * handful of copies.
   *
   * <p>
   * So the skip is scoped back to the case it was written for: a collection
   * eXo created for its own copies and nothing else keeps events in. Any other
   * mirror destination is materialised like the ordinary calendar it is, and
   * the copies inside it are protected one object at a time, by the mapping
   * table, where ownership actually lives.
   *
   * <p>
   * The path rather than this user's recorded href, for the same reason
   * {@link #isExoCreated(String)} reads the path: a CalDAV account can be
   * shared, and another eXo user's dedicated mirror must not be materialised
   * as this user's calendar either.
   *
   * @param href the collection path, canonical
   * @return true when the path is the dedicated mirror's
   */
  private boolean isDedicatedMirror(String href) {
    return href.endsWith("/" + CaldavPushService.MIRROR_COLLECTION_SLUG);
  }

  /**
   * Whether eXo is the one that created this collection, judged from its path
   * alone.
   *
   * <p>
   * The pair check below cannot answer this on its own: pairs are read for
   * <em>one</em> user, while a CalDAV account can be shared by several. Two
   * eXo users connected to the same account see each other's pushed
   * collections as ordinary remote ones, each materialises the other's, each
   * then pushes the result as a new collection — and the pair of them
   * multiply calendars without either behaving incorrectly. Observed live:
   * one user's <code>exo-cal-946eec40…</code> came back as another user's
   * calendar 23.
   *
   * <p>
   * The path is the reliable signal because eXo mints it: a collection under
   * the outbound prefix was created by eXo, whichever user asked for it, and
   * is never something to import.
   *
   * @param href the collection path, canonical
   * @return true when the path is one eXo derives
   */
  private boolean isExoCreated(String href) {
    String slug = StringUtils.substringAfterLast(StringUtils.stripEnd(href, "/"), "/");
    return StringUtils.startsWith(slug, CaldavOutboundService.COLLECTION_PREFIX);
  }

  /**
   * Creates the eXo calendar standing for one remote collection.
   *
   * @param userIdentityId identity of the user
   * @param username the user's login
   * @param serverId the declared server registration
   * @param collection the remote collection
   */
  private void materialise(long userIdentityId, String username, long serverId, CalendarCollection collection) {
    Calendar calendar = new Calendar();
    calendar.setOwnerId(userIdentityId);
    // Agenda persists getName(); getTitle() is a display field it computes,
    // and a calendar left with no name falls back to the owner's identity —
    // which is how two materialised collections both came out called after
    // their owner instead of after themselves.
    String name = StringUtils.defaultIfBlank(collection.displayName(), collection.href());
    calendar.setName(name);
    calendar.setTitle(name);
    calendar.setColor(collection.color());
    Calendar created;
    try {
      created = agendaCalendarService.createCalendar(calendar, username);
    } catch (IllegalAccessException e) {
      LOG.warn("User {} may not have a calendar created for collection {}", userIdentityId, collection.href(), e);
      return;
    }
    CalendarSync pair = new CalendarSync();
    pair.setUserIdentityId(userIdentityId);
    pair.setServerId(serverId);
    // Agenda mints the anchor when it creates the calendar; the pair records
    // it so the binding survives anything that renumbers ids.
    pair.setLocalCalendarSyncUid(created.getSyncUid());
    pair.setRemoteHref(collection.href());
    // The user made this collection, not eXo. The origin decides what may
    // later be deleted, and getting it wrong here would let eXo remove a
    // calendar it never created.
    pair.setOrigin(SyncOrigin.REMOTE);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    // Deliberately no ctag, no sync token and no sync time. All three are
    // claims about what has been read out of this collection, and nothing has
    // been read out of it yet — its events are imported further down this very
    // pass.
    //
    // Recording them here made a brand-new binding look completely up to date
    // the instant it was created, and the import step skips a collection whose
    // ctag still matches and whose last sync is from today. So a calendar
    // materialised with events already in it came in empty, and stayed empty
    // for the rest of the day: every later pass compared the same ctag and
    // agreed there was nothing to read. Only a change made on the server —
    // moving the ctag — ever broke it out, which made the ordinary first
    // connect the one case that failed.
    //
    // A binding with no last sync also reads as due (findDue treats a null as
    // due), which is exactly what an unread collection should be.
    caldavSyncStorage.savePair(pair);
    LOG.info("Materialised remote calendar {} as eXo calendar {}", collection.href(), created.getId());
  }

  /**
   * Whether an account is usable.
   *
   * @param settings the stored account
   * @return true when it carries credentials
   */
  private boolean connected(CaldavUserSetting settings) {
    return settings != null && StringUtils.isNotBlank(settings.getUsername())
        && StringUtils.isNotBlank(settings.getPassword());
  }
}
