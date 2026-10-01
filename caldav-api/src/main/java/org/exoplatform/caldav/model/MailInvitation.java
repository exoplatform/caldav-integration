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
package org.exoplatform.caldav.model;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.agenda.constant.EventAttendeeResponse;

/**
 * A calendar invitation a user answered from a mail, as this add-on lands it
 * (EXO-90848) — this add-on's own words for what the mail reader hands over, so
 * that nothing but the one bean conditional on the reader names a type of the
 * reader's: a bean whose signature did would make this whole add-on fail to
 * boot on a server without the mail reader installed, since Spring introspects
 * every method of every bean it creates.
 *
 * <p>
 * The object is the sender's. The only person a landing acts for is
 * {@code username}, in their own calendar; the attendee address is that same
 * user's mailbox, the one the organiser invited.
 *
 * @param username the user who answered, the only person the landing acts for
 * @param attendeeAddress the user's own mailbox address, possibly null
 * @param uid the UID the reader showed and the user answered
 * @param sequence the SEQUENCE the reader read, 0 when the message carries none
 * @param response the answer, as agenda holds it
 * @param icalendar the message's iCalendar object, as received
 */
public record MailInvitation(String username,
                             String attendeeAddress,
                             String uid,
                             int sequence,
                             EventAttendeeResponse response,
                             String icalendar) {

  /**
   * Refuses an invitation missing what the landing needs.
   *
   * @param username the user
   * @param attendeeAddress their mailbox address
   * @param uid the event's UID
   * @param sequence the SEQUENCE
   * @param response the answer
   * @param icalendar the object
   * @throws IllegalArgumentException without a user, a UID, an answer that is
   *           one or an object
   */
  public MailInvitation {
    if (StringUtils.isBlank(username) || StringUtils.isBlank(uid) || response == null
        || response == EventAttendeeResponse.NEEDS_ACTION || StringUtils.isBlank(icalendar)) {
      throw new IllegalArgumentException("A mail invitation names the user, the event's UID, an answer and the object");
    }
  }
}
