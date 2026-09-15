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
 * The door through which one calendar object is written to, or removed from,
 * a server — the four write shapes the engine uses, and nothing else.
 *
 * <p>
 * Why an interface between the engine and {@link CalDavClient} (EXO-90307).
 * On BlueMind a CalDAV {@code PUT} makes the server schedule the meeting
 * itself, and the only door that does not is BlueMind's own REST import API;
 * the engine, though, must not know which server it is talking to — a service
 * that branched on "is this BlueMind" at every write would carry that
 * knowledge into a dozen call sites. So each write goes through the writer
 * {@link CalendarObjectWriters} resolves for the server, and the engine's own
 * rules — conditional in both directions, a 412 is an answer and not a fault,
 * a 404 on delete is a fact — are stated once here and honoured by every
 * implementation.
 *
 * <p>
 * The contracts are those of the {@link CalDavClient} methods of the same
 * names, restated so an implementation that has no {@code If-Match} header to
 * send knows what it must emulate:
 * <ul>
 * <li>{@link #putObject} creates and only creates: an object already at the
 * href is answered 412, never overwritten.</li>
 * <li>{@link #updateObject} replaces only what the caller last read: a
 * version other than {@code ifMatch} — or no object at all — is answered
 * 412, and a blank precondition is refused rather than sent unconditional.</li>
 * <li>{@link #overwriteObject} writes whatever is there, and is only ever
 * reached after somebody compared both copies; see {@link CalDavClient#overwriteObject}.</li>
 * <li>{@link #deleteObject} removes conditionally when a version is given, and
 * answers 200/204 removed, 404/410 already gone, 412 refused.</li>
 * </ul>
 * Every result's {@code etag} is the version the server now holds for the
 * object, in the shape the server's own listings answer for it, or null when
 * the implementation could not learn it.
 */
public interface CalendarObjectWriter {

  /**
   * Creates one object; an object already at the href is answered 412.
   *
   * @param endpoint the declared server
   * @param href the object's server-absolute path to create at
   * @param icsData the iCalendar text to store
   * @return the status and, when known, the stored version
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the write could not be carried out
   */
  PutResult putObject(CalDavEndpoint endpoint, String href, String icsData);

  /**
   * Writes one object over whatever is there, with no precondition at all.
   *
   * @param endpoint the declared server
   * @param href the object's server-absolute path
   * @param icsData the iCalendar text to store
   * @return the status and, when known, the stored version; never a 412
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the write could not be carried out
   */
  PutResult overwriteObject(CalDavEndpoint endpoint, String href, String icsData);

  /**
   * Replaces one existing object, only when its version is still the one the
   * caller last read.
   *
   * @param endpoint the declared server
   * @param href the object's server-absolute path
   * @param icsData the iCalendar text to store
   * @param ifMatch the version the caller's own read answered, verbatim
   * @return the status and, when known, the stored version; 412 when the
   *         object moved on or is gone
   * @throws IllegalArgumentException when the precondition is blank
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the write could not be carried out
   */
  PutResult updateObject(CalDavEndpoint endpoint, String href, String icsData, String ifMatch);

  /**
   * Deletes one object, conditionally when a version is given.
   *
   * @param endpoint the declared server
   * @param href the object's server-absolute path
   * @param ifMatch the version to condition the removal on, or null to remove
   *          unconditionally
   * @return 200 or 204 removed, 404 or 410 already gone, 412 refused
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the removal could not be carried out
   */
  int deleteObject(CalDavEndpoint endpoint, String href, String ifMatch);
}
