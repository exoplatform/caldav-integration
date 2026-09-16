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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import org.exoplatform.caldav.dao.CaldavPendingSubscriptionDAO;
import org.exoplatform.caldav.model.PendingSubscription;
import org.exoplatform.caldav.model.PendingSubscriptionKind;

/**
 * What eXo owes a colleague's BlueMind account, against a real database and
 * the schema this add-on actually ships (EXO-90277).
 *
 * <p>
 * The service tests mock this storage, so they pin the decision and never the
 * mechanism — and the mechanism is where the contract lives: one row per
 * colleague, server and container, so a revoke recorded over a pending
 * subscribe <em>replaces</em> it; a row for a colleague who holds no pair at
 * all, which is the whole reason the table has no foreign key; and bulk
 * updates reaching the right row. A mock cannot know that a unique index
 * exists or that no foreign key does.
 *
 * <p>
 * Liquibase builds the schema, as in {@code CaldavPendingPushStorageTest} and
 * for the same reason: the assertions are made against the columns and
 * constraints that will exist in production. HSQLDB, not MySQL: it proves the
 * constraints and the statements, nothing about a key length under utf8mb4.
 */
public class CaldavPendingSubscriptionStorageTest {

  /** The changelog the webapp points spring.liquibase.change-log at. */
  private static final String              CHANGELOG = "db/changelog/caldav-rdbms.db.changelog-master.xml";

  private static final long                BOB       = 9L;

  private static final long                CAROL     = 12L;

  private static final long                SERVER    = 1L;

  private static final long                OTHER_SERVER = 2L;

  private static final String              CONTAINER = "exo-cal-9f1c2d3e-4b5a-6c7d-8e9f-0a1b2c3d4e5f";

  private static final String              OTHER     = "exo-cal-0000aaaa-1111-2222-3333-444455556666";

  private Connection                       connection;

  private EntityManagerFactory             factory;

  private EntityManager                    entityManager;

  private CaldavPendingSubscriptionStorage storage;

  private ListAppender<ILoggingEvent>      logged;

  private Logger                           logger;

  private Level                            previousLevel;

