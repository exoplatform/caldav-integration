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
 * The conversation never reached the calendar server: the transport failed
 * outright, or a gateway standing in front of the server answered that it
 * could not reach it either (502, 503, 504).
 *
 * <p>
 * Distinguished from its parent {@link CalDavException} for the same reason
 * {@link CalDavAuthenticationException} is — <b>the caller reacts
 * differently</b>. A plain {@link CalDavException} says this call did not
 * work; this one says <b>the server is not there</b>, which is a property of
 * the server and not of the call, and is therefore known after <b>one</b>
 * attempt. A sequence of independent steps — the connect sequence, one
 * synchronisation pass — must abandon its remaining steps rather than let
 * each of them discover the same absence on its own: a CalDAV server may
 * auto-ban a source address after repeated failed requests, and such bans are
 * persistent and silent (a banned address has its connections dropped without
 * a reply, which reads as a broken server rather than a block).
 *
 * <p>
 * Deliberately <b>not</b> a signal to retry or to back off. There is no retry
 * logic in this client and none is wanted; the answer to an absent server is
 * fewer requests, not more.
 *
 * <p>
 * 500 is deliberately absent from the list: BlueMind answers 500 for an
 * absent object, a quirk this add-on already accommodates, so treating it as
 * "the server is not there" would abandon passes against a perfectly present
 * server.
 */
public class CalDavUnreachableException extends CalDavException {

  private static final long serialVersionUID = 6521186957076217603L;

  /**
   * An unreachable server explained by its message alone — a gateway status
   * naming the method and URI it answered.
   *
   * @param message what went wrong, in server-and-URL terms
   */
  public CalDavUnreachableException(String message) {
    super(message);
  }

  /**
   * An unreachable server wrapping the transport failure that proved it.
   *
   * @param message what went wrong, in server-and-URL terms
   * @param cause the underlying transport failure
   */
  public CalDavUnreachableException(String message, Throwable cause) {
    super(message, cause);
  }
}
