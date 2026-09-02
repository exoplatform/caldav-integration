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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.RemoteEvent;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.MkCalendarResult;
import org.exoplatform.caldav.client.PutResult;
import org.exoplatform.caldav.client.ServerCapabilities;
import org.exoplatform.caldav.client.SyncCollectionResult;
import org.exoplatform.caldav.ics.IcsEquivalence;
import org.exoplatform.caldav.ics.IcsMerger;
import org.exoplatform.caldav.ics.IcsWriter;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.IcsPerson;
import org.exoplatform.caldav.model.MirrorVerification;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * The push and the verification pass, run against a server that does not keep
 * the bytes it is given.
 *
 * <p>
 * This is EXO-89716 in a test. On BlueMind every copy eXo pushed was judged
 * altered on every pass, repaired, judged altered again, and after
 * {@code maxRepairs} rounds abandoned — after which eXo could never write to it
 * again and the meeting went quietly stale in the user's calendar. Nobody was
 * misbehaving: BlueMind parses an object into its own model and re-serialises
 * it, and eXo was comparing what the server stored against a digest of what eXo
 * had <i>sent</i>. The same code against byte-stable Stalwart was silent.
 *
 * <p>
 * Recording a digest of what the server was seen to <i>store</i> instead — read
 * back right after the write — ended the abandonment on a live account and
 * never converged: all 19 copies were judged altered and rewritten every five
 * minutes, for ever, because BlueMind was still settling the object when the
 * read-back arrived and finished afterwards without moving the ETag. So this
 * fake server does the same: it settles <b>after</b> the write returns, and
 * silently. Any test whose server hands back its final state on the first read
 * would pass against the code that failed in production.
 *
 * <p>
 * The two services are the real ones here, wired to each other, along with the
 * real {@link org.exoplatform.caldav.ics.IcsEquivalence} and the real
 * {@link IcsWriter} — because the defect lived in none of them alone, and a
 * mock of any of the three would have agreed with itself. What is faked is the
 * server, three ways: one that re-serialises and settles late, one that keeps
 * the bytes verbatim, and one that re-serialises <i>and</i> adds a property of
 * its own, so that "anything unrecognised counts as different" is a test rather
 * than a sentence.
 */
@ExtendWith(MockitoExtension.class)
public class NormalisingServerMirrorTest {

  private static final long                    USER    = 42L;

  private static final long                    SERVER  = 7L;

  private static final long                    EVENT   = 500L;

  private static final String                  LOGIN   = "john";

  private static final String                  HOME    = "/dav/calendars/john/";

  private static final String                  MIRROR  = "/dav/calendars/john/exo-meetings/";

  private static final String                  HREF    = MIRROR + "evt-1.ics";

  /** The address a copy on this account names its owner by. */
  private static final String                  OWNER   = "john@acme.test";

  /** The zone the fake server restates the meeting's wall clock on. */
  private static final String                  ZONE    = "Europe/Paris";

  /** When the meeting starts, in the zone the fake server restates it in. */
  private static final Instant                 START   = Instant.parse("2026-09-01T09:00:00Z");

  /** When it ends. */
  private static final Instant                 END     = Instant.parse("2026-09-01T10:00:00Z");

  @Mock
  private CaldavConnectorStorage               caldavConnectorStorage;

  @Mock
  private CaldavSyncStorage                    caldavSyncStorage;

  /** The real engine: what eXo writes is what this test compares against. */
  private final IcsWriter                      icsWriter = new IcsWriter();

  /** The real merge, because it decides what a repair actually leaves behind. */
  private final IcsMerger                      icsMerger = new IcsMerger();

  /** The real judge, which is the thing under test. */
  private final IcsEquivalence                 icsEquivalence = new IcsEquivalence();

  @Mock
  private AgendaEventService                   agendaEventService;

  @Mock
  private AgendaEventIcsMapper                 agendaEventIcsMapper;

  @Mock
  private AgendaRemoteEventService             agendaRemoteEventService;

  @Mock
  private AgendaCalendarService                agendaCalendarService;

  /**
   * Mocked, deliberately. EXO-89681 reads the owner's answer off a copy the
   * pass has just called altered; what this rig pins is <b>which</b> copies get
   * called that, not what is then done with the answer on one — that is
   * {@code CaldavAnswerAdoptionServiceTest}'s subject. Answering NOTHING keeps
   * every scenario here on the ordinary repair path it was written for.
   */
  @Mock
  private CaldavAnswerAdoptionService          caldavAnswerAdoptionService;

  /**
   * The registry the pass asks what this server is excused for (EXO-89771) and
   * whether a copy-governing setting has moved since this mirror last applied
   * one (EXO-89759). Unstubbed, which answers null and is the state every
   * deployment starts in — the deployment-wide excusals decide, nothing is to
   * be applied, and every scenario in this rig stays on the ordinary ETag-gated
   * path it was written for.
   */
  @Mock
  private CaldavServerService                  caldavServerService;

  private FakeCalDavServer                     server;

  private CaldavPushService                    push;

  private CaldavMirrorVerificationService      verification;

  /** The mapping rows, as a database would hold them. */
  private final Map<Long, ObjectSync>          rows    = new LinkedHashMap<>();

  /** The mirror binding, the only pair this account has. */
  private CalendarSync                         mirror;

