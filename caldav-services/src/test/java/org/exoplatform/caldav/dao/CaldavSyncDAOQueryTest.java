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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.caldav.entity.CaldavCalendarSyncEntity;
import org.exoplatform.caldav.entity.CaldavConnectionEntity;
import org.exoplatform.caldav.entity.CaldavPendingSubscriptionEntity;
import org.exoplatform.caldav.model.PendingSubscriptionKind;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.service.CaldavConnectionIdentityService;
import org.exoplatform.caldav.storage.CaldavConnectionStorage;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.exoplatform.caldav.entity.CaldavObjectSyncEntity;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.service.CaldavDeletionService;
import org.exoplatform.caldav.service.CaldavSyncServiceTest;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * Executes the two hand-written JPQL queries in this package against a real
 * engine, over a schema this test builds by applying the module's own Liquibase
 * changelog.
 * <p>
 * Both things it does are deliberate, and neither is covered elsewhere. The
 * storage tests are Mockito-based, so a mock DAO answers happily to a query
 * string the engine would refuse — which is exactly how these two methods
 * shipped binding {@code :status}, {@code :before} and {@code :calendarSyncId}
 * with no {@code @Param} and no {@code -parameters} on the compiler: nothing
 * ever asked Spring Data to build the query, so nothing failed until the
 * repository proxy was created at runtime. And the changelog itself had no test
 * at all, so its twelve changesets reached develop unapplied by anything.
 * <p>
 * The assertions are secondary. What this class proves is that the queries
 * parse, bind by name and run, and that the changelog applies cleanly.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/caldav-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
public class CaldavSyncDAOQueryTest {

  @Autowired
  private CaldavCalendarSyncDAO calendarSyncDAO;

  @Autowired
  private CaldavObjectSyncDAO   objectSyncDAO;

  @Autowired
  private EntityManager         entityManager;

  @Autowired
  private CaldavConnectionDAO   connectionDAO;

  @Autowired
  private CaldavPendingSubscriptionDAO pendingSubscriptionDAO;

  /** The server two users share in the EXO-90190 scenarios below. */
  private static final long     SHARED_SERVER = 5L;

  private static final long     USER_ONE      = 1L;

  private static final long     USER_SIX      = 6L;

  /** The one meeting user one's mirror wrote a copy of. */
  private static final String   SHARED_UID    = "485e6afe-c5f5-4026-ae51-8c1ad905c45c";

  // ---------------------------------------------------------------------
  // EXO-90277 - the subscription changes eXo owes colleagues on BlueMind.
  // ---------------------------------------------------------------------

  /**
   * <b>Hole 2 of the brief, on the engine.</b> Bob (77) received a share and
   * holds no CALDAV_CALENDAR_SYNC row at all, so the sweep's due-accounts
   * query never names him - and the owed-subscription query does. Every
   * hand-written query of the DAO binds its named parameters and runs; the
   * two bulk updates reach the one row they name.
   */
  @Test
  public void aColleagueWhoHoldsNoPairIsDrainedFromTheOwedTableNotFromTheAccounts() {
    long bob = 77L;
    persistCalendarSync(1L, "alices", CalendarSyncStatus.ACTIVE, new Date(0L));
    long owed = persistPendingSubscription(bob, 5L, "exo-cal-shared", PendingSubscriptionKind.SUBSCRIBE, 0);
    long spent = persistPendingSubscription(bob, 5L, "exo-cal-given-up", PendingSubscriptionKind.SUBSCRIBE, 5);
    persistPendingSubscription(1L, 5L, "exo-cal-other", PendingSubscriptionKind.UNSUBSCRIBE, 2);

    var due = calendarSyncDAO.findDueAccounts(CalendarSyncStatus.ACTIVE, new Date(), PageRequest.of(0, 10));
    assertFalse(due.getContent().contains(bob), "the account sweep never reaches a colleague without a pair");

    List<CaldavPendingSubscriptionEntity> attemptable = pendingSubscriptionDAO.findAttemptable(5, PageRequest.of(0, 10, org.springframework.data.domain.Sort.by("id")));
    assertEquals(2, attemptable.size(), "the spent row is left out");
    assertEquals(owed, attemptable.get(0).getId(), "oldest first, and bob is reached");
    assertTrue(attemptable.stream().noneMatch(e -> e.getId() == spent));

    List<CaldavPendingSubscriptionEntity> bobs = pendingSubscriptionDAO.findAttemptableOf(bob, 5, PageRequest.of(0, 10, org.springframework.data.domain.Sort.by("id")));
    assertEquals(List.of(owed), bobs.stream().map(CaldavPendingSubscriptionEntity::getId).toList());

    assertEquals(1, pendingSubscriptionDAO.recordAttempt(owed));
    assertEquals(1, pendingSubscriptionDAO.spendBudget(owed, 5));
    entityManager.clear();
    assertEquals(5, pendingSubscriptionDAO.findById(owed).orElseThrow().getAttempts());
    assertTrue(pendingSubscriptionDAO.findAttemptableOf(bob, 5, PageRequest.of(0, 10)).isEmpty());
    assertTrue(pendingSubscriptionDAO.findByUserIdentityIdAndServerIdAndContainerUid(bob, 5L, "exo-cal-shared").isPresent());
    assertTrue(pendingSubscriptionDAO.findByUserIdentityIdAndServerIdAndContainerUid(bob, 6L, "exo-cal-shared").isEmpty());
  }

  /**
   * One owed change, written straight through the repository.
   *
   * @param userIdentityId the sharee
   * @param serverId the server key
   * @param containerUid the container
   * @param kind the change
   * @param attempts refusals so far
   * @return the row id
   */
  private long persistPendingSubscription(long userIdentityId, long serverId, String containerUid, PendingSubscriptionKind kind, int attempts) {
    CaldavPendingSubscriptionEntity entity = new CaldavPendingSubscriptionEntity();
    entity.setUserIdentityId(userIdentityId);
    entity.setServerId(serverId);
    entity.setContainerUid(containerUid);
    entity.setKind(kind);
    entity.setAttempts(attempts);
    entity.setSince(new Date());
    return pendingSubscriptionDAO.save(entity).getId();
  }

  @Test
  public void findDueBindsItsNamedParametersAndRuns() {
    Date now = new Date();
    long due = persistCalendarSync(1L, "due", CalendarSyncStatus.ACTIVE, new Date(now.getTime() - 60_000L));
    persistCalendarSync(2L, "fresh", CalendarSyncStatus.ACTIVE, new Date(now.getTime() + 60_000L));
    long neverSynced = persistCalendarSync(3L, "never", CalendarSyncStatus.ACTIVE, null);

    var page = calendarSyncDAO.findDue(CalendarSyncStatus.ACTIVE, now, PageRequest.of(0, 10));

    // the one synced after the cut-off is excluded; a null lastSyncEnd counts as due
    assertEquals(2, page.getTotalElements());
    assertTrue(page.getContent().stream().anyMatch(e -> e.getId() == due));
    assertTrue(page.getContent().stream().anyMatch(e -> e.getId() == neverSynced));
  }

