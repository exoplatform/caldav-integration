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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.PutResult;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * <h2>Moves the copies already on the server when the destination changes.</h2>
 *
 * <p>
 * <b>The problem.</b> EXO-89760 let an administrator say, per server, where the
 * meeting copies are written. On its own that setting only governs the
 * <i>next</i> push: the copies already written stay in the calendar they were
 * put in, indefinitely, while every new one lands somewhere else. A user then
 * has their meetings split across two calendars with nothing saying why, and a
 * setting that silently strands a hundred objects in the calendar it just
 * stopped using is worse than no setting at all.
 *
 * <p>
 * <b>The idiom already exists; this is its bulk case.</b> The push path has
 * moved a single copy between collections since EXO-89597 — write into the new
 * place, then conditionally delete the old one, keeping it when the delete is
 * refused because a client edited it ({@code CaldavPushService}'s
 * {@code mappingElsewhere} / {@code removeWhatWasLeftBehind}). Nothing here
 * invents a second discipline; it applies that one to every mapped object of a
 * mirror, a page at a time.
 *
 * <p>
 * <b>The pair is re-pointed first.</b> Before a single object moves, the
 * destination is resolved and written onto the mirror pair — so from that
 * instant every new push and every repair goes to the new collection, and the
 * relocation is only ever catching the backlog up. Re-pointing last would leave
 * a window in which the sweep wrote into the calendar being emptied.
 *
 * <p>
 * <b>Resumability is the mapping row itself, and deliberately nothing else.</b>
 * The row's own href is the progress marker: an object whose href is not under
 * the destination has not moved yet, whatever happened to the process that was
 * moving it. A restart therefore re-enters exactly where it stopped, with no
 * bookkeeping to reconcile. A "moving" value on the pair's status was the
 * obvious alternative and is the wrong one twice over — every existing status
 * value gates other machinery (the sweep's due-pair query, the deletion guards,
 * the settings screen), and a crash with a sticky status leaves the pair stuck
 * in a state nothing clears.
 *
 * <p>
 * <b>The answer is read before the move, and that ordering is the whole of it.</b>
 * A copy whose ETag has moved away from the one eXo recorded was written by the
 * user's own client, and the answer on it is their latest word (EXO-89681). Once
 * the mapping points at the new href, the old copy is no longer watched by
 * anything — the verification pass lists the destination collection and nothing
 * else — so an answer left on it would simply be lost. Reading first is the same
 * ordering the sweep already enforces between adoption and repair, for the same
 * reason.
 *
 * <p>
 * <b>What it refuses to do.</b> It never deletes the emptied collection. Removing
 * a calendar from somebody's account is exactly what this add-on's deletion
 * guards exist to prevent, and the cost of leaving it is nothing: an empty
 * calendar is inert, it is visible, and the user can remove it themselves if
 * they want to. It never re-pushes a copy that is already under the destination,
 * so an account that was already converged comes out of a pass having written,
 * moved and deleted nothing at all.
 *
 * <p>
 * <b>What a refusal costs.</b> A copy the server would not remove is logged
 * once — not once per sweep, which would bury the line it is meant to stand out
 * in — counted, and left where it is. While anything was refused or failed the
 * change is <b>not</b> stamped as applied, so the next pass tries again.
 */
@Service
public class CaldavMirrorRelocationService {

  private static final Log        LOG       = ExoLogger.getExoLogger(CaldavMirrorRelocationService.class);

  /** How many mapping rows one pass reads at a time, as the verification pass reads them. */
  private static final int        PAGE_SIZE = 200;

  /**
   * What the account's destination is remembered under in {@link #reported}.
   *
   * <p>
   * The set is keyed by user and href, and this stands in the href's place for
   * the one entry that is about no particular copy. It cannot collide with a
   * real path: every one of those is server-absolute and starts with a slash.
   */
  private static final String     DESTINATION = "destination";

  @Autowired
  private CalDavClient            calDavClient;

  @Autowired
  private CaldavSyncStorage       caldavSyncStorage;

  @Autowired
  private CaldavPushService       caldavPushService;

  @Autowired
  private CaldavAnswerAdoptionService caldavAnswerAdoptionService;

