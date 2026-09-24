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
package org.exoplatform.caldav.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import jakarta.persistence.Column;
import jakarta.persistence.Table;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.ValidationFailedException;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.resource.ResourceAccessor;

/**
 * The changelog, run by Liquibase against a real database.
 *
 * <p>
 * <b>Why this exists.</b> Every other assertion this module makes about its
 * schema reads the XML as text. A changelog that reads perfectly can still be
 * one Liquibase refuses to apply, or one that applies and cannot be rolled
 * back — and a failed changeset here does not fail a feature, it takes the
 * whole platform down: Liquibase runs ahead of the entityManagerFactory,
 * which the Kernel/Spring bridge waits on. That is the incident 1.0.0-2 and
 * 1.0.0-3 recount, found on an acceptance server rather than here.
 *
 * <p>
 * The generic checks name no changeset, on purpose. They run whatever the
 * changelog holds, so the changeset added next year is covered by the same
 * three assertions with nobody remembering to extend them: it applies, it
 * rolls back, and applying it again from nothing lands on the same schema.
 *
 * <p>
 * The upgrade-path tests are the exception, and deliberately so: they are
 * tied to changesets 1.0.0-48, 1.0.0-53 and 1.0.0-54 and to the checksums
 * databases recorded for them (EXO-90613). The ones that stand for an
 * existing database rebuild its state from the current changelog by editing
 * those changesets by id, and their recorded-checksum assertion ties the
 * rebuilt state to the real database; the others run the current changelog
 * on an empty one.
 *
 * <p>
 * <b>What it still cannot say.</b> This is HSQLDB, not MySQL. It proves the
 * grammar and the reversibility; it proves nothing about a MySQL index key
 * length, a collation, or the ENGINE append the modifySql blocks carry — the
 * dbms-scoped statements are not even generated here. Those still need a run
 * against the real vendor.
 */
public class ChangelogExecutionTest {

  /** The changelog the webapp points spring.liquibase.change-log at. */
  private static final String CHANGELOG = "db/changelog/caldav-rdbms.db.changelog-master.xml";

  /** The column EXO-89757 appends, and the reason this test was finally written. */
  private static final String ANSWER_LINKS_COLUMN = "ANSWER_LINKS_IN_COPY";

  /** The per-server excusal lists EXO-89771 appends, and the summary they are ticked from. */
  private static final String[] QUIRK_COLUMNS = { "IGNORED_PROPERTIES", "DROPPED_PROPERTIES", "OBSERVED_QUIRKS",
      "OMITTED_PROPERTIES" };

  /** The stamp EXO-89759 puts on a registration when a copy-governing setting changes. */
  private static final String SETTINGS_UPDATED_COLUMN = "COPY_SETTINGS_UPDATED";

  /** And the one a mirror pair carries for the stamp it has already applied. */
  private static final String SETTINGS_APPLIED_COLUMN = "COPY_SETTINGS_APPLIED";

  /** The per-server destination EXO-89760 appends. */
  private static final String MIRROR_TARGET_COLUMN = "MIRROR_TARGET";

  /** The per-server credentials provider EXO-89657 appends. */
  private static final String AUTH_PROVIDER_COLUMN = "AUTH_PROVIDER_NAME";

  /** Who each connected user is on their server, the table EXO-90243 adds. */
  private static final String CONNECTION_TABLE    = "CALDAV_CONNECTION";

  /** The index the platform builds its EntityManager from. */
  private static final String ENTITY_INDEX        = "jpa-entities.idx";

  /** The subscription changes eXo owes colleagues on BlueMind, the table EXO-90277 adds. */
  private static final String PENDING_SUBSCRIPTION_TABLE = "CALDAV_PENDING_SUBSCRIPTION";

  /**
   * The checksum the acceptance server recorded for 1.0.0-48 when that id
   * still carried EXO-90307's WRITE_CHANNEL column (EXO-90613), copied from
   * its startup log.
   */
  private static final String FIRST_HISTORY_48_CHECKSUM = "9:588b6a86d17c02ef17738e390c9f9472";

  /** The checksum every other database recorded for 1.0.0-48, EXO-90190's ownership index. */
  private static final String INDEX_48_CHECKSUM         = "9:65c57ed8043bec6f241947242302533a";

  /** The column EXO-90307 adds, first under 1.0.0-48 and then under 1.0.0-53. */
  private static final String WRITE_CHANNEL_COLUMN      = "WRITE_CHANNEL";

  /** The ownership index EXO-90190 adds under 1.0.0-48 and EXO-90613 offers again under 1.0.0-54. */
  private static final String OWNERSHIP_INDEX           = "IDX_CALDAV_CALENDAR_SYNC_ORIGIN";

  /** The pairs table the ownership index is on. */
  private static final String CALENDAR_SYNC_TABLE       = "CALDAV_CALENDAR_SYNC";

  /** The Liquibase namespace the changelog is written in. */
  private static final String LIQUIBASE_NS              = "http://www.liquibase.org/xml/ns/dbchangelog";

  /** Where an earlier history of the changelog is written, under the same logical path. */
  @TempDir
  Path                        history;

  private Connection          connection;

  /**
   * Opens a database of this test's own, named after the test so a parallel
   * run cannot share one.
   *
   * @throws Exception when the in-memory database cannot be opened
   */
  @BeforeEach
  public void openDatabase() throws Exception {
    connection = DriverManager.getConnection("jdbc:hsqldb:mem:caldav-changelog-" + System.nanoTime(), "sa", "");
  }

