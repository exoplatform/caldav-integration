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
package org.exoplatform.caldav.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.exoplatform.caldav.model.CalendarShares;
import org.exoplatform.caldav.model.CalendarShares.CalendarSharee;
import org.exoplatform.caldav.model.CalendarShares.PublishedLinkMode;
import org.exoplatform.caldav.model.CalendarShares.ShareAccess;
import org.exoplatform.caldav.model.CalendarShares.ShareUser;
import org.exoplatform.caldav.model.CalendarShares.ShareeKind;
import org.exoplatform.caldav.rest.model.ShareCalendarRequest;
import org.exoplatform.caldav.rest.model.ShareableCalendars;
import org.exoplatform.caldav.service.CaldavCalendarShareService;
import org.exoplatform.caldav.service.CaldavShareException;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The share endpoints' contract (EXO-90253): whose calendar is acted on comes
 * from the conversation state and from nothing the request carries, each
 * outcome of the service answers the status the REST norm assigns it, a
 * server refusal keeps what the server said in the body, and the JSON the
 * drawer reads is pinned by property name.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavShareRestTest {

  private static final String        USER_NAME   = "alice";

  private static final long          IDENTITY_ID = 5L;

  @Mock
  private CaldavCalendarShareService caldavCalendarShareService;

  @Mock
  private IdentityManager            identityManager;

  @InjectMocks
  private CaldavShareRest            caldavShareRest;

  private final JsonMapper           mapper      = JsonMapper.builder().build();

  /**
   * Alice is the authenticated caller.
   */
  @BeforeEach
  public void authenticate() {
    ConversationState.setCurrent(new ConversationState(new org.exoplatform.services.security.Identity(USER_NAME)));
    Identity identity = new Identity(OrganizationIdentityProvider.NAME, USER_NAME);
    identity.setId(String.valueOf(IDENTITY_ID));
    lenient().when(identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, USER_NAME)).thenReturn(identity);
  }

  /**
   * No caller leaks into another test.
   */
  @AfterEach
  public void clearAuthentication() {
    ConversationState.setCurrent(null);
  }

  /**
   * The caller is alice from the conversation state; the body names the
   * colleague alone, and the answer is the list the service read back.
   *
   * @throws Exception never
   */
  @Test
  public void sharingActsForTheCallerOnTheColleagueTheBodyNames() throws Exception {
    CalendarShares shares = new CalendarShares(12L, List.of());
    when(caldavCalendarShareService.grant(IDENTITY_ID, USER_NAME, 12L, "bob")).thenReturn(shares);

    assertSame(shares, caldavShareRest.share(12L, new ShareCalendarRequest("bob")));
  }

  /**
   * A missing body is a request naming nobody, which the service refuses —
   * not a server error.
   *
   * @throws Exception never
   */
  @Test
  public void aMissingBodyNamesNobody() throws Exception {
    when(caldavCalendarShareService.grant(IDENTITY_ID, USER_NAME, 12L, null))
                                                                               .thenThrow(new IllegalArgumentException(CaldavCalendarShareService.SHAREE_REQUIRED));

    ResponseStatusException refused = assertThrows(ResponseStatusException.class, () -> caldavShareRest.share(12L, null));

    assertEquals(HttpStatus.BAD_REQUEST, refused.getStatusCode());
    assertEquals(CaldavCalendarShareService.SHAREE_REQUIRED, refused.getReason());
  }

  /**
   * Unsharing, listing, candidates and the shareable list all act for the
   * caller.
   *
   * @throws Exception never
   */
  @Test
  public void everyEndpointActsForTheCaller() throws Exception {
    CalendarShares shares = new CalendarShares(12L, List.of());
    when(caldavCalendarShareService.revoke(IDENTITY_ID, USER_NAME, 12L, "bob")).thenReturn(shares);
    when(caldavCalendarShareService.listShares(IDENTITY_ID, USER_NAME, 12L)).thenReturn(shares);
    List<ShareUser> candidates = List.of(new ShareUser(9L, "bob", "Bob Test", null));
    when(caldavCalendarShareService.candidates(IDENTITY_ID, USER_NAME, 12L, "bo")).thenReturn(candidates);
    when(caldavCalendarShareService.shareableCalendarIds(IDENTITY_ID, USER_NAME)).thenReturn(List.of(12L, 14L));

    assertSame(shares, caldavShareRest.unshare(12L, "bob"));
    assertSame(shares, caldavShareRest.shares(12L));
    assertSame(candidates, caldavShareRest.candidates(12L, "bo"));
    assertEquals(List.of(12L, 14L), caldavShareRest.shareableCalendars().calendarIds());
  }

  /**
   * Existence, ownership and validation answer 404, 403 and 400, each with
   * its code as the reason.
   *
   * @throws Exception never
   */
  @Test
  public void theServiceOutcomesAnswerTheirStatuses() throws Exception {
    when(caldavCalendarShareService.listShares(IDENTITY_ID, USER_NAME, 99L))
                                                                           .thenThrow(new ObjectNotFoundException(CaldavCalendarShareService.CALENDAR_NOT_FOUND));
    when(caldavCalendarShareService.listShares(IDENTITY_ID, USER_NAME, 20L))
                                                                           .thenThrow(new IllegalAccessException(CaldavCalendarShareService.NOT_OWNER));
    when(caldavCalendarShareService.listShares(IDENTITY_ID, USER_NAME, 30L))
                                                                           .thenThrow(new IllegalArgumentException(CaldavCalendarShareService.CALENDAR_NOT_ON_SERVER));

    assertStatus(HttpStatus.NOT_FOUND, CaldavCalendarShareService.CALENDAR_NOT_FOUND, () -> caldavShareRest.shares(99L));
    assertStatus(HttpStatus.FORBIDDEN, CaldavCalendarShareService.NOT_OWNER, () -> caldavShareRest.shares(20L));
    assertStatus(HttpStatus.BAD_REQUEST, CaldavCalendarShareService.CALENDAR_NOT_ON_SERVER, () -> caldavShareRest.shares(30L));
  }

  /**
   * A failure of the account or the server answers 409 or 502 with its code,
   * and a refusal carries the server's preconditions and missing privileges.
   */
  @Test
  public void aServerFailureKeepsItsCodeAndWhatTheServerSaid() {
    ResponseEntity<Map<String, Object>> refused = caldavShareRest.onShareFailure(new CaldavShareException(CaldavCalendarShareService.SERVER_REFUSED,
                                                                                                          List.of("need-privileges"),
                                                                                                          List.of("write-acl"),
                                                                                                          null));
    assertEquals(HttpStatus.CONFLICT, refused.getStatusCode());
    assertEquals(CaldavCalendarShareService.SERVER_REFUSED, refused.getBody().get("message"));
    assertEquals(List.of("need-privileges"), refused.getBody().get("preconditions"));
    assertEquals(List.of("write-acl"), refused.getBody().get("missingPrivileges"));

    for (String code : List.of(CaldavCalendarShareService.NOT_CONNECTED,
                               CaldavCalendarShareService.OWNER_UNKNOWN,
                               CaldavCalendarShareService.FOREIGN_ACCESS_NOT_PRESERVED,
                               CaldavCalendarShareService.NOT_SUPPORTED,
                               CaldavCalendarShareService.ACL_UNREADABLE,
                               CaldavCalendarShareService.ACL_NOT_UNDERSTOOD,
                               CaldavCalendarShareService.CREDENTIALS)) {
      assertEquals(HttpStatus.CONFLICT, caldavShareRest.onShareFailure(new CaldavShareException(code)).getStatusCode(), code);
    }
    for (String code : List.of(CaldavCalendarShareService.NOT_APPLIED, CaldavCalendarShareService.SERVER_UNAVAILABLE)) {
      ResponseEntity<Map<String, Object>> gateway = caldavShareRest.onShareFailure(new CaldavShareException(code));
      assertEquals(HttpStatus.BAD_GATEWAY, gateway.getStatusCode(), code);
      assertEquals(502, gateway.getBody().get("status"));
    }
  }

  /**
   * The JSON the drawer reads: sharees with kind, users, name, access,
   * whether they can be removed and, for a link BlueMind published, its mode; whether a colleague must subscribe on the
   * server first, which decides the drawer's BlueMind note; whether the
   * calendar also holds the copies of the user's eXo meetings, which decides
   * the drawer's warning and confirmation; the shareable ids; a request body
   * with the login alone.
   *
   * @throws Exception never
   */
  @Test
  public void theWireShapeIsTheOneTheDrawerReads() throws Exception {
    CalendarShares shares = new CalendarShares(12L,
                                               List.of(new CalendarSharee("/dav/pal/bob%40stalwart.local/",
                                                                          ShareeKind.EXO_USERS,
                                                                          List.of(new ShareUser(9L, "bob", "Bob Test", "/avatar/bob")),
                                                                          null,
                                                                          ShareAccess.READ,
                                                                          true),
                                                       new CalendarSharee("/dav/pal/zoe%40partner.example/",
                                                                          ShareeKind.OUTSIDE_EXO,
                                                                          List.of(),
                                                                          "Zoé Partner",
                                                                          ShareAccess.MORE,
                                                                          false),
                                                       new CalendarSharee("published-link:private",
                                                                          ShareeKind.PUBLISHED_LINK,
                                                                          List.of(),
                                                                          null,
                                                                          ShareAccess.READ,
                                                                          false,
                                                                          PublishedLinkMode.PRIVATE)));

    JsonNode json = mapper.readTree(mapper.writeValueAsString(shares));

    assertEquals(12L, json.get("calendarId").asLong());
    assertTrue(json.has("subscriptionRequired"), "the drawer reads shares.subscriptionRequired");
    assertEquals(false, json.get("subscriptionRequired").asBoolean(), "a server where a grant is seen at once");
    assertEquals(true,
                 mapper.readTree(mapper.writeValueAsString(new CalendarShares(12L, List.of(), true))).get("subscriptionRequired").asBoolean(),
                 "BlueMind: the colleague must subscribe first");
    assertTrue(json.has("meetingCopies"), "the drawer reads shares.meetingCopies to warn and ask before sharing");
    assertEquals(false, json.get("meetingCopies").asBoolean(), "a calendar holding no meeting copies");
    assertEquals(true,
                 mapper.readTree(mapper.writeValueAsString(new CalendarShares(12L, List.of()).withMeetingCopies(true))).get("meetingCopies").asBoolean(),
                 "a calendar holding the meeting copies");
    JsonNode bob = json.get("sharees").get(0);
    assertEquals("/dav/pal/bob%40stalwart.local/", bob.get("principal").asText());
    assertEquals("EXO_USERS", bob.get("kind").asText());
    assertEquals("bob", bob.get("users").get(0).get("username").asText());
    assertEquals("Bob Test", bob.get("users").get(0).get("fullName").asText());
    assertEquals(9L, bob.get("users").get(0).get("identityId").asLong());
    assertEquals("/avatar/bob", bob.get("users").get(0).get("avatarUrl").asText());
    assertTrue(bob.get("displayName").isNull());
    assertEquals("READ", bob.get("access").asText());
    assertTrue(bob.get("removable").asBoolean());
    JsonNode zoe = json.get("sharees").get(1);
    assertEquals("OUTSIDE_EXO", zoe.get("kind").asText());
    assertEquals("Zoé Partner", zoe.get("displayName").asText());
    assertEquals("MORE", zoe.get("access").asText());
    assertTrue(zoe.get("publishedLink").isNull(), "only a published link has a mode");
    JsonNode link = json.get("sharees").get(2);
    assertEquals("PUBLISHED_LINK", link.get("kind").asText());
    assertEquals("PRIVATE", link.get("publishedLink").asText(), "the drawer names a published link by its mode");
    assertTrue(link.get("displayName").isNull());
    assertFalse(link.get("removable").asBoolean());

    assertEquals("[12,14]", mapper.readTree(mapper.writeValueAsString(new ShareableCalendars(List.of(12L, 14L)))).get("calendarIds").toString());
    assertEquals("bob", mapper.readValue("{\"username\":\"bob\"}", ShareCalendarRequest.class).username());
  }

  /**
   * Asserts a call is refused with a status and a reason.
   *
   * @param status the status expected
   * @param reason the reason expected
   * @param call the call
   */
  private static void assertStatus(HttpStatus status, String reason, org.junit.jupiter.api.function.Executable call) {
    ResponseStatusException refused = assertThrows(ResponseStatusException.class, call);
    assertEquals(status, refused.getStatusCode());
    assertEquals(reason, refused.getReason());
  }
}