  /**
   * The copies whose removal has already been reported, keyed by user and old
   * href.
   *
   * <p>
   * In memory, beside the verification pass's repair counts and for the same
   * reason: this records something going wrong right now, not a fact about the
   * account worth carrying across a restart. A restart says it again once,
   * which is the right bias — the state it describes is one an administrator
   * may need reminding of after a deploy.
   */
  private final Set<String>       reported  = ConcurrentHashMap.newKeySet();

  /**
   * Brings the copies already on the server to where the destination now says
   * they go.
   *
   * <p>
   * Called only when a copy-governing setting has changed (EXO-89759), which is
   * the one moment the destination can have moved: the fingerprint that moves
   * that stamp reads the model's own fields, so {@code mirrorTarget} is in it
   * without naming itself. On the far commoner change that is <i>not</i> a
   * destination change — the answer-links switch, an excusal list — the
   * destination resolves to the collection the pair already points at, nothing
   * is pending, and this costs one resolution and one page read.
   *
   * @param userIdentityId identity of the user whose copies are moved
   * @param settings the connected account
   * @param mirror the binding standing for the mirror collection
   * @return what the pass moved, and whether the change may be stamped applied
   */
  public MirrorRelocation relocate(long userIdentityId, CaldavUserSetting settings, CalendarSync mirror) {
    String destination = destinationOf(userIdentityId);
    if (destination == null) {
      return MirrorRelocation.deferred();
    }
    CalendarSync pair = repoint(userIdentityId, mirror, destination);
    if (pair == null) {
      return MirrorRelocation.deferred();
    }
    return movePending(userIdentityId, settings, pair, destination);
  }

  /**
   * Forgets what is remembered about an account's refused removals, so that a
   * fresh attempt says out loud what it finds.
   *
   * @param userIdentityId identity of the user
   */
  public void forget(long userIdentityId) {
    reported.removeIf(key -> key.startsWith(userIdentityId + "|"));
  }

  /**
   * Where this account's copies now go, asked of the one place that decides it.
   *
   * <p>
   * {@code CaldavPushService.ensureMirror} and nothing else, which is
   * EXO-89760's whole constraint: a second reader of the destination setting
   * would be a second answer, and the two would disagree on exactly the account
   * where it mattered. It also creates the dedicated calendar when a move back
   * to it finds it gone, and records the destination on the account, both of
   * which this needs and neither of which belongs here.
   *
   * <p>
   * <b>Every failure defers rather than guesses.</b> An account that cannot be
   * reached answers nothing, and a registration asking for a default calendar
   * the account names none of refuses. Both mean "not yet", and both leave the
   * change unstamped so that the pass which can finish it does.
   *
   * <p>
   * <b>But they are not the same silence, and this used to make them one.</b>
   * Every {@code RuntimeException} was recorded at debug, so a state of the
   * account and an outright failure of the attempt — rejected credentials, a
   * refused collection, a 500 — read identically in a log, which is to say not
   * at all. A state a person has to clear is recorded at debug, without a
   * trace, because the service that decides the destination has already said it
   * once; anything else is a failure and is said at warn with its trace, once,
   * through the same {@link #sayOnce} the refused removals go through, so a
   * server that is down for an afternoon costs one line rather than one per
   * sweep.
   *
   * @param userIdentityId identity of the user
   * @return the collection the copies belong in, or null when none could be
   *         established
   */
  private String destinationOf(long userIdentityId) {
    try {
      MirrorTarget target = caldavPushService.ensureMirror(userIdentityId);
      String href = target == null || StringUtils.isBlank(target.href()) ? null : target.href();
      if (href != null) {
        // Out of whatever state this account was in: the next spell of one is
        // said again rather than remembered as already reported.
        reported.remove(userIdentityId + "|" + DESTINATION);
      }
      return href;
    } catch (CaldavPushException refusal) {
      if (isDeferredState(refusal.getCode())) {
        LOG.debug("User {} has no destination for their copies yet ({}); they are not moved this pass",
                  userIdentityId,
                  refusal.getCode());
      } else {
        sayOnce(userIdentityId,
                DESTINATION,
                "The destination of the meeting copies of user {} could not be established ({}); their copies are not"
                    + " moved until it can be",
                userIdentityId,
                refusal.getCode(),
                refusal);
      }
      return null;
    } catch (RuntimeException | LinkageError e) {
      sayOnce(userIdentityId,
              DESTINATION,
              "The destination of the meeting copies of user {} could not be established; their copies are not moved"
                  + " until it can be",
              userIdentityId,
              e);
      return null;
    }
  }

