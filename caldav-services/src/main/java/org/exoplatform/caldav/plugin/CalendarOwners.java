/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.caldav.plugin;

import java.io.Serializable;
import java.util.Map;

/**
 * The owner of every calendar an account's session sees, as the server
 * answers it: what the owner cache keeps per account.
 */
public interface CalendarOwners extends Serializable {

  /**
   * The uid of the account the session was authenticated as.
   *
   * @return the uid
   */
  String accountUid();

  /**
   * The owner uid of each calendar the session sees, by container uid.
   *
   * @return the owners, unmodifiable
   */
  Map<String, String> ownerByContainerUid();

}