  /**
   * Wires the two real services to a fake normalising server and an in-memory
   * mapping table.
   */
  @BeforeEach
  public void connectAnAccountOnANormalisingServer() {
    server = new FakeCalDavServer(Normalisation.RESERIALISE);
    push = new CaldavPushService();
    verification = new CaldavMirrorVerificationService();
    inject(push);
    inject(verification);
    ReflectionTestUtils.setField(push, "icsWriter", icsWriter);
    ReflectionTestUtils.setField(push, "icsMerger", icsMerger);
    ReflectionTestUtils.setField(push, "agendaEventService", agendaEventService);
    ReflectionTestUtils.setField(push, "agendaEventIcsMapper", agendaEventIcsMapper);
    ReflectionTestUtils.setField(push, "agendaRemoteEventService", agendaRemoteEventService);
    ReflectionTestUtils.setField(push, "agendaCalendarService", agendaCalendarService);
    // EXO-89863 gave both services the same rule to consult before writing a
    // copy or judging one. The real thing, not a mock: this rig builds real
    // events and what it must keep proving is that an ordinary confirmed
    // meeting is written and repaired exactly as it always was.
    ReflectionTestUtils.setField(push, "caldavCopyPolicy", new CaldavCopyPolicy());
    ReflectionTestUtils.setField(verification, "caldavCopyPolicy", new CaldavCopyPolicy());
    ReflectionTestUtils.setField(verification, "agendaEventService", agendaEventService);
    // The attendee reader the repair bound consults for the answer half of a
    // statement (EXO-89863). Mocked and never stubbed: this rig is about which
    // copies a normalising server makes look rewritten, and an unstubbed reader
    // leaves the statement constant, which is the state its abandonment
    // arithmetic was written against. Left null it would cost a caught
    // NullPointerException and a debug line per copy per pass.
    ReflectionTestUtils.setField(verification,
                                 "agendaEventAttendeeService",
                                 org.mockito.Mockito.mock(org.exoplatform.agenda.service.AgendaEventAttendeeService.class));
    ReflectionTestUtils.setField(verification, "caldavPushService", push);
    ReflectionTestUtils.setField(verification, "icsEquivalence", icsEquivalence);
    ReflectionTestUtils.setField(verification, "caldavAnswerAdoptionService", caldavAnswerAdoptionService);
    // EXO-89771 gave the pass a registry to ask what this server is excused for
    // and a place to record what it saw; EXO-89759 asks the same registry
    // whether a copy-governing setting has moved. Both are mocks and neither is
    // stubbed: this class connects a fake server with no registration behind it,
    // so the comparison runs on the deployment-wide fallback the
    // ReflectionTestUtils calls below still set, and no settings round is owed.
    ReflectionTestUtils.setField(verification, "caldavServerService", caldavServerService);
    ReflectionTestUtils.setField(verification,
                                 "caldavServerQuirkService",
                                 org.mockito.Mockito.mock(CaldavServerQuirkService.class));
    lenient().when(caldavAnswerAdoptionService.adoptAnswer(anyLong(), anyLong(), anyString()))
             .thenReturn(CaldavAnswerAdoptionService.Outcome.NOTHING);
    // EXO-89814 gave the pass a second reader — the one that meets the copies in
    // a collection no binding reads. Mocked and never stubbed: this class is
    // about which copies a normalising server makes look rewritten, and its
    // fake server has no collection nothing else reads. Left null it would take
    // every case here down with a NullPointerException, which says nothing at
    // all about normalisation.
    ReflectionTestUtils.setField(verification,
                                 "caldavMirrorAnswerService",
                                 org.mockito.Mockito.mock(CaldavMirrorAnswerService.class));
    ReflectionTestUtils.setField(icsEquivalence, "ignoredProperties", "");
    ReflectionTestUtils.setField(verification, "maxRepairs", 3);
    // EXO-89681 gave the pass an answer-adoption collaborator that did not exist
    // when this test was written. It is stubbed rather than exercised: what is
    // under test here is which copies the pass calls altered on a normalising
    // server, not what it does with an answer found on one.
    ReflectionTestUtils.setField(verification,
                                 "caldavAnswerAdoptionService",
                                 org.mockito.Mockito.mock(CaldavAnswerAdoptionService.class));

    mirror = new CalendarSync();
    mirror.setId(3L);
    mirror.setUserIdentityId(USER);
    mirror.setServerId(SERVER);
    mirror.setRemoteHref(CaldavSyncStorage.canonicalHref(MIRROR));
    mirror.setOrigin(SyncOrigin.MIRROR);
    mirror.setStatus(CalendarSyncStatus.ACTIVE);

    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(mirror));
    lenient().when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(mirror));
    givenAnInMemoryMappingTable();
    givenTheRepairCanRebuildTheEvent();
  }
  @Test
  public void aServerThatReSerialisesWhatItStoresDoesNotMakeEveryCopyLookTamperedWith() {
    // The bug, stated as the live rig stated it: pass after pass of "19
    // checked, 0 missing, 19 altered". One object is enough to reproduce it;
    // the count only multiplied it.
    push.writeInto(USER, mirror, event(), EVENT);
    // What the server holds is not what eXo sent, and that is legitimate.
    assertNotEquals(exoRender(), server.stored(HREF));

    for (int pass = 0; pass < 4; pass++) {
      MirrorVerification result = verification.verify(USER);

      assertEquals(1, result.checked(), "pass " + pass);
      assertEquals(0, result.altered(), "pass " + pass);
      assertEquals(0, result.repaired(), "pass " + pass);
      assertEquals(0, result.abandoned(), "pass " + pass);
    }
  }

  @Test
  public void aServerStillSettlingTheObjectAfterTheWriteChangesNothing() {
    // Why the digest was replaced rather than re-timed. On the live account the
    // recorded digest and ETag moved on every sweep although nothing but our
    // own repairs was writing, which leaves only one shape: the server had not
    // finished with the object when we read it back, and finished without
    // moving the ETag. So this server settles late — the form it holds changes
    // once more, after the first read, into a second re-serialisation that is
    // again the same meeting. No digest taken at any moment is stable, and it
    // does not matter, because none is taken.
    server.settlesLate();
    push.writeInto(USER, mirror, event(), EVENT);
    String first = server.stored(HREF);

    MirrorVerification pass = verification.verify(USER);

    assertNotEquals(first, server.stored(HREF), "the server was supposed to settle into another form");
    assertEquals(0, pass.altered());
    assertEquals(0, verification.verify(USER).altered());
    assertEquals(0, verification.verify(USER).altered());
  }

  @Test
  public void aByteStableServerIsUnaffected() {
    // Stalwart. It was already healthy, and this must not make it anything
    // else: the object it holds is the object eXo sent.
    server = new FakeCalDavServer(Normalisation.NONE);
    inject(push);
    inject(verification);

    ObjectSync mapping = push.writeInto(USER, mirror, event(), EVENT);

    assertEquals(exoRender(), server.stored(HREF));
    assertEquals(0, verification.verify(USER).altered());
    assertEquals(server.etag(HREF), mapping.getEtag());
  }

  @Test
  public void anEditMadeInExoReachesTheCopyOnANormalisingServer() {
    // The ticket's second acceptance criterion. An ordinary update is guarded
    // by If-Match with the version eXo recorded, so a recorded version the
    // server has left behind is an eXo-side edit the user never sees arrive.
    // The verification pass is what keeps that version current, by adopting it
    // the first time it reads a copy and finds it equivalent.
    push.writeInto(USER, mirror, event(), EVENT);
    verification.verify(USER);

    IcsEvent moved = event();
    moved.setStart(START.plusSeconds(6 * 3600));
    moved.setEnd(END.plusSeconds(6 * 3600));
    push.writeInto(USER, mirror, moved, EVENT);

    // 15:00 UTC, which this server restates as 17:00 on Europe/Paris — the very
    // re-anchoring that used to make the copy look rewritten.
    assertTrue(server.stored(HREF).contains("DTSTART;TZID=Europe/Paris:20260901T170000"), server.stored(HREF));
  }

  @Test
  public void aGenuineClientEditIsStillCalledAlteredAndStillRepaired() {
    // The whole point of the pass, and the thing a fix must not buy its silence
    // with. Somebody opened the copy on their phone and renamed the meeting;
    // the mirror is eXo's projection, so eXo writes it back.
    push.writeInto(USER, mirror, event(), EVENT);

    server.editedByAClient(HREF, server.stored(HREF).replace("Sprint review", "Sprint review (moved to the pub)"));
    MirrorVerification result = verification.verify(USER);

    assertEquals(1, result.altered());
    assertEquals(1, result.repaired());
    assertEquals(0, result.abandoned());
    // And the repair converges: what the server now holds says what eXo says,
    // so the next pass has nothing to report. Before EXO-89716 this second pass
    // said "altered" once more, which is how an object reached maxRepairs.
    assertEquals(0, verification.verify(USER).altered());
  }

  @Test
  public void aClientThatMovedTheMeetingIsCaughtEvenWhenItRestatedTheTimeInAnotherZone() {
    // The comparison folds a wall clock on a zone into the instant it denotes,
    // which is what stops a re-serialisation counting as a rewrite. It must not
    // also let a real move through: same form, different meeting.
    push.writeInto(USER, mirror, event(), EVENT);

    server.editedByAClient(HREF, server.stored(HREF).replace("T110000", "T160000"));

    assertEquals(1, verification.verify(USER).altered());
  }

  @Test
  public void anAttendeeTheServerDidNotKeepDoesNotMakeTheCopyLookRewritten() {
    // The reverse of what this test asserted two rounds ago, by the architect's
    // decision. A server that discards attendees it does not know about leaves
    // a copy eXo can never make match: it re-pushes the whole roster, the
    // server drops the same address again, and the pass says the same thing
    // five minutes later. Four passes, because one proves nothing about a loop.
    IcsEvent invited = event();
    invited.setOrganizer(person("boss@acme.test", "The Boss"));
    invited.setAttendees(List.of(person("ann@acme.test", "Ann"), person("bob@acme.test", "Bob")));
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), anyLong())).thenReturn(invited);
    push.writeInto(USER, mirror, invited, EVENT);

    server.editedByAClient(HREF,
                           Arrays.stream(server.stored(HREF).split("\r\n"))
                                 .filter(line -> !line.contains("bob@acme.test"))
                                 .collect(Collectors.joining("\r\n"))
                              + "\r\n");

    for (int pass = 0; pass < 4; pass++) {
      MirrorVerification result = verification.verify(USER);

      assertEquals(0, result.altered(), "pass " + pass);
      assertEquals(0, result.repaired(), "pass " + pass);
    }
  }

  @Test
  public void aClientAddingAnAttendeeIsStillCaughtWhileAnotherIsBeingTolerated() {
    // Both tolerances active on one object, end to end. The server never kept
    // Bob, and a client has now added Mallory. The first is not a rewrite and
    // the second is, and the pass has to separate them on the same copy — which
    // is the whole risk of having two rules that point opposite ways.
    IcsEvent invited = event();
    invited.setOrganizer(person("boss@acme.test", "The Boss"));
    invited.setAttendees(List.of(person("ann@acme.test", "Ann"), person("bob@acme.test", "Bob")));
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), anyLong())).thenReturn(invited);
    push.writeInto(USER, mirror, invited, EVENT);

    // Inside the VEVENT, not appended to the document. Written the lazy way the
    // added line lands after END:VCALENDAR, where it is not an attendee of
    // anything — and the test still went green, on a parse failure rather than
    // on the roster. Checking what the splice actually produced is what caught
    // it.
    server.editedByAClient(HREF,
                           Arrays.stream(server.stored(HREF).split("\r\n"))
                                 .filter(line -> !line.contains("bob@acme.test"))
                                 .collect(Collectors.joining("\r\n"))
                                 .replace("END:VEVENT",
                                          "ATTENDEE;CN=Mallory:mailto:mallory@acme.test\r\nEND:VEVENT")
                              + "\r\n");

    assertEquals(1, verification.verify(USER).altered());
  }

  @Test
  public void aPropertyNobodyRecognisesCountsAsADifference() {
    // Conservative by construction. eXo emits a closed set of properties, and
    // anything else inside the component it owns is a change it cannot vouch
    // for — so it is reported rather than waved through. The cost of being
    // wrong here is one rewrite; the cost of the opposite mistake is a user's
    // edit lost without trace.
    server = new FakeCalDavServer(Normalisation.RESERIALISE_AND_ANNOTATE);
    inject(push);
    inject(verification);
    push.writeInto(USER, mirror, event(), EVENT);

    assertEquals(1, verification.verify(USER).altered());
  }

  @Test
  public void aPropertyTheOperatorHasDeclaredUninterestingIsNotADifference() {
    // The one lever for a server that re-adds its own property on every store,
    // which no amount of rewriting would remove. Narrow on purpose: it can only
    // silence a property eXo does not emit — no setting of it can make a
    // changed summary or a moved start look equal.
    server = new FakeCalDavServer(Normalisation.RESERIALISE_AND_ANNOTATE);
    inject(push);
    inject(verification);
    ReflectionTestUtils.setField(icsEquivalence, "ignoredProperties", "X-FAKEMIND-SEQ");
    push.writeInto(USER, mirror, event(), EVENT);

    assertEquals(0, verification.verify(USER).altered());
  }

  @Test
  public void aCopyAbandonedBeforeTheFixHealsWithoutOneRepair() {
    // The installed base. A row written by the old code carries a version the
    // server has since moved past, and the repair counter that abandoned it
    // lived in memory — so deploying restarts the JVM and forgives the counter.
    // What is left is a stale ETag, and it costs a fetch rather than a rewrite:
    // the copy still says what eXo says, so the version is adopted and nothing
    // is pushed at all.
    ObjectSync legacy = push.writeInto(USER, mirror, event(), EVENT);
    legacy.setEtag("\"v0\"");
    caldavSyncStorage.saveObject(legacy);

    MirrorVerification first = verification.verify(USER);
    MirrorVerification second = verification.verify(USER);

    assertEquals(0, first.altered());
    assertEquals(0, first.repaired());
    assertEquals(0, second.altered());
    assertEquals(0, second.abandoned());
  }

  @Test
  public void anEtagThatMovedOverAnEquivalentCopyIsRecordedRatherThanPaidForForEver() {
    // A server touching its own metadata. The copy still says what eXo says, so
    // the version it now publishes names our copy: recording it stops the next
    // pass paying another fetch to reach the same conclusion, and — the reason
    // it matters — stops the next ordinary update carrying an If-Match the
    // server has already left behind, which it would refuse.
    ObjectSync mapping = push.writeInto(USER, mirror, event(), EVENT);
    verification.verify(USER);
    server.touchedItsOwnMetadata(HREF);

    assertEquals(0, verification.verify(USER).altered());
    assertEquals(server.etag(HREF), rows.get(mapping.getId()).getEtag());
    // Cheap from now on: the listing and the row agree, so nothing is fetched.
    int before = server.fetches();
    verification.verify(USER);
    assertEquals(before, server.fetches());
  }

  @Test
  public void aServerThatSpellsAVersionOneWayInItsHeadersAndAnotherInItsListingStillFallsSilent() {
    // EXO-89809, and the reason it went unnoticed for as long as it did: every
    // symptom of it is an absence. BlueMind answers a write with
    // bmdav_2859517047_0 and publishes "Ym1kYXZfMjY4MjA1MjMzOF8xMjc=" for the
    // neighbouring object in the same collection — two spellings of one
    // entity-tag — and the gate compares the row against the listing. So the
    // row the write left behind never agreed with the listing, every copy was
    // rendered, fetched and compared on every sweep for ever, and not one line
    // was ever logged, because a copy that compares equal says nothing.
    //
    // Four passes, because one proves nothing about a loop, and the assertion
    // is the fetch count rather than a verdict: the verdicts were always 0.
    server.spellsItsVersionsTwoWays();
    push.writeInto(USER, mirror, event(), EVENT);

    verification.verify(USER);
    int afterTheFirstPass = server.fetches();
    assertTrue(afterTheFirstPass > 0, "the first pass had to read the copy to know it was ours");

    for (int pass = 0; pass < 4; pass++) {
      MirrorVerification later = verification.verify(USER);

      assertEquals(0, later.altered(), "pass " + pass);
      assertEquals(0, later.repaired(), "pass " + pass);
      assertEquals(afterTheFirstPass, server.fetches(), "pass " + pass);
    }
  }

  @Test
  public void theVersionRecordedForAnEquivalentCopyIsTheOneTheListingPublishes() {
    // The fix stated as what is written down rather than as what stops
    // happening. The pass gates on the collection listing, so the value the row
    // keeps has to be the listing's own — not the one the fetch answered, which
    // is the same thing on a server whose channels agree and a row that never
    // agrees with anything on a server whose channels do not.
    server.spellsItsVersionsTwoWays();
    ObjectSync mapping = push.writeInto(USER, mirror, event(), EVENT);

    verification.verify(USER);

    assertEquals(server.listed(HREF), rows.get(mapping.getId()).getEtag());
    assertNotEquals(server.etag(HREF),
                    rows.get(mapping.getId()).getEtag(),
                    "this server was supposed to spell the two channels differently");
  }

  @Test
  public void anAnswerReadOffACopyTheServerWillNotTakeBackIsNotReadAgainOnEveryPass() {
    // The same defect on the other recording site. When the answer has been
    // taken in but the repair cannot be written — a server refusing the
    // rewrite — what the pass recorded before attempting it is what stands, and
    // it is what the next pass's gate compares. Recording the fetch's spelling
    // left that gate open for ever: the copy was fetched and its answer read
    // again every five minutes, and adopting an answer already adopted changes
    // nothing, so nothing was ever said about it.
    CaldavAnswerAdoptionService adoption = org.mockito.Mockito.mock(CaldavAnswerAdoptionService.class);
    when(adoption.adoptAnswer(anyLong(), anyLong(), anyString())).thenReturn(CaldavAnswerAdoptionService.Outcome.ADOPTED);
    ReflectionTestUtils.setField(verification, "caldavAnswerAdoptionService", adoption);
    server.spellsItsVersionsTwoWays();
    push.writeInto(USER, mirror, event(), EVENT);
    verification.verify(USER);
    server.editedByAClient(HREF, server.stored(HREF).replace("Sprint review", "Sprint review (accepted)"));
    server.refuseWrites();

    MirrorVerification first = verification.verify(USER);
    int afterTheAnswerWasRead = server.fetches();

    assertEquals(1, first.adopted());
    for (int pass = 0; pass < 3; pass++) {
      verification.verify(USER);

      assertEquals(afterTheAnswerWasRead, server.fetches(), "pass " + pass);
    }
    org.mockito.Mockito.verify(adoption, org.mockito.Mockito.times(1)).adoptAnswer(anyLong(), anyLong(), anyString());
  }

  @Test
  public void anAnswerExoWritesOntoTheCopyIsNotThenUndoneByTheVerificationPass() {
    // EXO-89715's guarantee, re-expressed without a digest — and it is a real
    // question, not a formality, because the two halves take different code
    // paths over the same object. pushAnswer rewrites ONE PARTSTAT surgically
    // through IcsMerger, leaving the rest of the server's own serialisation
    // untouched; the verification pass then compares that document against a
    // FULL re-render by IcsWriter. They have to agree, or eXo repairs the copy
    // back to what it last pushed and the user's answer disappears.
    //
    // The digest used to buy that agreement by remembering the bytes it had
    // just written. This gets it for a better reason: the answer was recorded
    // in agenda before pushAnswer was called, so the re-render already carries
    // it. The baseline agrees because it comes from the same source the answer
    // did.
    IcsEvent invited = event();
    invited.setOrganizer(person("boss@acme.test", "The Boss"));
    invited.setAttendees(List.of(person(OWNER, "John")));
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), anyLong())).thenReturn(invited);
    when(agendaEventIcsMapper.addressOf(USER)).thenReturn(OWNER);
    push.writeInto(USER, mirror, invited, EVENT);
    assertEquals(0, verification.verify(USER).altered());

    // The answer is recorded in eXo first — that is what triggers the push —
    // so what eXo would render from now on carries it.
    IcsEvent answered = event();
    answered.setOrganizer(person("boss@acme.test", "The Boss"));
    IcsPerson accepted = person(OWNER, "John");
    accepted.setResponse("ACCEPTED");
    answered.setAttendees(List.of(accepted));
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), anyLong())).thenReturn(answered);

    assertTrue(push.pushAnswer(USER, EVENT, "ACCEPTED"), "the answer should have reached the copy");
    assertTrue(server.stored(HREF).contains("PARTSTAT=ACCEPTED"), server.stored(HREF));

    // And the pass says nothing about the write eXo has just made.
    MirrorVerification after = verification.verify(USER);

    assertEquals(0, after.altered());
    assertEquals(0, after.repaired());
    assertTrue(server.stored(HREF).contains("PARTSTAT=ACCEPTED"), server.stored(HREF));
  }

  @Test
  public void aServerThatAttachesTheCalendarsOwnerDoesNotMakeEveryCopyLookRewritten() {
    // The first deploy of EXO-89716, reproduced. The comparison worked and the
    // pass still reported "20 checked, 20 altered, 20 re-pushed" on every
    // sweep, because BlueMind attaches the calendar's owner to every copy and
    // puts the line straight back after each repair. Four passes, because one
    // proves nothing about a loop.
    server = new FakeCalDavServer(Normalisation.RESERIALISE_AND_ATTACH_OWNER);
    inject(push);
    inject(verification);
    when(agendaEventIcsMapper.addressOf(USER)).thenReturn(OWNER);
    push.writeInto(USER, mirror, event(), EVENT);
    assertTrue(server.stored(HREF).contains("mailto:" + OWNER), server.stored(HREF));

    for (int pass = 0; pass < 4; pass++) {
      MirrorVerification result = verification.verify(USER);

      assertEquals(1, result.checked(), "pass " + pass);
      assertEquals(0, result.altered(), "pass " + pass);
      assertEquals(0, result.repaired(), "pass " + pass);
    }
  }

  @Test
  public void anOwnerAnsweringOnTheirPhoneIsStillCaughtOnAServerThatAttachesThem() {
    // The fence around the relaxation, where it actually matters. The same
    // server, the same attached line — but this time it carries an answer, so
    // it is the user replying and the pass has to say so. Silence here would
    // mean EXO-89681 never reads an answer off a BlueMind copy again.
    server = new FakeCalDavServer(Normalisation.RESERIALISE_AND_ATTACH_OWNER);
    inject(push);
    inject(verification);
    when(agendaEventIcsMapper.addressOf(USER)).thenReturn(OWNER);
    push.writeInto(USER, mirror, event(), EVENT);
    assertEquals(0, verification.verify(USER).altered());

    server.editedByAClient(HREF, server.stored(HREF).replace("mailto:" + OWNER,
                                                             "mailto:" + OWNER).replace("CN=FRANCOIS",
                                                                                        "CN=FRANCOIS;PARTSTAT=ACCEPTED"));

    assertEquals(1, verification.verify(USER).altered());
  }

  @Test
  public void anOwnerWhoOrganisedTheMeetingDoesNotMakeTheirCopyLoopForEver() {
    // EXO-89768, as the live rig stated it: "OWNER-ATTENDEE;PARTSTAT=ACCEPTED
    // (server 0, eXo 1)" on three copies of a production BlueMind account,
    // every sweep from 09:15, unchanged by three separate fixes, ending in the
    // copies being abandoned — and an abandoned copy is no longer watched, so
    // EXO-89681 stops reading answers off it too.
    //
    // The cause, established by reading the object back over CalDAV rather
    // than reasoned: agenda puts the person who called a meeting on its own
    // attendee list, so eXo wrote them twice — as ORGANIZER and as an ATTENDEE
    // carrying their answer — and the server keeps no attendee line for its
    // organizer. Four passes, because one proves nothing about a loop.
    server = new FakeCalDavServer(Normalisation.RESERIALISE_AND_DROP_THE_ORGANIZERS_ATTENDEE_LINE);
    inject(push);
    inject(verification);
    when(agendaEventIcsMapper.addressOf(USER)).thenReturn(OWNER);
    IcsEvent own = event();
    own.setOrganizer(person(OWNER, "John"));
    IcsPerson accepted = person(OWNER, "John");
    accepted.setResponse("ACCEPTED");
    own.setAttendees(List.of(accepted, person("guest@acme.test", "A Guest")));
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), anyLong())).thenReturn(own);

    push.writeInto(USER, mirror, own, EVENT);

    for (int pass = 0; pass < 4; pass++) {
      MirrorVerification result = verification.verify(USER);

      assertEquals(1, result.checked(), "pass " + pass);
      assertEquals(0, result.altered(), "pass " + pass);
      assertEquals(0, result.repaired(), "pass " + pass);
    }
  }

  @Test
  public void theGuestsAnswerStillReachesACopyOnThatServer() {
    // The fence, and the reason the rule is about the organizer's own line and
    // nothing else. On the same live account fifteen copies whose owner is an
    // ordinary invitee carry the PARTSTAT eXo wrote, kept by the same server —
    // so the outward half of EXO-89715 works there, and a change made for the
    // organizer's line must not touch it.
    server = new FakeCalDavServer(Normalisation.RESERIALISE_AND_DROP_THE_ORGANIZERS_ATTENDEE_LINE);
    inject(push);
    inject(verification);
    when(agendaEventIcsMapper.addressOf(USER)).thenReturn(OWNER);
    IcsEvent invited = event();
    invited.setOrganizer(person("boss@acme.test", "The Boss"));
    invited.setAttendees(List.of(person(OWNER, "John")));
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), anyLong())).thenReturn(invited);
    push.writeInto(USER, mirror, invited, EVENT);
    assertEquals(0, verification.verify(USER).altered());

    IcsEvent answered = event();
    answered.setOrganizer(person("boss@acme.test", "The Boss"));
    IcsPerson accepted = person(OWNER, "John");
    accepted.setResponse("ACCEPTED");
    answered.setAttendees(List.of(accepted));
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), anyLong())).thenReturn(answered);

    assertTrue(push.pushAnswer(USER, EVENT, "ACCEPTED"), "the answer should have reached the copy");
    assertTrue(server.stored(HREF).contains("PARTSTAT=ACCEPTED"), server.stored(HREF));
    assertEquals(0, verification.verify(USER).altered());
  }

  @Test
  public void aServerThatCannotBeReadLeavesItsCopiesAlone() {
    // Unreadable is not the same as rewritten. A re-push here would overwrite
    // whatever is on the user's calendar on the strength of a network error,
    // and the pass has to be able to say nothing at all.
    push.writeInto(USER, mirror, event(), EVENT);
    server.touchedItsOwnMetadata(HREF);
    server.refuseReads();

    MirrorVerification result = verification.verify(USER);

    assertEquals(1, result.checked());
    assertEquals(0, result.altered());
    assertEquals(0, result.repaired());
  }

  @Test
  public void anEventThatCanNoLongerBeReadLeavesItsCopyAlone() {
    // The baseline is regenerated, so there is a case where it cannot be: the
    // event was deleted in eXo, or is no longer visible to this user. Nothing
    // is concluded from an absence, and above all nothing is written.
    push.writeInto(USER, mirror, event(), EVENT);
    server.touchedItsOwnMetadata(HREF);
    givenTheEventCannotBeRead();

    MirrorVerification result = verification.verify(USER);

    assertEquals(1, result.checked());
    assertEquals(0, result.altered());
    assertEquals(0, result.repaired());
  }

  /**
   * Points a service's collaborators at this test's fakes.
   *
   * @param service the push or the verification service
   */
  private void inject(Object service) {
    ReflectionTestUtils.setField(service, "calDavClient", server);
    ReflectionTestUtils.setField(service, "caldavConnectorStorage", caldavConnectorStorage);
    ReflectionTestUtils.setField(service, "caldavSyncStorage", caldavSyncStorage);
  }

  /**
   * Makes the mapping-table mock behave like a table: rows come back as they
   * were last written, which is what a verification pass reads.
   */
  private void givenAnInMemoryMappingTable() {
    AtomicLong sequence = new AtomicLong(1000L);
    lenient().when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> {
      ObjectSync object = invocation.getArgument(0);
      if (object.getId() == null || object.getId() <= 0) {
        object.setId(sequence.incrementAndGet());
      }
      rows.put(object.getId(), object);
      return object;
    });
    lenient().when(caldavSyncStorage.getObjectByUid(anyLong(), anyString())).thenAnswer(invocation -> {
      String uid = invocation.getArgument(1);
      return rows.values().stream().filter(row -> uid.equals(row.getIcsUid())).findFirst().orElse(null);
    });
    lenient().when(caldavSyncStorage.getObjectByEvent(anyLong(), anyLong())).thenAnswer(invocation -> {
      long eventId = invocation.getArgument(1);
      return rows.values()
                 .stream()
                 .filter(row -> row.getLocalEventId() != null && row.getLocalEventId() == eventId)
                 .findFirst()
                 .orElse(null);
    });
    lenient().when(caldavSyncStorage.getObjects(anyLong(), anyInt(), anyInt()))
             .thenAnswer(invocation -> {
               int page = invocation.getArgument(1);
               return new PageImpl<>(page == 0 ? new ArrayList<>(rows.values()) : List.of());
             });
  }

  /**
   * Lets agenda answer for the event, which both the repair and the baseline
   * render need — the pass regenerates what eXo would write on every judgement,
   * so this is not a repair fixture any more, it is the ordinary path.
   */
  private void givenTheRepairCanRebuildTheEvent() {
    Event event = new Event();
    event.setId(EVENT);
    event.setCalendarId(11L);
    try {
      lenient().when(agendaEventService.getEventById(EVENT, null, USER)).thenReturn(event);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
    lenient().when(agendaRemoteEventService.findRemoteEvent(EVENT, USER)).thenReturn(remoteEvent());
    lenient().when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), anyLong())).thenReturn(event());
    lenient().when(agendaCalendarService.getCalendarById(11L)).thenReturn(null);
  }

  /**
   * Makes agenda answer that the event is gone, which is what a deletion in eXo
   * — or a visibility change — looks like from here.
   */
  private void givenTheEventCannotBeRead() {
    try {
      when(agendaEventService.getEventById(EVENT, null, USER)).thenReturn(null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * @return the object eXo's own engine renders for this event
   */
  private String exoRender() {
    return icsWriter.write(event());
  }

  /**
   * @param email the address
   * @param name how the person is displayed
   * @return one calendar user
   */
  private IcsPerson person(String email, String name) {
    IcsPerson person = new IcsPerson();
    person.setEmail(email);
    person.setDisplayName(name);
    return person;
  }

  /**
   * @return the identifier agenda already holds for this event's object
   */
  private RemoteEvent remoteEvent() {
    RemoteEvent remote = new RemoteEvent();
    remote.setEventId(EVENT);
    remote.setIdentityId(USER);
    remote.setRemoteId("evt-1");
    return remote;
  }

  /**
   * @return the event as the writer receives it
   */
  private IcsEvent event() {
    IcsEvent event = new IcsEvent();
    event.setUid("evt-1");
    event.setSummary("Sprint review");
    event.setStart(START);
    event.setEnd(END);
    event.setTimeZoneId("Europe/Paris");
    return event;
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

  /**
   * A CalDAV server that either keeps the bytes it is handed or re-serialises
   * them, and says which of the two it is at construction.
   *
   * <p>
   * Faked rather than mocked on purpose: the defect is a disagreement between
   * what one call wrote and what a later call reads, and a mock has no memory
   * to disagree with itself in.
   */
  private static final class FakeCalDavServer implements CalDavClient {

    /** What this server does to the objects it is handed. */
    private final Normalisation       normalisation;

    /** The objects it holds, by href. */
    private final Map<String, String> objects = new LinkedHashMap<>();

    /** The version it publishes for each of them. */
    private final Map<String, String> etags   = new LinkedHashMap<>();

    /** Bumped on every write, so no two versions are alike. */
    private int                       version;

    /** How many GETs it has served. */
    private int                       fetches;

    /** Whether reads are refused, standing for an unreachable server. */
    private boolean                   readsRefused;

    /** Whether writes are refused, standing for a server that will not take the copy. */
    private boolean                   writesRefused;

    /** How many annotations it has stamped, so no two are alike. */
    private int                       annotations;

    /**
     * Whether this server is still settling the objects it stores, and finishes
     * only after somebody has read them once — without moving the ETag.
     */
    private boolean                   settlesLate;

    /** The hrefs it has not finished settling. */
    private final java.util.Set<String> unsettled = new java.util.LinkedHashSet<>();

    /**
     * Whether this server spells one and the same version two ways: plainly in
     * the {@code ETag} header its writes and its reads answer, and quoted
     * base64 in the {@code DAV:getetag} its collection listing publishes.
     */
    private boolean                   spellsItsVersionsTwoWays;

    /**
     * @param normalisation what it does to what it stores
     */
    private FakeCalDavServer(Normalisation normalisation) {
      this.normalisation = normalisation;
    }

    /**
     * Makes this server finish storing an object only after the first read of
     * it, changing its serialisation once more without moving its version.
     *
     * <p>
     * The live measurement said the recorded digest and ETag moved on every
     * sweep while nothing but eXo's own repairs was writing, and a diagnostic
     * that read each object twice in a row found them identical. Reads are
     * stable at rest, nothing else writes, and the baseline was still wrong —
     * which leaves the server finishing after the read-back and not saying so.
     * The exact mechanism could not be recovered from outside; what this models
     * is the property that follows from it, and the property is what matters:
     * <b>no digest of this object is stable, whenever it is taken</b>.
     */
    private void settlesLate() {
      settlesLate = true;
    }

    /**
     * Makes this server publish its versions in one spelling through its
     * headers and another through its collection listing.
     *
     * <p>
     * BlueMind, measured on a live account (EXO-89809): the {@code ETag} header
     * of a PUT or a GET reads {@code bmdav_2859517047_0}, while the
     * {@code DAV:getetag} the collection listing publishes for a neighbouring
     * object in the very same collection reads
     * {@code "Ym1kYXZfMjY4MjA1MjMzOF8xMjc="} — quoted base64 of the same kind
     * of value. Two spellings of one entity-tag, and the connector's
     * normalisation strips quoting and a weak marker, so it can turn neither
     * into the other.
     *
     * <p>
     * It is not a cosmetic difference: the verification pass gates on the
     * <i>listing</i>, so a row holding the header's spelling never agrees with
     * it. Every copy is then rendered, fetched and compared on every sweep for
     * ever, and says nothing at all — a copy that compares equal reports
     * nothing. Modelled as a property of the server rather than as a scenario,
     * for the reason the misplaced VERSION is: it is what the server does, not
     * what a test does.
     */
    private void spellsItsVersionsTwoWays() {
      spellsItsVersionsTwoWays = true;
    }

    /**
     * How this server spells a version in its collection listing.
     *
     * @param etag the version as its headers spell it
     * @return the version as its {@code DAV:getetag} spells it
     */
    private String asListed(String etag) {
      if (!spellsItsVersionsTwoWays || etag == null) {
        return etag;
      }
      return "\"" + java.util.Base64.getEncoder().encodeToString(etag.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          + "\"";
    }

    /**
     * @param href the object's path
     * @return the iCalendar text this server holds for it
     */
    private String stored(String href) {
      return objects.get(href);
    }

    /**
     * @param href the object's path
     * @return the version it publishes for it
     */
    private String etag(String href) {
      return etags.get(href);
    }

    /**
     * @return how many GETs this server has served
     */
    private int fetches() {
      return fetches;
    }

    /** Makes every read fail, standing for a server that cannot be reached. */
    private void refuseReads() {
      readsRefused = true;
    }

    /**
     * Makes every write fail, standing for a server that will not take the
     * copy back — so that what the pass recorded before attempting the repair
     * is what stands, and can be looked at.
     */
    private void refuseWrites() {
      writesRefused = true;
    }

    /**
     * @param href the object's path
     * @return the version its collection listing publishes for it
     */
    private String listed(String href) {
      return asListed(etags.get(href));
    }

    /**
     * Rewrites an object the way another client would: new bytes, new version,
     * and no normalisation, because this text is the client's own.
     *
     * @param href the object's path
     * @param ics what the client left there
     */
    private void editedByAClient(String href, String ics) {
      objects.put(href, ics);
      etags.put(href, "\"v" + ++version + "\"");
    }

    /**
     * Moves an object's version without touching a byte of it, the way a
     * server does when it changes its own metadata.
     *
     * @param href the object's path
     */
    private void touchedItsOwnMetadata(String href) {
      etags.put(href, "\"v" + ++version + "\"");
    }

    /**
     * What this server actually stores for what it was given.
     *
     * <p>
     * A stand-in for BlueMind's parse-and-re-serialise, and not a cosmetic one:
     * the document is parsed with ical4j and written back with this server's
     * own PRODID, its own DTSTAMP, the redundant {@code TRANSP:OPAQUE} dropped
     * as the RFC allows, the calendar-scale statement gone, and the start and
     * end restated as a wall clock on the event's zone rather than in UTC —
     * carrying the VTIMEZONE that anchors them. The same meeting, and not one
     * line in common. That last transformation is the one that matters: it is
     * the difference a comparison of values would still call a rewrite, and
     * only a comparison of <i>meaning</i> resolves.
     *
     * @param ics the text that was PUT
     * @param second whether this is the second, settling pass over the object
     * @return the text this server keeps
     */
    private String store(String ics, boolean second) {
      if (normalisation == Normalisation.NONE) {
        return ics;
      }
      net.fortuna.ical4j.model.Calendar calendar;
      try {
        calendar = new net.fortuna.ical4j.data.CalendarBuilder().build(new java.io.StringReader(ics));
      } catch (Exception e) {
        throw new IllegalStateException("the fake server was handed something it cannot parse", e);
      }
      calendar.getProperties().remove(calendar.getProperty(net.fortuna.ical4j.model.Property.PRODID));
      calendar.getProperties().add(new net.fortuna.ical4j.model.property.ProdId("-//FakeMind//Calendar//EN"));
      calendar.getProperties().remove(calendar.getProperty(net.fortuna.ical4j.model.Property.CALSCALE));
      for (net.fortuna.ical4j.model.component.CalendarComponent component :
           calendar.getComponents(net.fortuna.ical4j.model.Component.VEVENT)) {
        reSerialise((net.fortuna.ical4j.model.component.VEvent) component, calendar, second);
      }
      return calendar.toString();
    }

    /**
     * Rewrites one component the way this server keeps it.
     *
     * @param event the component to rewrite
     * @param calendar the object it belongs to, which gains the zone definition
     * @param second whether this is the settling pass, which chooses a second
     *          serialisation of the same meeting rather than the first
     */
    private void reSerialise(net.fortuna.ical4j.model.component.VEvent event,
                             net.fortuna.ical4j.model.Calendar calendar,
                             boolean second) {
      event.getProperties().remove(event.getProperty(net.fortuna.ical4j.model.Property.TRANSP));
      event.getProperties().remove(event.getProperty(net.fortuna.ical4j.model.Property.DTSTAMP));
      event.getProperties().add(new net.fortuna.ical4j.model.property.DtStamp());
      // VERSION inside the VEVENT, which is not conformant and is what BlueMind
      // stores. Every scenario in this rig runs against it, rather than one
      // test doing so: it is a property of the server, not of a scenario, and a
      // fixture that only sometimes resembles the real thing is how the first
      // deploy got as far as it did.
      event.getProperties().add(new net.fortuna.ical4j.model.property.Version(new net.fortuna.ical4j.model.ParameterList(), "2.0"));
      substituteDirectoryNames(event);
      if (normalisation == Normalisation.RESERIALISE_AND_ANNOTATE) {
        event.getProperties()
             .add(new net.fortuna.ical4j.model.property.XProperty("X-FAKEMIND-SEQ", String.valueOf(++annotations)));
      }
      if (normalisation == Normalisation.RESERIALISE_AND_ATTACH_OWNER) {
        attachOwner(event);
      }
      if (normalisation == Normalisation.RESERIALISE_AND_DROP_THE_ORGANIZERS_ATTENDEE_LINE) {
        dropTheOrganizersAttendeeLine(event);
      }
      net.fortuna.ical4j.model.TimeZone zone =
          net.fortuna.ical4j.model.TimeZoneRegistryFactory.getInstance().createRegistry().getTimeZone(ZONE);
      if (calendar.getComponent(net.fortuna.ical4j.model.Component.VTIMEZONE) == null) {
        calendar.getComponents().add(zone.getVTimeZone());
      }
      // The settling pass states the very same instants a second way — a UTC
      // wall clock rather than a zoned one — so that no digest of this object
      // is stable at any moment, which is the whole point of it.
      anchor(event, net.fortuna.ical4j.model.Property.DTSTART, zone, second);
      anchor(event, net.fortuna.ical4j.model.Property.DTEND, zone, second);
    }

    /**
     * Replaces every calendar user's display name with this server's own
     * directory's spelling of it, which is what BlueMind does — CN=FRANCOIS
     * where eXo wrote CN=Root Root, for one and the same address.
     *
     * <p>
     * On every store and to every ORGANIZER and ATTENDEE, for the reason the
     * misplaced VERSION is done that way: it is a property of the server, not
     * of one scenario, and a fixture that only sometimes resembles the real
     * thing is how three deploys each found one more of these.
     *
     * @param event the component being stored
     */
    private void substituteDirectoryNames(net.fortuna.ical4j.model.component.VEvent event) {
      for (String name : List.of(net.fortuna.ical4j.model.Property.ORGANIZER,
                                 net.fortuna.ical4j.model.Property.ATTENDEE)) {
        for (Object candidate : event.getProperties(name)) {
          net.fortuna.ical4j.model.Property person = (net.fortuna.ical4j.model.Property) candidate;
          net.fortuna.ical4j.model.Parameter existing =
              person.getParameter(net.fortuna.ical4j.model.Parameter.CN);
          if (existing != null) {
            person.getParameters().remove(existing);
          }
          person.getParameters()
                .add(new net.fortuna.ical4j.model.parameter.Cn(person.getValue()
                                                                     .replaceFirst("(?i)^mailto:", "")
                                                                     .replaceFirst("@.*$", "")
                                                                     .toUpperCase(java.util.Locale.ROOT)));
        }
      }
    }

    /**
     * Attaches the calendar's owner to a component, the way a server does with
     * an event that lands in somebody's calendar: named from its own directory,
     * with a pointer into it, and with no answer on it yet.
     *
     * <p>
     * Re-attached on every store, deliberately. That is what makes this the
     * live defect rather than a one-off: a repair strips the line and the very
     * next write puts it straight back, so a pass that calls it a rewrite can
     * never converge however many times it repairs.
     *
     * @param event the component being stored
     */
    private void attachOwner(net.fortuna.ical4j.model.component.VEvent event) {
      for (Object existing : event.getProperties(net.fortuna.ical4j.model.Property.ATTENDEE)) {
        if (((net.fortuna.ical4j.model.Property) existing).getValue().toLowerCase(java.util.Locale.ROOT)
                                                          .endsWith(OWNER.toLowerCase(java.util.Locale.ROOT))) {
          return;
        }
      }
      net.fortuna.ical4j.model.ParameterList parameters = new net.fortuna.ical4j.model.ParameterList();
      parameters.add(new net.fortuna.ical4j.model.parameter.Cn("FRANCOIS"));
      parameters.add(new net.fortuna.ical4j.model.parameter.Dir(java.net.URI.create("bm://19d43a7c-dead-beef")));
      event.getProperties()
           .add(new net.fortuna.ical4j.model.property.Attendee(parameters, java.net.URI.create("mailto:" + OWNER)));
    }

    /**
     * Discards any ATTENDEE line naming the component's own organizer, which
     * is what a server holding an organizer and a list of attendees that
     * excludes them does with such a line.
     *
     * <p>
     * Captured from BlueMind on 2026-08-28 by reading the object back over
     * CalDAV: a copy eXo had written carrying
     * {@code ORGANIZER:mailto:x} and
     * {@code ATTENDEE;PARTSTAT=ACCEPTED:mailto:x} came back holding the
     * ORGANIZER alone. Applied on every store, like the other behaviours here,
     * because that is what makes it a loop rather than a one-off: a repair
     * writes the line back and the very next store removes it again.
     *
     * @param event the component being stored
     */
    private void dropTheOrganizersAttendeeLine(net.fortuna.ical4j.model.component.VEvent event) {
      net.fortuna.ical4j.model.Property organizer =
          event.getProperty(net.fortuna.ical4j.model.Property.ORGANIZER);
      if (organizer == null) {
        return;
      }
      String organizerAddress = bare(organizer.getValue());
      List<net.fortuna.ical4j.model.Property> dropped = new ArrayList<>();
      for (Object candidate : event.getProperties(net.fortuna.ical4j.model.Property.ATTENDEE)) {
        net.fortuna.ical4j.model.Property attendee = (net.fortuna.ical4j.model.Property) candidate;
        if (organizerAddress.equals(bare(attendee.getValue()))) {
          dropped.add(attendee);
        }
      }
      dropped.forEach(event.getProperties()::remove);
    }

    /**
     * A calendar address without its scheme or its casing.
     *
     * @param value the property value
     * @return the comparable form
     */
    private String bare(String value) {
      return value == null ? "" : value.replaceFirst("(?i)^mailto:", "").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Restates one date-time property without moving the instant it denotes.
     *
     * @param event the component
     * @param name the property to restate
     * @param zone the zone to anchor it on
     * @param utc true to restate it in UTC instead
     */
    private void anchor(net.fortuna.ical4j.model.component.VEvent event,
                        String name,
                        net.fortuna.ical4j.model.TimeZone zone,
                        boolean utc) {
      net.fortuna.ical4j.model.Property property = event.getProperty(name);
      if (!(property instanceof net.fortuna.ical4j.model.property.DateProperty dated)
          || !(dated.getDate() instanceof net.fortuna.ical4j.model.DateTime)) {
        return;
      }
      net.fortuna.ical4j.model.DateTime restated =
          new net.fortuna.ical4j.model.DateTime(java.util.Date.from(dated.getDate().toInstant()));
      if (utc) {
        restated.setUtc(true);
      } else {
        restated.setTimeZone(zone);
      }
      dated.setDate(restated);
    }

    /**
     * Stores an object and publishes a version for it.
     *
     * <p>
     * A normalising server answers the write with the version of the document
     * it was <i>handed</i>, then keeps its own re-serialisation under the next
     * version — so the ETag the PUT returned names a representation the server
     * does not hold. That is not an embellishment: it is what made the live
     * defect visible at all. The verification pass short-circuits on an
     * unchanged ETag, so a server whose version never moved would hide a
     * mismatched digest instead of reporting it, and the rig reported "19
     * altered" precisely because BlueMind's version had moved. A byte-stable
     * server has nothing to re-serialise and publishes one version.
     *
     * @param href the object's path
     * @param ics the text that was PUT
     * @param status the status to answer
     * @return the write's result
     */
    private PutResult accept(String href, String ics, int status) {
      if (writesRefused) {
        throw new IllegalStateException("the server will not take this copy");
      }
      String answered = "\"v" + ++version + "\"";
      objects.put(href, store(ics, false));
      etags.put(href, normalisation == Normalisation.NONE ? answered : "\"v" + ++version + "\"");
      if (settlesLate) {
        unsettled.add(href);
      }
      return new PutResult(status, answered, null);
    }

    @Override
    public CalDavEndpoint endpoint(Long serverId, String davUsername) {
      // Null on purpose: this fake is addressed by href alone, and every
      // method below ignores the endpoint. Minting a real one would only add
      // a registry the test has no use for.
      return null;
    }

    @Override
    public String discoverCalendarHome(CalDavEndpoint endpoint, String username, String password) {
      return HOME;
    }

    /**
     * This fake speaks no scheduling extension, which is what most CalDAV
     * servers do — and the registration behind this test asks for eXo's own
     * dedicated calendar anyway, so nothing here ever asks the question.
     *
     * @param endpoint ignored, this fake is addressed by href alone
     * @param username ignored
     * @param password ignored
     * @return null, the answer of a server naming no default calendar
     */
    @Override
    public String discoverDefaultCalendar(CalDavEndpoint endpoint, String username, String password) {
      return null;
    }

    @Override
    public List<CalendarCollection> listCalendars(CalDavEndpoint endpoint,
                                                  String homeHref,
                                                  String username,
                                                  String password) {
      return List.of(new CalendarCollection(MIRROR, "eXo Meetings", null, null, null, true));
    }

    @Override
    public CalendarCollection readCalendar(CalDavEndpoint endpoint, String href, String username, String password) {
      return null;
    }

    @Override
    public String getCtag(CalDavEndpoint endpoint, String href, String username, String password) {
      return null;
    }

    @Override
    public Map<String, String> listResourceEtags(CalDavEndpoint endpoint,
                                                 String collectionHref,
                                                 String username,
                                                 String password) {
      Map<String, String> listed = new LinkedHashMap<>();
      etags.forEach((href, etag) -> listed.put(href, asListed(etag)));
      return listed;
    }

    @Override
    public List<CalendarObject> calendarQuery(CalDavEndpoint endpoint,
                                              String collectionHref,
                                              Instant start,
                                              Instant end,
                                              String username,
                                              String password) {
      return List.of();
    }

    @Override
    public List<CalendarObject> multiget(CalDavEndpoint endpoint,
                                         String collectionHref,
                                         List<String> hrefs,
                                         String username,
                                         String password) {
      return List.of();
    }

    @Override
    public SyncCollectionResult syncCollection(CalDavEndpoint endpoint,
                                               String collectionHref,
                                               String syncToken,
                                               String username,
                                               String password) {
      throw new UnsupportedOperationException("not part of this test");
    }

    @Override
    public ServerCapabilities probeCapabilities(CalDavEndpoint endpoint,
                                                String collectionHref,
                                                String username,
                                                String password) {
      throw new UnsupportedOperationException("not part of this test");
    }

    @Override
    public CalendarObject fetchObject(CalDavEndpoint endpoint, String href, String username, String password) {
      fetches++;
      if (readsRefused) {
        throw new IllegalStateException("the server cannot be reached");
      }
      String ics = objects.get(href);
      if (ics != null && unsettled.remove(href)) {
        // It finishes here, after the read, and says nothing about it: the
        // version stays exactly where it was.
        ics = store(ics, true);
        objects.put(href, ics);
      }
      return ics == null ? null : new CalendarObject(href, etags.get(href), ics);
    }

    @Override
    public PutResult putObject(CalDavEndpoint endpoint, String href, String icsData, String username, String password) {
      // If-None-Match: * — a create, and only a create.
      return objects.containsKey(href) ? new PutResult(412, null, null) : accept(href, icsData, 201);
    }

    @Override
    public PutResult overwriteObject(CalDavEndpoint endpoint,
                                     String href,
                                     String icsData,
                                     String username,
                                     String password) {
      return accept(href, icsData, 200);
    }

    @Override
    public PutResult updateObject(CalDavEndpoint endpoint,
                                  String href,
                                  String icsData,
                                  String ifMatch,
                                  String username,
                                  String password) {
      // If-Match — refused when the caller's version is not the current one,
      // which is how a stale recorded ETag stops an eXo edit from landing.
      //
      // Either spelling of the current version is honoured, and a server that
      // publishes two has to: the precondition is defined against the
      // entity-tag the server itself published, and this one published both.
      // The alternative — publishing a value through the listing and then
      // refusing it in a precondition — would leave a client no storable value
      // at all, and is not a shape any measurement of BlueMind showed.
      String current = etags.get(href);
      boolean matches = ifMatch != null && (ifMatch.equals(current) || ifMatch.equals(asListed(current)));
      return matches ? accept(href, icsData, 204) : new PutResult(412, null, null);
    }

    @Override
    public int deleteObject(CalDavEndpoint endpoint, String href, String ifMatch, String username, String password) {
      objects.remove(href);
      etags.remove(href);
      return 204;
    }

    @Override
    public MkCalendarResult mkCalendar(CalDavEndpoint endpoint,
                                       String href,
                                       String displayName,
                                       String color,
                                       String username,
                                       String password) {
      return new MkCalendarResult(201, List.of());
    }

    @Override
    public int deleteCollection(CalDavEndpoint endpoint, CalendarSync pair, String username, String password) {
      return 204;
    }
  }

  /** What a fake server does to the objects it is handed. */
  private enum Normalisation {
    /** Keeps the bytes verbatim, as Stalwart does. */
    NONE,
    /** Parses and writes the same meeting its own way, as BlueMind does. */
    RESERIALISE,
    /** The same, and stamps a proprietary property of its own on every store. */
    RESERIALISE_AND_ANNOTATE,
    /**
     * The same, and attaches the calendar's own owner to every copy that lands
     * in it, naming them from its own directory — which is what BlueMind does,
     * and what made all 20 copies of a live account altered on every sweep.
     */
    RESERIALISE_AND_ATTACH_OWNER,
    /**
     * The same, and refuses to store an ATTENDEE line naming the meeting's own
     * organizer — which is what BlueMind does, and what EXO-89768 is.
     */
    RESERIALISE_AND_DROP_THE_ORGANIZERS_ATTENDEE_LINE
  }
}
