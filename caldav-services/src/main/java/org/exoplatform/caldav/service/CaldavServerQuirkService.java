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

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.ics.IcsEquivalence;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.IcsDivergence;
import org.exoplatform.caldav.model.ObservedQuirk;
import org.exoplatform.caldav.model.ServerQuirk;
import org.exoplatform.caldav.model.ServerQuirkDirection;
import org.exoplatform.caldav.model.ServerQuirkEffect;
import org.exoplatform.caldav.storage.CaldavServerStorage;
import org.exoplatform.caldav.utils.ServerQuirkSummary;
import org.exoplatform.caldav.utils.ServerQuirkSummary.Observation;
import org.exoplatform.caldav.utils.ServerQuirkSummary.Retention;
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

  /**
   * How many days a behaviour stays on the list after the last time a server was
   * seen doing it.
   *
   * <p>
   * <b>Why a summary has to forget at all.</b> It only ever grew. A server that
   * stopped doing something went on offering it as a live decision for ever, and
   * — the case a live account showed — a behaviour the comparison learned to
   * classify differently was listed twice: once under its new description, and
   * once under the older, broader one it had been recorded as, whose excusal
   * would have covered far more than the administrator intended.
   *
   * <p>
   * A month, because the number is read as "does this server do this", and a
   * behaviour nothing has seen for a month is not something to decide about
   * today. Short enough to clear what has ended, long enough that a quiet
   * fortnight does not erase a real finding — and if it does, the next sweep
   * that meets it puts it straight back.
   */
  @Value("${exo.agenda.caldav.mirror.quirkRetentionDays:30}")
  private long                                      retentionDays;

  /**
   * How long a record survives after the case that replaces it has been excused.
   *
   * <p>
   * <b>Why an excusal cannot simply erase it on sight.</b> An excusal never
   * expires, so a rule with no grace would wipe a record of the superseded
   * property on the first pass that did not happen to see it — and on a server
   * excused for dropping the organizer of an appointment, that record is exactly
   * an organizer vanishing from a real meeting, which EXO-89775 went out of its
   * way to keep visible. A day is enough: a behaviour that is still happening
   * refreshes its stamp every sweep it happens on and stays, while one frozen
   * since before the reclassification never does.
   */
  @Value("${exo.agenda.caldav.mirror.quirkSupersededGraceDays:1}")
  private long                                      supersededGraceDays;

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
    if (serverId == null || serverId <= 0) {
      return;
    }
    if (divergences != null && !divergences.isEmpty()) {
      Map<Observation, Long> increments = pending.computeIfAbsent(serverId, key -> new ConcurrentHashMap<>());
      for (IcsDivergence divergence : divergences) {
        Observation observation = Observation.of(divergence.direction(), divergence.property());
        if (observation != null) {
          increments.merge(observation, 1L, Long::sum);
        }
      }
    }
    settle(serverId);
  }

  /**
   * Gives one server's summary its chance to be written and pruned, whether or
   * not anything diverged.
   *
   * <p>
   * <b>The cleanup must not depend on the problem still happening.</b> Pruning
   * used to ride on the write, the write on a pending count, and the count on a
   * divergence — and a copy that agrees with what eXo writes moves no ETag, so a
   * converged account never even reaches the comparison. An account that is
   * healthy, which is exactly the account whose stale records need clearing,
   * therefore never wrote its row at all. That is the same failure one level
   * down from the one supersession-by-excusal fixed: the mechanism could only
   * act while the fault it cleans up after was still occurring.
   *
   * <p>
   * So the pass calls this once per sweep for the server it swept, with nothing
   * to report. The throttle below is what keeps that cheap — one read per server
   * per interval, however many accounts are connected to it — and the write
   * happens only when the summary actually changes, so a settled row stays
   * untouched rather than being rewritten every five minutes for ever.
   *
   * @param serverId the registration the pass ran against, may be null for an
   *          account predating the registry — there is no row to settle
   */
  public void settle(Long serverId) {
    if (serverId == null || serverId <= 0) {
      return;
    }
    flushIfDue(serverId);
  }

  /**
   * Writes a server's pending observations to its row when enough time has
   * passed since the last write.
   *
   * <p>
   * The first pass after a restart always proceeds, because nothing has been
   * flushed yet: a behaviour that started while eXo was down is in the drawer as
   * soon as the first sweep sees it, and a record that should already have gone
   * goes then too.
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
    // Never null, and deliberately allowed to be empty: an empty batch is what a
    // converged account produces, and it is precisely the account whose summary
    // most needs pruning. The storage writes only if the result differs from
    // what is stored, so this costs a read and nothing else.
    Map<Observation, Long> increments = pending.remove(serverId);
    if (increments == null) {
      increments = Map.of();
    }
    try {
      caldavServerStorage.mergeObservedQuirks(serverId, increments, retention(serverId, increments));
    } catch (RuntimeException e) {
      // The summary is diagnostic. A row that cannot be written must never end
      // a sweep, and the next interval simply counts from where this one left
      // off — minus what this attempt was holding, which nothing depends on.
      LOG.debug("The observed behaviours of CalDAV server {} could not be recorded", serverId, e);
    }
  }

  /**
   * What this write is allowed to forget on one server.
   *
   * <p>
   * <b>Two ways a record can be replaced, and they carry different weight.</b>
   * A case <i>observed in this pass</i> replaces the older record of the same
   * property at once — contemporaneous evidence, on this very server, and the
   * property is demonstrably not being reported under its own name in the same
   * breath. A case <i>excused</i> on the server replaces it only once that
   * record has gone quiet, because an excusal is a permanent decision rather
   * than an observation.
   *
   * <p>
   * <b>The second exists because the first stops firing exactly when it
   * works.</b> Excusing a case is what stops it being observed: eXo acts on it,
   * the copies converge, the case falls silent — so supersession by observation
   * alone could only clean up the old record <i>while the problem still
   * existed</i>. Fix the problem and the stale entry outlived the fix by the
   * whole retention window, all the while offering an administrator a broader,
   * more dangerous excusal for something already solved. Every later
   * reclassification would have had the same shape.
   *
   * <p>
   * <b>A property observed in this same batch is never superseded, by either
   * route.</b> Both can be true at once — a server can drop the organizer of an
   * appointment and lose one from a real meeting — and without this the two
   * records would take turns erasing each other on every sweep.
   *
   * @param serverId the registration being written
   * @param increments what this batch saw
   * @return the rules for this write, never null
   */
  private Retention retention(long serverId, Map<Observation, Long> increments) {
    Set<String> seen = increments.keySet().stream().map(Observation::property).collect(Collectors.toSet());
    Set<String> observed = seen.stream()
                               .map(ServerQuirk::superseding)
                               .flatMap(Optional::stream)
                               .filter(property -> !seen.contains(property))
                               .collect(Collectors.toSet());
    Set<String> settled = supersededByExcusal(serverId).stream()
                                                       .filter(property -> !seen.contains(property))
                                                       .collect(Collectors.toSet());
    return new Retention(observed, settled, supersededGraceDays, retentionDays, LocalDate.now().toEpochDay());
  }

  /**
   * The properties whose records a case already excused on this server replaces.
   *
   * <p>
   * Read from the row rather than from the catalogue alone, for the same reason
   * the observed route is: a deployment whose server has never shown the case,
   * and whose administrator has therefore never excused it, keeps its older
   * records untouched.
   *
   * @param serverId the registration
   * @return the property names, never null
   */
  private Set<String> supersededByExcusal(long serverId) {
    CaldavServer server = caldavServerStorage.getServerById(serverId);
    if (server == null) {
      return Set.of();
    }
    return Arrays.stream(ServerQuirk.values())
                 .filter(quirk -> quirk.getSupersedes() != null && excusedOn(server, quirk))
                 .map(ServerQuirk::getSupersedes)
                 .collect(Collectors.toSet());
  }

  /**
   * Whether one catalogue entry is ticked on a registration.
   *
   * <p>
   * The list it would have been written into is decided the same way the drawer
   * decides it — effect first, direction only then — so a tick and the reading
   * of that tick cannot drift apart.
   *
   * @param server the registration, as stored
   * @param quirk the catalogue entry
   * @return true when the row carries it
   */
  private boolean excusedOn(CaldavServer server, ServerQuirk quirk) {
    String list;
    if (quirk.getEffect() == ServerQuirkEffect.OMIT) {
      list = server.getOmittedProperties();
    } else {
      list = quirk.getDirection() == ServerQuirkDirection.ADDED ? server.getIgnoredProperties()
                                                                : server.getDroppedProperties();
    }
    return quirk.getPatterns().stream().anyMatch(pattern -> ServerQuirk.listMatches(list, pattern));
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
    // The omission list has no deployment-wide fallback and never had one: it
    // changes what eXo writes, and no property file has ever been allowed to do
    // that. Null and empty mean the same thing here.
    String omitted = StringUtils.defaultString(server.getOmittedProperties());
    server.setIgnoredProperties(ignored);
    server.setDroppedProperties(dropped);
    server.setObservedQuirks(excusalOf(server.getObservedQuirks(), ignored, dropped, omitted));
    return server;
  }

  /**
   * Marks each observed behaviour with whether the lists in force cover it.
   *
   * @param observed what the server has been seen doing, may be null
   * @param ignored the patterns in force for a property the server adds
   * @param dropped the patterns in force for a property the server does not
   *          keep
   * @param omitted the cases eXo already leaves out of what it writes here
   * @return the same behaviours, each carrying its excusal
   */
  private List<ObservedQuirk> excusalOf(List<ObservedQuirk> observed, String ignored, String dropped, String omitted) {
    if (observed == null) {
      return List.of();
    }
    return observed.stream()
                   .map(quirk -> new ObservedQuirk(quirk.quirkId(),
                                                   quirk.properties(),
                                                   quirk.direction(),
                                                   quirk.effect(),
                                                   quirk.count(),
                                                   excused(quirk, listFor(quirk, ignored, dropped, omitted)),
                                                   quirk.patterns()))
                   .toList();
  }

  /**
   * Which of the two lists decides whether a behaviour is excused.
   *
   * <p>
   * <b>The effect decides first, and the direction only then.</b> A behaviour
   * whose answer is that eXo stops writing something is recorded in the omission
   * list, not in either tolerance list — the two kinds of decision are stored
   * apart precisely so that reading one can never be mistaken for reading the
   * other.
   *
   * @param quirk the observed behaviour
   * @param ignored the patterns in force for a property the server adds
   * @param dropped the patterns in force for a property the server does not
   *          keep
   * @param omitted the cases eXo already leaves out of what it writes here
   * @return the list that would carry it
   */
  private String listFor(ObservedQuirk quirk, String ignored, String dropped, String omitted) {
    if (quirk.effect() == ServerQuirkEffect.OMIT) {
      return omitted;
    }
    return switch (quirk.direction()) {
      case ADDED -> ignored;
      case DROPPED, REWRITTEN -> dropped;
    };
  }

  /**
   * Whether the list in force already covers a behaviour.
   *
   * <p>
   * Asked of every property the entry covers, not of one of them, because a
   * grouped entry stands for a family: the list is read with the same matcher
   * the comparison uses, so a family stored as {@code X-MOZ-*} and an older
   * operator setting naming one member both read as "this is already excused".
   *
   * @param quirk the observed behaviour
   * @param patterns the list in force, may be null or blank
   * @return true when the list covers it
   */
  private boolean excused(ObservedQuirk quirk, String patterns) {
    if (StringUtils.isBlank(patterns) || quirk.properties() == null) {
      return false;
    }
    return quirk.properties().stream().anyMatch(property -> matches(patterns, property));
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
