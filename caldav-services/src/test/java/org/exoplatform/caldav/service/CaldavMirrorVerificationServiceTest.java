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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarObject;
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

  private static final String                ICS    = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n";

  @Mock
  private CalDavClient                       calDavClient;

  @Mock
  private CaldavConnectorStorage             caldavConnectorStorage;

  @Mock
  private CaldavSyncStorage                  caldavSyncStorage;

  @Mock
  private CaldavPushService                  caldavPushService;

  @Mock
  private CalDavEndpoint                     endpoint;

  @InjectMocks
  private CaldavMirrorVerificationService    service;

  @BeforeEach
  public void connectAnAccountWithAMirror() {
    ReflectionTestUtils.setField(service, "maxRepairs", 3);
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, LOGIN)).thenReturn(endpoint);
    lenient().when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(mirror()));
  }

  @Test
  public void aCopyTheServerNoLongerHoldsIsWrittenAgain() {
    // Someone deleted it from their phone. The mirror is eXo's projection and
    // nothing syncs back from it, so the answer is to put it back — not to
    // take the deletion as an instruction.
    givenServerHolds(Map.of());
    givenMappings(mapping(HREF, "\"etag-1\"", hash(ICS), 5L));

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
    givenMappings(mapping(HREF, "\"etag-1\"", hash(ICS), 5L));

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.checked());
    assertEquals(0, result.missing());
    assertEquals(0, result.altered());
    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
  }

  @Test
  public void aQuotingDifferenceIsNotARewrite() {
    // Servers publish the same ETag weak, quoted, or bare. Comparing the
    // strings as they arrive would fetch and re-push every object on every
    // pass, against a server that changed nothing.
    givenServerHolds(Map.of(HREF, "W/\"etag-1\""));
    givenMappings(mapping(HREF, "\"etag-1\"", hash(ICS), 5L));

    assertEquals(0, service.verify(USER).altered());
    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
  }

  @Test
  public void aCopyTheServerRewroteIsWrittenAgain() {
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    givenMappings(mapping(HREF, "\"etag-1\"", hash(ICS), 5L));
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                                            .thenReturn(new CalendarObject(HREF,
                                                                                                           "\"etag-2\"",
                                                                                                           "BEGIN:VCALENDAR\r\nSUMMARY:mangled\r\nEND:VCALENDAR\r\n"));

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.altered());
    assertEquals(1, result.repaired());
    // Through the unconditional entry point, and it matters which: the
    // guarded one carries the etag recorded before the server rewrote the
    // object, so it is refused every time — which is how this pass came to
    // report "altered: 1, re-pushed: 0" on a live account while this test,
    // mocking the push service, stayed green.
    verify(caldavPushService).rewriteAgendaEvent(USER, 5L);
    verify(caldavPushService, never()).pushAgendaEvent(anyLong(), anyLong(), any());
  }

  @Test
  public void aRowWithNoEventAndNoObjectIsDropped() {
    // Gone from the server and standing for no eXo event: the row describes
    // nothing on either side, so there is nothing to protect by keeping it —
    // only a missing count that never reaches zero.
    givenServerHolds(Map.of());
    ObjectSync orphan = mapping(HREF, "\"etag-1\"", hash(ICS), null);
    orphan.setId(77L);
    givenMappings(orphan);

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.missing());
    assertEquals(0, result.repaired());
    verify(caldavSyncStorage).deleteObject(77L);
  }

  @Test
  public void aRowWithNoEventWhoseObjectIsOnlyChangedIsKept() {
    // The object is still there. The row is the only link to it, so dropping
    // it would lose a copy the user may well want.
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    ObjectSync orphan = mapping(HREF, "\"etag-1\"", hash(ICS), null);
    orphan.setId(77L);
    givenMappings(orphan);
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                                            .thenReturn(new CalendarObject(HREF,
                                                                                                           "\"etag-2\"",
                                                                                                           "BEGIN:VCALENDAR\r\nSUMMARY:mangled\r\nEND:VCALENDAR\r\n"));

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.altered());
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
    ObjectSync stale = mapping(HREF, "\"etag-1\"", hash(ICS), 5L);
    stale.setId(9002L);
    givenMappings(stale);
    ObjectSync elsewhere = mapping(MIRROR + "moved.ics", "\"etag-3\"", hash(ICS), 5L);
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
    ObjectSync row = mapping(HREF, "\"etag-1\"", hash(ICS), 5L);
    row.setId(9001L);
    givenMappings(row);
    ObjectSync same = mapping(HREF, "\"etag-2\"", hash(ICS), 5L);
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
    ObjectSync row = mapping(HREF, "\"etag-1\"", hash(ICS), 5L);
    row.setId(null);
    givenMappings(row);
    ObjectSync elsewhere = mapping(MIRROR + "moved.ics", "\"etag-3\"", hash(ICS), 5L);
    elsewhere.setId(4242L);
    when(caldavPushService.rewriteAgendaEvent(USER, 5L)).thenReturn(elsewhere);

    service.verify(USER);

    verify(caldavSyncStorage, never()).deleteObject(anyLong());
  }

  @Test
  public void anEtagThatMovedOverTheSameBytesIsNotARewrite() {
    // A server touching its own metadata moves the ETag without touching the
    // object. Trusting the ETag alone would re-push a copy that is exactly
    // what eXo wrote.
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    givenMappings(mapping(HREF, "\"etag-1\"", hash(ICS), 5L));
    when(calDavClient.fetchObject(any(), eq(HREF), anyString(), anyString()))
                                                                            .thenReturn(new CalendarObject(HREF,
                                                                                                           "\"etag-2\"",
                                                                                                           ICS));

    assertEquals(0, service.verify(USER).altered());
    verify(caldavPushService, never()).rewriteAgendaEvent(anyLong(), anyLong());
  }

  @Test
  public void aCopyWrittenBeforeHashesWereRecordedIsLeftAlone() {
    // Nothing here can say whether the change matters, and a re-push would
    // overwrite a copy that may be perfectly fine.
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    givenMappings(mapping(HREF, "\"etag-1\"", null, 5L));

    assertEquals(0, service.verify(USER).altered());
    verify(calDavClient, never()).fetchObject(any(), anyString(), anyString(), anyString());
  }

  @Test
  public void anUnreadableCopyIsLeftAloneRatherThanOverwritten() {
    // Unreadable is not the same as rewritten, and a re-push here would
    // overwrite whatever is there on the strength of a network error.
    givenServerHolds(Map.of(HREF, "\"etag-2\""));
    givenMappings(mapping(HREF, "\"etag-1\"", hash(ICS), 5L));
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
    givenMappings(mapping(HREF, "\"etag-1\"", hash(ICS), 5L));

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
    givenMappings(mapping(HREF, "\"etag-1\"", hash(ICS), 5L));
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
    givenMappings(mapping(HREF, "\"etag-1\"", hash(ICS), null));

    MirrorVerification result = service.verify(USER);

    assertEquals(1, result.missing());
    assertEquals(0, result.repaired());
    verify(caldavPushService, never()).rewriteAgendaEvent(anyLong(), anyLong());
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
   * @param pushedHash the digest recorded when it was written
   * @param localEventId the agenda event it stands for
   * @return the mapping row
   */
  private ObjectSync mapping(String href, String etag, String pushedHash, Long localEventId) {
    ObjectSync object = new ObjectSync();
    object.setId(1L);
    object.setCalendarSyncId(3L);
    object.setRemoteHref(href);
    object.setEtag(etag);
    object.setPushedHash(pushedHash);
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

  /**
   * @param ics the object
   * @return the digest the push would have recorded for it
   */
  private String hash(String ics) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(ics.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
