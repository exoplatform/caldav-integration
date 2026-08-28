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
package org.exoplatform.caldav.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;

/**
 * Which columns a write to a calendar pair actually names, asked of a real
 * Hibernate over a real database (EXO-89759).
 *
 * <p>
 * <b>Why this test exists at all.</b> {@code CALDAV_CALENDAR_SYNC} carries no
 * version column and has several writers doing read-modify-save over the whole
 * row: the sync pass writes the token, the ctag and the two timestamps; the
 * failure counter is written on its own; and this task adds a third, the mirror
 * verification stamping {@code COPY_SETTINGS_APPLIED} at the end of a round that
 * can take minutes. Without {@code @DynamicUpdate} Hibernate emits an UPDATE
 * naming <b>every</b> column, filled from the snapshot the writer read when it
 * started — so the stamp written by a long round silently erases the sync that
 * finished in the meantime, and the sync erases the stamp. Nothing throws.
 * Nothing logs. The setting simply never applies, intermittently.
 *
 * <p>
 * <b>And why it has to run real SQL.</b> The annotation's effect is invisible
 * from Java: the entity behaves identically, the DTO round-trips identically,
 * and every mock-based test passes with it and without it. The only place the
 * difference exists is the statement, so the statement is what is read — through
 * Hibernate's own {@link StatementInspector}, against HSQLDB, with the schema
 * Hibernate itself generates from the entity.
 *
 * <p>
 * <b>What it still cannot say.</b> This is HSQLDB and this is Hibernate's own
 * generated schema, not the Liquibase one — {@code ChangelogExecutionTest} is
 * where the changelog is executed. What this pins is the shape of the write, and
 * that is vendor-independent.
 */
public class CalendarSyncWriteColumnsTest {

  /** Every statement this test's Hibernate was asked to run. */
  private static final List<String> STATEMENTS = new ArrayList<>();

  private SessionFactory            sessionFactory;

  /** The identifier the database gave the row every test writes to. */
  private Long                      pairId;

  /**
   * Builds a Hibernate over an in-memory database of this test's own, mapping
   * the one entity under examination and recording every statement it issues.
   */
  @BeforeEach
  public void openDatabase() {
    STATEMENTS.clear();
    sessionFactory = new Configuration().addAnnotatedClass(CaldavCalendarSyncEntity.class)
                                        .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbcDriver")
                                        .setProperty("hibernate.connection.url",
                                                     "jdbc:hsqldb:mem:caldav-columns-" + System.nanoTime())
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
   * <b>Stamping the settings round writes the stamp and nothing else.</b>
   *
   * <p>
   * The write is done exactly as the verification pass does it — read the row
   * back, set the one field, save — and the statement it produces must name
   * {@code COPY_SETTINGS_APPLIED} and none of the columns another writer owns.
   * Remove {@code @DynamicUpdate} from the entity and this fails on the first
   * assertion below: the UPDATE names CTAG, SYNC_TOKEN and the rest, every one
   * of them a value read minutes ago.
   */
  @Test
  public void stampingASettingsRoundWritesOnlyTheStamp() {
    givenAStoredPair();

    sessionFactory.inTransaction(session -> {
      CaldavCalendarSyncEntity row = session.find(CaldavCalendarSyncEntity.class, pairId);
      row.setCopySettingsApplied(new Date(1_800_000_000_000L));
    });

    String update = update();
    assertTrue(update.contains("copy_settings_applied"), "the stamp must be written: " + update);
    assertFalse(update.contains("ctag"), "and nothing another writer owns may be written with it: " + update);
    assertFalse(update.contains("sync_token"), "including the sync token: " + update);
    assertFalse(update.contains("consecutive_failures"), "and the failure counter: " + update);
    assertFalse(update.contains("last_sync_end"), "and the sync timestamps: " + update);
  }

  /**
   * <b>And the sync pass, writing its own columns, does not erase the stamp.</b>
   *
   * <p>
   * The other direction of the same collision, and the one that would lose an
   * applied round rather than a sync: without the annotation this UPDATE carries
   * {@code COPY_SETTINGS_APPLIED} from a snapshot taken before the round
   * stamped it, and the mirror is told to run the whole comparison again.
   */
  @Test
  public void aSyncWriteDoesNotCarryTheStampAlongWithIt() {
    givenAStoredPair();

    sessionFactory.inTransaction(session -> {
      CaldavCalendarSyncEntity row = session.find(CaldavCalendarSyncEntity.class, pairId);
      row.setCtag("\"ctag-2\"");
      row.setLastSyncEnd(new Date(1_800_000_000_000L));
    });

    String update = update();
    assertTrue(update.contains("ctag"), "the ctag must be written: " + update);
    assertFalse(update.contains("copy_settings_applied"), "but never the stamp it did not touch: " + update);
  }

  /**
   * Writes one pair and forgets every statement that took, so that the
   * assertions read only the write under test.
   */
  private void givenAStoredPair() {
    CaldavCalendarSyncEntity pair = pair();
    sessionFactory.inTransaction(session -> session.persist(pair));
    pairId = pair.getId();
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

  /**
   * A pair with every column carrying a value, so that a statement writing one
   * it should not have written is visible rather than indistinguishable from a
   * null.
   *
   * @return the pair
   */
  private CaldavCalendarSyncEntity pair() {
    CaldavCalendarSyncEntity pair = new CaldavCalendarSyncEntity();
    pair.setUserIdentityId(42L);
    pair.setServerId(7L);
    pair.setLocalCalendarSyncUid(null);
    pair.setRemoteHref("/dav/calendars/john/exo-meetings");
    pair.setOrigin(SyncOrigin.MIRROR);
    pair.setSyncToken("token-1");
    pair.setCtag("\"ctag-1\"");
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    pair.setLastSyncStart(new Date(1_700_000_000_000L));
    pair.setLastSyncEnd(new Date(1_700_000_001_000L));
    pair.setConsecutiveFailures(0);
    return pair;
  }

  /** Records every statement Hibernate issues, and changes none of them. */
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
