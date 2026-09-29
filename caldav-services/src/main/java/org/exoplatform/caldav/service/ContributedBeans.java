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
package org.exoplatform.caldav.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.ClassUtils;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The beans contributed to one of this add-on's extension points, read one
 * by one so that a contribution which cannot be created reads as absent
 * rather than failing every question asked of the extension point.
 *
 * <p>
 * A contribution from another web application reaches this one through the
 * Kernel/Spring bridge as a lazy proxy: the bean the proxy stands for is only
 * created when a method of it is first called, and sorting by order is such
 * a call. Read as one ordered stream, a single contribution whose bean fails
 * to be created would make the whole read throw, and with it every caller —
 * a Stalwart-only question as much as a BlueMind one. So each contribution is
 * asked a question of its own first (the probe, its identity on the extension
 * point), then its order, inside its own guard; one that fails either is left
 * out and said so, once per contribution, and the others are sorted by the
 * orders read. A contribution of this web application that the bean factory
 * itself fails to create while listing is left out the same way, and the
 * listing goes on past it.
 *
 * @param <T> the extension point's contract
 */
public final class ContributedBeans<T> implements Supplier<List<T>> {

  /** The reader's log. */
  private static final Log      LOG    = ExoLogger.getLogger(ContributedBeans.class);

  /**
   * How many contributions the listing itself may fail to create before the
   * rest of it is given up: the bean factory's listing moves past a bean it
   * could not create, and this bound only guards against a listing that
   * would fail for ever without moving.
   */
  private static final int        MAX_LISTING_FAILURES = 64;

  /** The contributions, as the bean factory lists them. */
  private final ObjectProvider<T> provider;

  /** What the log calls one contribution of this extension point. */
  private final String            kind;

  /** The question each contribution must answer to be kept. */
  private final Function<T, ?>    probe;

  /**
   * The contributions already reported as left out, by class: each one is
   * said once at WARN, and at debug on every later read.
   */
  private final Set<String>       warned = ConcurrentHashMap.newKeySet();

  /**
   * The reader of one extension point's contributions.
   *
   * @param provider the contributions, from every web application; null reads
   *          as none
   * @param kind what the log calls one contribution, such as "share channel"
   * @param probe the question each contribution answers before it is kept —
   *          its identity on the extension point — which creates the bean a
   *          proxy stands for
   */
  public ContributedBeans(ObjectProvider<T> provider, String kind, Function<T, ?> probe) {
    this.provider = provider;
    this.kind = kind;
    this.probe = probe;
  }

  /**
   * The contributions that could be created, in their declared order; never
   * throws for a contribution that cannot be.
   *
   * @return the contributions, never null
   */
  @Override
  public List<T> get() {
    if (provider == null) {
      return List.of();
    }
    List<Ordered<T>> resolved = new ArrayList<>();
    Iterator<T> beans;
    try {
      beans = provider.stream().iterator();
    } catch (RuntimeException e) {
      leftOut("*", e);
      return List.of();
    }
    int failures = 0;
    while (failures <= MAX_LISTING_FAILURES) {
      T bean;
      try {
        if (!beans.hasNext()) {
          break;
        }
        bean = beans.next();
      } catch (RuntimeException e) {
        failures++;
        leftOut(e instanceof BeanCreationException creation && creation.getBeanName() != null ? creation.getBeanName() : "*", e);
        continue;
      }
      Ordered<T> ordered = resolve(bean);
      if (ordered != null) {
        resolved.add(ordered);
      }
    }
    return resolved.stream().sorted(Comparator.comparingInt(Ordered::order)).map(Ordered::bean).toList();
  }

  /**
   * One contribution with its order, or null, after saying so, when it
   * cannot answer its probe or its order.
   *
   * @param bean the contribution, may be null
   * @return the contribution and its order, or null when it is left out
   */
  private Ordered<T> resolve(T bean) {
    if (bean == null) {
      return null;
    }
    try {
      probe.apply(bean);
      return new Ordered<>(bean, AnnotationAwareOrderComparator.INSTANCE.getOrder(bean, null));
    } catch (RuntimeException e) {
      leftOut(ClassUtils.getUserClass(bean).getName(), e);
      return null;
    }
  }

  /**
   * Says a contribution is left out: at WARN, with the cause, the first time
   * this reader meets it, at debug afterwards.
   *
   * @param contribution the contribution's class name, its bean name when the
   *          listing failed to create it, or {@code *} when the listing failed
   *          otherwise
   * @param cause why it is left out
   */
  private void leftOut(String contribution, RuntimeException cause) {
    if (warned.add(contribution)) {
      LOG.warn("The {} contribution {} could not be created; it is left out, as if its add-on were not installed",
               kind,
               contribution,
               cause);
    } else {
      LOG.debug("The {} contribution {} is still left out: {}", kind, contribution, cause.getMessage());
    }
  }

  /**
   * A contribution and the order it declares.
   *
   * @param bean the contribution
   * @param order its order, lowest first
   * @param <T> the extension point's contract
   */
  private record Ordered<T>(T bean, int order) {
  }
}