  /**
   * Whether a refusal names a state of the account rather than a failed
   * attempt.
   *
   * <p>
   * The three this method's own contract already describes: an account that is
   * no longer connected, one whose registration leaves the destination to a
   * user who has not chosen, and one asked for a main calendar that cannot be
   * resolved. None of them is an incident, none of them is cleared by retrying,
   * and {@code CaldavPushService} has already said the last of them once, at
   * warn, naming the server and what was asked for.
   *
   * <p>
   * <b>Anything else is a failure</b>, including a code this version does not
   * know: a default of "state" would make this a silencer the next time the
   * vocabulary grows. EXO-89798 is introducing {@code isKnownState} over the
   * same vocabulary in {@code CaldavPushService}; when it lands, this method is
   * a call to it and nothing else.
   *
   * @param code the code the refusal carries, may be null
   * @return true when only a person can clear it
   */
  private boolean isDeferredState(String code) {
    // EXO-89798's classification, not a second list beside it. This shipped as
    // a local copy only because that branch was unmerged; it has since landed,
    // so the two collapse as its javadoc said they would. Delegating also picks
    // up the guard rail: a code added later must be classified or a test fails.
    return CaldavPushService.isKnownState(code);
  }

  /**
   * Points the mirror pair at the destination, before anything is moved.
   *
   * <p>
   * First, and it has to be first. The pair is what the push writes into and
   * what the verification pass lists; until it points at the destination, every
   * write this pass triggers lands back in the collection being emptied. Doing
   * it first also means a crash immediately afterwards is the ordinary resumable
   * state — the pair is right, the rows are behind — rather than a state nothing
   * else in the add-on understands.
   *
   * <p>
   * The row is read again rather than the one the caller carried: the sync pass
   * writes the token, the ctag and the timestamps on the same row, and with
   * {@code @DynamicUpdate} on the entity only the href genuinely differs from
   * the row as it now stands.
   *
   * @param userIdentityId identity of the user, for the log
   * @param mirror the binding the caller is working from
   * @param destination where the copies now go
   * @return the pair as it now stands, or null when it has been deleted under
   *         the pass
   */
  private CalendarSync repoint(long userIdentityId, CalendarSync mirror, String destination) {
    CalendarSync pair = caldavSyncStorage.getPair(mirror.getId());
    if (pair == null) {
      LOG.debug("The mirror pair {} is gone; there is nothing left to move copies into", mirror.getId());
      return null;
    }
    String current = CaldavSyncStorage.canonicalHref(pair.getRemoteHref());
    String target = CaldavSyncStorage.canonicalHref(destination);
    if (StringUtils.equals(current, target)) {
      return pair;
    }
    LOG.info("The meeting copies of user {} now go to {} instead of {}; the copies already written are moved there",
             userIdentityId,
             destination,
             pair.getRemoteHref());
    pair.setRemoteHref(destination);
    return caldavSyncStorage.savePair(pair);
  }