  /**
   * Builds a database of this test's own from the changelog, and a storage
   * talking to it through a real repository proxy.
   *
   * @throws Exception when the schema cannot be built
   */
  @BeforeEach
  public void openADatabaseOfItsOwn() throws Exception {
    String url = "jdbc:hsqldb:mem:caldav-pending-subscription-" + System.nanoTime();
    connection = DriverManager.getConnection(url, "sa", "");
    connection.setAutoCommit(true);
    Connection schema = DriverManager.getConnection(url, "sa", "");
    Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(schema));
    try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
      liquibase.update(new Contexts(), new LabelExpression());
    }
    Map<String, Object> overrides = new HashMap<>();
    overrides.put("jakarta.persistence.jdbc.url", url);
    overrides.put("hibernate.hbm2ddl.auto", "none");
    factory = Persistence.createEntityManagerFactory("caldav-test", overrides);
    entityManager = factory.createEntityManager();
    CaldavPendingSubscriptionDAO dao = new JpaRepositoryFactory(entityManager).getRepository(CaldavPendingSubscriptionDAO.class);
    storage = new CaldavPendingSubscriptionStorage();
    ReflectionTestUtils.setField(storage, "pendingSubscriptionDAO", dao);

    logger = (Logger) LoggerFactory.getLogger(CaldavPendingSubscriptionStorage.class);
    previousLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    logged = new ListAppender<>();
    logged.start();
    logger.addAppender(logged);
  }

  /**
   * Closes both connections and drops the database.
   *
   * @throws Exception when the database cannot be shut down
   */
  @AfterEach
  public void dropTheDatabase() throws Exception {
    if (logger != null) {
      logger.detachAppender(logged);
      logger.setLevel(previousLevel);
    }
    if (entityManager != null) {
      entityManager.close();
    }
    if (factory != null) {
      factory.close();
    }
    if (connection != null) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("SHUTDOWN");
      }
      connection.close();
    }
  }

  /**
   * An obligation comes back saying what it was recorded as — and it is
   * recorded for a colleague who holds <b>no pair at all</b>: no
   * {@code CALDAV_CALENDAR_SYNC} row was written for bob, and nothing refused
   * the insert. That is hole 2 of the brief at the schema level.
   */
  @Test
  public void anObligationIsRecordedForAColleagueWhoHoldsNoPair() {
    assertEquals(0, rowCount("SELECT COUNT(*) FROM CALDAV_CALENDAR_SYNC WHERE USER_IDENTITY_ID = " + BOB));

    inTransaction(() -> storage.owe(BOB, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE));

    PendingSubscription owed = only(storage.attemptable(5, 10));
    assertNotNull(owed.getId());
    assertEquals(BOB, owed.getUserIdentityId());
    assertEquals(SERVER, owed.getServerId());
    assertEquals(CONTAINER, owed.getContainerUid());
    assertEquals(PendingSubscriptionKind.SUBSCRIBE, owed.getKind());
    assertEquals(0, owed.getAttempts());
    assertNotNull(owed.getSince());
  }

  /**
   * The latest instruction wins: a revoke recorded over a pending subscribe
   * leaves one row, an UNSUBSCRIBE, with its attempts back to zero — and a
   * second colleague, another server or another container each get a row of
   * their own.
   */
  @Test
  public void aRevokeRecordedOverAPendingSubscribeReplacesItAndResetsTheCount() {
    inTransaction(() -> storage.owe(BOB, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE));
    long id = only(storage.attemptable(5, 10)).getId();
    inTransaction(() -> storage.refused(id));
    inTransaction(() -> storage.refused(id));
    assertEquals(2, only(storage.attemptable(5, 10)).getAttempts());

    inTransaction(() -> storage.owe(BOB, SERVER, CONTAINER, PendingSubscriptionKind.UNSUBSCRIBE));

    PendingSubscription replaced = only(storage.attemptable(5, 10));
    assertEquals(id, replaced.getId(), "the same row, rewritten");
    assertEquals(PendingSubscriptionKind.UNSUBSCRIBE, replaced.getKind());
    assertEquals(0, replaced.getAttempts(), "a new instruction deserves its own patience");
    assertEquals(1, rowCount("SELECT COUNT(*) FROM CALDAV_PENDING_SUBSCRIPTION"));

    inTransaction(() -> storage.owe(CAROL, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE));
    inTransaction(() -> storage.owe(BOB, OTHER_SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE));
    inTransaction(() -> storage.owe(BOB, SERVER, OTHER, PendingSubscriptionKind.SUBSCRIBE));
    assertEquals(4, rowCount("SELECT COUNT(*) FROM CALDAV_PENDING_SUBSCRIPTION"));
  }

  /**
   * The unique index is what settles two writers: a second raw insert for the
   * same colleague, server and container is an integrity violation.
   *
   * @throws SQLException when the database cannot be asked
   */
  @Test
  public void theUniqueIndexRefusesASecondRowForOneColleagueServerAndContainer() throws SQLException {
    execute("INSERT INTO CALDAV_PENDING_SUBSCRIPTION (ID, USER_IDENTITY_ID, SERVER_ID, CONTAINER_UID, KIND, ATTEMPTS) VALUES"
        + " (1001, " + BOB + ", " + SERVER + ", '" + CONTAINER + "', 'SUBSCRIBE', 0)");

    SQLException refused = assertThrows(SQLException.class,
                                        () -> execute("INSERT INTO CALDAV_PENDING_SUBSCRIPTION (ID, USER_IDENTITY_ID, SERVER_ID,"
                                            + " CONTAINER_UID, KIND, ATTEMPTS) VALUES (1002, " + BOB + ", " + SERVER + ", '"
                                            + CONTAINER + "', 'UNSUBSCRIBE', 0)"));

    assertTrue(refused.getSQLState().startsWith("23"), refused.getMessage());
  }

  /**
   * Settling forgets the instruction that landed and no other.
   */
  @Test
  public void settlingForgetsTheInstructionThatLandedAndNoOther() {
    inTransaction(() -> storage.owe(BOB, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE));
    inTransaction(() -> storage.owe(BOB, SERVER, OTHER, PendingSubscriptionKind.SUBSCRIBE));

    inTransaction(() -> storage.settledIfStillAsking(BOB, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE));

    assertEquals(OTHER, only(storage.attemptable(5, 10)).getContainerUid());
    inTransaction(() -> storage.settledIfStillAsking(BOB, SERVER, "never-owed", PendingSubscriptionKind.SUBSCRIBE));
    assertEquals(1, rowCount("SELECT COUNT(*) FROM CALDAV_PENDING_SUBSCRIPTION"), "settling what is not owed is a no-op");
  }

  /**
   * <b>The race that made the drain's settle a four-argument call.</b> A drain
   * reads its rows once and then spends one round trip on each, inside a
   * session that costs a login and a logout, holding no lock the share service
   * takes. A revoke arriving in that window records an
   * UNSUBSCRIBE over the pending SUBSCRIBE — the same row, by the
   * one-row-per-container rule, so the row's own id is no help — and the drain
   * then lands its now-stale SUBSCRIBE.
   *
   * <p>
   * Settling by container alone would strike off the removal nobody has made
   * yet, and the colleague would keep, silently and for good, a subscription
   * to a calendar whose access entry is gone: the dangling subscription this
   * feature exists to prevent, reached from the inside. The kind is what makes
   * the delete refuse an instruction it did not land, and the surviving row
   * still says UNSUBSCRIBE with its own patience intact.
   */
  @Test
  public void aLandedSubscribeDoesNotSettleTheRevokeRecordedWhileItWasInFlight() {
    inTransaction(() -> storage.owe(BOB, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE));
    long owed = only(storage.attemptable(5, 10)).getId();
    inTransaction(() -> storage.refused(owed));
    inTransaction(() -> storage.refused(owed));
    // The revoke lands in eXo while the drain's session is still open.
    inTransaction(() -> storage.owe(BOB, SERVER, CONTAINER, PendingSubscriptionKind.UNSUBSCRIBE));

    // The drain comes back and reports the subscribe it posted before that.
    inTransaction(() -> storage.settledIfStillAsking(BOB, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE));

    PendingSubscription standing = only(storage.attemptable(5, 10));
    assertEquals(PendingSubscriptionKind.UNSUBSCRIBE, standing.getKind(), "the removal nobody has made yet is still owed");
    assertEquals(CONTAINER, standing.getContainerUid());
    assertEquals(0, standing.getAttempts(), "and with its own patience, not the subscribe's two spent attempts");

    // The decline is the only trace this race leaves: settle logs "landed"
    // either way, so without this line the guard doing its work and the guard
    // never running look identical to whoever is reading the log afterwards.
    String declined = logged.list.stream()
                                 .filter(event -> event.getLevel() == Level.DEBUG)
                                 .map(ILoggingEvent::getFormattedMessage)
                                 .collect(java.util.stream.Collectors.joining("\n"));
    assertTrue(declined.contains("SUBSCRIBE") && declined.contains("UNSUBSCRIBE") && declined.contains(CONTAINER),
               "the decline names the container and both kinds: " + declined);

    // And when the drain does land that removal, it settles.
    inTransaction(() -> storage.settledIfStillAsking(BOB, SERVER, CONTAINER, PendingSubscriptionKind.UNSUBSCRIBE));
    assertEquals(0, rowCount("SELECT COUNT(*) FROM CALDAV_PENDING_SUBSCRIPTION"));
  }

  /**
   * <b>The other overload, and why it is not the same one.</b> The grant and
   * the revoke decide their instruction in the call that then settles it,
   * inside the share's stripe lock and after the read-back — nothing they can
   * be stale about. So they clear whatever row stands for the container, and
   * the kind-guarded overload would be wrong for them in the one way that
   * matters: a grant whose subscribe failed leaves a pending SUBSCRIBE, the
   * owner revokes, the unsubscribe lands — and a guarded settle would keep
   * that SUBSCRIBE alive for the next drain to post, subscribing the colleague
   * to a calendar whose access entry is gone. BlueMind's subscribe makes no
   * access check, so it would land, and the only trace would be a success
   * line.
   */
  @Test
  public void theUnconditionalSettleClearsTheOtherKindToo() {
    inTransaction(() -> storage.owe(BOB, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE));
    inTransaction(() -> storage.owe(BOB, SERVER, OTHER, PendingSubscriptionKind.UNSUBSCRIBE));

    // A revoke that landed, over a subscribe that never did.
    inTransaction(() -> storage.settledWhateverWasOwed(BOB, SERVER, CONTAINER));
    // And a grant that landed, over a revoke that never did.
    inTransaction(() -> storage.settledWhateverWasOwed(BOB, SERVER, OTHER));

    assertEquals(0, rowCount("SELECT COUNT(*) FROM CALDAV_PENDING_SUBSCRIPTION"), "nothing is left for a drain to re-apply");
  }

  /**
   * Refused counts one; abandoned spends the whole bound; both leave the
   * attemptable set as the bound says — the abandoned row stays in the table
   * as the record that eXo gave up, and is never handed out again.
   */
  @Test
  public void refusedCountsOneAndAbandonedSpendsTheBudgetWhole() {
    inTransaction(() -> storage.owe(BOB, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE));
    inTransaction(() -> storage.owe(CAROL, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE));
    long bobs = storage.attemptable(BOB, 5, 10).get(0).getId();
    long carols = storage.attemptable(CAROL, 5, 10).get(0).getId();

    for (int i = 0; i < 4; i++) {
      inTransaction(() -> storage.refused(bobs));
    }
    inTransaction(() -> storage.abandoned(carols, 5));

    assertEquals(4, only(storage.attemptable(BOB, 5, 10)).getAttempts());
    assertTrue(storage.attemptable(CAROL, 5, 10).isEmpty(), "abandoned is below the bound for good");
    assertEquals(1, storage.attemptable(5, 10).size(), "table-wide, only bob's is still worth attempting");
    inTransaction(() -> storage.refused(bobs));
    assertTrue(storage.attemptable(5, 10).isEmpty(), "the fifth refusal reaches the bound");
    assertEquals(2, rowCount("SELECT COUNT(*) FROM CALDAV_PENDING_SUBSCRIPTION"), "both rows stay as the record");
  }

  /**
   * Table-wide is oldest first and bounded; per colleague is theirs alone.
   */
  @Test
  public void theBacklogIsDrainedOldestFirstAndBounded() {
    inTransaction(() -> storage.owe(CAROL, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE));
    inTransaction(() -> storage.owe(BOB, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE));
    inTransaction(() -> storage.owe(BOB, SERVER, OTHER, PendingSubscriptionKind.UNSUBSCRIBE));

    List<PendingSubscription> all = storage.attemptable(5, 10);
    assertEquals(List.of(CAROL, BOB, BOB), all.stream().map(PendingSubscription::getUserIdentityId).toList());
    assertEquals(2, storage.attemptable(5, 2).size());
    assertEquals(CAROL, storage.attemptable(5, 1).get(0).getUserIdentityId());
    assertEquals(List.of(CONTAINER, OTHER), storage.attemptable(BOB, 5, 10).stream().map(PendingSubscription::getContainerUid).toList());
    assertTrue(storage.attemptable(99L, 5, 10).isEmpty());
  }

  /**
   * Runs a storage call inside one transaction, as the platform's proxy does.
   *
   * @param call the call
   */
  private void inTransaction(Runnable call) {
    entityManager.getTransaction().begin();
    try {
      call.run();
      entityManager.getTransaction().commit();
    } catch (RuntimeException e) {
      entityManager.getTransaction().rollback();
      throw e;
    }
    entityManager.clear();
  }

  /**
   * Runs a storage call that returns something inside one transaction.
   *
   * @param <T> what it returns
   * @param call the call
   * @return what it returned
   */
  @SuppressWarnings("unused")
  private <T> T inTransaction(Supplier<T> call) {
    entityManager.getTransaction().begin();
    T result = call.get();
    entityManager.getTransaction().commit();
    entityManager.clear();
    return result;
  }

  /**
   * The one obligation a list was expected to hold.
   *
   * @param owed the list
   * @return its one element
   */
  private static PendingSubscription only(List<PendingSubscription> owed) {
    assertEquals(1, owed.size(), "exactly one obligation was expected, got " + owed);
    return owed.get(0);
  }

  /**
   * Runs one statement on the raw connection, outside JPA entirely.
   *
   * @param sql the statement
   * @throws SQLException when the database refuses it
   */
  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  /**
   * One number read on the raw connection.
   *
   * @param sql a query answering one number
   * @return the number
   */
  private long rowCount(String sql) {
    try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getLong(1) : 0L;
    } catch (SQLException e) {
      throw new IllegalStateException("the database could not answer " + sql, e);
    }
  }
}
