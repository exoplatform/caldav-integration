/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.caldav.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
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
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import org.exoplatform.caldav.dao.CaldavPendingPushDAO;
import org.exoplatform.caldav.model.PendingPush;
import org.exoplatform.caldav.model.PendingPushKind;

/**
 * What eXo owes a calendar copy, against a real database and the schema this
 * add-on actually ships.
 *
 * <p>
 * <b>Why this is not optional.</b> EXO-89773's whole contract lives in this
 * class: the record is written <i>before</i> the write is attempted, and struck
 * off <i>only</i> when the write lands. Everything above it — the fan-out, the
 * retry pass, the bound — is decoration if the store does not do exactly that.
 * The service tests mock this storage, so they pin the decision and never the
 * mechanism, and a mock cannot know that a unique constraint exists, that a
 * cascade fires, or that a bulk increment reaches the right row.
 *
 * <p>
 * <b>Liquibase builds the schema here, not Hibernate.</b> That is deliberate and
 * it is what lets the cascade be tested at all: {@code ON DELETE CASCADE} lives
 * in the changelog, and the entity maps the parent as a plain column with no
 * association, so a schema generated from the mapping would carry no foreign key
 * and the cascade test would pass by having nothing to cascade. Running the
 * changelog also means these assertions are made against the columns, types and
 * constraints that will exist in production rather than against a schema
 * Hibernate invented for the occasion.
 *
 * <p>
 * <b>What it still cannot say.</b> HSQLDB, not MySQL: it proves the constraints
 * and the statements, and nothing about an index key length under utf8mb4 or a
 * collation. Those need a run against the real vendor.
 */
public class CaldavPendingPushStorageTest {

  /** The changelog the webapp points spring.liquibase.change-log at. */
  private static final String        CHANGELOG = "db/changelog/caldav-rdbms.db.changelog-master.xml";

  private static final long          ALICE     = 11L;

  private static final long          BOB       = 22L;

  /** The mapping rows every test writes obligations against. */
  private static final long          ALICE_COPY = 501L;

  private static final long          ALICE_OTHER_COPY = 502L;

  private static final long          BOB_COPY  = 503L;

  private static final long          EVENT     = 8801L;

  private Connection                 connection;

  private EntityManagerFactory       factory;

  private EntityManager              entityManager;

  private CaldavPendingPushStorage   storage;

