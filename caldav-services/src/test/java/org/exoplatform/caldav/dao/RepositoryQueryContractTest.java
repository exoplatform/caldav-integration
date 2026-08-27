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

  private static final Pattern DERIVED     = Pattern.compile("(?:find|count|exists|delete|remove)By(\\w+)");

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
   * Every derived query names properties the entity actually has.
   */
  @Test
  public void everyDerivedQueryNamesPropertiesTheEntityHas() {
    // A method with no @Query is a query Spring Data derives from its name,
    // resolved against the entity's properties when the repository proxy is
    // built. A property that does not exist fails at container start-up with a
    // reason buried in a bean-creation stack — and never at all in a suite
    // where the repository is a mock. Renaming a field and forgetting a method
    // name is the whole failure mode; this is what sees it.
    List<String> unknown = new ArrayList<>();
    for (Class<?> dao : repositories()) {
      for (Method method : dao.getDeclaredMethods()) {
        if (method.getAnnotation(Query.class) != null) {
          continue;
        }
        Matcher matcher = DERIVED.matcher(method.getName());
        if (!matcher.matches()) {
          continue;
        }
        for (String property : matcher.group(1).split("And")) {
          if (!property.isEmpty() && !resolves(entityOf(dao), property)) {
            unknown.add(dao.getSimpleName() + "." + method.getName() + " -> " + property);
          }
        }
      }
    }
    assertTrue(unknown.isEmpty(), "derived queries naming a property the entity does not have: " + unknown);
  }

  /**
   * The pass above inspects derived queries too.
   */
  @Test
  public void theseAssertionsActuallyReadDerivedQueries() {
    // Same self-check as below, for the same reason: a name pattern that
    // stopped matching would leave the derived-query test green and blind.
    int derived = 0;
    for (Class<?> dao : repositories()) {
      for (Method method : dao.getDeclaredMethods()) {
        if (method.getAnnotation(Query.class) == null && DERIVED.matcher(method.getName()).matches()) {
          derived++;
        }
      }
    }
    assertTrue(derived >= 8, "expected derived queries to check, found " + derived);
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
   * Whether one segment of a derived query name resolves to properties the
   * entity has.
   *
   * <p>
   * Split on {@code And} only, then on {@code Or} as a fallback: {@code Or} is
   * also two letters in the middle of ordinary property names — {@code Origin}
   * is one of this add-on's — so splitting on it unconditionally invents
   * properties nobody wrote.
   *
   * @param entity the entity class
   * @param segment one {@code And}-separated part of the derived name
   * @return true when the segment names properties the entity declares
   */
  private boolean resolves(Class<?> entity, String segment) {
    if (hasField(entity, uncapitalised(segment))) {
      return true;
    }
    String[] alternatives = segment.split("Or");
    if (alternatives.length < 2) {
      return false;
    }
    for (String alternative : alternatives) {
      if (alternative.isEmpty() || !hasField(entity, uncapitalised(alternative))) {
        return false;
      }
    }
    return true;
  }

  /**
   * @param name a capitalised property name as it appears in a method name
   * @return the same name as a Java field would spell it
   */
  private String uncapitalised(String name) {
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  /**
   * @param entity the entity class
   * @param field the property name looked for
   * @return true when the entity declares it
   */
  private boolean hasField(Class<?> entity, String field) {
    for (java.lang.reflect.Field declared : entity.getDeclaredFields()) {
      if (declared.getName().equals(field)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The entity a repository is declared over.
   *
   * @param dao the repository interface
   * @return its entity class
   */
  private Class<?> entityOf(Class<?> dao) {
    for (java.lang.reflect.Type parent : dao.getGenericInterfaces()) {
      if (parent instanceof java.lang.reflect.ParameterizedType parameterized) {
        return (Class<?>) parameterized.getActualTypeArguments()[0];
      }
    }
    throw new AssertionError(dao.getSimpleName() + " declares no entity type");
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
