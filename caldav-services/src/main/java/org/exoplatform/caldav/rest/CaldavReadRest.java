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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.annotation.Secured;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.exoplatform.caldav.model.RemoteCalendar;
import org.exoplatform.caldav.model.RemoteIcsEvent;
import org.exoplatform.caldav.service.CaldavReadService;
import org.exoplatform.caldav.service.CaldavSyncService;
import org.exoplatform.caldav.utils.CaldavConnectorUtils;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The read endpoints the page calls instead of downloading and parsing every
 * iCalendar object itself.
 *
 * <p>
 * One request replaces what used to be one REPORT per calendar issued from the
 * browser, followed by parsing every object in the main thread with the user's
 * password in the page.
 */
@RestController
@Tag(name = "/caldav/rest/events", description = "Reads the connected user's remote calendars")
public class CaldavReadRest {

  @Autowired
  private CaldavReadService caldavReadService;

  @Autowired
  private CaldavSyncService caldavSyncService;

  @Autowired
  private IdentityManager   identityManager;

  /**
   * The connected account's events over a window.
   *
   * @param start beginning of the window, an ISO instant
   * @param end end of the window, an ISO instant
   * @return the occurrences, each tagged with its calendar and colour
   */
  @GetMapping("/events")
  @Secured("users")
  @Operation(summary = "Reads the connected account's events over a period",
      description = "One REPORT per calendar, bounded by the account's calendar count. A calendar that fails "
          + "contributes no events rather than blanking the whole answer.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "The events of the period"),
      @ApiResponse(responseCode = "400", description = "The period is missing or not a pair of instants") })
  public List<RemoteIcsEvent> events(@Parameter(description = "Beginning of the period, ISO instant", required = true)
                                     @RequestParam("start")
                                     String start,
                                     @Parameter(description = "End of the period, ISO instant", required = true)
                                     @RequestParam("end")
                                     String end) {
    // The period is checked before anything else happens: a request that is
    // about to be refused should not first make the platform talk to a
    // calendar server.
    Instant from = instantOf(start, "start");
    Instant to = instantOf(end, "end");
    // Opening the agenda is the trigger. Throttled, so three page loads in a
    // minute are three page loads and not three reasons to talk to a calendar
    // server — and it never throws, so a server being down cannot stop an
    // agenda rendering the events it already has.
    caldavSyncService.syncIfDue(currentUser(), CaldavConnectorUtils.getCurrentUser());
    return caldavReadService.readEvents(currentUser(), from, to);
  }

  /**
   * Synchronises the connected account now, whatever the throttle says.
   *
   * @return an empty 204
   */
  @PostMapping("/sync")
  @Secured("users")
  @Operation(summary = "Synchronises the connected CalDAV account now",
      description = "Bypasses the throttle: a user pressing this has a reason the throttle cannot know — they just "
          + "changed something on another device and want to see it.")
  @ApiResponses(value = { @ApiResponse(responseCode = "204", description = "Synchronisation ran") })
  public ResponseEntity<Void> syncNow() {
    caldavSyncService.syncNow(currentUser(), CaldavConnectorUtils.getCurrentUser());
    return ResponseEntity.noContent().build();
  }

  /**
   * When the connected account last finished synchronising.
   *
   * <p>
   * Its own endpoint rather than a field on the settings: the settings are
   * read on every page that shows a connector, and this walks the bindings.
   * Only the screen that displays the state should pay for it.
   *
   * @return the instant in milliseconds, or 204 when nothing has synchronised
   *         yet
   */
  @GetMapping("/sync/state")
  @Secured("users")
  @Operation(summary = "When the connected CalDAV account last finished synchronising",
      description = "Read from the stored bindings, so it survives a restart — unlike the in-memory throttle, which "
          + "would report a fresh account after every reboot.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "The instant, in epoch milliseconds"),
      @ApiResponse(responseCode = "204", description = "Nothing has ever synchronised") })
  public ResponseEntity<Long> lastSync() {
    Date lastSyncEnd = caldavSyncService.lastSyncEnd(currentUser());
    return lastSyncEnd == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(lastSyncEnd.getTime());
  }

  /**
   * The calendars of the connected account.
   *
   * @return the calendars, each with a usable colour
   */
  @GetMapping("/calendars")
  @Secured("users")
  @Operation(summary = "Lists the connected account's calendars",
      description = "The identity of a calendar is its collection href, never its display name: a user renaming "
          + "a calendar in their own client must not detach what eXo associated with it.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "The account's calendars") })
  public List<RemoteCalendar> calendars() {
    return caldavReadService.listCalendars(currentUser());
  }

  /**
   * One end of the window, refused rather than guessed at when it is not an
   * instant.
   *
   * <p>
   * A defaulted period would answer with events from a window nobody asked
   * for, which reads as missing meetings rather than as a bad request.
   *
   * @param value the parameter as received
   * @param name which parameter it is, for the message
   * @return the instant
   */
  private Instant instantOf(String value, String name) {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException | NullPointerException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "caldav.error.period." + name);
    }
  }

  /**
   * The identity of the authenticated caller, read from the conversation state
   * rather than from anything the request carries.
   *
   * @return the caller's social identity id
   */
  private long currentUser() {
    return CaldavConnectorUtils.getCurrentUserIdentityId(identityManager);
  }
}
