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
package org.exoplatform.caldav.constant;

/**
 * What a login-time attachment to the managed CalDAV server came to, one value
 * per branch of the rules and their failures.
 */
public enum ManagedEnrollmentOutcome {
  /** No managed server applies to the user: nothing designated, or excluded. */
  NOT_MANAGED,
  /** The user has no social identity to record a connection against. */
  NO_IDENTITY,
  /** The user has a CalDAV configuration, or made one during the attempt. */
  ALREADY_CONFIGURED,
  /** The user was attached to the managed server. */
  ATTACHED,
  /** The server or the connect refused; the next login tries again. */
  REFUSED,
  /** An unexpected failure, logged; the next login tries again. */
  FAILED,
  /**
   * Managed mode had attached the user and no longer governs them, and nothing
   * else is designated for them: they were disconnected.
   */
  DETACHED
}