  @Test
  public void deleteByCalendarSyncIdBindsItsNamedParameterAndRuns() {
    long calendarSyncId = persistCalendarSync(4L, "with-objects", CalendarSyncStatus.ACTIVE, new Date());
    long otherCalendarSyncId = persistCalendarSync(5L, "other", CalendarSyncStatus.ACTIVE, new Date());
    persistObjectSync(calendarSyncId, "uid-1");
    persistObjectSync(calendarSyncId, "uid-2");
    // a real sibling row, not a fabricated id: CALDAV_OBJECT_SYNC carries a foreign key
    // onto CALDAV_CALENDAR_SYNC, which this test discovers because it runs the changelog
    persistObjectSync(otherCalendarSyncId, "uid-other");

    int deleted = objectSyncDAO.deleteByCalendarSyncId(calendarSyncId);

    assertEquals(2, deleted);
    assertEquals(1, objectSyncDAO.count());
  }

  // ---------------------------------------------------------------------
  // EXO-90190 — ownership is a fact about the account: not about one user,
  // and not about the whole server registration. The rig runs one user
  // against one server and the Mockito storage test answers whatever it is
  // told, so this is the one place the account-scoped question meets several
  // users and several accounts against the engine that will answer it in
  // production.
  // ---------------------------------------------------------------------

  /** The shared account's calendar home, as the storage would pass it. */
  private static final String   SHARED_HOME   = "/dav/calendars/751E/%";

  /** A third user's own account on the same server, as a prefix. */
  private static final String   OWN_HOME      = "/dav/calendars/8E8E/%";

  /** The third user, connected with an account of their own. */
  private static final long     USER_EIGHT    = 8L;

  /**
   * Two users on one account: the copy one of them wrote is eXo's.
   */
  @Test
  public void aCopyAnotherUsersMirrorWroteIsRecognisedAsEXosOwn() {
    long mirrorOfOne = persistPair(USER_ONE, SHARED_SERVER, SyncOrigin.MIRROR, "/dav/calendars/751E/exo-meetings");
    persistPair(USER_SIX, SHARED_SERVER, SyncOrigin.REMOTE, "/dav/calendars/751E/calendar");
    persistObjectSync(mirrorOfOne, SHARED_UID, 52L);

    // Asked from user six's pass, about user one's copy: eXo's.
    assertEquals(1, objectSyncDAO.countByHomeAndOriginAndIcsUid(SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID, SHARED_HOME));
    // The server scope stays: a UID on one account says nothing about another.
    assertEquals(0, objectSyncDAO.countByHomeAndOriginAndIcsUid(99L, SyncOrigin.MIRROR, SHARED_UID, SHARED_HOME));
    // Only a mirror copy is eXo's by construction; a calendar binding's row
    // says the object was read, not written.
    assertEquals(0, objectSyncDAO.countByHomeAndOriginAndIcsUid(SHARED_SERVER, SyncOrigin.EXO, SHARED_UID, SHARED_HOME));
  }

  /**
   * The regression round one shipped: another account on the same server is
   * not the shared account, and its user's meeting is not suppressed.
   */
  @Test
  public void aMirrorCopyOnAnotherAccountOfTheServerDoesNotOwnAThirdUsersMeeting() {
    // The worked example of the round-one review. An externally organised
    // meeting keeps the organiser's UID as its remote identity; user one moved
    // it onto a space calendar, so user one's mirror maps that externally
    // issued UID. User eight, invited to the same meeting by the same
    // organiser, is connected with their OWN account on the same server
    // registration. Asked for the whole server, the question answered "eXo's"
    // about user eight's genuine copy, and user eight never saw the meeting.
    long mirrorOfOne = persistPair(USER_ONE, SHARED_SERVER, SyncOrigin.MIRROR, "/dav/calendars/751E/exo-meetings");
    persistPair(USER_SIX, SHARED_SERVER, SyncOrigin.REMOTE, "/dav/calendars/751E/calendar");
    persistPair(USER_EIGHT, SHARED_SERVER, SyncOrigin.REMOTE, "/dav/calendars/8E8E/calendar");
    persistObjectSync(mirrorOfOne, SHARED_UID, 52L);

    assertEquals(0,
                 objectSyncDAO.countByHomeAndOriginAndIcsUid(SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID, OWN_HOME),
                 "user eight's own copy of the meeting is not eXo's and must be imported");
    assertEquals(1,
                 objectSyncDAO.countByHomeAndOriginAndIcsUid(SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID, SHARED_HOME),
                 "user six, on user one's account, still sees user one's copy as eXo's");

    // The statement round one ran, kept as text so the regression stays
    // reproducible after the method is gone: scoped to the server, it counted
    // user one's mirror for user eight too.
    long serverScoped = entityManager.createQuery("SELECT COUNT(o) FROM CaldavObjectSyncEntity o, CaldavCalendarSyncEntity p"
        + " WHERE o.calendarSyncId = p.id"
        + " AND p.serverId = :serverId AND p.origin = :origin AND o.icsUid = :icsUid", Long.class)
                                     .setParameter("serverId", SHARED_SERVER)
                                     .setParameter("origin", SyncOrigin.MIRROR)
                                     .setParameter("icsUid", SHARED_UID)
                                     .getSingleResult();
    assertEquals(1, serverScoped, "the server-scoped count is what suppressed user eight's meeting");
  }

  /**
   * The pinned negative: the question the account-scoped one replaced twice
   * answered zero here.
   */
  @Test
  public void theUserScopedQuestionThisReplacesAnsweredZeroForTheOtherUser() {
    // The exact statement countByOwnerAndOriginAndIcsUid ran, kept as text so
    // that the defect stays reproducible after the method is gone: asked for
    // user six about user one's copy, it found nothing, and the import went
    // ahead. Anyone tempted to put the user predicate back can run this.
    long mirrorOfOne = persistPair(USER_ONE, SHARED_SERVER, SyncOrigin.MIRROR, "/dav/calendars/751E/exo-meetings");
    persistPair(USER_SIX, SHARED_SERVER, SyncOrigin.REMOTE, "/dav/calendars/751E/calendar");
    persistObjectSync(mirrorOfOne, SHARED_UID, 52L);

    long userScoped = entityManager.createQuery("SELECT COUNT(o) FROM CaldavObjectSyncEntity o, CaldavCalendarSyncEntity p"
        + " WHERE o.calendarSyncId = p.id AND p.userIdentityId = :userIdentityId"
        + " AND p.serverId = :serverId AND p.origin = :origin AND o.icsUid = :icsUid", Long.class)
                                   .setParameter("userIdentityId", USER_SIX)
                                   .setParameter("serverId", SHARED_SERVER)
                                   .setParameter("origin", SyncOrigin.MIRROR)
                                   .setParameter("icsUid", SHARED_UID)
                                   .getSingleResult();

    assertEquals(0, userScoped, "the user-scoped count is what let user six import user one's copy");
    assertEquals(1, objectSyncDAO.countByHomeAndOriginAndIcsUid(SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID, SHARED_HOME));
  }

  /**
   * The answer question keeps the user scope the ownership question lost.
   */
  @Test
  public void theEventBehindACopyIsNamedForItsOwnerAlone() {
    // The asymmetry PLAN 3/5 of EXO-90190 pins. Ownership is widened so that
    // user six does not import user one's copy; the event behind it is NOT,
    // because it feeds answer adoption, which records the reading user's
    // response. Widened, user six's pass would record user one's phone answer
    // as user six's own.
    long mirrorOfOne = persistPair(USER_ONE, SHARED_SERVER, SyncOrigin.MIRROR, "/dav/calendars/751E/exo-meetings");
    persistPair(USER_SIX, SHARED_SERVER, SyncOrigin.REMOTE, "/dav/calendars/751E/calendar");
    persistObjectSync(mirrorOfOne, SHARED_UID, 52L);

    assertEquals(List.of(52L),
                 objectSyncDAO.findEventIdsByOwnerAndOriginAndIcsUid(USER_ONE, SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID));
    assertTrue(objectSyncDAO.findEventIdsByOwnerAndOriginAndIcsUid(USER_SIX, SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID)
                            .isEmpty(),
               "user six must not be handed the event behind user one's copy");
  }

