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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Table;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

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
 * Nothing in this class names a changeset, on purpose. It runs whatever the
 * changelog holds, so the changeset added next year is covered by the same
 * three assertions with nobody remembering to extend them: it applies, it
 * rolls back, and applying it again from nothing lands on the same schema.
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

  /** The index the platform builds its EntityManager from. */
  private static final String ENTITY_INDEX        = "jpa-entities.idx";

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

    update();

    assertTrue(tableExists("CALDAV_SERVER"), "and re-applying from nothing must rebuild it");
    assertTrue(columnExists("CALDAV_SERVER", ANSWER_LINKS_COLUMN), "with every column it carries");
    for (String column : QUIRK_COLUMNS) {
      assertTrue(columnExists("CALDAV_SERVER", column), "including " + column);
    }
    assertTrue(columnExists("CALDAV_SERVER", SETTINGS_UPDATED_COLUMN), "including the copy-settings stamp");
    assertTrue(columnExists("CALDAV_CALENDAR_SYNC", SETTINGS_APPLIED_COLUMN), "and the one the pair applies it with");
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
    Database database = DatabaseFactory.getInstance()
                                       .findCorrectDatabaseImplementation(new JdbcConnection(connection));
    return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
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
