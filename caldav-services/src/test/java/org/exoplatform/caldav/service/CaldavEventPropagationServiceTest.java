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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.caldav.LogRecorder;
import org.exoplatform.agenda.constant.AgendaEventModificationType;
import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.EventAttendee;
import org.exoplatform.agenda.model.EventAttendeeList;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.PendingPush;
import org.exoplatform.caldav.model.PendingPushKind;
import org.exoplatform.caldav.storage.CaldavPendingPushStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.identity.provider.SpaceIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * What happens to a meeting in eXo — created, edited, cancelled, destroyed —
 * and what each of those does to the calendar copies people hold.
 *
 * <p>
 * Two regressions are pinned here. The edit path did nothing at all: the only
 * push of an event was the one the editing user's own browser made, so an
 * organiser who moved a meeting moved it on their own phone and on nobody
 * else's. And the creation path did not exist (EXO-89754): a new meeting only
 * ever acquired a copy once somebody edited or answered it, or once the
 * background seeding pass happened to find it in its upcoming window.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavEventPropagationServiceTest {

  private static final long                     EVENT     = 8801L;

  private static final long                     ALICE     = 11L;

  private static final long                     BOB       = 22L;

  private static final long                     CAROL     = 33L;

  private static final long                     DAVE      = 55L;

  private static final long                     ERIN      = 66L;

  private static final long                     FRANK     = 77L;

  /**
   * An exceptional occurrence of the meeting. Its copy lives under the series'
   * identity, which is why answering it has to resolve the parent.
   */
  private static final long                     OCCURRENCE = 8802L;

  /**
   * The addresses that name the answerer on somebody <b>else's</b> copy.
   *
   * <p>
   * Both spellings, most specific first, as {@code addressesNaming} offers
   * them: a deployment holds copies written under either rule.
   */
  private static final List<String>             CAROL_ADDRESSES =
                                                                List.of("carol@stalwart.local", "carol@example.org");

  /**
   * Whoever created the meeting. Never one of the identities a creation test
   * expects a copy for: their own copy is written by their browser, not here.
   */
  private static final long                     AUTHOR    = 44L;

  /** How many refusals the retry argues with before it gives up, under test. */
  private static final int                      MAX_ATTEMPTS = 3;

  /**
   * The exact set agenda broadcasts when a date poll is confirmed.
   *
   * <p>
   * Copied from {@code AgendaEventServiceImpl.selectEventDateOption} on
   * {@code origin/develop} of {@code Meeds-io/agenda}, which builds
   * {@code modificationTypes} out of these three and nothing else before
   * {@code Utils.broadcastEvent(listenerService, POST_UPDATE_AGENDA_EVENT_EVENT, ...)}.
   * Written out here rather than approximated, because the whole confirmation
   * leg is keyed on what is in this set — and what is <b>not</b> in it,
   * {@code STATUS_UPDATED}, is the mistake that would make a confirmed poll
   * reach nobody, silently, for ever.
   */
  private static final Set<AgendaEventModificationType> A_POLL_CONFIRMATION =
                                                                            EnumSet.of(AgendaEventModificationType.UPDATED,
                                                                                       AgendaEventModificationType.DATE_OPTION_SELECTED,
                                                                                       AgendaEventModificationType.SWITCHED_DATE_POLL_TO_EVENT);

  /**
   * What agenda broadcasts when date options are added to an event that was a
   * meeting — every one of them on the invisible list, which is exactly why
   * the retirement is asked before that list is consulted.
   */
  private static final Set<AgendaEventModificationType> AN_EDIT_A_COPY_CANNOT_SHOW =
                                                                            EnumSet.of(AgendaEventModificationType.UPDATED,
                                                                                       AgendaEventModificationType.DATE_OPTION_CREATED);

  private static final Set<AgendaEventModificationType> A_REAL_EDIT =
                                                                    EnumSet.of(AgendaEventModificationType.UPDATED,
                                                                               AgendaEventModificationType.START_DATE_UPDATED);

  @Mock
  private CaldavPushService                     caldavPushService;

  @Mock
  private CaldavSyncStorage                     caldavSyncStorage;

  @Mock
  private AgendaEventService                    agendaEventService;

  @Mock
  private AgendaEventAttendeeService            agendaEventAttendeeService;

  @Mock
  private IdentityManager                       identityManager;

  @Mock
  private CaldavPendingInvitationService        caldavPendingInvitationService;

  /**
   * A real store rather than a mock, and that is the point of these tests.
   *
   * <p>
   * What has to be proved is that a copy converges <b>after</b> a push has
   * failed — that the failure leaves something behind, and that a later pass
   * finds that something and acts on it. A mocked store would have the test
   * hand the retry the very record it is supposed to have found, which proves
   * that the retry can read a list. The two halves have to meet in state
   * nobody staged.
   */
  @Spy
  private CaldavPendingPushStorage              caldavPendingPushStorage = new InMemoryPendingPushStorage();

  /**
   * The real rule rather than a mock, and deliberately: whether an event may
   * hold a copy is what decides between a rewrite and a retirement here, and a
   * mocked policy would answer whatever a test told it to rather than what the
   * event's status actually says.
   */
  @Spy
  private CaldavCopyPolicy                      caldavCopyPolicy = new CaldavCopyPolicy();

  @InjectMocks
  private CaldavEventPropagationService         service;

  /**
   * By default the edited event is a stand-alone meeting, so the series lookup
   * finds nothing extra to consider.
   */
  @BeforeEach
  public void theEventIsAnOrdinaryMeeting() {
    Event event = new Event();
    event.setId(EVENT);
    event.setParentId(0);
    lenient().when(agendaEventService.getEventById(EVENT)).thenReturn(event);
    // The property is @Value-injected in production and zero here, which would
    // make every obligation look already abandoned.
    ReflectionTestUtils.setField(service, "maxPushAttempts", MAX_ATTEMPTS);
  }

  /**
   * The defect itself: an attendee who already holds a copy gets the edit.
   * Before this service existed, nothing wrote to anybody's copy but the
   * editor's own.
   */
  @Test
  public void anEditReachesTheCopyAnAttendeeAlreadyHolds() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
  }

  /**
   * Every holder, not the first one. Fifty attendees means fifty accounts, and
   * a loop that stopped at one would look like it worked.
   */
  @Test
  public void anEditReachesEveryHolderOfACopy() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    when(caldavPushService.pushAgendaEvent(anyLong(), eq(EVENT))).thenReturn(new ObjectSync());

    assertEquals(2, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
    verify(caldavPushService).pushAgendaEvent(BOB, EVENT);
  }

  /**
   * The guard the task asks for by name. An attendee who has never had a copy
   * of this meeting must not acquire one because somebody edited it — seeding
   * a copy is a different decision, taken elsewhere.
   */
  @Test
  public void anAttendeeWithNoCopyDoesNotAcquireOne() {
    givenNoHolders();

    assertEquals(0, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  /**
   * A mapping with no href is the tombstone a removal leaves, not a copy.
   * Writing to it would re-create on the server the object somebody deleted.
   */
  @Test
  public void aTombstoneMappingIsNotACopyAndIsNotWrittenTo() {
    givenHolders(mapping(1L, 100L, "uid-8801", null));
    // The pair resolves perfectly well: the only thing that must stop this
    // push is the missing href, and a test that let something else stop it
    // would pass against the very bug it pins.
    givenPair(100L, ALICE);

    assertEquals(0, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  /**
   * One unreachable server is an ordinary Tuesday, not a reason the other
   * attendees keep a stale meeting.
   */
  @Test
  public void oneUnreachableServerDoesNotStopTheOthers() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"),
                 mapping(3L, 300L, "uid-8801", "/dav/carol/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    givenPair(300L, CAROL);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());
    when(caldavPushService.pushAgendaEvent(BOB, EVENT))
                                                             .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                                "bob's server is down"));
    when(caldavPushService.pushAgendaEvent(CAROL, EVENT)).thenReturn(new ObjectSync());

    assertEquals(2, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(CAROL, EVENT);
  }

  /**
   * And an error rather than an exception, which is the shape that once escaped
   * a {@code catch (RuntimeException)} here and took a whole sweep with it.
   */
  @Test
  public void aLinkageErrorFromOneServerDoesNotStopTheOthers() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenThrow(new NoSuchMethodError("a half-assembled classpath"));
    when(caldavPushService.pushAgendaEvent(BOB, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(BOB, EVENT);
  }

  /**
   * A change no copy can show buys nobody anything and costs three network
   * round trips per attendee.
   */
  @Test
  public void anEditNoCopyCanShowIsNotCarriedOut() {
    assertEquals(0,
                 service.propagateUpdate(EVENT,
                                         EnumSet.of(AgendaEventModificationType.UPDATED,
                                                    AgendaEventModificationType.COLOR_UPDATED,
                                                    AgendaEventModificationType.ALLOW_INVITE_UPDATED)));

    verify(caldavSyncStorage, never()).getObjectsByEvent(anyLong(), anyInt(), anyInt());
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  /**
   * Availability used to sit in that set, and EXO-89870 took it out.
   *
   * <p>
   * The justification for it being there was that {@code TRANSP} was written
   * {@code OPAQUE} unconditionally, so nothing about availability could reach
   * the object. It can now: an event marked {@code FREE} is copied with
   * {@code TRANSP:TRANSPARENT}. Left in the deny-list, the one modification
   * that moves that property would have been the one modification no rewrite
   * is issued for, and the copy would have gone on claiming the owner's time
   * until the mirror sweep noticed the divergence and repaired it — a repair
   * standing in for an edit nobody carried.
   */
  @Test
  public void aChangeOfAvailabilityIsCarriedBecauseTheCopyStatesIt() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1,
                 service.propagateUpdate(EVENT,
                                         EnumSet.of(AgendaEventModificationType.UPDATED,
                                                    AgendaEventModificationType.AVAILABILITY_UPDATED)));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
  }

  /**
   * The deny-list fails towards carrying, not towards skipping: a modification
   * type nobody has classified yet is pushed, because a modification silently
   * not carried is the defect this whole thing exists to end.
   */
  @Test
  public void aModificationNobodyClassifiedIsCarriedRatherThanSkipped() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, EnumSet.of(AgendaEventModificationType.CONFERENCE_ADDED)));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
  }

  /**
   * Cancelling is an update in agenda, so it takes the ordinary rewrite path —
   * and it must, because that is what puts {@code STATUS:CANCELLED} on the copy
   * rather than making the meeting vanish.
   */
  @Test
  public void aCancellationIsCarriedAsARewriteNotAsARemoval() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1,
                 service.propagateUpdate(EVENT,
                                         EnumSet.of(AgendaEventModificationType.UPDATED,
                                                    AgendaEventModificationType.STATUS_UPDATED)));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
    verify(caldavPushService, never()).deleteEvent(anyLong(), anyString());
  }

  // ------------------------------------------------- date polls (EXO-89863)

  /**
   * The case that makes "just do not write it" insufficient.
   *
   * <p>
   * A confirmed meeting can <b>become</b> a date poll: adding a second date
   * option through {@code updateEvent} sends it back to {@code TENTATIVE} and
   * stretches it over the envelope of the options. The copies written while it
   * was a meeting are then standing for something a calendar must not show, so
   * they are taken away rather than rewritten — rewriting one would put the
   * multi-day block on the holder's calendar, which is the entry this whole
   * change exists to prevent.
   */
  @Test
  public void aMeetingThatBecomesADatePollHasItsCopiesRetired() {
    givenTheEventIs(EventStatus.TENTATIVE);
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);

    assertEquals(2, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).deleteEvent(ALICE, "uid-8801");
    verify(caldavPushService).deleteEvent(BOB, "uid-8801");
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  /**
   * The ordering decision, pinned.
   *
   * <p>
   * Agenda broadcasts {@code DATE_OPTION_CREATED} when options are added, and
   * that type is on the invisible list — for a poll that stays a poll it
   * genuinely changes nothing a copy states. Asked in the order the method used
   * to run in, the cheap "is this worth carrying" question would return early
   * on precisely the edit that has to retire a copy, and the retirement would
   * never happen. Nothing else in the suite would have noticed.
   */
  @Test
  public void aRetirementIsNotSkippedByAnEditNoCopyCanShow() {
    givenTheEventIs(EventStatus.TENTATIVE);
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);

    assertEquals(1, service.propagateUpdate(EVENT, AN_EDIT_A_COPY_CANNOT_SHOW));

    verify(caldavPushService).deleteEvent(ALICE, "uid-8801");
  }

  /**
   * A retirement a server refused is owed as a removal, and settled by the same
   * retry every other removal goes through. Without it, a holder whose account
   * was down when the poll appeared would keep the multi-day block for ever.
   */
  @Test
  public void aRetirementThatFailsStaysOwedAsARemoval() {
    givenTheEventIs(EventStatus.TENTATIVE);
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    org.mockito.Mockito.doThrow(new CaldavPushException(CaldavPushService.SAVE, "alice's server is down"))
                       .when(caldavPushService)
                       .deleteEvent(ALICE, "uid-8801");

    assertEquals(0, service.propagateUpdate(EVENT, A_REAL_EDIT));

    assertEquals(PendingPushKind.REMOVE, onlyObligationOf(ALICE).getKind(), "a retirement is a removal, not a rewrite");
  }

  /**
   * One line per retired copy, at INFO. An administrator watching the first
   * sweep after this deploy is looking for exactly this: which copies went, on
   * whose account — a per-pass total would say how many without saying which.
   */
  @Test
  public void everyRetiredCopyIsAnnouncedOnceAtInfo() {
    givenTheEventIs(EventStatus.TENTATIVE);
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);

    List<ILoggingEvent> announced;
    try (LogRecorder log = new LogRecorder(CaldavEventPropagationService.class)) {
      service.propagateUpdate(EVENT, A_REAL_EDIT);
      announced = log.events()
                     .stream()
                     .filter(recorded -> recorded.getFormattedMessage().contains("has been retired"))
                     .toList();
    }

    assertEquals(2, announced.size(), "one line per copy, not one line per pass");
    assertTrue(announced.stream().allMatch(recorded -> recorded.getLevel() == Level.INFO),
               "a migration an administrator is meant to watch cannot be at debug");
  }

  /**
   * The other half of the rule, and the one a careless guard destroys. A
   * cancelled meeting keeps its copy and has it rewritten: the copy carrying
   * {@code STATUS:CANCELLED} is the only place its attendees are still told the
   * meeting is off, because eXo hides a cancelled event from its own screens.
   */
  @Test
  public void aCancelledMeetingKeepsItsTombstoneAndHasItRewritten() {
    givenTheEventIs(EventStatus.CANCELLED);
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
    verify(caldavPushService, never()).deleteEvent(anyLong(), anyString());
  }

  /**
   * The confirmation leg, on the exact broadcast agenda makes.
   *
   * <p>
   * Nobody holds a copy of a poll — none was ever written — so there is nothing
   * for the rewrite loop to reach, and the meeting has to be seeded as though
   * it had just been created. Keyed on {@code SWITCHED_DATE_POLL_TO_EVENT},
   * which is what {@link #A_POLL_CONFIRMATION} carries and what agenda actually
   * broadcasts.
   */
  @Test
  public void aConfirmedDatePollIsSeededForEverybodyInvited() {
    givenTheEventIs(EventStatus.CONFIRMED);
    givenNoHolders();
    givenInvited(user(ALICE), user(BOB));
    when(caldavPendingInvitationService.seedMeeting(anyLong(), eq(EVENT))).thenReturn(true);

    assertEquals(2, service.propagateUpdate(EVENT, A_POLL_CONFIRMATION));

    verify(caldavPendingInvitationService).seedMeeting(ALICE, EVENT);
    verify(caldavPendingInvitationService).seedMeeting(BOB, EVENT);
  }

  /**
   * Whoever confirmed the poll is skipped, for the reason a creation's author
   * is: their own browser pushes their copy on save, and both writers minting
   * the same iCalendar UID means the second one to record its mapping row dies
   * on the uniqueness constraint. Their identity is the modifier agenda stamped
   * on the event when it stored the confirmation.
   */
  @Test
  public void whoeverConfirmedThePollIsNotWrittenToTwice() {
    Event confirmed = new Event();
    confirmed.setId(EVENT);
    confirmed.setStatus(EventStatus.CONFIRMED);
    confirmed.setModifierId(AUTHOR);
    when(agendaEventService.getEventById(EVENT)).thenReturn(confirmed);
    givenNoHolders();
    givenInvited(user(ALICE), user(AUTHOR));
    when(caldavPendingInvitationService.seedMeeting(anyLong(), eq(EVENT))).thenReturn(true);

    assertEquals(1, service.propagateUpdate(EVENT, A_POLL_CONFIRMATION));

    verify(caldavPendingInvitationService).seedMeeting(ALICE, EVENT);
    verify(caldavPendingInvitationService, never()).seedMeeting(AUTHOR, EVENT);
  }

  /**
   * The fact the confirmation leg rests on, written down so that it is read
   * rather than assumed.
   *
   * <p>
   * {@code AgendaEventServiceImpl.selectEventDateOption} broadcasts
   * {@code UPDATED}, {@code DATE_OPTION_SELECTED} and
   * {@code SWITCHED_DATE_POLL_TO_EVENT}. It does <b>not</b> broadcast
   * {@code STATUS_UPDATED}, although agenda has the constant and uses it
   * elsewhere. A confirmation leg keyed on {@code STATUS_UPDATED} would
   * therefore never run — no copy of a confirmed poll would ever be written,
   * and nothing anywhere would report a failure. {@code UPDATED} cannot serve
   * either: it accompanies every edit and is on the invisible list.
   */
  @Test
  public void agendaDoesNotSayStatusUpdatedWhenAPollIsConfirmed() {
    assertFalse(A_POLL_CONFIRMATION.contains(AgendaEventModificationType.STATUS_UPDATED),
                "keyed on this, the confirmation leg would never run");
    assertTrue(A_POLL_CONFIRMATION.contains(AgendaEventModificationType.SWITCHED_DATE_POLL_TO_EVENT),
               "this is the only type in the broadcast that means 'it stopped being a poll'");
  }

  /**
   * An ordinary edit of an event nobody holds a copy of still seeds nobody. The
   * confirmation leg is a door for one broadcast, not a second creation path
   * that any edit can walk through.
   */
  @Test
  public void anOrdinaryEditOfAnUncopiedMeetingStillSeedsNobody() {
    givenTheEventIs(EventStatus.CONFIRMED);
    givenNoHolders();

    assertEquals(0, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPendingInvitationService, never()).seedMeeting(anyLong(), anyLong());
  }

  /**
   * A confirmation whose event somebody already holds a copy of — a poll copy
   * pushed before this change and not yet retired — is rewritten, not seeded a
   * second time. Seeding on top of an existing mapping is how two writers end
   * up minting the same UID.
   */
  @Test
  public void aConfirmationOfAPollSomebodyStillHoldsIsRewrittenNotSeeded() {
    givenTheEventIs(EventStatus.CONFIRMED);
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_POLL_CONFIRMATION));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
    verify(caldavPendingInvitationService, never()).seedMeeting(anyLong(), anyLong());
  }

  /**
   * An event agenda could not answer for is carried as an ordinary edit, never
   * retired. The pass runs every few minutes and the other direction would
   * delete live meetings from people's calendars on any agenda hiccup; the
   * mirror sweep retires a genuine poll on its own pass anyway.
   */
  @Test
  public void anEventThatCouldNotBeReadIsNotRetired() {
    when(agendaEventService.getEventById(EVENT)).thenThrow(new IllegalStateException("agenda is unavailable"));
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService, never()).deleteEvent(anyLong(), anyString());
  }

  /**
   * A destroyed meeting is removed from every copy that exists, by the
   * iCalendar identity the mapping row carries — agenda no longer has one to
   * ask.
   */
  @Test
  public void aDeletedMeetingIsRemovedFromEveryCopy() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);

    assertEquals(2, service.propagateDeletion(EVENT));

    verify(caldavPushService).deleteEvent(ALICE, "uid-8801");
    verify(caldavPushService).deleteEvent(BOB, "uid-8801");
  }

  /**
   * A deletion never reads agenda for the event, and cannot: the row is already
   * gone by the time the broadcast arrives.
   */
  @Test
  public void aDeletionDoesNotAskAgendaForAnEventItNoLongerHas() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);

    service.propagateDeletion(EVENT);

    verify(agendaEventService, never()).getEventById(anyLong());
  }

  /**
   * One server refusing the removal leaves the others removed.
   */
  @Test
  public void aRemovalThatFailsForOneHolderStillHappensForTheOthers() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    org.mockito.Mockito.doThrow(new CaldavPushException(CaldavPushService.SAVE, "alice's server is down"))
                       .when(caldavPushService)
                       .deleteEvent(ALICE, "uid-8801");

    assertEquals(1, service.propagateDeletion(EVENT));

    verify(caldavPushService).deleteEvent(BOB, "uid-8801");
  }

  /**
   * An override and its series share one calendar object, written under the
   * series' identity. Editing the override alone would otherwise find no copy
   * at all.
   */
  @Test
  public void editingOneOccurrenceReachesTheCopyWrittenUnderItsSeries() {
    long override = 9902L;
    long series = 9900L;
    Event exceptional = new Event();
    exceptional.setId(override);
    exceptional.setParentId(series);
    when(agendaEventService.getEventById(override)).thenReturn(exceptional);
    givenNoHoldersFor(override);
    givenHoldersFor(series, mapping(1L, 100L, "uid-9900", "/dav/alice/mirror/uid-9900.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, override)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(override, A_REAL_EDIT));

    // Pushed under the override's own id, so the merge splices the amended
    // instance into the series object rather than replacing the series.
    verify(caldavPushService).pushAgendaEvent(ALICE, override);
  }

  /**
   * One user, two collections, one meeting: the copy is written once. Two
   * writes would be two attempts to settle the same object, the second of them
   * refused by the first's own ETag.
   */
  @Test
  public void aUserMappedInTwoCollectionsIsWrittenToOnce() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 101L, "uid-8801", "/dav/alice/personal/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(101L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
  }

  /**
   * The fan-out is walked in slices, and the second slice is not forgotten.
   */
  @Test
  public void everySliceOfHoldersIsWalked() {
    List<ObjectSync> first = new ArrayList<>();
    for (long i = 0; i < 50; i++) {
      first.add(mapping(i + 1, 1000L + i, "uid-8801", "/dav/u" + i + "/mirror/uid-8801.ics"));
      givenPair(1000L + i, 1000L + i);
    }
    ObjectSync last = mapping(51L, 2000L, "uid-8801", "/dav/last/mirror/uid-8801.ics");
    givenPair(2000L, 2000L);
    when(caldavSyncStorage.getObjectsByEvent(EVENT, 0, 50)).thenReturn(new PageImpl<>(first, PageRequest.of(0, 50), 51));
    when(caldavSyncStorage.getObjectsByEvent(EVENT, 1, 50)).thenReturn(new PageImpl<>(List.of(last),
                                                                                     PageRequest.of(1, 50),
                                                                                     51));
    when(caldavPushService.pushAgendaEvent(anyLong(), eq(EVENT))).thenReturn(new ObjectSync());

    assertEquals(51, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPushService).pushAgendaEvent(2000L, EVENT);
  }

  /**
   * The defect EXO-89754 is about: a meeting invited somebody, and nothing was
   * ever written to their calendar. Every existing listener reacted to a
   * meeting that already existed, so the copy only appeared once the user
   * edited or answered the event — which is why anyone testing by clicking
   * around saw a feature that worked.
   */
  @Test
  public void aNewMeetingIsCopiedToEverybodyInvitedToIt() {
    givenInvited(user(ALICE), user(BOB));
    when(caldavPendingInvitationService.seedMeeting(ALICE, EVENT)).thenReturn(true);
    when(caldavPendingInvitationService.seedMeeting(BOB, EVENT)).thenReturn(true);

    assertEquals(2, service.propagateCreation(EVENT, AUTHOR));

    verify(caldavPendingInvitationService).seedMeeting(ALICE, EVENT);
    verify(caldavPendingInvitationService).seedMeeting(BOB, EVENT);
  }

  /**
   * The guard against the double push this fix could have introduced.
   *
   * <p>
   * A creation does not reach this service once. Agenda auto-accepts the
   * organiser from inside the very broadcast that says the event was created,
   * which makes it emit {@code responseSaved} too — and this add-on listens to
   * that as well. So the question is not whether a creation triggers one thing
   * or two; it is whether the second one writes. It must not: a second write
   * carrying a fresh {@code DTSTAMP} is exactly the churn EXO-89716 spent a day
   * removing.
   *
   * <p>
   * The whole decision, including "this user already has a copy", is deferred to
   * the seeding service rather than re-implemented here — so this pins that the
   * fan-out asks it and obeys the answer, and writes nothing of its own when it
   * says no.
   */
  @Test
  public void aSecondTriggerOnTheSameCreationWritesNothing() {
    givenInvited(user(ALICE));
    when(caldavPendingInvitationService.seedMeeting(ALICE, EVENT)).thenReturn(true, false);

    assertEquals(1, service.propagateCreation(EVENT, AUTHOR));
    assertEquals(0, service.propagateCreation(EVENT, AUTHOR));

    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  /**
   * A meeting somebody made for themselves reaches nothing here, and — the
   * half that matters — leaves nothing owed either (EXO-89803).
   *
   * <p>
   * This is the finding that decided the shape of EXO-89803, and it is worth
   * stating as a test because reading the log cannot tell it apart from the
   * other possibility. A solo event has one attendee, its author, whose own
   * copy the browser writes; the set is therefore empty and the method returns
   * at DEBUG before it asks anybody anything. What "nothing attempted" costs is
   * that <b>no obligation is recorded</b>: an obligation names a mapping row,
   * a copy that was never written has none, and so a creation that could not be
   * copied is invisible to {@link CaldavEventPropagationService#retryOwedPushes}
   * and to the count the settings page shows. Only the seeding pass carries it,
   * on the next sweep — which is exactly the half-hour wait reported.
   *
   * <p>
   * Pinned so that a later change cannot quietly start claiming the obligation
   * table covers creations. If one ever should, the mapping row has to exist
   * first, and that is a different design.
   */
  @Test
  public void aSoloCreationAttemptsNothingAndOwesNothing() {
    givenInvited(user(AUTHOR));

    assertEquals(0, service.propagateCreation(EVENT, AUTHOR));

    verify(caldavPendingInvitationService, never()).seedMeeting(anyLong(), anyLong());
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
    verify(caldavPendingPushStorage, never()).owe(anyLong(), anyLong(), any(), any(), anyString());
  }

  /**
   * The count the settings page reads is the one eXo is still attempting, for
   * that user, and an unreadable store answers nothing rather than a number
   * nobody can trust.
   */
  @Test
  public void theOwedCountIsWhatIsStillBeingAttempted() {
    caldavPendingPushStorage.owe(701L, ALICE, PendingPushKind.REWRITE, EVENT, "uid-a");
    caldavPendingPushStorage.owe(702L, ALICE, PendingPushKind.REWRITE, EVENT, "uid-b");
    caldavPendingPushStorage.owe(703L, BOB, PendingPushKind.REWRITE, EVENT, "uid-c");

    assertEquals(2L, service.owedCopies(ALICE), "one account's copies, not everybody's");
    assertEquals(0L, service.owedCopies(0L), "no user, no count, and no query");

    for (int refusal = 0; refusal < MAX_ATTEMPTS; refusal++) {
      caldavPendingPushStorage.refused(caldavPendingPushStorage.attemptable(ALICE, MAX_ATTEMPTS, 10).get(0).getId());
    }

    assertEquals(1L, service.owedCopies(ALICE), "a copy eXo gave up on is not somebody waiting for one");
  }

  /**
   * An unreadable store answers nothing rather than a number nobody can trust:
   * claiming a backlog that may not exist is worse than saying nothing.
   */
  @Test
  public void anUnreadableStoreIsNotABacklog() {
    org.mockito.Mockito.doThrow(new IllegalStateException("no database")).when(caldavPendingPushStorage)
                                                     .owedAndStillTrying(ALICE, MAX_ATTEMPTS);

    assertEquals(0L, service.owedCopies(ALICE));
  }

  /**
   * A creation is the one moment where handing out a copy is the instruction,
   * so the set of people written to is agenda's attendee list — never the
   * mapping table, which is empty for an event nobody has a copy of yet. Asking
   * the mapping table is what would have made this fix a no-op.
   */
  @Test
  public void aCreationDoesNotAskWhoAlreadyHoldsACopy() {
    givenInvited(user(ALICE));
    when(caldavPendingInvitationService.seedMeeting(ALICE, EVENT)).thenReturn(true);

    service.propagateCreation(EVENT, AUTHOR);

    verify(caldavSyncStorage, never()).getObjectsByEvent(anyLong(), anyInt(), anyInt());
  }

  /**
   * The double push, measured rather than imagined.
   *
   * <p>
   * The author's own copy is written by their <b>browser</b>, on save, from
   * agenda's connector panel — the one path that worked before this method
   * existed. Writing it here as well is not a harmless duplicate: on a rig
   * (2026-08-27) both writers minted the same stable iCalendar UID, both read
   * "no copy yet", both wrote the object, and the second to record its mapping
   * row died on {@code UQ_CALDAV_OBJECT_SYNC_UID} — an ERROR with a stack trace
   * on the ordinary path of every single creation. No "already copied" check
   * can prevent that, because neither writer had written when both looked.
   *
   * <p>
   * So the author is skipped, and every other attendee — the people who got
   * nothing at all, which is the defect — is written to.
   */
  @Test
  public void theAuthorsOwnCopyIsLeftToTheirBrowser() {
    givenInvited(user(AUTHOR), user(ALICE));
    when(caldavPendingInvitationService.seedMeeting(ALICE, EVENT)).thenReturn(true);

    assertEquals(1, service.propagateCreation(EVENT, AUTHOR));

    verify(caldavPendingInvitationService).seedMeeting(ALICE, EVENT);
    verify(caldavPendingInvitationService, never()).seedMeeting(eq(AUTHOR), anyLong());
  }

  /**
   * A broadcast that names no author writes to everybody invited. Skipping the
   * author is a defence against one concurrent writer, not a rule about who
   * deserves a copy, so an unnamed one must not silently cost an attendee
   * theirs.
   */
  @Test
  public void aCreationThatNamesNoAuthorStillReachesEverybody() {
    givenInvited(user(ALICE));
    when(caldavPendingInvitationService.seedMeeting(ALICE, EVENT)).thenReturn(true);

    assertEquals(1, service.propagateCreation(EVENT, 0L));

    verify(caldavPendingInvitationService).seedMeeting(ALICE, EVENT);
  }

  /**
   * A space invited to a meeting is one attendee row standing for its members.
   * Expanding it here would turn one creation into as many settings reads and
   * writes as the space has members — for people the copy does not even name.
   * They are reached by the background seeding pass, which resolves space
   * membership through agenda's own query.
   */
  @Test
  public void aSpaceAmongTheAttendeesIsLeftToTheSeedingPass() {
    givenInvited(user(ALICE), space(CAROL));
    when(caldavPendingInvitationService.seedMeeting(ALICE, EVENT)).thenReturn(true);

    assertEquals(1, service.propagateCreation(EVENT, AUTHOR));

    verify(caldavPendingInvitationService, never()).seedMeeting(eq(CAROL), anyLong());
  }

  /**
   * One account refusing its copy leaves the other attendees theirs. Fifty
   * attendees means as many accounts on as many servers, and one of them being
   * unreachable is an ordinary Tuesday.
   */
  @Test
  public void oneAccountFailingLeavesTheOtherAttendeesTheirCopy() {
    givenInvited(user(ALICE), user(BOB));
    when(caldavPendingInvitationService.seedMeeting(ALICE, EVENT))
                                                                 .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                                    "alice's server is down"));
    when(caldavPendingInvitationService.seedMeeting(BOB, EVENT)).thenReturn(true);

    assertEquals(1, service.propagateCreation(EVENT, AUTHOR));

    verify(caldavPendingInvitationService).seedMeeting(BOB, EVENT);
  }

  /**
   * Agenda answering nothing about the attendees is not a reason to fail the
   * creation, which is already recorded in eXo.
   */
  @Test
  public void aCreationWhoseAttendeesCannotBeListedWritesNothing() {
    when(agendaEventAttendeeService.getEventAttendees(EVENT)).thenThrow(new IllegalStateException("agenda is unhappy"));

    assertEquals(0, service.propagateCreation(EVENT, AUTHOR));

    verify(caldavPendingInvitationService, never()).seedMeeting(anyLong(), anyLong());
  }

  // ------------------------------------------- EXO-89773: a failed push converges

  /**
   * The defect, stated as the only test that can prove it is fixed: a push that
   * <b>fails</b>, and a copy that is right afterwards anyway.
   *
   * <p>
   * A test showing that a working push works proves nothing here. What was
   * broken is what happened next — nothing. The log said the verification pass
   * would retry; that pass compares the version the server publishes against
   * the version eXo recorded, and an edit that never reached the server does not
   * move the server's version, so the copy was judged untouched before anything
   * was fetched and stayed wrong for ever.
   */
  @Test
  public void aPushThatFailsIsMadeAgainByALaterPass() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT))
                                                        .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                           "alice's server is down"))
                                                        .thenReturn(new ObjectSync());

    assertEquals(0, service.propagateUpdate(EVENT, A_REAL_EDIT), "the edit did not reach the copy");
    assertEquals(1, caldavPendingPushStorage.owed(ALICE), "the failure has to leave a record, or nothing can find it");

    assertEquals(1, service.retryOwedPushes(ALICE), "a later pass writes the copy the edit never reached");

    verify(caldavPushService, times(2)).pushAgendaEvent(ALICE, EVENT);
    assertEquals(0, caldavPendingPushStorage.owed(ALICE), "a copy that has been written is owed nothing");
  }

  /**
   * The obligation is written down <b>before</b> the write is attempted.
   *
   * <p>
   * This is what a {@code catch} block cannot do. A PUT that times out
   * ambiguously, a thread killed halfway through fifty attendees and a platform
   * restarted between two of them all leave no exception for anybody to catch —
   * and all of them leave the record standing. The order is the assertion.
   */
  @Test
  public void whatIsOwedIsRecordedBeforeTheWriteIsAttempted() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    service.propagateUpdate(EVENT, A_REAL_EDIT);

    InOrder order = inOrder(caldavPendingPushStorage, caldavPushService);
    order.verify(caldavPendingPushStorage).owe(1L, ALICE, PendingPushKind.REWRITE, EVENT, "uid-8801");
    order.verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
  }

  /**
   * Every holder is recorded before any of them is written to, so a thread that
   * dies at the third of fifty attendees leaves the other forty-seven owed.
   */
  @Test
  public void everyHolderIsRecordedBeforeTheFirstOneIsWrittenTo() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    when(caldavPushService.pushAgendaEvent(anyLong(), eq(EVENT))).thenReturn(new ObjectSync());

    service.propagateUpdate(EVENT, A_REAL_EDIT);

    InOrder order = inOrder(caldavPendingPushStorage, caldavPushService);
    order.verify(caldavPendingPushStorage).owe(eq(1L), eq(ALICE), any(), any(), anyString());
    order.verify(caldavPendingPushStorage).owe(eq(2L), eq(BOB), any(), any(), anyString());
    order.verify(caldavPushService).pushAgendaEvent(anyLong(), eq(EVENT));
  }

  /**
   * A removal that fails is retried too, and this is the case with <b>no other
   * safety net at all</b>.
   *
   * <p>
   * A destroyed event renders to nothing, and the verification pass
   * deliberately refuses to conclude anything from an empty render — so before
   * this record existed, nothing in eXo would ever have taken that meeting out
   * of the attendee's calendar. They kept a meeting that no longer exists.
   */
  @Test
  public void aRemovalThatFailsIsMadeAgainByALaterPass() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    org.mockito.Mockito.doThrow(new CaldavPushException(CaldavPushService.SAVE, "alice's server is down"))
                       .doNothing()
                       .when(caldavPushService)
                       .deleteEvent(ALICE, "uid-8801");

    assertEquals(0, service.propagateDeletion(EVENT), "the removal did not reach the copy");
    PendingPush owed = onlyObligationOf(ALICE);
    assertEquals(PendingPushKind.REMOVE, owed.getKind(), "a removal must not be recorded as a rewrite");

    assertEquals(1, service.retryOwedPushes(ALICE), "a later pass removes the copy the deletion never reached");

    verify(caldavPushService, times(2)).deleteEvent(ALICE, "uid-8801");
    assertEquals(0, caldavPendingPushStorage.owed(ALICE));
  }

  /**
   * The kind is read from the record and never re-derived, because by the time
   * the retry runs there is nothing left to derive it from: the event was
   * destroyed, so anything asking agenda "should this copy be rewritten or
   * removed?" is asking about a row that no longer exists. Getting it wrong
   * puts a cancelled meeting back into somebody's calendar.
   */
  @Test
  public void aRetriedRemovalIsRemovedAndNeverRewritten() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    org.mockito.Mockito.doThrow(new CaldavPushException(CaldavPushService.SAVE, "alice's server is down"))
                       .when(caldavPushService)
                       .deleteEvent(ALICE, "uid-8801");

    service.propagateDeletion(EVENT);
    service.retryOwedPushes(ALICE);

    // Both halves, because either alone passes against a retry that does
    // nothing at all: the removal was attempted again, and no rewrite was.
    verify(caldavPushService, times(2)).deleteEvent(ALICE, "uid-8801");
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  /**
   * A server that is never going to change its mind is argued with a few times
   * and then left alone.
   *
   * <p>
   * Unbounded retrying is the failure this mechanism could easily have become:
   * one refusing account, three network round trips, every five minutes, for as
   * long as the account exists. The record is kept rather than deleted — it is
   * the only place anybody can see that a copy is wrong and that eXo has
   * stopped putting it right — but it is not read again.
   */
  @Test
  public void aServerThatKeepsRefusingIsNotRetriedForEver() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT))
                                                        .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                           "this server will never take it"));

    service.propagateUpdate(EVENT, A_REAL_EDIT);
    for (int sweep = 0; sweep < MAX_ATTEMPTS + 5; sweep++) {
      service.retryOwedPushes(ALICE);
    }

    // One write from the edit itself, then exactly MAX_ATTEMPTS retries: the
    // sweeps after that read nothing and write nothing.
    verify(caldavPushService, times(MAX_ATTEMPTS + 1)).pushAgendaEvent(ALICE, EVENT);
    assertEquals(1,
                 caldavPendingPushStorage.owed(ALICE),
                 "an abandoned obligation stays as the record that this copy is wrong");
  }

  /**
   * A copy that is genuinely up to date is not re-pushed, on this sweep or any
   * other.
   *
   * <p>
   * The obvious wrong fix to EXO-89773 is to have the sweep re-render and
   * re-push everything so that nothing can be missed. That is exactly the churn
   * EXO-89716 and EXO-89756 spent two days removing — nineteen copies rewritten
   * every five minutes, for ever — and re-introducing it would be a worse defect
   * than the one being fixed. So a copy nobody owes anything to is not read, not
   * rendered and not written.
   */
  @Test
  public void aCopyThatIsUpToDateIsNeverRePushed() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_REAL_EDIT));
    assertEquals(0, service.retryOwedPushes(ALICE), "nothing is owed once the write has landed");
    assertEquals(0, service.retryOwedPushes(ALICE));

    verify(caldavPushService, times(1)).pushAgendaEvent(ALICE, EVENT);
  }

  /**
   * An account nothing ever went wrong on costs the sweep nothing: no
   * obligation, no mapping read, no render, no write.
   */
  @Test
  public void anAccountThatIsOwedNothingCostsTheSweepNoWrite() {
    assertEquals(0, service.retryOwedPushes(ALICE));

    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
    verify(caldavPushService, never()).deleteEvent(anyLong(), anyString());
    verify(caldavSyncStorage, never()).getObjectsByEvent(anyLong(), anyInt(), anyInt());
  }

  /**
   * One unreachable server leaves the others' copies written <b>and</b> its own
   * recorded — the isolation that already existed, now with a consequence.
   */
  @Test
  public void theHolderWhoseServerFailedIsTheOnlyOneStillOwedAWrite() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT))
                                                        .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                           "alice's server is down"));
    when(caldavPushService.pushAgendaEvent(BOB, EVENT)).thenReturn(new ObjectSync());

    service.propagateUpdate(EVENT, A_REAL_EDIT);

    assertEquals(1, caldavPendingPushStorage.owed(ALICE));
    assertEquals(0, caldavPendingPushStorage.owed(BOB), "a copy that was written is owed nothing");
  }

  /**
   * A conflict is the one failure the verification pass genuinely does cover, so
   * it is struck off rather than retried.
   *
   * <p>
   * A conflict means the server's version moved away from the one eXo recorded
   * — which is precisely the gate that pass opens on, so it will fetch, compare
   * and reconcile. Retrying here instead would fight the same conditional write
   * to the same refusal, three times, and then report a copy as abandoned that
   * nothing was ever wrong with.
   */
  @Test
  public void aConflictIsLeftToTheVerificationPassRatherThanRetried() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT))
                                                        .thenThrow(new CaldavPushException(CaldavPushService.CONFLICT,
                                                                                           "somebody wrote it first"));

    service.propagateUpdate(EVENT, A_REAL_EDIT);

    assertEquals(0, caldavPendingPushStorage.owed(ALICE), "a conflict is not an arrear");
  }

  /**
   * A holder who never connected an account is quiet in the log and
   * <b>unchanged in the ledger</b> (EXO-89798).
   *
   * <p>
   * The second half is the one worth pinning. The branch that quietens this
   * sits directly under the conflict branch above, which strikes the
   * obligation off — and folding a known state into that branch is the easy
   * mistake, because both read as "not worth retrying". They are not the same:
   * a conflict means the copy is out there and the verification pass owns it,
   * while an unconnected holder has no copy at all. Striking that off would
   * lose the write for good, and the day they connect nothing would carry the
   * edit to them.
   */
  @Test
  public void anUnconnectedHolderIsStillOwedTheEditTheyDidNotGet() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT))
                                                        .thenThrow(new CaldavPushException(CaldavPushService.NOT_CONNECTED,
                                                                                           "User 1 has no connected CalDAV account"));

    service.propagateUpdate(EVENT, A_REAL_EDIT);

    assertEquals(1,
                 caldavPendingPushStorage.owed(ALICE),
                 "a state only the holder can clear is not a settled obligation");
  }

  /**
   * The reason this matters at the scale the report described: in a space where
   * most members never connected an account, the unconnected ones must cost the
   * others nothing — not their copy, and not their ledger.
   */
  @Test
  public void anUnconnectedHolderDoesNotCostTheOthersTheirEdit() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT))
                                                        .thenThrow(new CaldavPushException(CaldavPushService.NOT_CONNECTED,
                                                                                           "User 1 has no connected CalDAV account"));
    when(caldavPushService.pushAgendaEvent(BOB, EVENT)).thenReturn(new ObjectSync());

    service.propagateUpdate(EVENT, A_REAL_EDIT);

    assertEquals(1, caldavPendingPushStorage.owed(ALICE), "the unconnected holder is still owed their write");
    assertEquals(0, caldavPendingPushStorage.owed(BOB), "a copy that was written is owed nothing");
  }

  /**
   * The same state on the removal path, where being wrong is worse: a removal
   * struck off is a meeting that stays in somebody's calendar after it was
   * deleted in eXo, and nothing else in the add-on would ever take it out.
   */
  @Test
  public void aRemovalOwedToAnUnconnectedHolderStaysOwed() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    org.mockito.Mockito.doThrow(new CaldavPushException(CaldavPushService.NOT_CONNECTED,
                                                        "User 1 has no connected CalDAV account"))
                       .when(caldavPushService)
                       .deleteEvent(ALICE, "uid-8801");

    assertEquals(0, service.propagateDeletion(EVENT), "the removal reached no copy");

    PendingPush owed = onlyObligationOf(ALICE);
    assertEquals(PendingPushKind.REMOVE, owed.getKind(), "the removal is still owed, and still as a removal");
  }

  /**
   * The level itself, on both paths of this class (EXO-89798).
   *
   * <p>
   * The three tests above pin what these branches <i>do</i>, and that is what a
   * reader should care about — but what they do is the same whether the branch
   * is there or not, because the change was never about behaviour. So they
   * cover the branches without discriminating them: delete the branch and they
   * still pass. This is the assertion that does not.
   *
   * <p>
   * One test for both paths rather than one each, and one such test per class
   * rather than one per site: reading the log is a tool for the claim that has
   * no other observable, not a habit. The classification itself is a pure
   * function with its own tests, and the failure half is asserted here too so
   * that quietening everything — the way this change could go wrong — fails.
   */
  @Test
  public void aKnownStateIsRecordedQuietlyOnBothPathsAndAFailureIsNot() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);

    List<ILoggingEvent> recorded;
    try (LogRecorder log = new LogRecorder(CaldavEventPropagationService.class)) {
      when(caldavPushService.pushAgendaEvent(ALICE, EVENT))
                                                          .thenThrow(new CaldavPushException(CaldavPushService.NOT_CONNECTED,
                                                                                             "no account"));
      org.mockito.Mockito.doThrow(new CaldavPushException(CaldavPushService.NOT_CONNECTED, "no account"))
                         .when(caldavPushService)
                         .deleteEvent(ALICE, "uid-8801");

      service.propagateUpdate(EVENT, A_REAL_EDIT);
      service.propagateDeletion(EVENT);
      recorded = onlyRefusals(log.events());
    }

    assertEquals(2, recorded.size(), "the edit and the removal each recorded their refusal once");
    for (ILoggingEvent line : recorded) {
      assertEquals(Level.DEBUG, line.getLevel(), "a holder who never connected an account is not an incident");
      assertNull(line.getThrowableProxy(), "a known state does not need a stack trace");
    }
  }

  /**
   * The other half, kept apart because it is the one that stops this becoming a
   * silencer: a refused write is still a warn and still carries its trace.
   */
  @Test
  public void aRefusedWriteIsStillRecordedAsAnIncident() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);

    List<ILoggingEvent> recorded;
    try (LogRecorder log = new LogRecorder(CaldavEventPropagationService.class)) {
      when(caldavPushService.pushAgendaEvent(ALICE, EVENT))
                                                          .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                             "the server refused it"));

      service.propagateUpdate(EVENT, A_REAL_EDIT);
      recorded = onlyRefusals(log.events());
    }

    assertEquals(1, recorded.size());
    assertEquals(Level.WARN, recorded.get(0).getLevel(), "a copy that never got the edit has to be visible");
    assertNotNull(recorded.get(0).getThrowableProxy(), "a failure keeps the trace that says where it came from");
  }

  /**
   * The refusal lines only, out of everything the pass writes.
   *
   * <p>
   * The propagation pass logs its own bookkeeping around these — what is owed,
   * what landed — and asserting over the lot would pin wording nobody meant to
   * freeze. Selected on the identity that is actually load-bearing here: a line
   * about a copy, at debug or warn.
   *
   * @param events everything the class wrote
   * @return the lines that record a refusal to write or remove a copy
   */
  private List<ILoggingEvent> onlyRefusals(List<ILoggingEvent> events) {
    return events.stream()
                 .filter(e -> e.getLevel() == Level.DEBUG || e.getLevel() == Level.WARN)
                 .filter(e -> e.getMessage().contains("copy"))
                 .toList();
  }

  /**
   * Five edits in a minute owe the copy one write, not five. The obligation
   * describes the copy — "this does not yet show what eXo holds" — rather than
   * queueing the events that made it true.
   */
  @Test
  public void aCopyEditedRepeatedlyIsOwedOneWriteAndNotFive() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT))
                                                        .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                           "alice's server is down"));

    service.propagateUpdate(EVENT, A_REAL_EDIT);
    service.propagateUpdate(EVENT, A_REAL_EDIT);
    service.propagateUpdate(EVENT, A_REAL_EDIT);

    assertEquals(1, caldavPendingPushStorage.owed(ALICE));
  }

  /**
   * A new edit renews the patience: the attempt count goes back to zero, so a
   * copy abandoned last week is tried again when the meeting moves.
   */
  @Test
  public void aFreshEditGivesAnAbandonedCopyAnotherChance() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT))
                                                        .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                           "down for the afternoon"));

    service.propagateUpdate(EVENT, A_REAL_EDIT);
    for (int sweep = 0; sweep < MAX_ATTEMPTS + 2; sweep++) {
      service.retryOwedPushes(ALICE);
    }
    assertEquals(0, service.retryOwedPushes(ALICE), "the copy has been given up on");

    service.propagateUpdate(EVENT, A_REAL_EDIT);
    assertEquals(0, onlyObligationOf(ALICE).getAttempts(), "a new edit is a new obligation, with its own patience");
  }

  /**
   * The retry is wider than the verification pass on purpose: that pass scopes
   * to the MIRROR pair, so a copy sitting in one of the user's own calendars was
   * outside it entirely. An obligation names the copy, whichever collection it
   * lives in.
   */
  @Test
  public void aCopyOutsideTheMirrorIsSettledToo() {
    ObjectSync inPersonalCalendar = mapping(1L, 101L, "uid-8801", "/dav/alice/personal/uid-8801.ics");
    givenHolders(inPersonalCalendar);
    givenPair(101L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT))
                                                        .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                           "alice's server is down"))
                                                        .thenReturn(new ObjectSync());

    service.propagateUpdate(EVENT, A_REAL_EDIT);

    assertEquals(1, service.retryOwedPushes(ALICE));
  }

  /**
   * Bookkeeping that cannot be written is said out loud and does not stop the
   * write it was recorded for. A copy nobody can retry is bad; a copy nobody
   * even tried to write is worse.
   */
  @Test
  public void aRecordThatCannotBeWrittenDoesNotStopTheWrite() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    org.mockito.Mockito.doThrow(new IllegalStateException("the database is unhappy"))
                       .when(caldavPendingPushStorage)
                       .owe(anyLong(), anyLong(), any(), any(), anyString());
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_REAL_EDIT));
  }

  // ------------------------- EXO-89773: the paths that would be silent if wrong

  /**
   * A push that writes <b>nothing</b> leaves the copy owed a write.
   *
   * <p>
   * {@code pushAgendaEvent} answers null rather than throwing when the event
   * belongs to one of the user's own calendars that has no collection to write
   * into. Nothing failed, so nothing is logged — and if that were taken for
   * success the record would be struck off and the copy left behind in exactly
   * the silence this whole change exists to end. It is the one non-exception
   * way for a write not to land.
   */
  @Test
  public void aPushThatWritesNothingLeavesTheCopyStillOwedAWrite() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(null);

    assertEquals(0, service.propagateUpdate(EVENT, A_REAL_EDIT), "nothing was written");
    assertEquals(1, caldavPendingPushStorage.owed(ALICE), "so the copy is still owed a write");
  }

  /**
   * An obligation naming no event is counted as a refusal rather than read for
   * ever.
   *
   * <p>
   * Nothing can render a rewrite with no event behind it, so attempting it will
   * never succeed. Skipping it would leave a row the due query keeps answering
   * and the pass keeps ignoring — work that repeats for ever and changes
   * nothing. Counting it as a refusal is what lets the bound retire it.
   */
  @Test
  public void aRewriteOwedForNoEventIsRefusedRatherThanReadForEver() {
    caldavPendingPushStorage.owe(1L, ALICE, PendingPushKind.REWRITE, null, "uid-8801");

    assertEquals(0, service.retryOwedPushes(ALICE));

    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
    assertEquals(1, onlyObligationOf(ALICE).getAttempts(), "counted, so the bound will eventually retire it");
  }

  /**
   * Arrears that cannot be read do not bring the sweep down with them.
   *
   * <p>
   * This runs inside {@code CaldavSyncService.sync}, before the seeding and the
   * verification pass. An exception escaping here would be caught up there as
   * "the copies could not be verified" and would cost the account both of the
   * passes that follow — a database hiccup on one query silently stopping three
   * unrelated pieces of work.
   */
  @Test
  public void arrearsThatCannotBeReadDoNotBringTheSweepDown() {
    org.mockito.Mockito.doThrow(new IllegalStateException("the database is unhappy"))
                       .when(caldavPendingPushStorage)
                       .attemptable(anyLong(), anyInt(), anyInt());

    assertEquals(0, service.retryOwedPushes(ALICE));
  }

  /**
   * A write that landed but could not be struck off still counts as landed.
   *
   * <p>
   * The two mistakes here are not the same size. An obligation left standing
   * costs one needless re-push on the next sweep; an exception escaping the
   * bookkeeping would abandon the rest of the fan-out, so the attendees after
   * this one would never be written to at all.
   */
  @Test
  public void aWriteThatLandedCountsEvenWhenItCannotBeStruckOff() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    org.mockito.Mockito.doThrow(new IllegalStateException("the database is unhappy"))
                       .when(caldavPendingPushStorage)
                       .settled(anyLong());
    when(caldavPushService.pushAgendaEvent(anyLong(), eq(EVENT))).thenReturn(new ObjectSync());

    assertEquals(2, service.propagateUpdate(EVENT, A_REAL_EDIT), "both copies were written");

    verify(caldavPushService).pushAgendaEvent(BOB, EVENT);
  }

  /**
   * A refusal that cannot be counted does not stop the rest of the batch.
   *
   * <p>
   * The bound is a convenience; the other accounts' copies are not. An
   * exception from the counter would leave every obligation after this one
   * unattempted, which turns a bookkeeping failure into a delivery failure.
   */
  @Test
  public void aRefusalThatCannotBeCountedDoesNotStopTheRestOfTheBatch() {
    caldavPendingPushStorage.owe(1L, ALICE, PendingPushKind.REWRITE, EVENT, "uid-a");
    caldavPendingPushStorage.owe(2L, ALICE, PendingPushKind.REWRITE, 9902L, "uid-b");
    org.mockito.Mockito.doThrow(new IllegalStateException("the database is unhappy"))
                       .doNothing()
                       .when(caldavPendingPushStorage)
                       .refused(anyLong());
    when(caldavPushService.pushAgendaEvent(anyLong(), anyLong()))
                                                                .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                                   "the server is down"));

    assertEquals(0, service.retryOwedPushes(ALICE));

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
    verify(caldavPushService).pushAgendaEvent(ALICE, 9902L);
  }

  /**
   * A mapping that has never been persisted is not owed anything — and the write
   * to it is still attempted.
   *
   * <p>
   * Nothing the storage answers looks like this, so the guard is against a
   * caller that built a mapping by hand. It fails towards writing: a copy
   * nobody can retry is bad, a copy nobody even tried to write is worse.
   */
  @Test
  public void aMappingThatWasNeverPersistedIsNotOwedAnythingAndIsStillWrittenTo() {
    ObjectSync unpersisted = mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics");
    unpersisted.setId(null);
    givenHolders(unpersisted);
    givenPair(100L, ALICE);
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(1, service.propagateUpdate(EVENT, A_REAL_EDIT));

    verify(caldavPendingPushStorage, never()).owe(anyLong(), anyLong(), any(), any(), anyString());
  }

  /**
   * A copy with no iCalendar identity is not queued for a removal nothing could
   * ever carry out.
   *
   * <p>
   * A removal addresses the object by its UID and by nothing else, so an
   * obligation without one is unsatisfiable by construction. Queued, it would be
   * attempted, refused and abandoned — five sweeps and three round trips each,
   * ending in a warning saying eXo has given up on a copy it was never able to
   * reach. That is said once, where the copy is found, instead.
   */
  @Test
  public void aCopyWithNoICalendarIdentityIsNotQueuedForARemoval() {
    givenHolders(mapping(1L, 100L, null, "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);

    assertEquals(0, service.propagateDeletion(EVENT));

    assertEquals(0, caldavPendingPushStorage.owed(ALICE), "an unsatisfiable obligation is not recorded");
    verify(caldavPushService, never()).deleteEvent(anyLong(), anyString());
  }

  // -------------------------------------------- the answer fan-out, EXO-89868

  /**
   * The defect itself, seen live by Benjamin on 2026-09-01: an attendee's
   * answer reaches the copies of the <b>other</b> attendees.
   *
   * <p>
   * root created a meeting in a space and invited Benjamin; Benjamin accepted
   * in macOS Calendar; eXo recorded it correctly in both agendas — and root's
   * BlueMind copy never learned, because the only write an answer ever caused
   * went to the answerer's own account.
   */
  @Test
  public void anAnswerReachesTheCopiesOfTheOtherAttendees() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    givenTheAnswererIsNamed(CAROL);
    givenEveryCopyAcceptsTheAnswer();

    assertEquals(2, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"));

    verify(caldavPushService).pushAnswerOnto(eq(ALICE), any(), eq(CAROL_ADDRESSES), eq("ACCEPTED"));
    verify(caldavPushService).pushAnswerOnto(eq(BOB), any(), eq(CAROL_ADDRESSES), eq("ACCEPTED"));
  }

  /**
   * The answerer's own copy is not written here.
   *
   * <p>
   * Not an optimisation. {@code pushAnswer} writes it, off the same listener
   * and on the same thread, and two conditional writes racing for one object
   * would make one of them fail its If-Match — a conflict eXo inflicted on
   * itself and then reported as a divergence.
   */
  @Test
  public void theAnswerersOwnCopyIsNotWrittenByTheFanOut() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/carol/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, CAROL);
    givenTheAnswererIsNamed(CAROL);
    givenEveryCopyAcceptsTheAnswer();

    assertEquals(1, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"));

    verify(caldavPushService).pushAnswerOnto(eq(ALICE), any(), any(), anyString());
    verify(caldavPushService, never()).pushAnswerOnto(eq(CAROL), any(), any(), anyString());
    assertEquals(0, caldavPendingPushStorage.owed(CAROL), "the answerer's own copy owes this fan-out nothing");
  }

  /**
   * The write names the <b>answerer</b> on the <b>holder's</b> copy, and that
   * crossing over is the whole mechanism.
   *
   * <p>
   * Getting it the other way round is the original defect written a second
   * time: it would rewrite each holder's own PARTSTAT to somebody else's
   * answer, which is worse than doing nothing at all.
   */
  @Test
  public void theWriteUsesTheAnswerersAddressesAndTheHoldersCopy() {
    ObjectSync alicesCopy = mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics");
    givenHolders(alicesCopy);
    givenPair(100L, ALICE);
    givenTheAnswererIsNamed(CAROL);
    givenEveryCopyAcceptsTheAnswer();

    service.propagateAnswer(EVENT, CAROL, "DECLINED");

    verify(caldavPushService).pushAnswerOnto(ALICE, alicesCopy, CAROL_ADDRESSES, "DECLINED");
    // The holder's own addresses are never asked for: they name the wrong
    // person on this object.
    verify(caldavPushService, never()).addressesNaming(ALICE);
  }

  /**
   * A mapping with no href is the tombstone a removal leaves, not a copy —
   * the same guard the edit path rests on, and it has to hold here too or an
   * answer re-creates on the server an object somebody deleted.
   */
  @Test
  public void aTombstoneIsNotWrittenAnAnswer() {
    givenHolders(mapping(1L, 100L, "uid-8801", null));
    // The pair resolves perfectly well: only the missing href must stop this.
    givenPair(100L, ALICE);
    givenTheAnswererIsNamed(CAROL);

    assertEquals(0, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"));

    verify(caldavPushService, never()).pushAnswerOnto(anyLong(), any(), any(), anyString());
    assertEquals(0, caldavPendingPushStorage.owed(ALICE), "a tombstone is owed nothing");
  }

  /**
   * The reset to NEEDS-ACTION that follows a material edit is not fanned out.
   *
   * <p>
   * Benjamin's decision, and the arithmetic behind it: the edit that caused
   * the reset has already been carried to every copy by
   * {@link CaldavEventPropagationService#propagateUpdate}, which renders every
   * attendee's current answer. Fanning out on each reset as well turns one
   * edit of an eight-person meeting into fifty-six extra writes saying what
   * fifty-six writes have just said.
   */
  @Test
  public void aResetToNeedsActionIsNotFannedOut() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    // Everything else about this fan-out is in working order — the answerer is
    // nameable and every server would take the write. A test that let the
    // empty-address guard do the refusing would pass against a build that had
    // no reset skip at all, which is exactly what it did until a mutant said
    // so.
    givenTheAnswererIsNamed(CAROL);
    givenEveryCopyAcceptsTheAnswer();

    assertEquals(0, service.propagateAnswer(EVENT, CAROL, "NEEDS_ACTION"));

    verify(caldavPushService, never()).pushAnswerOnto(anyLong(), any(), any(), anyString());
    assertEquals(0, caldavPendingPushStorage.owed(ALICE), "a reset owes no copy anything");
  }

  /**
   * And so is an answer this add-on has no word for, because it would be
   * <i>written</i> as a reset.
   *
   * <p>
   * The skip is on the PARTSTAT the write would carry, not on the response
   * name. {@code IcsText.partStat} maps anything unrecognised to
   * {@code NEEDS-ACTION}, so a response agenda grows tomorrow would otherwise
   * fan a reset out to every copy in the name of an answer nobody gave —
   * exactly the case a name comparison would let through.
   */
  @Test
  public void anAnswerTheRfcDoesNotDefineIsNotFannedOutAsAReset() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenTheAnswererIsNamed(CAROL);
    givenEveryCopyAcceptsTheAnswer();

    assertEquals(0, service.propagateAnswer(EVENT, CAROL, "MAYBE_LATER"));

    verify(caldavPushService, never()).pushAnswerOnto(anyLong(), any(), any(), anyString());
  }

  /**
   * But an answer propagated onto a series' exceptional occurrences <b>is</b>
   * fanned out.
   *
   * <p>
   * Agenda saves-without-sending there too, which is what made it tempting to
   * treat it like a reset. It is not one: what it saves is a real answer, and
   * the other attendees' copies have to learn it like any other. The copy
   * itself lives under the series' identity, which is why the holders are
   * resolved through the parent.
   */
  @Test
  public void anAnswerPropagatedOntoAnOccurrenceIsStillFannedOut() {
    Event occurrence = new Event();
    occurrence.setId(OCCURRENCE);
    occurrence.setParentId(EVENT);
    when(agendaEventService.getEventById(OCCURRENCE)).thenReturn(occurrence);
    givenNoHoldersFor(OCCURRENCE);
    givenHoldersFor(EVENT, mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenTheAnswererIsNamed(CAROL);
    givenEveryCopyAcceptsTheAnswer();

    assertEquals(1, service.propagateAnswer(OCCURRENCE, CAROL, "ACCEPTED"));

    verify(caldavPushService).pushAnswerOnto(eq(ALICE), any(), eq(CAROL_ADDRESSES), eq("ACCEPTED"));
  }

  /**
   * Every obligation is recorded before the first network call, so a thread
   * that dies at the third of five holders leaves the other four owed.
   *
   * <p>
   * The same crash discipline the edit path has, and it is what a
   * {@code catch} block cannot buy: a PUT that times out ambiguously, a thread
   * killed mid-fan-out and a platform restarted between two attendees all
   * leave no exception for anybody to catch, and all of them leave the
   * obligation standing.
   */
  @Test
  public void everyObligationIsRecordedBeforeTheFirstWriteIsAttempted() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/a/uid.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/b/uid.ics"),
                 mapping(3L, 300L, "uid-8801", "/dav/c/uid.ics"),
                 mapping(4L, 400L, "uid-8801", "/dav/d/uid.ics"),
                 mapping(5L, 500L, "uid-8801", "/dav/e/uid.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    givenPair(300L, CAROL);
    givenPair(400L, DAVE);
    givenPair(500L, ERIN);
    givenTheAnswererIsNamed(FRANK);
    // An OutOfMemoryError rather than an exception, and rather than the
    // LinkageError the per-holder guard absorbs: this has to model a thread
    // that simply stops, unwinding past every catch there is. If the guard
    // caught it, the fan-out would carry on and the test would be pinning the
    // guard instead of the ordering.
    when(caldavPushService.pushAnswerOnto(anyLong(), any(), any(), anyString()))
                                                                               .thenReturn(CaldavPushService.AnswerOutcome.WRITTEN)
                                                                               .thenReturn(CaldavPushService.AnswerOutcome.WRITTEN)
                                                                               .thenThrow(new OutOfMemoryError("the thread dies here"));

    org.junit.jupiter.api.Assertions.assertThrows(OutOfMemoryError.class,
                                                  () -> service.propagateAnswer(EVENT, FRANK, "ACCEPTED"));

    // The two that landed are settled; the three that were never attempted are
    // owed, which is the whole point of recording them first.
    assertEquals(0, caldavPendingPushStorage.owed(ALICE), "a copy the answer reached is owed nothing");
    assertEquals(0, caldavPendingPushStorage.owed(BOB), "a copy the answer reached is owed nothing");
    assertEquals(1, caldavPendingPushStorage.owed(CAROL), "the copy the thread died on is still owed its write");
    assertEquals(1, caldavPendingPushStorage.owed(DAVE), "a copy never attempted is still owed its write");
    assertEquals(1, caldavPendingPushStorage.owed(ERIN), "a copy never attempted is still owed its write");
  }

  /**
   * And the ordering itself, asserted directly: every obligation is written
   * down before the <b>first</b> network call, not interleaved with the
   * writes.
   *
   * <p>
   * Interleaving would leave the holders after the one that died looking as
   * though nobody had ever intended to write to them — which is
   * indistinguishable, to every later pass, from an answer that never
   * concerned them.
   */
  @Test
  public void everyHolderIsRecordedBeforeTheFirstAnswerIsWritten() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/a/uid.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/b/uid.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    givenTheAnswererIsNamed(CAROL);
    givenEveryCopyAcceptsTheAnswer();

    service.propagateAnswer(EVENT, CAROL, "ACCEPTED");

    InOrder order = inOrder(caldavPendingPushStorage, caldavPushService);
    order.verify(caldavPendingPushStorage).owe(1L, ALICE, PendingPushKind.REWRITE, EVENT, "uid-8801");
    order.verify(caldavPendingPushStorage).owe(2L, BOB, PendingPushKind.REWRITE, EVENT, "uid-8801");
    order.verify(caldavPushService).pushAnswerOnto(anyLong(), any(), any(), anyString());
  }

  /**
   * A copy that already carries the answer is settled rather than rewritten,
   * and settled rather than left owed.
   *
   * <p>
   * Both halves are the task's own requirement. Rewriting it would move the
   * ETag for nothing, every time anybody answers. Leaving it owed would be the
   * same waste five minutes later and worse in kind: the retry is a
   * <b>full</b> rewrite, which is the one operation this design avoids because
   * it can destroy an answer nothing has read yet.
   */
  @Test
  public void aCopyThatAlreadyCarriesTheAnswerIsSettledRatherThanOwed() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenTheAnswererIsNamed(CAROL);
    when(caldavPushService.pushAnswerOnto(anyLong(), any(), any(), anyString()))
                                                                               .thenReturn(CaldavPushService.AnswerOutcome.ALREADY_SAID);

    assertEquals(0, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"), "nothing was written");

    assertEquals(0, caldavPendingPushStorage.owed(ALICE), "a copy that already agrees is owed nothing");
  }

  /**
   * A copy that does not name the answerer stays owed, so the bounded full
   * rewrite comes round and puts the missing ATTENDEE line there.
   *
   * <p>
   * The one outcome where the blunt instrument is the right one: a targeted
   * rewrite has nothing to target, and only a full render can add a line that
   * is not on the object.
   */
  @Test
  public void aCopyThatDoesNotNameTheAnswererStaysOwed() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenTheAnswererIsNamed(CAROL);
    when(caldavPushService.pushAnswerOnto(anyLong(), any(), any(), anyString()))
                                                                               .thenReturn(CaldavPushService.AnswerOutcome.NOT_NAMED);

    assertEquals(0, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"));

    PendingPush owed = onlyObligationOf(ALICE);
    assertEquals(PendingPushKind.REWRITE, owed.getKind(), "the fallback is a full rewrite of the event");
    assertEquals(EVENT, owed.getLocalEventId(), "and it renders the event, which now carries the answer");
  }

  /**
   * A conflict settles the obligation rather than queueing a retry.
   *
   * <p>
   * The same rule the edit path follows, for the same reason: a conflict means
   * the server's version moved away from the one eXo recorded, which is
   * precisely the gate the verification pass opens on. Retrying would fight
   * the same conditional write to the same refusal.
   */
  @Test
  public void aConflictSettlesAndIsLeftToTheVerificationPass() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenTheAnswererIsNamed(CAROL);
    when(caldavPushService.pushAnswerOnto(anyLong(), any(), any(), anyString()))
                                                                               .thenThrow(new CaldavPushException(CaldavPushService.CONFLICT,
                                                                                                                  "somebody wrote it first"));

    assertEquals(0, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"));

    assertEquals(0, caldavPendingPushStorage.owed(ALICE), "a conflict is the verification pass's business, not the retry's");
  }

  /**
   * Any other failure leaves the obligation standing, and the retry pass turns
   * it into a bounded full rewrite of the event — which carries the answer,
   * because agenda recorded it before this listener ever ran.
   */
  @Test
  public void aFailedAnswerStaysOwedAndConvergesThroughTheRetry() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenTheAnswererIsNamed(CAROL);
    when(caldavPushService.pushAnswerOnto(anyLong(), any(), any(), anyString()))
                                                                               .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                                                  "alice's server is down"));
    when(caldavPushService.pushAgendaEvent(ALICE, EVENT)).thenReturn(new ObjectSync());

    assertEquals(0, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"));
    assertEquals(PendingPushKind.REWRITE, onlyObligationOf(ALICE).getKind());

    assertEquals(1, service.retryOwedPushes(ALICE), "a later pass carries the answer as a full rewrite");

    verify(caldavPushService).pushAgendaEvent(ALICE, EVENT);
  }

  /**
   * And a failure that is not a {@code CaldavPushException} at all leaves the
   * obligation standing just the same.
   *
   * <p>
   * Its own test because it is caught in its own clause: a settle slipped into
   * either clause is invisible to a test that only ever throws the other kind,
   * and a mutant proved that before this existed.
   */
  @Test
  public void anUnexpectedFailureAlsoLeavesTheCopyOwed() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenTheAnswererIsNamed(CAROL);
    when(caldavPushService.pushAnswerOnto(anyLong(), any(), any(), anyString()))
                                                                               .thenThrow(new IllegalStateException("something nobody classified"));

    assertEquals(0, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"));

    assertEquals(PendingPushKind.REWRITE, onlyObligationOf(ALICE).getKind(), "an unclassified failure is still owed a write");
  }

  /**
   * One unreachable server is not a reason the other attendees keep a stale
   * RSVP.
   */
  @Test
  public void oneUnreachableHolderDoesNotStopTheFanOut() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/a/uid.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/b/uid.ics"),
                 mapping(3L, 300L, "uid-8801", "/dav/c/uid.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    givenPair(300L, DAVE);
    givenTheAnswererIsNamed(CAROL);
    when(caldavPushService.pushAnswerOnto(eq(ALICE), any(), any(), anyString()))
                                                                               .thenReturn(CaldavPushService.AnswerOutcome.WRITTEN);
    when(caldavPushService.pushAnswerOnto(eq(BOB), any(), any(), anyString()))
                                                                             .thenThrow(new CaldavPushException(CaldavPushService.SAVE,
                                                                                                                "bob's server is down"));
    when(caldavPushService.pushAnswerOnto(eq(DAVE), any(), any(), anyString()))
                                                                              .thenReturn(CaldavPushService.AnswerOutcome.WRITTEN);

    assertEquals(2, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"));

    verify(caldavPushService).pushAnswerOnto(eq(DAVE), any(), any(), anyString());
    assertEquals(1, caldavPendingPushStorage.owed(BOB), "only the one that failed is still owed");
  }

  /**
   * An answerer no copy can name reaches nothing, and owes nothing.
   *
   * <p>
   * No address means the mapper wrote no ATTENDEE line for them on anybody's
   * copy, so there is nothing a targeted rewrite could find and nothing a full
   * rewrite would add. Recording obligations for that would queue one
   * unsatisfiable rewrite per holder — attempted, refused and abandoned, five
   * times each, to say once what one warning says here.
   */
  @Test
  public void anAnswererNoCopyCanNameReachesNothingAndOwesNothing() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    when(caldavPushService.addressesNaming(CAROL)).thenReturn(List.of());

    assertEquals(0, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"));

    verify(caldavPushService, never()).pushAnswerOnto(anyLong(), any(), any(), anyString());
    assertEquals(0, caldavPendingPushStorage.owed(ALICE), "an unsatisfiable obligation is not recorded");
  }

  /**
   * Nobody else holds a copy, so nothing is read, written or owed.
   */
  @Test
  public void anAnswerToAMeetingNobodyElseHasACopyOfCostsNothing() {
    givenNoHolders();
    givenTheAnswererIsNamed(CAROL);

    assertEquals(0, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"));

    verify(caldavPushService, never()).pushAnswerOnto(anyLong(), any(), any(), anyString());
  }

  /**
   * The gap live testing on the rig found (EXO-89868, second round): an
   * organiser's own answer reaches nobody, so it records nothing and attempts
   * nothing.
   *
   * <p>
   * root organised event 1040 and answered it, and every holder's copy refused
   * the write — "it names none of [root's address], so there is no
   * participation status of theirs to rewrite". A copy names whoever called
   * the meeting on its ORGANIZER line and never on an ATTENDEE line, so there
   * is nothing on any copy for a targeted rewrite to find.
   */
  @Test
  public void anOrganizersOwnAnswerIsNotFannedOut() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    // Everything else about this fan-out is in working order: the organiser is
    // nameable and every server would take the write. Only the skip may stop
    // it, or this pins the wrong guard.
    givenTheAnswererIsNamed(CAROL);
    givenEveryCopyAcceptsTheAnswer();
    givenTheEventWasCreatedBy(CAROL);

    assertEquals(0, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"));

    verify(caldavPushService, never()).pushAnswerOnto(anyLong(), any(), any(), anyString());
  }

  /**
   * And it records no obligation, which is the half with teeth.
   *
   * <p>
   * {@code NOT_NAMED} deliberately leaves the obligation standing so a full
   * rewrite can add a missing ATTENDEE line. No full rewrite can add
   * <b>this</b> line — the render is what omits it — so before the skip every
   * organiser's answer scheduled a doomed retry against every other holder's
   * copy, up to the bound, on the one path that can destroy an unread answer.
   * The rig said so out loud: "User 13 was owed 1 calendar write(s); 0 landed
   * this round".
   */
  @Test
  public void anOrganizersOwnAnswerSchedulesNoDoomedRewrite() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"),
                 mapping(2L, 200L, "uid-8801", "/dav/bob/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenPair(200L, BOB);
    givenTheAnswererIsNamed(CAROL);
    givenEveryCopyAcceptsTheAnswer();
    givenTheEventWasCreatedBy(CAROL);

    service.propagateAnswer(EVENT, CAROL, "ACCEPTED");

    assertEquals(0, caldavPendingPushStorage.owed(ALICE), "an obligation nothing could ever satisfy is not recorded");
    assertEquals(0, caldavPendingPushStorage.owed(BOB), "an obligation nothing could ever satisfy is not recorded");
    verify(caldavPendingPushStorage, never()).owe(anyLong(), anyLong(), any(), any(), anyString());
  }

  /**
   * The other side of the same event: an ordinary attendee's answer is still
   * fanned out, on a meeting somebody else organises.
   *
   * <p>
   * The skip has to be about who <i>this answerer</i> is and not about the
   * event having an organiser at all — every meeting has one. This is the
   * behaviour the rig confirmed working end to end, and it must survive the
   * fix to the other direction.
   */
  @Test
  public void anAttendeesAnswerIsStillFannedOutOnAMeetingSomebodyElseOrganizes() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenTheAnswererIsNamed(CAROL);
    givenEveryCopyAcceptsTheAnswer();
    givenTheEventWasCreatedBy(ALICE);

    assertEquals(1, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"));

    verify(caldavPushService).pushAnswerOnto(eq(ALICE), any(), eq(CAROL_ADDRESSES), eq("ACCEPTED"));
  }

  /**
   * An event whose creator is on nobody's roster still behaves: the answerer
   * is not that creator, so the fan-out proceeds.
   */
  @Test
  public void anEventOrganizedBySomebodyNotOnTheRosterStillFansOut() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenTheAnswererIsNamed(CAROL);
    givenEveryCopyAcceptsTheAnswer();
    givenTheEventWasCreatedBy(FRANK);

    assertEquals(1, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"));

    verify(caldavPushService).pushAnswerOnto(eq(ALICE), any(), eq(CAROL_ADDRESSES), eq("ACCEPTED"));
  }

  /**
   * The organiser is decided the way the render decides it — on the event as
   * given, so an occurrence is judged by its own creator rather than its
   * series'.
   *
   * <p>
   * {@code AgendaEventIcsMapper.toIcsEvent} is handed the occurrence when an
   * occurrence is pushed and reads {@code getCreatorId()} off that, so a skip
   * reading the series would answer a different question from the render it
   * mirrors.
   */
  @Test
  public void anOccurrenceIsJudgedByItsOwnCreatorAsTheRenderIs() {
    Event occurrence = new Event();
    occurrence.setId(OCCURRENCE);
    occurrence.setParentId(EVENT);
    occurrence.setCreatorId(CAROL);
    when(agendaEventService.getEventById(OCCURRENCE)).thenReturn(occurrence);
    givenNoHoldersFor(OCCURRENCE);
    givenHoldersFor(EVENT, mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenTheAnswererIsNamed(CAROL);
    givenEveryCopyAcceptsTheAnswer();

    assertEquals(0, service.propagateAnswer(OCCURRENCE, CAROL, "ACCEPTED"));

    verify(caldavPushService, never()).pushAnswerOnto(anyLong(), any(), any(), anyString());
  }

  /**
   * An event agenda cannot read at all does not stop the fan-out.
   *
   * <p>
   * The bias {@code readEvent} already takes. Proceeding costs at worst the
   * doomed retries the skip exists to prevent, for as long as agenda cannot
   * answer; refusing would silently drop the answers of people who are not
   * organisers at all, which is the defect this whole delivery is about.
   */
  @Test
  public void anUnreadableEventDoesNotSuppressTheFanOut() {
    givenHolders(mapping(1L, 100L, "uid-8801", "/dav/alice/mirror/uid-8801.ics"));
    givenPair(100L, ALICE);
    givenTheAnswererIsNamed(CAROL);
    givenEveryCopyAcceptsTheAnswer();
    when(agendaEventService.getEventById(EVENT)).thenThrow(new IllegalStateException("agenda is having a moment"));

    assertEquals(1, service.propagateAnswer(EVENT, CAROL, "ACCEPTED"));

    verify(caldavPushService).pushAnswerOnto(eq(ALICE), any(), any(), anyString());
  }

  /**
   * Declares who agenda records as having created the event — the predicate
   * the render heads its ORGANIZER line with.
   *
   * @param creatorIdentityId who called the meeting
   */
  private void givenTheEventWasCreatedBy(long creatorIdentityId) {
    Event event = new Event();
    event.setId(EVENT);
    event.setParentId(0);
    event.setCreatorId(creatorIdentityId);
    when(agendaEventService.getEventById(EVENT)).thenReturn(event);
  }

  /**
   * Declares which addresses name the answerer on somebody else's copy.
   *
   * @param answererIdentityId who answered
   */
  private void givenTheAnswererIsNamed(long answererIdentityId) {
    lenient().when(caldavPushService.addressesNaming(answererIdentityId)).thenReturn(CAROL_ADDRESSES);
  }

  /**
   * Declares that every holder's server takes the answer.
   */
  private void givenEveryCopyAcceptsTheAnswer() {
    lenient().when(caldavPushService.pushAnswerOnto(anyLong(), any(), any(), anyString()))
             .thenReturn(CaldavPushService.AnswerOutcome.WRITTEN);
  }

  // ---------------------------------------------------------------- fixtures

  /**
   * Declares what agenda now holds for the edited event.
   *
   * @param status the status agenda stores — TENTATIVE for a date poll
   */
  private void givenTheEventIs(EventStatus status) {
    Event event = new Event();
    event.setId(EVENT);
    event.setParentId(0);
    event.setStatus(status);
    when(agendaEventService.getEventById(EVENT)).thenReturn(event);
  }

  /**
   * Declares who agenda says was invited to the created event.
   *
   * @param identityIds the attendee identities, as agenda lists them
   */
  private void givenInvited(long... identityIds) {
    List<EventAttendee> attendees = new ArrayList<>();
    for (long identityId : identityIds) {
      EventAttendee attendee = new EventAttendee();
      attendee.setEventId(EVENT);
      attendee.setIdentityId(identityId);
      attendees.add(attendee);
    }
    when(agendaEventAttendeeService.getEventAttendees(EVENT)).thenReturn(new EventAttendeeList(attendees));
  }

  /**
   * Declares one identity as a person, and answers with it.
   *
   * @param identityId the social identity
   * @return the same identity, so it can be passed straight to
   *         {@link #givenInvited(long...)}
   */
  private long user(long identityId) {
    return givenIdentity(identityId, OrganizationIdentityProvider.NAME);
  }

  /**
   * Declares one identity as a space, and answers with it.
   *
   * @param identityId the social identity
   * @return the same identity, so it can be passed straight to
   *         {@link #givenInvited(long...)}
   */
  private long space(long identityId) {
    return givenIdentity(identityId, SpaceIdentityProvider.NAME);
  }

  /**
   * Declares what one attendee identity is.
   *
   * @param identityId the social identity
   * @param providerId the provider that owns it
   * @return the identity id, unchanged
   */
  private long givenIdentity(long identityId, String providerId) {
    Identity identity = new Identity(String.valueOf(identityId));
    identity.setProviderId(providerId);
    lenient().when(identityManager.getIdentity(String.valueOf(identityId))).thenReturn(identity);
    return identityId;
  }

  /**
   * Declares the mappings the edited event has, on its own id.
   *
   * @param mappings the mapping rows the storage answers
   */
  private void givenHolders(ObjectSync... mappings) {
    givenHoldersFor(EVENT, mappings);
  }

  /**
   * Declares the mappings one event id has.
   *
   * @param eventId the event the mappings belong to
   * @param mappings the mapping rows the storage answers
   */
  private void givenHoldersFor(long eventId, ObjectSync... mappings) {
    Page<ObjectSync> page = new PageImpl<>(List.of(mappings), PageRequest.of(0, 50), mappings.length);
    lenient().when(caldavSyncStorage.getObjectsByEvent(eventId, 0, 50)).thenReturn(page);
  }

  /**
   * Declares that the edited event has no mapping at all.
   */
  private void givenNoHolders() {
    givenNoHoldersFor(EVENT);
  }

  /**
   * Declares that one event id has no mapping at all.
   *
   * @param eventId the event with no copy anywhere
   */
  private void givenNoHoldersFor(long eventId) {
    lenient().when(caldavSyncStorage.getObjectsByEvent(eq(eventId), anyInt(), anyInt()))
             .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
  }

  /**
   * Declares which user a collection belongs to.
   *
   * @param pairId the collection pair
   * @param userIdentityId its owner
   */
  private void givenPair(long pairId, long userIdentityId) {
    CalendarSync pair = new CalendarSync();
    pair.setId(pairId);
    pair.setUserIdentityId(userIdentityId);
    lenient().when(caldavSyncStorage.getPair(pairId)).thenReturn(pair);
  }

  /**
   * One mapping row.
   *
   * @param id the row's identifier
   * @param pairId the collection it lives in
   * @param icsUid the iCalendar identity of the object
   * @param href where the object sits, null for a tombstone
   * @return the mapping
   */
  private ObjectSync mapping(long id, long pairId, String icsUid, String href) {
    ObjectSync mapping = new ObjectSync();
    mapping.setId(id);
    mapping.setCalendarSyncId(pairId);
    mapping.setLocalEventId(EVENT);
    mapping.setIcsUid(icsUid);
    mapping.setRemoteHref(href);
    mapping.setEtag("\"v1\"");
    mapping.setLastSync(new Date());
    return mapping;
  }

  /**
   * The single obligation an account carries, failing loudly when there is not
   * exactly one — a test that asserted on "the first of however many" would
   * pass against a store that queued duplicates.
   *
   * @param userIdentityId whose obligations are read
   * @return the one obligation recorded against that account
   */
  private PendingPush onlyObligationOf(long userIdentityId) {
    List<PendingPush> owed = caldavPendingPushStorage.attemptable(userIdentityId, Integer.MAX_VALUE, 10);
    assertEquals(1, owed.size(), "exactly one obligation was expected");
    PendingPush only = owed.get(0);
    assertNotNull(only.getKind(), "an obligation says what is owed");
    return only;
  }

  /**
   * The obligation store, in memory, behaving as the persisted one does.
   *
   * <p>
   * One record per copy — the unique constraint the schema carries — replaced
   * rather than queued when it is recorded again, with the attempt count reset,
   * and read back oldest first while it is still worth attempting.
   */
  private static class InMemoryPendingPushStorage extends CaldavPendingPushStorage {

    private final Map<Long, PendingPush> byObject = new LinkedHashMap<>();

    private long                         sequence;

    /**
     * {@inheritDoc}
     */
    @Override
    public PendingPush owe(long objectSyncId, long userIdentityId, PendingPushKind kind, Long localEventId, String icsUid) {
      PendingPush existing = byObject.get(objectSyncId);
      PendingPush recorded = new PendingPush(existing == null ? ++sequence : existing.getId(),
                                             objectSyncId,
                                             userIdentityId,
                                             kind,
                                             localEventId,
                                             icsUid,
                                             0,
                                             existing == null ? new Date() : existing.getSince());
      byObject.put(objectSyncId, recorded);
      return recorded;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void settled(long objectSyncId) {
      byObject.remove(objectSyncId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void refused(long id) {
      byObject.replaceAll((objectSyncId, pending) -> {
        if (pending.getId() != null && pending.getId() == id) {
          pending.setAttempts(pending.getAttempts() + 1);
        }
        return pending;
      });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<PendingPush> attemptable(long userIdentityId, int maxAttempts, int limit) {
      return byObject.values()
                     .stream()
                     .filter(pending -> pending.getUserIdentityId() == userIdentityId)
                     .filter(pending -> pending.getAttempts() < maxAttempts)
                     .sorted(Comparator.comparing(PendingPush::getId))
                     .limit(limit)
                     .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long owed(long userIdentityId) {
      return byObject.values().stream().filter(pending -> pending.getUserIdentityId() == userIdentityId).count();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long owedAndStillTrying(long userIdentityId, int maxAttempts) {
      return byObject.values()
                     .stream()
                     .filter(pending -> pending.getUserIdentityId() == userIdentityId)
                     .filter(pending -> pending.getAttempts() < maxAttempts)
                     .count();
    }
  }
}