  /**
   * The third question of the family runs, binds by name, and names the meeting
   * behind a copy eXo wrote into <b>another account</b> of the same server
   * (EXO-90247).
   *
   * <p>
   * The shape a server that schedules for itself produces: user one's copy is
   * in user one's own home, and its UID turns up in user eight's home, which is
   * a different account of the same server. Both scoped questions answer
   * "nothing" there, correctly, and neither names the meeting; this one does.
   */
  @Test
  public void theEventBehindACopyIsNamedAcrossAccountsOfOneServer() {
    long mirrorOfOne = persistPair(USER_ONE, SHARED_SERVER, SyncOrigin.MIRROR, "/dav/calendars/751E/exo-meetings");
    persistObjectSync(mirrorOfOne, SHARED_UID, 52L);

    // What user eight's own pass can ask today, on their own account, about the
    // object their server delivered to them: neither question reaches it.
    assertEquals(0,
                 objectSyncDAO.countByHomeAndOriginAndIcsUid(SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID, OWN_HOME),
                 "the copy is not in user eight's calendar home, so the account-scoped question rightly says no");
    assertTrue(objectSyncDAO.findEventIdsByOwnerAndOriginAndIcsUid(USER_EIGHT, SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID)
                            .isEmpty(),
               "the copy is not user eight's, so the user-scoped question rightly says nothing");

    assertEquals(List.of(52L), objectSyncDAO.findEventIdsByServerAndOriginAndIcsUid(SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID));
  }

  /**
   * The server-scoped question stays inside its server and its origin, so it
   * cannot answer for a copy on another registration or for a collection eXo
   * did not write meeting copies into.
   */
  @Test
  public void theServerScopedQuestionIsBoundedByServerAndOrigin() {
    long mirrorOfOne = persistPair(USER_ONE, SHARED_SERVER, SyncOrigin.MIRROR, "/dav/calendars/751E/exo-meetings");
    persistObjectSync(mirrorOfOne, SHARED_UID, 52L);
    long remoteOfSix = persistPair(USER_SIX, SHARED_SERVER, SyncOrigin.REMOTE, "/dav/calendars/751E/calendar");
    persistObjectSync(remoteOfSix, "not-a-copy-uid", 53L);

    assertTrue(objectSyncDAO.findEventIdsByServerAndOriginAndIcsUid(99L, SyncOrigin.MIRROR, SHARED_UID).isEmpty(),
               "another server's registration is another world");
    assertTrue(objectSyncDAO.findEventIdsByServerAndOriginAndIcsUid(SHARED_SERVER, SyncOrigin.REMOTE, SHARED_UID).isEmpty(),
               "only a mirror pair holds a copy eXo wrote");
    assertTrue(objectSyncDAO.findEventIdsByServerAndOriginAndIcsUid(SHARED_SERVER, SyncOrigin.MIRROR, "unknown-uid").isEmpty());
  }

  /**
   * The outbound lock tells another user's copy from one's own, on one account.
   */
  @Test
  public void anotherUsersCopyIsToldApartFromOnesOwnOnTheSameAccount() {
    long mirrorOfOne = persistPair(USER_ONE, SHARED_SERVER, SyncOrigin.MIRROR, "/dav/calendars/751E/exo-meetings");
    persistObjectSync(mirrorOfOne, SHARED_UID, 52L);

    // User six, writing into their own collection on the shared account.
    assertEquals(1,
                 objectSyncDAO.countByOtherOwnerAndHomeAndOriginAndIcsUid(USER_SIX, SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID, SHARED_HOME));
    // User one, writing their own copy.
    assertEquals(0,
                 objectSyncDAO.countByOtherOwnerAndHomeAndOriginAndIcsUid(USER_ONE, SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID, SHARED_HOME));
    // Another server altogether.
    assertEquals(0,
                 objectSyncDAO.countByOtherOwnerAndHomeAndOriginAndIcsUid(USER_SIX, 99L, SyncOrigin.MIRROR, SHARED_UID, SHARED_HOME));
    // User eight, writing the same UID into their own collection on their OWN
    // account of the same server: nothing of user one's is at risk there, and
    // round one refused this write.
    assertEquals(0,
                 objectSyncDAO.countByOtherOwnerAndHomeAndOriginAndIcsUid(USER_EIGHT, SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID, OWN_HOME),
                 "user eight's push into their own account is not a write over user one's copy");
  }

  /**
   * A paused mirror still owns the copies it wrote: the rows and the objects
   * outlive the connection.
   */
  @Test
  public void aPausedMirrorStillOwnsTheCopiesItWrote() {
    // The decision recorded on the DAO: disconnecting pauses the pair and
    // deletes neither its rows nor the copies on the server, so the copy is
    // still physically in the account and still eXo's. An ACTIVE predicate
    // here would hand a departed user's copies to whoever else reads the
    // account as genuine remote events — the defect back.
    long pausedMirrorOfOne = persistPair(USER_ONE,
                                         SHARED_SERVER,
                                         SyncOrigin.MIRROR,
                                         "/dav/calendars/751E/exo-meetings",
                                         CalendarSyncStatus.PAUSED);
    persistObjectSync(pausedMirrorOfOne, SHARED_UID, 52L);

    assertEquals(1, objectSyncDAO.countByHomeAndOriginAndIcsUid(SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID, SHARED_HOME));
    assertEquals(1,
                 objectSyncDAO.countByOtherOwnerAndHomeAndOriginAndIcsUid(USER_SIX, SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID, SHARED_HOME));
  }

  /**
   * The unique index fires on a second row for one pair and UID.
   */
  @Test
  public void theUniqueIndexRefusesASecondRowForOnePairAndUid() {
    // What the duplicate-key hardening in CaldavPushService.saveMapping
    // converges on, produced by the real index rather than a thrown stub. The
    // type it arrives as is this slice's — a Boot-managed factory translates
    // it to DataIntegrityViolationException; production's own factory does
    // not, see CaldavSyncStorageRawEntityManagerFactoryTest — which is why the
    // service tells it by its JDBC cause and not by its type.
    long pair = persistPair(USER_ONE, SHARED_SERVER, SyncOrigin.MIRROR, "/dav/calendars/751E/exo-meetings");
    persistObjectSync(pair, SHARED_UID, 52L);
    objectSyncDAO.flush();

    DataIntegrityViolationException refused = assertThrows(DataIntegrityViolationException.class, () -> {
      persistObjectSync(pair, SHARED_UID, 52L);
      objectSyncDAO.flush();
    });

    assertTrue(CaldavSyncStorage.isDuplicateKey(refused), "told by the JDBC cause, which this shape carries too");
  }

