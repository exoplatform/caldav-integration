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

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.persistence.Entity;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.exoplatform.caldav.entity.CaldavCalendarSyncEntity;
import org.exoplatform.caldav.entity.CaldavObjectSyncEntity;
import org.exoplatform.caldav.entity.CaldavServerEntity;

/**
 * What a mocked repository can never tell us.
 *
 * <p>
 * A Spring Data query is a string resolved at runtime by a proxy. Mock the
 * repository and the string is never read, so a test suite can be entirely
 * green while a query cannot execute at all. That is not hypothetical: this
 * add-on shipped two queries whose named parameters were never bound, and the
 * sibling agenda migration shipped one naming an entity that does not exist.
 * All of them passed their tests.
 *
 * <p>
 * These assertions read the queries and check them against the declarations
 * they depend on. It is not a substitute for running them against a database —
 * nothing here proves a column exists — but it closes the two ways we have
 * actually been bitten.
 */
public class RepositoryQueryContractTest {

  private static final Pattern NAMED_PARAM = Pattern.compile(":(\\w+)");

  private static final Pattern ENTITY_IN   =
                                           Pattern.compile("\\b(?:FROM|UPDATE)\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

  /**
   * Every named parameter is bound by an annotated method parameter.
   */
  @Test
  public void everyNamedParameterIsBoundByAnAnnotatedArgument() {
    // Without @Param, Spring Data needs the -parameters javac flag to know
    // what an argument is called. This module does not set it, so the binding
    // fails at runtime with a message about a flag rather than about the
    // query, and only the first caller ever finds out.
    List<String> unbound = new ArrayList<>();
    for (Class<?> dao : repositories()) {
      for (Method method : dao.getDeclaredMethods()) {
        Query query = method.getAnnotation(Query.class);
        if (query == null) {
          continue;
        }
        Matcher matcher = NAMED_PARAM.matcher(query.value());
        while (matcher.find()) {
          if (!isBound(method, matcher.group(1))) {
            unbound.add(dao.getSimpleName() + "." + method.getName() + " -> :" + matcher.group(1));
          }
        }
      }
    }
    assertTrue(unbound.isEmpty(), "named parameters with no @Param to bind them: " + unbound);
  }

  /**
   * Every entity a query names is one JPA actually declares.
   */
  @Test
  public void everyEntityAQueryNamesIsOneJpaDeclares() {
    // JPQL resolves the name on @Entity, which is free to differ from the
    // class name — and does, elsewhere in this platform. A query naming the
    // class instead fails with "Could not resolve root entity", at the first
    // execution and not before.
    List<String> declared = List.of(nameOf(CaldavCalendarSyncEntity.class),
                                    nameOf(CaldavObjectSyncEntity.class),
                                    nameOf(CaldavServerEntity.class));
    List<String> unknown = new ArrayList<>();
    for (Class<?> dao : repositories()) {
      for (Method method : dao.getDeclaredMethods()) {
        Query query = method.getAnnotation(Query.class);
        if (query == null) {
          continue;
        }
        Matcher matcher = ENTITY_IN.matcher(query.value());
        while (matcher.find()) {
          if (!declared.contains(matcher.group(1))) {
            unknown.add(dao.getSimpleName() + "." + method.getName() + " -> " + matcher.group(1));
          }
        }
      }
    }
    assertTrue(unknown.isEmpty(), "queries naming an entity JPA does not declare: " + unknown);
  }

  /**
   * The pass above inspects something.
   */
  @Test
  public void theseAssertionsActuallyReadQueries() {
    // A contract test that finds nothing to check is green for the wrong
    // reason. If the repository list goes stale, or the annotations move, the
    // two tests above would keep passing while guarding nothing — which is the
    // exact failure they exist to prevent, turned on themselves.
    int queries = 0;
    int namedParameters = 0;
    for (Class<?> dao : repositories()) {
      for (Method method : dao.getDeclaredMethods()) {
        Query query = method.getAnnotation(Query.class);
        if (query == null) {
          continue;
        }
        queries++;
        Matcher matcher = NAMED_PARAM.matcher(query.value());
        while (matcher.find()) {
          namedParameters++;
        }
      }
    }
    assertTrue(queries >= 2, "expected the repositories to declare queries, found " + queries);
    assertTrue(namedParameters >= 3, "expected named parameters to check, found " + namedParameters);
  }

  /**
   * @param method the repository method
   * @param name the named parameter to look for
   * @return true when an argument declares it
   */
  private boolean isBound(Method method, String name) {
    for (Parameter parameter : method.getParameters()) {
      Param param = parameter.getAnnotation(Param.class);
      if (param != null && name.equals(param.value())) {
        return true;
      }
    }
    return false;
  }

  /**
   * @param entity a JPA entity class
   * @return the name JPQL resolves it by
   */
  private String nameOf(Class<?> entity) {
    Entity annotation = entity.getAnnotation(Entity.class);
    return annotation == null || annotation.name().isEmpty() ? entity.getSimpleName() : annotation.name();
  }

  /**
   * @return the add-on's repositories
   */
  private List<Class<?>> repositories() {
    return List.of(CaldavCalendarSyncDAO.class, CaldavObjectSyncDAO.class, CaldavServerDAO.class);
  }
}
