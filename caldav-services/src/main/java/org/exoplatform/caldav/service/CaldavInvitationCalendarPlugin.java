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
import org.exoplatform.caldav.model.MailInvitation;
import org.exoplatform.emailConnector.model.InvitationLanding;
import org.exoplatform.emailConnector.plugin.InvitationCalendarPlugin;

/**
 * Lands, for the mail reader, an invitation the user answered from a mail in
 * their CalDAV-bound calendar (EXO-90848).
 *
 * <p>
 * The mail reader finds this bean by type across add-ons when a user answers,
 * and depends on no calendar add-on; this bean is not created at all on a
 * server without the mail reader installed ({@code @ConditionalOnClass}, read
 * from the class metadata, never loading the class). A {@code @Service} with a
 * name of its own, because that is what the bridge exports to the reader's
 * context.
 *
 * <p>
 * <b>The only class of this add-on that names a type of the reader's.</b> Spring
 * introspects every method of every bean it creates, so an unconditional bean
 * whose signature named the reader's record would fail this whole add-on's
 * context on a server without the reader; the landing service therefore speaks
 * {@link MailInvitation}, this add-on's own words, and this bean translates.
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
   * @throws IllegalArgumentException when the invitation cannot be landed as it
   *           is
   * @throws RuntimeException when the landing was attempted and failed
   */
  @Override
  public boolean land(InvitationLanding landing) {
    return caldavInvitationLandingService.land(new MailInvitation(landing.username(),
                                                                  landing.attendeeAddress(),
                                                                  landing.uid(),
                                                                  landing.sequence(),
                                                                  EventAttendeeResponse.valueOf(landing.answer().name()),
                                                                  landing.icalendar()));
  }
}
