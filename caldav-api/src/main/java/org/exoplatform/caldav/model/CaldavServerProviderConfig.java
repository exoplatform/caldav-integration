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

import java.util.Map;
import java.util.Set;

/**
 * A declared server's provider configuration as the administration screen may
 * see it: the stored values with every secret left out, and which secrets have
 * a value stored.
 *
 * <p>
 * The second part is what lets the screen accept a required secret left blank
 * - it means "keep the stored one" - only when one is stored, instead of
 * guessing it from the server having an id.
 *
 * @param values the stored non-secret values, keyed by descriptor field; empty
 *          when nothing is stored
 * @param storedSecretKeys the keys of the secret fields that have a value
 *          stored; never the values
 */
public record CaldavServerProviderConfig(Map<String, String> values, Set<String> storedSecretKeys) {

  /** Nothing stored: a server with no provider, or no storage deployed. */
  public static final CaldavServerProviderConfig EMPTY = new CaldavServerProviderConfig(Map.of(), Set.of());

}