  /**
   * A hidden share (EXO-90239) against the schema the changelog builds: the
   * new status fits the STATUS column and reads back as itself, the anchor
   * derived from its path fits the anchor column, and the row stands beside
   * the user's other pairs — a second hidden share, their mirror pair (the
   * one null anchor), an active pair of their own. It is due for nothing the
   * sweep selects — the sweep reads ACTIVE pairs alone, and a pair with no
   * sync time would otherwise be the most due of all.
   */
  @Test
  public void aHiddenShareIsStoredBesideTheUsersOtherPairsAndIsDueForNothing() {
    long id = calendarSyncDAO.save(hiddenShare(USER_SIX, "/dav/calendars/751E/alice-default")).getId();
    calendarSyncDAO.save(hiddenShare(USER_SIX, "/dav/calendars/751E/bob-default"));
    persistPair(USER_SIX, SHARED_SERVER, SyncOrigin.MIRROR, "/dav/calendars/751E/exo-meetings");
    persistPair(USER_SIX, SHARED_SERVER, SyncOrigin.REMOTE, "/dav/calendars/751E/calendar");
    entityManager.flush();
    entityManager.clear();

    List<CaldavCalendarSyncEntity> pairs = calendarSyncDAO.findByUserIdentityIdAndServerId(USER_SIX, SHARED_SERVER);
    CaldavCalendarSyncEntity readBack = pairs.stream().filter(pair -> pair.getId() == id).findFirst().orElseThrow();

    assertEquals(4, pairs.size());
    assertEquals(CalendarSyncStatus.HIDDEN_SHARE, readBack.getStatus());
    assertEquals(SyncOrigin.REMOTE, readBack.getOrigin());
    assertEquals(CaldavDeletionService.hiddenShareAnchor("/dav/calendars/751E/alice-default"), readBack.getLocalCalendarSyncUid());
    assertEquals("/dav/calendars/751E/alice-default", readBack.getRemoteHref());
    assertTrue(calendarSyncDAO.findDue(CalendarSyncStatus.ACTIVE, new Date(), PageRequest.of(0, 10))
                              .getContent()
                              .stream()
                              .noneMatch(pair -> pair.getId() == id),
               "a hidden share is never due");
  }

  /**
   * One record per hidden calendar, enforced by the real unique index: a
   * second row hiding the same share for the same user is refused, as the
   * duplicate key the hide converges on when two requests race — told by its
   * JDBC cause, the shape this slice produces. Another user hiding the same
   * share is their own record.
   */
  @Test
  public void theUniqueIndexKeepsOneRecordPerHiddenShare() {
    calendarSyncDAO.save(hiddenShare(USER_SIX, "/dav/calendars/751E/alice-default"));
    calendarSyncDAO.save(hiddenShare(USER_EIGHT, "/dav/calendars/751E/alice-default"));
    calendarSyncDAO.flush();

    DataIntegrityViolationException refused = assertThrows(DataIntegrityViolationException.class, () -> {
      calendarSyncDAO.save(hiddenShare(USER_SIX, "/dav/calendars/751E/alice-default"));
      calendarSyncDAO.flush();
    });

    assertTrue(CaldavSyncStorage.isDuplicateKey(refused), "told by the JDBC cause, as the hide tells it");
  }

  /**
   * @param userIdentityId the user who hid the share
   * @param href the shared collection, canonical
   * @return the row a hide records, as the service builds it
   */
  private CaldavCalendarSyncEntity hiddenShare(long userIdentityId, String href) {
    CaldavCalendarSyncEntity hidden = new CaldavCalendarSyncEntity();
    hidden.setUserIdentityId(userIdentityId);
    hidden.setServerId(SHARED_SERVER);
    hidden.setLocalCalendarSyncUid(CaldavDeletionService.hiddenShareAnchor(href));
    hidden.setRemoteHref(href);
    hidden.setOrigin(SyncOrigin.REMOTE);
    hidden.setStatus(CalendarSyncStatus.HIDDEN_SHARE);
    return hidden;
  }

  /**
   * The prefix the storage builds — escape character included — is what the
   * engine takes literally.
   */
  @Test
  public void thePrefixTheStorageBuildsIsTakenLiterallyByTheEngine() {
    // The storage escapes the pattern's wildcards and its own escape
    // character, and here its output is driven through the real query
    // rather than a pattern written by hand: a home carrying a literal "!"
    // and "_" matches itself and nothing that differs by one character.
    long mirrorUnderOddHome = persistPair(USER_ONE, SHARED_SERVER, SyncOrigin.MIRROR, "/dav/calendars/a!b_c/exo-meetings");
    long mirrorUnderLookalike = persistPair(USER_SIX, SHARED_SERVER, SyncOrigin.MIRROR, "/dav/calendars/ab_c/exo-meetings");
    long mirrorUnderOtherLookalike = persistPair(USER_EIGHT, SHARED_SERVER, SyncOrigin.MIRROR, "/dav/calendars/a!bXc/exo-meetings");
    persistObjectSync(mirrorUnderOddHome, SHARED_UID, 52L);
    persistObjectSync(mirrorUnderLookalike, SHARED_UID, 53L);
    persistObjectSync(mirrorUnderOtherLookalike, SHARED_UID, 54L);

    String prefix = CaldavSyncStorage.homePrefixOf("/dav/calendars/a!b_c/calendar/");

    assertEquals("/dav/calendars/a!!b!_c/%", prefix);
    assertEquals(1,
                 objectSyncDAO.countByHomeAndOriginAndIcsUid(SHARED_SERVER, SyncOrigin.MIRROR, SHARED_UID, prefix),
                 "the odd home's own mirror, and neither lookalike");
    assertFalse(CaldavSyncStorage.homePrefixOf("/dav/calendars/ab_c/calendar/").equals(prefix));
  }

  /**
   * A calendar this deployment exported is found by its anchor on its server,
   * whoever holds it and whatever state its pair is in — and not on another
   * server, not under another origin.
   */
  @Test
  public void aCalendarOfThisDeploymentIsFoundByItsAnchorOnItsServer() {
    // The pair-level ownership question adoption rests on (EXO-90226). User
    // one exported a calendar; user six, sharing the account, must see its
    // collection as this deployment's — the calendar exists here — while a
    // collection whose anchor no EXO pair on the server carries is another
    // deployment's. The anchor rather than the href, since BlueMind lists
    // eXo's collections under a path other than the one they were created at.
    persistExoPair(USER_ONE, SHARED_SERVER, "c0ffee-one", CalendarSyncStatus.ACTIVE);
    persistExoPair(USER_SIX, SHARED_SERVER, "c0ffee-paused", CalendarSyncStatus.PAUSED);
    persistExoPair(USER_EIGHT, SHARED_SERVER, "c0ffee-tombstone", CalendarSyncStatus.LOCALLY_DELETED);
    // The same anchor exported to another server: another registration, so
    // another account, and no answer for this one.
    persistExoPair(9L, 99L, "c0ffee-elsewhere", CalendarSyncStatus.ACTIVE);
    // A REMOTE pair carrying an anchor: a calendar materialised here, whose
    // collection is the server's and not one eXo minted.
    persistPair(10L, SHARED_SERVER, SyncOrigin.REMOTE, "/dav/calendars/751E/private");

    assertTrue(calendarSyncDAO.existsByServerIdAndOriginAndLocalCalendarSyncUid(SHARED_SERVER, SyncOrigin.EXO, "c0ffee-one"),
               "a colleague's active export");
    assertTrue(calendarSyncDAO.existsByServerIdAndOriginAndLocalCalendarSyncUid(SHARED_SERVER, SyncOrigin.EXO, "c0ffee-paused"),
               "status is not the question: a paused pair still names a calendar here");
    assertTrue(calendarSyncDAO.existsByServerIdAndOriginAndLocalCalendarSyncUid(SHARED_SERVER, SyncOrigin.EXO, "c0ffee-tombstone"),
               "nor is a tombstone: the calendar was this deployment's, and adopting it would resurrect it for someone else");
    assertFalse(calendarSyncDAO.existsByServerIdAndOriginAndLocalCalendarSyncUid(SHARED_SERVER, SyncOrigin.EXO, "c0ffee-elsewhere"),
                "exported to another server");
    assertFalse(calendarSyncDAO.existsByServerIdAndOriginAndLocalCalendarSyncUid(SHARED_SERVER,
                                                                                SyncOrigin.EXO,
                                                                                "fd3fe75f-58f9-49e5-93d0-85f63b24a807"),
                "another deployment's anchor, known to no pair here");
    String materialisedAnchor = "anchor-10-" + "/dav/calendars/751E/private".hashCode();
    assertFalse(calendarSyncDAO.existsByServerIdAndOriginAndLocalCalendarSyncUid(SHARED_SERVER, SyncOrigin.EXO, materialisedAnchor),
                "a REMOTE pair's anchor is not an export");
    assertTrue(calendarSyncDAO.existsByServerIdAndOriginAndLocalCalendarSyncUid(SHARED_SERVER, SyncOrigin.REMOTE, materialisedAnchor),
               "the same row, asked under its own origin — the origin predicate is what tells the two apart");
  }

