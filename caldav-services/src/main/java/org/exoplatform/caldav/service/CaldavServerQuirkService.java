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
package org.exoplatform.caldav.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.ics.IcsEquivalence;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.IcsDivergence;
import org.exoplatform.caldav.model.ObservedQuirk;
import org.exoplatform.caldav.model.ServerQuirk;
import org.exoplatform.caldav.storage.CaldavServerStorage;
import org.exoplatform.caldav.utils.ServerQuirkSummary;
import org.exoplatform.caldav.utils.ServerQuirkSummary.Observation;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * What each CalDAV server has been seen doing, and what its administrator has
 * excused it for.
 *
 * <p>
 * <b>The list is evidence, not configuration.</b> Before EXO-89771 an operator
 * had to read a log line naming a property, type it into
 * {@code exo.agenda.caldav.mirror.ignoredProperties} or
 * {@code ...droppedProperties}, and restart — and both settings were global, so
 * naming {@code CONFERENCE} for BlueMind also blinded a well-behaved server to a
 * genuinely deleted conference link. The sweep already knows exactly what
 * diverges on each server every five minutes; this records it per server, so
 * the drawer can offer what that server actually does and the administrator can
 * choose from evidence instead of guessing.
 *
 * <p>
 * <b>Where it is recorded, and why there.</b> One column on the server's own
 * row, holding a bounded rolling summary — see
 * {@link org.exoplatform.caldav.utils.ServerQuirkSummary} for the format. It
 * survives a restart, because an empty drawer after one is an empty drawer
 * exactly when somebody is investigating; it stays cheap, because it is one
 * bounded row per server rather than a row per copy per divergence; and it
 * costs no extra query, because the drawer already fetches the registration.
 *
 * <p>
 * <b>Accumulated in memory, flushed at most once per sweep per server.</b> The
 * sweep runs per user, so a deployment with five hundred accounts on one server
 * would otherwise write that row five hundred times every five minutes. The
 * counts are therefore approximate by construction — a crash loses at most one
 * interval — which is all they need to be: they answer "does this server always
 * do this, or did it happen once", and no decision is made from an exact value.
 */
@Service
public class CaldavServerQuirkService {

  /** Where the diagnostic lines of this service go. */
  private static final Log                        LOG    = ExoLogger.getExoLogger(CaldavServerQuirkService.class);

  /** A nanosecond, so the flush interval reads in seconds where it is declared. */
  private static final long                       SECOND = 1_000_000_000L;

  /** Where a registration's rolling summary is read from and written to. */
  @Autowired
  private CaldavServerStorage                     caldavServerStorage;

  /** Where the deployment-wide fallback lists are declared. */
  @Autowired
  private IcsEquivalence                          icsEquivalence;

  /**
   * How long one server's observations are held in memory before they reach its
   * row. One sweep by default, so a behaviour a server has just started doing
   * appears in the drawer within a sweep of it starting.
   */
  @Value("${exo.agenda.caldav.mirror.quirkFlushSeconds:300}")
  private long                                      flushSeconds;

  /** What has been seen since each server's summary was last written. */
  private final Map<Long, Map<Observation, Long>>   pending  = new ConcurrentHashMap<>();

  /** When each server's summary was last written, as a monotonic reading. */
  private final Map<Long, Long>                     flushed  = new ConcurrentHashMap<>();

  /**
   * Records what one comparison found a server doing to one copy.
   *
   * <p>
   * Called for every judged copy, including the ones judged equivalent —
   * <b>a divergence the administrator has already excused is still reported by
   * the comparison and still counted here</b>, or the very behaviour somebody
   * ticked would disappear from the list they ticked it in, and nobody could
   * untick it or see what a deployment-wide property is already hiding.
   *
   * @param serverId the registration the account is connected through, may be
   *          null for an account predating the registry — nothing is recorded
   *          for one, since there is no row to record it against
   * @param divergences what the comparison saw, one entry per property
   */
  public void observe(Long serverId, List<IcsDivergence> divergences) {
    if (serverId == null || serverId <= 0 || divergences == null || divergences.isEmpty()) {
      return;
    }
    Map<Observation, Long> increments = pending.computeIfAbsent(serverId, key -> new ConcurrentHashMap<>());
    for (IcsDivergence divergence : divergences) {
      Observation observation = Observation.of(divergence.direction(), divergence.property());
      if (observation != null) {
        increments.merge(observation, 1L, Long::sum);
      }
    }
    flushIfDue(serverId);
  }