  /**
   * Walks the mapping rows and moves the ones not yet under the destination.
   *
   * <p>
   * The pending set is defined by the rows themselves and recomputed on every
   * pass, which is what makes this resumable: a row already under the
   * destination is passed over without a single request, so an account that has
   * nothing left to move costs one page read.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   * @param pair the mirror pair, already re-pointed
   * @param destination where the copies now go
   * @return the tally
   */
  private MirrorRelocation movePending(long userIdentityId,
                                       CaldavUserSetting settings,
                                       CalendarSync pair,
                                       String destination) {
    CalDavEndpoint endpoint = calDavClient.endpoint(settings.getServerId(), settings.getUsername());
    // One listing per collection the pending copies are actually in — normally
    // exactly one, the calendar just left. Read once and reused, for the reason
    // the verification pass reads one: an ETag per object from a single PROPFIND
    // is what keeps a fetch to the copies somebody has genuinely written.
    Map<String, Map<String, String>> listings = new HashMap<>();
    int moved = 0;
    int refused = 0;
    int failed = 0;
    int unmovable = 0;
    int page = 0;
    List<ObjectSync> objects = caldavSyncStorage.getObjects(pair.getId(), page, PAGE_SIZE).getContent();
    while (!objects.isEmpty()) {
      for (ObjectSync object : objects) {
        if (StringUtils.isBlank(object.getRemoteHref()) || StringUtils.isBlank(object.getIcsUid())) {
          // A mapping kept as the record that this event was once pushed, with
          // its remote identity cleared. There is no copy to move.
          continue;
        }
        String to = CaldavPushService.objectHref(destination, object.getIcsUid());
        if (StringUtils.equals(CaldavSyncStorage.canonicalHref(object.getRemoteHref()),
                               CaldavSyncStorage.canonicalHref(to))) {
          // Already where it belongs. The converged account is entirely made of
          // these, and it must cost nothing.
          continue;
        }
        switch (moveOne(userIdentityId, settings, endpoint, object, to, listings)) {
        case MOVED -> moved++;
        case REFUSED -> refused++;
        case UNMOVABLE -> unmovable++;
        default -> failed++;
        }
      }
      objects = caldavSyncStorage.getObjects(pair.getId(), ++page, PAGE_SIZE).getContent();
    }
    if (moved > 0 || refused > 0 || failed > 0 || unmovable > 0) {
      LOG.info("Copies of user {} moved to {}: {} moved, {} old copies the server would not remove, {} could not be written, "
          + "{} stand for no event eXo can render",
               userIdentityId,
               destination,
               moved,
               refused,
               failed,
               unmovable);
    }
    return new MirrorRelocation(destination, moved, refused, failed, unmovable);
  }

  /**
   * Moves one copy: reads any answer left on it, writes it into the destination,
   * re-points its mapping, then removes the old object.
   *
   * <p>
   * <b>The order is the design.</b> The answer first, because after the mapping
   * moves nothing watches the old copy again. The write before the row, because
   * a row pointing at an object that was never written is a copy nobody can
   * find. The row before the delete, because a delete that succeeded while the
   * row still named the old href would leave the user's meeting nowhere — the
   * same reason the per-event move deletes last.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   * @param endpoint where the account lives
   * @param object the mapping row of a copy not yet under the destination
   * @param to where the copy belongs now
   * @param listings the collection listings read so far this pass
   * @return what happened to this copy
   */
  private Outcome moveOne(long userIdentityId,
                          CaldavUserSetting settings,
                          CalDavEndpoint endpoint,
                          ObjectSync object,
                          String to,
                          Map<String, Map<String, String>> listings) {
    String from = object.getRemoteHref();
    String listed = etagOf(from, listingOf(from, endpoint, settings, listings));
    // The version this pass will guard the removal with. It starts as the one
    // eXo recorded and becomes the one just observed on the copy, because by
    // then this pass has read that copy — which is the same "somebody has
    // looked" that lets a repair overwrite unconditionally. A 412 against a
    // version read moments ago is a genuine concurrent write, and that is the
    // one this keeps the copy for.
    String guard = StringUtils.defaultIfBlank(listed, object.getEtag());
    if (clientWrote(object, listed)) {
      CalendarObject remote = fetch(endpoint, settings, from);
      if (remote != null && StringUtils.isNotBlank(remote.calendarData())) {
        if (adopt(userIdentityId, object, remote) == CaldavAnswerAdoptionService.Outcome.FAILED) {
          // The old copy still holds the only record of the user's answer.
          // Nothing may move or remove it; the next pass reads it again.
          LOG.debug("The answer on the copy at {} could not be recorded; it is not moved this pass", from);
          return Outcome.FAILED;
        }
        guard = StringUtils.defaultIfBlank(remote.etag(), guard);
      }
    }
    String ics = render(userIdentityId, object);
    if (StringUtils.isBlank(ics)) {
      // eXo has nothing to put in the new calendar for this row, and will not
      // have on the next pass either. Said once, then left alone; the ordinary
      // verification pass already owns the fate of a mapping that stands for no
      // event, dropping it when the server stops holding its object too.
      sayOnce(userIdentityId,
              from,
              "The copy at {} stands for no event eXo can render; it stays where it is and is not moved to {}",
              from,
              to);
      return Outcome.UNMOVABLE;
    }
    PutResult written;
    try {
      // Unconditional, and it has to be. A create-only write carries
      // If-None-Match and is refused by exactly the object a previous pass wrote
      // before it crashed — which is the state this is resuming from — while a
      // guarded write carries a version the destination has never had. What
      // makes the overwrite legitimate is that the object at the destination is
      // this connector's own writing under this connector's own UID.
      written = calDavClient.overwriteObject(endpoint, to, ics, settings.getUsername(), settings.getPassword());
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The copy at {} of user {} could not be written to {}; it stays where it is", from, userIdentityId, to, e);
      return Outcome.FAILED;
    }
    object.setRemoteHref(to);
    object.setEtag(written.etag());
    object.setLastSync(new Date());
    caldavSyncStorage.saveObject(object);
    return removeOldCopy(userIdentityId, endpoint, settings, from, guard) ? Outcome.MOVED : Outcome.REFUSED;
  }