  /**
   * A calendar this deployment exported is found by the path its pair
   * records when the server lists it under a slug that is not its anchor.
   */
  @Test
  public void aCalendarOfThisDeploymentIsFoundByItsRecordedPathWhenTheSlugIsNotItsAnchor() {
    // The other arm of the same question, against the engine. The shape is
    // EXO-89590's (CaldavSyncServiceTest.RENAMED_BY_THE_SERVER): BlueMind
    // reported an eXo-made collection with the prefix kept and the slug
    // replaced, so the slug names no anchor here — the first query misses —
    // while the pair recorded at that path still says the calendar is this
    // deployment's. Paused on purpose: the status is not the question for
    // this arm either. The path is stored canonical, so it is asked canonical.
    String published = CaldavSyncStorage.canonicalHref(CaldavSyncServiceTest.RENAMED_BY_THE_SERVER);
    persistExoPairAt(USER_ONE, SHARED_SERVER, "anchor-mine", published, CalendarSyncStatus.PAUSED);
    // A collection materialised here from that same path on another server:
    // REMOTE, so not an export, and not this server anyway.
    persistPair(USER_SIX, 99L, SyncOrigin.REMOTE, published);

    assertFalse(calendarSyncDAO.existsByServerIdAndOriginAndLocalCalendarSyncUid(SHARED_SERVER,
                                                                                SyncOrigin.EXO,
                                                                                "renamed-by-the-server"),
                "the slug the server chose is nobody's anchor: the anchor arm misses this shape");
    assertTrue(calendarSyncDAO.existsByServerIdAndOriginAndRemoteHref(SHARED_SERVER, SyncOrigin.EXO, published),
               "a colleague's paused export, recorded at the published path, is still this deployment's");
    assertFalse(calendarSyncDAO.existsByServerIdAndOriginAndRemoteHref(SHARED_SERVER, SyncOrigin.REMOTE, published),
                "the origin predicate: an EXO pair is not a REMOTE one");
    assertFalse(calendarSyncDAO.existsByServerIdAndOriginAndRemoteHref(99L, SyncOrigin.EXO, published),
                "the server predicate: the REMOTE pair there is not an export, and no EXO pair is on that server");
    assertFalse(calendarSyncDAO.existsByServerIdAndOriginAndRemoteHref(SHARED_SERVER,
                                                                      SyncOrigin.EXO,
                                                                      CaldavSyncServiceTest.RENAMED_BY_THE_SERVER),
                "compared as stored: the trailing slash the listing carries is the caller's to strip");
  }

  /**
   * The third arm (EXO-90347), on the engine: a colleague's calendar
   * <em>imported</em> — REMOTE, a tombstone since she deleted it in eXo,
   * recorded under her own home — is found by the container uid the sharee's
   * listing spells under his home, and by nothing the other two arms read.
   * The mirror ledger is left out, another server is not asked, and the
   * suffix is a suffix: a row whose container merely contains the uid, and
   * a row one wildcard away from it, do not answer — the derived query
   * escapes what it is given.
   */
  @Test
  public void aColleaguesImportedCalendarIsFoundByItsContainerUidUnderAnyHome() {
    String container = "exo-cal-fd3fe75f-58f9-49e5-93d0-85f63b24a807";
    String erics = "/dav/calendars/__uids__/4C60FEDD-0562-4903-A524-E95E1CCBCDE0/" + container;
    persistPair(USER_SIX, SHARED_SERVER, SyncOrigin.REMOTE, erics, CalendarSyncStatus.LOCALLY_DELETED);
    persistPair(USER_ONE, 99L, SyncOrigin.REMOTE, "/dav/calendars/__uids__/other/" + container);
    persistPair(USER_ONE, SHARED_SERVER, SyncOrigin.MIRROR, "/dav/calendars/__uids__/other/exo-cal-mirror-only");
    persistPair(USER_ONE, SHARED_SERVER, SyncOrigin.REMOTE, "/dav/calendars/__uids__/other/exo-cal-ab_d-suffix");
    persistPair(USER_ONE, SHARED_SERVER, SyncOrigin.REMOTE, "/dav/calendars/__uids__/other/exo-cal-abXd");

    assertFalse(calendarSyncDAO.existsByServerIdAndOriginAndLocalCalendarSyncUid(SHARED_SERVER,
                                                                                SyncOrigin.EXO,
                                                                                "fd3fe75f-58f9-49e5-93d0-85f63b24a807"),
                "the slug is not her anchor: the first arm misses");
    assertFalse(calendarSyncDAO.existsByServerIdAndOriginAndRemoteHref(SHARED_SERVER,
                                                                      SyncOrigin.EXO,
                                                                      "/dav/calendars/__uids__/751E6D1A-7FDB-49B2-B668-B569E9A5A42D/" + container),
                "the sharee's spelling is under his home, and the pair is not an export: the second arm misses");
    assertTrue(calendarSyncDAO.existsByServerIdAndOriginNotAndRemoteHrefEndingWith(SHARED_SERVER, SyncOrigin.MIRROR, "/" + container),
               "the container uid answers, whatever home the pair recorded and whatever its status");
    assertFalse(calendarSyncDAO.existsByServerIdAndOriginNotAndRemoteHrefEndingWith(SHARED_SERVER, SyncOrigin.MIRROR, "/exo-cal-mirror-only"),
                "the mirror ledger binds no calendar");
    assertFalse(calendarSyncDAO.existsByServerIdAndOriginNotAndRemoteHrefEndingWith(77L, SyncOrigin.MIRROR, "/" + container),
                "another server is not asked");
    assertFalse(calendarSyncDAO.existsByServerIdAndOriginNotAndRemoteHrefEndingWith(SHARED_SERVER, SyncOrigin.MIRROR, "/exo-cal-ab_d"),
                "a suffix, not a substring: the row ending in -suffix does not answer");
    assertFalse(calendarSyncDAO.existsByServerIdAndOriginNotAndRemoteHrefEndingWith(SHARED_SERVER, SyncOrigin.MIRROR, "/exo-cal-abXd".replace('X', '_')),
                "the underscore is escaped, not a wildcard: exo-cal-abXd does not answer exo-cal-ab_d");
  }

