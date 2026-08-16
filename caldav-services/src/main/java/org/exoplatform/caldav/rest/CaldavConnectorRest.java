/*
 * Copyright (C) 2023 eXo Platform SAS.
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.service.CaldavConnectorService;
import org.exoplatform.caldav.utils.CaldavConnectorUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.rest.resource.ResourceContainer;
import org.exoplatform.social.core.manager.IdentityManager;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/v1/caldav")
public class CaldavConnectorRest implements ResourceContainer {
  private static final Log       LOG = ExoLogger.getLogger(CaldavConnectorRest.class);

  private CaldavConnectorService caldavConnectorService;

  private IdentityManager        identityManager;

  public CaldavConnectorRest(CaldavConnectorService caldavConnectorService, IdentityManager identityManager) {
    this.caldavConnectorService = caldavConnectorService;
    this.identityManager = identityManager;
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("users")
  @Operation(summary = "Create caldav user setting", method = "POST")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Invalid query input"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "500", description = "Internal server error") })
  public Response createCaldavSetting(@Parameter(description = "Caldav user setting object to create", required = true)
  CaldavUserSetting caldavUserSetting) {
    if (caldavUserSetting == null) {
      return Response.status(Response.Status.BAD_REQUEST).build();
    }
    long identityId = CaldavConnectorUtils.getCurrentUserIdentityId(identityManager);
    try {
      caldavConnectorService.createCaldavSetting(caldavUserSetting, identityId);
      return Response.ok().build();
    } catch (Exception e) {
      LOG.error("Error when creating caldav user setting ", e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }
  }

  @GET
  @RolesAllowed("users")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Get caldav user setting", method = "GET")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "500", description = "Internal server error") })
  public Response getCaldavSetting() {
    long identityId = CaldavConnectorUtils.getCurrentUserIdentityId(identityManager);
    try {
      return Response.ok(caldavConnectorService.getCaldavSetting(identityId)).build();
    } catch (Exception e) {
      LOG.error("Error when retrieving caldav user settings for user with id '{}'", identityId, e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Saves the href of the mirror calendar of the current user: the collection,
   * on the connected CalDAV server, that receives the meetings pushed by eXo.
   * Only the href travels here — the collection itself is created (or picked)
   * by the front-end connector, which is the only party holding a CalDAV
   * session.
   *
   * @param caldavUserSetting a {@link CaldavUserSetting} carrying the
   *          mirrorCalendarHref to store; other fields are ignored
   * @return 200 when stored, 400 when the href is blank
   */
  @PUT
  @Path("mirrorCalendar")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("users")
  @Operation(summary = "Save the mirror calendar href of the caldav user setting", method = "PUT")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Invalid query input"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "500", description = "Internal server error") })
  public Response saveMirrorCalendarHref(@Parameter(description = "Caldav user setting carrying the mirror calendar href", required = true)
  CaldavUserSetting caldavUserSetting) {
    if (caldavUserSetting == null) {
      return Response.status(Response.Status.BAD_REQUEST).build();
    }
    long identityId = CaldavConnectorUtils.getCurrentUserIdentityId(identityManager);
    try {
      caldavConnectorService.saveMirrorCalendarHref(caldavUserSetting.getMirrorCalendarHref(), identityId);
      return Response.ok().build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
    } catch (Exception e) {
      LOG.error("Error when saving caldav mirror calendar href for user with id '{}'", identityId, e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }
  }

  @DELETE
  @RolesAllowed("users")
  @Operation(summary = "Delete caldav user setting", method = "DELETE")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "500", description = "Internal server error") })
  public Response deleteCaldavSetting() {
    long identityId = CaldavConnectorUtils.getCurrentUserIdentityId(identityManager);
    try {
      caldavConnectorService.deleteCaldavSetting(identityId);
      return Response.ok().build();
    } catch (Exception e) {
      LOG.error("Error when deleting caldav user setting for user with id '{}'", identityId, e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }
  }
}
