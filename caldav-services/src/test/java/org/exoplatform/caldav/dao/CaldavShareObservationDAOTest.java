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
package org.exoplatform.caldav.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.caldav.entity.CaldavCalendarSyncEntity;
import org.exoplatform.caldav.entity.CaldavShareObservationEntity;
import org.exoplatform.caldav.storage.CaldavShareObservationStorage;

/**
 * The share observations of EXO-90331, against the engine that will answer
 * them: the module's own Liquibase changelog builds the schema, and the
 * grouped count, the reconciliation and the unique key run over it.
 *
 * <p>
 * Here rather than only in a Mockito storage test, for the reason
 * {@code CaldavSyncDAOQueryTest} records: a mock DAO answers happily to a
 * query string the engine would refuse, and neither a {@code GROUP BY} nor a
 * unique index has any meaning against one. Three things in particular can
 * only be seen here — that changesets {@code 1.0.0-53} to {@code 1.0.0-56}
 * apply, that {@code countShareesByCalendar} parses, binds by name and groups,
 * and that the unique key is what makes a repeated pass idempotent under two
 * writers rather than merely by convention.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/caldav-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
public class CaldavShareObservationDAOTest {

  /** Eric, who owns the calendars. */
  private static final long              ERIC        = 41L;

  /** Root, who sees them in his own home. */
  private static final long              ROOT        = 42L;

  /** A third user, on the same server. */
  private static final long              JOHN        = 43L;

  /** The declared server registration the rig runs. */
  private static final long              SERVER      = 5L;

  /** Eric's first calendar, by the anchor its collection's slug carries. */
  private static final String            CAL2        = "959b5529-ea4c-4ae4-a793-a2c201c3af9f";

  /** Eric's second calendar. */
  private static final String            CAL3        = "c434ba2a-3f58-4d9c-9a0a-2b2f8e1f7a10";

  /**
   * The storage's page size, read from the storage rather than mirrored.
   *
   * <p>
   * A copied number would decay in the one way that costs the most: raise the
   * storage's page size and the boundary test below goes on writing its old
   * count, stops straddling the boundary it was written for, and keeps
   * passing against the very defect it pins. Reflection because the constant
   * is package-private to the storage package and nothing outside it should
   * need the value at runtime.
   */
  private static final int               SIGHTINGS_PER_SHAREE_READ =
                                                                  (int) ReflectionTestUtils.getField(CaldavShareObservationStorage.class,
                                                                                                     "SIGHTINGS_PER_SHAREE_READ");

  @Autowired
  private CaldavShareObservationDAO      shareObservationDAO;

  @Autowired
  private EntityManager                  entityManager;

  /**
   * The storage under test, wired onto the real repository proxy.
   *
   * @return a storage writing through the engine
   */
  private CaldavShareObservationStorage storage() {
    CaldavShareObservationStorage storage = new CaldavShareObservationStorage();
    ReflectionTestUtils.setField(storage, "shareObservationDAO", shareObservationDAO);
    return storage;
  }

  /**
   * The grouped count parses, binds by name, and answers one row per calendar
   * however many colleagues see it.
   */
  @Test
  public void theCountGroupsBySharedCalendarAndRunsOnTheEngine() {
    persist(ERIC, ROOT, SERVER, CAL2);
    persist(ERIC, JOHN, SERVER, CAL2);
    persist(ERIC, ROOT, SERVER, CAL3);
    // Another server's registration is another world, and another owner's
    // calendar is not Eric's.
    persist(ERIC, ROOT, 99L, CAL3);
    persist(JOHN, ROOT, SERVER, "someone-elses-anchor");
    entityManager.flush();

    assertEquals(Map.of(CAL2, 2L, CAL3, 1L), storage().countShareesByAnchor(ERIC, SERVER));
    assertEquals(Map.of(CAL3, 1L), storage().countShareesByAnchor(ERIC, 99L));
    assertTrue(storage().countShareesByAnchor(ROOT, SERVER).isEmpty(), "root owns none of these calendars");
  }

  /**
   * <b>Idempotency.</b> The same listing, reconciled again, changes nothing:
   * the sweep runs every five minutes for as long as a share lasts, and a pass
   * that found what the last one found must cost no insert and no delete.
   */
  @Test
  public void reconcilingTheSameListingTwiceChangesNothing() {
    CaldavShareObservationStorage storage = storage();
    Map<String, Long> listing = Map.of(CAL2, ERIC, CAL3, ERIC);

    assertEquals(2, storage.reconcile(ROOT, SERVER, listing));
    entityManager.flush();
    entityManager.clear();
    List<Long> firstIds = ids();

    assertEquals(0, storage.reconcile(ROOT, SERVER, listing), "nothing was inserted and nothing was removed");
    entityManager.flush();
    entityManager.clear();

    assertEquals(firstIds, ids(), "the very same rows, not rewritten ones");
    assertEquals(Map.of(CAL2, 1L, CAL3, 1L), storage.countShareesByAnchor(ERIC, SERVER),
                 "one sharee per calendar, counted once however many passes have run");
  }

  /**
   * <b>Removal.</b> A calendar the listing no longer holds loses its row, and
   * with it the mark on its owner's agenda row.
   *
   * <p>
   * The load-bearing half of the design. A stale row draws a "shared" mark on a
   * calendar that is no longer shared, which tells its owner they are exposed
   * when they are not — worse than drawing nothing. A listing is complete by
   * construction, so what it omits has stopped existing.
   */
  @Test
  public void aShareThatStopsBeingListedLosesItsRow() {
    CaldavShareObservationStorage storage = storage();
    storage.reconcile(ROOT, SERVER, Map.of(CAL2, ERIC, CAL3, ERIC));
    entityManager.flush();
    entityManager.clear();

    assertEquals(1, storage.reconcile(ROOT, SERVER, Map.of(CAL2, ERIC)), "one sighting is gone");
    entityManager.flush();
    entityManager.clear();

    assertEquals(Map.of(CAL2, 1L), storage.countShareesByAnchor(ERIC, SERVER));
    assertEquals(1, shareObservationDAO.findBySharee(ROOT, SERVER, PageRequest.of(0, 10)).size());
  }

  /**
   * A home that lists no colleague's calendar at all forgets every sighting it
   * used to produce — the same removal, at its limit.
   */
  @Test
  public void aHomeThatListsNoColleaguesCalendarForgetsEveryOne() {
    CaldavShareObservationStorage storage = storage();
    storage.reconcile(ROOT, SERVER, Map.of(CAL2, ERIC, CAL3, ERIC));
    entityManager.flush();
    entityManager.clear();

    assertEquals(2, storage.reconcile(ROOT, SERVER, Map.of()));
    entityManager.flush();
    entityManager.clear();

    assertTrue(storage.countShareesByAnchor(ERIC, SERVER).isEmpty());
  }

  /**
   * One home's reconciliation never touches another's, nor another server's —
   * the scope that keeps one colleague's pass from erasing everyone else's
   * sightings.
   */
  @Test
  public void reconcilingOneHomeLeavesTheOtherHomesAlone() {
    CaldavShareObservationStorage storage = storage();
    storage.reconcile(ROOT, SERVER, Map.of(CAL2, ERIC));
    storage.reconcile(JOHN, SERVER, Map.of(CAL2, ERIC));
    storage.reconcile(ROOT, 99L, Map.of(CAL3, ERIC));
    entityManager.flush();
    entityManager.clear();

    assertEquals(0, storage.reconcile(ROOT, SERVER, Map.of(CAL2, ERIC)));
    entityManager.flush();
    entityManager.clear();

    assertEquals(Map.of(CAL2, 2L), storage.countShareesByAnchor(ERIC, SERVER), "john's sighting survived root's pass");
    assertEquals(Map.of(CAL3, 1L), storage.countShareesByAnchor(ERIC, 99L), "and so did root's on the other server");
  }

  /**
   * Disconnecting a user's account forgets every sighting their home produced,
   * on every server, and nobody else's.
   */
  @Test
  public void forgettingAShareeRemovesTheirSightingsAndOnlyTheirs() {
    CaldavShareObservationStorage storage = storage();
    storage.reconcile(ROOT, SERVER, Map.of(CAL2, ERIC));
    storage.reconcile(ROOT, 99L, Map.of(CAL3, ERIC));
    storage.reconcile(JOHN, SERVER, Map.of(CAL2, ERIC));
    entityManager.flush();
    entityManager.clear();

    assertEquals(2, storage.forgetSharee(ROOT));
    entityManager.flush();
    entityManager.clear();

    assertEquals(Map.of(CAL2, 1L), storage.countShareesByAnchor(ERIC, SERVER));
    assertTrue(storage.countShareesByAnchor(ERIC, 99L).isEmpty());
    assertEquals(0, storage.forgetSharee(ROOT), "forgetting a user who saw nothing costs one statement and removes nothing");
  }

  /**
   * <b>Past one page of stored rows the reconciliation still works.</b>
   *
   * <p>
   * The page size was a cap once, and the failure it produced was not the
   * graceful one its comment promised. A home whose stored rows outran the cap
   * had the later ones missing from the comparison, so the insert loop rebuilt
   * them, the unique index refused the duplicate, and the whole transaction
   * rolled back — no row refreshed, no row removed, on that pass or on any
   * pass after it, while the service logged one WARN and carried on. Executed
   * here rather than reasoned about: one page plus one anchor, reconciled
   * twice, then a removal past the page boundary.
   */
  @Test
  public void aHomeWithMoreSightingsThanOnePageStillReconciles() {
    CaldavShareObservationStorage storage = storage();
    int beyondOnePage = SIGHTINGS_PER_SHAREE_READ + 1;
    Map<String, Long> listing = new LinkedHashMap<>();
    for (int i = 0; i < beyondOnePage; i++) {
      listing.put(String.format("anchor-%04d", i), ERIC);
    }

    assertEquals(beyondOnePage, storage.reconcile(ROOT, SERVER, listing));
    entityManager.flush();
    entityManager.clear();

    // The pass that used to throw: the same listing again, writing nothing.
    assertEquals(0, storage.reconcile(ROOT, SERVER, listing), "the same listing again is still a no-op past one page");
    entityManager.flush();
    entityManager.clear();
    assertEquals(beyondOnePage, shareObservationDAO.count());
    assertEquals(beyondOnePage, storage.countShareesByAnchor(ERIC, SERVER).size());

    // And removal still reaches a row that sits past the page boundary.
    Map<String, Long> shorter = new LinkedHashMap<>(listing);
    shorter.remove(String.format("anchor-%04d", beyondOnePage - 1));
    assertEquals(1, storage.reconcile(ROOT, SERVER, shorter));
    entityManager.flush();
    entityManager.clear();

    assertEquals(beyondOnePage - 1L, shareObservationDAO.count());
  }

  /**
   * <b>The unique key exists on the engine.</b> Two nodes reconciling the same
   * home at once are what it is for: without it the loser would add a second
   * row for one sighting and the owner's count would say two colleagues where
   * there is one.
   */
  @Test
  public void oneSightingPerShareeServerAndCalendarIsEnforcedBytheIndex() {
    persist(ERIC, ROOT, SERVER, CAL2);
    entityManager.flush();

    PersistenceException refused = assertThrows(PersistenceException.class, () -> {
      persist(ERIC, ROOT, SERVER, CAL2);
      entityManager.flush();
    });
    // The index the changeset declares, named by the engine that refused —
    // so this pins changeset 1.0.0-55 and not merely "some constraint".
    assertTrue(rootCauseMessage(refused).contains("UQ_CALDAV_SHARE_OBSERVATION"), rootCauseMessage(refused));
    entityManager.clear();

    // The three columns of the key are each discriminating: change any one and
    // the row is a different sighting.
    persist(ERIC, JOHN, SERVER, CAL2);
    persist(ERIC, ROOT, 99L, CAL2);
    persist(ERIC, ROOT, SERVER, CAL3);
    entityManager.flush();
    assertEquals(4, shareObservationDAO.count());
  }

  /**
   * The sighting instant is written on every pass, so the table can still say
   * when a share was last confirmed even though no count reads it.
   */
  @Test
  public void theSightingInstantIsRefreshedByEveryPass() throws InterruptedException {
    CaldavShareObservationStorage storage = storage();
    storage.reconcile(ROOT, SERVER, Map.of(CAL2, ERIC));
    entityManager.flush();
    entityManager.clear();
    Date first = shareObservationDAO.findBySharee(ROOT, SERVER, PageRequest.of(0, 10)).get(0).getObserved();

    Thread.sleep(20L);
    storage.reconcile(ROOT, SERVER, Map.of(CAL2, ERIC));
    entityManager.flush();
    entityManager.clear();

    Date again = shareObservationDAO.findBySharee(ROOT, SERVER, PageRequest.of(0, 10)).get(0).getObserved();
    assertTrue(again.after(first), "a pass that changed nothing still confirms what it saw: " + first + " then " + again);
  }

  /**
   * The deepest message of a failure, where the engine names what it refused.
   *
   * @param failure the exception the flush threw
   * @return the root cause's message, never null
   */
  private static String rootCauseMessage(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());
  }

  /**
   * Persists one sighting.
   *
   * @param ownerIdentityId the eXo user whose calendar it is
   * @param shareeIdentityId the eXo user whose home listed it
   * @param serverId the declared server registration
   * @param anchor the calendar's sync uid
   */
  private void persist(long ownerIdentityId, long shareeIdentityId, long serverId, String anchor) {
    shareObservationDAO.save(new CaldavShareObservationEntity(null,
                                                              ownerIdentityId,
                                                              shareeIdentityId,
                                                              serverId,
                                                              anchor,
                                                              new Date()));
  }

  /**
   * Every stored row's id, lowest first.
   *
   * @return the ids
   */
  private List<Long> ids() {
    return shareObservationDAO.findAll().stream().map(CaldavShareObservationEntity::getId).sorted().toList();
  }

  /**
   * The slice needs a boot configuration of its own: caldav-services is a
   * library module with no {@code @SpringBootApplication} to search upwards
   * for.
   */
  @SpringBootConfiguration
  @EntityScan(basePackageClasses = CaldavCalendarSyncEntity.class)
  @EnableJpaRepositories(basePackageClasses = CaldavShareObservationDAO.class)
  static class JpaSliceConfiguration {
  }
}
