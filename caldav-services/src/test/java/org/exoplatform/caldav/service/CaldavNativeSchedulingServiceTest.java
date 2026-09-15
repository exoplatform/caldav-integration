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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.model.Event;
import org.exoplatform.caldav.LogRecorder;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;

/**
 * Whether an invitee's copy is written by eXo or delivered by the organizer's
 * own server (EXO-90247).
 *
 * <p>
 * The duplicate this decides against is not hypothetical: with X inviting Y on
 * a space event, Y's eXo mail address being a mailbox of the same BlueMind as
 * X's account, the meeting appeared twice in Y's BlueMind calendar and twice in
 * Y's eXo agenda. eXo writes one copy per invited user, and BlueMind — reading
 * an {@code ORGANIZER} line eXo deliberately leaves without a
 * {@code SCHEDULE-AGENT}, which RFC 6638 section 7.1 reads as {@code SERVER} —
 * delivers a second one of its own.
 *
 * <p>
 * <b>The refusals are the subject, not the acceptance.</b> Answering yes takes
 * a copy away from somebody; answering no leaves a duplicate that the inbound
 * half then keeps out of the agenda anyway. So every clause has a test that
 * turns exactly one fact off and asserts the copy is still written, and the
 * suite is written that way round on purpose.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavNativeSchedulingServiceTest {

  /** The organizer: their copy is the one the server schedules from. */
  private static final long   ORGANIZER = 1L;

  /** The invitee whose mailbox is on the organizer's own server. */
  private static final long   INVITEE   = 2L;

  /** The declared server both accounts sit on. */
  private static final long   SERVER    = 7L;

  /** The event they are both on. */
  private static final long   EVENT     = 55L;

  /** The organizer's BlueMind principal. */
  private static final String ORGANIZER_PRINCIPAL = "/dav/principals/__uids__/751E7A20-4D5E-4B7A-8C61-2E0D7A4B9C13";

  /** The invitee's, a different account of the same server. */
  private static final String INVITEE_PRINCIPAL   = "/dav/principals/__uids__/8E8E1B31-06A4-4F92-9D18-5C7E1B2A64F0";

  /** What the organizer's copy names the organizer by. */
  private static final String ORGANIZER_ADDRESS   = "xavier@bluemind.test";

  /** What it names the invitee by, and their login on the server. */
  private static final String INVITEE_ADDRESS     = "yvonne@bluemind.test";

  @Mock
  private CaldavConnectorStorage          caldavConnectorStorage;

  @Mock
  private CaldavConnectionIdentityService caldavConnectionIdentityService;

  @Mock
  private AgendaEventIcsMapper            agendaEventIcsMapper;

  @Mock
  private CaldavCopyConsent               caldavCopyConsent;

  @InjectMocks
  private CaldavNativeSchedulingService   service;

  /**
   * Two BlueMind accounts of one server, the organizer receiving copies, the
   * invitee written as an attendee under their own mailbox. Every test below
   * either asserts this answers yes, or turns one fact off.
   */
  @BeforeEach
  public void twoBlueMindAccountsOfOneServer() {
    lenient().when(caldavConnectorStorage.getCaldavSetting(INVITEE)).thenReturn(account(INVITEE_ADDRESS, SERVER));
    lenient().when(caldavConnectorStorage.getCaldavSetting(ORGANIZER)).thenReturn(account(ORGANIZER_ADDRESS, SERVER));
    lenient().when(caldavCopyConsent.copiesEnabled(ORGANIZER)).thenReturn(true);
    lenient().when(caldavConnectionIdentityService.principalOf(ORGANIZER, SERVER)).thenReturn(ORGANIZER_PRINCIPAL);
    lenient().when(caldavConnectionIdentityService.principalOf(INVITEE, SERVER)).thenReturn(INVITEE_PRINCIPAL);
    lenient().when(agendaEventIcsMapper.addressOf(ORGANIZER)).thenReturn(ORGANIZER_ADDRESS);
    lenient().when(agendaEventIcsMapper.addressOf(INVITEE)).thenReturn(INVITEE_ADDRESS);
  }

  // --------------------------------------------------------------- the case

  @Test
  public void aBlueMindInviteeOfABlueMindOrganizerIsInvitedByTheServer() {
    assertTrue(service.deliveredNatively(meeting(), INVITEE));
  }

  /**
   * The line an administrator reads when a copy is missing on purpose, said
   * once per (event, invitee) however many sweeps ask.
   */
  @Test
  public void theSkipIsAnnouncedOnceWithItsReason() {
    try (LogRecorder log = new LogRecorder(CaldavNativeSchedulingService.class)) {
      assertTrue(service.deliveredNatively(meeting(), INVITEE));
      assertTrue(service.deliveredNatively(meeting(), INVITEE));

      List<ILoggingEvent> announced = log.events().stream().filter(event -> event.getLevel() == Level.INFO).toList();
      assertEquals(1, announced.size(), "the seeding pass reaches this decision every sweep; the line is said once");
      String said = announced.get(0).getFormattedMessage();
      assertTrue(said.contains("event " + EVENT), said);
      assertTrue(said.contains("user " + INVITEE), said);
      assertTrue(said.contains("organizer " + ORGANIZER), said);
      assertTrue(said.contains("server " + SERVER), said);
    }
  }

  // ------------------------------------------------------- clause by clause

  /**
   * Clause 1: the copy that makes the server schedule is never the one skipped.
   *
   * <p>
   * <b>What this pin does not prove.</b> Deleting the identity comparison
   * leaves this green, and that is not a gap in the test but a fact about the
   * clauses: asked about the organizer, the registry answers the same principal
   * on both sides, so clause 4's inequality refuses the case anyway. The
   * comparison is kept as a guard against that coupling changing — and as the
   * readable statement of the rule — rather than as the thing doing the work
   * today. The half of clause 1 that <i>is</i> load-bearing is the creator
   * check, pinned separately below.
   */
  @Test
  public void theOrganizersOwnCopyIsNeverSkipped() {
    assertFalse(service.deliveredNatively(meeting(), ORGANIZER));
  }

  /** Clause 2: a BlueMind invitee whose organizer is elsewhere gets eXo's copy. */
  @Test
  public void aBlueMindInviteeOfAnOrganizerOnAnotherServerStillGetsACopy() {
    when(caldavConnectorStorage.getCaldavSetting(ORGANIZER)).thenReturn(account("xavier@stalwart.local", 9L));

    assertFalse(service.deliveredNatively(meeting(), INVITEE),
                "no organizer-bearing object reaches the invitee's server, so nothing is delivered natively");
  }

  /** Clause 2: an organizer with no CalDAV account at all is the same case. */
  @Test
  public void anOrganizerWithNoAccountAtAllStillHasTheCopyWritten() {
    when(caldavConnectorStorage.getCaldavSetting(ORGANIZER)).thenReturn(new CaldavUserSetting());

    assertFalse(service.deliveredNatively(meeting(), INVITEE));
  }

  /** Clause 2: an invitee connected to nothing is not skipped either. */
  @Test
  public void anInviteeOnNoDeclaredServerIsNotSkipped() {
    when(caldavConnectorStorage.getCaldavSetting(INVITEE)).thenReturn(account(INVITEE_ADDRESS, 0L));

    assertFalse(service.deliveredNatively(meeting(), INVITEE));
  }

  /**
   * Clause 3: an organizer who turned copies off puts no object on the server,
   * so there is nothing for it to schedule from.
   */
  @Test
  public void anOrganizerWhoRefusesCopiesLeavesNothingToScheduleFrom() {
    when(caldavCopyConsent.copiesEnabled(ORGANIZER)).thenReturn(false);

    assertFalse(service.deliveredNatively(meeting(), INVITEE));
  }

  /** Clause 4: a server whose accounts are not BlueMind's shape is left alone. */
  @Test
  public void aStalwartAccountIsNotSkipped() {
    // Stalwart advertises RFC 6638's calendar-auto-schedule exactly as BlueMind
    // does and was observed storing back what eXo writes, which is why the
    // capability class is not the witness. Its principals are not BlueMind's.
    when(caldavConnectionIdentityService.principalOf(ORGANIZER, SERVER)).thenReturn("/dav/principal/xavier");
    lenient().when(caldavConnectionIdentityService.principalOf(INVITEE, SERVER)).thenReturn("/dav/principal/yvonne");

    assertFalse(service.deliveredNatively(meeting(), INVITEE));
  }

  /** Clause 4: an account whose principal was never recorded answers no. */
  @Test
  public void anAccountWithNoRecordedPrincipalIsNotSkipped() {
    when(caldavConnectionIdentityService.principalOf(INVITEE, SERVER)).thenReturn(null);

    assertFalse(service.deliveredNatively(meeting(), INVITEE));
  }

  /**
   * Clause 4, the EXO-90190 shape: two eXo users on <b>one</b> BlueMind account
   * write into one calendar home, and a server delivers nothing to an account
   * from itself. Both copies stay.
   */
  @Test
  public void twoUsersSharingOneBlueMindAccountBothKeepTheirCopy() {
    when(caldavConnectionIdentityService.principalOf(INVITEE, SERVER)).thenReturn(ORGANIZER_PRINCIPAL);
    // Every other clause is satisfied, so the inequality is the only thing
    // that can refuse: the invitee's mailbox is their own login, and their
    // attendee line is not the organizer's.
    assertFalse(service.deliveredNatively(meeting(), INVITEE));
  }

  /**
   * And the same account spelled in another case is still one account.
   *
   * <p>
   * Only the uid segment is re-cased: BlueMind's path vocabulary is matched as
   * it spells it, so upper-casing the whole path would make this a test of the
   * shape rule rather than of the comparison — which is exactly what it was
   * until a mutant showed the inequality could be deleted with the test still
   * green.
   */
  @Test
  public void theSameAccountRecordedInAnotherCaseIsStillOneAccount() {
    String recased = "/dav/principals/__uids__/" + ORGANIZER_PRINCIPAL.substring(ORGANIZER_PRINCIPAL.lastIndexOf('/') + 1)
                                                                     .toLowerCase();
    when(caldavConnectionIdentityService.principalOf(INVITEE, SERVER)).thenReturn(recased);

    assertFalse(service.deliveredNatively(meeting(), INVITEE));
  }

  /**
   * Clause 5: an organizer with no visible address makes
   * {@code IcsWriter.addPeople} omit ORGANIZER and every ATTENDEE, so no server
   * invites anybody.
   */
  @Test
  public void anOrganizerWithNoVisibleAddressLeavesTheCopyToBeWritten() {
    when(agendaEventIcsMapper.addressOf(ORGANIZER)).thenReturn(null);

    assertFalse(service.deliveredNatively(meeting(), INVITEE));
  }

  /** Clause 5: an invitee with no visible address is written as no attendee. */
  @Test
  public void anInviteeWithNoVisibleAddressIsOnNobodysAttendeeLine() {
    when(agendaEventIcsMapper.addressOf(INVITEE)).thenReturn(" ");

    assertFalse(service.deliveredNatively(meeting(), INVITEE));
  }

  /**
   * Clause 5, the solo shape read from the other end: an invitee spelled by the
   * organizer's own address is dropped from the attendee list by
   * {@code IcsWriter.guests}, so nothing invites them.
   */
  @Test
  public void anInviteeSpelledByTheOrganizersOwnAddressIsNotAnAttendee() {
    when(agendaEventIcsMapper.addressOf(INVITEE)).thenReturn(ORGANIZER_ADDRESS);
    // Their account answers to that address too, so the mailbox clause is
    // satisfied and the organizer comparison is the only thing left to refuse.
    // Without this the test passed against a deleted comparison.
    when(caldavConnectorStorage.getCaldavSetting(INVITEE)).thenReturn(account(ORGANIZER_ADDRESS, SERVER));

    assertFalse(service.deliveredNatively(meeting(), INVITEE));
  }

  /**
   * Clause 6: an invitee whose profile address is not the mailbox their account
   * answers to keeps their copy — the deliberate strict direction.
   */
  @Test
  public void anInviteeWhoseAddressIsNotTheirMailboxKeepsTheirCopy() {
    when(agendaEventIcsMapper.addressOf(INVITEE)).thenReturn("yvonne.other@elsewhere.test");

    assertFalse(service.deliveredNatively(meeting(), INVITEE),
                "eXo cannot tell that an alias resolves on the server, and guesses towards writing the copy");
  }

  /** Clause 6 the other way: the comparison is not case-sensitive. */
  @Test
  public void aMailboxSpelledInAnotherCaseIsStillTheSameMailbox() {
    when(agendaEventIcsMapper.addressOf(INVITEE)).thenReturn(INVITEE_ADDRESS.toUpperCase());

    assertTrue(service.deliveredNatively(meeting(), INVITEE));
  }

  // -------------------------------------------------------------- the edges

  @Test
  public void anEventNobodyCouldReadIsNotADecision() {
    assertFalse(service.deliveredNatively(null, INVITEE));
  }

  @Test
  public void anEventWithNoCreatorIsNotADecision() {
    Event event = meeting();
    event.setCreatorId(0L);

    assertFalse(service.deliveredNatively(event, INVITEE));
  }

  /**
   * Not knowing is not a reason to withhold a copy: any failure while deciding
   * answers "write it".
   */
  @Test
  public void aFailureWhileDecidingWritesTheCopy() {
    when(caldavConnectorStorage.getCaldavSetting(INVITEE)).thenThrow(new IllegalStateException("settings unreadable"));

    assertFalse(service.deliveredNatively(meeting(), INVITEE));
  }

  /**
   * @return the meeting X called and Y is invited to
   */
  private Event meeting() {
    Event event = new Event();
    event.setId(EVENT);
    event.setCreatorId(ORGANIZER);
    return event;
  }

  /**
   * @param username the mailbox the account answers to
   * @param serverId the declared server it is on
   * @return a connected account
   */
  private CaldavUserSetting account(String username, long serverId) {
    CaldavUserSetting settings = new CaldavUserSetting();
    settings.setUsername(username);
    settings.setPassword("secret");
    settings.setServerId(serverId);
    return settings;
  }
}
