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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
 * Checks that the copies eXo pushed are still there, and still what eXo wrote.
 *
 * <p>
 * The mirror calendar is a <b>projection</b>: eXo is authoritative and nothing
 * ever syncs back from it. Until this ran, that was a claim rather than a
 * guarantee — a copy deleted from someone's phone stayed deleted, and a copy a
 * server rewrote stayed rewritten, both silently. Nothing in eXo would ever
 * notice, because nothing ever looked.
 *
 * <p>
 * One ETag listing of the collection answers both questions at once, and it is
 * the same confirm-by-reading-back discipline the mirror's own creation
 * already needs: an href that is gone means the copy was deleted, and an ETag
 * that moved means somebody rewrote it. Only the second case costs a fetch,
 * and only when the ETag actually moved.
 *
 * <p>
 * <b>What "what eXo wrote" means.</b> The baseline it compares against is the
 * copy as the <i>server stored</i> it, read back once at push time — not the
 * bytes eXo sent. A server is free to re-serialise what it is given and
 * BlueMind does, so a baseline taken from the sent bytes made every copy on
 * such a server look tampered with, on every pass, until it was abandoned. See
 * {@code CaldavPushService.storedBaseline}: this pass only works because the
 * push records the right thing.
 *
 * <p>
 * <b>It gives up.</b> An object that keeps disappearing — a server refusing
 * writes it pretends to accept, a rule on the account deleting what eXo sends
 * — is left alone after a few attempts and said out loud, rather than
 * re-pushed on every sync for ever. The count is held in memory on purpose: a
 * restart forgives, which is the right bias when the cause is usually
 * temporary and the alternative is a database column recording that a server
 * misbehaved once.
 */
@Service
public class CaldavMirrorVerificationService {

  private static final Log             LOG          = ExoLogger.getExoLogger(CaldavMirrorVerificationService.class);

  /** How many objects one pass reads at a time from the mapping table. */
  private static final int             PAGE_SIZE    = 200;

  @Autowired
  private CalDavClient                 calDavClient;

  @Autowired
  private CaldavConnectorStorage       caldavConnectorStorage;

  @Autowired
  private CaldavSyncStorage            caldavSyncStorage;

  @Autowired
  private CaldavPushService            caldavPushService;

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
      return MirrorVerification.nothing();
    }
    return comparePages(userIdentityId, mirror, settings, etags);
  }

  /**
   * Walks the mapping rows of the mirror, a page at a time.
   *
   * @param userIdentityId identity of the user
   * @param mirror the binding standing for the mirror collection
   * @param settings the connected account
   * @param etags what the server currently holds, by href
   * @return the tally
   */
  private MirrorVerification comparePages(long userIdentityId,
                                          CalendarSync mirror,
                                          CaldavUserSetting settings,
                                          Map<String, String> etags) {
    int checked = 0;
    int missing = 0;
    int altered = 0;
    int repaired = 0;
    int abandoned = 0;
    int page = 0;
    List<ObjectSync> objects = caldavSyncStorage.getObjects(mirror.getId(), page, PAGE_SIZE).getContent();
    while (!objects.isEmpty()) {
      for (ObjectSync object : objects) {
        if (StringUtils.isBlank(object.getRemoteHref())) {
          continue;
        }
        checked++;
        Verdict verdict = judge(object, settings, etags);
        if (verdict == Verdict.UNTOUCHED) {
          continue;
        }
        if (verdict == Verdict.MISSING) {
          missing++;
        } else {
          altered++;
        }
        if (giveUpOn(userIdentityId, object)) {
          abandoned++;
        } else if (repair(userIdentityId, object, verdict)) {
          repaired++;
        }
      }
      objects = caldavSyncStorage.getObjects(mirror.getId(), ++page, PAGE_SIZE).getContent();
    }
    if (missing > 0 || altered > 0) {
      LOG.info("Mirror of user {}: {} checked, {} missing, {} altered, {} re-pushed, {} abandoned",
               userIdentityId,
               checked,
               missing,
               altered,
               repaired,
               abandoned);
    }
    return new MirrorVerification(checked, missing, altered, repaired, abandoned);
  }

  /**
   * What the server's listing says about one copy.
   *
   * @param object the mapping row
   * @param settings the connected account
   * @param etags what the server currently holds, by href
   * @return the verdict
   */
  private Verdict judge(ObjectSync object, CaldavUserSetting settings, Map<String, String> etags) {
    String etag = etagOf(object.getRemoteHref(), etags);
    if (etag == null) {
      return Verdict.MISSING;
    }
    if (StringUtils.isBlank(object.getEtag()) || StringUtils.equals(normalise(etag), normalise(object.getEtag()))) {
      // The same ETag, or a server that publishes none we can compare. Either
      // way there is no reason to spend a fetch: an unchanged ETag is the
      // server's own promise that the bytes are the ones it was given.
      return Verdict.UNTOUCHED;
    }
    if (StringUtils.isBlank(object.getPushedHash())) {
      // Written before the hash was recorded. The ETag moved, and nothing
      // here can say whether that matters — a re-push would overwrite a copy
      // that may be perfectly fine.
      return Verdict.UNTOUCHED;
    }
    return alteredContent(object, settings, etag) ? Verdict.ALTERED : Verdict.UNTOUCHED;
  }

  /**
   * Whether what the server holds differs from what eXo wrote.
   *
   * <p>
   * An ETag moves for reasons that are not a rewrite — a server touching its
   * own metadata, a change of storage format — so the bytes are compared
   * rather than trusted to it.
   *
   * @param object the mapping row
   * @param settings the connected account
   * @param currentEtag the version the listing published for it
   * @return true when the content is not the one recorded
   */
  private boolean alteredContent(ObjectSync object, CaldavUserSetting settings, String currentEtag) {
    try {
      CalDavEndpoint endpoint = calDavClient.endpoint(settings.getServerId(), settings.getUsername());
      CalendarObject remote = calDavClient.fetchObject(endpoint,
                                                       object.getRemoteHref(),
                                                       settings.getUsername(),
                                                       settings.getPassword());
      if (remote == null || StringUtils.isBlank(remote.calendarData())) {
        return true;
      }
      if (StringUtils.equals(hashOf(remote.calendarData()), object.getPushedHash())) {
        adoptVersion(object, StringUtils.defaultIfBlank(remote.etag(), currentEtag));
        return false;
      }
      return true;
    } catch (RuntimeException e) {
      // Unreadable is not the same as rewritten, and a re-push on this path
      // would overwrite whatever is there on the strength of a network error.
      LOG.debug("The copy at {} could not be read back; it is left alone", object.getRemoteHref(), e);
      return false;
    }
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
   * Only ever on bytes that matched. This is not "trust the server's version",
   * it is "the bytes are ours, so this version names our copy".
   *
   * @param object the mapping row whose content was just confirmed
   * @param currentEtag the version the server publishes now, may be blank
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
    String key = userIdentityId + "|" + object.getRemoteHref();
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
   * Forgets what is known about an account's repairs.
   *
   * @param userIdentityId identity of the user
   */
  public void forgetRepairs(long userIdentityId) {
    repairs.keySet().removeIf(key -> key.startsWith(userIdentityId + "|"));
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

  /**
   * A stable digest of an object, computed the same way the push records one.
   *
   * @param ics the object as the server holds it
   * @return the digest, hexadecimal
   */
  private String hashOf(String ics) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(ics.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", e);
    }
  }

  /** What one copy turned out to be. */
  private enum Verdict {
    UNTOUCHED, MISSING, ALTERED
  }
}
