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
package org.exoplatform.caldav.client;

/**
 * The server accepted the credentials and refused the write anyway: a 403 on
 * a PUT or a DELETE, which is how a CalDAV server says "this resource is not
 * yours to change".
 *
 * <p>
 * Distinguished from its parent {@link CalDavException} for the reason the
 * other two subclasses are — <b>the caller reacts differently</b>. A plain
 * {@link CalDavException} says this call did not work and may work next time;
 * a 403 on a write says the account has no write privilege on that
 * collection, which is a property of the collection and not of the attempt.
 * Observed live on Stalwart 0.16 (EXO-90235): a calendar a colleague shared
 * read-only had been materialised as the user's own, and every edit pushed
 * into it was refused with 403 and retried, five times over twenty-five
 * minutes, for a refusal the server was never going to withdraw.
 *
 * <p>
 * Only the write verbs ever raise this. On the read verbs a 403 is classified
 * as a credential refusal before any status policy runs, because BlueMind
 * answers 403 for refused Basic auth on a PROPFIND — see
 * {@code HttpCalDavClient.checkAuthStatus} — so a read that reaches the
 * generic refusal never carries a 403.
 *
 * <p>
 * Nothing in the body is parsed. RFC 3744 §7.1.1 lets a server name the
 * missing privilege in a {@code DAV:need-privileges} element, and neither of
 * the two servers this add-on is verified against was captured doing so; the
 * status alone is what the engine acts on.
 */
public class CalDavForbiddenException extends CalDavException {

  private static final long serialVersionUID = 4121186957076217604L;

  /**
   * A refused write explained by its message alone — the status naming the
   * method and URI it answered.
   *
   * @param message what went wrong, in server-and-URL terms
   */
  public CalDavForbiddenException(String message) {
    super(message);
  }
}
