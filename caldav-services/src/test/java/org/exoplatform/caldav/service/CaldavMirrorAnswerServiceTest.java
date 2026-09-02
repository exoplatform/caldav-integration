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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.SyncCollectionResult;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * <h2>The copies in a collection nothing else reads still give up their
 * answers (EXO-89814).</h2>
 *
 * <p>
 * Where the copies go to eXo's own <code>exo-meetings</code> collection —
 * {@code DEDICATED_CALENDAR}, the <b>default</b> — that collection is
 * deliberately never materialised and its MIRROR pair is deliberately excluded
 * from the inbound loop, so no inbound pass ever meets those copies: EXO-89807
 * has nowhere to run and EXO-89810's daily full read cannot reach them. The
 * only reader left is the mirror verification's ETag gate, which never opens on
 * a server that records an answer without moving the ETag.
 *
 * <p>
 * These pin the pass that closes that hole and, as much, the four things it
 * refuses to do: it never asks a server that another binding already reads, it
 * never fetches a whole collection to establish a token, it never fetches
 * without bound, and it never imports anything.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavMirrorAnswerServiceTest {

  private static final long              USER       = 42L;

  private static final long              SERVER     = 7L;

  private static final long              PAIR       = 195L;

  private static final long              EVENT      = 1014L;

  private static final String            LOGIN      = "alice@stalwart.local";

  private static final String            COLLECTION = "/dav/cal/alice@stalwart.local/exo-meetings";

  private static final String            HREF       = COLLECTION + "/one.ics";

  private static final String            TOKEN      = "urn:stalwart:davsync:b";

  private static final String            FRESH      = "urn:stalwart:davsync:c";

  private static final String            ANSWERED   = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:uid-1\r\n"
      + "ATTENDEE;PARTSTAT=TENTATIVE:mailto:" + LOGIN + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

  @Mock
  private CalDavClient                   calDavClient;

  @Mock
  private CaldavSyncStorage              caldavSyncStorage;

  @Mock
  private CaldavAnswerAdoptionService    caldavAnswerAdoptionService;

  @Mock
  private CalDavEndpoint                 endpoint;

  @InjectMocks
  private CaldavMirrorAnswerService      service;

  /** The row as the storage would hand it back, so the token write is visible. */
  private CalendarSync                   stored;

  @BeforeEach
  public void aDedicatedMirrorNoBindingReads() {
    ReflectionTestUtils.setField(service, "maxObjectsPerPass", 100);
    stored = pair(PAIR, SyncOrigin.MIRROR, COLLECTION, TOKEN, CalendarSyncStatus.ACTIVE);
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
    lenient().when(caldavSyncStorage.getPair(PAIR)).thenReturn(stored);
    // The account's own calendar, on a different collection: nothing here
    // reads exo-meetings.
    lenient().when(caldavSyncStorage.getPairs(USER, SERVER))
             .thenReturn(List.of(pair(194L,
                                      SyncOrigin.REMOTE,
                                      "/dav/cal/alice@stalwart.local/default",
                                      "t",
                                      CalendarSyncStatus.ACTIVE),
                                 pair(PAIR, SyncOrigin.MIRROR, COLLECTION, TOKEN, CalendarSyncStatus.ACTIVE)));
    lenient().when(caldavSyncStorage.getObjects(eq(PAIR), anyInt(), anyInt()))
             .thenAnswer(invocation -> invocation.getArgument(1, Integer.class) == 0 ? new PageImpl<>(List.of(mapping()))
                                                                                     : new PageImpl<>(List.of()));
  }

  @Test
  public void anAnswerOnACopyNoOtherPassMeetsIsRecorded() {
    givenTheCollectionReports(new SyncCollectionResult(true, FRESH, List.of(named(HREF)), List.of()));
    when(calDavClient.multiget(endpoint, COLLECTION + "/", List.of(HREF)))
                                                                                          .thenReturn(List.of(new CalendarObject(HREF,
                                                                                                                                 "\"3244716488\"",
                                                                                                                                 ANSWERED)));
    when(caldavAnswerAdoptionService.adoptAnswer(USER, EVENT, ANSWERED))
                                                                       .thenReturn(CaldavAnswerAdoptionService.Outcome.ADOPTED);

    assertEquals(1, service.readAnswers(USER, LOGIN, settings(), mirror()));

    verify(caldavAnswerAdoptionService).adoptAnswer(USER, EVENT, ANSWERED);
  }

  @Test
  public void theTokenMovesPastWhatWasRead() {
    // Without this the same generation is read on every sweep for ever, which
    // is the unbounded read this whole design is arranged to avoid.
    givenTheCollectionReports(new SyncCollectionResult(true, FRESH, List.of(named(HREF)), List.of()));
    when(calDavClient.multiget(any(), anyString(), anyList()))
                                                                                       .thenReturn(List.of(new CalendarObject(HREF,
                                                                                                                              "e",
                                                                                                                              ANSWERED)));

    service.readAnswers(USER, LOGIN, settings(), mirror());

    assertEquals(FRESH, savedPair().getSyncToken());
  }

  @Test
  public void aCollectionABindingAlreadyReadsIsNotAskedAtAll() {
    // Where the copies land in the account's own calendar (MAIN_CALENDAR) that
    // calendar is materialised and read through its own binding, and EXO-89807
    // adopts every answer on that path. Asking again would double a REPORT and
    // a fetch per sweep to reach a conclusion already reached.
    when(caldavSyncStorage.getPairs(USER, SERVER))
                                                  .thenReturn(List.of(pair(192L,
                                                                           SyncOrigin.REMOTE,
                                                                           COLLECTION,
                                                                           "t",
                                                                           CalendarSyncStatus.ACTIVE),
                                                                      pair(PAIR,
                                                                           SyncOrigin.MIRROR,
                                                                           COLLECTION,
                                                                           TOKEN,
                                                                           CalendarSyncStatus.ACTIVE)));

    assertEquals(0, service.readAnswers(USER, LOGIN, settings(), mirror()));

    verifyNoInteractions(calDavClient);
    verifyNoInteractions(caldavAnswerAdoptionService);
  }

  @Test
  public void aCollectionWhoseOnlyBindingIsPausedIsRead() {
    // The condition is "no binding READS this collection", not "no binding
    // exists": the inbound loop skips a paused or tombstoned binding, so a
    // collection behind one is read by nothing at all — this pass's case
    // exactly, and one a check on the destination setting would miss.
    when(caldavSyncStorage.getPairs(USER, SERVER))
                                                  .thenReturn(List.of(pair(192L,
                                                                           SyncOrigin.REMOTE,
                                                                           COLLECTION,
                                                                           "t",
                                                                           CalendarSyncStatus.PAUSED),
                                                                      pair(PAIR,
                                                                           SyncOrigin.MIRROR,
                                                                           COLLECTION,
                                                                           TOKEN,
                                                                           CalendarSyncStatus.ACTIVE)));
    givenTheCollectionReports(new SyncCollectionResult(true, FRESH, List.of(named(HREF)), List.of()));
    when(calDavClient.multiget(any(), anyString(), anyList()))
                                                                                       .thenReturn(List.of(new CalendarObject(HREF,
                                                                                                                              "e",
                                                                                                                              ANSWERED)));
    when(caldavAnswerAdoptionService.adoptAnswer(USER, EVENT, ANSWERED))
                                                                       .thenReturn(CaldavAnswerAdoptionService.Outcome.ADOPTED);

    assertEquals(1, service.readAnswers(USER, LOGIN, settings(), mirror()));
  }

  @Test
  public void establishingATokenReadsNothing() {
    // The initial sync names every object in the collection. Fetching those
    // bodies is the burst EXO-89810 refused to build — one listing and a fetch
    // per copy, at once, at the one moment a deployment can least absorb it,
    // and the shape that got this rig's test proxy banned twice.
    CalendarSync fresh = mirror();
    fresh.setSyncToken(null);
    when(calDavClient.syncCollection(endpoint, COLLECTION + "/", null))
                                                                                       .thenReturn(new SyncCollectionResult(true,
                                                                                                                            FRESH,
                                                                                                                            List.of(named(HREF),
                                                                                                                                    named(COLLECTION
                                                                                                                                        + "/two.ics")),
                                                                                                                            List.of()));

    assertEquals(0, service.readAnswers(USER, LOGIN, settings(), fresh));

    verify(calDavClient, never()).multiget(any(), anyString(), anyList());
    verify(caldavAnswerAdoptionService, never()).adoptAnswer(anyLong(), anyLong(), anyString());
    assertEquals(FRESH, savedPair().getSyncToken());
  }

  @Test
  public void aRefusedTokenIsDroppedRatherThanReplaced() {
    // The refused-token result carries no token to store. Clearing it is what
    // sends the next pass down the establishing path above — where a fresh one
    // is taken without reading a whole collection's worth of bodies.
    givenTheCollectionReports(SyncCollectionResult.invalidToken());

    assertEquals(0, service.readAnswers(USER, LOGIN, settings(), mirror()));

    verify(calDavClient, never()).multiget(any(), anyString(), anyList());
    assertNull(savedPair().getSyncToken());
  }

  @Test
  public void aBurstOfChangesIsCappedAndStillDrains() {
    // A connect-time backfill writes a whole account's upcoming meetings at
    // once, and the next report names all of them. The ceiling bounds the
    // fetch; the token still moves, because holding it back would make the
    // next pass re-fetch the same head for ever and never drain.
    ReflectionTestUtils.setField(service, "maxObjectsPerPass", 2);
    List<CalendarObject> reported = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      reported.add(named(COLLECTION + "/copy-" + i + ".ics"));
    }
    givenTheCollectionReports(new SyncCollectionResult(true, FRESH, reported, List.of()));
    when(calDavClient.multiget(any(), anyString(), anyList())).thenReturn(List.of());

    service.readAnswers(USER, LOGIN, settings(), mirror());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> asked = ArgumentCaptor.forClass(List.class);
    verify(calDavClient).multiget(any(), anyString(), asked.capture());
    assertEquals(2, asked.getValue().size());
    assertEquals(FRESH, savedPair().getSyncToken());
  }

  @Test
  public void anObjectTheMappingDoesNotNameIsNotAnswerable() {
    // Something else in the collection — another client's event, a leftover.
    // There is no meeting of eXo's to record an answer against, and guessing
    // one would record somebody's answer against a meeting nobody asked them
    // about.
    String stranger = COLLECTION + "/stranger.ics";
    givenTheCollectionReports(new SyncCollectionResult(true, FRESH, List.of(named(stranger)), List.of()));
    when(calDavClient.multiget(any(), anyString(), anyList()))
                                                                                       .thenReturn(List.of(new CalendarObject(stranger,
                                                                                                                              "e",
                                                                                                                              ANSWERED)));

    assertEquals(0, service.readAnswers(USER, LOGIN, settings(), mirror()));

    verify(caldavAnswerAdoptionService, never()).adoptAnswer(anyLong(), anyLong(), anyString());
  }

  @Test
  public void aCollectionThatCouldNotReportKeepsItsToken() {
    // Nothing is concluded from a report that could not be made. Moving the
    // token here would claim the changes had been taken in, and nothing would
    // ever mention them again.
    when(calDavClient.syncCollection(any(), anyString(), anyString()))
                                                                                               .thenThrow(new CalDavException("down"));

    assertEquals(0, service.readAnswers(USER, LOGIN, settings(), mirror()));

    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * @param result what the collection answers when asked with its token
   */
  private void givenTheCollectionReports(SyncCollectionResult result) {
    when(calDavClient.syncCollection(endpoint, COLLECTION + "/", TOKEN)).thenReturn(result);
  }

  /**
   * @return the pair as it was written back
   */
  private CalendarSync savedPair() {
    ArgumentCaptor<CalendarSync> saved = ArgumentCaptor.forClass(CalendarSync.class);
    verify(caldavSyncStorage).savePair(saved.capture());
    return saved.getValue();
  }

  /**
   * @param href the path the report names
   * @return the object as a report carries it: a path and a version, never a
   *         body
   */
  private CalendarObject named(String href) {
    return new CalendarObject(href, "\"v\"", null);
  }

  /**
   * @return the mirror's single mapping row
   */
  private ObjectSync mapping() {
    ObjectSync object = new ObjectSync();
    object.setId(815L);
    object.setCalendarSyncId(PAIR);
    object.setLocalEventId(EVENT);
    object.setIcsUid("uid-1");
    object.setRemoteHref(HREF);
    return object;
  }

  /**
   * @return the binding recording where the copies are written
   */
  private CalendarSync mirror() {
    return pair(PAIR, SyncOrigin.MIRROR, COLLECTION, TOKEN, CalendarSyncStatus.ACTIVE);
  }

  /**
   * @param id technical identifier
   * @param origin what the pair stands for
   * @param href the collection it names
   * @param token the sync token it carries
   * @param status whether the inbound loop would read it
   * @return the pair
   */
  private CalendarSync pair(long id, SyncOrigin origin, String href, String token, CalendarSyncStatus status) {
    CalendarSync pair = new CalendarSync();
    pair.setId(id);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setOrigin(origin);
    pair.setRemoteHref(href);
    pair.setSyncToken(token);
    pair.setStatus(status);
    return pair;
  }

  /**
   * @return the connected account
   */
  private CaldavUserSetting settings() {
    CaldavUserSetting settings = new CaldavUserSetting();
    settings.setServerId(SERVER);
    settings.setUsername(LOGIN);
    settings.setPassword("secret");
    return settings;
  }
}
