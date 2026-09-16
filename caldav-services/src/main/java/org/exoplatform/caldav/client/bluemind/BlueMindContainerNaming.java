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

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * Whose calendar a BlueMind collection is, read from the container uid the
 * server names it by (EXO-90275).
 *
 * <p>
 * <b>Why the name, and nothing the server answers about the collection.</b>
 * BlueMind lists every calendar a user subscribed to under that user's own
 * home, and says nothing there that would tell it from their own:
 * {@code DAV:owner} is built from the uid of the home being listed, not from
 * the container's owner ({@code net.bluemind.dav.server.proto.props.webdav.Owner#fetch}),
 * the privilege set grants write whenever the home is the user's
 * ({@code CurrentUserPrivilegeSet#fetch}), and the listing walks the user's
 * subscriptions ({@code DavStore#getCalendarDavResource}, which reads the real
 * owner only to filter). What does carry the owner is the container uid,
 * which is the collection's last path segment, and which BlueMind mints in
 * three shapes ({@code net.bluemind.calendar.api.ICalendarUids},
 * {@code UserCalendarService}):
 * <ul>
 * <li>{@code calendar:Default:<user uid>} — a user's main calendar;</li>
 * <li>{@code calendar:UserCreated:<user uid>:<random uuid>} — a calendar a
 * user created;</li>
 * <li>{@code calendar:<resource uid>} — a resource's calendar
 * ({@code ResourceCalendarHook}: {@code ICalendarUids.resourceCalendar(resource.uid)}).</li>
 * </ul>
 * A uid other than the account's own principal uid names somebody else, and
 * the collection is a subscription: to a person's calendar in the first two
 * shapes, to a resource in the third.
 *
 * <p>
 * <b>What it does not read.</b> A collection created over CalDAV takes the
 * client's own path segment as its container uid
 * ({@code MkCalendarProtocol}: eXo's {@code exo-cal-*}, a bare uuid), and a
 * domain calendar an administrator creates gets a bare uuid
 * ({@code QCreateCalendarModelHandler}) — none of those shapes names an owner
 * and all of them are left to the other witnesses. A client may still create
 * a collection named {@code calendar:<something>} over CalDAV, which BlueMind
 * accepts as it is; the name then points at nobody, which is why a binding is
 * retired only once the owner it points at answers
 * ({@code CaldavSubscriptionRetirementService}). The rule is read only on an
 * account of BlueMind's shape (see {@link #isBlueMindAccount}). Nor does it ask
 * {@code calendar-user-type} (RFC 6638): BlueMind's principal defines no such
 * property ({@code store/path/Principal}). A server that names no principal
 * switches the rule off, as it switches the owner comparison off: an owner
 * that cannot be compared is not evidence of a subscription.
 *
 * <p>
 * The uid comparison ignores case, as {@code CaldavPushService} does when it
 * picks the account's own {@code :Default:} calendar (EXO-90225).
 */
public final class BlueMindContainerNaming {

  /** The type prefix every BlueMind calendar container uid starts with. */
  private static final String CALENDAR_PREFIX     = "calendar:";

  /** The marker of a user's main calendar, after the type prefix. */
  private static final String DEFAULT_MARKER      = "Default:";

  /** The marker of a calendar a user created, after the type prefix. */
  private static final String USER_CREATED_MARKER = "UserCreated:";

  /** The segment BlueMind puts before every uid in a principal or home path. */
  private static final String UIDS_SEGMENT        = "__uids__";

  /**
   * A subscription the naming revealed: whose uid the container carries,
   * and whether that uid is a resource's.
   *
   * @param ownerUid the uid of the person or resource the calendar belongs to
   * @param resource true when the container is a resource's calendar
   */
  public record Subscription(String ownerUid, boolean resource) {
  }

  /**
   * Not instantiable: a rule, not a bean.
   */
  private BlueMindContainerNaming() {
  }

  /**
   * The subscription a listed collection is, when its container uid names
   * somebody other than the account.
   *
   * @param href the collection's path, raw or canonical
   * @param principal the account's own {@code current-user-principal} path,
   *          may be null or blank when the server named none
   * @return the subscription, or null when the collection is not one of the
   *         three shapes, names the account itself, or no principal is known
   */
  public static Subscription subscriptionOf(String href, String principal) {
    String principalPath = principal == null ? null : CalendarCollection.principalPathOf(principal);
    String principalUid = lastSegmentOf(principalPath);
    String collectionPath = CaldavSyncStorage.canonicalHref(href);
    String segment = lastSegmentOf(collectionPath);
    if (StringUtils.isBlank(principalUid) || !StringUtils.startsWithIgnoreCase(segment, CALENDAR_PREFIX)
        || !isBlueMindAccount(principalPath, collectionPath, principalUid)) {
      return null;
    }
    String container = segment.substring(CALENDAR_PREFIX.length());
    if (StringUtils.startsWithIgnoreCase(container, DEFAULT_MARKER)) {
      return personOrOwn(container.substring(DEFAULT_MARKER.length()), principalUid);
    }
    if (StringUtils.startsWithIgnoreCase(container, USER_CREATED_MARKER)) {
      String rest = container.substring(USER_CREATED_MARKER.length());
      String ownerUid = StringUtils.substringBefore(rest, ":");
      if (!rest.contains(":") || StringUtils.isBlank(StringUtils.substringAfter(rest, ":"))) {
        // A seed without an owner in front of it — the shape
        // ICalendarUids.userCreatedCalendar gives a caller that passes a bare
        // uuid. Nobody is named, so nothing is concluded.
        return null;
      }
      return personOrOwn(ownerUid, principalUid);
    }
    if (StringUtils.isBlank(container) || container.contains(":")) {
      return null;
    }
    return StringUtils.equalsIgnoreCase(container, principalUid) ? null : new Subscription(container, true);
  }

  /**
   * The principal path of the person or resource a subscription names, in
   * the account principal's own collection.
   *
   * <p>
   * BlueMind spells every principal {@code <dav root>/principals/__uids__/<uid>/}
   * ({@code CurrentUserPrincipal#fetch}), so the owner's is the account's
   * with the last segment replaced. Built from the account's answer rather
   * than from a constant, so a server mounted under another root keeps it.
   *
   * @param principal the account's own principal path, not blank
   * @param uid the owner's uid, as {@link #subscriptionOf} returned it
   * @return the owner's principal path, slash-terminated like the account's
   */
  public static String principalOf(String principal, String uid) {
    String canonical = CalendarCollection.principalPathOf(principal);
    return StringUtils.substringBeforeLast(canonical, "/") + "/" + uid + "/";
  }

  /**
   * The directory entry uid a BlueMind user principal names: the last segment
   * of {@code <dav root>/principals/__uids__/<uid>/}
   * ({@code CurrentUserPrincipal#fetch}; {@code ResType.PRINCIPAL}).
   *
   * <p>
   * The uid is what BlueMind's REST API addresses a user by — the
   * {@code subject} of an access entry, of a subscription edit — so it is the
   * one thing a recorded principal has to yield for eXo to act on that user's
   * behalf (EXO-90253, EXO-90277). A principal of another shape yields nothing:
   * acting on a guessed uid is worse than not acting.
   *
   * @param principal a principal path, any spelling, may be null
   * @return the uid, or null when the path is not a BlueMind user principal
   */
  public static String userUidOf(String principal) {
    if (StringUtils.isBlank(principal)) {
      return null;
    }
    String canonical = CalendarCollection.principalPathOf(principal);
    String uid = lastSegmentOf(canonical);
    String parent = StringUtils.substringBeforeLast(StringUtils.stripEnd(canonical, "/"), "/");
    return StringUtils.isBlank(uid) || !UIDS_SEGMENT.equals(lastSegmentOf(parent)) || !parent.contains("/principals/") ? null : uid;
  }

  /**
   * Whether the account and the collection have BlueMind's shape, which is
   * the only place the rule is known to hold.
   *
   * <p>
   * BlueMind spells a principal {@code …/principals/__uids__/<uid>/}
   * ({@code CurrentUserPrincipal#fetch}) and lists every calendar of a home,
   * subscriptions included, as {@code …/calendars/__uids__/<uid>/<container
   * uid>/} ({@code ResType#VSTUFF_CONTAINER}, {@code DavStore#getCalendarDavResource}).
   * A {@code calendar:} segment on an account of another shape — a server
   * where a client may name a collection that way for reasons of its own —
   * says nothing about its owner, and is not read.
   *
   * @param principalPath the account's principal, canonical
   * @param collectionPath the collection, canonical
   * @param principalUid the account's own uid
   * @return true when both carry BlueMind's {@code __uids__} spelling and the
   *         collection sits in the account's own home
   */
  private static boolean isBlueMindAccount(String principalPath, String collectionPath, String principalUid) {
    String principalParent = StringUtils.substringBeforeLast(principalPath, "/");
    String home = StringUtils.substringBeforeLast(collectionPath, "/");
    return UIDS_SEGMENT.equals(lastSegmentOf(principalParent))
        && StringUtils.equalsIgnoreCase(lastSegmentOf(home), principalUid)
        && UIDS_SEGMENT.equals(lastSegmentOf(StringUtils.substringBeforeLast(home, "/")));
  }

  /**
   * A subscription to a person's calendar, unless the uid is the account's.
   *
   * @param ownerUid the uid the container carries
   * @param principalUid the account's own uid
   * @return the subscription, or null for a blank uid, a uid with further
   *         structure, or the account's own
   */
  private static Subscription personOrOwn(String ownerUid, String principalUid) {
    if (StringUtils.isBlank(ownerUid) || ownerUid.contains(":") || StringUtils.equalsIgnoreCase(ownerUid, principalUid)) {
      return null;
    }
    return new Subscription(ownerUid, false);
  }

  /**
   * The last segment of a path whose trailing slash is already dropped.
   *
   * @param path a canonical path, may be null
   * @return the segment after the last slash, the path itself when it has
   *         none, null for a blank path
   */
  private static String lastSegmentOf(String path) {
    if (StringUtils.isBlank(path)) {
      return null;
    }
    String stripped = StringUtils.stripEnd(path, "/");
    return stripped.contains("/") ? StringUtils.substringAfterLast(stripped, "/") : stripped;
  }
}
