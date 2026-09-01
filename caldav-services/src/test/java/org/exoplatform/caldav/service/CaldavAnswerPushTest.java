/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.caldav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.RemoteEvent;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.PutResult;
import org.exoplatform.caldav.ics.IcsMerger;
import org.exoplatform.caldav.ics.IcsWriter;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * The outward half of answering a meeting.
 *
 * <p>
 * The load-bearing test is {@link #anAnswerGivenInExoReachesTheCopy()}, and it
 * pins a defect that was reproduced end to end: a user declined in their
 * calendar client, changed their mind and clicked Accept in the notification
 * mail, and five verification passes later an ordinary rename of the event in
 * the client put the declined answer back — because nothing had ever carried
 * the accepted one out to the copy, and the copy is what adoption reads.
 *
 * <p>
 * The merger is real here rather than mocked. A mocked merger would let the
 * whole chain pass while producing an object whose PARTSTAT never moved, which
 * is exactly the failure being pinned.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavAnswerPushTest {

  private static final long        USER    = 42L;

  private static final long        SERVER  = 7L;

  private static final long        EVENT   = 964L;

  private static final String      MIRROR  = "/dav/cal/alice@stalwart.local/exo-meetings";

  private static final String      HREF    = MIRROR + "/evt-1.ics";

  /** What the copy names the account's owner by: their CalDAV account address. */
  private static final String      ACCOUNT = "alice@stalwart.local";

  /** What their eXo profile says, which on the rig is a different mailbox. */
  private static final String      PROFILE = "bob@stalwart.local";

  /**
   * Another attendee of the same meeting: the one whose answer the fan-out of
   * EXO-89868 has to write onto <b>this</b> account's copy.
   */
  private static final long        CAROL   = 77L;

  /**
   * How that other attendee is named on a copy written for somebody else —
   * their eXo profile address, because the mapper spells the account address
   * on one line only, the line of the person the copy is written for.
   */
  private static final String      CAROL_ADDRESS = "carol@stalwart.local";

  @Mock
  private CalDavClient             calDavClient;

  @Mock
  private CaldavConnectorStorage   caldavConnectorStorage;

  @Mock
  private CaldavSyncStorage        caldavSyncStorage;

  @Mock
  private IcsWriter                icsWriter;

  @Spy
  private IcsMerger                icsMerger = new IcsMerger();

  @Mock
  private AgendaEventService       agendaEventService;

  @Mock
  private AgendaEventIcsMapper     agendaEventIcsMapper;

  @Mock
  private AgendaRemoteEventService agendaRemoteEventService;

  @Mock
  private AgendaCalendarService    agendaCalendarService;

  @Mock
  private CalDavEndpoint           endpoint;

  @InjectMocks
  private CaldavPushService        service;

  /**
   * A connected account whose mirror already holds a copy of the meeting.
   */
  @BeforeEach
  public void connectAnAccountHoldingTheCopy() {
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, ACCOUNT)).thenReturn(endpoint);
    lenient().when(agendaEventIcsMapper.addressOf(USER)).thenReturn(PROFILE);
    lenient().when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
    lenient().when(caldavSyncStorage.getObjectByUid(1L, "evt-1")).thenReturn(mapped());
    lenient().when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  /**
   * The regression this whole change exists for: an answer recorded in eXo is
   * written onto the copy, so the copy stops carrying an answer the user has
   * already changed — and stops being a stale answer waiting to be adopted.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void anAnswerGivenInExoReachesTheCopy() throws Exception {
    givenTheMeetingIsCopied();
    givenTheCopySays("DECLINED");
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                          .thenReturn(new PutResult(204,
                                                                                                                                    "\"etag-2\"",
                                                                                                                                    null));

    assertTrue(service.pushAnswer(USER, EVENT, "ACCEPTED"));

    ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).updateObject(eq(endpoint),
                                      eq(HREF),
                                      written.capture(),
                                      eq("\"etag-1\""),
                                      eq(ACCOUNT),
                                      eq("secret"));
    assertTrue(written.getValue().contains("PARTSTAT=ACCEPTED"), written.getValue());
    assertFalse(written.getValue().contains("PARTSTAT=DECLINED"), written.getValue());
    // Everything else the object carried is still there: an answer is not
    // consent to re-serialise a meeting another client may have added to.
    assertTrue(written.getValue().contains("SUMMARY:invit5"), written.getValue());
    assertTrue(written.getValue().contains("mailto:root@stalwart.local"), written.getValue());
  }

  /**
   * The live defect this had to be fixed for, and the reason the addresses are
   * offered as a set.
   *
   * <p>
   * A copy names the account's own owner by the address their CalDAV account
   * answers to — that is what lets a client recognise the meeting as an
   * invitation to itself — while their eXo profile carries another mailbox
   * entirely. On the rig they were {@code alice@stalwart.local} and
   * {@code bob@stalwart.local} for one user. Asking the profile alone matched
   * no ATTENDEE line, so the merger changed nothing, so nothing was written,
   * and the whole propagation reported success by saying nothing at all.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void theAnswerReachesACopyThatNamesTheUserByTheirAccountAddress() throws Exception {
    givenTheMeetingIsCopied();
    givenTheCopySays("DECLINED");
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                          .thenReturn(new PutResult(204,
                                                                                                                                    "\"etag-2\"",
                                                                                                                                    null));

    assertTrue(service.pushAnswer(USER, EVENT, "ACCEPTED"));

    ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).updateObject(any(), anyString(), written.capture(), anyString(), anyString(), anyString());
    assertTrue(written.getValue().contains("PARTSTAT=ACCEPTED"), written.getValue());
    assertTrue(written.getValue().contains(ACCOUNT), written.getValue());
  }

  /**
   * And a copy written before that rule existed, which names them by their
   * profile address instead, is still answered. Both spellings are offered and
   * the object decides — a deployment holds copies written under either.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void theAnswerAlsoReachesACopyWrittenUnderTheOlderNamingRule() throws Exception {
    givenTheMeetingIsCopied();
    when(calDavClient.fetchObject(eq(endpoint), eq(HREF), eq(ACCOUNT), eq("secret")))
                                                                                     .thenReturn(new CalendarObject(HREF,
                                                                                                                    "\"etag-1\"",
                                                                                                                    copy("DECLINED").replace(ACCOUNT,
                                                                                                                                             PROFILE)));
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                          .thenReturn(new PutResult(204,
                                                                                                                                    "\"etag-2\"",
                                                                                                                                    null));

    assertTrue(service.pushAnswer(USER, EVENT, "ACCEPTED"));

    ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).updateObject(any(), anyString(), written.capture(), anyString(), anyString(), anyString());
    assertTrue(written.getValue().contains("PARTSTAT=ACCEPTED"), written.getValue());
  }

  /**
   * A copy that names neither is left alone rather than half-rewritten — and
   * the caller, which is the one that can log it, is told the difference
   * between "nobody to rewrite" and "already correct".
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void aCopyNamingTheUserByNeitherAddressIsLeftAlone() throws Exception {
    givenTheMeetingIsCopied();
    when(calDavClient.fetchObject(eq(endpoint), eq(HREF), eq(ACCOUNT), eq("secret")))
                                                                                     .thenReturn(new CalendarObject(HREF,
                                                                                                                    "\"etag-1\"",
                                                                                                                    copy("DECLINED").replace(ACCOUNT,
                                                                                                                                             "carol@stalwart.local")));

    assertFalse(service.pushAnswer(USER, EVENT, "ACCEPTED"));

    verify(calDavClient, never()).updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString());
    verify(caldavSyncStorage, never()).saveObject(any());
  }

  /**
   * The recorded hash follows what was just written. Left as it was, the next
   * verification pass would read the ETag it does not recognise, judge the copy
   * altered by a client, and repair it back to the answer this call replaced —
   * the same silent revert, taking one pass longer.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void theCopyIsNotLeftLookingLikeAClientRewroteIt() throws Exception {
    givenTheMeetingIsCopied();
    givenTheCopySays("NEEDS-ACTION");
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                          .thenReturn(new PutResult(204,
                                                                                                                                    "\"etag-2\"",
                                                                                                                                    null));

    service.pushAnswer(USER, EVENT, "DECLINED");

    // The version the write produced, and nothing else recorded against the
    // copy. EXO-89716 removed the digest this used to assert: the guarantee it
    // bought — that the next verification pass does not judge eXo's own answer
    // a client's doing and repair it away — now comes from the pass comparing
    // the copy against what eXo would render, which already carries the answer
    // because agenda recorded it before this method was called. That is a
    // statement about two services agreeing, so it is pinned where both are
    // real: NormalisingServerMirrorTest
    // #anAnswerExoWritesOntoTheCopyIsNotThenUndoneByTheVerificationPass.
    ArgumentCaptor<ObjectSync> saved = ArgumentCaptor.forClass(ObjectSync.class);
    verify(caldavSyncStorage).saveObject(saved.capture());
    assertEquals("\"etag-2\"", saved.getValue().getEtag());
  }

  /**
   * A copy that already says this is left alone. Writing anyway would move the
   * ETag for nothing — and would push straight back at the server an answer
   * that had just been adopted from it, which is how two convergent halves turn
   * into a loop.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void aCopyThatAlreadySaysThisIsNotWrittenAgain() throws Exception {
    givenTheMeetingIsCopied();
    givenTheCopySays("ACCEPTED");

    assertFalse(service.pushAnswer(USER, EVENT, "ACCEPTED"));

    verify(calDavClient, never()).updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString());
    verify(caldavSyncStorage, never()).saveObject(any());
  }

  /**
   * An answer to one instance of a series reaches the object the series is
   * written under, not an object of its own: agenda gives an override its own
   * event id, while RFC 4791 puts the whole series in one file.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void anAnswerToAnOverrideFindsTheSeriesCopy() throws Exception {
    Event override = new Event();
    override.setId(965L);
    override.setParentId(EVENT);
    when(agendaEventService.getEventById(eq(965L), isNull(), eq(USER))).thenReturn(override);
    when(agendaRemoteEventService.findRemoteEvent(EVENT, USER)).thenReturn(remoteEvent());
    givenTheCopySays("NEEDS-ACTION");
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                          .thenReturn(new PutResult(204,
                                                                                                                                    "\"etag-2\"",
                                                                                                                                    null));

    assertTrue(service.pushAnswer(USER, 965L, "TENTATIVE"));

    verify(agendaRemoteEventService).findRemoteEvent(EVENT, USER);
  }

  /**
   * A user with no CalDAV account has no copy to keep in step, and that is an
   * ordinary state rather than a failure. Nothing is read, nothing is written,
   * and nothing is said about it.
   */
  @Test
  public void aUserWithNoAccountIsNotAFailure() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    assertFalse(service.pushAnswer(USER, EVENT, "ACCEPTED"));

    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
  }

  /**
   * A meeting this user never had copied out has no ATTENDEE line anywhere to
   * rewrite. Answering it is not a reason to start copying it: the push is
   * driven by the connector's own rules, not by whoever happens to answer.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void aMeetingWithNoCopyIsNotCopiedNow() throws Exception {
    Event event = new Event();
    event.setId(EVENT);
    when(agendaEventService.getEventById(eq(EVENT), isNull(), eq(USER))).thenReturn(event);
    when(agendaRemoteEventService.findRemoteEvent(EVENT, USER)).thenReturn(null);

    assertFalse(service.pushAnswer(USER, EVENT, "ACCEPTED"));

    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
    verify(calDavClient, never()).putObject(any(), anyString(), anyString(), anyString(), anyString());
  }

  /**
   * A mapping with no ETag describes an object eXo never managed to write.
   * There is nothing to condition a write on, and the client refuses an
   * unconditional one on purpose — so this stops rather than fetching an object
   * it could not write back.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void aCopyWithNoTagRecordedIsLeftToTheSweep() throws Exception {
    givenTheMeetingIsCopied();
    ObjectSync interrupted = mapped();
    interrupted.setEtag(" ");
    when(caldavSyncStorage.getObjectByUid(1L, "evt-1")).thenReturn(interrupted);

    assertFalse(service.pushAnswer(USER, EVENT, "ACCEPTED"));

    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
  }

  /**
   * A copy someone edited between our read and our write is refused rather than
   * overwritten. This has been told an answer and has not read the object, so
   * it is in no position to decide anything about the rest of it; the
   * verification pass, which does read before it writes, is.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void aConcurrentEditIsRefusedRatherThanOverwritten() throws Exception {
    givenTheMeetingIsCopied();
    givenTheCopySays("DECLINED");
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                          .thenReturn(new PutResult(412,
                                                                                                                                    null,
                                                                                                                                    null));

    CaldavPushException failure = org.junit.jupiter.api.Assertions.assertThrows(CaldavPushException.class,
                                                                               () -> service.pushAnswer(USER,
                                                                                                        EVENT,
                                                                                                        "ACCEPTED"));

    assertEquals(CaldavPushService.CONFLICT, failure.getCode());
    verify(caldavSyncStorage, never()).saveObject(any());
  }

  /**
   * An answer agenda holds under a value the RFC does not define is written as
   * NEEDS-ACTION rather than as itself: a strict reader rejects the whole
   * object over one invalid token, and a rejected object is a meeting that
   * vanishes from the user's phone.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void anAnswerTheRfcDoesNotDefineIsWrittenAsNeedsAction() throws Exception {
    givenTheMeetingIsCopied();
    givenTheCopySays("ACCEPTED");
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                          .thenReturn(new PutResult(204,
                                                                                                                                    "\"etag-2\"",
                                                                                                                                    null));

    service.pushAnswer(USER, EVENT, "MAYBE_LATER");

    ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).updateObject(any(), anyString(), written.capture(), anyString(), anyString(), anyString());
    assertTrue(written.getValue().contains("PARTSTAT=NEEDS-ACTION"), written.getValue());
  }

  /**
   * The fan-out's whole point, on a real merger: somebody <b>else's</b> answer
   * is written onto this account's copy.
   *
   * <p>
   * Before EXO-89868 nothing did this at any level. root created a meeting,
   * Benjamin accepted it in macOS Calendar, eXo recorded the acceptance — and
   * root's copy went on saying NEEDS-ACTION for ever, because the only write
   * an answer ever caused went to the answerer's own account.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void anotherAttendeesAnswerIsWrittenOntoThisAccountsCopy() throws Exception {
    givenTheCopyAlsoNames(CAROL_ADDRESS, "NEEDS-ACTION");
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                          .thenReturn(new PutResult(204,
                                                                                                                                    "\"etag-2\"",
                                                                                                                                    null));

    assertEquals(CaldavPushService.AnswerOutcome.WRITTEN,
                 service.pushAnswerOnto(USER, mapped(), List.of(CAROL_ADDRESS), "ACCEPTED"));

    ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).updateObject(eq(endpoint),
                                      eq(HREF),
                                      written.capture(),
                                      eq("\"etag-1\""),
                                      eq(ACCOUNT),
                                      eq("secret"));
    assertTrue(attendeeLine(written.getValue(), CAROL_ADDRESS).contains("PARTSTAT=ACCEPTED"),
               written.getValue());
  }

  /**
   * <b>The anti-clobber pin, and the reason this is a targeted line rewrite
   * rather than a rewrite of the event.</b>
   *
   * <p>
   * The tempting implementation is to treat an answer like an edit and issue
   * {@code propagateUpdate}'s full rewrite per holder. It would have destroyed
   * answers. {@code IcsMerger.merge} replaces the master VEVENT wholesale, and
   * on a server that records its own owner's answer <b>without moving the
   * ETag</b> — BlueMind, measured on this rig — the conditional write succeeds
   * and takes an answer nothing has read yet down with it.
   *
   * <p>
   * A targeted rewrite cannot do that, and this pins the property rather than
   * the intention: the other attendee's <b>answer</b> comes out of the write
   * exactly as it went in. Read off that attendee's own line, not looked for
   * anywhere in the document — a test asserting only that
   * {@code PARTSTAT=ACCEPTED} appears somewhere would pass against a rewrite
   * that had moved the answer onto the wrong line.
   *
   * <p>
   * <b>The line itself is not byte-identical, and saying so is the point.</b>
   * Measured here rather than assumed: a targeted rewrite still parses and
   * re-serialises the whole object through ical4j, which normalises what it
   * emits — the served {@code CN="benjamin mestrallet"} comes back as
   * {@code CN=benjamin mestrallet}, because the quotes were not required. That
   * is a spelling of the same statement and it is what the merge path has
   * always done to every line it touches. What must not move, and does not, is
   * the statement: the address named and the answer against it. Anything
   * stronger asserted here would be pinning ical4j's serialiser rather than
   * this add-on's behaviour, and would break on the next library bump for no
   * user-visible reason.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void writingOneAttendeesAnswerLeavesTheOtherAttendeesAnswerUntouched() throws Exception {
    String served = copyNaming(CAROL_ADDRESS, "NEEDS-ACTION", "ACCEPTED");
    when(calDavClient.fetchObject(eq(endpoint), eq(HREF), eq(ACCOUNT), eq("secret")))
                                                                                     .thenReturn(new CalendarObject(HREF,
                                                                                                                    "\"etag-1\"",
                                                                                                                    served));
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                          .thenReturn(new PutResult(204,
                                                                                                                                    "\"etag-2\"",
                                                                                                                                    null));

    service.pushAnswerOnto(USER, mapped(), List.of(CAROL_ADDRESS), "DECLINED");

    ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).updateObject(any(), anyString(), written.capture(), anyString(), anyString(), anyString());
    assertEquals("PARTSTAT=ACCEPTED",
                 partStatOn(served, ACCOUNT),
                 "the fixture must actually carry the holder's own answer, or this pins nothing");
    assertEquals(partStatOn(served, ACCOUNT),
                 partStatOn(written.getValue(), ACCOUNT),
                 "the holder's own answer must come out of somebody else's answer untouched");
    assertEquals("PARTSTAT=DECLINED", partStatOn(written.getValue(), CAROL_ADDRESS), written.getValue());
  }

  /**
   * The copy already carries the answer, so nothing is written — and the
   * caller is told it is settled rather than failed.
   *
   * <p>
   * The guard the task names by name: without it one answer rewrites every
   * copy that already agrees, every time anybody answers, moving N ETags for
   * nothing and handing the verification pass N divergences of its own making.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void aCopyThatAlreadyCarriesTheAnswerIsNotWrittenAndIsSettled() throws Exception {
    givenTheCopyAlsoNames(CAROL_ADDRESS, "ACCEPTED");

    assertEquals(CaldavPushService.AnswerOutcome.ALREADY_SAID,
                 service.pushAnswerOnto(USER, mapped(), List.of(CAROL_ADDRESS), "ACCEPTED"));

    verify(calDavClient, never()).updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString());
    verify(caldavSyncStorage, never()).saveObject(any());
  }

  /**
   * A copy that does not name the answerer at all is told apart from one that
   * already agrees, because the two owe eXo opposite things: this one still
   * needs a full rewrite to acquire the missing line, and the obligation the
   * caller recorded is what brings one.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void aCopyThatDoesNotNameTheAnswererIsNotSettled() throws Exception {
    givenTheCopySays("ACCEPTED");

    assertEquals(CaldavPushService.AnswerOutcome.NOT_NAMED,
                 service.pushAnswerOnto(USER, mapped(), List.of(CAROL_ADDRESS), "ACCEPTED"));

    assertFalse(CaldavPushService.AnswerOutcome.NOT_NAMED.settles());
    verify(calDavClient, never()).updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  /**
   * The write is conditional on the <b>holder's</b> recorded version, and a
   * server that has moved on refuses it rather than overwriting whatever
   * moved it. The same discipline every other ordinary write here follows.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void aConcurrentChangeToTheHoldersCopyIsRefusedRatherThanOverwritten() throws Exception {
    givenTheCopyAlsoNames(CAROL_ADDRESS, "NEEDS-ACTION");
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                          .thenReturn(new PutResult(412,
                                                                                                                                    null,
                                                                                                                                    null));

    CaldavPushException failure =
                                org.junit.jupiter.api.Assertions.assertThrows(CaldavPushException.class,
                                                                             () -> service.pushAnswerOnto(USER,
                                                                                                          mapped(),
                                                                                                          List.of(CAROL_ADDRESS),
                                                                                                          "ACCEPTED"));

    assertEquals(CaldavPushService.CONFLICT, failure.getCode());
    verify(caldavSyncStorage, never()).saveObject(any());
  }

  /**
   * A holder who has connected no account is refused with the code that says
   * so, not with a silent false — the caller prints it without the word
   * failure and leaves the copy owed, so the day they connect the sweep
   * settles it.
   *
   * @throws Exception never, agenda is mocked
   */
  @Test
  public void aHolderWithNoConnectedAccountIsRefusedAsAKnownState() throws Exception {
    when(caldavConnectorStorage.getCaldavSetting(CAROL)).thenReturn(null);

    CaldavPushException failure =
                                org.junit.jupiter.api.Assertions.assertThrows(CaldavPushException.class,
                                                                             () -> service.pushAnswerOnto(CAROL,
                                                                                                          mapped(),
                                                                                                          List.of(CAROL_ADDRESS),
                                                                                                          "ACCEPTED"));

    assertEquals(CaldavPushService.NOT_CONNECTED, failure.getCode());
    assertTrue(CaldavPushService.isKnownState(failure.getCode()));
  }

  /**
   * The answerer need not have connected an account for the other attendees'
   * copies to learn their answer, so the addresses that name them are
   * available without one.
   *
   * <p>
   * The profile address alone is the right answer here rather than a
   * degraded one: a copy written for somebody else spells everybody but its
   * own owner by their eXo profile address, which is exactly the line this
   * has to find.
   */
  @Test
  public void theAnswererIsNameableWithoutAConnectedAccount() {
    when(caldavConnectorStorage.getCaldavSetting(CAROL)).thenReturn(null);
    when(agendaEventIcsMapper.addressOf(CAROL)).thenReturn(CAROL_ADDRESS);

    assertEquals(List.of(CAROL_ADDRESS), service.addressesNaming(CAROL));
  }

  /**
   * And an account that <i>is</i> connected contributes its own address as
   * well, most specific first — a copy in a deployment may have been written
   * under either spelling.
   */
  @Test
  public void aConnectedAnswererIsNameableByBothSpellings() {
    assertEquals(List.of(ACCOUNT, PROFILE), service.addressesNaming(USER));
  }

  /**
   * The object the server holds, naming this account's owner with one answer
   * and one other attendee with another.
   *
   * @param address the other attendee's address
   * @param partStat the answer the copy currently carries for them
   */
  private void givenTheCopyAlsoNames(String address, String partStat) {
    when(calDavClient.fetchObject(eq(endpoint), eq(HREF), eq(ACCOUNT), eq("secret")))
                                                                                     .thenReturn(new CalendarObject(HREF,
                                                                                                                    "\"etag-1\"",
                                                                                                                    copyNaming(address,
                                                                                                                               partStat,
                                                                                                                               "ACCEPTED")));
  }

  /**
   * A calendar object naming two attendees, as a server serves it.
   *
   * @param address the second attendee's address
   * @param theirPartStat the answer the copy carries for them
   * @param ownersPartStat the answer the copy carries for the account's owner
   * @return the object
   */
  private String copyNaming(String address, String theirPartStat, String ownersPartStat) {
    return String.join("\r\n",
                       "BEGIN:VCALENDAR",
                       "VERSION:2.0",
                       "PRODID:-//eXo//caldav//EN",
                       "BEGIN:VEVENT",
                       "UID:evt-1",
                       "DTSTAMP:20260826T150000Z",
                       "DTSTART:20260908T090000Z",
                       "DTEND:20260908T100000Z",
                       "SUMMARY:invit5",
                       "ORGANIZER;CN=Root Root:mailto:root@stalwart.local",
                       "ATTENDEE;CN=\"benjamin mestrallet\";PARTSTAT=" + ownersPartStat
                           + ";SCHEDULE-AGENT=CLIENT:mailto:" + ACCOUNT,
                       "ATTENDEE;CN=Carol;PARTSTAT=" + theirPartStat + ";SCHEDULE-AGENT=CLIENT:mailto:" + address,
                       "END:VEVENT",
                       "END:VCALENDAR",
                       "");
  }

  /**
   * The ATTENDEE line naming one address, unfolded, so that two documents can
   * be compared line for line whatever width either was serialised at.
   *
   * @param document a calendar object
   * @param address the address whose line is wanted
   * @return the line, without its line break
   */
  private String attendeeLine(String document, String address) {
    String unfolded = document.replace("\r\n ", "").replace("\n ", "");
    for (String line : unfolded.split("\r?\n")) {
      if (line.startsWith("ATTENDEE") && line.contains(address)) {
        return line;
      }
    }
    return "";
  }

  /**
   * The PARTSTAT parameter carried by the ATTENDEE line naming one address.
   *
   * <p>
   * Read off <b>that</b> line rather than searched for in the document,
   * because the whole question the anti-clobber pin asks is whose answer is
   * whose. Answers a marker rather than null when the line or the parameter is
   * absent, so a comparison against it fails loudly instead of comparing two
   * nothings and passing.
   *
   * @param document a calendar object
   * @param address the attendee whose answer is wanted
   * @return the {@code PARTSTAT=...} segment, or a marker naming what was
   *         missing
   */
  private String partStatOn(String document, String address) {
    String line = attendeeLine(document, address);
    if (line.isEmpty()) {
      return "(no line naming " + address + ")";
    }
    for (String parameter : line.split(";")) {
      if (parameter.startsWith("PARTSTAT=")) {
        // The value follows the last parameter after a colon, and the merger's
        // remove-then-add leaves PARTSTAT last on the line it rewrote — so the
        // address rides along on that segment and has to be cut off.
        return StringUtils.substringBefore(parameter, ":");
      }
    }
    return "(no PARTSTAT on the line naming " + address + ")";
  }

  /**
   * The meeting has a copy, under the identifier agenda recorded for it.
   *
   * @throws Exception never, agenda is mocked
   */
  private void givenTheMeetingIsCopied() throws Exception {
    Event event = new Event();
    event.setId(EVENT);
    when(agendaEventService.getEventById(eq(EVENT), isNull(), eq(USER))).thenReturn(event);
    when(agendaRemoteEventService.findRemoteEvent(EVENT, USER)).thenReturn(remoteEvent());
  }

  /**
   * The object the server holds, naming this user with one answer.
   *
   * @param partStat the answer the copy currently carries
   */
  private void givenTheCopySays(String partStat) {
    when(calDavClient.fetchObject(eq(endpoint), eq(HREF), eq(ACCOUNT), eq("secret")))
                                                                                     .thenReturn(new CalendarObject(HREF,
                                                                                                                    "\"etag-1\"",
                                                                                                                    copy(partStat)));
  }

  /**
   * @param partStat the answer the copy carries
   * @return a calendar object as a server serves it
   */
  private String copy(String partStat) {
    return String.join("\r\n",
                       "BEGIN:VCALENDAR",
                       "VERSION:2.0",
                       "PRODID:-//eXo//caldav//EN",
                       "BEGIN:VEVENT",
                       "UID:evt-1",
                       "DTSTAMP:20260826T150000Z",
                       "DTSTART:20260908T090000Z",
                       "DTEND:20260908T100000Z",
                       "SUMMARY:invit5",
                       "ORGANIZER;CN=Root Root:mailto:root@stalwart.local",
                       "ATTENDEE;CN=\"benjamin mestrallet\";PARTSTAT=" + partStat
                           + ";SCHEDULE-AGENT=CLIENT:mailto:" + ACCOUNT,
                       "END:VEVENT",
                       "END:VCALENDAR",
                       "");
  }

  /**
   * @return a connected account
   */
  private CaldavUserSetting settings() {
    CaldavUserSetting setting = new CaldavUserSetting();
    // The rig's account username is itself a mail address, which is exactly why
    // a copy can name its owner by it.
    setting.setUsername(ACCOUNT);
    setting.setPassword("secret");
    setting.setServerId(SERVER);
    return setting;
  }

  /**
   * @return the mirror pair, already bound
   */
  private CalendarSync pair() {
    CalendarSync pair = new CalendarSync();
    pair.setId(1L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setRemoteHref(MIRROR);
    pair.setOrigin(SyncOrigin.MIRROR);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  /**
   * @return the mapping of the meeting's copy
   */
  private ObjectSync mapped() {
    ObjectSync mapping = new ObjectSync();
    mapping.setId(5L);
    mapping.setCalendarSyncId(1L);
    mapping.setIcsUid("evt-1");
    mapping.setRemoteHref(HREF);
    mapping.setEtag("\"etag-1\"");
    return mapping;
  }

  /**
   * @return the identifier agenda recorded for the copy
   */
  private RemoteEvent remoteEvent() {
    RemoteEvent remoteEvent = new RemoteEvent();
    remoteEvent.setEventId(EVENT);
    remoteEvent.setIdentityId(USER);
    remoteEvent.setRemoteId("evt-1");
    return remoteEvent;
  }

}
