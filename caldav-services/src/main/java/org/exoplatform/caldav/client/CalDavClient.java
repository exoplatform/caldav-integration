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

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The CalDAV protocol, and nothing else: it fetches, it does not decide. No
 * business rule, no storage, no notion of what an event means locally — the
 * sync engine owns all of that. It exists as an interface so the engine can
 * be tested against canned answers, and so a different transport could be
 * dropped in without the engine noticing — the exact seam email-connector's
 * {@code CardDavClient} cut for CardDAV.
 * <p>
 * Two disciplines hold on every method:
 * <ul>
 * <li><b>Targets come from the registry, never from a caller.</b> Every
 * method addresses a {@link CalDavEndpoint}, which only
 * {@link #endpoint(Long, String)} can mint, and only from the
 * administrator-managed server registry; hrefs are server-absolute paths
 * resolved against that endpoint's authority, and an absolute href naming
 * any other host is refused, not followed.</li>
 * <li><b>A 207 is never read as success by itself.</b> Every property is
 * read through its propstat status — BlueMind answers 207 with failing
 * propstats where a naive client reads 2xx as success — and MKCALENDAR
 * answers a raw {@link MkCalendarResult} whose one trustworthy confirmation
 * is the caller's own fresh listing.</li>
 * </ul>
 */
public interface CalDavClient {

  /**
   * Mints the one kind of target this client will talk to: the account base
   * of a declared server, resolved exclusively from the server registry —
   * the row the account references, else the seed row, else the legacy
   * deployment property — with the {@code {username}} placeholder replaced
   * by the account's DAV username, percent-encoded for its path position.
   *
   * @param serverId registry row the user's account references, or null for
   *          the seed-row / legacy-property resolution
   * @param davUsername the account's username on the CalDAV server
   * @return the endpoint every other method addresses
   * @throws CalDavException when no server is declared anywhere, the
   *           resolved URL is unusable, or the username cannot be part of a
   *           URL path
   */
  CalDavEndpoint endpoint(Long serverId, String davUsername);

  /**
   * Walks discovery from the endpoint base: asks who the authenticated user
   * is ({@code current-user-principal}), then where that principal's
   * calendars live ({@code calendar-home-set}). Started at the registered
   * base path directly — never at {@code /.well-known/caldav}, whose
   * contract is a redirect and this client follows none.
   *
   * @param endpoint the declared server to discover on
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the calendar home's server-absolute raw path
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the server cannot be reached or answers no
   *           principal or no home
   */
  String discoverCalendarHome(CalDavEndpoint endpoint, String username, String password);

  /**
   * Lists the calendar collections of a home with every property the sync
   * engine binds on: display name, resource type, ctag, sync token,
   * supported reports, the user's privileges, and the calendar colour.
   *
   * @param endpoint the declared server
   * @param homeHref the calendar home's server-absolute path
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the calendar collections, never null — non-calendar members of
   *         the home (the home itself included) are filtered out here by
   *         resource type, never guessed from the path
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the server cannot be reached or errors
   */
  List<CalendarCollection> listCalendars(CalDavEndpoint endpoint, String homeHref, String username, String password);

  /**
   * Reads one collection's properties at Depth:0 — what a bound pair
   * re-reads on each run without paying a full home listing.
   *
   * @param endpoint the declared server
   * @param href the collection's server-absolute path
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the collection, or null when the resource exists but is not a
   *         calendar collection
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the server cannot be reached or errors
   */
  CalendarCollection readCalendar(CalDavEndpoint endpoint, String href, String username, String password);

  /**
   * Reads the collection's current ctag, the cheap tier-2 question asked
   * first on every run.
   *
   * @param endpoint the declared server
   * @param href the collection's server-absolute path
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the current ctag, or null when the server does not implement it
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the server cannot be reached or errors
   */
  String getCtag(CalDavEndpoint endpoint, String href, String username, String password);

  /**
   * Lists every object in the collection with its version — the
   * {@code listResourceEtags} pattern the plan reuses verbatim from
   * email-connector, and what tells the engine who is new, who changed and
   * who is gone. Also the mirror-verification listing of the metadata
   * design.
   *
   * @param endpoint the declared server
   * @param collectionHref the collection's server-absolute path
   * @param username the account to authenticate as
   * @param password that account's password
   * @return object path to object version, insertion-ordered, never null —
   *         the collection itself and any member without an ETag are
   *         skipped by that one rule, never by guessing from the path
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the server cannot be reached or errors
   */
  Map<String, String> listResourceEtags(CalDavEndpoint endpoint, String collectionHref, String username, String password);

  /**
   * Runs a calendar-query REPORT for the VEVENTs of a time window — the
   * tier-3 floor for servers whose listings carry no usable ETags, and the
   * ranged read the events endpoint will serve from.
   *
   * @param endpoint the declared server
   * @param collectionHref the collection's server-absolute path
   * @param start window start, inclusive, or null for an unbounded start
   * @param end window end, exclusive, or null for an unbounded end
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the matching objects with their calendar data, never null
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the server cannot be reached or errors
   */
  List<CalendarObject> calendarQuery(CalDavEndpoint endpoint,
                                     String collectionHref,
                                     Instant start,
                                     Instant end,
                                     String username,
                                     String password);

  /**
   * Fetches the calendar data of the given objects in one calendar-multiget
   * REPORT — why a changed collection costs one round trip per batch rather
   * than one per event.
   *
   * @param endpoint the declared server
   * @param collectionHref the collection's server-absolute path
   * @param hrefs the object paths to fetch
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the objects the server returned, never null; an href it did not
   *         return is simply absent, which the caller must tolerate
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the server cannot be reached or errors
   */
  List<CalendarObject> multiget(CalDavEndpoint endpoint,
                                String collectionHref,
                                List<String> hrefs,
                                String username,
                                String password);

  /**
   * Runs an RFC 6578 sync-collection REPORT with a stored token — tier 1,
   * one round trip for everything changed or deleted since that token.
   *
   * @param endpoint the declared server
   * @param collectionHref the collection's server-absolute path
   * @param syncToken the stored token, or null/blank for the initial sync
   *          that establishes one
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the changes and the token to store — or the invalid-token
   *         result when the server rejected the presented token, which the
   *         caller answers by falling through to the listing tier for this
   *         run
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the server cannot be reached or errors
   */
  SyncCollectionResult syncCollection(CalDavEndpoint endpoint,
                                      String collectionHref,
                                      String syncToken,
                                      String username,
                                      String password);

  /**
   * Probes which report tiers a collection's server can serve, in one
   * Depth:0 PROPFIND. Stateless: the answer is bind-time information whose
   * caching belongs to the caller's pair state, not to this client.
   *
   * @param endpoint the declared server
   * @param collectionHref the collection's server-absolute path
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the capability answer, never null
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the server cannot be reached or errors
   */
  ServerCapabilities probeCapabilities(CalDavEndpoint endpoint, String collectionHref, String username, String password);

  /**
   * Reads one object's current data and version in a single GET — what a
   * conflict re-read asks, so the three-way runs against the object as the
   * server holds it NOW rather than as the last sync remembers it.
   *
   * @param endpoint the declared server
   * @param href the object's server-absolute path
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the object as the server returned it, or null when the server
   *         says there is no such object — including BlueMind's way of
   *         saying it, a <b>500</b> for an .ics that is simply not there;
   *         reporting "absent" on a server error is safe rather than
   *         optimistic because every creating write keeps its
   *         {@code If-None-Match: *}, so the worst case is a refused write,
   *         never a silently overwritten one
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the server cannot be reached or errors
   */
  CalendarObject fetchObject(CalDavEndpoint endpoint, String href, String username, String password);

  /**
   * Creates one object — always under {@code If-None-Match: *}, with no
   * unconditional variant on purpose: the server itself then guarantees the
   * PUT can only CREATE, and an object already at that path answers 412
   * instead of being overwritten, whatever race led there.
   *
   * @param endpoint the declared server
   * @param href the object's server-absolute path to create at
   * @param icsData the iCalendar text to store
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the status and, when the server sent them, the stored version
   *         and the object's real location; a 412 is answered, not thrown
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the server cannot be reached or answers a
   *           status that is neither a write nor a precondition refusal
   */
  PutResult putObject(CalDavEndpoint endpoint, String href, String icsData, String username, String password);

  /**
   * Replaces one existing object — guarded the opposite way from
   * {@link #putObject}: {@code If-Match} with the version the caller just
   * read means the server only accepts the write when nobody changed the
   * object since that read. A blank precondition is refused here rather
   * than sent as an unconditional overwrite: "every conditional write stays
   * conditional" is the connector doctrine this client enforces
   * structurally.
   *
   * @param endpoint the declared server
   * @param href the object's server-absolute path
   * @param icsData the iCalendar text to store
   * @param ifMatch the {@code If-Match} value — the ETag the caller's own
   *          read just answered, exactly as sent, quotes and all
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the status and the stored version when the server sent one; a
   *         412 is answered, not thrown
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the server cannot be reached or answers a
   *           status that is neither a write nor a precondition refusal
   * @throws IllegalArgumentException when the precondition is blank
   */
  PutResult updateObject(CalDavEndpoint endpoint,
                         String href,
                         String icsData,
                         String ifMatch,
                         String username,
                         String password);

  /**
   * Deletes one object, conditionally when a version is given.
   *
   * @param endpoint the declared server
   * @param href the object's server-absolute path
   * @param ifMatch the {@code If-Match} value to guard the delete with, or
   *          null to delete unconditionally — tolerated here (unlike
   *          {@link #updateObject}) because a delete of an already-diverged
   *          object is recoverable where an overwrite is not, and some
   *          flows legitimately hold no version
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the status the server answered: 200/204 deleted, 404/410
   *         already gone — a fact, not a fault — and 412 a refused
   *         precondition
   * @throws CalDavAuthenticationException when the credentials are refused
   * @throws CalDavException when the server cannot be reached or answers
   *           any other status
   */
  int deleteObject(CalDavEndpoint endpoint, String href, String ifMatch, String username, String password);

  /**
   * Asks the server to create a calendar collection, display name and
   * optional colour set atomically. The answer is a claim, never a fact:
   * see {@link MkCalendarResult} for why the one statement of success a
   * caller may trust is the collection's presence in its own fresh listing.
   *
   * @param endpoint the declared server
   * @param href the collection's server-absolute path to create at
   * @param displayName the display name to create with
   * @param color the Apple calendar-color to set, or null to set none
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the raw outcome, refusals included — BlueMind refuses
   *         MKCALENDAR outright and that refusal is an answer the caller
   *         maps to its degraded states, not an error
   * @throws CalDavAuthenticationException when the credentials are refused
   *           (401/407 — a 403 here is the refusal, not an auth failure)
   * @throws CalDavException when the server cannot be reached
   */
  MkCalendarResult mkCalendar(CalDavEndpoint endpoint,
                              String href,
                              String displayName,
                              String color,
                              String username,
                              String password);
}
