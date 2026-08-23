package org.exoplatform.caldav.rest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.caldav.utils.CaldavConnectorUtils;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * TEMPORARY discriminator endpoint — does nothing but call agenda's
 * deleteEventById from inside the caldav WAR, with no sync, import or
 * reconciliation anywhere near it. Delete this class before merging.
 */
@RestController
public class CaldavDebugRest {

  @Autowired
  private AgendaEventService agendaEventService;

  @Autowired
  private IdentityManager    identityManager;

  /**
   * Deletes one agenda event, exactly as the reconciliation would.
   *
   * @param eventId the agenda event to delete
   * @return 204 on success; any exception surfaces as a 500 with its message
   * @throws Exception whatever agenda throws, unwrapped
   */
  @DeleteMapping("/debug/agenda-events/{eventId}")
  @Secured("users")
  public ResponseEntity<Void> delete(@PathVariable("eventId") long eventId) throws Exception {
    long userIdentityId = CaldavConnectorUtils.getCurrentUserIdentityId(identityManager);
    agendaEventService.deleteEventById(eventId, userIdentityId);
    return ResponseEntity.noContent().build();
  }
}
