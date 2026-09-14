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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.exoplatform.caldav.model.CalendarShares;
import org.exoplatform.caldav.model.CalendarShares.ShareUser;
import org.exoplatform.caldav.rest.model.ShareCalendarRequest;
import org.exoplatform.caldav.rest.model.ShareableCalendars;
import org.exoplatform.caldav.service.CaldavCalendarShareService;
import org.exoplatform.caldav.service.CaldavShareException;
import org.exoplatform.caldav.utils.CaldavConnectorUtils;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Sharing a user's own eXo calendar, read-only, with colleagues connected to
 * the same CalDAV server (EXO-90253).
 *
 * <p>
 * The caller is read from the conversation state, never from the request, and
 * the request names only an agenda calendar id and a colleague's login: the
 * collection, the principals and the access list are all resolved and checked
 * by {@link CaldavCalendarShareService}. This layer maps the outcome onto a
 * status and nothing else: 404 for a calendar that does not exist, 403 for one
 * the caller does not own, 400 with the message code for a request that cannot
 * be honoured, and for a failure of the account or the server the code with
 * whatever the server said (409 for a state of the account or the server, 502
 * when the server could not be reached or did not apply what it accepted).
 */
@RestController
@Tag(name = "/caldav/rest/calendars/{calendarId}/shares",
    description = "Shares the connected user's own calendars read-only on their CalDAV server")
public class CaldavShareRest {

  private static final Log           LOG = ExoLogger.getLogger(CaldavShareRest.class);

  @Autowired
  private CaldavCalendarShareService caldavCalendarShareService;

  @Autowired
  private IdentityManager            identityManager;

  /**
   * The caller's calendars agenda may offer "Share" on.
   *
   * @return the calendar ids, empty whenever anything stands in the way
   */
  @GetMapping("/calendars/shareable")
  @Secured("users")
  @Operation(summary = "Lists the connected user's calendars that can be shared from eXo",
      description = "A calendar is listed when the user owns it, eXo created a collection for it on the user's CalDAV "
          + "server, and that server offers a way to grant access whose every change eXo confirms by reading it back (Stalwart's RFC 3744 ACL "
          + "method; BlueMind's CS:share, confirmed through BlueMind's REST access list, when the account's credentials are a "
          + "login and password). Never fails: no account, an unreachable server or any other obstacle answers an empty list.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "The shareable calendar ids, possibly none") })
  public ShareableCalendars shareableCalendars() {
    return new ShareableCalendars(caldavCalendarShareService.shareableCalendarIds(currentUser(), currentLogin()));
  }

  /**
   * Who a calendar is shared with, read from the server now.
   *
   * @param calendarId the agenda calendar
   * @return the sharees
   */
  @GetMapping("/calendars/{calendarId}/shares")
  @Secured("users")
  @Operation(summary = "Lists who a calendar of the user's is shared with",
      description = "Read from the server's access list on every call — over DAV on a server using RFC 3744 ACLs, "
          + "through BlueMind's REST API on BlueMind; nothing is stored. Each sharee is a principal: "
          + "`EXO_USERS` with the eXo users connected as it, `OUTSIDE_EXO` named by the server, or `EVERYONE`. "
          + "`access` is `READ` for a view-only grant and `MORE` for one made outside eXo; `removable` says whether "
          + "eXo may take it away. `subscriptionRequired` is true where a colleague sees a shared calendar only after "
          + "subscribing to it on the server itself, as on BlueMind.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "The sharees"),
      @ApiResponse(responseCode = "400", description = "The calendar has no collection eXo created; body message is the code"),
      @ApiResponse(responseCode = "403", description = "Not the user's calendar"),
      @ApiResponse(responseCode = "404", description = "No such calendar"),
      @ApiResponse(responseCode = "409", description = "No account, sharing not offered on this server, or the access "
          + "list cannot be read or written back"),
      @ApiResponse(responseCode = "502", description = "The server could not be reached") })
  public CalendarShares shares(@Parameter(description = "Agenda calendar id", required = true)
                               @PathVariable("calendarId")
                               long calendarId) {
    return answer(calendarId, () -> caldavCalendarShareService.listShares(currentUser(), currentLogin(), calendarId));
  }

