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
 * A calendar of the connected account, in the shape agenda expects from any
 * connector.
 *
 * <p>
 * The identity is the collection href and never the display name: a user
 * renaming a calendar in their own client must not detach whatever eXo has
 * associated with it, and nothing stops two collections sharing a name.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RemoteCalendar {

  /** The collection href, which is the calendar's identity. */
  private String  id;

  /** What the user called it. */
  private String  name;

  /** A colour that is always usable, published or derived. */
  private String  color;

  /** Whether events may be written into it. */
  private boolean readOnly;

}