  /**
   * Removes the copy left in the calendar the meetings moved out of.
   *
   * <p>
   * Conditional on the version this pass observed, so a client that wrote the
   * copy between the listing and this call is refused rather than overruled —
   * the administrator moved a destination, which is not consent to discard an
   * edit somebody made on their phone in the meantime. A refusal leaves the
   * object exactly where it is, and the change goes unstamped so the next pass
   * tries again.
   *
   * <p>
   * A copy the server says is already gone is not a failure: the object is not
   * there, which is what was being asked for.
   *
   * @param userIdentityId identity of the user
   * @param endpoint where the account lives
   * @param settings the connected account
   * @param from the old object's path
   * @param guard the version to condition the removal on, may be null
   * @return true when the old object is no longer there
   */
  private boolean removeOldCopy(long userIdentityId,
                                CalDavEndpoint endpoint,
                                CaldavUserSetting settings,
                                String from,
                                String guard) {
    try {
      int status = calDavClient.deleteObject(endpoint, from, guard, settings.getUsername(), settings.getPassword());
      if (status != PutResult.PRECONDITION_FAILED) {
        return true;
      }
    } catch (RuntimeException | LinkageError e) {
      sayOnce(userIdentityId,
              from,
              "The copy user {} left at {} could not be removed after their meetings moved; it stays, and the change is "
                  + "not recorded as applied",
              userIdentityId,
              from);
      LOG.debug("The removal of {} failed", from, e);
      return false;
    }
    sayOnce(userIdentityId,
            from,
            "The copy user {} left at {} was changed on another device and is kept rather than removed; the change is "
                + "not recorded as applied",
            userIdentityId,
            from);
    return false;
  }

  /**
   * What eXo would write for this row's event right now.
   *
   * @param userIdentityId identity of the user
   * @param object the mapping row
   * @return the object as text, or null when eXo can say nothing for it
   */
  private String render(long userIdentityId, ObjectSync object) {
    if (object.getLocalEventId() == null || object.getLocalEventId() <= 0) {
      return null;
    }
    try {
      return caldavPushService.renderAgendaEvent(userIdentityId, object.getLocalEventId(), object.getIcsUid());
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("Event {} could not be rendered; its copy is not moved", object.getLocalEventId(), e);
      return null;
    }
  }

  /**
   * Records the owner's answer off a copy their own client rewrote.
   *
   * @param userIdentityId identity of the user
   * @param object the mapping row
   * @param remote the copy as the client left it
   * @return what the adoption did
   */
  private CaldavAnswerAdoptionService.Outcome adopt(long userIdentityId, ObjectSync object, CalendarObject remote) {
    if (object.getLocalEventId() == null || object.getLocalEventId() <= 0) {
      return CaldavAnswerAdoptionService.Outcome.NOTHING;
    }
    try {
      return caldavAnswerAdoptionService.adoptAnswer(userIdentityId, object.getLocalEventId(), remote.calendarData());
    } catch (RuntimeException | LinkageError e) {
      // Indistinguishable, from here, from the adoption reporting failure: an
      // answer may be on that copy and may not have been recorded, so the copy
      // is left alone either way.
      LOG.debug("The answer on the copy at {} could not be read", object.getRemoteHref(), e);
      return CaldavAnswerAdoptionService.Outcome.FAILED;
    }
  }

