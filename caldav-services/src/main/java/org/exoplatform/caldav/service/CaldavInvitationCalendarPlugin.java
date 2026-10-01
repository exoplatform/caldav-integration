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
package org.exoplatform.caldav.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.InvitationLanding;
import org.exoplatform.emailConnector.plugin.InvitationCalendarPlugin;

/**
 * Lands, for the mail reader, an invitation the user answered from a mail in
 * their CalDAV-bound calendar (EXO-90848).
 *
 * <p>
 * The mail reader finds this bean by type across add-ons when a user answers,
 * and depends on no calendar add-on; this bean depends on the reader's SPI
 * only at compile time, and is not created at all on a server without the mail
 * reader installed ({@code @ConditionalOnClass}, read from the class metadata,
 * never loading the class). A {@code @Service} with a name of its own, because
 * that is what the bridge exports to the reader's context.
 *
 * <p>
 * Carries no logic: {@link CaldavInvitationLandingService} decides and acts.
 */
@Service("caldavInvitationCalendarPlugin")
@ConditionalOnClass(InvitationCalendarPlugin.class)
public class CaldavInvitationCalendarPlugin implements InvitationCalendarPlugin {

  @Autowired
  private CaldavInvitationLandingService caldavInvitationLandingService;

  /**
   * Lands the answered invitation in the user's CalDAV-bound calendar.
   *
   * @param landing the invitation, the user and their answer
   * @return true when the calendar now holds the event with this answer, false
   *         when the user has no connected account or no calendar bound on it
   */
  @Override
  public boolean land(InvitationLanding landing) {
    return caldavInvitationLandingService.land(landing);
  }
}
