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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import org.exoplatform.caldav.plugin.CalendarServerSeed;

/**
 * The server seeds as contributions (EXO-90737): one seed per identifier, the
 * first in the bean factory's order, and none from a contribution that cannot
 * say what it seeds.
 */
class CalendarServerSeedRegistryTest {

  /**
   * With nothing installed, nothing is seeded besides the host's default.
   */
  @Test
  void withNoSeedThereIsNothingToSeed() {
    assertTrue(CalendarServerSeedRegistry.of(null).effectiveSeeds().isEmpty());
    assertTrue(new CalendarServerSeedRegistry(null).effectiveSeeds().isEmpty());
  }

  /**
   * Two seeds of one identifier — whatever its case — are one seed, the first;
   * a seed of another identifier is kept beside it, in order.
   */
  @Test
  void theFirstSeedOfAnIdentifierIsTheOnlyOne() {
    CalendarServerSeed first = seed("BlueMind", "BlueMind (add-on)");
    CalendarServerSeed other = seed("acme", "Acme");
    CalendarServerSeed builtIn = new UnorderedSeed();

    List<CalendarServerSeed> seeds = CalendarServerSeedRegistry.of(List.of(first, other, builtIn)).effectiveSeeds();

    assertEquals(2, seeds.size());
    assertSame(first, seeds.get(0));
    assertSame(other, seeds.get(1));
  }

  /**
   * A contribution with no identifier, name or address, one that fails to
   * describe itself, and a null one are left out; the others are kept.
   */
  @Test
  void aSeedThatCannotSayWhatItSeedsIsLeftOut() {
    CalendarServerSeed failing = new CalendarServerSeed() {
      @Override
      public String id() {
        throw new IllegalStateException("broken");
      }

      @Override
      public String name() {
        return "Broken";
      }

      @Override
      public String serverUrl() {
        return "https://broken.example.invalid/";
      }
    };
    List<CalendarServerSeed> contributed = new ArrayList<>(Arrays.asList(null,
                                                                         failing,
                                                                         seed(" ", "No id"),
                                                                         seed("no-name", ""),
                                                                         new UnorderedSeed()));

    List<CalendarServerSeed> seeds = CalendarServerSeedRegistry.of(contributed).effectiveSeeds();

    assertEquals(1, seeds.size());
    assertEquals(UnorderedSeed.SEED_ID, seeds.get(0).id());
  }

  /**
   * A contribution the bean factory cannot create is left out: alone, it
   * reads as none, so the host still seeds its own default; beside another
   * seed, listed before it, the other is still seeded.
   */
  @Test
  void aSeedThatCannotBeCreatedIsLeftOutAndTheOthersAreSeeded() {
    DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
    factory.registerBeanDefinition("broken", new RootBeanDefinition(CalendarServerSeed.class, () -> {
      throw new IllegalStateException("cannot create");
    }));

    assertTrue(new CalendarServerSeedRegistry(factory.getBeanProvider(CalendarServerSeed.class)).effectiveSeeds().isEmpty());

    factory.registerBeanDefinition("unorderedSeed", new RootBeanDefinition(UnorderedSeed.class));
    List<CalendarServerSeed> seeds = new CalendarServerSeedRegistry(factory.getBeanProvider(CalendarServerSeed.class)).effectiveSeeds();

    assertEquals(1, seeds.size());
    assertEquals(UnorderedSeed.class, seeds.get(0).getClass());
  }

  /**
   * Over a real bean factory, a seed that states an order ahead of one that
   * states none is the one kept for their shared identifier, whichever was
   * registered first and whichever comparator the factory carries.
   */
  @Test
  void anOrderedSeedIsKeptAheadOfAnUnorderedOne() {
    for (boolean annotationAware : List.of(true, false)) {
      DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
      if (annotationAware) {
        factory.setDependencyComparator(AnnotationAwareOrderComparator.INSTANCE);
      }
      factory.registerBeanDefinition("unorderedSeed", new RootBeanDefinition(UnorderedSeed.class));
      factory.registerBeanDefinition("orderedSeed", new RootBeanDefinition(OrderedSeed.class));

      List<CalendarServerSeed> seeds =
                                     new CalendarServerSeedRegistry(factory.getBeanProvider(CalendarServerSeed.class)).effectiveSeeds();

      assertEquals(1, seeds.size(), "annotation-aware: " + annotationAware);
      assertEquals(OrderedSeed.class, seeds.get(0).getClass(), "annotation-aware: " + annotationAware);
    }
  }

  /**
   * A seed of the given identifier and name.
   *
   * @param id the identifier
   * @param name the name
   * @return the seed
   */
  private static CalendarServerSeed seed(String id, String name) {
    return new CalendarServerSeed() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public String serverUrl() {
        return "https://" + id + ".example.invalid/dav/";
      }
    };
  }

  /**
   * A seed that states no order, as a contribution declared without one is.
   */
  public static class UnorderedSeed implements CalendarServerSeed {

    /** The identifier this seed and {@link OrderedSeed} share. */
    static final String SEED_ID = "bluemind";

    /**
     * {@inheritDoc}
     */
    @Override
    public String id() {
      return SEED_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
      return "BlueMind (unordered)";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String serverUrl() {
      return "https://bluemind.example.invalid/dav/";
    }
  }

  /**
   * A seed of the same identifier that states an order, as a contribution
   * meant to win does.
   */
  public static class OrderedSeed implements CalendarServerSeed, Ordered {

    /**
     * {@inheritDoc}
     */
    @Override
    public String id() {
      return UnorderedSeed.SEED_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
      return "BlueMind (ordered)";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String serverUrl() {
      return "https://bluemind.example.invalid/dav/";
    }

    /**
     * Ahead of a seed that states none.
     *
     * @return an order ahead of {@link Ordered#LOWEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
      return Ordered.HIGHEST_PRECEDENCE + 100;
    }
  }
}
