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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.caldav.entity.CaldavCalendarSyncEntity;
import org.exoplatform.caldav.entity.CaldavObjectSyncEntity;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;

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