  /**
   * Whether the copy carries somebody else's writing.
   *
   * <p>
   * The same rule the verification pass applies, and the same evidence: a
   * version that moved away from the one eXo recorded is the server's own
   * statement that a client wrote after eXo did. A server that publishes no
   * version, or a row with none recorded, proves nothing and is not treated as
   * a client write.
   *
   * @param object the mapping row
   * @param listed the version the collection listing publishes, may be null
   * @return true when the copy was written by a client
   */
  private boolean clientWrote(ObjectSync object, String listed) {
    return StringUtils.isNotBlank(object.getEtag()) && listed != null
        && !StringUtils.equals(normalise(listed), normalise(object.getEtag()));
  }

  /**
   * Reads one copy back from the server.
   *
   * @param endpoint where the account lives
   * @param settings the connected account
   * @param href the copy's path
   * @return the copy, or null when it could not be read
   */
  private CalendarObject fetch(CalDavEndpoint endpoint, CaldavUserSetting settings, String href) {
    try {
      return calDavClient.fetchObject(endpoint, href, settings.getUsername(), settings.getPassword());
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("The copy at {} could not be read back before being moved", href, e);
      return null;
    }
  }

  /**
   * The listing of the collection one copy lives in, read at most once per pass.
   *
   * <p>
   * A collection that cannot be listed answers an empty listing rather than
   * ending the pass: no version is then observed for its copies, no client write
   * is inferred from silence, and the removal is guarded by the version eXo
   * recorded — which is the conservative reading in every direction.
   *
   * @param href a copy's path
   * @param endpoint where the account lives
   * @param settings the connected account
   * @param listings the listings read so far
   * @return what the collection holds, by href
   */
  private Map<String, String> listingOf(String href,
                                        CalDavEndpoint endpoint,
                                        CaldavUserSetting settings,
                                        Map<String, Map<String, String>> listings) {
    String collection = collectionOf(href);
    if (collection == null) {
      return Map.of();
    }
    return listings.computeIfAbsent(collection, path -> {
      try {
        // Slashed, for the reason the verification pass appends one: BlueMind
        // ignores the slashless form of a collection without answering or
        // redirecting, and the request then spends the whole timeout.
        return calDavClient.listResourceEtags(endpoint,
                                              StringUtils.appendIfMissing(path, "/"),
                                              settings.getUsername(),
                                              settings.getPassword());
      } catch (RuntimeException | LinkageError e) {
        LOG.debug("The collection at {} could not be listed before its copies are moved", path, e);
        return Map.of();
      }
    });
  }

  /**
   * The collection an object's path sits in.
   *
   * @param href the object's path
   * @return the collection's path, or null when the path names no collection
   */
  private String collectionOf(String href) {
    int cut = StringUtils.lastIndexOf(href, '/');
    return cut <= 0 ? null : href.substring(0, cut);
  }

  /**
   * The version the listing carries for a path, matching the way hrefs are
   * compared everywhere else in this add-on.
   *
   * @param href the path recorded for the copy
   * @param etags the listing
   * @return the version, or null when the collection no longer holds it
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
   * A version without its quoting or weak marker, which servers vary.
   *
   * @param etag the value as published
   * @return the value to compare
   */
  private String normalise(String etag) {
    return StringUtils.removeStart(StringUtils.strip(etag, "\""), "W/").replace("\"", "");
  }

  /**
   * Says something about one copy once, however many sweeps meet it again.
   *
   * <p>
   * A relocation that cannot finish is met by every sweep, five minutes apart,
   * for as long as the cause lasts. Said every time, the line that names the
   * copy an administrator has to look at is buried under its own repetitions
   * within an hour — the same reason the verification pass announces giving up
   * exactly once.
   *
   * @param userIdentityId identity of the user
   * @param href the copy's old path, or {@link #DESTINATION} for the one thing
   *          this says that is about no particular copy
   * @param message the line to write, with its placeholders
   * @param arguments what fills them — a trailing throwable is written as the
   *          line's trace, as everywhere else in this add-on
   */
  private void sayOnce(long userIdentityId, String href, String message, Object... arguments) {
    if (reported.add(userIdentityId + "|" + href)) {
      LOG.warn(message, arguments);
    }
  }

  /** What one copy's move came to. */
  private enum Outcome {
    /** Written into the destination and the old object removed. */
    MOVED,
    /** Written into the destination; the server would not remove the old object. */
    REFUSED,
    /** Not written: eXo can render nothing for the row, and will not later. */
    UNMOVABLE,
    /** Not written, or its answer not recorded; the next pass tries again. */
    FAILED
  }
}
