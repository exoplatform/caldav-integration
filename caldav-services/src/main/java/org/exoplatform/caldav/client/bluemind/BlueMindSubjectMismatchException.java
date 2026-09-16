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
package org.exoplatform.caldav.client.bluemind;

import org.exoplatform.caldav.client.CalDavException;

/**
 * The session BlueMind opened belongs to somebody other than the account eXo
 * meant to edit, so nothing was sent (EXO-90277).
 *
 * <p>
 * A subscription change is addressed to a directory entry uid eXo recorded
 * for the colleague when their account was last discovered, and made with the
 * credentials stored for their eXo login. Those two facts can drift apart — a
 * colleague reconnected as another BlueMind user, a principal recorded from a
 * stale discovery — and BlueMind itself would let the session's own user edit
 * only their own subscriptions ({@code ROLE_SELF}) while answering the same
 * 403 for an attempt on somebody else's. The client does not rely on that: it
 * compares {@code authUser.uid} from the login answer with the uid it was
 * asked to edit <em>before any POST</em>, and refuses on a difference, so the
 * one account eXo ever writes to with a colleague's password is that
 * colleague's own.
 *
 * <p>
 * Final by nature: the recorded principal has to be re-discovered for the
 * comparison to change, which is not this client's job. The caller gives up
 * the change rather than counting attempts against it.
 */
public class BlueMindSubjectMismatchException extends CalDavException {

  private static final long serialVersionUID = 7384205118866312847L;

  /**
   * A refused edit explained by its message alone — never naming a key or a
   * password, and never the login.
   *
   * @param message what did not match, in uid terms
   */
  public BlueMindSubjectMismatchException(String message) {
    super(message);
  }
}
