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

import org.exoplatform.services.log.Log;
import org.exoplatform.services.log.ExoLogger;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

import org.exoplatform.caldav.model.CalendarDeletionPlan;
import org.exoplatform.caldav.model.CalendarSyncState;
import org.exoplatform.caldav.model.HiddenCalendar;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.service.CaldavPushException;
import org.exoplatform.caldav.service.CaldavDeletionService;
import org.exoplatform.caldav.service.CaldavEventPropagationService;
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
@Tag(name = "/caldav/rest/push",
     description = "Writes eXo events into the connected user's remote calendar. Two vocabularies, kept apart in "
         + "the paths on purpose: /push/events takes an eXo event identifier, /push/objects takes the iCalendar "
         + "UID of the calendar object that event was written as.")
public class CaldavPushRest {

  private static final Log LOG = ExoLogger.getLogger(CaldavPushRest.class);


  @Autowired
  private CaldavPushService     caldavPushService;

  @Autowired
  private CaldavDeletionService caldavDeletionService;

  @Autowired
  private CaldavEventPropagationService caldavEventPropagationService;

  @Autowired
  private IdentityManager   identityManager;

  /**
   * Copies one agenda event into the connected user's mirror calendar.
   *
   * <p>
   * The request carries no link back into eXo. It used to, built by the page
   * and passed as a query parameter, and that is exactly why the copy kept
   * losing it: a value only the browser supplied was absent on every sweep and
   * every repair. The link is derived from the event server-side now, so the
   * page has nothing to say about it (EXO-89751).
   *
   * @param eventId the agenda event to copy
   * @return the resulting mapping, or an empty 204 when the event's calendar
   *         has no collection to copy into
   */
  @PostMapping("/push/events/{eventId}")
  @Secured("users")
  @Operation(summary = "Copies an eXo event into the connected remote calendar",
      description = "Reads the event through agenda's own service, so its ACL applies, then writes it "
          + "server-side with the stored CalDAV credentials. The browser never sees them.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Event copied"),
      @ApiResponse(responseCode = "204", description = "The event's calendar has no collection to copy into"),
      @ApiResponse(responseCode = "403", description = "Stored CalDAV credentials rejected upstream"),
      @ApiResponse(responseCode = "409", description = "No connected account, or the object changed concurrently"),
      @ApiResponse(responseCode = "502", description = "The calendar server refused or could not be reached") })
  public ResponseEntity<ObjectSync> push(@Parameter(description = "Technical identifier of the agenda event",
                                                    required = true)
                                         @PathVariable("eventId")
                                         long eventId) {
    ObjectSync written = caldavPushService.pushAgendaEvent(currentUser(), eventId);
    // 204, not an empty 200: the caller asked for a copy and there is no
    // collection to make one in. Nothing failed, and nothing happened, and
    // those are different answers.
    return written == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(written);
  }

  /**
   * How many calendar copies eXo still owes this user and has not written.
   *
   * <p>
   * A number, not a list: what the user needs is to be able to tell "eXo is
   * still working on it" from "eXo thinks it is done", and naming the meetings
   * would cost a page of them and answer a question nobody asked.
   *
   * @return the count, zero on an account whose copies have all landed
   */
  @GetMapping("/push/owed")
  @Secured("users")
  @Operation(summary = "How many calendar copies eXo has not managed to write yet",
      description = "Counts only the writes eXo is still attempting. A copy it has given up on, and a meeting "
          + "whose first copy was never written at all, are not in this number — see the service for why.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "The count") })
  public long owedCopies() {
    return caldavEventPropagationService.owedCopies(currentUser());
  }

  /**
   * Removes one event's object from the mirror calendar.
   *
   * @param icsUid the iCalendar UID of the object to remove
   * @return an empty 204
   */
  @DeleteMapping("/push/objects/{icsUid}")
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
   * Removes one occurrence from a series, leaving the series in place.
   *
   * @param icsUid the iCalendar UID of the series
   * @param occurrence the instance to exclude, an ISO instant
   * @return an empty 204
   */
  @DeleteMapping("/push/objects/{icsUid}/occurrences/{occurrence}")
  @Secured("users")
  @Operation(summary = "Removes one occurrence of a series",
      description = "A rewrite, not a deletion: every component of a series lives in one calendar object, so "
          + "deleting the object would cancel every meeting of the series to cancel one.")
  @ApiResponses(value = { @ApiResponse(responseCode = "204", description = "Occurrence excluded"),
      @ApiResponse(responseCode = "403", description = "Stored CalDAV credentials rejected upstream"),
      @ApiResponse(responseCode = "409", description = "The series changed since it was read"),
      @ApiResponse(responseCode = "502", description = "The calendar server could not be reached") })
  public ResponseEntity<Void> excludeOccurrence(@Parameter(description = "iCalendar UID of the series", required = true)
                                                @PathVariable("icsUid")
                                                String icsUid,
                                                @Parameter(description = "The instance to exclude, ISO instant",
                                                    required = true)
                                                @PathVariable("occurrence")
                                                String occurrence) {
    caldavPushService.excludeOccurrence(currentUser(), icsUid, instantOf(occurrence));
    return ResponseEntity.noContent().build();
  }

  /**
   * The instance identifier, refused rather than guessed at when it is not an
   * instant — excluding the wrong occurrence cancels a meeting nobody meant to.
   *
   * @param value the parameter as received
   * @return the instant
   */
  private java.time.Instant instantOf(String value) {
    try {
      return java.time.Instant.parse(value);
    } catch (java.time.format.DateTimeParseException | NullPointerException e) {
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                                                                      "caldav.error.occurrence");
    }
  }

  /**
   * Establishes where copies go, creating the collection when needed.
   *
   * @return the destination, and whether an existing calendar was adopted
   */
  /**
   * The calendar the copies are currently written into, if one is set.
   *
   * <p>
   * Its own endpoint because the listing that serves the Remote section
   * deliberately hides this collection — it holds nothing but copies of events
   * the agenda already shows. Resolving the destination's name through that
   * listing therefore always came back empty, which the settings screen read
   * as "no destination" and used to switch the copy setting back off in front
   * of the user who had just chosen one.
   *
   * @return the destination and its current name, or 204 when none is set
   */
  @GetMapping("/push/mirror")
  @Secured("users")
  @Operation(summary = "The calendar copies are currently written into",
      description = "Reads it; never creates one. The name is read from the server on each call, so a calendar "
          + "renamed in the user's own client reads correctly here.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "The destination"),
      @ApiResponse(responseCode = "204", description = "No destination is set") })
  public ResponseEntity<MirrorTarget> currentMirror() {
    MirrorTarget mirror = caldavPushService.currentMirror(currentUser());
    return mirror == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(mirror);
  }

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
   * What deleting an eXo calendar would also do remotely.
   *
   * @param calendarId the eXo calendar in question
   * @return the plan, claiming nothing when no binding exists
   */
  @GetMapping("/push/calendars/{calendarId}/deletion-plan")
  @Secured("users")
  @Operation(summary = "Describes what deleting an eXo calendar would do remotely",
      description = "The page cannot work this out: it knows neither whether eXo created the remote collection "
          + "nor which server holds it, and both decide what the confirmation must warn about.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "The plan") })
  public CalendarDeletionPlan deletionPlan(@Parameter(description = "Technical identifier of the eXo calendar",
                                                      required = true)
                                           @PathVariable("calendarId")
                                           long calendarId) {
    return caldavDeletionService.describeDeletion(currentUser(), calendarId);
  }

  /**
   * Removes the remote collection an eXo calendar is mirrored as, before
   * agenda removes the calendar itself.
   *
   * @param calendarId the eXo calendar being deleted
   * @return an empty 204
   */
  @DeleteMapping("/push/calendars/{calendarId}/remote")
  @Secured("users")
  @Operation(summary = "Removes the remote collection of an eXo calendar",
      description = "Remote first: a refusal here stops the deletion before agenda has touched anything. Deleting "
          + "locally first can strand a collection on a server after the record that knew about it is gone.")
  @ApiResponses(value = { @ApiResponse(responseCode = "204", description = "The remote collection is gone"),
      @ApiResponse(responseCode = "403", description = "Stored CalDAV credentials rejected upstream"),
      @ApiResponse(responseCode = "502", description = "Nothing was deleted, in eXo or on the server") })
  public ResponseEntity<Void> deleteRemoteCounterpart(@Parameter(description = "Technical identifier of the eXo calendar",
                                                                 required = true)
                                                      @PathVariable("calendarId")
                                                      long calendarId) {
    caldavDeletionService.deleteRemoteCounterpart(currentUser(), calendarId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Records that the user chose to keep the remote calendar while deleting the
   * eXo one.
   *
   * @param calendarId the eXo calendar being deleted
   * @return an empty 204
   */
  @PostMapping("/push/calendars/{calendarId}/keep-remote")
  @Secured("users")
  @Operation(summary = "Keeps the remote calendar while the eXo one is deleted",
      description = "The escape hatch from the atomic rule: divergence between the two sides is only ever chosen, "
          + "named and recorded — never a side effect of a failed deletion.")
  @ApiResponses(value = { @ApiResponse(responseCode = "204", description = "Recorded") })
  public ResponseEntity<Void> keepRemoteCounterpart(@Parameter(description = "Technical identifier of the eXo calendar",
                                                               required = true)
                                                    @PathVariable("calendarId")
                                                    long calendarId) {
    caldavDeletionService.keepRemoteCounterpart(currentUser(), calendarId);
    return ResponseEntity.noContent().build();
  }

  /**
   * The calendars this user has hidden on their account.
   *
   * @return what can be shown again, empty when nothing is hidden
   */
  /**
   * What each of this user's calendars is doing, for the ones worth telling
   * them about.
   *
   * @return the states, empty when every calendar is synchronising normally
   */
  @GetMapping("/calendar-states")
  @Secured("users")
  @Operation(summary = "Lists the calendars whose synchronisation needs the user's attention",
      description = "Only the states where something the user might do would change the outcome. A calendar that "
          + "is synchronising is not news, and a calendar the user hid has its own listing.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "The states, possibly empty") })
  public List<CalendarSyncState> calendarStates() {
    return caldavDeletionService.listSyncStates(currentUser(), CaldavConnectorUtils.getCurrentUser());
  }

  @GetMapping("/hidden-calendars")
  @Secured("users")
  @Operation(summary = "Lists the calendars the user hid",
      description = "A hidden calendar is one deleted in eXo while its remote counterpart was kept. Nothing on "
          + "screen shows it any more, so this is the only way back to it.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "The hidden calendars, possibly none") })
  public List<HiddenCalendar> hiddenCalendars() {
    return caldavDeletionService.listHidden(currentUser());
  }

  /**
   * Shows a hidden calendar again.
   *
   * @param pairId the binding to lift
   * @return an empty 204
   */
  @DeleteMapping("/hidden-calendars/{pairId}")
  @Secured("users")
  @Operation(summary = "Shows a hidden calendar again",
      description = "Lifts the tombstone and synchronises, so the collection is materialised again straight "
          + "away rather than surfacing as an unbound remote calendar until the next run. It comes back as a "
          + "new calendar, not as the deleted one restored.")
  @ApiResponses(value = { @ApiResponse(responseCode = "204", description = "Lifted"),
      @ApiResponse(responseCode = "403", description = "Not this user's calendar"),
      @ApiResponse(responseCode = "404", description = "No such hidden calendar") })
  public ResponseEntity<Void> showCalendarAgain(@Parameter(description = "Technical identifier of the binding",
                                                           required = true)
                                                @PathVariable("pairId")
                                                long pairId) {
    try {
      caldavDeletionService.showAgain(currentUser(), pairId, CaldavConnectorUtils.getCurrentUser());
      return ResponseEntity.noContent().build();
    } catch (ObjectNotFoundException e) {
      // Not an incident: a stale drawer offering something already lifted.
      LOG.debug("No hidden calendar with id {}", pairId, e);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      LOG.debug("User {} may not lift binding {}", currentUser(), pairId, e);
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    }
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
    // A state of this account rather than a failure of the server behind it —
    // the reading that already puts NOT_CONNECTED here, and the reason it does
    // not answer the 502 a browser renders as "the calendar server is down".
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
