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

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.ics.IcsEquivalence;
import org.exoplatform.caldav.ics.IcsJudgement;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.MirrorVerification;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Checks that the copies eXo pushed are still there, and still what eXo wrote
 * — and reads back the one field the user is allowed to change on them.
 *
 * <p>
 * The mirror calendar is a <b>projection</b>: eXo is authoritative and nothing
 * ever syncs back from it, with a single deliberate exception since EXO-89681
 * — the owner's own PARTSTAT, so a meeting can be answered from the calendar
 * on their phone. Until this ran, even the projection was a claim rather than
 * a guarantee — a copy deleted from someone's phone stayed deleted, and a
 * copy a server rewrote stayed rewritten, both silently. Nothing in eXo would
 * ever notice, because nothing ever looked.
 *
 * <p>
 * One ETag listing of the collection answers both questions at once, and it is
 * the same confirm-by-reading-back discipline the mirror's own creation
 * already needs: an href that is gone means the copy was deleted, and an ETag
 * that moved means somebody rewrote it. Only the second case costs a fetch,
 * and only when the ETag actually moved.
 *
 * <p>
 * <b>What "what eXo wrote" means.</b> Not bytes — <i>meaning</i>. The copy is
 * compared against the object eXo would render for the same event now, by
 * {@link org.exoplatform.caldav.ics.IcsEquivalence}, and nothing is recorded to
 * compare against. Two byte baselines were tried and neither can be captured:
 * a digest of what eXo <i>sent</i> made every copy on a re-serialising server
 * look tampered with from the first pass, and a digest of what the server was
 * seen to <i>store</i>, read back after the write, caught BlueMind mid-settle —
 * it finished afterwards without moving the ETag, so all 19 copies of a live
 * account were judged altered and rewritten every five minutes, for ever. A
 * CalDAV object is a structured document and a server re-serialising it is
 * behaving normally; the fix is to understand the content rather than to learn
 * one server's serialisation.
 *
 * <p>
 * <b>The ETag also decides direction.</b> Comparing PARTSTAT values alone
 * cannot tell "answered on the phone" from "answered in eXo and not pushed
 * yet" — both read as a difference. An ETag that moved away from the one this
 * connector recorded proves the client wrote the object after eXo did, so the
 * answer on it is the user's latest word and is adopted; an ETag that still
 * matches proves the copy is untouched, so any difference is eXo-side and the
 * ordinary push simply overwrites the copy. And the answer is read
 * <i>before</i> the object is repaired, deliberately: when eXo changed the
 * meeting while the user accepted it on their phone, both differ at once, and
 * a repair-first pass would overwrite the acceptance before anything read it.
 *
 * <p>
 * <b>It gives up, and giving up stops the work and not only the writing.</b> An
 * object that keeps disappearing — a server refusing writes it pretends to
 * accept, a rule on the account deleting what eXo sends — is left alone after a
 * few attempts and said out loud, rather than re-pushed on every sync for ever.
 * The count is held in memory on purpose: a restart forgives, which is the right
 * bias when the cause is usually temporary and the alternative is a database
 * column recording that a server misbehaved once.
 *
 * <p>
 * That check used to sit <i>after</i> the object had been fetched, re-rendered,
 * compared and named in the log, so an abandoned copy still cost a round trip
 * and a log line every five minutes for ever — everything except the one step
 * that could have changed something. The version each abandoned object settled
 * at is now remembered ({@link #settled}), and a pass that finds the listing
 * still publishing it moves on without a fetch. It is the version rather than a
 * flag deliberately: a copy the user answers on their phone moves its ETag, and
 * that copy is examined again and its answer read, because abandonment is a
 * statement about eXo's writing and never about the user's.
 *
 * <p>
 * <b>And once, when a setting changes, it looks at everything.</b> Everything
 * above converges towards the current render only when something moves the
 * remote ETag — which is exactly what an administrator changing a setting does
 * <i>not</i> do. Flip the answer-links switch and every copy already on the
 * server keeps the links it was given, for ever, because no version ever moves
 * and this pass never looks. So the registration carries a stamp of when a
 * copy-affecting setting last changed, the mirror pair carries the value it has
 * already applied, and a pair that is behind gets one round with the ETag gate
 * removed: every mapped object is rendered, fetched and compared on its content.
 * Which settings move that stamp is one decision in one place —
 * {@link org.exoplatform.caldav.utils.CopySettingsFingerprint}.
 *
 * <p>
 * <b>And when the setting that changed was <i>where</i> the copies go, they are
 * moved rather than stranded.</b> The destination is part of the same
 * fingerprint, so the round a destination change owes is this one — and before
 * a single content comparison runs, {@link CaldavMirrorRelocationService} points
 * the pair at the new collection and carries the backlog across, object by
 * object, reading any answer off a copy before the mapping stops watching it.
 * The stamp is then written only if that finished: a copy the server would not
 * let go of leaves the change owed, and the next sweep tries again.
 *
 * <p>
 * <b>Compare then repair; never a mass re-push.</b> The round is this pass minus
 * one gate, and nothing else. It does not write what it has not first found
 * different: on a server that re-serialises or stamps what it stores — which is
 * every server this add-on has met — an unconditional rewrite of everything is
 * the churn incident of EXO-89716 and EXO-89756 back again, at the scale of a
 * whole account at once. A converged mirror comes out of a settings round having
 * written nothing at all, and the price it does pay is honest and bounded: one
 * fetch per copy, once, instead of one listing.
 *
 * <p>
 * <b>Idempotent and resumable by construction.</b> The stamp is written after
 * the walk, so a crash halfway leaves it where it was and the next sweep re-runs
 * a comparison that rewrites nothing already converged. The repair cap and the
 * answer-before-repair ordering come for free, being the same code. Two things
 * the round deliberately does not change: an abandoned copy at its settled
 * version stays skipped — giving up is a statement about eXo's writing that a
 * setting does not retract — and an answer is still only adopted off a copy
 * whose ETag actually moved, because the direction rule is the ETag's other job
 * and the round removes it only as a <i>gate</i>, never as evidence.
 */
@Service
public class CaldavMirrorVerificationService {

  private static final Log             LOG          = ExoLogger.getExoLogger(CaldavMirrorVerificationService.class);

  /** How many objects one pass reads at a time from the mapping table. */
  private static final int             PAGE_SIZE    = 200;

  /** The version stood in for an object the collection listing no longer carries. */
  private static final String          ABSENT       = "(absent)";

  @Autowired
  private CalDavClient                 calDavClient;

  @Autowired
  private CaldavConnectorStorage       caldavConnectorStorage;

  @Autowired
  private CaldavSyncStorage            caldavSyncStorage;

  @Autowired
  private CaldavPushService            caldavPushService;

  @Autowired
  private IcsEquivalence               icsEquivalence;

  @Autowired
  private CaldavAnswerAdoptionService  caldavAnswerAdoptionService;

  @Autowired
  private CaldavServerService          caldavServerService;

  @Autowired
  private CaldavServerQuirkService     caldavServerQuirkService;

  @Autowired
  private CaldavMirrorRelocationService caldavMirrorRelocationService;

  @Autowired
  private CaldavMirrorReportService    caldavMirrorReportService;

  /**
   * Reads the owner's answers off the copies of a collection no binding reads
   * (EXO-89814) — the dedicated <code>exo-meetings</code> collection, which is
   * the default destination. Held here rather than in the sweep because this is
   * the pass that already holds the mirror pair, and because the mirror pass is
   * the sweep's alone: a read must never turn into a burst of requests on
   * somebody's page load.
   */
  @Autowired
  private CaldavMirrorAnswerService    caldavMirrorAnswerService;

  /**
   * How many times one object may be repaired before the pass stops trying.
   * Three is enough to ride out a server having a bad minute and few enough
   * that a genuine fight is over quickly.
   */
  @Value("${exo.agenda.caldav.mirror.maxRepairs:3}")
  private int                          maxRepairs;

  /**
   * How many times each object has been repaired, keyed by user and href.
   *
   * <p>
   * In memory, like the sync throttle and for the same reason: this records
   * that something is going wrong right now, not a fact about the account
   * worth carrying across a restart.
   */
  private final Map<String, Integer>   repairs      = new ConcurrentHashMap<>();

  /**
   * The version each abandoned object carried when the pass stopped arguing with
   * it, keyed the same way as {@link #repairs}.
   *
   * <p>
   * <b>What this is for.</b> Giving up used to stop the writing and nothing
   * else. The check sat after the object had already been listed, fetched,
   * re-rendered, compared and named in an INFO line, so an abandoned copy went
   * on costing a round trip and a log line every five minutes for ever — the
   * work, minus the only part of it that could ever change anything. Recording
   * the version it settled at lets the next pass answer "nothing has happened
   * here" from the collection listing it already has.
   *
   * <p>
   * <b>Why the version and not simply a flag.</b> A flag would also stop the
   * pass reading the user's answer off that copy, and abandonment must not blind
   * eXo to it: the copy still sits in their calendar and they can still accept
   * the meeting on their phone. An ETag that moves away from this one is the
   * server's own statement that somebody wrote, and the pass looks again —
   * fetches, adopts the answer (EXO-89681), and settles on the new version. So
   * what is skipped is exactly the case where nothing has changed.
   *
   * <p>
   * <b>And why not record it on the row instead.</b> Writing the server's
   * version into {@code ObjectSync.etag} would say "this copy is what eXo
   * stands behind", which is the opposite of true, and it would outlive the
   * restart that is meant to forgive: the next pass after a restart would find
   * the row agreeing with the listing and never re-examine a copy it is supposed
   * to get another chance at. In memory, beside the repair counts, for the same
   * reason they are — this records what is going wrong right now.
   */
  private final Map<String, String>    settled      = new ConcurrentHashMap<>();

  /**
   * Compares every copy eXo pushed for a user against what the server holds.
   *
   * @param userIdentityId identity of the user whose mirror is checked
   * @return what the pass found and did
   */
  public MirrorVerification verify(long userIdentityId) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (settings == null || StringUtils.isBlank(settings.getUsername())) {
      return MirrorVerification.nothing();
    }
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    List<CalendarSync> mirrors = caldavSyncStorage.getPairsByOrigin(userIdentityId, serverId, SyncOrigin.MIRROR);
    if (mirrors.isEmpty()) {
      // Nothing has ever been pushed for this user. Not a failure — most
      // accounts are in this state until the first meeting is copied.
      return MirrorVerification.nothing();
    }
    CalendarSync mirror = mirrors.get(0);
    CaldavServer server = resolveServer(settings);
    Date owed = settingsRoundOwed(server, mirror);
    // Before the listing, deliberately. A setting that moved the destination
    // moves this collection too, and listing first would spend the pass
    // comparing the contents of a calendar the copies are about to leave.
    MirrorRelocation relocation = relocate(userIdentityId, settings, mirror, owed);
    if (relocation.applicable()) {
      mirror.setRemoteHref(relocation.destination());
    }
    // Before the comparison, and that ordering is the whole of EXO-89814 twice
    // over. It is the only pass that meets a copy in a collection no binding
    // reads, so an answer on it is read here or nowhere; and reading it first
    // is what stops the settings round below repairing a copy over the very
    // answer nothing had read yet. The relocation runs first for its own
    // reason — asking the collection the copies are about to leave what changed
    // in it would spend a report on the wrong calendar.
    caldavMirrorAnswerService.readAnswers(userIdentityId, settings, mirror);
    Map<String, String> etags;
    try {
      CalDavEndpoint endpoint = calDavClient.endpoint(settings.getServerId(), settings.getUsername());
      // Slashed: the stored href is canonical so that two spellings compare
      // equal, but addressing a collection is a different job and a server may
      // answer one spelling and not the other. BlueMind ignores the slashless
      // form without answering or redirecting, so this call spent the whole
      // request timeout on every sweep.
      etags = calDavClient.listResourceEtags(endpoint,
                                             StringUtils.appendIfMissing(mirror.getRemoteHref(), "/"),
                                             settings.getUsername(),
                                             settings.getPassword());
    } catch (RuntimeException e) {
      // A collection that cannot be listed says nothing about the copies in
      // it. Treating an unreachable server as "everything was deleted" would
      // re-push the user's whole history the moment it came back.
      LOG.warn("The mirror collection of user {} could not be listed; nothing is verified this round", userIdentityId, e);
      // Recorded all the same. A pass that could not list the collection is
      // exactly what an administrator watching a destination change needs to
      // see - reporting only the passes that succeeded would show a change as
      // quietly finished on the very accounts it never reached.
      return record(userIdentityId, MirrorVerification.nothing(), relocation);
    }
    // Once per pass, whatever the pass finds, and before anything below can
    // decline to walk the copies. The summary's pruning rides on its write, the
    // write on something having diverged - and a converged account diverges on
    // nothing, moves no ETag, and never reaches the comparison at all. Without
    // this the records that most need clearing are the ones on the servers that
    // stopped producing them.
    caldavServerQuirkService.settle(server == null ? null : server.getId());
    MirrorVerification verification = comparePages(userIdentityId,
                                                   mirror,
                                                   settings,
                                                   etags,
                                                   owed != null && relocation.applicable(),
                                                   server);
    if (owed != null && relocation.complete()) {
      // After the walk, never before. This is the whole of the resumability:
      // anything that ends the round early — an exception out of the storage,
      // the platform stopping — never reaches here, the pair stays behind, and
      // the next sweep re-runs a comparison that rewrites nothing already
      // converged.
      //
      // And only when the relocation of EXO-89761 finished. A copy the server
      // would not let go of, or one that could not be written into the new
      // collection, is work this change still owes; stamping over it would tell
      // every later pass there is nothing to do and strand that copy for good.
      stampSettingsApplied(mirror, owed);
    }
    return record(userIdentityId, verification, relocation);
  }

  /**
   * Keeps what this pass found and moved where an administrator can read it,
   * and hands the verification straight back.
   *
   * <p>
   * Returning its argument is what lets every exit of {@link #verify} record
   * without an extra local and without a second return path to keep in step -
   * the mistake this shape exists to make impossible is a pass that quietly
   * reports nothing because somebody added a return above the recording.
   *
   * <p>
   * Recorded only from the exits that ran against the account. The two early
   * returns above - no connected account, nothing ever pushed - are not a pass
   * with a poor result, they are no pass at all, and a row of zeroes for a user
   * who has never had a copy written would be noise on a screen whose whole
   * value is that a number on it means something.
   *
   * @param userIdentityId identity of the user the pass ran for
   * @param verification what the comparison found
   * @param relocation what the move did
   * @return the verification, unchanged
   */
  private MirrorVerification record(long userIdentityId, MirrorVerification verification, MirrorRelocation relocation) {
    try {
      caldavMirrorReportService.record(userIdentityId, verification, relocation);
    } catch (RuntimeException e) {
      // An observability aid must never cost a pass its result. Nothing reads
      // these reports, so losing one changes no behaviour at all.
      LOG.debug("The result of the mirror pass of user {} could not be recorded", userIdentityId, e);
    }
    return verification;
  }

  /**
   * Brings the copies already on the server to wherever the destination now
   * says they go, when a setting has changed (EXO-89761).
   *
   * <p>
   * Driven by the very stamp that drives the comparison round, because the two
   * answer one question between them: the destination setting is in the
   * fingerprint that moves the stamp, so the one moment a mirror can find itself
   * pointing at the wrong collection is the moment it finds itself owing a
   * comparison. On every other change — the answer-links switch, an excusal
   * list — the destination resolves to the collection the pair already points
   * at, nothing is pending, and this costs one resolution and one page read.
   *
   * <p>
   * A pass owing nothing does not ask at all, and that is what keeps the
   * ordinary sweep at one listing: the relocation is bounded to the rounds a
   * settings change already pays for.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   * @param mirror the binding standing for the mirror collection
   * @param owed the server stamp this round would apply, or null when none is
   *          owed
   * @return what the relocation moved, or a deferred one when nothing was owed
   */
  private MirrorRelocation relocate(long userIdentityId, CaldavUserSetting settings, CalendarSync mirror, Date owed) {
    if (owed == null) {
      return MirrorRelocation.deferred();
    }
    LOG.info("A copy setting of user {}'s server changed; every copy of theirs is compared once this round",
             userIdentityId);
    return caldavMirrorRelocationService.relocate(userIdentityId, settings, mirror);
  }

  /**
   * The registration the account is connected through: whose excusals decide
   * what counts as unchanged on it, and whose stamp decides whether its copies
   * are owed a full comparison.
   *
   * <p>
   * Resolved once per pass rather than once per copy: it is the same row for
   * every copy of one account, and a registry lookup per object would turn a
   * quiet sweep into a query storm. A registry that cannot answer leaves the
   * comparison on the deployment-wide fallback, which is exactly what a
   * deployment that never opened the drawer runs on anyway — and leaves no
   * settings round owed, which is the same neutral answer.
   *
   * <p>
   * Resolved the same way every other read of an account's server resolves it —
   * the row the account references, else the seed — so an account connected
   * before the registry existed is neither excluded from the mechanism nor made
   * to compare against a registration that is not its own.
   *
   * @param settings the connected account
   * @return the registration, or null when none can be resolved
   */
  private CaldavServer resolveServer(CaldavUserSetting settings) {
    try {
      return caldavServerService.resolveServer(settings.getServerId());
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("No CalDAV registration could be resolved for the account; the deployment-wide excusals decide", e);
      return null;
    }
  }

  /**
   * The server stamp this mirror still owes its copies a full comparison for,
   * or null when it owes none.
   *
   * <p>
   * Null on the server's side means no administrator has yet changed a setting
   * that governs the copies, and that is what makes the mechanism
   * behaviour-neutral on an upgrade: every registration and every pair starts
   * unstamped, nothing is behind, and no round runs until somebody acts. A
   * registration that could not be resolved at all answers the same way, which
   * is the safe direction: no round rather than a round against a row that is
   * not this account's.
   *
   * @param server the registration {@link #resolveServer} produced for this
   *          pass, may be null
   * @param mirror the binding standing for the mirror collection
   * @return the server stamp the round would apply, or null when nothing is owed
   */
  private Date settingsRoundOwed(CaldavServer server, CalendarSync mirror) {
    if (server == null || server.getCopySettingsUpdated() == null) {
      return null;
    }
    Date applied = mirror.getCopySettingsApplied();
    if (applied != null && !applied.before(server.getCopySettingsUpdated())) {
      return null;
    }
    return server.getCopySettingsUpdated();
  }

  /**
   * Records that this pair has carried the server's settings through one full
   * comparison of its copies.
   *
   * <p>
   * The <i>server's</i> value is written, not the moment the round finished. A
   * round over a large calendar takes minutes, and an administrator who changes
   * a second setting while it runs must not have that change swallowed by a
   * stamp that happens to be later than it. Written this way, the next pass
   * compares two server stamps and sees the second change plainly.
   *
   * <p>
   * The row is read again immediately before the write rather than the one
   * carried through the round: the sync pass writes the token, the ctag and the
   * timestamps on the same row, and a round that started minutes ago holds a
   * snapshot of every one of them. With {@code @DynamicUpdate} on the entity,
   * only the columns that genuinely differ from the row as it now stands are in
   * the UPDATE — which, after this re-read, is the stamp and nothing else.
   *
   * <p>
   * A pair that has since been deleted is not recreated: there is nothing left
   * to apply a setting to.
   *
   * @param mirror the binding standing for the mirror collection
   * @param owed the server stamp the round has just applied
   */
  private void stampSettingsApplied(CalendarSync mirror, Date owed) {
    CalendarSync pair = caldavSyncStorage.getPair(mirror.getId());
    if (pair == null) {
      LOG.debug("The mirror pair {} is gone; the settings round it just ran is not recorded", mirror.getId());
      return;
    }
    pair.setCopySettingsApplied(owed);
    caldavSyncStorage.savePair(pair);
  }

  /**
   * Walks the mapping rows of the mirror, a page at a time.
   *
   * @param userIdentityId identity of the user
   * @param mirror the binding standing for the mirror collection
   * @param settings the connected account
   * @param etags what the server currently holds, by href
   * @param settingsRound whether this is the one round a settings change is
   *          owed, in which case every mapped object is judged on its content
   *          rather than on its version
   * @param server the registration the account is connected through, may be
   *          null — the comparison then falls back to the deployment-wide
   *          excusals
   * @return the tally
   */
  private MirrorVerification comparePages(long userIdentityId,
                                          CalendarSync mirror,
                                          CaldavUserSetting settings,
                                          Map<String, String> etags,
                                          boolean settingsRound,
                                          CaldavServer server) {
    int checked = 0;
    int missing = 0;
    int altered = 0;
    int adopted = 0;
    int repaired = 0;
    int abandoned = 0;
    int held = 0;
    int page = 0;
    List<ObjectSync> objects = caldavSyncStorage.getObjects(mirror.getId(), page, PAGE_SIZE).getContent();
    while (!objects.isEmpty()) {
      for (ObjectSync object : objects) {
        if (StringUtils.isBlank(object.getRemoteHref())) {
          continue;
        }
        checked++;
        if (hasSettled(userIdentityId, object, etags)) {
          // Abandoned, and the server still publishes the version it was
          // abandoned at. There is nothing here a fetch could tell this pass
          // that the listing has not already told it, and nothing a repair is
          // allowed to do about it either.
          abandoned++;
          continue;
        }
        Assessment assessment = judge(userIdentityId, object, settings, etags, settingsRound, server);
        if (assessment.verdict() == Verdict.UNTOUCHED) {
          continue;
        }
        if (assessment.verdict() == Verdict.MISSING) {
          missing++;
        } else {
          altered++;
        }
        if (assessment.verdict() == Verdict.ALTERED && assessment.clientWrote()) {
          // Before the repair, and it has to be. The ETag moved, so the
          // client wrote this object after eXo did: whatever answer it
          // carries is the user's latest word, and a repair that ran first
          // would overwrite it before anything had read it.
          CaldavAnswerAdoptionService.Outcome answer = adoptAnswer(userIdentityId, object, assessment.remote());
          if (answer == CaldavAnswerAdoptionService.Outcome.FAILED) {
            // The object still holds the only record of the user's answer.
            // Nothing may overwrite it; the next pass reads it again.
            continue;
          }
          if (answer == CaldavAnswerAdoptionService.Outcome.ADOPTED) {
            adopted++;
            recordClientWrite(object, assessment.remote(), etagOf(object.getRemoteHref(), etags));
          }
        } else if (assessment.verdict() == Verdict.ALTERED && holdsAnAnswerNothingHasRead(userIdentityId, object, assessment)) {
          held++;
          continue;
        }
        if (giveUpOn(userIdentityId, object)) {
          abandoned++;
          settled.put(keyOf(userIdentityId, object), versionOf(object, etags));
        } else if (repair(userIdentityId, object, assessment.verdict())) {
          repaired++;
        }
      }
      objects = caldavSyncStorage.getObjects(mirror.getId(), ++page, PAGE_SIZE).getContent();
    }
    if (missing > 0 || altered > 0) {
      LOG.info("Mirror of user {}: {} checked, {} missing, {} altered, {} answers adopted, {} re-pushed, {} abandoned, "
          + "{} left alone as carrying an unread answer",
               userIdentityId,
               checked,
               missing,
               altered,
               adopted,
               repaired,
               abandoned,
               held);
    }
    return new MirrorVerification(checked, missing, altered, adopted, repaired, abandoned);
  }

  /**
   * Reads the owner's answer off a rewritten copy and records it in agenda.
   *
   * <p>
   * Only for a copy whose content could be fetched and whose eXo event is
   * known — anything else has no answer to read or no event to record it
   * against, and falls through to the ordinary repair.
   *
   * @param userIdentityId identity of the user
   * @param object the mapping row of the rewritten copy
   * @param remote the copy as the client left it, may be null
   * @return what the adoption did
   */
  private CaldavAnswerAdoptionService.Outcome adoptAnswer(long userIdentityId, ObjectSync object, CalendarObject remote) {
    if (remote == null || StringUtils.isBlank(remote.calendarData())
        || object.getLocalEventId() == null || object.getLocalEventId() <= 0) {
      return CaldavAnswerAdoptionService.Outcome.NOTHING;
    }
    return caldavAnswerAdoptionService.adoptAnswer(userIdentityId, object.getLocalEventId(), remote.calendarData());
  }

  /**
   * Whether a copy this pass is about to overwrite carries an answer of the
   * owner's that agenda does not hold (EXO-89814).
   *
   * <p>
   * <b>The case this exists for.</b> A settings round removes the ETag gate:
   * every mapped copy is fetched and judged on its content, and a copy that
   * differs is repaired. On a server that records an answer <i>without</i>
   * moving the ETag — BlueMind, measured — the answer on such a copy is
   * precisely the difference the round finds, and {@code clientWrote} is false,
   * so nothing adopts it and the repair writes eXo's own render straight over
   * it. The user's answer is not merely unread at that point: it is destroyed,
   * and it existed nowhere else.
   *
   * <p>
   * <b>Why it refuses the repair rather than adopting.</b> Adopting would need
   * to know the direction, and an unmoved version is exactly the absence of
   * that knowledge: the copy may carry the client's latest word, or it may
   * carry eXo's own previous one while a newer answer given in eXo waits on a
   * push that has not landed. Adopting the second case would revert the user's
   * answer, which is the very failure the ETag gate of EXO-89681 exists to
   * prevent and which EXO-89807 was careful not to reopen. Leaving the copy
   * alone is safe in both readings — nothing is lost either way — and it
   * resolves itself: {@link CaldavMirrorAnswerService} adopts the answer on a
   * later sweep, after which agenda and the copy agree and the copy repairs
   * like any other.
   *
   * <p>
   * <b>The round is still stamped as applied.</b> Holding the stamp back would
   * make the round owed for ever on a server where the answer can never be
   * adopted, and a settings round costs a fetch per copy — an owed round that
   * never completes is one of those on every sweep, which is the shape that got
   * this rig's test proxy banned twice. A handful of copies keeping the
   * settings they were written with is the smaller wrong, and it is said out
   * loud rather than left to be discovered.
   *
   * @param userIdentityId identity of the account's owner
   * @param object the mapping row of the copy about to be repaired
   * @param assessment the verdict, carrying the copy as the server holds it
   * @return true when the copy must not be written over
   */
  private boolean holdsAnAnswerNothingHasRead(long userIdentityId, ObjectSync object, Assessment assessment) {
    CalendarObject remote = assessment.remote();
    if (remote == null || StringUtils.isBlank(remote.calendarData()) || object.getLocalEventId() == null
        || object.getLocalEventId() <= 0) {
      return false;
    }
    boolean unread = caldavAnswerAdoptionService.holdsUnrecordedAnswer(userIdentityId,
                                                                      object.getLocalEventId(),
                                                                      remote.calendarData());
    if (unread) {
      LOG.warn("The copy at {} carries an answer eXo does not hold and its version never moved; it is left as it is "
          + "rather than written over",
               object.getRemoteHref());
    }
    return unread;
  }

  /**
   * Records the client's write as the copy this connector now stands behind.
   *
   * <p>
   * This is what stops the adoption looping. Without it, the next pass sees
   * the same moved ETag, reads the same answer, and adopts it again — for
   * ever, and over any answer the user gives in eXo later. With it, the pass
   * only ever adopts a write that happened <i>after</i> the last one it
   * looked at. Recorded before the repair rather than after, so a repair that
   * fails cannot reopen the loop; the repair's own write then records its own
   * ETag over this one.
   *
   * <p>
   * The ETag is the whole of it, and it always was. This used to record a
   * digest of the client's bytes as well, but that digest could only ever
   * matter for an object the ETag gate had already let through — and the gate
   * only lets an object through when its version moved, which is precisely
   * when the pass wants to look again. EXO-89716 removed every stored digest
   * because none can be captured reliably on a re-serialising server; keeping
   * one here would have put back, on the one path that must not loop, the
   * state that never converges.
   *
   * <p>
   * <b>The listing's value, not the fetch's</b>, and on a server where the two
   * agree that sentence costs nothing. Where they do not it is the whole of
   * EXO-89809: the gate above compares the row against the
   * <i>collection listing</i>, so a row holding the value some other channel
   * published is a row the listing never agrees with, and the answer on this
   * copy is read again on every sweep for ever — silently, because a copy that
   * has already given up its answer changes nothing when it gives it up again.
   * BlueMind is such a server: its {@code DAV:getetag} property is a quoted
   * base64 of the value its own {@code ETag} response header carries in plain,
   * and no normalisation turns one into the other. Recording the listing's
   * value is also the conservative half of the choice: the fetch happened after
   * the listing, so its version can only be the newer of the two, and a row
   * holding the older one has the next pass look again rather than skip a write
   * that landed in between.
   *
   * @param object the mapping row of the copy
   * @param remote the copy as the client left it, whose own ETag is used only
   *          when the listing carried none
   * @param listedEtag the ETag the collection listing carried for the copy —
   *          the value the next pass's gate compares against
   */
  private void recordClientWrite(ObjectSync object, CalendarObject remote, String listedEtag) {
    object.setEtag(StringUtils.isNotBlank(listedEtag) ? listedEtag : remote.etag());
    object.setLastSync(new java.util.Date());
    caldavSyncStorage.saveObject(object);
  }

  /**
   * What the server's listing says about one copy.
   *
   * <p>
   * The ETag decides <i>whether</i> anything happened; it never decides what.
   * That split is deliberate and belongs to EXO-89681: an ETag that has not
   * moved is the server's own promise that nobody wrote, and it is the reason
   * this pass costs one listing on a converged mirror rather than one fetch per
   * copy.
   *
   * <p>
   * <b>The settings round removes the gate and keeps the evidence.</b> When a
   * setting that governs the copies has changed, an unchanged version is no
   * longer a reason to skip the comparison — eXo's own render moved, and the
   * server had no way to say so. What the version still decides is
   * <i>direction</i>: it is passed on as {@code clientWrote}, so a copy whose
   * version never moved is judged on its content and is still not treated as
   * carrying the user's latest answer.
   *
   * @param userIdentityId identity of the user
   * @param object the mapping row
   * @param settings the connected account
   * @param etags what the server currently holds, by href
   * @param settingsRound whether the ETag gate is lifted for this round
   * @param server the registration the account is connected through, may be
   *          null
   * @return the verdict, carrying the fetched copy when there is one to act on
   */
  private Assessment judge(long userIdentityId,
                           ObjectSync object,
                           CaldavUserSetting settings,
                           Map<String, String> etags,
                           boolean settingsRound,
                           CaldavServer server) {
    String etag = etagOf(object.getRemoteHref(), etags);
    if (etag == null) {
      return new Assessment(Verdict.MISSING, null, false);
    }
    // The same ETag, or a server that publishes none we can compare. Either way
    // this is not the client's writing.
    boolean clientWrote = StringUtils.isNotBlank(object.getEtag())
        && !StringUtils.equals(normalise(etag), normalise(object.getEtag()));
    if (!clientWrote && !settingsRound) {
      // There is no reason to spend a fetch: an unchanged ETag is the server's
      // own promise that nobody has written since eXo did. It is also the
      // direction rule's other half: untouched since eXo wrote it, so any
      // difference with agenda is eXo-side and the ordinary push overwrites the
      // copy.
      return new Assessment(Verdict.UNTOUCHED, null, false);
    }
    return assessContent(userIdentityId, object, settings, etag, clientWrote, server);
  }

  /**
   * Whether what the server holds still says what eXo would write for this
   * event — and, when it does not, the copy itself, because the answer the
   * client wrote on it is read from there.
   *
   * <p>
   * An ETag moves for reasons that are not a rewrite — a server touching its
   * own metadata, a change of storage format, a re-serialisation on store — so
   * the object is read and its <i>content</i> judged, never trusted to the
   * version alone.
   *
   * <p>
   * The render comes before the fetch, and that ordering is the whole cost
   * argument: a row this pass cannot judge costs no network call at all. It
   * takes nothing from the answer path either, because every case that skips
   * the fetch here is a case {@code adoptAnswer} would have answered
   * {@code NOTHING} to — it needs the same eXo event this needs to render one.
   *
   * <p>
   * The owner's own addresses go with the comparison, and both of them do:
   * a server may attach the calendar's owner to a copy that lands in their
   * calendar, and recognising that line means recognising the person on it —
   * who a copy names either by the address their CalDAV account answers to or
   * by their eXo profile address. The pair comes from
   * {@code CaldavPushService.addressesNaming}, the same one EXO-89715 uses to
   * find their line and EXO-89681 uses to read their answer off it, because an
   * exemption that checked only one of the two would miss the way 89715 missed.
   *
   * <p>
   * Four ways to answer "no rewrite" and they are not the same. The copy states
   * what eXo states: the version is adopted and the next pass is free. eXo
   * cannot say what it would write — the event is gone, the mapping never
   * carried one, the render is unusable: nothing is concluded, and the copy is
   * left exactly as it is. The server cannot be read at all: likewise, because
   * a re-push on the strength of a network error would overwrite a user's
   * calendar with no evidence. And the comparison itself failing is the same
   * answer again, for the reason the fetch's {@code LinkageError} records —
   * one unreadable object must leave the other copies unexamined rather than
   * end the pass.
   *
   * @param userIdentityId identity of the user
   * @param object the mapping row
   * @param settings the connected account
   * @param currentEtag the version the listing published for it
   * @param clientWrote whether the version moved away from the one eXo
   *          recorded, which is the only proof that the copy carries somebody
   *          else's writing — and therefore an answer worth reading
   * @param server the registration the account is connected through, may be
   *          null
   * @return the verdict, carrying the fetched copy when there is one to act on
   */
  private Assessment assessContent(long userIdentityId,
                                   ObjectSync object,
                                   CaldavUserSetting settings,
                                   String currentEtag,
                                   boolean clientWrote,
                                   CaldavServer server) {
    if (object.getLocalEventId() == null || object.getLocalEventId() <= 0) {
      // Nothing to compare against, nothing a repair could write, and no event
      // to record an answer against either.
      LOG.debug("The copy at {} stands for no known event; its content is not judged", object.getRemoteHref());
      return new Assessment(Verdict.UNTOUCHED, null, false);
    }
    String rendered;
    try {
      rendered = caldavPushService.renderAgendaEvent(userIdentityId, object.getLocalEventId(), object.getIcsUid());
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("Event {} could not be rendered; its copy is left alone", object.getLocalEventId(), e);
      return new Assessment(Verdict.UNTOUCHED, null, false);
    }
    if (StringUtils.isBlank(rendered)) {
      LOG.debug("Event {} renders to nothing; its copy is left alone", object.getLocalEventId());
      return new Assessment(Verdict.UNTOUCHED, null, false);
    }
    CalendarObject remote;
    try {
      CalDavEndpoint endpoint = calDavClient.endpoint(settings.getServerId(), settings.getUsername());
      remote = calDavClient.fetchObject(endpoint,
                                        object.getRemoteHref(),
                                        settings.getUsername(),
                                        settings.getPassword());
    } catch (RuntimeException | LinkageError e) {
      // Unreadable is not the same as rewritten, and a re-push on this path
      // would overwrite whatever is there on the strength of a network error.
      //
      // LinkageError belongs here for the same reason it belongs on the
      // sweep: an object can be unreadable because the parser is missing a
      // class it only needs for certain content, and one such object must
      // leave the other copies unexamined rather than end the pass.
      LOG.debug("The copy at {} could not be read back; it is left alone", object.getRemoteHref(), e);
      return new Assessment(Verdict.UNTOUCHED, null, false);
    }
    if (remote == null || StringUtils.isBlank(remote.calendarData())) {
      // The server answered and holds nothing readable where the copy should
      // be: rewritten into something that is not the copy, with no answer on
      // it to read.
      return new Assessment(Verdict.ALTERED, null, false);
    }
    IcsJudgement judgement;
    try {
      judgement = icsEquivalence.compare(remote.calendarData(),
                                         rendered,
                                         caldavPushService.addressesNaming(userIdentityId, settings),
                                         server == null ? null : server.getIgnoredProperties(),
                                         server == null ? null : server.getDroppedProperties());
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("The copy at {} could not be judged; it is left alone", object.getRemoteHref(), e);
      return new Assessment(Verdict.UNTOUCHED, null, false);
    }
    // Recorded whatever the verdict is, and that is the point: a divergence
    // this server's administrator has already excused is still reported by the
    // comparison, so it goes on being counted and goes on appearing in the
    // drawer with its box ticked. Recorded only when it changes nothing about
    // what happens next — the summary is what an administrator decides from,
    // never what this pass decides from.
    caldavServerQuirkService.observe(server == null ? null : server.getId(), judgement.divergences());
    if (judgement.different()) {
      // Named, not counted. A pass that keeps finding the same divergence is
      // the failure this design is answering, and the only way anyone can see
      // which property is doing it is if the line says so. The copy travels
      // with the verdict: the answer its writer left on it is read from there,
      // before any repair overwrites it.
      LOG.info("The copy at {} no longer states what eXo writes: {}", object.getRemoteHref(), judgement.detail());
      // The copy travels with the verdict, and the direction travels beside it
      // rather than being spelt by the copy's absence. Adoption is still
      // licensed by the version having moved and by nothing else: in a settings
      // round the difference is usually eXo's own — the render changed under a
      // copy nobody touched — and adopting there would read eXo's own last
      // writing back as though it were the user's latest answer, over whatever
      // they have since said in eXo. What the copy is needed for on that path
      // is the opposite question, asked before the repair rather than instead
      // of it: whether it carries an answer nothing has read, in which case it
      // must not be written over (EXO-89814).
      return new Assessment(Verdict.ALTERED, remote, clientWrote);
    }
    if (judgement.verdict() == IcsJudgement.Verdict.UNJUDGEABLE) {
      LOG.debug("The copy at {} cannot be judged ({}); it is left alone", object.getRemoteHref(), judgement.detail());
      return new Assessment(Verdict.UNTOUCHED, null, false);
    }
    // The listing's value first, the fetch's only when the listing carried
    // none. See adoptVersion: what is recorded here is compared against the
    // listing on the next pass, so it has to be the listing's own value
    // (EXO-89809). The third argument is EXO-89814's: direction travels beside
    // the verdict rather than being spelt by the copy's absence.
    adoptVersion(object, StringUtils.defaultIfBlank(currentEtag, remote.etag()));
    return new Assessment(Verdict.UNTOUCHED, null, false);
  }

  /**
   * Records the version a server now publishes for a copy whose content is
   * exactly the one eXo wrote.
   *
   * <p>
   * The comparison above exists because an ETag moves for reasons that are not
   * a rewrite. Having paid a fetch to establish that this is one of them, the
   * pass has learnt the object's current version and there is no reason to
   * keep the superseded one: kept, the listing disagrees with the row on every
   * later pass and every later pass fetches the object again to reach the same
   * conclusion — and, worse, the next ordinary update carries that superseded
   * version as its {@code If-Match} and is refused, which is an eXo-side edit
   * silently not reaching the copy.
   *
   * <p>
   * Only ever on a copy that was just judged equivalent. This is not "trust the
   * server's version", it is "the copy still says what we say, so this version
   * names our copy". It is also what makes the pass fall silent: after the
   * first sweep absorbs a server's own serialisation, the listing and the row
   * agree and nothing is fetched again until somebody actually writes.
   *
   * <p>
   * <b>"The version the server publishes" means the one the collection listing
   * publishes</b>, and EXO-89809 is what happens when it does not. The gate is
   * a comparison against the listing; recording anything else leaves the row
   * disagreeing with the listing on the very next pass, so the fetch this
   * method exists to make unnecessary is paid again, and again, for ever — and
   * in complete silence, because a copy that compares equal says nothing. The
   * caller used to hand this the ETag of the <i>fetch</i> in preference to the
   * listing's, which is identical on a server whose channels agree and useless
   * on one whose channels do not: BlueMind answers a GET or a PUT with
   * {@code bmdav_2859517047_0} and publishes {@code DAV:getetag} as
   * {@code "Ym1kYXZfMjY4MjA1MjMzOF8xMjc="} — a quoted base64 of the same kind
   * of value, carrying the real version rather than 0. {@link #normalise}
   * strips quoting and a weak marker; it cannot decode one form into the other,
   * and it must not learn to, because a comparison that decodes a server's
   * private encoding is a comparison nobody can reason about. The value stored
   * is what is fixed instead.
   *
   * <p>
   * The stored value is also the {@code If-Match} of the next ordinary update,
   * so this is the value the server is asked to match. Recording what the
   * server publishes as the resource's entity-tag is what that precondition is
   * defined against; recording what some other channel answered is not.
   *
   * @param object the mapping row whose content was just confirmed
   * @param currentEtag the version the collection listing publishes now, may be
   *          blank
   */
  private void adoptVersion(ObjectSync object, String currentEtag) {
    if (StringUtils.isBlank(currentEtag) || StringUtils.equals(normalise(currentEtag), normalise(object.getEtag()))) {
      return;
    }
    object.setEtag(currentEtag);
    caldavSyncStorage.saveObject(object);
  }

  /**
   * Writes the copy again.
   *
   * <p>
   * The link back into eXo is not restored: it is carried on the push request
   * and never stored, so a repair writes the meeting without it. Everything
   * that makes the copy useful on the user's other devices — when it is, what
   * it is called, where it is — comes from the event itself.
   *
   * <p>
   * The write is unconditional, and has to be. A guarded write carries the
   * etag this connector last recorded, and the server refuses it when the
   * object has moved on — which is the definition of the case being repaired
   * here. Guarded, every repair failed with a conflict and the pass reported
   * "1 altered, 0 re-pushed" forever. The overwrite is safe because the
   * comparison that led here has already read both copies.
   *
   * @param userIdentityId identity of the user
   * @param object the mapping row to repair
   * @param verdict why it is being repaired
   * @return true when the copy was written
   */
  private boolean repair(long userIdentityId, ObjectSync object, Verdict verdict) {
    if (object.getLocalEventId() == null || object.getLocalEventId() <= 0) {
      // A copy whose eXo event is not recorded cannot be rebuilt from
      // anything. What happens next depends on which side is still there.
      if (verdict == Verdict.MISSING && object.getId() > 0) {
        // Neither side is: the object has been deleted from the server and no
        // eXo event stands behind it, so the row describes nothing at all.
        // Keeping it was not caution — it made the row unrepairable *and*
        // permanent, reported missing on every pass, which is what kept a
        // live account's calendar flagged as needing attention with a count
        // that never moved.
        LOG.info("The mapping at {} stands for no event and for nothing on the server; it is dropped",
                 object.getRemoteHref());
        caldavSyncStorage.deleteObject(object.getId());
        return false;
      }
      // The object is still on the server, only changed. Dropping the row
      // would lose the only link to it, and that would be a guess about data
      // the user may want.
      LOG.debug("The copy at {} stands for no known event and cannot be repaired", object.getRemoteHref());
      return false;
    }
    try {
      ObjectSync written = caldavPushService.rewriteAgendaEvent(userIdentityId, object.getLocalEventId());
      LOG.info("The copy of event {} was {} on the server and has been written again",
               object.getLocalEventId(),
               verdict == Verdict.MISSING ? "deleted" : "rewritten");
      dropIfSuperseded(object, written);
      return true;
    } catch (RuntimeException e) {
      // The event may have been deleted in eXo since, or the account may be
      // refusing writes. Either way the next pass tries again, up to the
      // limit above.
      LOG.warn("The copy of event {} could not be written again", object.getLocalEventId(), e);
      return false;
    }
  }

  /**
   * Forgets a mapping row the repair did not write to.
   *
   * <p>
   * A push writes to the href recorded against the event's iCalendar UID, and
   * one UID has one row. So when a row is reported missing and the repair
   * comes back having written somewhere else, this row is not the copy — it
   * is a second row left over for a copy that has since moved, and the object
   * it names is gone for good. Kept, it is reported missing on every pass
   * forever: the calendar never stops "needing attention", and the count
   * never reaches zero however many times the repair succeeds.
   *
   * @param object the row that was repaired
   * @param written the row the push actually wrote, or null
   */
  private void dropIfSuperseded(ObjectSync object, ObjectSync written) {
    // Objects.equals, not ==: these identifiers are Long, so == compares
    // references and answers false for any value outside the boxing cache —
    // which is every identifier a real database hands out. Written as ==,
    // this deleted the row the repair had just refreshed, and the test missed
    // it because the identifier it used was small enough to be cached.
    if (written == null || object.getId() == null || object.getId() <= 0
        || Objects.equals(written.getId(), object.getId())) {
      return;
    }
    LOG.info("The mapping at {} stood for a copy now written to {}; the stale one is dropped",
             object.getRemoteHref(),
             written.getRemoteHref());
    caldavSyncStorage.deleteObject(object.getId());
  }

  /**
   * Whether this object has been repaired often enough to stop.
   *
   * @param userIdentityId identity of the user
   * @param object the mapping row
   * @return true when the pass should leave it alone and say so
   */
  private boolean giveUpOn(long userIdentityId, ObjectSync object) {
    String key = keyOf(userIdentityId, object);
    int attempts = repairs.merge(key, 1, Integer::sum);
    if (attempts <= maxRepairs) {
      return false;
    }
    if (attempts == maxRepairs + 1) {
      // Once, not on every pass: this is a state, and repeating it every few
      // minutes would bury the log it is meant to stand out in.
      LOG.warn("The copy at {} has been repaired {} times and keeps going wrong; eXo stops re-pushing it",
               object.getRemoteHref(),
               maxRepairs);
    }
    return true;
  }

  /**
   * Whether an object has already been abandoned and the server still publishes
   * the very version it was abandoned at.
   *
   * <p>
   * Deliberately two conditions, not one. Abandonment alone would silence the
   * copy for good, including the pass that reads the owner's answer off it; the
   * version is what makes the silence conditional on nothing having happened.
   * The count is read rather than incremented — this is not another attempt.
   *
   * @param userIdentityId identity of the user
   * @param object the mapping row
   * @param etags what the server currently holds, by href
   * @return true when the pass has nothing left to learn about this copy
   */
  private boolean hasSettled(long userIdentityId, ObjectSync object, Map<String, String> etags) {
    String key = keyOf(userIdentityId, object);
    if (repairs.getOrDefault(key, 0) <= maxRepairs) {
      return false;
    }
    return StringUtils.equals(settled.get(key), versionOf(object, etags));
  }

  /**
   * The version the collection listing publishes for a copy, in the form the
   * settled state compares.
   *
   * <p>
   * A copy the listing does not carry is a version too — {@link #ABSENT} —
   * rather than nothing at all. An abandoned object that has been deleted from
   * the server stays deleted, and reporting it missing on every pass for ever is
   * the same pointless round trip in its other form; if it comes back, the
   * version is no longer {@code ABSENT} and the pass looks again.
   *
   * @param object the mapping row
   * @param etags what the server currently holds, by href
   * @return the comparable version, never null
   */
  private String versionOf(ObjectSync object, Map<String, String> etags) {
    String etag = etagOf(object.getRemoteHref(), etags);
    return etag == null ? ABSENT : normalise(etag);
  }

  /**
   * The key one copy is remembered by, in both in-memory maps.
   *
   * @param userIdentityId identity of the user
   * @param object the mapping row
   * @return the key
   */
  private String keyOf(long userIdentityId, ObjectSync object) {
    return userIdentityId + "|" + object.getRemoteHref();
  }

  /**
   * Forgets what is known about an account's repairs.
   *
   * <p>
   * The settled versions go with them: they are the second half of the same
   * state, and a settled version left behind after the repair counts were
   * cleared would be consulted by {@link #hasSettled} for an object that is no
   * longer abandoned — harmless today because the count is checked first, and a
   * trap for the next change that reorders those two checks.
   *
   * @param userIdentityId identity of the user
   */
  public void forgetRepairs(long userIdentityId) {
    repairs.keySet().removeIf(key -> key.startsWith(userIdentityId + "|"));
    settled.keySet().removeIf(key -> key.startsWith(userIdentityId + "|"));
    // And what the relocation remembers about the same account, for the same
    // reason: it is the third map of "what is going wrong right now" for this
    // user, and forgetting two of the three would leave a refused removal
    // silent after the very restart that is meant to say it again.
    caldavMirrorRelocationService.forget(userIdentityId);
    // And the tally an administrator reads, for the plainest reason of the
    // three: a number attributed to an account that no longer exists is not
    // stale, it is wrong.
    caldavMirrorReportService.forget(userIdentityId);
  }

  /**
   * The ETag the listing carries for a path, matching the way hrefs are
   * compared everywhere else in this add-on.
   *
   * @param href the path recorded for the copy
   * @param etags the listing
   * @return the ETag, or null when the server no longer holds it
   */
  private String etagOf(String href, Map<String, String> etags) {
    String canonical = CaldavSyncStorage.canonicalHref(href);
    for (Map.Entry<String, String> entry : etags.entrySet()) {
      if (StringUtils.equals(canonical, CaldavSyncStorage.canonicalHref(entry.getKey()))) {
        return entry.getValue();
      }
    }
    return null;
  }

  /**
   * An ETag without its quoting or weak marker, which servers vary.
   *
   * @param etag the value as published
   * @return the value to compare
   */
  private String normalise(String etag) {
    return StringUtils.removeStart(StringUtils.strip(etag, "\""), "W/").replace("\"", "");
  }

  /** What one copy turned out to be. */
  private enum Verdict {
    UNTOUCHED, MISSING, ALTERED
  }

  /**
   * The verdict on one copy, carrying the fetched object when there is one.
   *
   * <p>
   * <b>The copy and the direction are two facts, and they used to be one.</b>
   * {@code remote} was handed on only when the version had moved, so a copy
   * fetched in a settings round on a server that answers without moving it
   * arrived here as {@code null} — and a null copy reads to every caller as
   * "there is nothing on it", which is how a repair came to be allowed to write
   * over an answer nobody had looked at (EXO-89814). The copy is now always
   * carried when it was read, and {@code clientWrote} says the one thing it
   * used to say by its absence: that the version moved, which is the only proof
   * the copy holds the user's latest word and therefore the only licence to
   * adopt off it. Nothing about the adoption rule changed — the licence is the
   * same one, said out loud.
   *
   * @param verdict what the copy turned out to be
   * @param remote the copy as the server holds it, whenever it was readable —
   *          for an ALTERED copy that is what an answer is read from, and what
   *          a refusal to repair is decided on
   * @param clientWrote whether the version moved away from the one eXo
   *          recorded, which is the only case an answer may be adopted in
   */
  private record Assessment(Verdict verdict, CalendarObject remote, boolean clientWrote) {
  }
}
