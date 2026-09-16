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
package org.exoplatform.caldav.storage;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.caldav.dao.CaldavShareObservationDAO;
import org.exoplatform.caldav.entity.CaldavShareObservationEntity;

/**
 * Reads and writes which of a user's calendars other eXo users' CalDAV homes
 * list (EXO-90331).
 *
 * <p>
 * No business logic: which collections count as a colleague's eXo calendar,
 * and whose they are, is the classification the sweep already made
 * ({@code CollectionOwnership#COLLEAGUES_EXO_CALENDAR}). What lives here is
 * the mechanical part — that reconciling a listing to the same set writes
 * nothing, that a sighting the listing dropped is deleted, and that the count
 * is one statement whatever the number of calendars.
 *
 * <p>
 * <b>What the rows cannot say</b>, and what nothing reading this class may
 * conclude from their absence: a sharee who is not an eXo user with a
 * connected CalDAV account never lists anything, so their share is in no row
 * (the population EXO-90277 documents as out of scope); and a share granted or
 * revoked since the sharee's last pass is not reflected until that pass runs
 * again, up to one synchronisation period later. The count is a floor on the
 * exposure as far as the homes still being read can see it; it can overstate
 * in one direction only, which {@code CaldavShareObservationEntity} sets out.
 */
@Component
public class CaldavShareObservationStorage {

  /**
   * How many of one sharee's sightings are read per statement.
   *
   * <p>
   * A page size, <b>not</b> a cap: {@link #storedFor} pages until the rows are
   * exhausted. It was a cap once, and that was a defect rather than a
   * degradation — a home past the cap had its later rows missing from the
   * comparison, so the insert loop rebuilt them, the unique index refused the
   * duplicate, and the <em>whole</em> reconciliation rolled back with a
   * {@code DataIntegrityViolationException} that
   * {@code CaldavShareObservationService.observed} swallowed as one WARN. Not
   * "the later rows are not refreshed": no row was refreshed or removed, on
   * that pass or on any pass after it, for as long as the home stayed above
   * the cap. Measured on HSQLDB with 501 anchors, first pass writing 501 rows
   * and every pass after it throwing.
   *
   * <p>
   * The read stays bounded by what it is a read of — one user's colleagues'
   * eXo calendars on one server, which is a subset of one CalDAV home's
   * listing — so paging to exhaustion reads a page or two in practice and
   * never a table.
   */
  static final int                  SIGHTINGS_PER_SHAREE_READ = 500;

  @Autowired
  private CaldavShareObservationDAO shareObservationDAO;

