/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.caldav.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which columns a write to a server registration names, asked of a real
 * Hibernate over a real database, the way {@link CalendarSyncWriteColumnsTest}
 * asks it of a calendar pair.
 *
 * <p>
 * {@code CALDAV_SERVER} has no version column and three writers doing a
 * read-modify-save: an administrator's save, and the inbound pass recording
 * observed quirks and foreign writers. Without {@code @DynamicUpdate} each
 * UPDATE names every column from the snapshot its writer read, so one writer
 * reverts what another has just saved. The effect is visible only in the
 * statement, so the statement is what is read.
 */
public class CaldavServerWriteColumnsTest {

  /** Every statement this test's Hibernate was asked to run. */
  private static final List<String> STATEMENTS = new ArrayList<>();

  private SessionFactory            sessionFactory;

  /** The identifier the database gave the row every test writes to. */
  private Long                      serverId;

  /**
   * Builds a Hibernate over an in-memory database of this test's own, mapping
   * the one entity under examination and recording every statement it issues.
   */
  @BeforeEach
  public void openDatabase() {
    STATEMENTS.clear();
    sessionFactory = new Configuration().addAnnotatedClass(CaldavServerEntity.class)
                                        .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbcDriver")
                                        .setProperty("hibernate.connection.url",
                                                     "jdbc:hsqldb:mem:caldav-server-columns-" + System.nanoTime())
                                        .setProperty("hibernate.connection.username", "sa")
                                        .setProperty("hibernate.connection.password", "")
                                        .setProperty("hibernate.dialect", "org.hibernate.dialect.HSQLDialect")
                                        .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                                        .setProperty("hibernate.show_sql", "false")
                                        .setStatementInspector(new Recorder())
                                        .buildSessionFactory();
  }

  /**
   * Closes it, so one test's database is never another's starting point.
   */
  @AfterEach
  public void dropDatabase() {
    if (sessionFactory != null) {
      sessionFactory.close();
    }
  }

  /**
   * Recording a foreign writer writes that column and none an administrator
   * saves.
   */
  @Test
  public void recordingAForeignWriterWritesOnlyThatColumn() {
    givenAStoredServer();

    sessionFactory.inTransaction(session -> {
      CaldavServerEntity row = session.find(CaldavServerEntity.class, serverId);
      row.setForeignWriters("other.example.test@20355");
    });

    String update = update();
    assertTrue(update.contains("foreign_writers"), "the foreign writers must be written: " + update);
    assertFalse(update.contains("server_url"), "and nothing an administrator saves may be written with it: " + update);
    assertFalse(update.contains("ignored_properties"), "including the excused properties: " + update);
    assertFalse(update.contains("observed_quirks"), "or the other background column: " + update);
  }

  /**
   * And an administrator's save does not carry the foreign writers along with
   * it.
   */
  @Test
  public void anAdministratorSaveDoesNotCarryTheForeignWriters() {
    givenAStoredServer();

    sessionFactory.inTransaction(session -> {
      CaldavServerEntity row = session.find(CaldavServerEntity.class, serverId);
      row.setName("Renamed");
    });

    String update = update();
    assertTrue(update.contains("name"), "the name must be written: " + update);
    assertFalse(update.contains("foreign_writers"), "but never the evidence it did not touch: " + update);
    assertFalse(update.contains("observed_quirks"), "nor the observed quirks: " + update);
  }

  /**
   * Writes one registration and forgets every statement that took, so that the
   * assertions read only the write under test.
   */
  private void givenAStoredServer() {
    CaldavServerEntity server = new CaldavServerEntity();
    server.setProviderName("agenda.caldavCalendar.7");
    server.setName("BlueMind");
    server.setDescription("d");
    server.setServerUrl("https://bm.example.test/dav/");
    server.setActive(true);
    server.setIgnoredProperties("X-ALT-DESC");
    server.setObservedQuirks("DROPPED:CONFERENCE=3@20350");
    server.setForeignWriters("seen.example.test@20350");
    sessionFactory.inTransaction(session -> session.persist(server));
    serverId = server.getId();
    STATEMENTS.clear();
  }

  /**
   * The one UPDATE the transaction under test issued.
   *
   * @return the statement, lower-cased so an assertion does not depend on how a
   *         dialect spells its keywords
   */
  private String update() {
    List<String> updates = STATEMENTS.stream()
                                     .map(statement -> statement.toLowerCase(Locale.ROOT))
                                     .filter(statement -> statement.startsWith("update"))
                                     .toList();
    assertEquals(1, updates.size(), "exactly one update was expected, got " + updates);
    return updates.get(0);
  }

  /** Keeps every statement Hibernate prepares, unchanged. */
  private static final class Recorder implements StatementInspector {

    /**
     * @param sql the statement Hibernate is about to run
     * @return the same statement, unchanged
     */
    @Override
    public String inspect(String sql) {
      STATEMENTS.add(sql);
      return sql;
    }
  }
}
