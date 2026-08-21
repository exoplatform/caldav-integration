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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A calendar user on the copy: an organizer or an attendee.
 *
 * <p>
 * The address is never invented. RFC 5545 makes it a CAL-ADDRESS, and a
 * fabricated one would be forwarded as a reply-to by any client acting on the
 * copy — so a person without a visible address is left off the object
 * entirely rather than given a plausible-looking one.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IcsPerson {

  /** Display name, written as a quoted CN; may be absent. */
  private String displayName;

  /** Mail address; a person without one is not written at all. */
  private String email;

  /**
   * The attendee's response as agenda holds it. Agenda's values are already
   * the RFC 5545 tokens up to the underscore spelling of the enum constant;
   * anything unrecognised becomes NEEDS-ACTION rather than an invalid token.
   * Ignored for an organizer.
   */
  private String response;

}