  /**
   * Makes the stored sightings of one sharee's home on one server equal to
   * what its listing just held.
   *
   * <p>
   * The whole removal story is here, and it is why the write is a
   * reconciliation rather than an insert. A listing is complete by
   * construction — the sweep asked the server for the home's collections and
   * accounted for every one of them, the ones it classified as a colleague's
   * eXo calendar and the ones it short-circuited because the user had hidden
   * them ({@code CaldavSyncService#noteHiddenColleaguesExoCalendar}; that
   * second path was missing once, and a user hiding a calendar took the mark
   * off its owner's row) — so a calendar that is in the stored set and
   * not in the listing is a share that has stopped existing, and a row kept
   * for it would draw a mark on a calendar nobody can see any more. A stale
   * mark is worse than no mark: it tells its owner they are exposed when they
   * are not, which is the one thing this feature exists to get right.
   *
   * <p>
   * Idempotent by the same construction: a pass that finds the same set as the
   * last one deletes nothing, inserts nothing, and writes only the sighting
   * instants.
   *
   * <p>
   * <b>Two nodes reconciling the same home at once</b> is settled by the
   * unique index {@code UQ_CALDAV_SHARE_OBSERVATION}, and settled by refusal
   * rather than by merging: the loser's insert raises
   * {@code DataIntegrityViolationException} and its whole transaction rolls
   * back, so it writes nothing at all — it does not add a second row, and it
   * does not update the winner's. {@code CaldavShareObservationService} logs
   * that and the next pass, reading the winner's rows, converges. What the
   * index guarantees is that the count can never be inflated by a race; it
   * does not make a losing pass succeed.
   *
   * <p>
   * Called only for a listing that succeeded. An empty map from a home that
   * genuinely lists no colleague's calendar removes every row, which is right;
   * an empty map standing for "the listing failed" would remove every mark the
   * owner has, which is why the caller never reaches this on that path.
   *
   * @param shareeIdentityId the eXo user whose home was listed
   * @param serverId the declared server registration
   * @param ownersByAnchor the calendars the listing held, calendar anchor to
   *          the identity of the eXo user who owns it; empty to forget every
   *          sighting of this home on this server
   * @return how many rows were inserted or removed, zero when the listing said
   *         exactly what was stored
   */
  @Transactional
  public int reconcile(long shareeIdentityId, long serverId, Map<String, Long> ownersByAnchor) {
    List<CaldavShareObservationEntity> stored = storedFor(shareeIdentityId, serverId);
    Date now = new Date();
    int changed = 0;
    List<CaldavShareObservationEntity> gone = new ArrayList<>();
    List<CaldavShareObservationEntity> kept = new ArrayList<>();
    for (CaldavShareObservationEntity sighting : stored) {
      Long owner = ownersByAnchor.get(sighting.getCalendarSyncUid());
      if (owner == null) {
        gone.add(sighting);
      } else {
        // The owner may have changed only in the sense that the pair behind
        // the collection now names another user - a restore, an account
        // re-pointed. Writing it keeps the row describing the deployment as it
        // is rather than as it was.
        sighting.setOwnerIdentityId(owner);
        sighting.setObserved(now);
        kept.add(sighting);
      }
    }
    if (!gone.isEmpty()) {
      shareObservationDAO.deleteAll(gone);
      changed += gone.size();
    }
    List<CaldavShareObservationEntity> written = new ArrayList<>(kept);
    for (Map.Entry<String, Long> listed : ownersByAnchor.entrySet()) {
      if (kept.stream().noneMatch(sighting -> sighting.getCalendarSyncUid().equals(listed.getKey()))) {
        written.add(new CaldavShareObservationEntity(null, listed.getValue(), shareeIdentityId, serverId, listed.getKey(), now));
        changed++;
      }
    }
    if (!written.isEmpty()) {
      shareObservationDAO.saveAll(written);
    }
    return changed;
  }

  /**
   * Every sighting stored for one sharee's home on one server.
   *
   * <p>
   * Paged to exhaustion rather than read in one statement, keeping the
   * {@code Pageable} the norm asks of a repository query while still comparing
   * the listing against the <em>whole</em> stored set — which is what the
   * reconciliation needs to be correct at all. A partial set makes the insert
   * loop rebuild rows that already exist, and the unique index turns that into
   * a failed transaction rather than a smaller update (see
   * {@link #SIGHTINGS_PER_SHAREE_READ}).
   *
   * <p>
   * The last page is recognised by being short, so an exact multiple of the
   * page size costs one extra empty read and never loops on a full page it has
   * already seen.
   *
   * @param shareeIdentityId the eXo user whose home was listed
   * @param serverId the declared server registration
   * @return the rows, oldest first, possibly empty
   */
  private List<CaldavShareObservationEntity> storedFor(long shareeIdentityId, long serverId) {
    List<CaldavShareObservationEntity> stored = new ArrayList<>();
    for (int page = 0;; page++) {
      List<CaldavShareObservationEntity> read = shareObservationDAO.findBySharee(shareeIdentityId,
                                                                                 serverId,
                                                                                 PageRequest.of(page, SIGHTINGS_PER_SHAREE_READ));
      stored.addAll(read);
      if (read.size() < SIGHTINGS_PER_SHAREE_READ) {
        return stored;
      }
    }
  }

  /**
   * How many colleagues see each of one owner's calendars on one server.
   *
   * @param ownerIdentityId the eXo user whose calendars they are
   * @param serverId the declared server registration
   * @return calendar anchor to the number of colleagues, in the engine's
   *         grouping order; empty when no calendar of theirs is seen by anyone
   */
  public Map<String, Long> countShareesByAnchor(long ownerIdentityId, long serverId) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Object[] row : shareObservationDAO.countShareesByCalendar(ownerIdentityId, serverId)) {
      if (row != null && row.length == 2 && row[0] != null && row[1] != null) {
        counts.put((String) row[0], ((Number) row[1]).longValue());
      }
    }
    return counts;
  }

  /**
   * Removes every sighting one sharee's home ever produced.
   *
   * @param shareeIdentityId the eXo user whose home is no longer read
   * @return how many rows were removed
   */
  @Transactional
  public int forgetSharee(long shareeIdentityId) {
    return shareObservationDAO.deleteBySharee(shareeIdentityId);
  }
}
