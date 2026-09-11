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
import org.exoplatform.caldav.entity.CaldavObjectSyncEntity;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
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

  /** The server two users share in the EXO-90190 scenarios below. */
  private static final long     SHARED_SERVER = 5L;

  private static final long     USER_ONE      = 1L;

  private static final long     USER_SIX      = 6L;

  /** The one meeting user one's mirror wrote a copy of. */
  private static final String   SHARED_UID    = "485e6afe-c5f5-4026-ae51-8c1ad905c45c";

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
   * The other users under one calendar home are the ones sharing the account.
   */
  @Test
  public void otherUsersUnderOneCalendarHomeAreListedByPrefix() {
    persistPair(USER_ONE, SHARED_SERVER, SyncOrigin.EXO, "/dav/calendars/751E/exo-cal-0b1318fd");
    persistPair(USER_SIX, SHARED_SERVER, SyncOrigin.EXO, "/dav/calendars/751E/exo-cal-6bade8c7");
    // Same server, another account: not under the home.
    persistPair(8L, SHARED_SERVER, SyncOrigin.EXO, "/dav/calendars/OTHER/exo-cal-c");
    // Same home spelling, another server: another account altogether.
    persistPair(9L, 99L, SyncOrigin.EXO, "/dav/calendars/751E/exo-cal-d");
    // Under the home but no longer active: not connected any more.
    persistPair(10L, SHARED_SERVER, SyncOrigin.EXO, "/dav/calendars/751E/exo-cal-e", CalendarSyncStatus.PAUSED);

    assertEquals(List.of(USER_ONE),
                 calendarSyncDAO.findOtherUsersUnderHref(USER_SIX,
                                                         SHARED_SERVER,
                                                         CalendarSyncStatus.ACTIVE,
                                                         SHARED_HOME,
                                                         PageRequest.of(0, 10)));
    assertTrue(calendarSyncDAO.findOtherUsersUnderHref(8L,
                                                       SHARED_SERVER,
                                                       CalendarSyncStatus.ACTIVE,
                                                       "/dav/calendars/OTHER/%",
                                                       PageRequest.of(0, 10))
                              .isEmpty());
  }

  /**
   * The listing is bounded by its page, and the engine honours the bound.
   */
  @Test
  public void theOtherUsersListedAreBoundedByThePage() {
    for (long user = 20L; user < 25L; user++) {
      persistPair(user, SHARED_SERVER, SyncOrigin.EXO, "/dav/calendars/751E/exo-cal-" + user);
    }

    assertEquals(2,
                 calendarSyncDAO.findOtherUsersUnderHref(USER_SIX,
                                                         SHARED_SERVER,
                                                         CalendarSyncStatus.ACTIVE,
                                                         SHARED_HOME,
                                                         PageRequest.of(0, 2))
                                .size(),
                 "a shared account names who else is on it, not everybody who ever was");
  }

  /**
   * The escape character the query declares is honoured.
   */
  @Test
  public void aWildcardInTheCalendarHomeIsTakenLiterally() {
    persistPair(USER_ONE, SHARED_SERVER, SyncOrigin.EXO, "/dav/calendars/a_b/exo-cal-x");
    persistPair(USER_SIX, SHARED_SERVER, SyncOrigin.EXO, "/dav/calendars/aXb/exo-cal-y");

    // Escaped, the underscore matches an underscore and nothing else.
    assertEquals(List.of(USER_ONE),
                 calendarSyncDAO.findOtherUsersUnderHref(7L,
                                                         SHARED_SERVER,
                                                         CalendarSyncStatus.ACTIVE,
                                                         "/dav/calendars/a!_b/%",
                                                         PageRequest.of(0, 10)));
    // Unescaped, it would have matched both — which is the reason the storage escapes it.
    assertEquals(2,
                 calendarSyncDAO.findOtherUsersUnderHref(7L,
                                                         SHARED_SERVER,
                                                         CalendarSyncStatus.ACTIVE,
                                                         "/dav/calendars/a_b/%",
                                                         PageRequest.of(0, 10))
                                .size());
  }

  /**
   * The prefix the storage builds — escape character included — is what the
   * engine takes literally.
   */
  @Test
  public void thePrefixTheStorageBuildsIsTakenLiterallyByTheEngine() {
    // The storage escapes the pattern's wildcards and its own escape
    // character, and here its output is driven through the real queries
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
    assertEquals(List.of(USER_ONE),
                 calendarSyncDAO.findOtherUsersUnderHref(USER_SIX,
                                                         SHARED_SERVER,
                                                         CalendarSyncStatus.ACTIVE,
                                                         prefix,
                                                         PageRequest.of(0, 10)));
    assertFalse(CaldavSyncStorage.homePrefixOf("/dav/calendars/ab_c/calendar/").equals(prefix));
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
