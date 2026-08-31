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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.ics.IcsEquivalence;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.MirrorVerification;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * Whether the copies eXo pushed are still there, and still what eXo wrote.
 *
 * <p>
 * The mirror is a projection and eXo is authoritative — but until this pass
 * ran, that was a claim rather than a guarantee: a copy deleted from someone's
 * phone stayed deleted and a copy a server rewrote stayed rewritten, both
 * silently, because nothing ever looked. These pin what looking now does, and
 * — as much — what it refuses to do on incomplete information.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavMirrorVerificationServiceTest {

  private static final long                  USER   = 42L;

  private static final long                  SERVER = 7L;

  private static final String                LOGIN  = "john";

  private static final String                MIRROR = "/dav/calendars/john/exo-meetings/";

  private static final String                HREF   = "/dav/calendars/john/exo-meetings/one.ics";

  private static final String                UID    = "evt-1";

  /** What eXo renders for the event this mirror row stands for. */
  private static final String                ICS    = "BEGIN:VCALENDAR\r\n"
      + "VERSION:2.0\r\n"
      + "PRODID:-//Exo Platform//NONSGML v1.0//EN\r\n"
      + "CALSCALE:GREGORIAN\r\n"
      + "BEGIN:VEVENT\r\n"
      + "SUMMARY:Sprint review\r\n"
      + "UID:evt-1\r\n"
      + "DTSTAMP:20260901T080000Z\r\n"
      + "DTSTART:20260901T090000Z\r\n"
      + "DTEND:20260901T100000Z\r\n"
      + "STATUS:CONFIRMED\r\n"
      + "TRANSP:OPAQUE\r\n"
      + "END:VEVENT\r\n"
      + "END:VCALENDAR\r\n";

  /** What eXo renders for a meeting this account's owner has been invited to. */
  private static final String                INVITED = ICS.replace("STATUS:CONFIRMED\r\n",
                                                                   "ATTENDEE;CN=John:mailto:john@acme.test\r\n"
                                                                       + "STATUS:CONFIRMED\r\n");

  /**
   * The same copy after the owner accepted it in their own calendar client:
   * one PARTSTAT, on a document the client also re-serialised on its way out.
   */
  private static final String                ANSWERED = INVITED.replace("ATTENDEE;CN=John:",
                                                                        "ATTENDEE;CN=John;PARTSTAT=ACCEPTED:");

  /**
   * The same meeting as a re-serialising server keeps it: its own PRODID, its
   * own property order, its own DTSTAMP, no redundant TRANSP, and the start
   * restated on a zone instead of in UTC. Not one byte in common with what eXo
   * sent, and exactly the same meeting.
   */
  private static final String                RESERIALISED = "BEGIN:VCALENDAR\r\n"
      + "PRODID:-//FakeMind//Calendar//EN\r\n"
      + "VERSION:2.0\r\n"
      + "BEGIN:VTIMEZONE\r\n"
      + "TZID:Europe/Paris\r\n"
      + "BEGIN:STANDARD\r\n"
      + "DTSTART:19710101T030000\r\n"
      + "TZOFFSETFROM:+0200\r\n"
      + "TZOFFSETTO:+0100\r\n"
      + "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"
      + "END:STANDARD\r\n"
      + "BEGIN:DAYLIGHT\r\n"
      + "DTSTART:19710101T020000\r\n"
      + "TZOFFSETFROM:+0100\r\n"
      + "TZOFFSETTO:+0200\r\n"
      + "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n"
      + "END:DAYLIGHT\r\n"
      + "END:VTIMEZONE\r\n"
      + "BEGIN:VEVENT\r\n"
      + "UID:evt-1\r\n"
      + "DTSTAMP:20260903T114500Z\r\n"
      + "DTSTART;TZID=Europe/Paris:20260901T110000\r\n"
      + "DTEND;TZID=Europe/Paris:20260901T120000\r\n"
      + "STATUS:CONFIRMED\r\n"
      + "SUMMARY:Sprint review\r\n"
      + "END:VEVENT\r\n"
      + "END:VCALENDAR\r\n";

  /** The same copy after a client rewrote the one thing eXo cannot let stand. */
  private static final String                HIJACKED = ICS.replace("SUMMARY:Sprint review", "SUMMARY:Hijacked");

  @Mock
  private CalDavClient                       calDavClient;

  @Mock
  private CaldavConnectorStorage             caldavConnectorStorage;

  @Mock
  private CaldavSyncStorage                  caldavSyncStorage;

  @Mock
  private CaldavPushService                  caldavPushService;

  @Mock
  private CaldavAnswerAdoptionService        caldavAnswerAdoptionService;

  /**
   * The registry, asked once per pass for two things: which behaviours this
   * server is excused for (EXO-89771), and whether a setting that governs the
   * copies has moved since this mirror last applied one (EXO-89759).
   *
   * <p>
   * Unstubbed everywhere except the settings-round tests below, so it answers
   * null — the "no registration" case, in which the comparison runs on the
   * deployment-wide fallback exactly as it did before EXO-89771 and no settings
   * round is ever owed. Every expectation outside those tests therefore still
   * measures what it did.
   */
  @Mock
  private CaldavServerService                caldavServerService;

  /** Where the pass records what it saw the server do; a sink here. */
  @Mock
  private CaldavServerQuirkService           caldavServerQuirkService;

  /**
   * The bulk move of EXO-89761, asked once per settings round.
   *
   * <p>
   * Stubbed leniently below to the answer a destination that did not change
   * produces — the destination is the collection the pair already points at,
   * nothing was pending, and nothing failed — because that is the case every
   * pre-existing expectation in this class was written against. The tests that
   * care about a relocation say so themselves.
   */
  @Mock
  private CaldavMirrorRelocationService      caldavMirrorRelocationService;

  @Mock
  private CaldavMirrorReportService          caldavMirrorReportService;

  /**
   * Reads the answers off a collection no binding reads (EXO-89814). Mocked
   * here: what it does is held by its own tests, and what belongs to this class
   * is only that it is asked, and asked before the comparison.
   */
  @Mock
  private CaldavMirrorAnswerService          caldavMirrorAnswerService;

  @Mock
  private CalDavEndpoint                     endpoint;

  /**
   * The real judge, never a mock. What this pass concludes <i>is</i> what the
   * comparison concludes, so a stubbed answer would test the plumbing and leave
   * the decision — the part that lost a live account's copies — unexercised.
   */
  @Spy
  private IcsEquivalence                     icsEquivalence = new IcsEquivalence();

  @InjectMocks
  private CaldavMirrorVerificationService    service;

  @BeforeEach
  public void connectAnAccountWithAMirror() {
    ReflectionTestUtils.setField(service, "maxRepairs", 3);
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
    lenient().when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(mirror()));
    lenient().when(caldavPushService.renderAgendaEvent(eq(USER), eq(5L), anyString())).thenReturn(ICS);
    lenient().when(caldavMirrorRelocationService.relocate(anyLong(), any(), any()))
             .thenReturn(new MirrorRelocation(MIRROR, 0, 0, 0, 0));
  }

  @Test
  public void aCopyTheServerNoLongerHoldsIsWrittenAgain() {
    // Someone deleted it from their phone. The mirror is eXo's projection and
    // nothing syncs back from it, so the answer is to put it back — not to
    // take the deletion as an instruction.
    givenServerHolds(Map.of());
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.missing());
    assertEquals(1, result.repaired());
    verify(caldavPushService).rewriteAgendaEvent(USER, 5L);
  }

  @Test
  public void aCopyStillThereAndUnchangedIsLeftAlone() {
    // The common case, and it must cost one listing and nothing else: an
    // unchanged ETag is the server's own promise that the bytes are the ones
    // it was given.
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.checked());
    assertEquals(0, result.missing());
    assertEquals(0, result.altered());
    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
  }

  @Test
  public void aPassThatFoundNothingStillSettlesTheServersSummary() {
    // The trigger the whole cleanup hangs from, and the one that was missing.
    // This is the converged account: an unchanged ETag, no fetch, no
    // comparison, no divergence - so nothing ever called into the summary and
    // nothing was ever pruned. The record that most needs clearing lives on
    // precisely this account, because clearing it is what made it converge.
    CaldavServer server = new CaldavServer();
    server.setId(SERVER);
    when(caldavServerService.resolveServer(any())).thenReturn(server);
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));

    service.verify(USER);

    verify(caldavServerQuirkService).settle(SERVER);
    verify(caldavServerQuirkService, never()).observe(anyLong(), anyList());
  }

  @Test
  public void aQuotingDifferenceIsNotARewrite() {
    // Servers publish the same ETag weak, quoted, or bare. Comparing the
    // strings as they arrive would fetch and re-push every object on every
    // pass, against a server that changed nothing.
    givenServerHolds(Map.of(HREF, "W/\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));

    assertEquals(0, service.verify(USER).altered());
    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
  }

  @Test
  public void aCopyTheServerRewroteIsWrittenAgain() {
    // Somebody opened the copy on their phone and renamed the meeting. The
    // mirror is eXo's projection, so eXo writes it back.
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                                            .thenReturn(new CalendarObject(HREF,
                                                                                                           "\"etag-2\"",
                                                                                                           RESERIALISED.replace("Sprint review",
                                                                                                                                "Sprint review (moved to the pub)")));

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.altered());
    assertEquals(1, result.repaired());
    // Through the unconditional entry point, and it matters which: the
    // guarded one carries the etag recorded before the server rewrote the
    // object, so it is refused every time — which is how this pass came to
    // report "altered: 1, re-pushed: 0" on a live account while this test,
    // mocking the push service, stayed green.
    verify(caldavPushService).rewriteAgendaEvent(USER, 5L);
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  @Test
  public void anAnswerOnARewrittenCopyIsAdoptedBeforeTheRepair() {
    // The ETag moved, so the client wrote the object after eXo did: whatever
    // answer it carries is the user's latest word. It is read and recorded
    // BEFORE the repair — the both-changed case is exactly the one where a
    // repair-first pass would overwrite the acceptance nobody had read yet.
    // A real answer on a real copy: eXo renders the invitation, the client
    // wrote back the same meeting with one PARTSTAT changed. The content check
    // has to call that altered on the strength of the PARTSTAT alone, and hand
    // the copy on so the answer can be read off it.
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(caldavPushService.renderAgendaEvent(eq(USER), eq(5L), anyString())).thenReturn(INVITED);
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                                            .thenReturn(new CalendarObject(HREF,
                                                                                                           "\"etag-2\"",
                                                                                                           ANSWERED));
    when(caldavAnswerAdoptionService.adoptAnswer(USER, 5L, ANSWERED))
                                                                     .thenReturn(CaldavAnswerAdoptionService.Outcome.ADOPTED);

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.altered());
    assertEquals(1, result.adopted());
    assertEquals(1, result.repaired());
    InOrder order = inOrder(caldavAnswerAdoptionService, caldavPushService);
    order.verify(caldavAnswerAdoptionService).adoptAnswer(USER, 5L, ANSWERED);
    order.verify(caldavPushService).rewriteAgendaEvent(USER, 5L);
  }

  @Test
  public void anAdoptedAnswerRecordsTheClientsEtagSoItIsNeverAdoptedTwice() {
    // Without recording what was just read, the next pass sees the same moved
    // ETag, reads the same answer, and adopts it again for ever — over any
    // answer the user gives in eXo later. The record is what closes the loop.
    String declined = ANSWERED.replace("PARTSTAT=ACCEPTED", "PARTSTAT=DECLINED");
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    ObjectSync row = mapping(HREF, "\"etag-1\"", 5L);
    givenMappings(row);
    when(caldavPushService.renderAgendaEvent(eq(USER), eq(5L), anyString())).thenReturn(INVITED);
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                                            .thenReturn(new CalendarObject(HREF,
                                                                                                           "\"etag-2\"",
                                                                                                           declined));
    when(caldavAnswerAdoptionService.adoptAnswer(USER, 5L, declined))
                                                                     .thenReturn(CaldavAnswerAdoptionService.Outcome.ADOPTED);

    service.verify(USER);

    ArgumentCaptor<ObjectSync> saved = ArgumentCaptor.forClass(ObjectSync.class);
    verify(caldavSyncStorage).saveObject(saved.capture());
    // The ETag, and only the ETag: EXO-89716 removed every stored digest, and
    // the version is what the direction rule reads on the next pass anyway.
    assertEquals("\"etag-2\"", saved.getValue().getEtag());

    // The second pass finds the recorded ETag and does not even fetch, let
    // alone re-adopt: the direction rule now reads the copy as untouched.
    MirrorVerification second = service.verify(USER);

    assertEquals(0, second.adopted());
    verify(caldavAnswerAdoptionService, times(1)).adoptAnswer(anyLong(), anyLong(), anyString());
  }

  @Test
  public void aFailedAdoptionLeavesTheCopyAlone() {
    // The object still holds the only record of the user's answer. A repair
    // here would overwrite it on the strength of a transient agenda failure;
    // the next pass reads the same answer again instead.
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(caldavPushService.renderAgendaEvent(eq(USER), eq(5L), anyString())).thenReturn(INVITED);
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                                            .thenReturn(new CalendarObject(HREF,
                                                                                                           "\"etag-2\"",
                                                                                                           ANSWERED));
    when(caldavAnswerAdoptionService.adoptAnswer(USER, 5L, ANSWERED))
                                                                     .thenReturn(CaldavAnswerAdoptionService.Outcome.FAILED);

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.altered());
    assertEquals(0, result.adopted());
    assertEquals(0, result.repaired());
    verify(caldavPushService, never()).rewriteAgendaEvent(anyLong(), anyLong());
    verify(caldavSyncStorage, never()).saveObject(any());
  }

  @Test
  public void aCopyThatCouldNotBeReadIsLeftAloneRatherThanRepairedOver() {
    // Same protection, different ignorance. FAILED says "an answer was found
    // and I could not record it"; UNREADABLE says "I could not read the copy
    // at all, so I do not know what is on it". Repairing on the second is the
    // more dangerous of the two — it writes eXo's own answer over a body
    // nobody has looked inside, and that body is exactly where a user's
    // answer from their own client lives (EXO-89820).
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(caldavPushService.renderAgendaEvent(eq(USER), eq(5L), anyString())).thenReturn(INVITED);
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                                            .thenReturn(new CalendarObject(HREF,
                                                                                                           "\"etag-2\"",
                                                                                                           ANSWERED));
    when(caldavAnswerAdoptionService.adoptAnswer(USER, 5L, ANSWERED))
                                                                     .thenReturn(CaldavAnswerAdoptionService.Outcome.UNREADABLE);

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.altered());
    assertEquals(0, result.adopted());
    assertEquals(0, result.repaired());
    verify(caldavPushService, never()).rewriteAgendaEvent(anyLong(), anyLong());
    verify(caldavSyncStorage, never()).saveObject(any());
  }

  @Test
  public void anAnswerGivenInExoAloneIsPushedNotAdopted() {
    // The direction rule's other half. The ETag still matches what eXo
    // recorded, so the copy is untouched since the last write: whatever
    // differs between agenda and the copy is eXo-side, the ordinary push owns
    // overwriting it, and nothing is read off the object at all — which is
    // what stops "answered in eXo, not pushed yet" being mistaken for an
    // answer from the phone.
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));

    MirrorVerification result = service.verify(USER);

    assertEquals(0, result.adopted());
    verify(caldavAnswerAdoptionService, never()).adoptAnswer(anyLong(), anyLong(), anyString());
    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
  }

  @Test
  public void aRowWithNoEventAndNoObjectIsDropped() {
    // Gone from the server and standing for no eXo event: the row describes
    // nothing on either side, so there is nothing to protect by keeping it —
    // only a missing count that never reaches zero.
    givenServerHolds(Map.of());
    ObjectSync orphan = mapping(HREF, "\"etag-1\"", null);
    orphan.setId(77L);
    givenMappings(orphan);

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.missing());
    assertEquals(0, result.repaired());
    verify(caldavSyncStorage).deleteObject(77L);
  }

  @Test
  public void aRowWithNoEventWhoseObjectIsStillThereIsNotJudgedAndIsKept() {
    // Nothing to compare it against: the baseline is what eXo would render for
    // the event, and this row stands for no event. Saying "altered" would be a
    // claim the pass cannot support, and it is not made — the object is not
    // even fetched. The row stays, because it is the only link to a copy the
    // user may well want.
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    ObjectSync orphan = mapping(HREF, "\"etag-1\"", null);
    orphan.setId(77L);
    givenMappings(orphan);

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.checked());
    assertEquals(0, result.altered());
    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
    verify(caldavSyncStorage, never()).deleteObject(anyLong());
  }

  @Test
  public void aRowTheRepairWrotePastIsDroppedRatherThanReportedForEver() {
    // Two rows can carry the same event when a copy moved: the push writes to
    // the href recorded for the event's UID, so the other row names an object
    // nobody will ever write again. Kept, every pass reports it missing and
    // the calendar never stops "needing attention" however often the repair
    // succeeds — which is exactly what a live account did.
    givenServerHolds(Map.of());
    ObjectSync stale = mapping(HREF, "\"etag-1\"", 5L);
    stale.setId(9002L);
    givenMappings(stale);
    ObjectSync elsewhere = mapping(MIRROR + "moved.ics", "\"etag-3\"", 5L);
    elsewhere.setId(9003L);
    when(caldavPushService.rewriteAgendaEvent(USER, 5L)).thenReturn(elsewhere);

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.repaired());
    verify(caldavSyncStorage).deleteObject(9002L);
  }

  @Test
  public void aRowTheRepairWroteBackIntoIsKept() {
    // The ordinary case: the repair wrote to this very row. Dropping it would
    // throw away the mapping the push had just refreshed.
    //
    // The identifier is deliberately larger than 127. These are Long, so a
    // comparison written with == answers on references, and every value
    // inside the boxing cache answers true by accident — which is exactly how
    // an earlier version of this test passed against code that deleted the
    // row. Anything a real database hands out is past the cache.
    givenServerHolds(Map.of());
    ObjectSync row = mapping(HREF, "\"etag-1\"", 5L);
    row.setId(9001L);
    givenMappings(row);
    ObjectSync same = mapping(HREF, "\"etag-2\"", 5L);
    same.setId(9001L);
    when(caldavPushService.rewriteAgendaEvent(USER, 5L)).thenReturn(same);

    service.verify(USER);

    verify(caldavSyncStorage, never()).deleteObject(anyLong());
  }

  @Test
  public void aRowCarryingNoIdentifierIsLeftAlone() {
    // A row that was never persisted has a null identifier, and unboxing one
    // to compare it against zero throws where the pass should simply move on.
    givenServerHolds(Map.of());
    ObjectSync row = mapping(HREF, "\"etag-1\"", 5L);
    row.setId(null);
    givenMappings(row);
    ObjectSync elsewhere = mapping(MIRROR + "moved.ics", "\"etag-3\"", 5L);
    elsewhere.setId(4242L);
    when(caldavPushService.rewriteAgendaEvent(USER, 5L)).thenReturn(elsewhere);

    service.verify(USER);

    verify(caldavSyncStorage, never()).deleteObject(anyLong());
  }

  @Test
  public void anEtagThatMovedOverAReSerialisationIsNotARewrite() {
    // EXO-89716 in one assertion. The server holds the same meeting written its
    // own way — its PRODID, its property order, its own DTSTAMP, the start on a
    // zone rather than in UTC — and the ETag moved because it stored it. Judged
    // on bytes this is a rewrite, on every pass, for ever; judged on meaning it
    // is nothing at all.
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                                            .thenReturn(new CalendarObject(HREF,
                                                                                                           "\"etag-2\"",
                                                                                                           RESERIALISED));

    assertEquals(0, service.verify(USER).altered());
    verify(caldavPushService, never()).rewriteAgendaEvent(anyLong(), anyLong());
  }

  @Test
  public void aVersionThatMovedOverAnEquivalentCopyIsRecorded() {
    // Having paid a fetch to establish that the bytes are still ours, the pass
    // has learnt the object's current version. Keeping the superseded one
    // makes every later pass fetch the object again to reach the same
    // conclusion — and, worse, makes the next ordinary update carry an
    // If-Match the server has already left behind, which it refuses. That
    // refusal is an eXo-side edit silently not reaching the copy.
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                                            .thenReturn(new CalendarObject(HREF,
                                                                                                           "\"etag-2\"",
                                                                                                           RESERIALISED));

    service.verify(USER);

    ArgumentCaptor<ObjectSync> saved = ArgumentCaptor.forClass(ObjectSync.class);
    verify(caldavSyncStorage).saveObject(saved.capture());
    assertEquals("\"etag-2\"", saved.getValue().getEtag());
  }

  @Test
  public void aVersionThatDidNotMoveIsNotWrittenBack() {
    // Only ever on a version that actually changed. A pass over a converged
    // mirror must cost a listing and nothing else — not a database write per
    // object per sweep.
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));

    service.verify(USER);

    verify(caldavSyncStorage, never()).saveObject(any());
  }

  @Test
  public void aCopyWhoseEventCanNoLongerBeRenderedIsLeftAlone() {
    // The event was deleted in eXo, or is no longer visible to this user.
    // Nothing here can say what the copy ought to say, and a re-push on that
    // basis would overwrite a calendar on the strength of an absence.
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(caldavPushService.renderAgendaEvent(eq(USER), eq(5L), anyString())).thenReturn(null);

    assertEquals(0, service.verify(USER).altered());
    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
    verify(caldavPushService, never()).rewriteAgendaEvent(anyLong(), anyLong());
  }

  @Test
  public void anUnreadableCopyIsLeftAloneRatherThanOverwritten() {
    // Unreadable is not the same as rewritten, and a re-push here would
    // overwrite whatever is there on the strength of a network error.
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString())).thenThrow(new IllegalStateException("down"));

    assertEquals(0, service.verify(USER).altered());
    verify(caldavPushService, never()).rewriteAgendaEvent(anyLong(), anyLong());
  }

  @Test
  public void aCollectionThatCannotBeListedVerifiesNothing() {
    // Treating an unreachable server as "everything was deleted" would
    // re-push the user's whole history the moment it came back.
    when(calDavClient.listResourceEtags(any(), anyString(), anyString(), anyString()))
                                                                                     .thenThrow(new IllegalStateException("down"));

    assertEquals(0, service.verify(USER).checked());
    verify(caldavSyncStorage, never()).getObjects(anyLong(), anyInt(), anyInt());
  }

  @Test
  public void anObjectThatKeepsGoingWrongIsAbandonedRatherThanFoughtForEver() {
    // A server refusing writes it pretends to accept, or a rule on the account
    // deleting what eXo sends. Re-pushing on every sync for ever is worse than
    // saying so once and stopping.
    givenServerHolds(Map.of());
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));

    for (int attempt = 0; attempt < 3; attempt++) {
      assertEquals(1, service.verify(USER).repaired());
    }
    MirrorVerification fourth = service.verify(USER);

    assertEquals(0, fourth.repaired());
    assertEquals(1, fourth.abandoned());
    verify(caldavPushService, times(3)).rewriteAgendaEvent(USER, 5L);
  }

  @Test
  public void forgettingAnAccountLetsItBeRepairedAgain() {
    // Reconnecting, or a restart. The count records that something is going
    // wrong right now, not a fact about the account worth keeping.
    givenServerHolds(Map.of());
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    for (int attempt = 0; attempt < 4; attempt++) {
      service.verify(USER);
    }

    service.forgetRepairs(USER);

    assertEquals(1, service.verify(USER).repaired());
  }

  @Test
  public void aCopyStandingForNoKnownEventIsNotRepaired() {
    // It cannot be rebuilt from anything, and deleting it would be a guess
    // about data the user may want.
    givenServerHolds(Map.of());
    givenMappings(mapping(HREF, "\"etag-1\"", null));

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.missing());
    assertEquals(0, result.repaired());
    verify(caldavPushService, never()).rewriteAgendaEvent(anyLong(), anyLong());
  }

  // ------------- what an administrator can read afterwards (EXO-89762)

  @Test
  public void everyPassThatRanAgainstTheAccountIsLeftWhereAnAdministratorCanReadIt() {
    // Without this the only record of a pass is the platform log, and the
    // add-on's own advice - move to the account's main calendar once copies
    // synchronise cleanly - becomes an instruction nobody outside operations
    // can follow.
    givenServerHolds(Map.of());
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));

    service.verify(USER);

    verify(caldavMirrorReportService).record(eq(USER), any(MirrorVerification.class), any(MirrorRelocation.class));
  }

  @Test
  public void aPassThatCouldNotEvenListTheCollectionIsReportedToo() {
    // The pass an administrator most needs to see. Reporting only the passes
    // that succeeded would show a change of destination as quietly finished on
    // exactly the accounts it never reached.
    when(calDavClient.listResourceEtags(any(), anyString(), anyString(), anyString()))
                                                                                     .thenThrow(new IllegalStateException("down"));

    service.verify(USER);

    verify(caldavMirrorReportService).record(eq(USER), any(MirrorVerification.class), any(MirrorRelocation.class));
  }

  @Test
  public void aPassResultIsStillReturnedWhenItCouldNotBeRecorded() {
    // An observability aid must never cost a pass its result: nothing reads
    // these reports and nothing depends on them.
    givenServerHolds(Map.of());
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    doThrow(new IllegalStateException("no store")).when(caldavMirrorReportService)
                                                  .record(anyLong(), any(), any());

    assertEquals(1, service.verify(USER).checked());
  }

  @Test
  public void forgettingAnAccountAlsoForgetsTheTallyAttributedToIt() {
    // A number attributed to an account that no longer exists is not stale, it
    // is wrong.
    service.forgetRepairs(USER);

    verify(caldavMirrorReportService).forget(USER);
  }

  @Test
  public void anAccountThatHasNeverPushedAnythingIsNotAFailure() {
    // Most accounts, until the first meeting is copied.
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of());

    assertEquals(0, service.verify(USER).checked());
    verify(calDavClient, never()).listResourceEtags(any(), anyString(), anyString(), anyString());
  }

  @Test
  public void noConnectedAccountVerifiesNothing() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    assertEquals(0, service.verify(USER).checked());
  }

  /**
   * @param etags what the server currently holds
   */
  private void givenServerHolds(Map<String, String> etags) {
    when(calDavClient.listResourceEtags(any(), anyString(), anyString(), anyString())).thenReturn(etags);
  }


  // ------------- what giving up must stop doing as well as writing (EXO-89756)

  @Test
  public void anAbandonedCopyIsNotFetchedAndComparedAllOverAgainEveryPass() {
    // Giving up used to stop the writing and nothing else: the check sat after
    // the copy had already been fetched, re-rendered, compared and named in an
    // INFO line, so an abandoned copy went on costing a round trip and a log
    // line every five minutes for ever. On the rig that was four copies against
    // a live BlueMind account, 399 times in one day.
    givenAnUnwinnableFight();

    for (int pass = 0; pass < 4; pass++) {
      service.verify(USER);
    }
    MirrorVerification afterAbandonment = service.verify(USER);

    assertEquals(1, afterAbandonment.checked());
    assertEquals(1, afterAbandonment.abandoned());
    assertEquals(0, afterAbandonment.altered(), "an unexamined copy must not be reported as judged");
    verify(calDavClient, times(4)).fetchObject(any(), eq(HREF), anyString(), anyString());
  }

  @Test
  public void anAbandonedCopyTheUserAnswersOnTheirPhoneIsStillReadBack() {
    // The pair, and the reason the settled state records the version rather
    // than a flag. Abandonment is a statement about eXo's writing, never about
    // the user's: the copy still sits in their calendar and they can still
    // accept the meeting on it. An ETag that moves away from the settled one is
    // the server saying somebody wrote, and the pass looks again.
    givenAnUnwinnableFight();
    for (int pass = 0; pass < 4; pass++) {
      service.verify(USER);
    }

    givenServerHolds(Map.of(HREF, "\"etag-9\""));
    service.verify(USER);

    verify(calDavClient, times(5)).fetchObject(any(), eq(HREF), anyString(), anyString());
  }

  @Test
  public void anAbandonedCopyThatSettlesAgainStopsCostingAFetchAgain() {
    // The version has to be re-recorded when the pass does look again, or the
    // saving lasts exactly one sweep and the loop comes back.
    givenAnUnwinnableFight();
    for (int pass = 0; pass < 4; pass++) {
      service.verify(USER);
    }
    givenServerHolds(Map.of(HREF, "\"etag-9\""));
    service.verify(USER);

    service.verify(USER);

    verify(calDavClient, times(5)).fetchObject(any(), eq(HREF), anyString(), anyString());
  }

  @Test
  public void forgettingAnAccountAlsoForgetsWhatItsCopiesSettledAt() {
    // A restart forgives, and forgiving must reach both halves of the state:
    // a settled version left behind would keep a no-longer-abandoned copy
    // unexamined.
    givenAnUnwinnableFight();
    for (int pass = 0; pass < 4; pass++) {
      service.verify(USER);
    }

    service.forgetRepairs(USER);
    MirrorVerification afterForgetting = service.verify(USER);

    assertEquals(1, afterForgetting.altered());
    assertEquals(1, afterForgetting.repaired());
  }

  /**
   * A copy the server rewrites back to something else however often eXo
   * repairs it: the listing never moves off its own version, the fetch always
   * returns a different meeting, and the repair never makes any difference.
   * Four passes of this abandon it.
   */
  private void givenAnUnwinnableFight() {
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                                            .thenReturn(new CalendarObject(HREF,
                                                                                                           "\"etag-2\"",
                                                                                                           HIJACKED));
  }

  /**
   * @param objects the mapping rows of the mirror
   */
  private void givenMappings(ObjectSync... objects) {
    when(caldavSyncStorage.getObjects(eq(3L), eq(0), anyInt())).thenReturn(new PageImpl<>(List.of(objects)));
    when(caldavSyncStorage.getObjects(eq(3L), eq(1), anyInt())).thenReturn(new PageImpl<>(List.of()));
  }

  /**
   * @param href where the copy lives
   * @param etag the ETag recorded when it was written
   * @param localEventId the agenda event it stands for
   * @return the mapping row
   */
  private ObjectSync mapping(String href, String etag, Long localEventId) {
    ObjectSync object = new ObjectSync();
    object.setId(1L);
    object.setCalendarSyncId(3L);
    object.setIcsUid(UID);
    object.setRemoteHref(href);
    object.setEtag(etag);
    object.setLocalEventId(localEventId);
    return object;
  }

  /**
   * @return the binding standing for the mirror collection
   */
  private CalendarSync mirror() {
    CalendarSync pair = new CalendarSync();
    pair.setId(3L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setRemoteHref(MIRROR);
    pair.setOrigin(SyncOrigin.MIRROR);
    return pair;
  }

  /**
   * @return the connected account
   */
  private CaldavUserSetting settings() {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername(LOGIN);
    setting.setPassword("secret");
    setting.setServerId(SERVER);
    return setting;
  }

  // ------- a settings change reaching the copies already written (EXO-89759)

  /** When the administrator changed a setting that governs the copies. */
  private static final java.util.Date SETTINGS_CHANGED = new java.util.Date(1_800_000_000_000L);

  /** A moment before that, for a pair that has already applied an older one. */
  private static final java.util.Date EARLIER          = new java.util.Date(1_700_000_000_000L);

  @Test
  public void aSettingsChangeReachesACopyThatWasAlreadyWritten() {
    // The whole point. The copy is exactly as eXo left it — the ETag has not
    // moved and never will — but eXo now renders something else for the same
    // event, because an administrator turned the answer links off. Without this
    // round nothing ever looks at that copy again.
    givenTheServerSettingsChanged();
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                    .thenReturn(new CalendarObject(HREF,
                                                                                   "\"etag-1\"",
                                                                                   ICS.replace("SUMMARY:Sprint review",
                                                                                               "SUMMARY:Sprint review "
                                                                                                   + "(Accept: http://exo/a)")));

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.altered(), "the copy no longer says what eXo would write for the event");
    assertEquals(1, result.repaired());
    verify(caldavPushService).rewriteAgendaEvent(USER, 5L);
  }

  @Test
  public void aConvergedAccountIsNotRePushedByASettingsRound() {
    // THE anti-churn pin, and the one the obvious wrong implementation fails:
    // "the settings changed, so re-push everything" writes every copy of every
    // account at once, on servers that stamp and re-serialise what they store —
    // which is the churn EXO-89716 and EXO-89756 spent two days removing, at
    // scale. The round compares. Three copies, all of them already saying what
    // eXo says (one of them only after the server re-serialised it), and not one
    // byte goes back.
    givenTheServerSettingsChanged();
    String second = "/dav/calendars/john/exo-meetings/two.ics";
    String third = "/dav/calendars/john/exo-meetings/three.ics";
    givenServerHolds(Map.of(HREF, "\"etag-1\"", second, "\"etag-2\"", third, "\"etag-3\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L),
                  mapping(second, "\"etag-2\"", 5L),
                  mapping(third, "\"etag-3\"", 5L));
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                    .thenReturn(new CalendarObject(HREF, "\"etag-1\"", ICS));
    when(calDavClient.fetchObject(any(), eq(second), anyString(), anyString()))
                                                    .thenReturn(new CalendarObject(second, "\"etag-2\"", RESERIALISED));
    when(calDavClient.fetchObject(any(), eq(third), anyString(), anyString()))
                                                    .thenReturn(new CalendarObject(third, "\"etag-3\"", ICS));

    MirrorVerification result = service.verify(USER);

    assertEquals(3, result.checked());
    assertEquals(0, result.altered(), "a copy that already says what eXo says is not altered");
    assertEquals(0, result.repaired(), "and nothing about a settings round may write it again");
    verify(caldavPushService, never()).rewriteAgendaEvent(anyLong(), anyLong());
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong());
  }

  @Test
  public void aSettingsRoundStillCostsOneFetchPerCopyAndOnlyOnce() {
    // The price it does pay, stated so that a change which quietly makes it
    // permanent is visible. One round fetches every copy; the round after it,
    // with the stamp applied, is back to one listing and nothing else.
    givenTheServerSettingsChanged();
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                    .thenReturn(new CalendarObject(HREF, "\"etag-1\"", ICS));

    service.verify(USER);
    givenTheMirrorHasApplied(SETTINGS_CHANGED);
    service.verify(USER);

    verify(calDavClient, times(1)).fetchObject(any(), eq(HREF), anyString(), anyString());
  }

  @Test
  public void aRoundRunsExactlyWhenTheMirrorIsBehindTheServer() {
    // The condition, both ways round. Behind: the copies are compared. Level:
    // nothing is fetched at all, which is what keeps the ordinary pass costing
    // one listing on a converged mirror.
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    lenient().when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
             .thenReturn(new CalendarObject(HREF, "\"etag-1\"", ICS));

    givenTheServerSettingsChanged();
    givenTheMirrorHasApplied(EARLIER);
    service.verify(USER);
    verify(calDavClient, times(1)).fetchObject(any(), eq(HREF), anyString(), anyString());

    givenTheMirrorHasApplied(SETTINGS_CHANGED);
    service.verify(USER);
    verify(calDavClient, times(1)).fetchObject(any(), eq(HREF), anyString(), anyString());
  }

  @Test
  public void anUpgradedInstallationDoesNothingUntilAnAdministratorActs() {
    // Both stamps null, which is the state every existing deployment is in the
    // moment the columns are added. Null means nothing to apply: no round, no
    // fetch, and nothing written on the pair either — an upgrade that woke every
    // connected account up to compare its whole calendar would be a worse bug
    // than the one this fixes.
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));

    service.verify(USER);

    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  @Test
  public void aCrashMidRoundLeavesTheStampUnsetAndTheNextPassCompletesIt() {
    // Resumable by construction: the stamp is written after the walk, so
    // anything that ends the round early never reaches it. The pair stays
    // behind, and the next sweep re-runs a comparison that rewrites nothing
    // already converged.
    givenTheServerSettingsChanged();
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    when(caldavSyncStorage.getObjects(eq(3L), eq(0), anyInt())).thenThrow(new IllegalStateException("the database went away"))
                                                              .thenReturn(new PageImpl<>(List.of(mapping(HREF,
                                                                                                         "\"etag-1\"",
                                                                                                         5L))));
    lenient().when(caldavSyncStorage.getObjects(eq(3L), eq(1), anyInt())).thenReturn(new PageImpl<>(List.of()));
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                    .thenReturn(new CalendarObject(HREF, "\"etag-1\"", ICS));

    assertThrows(IllegalStateException.class, () -> service.verify(USER));
    verify(caldavSyncStorage, never()).savePair(any());

    service.verify(USER);

    ArgumentCaptor<CalendarSync> stamped = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(stamped.capture());
    assertEquals(SETTINGS_CHANGED, stamped.getValue().getCopySettingsApplied());
  }

  @Test
  public void theStampRecordedIsTheServersValueAndNotTheMomentTheRoundFinished() {
    // A round over a large calendar takes minutes. Stamping "now" would swallow
    // a second change an administrator made while it ran; stamping the value the
    // round actually applied leaves that change plainly newer.
    givenTheServerSettingsChanged();
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                    .thenReturn(new CalendarObject(HREF, "\"etag-1\"", ICS));

    service.verify(USER);

    ArgumentCaptor<CalendarSync> stamped = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(stamped.capture());
    assertEquals(SETTINGS_CHANGED, stamped.getValue().getCopySettingsApplied());
  }

  @Test
  public void anAnswerIsNotAdoptedOffACopyNobodyWrote() {
    // The direction rule survives the round. In a settings round the difference
    // is usually eXo's own — the render moved under a copy nobody touched — and
    // handing that copy to the adoption would read eXo's own last writing back
    // as though it were the user's latest answer, over whatever they have since
    // said in eXo. The ETag is still the only proof that somebody else wrote.
    //
    // The copy is stated as carrying nothing at risk, deliberately: this test
    // is about the ADOPTION being refused, and leaving the question unstubbed
    // would make it pass through EXO-89814's refusal-to-repair instead — an
    // assertion answered by the wrong mechanism is an assertion that no longer
    // holds what it names.
    givenTheServerSettingsChanged();
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(caldavPushService.renderAgendaEvent(eq(USER), eq(5L), anyString())).thenReturn(INVITED);
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                    .thenReturn(new CalendarObject(HREF, "\"etag-1\"", ANSWERED));
    when(caldavAnswerAdoptionService.holdsUnrecordedAnswer(USER, 5L, ANSWERED)).thenReturn(false);

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.altered());
    assertEquals(0, result.adopted(), "an unmoved ETag is not the client's writing, whatever the round is doing");
    verify(caldavAnswerAdoptionService, never()).adoptAnswer(anyLong(), anyLong(), anyString());
    verify(caldavPushService).rewriteAgendaEvent(USER, 5L);
  }

  @Test
  public void aSettingsRoundNeverWritesOverAnAnswerNothingHasRead() {
    // The more serious half of EXO-89814, and the one that loses data rather
    // than merely failing to gain it. A settings round removes the ETag gate:
    // every copy is fetched and judged on its content, and one that differs is
    // repaired. On a server that records an answer WITHOUT moving the ETag —
    // BlueMind, measured — the answer IS that difference, and nothing adopts it
    // because the direction is unproven. So the round used to write eXo's own
    // render straight over the only record of the user's answer.
    //
    // Refusing the repair is safe whichever way the difference runs: if the
    // client wrote it, the answer survives to be adopted; if eXo did, an
    // unrepaired copy costs a setting and nothing else.
    givenTheServerSettingsChanged();
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(caldavPushService.renderAgendaEvent(eq(USER), eq(5L), anyString())).thenReturn(INVITED);
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                    .thenReturn(new CalendarObject(HREF, "\"etag-1\"", ANSWERED));
    when(caldavAnswerAdoptionService.holdsUnrecordedAnswer(USER, 5L, ANSWERED)).thenReturn(true);

    service.verify(USER);

    verify(caldavPushService, never()).rewriteAgendaEvent(anyLong(), anyLong());
  }

  @Test
  public void theRoundIsStillStampedWhenACopyWasLeftAlone() {
    // Holding the stamp back would make the round owed for ever on a server
    // where the answer can never be adopted — and an owed round costs a fetch
    // per copy on EVERY sweep, which is the unbounded read shape that got this
    // rig's test proxy banned twice. A handful of copies keeping the settings
    // they were written with is the smaller wrong, and the log says so.
    givenTheServerSettingsChanged();
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(caldavPushService.renderAgendaEvent(eq(USER), eq(5L), anyString())).thenReturn(INVITED);
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                    .thenReturn(new CalendarObject(HREF, "\"etag-1\"", ANSWERED));
    when(caldavAnswerAdoptionService.holdsUnrecordedAnswer(USER, 5L, ANSWERED)).thenReturn(true);

    service.verify(USER);

    ArgumentCaptor<CalendarSync> stamped = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(stamped.capture());
    assertEquals(SETTINGS_CHANGED, stamped.getValue().getCopySettingsApplied());
  }

  @Test
  public void theAnswersOnACollectionNothingElseReadsAreReadBeforeItIsCompared() {
    // EXO-89814's first half, held where the ordering lives. The read has to
    // come before the comparison twice over: it is the only pass that meets a
    // copy in a collection no binding reads, so an answer on it is read there
    // or nowhere — and reading it first is what leaves agenda holding the
    // answer by the time the settings round decides whether the copy differs.
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));

    service.verify(USER);

    InOrder order = inOrder(caldavMirrorAnswerService, calDavClient);
    order.verify(caldavMirrorAnswerService).readAnswers(eq(USER), any(), any());
    order.verify(calDavClient).listResourceEtags(any(), anyString(), anyString(), anyString());
  }

  @Test
  public void anAnswerIsStillAdoptedWhenTheCopyDidMoveDuringASettingsRound() {
    // The other half, so that the guard above cannot be read as "a settings
    // round never adopts". A copy whose version moved is the client's writing
    // whatever else is going on, and the answer on it is read before the repair
    // exactly as it always was.
    givenTheServerSettingsChanged();
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(caldavPushService.renderAgendaEvent(eq(USER), eq(5L), anyString())).thenReturn(INVITED);
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                    .thenReturn(new CalendarObject(HREF, "\"etag-2\"", ANSWERED));
    when(caldavAnswerAdoptionService.adoptAnswer(USER, 5L, ANSWERED))
                                                    .thenReturn(CaldavAnswerAdoptionService.Outcome.ADOPTED);

    assertEquals(1, service.verify(USER).adopted());
  }

  @Test
  public void anAbandonedCopyIsStillLeftAloneByASettingsRound() {
    // The round is the existing pass minus ONE gate, and the repair cap is not
    // it. Giving up is a statement about eXo's writing that a setting does not
    // retract — and re-opening the fight on every settings change is how a
    // permanently unwritable copy comes back to cost a round trip for ever.
    givenAnUnwinnableFight();
    for (int pass = 0; pass < 4; pass++) {
      service.verify(USER);
    }

    givenTheServerSettingsChanged();
    MirrorVerification duringTheRound = service.verify(USER);

    assertEquals(1, duringTheRound.abandoned());
    assertEquals(0, duringTheRound.repaired());
    verify(calDavClient, times(4)).fetchObject(any(), eq(HREF), anyString(), anyString());
  }

  @Test
  public void aMirrorPairDeletedUnderTheRoundIsNotRecreatedToCarryAStamp() {
    // The pair went while the round walked. There is nothing left to apply a
    // setting to, and writing the row back would resurrect a binding somebody
    // deliberately removed.
    givenTheServerSettingsChanged();
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                    .thenReturn(new CalendarObject(HREF, "\"etag-1\"", ICS));
    when(caldavSyncStorage.getPair(3L)).thenReturn(null);

    service.verify(USER);

    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * An administrator has changed a setting that governs the copies, and this
   * mirror has never applied one.
   */
  private void givenTheServerSettingsChanged() {
    CaldavServer server = new CaldavServer();
    server.setId(SERVER);
    server.setCopySettingsUpdated(SETTINGS_CHANGED);
    when(caldavServerService.resolveServer(SERVER)).thenReturn(server);
    lenient().when(caldavSyncStorage.getPair(3L)).thenReturn(mirror());
  }

  /**
   * The mirror has already carried the copies through a comparison for a given
   * server stamp.
   *
   * @param applied the server stamp the pair has applied
   */
  private void givenTheMirrorHasApplied(java.util.Date applied) {
    CalendarSync pair = mirror();
    pair.setCopySettingsApplied(applied);
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair));
  }

  // ---- moving the copies when the destination changes (EXO-89761) ----------

  /** Where the copies go after an administrator moved the destination. */
  private static final String MOVED_TO = "/dav/calendars/john/personal/";

  @Test
  public void thePassLooksAtTheCollectionTheCopiesWereJustMovedInto() {
    // The relocation runs BEFORE the listing, and the listing has to follow it.
    // Listing first would spend the whole round comparing the contents of a
    // calendar the copies have just left — and then report every one of them
    // missing.
    givenTheServerSettingsChanged();
    when(caldavMirrorRelocationService.relocate(anyLong(), any(), any()))
                                                          .thenReturn(new MirrorRelocation(MOVED_TO, 1, 0, 0, 0));
    givenServerHolds(Map.of());
    givenMappings(mapping(MOVED_TO + "one.ics", "\"etag-9\"", 5L));
    lenient().when(caldavPushService.rewriteAgendaEvent(anyLong(), anyLong())).thenReturn(null);

    service.verify(USER);

    ArgumentCaptor<String> listed = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).listResourceEtags(any(), listed.capture(), anyString(), anyString());
    assertEquals(MOVED_TO, listed.getValue(), "the new collection, not the one just emptied");
  }

  @Test
  public void aRelocationThatCouldNotFinishLeavesTheChangeUnstamped() {
    // A copy the server would not let go of is work this change still owes.
    // Stamping over it would tell every later pass there is nothing to do and
    // strand that copy for good — which is exactly the bug EXO-89761 exists to
    // remove, reintroduced by the mechanism meant to fix it.
    givenTheServerSettingsChanged();
    when(caldavMirrorRelocationService.relocate(anyLong(), any(), any()))
                                                          .thenReturn(new MirrorRelocation(MIRROR, 3, 1, 0, 0));
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                    .thenReturn(new CalendarObject(HREF, "\"etag-1\"", ICS));

    service.verify(USER);

    verify(caldavSyncStorage, never()).savePair(any());
  }

  @Test
  public void aDestinationTheUserHasNotChosenYetLeavesTheRoundToTheNextPass() {
    // Nothing can be applied to copies that have nowhere to be. The round is
    // not run — comparing contents against a render is wasted while eXo does not
    // know which collection they belong in — and the stamp stays where it was,
    // so the pass after the user chooses does the work.
    givenTheServerSettingsChanged();
    when(caldavMirrorRelocationService.relocate(anyLong(), any(), any())).thenReturn(MirrorRelocation.deferred());
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));

    service.verify(USER);

    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  @Test
  public void aPassOwingNothingDoesNotAskForARelocationAtAll() {
    // The relocation is bounded to the rounds a settings change already pays
    // for. Asking on every sweep would put a calendar-home listing and a
    // destination resolution on the quiet path this add-on spent EXO-89756
    // making quiet.
    givenServerHolds(Map.of(HREF, "\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", 5L));

    service.verify(USER);

    verify(caldavMirrorRelocationService, never()).relocate(anyLong(), any(), any());
  }

  @Test
  public void forgettingAnAccountAlsoForgetsWhatItsRelocationReported() {
    // The third map of "what is going wrong right now" for this user. Forgetting
    // two of the three would leave a refused removal silent after the very
    // restart meant to say it again.
    service.forgetRepairs(USER);

    verify(caldavMirrorRelocationService).forget(USER);
  }

}

