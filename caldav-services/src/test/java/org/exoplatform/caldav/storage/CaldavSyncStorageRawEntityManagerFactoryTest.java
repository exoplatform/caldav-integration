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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;

import org.hibernate.cfg.JdbcSettings;
import org.hibernate.jpa.HibernatePersistenceConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.caldav.dao.CaldavCalendarSyncDAO;
import org.exoplatform.caldav.dao.CaldavObjectSyncDAO;
import org.exoplatform.caldav.entity.CaldavCalendarSyncEntity;
import org.exoplatform.caldav.entity.CaldavObjectSyncEntity;
import org.exoplatform.caldav.entity.CaldavPendingPushEntity;
import org.exoplatform.caldav.entity.CaldavServerEntity;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;

/**
 * The duplicate-key refusal as production surfaces it, against the engine
 * (EXO-90190).
 *
 * <p>
 * {@code CaldavSyncDAOQueryTest} drives the unique index through a
 * Boot-managed {@code EntityManagerFactory} and sees a
 * {@code DataIntegrityViolationException} — which is what the first version of
 * {@code CaldavPushService.saveMapping} caught, and what production never
 * throws. The platform's {@code PersistenceUnitIntegration} hands Spring the
 * Kernel's own factory as a plain bean: not an {@code EntityManagerFactoryInfo},
 * so the {@code JpaTransactionManager} falls back to {@code DefaultJpaDialect},
 * and not a {@code PersistenceExceptionTranslator}, so the repository proxy has
 * nothing to translate with. This harness builds the factory the same way — a
 * JPA bootstrap over the slice's data source, exposed as a bare bean — and lets
 * Boot's auto-configuration back off around it exactly as it does in a WAR.
 *
 * <p>
 * Two paths, because the insert runs at two different moments depending on the
 * id strategy: at commit where the id comes from a sequence (HSQLDB here,
 * PostgreSQL in production), so the refusal passes through the transaction
 * manager's dialect; and inside the repository call where the column is an
 * identity (MySQL in production), which {@code saveAndFlush} reproduces, so the
 * refusal passes through the repository proxy's translator chain. Neither
 * yields the type the first catch expected, and both carry the JDBC cause
 * {@link CaldavSyncStorage#isDuplicateKey} reads.
 *
 * <p>
 * Not transactional at the test level, on purpose: the storage's own
 * transaction has to commit for the commit-time path to exist.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/caldav-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
public class CaldavSyncStorageRawEntityManagerFactoryTest {

  private static final long   SERVER = 5L;

  private static final String UID    = "485e6afe-c5f5-4026-ae51-8c1ad905c45c";

  @Autowired
  private CaldavCalendarSyncDAO calendarSyncDAO;

  @Autowired
  private CaldavObjectSyncDAO   objectSyncDAO;

  @Autowired
  private CaldavSyncStorage     storage;

  @Autowired
  private EntityManagerFactory  entityManagerFactory;

  /**
   * Nothing rolls a test back here, so each one leaves the tables as it found
   * them.
   */
  @AfterEach
  public void forgetTheRows() {
    objectSyncDAO.deleteAll();
    calendarSyncDAO.deleteAll();
  }

  /**
   * The harness is production's shape, or the rest of this class pins nothing.
   */
  @Test
  public void theFactoryIsTheKernelsShapeNotBoots() {
    assertFalse(entityManagerFactory instanceof EntityManagerFactoryInfo,
                "a Boot-managed factory would carry HibernateJpaDialect and make every assertion below trivially true");
  }

  /**
   * At commit, the refusal is a JpaSystemException — not the type round one
   * caught.
   */
  @Test
  public void aDuplicateFlushedAtCommitArrivesAsAJpaSystemException() {
    long pair = persistPair();
    storage.saveObject(mapping(pair));

    RuntimeException refused = assertThrows(RuntimeException.class, () -> storage.saveObject(mapping(pair)));

    assertFalse(refused instanceof DataIntegrityViolationException,
                "the type the first catch expected, which DefaultJpaDialect never produces for a constraint: " + refused);
    assertInstanceOf(JpaSystemException.class, refused, "DefaultJpaDialect's answer to any PersistenceException it does not recognise");
    assertTrue(CaldavSyncStorage.isDuplicateKey(refused), "told by the JDBC cause: " + refused);
  }

  /**
   * Inside the repository call, the refusal arrives as Hibernate's own —
   * untranslated, no Spring type at all.
   */
  @Test
  public void aDuplicateRefusedInsideTheRepositoryCallArrivesUntranslated() {
    long pair = persistPair();
    objectSyncDAO.saveAndFlush(entity(pair));

    RuntimeException refused = assertThrows(RuntimeException.class, () -> objectSyncDAO.saveAndFlush(entity(pair)));

    assertFalse(refused instanceof DataAccessException,
                "no PersistenceExceptionTranslator bean exists for the proxy to consult, so nothing is translated: " + refused);
    assertInstanceOf(PersistenceException.class, refused);
    assertTrue(CaldavSyncStorage.isDuplicateKey(refused), "told by the JDBC cause: " + refused);
  }

  /**
   * @return the identifier of a mirror pair to hang the rows on
   */
  private long persistPair() {
    CaldavCalendarSyncEntity entity = new CaldavCalendarSyncEntity();
    entity.setUserIdentityId(1L);
    entity.setServerId(SERVER);
    entity.setRemoteHref("/dav/calendars/751E/exo-meetings");
    entity.setOrigin(SyncOrigin.MIRROR);
    entity.setStatus(CalendarSyncStatus.ACTIVE);
    entity.setLastSyncEnd(new Date());
    return calendarSyncDAO.save(entity).getId();
  }

  /**
   * @param calendarSyncId the pair
   * @return a mapping row of the one UID, as the service would save it
   */
  private static ObjectSync mapping(long calendarSyncId) {
    ObjectSync mapping = new ObjectSync();
    mapping.setCalendarSyncId(calendarSyncId);
    mapping.setIcsUid(UID);
    mapping.setLocalEventId(52L);
    mapping.setRemoteHref("/dav/calendars/751E/exo-meetings/" + UID + ".ics");
    mapping.setEtag("\"etag-1\"");
    mapping.setLastSync(new Date());
    return mapping;
  }

  /**
   * @param calendarSyncId the pair
   * @return the same row, as the repository takes it
   */
  private static CaldavObjectSyncEntity entity(long calendarSyncId) {
    CaldavObjectSyncEntity entity = new CaldavObjectSyncEntity();
    entity.setCalendarSyncId(calendarSyncId);
    entity.setIcsUid(UID);
    entity.setLocalEventId(52L);
    entity.setRemoteHref("/dav/calendars/751E/exo-meetings/" + UID + ".ics");
    return entity;
  }

  /**
   * The Kernel's shape: the factory built by a JPA bootstrap and handed over as
   * a plain bean, which is what {@code PersistenceUnitIntegration} does in
   * every WAR.
   */
  @SpringBootConfiguration
  @EnableJpaRepositories(basePackageClasses = CaldavCalendarSyncDAO.class)
  @Import(CaldavSyncStorage.class)
  static class KernelShapedJpaConfiguration {

    /**
     * Every entity of the module's index, because every repository of the
     * package is instantiated and each needs its type managed.
     *
     * @param dataSource the slice's embedded database, which Liquibase has
     *          already prepared
     * @return the raw factory, no {@code EntityManagerFactoryInfo} around it
     */
    @Bean
    public EntityManagerFactory entityManagerFactory(DataSource dataSource) {
      return new HibernatePersistenceConfiguration("caldav-kernel-shaped").managedClass(CaldavServerEntity.class)
                                                                          .managedClass(CaldavCalendarSyncEntity.class)
                                                                          .managedClass(CaldavObjectSyncEntity.class)
                                                                          .managedClass(CaldavPendingPushEntity.class)
                                                                          .property(JdbcSettings.JAKARTA_NON_JTA_DATASOURCE,
                                                                                    dataSource)
                                                                          .property("hibernate.hbm2ddl.auto", "none")
                                                                          .createEntityManagerFactory();
    }
  }
}
