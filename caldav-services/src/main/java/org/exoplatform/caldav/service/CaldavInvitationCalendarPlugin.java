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

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.caldav.model.LandedMailInvitation;
import org.exoplatform.caldav.model.MailInvitation;
import org.exoplatform.emailConnector.model.InvitationLanding;
import org.exoplatform.emailConnector.model.LandedInvitation;
import org.exoplatform.emailConnector.plugin.InvitationCalendarPlugin;

/**
 * Lands, for the mail reader, an invitation the user received by mail in their
 * CalDAV-bound calendar (EXO-90848).
 *
 * <p>
 * The mail reader finds this bean by type across add-ons when a user clicks,
 * and depends on no calendar add-on; this bean is not created at all on a
 * server without the mail reader installed ({@code @ConditionalOnClass}, read
 * from the class metadata, never loading the class). A {@code @Service} with a
 * name of its own, because that is what the bridge exports to the reader's
 * context.
 *
 * <p>
 * <b>The only class of this add-on that names a type of the reader's.</b> Spring
 * introspects every method of every bean it creates, so an unconditional bean
 * whose signature named the reader's records would fail this whole add-on's
 * context on a server without the reader; the landing service therefore speaks
 * {@link MailInvitation} and {@link LandedMailInvitation}, this add-on's own
 * words, and this bean translates both ways. Carries no logic:
 * {@link CaldavInvitationLandingService} decides and acts.
 */
@Service("caldavInvitationCalendarPlugin")
@ConditionalOnClass(InvitationCalendarPlugin.class)
public class CaldavInvitationCalendarPlugin implements InvitationCalendarPlugin {

  @Autowired
  private CaldavInvitationLandingService caldavInvitationLandingService;

  /**
   * Whether this add-on holds a CalDAV-bound calendar for the user.
   *
   * @param username the user's eXo login
   * @return true when a connected account has a calendar bound on it
   */
  @Override
  public boolean holdsCalendarFor(String username) {
    return caldavInvitationLandingService.holdsCalendarFor(username);
  }

  /**
   * Lands the invitation in the user's CalDAV-bound calendar.
   *
   * @param landing the invitation, the user and what they asked
   * @return what was done, null when the user has no calendar here or there
   *         was nothing to do
   * @throws IllegalArgumentException when the invitation cannot be landed as it
   *           is
   * @throws RuntimeException when the landing was attempted and failed
   */
  @Override
  public LandedInvitation land(InvitationLanding landing) {
    LandedMailInvitation landed =
                                caldavInvitationLandingService.land(new MailInvitation(landing.username(),
                                                                                       landing.attendeeAddress(),
                                                                                       landing.method(),
                                                                                       landing.uid(),
                                                                                       landing.sequence(),
                                                                                       landing.answer() == null ? null
                                                                                                                : EventAttendeeResponse.valueOf(landing.answer()
                                                                                                                                                       .name()),
                                                                                       landing.icalendar()));
    return landed == null ? null : new LandedInvitation(landed.eventId(), landed.link(), landed.removed(), landed.alreadyHeld());
  }
}
