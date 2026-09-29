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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

import org.exoplatform.caldav.plugin.CalendarServerSeed;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The server seeds installed on this platform (EXO-90737), and the one
 * question the host asks of them on a first install: which rows, besides its
 * own default, a fresh registry receives.
 *
 * <p>
 * A {@code @Service} like the other calendar registries, whose contributions
 * are read on each question through the bean factory. That is what makes a
 * seed of a web application booting after this one visible at seeding time:
 * the Kernel/Spring bridge registers every web application's exported beans
 * into every other context before any context creates its singletons, so the
 * definition is there whenever the question is asked, and asking it creates
 * the contribution on demand when its own context has not reached it yet.
 */
@Service
public class CalendarServerSeedRegistry {

  /** The registry's log. */
  private static final Log                         LOG = ExoLogger.getLogger(CalendarServerSeedRegistry.class);

  /** Where the contributed seeds are read from, on each question. */
  private final Supplier<List<CalendarServerSeed>> seeds;

  /**
   * The registry over every contributed seed.
   *
   * @param seeds the contributions, from every web application; null reads as
   *          none
   */
  @Autowired
  public CalendarServerSeedRegistry(ObjectProvider<CalendarServerSeed> seeds) {
    this(() -> seeds == null ? List.of() : seeds.orderedStream().toList());
  }

  /**
   * The registry over a supplier of contributions.
   *
   * @param seeds where the contributions are read from
   */
  private CalendarServerSeedRegistry(Supplier<List<CalendarServerSeed>> seeds) {
    this.seeds = seeds;
  }

  /**
   * The registry over a fixed list of seeds, as a test states them.
   *
   * @param seeds the seeds, in the order the bean factory would give them;
   *          may be null for none
   * @return the registry
   */
  public static CalendarServerSeedRegistry of(List<CalendarServerSeed> seeds) {
    List<CalendarServerSeed> fixed = seeds == null ? List.of() : new ArrayList<>(seeds);
    return new CalendarServerSeedRegistry(() -> fixed);
  }

  /**
   * The seeds a fresh registry receives: every contribution in order, the
   * first one for each identifier (ignoring case) and none after it, leaving
   * out — with a warning — a contribution that cannot say what it seeds.
   *
   * @return the effective seeds, in order, never null
   */
  public List<CalendarServerSeed> effectiveSeeds() {
    List<CalendarServerSeed> contributed;
    try {
      contributed = seeds.get();
    } catch (RuntimeException e) {
      LOG.warn("The contributed CalDAV server seeds could not be read; only the default server is seeded", e);
      return List.of();
    }
    List<CalendarServerSeed> effective = new ArrayList<>();
    Set<String> seededIds = new HashSet<>();
    for (CalendarServerSeed seed : contributed) {
      String id = describable(seed);
      if (id == null) {
        continue;
      }
      if (seededIds.add(id.toLowerCase(Locale.ROOT))) {
        effective.add(seed);
      } else {
        LOG.debug("CalDAV server seed '{}' of {} is ignored: an earlier contribution seeds that server",
                  id,
                  ClassUtils.getUserClass(seed).getName());
      }
    }
    return effective;
  }

  /**
   * The identifier of a seed that says what it seeds — an identifier, a name
   * and an address — or null, after a warning, for one that does not.
   *
   * @param seed a contribution, may be null
   * @return its identifier, or null when it is left out
   */
  private static String describable(CalendarServerSeed seed) {
    if (seed == null) {
      return null;
    }
    try {
      String id = seed.id();
      if (StringUtils.isAnyBlank(id, seed.name(), seed.serverUrl())) {
        LOG.warn("CalDAV server seed {} is left out: it names no identifier, name or address",
                 ClassUtils.getUserClass(seed).getName());
        return null;
      }
      return id;
    } catch (RuntimeException e) {
      LOG.warn("CalDAV server seed {} is left out: it failed to describe itself", ClassUtils.getUserClass(seed).getName(), e);
      return null;
    }
  }

}
