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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.PutResult;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * What changing where the meeting copies go does to the copies already written
 * (EXO-89761).
 *
 * <p>
 * The setting of EXO-89760 governs the next push. Left there, an administrator
 * moving the destination splits a user's meetings across two calendars for ever
 * — the old ones in the calendar just abandoned, the new ones somewhere else,
 * and nothing saying why. These pin the move that answers it, and, as much, the
 * things it must refuse to do: never touch an account that is already on the
 * new destination, never lose an answer somebody left on a copy, never record
 * the change as applied while a copy is still where it was.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavMirrorRelocationServiceTest {

  private static final long                  USER      = 42L;

  private static final long                  SERVER    = 7L;

  private static final long                  PAIR      = 3L;

  private static final long                  EVENT     = 5L;

  private static final String                LOGIN     = "john";

  private static final String                PASSWORD  = "secret";

  private static final String                UID       = "evt-1";

  /** Where the copies were: a calendar of eXo's own making. */
  private static final String                DEDICATED = "/dav/calendars/john/exo-meetings/";

  /** Where an administrator has just said they go instead. */
  private static final String                MAIN      = "/dav/calendars/john/personal/";

  private static final String                IN_DEDICATED = DEDICATED + UID + ".ics";

  private static final String                IN_MAIN      = MAIN + UID + ".ics";

  /** What eXo renders for the event the mapping row stands for. */
  private static final String                ICS       = "BEGIN:VCALENDAR\r\n"
      + "VERSION:2.0\r\n"
      + "BEGIN:VEVENT\r\n"
      + "UID:evt-1\r\n"
      + "SUMMARY:Sprint review\r\n"
      + "ATTENDEE;CN=John:mailto:john@acme.test\r\n"
      + "END:VEVENT\r\n"
      + "END:VCALENDAR\r\n";

  /** The same copy after the owner accepted the meeting on their phone. */
  private static final String                ANSWERED  = ICS.replace("ATTENDEE;CN=John:",
                                                                     "ATTENDEE;CN=John;PARTSTAT=ACCEPTED:");

  @Mock
  private CalDavClient                       calDavClient;

  @Mock
  private CaldavSyncStorage                  caldavSyncStorage;

  @Mock
  private CaldavPushService                  caldavPushService;

  @Mock
  private CaldavAnswerAdoptionService        caldavAnswerAdoptionService;

  @Mock
  private CalDavEndpoint                     endpoint;

  @InjectMocks
  private CaldavMirrorRelocationService      service;

  @BeforeEach
  public void connectAnAccount() {
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
    lenient().when(caldavSyncStorage.savePair(any())).thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(caldavPushService.renderAgendaEvent(USER, EVENT, UID)).thenReturn(ICS);
  }

  // ---------------------------------------------------------------- moving out

  @Test
  public void aCopyAlreadyWrittenIsMovedIntoTheNewCalendarAndTakenOutOfTheOld() {
    // The whole point. An administrator moved the destination; a hundred
    // objects sitting in the calendar just abandoned is worse than no setting.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(DEDICATED);
    givenTheOldCollectionHolds(DEDICATED, Map.of(IN_DEDICATED, "\"etag-1\""));
    givenMappings(mapping(IN_DEDICATED, "\"etag-1\"", EVENT));
    givenTheWriteSucceeds(IN_MAIN, "\"etag-9\"");
    when(calDavClient.deleteObject(endpoint, IN_DEDICATED, "\"etag-1\"", LOGIN, PASSWORD)).thenReturn(204);

    MirrorRelocation relocation = service.relocate(USER, settings(), mirror(DEDICATED));

    assertEquals(1, relocation.moved());
    assertTrue(relocation.complete(), "nothing failed, so the change may be recorded as applied");
    verify(calDavClient).overwriteObject(endpoint, IN_MAIN, ICS, LOGIN, PASSWORD);
    verify(calDavClient).deleteObject(endpoint, IN_DEDICATED, "\"etag-1\"", LOGIN, PASSWORD);
  }

  @Test
  public void theMappingRowFollowsTheCopyIntoTheNewCalendar() {
    // The row IS the progress marker, so it has to move with the object: left
    // behind, the copy is unfindable, the next push writes a second one into
    // the calendar just abandoned, and no restart could ever tell what had
    // already been done.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(DEDICATED);
    givenTheOldCollectionHolds(DEDICATED, Map.of(IN_DEDICATED, "\"etag-1\""));
    givenMappings(mapping(IN_DEDICATED, "\"etag-1\"", EVENT));
    givenTheWriteSucceeds(IN_MAIN, "\"etag-9\"");
    when(calDavClient.deleteObject(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(204);

    service.relocate(USER, settings(), mirror(DEDICATED));

    ArgumentCaptor<ObjectSync> saved = ArgumentCaptor.forClass(ObjectSync.class);
    verify(caldavSyncStorage).saveObject(saved.capture());
    assertEquals(IN_MAIN, saved.getValue().getRemoteHref());
    assertEquals("\"etag-9\"", saved.getValue().getEtag(), "the version of the copy in its new home, not the old one");
  }

  @Test
  public void thePairIsPointedAtTheNewCalendarBeforeASingleCopyMoves() {
    // Re-pointing last would leave a window in which every push and every
    // repair wrote back into the calendar being emptied — the relocation would
    // then be racing the sweep that undoes it.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(DEDICATED);
    givenTheOldCollectionHolds(DEDICATED, Map.of(IN_DEDICATED, "\"etag-1\""));
    givenMappings(mapping(IN_DEDICATED, "\"etag-1\"", EVENT));
    givenTheWriteSucceeds(IN_MAIN, "\"etag-9\"");
    when(calDavClient.deleteObject(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(204);

    service.relocate(USER, settings(), mirror(DEDICATED));

    InOrder order = inOrder(caldavSyncStorage, calDavClient);
    order.verify(caldavSyncStorage).savePair(any());
    order.verify(calDavClient).overwriteObject(endpoint, IN_MAIN, ICS, LOGIN, PASSWORD);
    ArgumentCaptor<CalendarSync> repointed = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(repointed.capture());
    assertEquals(MAIN, repointed.getValue().getRemoteHref());
  }

  // --------------------------------------------------------------- moving back

  @Test
  public void theCopiesComeBackWhenTheDestinationGoesBackToExosOwnCalendar() {
    // The escape hatch, and it must work as plainly as the way out: an
    // administrator who tries the account's main calendar and finds the churn
    // it produces has to be able to put the copies back where they were.
    givenTheDestinationIsNow(DEDICATED);
    givenThePairPointsAt(MAIN);
    givenTheOldCollectionHolds(MAIN, Map.of(IN_MAIN, "\"etag-9\""));
    givenMappings(mapping(IN_MAIN, "\"etag-9\"", EVENT));
    givenTheWriteSucceeds(IN_DEDICATED, "\"etag-11\"");
    when(calDavClient.deleteObject(endpoint, IN_MAIN, "\"etag-9\"", LOGIN, PASSWORD)).thenReturn(204);

    MirrorRelocation relocation = service.relocate(USER, settings(), mirror(MAIN));

    assertEquals(1, relocation.moved());
    assertTrue(relocation.complete());
    verify(calDavClient).overwriteObject(endpoint, IN_DEDICATED, ICS, LOGIN, PASSWORD);
    verify(calDavClient).deleteObject(endpoint, IN_MAIN, "\"etag-9\"", LOGIN, PASSWORD);
  }

  @Test
  public void theCalendarLeftEmptyIsNotDeleted() {
    // Deleting a collection on somebody's account is exactly what this add-on's
    // deletion guards exist to prevent. An empty calendar is inert and visible,
    // and the user can remove it themselves.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(DEDICATED);
    givenTheOldCollectionHolds(DEDICATED, Map.of(IN_DEDICATED, "\"etag-1\""));
    givenMappings(mapping(IN_DEDICATED, "\"etag-1\"", EVENT));
    givenTheWriteSucceeds(IN_MAIN, "\"etag-9\"");
    when(calDavClient.deleteObject(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(204);

    service.relocate(USER, settings(), mirror(DEDICATED));

    verify(calDavClient, never()).deleteCollection(any(), any(), anyString(), anyString());
  }

  // ------------------------------------------------------- the converged case

  @Test
  public void anAccountAlreadyOnTheNewDestinationWritesMovesAndDeletesNothing() {
    // THE pin against the recurring shape of defect in this codebase: logic
    // gated on the problem still happening, tested only on the broken path,
    // then deployed onto an account where everything is already correct. A pass
    // over a converged account must cost one page read and not one request.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(MAIN);
    givenMappings(mapping(IN_MAIN, "\"etag-9\"", EVENT));

    MirrorRelocation relocation = service.relocate(USER, settings(), mirror(MAIN));

    assertEquals(0, relocation.moved());
    assertEquals(0, relocation.refused());
    assertTrue(relocation.complete());
    verify(caldavSyncStorage, never()).savePair(any());
    verify(caldavSyncStorage, never()).saveObject(any());
    verify(calDavClient, never()).overwriteObject(any(), anyString(), anyString(), anyString(), anyString());
    verify(calDavClient, never()).deleteObject(any(), anyString(), any(), anyString(), anyString());
    verify(calDavClient, never()).listResourceEtags(any(), anyString(), anyString(), anyString());
    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
  }

  // ------------------------------------------------- the answer, read in time

  @Test
  public void theAnswerOnACopyIsReadBeforeThatCopyStopsBeingWatched() {
    // The ordering that matters most. Once the mapping points at the new href
    // nothing lists the old collection again, so an answer left on the old copy
    // is simply gone. It is read first, exactly as the sweep reads an answer
    // before repairing over it, and for the same reason.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(DEDICATED);
    givenTheOldCollectionHolds(DEDICATED, Map.of(IN_DEDICATED, "\"etag-2\""));
    givenMappings(mapping(IN_DEDICATED, "\"etag-1\"", EVENT));
    when(calDavClient.fetchObject(endpoint, IN_DEDICATED, LOGIN, PASSWORD))
                                                          .thenReturn(new CalendarObject(IN_DEDICATED,
                                                                                         "\"etag-2\"",
                                                                                         ANSWERED));
    when(caldavAnswerAdoptionService.adoptAnswer(USER, EVENT, ANSWERED))
                                                          .thenReturn(CaldavAnswerAdoptionService.Outcome.ADOPTED);
    givenTheWriteSucceeds(IN_MAIN, "\"etag-9\"");
    when(calDavClient.deleteObject(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(204);

    service.relocate(USER, settings(), mirror(DEDICATED));

    InOrder order = inOrder(caldavAnswerAdoptionService, calDavClient, caldavSyncStorage);
    order.verify(caldavAnswerAdoptionService).adoptAnswer(USER, EVENT, ANSWERED);
    order.verify(calDavClient).overwriteObject(endpoint, IN_MAIN, ICS, LOGIN, PASSWORD);
    order.verify(caldavSyncStorage).saveObject(any());
    order.verify(calDavClient).deleteObject(endpoint, IN_DEDICATED, "\"etag-2\"", LOGIN, PASSWORD);
  }

  @Test
  public void anAnswerThatCouldNotBeRecordedStopsTheMoveOfThatCopy() {
    // The old copy still holds the only record of what the user said. Nothing
    // may write over it or remove it; the next pass reads it again.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(DEDICATED);
    givenTheOldCollectionHolds(DEDICATED, Map.of(IN_DEDICATED, "\"etag-2\""));
    givenMappings(mapping(IN_DEDICATED, "\"etag-1\"", EVENT));
    when(calDavClient.fetchObject(endpoint, IN_DEDICATED, LOGIN, PASSWORD))
                                                          .thenReturn(new CalendarObject(IN_DEDICATED,
                                                                                         "\"etag-2\"",
                                                                                         ANSWERED));
    when(caldavAnswerAdoptionService.adoptAnswer(USER, EVENT, ANSWERED))
                                                          .thenReturn(CaldavAnswerAdoptionService.Outcome.FAILED);

    MirrorRelocation relocation = service.relocate(USER, settings(), mirror(DEDICATED));

    assertEquals(0, relocation.moved());
    assertEquals(1, relocation.failed());
    assertFalse(relocation.complete(), "the change is not applied while a copy is still to move");
    verify(calDavClient, never()).overwriteObject(any(), anyString(), anyString(), anyString(), anyString());
    verify(calDavClient, never()).deleteObject(any(), anyString(), any(), anyString(), anyString());
    verify(caldavSyncStorage, never()).saveObject(any());
  }

  @Test
  public void anUntouchedCopyIsMovedWithoutBeingFetched() {
    // The version the listing publishes is the server's own promise that nobody
    // wrote since eXo did. Fetching every copy to move it would turn a
    // relocation of a large calendar into one round trip per object for nothing.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(DEDICATED);
    givenTheOldCollectionHolds(DEDICATED, Map.of(IN_DEDICATED, "\"etag-1\""));
    givenMappings(mapping(IN_DEDICATED, "\"etag-1\"", EVENT));
    givenTheWriteSucceeds(IN_MAIN, "\"etag-9\"");
    when(calDavClient.deleteObject(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(204);

    service.relocate(USER, settings(), mirror(DEDICATED));

    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
    verify(caldavAnswerAdoptionService, never()).adoptAnswer(anyLong(), anyLong(), anyString());
  }

  // -------------------------------------------------------------- resumability

  @Test
  public void aPassThatStoppedHalfwayResumesFromTheRowsStillPointingElsewhere() {
    // Resumability is the mapping row and nothing else. The pair was re-pointed
    // before the crash, so a pass that decided what to do from the PAIR would
    // conclude there is nothing left to move and strand every row the first
    // pass had not reached.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(MAIN);
    givenTheOldCollectionHolds(DEDICATED, Map.of(DEDICATED + "evt-2.ics", "\"etag-1\""));
    givenMappings(mapping(IN_MAIN, "\"etag-9\"", EVENT), stillBehind());
    when(calDavClient.overwriteObject(eq(endpoint), eq(MAIN + "evt-2.ics"), anyString(), anyString(), anyString()))
                                                                        .thenReturn(new PutResult(201, "\"etag-12\"", null));
    when(caldavPushService.renderAgendaEvent(USER, 6L, "evt-2")).thenReturn(ICS);
    when(calDavClient.deleteObject(endpoint, DEDICATED + "evt-2.ics", "\"etag-1\"", LOGIN, PASSWORD)).thenReturn(204);

    MirrorRelocation relocation = service.relocate(USER, settings(), mirror(MAIN));

    assertEquals(1, relocation.moved(), "the row left behind by the interrupted pass, and only that one");
    verify(caldavSyncStorage, never()).savePair(any());
    verify(calDavClient, never()).overwriteObject(any(), eq(IN_MAIN), anyString(), anyString(), anyString());
    verify(calDavClient).overwriteObject(eq(endpoint), eq(MAIN + "evt-2.ics"), anyString(), anyString(), anyString());
  }

  // ------------------------------------------------------- what refuses to move

  @Test
  public void aRemovalTheServerRefusesKeepsTheOldCopyAndLeavesTheChangeUnapplied() {
    // Somebody wrote the old copy between the listing and the removal. The
    // administrator moved a destination, which is not consent to discard an
    // edit made on a phone in the meantime — so the copy stays, and the change
    // is NOT stamped as applied, which is what brings the next pass back to it.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(DEDICATED);
    givenTheOldCollectionHolds(DEDICATED, Map.of(IN_DEDICATED, "\"etag-1\""));
    givenMappings(mapping(IN_DEDICATED, "\"etag-1\"", EVENT));
    givenTheWriteSucceeds(IN_MAIN, "\"etag-9\"");
    when(calDavClient.deleteObject(endpoint, IN_DEDICATED, "\"etag-1\"", LOGIN, PASSWORD))
                                                                          .thenReturn(PutResult.PRECONDITION_FAILED);

    MirrorRelocation relocation = service.relocate(USER, settings(), mirror(DEDICATED));

    assertEquals(0, relocation.moved());
    assertEquals(1, relocation.refused());
    assertFalse(relocation.complete(), "a copy left behind is work this change still owes");
    // The copy IS in the new calendar and the row points at it: the user's
    // meeting is where it belongs, and what is left over is a duplicate they
    // can see rather than a meeting they cannot find.
    verify(calDavClient).overwriteObject(endpoint, IN_MAIN, ICS, LOGIN, PASSWORD);
    ArgumentCaptor<ObjectSync> saved = ArgumentCaptor.forClass(ObjectSync.class);
    verify(caldavSyncStorage).saveObject(saved.capture());
    assertEquals(IN_MAIN, saved.getValue().getRemoteHref());
  }

  @Test
  public void aRemovalThatCannotBeAttemptedAtAllIsAlsoARefusal() {
    // The server went away between the write and the delete. The old object is
    // still there as far as anybody knows, so the change is not finished.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(DEDICATED);
    givenTheOldCollectionHolds(DEDICATED, Map.of(IN_DEDICATED, "\"etag-1\""));
    givenMappings(mapping(IN_DEDICATED, "\"etag-1\"", EVENT));
    givenTheWriteSucceeds(IN_MAIN, "\"etag-9\"");
    when(calDavClient.deleteObject(endpoint, IN_DEDICATED, "\"etag-1\"", LOGIN, PASSWORD))
                                                                    .thenThrow(new IllegalStateException("connection reset"));

    MirrorRelocation relocation = service.relocate(USER, settings(), mirror(DEDICATED));

    assertEquals(1, relocation.refused());
    assertFalse(relocation.complete());
  }

  @Test
  public void aCopyTheServerAlreadyLostIsNotARefusal() {
    // 404 and 410 are facts, not faults: the object is not there, which is what
    // the removal was asking for.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(DEDICATED);
    givenTheOldCollectionHolds(DEDICATED, Map.of());
    givenMappings(mapping(IN_DEDICATED, "\"etag-1\"", EVENT));
    givenTheWriteSucceeds(IN_MAIN, "\"etag-9\"");
    when(calDavClient.deleteObject(endpoint, IN_DEDICATED, "\"etag-1\"", LOGIN, PASSWORD)).thenReturn(404);

    MirrorRelocation relocation = service.relocate(USER, settings(), mirror(DEDICATED));

    assertEquals(1, relocation.moved());
    assertTrue(relocation.complete());
  }

  @Test
  public void aWriteTheNewCalendarRefusesLeavesTheOldCopyExactlyWhereItIs() {
    // Write first, remove second, and never the other way round: a move whose
    // write failed and whose delete ran would have taken the user's meeting
    // away instead of moving it.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(DEDICATED);
    givenTheOldCollectionHolds(DEDICATED, Map.of(IN_DEDICATED, "\"etag-1\""));
    givenMappings(mapping(IN_DEDICATED, "\"etag-1\"", EVENT));
    when(calDavClient.overwriteObject(endpoint, IN_MAIN, ICS, LOGIN, PASSWORD))
                                                                    .thenThrow(new IllegalStateException("507 insufficient storage"));

    MirrorRelocation relocation = service.relocate(USER, settings(), mirror(DEDICATED));

    assertEquals(1, relocation.failed());
    assertFalse(relocation.complete());
    verify(calDavClient, never()).deleteObject(any(), anyString(), any(), anyString(), anyString());
    verify(caldavSyncStorage, never()).saveObject(any());
  }

  @Test
  public void aRowStandingForNoEventIsLeftAloneAndDoesNotHoldTheChangeOpen() {
    // eXo can render nothing for it, and will not be able to on the next pass
    // either. Letting it hold the stamp open would put the account into a
    // full-content comparison round every five minutes for ever — the very cost
    // this mechanism exists to pay exactly once.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(DEDICATED);
    givenTheOldCollectionHolds(DEDICATED, Map.of(IN_DEDICATED, "\"etag-1\""));
    givenMappings(mapping(IN_DEDICATED, "\"etag-1\"", null));

    MirrorRelocation relocation = service.relocate(USER, settings(), mirror(DEDICATED));

    assertEquals(1, relocation.unmovable());
    assertEquals(0, relocation.failed());
    assertTrue(relocation.complete(), "a permanent condition must not keep the change owed for ever");
    verify(calDavClient, never()).overwriteObject(any(), anyString(), anyString(), anyString(), anyString());
    verify(calDavClient, never()).deleteObject(any(), anyString(), any(), anyString(), anyString());
  }

  @Test
  public void aMappingWithItsRemoteIdentityClearedIsNotACopyToMove() {
    // The record that this event was once pushed, kept with no href. There is
    // nothing on the server to move and nothing to ask about it.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(DEDICATED);
    givenMappings(mapping(null, null, EVENT));

    MirrorRelocation relocation = service.relocate(USER, settings(), mirror(DEDICATED));

    assertEquals(0, relocation.moved());
    assertEquals(0, relocation.unmovable());
    assertTrue(relocation.complete());
    verify(calDavClient, never()).listResourceEtags(any(), anyString(), anyString(), anyString());
  }

  // ------------------------------------------------------ when to do nothing

  @Test
  public void aDestinationTheUserHasNotChosenYetDefersInsteadOfGuessing() {
    // The registration says the destination is the user's to name. Moving the
    // copies into a calendar of eXo's own choosing would answer a question they
    // were asked and have not answered — in somebody else's calendar.
    when(caldavPushService.ensureMirror(USER)).thenThrow(new CaldavPushException(CaldavPushService.CHOICE_PENDING,
                                                                                 "not chosen"));

    MirrorRelocation relocation = service.relocate(USER, settings(), mirror(DEDICATED));

    assertNull(relocation.destination());
    assertFalse(relocation.applicable());
    assertFalse(relocation.complete(), "nothing was applied, so nothing may be recorded as applied");
    verify(caldavSyncStorage, never()).savePair(any());
    verify(caldavSyncStorage, never()).getObjects(anyLong(), anyInt(), anyInt());
  }

  @Test
  public void aPairDeletedUnderThePassIsNotRecreatedToReceiveCopies() {
    // Somebody disconnected the account while this ran. There is nothing left
    // to move copies into, and writing the row back would resurrect a binding
    // deliberately removed.
    givenTheDestinationIsNow(MAIN);
    when(caldavSyncStorage.getPair(PAIR)).thenReturn(null);

    MirrorRelocation relocation = service.relocate(USER, settings(), mirror(DEDICATED));

    assertFalse(relocation.applicable());
    verify(caldavSyncStorage, never()).savePair(any());
    verify(caldavSyncStorage, never()).getObjects(anyLong(), anyInt(), anyInt());
  }

  @Test
  public void anOldCollectionThatCannotBeListedStillMovesItsCopies() {
    // No version observed is not "the client wrote it" and not "give up": the
    // removal falls back to the version eXo recorded, which is the conservative
    // reading in both directions.
    givenTheDestinationIsNow(MAIN);
    givenThePairPointsAt(DEDICATED);
    when(calDavClient.listResourceEtags(eq(endpoint), eq(DEDICATED), anyString(), anyString()))
                                                                      .thenThrow(new IllegalStateException("504"));
    givenMappings(mapping(IN_DEDICATED, "\"etag-1\"", EVENT));
    givenTheWriteSucceeds(IN_MAIN, "\"etag-9\"");
    when(calDavClient.deleteObject(endpoint, IN_DEDICATED, "\"etag-1\"", LOGIN, PASSWORD)).thenReturn(204);

    assertEquals(1, service.relocate(USER, settings(), mirror(DEDICATED)).moved());
    verify(caldavAnswerAdoptionService, never()).adoptAnswer(anyLong(), anyLong(), anyString());
  }

  // ------------------------------------------------------------------ fixtures

  /**
   * The destination the one place that decides it now answers.
   *
   * @param href where the copies go
   */
  private void givenTheDestinationIsNow(String href) {
    when(caldavPushService.ensureMirror(USER)).thenReturn(new MirrorTarget(href, false, "wherever"));
  }

  /**
   * The mirror pair as the storage holds it.
   *
   * @param href the collection it is bound to
   */
  private void givenThePairPointsAt(String href) {
    when(caldavSyncStorage.getPair(PAIR)).thenReturn(mirror(href));
  }

  /**
   * What a collection currently holds.
   *
   * @param collection the collection listed
   * @param etags its objects, by href
   */
  private void givenTheOldCollectionHolds(String collection, Map<String, String> etags) {
    when(calDavClient.listResourceEtags(eq(endpoint), eq(collection), anyString(), anyString())).thenReturn(etags);
  }

  /**
   * The destination accepts the copy.
   *
   * @param href where it is written
   * @param etag the version the server answers with
   */
  private void givenTheWriteSucceeds(String href, String etag) {
    when(calDavClient.overwriteObject(endpoint, href, ICS, LOGIN, PASSWORD)).thenReturn(new PutResult(201, etag, null));
  }

  /**
   * The mapping rows of the mirror, in one page.
   *
   * @param objects the rows
   */
  private void givenMappings(ObjectSync... objects) {
    when(caldavSyncStorage.getObjects(eq(PAIR), eq(0), anyInt())).thenReturn(new PageImpl<>(List.of(objects)));
    when(caldavSyncStorage.getObjects(eq(PAIR), eq(1), anyInt())).thenReturn(new PageImpl<>(List.of()));
  }

  /**
   * @param href where the copy lives
   * @param etag the version recorded when eXo wrote it
   * @param localEventId the agenda event it stands for, or null
   * @return the mapping row
   */
  private ObjectSync mapping(String href, String etag, Long localEventId) {
    ObjectSync object = new ObjectSync();
    object.setId(1L);
    object.setCalendarSyncId(PAIR);
    object.setIcsUid(UID);
    object.setRemoteHref(href);
    object.setEtag(etag);
    object.setLocalEventId(localEventId);
    return object;
  }

  /**
   * A second row an interrupted pass never reached.
   *
   * @return the mapping row, still pointing at the old collection
   */
  private ObjectSync stillBehind() {
    ObjectSync object = new ObjectSync();
    object.setId(2L);
    object.setCalendarSyncId(PAIR);
    object.setIcsUid("evt-2");
    object.setRemoteHref(DEDICATED + "evt-2.ics");
    object.setEtag("\"etag-1\"");
    object.setLocalEventId(6L);
    return object;
  }

  /**
   * @param href the collection the pair is bound to
   * @return the binding standing for the mirror collection
   */
  private CalendarSync mirror(String href) {
    CalendarSync pair = new CalendarSync();
    pair.setId(PAIR);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setRemoteHref(href);
    pair.setOrigin(SyncOrigin.MIRROR);
    return pair;
  }

  /**
   * @return the connected account
   */
  private CaldavUserSetting settings() {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername(LOGIN);
    setting.setPassword(PASSWORD);
    setting.setServerId(SERVER);
    return setting;
  }
}
