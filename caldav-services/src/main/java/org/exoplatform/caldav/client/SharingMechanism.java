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
 * How a calendar server lets an owner grant somebody else access to a
 * collection, and whether eXo offers it there (EXO-90253).
 *
 * <p>
 * The catalogue of granting mechanisms, in code: an entry is a protocol and a
 * verdict on it, and which entry a server gets is <b>selected from what the
 * server says about itself</b> ({@link #of(DavOptions)}), not ticked by an
 * administrator nor frozen on a registration row. Granting is not a CalDAV
 * feature — no RFC defines calendar sharing — so the two servers this add-on
 * targets have nothing in common here: Stalwart takes RFC 3744's {@code ACL}
 * method and nothing else, BlueMind takes Apple's {@code POST CS:share}
 * (design note 52695 B.8.2, review 52697).
 *
 * <p>
 * <b>Offered only where verified.</b> An entry is offered once a grant made
 * through it has been seen to reach the sharee on a real server. Enabling a
 * mechanism later is this flag plus the client method that speaks it, plus a
 * way to confirm a grant was applied — which is not the same on every server:
 * the BlueMind owner cannot read a calendar's ACL back over DAV at all (note
 * 52697, live results of 2026-09-13).
 */
public enum SharingMechanism {

  /**
   * RFC 3744 §8.1: the owner writes the collection's whole access control
   * list with the {@code ACL} method. Verified on Stalwart 0.16 on
   * 2026-09-13: a read grant from Alice to Bob answered 200, and Alice's
   * calendar was then listed in Bob's own home, owned by Alice and
   * read-only for him (note 52696).
   */
  WEBDAV_ACL(true),

  /**
   * Apple CalendarServer sharing: {@code POST} of a {@code CS:share} body to
   * the collection. What BlueMind advertises. Not offered: BlueMind's handler
   * resolves the sharee by e-mail only and answers 200 with no body whether
   * or not it wrote anything, the owner cannot read the result back over
   * DAV, and the sharee sees nothing until they subscribe in BlueMind. No
   * grant made this way has been captured (spike S3 of note 52697 pending).
   */
  CALENDARSERVER_SHARE(false),

  /** No granting mechanism eXo knows of: Google, or a server without RFC 3744. */
  NONE(false);

  /** The compliance class of Apple's sharing extension. */
  static final String CALENDARSERVER_SHARING = "calendarserver-sharing";

  /** The compliance class of the expired draft-pot resource sharing. */
  static final String RESOURCE_SHARING      = "resource-sharing";

  /** The compliance class of RFC 3744 access control. */
  static final String ACCESS_CONTROL        = "access-control";

  /** The compliance class of Apple's calendar-proxy delegation. */
  static final String CALENDAR_PROXY        = "calendar-proxy";

  /** The RFC 3744 method that writes an access control list. */
  static final String ACL_METHOD            = "ACL";

  private final boolean offered;

  /**
   * An entry and its verdict.
   *
   * @param offered whether eXo offers granting through it
   */
  SharingMechanism(boolean offered) {
    this.offered = offered;
  }

  /**
   * Whether eXo offers granting through this mechanism.
   *
   * @return true only for a mechanism verified on a real server
   */
  public boolean isOffered() {
    return offered;
  }

  /**
   * The mechanism a server's own answer to {@code OPTIONS} selects.
   *
   * <p>
   * Read in this order, and the order is the point:
   * <ol>
   * <li>A <b>vendor sharing protocol</b> advertised ({@code
   * calendarserver-sharing}, or draft-pot's {@code resource-sharing}) selects
   * {@link #CALENDARSERVER_SHARE}, whatever else the server says. On such a
   * server the ACL is the storage its own sharing layer manages, and a grant
   * written beside that layer is not what its users or its UI see. BlueMind is
   * the case that makes this rule first: its captured {@code DAV} header
   * advertises {@code access-control} as well as {@code
   * calendarserver-sharing} ({@code bluemind-principal.captured.xml}), so a
   * rule on RFC 3744 alone would offer it.</li>
   * <li>RFC 3744 <b>access control</b> advertised, the {@code ACL} method
   * allowed, and no {@code calendar-proxy} delegation layered on top selects
   * {@link #WEBDAV_ACL}. Stalwart answers exactly this (design A.5, live
   * {@code OPTIONS}). The delegation exclusion keeps out servers whose proxy
   * groups are mapped onto the same ACL (SOGo).</li>
   * <li>Anything else is {@link #NONE}: Google advertises no access control
   * at all.</li>
   * </ol>
   * A server outside the ones characterised that answers the second shape is
   * offered too, with two guards the service applies to every grant: an ACL
   * it cannot parse entirely is never written back, and a grant not visible
   * when the ACL is read again is reported as not applied.
   *
   * @param options what the collection answered, null when nothing was asked
   * @return the mechanism, never null
   */
  public static SharingMechanism of(DavOptions options) {
    if (options == null) {
      return NONE;
    }
    if (options.advertises(CALENDARSERVER_SHARING) || options.advertises(RESOURCE_SHARING)) {
      return CALENDARSERVER_SHARE;
    }
    if (options.advertises(ACCESS_CONTROL) && options.allows(ACL_METHOD) && !options.advertises(CALENDAR_PROXY)) {
      return WEBDAV_ACL;
    }
    return NONE;
  }
}
