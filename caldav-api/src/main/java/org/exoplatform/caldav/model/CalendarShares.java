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

import java.util.List;

/**
 * Who an eXo personal calendar is shared with on its CalDAV server, as the
 * server's access control list says at the moment of asking (EXO-90253).
 *
 * <p>
 * Never stored: the list belongs to the server, and anybody with another
 * client can change it. Every answer is a fresh read.
 *
 * @param calendarId the agenda calendar the collection belongs to
 * @param sharees one entry per principal the list grants something to, in
 *          the order the server lists them
 * @param subscriptionRequired whether a colleague sees the calendar only after
 *          subscribing to it on the server itself, as on BlueMind
 * @param meetingCopies whether the calendar is also where eXo writes the copies
 *          of the user's eXo meetings, so that a colleague it is shared with
 *          sees those meetings too
 */
public record CalendarShares(long calendarId, List<CalendarSharee> sharees, boolean subscriptionRequired, boolean meetingCopies) {

  /**
   * Shares on a server where a grant is visible to the colleague at once.
   *
   * @param calendarId the agenda calendar
   * @param sharees the sharees
   */
  public CalendarShares(long calendarId, List<CalendarSharee> sharees) {
    this(calendarId, sharees, false, false);
  }

  /**
   * Shares of a calendar holding no copies of the user's eXo meetings.
   *
   * @param calendarId the agenda calendar
   * @param sharees the sharees
   * @param subscriptionRequired whether a colleague must subscribe on the server
   */
  public CalendarShares(long calendarId, List<CalendarSharee> sharees, boolean subscriptionRequired) {
    this(calendarId, sharees, subscriptionRequired, false);
  }

  /**
   * The same shares, saying whether the calendar also holds the copies of the
   * user's eXo meetings.
   *
   * @param holdsMeetingCopies whether eXo writes the meeting copies into it
   * @return the shares with that flag
   */
  public CalendarShares withMeetingCopies(boolean holdsMeetingCopies) {
    return new CalendarShares(calendarId, sharees, subscriptionRequired, holdsMeetingCopies);
  }

  /**
   * One principal a calendar is shared with.
   *
   * @param principal the principal's href as the server wrote it, the
   *          pseudo-principal's Clark name ({@code {DAV:}all}) for
   *          {@link ShareeKind#EVERYONE}, or a fixed key naming the mode for
   *          {@link ShareeKind#PUBLISHED_LINK}, never the link's secret
   * @param kind who the principal is to eXo
   * @param users the eXo users connected to the server as that principal —
   *          several when they share one login — empty unless
   *          {@link ShareeKind#EXO_USERS}
   * @param displayName what to call a principal eXo cannot name as a user:
   *          the server's {@code DAV:displayname}, else the decoded last
   *          segment of its path; null for eXo users, for everyone and for
   *          a published link
   * @param access what the entries grant
   * @param removable whether eXo may remove the grant: a read-only grant to
   *          eXo users, that the server lets be changed
   * @param publishedLink how a link BlueMind published gives access; null
   *          unless {@link ShareeKind#PUBLISHED_LINK}
   */
  public record CalendarSharee(String principal,
                               ShareeKind kind,
                               List<ShareUser> users,
                               String displayName,
                               ShareAccess access,
                               boolean removable,
                               PublishedLinkMode publishedLink) {

    /**
     * A sharee that is not a published link.
     *
     * @param principal the principal's href as the server wrote it, or the
     *          pseudo-principal's Clark name
     * @param kind who the principal is to eXo
     * @param users the eXo users connected to the server as that principal
     * @param displayName what to call a principal eXo cannot name as a user
     * @param access what the entries grant
     * @param removable whether eXo may remove the grant
     */
    public CalendarSharee(String principal, ShareeKind kind, List<ShareUser> users, String displayName, ShareAccess access, boolean removable) {
      this(principal, kind, users, displayName, access, removable, null);
    }
  }

  /** Who a principal on a calendar's access list is, to eXo. */
  public enum ShareeKind {
    /** A principal one or more eXo users are connected to the server as. */
    EXO_USERS,
    /** A principal no eXo user is connected as: someone outside eXo, or a colleague no longer connected. */
    OUTSIDE_EXO,
    /** A pseudo-principal: every user, every authenticated user, every unauthenticated user. */
    EVERYONE,
    /**
     * A link BlueMind's calendar publishing gave out. Its access entry's
     * subject is the secret part of the link's URL, so eXo shows the link's
     * mode alone, and never removes it.
     */
    PUBLISHED_LINK
  }

  /** Which of BlueMind's two kinds of published link a calendar was published as. */
  public enum PublishedLinkMode {
    /** A link BlueMind publishes as private ({@code PublishMode.PRIVATE}). */
    PRIVATE,
    /** A link BlueMind publishes as public ({@code PublishMode.PUBLIC}). */
    PUBLIC
  }

  /** What a principal's entries grant. */
  public enum ShareAccess {
    /** Seeing the calendar and nothing more: what eXo grants. */
    READ,
    /** Something beyond seeing it — writing, managing its access — granted outside eXo. */
    MORE
  }

  /**
   * An eXo user, as a sharee or a candidate.
   *
   * @param identityId the social identity
   * @param username the login
   * @param fullName the full name, the login when the profile has none
   * @param avatarUrl the avatar URL, may be null
   */
  public record ShareUser(long identityId, String username, String fullName, String avatarUrl) {
  }
}
