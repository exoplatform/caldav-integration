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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.service.CaldavPushException;
import org.exoplatform.caldav.service.CaldavPushService;
import org.exoplatform.caldav.service.MirrorTarget;
import org.exoplatform.caldav.utils.CaldavConnectorUtils;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The write endpoints the browser calls now that it no longer builds
 * iCalendar itself.
 *
 * <p>
 * This layer moves bytes and maps failures onto statuses; every decision —
 * which identities are addressable, what the object looks like, whether the
 * write is conditional — lives in {@link CaldavPushService}. The failure
 * bodies carry the connector's own machine-readable codes unchanged, because
 * the browser stops building objects but keeps rendering the failures, and a
 * renamed code would degrade each of them into the generic message.
 */
@RestController
@Tag(name = "/caldav/rest/push", description = "Writes eXo events into the connected user's remote calendar")
public class CaldavPushRest {

  @Autowired
  private CaldavPushService caldavPushService;

  @Autowired
  private IdentityManager   identityManager;

  /**
   * Copies one agenda event into the connected user's mirror calendar.
   *
   * @param eventId the agenda event to copy
   * @param eventUrl absolute link back to the event in eXo
   * @return the resulting mapping
   */
  @PostMapping("/push/events/{eventId}")
  @Secured("users")
  @Operation(summary = "Copies an eXo event into the connected remote calendar",
      description = "Reads the event through agenda's own service, so its ACL applies, then writes it "
          + "server-side with the stored CalDAV credentials. The browser never sees them.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Event copied"),
      @ApiResponse(responseCode = "403", description = "Stored CalDAV credentials rejected upstream"),
      @ApiResponse(responseCode = "409", description = "No connected account, or the object changed concurrently"),
      @ApiResponse(responseCode = "502", description = "The calendar server refused or could not be reached") })
  public ObjectSync push(@Parameter(description = "Technical identifier of the agenda event", required = true)
                         @PathVariable("eventId")
                         long eventId,
                         @Parameter(description = "Absolute link back to the event in eXo")
                         @RequestParam(value = "eventUrl", required = false)
                         String eventUrl) {
    return caldavPushService.pushAgendaEvent(currentUser(), eventId, eventUrl);
  }

  /**
   * Removes one event's object from the mirror calendar.
   *
   * @param icsUid the iCalendar UID of the object to remove
   * @return an empty 204
   */
  @DeleteMapping("/push/events/{icsUid}")
  @Secured("users")
  @Operation(summary = "Removes a copied event from the remote calendar",
      description = "A deletion whose object is already gone succeeds: the end state the caller asked for holds.")
  @ApiResponses(value = { @ApiResponse(responseCode = "204", description = "Object removed, or already absent"),
      @ApiResponse(responseCode = "403", description = "Stored CalDAV credentials rejected upstream"),
      @ApiResponse(responseCode = "502", description = "The calendar server could not be reached") })
  public ResponseEntity<Void> delete(@Parameter(description = "iCalendar UID of the copied event", required = true)
                                     @PathVariable("icsUid")
                                     String icsUid) {
    caldavPushService.deleteEvent(currentUser(), icsUid);
    return ResponseEntity.noContent().build();
  }

  /**
   * Establishes where copies go, creating the collection when needed.
   *
   * @return the destination, and whether an existing calendar was adopted
   */
  @PostMapping("/push/mirror")
  @Secured("users")
  @Operation(summary = "Establishes the calendar copies are written into",
      description = "Creation is confirmed by reading the calendar home back, never by the MKCALENDAR status: "
          + "at least one server answers 201 while creating nothing.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Destination established"),
      @ApiResponse(responseCode = "403", description = "Stored CalDAV credentials rejected upstream"),
      @ApiResponse(responseCode = "502", description = "No destination could be established") })
  public MirrorTarget mirror() {
    return caldavPushService.ensureMirror(currentUser());
  }

  /**
   * Maps a push failure onto a status, keeping its code in the body.
   *
   * <p>
   * The codes are the contract: the browser matches on them to tell a user
   * whose password changed from one whose meeting was edited elsewhere. The
   * status is for anything that reads HTTP rather than our vocabulary.
   *
   * @param failure what went wrong
   * @return the response the browser receives
   */
  @ExceptionHandler(CaldavPushException.class)
  public ResponseEntity<String> onPushFailure(CaldavPushException failure) {
    HttpStatus status = switch (failure.getCode()) {
    case CaldavPushService.CREDENTIALS -> HttpStatus.FORBIDDEN;
    case CaldavPushService.CONFLICT, CaldavPushService.NOT_CONNECTED -> HttpStatus.CONFLICT;
    default -> HttpStatus.BAD_GATEWAY;
    };
    return ResponseEntity.status(status).body(failure.getCode());
  }

  /**
   * The identity of the authenticated caller, read from the conversation
   * state rather than from anything the request carries — a caller must never
   * be able to name whose calendar is written to.
   *
   * @return the caller's social identity id
   */
  private long currentUser() {
    return CaldavConnectorUtils.getCurrentUserIdentityId(identityManager);
  }
}
