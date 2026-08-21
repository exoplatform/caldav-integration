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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.caldav.model.RemoteCalendar;
import org.exoplatform.caldav.model.RemoteIcsEvent;
import org.exoplatform.caldav.service.CaldavReadService;
import org.exoplatform.caldav.service.CaldavSyncService;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The read endpoints the page calls instead of speaking CalDAV itself.
 *
 * <p>
 * Two things are pinned. Whose calendars are read comes from the conversation
 * state and from nothing the request carries — an endpoint taking the identity
 * from a parameter would let any caller read someone else's calendar. And a
 * period that does not name two instants is refused rather than defaulted: a
 * window nobody asked for answers with the wrong events, which a user reads as
 * missing meetings rather than as a failed request.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavReadRestTest {

  /** The authenticated caller these tests act as. */
  private static final String    USER_NAME   = "root";

  /** The social identity that user resolves to. */
  private static final String    IDENTITY_ID = "42";

  @Mock
  private CaldavReadService      caldavReadService;

  @Mock
  private CaldavSyncService      caldavSyncService;

  @Mock
  private IdentityManager        identityManager;

  @InjectMocks
  private CaldavReadRest         caldavReadRest;

  /**
   * Puts an authenticated caller in place, since both endpoints resolve the
   * account from the conversation state rather than from the request.
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
   * The period is handed over as instants, for the caller's own account.
   */
  @Test
  public void shouldReadTheCallerOwnCalendarsOverTheGivenPeriod() {
    withCurrentUser();
    RemoteIcsEvent event = new RemoteIcsEvent();
    when(caldavReadService.readEvents(42L,
                                      Instant.parse("2026-10-01T00:00:00Z"),
                                      Instant.parse("2026-11-30T00:00:00Z"))).thenReturn(List.of(event));

    List<RemoteIcsEvent> events = caldavReadRest.events("2026-10-01T00:00:00Z", "2026-11-30T00:00:00Z");

    assertEquals(1, events.size());
    assertSame(event, events.get(0));
  }

  /**
   * A start that is not an instant is a bad request, not a default.
   */
  /**
   * A refused period costs nothing: the platform does not talk to a calendar
   * server for a request it is about to reject.
   */
  @Test
  public void shouldNotSynchroniseForARequestItIsAboutToRefuse() {
    assertThrows(ResponseStatusException.class, () -> caldavReadRest.events("last tuesday", "2026-11-30T00:00:00Z"));

    verify(caldavSyncService, never()).syncIfDue(anyLongValue(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  public void shouldRefuseAPeriodWhoseStartIsNotAnInstant() {
    ResponseStatusException refusal = assertThrows(ResponseStatusException.class,
                                                   () -> caldavReadRest.events("last tuesday",
                                                                               "2026-11-30T00:00:00Z"));

    assertEquals(HttpStatus.BAD_REQUEST, refusal.getStatusCode());
    // The code names which bound was wrong, so the page can say so.
    assertEquals(true, refusal.getReason().endsWith("start"));
    // Never asked for: answering over a window nobody named would look like
    // missing meetings rather than like a refused request.
    verify(caldavReadService, never()).readEvents(anyLongValue(), any(), any());
  }

  /**
   * And an end that is not an instant is refused the same way, named as the end.
   */
  @Test
  public void shouldRefuseAPeriodWhoseEndIsNotAnInstant() {
    ResponseStatusException refusal = assertThrows(ResponseStatusException.class,
                                                   () -> caldavReadRest.events("2026-10-01T00:00:00Z", "soon"));

    assertEquals(HttpStatus.BAD_REQUEST, refusal.getStatusCode());
    assertEquals(true, refusal.getReason().endsWith("end"));
  }

  /**
   * A missing bound is refused rather than treated as an open window.
   */
  @Test
  public void shouldRefuseAnAbsentBound() {
    assertThrows(ResponseStatusException.class, () -> caldavReadRest.events(null, "2026-11-30T00:00:00Z"));
  }

  /**
   * Opening the agenda is what triggers a synchronisation, and the throttled
   * form is the only one it may use: a page load must not become a reason to
   * talk to a calendar server every time.
   */
  @Test
  public void shouldSynchroniseTheCallerAccountWhenTheAgendaIsOpened() {
    withCurrentUser();
    when(caldavReadService.readEvents(42L,
                                      Instant.parse("2026-10-01T00:00:00Z"),
                                      Instant.parse("2026-11-30T00:00:00Z"))).thenReturn(List.of());

    caldavReadRest.events("2026-10-01T00:00:00Z", "2026-11-30T00:00:00Z");

    verify(caldavSyncService).syncIfDue(42L, USER_NAME);
  }

  /**
   * The button bypasses the throttle: a user pressing it has a reason the
   * throttle cannot know — they just changed something on another device.
   * Answering them with a stale agenda because a page load happened to run a
   * sync a minute ago is exactly what the button is for.
   */
  @Test
  public void shouldSynchroniseNowWhateverTheThrottleSays() {
    withCurrentUser();

    assertEquals(HttpStatus.NO_CONTENT, caldavReadRest.syncNow().getStatusCode());

    verify(caldavSyncService).syncNow(42L, USER_NAME);
    verify(caldavSyncService, never()).syncIfDue(anyLongValue(), org.mockito.ArgumentMatchers.anyString());
  }

  /**
   * The calendars come back for the caller's own account.
   */
  @Test
  public void shouldListTheCallerOwnCalendars() {
    withCurrentUser();
    RemoteCalendar calendar = new RemoteCalendar("/dav/calendars/john/work/", "Work", "#112233", false);
    when(caldavReadService.listCalendars(42L)).thenReturn(List.of(calendar));

    List<RemoteCalendar> calendars = caldavReadRest.calendars();

    assertSame(calendar, calendars.get(0));
    verify(caldavReadService).listCalendars(42L);
  }

  /**
   * Resolves the caller to the identity the endpoints read the account from.
   */
  private void withCurrentUser() {
    Identity identity = new Identity(OrganizationIdentityProvider.NAME, USER_NAME);
    identity.setId(IDENTITY_ID);
    when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, USER_NAME)).thenReturn(identity);
  }

  /**
   * A long matcher, kept out of the assertions so they read as statements.
   *
   * @return any long
   */
  private long anyLongValue() {
    return org.mockito.ArgumentMatchers.anyLong();
  }

  /**
   * An any-matcher for instants.
   *
   * @return any instant
   */
  private Instant any() {
    return org.mockito.ArgumentMatchers.any();
  }
}
