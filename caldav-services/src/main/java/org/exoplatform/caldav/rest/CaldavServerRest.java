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

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.annotation.Secured;
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
import org.exoplatform.caldav.service.CaldavServerService;
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
}
