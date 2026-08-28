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

import java.io.InputStream;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.CaldavSyncTuning;
import org.exoplatform.caldav.service.CaldavMirrorReportService;
import org.exoplatform.caldav.service.CaldavServerService;
import org.exoplatform.caldav.service.CaldavTuningService;
import org.exoplatform.caldav.service.MirrorPassReport;
import org.exoplatform.commons.exception.ObjectNotFoundException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * REST access to the CalDAV server registry. Reads are open to every
 * authenticated user — the rows are credential-free by construction, and the
 * browser needs the names and URLs to offer the connectors. Writes are for
 * platform administrators; the ACL decision itself lives in the service, this
 * layer only maps its exceptions onto statuses (404 for a missing object, 403
 * for a refused access, 400 with the message code for an invalid input — note
 * the legacy JAX-RS resource of this add-on answers 401 where the org
 * contract says 403; this new controller follows the contract).
 */
@RestController
@RequestMapping("/servers")
@Tag(name = "/caldav/rest/servers", description = "Manages the registry of CalDAV servers users may connect to")
public class CaldavServerRest {

  @Autowired
  private CaldavServerService caldavServerService;

  @Autowired
  private CaldavTuningService caldavTuningService;

  @Autowired
  private CaldavMirrorReportService caldavMirrorReportService;

  /**
   * How often and how widely eXo synchronises CalDAV accounts.
   *
   * <p>
   * Readable by any authenticated user because nothing here is secret and the
   * administration screen renders before it knows who is looking; writing is
   * another matter.
   *
   * @return the tuning in force
   */
  @GetMapping("/tuning")
  @Secured("users")
  @Operation(summary = "Reads the CalDAV synchronisation tuning", method = "GET",
      description = "Each value is what an administrator saved, else what the deployment set as a property, else "
          + "the coded default.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled") })
  public CaldavSyncTuning getTuning() {
    return caldavTuningService.getTuning();
  }

  /**
   * Records how often and how widely eXo synchronises.
   *
   * <p>
   * Takes effect on the next synchronisation, not on the next restart: the
   * engine reads these where it uses them rather than capturing them when its
   * beans are built.
   *
   * @param tuning the values to store
   * @return the tuning now in force
   */
  @PutMapping("/tuning")
  @Secured("administrators")
  @Operation(summary = "Records the CalDAV synchronisation tuning", method = "PUT",
      description = "Takes effect on the next synchronisation. A window wider than ten years is refused: it turns "
          + "the first page load of the day into a full download of a decade of history.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public CaldavSyncTuning saveTuning(@RequestBody
                                     CaldavSyncTuning tuning) {
    try {
      caldavTuningService.saveTuning(tuning);
      return caldavTuningService.getTuning();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * What the last pass over each connected user's meeting copies found and
   * moved.
   *
   * <p>
   * Administrators only, and for a reason the reads above do not have: a row
   * here names a user and says something about the state of their calendar.
   * Nothing in it is a credential, but it is not the sort of thing every
   * authenticated caller has any business enumerating.
   *
   * <p>
   * In memory and therefore empty after a restart, until each account
   * synchronises again. That is stated in the screen rather than hidden here.
   *
   * @return the last pass of every user that has had one, newest first
   */
  @GetMapping("/mirror/reports")
  @Secured("administrators")
  @Operation(summary = "Reads the last mirror pass of every connected user", method = "GET",
      description = "One tally per user: what the comparison of their meeting copies found, and what a change of "
          + "destination moved. Kept in memory, so it is empty after a restart until the accounts synchronise again.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public List<MirrorPassReport> getMirrorReports() {
    return caldavMirrorReportService.getReports();
  }

  /**
   * Lists every declared CalDAV server, active or not — the admin section
   * shows both, and nothing in a row is secret.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @return every registration
   */
  @GetMapping
  @Secured("users")
  @Operation(summary = "Lists the declared CalDAV servers", method = "GET",
      description = "Lists every declared CalDAV server. The rows never hold credentials.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled") })
  public List<CaldavServer> getServers(HttpServletRequest request) {
    return caldavServerService.getServers();
  }

  /**
   * Declares a new CalDAV server.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param server registration to create
   * @return the created registration, carrying its id and provider name
   */
  @PostMapping
  @Secured("administrators")
  @Operation(summary = "Declares a CalDAV server", method = "POST",
      description = "Declares a new CalDAV server and bridges it to an agenda remote provider")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public CaldavServer createServer(HttpServletRequest request,
                                   @RequestBody
                                   CaldavServer server) {
    try {
      return caldavServerService.createServer(server, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Updates a declared CalDAV server.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param serverId technical identifier of the registration
   * @param server new values of the registration
   * @return the updated registration
   */
  @PutMapping("/{serverId}")
  @Secured("administrators")
  @Operation(summary = "Updates a declared CalDAV server", method = "PUT",
      description = "Updates the name, description, URL and activation of a declared CalDAV server")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found") })
  public CaldavServer updateServer(HttpServletRequest request,
                                   @Parameter(description = "Technical identifier of the registration", required = true)
                                   @PathVariable("serverId")
                                   long serverId,
                                   @RequestBody
                                   CaldavServer server) {
    try {
      server.setId(serverId);
      return caldavServerService.updateServer(server, request.getRemoteUser());
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Activates or deactivates a declared CalDAV server.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param serverId technical identifier of the registration
   * @param active whether users may connect to this server
   * @return the updated registration
   */
  @PatchMapping("/{serverId}/status")
  @Secured("administrators")
  @Operation(summary = "Activates or deactivates a declared CalDAV server", method = "PATCH",
      description = "Flips the activation of a declared CalDAV server, propagated to its agenda remote provider")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found") })
  public CaldavServer setServerActive(HttpServletRequest request,
                                      @Parameter(description = "Technical identifier of the registration", required = true)
                                      @PathVariable("serverId")
                                      long serverId,
                                      @Parameter(description = "Whether users may connect to this server", required = true)
                                      @RequestParam("active")
                                      boolean active) {
    try {
      return caldavServerService.setServerActive(serverId, active, request.getRemoteUser());
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
  }

  /**
   * Deletes a declared CalDAV server, refused with a 409 — carrying
   * {@code caldav.server.referenced:<count>} — while connected accounts still
   * reference it.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param serverId technical identifier of the registration
   */
  @DeleteMapping("/{serverId}")
  @Secured("administrators")
  @Operation(summary = "Deletes a declared CalDAV server", method = "DELETE",
      description = "Deletes a declared CalDAV server, unless connected accounts still reference it")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Connected accounts still reference this server") })
  public void deleteServer(HttpServletRequest request,
                           @Parameter(description = "Technical identifier of the registration", required = true)
                           @PathVariable("serverId")
                           long serverId) {
    try {
      caldavServerService.deleteServer(serverId, request.getRemoteUser());
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  /**
   * Serves the uploaded image of a declared CalDAV server — the icon the
   * admin table and the connectors list render.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param serverId technical identifier of the registration
   * @return the image bytes as PNG
   */
  @GetMapping("/{serverId}/image")
  @Secured("users")
  @Operation(summary = "Gets the image of a declared CalDAV server", method = "GET",
      description = "Serves the uploaded image of a declared CalDAV server")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "404", description = "Not found") })
  public ResponseEntity<InputStreamResource> getServerImage(HttpServletRequest request,
                                                            @Parameter(description = "Technical identifier of the registration", required = true)
                                                            @PathVariable("serverId")
                                                            long serverId) {
    try {
      InputStream stream = caldavServerService.getServerImageInputStream(serverId);
      if (stream == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(new InputStreamResource(stream));
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }
}