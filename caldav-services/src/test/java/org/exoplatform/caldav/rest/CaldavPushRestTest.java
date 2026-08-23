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
package org.exoplatform.caldav.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.exoplatform.caldav.model.CalendarDeletionPlan;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.service.CaldavDeletionService;
import org.exoplatform.caldav.service.CaldavPushException;
import java.util.List;

import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.caldav.service.CaldavPushService;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.caldav.service.CaldavDeletionService;
import org.exoplatform.caldav.model.HiddenCalendar;
import org.exoplatform.caldav.service.MirrorTarget;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The write endpoints the browser calls now that it no longer holds the user's
 * CalDAV credentials.
 *
 * <p>
 * Two things are pinned here. Whose calendar gets written to is read from the
 * conversation state and from nothing the request carries — an endpoint that
 * took the identity from a path variable or a header would let any caller push
 * into someone else's account. And the failure codes survive the trip: the
 * browser stopped building iCalendar but kept rendering the failures, so it
 * still matches on {@code caldav.error.credentials} to tell a user whose
 * password changed from one whose meeting was edited elsewhere. A code lost or
 * renamed here degrades every one of those messages into the generic one, and
 * nothing in the response status would show it.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavPushRestTest {

  /** The authenticated caller these tests act as. */
  private static final String    USER_NAME   = "root";

  /** The social identity that user resolves to. */
  private static final String    IDENTITY_ID = "42";

  @Mock
  private CaldavPushService      caldavPushService;

  @Mock
  private CaldavDeletionService  caldavDeletionService;

  @Mock
  private IdentityManager        identityManager;

  @InjectMocks
  private CaldavPushRest         caldavPushRest;

  /**
   * Puts an authenticated caller in place, since every endpoint here resolves
   * the target account from the conversation state rather than the request.
   */
  @BeforeEach
  public void authenticate() {
    ConversationState.setCurrent(new ConversationState(new org.exoplatform.services.security.Identity(USER_NAME)));
  }

  /**
   * Clears the conversation state so it cannot leak into another test's idea of
   * who is calling.
   */
  @AfterEach
  public void clearAuthentication() {
    ConversationState.setCurrent(null);
  }

  /**
   * The endpoint hands the service the event id and the link back, and names
   * the caller's own identity as the account written to — the request carries
   * no way to name anybody else's.
   */
  @Test
  public void shouldPushForTheCallerRatherThanForAnyoneTheRequestNames() {
    withCurrentUser();
    ObjectSync mapping = new ObjectSync();
    when(caldavPushService.pushAgendaEvent(42L, 101L, "https://exo.test/event/101")).thenReturn(mapping);

    ResponseEntity<ObjectSync> pushed = caldavPushRest.push(101L, "https://exo.test/event/101");

    assertEquals(HttpStatus.OK, pushed.getStatusCode());
    assertSame(mapping, pushed.getBody());
    verify(caldavPushService).pushAgendaEvent(42L, 101L, "https://exo.test/event/101");
  }

  /**
   * An event whose calendar has no collection to copy into answers 204, not an
   * empty 200. Nothing failed and nothing happened, and the caller has to be
   * able to tell those apart — a 200 with no body reads as a copy that was
   * made and then lost on the way back.
   */
  @Test
  public void shouldAnswerNoContentWhenThereIsNowhereToCopyInto() {
    withCurrentUser();
    when(caldavPushService.pushAgendaEvent(42L, 102L, null)).thenReturn(null);

    ResponseEntity<ObjectSync> pushed = caldavPushRest.push(102L, null);

    assertEquals(HttpStatus.NO_CONTENT, pushed.getStatusCode());
    assertNull(pushed.getBody());
  }

  /**
   * A removal answers 204 with no body: the browser reads the status alone to
   * decide the copy is gone, and a 200 carrying something would have it render
   * a mapping for an object that no longer exists.
   */
  @Test
  public void shouldAnswerARemovalWithNoContent() {
    withCurrentUser();

    ResponseEntity<Void> response = caldavPushRest.delete("evt-1");

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(caldavPushService).deleteEvent(42L, "evt-1");
  }

  /**
   * The destination travels back whole, adoption flag included: the settings
   * drawer names the calendar genuinely receiving the copies, and dropping the
   * flag here would have it announce a collection the server never created.
   */
  @Test
  public void shouldHandTheEstablishedDestinationBackWhole() {
    withCurrentUser();
    MirrorTarget target = new MirrorTarget("/dav/calendars/root/personal/", true, "Personal");
    when(caldavPushService.ensureMirror(42L)).thenReturn(target);

    MirrorTarget answered = caldavPushRest.mirror();

    assertSame(target, answered);
  }

  /**
   * Rejected credentials are the one failure the user can act on alone, so they
   * get their own status: a 403 tells the drawer to ask for the password again,
   * where the generic 502 would only tell it to try later.
   */
  @Test
  public void shouldReportRejectedCredentialsAsForbidden() {
    assertEquals(HttpStatus.FORBIDDEN, statusOf(CaldavPushService.CREDENTIALS));
  }

  /**
   * A concurrent edit and a missing account are both states the caller must
   * resolve before pushing again, not transport failures, so both answer 409 —
   * mapping either onto a 502 would have the browser retry against a server
   * that is behaving perfectly.
   */
  @Test
  public void shouldReportStatesTheCallerMustResolveAsConflicts() {
    assertEquals(HttpStatus.CONFLICT, statusOf(CaldavPushService.CONFLICT));
    assertEquals(HttpStatus.CONFLICT, statusOf(CaldavPushService.NOT_CONNECTED));
  }

  /**
   * Anything else is the calendar server refusing or unreachable, and answers
   * 502 rather than a 500: the fault is upstream, and a 500 would send anyone
   * reading only the status looking through eXo's logs for it.
   */
  @Test
  public void shouldReportAnyOtherFailureAsABadGateway() {
    assertEquals(HttpStatus.BAD_GATEWAY, statusOf(CaldavPushService.SAVE));
    assertEquals(HttpStatus.BAD_GATEWAY, statusOf(CaldavPushService.CREATION_REFUSED));
  }

  /**
   * Whatever the status, the body is the code itself and nothing else: it is
   * what the browser matches on, so a body carrying a rendered message or a
   * wrapper object would leave every failure looking alike to it.
   */
  @Test
  public void shouldCarryTheMachineReadableCodeInTheBody() {
    ResponseEntity<String> response =
                                    caldavPushRest.onPushFailure(new CaldavPushException(CaldavPushService.CONFLICT,
                                                                                         "the object changed since it was read"));

    assertEquals(CaldavPushService.CONFLICT, response.getBody());
  }

  /**
   * Excluding one occurrence names the series by its iCalendar UID and the
   * instance by an instant, and answers with no body.
   */
  @Test
  public void shouldExcludeOneOccurrenceForTheCaller() {
    withCurrentUser();

    ResponseEntity<Void> response = caldavPushRest.excludeOccurrence("series-uid", "2026-09-15T07:00:00Z");

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(caldavPushService).excludeOccurrence(42L, "series-uid", java.time.Instant.parse("2026-09-15T07:00:00Z"));
  }

  /**
   * An instance identifier that is not an instant is refused, never guessed at.
   */
  @Test
  public void shouldRefuseAnOccurrenceThatIsNotAnInstant() {
    // Excluding the wrong occurrence cancels a meeting nobody meant to cancel,
    // and the object is rewritten in place — there is nothing to undo it with.
    org.springframework.web.server.ResponseStatusException refusal =
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                                                      () -> caldavPushRest.excludeOccurrence("series-uid", "next week"));

    assertEquals(HttpStatus.BAD_REQUEST, refusal.getStatusCode());
    verify(caldavPushService, org.mockito.Mockito.never()).excludeOccurrence(org.mockito.ArgumentMatchers.anyLong(),
                                                                             org.mockito.ArgumentMatchers.anyString(),
                                                                             org.mockito.ArgumentMatchers.any());
  }

  // The calendar-deletion endpoints. The dialog they answer stands between a
  // user and the one irreversible action in this connector, so what is pinned
  // here is that the plan reaches it whole and that a refusal reaches it at
  // all.

  /**
   * The plan is worked out for the caller's own account, and travels back
   * whole. The dialog renders one sentence or the other from
   * {@code propagates} and names the server from {@code server}; a field
   * dropped here would have it promise the wrong thing about an irreversible
   * deletion.
   */
  @Test
  public void shouldDescribeADeletionForTheCallerRatherThanForAnyoneTheRequestNames() {
    withCurrentUser();
    CalendarDeletionPlan plan = new CalendarDeletionPlan(true, true, "https://webmail.example.test/dav/");
    when(caldavDeletionService.describeDeletion(42L, 11L)).thenReturn(plan);

    CalendarDeletionPlan described = caldavPushRest.deletionPlan(11L);

    assertSame(plan, described);
    verify(caldavDeletionService).describeDeletion(42L, 11L);
  }

  /**
   * A calendar this connector does not mirror answers a plan claiming nothing,
   * not a 404: the dialog asks about every calendar agenda deletes, and a
   * failure status would break the deletion of calendars the connector has no
   * stake in.
   */
  @Test
  public void shouldAnswerAPlanClaimingNothingRatherThanAFailureForAnUnmirroredCalendar() {
    withCurrentUser();
    when(caldavDeletionService.describeDeletion(42L, 11L)).thenReturn(new CalendarDeletionPlan(false, false, null));

    CalendarDeletionPlan described = caldavPushRest.deletionPlan(11L);

    assertFalse(described.claimed());
    assertNull(described.server());
  }

  /**
   * Removing the remote collection answers 204 with no body: agenda deletes
   * the calendar itself once this returns, and reads the status alone to
   * decide it may.
   */
  @Test
  public void shouldAnswerARemoteCalendarDeletionWithNoContent() {
    withCurrentUser();

    ResponseEntity<Void> response = caldavPushRest.deleteRemoteCounterpart(11L);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(caldavDeletionService).deleteRemoteCounterpart(42L, 11L);
  }

  /**
   * A refusal is never turned into a 204. The rejection is what stops agenda
   * deleting locally, and swallowing it here would strand a collection on the
   * server after the record that knew about it is gone.
   */
  @Test
  public void shouldLetARefusedRemoteDeletionThroughRatherThanReportingSuccess() {
    withCurrentUser();
    org.mockito.Mockito.doThrow(new CaldavPushException(CaldavDeletionService.NOTHING_DELETED, "still listed"))
                       .when(caldavDeletionService)
                       .deleteRemoteCounterpart(42L, 11L);

    CaldavPushException refusal =
                                org.junit.jupiter.api.Assertions.assertThrows(CaldavPushException.class,
                                                                             () -> caldavPushRest.deleteRemoteCounterpart(11L));

    assertEquals(CaldavDeletionService.NOTHING_DELETED, refusal.getCode());
  }

  /**
   * A deletion that did not happen is the calendar server refusing, so it
   * answers 502 and carries its own code — the browser matches on it to say
   * "nothing was deleted, on either side" rather than the generic failure.
   */
  @Test
  public void shouldReportADeletionThatDidNotHappenAsABadGateway() {
    ResponseEntity<String> response =
                                    caldavPushRest.onPushFailure(new CaldavPushException(CaldavDeletionService.NOTHING_DELETED,
                                                                                         "still listed afterwards"));

    assertEquals(HttpStatus.BAD_GATEWAY, HttpStatus.valueOf(response.getStatusCode().value()));
    assertEquals(CaldavDeletionService.NOTHING_DELETED, response.getBody());
  }

  /**
   * Choosing to keep the remote calendar answers 204 too, and is a real call
   * rather than a no-op: without recording it, the next sweep materialises the
   * remote calendar straight back and undoes the deletion in front of the
   * user.
   */
  @Test
  public void shouldRecordAKeptRemoteCalendarForTheCaller() {
    withCurrentUser();

    ResponseEntity<Void> response = caldavPushRest.keepRemoteCounterpart(11L);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(caldavDeletionService).keepRemoteCounterpart(42L, 11L);
  }

  /**
   * Neither deletion endpoint deletes anything remotely of its own accord: the
   * one that keeps the collection must never reach the push service, or the
   * escape hatch from the atomic rule would destroy exactly what the user
   * asked to spare.
   */
  @Test
  public void shouldKeepTheDeletionEndpointsAwayFromThePushService() {
    withCurrentUser();

    caldavPushRest.keepRemoteCounterpart(11L);
    caldavPushRest.deletionPlan(11L);

    org.mockito.Mockito.verifyNoInteractions(caldavPushService);
  }

  /**
   * Makes the authenticated caller resolvable to a social identity.
   */
  private void withCurrentUser() {
    Identity identity = new Identity(OrganizationIdentityProvider.NAME, USER_NAME);
    identity.setId(IDENTITY_ID);
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, USER_NAME)).thenReturn(identity);
  }

  /**
   * The status a push failure carrying one code maps onto.
   *
   * @param code the machine-readable failure code
   * @return the status the endpoint answers with
   */
  private HttpStatus statusOf(String code) {
    return HttpStatus.valueOf(caldavPushRest.onPushFailure(new CaldavPushException(code, "failed")).getStatusCode().value());
  }

  /**
   * Un-hiding names the caller and their login, and answers 204.
   */
  @Test
  public void unHidingNamesTheCallerAndTheirLogin() {
    // The login is what lets the service delete and recreate calendars under
    // agenda's ACL. The binding id comes from the path; whose it is does not.
    withCurrentUser();

    ResponseEntity<Void> response = caldavPushRest.showCalendarAgain(9L);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    assertDoesNotThrow(() -> verify(caldavDeletionService).showAgain(42L, 9L, USER_NAME));
  }

  /**
   * A binding that is not this user's is a 403, never a silent success.
   */
  @Test
  public void aBindingThatIsNotYoursIsForbidden() {
    // The binding carries whose it is, and that is the only thing standing
    // between one user and another user's calendars.
    withCurrentUser();
    assertDoesNotThrow(() -> doThrow(new IllegalAccessException("not yours")).when(caldavDeletionService)
                                                                            .showAgain(anyLong(), anyLong(), anyString()));

    ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                                                   () -> caldavPushRest.showCalendarAgain(9L));

    assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
  }

  /**
   * A tombstone already lifted is a 404 and not an incident.
   */
  @Test
  public void aTombstoneAlreadyLiftedIsNotFound() {
    // A stale drawer offering something someone already brought back. Worth a
    // status, not worth a log at error level.
    withCurrentUser();
    assertDoesNotThrow(() -> doThrow(new ObjectNotFoundException("gone")).when(caldavDeletionService)
                                                                        .showAgain(anyLong(), anyLong(), anyString()));

    ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                                                   () -> caldavPushRest.showCalendarAgain(9L));

    assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode());
  }

  /**
   * The hidden calendars are handed through as the service listed them.
   */
  @Test
  public void theHiddenCalendarsAreHandedThrough() {
    withCurrentUser();
    List<HiddenCalendar> hidden = List.of(new HiddenCalendar(9L, "Family"));
    when(caldavDeletionService.listHidden(42L)).thenReturn(hidden);

    assertEquals(hidden, caldavPushRest.hiddenCalendars());
  }

  /**
   * No destination set answers 204 rather than an empty object.
   */
  @Test
  public void noDestinationAnswersNoContent() {
    // An empty body with a 200 would read as "there is one, and it has no
    // name", which is what the settings screen would then display.
    withCurrentUser();
    when(caldavPushService.currentMirror(42L)).thenReturn(null);

    assertEquals(HttpStatus.NO_CONTENT, caldavPushRest.currentMirror().getStatusCode());
  }

  /**
   * A destination is answered with its current name.
   */
  @Test
  public void aDestinationIsAnsweredWithItsName() {
    withCurrentUser();
    MirrorTarget mirror = new MirrorTarget("/dav/root/exo-meetings/", false, "eXo Meetings");
    when(caldavPushService.currentMirror(42L)).thenReturn(mirror);

    ResponseEntity<MirrorTarget> response = caldavPushRest.currentMirror();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(mirror, response.getBody());
  }

}