  /**
   * Drops the database, so one test's schema is never another's starting
   * point.
   *
   * @throws Exception when the database cannot be shut down
   */
  @AfterEach
  public void dropDatabase() throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SHUTDOWN");
    }
    connection.close();
  }

  /**
   * The whole changelog applies.
   *
   * @throws Exception when a changeset cannot be applied
   */
  @Test
  public void theChangelogApplies() throws Exception {
    update();

    assertTrue(tableExists("CALDAV_SERVER"), "the registry table must exist once the changelog has run");
    assertTrue(tableExists("CALDAV_CALENDAR_SYNC"), "and so must the calendar pairs");
    assertTrue(tableExists("CALDAV_OBJECT_SYNC"), "and the object mappings");
    // 1.0.0-12 dropped it, and a changelog that applies must have actually
    // done so — a dropColumn silently marked as ran would leave the column
    // behind and nobody would notice until the next reader of it.
    assertFalse(columnExists("CALDAV_OBJECT_SYNC", "PUSHED_HASH"),
                "the digest column 1.0.0-12 dropped must be gone");
    assertTrue(tableExists(CONNECTION_TABLE), "and the connection identities EXO-90243 records");
  }

  /**
   * The whole changelog rolls back, and re-applies onto the schema the
   * rollback left.
   *
   * <p>
   * This is what catches the changeset that ships with no usable rollback —
   * {@code update} and {@code dropIndex} have none of their own, and an empty
   * {@code <rollback/>} suppresses the automatic rollback of every other
   * change sharing its changeset.
   *
   * @throws Exception when a changeset cannot be applied or rolled back
   */
  @Test
  public void theChangelogRollsBackAndReapplies() throws Exception {
    update();
    rollbackEverything();

    assertFalse(tableExists("CALDAV_SERVER"), "rolling everything back must leave no registry table");
    assertFalse(tableExists(CONNECTION_TABLE), "nor a connection-identity table");

    update();

    assertTrue(tableExists("CALDAV_SERVER"), "and re-applying from nothing must rebuild it");
    assertTrue(columnExists("CALDAV_SERVER", ANSWER_LINKS_COLUMN), "with every column it carries");
    for (String column : QUIRK_COLUMNS) {
      assertTrue(columnExists("CALDAV_SERVER", column), "including " + column);
    }
    assertTrue(columnExists("CALDAV_SERVER", SETTINGS_UPDATED_COLUMN), "including the copy-settings stamp");
    assertTrue(columnExists("CALDAV_CALENDAR_SYNC", SETTINGS_APPLIED_COLUMN), "and the one the pair applies it with");
    assertTrue(columnExists("CALDAV_SERVER", MIRROR_TARGET_COLUMN), "and " + MIRROR_TARGET_COLUMN);
    assertEquals(List.of("SERVER_ID", "PRINCIPAL"),
                 indexColumns(CONNECTION_TABLE, "IDX_CALDAV_CONNECTION_PRINCIPAL"),
                 "and the connection identities with the index their lookup needs");
    assertEquals(List.of("SERVER_ID", "ORIGIN", "LOCAL_CALENDAR_SYNC_UID"),
                 indexColumns("CALDAV_CALENDAR_SYNC", "IDX_CALDAV_CALENDAR_SYNC_ORIGIN"),
                 "and the pairs with the index the deployment-wide ownership question needs");
  }

  /**
   * The deployment-wide ownership index (1.0.0-48) exists with its columns in
   * lookup order, and its rollback removes it without touching the table.
   *
   * @throws Exception when a changeset cannot be applied or rolled back
   */
  @Test
  public void theOwnershipIndexLeadsWithTheServerAndRollsBackAlone() throws Exception {
    update();
    assertEquals(List.of("SERVER_ID", "ORIGIN", "LOCAL_CALENDAR_SYNC_UID"),
                 indexColumns("CALDAV_CALENDAR_SYNC", "IDX_CALDAV_CALENDAR_SYNC_ORIGIN"));

    rollbackCount(changesetsFrom("1.0.0-48"));

    assertTrue(indexColumns("CALDAV_CALENDAR_SYNC", "IDX_CALDAV_CALENDAR_SYNC_ORIGIN").isEmpty(),
               "rolling 1.0.0-48 back must drop the index");
    assertTrue(tableExists("CALDAV_CALENDAR_SYNC"), "and leave the table");
  }

  /**
   * <b>One identity per user, and one principal may be many users'.</b>
   *
   * <p>
   * The two constraints EXO-90243's table rests on, asked of the database the
   * changelog built rather than of the entity. The unique index on the user
   * is what makes a user who reconnects replace their row and two nodes
   * recording one user collide instead of writing two; the lookup index on
   * (server, principal) must NOT be unique, because two eXo users on one login
   * is the legitimate shape the shared-account warning reports. And the
   * principal is NOT NULL: a row saying nothing about who the account is would
   * be a row every reader has to guess about.
   *
   * @throws Exception when a changeset cannot be applied or a row not written
   */
  @Test
  public void aConnectionIdentityIsOneRowPerUserAndOnePrincipalMayBeSeveralUsers() throws Exception {
    update();

    assertEquals(List.of("USER_IDENTITY_ID"), indexColumns(CONNECTION_TABLE, "UQ_CALDAV_CONNECTION_USER"));
    assertEquals(0, nullableFlag(CONNECTION_TABLE, "PRINCIPAL"), "the principal must be NOT NULL");
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO CALDAV_CONNECTION (ID, USER_IDENTITY_ID, SERVER_ID, PRINCIPAL) "
          + "VALUES (1, 5, 1, '/dav/pal/alice@stalwart.local')");
      statement.executeUpdate("INSERT INTO CALDAV_CONNECTION (ID, USER_IDENTITY_ID, SERVER_ID, PRINCIPAL) "
          + "VALUES (2, 6, 1, '/dav/pal/alice@stalwart.local')");
      java.sql.SQLException refused = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                                                                                    () -> statement.executeUpdate("INSERT INTO CALDAV_CONNECTION"
                                                                                        + " (ID, USER_IDENTITY_ID, SERVER_ID, PRINCIPAL)"
                                                                                        + " VALUES (3, 5, 2, '/dav/pal/other')"));
      assertTrue(refused.getSQLState().startsWith("23"), "a second identity for one user is an integrity violation");
      try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM CALDAV_CONNECTION"
          + " WHERE SERVER_ID = 1 AND PRINCIPAL = '/dav/pal/alice@stalwart.local'")) {
        assertTrue(rows.next());
        assertEquals(2, rows.getInt(1), "two users on one login are both recorded");
      }
    }
  }

  /**
   * A pending subscription is one row per colleague, server and container,
   * and belongs to no other table: no foreign key, because the colleague it
   * names holds no pair for a calendar somebody shared with them - a row for
   * a user with no CALDAV_CALENDAR_SYNC row inserts, and a second row for the
   * same colleague, server and container is an integrity violation (EXO-90277).
   *
   * @throws Exception when a changeset cannot be applied or the rows not written
   */
  @Test
  public void aPendingSubscriptionIsOneRowPerColleagueServerAndContainerAndNeedsNoPair() throws Exception {
    update();

    assertEquals(List.of("USER_IDENTITY_ID", "SERVER_ID", "CONTAINER_UID"),
                 indexColumns(PENDING_SUBSCRIPTION_TABLE, "UQ_CALDAV_PENDING_SUBSCRIPTION"));
    assertEquals(List.of("ATTEMPTS"), indexColumns(PENDING_SUBSCRIPTION_TABLE, "IDX_CALDAV_PENDING_SUBSCRIPTION_ATTEMPTS"));
    assertEquals(0, nullableFlag(PENDING_SUBSCRIPTION_TABLE, "CONTAINER_UID"), "the container must be NOT NULL");
    assertEquals(0, nullableFlag(PENDING_SUBSCRIPTION_TABLE, "KIND"), "the kind must be NOT NULL");
    try (ResultSet keys = connection.getMetaData().getImportedKeys(null, null, PENDING_SUBSCRIPTION_TABLE)) {
      assertFalse(keys.next(), "no foreign key: the sharee holds no pair to point at");
    }
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO CALDAV_PENDING_SUBSCRIPTION (ID, USER_IDENTITY_ID, SERVER_ID, CONTAINER_UID, KIND) "
          + "VALUES (1, 77, 5, 'exo-cal-shared', 'SUBSCRIBE')");
      try (ResultSet rows = statement.executeQuery("SELECT ATTEMPTS FROM CALDAV_PENDING_SUBSCRIPTION WHERE ID = 1")) {
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1), "a row written without naming ATTEMPTS counts from zero");
      }
      java.sql.SQLException refused = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                                                                                    () -> statement.executeUpdate("INSERT INTO CALDAV_PENDING_SUBSCRIPTION"
                                                                                        + " (ID, USER_IDENTITY_ID, SERVER_ID, CONTAINER_UID, KIND)"
                                                                                        + " VALUES (2, 77, 5, 'exo-cal-shared', 'UNSUBSCRIBE')"));
      assertTrue(refused.getSQLState().startsWith("23"), "a second change for one colleague, server and container is an integrity violation");
      statement.executeUpdate("INSERT INTO CALDAV_PENDING_SUBSCRIPTION (ID, USER_IDENTITY_ID, SERVER_ID, CONTAINER_UID, KIND) "
          + "VALUES (3, 77, 6, 'exo-cal-shared', 'SUBSCRIBE')");
    }
  }

  /**
   * <b>The acceptance database starts again (EXO-90613).</b>
   *
   * <p>
   * The state ai-contribution-ft.meeds.io was left in: it ran
   * feature/ai-contribution up to 3830e43b, where 1.0.0-48 was EXO-90307's
   * WRITE_CHANNEL column, and has no ownership index. The history is rebuilt
   * from the current changelog and checked to record the very checksum the
   * server's log printed, so the replay is that database and not a likeness
   * of it. The current changelog must then validate there (the validCheckSum
   * on 1.0.0-48), must not add the column a second time (the precondition on
   * 1.0.0-53) and must leave the index in place (1.0.0-54).
   *
   * @throws Exception when a changelog cannot be applied or the catalogue read
   */
  @Test
  public void aDatabaseThatRanTheWriteChannelUnder48ValidatesAndGainsTheIndex() throws Exception {
    update(firstWriteChannelHistory());
    assertEquals(FIRST_HISTORY_48_CHECKSUM, recordedChecksum("1.0.0-48"),
                 "the replay must record what the acceptance server recorded, or it proves nothing about it");
    assertTrue(columnExists("CALDAV_SERVER", WRITE_CHANNEL_COLUMN), "that history holds the column");
    assertTrue(indexColumns(CALENDAR_SYNC_TABLE, OWNERSHIP_INDEX).isEmpty(), "and not the index");

    update();

    assertEquals(List.of("SERVER_ID", "ORIGIN", "LOCAL_CALENDAR_SYNC_UID"), indexColumns(CALENDAR_SYNC_TABLE, OWNERSHIP_INDEX),
                 "the ownership index must exist once the current changelog has run");
    assertEquals("MARK_RAN", execType("1.0.0-53"), "the column is already there, so 1.0.0-53 must only be marked");
    assertEquals("EXECUTED", execType("1.0.0-54"), "and the index was missing, so 1.0.0-54 must have created it");
    assertWriteChannelDefaultsToCaldav();

    rollbackCount(changesetsFrom("1.0.0-48"));
    assertTrue(indexColumns(CALENDAR_SYNC_TABLE, OWNERSHIP_INDEX).isEmpty(),
               "rolling back through 1.0.0-48 must drop the index 1.0.0-54 built there");
    assertFalse(columnExists("CALDAV_SERVER", WRITE_CHANNEL_COLUMN), "and the column 1.0.0-53 recognised");
    assertTrue(tableExists(CALENDAR_SYNC_TABLE), "and leave the table");
    update();
    assertEquals(List.of("SERVER_ID", "ORIGIN", "LOCAL_CALENDAR_SYNC_UID"), indexColumns(CALENDAR_SYNC_TABLE, OWNERSHIP_INDEX),
                 "and re-applying must build them again");
    assertEquals(INDEX_48_CHECKSUM, recordedChecksum("1.0.0-48"), "this time under the index history");
    assertWriteChannelDefaultsToCaldav();
  }

  /**
   * Rolling 1.0.0-54 back alone never drops the index, on any database: where
   * it only marked, the index is 1.0.0-48's, and where it built it, 1.0.0-48
   * is still recorded and its rollback is the one that drops it. An automatic
   * rollback here would drop 1.0.0-48's index on a fresh database, and make
   * 1.0.0-48's own rollback fail after it.
   *
   * @throws Exception when a changeset cannot be applied or rolled back
   */
  @Test
  public void rollingBack54AloneKeepsTheIndex() throws Exception {
    update();
    rollbackCount(1);
    assertEquals(List.of("SERVER_ID", "ORIGIN", "LOCAL_CALENDAR_SYNC_UID"), indexColumns(CALENDAR_SYNC_TABLE, OWNERSHIP_INDEX),
                 "on a fresh database the index is 1.0.0-48's and must survive");
    rollbackCount(changesetsFrom("1.0.0-48"));
    assertTrue(indexColumns(CALENDAR_SYNC_TABLE, OWNERSHIP_INDEX).isEmpty(), "until 1.0.0-48 itself is rolled back");
  }

  /**
   * <b>A database that ran EXO-90190's 1.0.0-48 before 1.0.0-53 existed</b>
   * (feature/ai-contribution between 07:38 and 11:06 on 2026-09-24, and
   * develop): it has the index and no column. The current changelog adds the
   * column through 1.0.0-53 and only marks 1.0.0-54, whose index is there.
   *
   * @throws Exception when a changelog cannot be applied or the catalogue read
   */
  @Test
  public void aDatabaseThatRanTheIndexUnder48GainsTheColumnAndKeepsOneIndex() throws Exception {
    update(historyOf(changelog -> {
      removeChangeSet(changelog, "1.0.0-53");
      removeChangeSet(changelog, "1.0.0-54");
    }));
    assertEquals(INDEX_48_CHECKSUM, recordedChecksum("1.0.0-48"));
    assertFalse(columnExists("CALDAV_SERVER", WRITE_CHANNEL_COLUMN), "that history has no column yet");

    update();

    assertEquals("EXECUTED", execType("1.0.0-53"), "so 1.0.0-53 must add it");
    assertEquals("MARK_RAN", execType("1.0.0-54"), "and 1.0.0-54 must find the index 1.0.0-48 built");
    assertEquals(List.of("SERVER_ID", "ORIGIN", "LOCAL_CALENDAR_SYNC_UID"), indexColumns(CALENDAR_SYNC_TABLE, OWNERSHIP_INDEX));
    assertWriteChannelDefaultsToCaldav();
  }

  /**
   * <b>A database that ran feature/ai-contribution as it stood before this
   * repair</b> (6f5a7428): 1.0.0-53 ran without its precondition. Adding one
   * must not move its checksum - the precondition is not part of it - so
   * that database validates, and 1.0.0-54 is only marked.
   *
   * @throws Exception when a changelog cannot be applied or the catalogue read
   */
  @Test
  public void aDatabaseThatRanTheUnguarded53StillValidates() throws Exception {
    update(historyOf(changelog -> {
      removeChangeSet(changelog, "1.0.0-54");
      removeChildren(changeSet(changelog, "1.0.0-53"), "preConditions");
    }));
    assertEquals(FIRST_HISTORY_48_CHECKSUM, recordedChecksum("1.0.0-53"));

    update();

    assertEquals("EXECUTED", execType("1.0.0-53"), "1.0.0-53 keeps the row it had");
    assertEquals("MARK_RAN", execType("1.0.0-54"));
    assertEquals(List.of("SERVER_ID", "ORIGIN", "LOCAL_CALENDAR_SYNC_UID"), indexColumns(CALENDAR_SYNC_TABLE, OWNERSHIP_INDEX));
  }

  /**
   * On a fresh database 1.0.0-48 builds the index and 1.0.0-53 the column,
   * so 1.0.0-54 has nothing to do - and the checksum every database recorded
   * for 1.0.0-48 is the one it still records.
   *
   * @throws Exception when the changelog cannot be applied or the catalogue read
   */
  @Test
  public void aFreshDatabaseBuildsTheIndexUnder48AndOnlyMarks54() throws Exception {
    update();

    assertEquals(INDEX_48_CHECKSUM, recordedChecksum("1.0.0-48"));
    assertEquals("EXECUTED", execType("1.0.0-53"));
    assertEquals("MARK_RAN", execType("1.0.0-54"));
    assertEquals(List.of("SERVER_ID", "ORIGIN", "LOCAL_CALENDAR_SYNC_UID"), indexColumns(CALENDAR_SYNC_TABLE, OWNERSHIP_INDEX));
    assertWriteChannelDefaultsToCaldav();
  }

  /**
   * The failure this repair answers, kept reproducible: the first history,
   * then a changelog whose 1.0.0-48 accepts only its own checksum, is refused
   * with the message the acceptance server logged. Without it, the test above
   * could pass because the replay silently failed to reproduce the incident.
   *
   * @throws Exception when a changelog cannot be written or applied
   */
  @Test
  public void withoutTheSecondChecksumTheFirstHistoryIsRefused() throws Exception {
    update(firstWriteChannelHistory());
    Path unrepaired = historyOf(changelog -> removeChildren(changeSet(changelog, "1.0.0-48"), "validCheckSum"));

    Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> update(unrepaired));
    Throwable refused = thrown;
    while (refused != null && !(refused instanceof ValidationFailedException)) {
      refused = refused.getCause();
    }
    assertNotNull(refused, "the refusal must be a validation failure, as on the acceptance server: " + thrown);
    assertTrue(refused.getMessage().contains("1.0.0-48::caldav was: " + FIRST_HISTORY_48_CHECKSUM + " but is now: "
        + INDEX_48_CHECKSUM), refused.getMessage());
  }

  /**
   * A server row written without naming WRITE_CHANNEL goes through the door
   * every existing registration used, CALDAV.
   *
   * @throws Exception when the row cannot be written or read
   */
  private void assertWriteChannelDefaultsToCaldav() throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO CALDAV_SERVER (ID, PROVIDER_NAME, NAME, SERVER_URL, ACTIVE) "
          + "VALUES (9, 'agenda.caldavCalendar.9', 'Bluemind', 'https://bluemind.example.invalid/dav/', TRUE)");
      try (ResultSet rows = statement.executeQuery("SELECT " + WRITE_CHANNEL_COLUMN + " FROM CALDAV_SERVER WHERE ID = 9")) {
        assertTrue(rows.next(), "the row must have been written");
        assertEquals("CALDAV", rows.getString(1));
      }
    }
    assertEquals(0, nullableFlag("CALDAV_SERVER", WRITE_CHANNEL_COLUMN), "and the column must be NOT NULL");
  }

  /**
   * The changelog as feature/ai-contribution held it up to 3830e43b: 1.0.0-48
   * is EXO-90307's WRITE_CHANNEL addColumn - the body 1.0.0-53 carries today,
   * without its precondition - in 1.0.0-48's place, and neither 1.0.0-53 nor
   * 1.0.0-54 exists.
   *
   * @return the root the history is written under
   * @throws Exception when the changelog cannot be read or written
   */
  private Path firstWriteChannelHistory() throws Exception {
    return historyOf(changelog -> {
      Element writeChannel = changeSet(changelog, "1.0.0-53");
      Element ownershipIndex = changeSet(changelog, "1.0.0-48");
      removeChildren(writeChannel, "preConditions");
      writeChannel.setAttribute("id", "1.0.0-48");
      ownershipIndex.getParentNode().replaceChild(writeChannel, ownershipIndex);
      removeChangeSet(changelog, "1.0.0-54");
    });
  }

  /**
   * Writes an earlier history of the changelog, derived from the current one
   * by the given edit, under the logical path the webapp uses - the path is
   * part of a changeset's identity, so the database records exactly the rows
   * that history recorded.
   *
   * @param edit what the history differs by
   * @return the root the history is written under
   * @throws Exception when the changelog cannot be read or written
   */
  private Path historyOf(Consumer<Document> edit) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    Document changelog;
    try (InputStream current = getClass().getClassLoader().getResourceAsStream(CHANGELOG)) {
      assertNotNull(current, CHANGELOG + " must be on the classpath");
      changelog = factory.newDocumentBuilder().parse(current);
    }
    edit.accept(changelog);
    Path root = Files.createTempDirectory(history, "changelog");
    Path file = root.resolve(CHANGELOG);
    Files.createDirectories(file.getParent());
    TransformerFactory.newInstance().newTransformer().transform(new DOMSource(changelog), new StreamResult(file.toFile()));
    return root;
  }

  /**
   * One changeset of a changelog, by id.
   *
   * @param changelog the changelog
   * @param id the changeset id
   * @return the element
   */
  private static Element changeSet(Document changelog, String id) {
    NodeList changeSets = changelog.getElementsByTagNameNS(LIQUIBASE_NS, "changeSet");
    for (int i = 0; i < changeSets.getLength(); i++) {
      Element changeSet = (Element) changeSets.item(i);
      if (id.equals(changeSet.getAttribute("id"))) {
        return changeSet;
      }
    }
    throw new AssertionError("no changeset " + id + " in " + CHANGELOG);
  }

  /**
   * Removes one changeset from a changelog, when the changelog holds it.
   *
   * <p>
   * Tolerant on purpose, like {@link #removeChildren(Element, String)}: a
   * history is what an earlier changelog held, and the current one losing a
   * guard must fail the test on the database's behaviour, not on the
   * derivation of the history. The recorded checksums the tests assert are
   * what tie each history to the database it stands for.
   *
   * @param changelog the changelog
   * @param id the changeset id
   */
  private static void removeChangeSet(Document changelog, String id) {
    NodeList changeSets = changelog.getElementsByTagNameNS(LIQUIBASE_NS, "changeSet");
    for (int i = 0; i < changeSets.getLength(); i++) {
      Element changeSet = (Element) changeSets.item(i);
      if (id.equals(changeSet.getAttribute("id"))) {
        changeSet.getParentNode().removeChild(changeSet);
        return;
      }
    }
  }

  /**
   * Removes every child element of the given name from a changeset, if it
   * has any.
   *
   * @param changeSet the changeset
   * @param name the local name of the children to remove
   */
  private static void removeChildren(Element changeSet, String name) {
    NodeList children = changeSet.getElementsByTagNameNS(LIQUIBASE_NS, name);
    List<Node> found = new ArrayList<>();
    for (int i = 0; i < children.getLength(); i++) {
      found.add(children.item(i));
    }
    found.forEach(child -> child.getParentNode().removeChild(child));
  }

  /**
   * The checksum the database recorded for a changeset.
   *
   * @param id the changeset id
   * @return the MD5SUM column
   * @throws Exception when the changelog table cannot be read
   */
  private String recordedChecksum(String id) throws Exception {
    return changelogColumn(id, "MD5SUM");
  }

  /**
   * How the database recorded a changeset: EXECUTED, or MARK_RAN when a
   * precondition found its work already done.
   *
   * @param id the changeset id
   * @return the EXECTYPE column
   * @throws Exception when the changelog table cannot be read
   */
  private String execType(String id) throws Exception {
    return changelogColumn(id, "EXECTYPE");
  }

  /**
   * One column of a changeset's DATABASECHANGELOG row.
   *
   * @param id the changeset id
   * @param column the column
   * @return its value
   * @throws Exception when the row does not exist or cannot be read
   */
  private String changelogColumn(String id, String column) throws Exception {
    try (Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery("SELECT " + column + " FROM DATABASECHANGELOG WHERE AUTHOR = 'caldav' AND ID = '"
             + id + "' AND FILENAME = '" + CHANGELOG + "'")) {
      assertTrue(rows.next(), "changeset " + id + " must have a row");
      String value = rows.getString(1);
      assertFalse(rows.next(), "and only one");
      return value;
    }
  }

  /**
   * The columns of one index, in index order.
   *
   * @param table name of the table
   * @param index name of the index
   * @return the column names, empty when the index does not exist
   * @throws Exception when the catalogue cannot be read
   */
  private List<String> indexColumns(String table, String index) throws Exception {
    java.util.Map<Short, String> columns = new java.util.TreeMap<>();
    try (ResultSet rows = connection.getMetaData().getIndexInfo(null, null, table.toUpperCase(Locale.ROOT), false, false)) {
      while (rows.next()) {
        if (index.equalsIgnoreCase(rows.getString("INDEX_NAME"))) {
          columns.put(rows.getShort("ORDINAL_POSITION"), rows.getString("COLUMN_NAME"));
        }
      }
    }
    return new ArrayList<>(columns.values());
  }

  /**
   * <b>A row that says nothing about this server's behaviour defers to the
   * deployment.</b>
   *
   * <p>
   * The three columns EXO-89771 adds are nullable with no default, and that is
   * what makes the change behaviour-neutral: NULL means "this row has never been
   * asked", and the deployment-wide
   * {@code exo.agenda.caldav.mirror.ignoredProperties} /
   * {@code ...droppedProperties} go on deciding for it. A DEFAULT '' added in an
   * edit would collapse null and empty into one answer and silence the global
   * lever on every upgraded instance — which, on the rig, brings the BlueMind
   * repair loop straight back. No Java test can see that; this one can.
   *
   * @throws Exception when a changeset cannot be applied or the row not written
   */
  @Test
  public void aServerRowSaysNothingAboutItsBehaviourUntilItIsAsked() throws Exception {
    update();

    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO CALDAV_SERVER (ID, PROVIDER_NAME, NAME, SERVER_URL, ACTIVE) "
          + "VALUES (2, 'agenda.caldavCalendar.2', 'Bluemind', 'https://caldav.example.invalid/dav/', TRUE)");
      for (String column : QUIRK_COLUMNS) {
        try (ResultSet rows = statement.executeQuery("SELECT " + column + " FROM CALDAV_SERVER WHERE ID = 2")) {
          assertTrue(rows.next(), "the row must have been written");
          rows.getString(1);
          assertTrue(rows.wasNull(), column + " must start null, so the deployment-wide setting still decides");
        }
      }
    }
    for (String column : QUIRK_COLUMNS) {
      assertEquals(1, nullableFlag("CALDAV_SERVER", column), "and " + column + " must stay nullable");
    }
  }

  /**
   * <b>A deployment that upgrades has nothing to apply.</b>
   *
   * <p>
   * The two columns EXO-89759 adds are nullable with no default, and that is the
   * whole upgrade story: null on the registration means no administrator has
   * changed a copy-governing setting yet, null on the pair means it has applied
   * none, and a pair that is not behind runs no round. A DEFAULT of the current
   * timestamp added in an edit — the obvious-looking "so it is never null" —
   * would, on the first sweep after an upgrade, have every connected account in
   * the deployment fetch and compare every copy it holds at once. No Java test
   * can see that; this one can.
   *
   * @throws Exception when a changeset cannot be applied or a row not written
   */
  @Test
  public void anUpgradedDeploymentStartsWithNothingToApply() throws Exception {
    update();

    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO CALDAV_SERVER (ID, PROVIDER_NAME, NAME, SERVER_URL, ACTIVE) "
          + "VALUES (3, 'agenda.caldavCalendar.3', 'Bluemind', 'https://caldav.example.invalid/dav/', TRUE)");
      try (ResultSet rows = statement.executeQuery("SELECT " + SETTINGS_UPDATED_COLUMN
          + " FROM CALDAV_SERVER WHERE ID = 3")) {
        assertTrue(rows.next(), "the row must have been written");
        rows.getTimestamp(1);
        assertTrue(rows.wasNull(), "a server nobody has reconfigured owes its copies nothing");
      }
      statement.executeUpdate("INSERT INTO CALDAV_CALENDAR_SYNC "
          + "(ID, USER_IDENTITY_ID, SERVER_ID, REMOTE_HREF, ORIGIN, STATUS) "
          + "VALUES (3, 42, 3, '/dav/calendars/john/exo-meetings', 'MIRROR', 'ACTIVE')");
      try (ResultSet rows = statement.executeQuery("SELECT " + SETTINGS_APPLIED_COLUMN
          + " FROM CALDAV_CALENDAR_SYNC WHERE ID = 3")) {
        assertTrue(rows.next(), "the pair must have been written");
        rows.getTimestamp(1);
        assertTrue(rows.wasNull(), "and a pair that has applied nothing must say so rather than claim a time");
      }
    }
    assertEquals(1, nullableFlag("CALDAV_SERVER", SETTINGS_UPDATED_COLUMN), "the server stamp must stay nullable");
    assertEquals(1, nullableFlag("CALDAV_CALENDAR_SYNC", SETTINGS_APPLIED_COLUMN), "and so must the applied one");
  }

  /**
   * <b>A row that says nothing about the answer links carries them.</b>
   *
   * <p>
   * The column is added with DEFAULT TRUE and NOT NULL, which is what makes
   * EXO-89757 behaviour-neutral: it is the same DDL clause that backfills the
   * rows an existing deployment already holds, so every server declared
   * before the setting existed goes on writing the links it writes today. A
   * DEFAULT lost in an edit would silently strip the answer links from every
   * copy on every upgraded instance, and no Java test could see it.
   *
   * @throws Exception when a changeset cannot be applied or the row not
   *           written
   */
  @Test
  public void aServerRowDefaultsToWritingTheAnswerLinks() throws Exception {
    update();

    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO CALDAV_SERVER (ID, PROVIDER_NAME, NAME, SERVER_URL, ACTIVE) "
          + "VALUES (1, 'agenda.caldavCalendar', 'Stalwart', 'http://localhost:8888/dav/cal/{username}/', TRUE)");
      try (ResultSet rows = statement.executeQuery("SELECT " + ANSWER_LINKS_COLUMN + " FROM CALDAV_SERVER WHERE ID = 1")) {
        assertTrue(rows.next(), "the row must have been written");
        assertTrue(rows.getBoolean(1), "a row that says nothing about the answer links must carry them");
      }
    }
    assertEquals(0, nullableFlag("CALDAV_SERVER", ANSWER_LINKS_COLUMN), "and the column must be NOT NULL");
  }

  /**
   * <b>A server row that says nothing about its destination writes copies where
   * it has always written them.</b>
   *
   * <p>
   * The column EXO-89760 appends is NOT NULL DEFAULT 'DEDICATED_CALENDAR', and
   * that DEFAULT is the whole upgrade story: it is the same DDL clause that
   * backfills every row an existing deployment already holds, so every server
   * declared before this setting existed goes on copying meetings into eXo's
   * own calendar. A DEFAULT lost in an edit would either fail the ALTER on a
   * populated table or, worse on a database that tolerates it, leave every
   * upgraded row with no destination at all — and no Java test can see either.
   *
   * @throws Exception when a changeset cannot be applied or the row not written
   */
  @Test
  public void aServerRowDefaultsToTheDedicatedCalendar() throws Exception {
    update();

    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO CALDAV_SERVER (ID, PROVIDER_NAME, NAME, SERVER_URL, ACTIVE) "
          + "VALUES (3, 'agenda.caldavCalendar.3', 'Legacy', 'https://legacy.example.invalid/dav/', TRUE)");
      try (ResultSet rows = statement.executeQuery("SELECT " + MIRROR_TARGET_COLUMN + " FROM CALDAV_SERVER WHERE ID = 3")) {
        assertTrue(rows.next(), "the row must have been written");
        assertEquals("DEDICATED_CALENDAR",
                     rows.getString(1),
                     "a row that says nothing about its destination must copy meetings where it always did");
      }
    }
    assertEquals(0, nullableFlag("CALDAV_SERVER", MIRROR_TARGET_COLUMN), "and the column must be NOT NULL");
  }

  /**
   * The credentials-provider column, same promise as the two above: a server
   * declared before it existed authenticates as it always did, with the user's own
   * credentials. A DEFAULT lost in an edit would leave every upgraded row with no
   * provider at all, which no Java test can see.
   *
   * @throws Exception when a changeset cannot be applied or the row not written
   */
  @Test
  public void aServerRowDefaultsToThePersonalProvider() throws Exception {
    update();

    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO CALDAV_SERVER (ID, PROVIDER_NAME, NAME, SERVER_URL, ACTIVE) "
          + "VALUES (4, 'agenda.caldavCalendar.4', 'Legacy', 'https://legacy.example.invalid/dav/', TRUE)");
      try (ResultSet rows = statement.executeQuery("SELECT " + AUTH_PROVIDER_COLUMN + " FROM CALDAV_SERVER WHERE ID = 4")) {
        assertTrue(rows.next(), "the row must have been written");
        assertEquals("personal", rows.getString(1), "a row that says nothing about its provider authenticates as the user");
      }
    }
    assertEquals(0, nullableFlag("CALDAV_SERVER", AUTH_PROVIDER_COLUMN), "and the column must be NOT NULL");
  }

  /**
   * Every column the entities map is a column the changelog creates.
   *
   * <p>
   * <b>The gap between the two halves of a schema change.</b> The changelog
   * writes the table; the JPA entity says what its columns are called; and
   * nothing until now compared them. A column named {@code OBJECT_SYNC_ID} in
   * one and {@code OBJECTSYNC_ID} in the other passes the changelog test (the
   * changeset applies), passes the repository test (the query names a property
   * the entity has) and fails at the first read, in production, with a message
   * about a column that does not exist.
   *
   * <p>
   * Nothing here names an entity: the list is {@code jpa-entities.idx}, the
   * same file the platform builds its EntityManager from, so an entity added
   * next year is covered by having been registered — which it has to be anyway.
   *
   * @throws Exception when the changelog cannot be applied or the catalogue
   *           read
   */
  @Test
  public void everyColumnTheEntitiesMapIsOneTheChangelogCreates() throws Exception {
    update();

    List<String> missing = new ArrayList<>();
    for (String entityName : registeredEntities()) {
      Class<?> entity = Class.forName(entityName);
      Table table = entity.getAnnotation(Table.class);
      assertNotNull(table, entity.getSimpleName() + " is registered as an entity but names no table");
      for (java.lang.reflect.Field field : entity.getDeclaredFields()) {
        Column column = field.getAnnotation(Column.class);
        if (column == null || column.name().isEmpty()) {
          continue;
        }
        if (!columnExists(table.name(), column.name())) {
          missing.add(table.name() + "." + column.name() + " (" + entity.getSimpleName() + "." + field.getName() + ")");
        }
      }
    }
    assertTrue(missing.isEmpty(), "columns the entities map that the changelog does not create: " + missing);
  }

  /**
   * The entities the platform registers, read from the index it reads.
   *
   * @return the fully-qualified names, in the order the file lists them
   * @throws Exception when the index cannot be read
   */
  private List<String> registeredEntities() throws Exception {
    List<String> names = new ArrayList<>();
    try (InputStream index = getClass().getClassLoader().getResourceAsStream(ENTITY_INDEX)) {
      assertNotNull(index, ENTITY_INDEX + " must be on the classpath; the platform reads it to build its EntityManager");
      for (String line : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
        if (!line.isBlank()) {
          names.add(line.trim());
        }
      }
    }
    assertTrue(names.size() >= 4, "expected the index to register entities, found " + names);
    return names;
  }

  /**
   * Applies every changeset the changelog holds.
   *
   * @throws Exception when a changeset cannot be applied
   */
  private void update() throws Exception {
    liquibase().update(new Contexts(), new LabelExpression());
  }

  /**
   * Applies an earlier history of the changelog, written by
   * {@link #historyOf(Consumer)}.
   *
   * @param root the root the history is written under
   * @throws Exception when a changeset cannot be applied
   */
  private void update(Path root) throws Exception {
    liquibase(new DirectoryResourceAccessor(root)).update(new Contexts(), new LabelExpression());
  }

  /**
   * Rolls back the given number of the most recently applied changesets.
   *
   * @param count how many
   * @throws Exception when a rollback fails
   */
  private void rollbackCount(int count) throws Exception {
    liquibase().rollback(count, new Contexts(), new LabelExpression());
  }

  /**
   * How many changesets ran from the given one onward, that one included: the
   * count that rolls the database back to just before it, however many
   * changesets the changelog runs after it.
   *
   * @param id the changeset id
   * @return the count
   * @throws Exception when the changelog table cannot be read
   */
  private int changesetsFrom(String id) throws Exception {
    try (Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ORDEREXECUTED >= "
             + "(SELECT ORDEREXECUTED FROM DATABASECHANGELOG WHERE AUTHOR = 'caldav' AND ID = '" + id + "')")) {
      rows.next();
      return rows.getInt(1);
    }
  }

  /**
   * Rolls back every changeset that has run, by asking for the state the
   * database was in before any of them existed.
   *
   * @throws Exception when a changeset cannot be rolled back
   */
  private void rollbackEverything() throws Exception {
    liquibase().rollback(new Date(0), new Contexts(), new LabelExpression());
  }

  /**
   * A Liquibase bound to this test's connection, reading the changelog off the
   * classpath exactly as the webapp's spring.liquibase.change-log does.
   *
   * <p>
   * Deliberately never closed: {@code Liquibase.close()} closes the
   * {@link Database} and with it the JDBC connection underneath, so a
   * try-with-resources here would leave every assertion after the first
   * update talking to a connection that no longer exists. The connection is
   * this class's to own, and {@link #dropDatabase()} is where it goes.
   *
   * @return the Liquibase instance
   * @throws Exception when the database implementation cannot be resolved
   */
  private Liquibase liquibase() throws Exception {
    return liquibase(new ClassLoaderResourceAccessor());
  }

  /**
   * A Liquibase bound to this test's connection, reading the changelog from
   * the given accessor under the webapp's logical path. Never closed, for the
   * reason {@link #liquibase()} gives.
   *
   * @param resources where the changelog is read from
   * @return the Liquibase instance
   * @throws Exception when the database implementation cannot be resolved
   */
  private Liquibase liquibase(ResourceAccessor resources) throws Exception {
    Database database = DatabaseFactory.getInstance()
                                       .findCorrectDatabaseImplementation(new JdbcConnection(connection));
    return new Liquibase(CHANGELOG, resources, database);
  }

  /**
   * Whether a table exists, asked of the database's own catalogue rather than
   * of a query that could fail for another reason.
   *
   * @param table name of the table
   * @return true when the database holds it
   * @throws Exception when the catalogue cannot be read
   */
  private boolean tableExists(String table) throws Exception {
    try (ResultSet tables = connection.getMetaData().getTables(null, null, table.toUpperCase(Locale.ROOT), null)) {
      return tables.next();
    }
  }

  /**
   * Whether a column exists on a table.
   *
   * @param table name of the table
   * @param column name of the column
   * @return true when the database holds it
   * @throws Exception when the catalogue cannot be read
   */
  private boolean columnExists(String table, String column) throws Exception {
    try (ResultSet columns = connection.getMetaData().getColumns(null,
                                                                 null,
                                                                 table.toUpperCase(Locale.ROOT),
                                                                 column.toUpperCase(Locale.ROOT))) {
      return columns.next();
    }
  }

  /**
   * The catalogue's nullability flag for a column: 0 when the column is
   * declared NOT NULL.
   *
   * @param table name of the table
   * @param column name of the column
   * @return the JDBC nullability flag
   * @throws Exception when the catalogue cannot be read
   */
  private int nullableFlag(String table, String column) throws Exception {
    try (ResultSet columns = connection.getMetaData().getColumns(null,
                                                                 null,
                                                                 table.toUpperCase(Locale.ROOT),
                                                                 column.toUpperCase(Locale.ROOT))) {
      assertTrue(columns.next(), "the column must exist to have a nullability");
      return columns.getInt("NULLABLE");
    }
  }
}
