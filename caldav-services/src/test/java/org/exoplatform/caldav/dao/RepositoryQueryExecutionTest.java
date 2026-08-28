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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

/**
 * Every query this module writes by hand, executed by the engine that will
 * execute it in production.
 *
 * <p>
 * <b>Why this exists on top of {@link RepositoryQueryContractTest}.</b> That one
 * reads the queries as text: it catches an unbound parameter and an entity name
 * JPA does not declare, which are the two ways this add-on has actually been
 * bitten. It cannot catch a property that does not exist on the entity, a join
 * the parser refuses, an {@code UPDATE} whose {@code SET} names a column
 * Hibernate will not write, or a derived method name that resolves to nothing —
 * and none of those fail until the first caller, which for a background sweep
 * means in production, at three in the morning, as a warning.
 *
 * <p>
 * So this boots a real Hibernate {@code EntityManagerFactory} over HSQLDB from
 * the entities {@code jpa-entities.idx} registers, builds a genuine Spring Data
 * repository proxy over it, and <b>calls every query method there is</b> — with
 * arguments synthesised from their types, because what is being proved is that
 * the statement runs, not what it answers.
 *
 * <p>
 * Nothing here names a query. It walks whatever the repositories declare, so
 * the method added next year is covered without anybody remembering to extend
 * it — the same discipline {@code ChangelogExecutionTest} applies to the
 * changelog.
 *
 * <p>
 * <b>What it still cannot say.</b> This is HSQLDB, not MySQL: it proves the
 * grammar and the mapping, and nothing about an index being used, a collation,
 * or a key length. Those need a run against the real vendor.
 */
public class RepositoryQueryExecutionTest {

  private EntityManagerFactory factory;

  private EntityManager        entityManager;

  /**
   * Opens a persistence unit and a schema of this test's own.
   */
  @BeforeEach
  public void openThePersistenceUnit() {
    factory = Persistence.createEntityManagerFactory("caldav-test");
    entityManager = factory.createEntityManager();
  }

  /**
   * Closes both, so a second test class does not inherit this one's schema.
   */
  @AfterEach
  public void closeThePersistenceUnit() {
    if (entityManager != null) {
      entityManager.close();
    }
    if (factory != null) {
      factory.close();
    }
  }

  /**
   * Every repository method runs against a real engine without being refused.
   */
  @Test
  public void everyQueryTheRepositoriesDeclareActuallyExecutes() {
    List<String> refused = new ArrayList<>();
    int executed = 0;
    for (Class<?> repository : repositories()) {
      Object proxy = new JpaRepositoryFactory(entityManager).getRepository(repository);
      for (Method method : repository.getDeclaredMethods()) {
        executed++;
        entityManager.getTransaction().begin();
        try {
          method.invoke(proxy, argumentsFor(method));
        } catch (ReflectiveOperationException e) {
          // The invocation target is what matters: a query the engine refuses
          // arrives here wrapped, and its cause is the message worth reading.
          Throwable cause = e.getCause() == null ? e : e.getCause();
          refused.add(repository.getSimpleName() + "." + method.getName() + " -> " + cause);
        } finally {
          entityManager.getTransaction().rollback();
        }
      }
    }
    assertTrue(refused.isEmpty(), "queries the engine refused to run: " + refused);
    // The self-check every contract test in this module carries: one that finds
    // nothing to run is green for the wrong reason.
    assertTrue(executed >= 20, "expected repository methods to execute, found " + executed);
  }

  /**
   * Arguments of the right shape for one repository method.
   *
   * <p>
   * Values are deliberately empty or zero: this asks whether the statement
   * runs, and a query that runs against no rows has been parsed, bound and
   * executed exactly as one that runs against a million.
   *
   * @param method the repository method about to be invoked
   * @return one argument per parameter
   */
  private Object[] argumentsFor(Method method) {
    Parameter[] parameters = method.getParameters();
    Object[] arguments = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      arguments[i] = argumentFor(parameters[i].getType());
    }
    return arguments;
  }

  /**
   * One argument of a given type.
   *
   * @param type the parameter's declared type
   * @return a value the engine will accept
   */
  private Object argumentFor(Class<?> type) {
    if (type == long.class || type == Long.class) {
      return 0L;
    }
    if (type == int.class || type == Integer.class) {
      return 0;
    }
    if (type == boolean.class || type == Boolean.class) {
      return Boolean.FALSE;
    }
    if (type == String.class) {
      return "";
    }
    if (type == Date.class) {
      return new Date(0);
    }
    if (Pageable.class.isAssignableFrom(type)) {
      // Unsorted, deliberately. A synthesised sort would test a combination no
      // caller makes, and one of these queries groups by a column — asking it
      // to order by the identifier as well is a statement HSQLDB and MySQL both
      // refuse, for the query's own good reasons rather than for a defect. The
      // sorts callers do pass are theirs to be right about; what this proves is
      // that the statements underneath them run.
      return PageRequest.of(0, 1);
    }
    if (Sort.class.isAssignableFrom(type)) {
      return Sort.unsorted();
    }
    if (Collection.class.isAssignableFrom(type)) {
      // Never empty: an IN clause over an empty collection is a different
      // statement, and one some engines refuse outright.
      return List.of(0L);
    }
    if (type.isEnum()) {
      return type.getEnumConstants()[0];
    }
    if (type.isInstance(null) || Object.class.equals(type)) {
      return null;
    }
    return fail("no argument of type " + type.getName() + " can be synthesised; teach this test about it");
  }

  /**
   * The queries a repository declares itself, inherited ones excluded — those
   * are Spring Data's own and are not this module's to prove.
   *
   * @return the add-on's repositories
   */
  private List<Class<?>> repositories() {
    return List.of(CaldavCalendarSyncDAO.class,
                   CaldavObjectSyncDAO.class,
                   CaldavPendingPushDAO.class,
                   CaldavServerDAO.class);
  }
}