  /**
   * The user behind an exported calendar is named by its anchor, and named
   * the same way on every listing: the active pair first, then the oldest.
   */
  @Test
  public void theUserBehindAnExportedCalendarIsNamedByItsAnchorActiveFirstThenOldest() {
    // The who-form of the anchor arm (EXO-90237), against the engine, with
    // the ORDER BY it rests on. Two users holding one anchor on one server is
    // a shape the schema allows and nothing produces on purpose; when it
    // happens the owner named must not depend on row order, so the paused
    // pair is inserted first — lowest id — and the active one must still win.
    persistExoPair(USER_SIX, SHARED_SERVER, "c0ffee-twice", CalendarSyncStatus.PAUSED);
    persistExoPair(USER_ONE, SHARED_SERVER, "c0ffee-twice", CalendarSyncStatus.ACTIVE);
    // Nobody active: the oldest row is the one that has been true longest.
    long oldest = persistExoPair(USER_EIGHT, SHARED_SERVER, "c0ffee-nobody-active", CalendarSyncStatus.LOCALLY_DELETED);
    persistExoPair(USER_SIX, SHARED_SERVER, "c0ffee-nobody-active", CalendarSyncStatus.PAUSED);
    // The same anchor on another server, and materialised here rather than
    // exported: neither answers.
    persistExoPair(9L, 99L, "c0ffee-twice", CalendarSyncStatus.ACTIVE);
    persistPair(10L, SHARED_SERVER, SyncOrigin.REMOTE, "/dav/calendars/751E/private");

    List<CaldavCalendarSyncEntity> named = calendarSyncDAO.findPairsByAnchorPreferring(SHARED_SERVER,
                                                                                       SyncOrigin.EXO,
                                                                                       "c0ffee-twice",
                                                                                       CalendarSyncStatus.ACTIVE,
                                                                                       PageRequest.of(0, 1));
    assertEquals(1, named.size(), "a page of one names one");
    assertEquals(USER_ONE, named.get(0).getUserIdentityId(), "the active pair, though it was inserted second");

    List<CaldavCalendarSyncEntity> both = calendarSyncDAO.findPairsByAnchorPreferring(SHARED_SERVER,
                                                                                      SyncOrigin.EXO,
                                                                                      "c0ffee-twice",
                                                                                      CalendarSyncStatus.ACTIVE,
                                                                                      PageRequest.of(0, 10));
    assertEquals(List.of(USER_ONE, USER_SIX), both.stream().map(CaldavCalendarSyncEntity::getUserIdentityId).toList(),
                 "active first, the rest after");

    List<CaldavCalendarSyncEntity> byAge = calendarSyncDAO.findPairsByAnchorPreferring(SHARED_SERVER,
                                                                                       SyncOrigin.EXO,
                                                                                       "c0ffee-nobody-active",
                                                                                       CalendarSyncStatus.ACTIVE,
                                                                                       PageRequest.of(0, 1));
    assertEquals(oldest, byAge.get(0).getId(), "no active pair: the oldest row is named");

    assertTrue(calendarSyncDAO.findPairsByAnchorPreferring(99L, SyncOrigin.EXO, "c0ffee-nobody-active", CalendarSyncStatus.ACTIVE,
                                                           PageRequest.of(0, 1))
                              .isEmpty(),
               "the server predicate");
    String materialisedAnchor = "anchor-10-" + "/dav/calendars/751E/private".hashCode();
    assertTrue(calendarSyncDAO.findPairsByAnchorPreferring(SHARED_SERVER, SyncOrigin.EXO, materialisedAnchor, CalendarSyncStatus.ACTIVE,
                                                           PageRequest.of(0, 1))
                              .isEmpty(),
               "the origin predicate: a REMOTE pair's anchor is not an export");
  }

  /**
   * The user behind an exported calendar is named by the path its pair
   * records when the slug is not its anchor, with the same pick.
   */
  @Test
  public void theUserBehindAnExportedCalendarIsNamedByItsRecordedPathWithTheSamePick() {
    // The who-form of the path arm (EXO-90237). Two users recorded at one
    // published path is likelier than two at one anchor — the path is unique
    // to nobody in the schema — so the pick is pinned here as well: the
    // active pair, inserted second, is the one named.
    String published = CaldavSyncStorage.canonicalHref(CaldavSyncServiceTest.RENAMED_BY_THE_SERVER);
    persistExoPairAt(USER_SIX, SHARED_SERVER, "anchor-six", published, CalendarSyncStatus.PAUSED);
    persistExoPairAt(USER_ONE, SHARED_SERVER, "anchor-one", published, CalendarSyncStatus.ACTIVE);
    persistPair(USER_EIGHT, 99L, SyncOrigin.REMOTE, published);

    List<CaldavCalendarSyncEntity> named = calendarSyncDAO.findPairsByRemoteHrefPreferring(SHARED_SERVER,
                                                                                           SyncOrigin.EXO,
                                                                                           published,
                                                                                           CalendarSyncStatus.ACTIVE,
                                                                                           PageRequest.of(0, 1));
    assertEquals(1, named.size());
    assertEquals(USER_ONE, named.get(0).getUserIdentityId(), "the active pair, though it was inserted second");
    assertTrue(calendarSyncDAO.findPairsByRemoteHrefPreferring(SHARED_SERVER,
                                                               SyncOrigin.EXO,
                                                               CaldavSyncServiceTest.RENAMED_BY_THE_SERVER,
                                                               CalendarSyncStatus.ACTIVE,
                                                               PageRequest.of(0, 1))
                              .isEmpty(),
               "compared as stored: the caller strips the trailing slash");
    assertTrue(calendarSyncDAO.findPairsByRemoteHrefPreferring(99L, SyncOrigin.EXO, published, CalendarSyncStatus.ACTIVE, PageRequest.of(0, 1))
                              .isEmpty(),
               "the REMOTE pair on the other server is neither an export nor on this server");
  }

  /**
   * @param userIdentityId the user whose calendar was exported
   * @param serverId the declared server
   * @param anchor the calendar's anchor, which the collection's slug carries
   * @param status the pair's state
   * @return the pair's identifier
   */
  private long persistExoPair(long userIdentityId, long serverId, String anchor, CalendarSyncStatus status) {
    return persistExoPairAt(userIdentityId, serverId, anchor, "/dav/calendars/751E/exo-cal-" + anchor, status);
  }

  /**
   * @param userIdentityId the user whose calendar was exported
   * @param serverId the declared server
   * @param anchor the calendar's anchor
   * @param href the path the pair records, canonical — the slug eXo minted,
   *          or the one the server republished it under
   * @param status the pair's state
   * @return the pair's identifier
   */
  private long persistExoPairAt(long userIdentityId, long serverId, String anchor, String href, CalendarSyncStatus status) {
    CaldavCalendarSyncEntity entity = new CaldavCalendarSyncEntity();
    entity.setUserIdentityId(userIdentityId);
    entity.setServerId(serverId);
    entity.setLocalCalendarSyncUid(anchor);
    entity.setRemoteHref(href);
    entity.setOrigin(SyncOrigin.EXO);
    entity.setStatus(status);
    entity.setLastSyncEnd(new Date());
    return calendarSyncDAO.save(entity).getId();
  }

  /**
   * @param userIdentityId the user holding the pair
   * @param serverId the declared server
   * @param origin which side created the collection
   * @param href the collection, canonical
   * @return the pair's identifier
   */
  private long persistPair(long userIdentityId, long serverId, SyncOrigin origin, String href) {
    return persistPair(userIdentityId, serverId, origin, href, CalendarSyncStatus.ACTIVE);
  }

