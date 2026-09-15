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

import java.util.regex.Pattern;

/**
 * How a calendar server lets an owner grant somebody else access to a
 * collection, and whether eXo offers it there (EXO-90253).
 *
 * <p>
 * The catalogue of granting mechanisms, in code: an entry is a protocol and a
 * verdict on it, and which entry a server gets is <b>selected from what the
 * server says about itself</b> ({@link #of(DavOptions, String)}: what the
 * collection advertises, {@link CalDavClient#capabilities}, and, for
 * BlueMind, where the collection lives),
 * not ticked by an
 * administrator nor frozen on a registration row. Granting is not a CalDAV
 * feature — no RFC defines calendar sharing — so the two servers this add-on
 * targets have nothing in common here: Stalwart takes RFC 3744's {@code ACL}
 * method and nothing else, BlueMind takes Apple's {@code POST CS:share}
 * (design note 52695 B.8.2, review 52697).
 *
 * <p>
 * <b>Offered only where every change can be confirmed.</b> An entry is
 * offered when eXo can read back, after each grant and revoke, whether the
 * server applied it, and reports "not applied" otherwise. How it is confirmed
 * differs per server. On Stalwart, the ACL is read over DAV, and a grant was
 * also seen to reach the sharee live (note 52696). On BlueMind, the owner
 * cannot read the ACL over DAV (note 52697, live results of 2026-09-13), so
 * the container's access list is read through BlueMind's REST API.
 * {@link #BLUEMIND_SHARE} is offered on BlueMind's source code and on that
 * read-back. The live capture of a BlueMind grant is still pending: the
 * BlueMind section of the delivery's live test.
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
   * Apple sharing ({@code POST} of a {@code CS:share} body, or the
   * {@code resource-sharing} draft) on a server that is not recognised as
   * BlueMind. Not offered: nothing tells eXo how such a server resolves a
   * sharee or how a change could be read back. A collection in BlueMind's
   * layout is {@link #BLUEMIND_SHARE} instead.
   */
  CALENDARSERVER_SHARE(false),

  /**
   * Apple sharing as BlueMind's DAV server implements it (BlueMind source,
   * {@code plugins/net.bluemind.dav.server/.../proto/sharing/SharingProtocol.java}):
   * a {@code POST} of {@code CS:share} on a calendar collection names the
   * sharee by e-mail, and the server rewrites the container's access list
   * through its own container management. Offered, with two things eXo does
   * around it because the protocol cannot: the server answers 200 whatever
   * happened, so every change is confirmed by reading the container's access
   * list back through BlueMind's REST API; and the server rewrites or removes
   * <em>every</em> entry of the sharee, so a sharee holding anything but plain
   * reading is refused rather than downgraded.
   */
  BLUEMIND_SHARE(true),

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

  /**
   * A calendar collection as BlueMind's DAV server lays it out
   * ({@code plugins/net.bluemind.dav.server/.../store/ResType.java},
   * {@code VSTUFF_CONTAINER}): the owner's directory entry uid, then the
   * container uid, under {@code /dav/calendars/__uids__/}.
   */
  static final Pattern BLUEMIND_COLLECTION = Pattern.compile("/dav/calendars/__uids__/[^/]+/[^/]+");

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
   * @return true for a mechanism whose every change eXo can confirm by reading it back
   */
  public boolean isOffered() {
    return offered;
  }

  /**
   * The mechanism a collection's advertised DAV classes and allowed methods
   * select, read from those alone.
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

  /**
   * The mechanism what a collection advertises
   * ({@link CalDavClient#capabilities}) selects, given where the collection
   * lives.
   *
   * <p>
   * Apple sharing is offered on one server only, and BlueMind is recognised
   * by two independent facts that must both hold: it advertises
   * {@code calendarserver-sharing} in the {@code DAV} header of its PROPFIND
   * answers ({@code PropFindProtocol.java:125}, captured in
   * {@code bluemind-principal.captured.xml}; where its {@code OPTIONS} is
   * answered bare in front of its DAV server, as on the deployments observed,
   * {@link CalDavClient#capabilities} reads them from a PROPFIND), and the
   * collection has the path
   * BlueMind's DAV server gives every calendar,
   * {@code /dav/calendars/__uids__/<owner uid>/<container uid>/}
   * ({@code ResType.VSTUFF_CONTAINER}). Apple's CalendarServer uses the same
   * {@code __uids__} layout without the {@code /dav} root, and Nextcloud and
   * iCloud use other paths; those stay not offered. The service confirms the
   * recognition before any change: BlueMind's REST API must accept the
   * owner's login, which no other server answers.
   *
   * @param options what the collection answered, null when nothing was asked
   * @param collectionHref the collection's path, may be null
   * @return the mechanism, never null
   */
  public static SharingMechanism of(DavOptions options, String collectionHref) {
    SharingMechanism mechanism = of(options);
    if (mechanism == CALENDARSERVER_SHARE && options.advertises(CALENDARSERVER_SHARING) && isBlueMindCollection(collectionHref)) {
      return BLUEMIND_SHARE;
    }
    return mechanism;
  }

  /**
   * Whether a path is a calendar collection as BlueMind's DAV server names one.
   *
   * @param collectionHref the collection's path, may be null
   * @return true for {@code /dav/calendars/__uids__/<uid>/<container>/}
   */
  public static boolean isBlueMindCollection(String collectionHref) {
    return collectionHref != null
        && BLUEMIND_COLLECTION.matcher(CalendarCollection.principalPathOf(collectionHref)).matches();
  }
}
