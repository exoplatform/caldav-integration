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
 * One reminder, as agenda holds it: a quantity and the unit it counts in.
 *
 * <p>
 * Kept in agenda's own terms rather than pre-converted to minutes, so that the
 * conversion — and the decision about what an unusable value means — lives in
 * one place with the rest of the ICS rules.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IcsReminder {

  /** How many units before the start; a negative value is unusable. */
  private long   before;

  /** The unit: minute (the default), hour, day or week. */
  private String beforePeriodType;

}