  /**
   * @param userIdentityId the user holding the pair
   * @param serverId the declared server
   * @param origin which side created the collection
   * @param href the collection, canonical
   * @param status the pair's state
   * @return the pair's identifier
   */
  private long persistPair(long userIdentityId, long serverId, SyncOrigin origin, String href, CalendarSyncStatus status) {
    CaldavCalendarSyncEntity entity = new CaldavCalendarSyncEntity();
    entity.setUserIdentityId(userIdentityId);
    entity.setServerId(serverId);
    entity.setLocalCalendarSyncUid(origin == SyncOrigin.MIRROR ? null : "anchor-" + userIdentityId + "-" + href.hashCode());
    entity.setRemoteHref(href);
    entity.setOrigin(origin);
    entity.setStatus(status);
    entity.setLastSyncEnd(new Date());
    return calendarSyncDAO.save(entity).getId();
  }

  /**
   * @param calendarSyncId the pair
   * @param icsUid the object's UID
   * @param localEventId the eXo event the row stands for
   */
  private void persistObjectSync(long calendarSyncId, String icsUid, Long localEventId) {
    CaldavObjectSyncEntity entity = new CaldavObjectSyncEntity();
    entity.setCalendarSyncId(calendarSyncId);
    entity.setIcsUid(icsUid);
    entity.setLocalEventId(localEventId);
    entity.setRemoteHref("/calendars/x/" + icsUid + ".ics");
    objectSyncDAO.save(entity);
  }

  private long persistCalendarSync(long userIdentityId, String uid, CalendarSyncStatus status, Date lastSyncEnd) {
    CaldavCalendarSyncEntity entity = new CaldavCalendarSyncEntity();
    entity.setUserIdentityId(userIdentityId);
    entity.setServerId(1L);
    entity.setLocalCalendarSyncUid(uid);
    entity.setRemoteHref("/calendars/" + uid + "/");
    entity.setOrigin(SyncOrigin.EXO);
    entity.setStatus(status);
    entity.setLastSyncEnd(lastSyncEnd);
    return calendarSyncDAO.save(entity).getId();
  }

  // ---------------------------------------------------------------------
  // EXO-90243 — who each connected user is on their server. The rig's shape:
  // alice (5) and alice2 (6) connected to ONE Stalwart login, bob (9) to his
  // own, and bob holding a pair under Alice's home — the pair the old
  // shared-account question read as "bob is on Alice's account".
  // ---------------------------------------------------------------------

  /** Alice's principal, canonical, as the discovery records it. */
  private static final String   ALICE_PRINCIPAL = "/dav/pal/alice@stalwart.local";

  /** Bob's own principal, canonical. */
  private static final String   BOB_PRINCIPAL   = "/dav/pal/bob@stalwart.local";

  private static final long     ALICE           = 5L;

  private static final long     ALICE2          = 6L;

  private static final long     BOB             = 9L;

  /** The rig's Stalwart registration. */
  private static final long     STALWART        = 1L;

  /**
   * The lookup the table exists for runs on the engine: by server and
   * principal, lowest user first, bounded by its page — and by user.
   */
  @Test
  public void aConnectionIsFoundByItsPrincipalOnItsServerAndByItsUser() {
    persistConnection(BOB, STALWART, BOB_PRINCIPAL);
    persistConnection(ALICE2, STALWART, ALICE_PRINCIPAL);
    persistConnection(ALICE, STALWART, ALICE_PRINCIPAL);
    // Same principal spelling on another registration: another account.
    persistConnection(10L, 2L, ALICE_PRINCIPAL);
    entityManager.flush();
    entityManager.clear();

    assertEquals(List.of(ALICE, ALICE2),
                 connectionDAO.findByServerAndPrincipal(STALWART, ALICE_PRINCIPAL, PageRequest.of(0, 10))
                              .stream()
                              .map(CaldavConnectionEntity::getUserIdentityId)
                              .toList());
    assertEquals(List.of(ALICE),
                 connectionDAO.findByServerAndPrincipal(STALWART, ALICE_PRINCIPAL, PageRequest.of(0, 1))
                              .stream()
                              .map(CaldavConnectionEntity::getUserIdentityId)
                              .toList(),
                 "the page bounds the answer, and the order makes the bound name the same user every time");
    assertEquals(List.of(10L),
                 connectionDAO.findByServerAndPrincipal(2L, ALICE_PRINCIPAL, PageRequest.of(0, 10))
                              .stream()
                              .map(CaldavConnectionEntity::getUserIdentityId)
                              .toList());
    assertEquals(BOB_PRINCIPAL, connectionDAO.findByUserIdentityId(BOB).orElseThrow().getPrincipal());
    assertTrue(connectionDAO.findByUserIdentityId(42L).isEmpty());
  }

  /**
   * Who a calendar on a server can be shared with (EXO-90253): every row of
   * that server, lowest user first, bounded by the page, and nobody recorded
   * on another server.
   */
  @Test
  public void theConnectionsOfOneServerAreReadInUserOrderAndBounded() {
    persistConnection(BOB, STALWART, BOB_PRINCIPAL);
    persistConnection(ALICE2, STALWART, ALICE_PRINCIPAL);
    persistConnection(ALICE, STALWART, ALICE_PRINCIPAL);
    persistConnection(10L, 2L, ALICE_PRINCIPAL);
    entityManager.flush();
    entityManager.clear();

    assertEquals(List.of(ALICE, ALICE2, BOB),
                 connectionDAO.findByServer(STALWART, PageRequest.of(0, 10))
                              .stream()
                              .map(CaldavConnectionEntity::getUserIdentityId)
                              .toList());
    assertEquals(List.of(ALICE, ALICE2),
                 connectionDAO.findByServer(STALWART, PageRequest.of(0, 2))
                              .stream()
                              .map(CaldavConnectionEntity::getUserIdentityId)
                              .toList(),
                 "the page bounds the answer, and the order keeps the same users inside it");
    assertEquals(List.of(10L),
                 connectionDAO.findByServer(2L, PageRequest.of(0, 10)).stream().map(CaldavConnectionEntity::getUserIdentityId).toList());
  }

  /**
   * One identity per user, enforced by the real unique index and told by its
   * JDBC cause — the refusal two nodes recording one user converge on.
   */
  @Test
  public void theUniqueIndexKeepsOneConnectionIdentityPerUser() {
    persistConnection(ALICE, STALWART, ALICE_PRINCIPAL);
    connectionDAO.flush();

    DataIntegrityViolationException refused = assertThrows(DataIntegrityViolationException.class, () -> {
      persistConnection(ALICE, STALWART, ALICE_PRINCIPAL);
      connectionDAO.flush();
    });

    assertTrue(CaldavSyncStorage.isDuplicateKey(refused), "told by the JDBC cause, as the identity service tells it");
  }

  /**
   * Forgetting a user removes their row and nobody else's, and forgetting a
   * user nobody recorded removes nothing.
   */
  @Test
  public void forgettingAUserRemovesTheirIdentityOnly() {
    persistConnection(ALICE, STALWART, ALICE_PRINCIPAL);
    persistConnection(ALICE2, STALWART, ALICE_PRINCIPAL);
    entityManager.flush();

    assertEquals(1, connectionDAO.deleteByUserIdentityId(ALICE));
    assertEquals(0, connectionDAO.deleteByUserIdentityId(ALICE));
    entityManager.clear();
    assertTrue(connectionDAO.findByUserIdentityId(ALICE).isEmpty());
    assertTrue(connectionDAO.findByUserIdentityId(ALICE2).isPresent());
  }

