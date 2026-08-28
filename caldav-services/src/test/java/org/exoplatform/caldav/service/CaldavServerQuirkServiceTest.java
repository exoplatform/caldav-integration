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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.caldav.ics.IcsEquivalence;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.IcsDivergence;
import org.exoplatform.caldav.model.ObservedQuirk;
import org.exoplatform.caldav.model.ServerQuirk;
import org.exoplatform.caldav.model.ServerQuirkDirection;
import org.exoplatform.caldav.model.ServerQuirkEffect;
import org.exoplatform.caldav.storage.CaldavServerStorage;
import org.exoplatform.caldav.utils.ServerQuirkSummary.Observation;

/**
 * What the sweep records about each server, and what the drawer is told.
 *
 * <p>
 * Two jobs, and each has a failure mode worth pinning. Recording has to stay
 * cheap — the sweep runs per user, so an unthrottled write would hit one row
 * once per account every five minutes — and it must never end a pass, because
 * what it writes is diagnostic and what the pass does is not. Telling the
 * drawer has to resolve the fallback the same way the comparison does, or the
 * boxes would say one thing and the sweep do another.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavServerQuirkServiceTest {

  private static final long                SERVER = 7L;

  @Mock
  private CaldavServerStorage              caldavServerStorage;

  @Mock
  private IcsEquivalence                   icsEquivalence;

  private CaldavServerQuirkService         service;

  /**
   * A service whose observations reach the row immediately, so the throttle is
   * exercised where it is the subject and stays out of the way everywhere else.
   */
  @BeforeEach
  public void aServiceThatWritesEveryTime() {
    service = new CaldavServerQuirkService();
    ReflectionTestUtils.setField(service, "caldavServerStorage", caldavServerStorage);
    ReflectionTestUtils.setField(service, "icsEquivalence", icsEquivalence);
    ReflectionTestUtils.setField(service, "flushSeconds", 0L);
  }

  // ------------------------------------------------------------- recording

  @Test
  public void whatOnePassSawIsAddedToTheRow() {
    service.observe(SERVER,
                    List.of(new IcsDivergence("CONFERENCE", ServerQuirkDirection.DROPPED),
                            new IcsDivergence("X-MOZ-GENERATION", ServerQuirkDirection.ADDED)));

    ArgumentCaptor<Map<Observation, Long>> increments = ArgumentCaptor.forClass(Map.class);
    verify(caldavServerStorage).mergeObservedQuirks(eq(SERVER), increments.capture());
    assertEquals(1L, increments.getValue().get(Observation.of(ServerQuirkDirection.DROPPED, "CONFERENCE")));
    assertEquals(1L, increments.getValue().get(Observation.of(ServerQuirkDirection.ADDED, "X-MOZ-GENERATION")));
  }

  @Test
  public void oneServerIsWrittenOncePerIntervalAndNotOncePerAccount() {
    // The sweep runs per user. Without this, a deployment with five hundred
    // accounts on one server would write that row five hundred times every
    // five minutes to record the same two facts.
    ReflectionTestUtils.setField(service, "flushSeconds", 3600L);
    List<IcsDivergence> seen = List.of(new IcsDivergence("CONFERENCE", ServerQuirkDirection.DROPPED));

    service.observe(SERVER, seen);
    service.observe(SERVER, seen);
    service.observe(SERVER, seen);

    verify(caldavServerStorage, times(1)).mergeObservedQuirks(anyLong(), anyMap());
  }

  @Test
  public void whatTheThrottleHeldBackIsNotLost() {
    ReflectionTestUtils.setField(service, "flushSeconds", 3600L);
    List<IcsDivergence> seen = List.of(new IcsDivergence("CONFERENCE", ServerQuirkDirection.DROPPED));

    service.observe(SERVER, seen);
    service.observe(SERVER, seen);
    service.observe(SERVER, seen);
    ReflectionTestUtils.setField(service, "flushSeconds", 0L);
    service.observe(SERVER, seen);

    ArgumentCaptor<Map<Observation, Long>> increments = ArgumentCaptor.forClass(Map.class);
    verify(caldavServerStorage, times(2)).mergeObservedQuirks(eq(SERVER), increments.capture());
    assertEquals(3L,
                 increments.getAllValues().get(1).get(Observation.of(ServerQuirkDirection.DROPPED, "CONFERENCE")),
                 "the three passes held back must arrive with the next write");
  }

  @Test
  public void anAccountPredatingTheRegistryRecordsNothing() {
    // There is no row to record it against, and inventing one would attach a
    // server's behaviour to whatever registration resolution falls back to.
    service.observe(null, List.of(new IcsDivergence("CONFERENCE", ServerQuirkDirection.DROPPED)));

    verifyNoInteractions(caldavServerStorage);
  }

  @Test
  public void aRowThatCannotBeWrittenDoesNotEndThePass() {
    // The summary is diagnostic. Whatever the sweep is doing with this copy
    // must not depend on being able to record what it saw.
    doThrow(new IllegalStateException("no database")).when(caldavServerStorage).mergeObservedQuirks(anyLong(), anyMap());

    service.observe(SERVER, List.of(new IcsDivergence("CONFERENCE", ServerQuirkDirection.DROPPED)));

    verify(caldavServerStorage).mergeObservedQuirks(anyLong(), anyMap());
  }

  @Test
  public void aDeletedRegistrationLeavesNothingToBeWrittenAgainstAReusedIdentifier() {
    ReflectionTestUtils.setField(service, "flushSeconds", 3600L);
    service.observe(SERVER, List.of(new IcsDivergence("CONFERENCE", ServerQuirkDirection.DROPPED)));

    service.forget(SERVER);
    ReflectionTestUtils.setField(service, "flushSeconds", 0L);
    service.observe(SERVER, List.of(new IcsDivergence("X-BM-FOO", ServerQuirkDirection.ADDED)));

    ArgumentCaptor<Map<Observation, Long>> increments = ArgumentCaptor.forClass(Map.class);
    verify(caldavServerStorage, times(2)).mergeObservedQuirks(eq(SERVER), increments.capture());
    assertFalse(increments.getAllValues()
                          .get(1)
                          .containsKey(Observation.of(ServerQuirkDirection.DROPPED, "CONFERENCE")),
                "what was pending for the deleted row must not resurface");
  }

  // --------------------------------------------------- telling the drawer

  @Test
  public void aRegistrationThatHasNeverBeenAskedShowsTheDeploymentWideLists() {
    // The same fallback the comparison applies, resolved here so the boxes and
    // the sweep cannot say different things.
    givenGlobals("X-MOZ-*", "CONFERENCE");
    CaldavServer server = registration(null, null, quirk("dropsConference", "CONFERENCE", ServerQuirkDirection.DROPPED, ServerQuirkEffect.TOLERATE));

    service.decorate(server);

    assertEquals("CONFERENCE", server.getDroppedProperties());
    assertEquals("X-MOZ-*", server.getIgnoredProperties());
    assertTrue(server.getObservedQuirks().get(0).excused(), "and what it excuses must show as ticked");
  }

  @Test
  public void aRegistrationWithItsOwnListsIgnoresTheDeploymentWideOnes() {
    givenGlobals("", "CONFERENCE");
    CaldavServer server = registration(null, "", quirk("dropsConference", "CONFERENCE", ServerQuirkDirection.DROPPED, ServerQuirkEffect.TOLERATE));

    service.decorate(server);

    assertEquals("", server.getDroppedProperties());
    assertFalse(server.getObservedQuirks().get(0).excused(),
                "an empty list is an answer: this server excuses nothing, whatever the deployment says");
  }

  @Test
  public void aBehaviourIsTickedFromTheListItsDirectionBelongsTo() {
    // Excusing a property the server ADDS goes through the ignored list;
    // excusing one it does not KEEP goes through the dropped list. Reading the
    // wrong one would tick a box the sweep does not honour.
    givenGlobals("X-MOZ-*", "");
    CaldavServer server = registration(null,
                                       null,
                                       quirk("addsCompatibilityMarkers", "X-MOZ-GENERATION", ServerQuirkDirection.ADDED, ServerQuirkEffect.TOLERATE),
                                       quirk("dropsConference", "CONFERENCE", ServerQuirkDirection.DROPPED, ServerQuirkEffect.TOLERATE));

    service.decorate(server);

    assertTrue(server.getObservedQuirks().get(0).excused());
    assertFalse(server.getObservedQuirks().get(1).excused());
  }

  @Test
  public void aRewrittenValueIsTickedFromTheDroppedList() {
    givenGlobals("", "DESCRIPTION");
    CaldavServer server = registration(null,
                                       null,
                                       quirk("rewritesDescription", "DESCRIPTION", ServerQuirkDirection.REWRITTEN, ServerQuirkEffect.TOLERATE));

    service.decorate(server);

    assertTrue(server.getObservedQuirks().get(0).excused());
  }

  @Test
  public void aPayloadChangingBehaviourIsTickedFromTheOmissionListAndNotFromATolerance() {
    // The two kinds of decision are stored apart on purpose. Reading a payload
    // decision out of a tolerance list would tick a box the writer does not
    // honour - and, worse, reading a tolerance out of the omission list would
    // let one kind of decision quietly become the other.
    givenGlobals("", "SOLO-ORGANIZER");
    CaldavServer server = registration(null,
                                       null,
                                       quirk("omitsSoloOrganizer",
                                             ServerQuirk.SOLO_ORGANIZER,
                                             ServerQuirkDirection.DROPPED,
                                             ServerQuirkEffect.OMIT));

    service.decorate(server);

    assertFalse(server.getObservedQuirks().get(0).excused(),
                "a dropped list naming it must not tick a box that changes what eXo writes");

    server = registration(null,
                          null,
                          quirk("omitsSoloOrganizer",
                                ServerQuirk.SOLO_ORGANIZER,
                                ServerQuirkDirection.DROPPED,
                                ServerQuirkEffect.OMIT));
    server.setOmittedProperties(ServerQuirk.SOLO_ORGANIZER);

    service.decorate(server);

    assertTrue(server.getObservedQuirks().get(0).excused(), "the omission list is what ticks it");
  }

  @Test
  public void theOmissionListHasNoDeploymentWideFallback() {
    // Nothing in a property file has ever been allowed to change what eXo
    // writes into somebody's calendar, and this does not start now.
    givenGlobals("X-MOZ-*", "CONFERENCE");
    CaldavServer server = registration(null,
                                       null,
                                       quirk("omitsSoloOrganizer",
                                             ServerQuirk.SOLO_ORGANIZER,
                                             ServerQuirkDirection.DROPPED,
                                             ServerQuirkEffect.OMIT));

    service.decorate(server);

    assertFalse(server.getObservedQuirks().get(0).excused());
  }

  @Test
  public void nothingIsDecoratedOntoNothing() {
    assertEquals(null, service.decorate(null));
    verifyNoInteractions(caldavServerStorage);
    verify(icsEquivalence, never()).getGlobalIgnoredProperties();
  }

  /**
   * Wires the deployment-wide fallback the decoration resolves against.
   *
   * @param ignored the deployment-wide ignored list
   * @param dropped the deployment-wide dropped list
   */
  private void givenGlobals(String ignored, String dropped) {
    org.mockito.Mockito.lenient().when(icsEquivalence.getGlobalIgnoredProperties()).thenReturn(ignored);
    org.mockito.Mockito.lenient().when(icsEquivalence.getGlobalDroppedProperties()).thenReturn(dropped);
  }

  /**
   * A registration carrying the given lists and observed behaviours.
   *
   * @param ignored its stored ignored list, null when never asked
   * @param dropped its stored dropped list, null when never asked
   * @param quirks what it has been seen doing
   * @return the registration
   */
  private CaldavServer registration(String ignored, String dropped, ObservedQuirk... quirks) {
    CaldavServer server = new CaldavServer();
    server.setId(SERVER);
    server.setIgnoredProperties(ignored);
    server.setDroppedProperties(dropped);
    server.setObservedQuirks(List.of(quirks));
    return server;
  }

  /**
   * One observed behaviour, as the storage maps it out of the stored summary.
   *
   * @param quirkId identifier of the catalogue entry describing it
   * @param property the property it was seen on
   * @param direction which way it pointed
   * @param effect whether ticking it changes what eXo notices or what eXo writes
   * @return the behaviour, not yet excused
   */
  private ObservedQuirk quirk(String quirkId, String property, ServerQuirkDirection direction, ServerQuirkEffect effect) {
    return new ObservedQuirk(quirkId, property, direction, effect, 12L, false, List.of(property));
  }
}