  /**
   * Writes a server's pending observations to its row when enough time has
   * passed since the last write.
   *
   * <p>
   * The first observation after a restart always writes, because nothing has
   * been flushed yet: a behaviour that started while eXo was down is in the
   * drawer as soon as the first sweep sees it.
   *
   * @param serverId technical identifier of the registration
   */
  private void flushIfDue(long serverId) {
    long now = System.nanoTime();
    Long last = flushed.get(serverId);
    if (last != null && now - last < flushSeconds * SECOND) {
      return;
    }
    flushed.put(serverId, now);
    Map<Observation, Long> increments = pending.remove(serverId);
    if (increments == null || increments.isEmpty()) {
      return;
    }
    try {
      caldavServerStorage.mergeObservedQuirks(serverId, increments);
    } catch (RuntimeException e) {
      // The summary is diagnostic. A row that cannot be written must never end
      // a sweep, and the next interval simply counts from where this one left
      // off — minus what this attempt was holding, which nothing depends on.
      LOG.debug("The observed behaviours of CalDAV server {} could not be recorded", serverId, e);
    }
  }

  /**
   * Fills in what the drawer needs to show a registration's behaviours: the
   * lists actually in force for it, and which observed behaviour each of them
   * excuses.
   *
   * <p>
   * <b>The lists come back resolved, and that is deliberate.</b> A registration
   * that has never been asked carries null and the deployment-wide property
   * decides for it; what the drawer shows — and therefore what it saves back —
   * is what is <i>in force</i>. So the first save of a server writes today's
   * deployment-wide list into its row and the row owns it from then on, which is
   * the honest reading of "this server's settings": an administrator looking at
   * a ticked box must be able to untick it, and a box ticked by a property file
   * they cannot see from here is not a setting they can act on.
   *
   * @param server the registration to enrich, may be null
   * @return the same registration, enriched; null when null was given
   */
  public CaldavServer decorate(CaldavServer server) {
    if (server == null) {
      return null;
    }
    String ignored = effective(server.getIgnoredProperties(), icsEquivalence.getGlobalIgnoredProperties());
    String dropped = effective(server.getDroppedProperties(), icsEquivalence.getGlobalDroppedProperties());
    server.setIgnoredProperties(ignored);
    server.setDroppedProperties(dropped);
    server.setObservedQuirks(excusalOf(server.getObservedQuirks(), ignored, dropped));
    return server;
  }

  /**
   * Marks each observed behaviour with whether the lists in force cover it.
   *
   * @param observed what the server has been seen doing, may be null
   * @param ignored the patterns in force for a property the server adds
   * @param dropped the patterns in force for a property the server does not
   *          keep
   * @return the same behaviours, each carrying its excusal
   */
  private List<ObservedQuirk> excusalOf(List<ObservedQuirk> observed, String ignored, String dropped) {
    if (observed == null) {
      return List.of();
    }
    return observed.stream()
                   .map(quirk -> new ObservedQuirk(quirk.quirkId(),
                                                   quirk.property(),
                                                   quirk.direction(),
                                                   quirk.count(),
                                                   matches(listFor(quirk, ignored, dropped), quirk.property()),
                                                   quirk.patterns()))
                   .toList();
  }

  /**
   * Which of the two lists decides whether a behaviour is excused.
   *
   * @param quirk the observed behaviour
   * @param ignored the patterns in force for a property the server adds
   * @param dropped the patterns in force for a property the server does not
   *          keep
   * @return the list that would excuse it
   */
  private String listFor(ObservedQuirk quirk, String ignored, String dropped) {
    return switch (quirk.direction()) {
      case ADDED -> ignored;
      case DROPPED, REWRITTEN -> dropped;
    };
  }

  /**
   * Whether a comma-separated list of patterns names a property, using the one
   * matcher the comparison and the catalogue also use.
   *
   * @param patterns the list in force, may be null or blank
   * @param property the property name
   * @return true when one of the patterns names it
   */
  private boolean matches(String patterns, String property) {
    if (StringUtils.isBlank(patterns)) {
      return false;
    }
    return Arrays.stream(patterns.split(",")).anyMatch(pattern -> ServerQuirk.patternMatches(pattern, property));
  }

  /**
   * The list that decides for one server: its own when it has one, the
   * deployment-wide property when it has never been asked.
   *
   * <p>
   * Null and empty are different answers, and the difference is the upgrade
   * story: null defers to the deployment, an empty string is an administrator's
   * own "excuse nothing" and must override it, or unticking the last box would
   * be a no-op.
   *
   * @param serverValue the value stored on the registration, may be null
   * @param globalValue the deployment-wide property
   * @return the list in force
   */
  private String effective(String serverValue, String globalValue) {
    return serverValue == null ? StringUtils.defaultString(globalValue) : serverValue;
  }

  /**
   * Forgets what has been seen on a server but not yet written, so a deleted
   * registration leaves nothing behind to be written against an identifier a
   * later row may reuse.
   *
   * @param serverId technical identifier of the registration
   */
  public void forget(long serverId) {
    pending.remove(serverId);
    flushed.remove(serverId);
  }

  /**
   * The bound the stored summary is kept within, named here so a caller can
   * reason about it without reaching into the codec.
   *
   * @return how many behaviours one server's summary keeps
   */
  public int getMaxObservedQuirks() {
    return ServerQuirkSummary.MAX_ENTRIES;
  }
}
