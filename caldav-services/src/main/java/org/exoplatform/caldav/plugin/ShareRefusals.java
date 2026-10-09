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

/**
 * The reasons a share channel refuses to read, grant or revoke a share: the
 * codes a {@link CaldavShareException} or an {@link IllegalArgumentException}
 * carries back to the owner. Each one is a platform message key the front end
 * and the i18n bundles read, so its value never changes.
 */
public final class ShareRefusals {

  /**
   * The imported collection is not the caller's own on the server: another
   * principal owns it, it is read-only for them, it lies outside their
   * calendar home, it is a subscription to someone else's calendar, or the
   * server gives them no right to manage who sees it.
   */
  public static final String      NOT_OWNED_ON_SERVER    = "caldav.share.notOwnedOnServer";

  /** The server offers no way to grant access whose changes eXo can confirm. */
  public static final String      NOT_SUPPORTED          = "caldav.share.notSupported";

  /** The sharee has no recorded identity on the same server. */
  public static final String      SHAREE_NOT_CONNECTED   = "caldav.share.shareeNotConnected";

  /** The sharee holds more than read access, granted outside eXo. */
  public static final String      NOT_READ_ONLY          = "caldav.share.notReadOnly";

  /** The server would not let the caller read the calendar's access list. */
  public static final String      ACL_UNREADABLE         = "caldav.share.aclUnreadable";

  /** The server refused the change. */
  public static final String      SERVER_REFUSED         = "caldav.share.serverRefused";

  /**
   * The server named no mail address for the sharee's principal, which is the
   * only way BlueMind's share handler finds a sharee.
   */
  public static final String      SHAREE_ADDRESS_UNKNOWN = "caldav.share.shareeAddressUnknown";

  /**
   * The colleague holds access below viewing given outside eXo — seeing when
   * the owner is free, say — which a share from eXo would replace and a later
   * stop would erase.
   */
  public static final String      SHAREE_HAS_OTHER_ACCESS = "caldav.share.shareeHasOtherAccess";

  /** The server accepted the change, and the list read back does not hold it. */
  public static final String      NOT_APPLIED            = "caldav.share.notApplied";

  private ShareRefusals() {
  }

}
