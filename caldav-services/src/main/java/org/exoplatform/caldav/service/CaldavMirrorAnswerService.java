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
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.SyncCollectionResult;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * <h2>Reads the owner's answers off the copies in a collection nothing else
 * reads (EXO-89814).</h2>
 *
 * <p>
 * <b>The defect this exists for.</b> Where the copies go to eXo's own
 * <code>exo-meetings</code> collection — {@code DEDICATED_CALENDAR}, which is
 * the <b>default</b> — nothing ever reads that collection back in.
 * {@code CaldavSyncService} excludes it from materialisation
 * ({@code isDedicatedMirror}) and excludes the MIRROR pair from the inbound
 * loop, both deliberately and both for the same real reason: the collection is
 * eXo's own projection, and importing it would show the user a second, personal
 * copy of every space meeting they already have. The consequence nobody chose
 * is that EXO-89807 — which reads an answer off one of eXo's own copies at the
 * moment the collection's sync report names it as changed — has no pass to sit
 * on there, and EXO-89810's daily full read cannot reach those copies either.
 * The only reader left is the mirror verification's ETag gate, and on a server
 * that records an answer <i>without</i> moving the ETag (BlueMind, measured)
 * that gate never opens. On such a server, with the default destination, an
 * answer given in the copy reaches eXo by no route at all.
 *
 * <p>
 * <b>What this does, and the line it does not cross.</b> It reads
 * <i>answers</i>, never events. The collection is asked what changed, the named
 * objects are fetched, and the one field the mirror allows back — the owner's
 * own PARTSTAT — is handed to {@link CaldavAnswerAdoptionService}, the same
 * narrow mapping EXO-89681 defined and the other two readers use. Nothing here
 * creates a calendar, imports an object, or writes to the server. The
 * duplicate-calendar failure the exclusion prevents is untouched, because the
 * exclusion is untouched: this pass never asks for the collection to be
 * materialised and never calls the import.
 *
 * <p>
 * <b>Why a sync report and not a wider look.</b> The report is the only
 * evidence available on a server whose ETag does not move, and it is the one
 * EXO-89807 already rests on. It is also what bounds the cost: a collection
 * where nothing has happened answers with an empty list, so a converged account
 * pays one REPORT per sweep and fetches nothing. What it does cost is one fetch
 * per copy eXo itself writes, once, on the sweep after the write — eXo's own
 * push is a change the report reports, and no signal tells it from a client's.
 * That is honest and it is bounded by how much eXo writes, never by how large
 * the collection is; {@link #maxObjectsPerPass} is the ceiling for the one case
 * that is not — a backfill burst — and it is spent on objects that carry
 * NEEDS-ACTION and adopt to nothing.
 *
 * <p>
 * <b>The token is established without reading anything.</b> A blank or refused
 * token means the next report is an initial sync, which names every object in
 * the collection. Those bodies are deliberately <i>not</i> fetched: the burst of
 * reads that shape produces is exactly what EXO-89810 refused to build, at the
 * one moment a deployment can least absorb it. So the first pass takes the
 * token and reads nothing, and every pass after it reads only what changed
 * since. An answer given <b>before</b> this ships is therefore not healed by it
 * — the same forward-looking limit EXO-89807 has, and for the same reason.
 *
 * <p>
 * <b>Only where nothing else reads the collection.</b> Where the copies land in
 * the account's own calendar ({@code MAIN_CALENDAR}), that calendar is
 * materialised and read through its own binding, and EXO-89807 already adopts
 * every answer on that path. Running here as well would double a REPORT and a
 * fetch per sweep to reach a conclusion already reached. The condition is
 * therefore stated as what it actually is — <i>no active binding reads this
 * collection</i> — rather than as the destination setting, so a mirror pointed
 * at a collection whose binding is paused or tombstoned is covered too.
 *
 * <p>
 * <b>NEEDS-ACTION is refused, and that is load-bearing here more than
 * anywhere.</b> Every copy eXo pushes carries NEEDS-ACTION until somebody
 * answers, and this pass meets those copies on the sweep after each push. A
 * pass that adopted NEEDS-ACTION would walk an account erasing the answers its
 * owner had given in eXo. The refusal lives in the adoption service and is
 * pinned through this pass against the real one, never a mock.
 */
@Service
public class CaldavMirrorAnswerService {

  private static final Log            LOG       = ExoLogger.getExoLogger(CaldavMirrorAnswerService.class);

  /** How many mapping rows one page carries, as the verification pass reads them. */
  private static final int            PAGE_SIZE = 200;

  @Autowired
  private CalDavClient                calDavClient;

  @Autowired
  private CaldavSyncStorage           caldavSyncStorage;

  @Autowired
  private CaldavAnswerAdoptionService caldavAnswerAdoptionService;

  /**
   * How many changed objects one pass will fetch bodies for.
   *
   * <p>
   * A ceiling rather than a budget: in ordinary running the report names the
   * handful of copies eXo has just written or a client has just answered, and
   * this is never reached. It exists for the one shape that is not ordinary —
   * a connect-time backfill writing a whole account's upcoming meetings at once
   * — where the objects above the ceiling are eXo's own fresh copies carrying
   * NEEDS-ACTION, which adopt to nothing. The token still moves past them,
   * because holding it back would make the next pass re-fetch the same head for
   * ever and never drain.
   */
  @Value("${exo.agenda.caldav.mirror.answers.maxPerPass:100}")
  private int                         maxObjectsPerPass;

  /**
   * Reads the owner's answers off the copies of a mirror collection that no
   * binding reads.
   *
   * <p>
   * Never throws at its caller. This runs inside the mirror pass, whose other
   * halves — the relocation and the verification — must not lose a round
   * because a report could not be made.
   *
   * @param userIdentityId identity of the account's owner
   * @param settings the connected account
   * @param mirror the binding recording where the copies are written
   * @return how many answers were recorded in agenda
   */
  public int readAnswers(long userIdentityId, CaldavUserSetting settings, CalendarSync mirror) {
    if (settings == null || mirror == null || StringUtils.isBlank(mirror.getRemoteHref())
        || StringUtils.isBlank(settings.getUsername())) {
      return 0;
    }
    try {
      if (isReadByABinding(userIdentityId, mirror)) {
        // Another pass already meets these copies and already adopts off them
        // (EXO-89807). Asking again would spend a REPORT, and a fetch per
        // change, to reach a conclusion that has been reached.
        LOG.debug("The mirror collection {} is read by a binding of its own; its answers are read there",
                  mirror.getRemoteHref());
        return 0;
      }
      return readWhatChanged(userIdentityId, settings, mirror);
    } catch (RuntimeException | LinkageError e) {
      // One field on some objects, against the copies still being verified and
      // repaired around it. The pass goes on.
      LOG.warn("The answers on the copies of user {} could not be read this round", userIdentityId, e);
      return 0;
    }
  }

  /**
   * Whether some active binding already reads the collection the copies are
   * written into.
   *
   * <p>
   * The MIRROR pair itself is never such a binding: it names no eXo calendar
   * and the inbound loop excludes it by origin. Any other pair on the same
   * collection <i>is</i> read, and the copies inside it are met — and adopted
   * off — one object at a time on that path.
   *
   * <p>
   * Status is part of the question rather than beside it: a paused or
   * tombstoned binding is skipped by the inbound loop, so a collection whose
   * only binding is one of those is read by nothing, which is this pass's case
   * exactly.
   *
   * @param userIdentityId identity of the account's owner
   * @param mirror the binding recording where the copies are written
   * @return true when another active binding reads that collection
   */
  private boolean isReadByABinding(long userIdentityId, CalendarSync mirror) {
    String collection = CaldavSyncStorage.canonicalHref(mirror.getRemoteHref());
    return caldavSyncStorage.getPairs(userIdentityId, mirror.getServerId())
                            .stream()
                            .filter(pair -> pair.getOrigin() != SyncOrigin.MIRROR)
                            .filter(pair -> pair.getStatus() == CalendarSyncStatus.ACTIVE)
                            .anyMatch(pair -> StringUtils.equals(collection,
                                                                 CaldavSyncStorage.canonicalHref(pair.getRemoteHref())));
  }

  /**
   * Asks the collection what changed since the token it was last given, and
   * reads an answer off each object it names.
   *
   * @param userIdentityId identity of the account's owner
   * @param settings the connected account
   * @param mirror the binding recording where the copies are written
   * @return how many answers were recorded in agenda
   */
  private int readWhatChanged(long userIdentityId, CaldavUserSetting settings, CalendarSync mirror) {
    CalDavEndpoint endpoint = calDavClient.endpoint(mirror.getServerId(), settings.getUsername());
    String collection = StringUtils.appendIfMissing(mirror.getRemoteHref(), "/");
    boolean establishing = StringUtils.isBlank(mirror.getSyncToken());
    SyncCollectionResult report;
    try {
      report = calDavClient.syncCollection(endpoint,
                                           collection,
                                           mirror.getSyncToken(),
                                           settings.getUsername(),
                                           settings.getPassword());
    } catch (RuntimeException e) {
      // A report that could not be made says nothing: not that the collection
      // is empty, and not that it is unchanged. The token stays where it is and
      // the next sweep asks again.
      LOG.debug("The mirror collection {} could not report its changes; no answer is read this round", collection, e);
      return 0;
    }
    if (report == null) {
      return 0;
    }
    if (!report.tokenValid()) {
      // The server refused the token. Cleared rather than replaced: the next
      // pass then runs the initial sync below, which establishes a fresh one
      // without reading a whole collection's worth of bodies.
      LOG.debug("The sync token of the mirror collection {} was refused; a fresh one is taken next round", collection);
      forgetToken(mirror);
      return 0;
    }
    if (establishing) {
      // The initial sync names every object in the collection. Their bodies are
      // deliberately not fetched — see the class comment: this is the burst
      // EXO-89810 refused to build. From the next pass on, only what changed.
      LOG.debug("The mirror collection {} now has a sync token; its changes are read from the next round on",
                collection);
      rememberToken(mirror, report.syncToken());
      return 0;
    }
    List<String> changed = hrefsOf(report);
    if (changed.isEmpty()) {
      rememberToken(mirror, report.syncToken());
      return 0;
    }
    int adopted = adoptOn(userIdentityId, settings, mirror, endpoint, collection, changed);
    rememberToken(mirror, report.syncToken());
    return adopted;
  }

  /**
   * The paths the report named as changed, capped at what one pass will fetch.
   *
   * @param report the collection's answer
   * @return the paths to fetch, never null
   */
  private List<String> hrefsOf(SyncCollectionResult report) {
    List<String> hrefs = new ArrayList<>(report.changed()
                                               .stream()
                                               .map(CalendarObject::href)
                                               .filter(StringUtils::isNotBlank)
                                               .toList());
    if (hrefs.size() > maxObjectsPerPass) {
      LOG.warn("{} copies changed at once and only {} are read for an answer this round; the rest are eXo's own writing",
               hrefs.size(),
               maxObjectsPerPass);
      return hrefs.subList(0, maxObjectsPerPass);
    }
    return hrefs;
  }

  /**
   * Fetches the named copies and records the owner's answer off each.
   *
   * @param userIdentityId identity of the account's owner
   * @param settings the connected account
   * @param mirror the binding recording where the copies are written
   * @param endpoint where the account's server lives
   * @param collection the collection path a request can be sent to
   * @param hrefs the paths the report named
   * @return how many answers were recorded in agenda
   */
  private int adoptOn(long userIdentityId,
                      CaldavUserSetting settings,
                      CalendarSync mirror,
                      CalDavEndpoint endpoint,
                      String collection,
                      List<String> hrefs) {
    List<CalendarObject> objects;
    try {
      objects = calDavClient.multiget(endpoint, collection, hrefs, settings.getUsername(), settings.getPassword());
    } catch (RuntimeException e) {
      LOG.debug("The {} changed copies of collection {} could not be fetched; no answer is read this round",
                hrefs.size(),
                collection,
                e);
      return 0;
    }
    if (objects == null || objects.isEmpty()) {
      return 0;
    }
    Map<String, Long> events = eventsByHref(mirror);
    int adopted = 0;
    for (CalendarObject object : objects) {
      if (adoptOne(userIdentityId, events, object)) {
        adopted++;
      }
    }
    if (adopted > 0) {
      LOG.info("{} answer(s) of user {} were read off the copies in {}", adopted, userIdentityId, collection);
    }
    return adopted;
  }

  /**
   * Records the owner's answer off one copy.
   *
   * <p>
   * The event the copy stands for comes from the mirror's own mapping rows and
   * from nowhere else. A copy the mapping does not name is not one of eXo's,
   * whatever it is doing in that collection, and there is no meeting to record
   * an answer against.
   *
   * @param userIdentityId identity of the account's owner
   * @param events the agenda event each mapped copy stands for, by path
   * @param object the copy as the server sent it, body included
   * @return true when agenda's record changed
   */
  private boolean adoptOne(long userIdentityId, Map<String, Long> events, CalendarObject object) {
    if (object == null || StringUtils.isBlank(object.calendarData())) {
      return false;
    }
    Long localEventId = events.get(CaldavSyncStorage.canonicalHref(object.href()));
    if (localEventId == null || localEventId <= 0) {
      LOG.debug("The object at {} is not a copy of ours; no answer is read off it", object.href());
      return false;
    }
    return caldavAnswerAdoptionService.adoptAnswer(userIdentityId,
                                                   localEventId,
                                                   object.calendarData()) == CaldavAnswerAdoptionService.Outcome.ADOPTED;
  }

  /**
   * The agenda event each of the mirror's copies stands for, keyed by the
   * canonical form of its path.
   *
   * <p>
   * Read in one page rather than one lookup per changed object: the report
   * usually names a handful and the mapping is small, so a query per href would
   * be the expensive half of a pass whose whole point is to be cheap.
   *
   * @param mirror the binding recording where the copies are written
   * @return the event identifiers by path, never null
   */
  private Map<String, Long> eventsByHref(CalendarSync mirror) {
    Map<String, Long> events = new HashMap<>();
    int page = 0;
    List<ObjectSync> objects = caldavSyncStorage.getObjects(mirror.getId(), page, PAGE_SIZE).getContent();
    while (!objects.isEmpty()) {
      for (ObjectSync object : objects) {
        if (StringUtils.isNotBlank(object.getRemoteHref()) && object.getLocalEventId() != null
            && object.getLocalEventId() > 0) {
          events.put(CaldavSyncStorage.canonicalHref(object.getRemoteHref()), object.getLocalEventId());
        }
      }
      objects = caldavSyncStorage.getObjects(mirror.getId(), ++page, PAGE_SIZE).getContent();
    }
    return events;
  }

  /**
   * Records the token the collection just gave, on the row as it now stands.
   *
   * <p>
   * The row is read again rather than the snapshot the pass is holding: the
   * sweep writes the ctag, the timestamps and the settings stamp on the same
   * row, and a snapshot taken at the top of a pass that talks to a server would
   * carry every one of them backwards. With {@code @DynamicUpdate} on the
   * entity only the columns that genuinely differ are in the UPDATE, which
   * after this re-read is the token and nothing else.
   *
   * @param mirror the binding to record it on
   * @param freshToken the token, ignored when blank or unchanged
   */
  private void rememberToken(CalendarSync mirror, String freshToken) {
    if (StringUtils.isBlank(freshToken) || StringUtils.equals(freshToken, mirror.getSyncToken())) {
      return;
    }
    CalendarSync row = caldavSyncStorage.getPair(mirror.getId());
    if (row == null) {
      return;
    }
    row.setSyncToken(freshToken);
    caldavSyncStorage.savePair(row);
    // Kept in step so the rest of this pass, and the caller's own snapshot, do
    // not go on believing the refused or absent token is still current.
    mirror.setSyncToken(freshToken);
  }

  /**
   * Forgets a token the server refused, so the next pass establishes one.
   *
   * @param mirror the binding whose token was refused
   */
  private void forgetToken(CalendarSync mirror) {
    if (StringUtils.isBlank(mirror.getSyncToken())) {
      return;
    }
    CalendarSync row = caldavSyncStorage.getPair(mirror.getId());
    if (row == null) {
      return;
    }
    row.setSyncToken(null);
    caldavSyncStorage.savePair(row);
    mirror.setSyncToken(null);
  }
}