  /**
   * <b>The rig's warning, before and after, on the engine.</b>
   *
   * <p>
   * Alice's account is connected by alice and alice2; bob, on his own login,
   * holds an active pair under Alice's home — the pair a share of Alice's
   * calendar left him. The question the warning asked until EXO-90243 —
   * other users with active pairs under the account's calendar home, kept
   * here as text so the regression stays reproducible — names bob beside
   * alice2, which is the "also connected by eXo users [6, 9]" line seen on
   * the rig. Asked by identity, through the real storage over this engine,
   * it names alice2 alone.
   */
  @Test
  public void theSharedAccountIsToldByPrincipalNotByTheHomeASharedCalendarSitsUnder() {
    persistPair(ALICE, STALWART, SyncOrigin.MIRROR, "/dav/cal/alice@stalwart.local/exo-meetings");
    persistPair(ALICE2, STALWART, SyncOrigin.REMOTE, "/dav/cal/alice@stalwart.local/default");
    persistPair(BOB, STALWART, SyncOrigin.REMOTE, "/dav/cal/alice@stalwart.local/default");
    persistConnection(ALICE, STALWART, ALICE_PRINCIPAL);
    persistConnection(ALICE2, STALWART, ALICE_PRINCIPAL);
    persistConnection(BOB, STALWART, BOB_PRINCIPAL);
    entityManager.flush();

    @SuppressWarnings("unchecked")
    List<Long> byHome = entityManager.createQuery("SELECT DISTINCT p.userIdentityId FROM CaldavCalendarSyncEntity p"
        + " WHERE p.serverId = :serverId AND p.status = :status AND p.userIdentityId <> :userIdentityId"
        + " AND p.remoteHref LIKE :prefix ESCAPE '!' ORDER BY p.userIdentityId")
                                     .setParameter("serverId", STALWART)
                                     .setParameter("status", CalendarSyncStatus.ACTIVE)
                                     .setParameter("userIdentityId", ALICE)
                                     .setParameter("prefix", CaldavSyncStorage.homePrefixOf("/dav/cal/alice@stalwart.local/default"))
                                     .getResultList();
    assertEquals(List.of(ALICE2, BOB), byHome, "the home question named bob, who only holds a share under Alice's home");

    CaldavConnectionStorage storage = new CaldavConnectionStorage();
    ReflectionTestUtils.setField(storage, "connectionDAO", connectionDAO);
    CaldavConnectorStorage settings = Mockito.mock(CaldavConnectorStorage.class);
    Mockito.when(settings.getCaldavSetting(Mockito.anyLong())).thenReturn(connectedTo(STALWART));
    CaldavConnectionIdentityService identities = new CaldavConnectionIdentityService();
    ReflectionTestUtils.setField(identities, "caldavConnectionStorage", storage);
    ReflectionTestUtils.setField(identities, "caldavConnectorStorage", settings);

    assertEquals(List.of(ALICE2), identities.otherUsersConnectedAs(ALICE, STALWART, "/dav/pal/alice%40stalwart.local/"));
    assertEquals(List.of(ALICE), identities.otherUsersConnectedAs(ALICE2, STALWART, "/dav/pal/alice%40stalwart.local/"));
    assertTrue(identities.otherUsersConnectedAs(BOB, STALWART, "/dav/pal/bob%40stalwart.local/").isEmpty(),
               "and bob, alone on his login, is told nothing");
  }

  /**
   * <b>Who is synchronising without a recorded identity, on the engine.</b>
   * Just after the upgrade: alice (two active pairs) recorded, alice2 (two
   * active pairs, same login) not yet; bob recorded; dave's pairs paused, no
   * row; carol active on Stalwart but recorded for another server. Missing on
   * Stalwart: alice2 and carol — each counted once however many pairs they
   * hold, dave not at all. Once alice2 is recorded and carol's identity is
   * recorded for Stalwart, nobody is missing.
   */
  @Test
  public void theUsersSynchronisingWithoutARecordedIdentityAreCountedOnTheEngine() {
    persistPair(ALICE, STALWART, SyncOrigin.MIRROR, "/dav/cal/alice@stalwart.local/exo-meetings");
    persistPair(ALICE, STALWART, SyncOrigin.EXO, "/dav/cal/alice@stalwart.local/exo-cal-a");
    persistPair(ALICE2, STALWART, SyncOrigin.REMOTE, "/dav/cal/alice@stalwart.local/default");
    persistPair(ALICE2, STALWART, SyncOrigin.EXO, "/dav/cal/alice@stalwart.local/exo-cal-b");
    persistPair(BOB, STALWART, SyncOrigin.EXO, "/dav/cal/bob@stalwart.local/exo-cal-c");
    persistPair(11L, STALWART, SyncOrigin.EXO, "/dav/cal/dave@stalwart.local/exo-cal-d", CalendarSyncStatus.PAUSED);
    persistPair(10L, STALWART, SyncOrigin.EXO, "/dav/cal/carol@stalwart.local/exo-cal-e");
    persistConnection(ALICE, STALWART, ALICE_PRINCIPAL);
    persistConnection(BOB, STALWART, BOB_PRINCIPAL);
    persistConnection(10L, 2L, "/dav/pal/carol@elsewhere");
    entityManager.flush();

    assertEquals(2L, connectionDAO.countUsersWithPairsButNoIdentity(STALWART, CalendarSyncStatus.ACTIVE));
    assertEquals(0L, connectionDAO.countUsersWithPairsButNoIdentity(2L, CalendarSyncStatus.ACTIVE),
                 "nobody holds a pair on the other server");

    persistConnection(ALICE2, STALWART, ALICE_PRINCIPAL);
    CaldavConnectionEntity carol = connectionDAO.findByUserIdentityId(10L).orElseThrow();
    carol.setServerId(STALWART);
    carol.setPrincipal("/dav/pal/carol@stalwart.local");
    connectionDAO.save(carol);
    entityManager.flush();

    assertEquals(0L, connectionDAO.countUsersWithPairsButNoIdentity(STALWART, CalendarSyncStatus.ACTIVE));
  }

  /**
   * @param serverId the registration the account names
   * @return a connected account on it
   */
  private static CaldavUserSetting connectedTo(long serverId) {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("someone@stalwart.local");
    setting.setPassword("secret");
    setting.setServerId(serverId);
    return setting;
  }

  /**
   * @param userIdentityId the eXo user
   * @param serverId the server key
   * @param principal the canonical principal
   */
  private void persistConnection(long userIdentityId, long serverId, String principal) {
    CaldavConnectionEntity connection = new CaldavConnectionEntity();
    connection.setUserIdentityId(userIdentityId);
    connection.setServerId(serverId);
    connection.setPrincipal(principal);
    connectionDAO.save(connection);
  }

  /**
   * The slice needs a boot configuration of its own: caldav-services is a library
   * module with no @SpringBootApplication to search upwards for.
   */
  @SpringBootConfiguration
  @EntityScan(basePackageClasses = CaldavCalendarSyncEntity.class)
  @EnableJpaRepositories(basePackageClasses = CaldavCalendarSyncDAO.class)
  static class JpaSliceConfiguration {
  }

  private void persistObjectSync(long calendarSyncId, String icsUid) {
    CaldavObjectSyncEntity entity = new CaldavObjectSyncEntity();
    entity.setCalendarSyncId(calendarSyncId);
    entity.setIcsUid(icsUid);
    entity.setRemoteHref("/calendars/x/" + icsUid + ".ics");
    objectSyncDAO.save(entity);
  }
}
