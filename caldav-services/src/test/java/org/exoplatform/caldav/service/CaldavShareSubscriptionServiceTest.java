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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavForbiddenException;
import org.exoplatform.caldav.client.CalDavNotFoundException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.bluemind.BlueMindSubjectMismatchException;
import org.exoplatform.caldav.client.bluemind.BlueMindSubscriptionClient;
import org.exoplatform.caldav.client.bluemind.BlueMindSubscriptionClient.Subscriptions;
import org.exoplatform.caldav.model.PendingSubscription;
import org.exoplatform.caldav.model.PendingSubscriptionKind;
import org.exoplatform.caldav.service.CaldavShareSubscriptionService.ShareeSubscription;
import org.exoplatform.caldav.storage.CaldavPendingSubscriptionStorage;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The colleague's subscription that follows a BlueMind share from eXo, and
 * the drain that settles what did not land (EXO-90277).
 *
 * <p>
 * What is pinned is what a wrong line would silently cost: a session opened
 * with the <em>owner's</em> endpoint rather than the colleague's (their
 * password used for somebody else's account); a subscribe failure escaping
 * into the owner's grant; a final refusal argued with until the bound, or a
 * transient one given up on at once; a colleague who holds no pair of their
 * own never reached; and a second colleague's rows lost to the first's
 * failure.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavShareSubscriptionServiceTest {

  private static final long   ALICE       = 5L;

  private static final long   BOB         = 9L;

  private static final long   CAROL       = 12L;

  private static final long   SERVER      = 1L;

  private static final String ERIC_UID    = "6B2E4F10-8A3C-4D7E-9B51-0C2D4E6F8A17";

  private static final String ERIC_PRINCIPAL = "/dav/principals/__uids__/" + ERIC_UID;

  private static final String WRITER_UID  = "D41A7C22-3E5B-4F60-8A19-2B7C9D0E1F35";

  private static final String WRITER_PRINCIPAL = "/dav/principals/__uids__/" + WRITER_UID;

  private static final String CONTAINER   = "exo-cal-9f1c2d3e-4b5a-6c7d-8e9f-0a1b2c3d4e5f";

  private static final String OTHER       = "exo-cal-0000aaaa-1111-2222-3333-444455556666";

  @Mock
  private BlueMindSubscriptionClient       blueMindSubscriptionClient;

  @Mock
  private CaldavPendingSubscriptionStorage caldavPendingSubscriptionStorage;

  @Mock
  private CalDavClient                     calDavClient;

  @Mock
  private CaldavConnectionIdentityService  caldavConnectionIdentityService;

  @Mock
  private IdentityManager                  identityManager;

  @Mock
  private CalDavEndpoint                   bobEndpoint;

  @Mock
  private CalDavEndpoint                   aliceEndpoint;

  @Mock
  private CalDavEndpoint                   carolEndpoint;

  @Mock
  private Subscriptions                    edits;

  @InjectMocks
  private CaldavShareSubscriptionService   service;

  private ListAppender<ILoggingEvent>      logged;

  private Logger                           logger;

  private Level                            previousLevel;

  /**
   * Bob (eric on BlueMind) and carol connected to server 1, each with an
   * endpoint of their own, and a client whose session hands the edits to the
   * job.
   */
  @BeforeEach
  public void rig() {
    lenient().when(calDavClient.endpoint(SERVER, "bob")).thenReturn(bobEndpoint);
    lenient().when(calDavClient.endpoint(SERVER, "alice")).thenReturn(aliceEndpoint);
    lenient().when(calDavClient.endpoint(SERVER, "carol")).thenReturn(carolEndpoint);
    lenient().when(identityManager.getIdentity(String.valueOf(BOB))).thenReturn(user(BOB, "bob"));
    lenient().when(identityManager.getIdentity(String.valueOf(CAROL))).thenReturn(user(CAROL, "carol"));
    lenient().when(caldavConnectionIdentityService.principalOf(BOB, SERVER)).thenReturn(ERIC_PRINCIPAL);
    lenient().when(caldavConnectionIdentityService.principalOf(CAROL, SERVER)).thenReturn(WRITER_PRINCIPAL);
    sessionOpens();
    logger = (Logger) LoggerFactory.getLogger(CaldavShareSubscriptionService.class);
    previousLevel = logger.getLevel();
    logger.setLevel(Level.INFO);
    logged = new ListAppender<>();
    logged.start();
    logger.addAppender(logged);
  }

  /**
   * The logger is left as found.
   */
  @AfterEach
  public void restoreLogger() {
    logger.detachAppender(logged);
    logger.setLevel(previousLevel);
  }

  // ---------------------------------------------------------------- at grant and revoke time

  /**
   * The session is opened with the <em>colleague's</em> endpoint — minted for
   * bob's login, never alice's — checked against eric's recorded uid, and
   * the subscribe lands: audited, and any row settled.
   */
  @Test
  public void aGrantSubscribesTheColleagueThroughTheirOwnEndpoint() {
    service.subscribeSharee(share());

    verify(calDavClient).endpoint(SERVER, "bob");
    verify(calDavClient, never()).endpoint(anyLong(), eq("alice"));
    verify(blueMindSubscriptionClient).asSharee(eq(bobEndpoint), eq(ERIC_UID), any());
    verify(edits).subscribe(CONTAINER);
    verify(caldavPendingSubscriptionStorage).settled(BOB, SERVER, CONTAINER);
    verify(caldavPendingSubscriptionStorage, never()).owe(anyLong(), anyLong(), anyString(), any());
    assertTrue(infoLines().stream().anyMatch(line -> line.contains("subscribed to") && line.contains("bob")
        && line.contains("alice") && line.contains(CONTAINER) && line.contains("server 1")), infoLines().toString());
  }

  /**
   * A revoke posts the reverse, and a legacy account (server key zero) mints
   * its endpoint for the seed row.
   */
  @Test
  public void aRevokeUnsubscribesTheColleague() {
    when(calDavClient.endpoint(null, "bob")).thenReturn(bobEndpoint);

    service.unsubscribeSharee(new ShareeSubscription("alice", BOB, "bob", ERIC_UID, 0L, CONTAINER));

    verify(edits).unsubscribe(CONTAINER);
    verify(edits, never()).subscribe(anyString());
    verify(caldavPendingSubscriptionStorage).settled(BOB, 0L, CONTAINER);
    assertTrue(infoLines().stream().anyMatch(line -> line.contains("unsubscribed from")));
  }

  /**
   * Whatever the subscribe fails with — the server unreachable, the
   * colleague's stale password refused, BlueMind refusing the edit, the
   * container gone, a session that is not theirs — the grant is not failed:
   * nothing is thrown, one WARN says so, and a SUBSCRIBE row is recorded for
   * the drain. The colleague's account is never paused from here.
   */
  @Test
  public void aSubscribeThatDoesNotLandIsRecordedAndNeverThrown() {
    RuntimeException[] atLogin = { new CalDavUnreachableException("down"), new CalDavAuthenticationException("refused"),
        new BlueMindSubjectMismatchException("not eric"), new UnsupportedOperationException("token, not a login"),
        new CalDavException("500") };
    for (RuntimeException failure : atLogin) {
      logged.list.clear();
      doThrow(failure).when(blueMindSubscriptionClient).asSharee(eq(bobEndpoint), eq(ERIC_UID), any());

      assertDoesNotThrow(() -> service.subscribeSharee(share()), failure.toString());

      assertEquals(1, warnLines().size(), failure + ": " + warnLines());
    }
    RuntimeException[] atEdit = { new CalDavForbiddenException("403"), new CalDavNotFoundException("gone"),
        new CalDavAuthenticationException("401 mid-session"), new CalDavException("500 other") };
    org.mockito.Mockito.reset(blueMindSubscriptionClient);
    sessionOpens();
    for (RuntimeException failure : atEdit) {
      logged.list.clear();
      doThrow(failure).when(edits).subscribe(CONTAINER);

      assertDoesNotThrow(() -> service.subscribeSharee(share()), failure.toString());

      assertEquals(1, warnLines().size(), failure + ": " + warnLines());
    }

    verify(caldavPendingSubscriptionStorage, times(atLogin.length + atEdit.length)).owe(BOB,
                                                                                         SERVER,
                                                                                         CONTAINER,
                                                                                         PendingSubscriptionKind.SUBSCRIBE);
    verify(caldavPendingSubscriptionStorage, never()).settled(anyLong(), anyLong(), anyString());
    verify(caldavPendingSubscriptionStorage, never()).settled(anyLong(), anyLong(), anyString(), any());
  }

  /**
   * A revoke that does not land records an UNSUBSCRIBE row — which, through
   * the storage's one-row-per-container rule, replaces a SUBSCRIBE still
   * pending from the grant.
   */
  @Test
  public void anUnsubscribeThatDoesNotLandIsRecordedAsAnUnsubscribe() {
    doThrow(new CalDavUnreachableException("down")).when(edits).unsubscribe(CONTAINER);

    assertDoesNotThrow(() -> service.unsubscribeSharee(share()));

    verify(caldavPendingSubscriptionStorage).owe(BOB, SERVER, CONTAINER, PendingSubscriptionKind.UNSUBSCRIBE);
  }

  /**
   * Even the record failing — the database away — never reaches the owner's
   * grant.
   */
  @Test
  public void aStorageFailureNeverEscapesTheSeam() {
    doThrow(new CalDavUnreachableException("down")).when(edits).subscribe(CONTAINER);
    when(caldavPendingSubscriptionStorage.owe(anyLong(), anyLong(), anyString(), any())).thenThrow(new IllegalStateException("db"));

    assertDoesNotThrow(() -> service.subscribeSharee(share()));

    org.mockito.Mockito.doNothing().when(edits).subscribe(CONTAINER);
    doThrow(new IllegalStateException("db")).when(caldavPendingSubscriptionStorage).settled(anyLong(), anyLong(), anyString());
    assertDoesNotThrow(() -> service.subscribeSharee(share()));
    verify(caldavPendingSubscriptionStorage).settled(BOB, SERVER, CONTAINER);
  }

  // ---------------------------------------------------------------- the drain

  /**
   * <b>Hole 2 of the brief.</b> The drain reads the owed table, not the
   * accounts: bob, who holds no pair of his own and is never swept, is
   * reached through his rows alone — one session for his two containers —
   * and each row is settled by its own answer: landed is settled, a refused
   * edit (403) and an absent container are given up on whole, an unexplained
   * answer is counted. Every drained row is one INFO line.
   */
  @Test
  public void theDrainReachesAColleagueWhoHoldsNoPairAndSettlesEachRowByItsAnswer() {
    PendingSubscription landed = row(1L, BOB, CONTAINER, PendingSubscriptionKind.SUBSCRIBE, 0);
    PendingSubscription forbidden = row(2L, BOB, OTHER, PendingSubscriptionKind.SUBSCRIBE, 1);
    PendingSubscription gone = row(3L, BOB, "exo-cal-gone", PendingSubscriptionKind.UNSUBSCRIBE, 0);
    PendingSubscription flaky = row(4L, BOB, "exo-cal-flaky", PendingSubscriptionKind.SUBSCRIBE, 2);
    when(caldavPendingSubscriptionStorage.attemptable(5, 50)).thenReturn(List.of(landed, forbidden, gone, flaky));
    // lenient: strict stubs would read the landed row's call, with another
    // container, as a potential stubbing problem and throw it into the drain.
    lenient().doThrow(new CalDavForbiddenException("403")).when(edits).subscribe(OTHER);
    lenient().doThrow(new CalDavNotFoundException("404")).when(edits).unsubscribe("exo-cal-gone");
    lenient().doThrow(new CalDavException("500 SQL_ERROR")).when(edits).subscribe("exo-cal-flaky");

    int settled = service.retryOwed(50);

    assertEquals(1, settled);
    verify(blueMindSubscriptionClient, times(1)).asSharee(eq(bobEndpoint), eq(ERIC_UID), any());
    verify(caldavPendingSubscriptionStorage).settled(BOB, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE);
    verify(caldavPendingSubscriptionStorage).abandoned(2L, 5);
    verify(caldavPendingSubscriptionStorage).abandoned(3L, 5);
    verify(caldavPendingSubscriptionStorage).refused(4L);
    verify(caldavPendingSubscriptionStorage, never()).refused(1L);
    verify(caldavPendingSubscriptionStorage, never()).refused(2L);
    verify(caldavPendingSubscriptionStorage, never()).abandoned(eq(4L), anyInt());
    assertEquals(4, infoLines().stream().filter(line -> line.startsWith("Owed BlueMind")).count(), infoLines().toString());
  }

  /**
   * A server that cannot be reached, or a login refused, may change by asking
   * again: every row of the colleague is counted once, none is given up on,
   * and the other colleague's rows are still drained.
   */
  @Test
  public void anUnreachableServerOrARefusedLoginCountsARetryForEveryRowOfTheColleague() {
    PendingSubscription bobOne = row(1L, BOB, CONTAINER, PendingSubscriptionKind.SUBSCRIBE, 0);
    PendingSubscription bobTwo = row(2L, BOB, OTHER, PendingSubscriptionKind.SUBSCRIBE, 0);
    PendingSubscription carols = row(3L, CAROL, CONTAINER, PendingSubscriptionKind.SUBSCRIBE, 0);
    when(caldavPendingSubscriptionStorage.attemptable(5, 50)).thenReturn(List.of(bobOne, bobTwo, carols));
    doThrow(new CalDavAuthenticationException("stale")).when(blueMindSubscriptionClient).asSharee(eq(bobEndpoint), eq(ERIC_UID), any());

    int settled = service.retryOwed(50);

    assertEquals(1, settled, "carol's row landed although bob's login was refused");
    verify(caldavPendingSubscriptionStorage).refused(1L);
    verify(caldavPendingSubscriptionStorage).refused(2L);
    verify(caldavPendingSubscriptionStorage, never()).abandoned(anyLong(), anyInt());
    verify(caldavPendingSubscriptionStorage).settled(CAROL, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE);
    verify(blueMindSubscriptionClient).asSharee(eq(carolEndpoint), eq(WRITER_UID), any());

    doThrow(new CalDavUnreachableException("down")).when(blueMindSubscriptionClient).asSharee(eq(bobEndpoint), eq(ERIC_UID), any());
    service.retryOwed(50);
    verify(caldavPendingSubscriptionStorage, times(2)).refused(1L);
  }

  /**
   * A session BlueMind authenticated as somebody else, credentials that are
   * not a login, a colleague no longer connected to that server, a recorded
   * principal that is not a BlueMind user's, and a login eXo cannot resolve
   * are final: every row of the colleague spends its budget, and the client
   * is not even called where the check is eXo's own.
   */
  @Test
  public void aSessionThatIsNotTheirsOrAColleagueNoLongerConnectedGivesUpEveryRow() {
    PendingSubscription bobOne = row(1L, BOB, CONTAINER, PendingSubscriptionKind.SUBSCRIBE, 0);
    PendingSubscription bobTwo = row(2L, BOB, OTHER, PendingSubscriptionKind.UNSUBSCRIBE, 0);
    when(caldavPendingSubscriptionStorage.attemptable(5, 50)).thenReturn(List.of(bobOne, bobTwo));
    doThrow(new BlueMindSubjectMismatchException("not eric")).when(blueMindSubscriptionClient).asSharee(eq(bobEndpoint), eq(ERIC_UID), any());
    service.retryOwed(50);
    verify(caldavPendingSubscriptionStorage).abandoned(1L, 5);
    verify(caldavPendingSubscriptionStorage).abandoned(2L, 5);

    doThrow(new UnsupportedOperationException("token")).when(blueMindSubscriptionClient).asSharee(eq(bobEndpoint), eq(ERIC_UID), any());
    service.retryOwed(50);
    verify(caldavPendingSubscriptionStorage, times(2)).abandoned(1L, 5);

    when(caldavConnectionIdentityService.principalOf(BOB, SERVER)).thenReturn(null);
    service.retryOwed(50);
    verify(caldavPendingSubscriptionStorage, times(3)).abandoned(1L, 5);

    when(caldavConnectionIdentityService.principalOf(BOB, SERVER)).thenReturn("/dav/pal/eric@stalwart.local");
    service.retryOwed(50);
    verify(caldavPendingSubscriptionStorage, times(4)).abandoned(1L, 5);

    when(identityManager.getIdentity(String.valueOf(BOB))).thenReturn(null);
    service.retryOwed(50);
    verify(caldavPendingSubscriptionStorage, times(5)).abandoned(1L, 5);
    verify(caldavPendingSubscriptionStorage, times(5)).abandoned(2L, 5);

    verify(blueMindSubscriptionClient, times(2)).asSharee(any(), anyString(), any());
    verify(caldavPendingSubscriptionStorage, never()).refused(anyLong());
    verify(caldavPendingSubscriptionStorage, never()).settled(anyLong(), anyLong(), anyString(), any());
  }

  /**
   * A session lost mid-way — the key refused, the server gone — counts the
   * row it failed on and leaves the rows after it untried for the next run,
   * rather than firing three more requests at a server that just went away.
   */
  @Test
  public void aSessionLostMidWayLeavesTheRemainingRowsUntried() {
    PendingSubscription first = row(1L, BOB, CONTAINER, PendingSubscriptionKind.SUBSCRIBE, 0);
    PendingSubscription second = row(2L, BOB, OTHER, PendingSubscriptionKind.SUBSCRIBE, 0);
    when(caldavPendingSubscriptionStorage.attemptable(5, 50)).thenReturn(List.of(first, second));
    doThrow(new CalDavUnreachableException("503")).when(edits).subscribe(CONTAINER);

    service.retryOwed(50);

    verify(caldavPendingSubscriptionStorage).refused(1L);
    verify(edits, never()).subscribe(OTHER);
    verify(caldavPendingSubscriptionStorage, never()).refused(2L);
    verify(caldavPendingSubscriptionStorage, never()).abandoned(eq(2L), anyInt());
    verify(caldavPendingSubscriptionStorage, never()).settled(eq(BOB), anyLong(), eq(OTHER), any());
  }

  /**
   * The colleague's own pass drains their rows and nobody else's, with the
   * bound the sweep uses; a table that cannot be read drains nothing and
   * throws nothing.
   */
  @Test
  public void theColleaguesOwnDrainReadsTheirRowsOnly() {
    when(caldavPendingSubscriptionStorage.attemptable(BOB, 5, 10)).thenReturn(List.of(row(1L, BOB, CONTAINER, PendingSubscriptionKind.SUBSCRIBE, 0)));

    assertEquals(1, service.retryOwed(BOB, 10));

    verify(caldavPendingSubscriptionStorage).attemptable(BOB, 5, 10);
    verify(caldavPendingSubscriptionStorage, never()).attemptable(anyInt(), anyInt());
    verify(edits).subscribe(CONTAINER);

    when(caldavPendingSubscriptionStorage.attemptable(BOB, 5, 10)).thenThrow(new IllegalStateException("db"));
    assertEquals(0, assertDoesNotThrow(() -> service.retryOwed(BOB, 10)));
    when(caldavPendingSubscriptionStorage.attemptable(5, 50)).thenThrow(new IllegalStateException("db"));
    assertEquals(0, assertDoesNotThrow(() -> service.retryOwed(50)));
  }

  /**
   * A failure that is not a server answer at all — eXo's own machinery giving
   * way on the road to BlueMind — is an obligation like any other, and the
   * two halves of this test are the two ways it used to disappear.
   *
   * <p>
   * At grant time an unclassified escape reached the seam's outer guard,
   * which logs "could not be recorded" and writes <b>no row</b>: the
   * colleague was owed a subscription that nothing would ever retry, and
   * nothing anywhere said so. At drain time it reached the per-colleague
   * guard, which counts nothing: the row kept its attempt count, and being
   * the oldest it came back at the head of the very next sweep — one login
   * as that colleague, with their stored password, every sweep period, for
   * ever, while the rest of the backlog waited behind it in the same batch.
   * Classified, it is a row at grant time and a spent attempt at drain time,
   * which is what the service's own Javadoc has always claimed.
   */
  @Test
  public void aFailureThatIsNotAServerAnswerIsStillRecordedAndStillCounted() {
    doThrow(new IllegalStateException("the credentials provider broke")).when(blueMindSubscriptionClient)
                                                                        .asSharee(eq(bobEndpoint), eq(ERIC_UID), any());

    assertDoesNotThrow(() -> service.subscribeSharee(share()));

    verify(caldavPendingSubscriptionStorage).owe(BOB, SERVER, CONTAINER, PendingSubscriptionKind.SUBSCRIBE);

    PendingSubscription first = row(1L, BOB, CONTAINER, PendingSubscriptionKind.SUBSCRIBE, 0);
    PendingSubscription second = row(2L, BOB, OTHER, PendingSubscriptionKind.SUBSCRIBE, 0);
    when(caldavPendingSubscriptionStorage.attemptable(5, 50)).thenReturn(List.of(first, second));

    assertEquals(0, assertDoesNotThrow(() -> service.retryOwed(50)));

    verify(caldavPendingSubscriptionStorage).refused(1L);
    verify(caldavPendingSubscriptionStorage).refused(2L);
    verify(caldavPendingSubscriptionStorage, never()).abandoned(anyLong(), anyInt());
    verify(caldavPendingSubscriptionStorage, never()).settled(anyLong(), anyLong(), anyString(), any());
  }

  /**
   * The same, thrown from inside the open session rather than at the door:
   * the edit the row asks for fails on something that is not a CalDAV answer,
   * and that row alone is counted while the colleague's next one is still
   * tried in the same session.
   */
  @Test
  public void aFailureInsideTheSessionCountsThatRowAndGoesOn() {
    PendingSubscription broken = row(1L, BOB, CONTAINER, PendingSubscriptionKind.SUBSCRIBE, 0);
    PendingSubscription fine = row(2L, BOB, OTHER, PendingSubscriptionKind.SUBSCRIBE, 0);
    when(caldavPendingSubscriptionStorage.attemptable(5, 50)).thenReturn(List.of(broken, fine));
    doThrow(new IllegalArgumentException("not a header value")).when(edits).subscribe(CONTAINER);

    assertEquals(1, service.retryOwed(50));

    verify(caldavPendingSubscriptionStorage).refused(1L);
    verify(edits).subscribe(OTHER);
    verify(caldavPendingSubscriptionStorage).settled(BOB, SERVER, OTHER, PendingSubscriptionKind.SUBSCRIBE);
    verify(caldavPendingSubscriptionStorage, never()).abandoned(anyLong(), anyInt());
  }

  /**
   * <b>The seam where the same call means two different things.</b> The drain
   * settles with the kind it posted, because the row may have been renewed
   * while its session was open; the grant and the revoke settle
   * unconditionally, because they decided the instruction in this very call,
   * inside the share's stripe lock and after the read-back, and nothing else
   * writes that table — so whatever row stands for the container is older than
   * what they just did.
   *
   * <p>
   * Getting that backwards is not a nicety. A grant whose subscribe failed
   * leaves a pending SUBSCRIBE; the owner then revokes and the unsubscribe
   * lands; a kind-guarded settle would refuse to delete the SUBSCRIBE, and the
   * next drain would post it — and BlueMind's subscribe makes no access check,
   * so it lands. The colleague ends subscribed to a calendar whose access
   * entry is gone, with no row, no retry and a success line in the log: the
   * dangling subscription this whole feature exists to prevent, arrived at
   * through the code that prevents it. So this test pins <em>which overload</em>
   * each caller uses, which is the only place that distinction is visible.
   */
  @Test
  public void theGrantAndTheRevokeClearWhateverWasOwedWhileTheDrainClearsOnlyWhatItPosted() {
    service.subscribeSharee(share());
    service.unsubscribeSharee(share());

    verify(caldavPendingSubscriptionStorage, times(2)).settled(BOB, SERVER, CONTAINER);
    verify(caldavPendingSubscriptionStorage, never()).settled(anyLong(), anyLong(), anyString(), any());

    when(caldavPendingSubscriptionStorage.attemptable(5, 50)).thenReturn(List.of(row(1L,
                                                                                     BOB,
                                                                                     OTHER,
                                                                                     PendingSubscriptionKind.UNSUBSCRIBE,
                                                                                     0)));

    service.retryOwed(50);

    verify(caldavPendingSubscriptionStorage).settled(BOB, SERVER, OTHER, PendingSubscriptionKind.UNSUBSCRIBE);
    verify(caldavPendingSubscriptionStorage, never()).settled(BOB, SERVER, OTHER);
  }

  /**
   * Nothing owed costs nothing: the client is not touched.
   */
  @Test
  public void nothingOwedCallsNoServer() {
    when(caldavPendingSubscriptionStorage.attemptable(5, 50)).thenReturn(List.of());

    assertEquals(0, service.retryOwed(50));

    verifyNoInteractions(blueMindSubscriptionClient, calDavClient);
  }

  /**
   * The client's session hands the edits to the job, as the real one does.
   */
  @SuppressWarnings("unchecked")
  private void sessionOpens() {
    lenient().when(blueMindSubscriptionClient.asSharee(any(), anyString(), any()))
             .thenAnswer(invocation -> ((Function<Subscriptions, Object>) invocation.getArgument(2)).apply(edits));
  }

  /**
   * Alice's share of her calendar with bob, as the grant hands it over.
   *
   * @return the share
   */
  private static ShareeSubscription share() {
    return new ShareeSubscription("alice", BOB, "bob", ERIC_UID, SERVER, CONTAINER);
  }

  /**
   * One owed row.
   *
   * @param id the row
   * @param user the sharee
   * @param container the container
   * @param kind the change
   * @param attempts refusals so far
   * @return the row
   */
  private static PendingSubscription row(long id, long user, String container, PendingSubscriptionKind kind, int attempts) {
    return new PendingSubscription(id, user, SERVER, container, kind, attempts, new Date());
  }

  /**
   * A social identity with a login.
   *
   * @param id the identity
   * @param login the login
   * @return the identity
   */
  private static Identity user(long id, String login) {
    Identity identity = new Identity(String.valueOf(id));
    identity.setRemoteId(login);
    return identity;
  }

  /**
   * @return the INFO lines the service logged
   */
  private List<String> infoLines() {
    return logged.list.stream().filter(event -> event.getLevel() == Level.INFO).map(ILoggingEvent::getFormattedMessage).toList();
  }

  /**
   * @return the WARN lines the service logged
   */
  private List<String> warnLines() {
    return logged.list.stream().filter(event -> event.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
  }

}