  /**
   * Gives a colleague read access to a calendar.
   *
   * @param calendarId the agenda calendar
   * @param request the colleague's login
   * @return the sharees after the grant, as read back
   */
  @PostMapping("/calendars/{calendarId}/shares")
  @Secured("users")
  @Operation(summary = "Shares a calendar of the user's read-only with a colleague",
      description = "The colleague must be an eXo user connected to the same CalDAV server under another login. On a "
          + "server using RFC 3744 ACLs, the access list is read, the grant added beside every existing entry, written "
          + "back, and read again. On BlueMind, a CS:share naming the colleague's own BlueMind address is posted, and "
          + "the access list is read back through BlueMind's REST API. Either way, the answer is the list as read after "
          + "the change, and a grant it does not hold is reported as not applied. On a server using RFC 3744 ACLs, "
          + "sharing with a colleague who can already read it changes nothing and succeeds. On BlueMind only a "
          + "colleague holding plain view access does; one holding more is refused (caldav.share.notReadOnly), and one "
          + "holding only other access given outside eXo too (caldav.share.shareeHasOtherAccess).")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Shared; the sharees as read back"),
      @ApiResponse(responseCode = "400", description = "No colleague named, unknown, the user themself, not connected to "
          + "this server, on the user's own login, already holding more than view access given outside eXo "
          + "(caldav.share.notReadOnly), a colleague holding other access given outside eXo (caldav.share.shareeHasOtherAccess), or a calendar with no collection eXo created"),
      @ApiResponse(responseCode = "403", description = "Not the user's calendar"),
      @ApiResponse(responseCode = "404", description = "No such calendar"),
      @ApiResponse(responseCode = "409", description = "No account, not offered on this server, the access list "
          + "unusable, the user's server principal unknown (caldav.share.ownerUnknown), another person holding access "
          + "that writing the list back could change (caldav.share.foreignAccessNotPreserved), the colleague's mail address not "
          + "published by the server (caldav.share.shareeAddressUnknown), or the server refused "
          + "(with its preconditions and missing privileges)"),
      @ApiResponse(responseCode = "502", description = "The server could not be reached, or accepted the grant and "
          + "does not hold it when read back") })
  public CalendarShares share(@Parameter(description = "Agenda calendar id", required = true)
                              @PathVariable("calendarId")
                              long calendarId,
                              @RequestBody(required = false)
                              ShareCalendarRequest request) {
    String username = request == null ? null : request.username();
    return answer(calendarId, () -> caldavCalendarShareService.grant(currentUser(), currentLogin(), calendarId, username));
  }

  /**
   * Takes a colleague's read access to a calendar away.
   *
   * @param calendarId the agenda calendar
   * @param username the colleague's login
   * @return the sharees after the revoke, as read back
   */
  @DeleteMapping("/calendars/{calendarId}/shares/{username}")
  @Secured("users")
  @Operation(summary = "Stops sharing a calendar of the user's with a colleague",
      description = "Removes the colleague's view-only grants and nothing else. On a server using RFC 3744 ACLs the "
          + "access list is read, the grant removed, written back and read again; on BlueMind a CS:share remove naming "
          + "the colleague's own BlueMind address is posted, and confirmed when the colleague is gone from the access "
          + "list read through BlueMind's REST API. A colleague holding more, granted outside eXo, is refused rather "
          + "than partly removed. A colleague with no grant changes nothing and succeeds.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Removed; the sharees as read back"),
      @ApiResponse(responseCode = "400", description = "Unknown colleague, not connected, the user themself, holding more "
          + "than view access, or a calendar with no collection eXo created"),
      @ApiResponse(responseCode = "403", description = "Not the user's calendar"),
      @ApiResponse(responseCode = "404", description = "No such calendar"),
      @ApiResponse(responseCode = "409", description = "No account, not offered, access list unusable, another person "
          + "holding access that writing the list back could change (caldav.share.foreignAccessNotPreserved), the "
          + "colleague's mail address not published by the server (caldav.share.shareeAddressUnknown), or refused"),
      @ApiResponse(responseCode = "502", description = "Unreachable, or not applied when read back") })
  public CalendarShares unshare(@Parameter(description = "Agenda calendar id", required = true)
                                @PathVariable("calendarId")
                                long calendarId,
                                @Parameter(description = "The colleague's eXo login", required = true)
                                @PathVariable("username")
                                String username) {
    return answer(calendarId, () -> caldavCalendarShareService.revoke(currentUser(), currentLogin(), calendarId, username));
  }

  /**
   * The colleagues a calendar can be shared with.
   *
   * @param calendarId the agenda calendar
   * @param query text the login or full name must contain
   * @return the candidates
   */
  @GetMapping("/calendars/{calendarId}/share-candidates")
  @Secured("users")
  @Operation(summary = "Lists the colleagues a calendar of the user's can be shared with",
      description = "eXo users connected to the same CalDAV server under another login, by full name. The server is asked "
          + "what the collection advertises (an OPTIONS, and a PROPFIND where the OPTIONS names no DAV classes) to confirm it offers sharing; the colleagues are read from eXo's own record of each connection, and "
          + "the server is asked who the user is only when that is not recorded yet.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "The candidates, possibly none"),
      @ApiResponse(responseCode = "400", description = "A calendar with no collection eXo created"),
      @ApiResponse(responseCode = "403", description = "Not the user's calendar"),
      @ApiResponse(responseCode = "404", description = "No such calendar"),
      @ApiResponse(responseCode = "409", description = "No connected account, sharing not offered on this server "
          + "(caldav.share.notSupported), the stored credentials refused (caldav.share.credentials), or the user's server "
          + "principal unknown (caldav.share.ownerUnknown)"),
      @ApiResponse(responseCode = "502", description = "The server could not be reached (caldav.share.serverUnavailable)") })
  public List<ShareUser> candidates(@Parameter(description = "Agenda calendar id", required = true)
                                    @PathVariable("calendarId")
                                    long calendarId,
                                    @Parameter(description = "Text the login or full name contains")
                                    @RequestParam(name = "q", required = false)
                                    String query) {
    return answer(calendarId, () -> caldavCalendarShareService.candidates(currentUser(), currentLogin(), calendarId, query));
  }

  /**
   * Maps a failure of the account or the server onto a status, keeping its
   * code, and what the server said, in the body.
   *
   * @param failure what went wrong
   * @return the response the browser receives
   */
  @ExceptionHandler(CaldavShareException.class)
  public ResponseEntity<Map<String, Object>> onShareFailure(CaldavShareException failure) {
    HttpStatus status = switch (failure.getCode()) {
    case CaldavCalendarShareService.SERVER_UNAVAILABLE, CaldavCalendarShareService.NOT_APPLIED -> HttpStatus.BAD_GATEWAY;
    default -> HttpStatus.CONFLICT;
    };
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", status.value());
    body.put("message", failure.getCode());
    body.put("preconditions", failure.getPreconditions());
    body.put("missingPrivileges", failure.getMissingPrivileges());
    return ResponseEntity.status(status).body(body);
  }

  /**
   * Runs a service call, turning the three checked outcomes of the REST
   * contract into their statuses. None is an incident: logged at debug.
   *
   * @param <T> the answer
   * @param calendarId the calendar, for the log
   * @param call the service call
   * @return its answer
   */
  private <T> T answer(long calendarId, ShareCall<T> call) {
    try {
      return call.call();
    } catch (ObjectNotFoundException e) {
      LOG.debug("User {} asked about calendar {}, which does not exist", currentLogin(), calendarId);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      LOG.debug("User {} may not share calendar {}", currentLogin(), calendarId);
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (IllegalArgumentException e) {
      LOG.debug("User {} asked something about calendar {} that cannot be done: {}", currentLogin(), calendarId, e.getMessage());
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * The identity of the authenticated caller.
   *
   * @return the caller's social identity id
   */
  private long currentUser() {
    return CaldavConnectorUtils.getCurrentUserIdentityId(identityManager);
  }

  /**
   * The login of the authenticated caller.
   *
   * @return the caller's eXo login
   */
  private String currentLogin() {
    return CaldavConnectorUtils.getCurrentUser();
  }

  /**
   * A service call declaring the checked outcomes of the REST contract.
   *
   * @param <T> the answer
   */
  @FunctionalInterface
  private interface ShareCall<T> {

    /**
     * Makes the call.
     *
     * @return its answer
     * @throws ObjectNotFoundException when the calendar does not exist
     * @throws IllegalAccessException when the caller does not own it
     */
    T call() throws ObjectNotFoundException, IllegalAccessException;
  }
}