  /**
   * Builds a database of this test's own from the changelog, and a storage
   * talking to it through a real repository proxy.
   *
   * @throws Exception when the schema cannot be built
   */
  @BeforeEach
  public void openADatabaseOfItsOwn() throws Exception {
    String url = "jdbc:hsqldb:mem:caldav-pending-" + System.nanoTime();
    // Opened first and kept open for the whole test: an in-memory database
    // lives only as long as a connection to it does, and this one is also what
    // the raw-SQL assertions read through.
    connection = DriverManager.getConnection(url, "sa", "");
    connection.setAutoCommit(true);
    // Liquibase gets a connection of its own, and gives it back. It holds the
    // changelog lock and leaves a transaction open on the connection it was
    // handed, so sharing one with Hibernate deadlocks the first query the
    // EntityManager makes — silently, as a test that never finishes.
    Connection schema = DriverManager.getConnection(url, "sa", "");
    Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(schema));
    try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
      liquibase.update(new Contexts(), new LabelExpression());
    }

    Map<String, Object> overrides = new HashMap<>();
    overrides.put("jakarta.persistence.jdbc.url", url);
    // The changelog has already built the schema; Hibernate must accept it as
    // it is rather than replace it with one generated from the mapping.
    overrides.put("hibernate.hbm2ddl.auto", "none");
    factory = Persistence.createEntityManagerFactory("caldav-test", overrides);
    entityManager = factory.createEntityManager();

    CaldavPendingPushDAO dao = new JpaRepositoryFactory(entityManager).getRepository(CaldavPendingPushDAO.class);
    storage = new CaldavPendingPushStorage();
    ReflectionTestUtils.setField(storage, "pendingPushDAO", dao);

    givenMappingRow(ALICE_COPY, 100L, ALICE);
    givenMappingRow(ALICE_OTHER_COPY, 100L, ALICE);
    givenMappingRow(BOB_COPY, 200L, BOB);
  }

  /**
   * Closes both connections and drops the database, so a parallel class cannot
   * inherit this one's rows.
   *
   * @throws Exception when the database cannot be shut down
   */
  @AfterEach
  public void dropTheDatabase() throws Exception {
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
   * An obligation comes back saying what it was recorded as.
   *
   * <p>
   * Every field of it, because each one is load-bearing somewhere: the kind
   * decides whether the retry rewrites or removes, the iCalendar identity is the
   * only thing a removal can address the object by once its event is destroyed,
   * and the count is what the bound reads.
   */
  @Test
  public void anObligationSurvivesARoundTripSayingWhatItIs() {
    inTransaction(() -> storage.owe(ALICE_COPY, ALICE, PendingPushKind.REWRITE, EVENT, "uid-8801"));

    PendingPush owed = only(storage.attemptable(ALICE, 5, 10));
    assertNotNull(owed.getId(), "a persisted obligation carries its identifier");
    assertEquals(ALICE_COPY, owed.getObjectSyncId());
    assertEquals(ALICE, owed.getUserIdentityId());
    assertEquals(PendingPushKind.REWRITE, owed.getKind());
    assertEquals(EVENT, owed.getLocalEventId());
    assertEquals("uid-8801", owed.getIcsUid());
    assertEquals(0, owed.getAttempts(), "a fresh obligation has not been refused yet");
    assertNotNull(owed.getSince(), "an obligation records when it started, or nobody can see how long a copy has been wrong");
  }

  /**
   * A removal keeps the identity a removal needs and carries no event, because
   * by then there is none.
   */
  @Test
  public void aRemovalIsStoredWithNoEventAndTheIdentityItWillBeAddressedBy() {
    inTransaction(() -> storage.owe(ALICE_COPY, ALICE, PendingPushKind.REMOVE, null, "uid-8801"));

    PendingPush owed = only(storage.attemptable(ALICE, 5, 10));
    assertEquals(PendingPushKind.REMOVE, owed.getKind());
    assertNull(owed.getLocalEventId(), "a destroyed event is not there to be rendered");
    assertEquals("uid-8801", owed.getIcsUid());
  }

  /**
   * Five edits in a minute owe the copy one write, not five — and the row is
   * replaced rather than queued, so the latest instruction is the one that
   * describes the copy.
   *
   * <p>
   * A rewrite recorded on top of a removal would otherwise put back a meeting
   * somebody destroyed.
   */
  @Test
  public void recordingTheSameCopyAgainReplacesWhatWasOwedRatherThanQueueingIt() {
    inTransaction(() -> storage.owe(ALICE_COPY, ALICE, PendingPushKind.REWRITE, EVENT, "uid-8801"));
    Long first = only(storage.attemptable(ALICE, 5, 10)).getId();
    Date since = only(storage.attemptable(ALICE, 5, 10)).getSince();

    inTransaction(() -> storage.owe(ALICE_COPY, ALICE, PendingPushKind.REMOVE, null, "uid-8801"));

    assertEquals(1, rowCount("SELECT COUNT(*) FROM CALDAV_PENDING_PUSH"), "one copy, one obligation");
    PendingPush owed = only(storage.attemptable(ALICE, 5, 10));
    assertEquals(first, owed.getId(), "the same row, rewritten");
    assertEquals(PendingPushKind.REMOVE, owed.getKind(), "the latest instruction is the one that stands");
    assertEquals(since, owed.getSince(), "and it has been owed since the first time, not since the last edit");
  }

  /**
   * Recording again resets the patience, so a copy given up on last week is
   * tried afresh when the meeting moves.
   */
  @Test
  public void recordingTheSameCopyAgainGivesItItsPatienceBack() {
    inTransaction(() -> storage.owe(ALICE_COPY, ALICE, PendingPushKind.REWRITE, EVENT, "uid-8801"));
    long id = only(storage.attemptable(ALICE, 5, 10)).getId();
    inTransaction(() -> refuse(id));
    inTransaction(() -> refuse(id));
    assertEquals(2, only(storage.attemptable(ALICE, 5, 10)).getAttempts());

    inTransaction(() -> storage.owe(ALICE_COPY, ALICE, PendingPushKind.REWRITE, EVENT, "uid-8801"));

    assertEquals(0, only(storage.attemptable(ALICE, 5, 10)).getAttempts(), "a new edit is a new obligation");
  }

  /**
   * The unique constraint is load-bearing rather than a convention the code
   * promises to keep.
   *
   * <p>
   * {@code owe} looks the row up before writing it, which is a check-then-act
   * and therefore a race: two listener threads carrying two edits of the same
   * meeting can both read "nothing owed" before either has written. What makes
   * the outcome one row rather than two is the database, and this is the
   * assertion that the database will actually say so — asked by inserting the
   * duplicate directly, because no path through the storage can be made to try.
   */
  @Test
  public void theDatabaseItselfRefusesASecondObligationForOneCopy() {
    inTransaction(() -> storage.owe(ALICE_COPY, ALICE, PendingPushKind.REWRITE, EVENT, "uid-8801"));

    SQLException refused = assertThrows(SQLException.class,
                                        () -> execute("INSERT INTO CALDAV_PENDING_PUSH"
                                            + " (ID, OBJECT_SYNC_ID, USER_IDENTITY_ID, KIND, ATTEMPTS)"
                                            + " VALUES (9999, " + ALICE_COPY + ", " + ALICE + ", 'REWRITE', 0)"));

    assertTrue(refused.getMessage().toLowerCase(java.util.Locale.ROOT).contains("unique")
        || refused.getMessage().toLowerCase(java.util.Locale.ROOT).contains("constraint"),
               "the refusal must come from the unique constraint: " + refused.getMessage());
    assertEquals(1, rowCount("SELECT COUNT(*) FROM CALDAV_PENDING_PUSH"));
  }

  /**
   * Striking one copy off leaves every other obligation exactly where it was.
   *
   * <p>
   * The failure this pins is the one that would be invisible: a write that lands
   * for one attendee clearing the arrears of the other forty-nine, who then keep
   * a stale meeting with nothing recording that they do.
   */
  @Test
  public void strikingOffRemovesTheOneCopyAndNoOther() {
    inTransaction(() -> storage.owe(ALICE_COPY, ALICE, PendingPushKind.REWRITE, EVENT, "uid-a"));
    inTransaction(() -> storage.owe(ALICE_OTHER_COPY, ALICE, PendingPushKind.REWRITE, EVENT, "uid-b"));
    inTransaction(() -> storage.owe(BOB_COPY, BOB, PendingPushKind.REWRITE, EVENT, "uid-c"));

    inTransaction(() -> settled(ALICE_COPY));

    assertEquals(1, storage.owed(ALICE), "only the copy that was written is struck off");
    assertEquals(1, storage.owed(BOB), "and nobody else's arrears are touched");
    assertEquals(ALICE_OTHER_COPY, only(storage.attemptable(ALICE, 5, 10)).getObjectSyncId());
  }

  /**
   * Striking off a copy nothing was owed to is not an error.
   *
   * <p>
   * It happens on the ordinary path: every successful push calls it, and most
   * successful pushes were never in arrears.
   */
  @Test
  public void strikingOffACopyThatOwedNothingIsQuiet() {
    inTransaction(() -> settled(ALICE_COPY));

    assertEquals(0, storage.owed(ALICE));
  }

  /**
   * When the mapping row goes, what was owed to it goes with it.
   *
   * <p>
   * The cascade is in the changelog, not in the code, and it is what stops an
   * obligation outliving the copy it names — which would be retried for ever
   * against an identifier that resolves to nothing. Asserted with raw SQL on
   * purpose: a count served out of the persistence context would prove that
   * Hibernate remembers, not that the database cascaded.
   *
   * @throws SQLException when the mapping row cannot be deleted
   */
  @Test
  public void whenTheMappingRowGoesWhatWasOwedToItGoesWithIt() throws SQLException {
    inTransaction(() -> storage.owe(ALICE_COPY, ALICE, PendingPushKind.REWRITE, EVENT, "uid-8801"));
    inTransaction(() -> storage.owe(BOB_COPY, BOB, PendingPushKind.REWRITE, EVENT, "uid-c"));
    assertEquals(2, rowCount("SELECT COUNT(*) FROM CALDAV_PENDING_PUSH"));

    execute("DELETE FROM CALDAV_OBJECT_SYNC WHERE ID = " + ALICE_COPY);

    assertEquals(1, rowCount("SELECT COUNT(*) FROM CALDAV_PENDING_PUSH"), "the obligation went with its copy");
    assertEquals(BOB_COPY, rowCount("SELECT OBJECT_SYNC_ID FROM CALDAV_PENDING_PUSH"), "and only that one");
  }

  /**
   * The due query answers one account's arrears and nobody else's.
   */
  @Test
  public void whatIsDueIsScopedToTheAccountItIsAskedAbout() {
    inTransaction(() -> storage.owe(ALICE_COPY, ALICE, PendingPushKind.REWRITE, EVENT, "uid-a"));
    inTransaction(() -> storage.owe(BOB_COPY, BOB, PendingPushKind.REWRITE, EVENT, "uid-c"));

    assertEquals(ALICE_COPY, only(storage.attemptable(ALICE, 5, 10)).getObjectSyncId());
    assertEquals(BOB_COPY, only(storage.attemptable(BOB, 5, 10)).getObjectSyncId());
  }

  /**
   * An obligation that has been refused as often as the bound allows drops out
   * of the due query — and stays in the table.
   *
   * <p>
   * Both halves matter. Dropping out is what stops eXo arguing with a server
   * that is not going to change its mind, three round trips at a time, for as
   * long as the account exists. Staying is what leaves somebody able to see that
   * the copy is wrong; deleting it would make giving up indistinguishable from
   * succeeding.
   */
  @Test
  public void anObligationPastTheBoundIsNoLongerDueButIsStillThere() {
    inTransaction(() -> storage.owe(ALICE_COPY, ALICE, PendingPushKind.REWRITE, EVENT, "uid-8801"));
    long id = only(storage.attemptable(ALICE, 3, 10)).getId();

    inTransaction(() -> refuse(id));
    inTransaction(() -> refuse(id));
    assertEquals(1, storage.attemptable(ALICE, 3, 10).size(), "two refusals of three is still worth attempting");

    inTransaction(() -> refuse(id));

    assertTrue(storage.attemptable(ALICE, 3, 10).isEmpty(), "the third refusal reaches the bound");
    assertEquals(1, storage.owed(ALICE), "and the record stays, because it is the only sign anything is wrong");
  }

  /**
   * A refusal is counted against the row it belongs to and against no other.
   *
   * <p>
   * The increment is a single statement in the database rather than a read, an
   * add and a save, so that two sweeps racing cannot both write "attempt 3" —
   * a bound that loses increments is a bound that does not bound.
   */
  @Test
  public void aRefusalIsCountedAgainstOneRowOnly() {
    inTransaction(() -> storage.owe(ALICE_COPY, ALICE, PendingPushKind.REWRITE, EVENT, "uid-a"));
    inTransaction(() -> storage.owe(ALICE_OTHER_COPY, ALICE, PendingPushKind.REWRITE, EVENT, "uid-b"));
    long first = storage.attemptable(ALICE, 5, 10).get(0).getId();

    inTransaction(() -> refuse(first));
    inTransaction(() -> refuse(first));

    List<PendingPush> owed = storage.attemptable(ALICE, 5, 10);
    assertEquals(2, owed.get(0).getAttempts(), "counted twice against the row it was asked about");
    assertEquals(0, owed.get(1).getAttempts(), "and not at all against the other");
  }

  /**
   * The arrears are drained oldest first, and a batch takes only as many as it
   * was asked for.
   *
   * <p>
   * A copy that has been wrong the longest is the one somebody is most likely to
   * be acting on, and a pass that shuffled would starve it for ever behind
   * whatever went wrong most recently.
   */
  @Test
  public void arrearsAreDrainedOldestFirstAndOnlyABatchAtATime() {
    inTransaction(() -> storage.owe(ALICE_COPY, ALICE, PendingPushKind.REWRITE, EVENT, "uid-a"));
    inTransaction(() -> storage.owe(ALICE_OTHER_COPY, ALICE, PendingPushKind.REWRITE, EVENT, "uid-b"));

    List<PendingPush> batch = storage.attemptable(ALICE, 5, 1);

    assertEquals(1, batch.size(), "a batch of one takes one");
    assertEquals(ALICE_COPY, batch.get(0).getObjectSyncId(), "the one that has been owed longest");
    assertEquals(2, storage.attemptable(ALICE, 5, 10).size(), "the rest waits for the next pass");
  }

  /**
   * An account whose copies all landed is owed nothing, which is what makes the
   * retry pass free on a converged account.
   */
  @Test
  public void anAccountThatIsOwedNothingSaysSo() {
    assertEquals(0, storage.owed(ALICE));
    assertTrue(storage.attemptable(ALICE, 5, 10).isEmpty());
  }

  // ---------------------------------------------------------------- fixtures

  /**
   * Runs a unit of storage work in a transaction of its own.
   *
   * <p>
   * The storage's {@code @Transactional} annotations are Spring's and there is
   * no proxy here to apply them, so the boundary is drawn by hand — and drawn
   * per call rather than around the whole test, because committing is what makes
   * the raw-SQL assertions able to see the work at all.
   *
   * @param work what to run
   * @param <T> what it answers
   * @return whatever the work answered
   */
  private <T> T inTransaction(Supplier<T> work) {
    entityManager.getTransaction().begin();
    try {
      T answer = work.get();
      entityManager.getTransaction().commit();
      return answer;
    } catch (RuntimeException e) {
      // Only when there is still one to roll back: a commit that fails has
      // already rolled itself back, and asking again throws an
      // IllegalStateException that would hide the failure being reported.
      if (entityManager.getTransaction().isActive()) {
        entityManager.getTransaction().rollback();
      }
      throw e;
    } finally {
      // The obligations are read back through the same EntityManager, and a
      // row a bulk UPDATE changed underneath it would otherwise be answered
      // from the first-level cache exactly as it was before.
      entityManager.clear();
    }
  }

  /**
   * Counts one refusal, as a value the transaction helper can carry.
   *
   * @param id the obligation
   * @return nothing meaningful
   */
  private Object refuse(long id) {
    storage.refused(id);
    return null;
  }

  /**
   * Strikes one copy off, as a value the transaction helper can carry.
   *
   * @param objectSyncId the mapping row whose copy was written
   * @return nothing meaningful
   */
  private Object settled(long objectSyncId) {
    storage.settled(objectSyncId);
    return null;
  }

  /**
   * The single obligation a list holds, failing when there is not exactly one.
   *
   * @param owed what the storage answered
   * @return its only element
   */
  private PendingPush only(List<PendingPush> owed) {
    assertEquals(1, owed.size(), "exactly one obligation was expected, got " + owed);
    return owed.get(0);
  }

  /**
   * Writes the mapping row an obligation will hang off, and the pair it needs
   * to exist at all.
   *
   * @param objectSyncId identifier of the mapping row
   * @param pairId identifier of the collection binding it belongs to
   * @param userIdentityId the owner of that binding
   * @throws SQLException when the rows cannot be written
   */
  private void givenMappingRow(long objectSyncId, long pairId, long userIdentityId) throws SQLException {
    if (rowCount("SELECT COUNT(*) FROM CALDAV_CALENDAR_SYNC WHERE ID = " + pairId) == 0) {
      execute("INSERT INTO CALDAV_CALENDAR_SYNC (ID, USER_IDENTITY_ID, SERVER_ID, REMOTE_HREF, ORIGIN, STATUS,"
          + " CONSECUTIVE_FAILURES) VALUES (" + pairId + ", " + userIdentityId + ", 1, '/dav/u" + userIdentityId
          + "/mirror', 'MIRROR', 'ACTIVE', 0)");
    }
    execute("INSERT INTO CALDAV_OBJECT_SYNC (ID, CALENDAR_SYNC_ID, LOCAL_EVENT_ID, ICS_UID, REMOTE_HREF) VALUES ("
        + objectSyncId + ", " + pairId + ", " + EVENT + ", 'uid-" + objectSyncId + "', '/dav/o" + objectSyncId + ".ics')");
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
   * Reads one number straight from the database, past every cache JPA holds.
   *
   * @param sql a query whose first column of whose first row is a number
   * @return that number, or 0 when the query answers no row
   */
  private long rowCount(String sql) {
    try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getLong(1) : 0L;
    } catch (SQLException e) {
      throw new IllegalStateException("the database could not answer " + sql, e);
    }
  }

}
