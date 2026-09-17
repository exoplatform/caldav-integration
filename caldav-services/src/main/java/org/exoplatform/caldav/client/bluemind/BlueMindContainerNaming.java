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
   * The uid of the home a collection is listed under, when the path has
   * BlueMind's home shape — {@code …/__uids__/<uid>/<container>} — and null
   * otherwise (EXO-90331).
   *
   * <p>
   * The half of {@link #isBlueMindAccount} that needs no principal: it says
   * only whose home the server listed the collection in, which for a
   * subscription is the <em>subscriber's</em>. Read so that the owner's own
   * path can be rebuilt from the subscriber's listing
   * ({@link #hrefInHomeOf}), and so that a grant can tell whether the
   * collection it shares is under a home of this shape at all — a collection
   * that is not is one an RFC 3744 server lists, whose {@code DAV:owner} is
   * read instead.
   *
   * @param href the collection's path, raw or canonical
   * @return the home's uid, or null when the collection does not sit
   *         directly under a {@code __uids__} home
   */
  public static String homeUidOf(String href) {
    String collectionPath = CaldavSyncStorage.canonicalHref(href);
    if (StringUtils.isBlank(collectionPath) || !collectionPath.contains("/")) {
      return null;
    }
    String home = StringUtils.substringBeforeLast(collectionPath, "/");
    String homeUid = lastSegmentOf(home);
    if (StringUtils.isBlank(homeUid) || !UIDS_SEGMENT.equals(lastSegmentOf(StringUtils.substringBeforeLast(home, "/")))) {
      return null;
    }
    return homeUid;
  }

  /**
   * The person a container uid names as the calendar's owner, whichever home
   * it is listed in (EXO-90331).
   *
   * <p>
   * {@link #subscriptionOf} answers the sweep's question — "is this somebody
   * else's?" — and so compares the uid against the account's own. This
   * answers the other question the share mark needs, without an account to
   * compare against: <em>whose</em> is it, by the name alone. Only the two
   * shapes that carry a person's uid answer, {@code calendar:Default:<uid>}
   * and {@code calendar:UserCreated:<uid>:<seed>}; a resource's
   * {@code calendar:<uid>} names no person, and a container BlueMind did not
   * name — eXo's {@code exo-cal-*}, a bare uuid — names nobody. Read only on
   * a collection of BlueMind's home shape ({@link #homeUidOf}), for the
   * reason {@link #isBlueMindAccount} gives.
   *
   * @param href the collection's path, raw or canonical
   * @return the owner's uid as the container spells it, or null when the
   *         name carries none
   */
  public static String ownerUidOf(String href) {
    if (homeUidOf(href) == null) {
      return null;
    }
    String segment = lastSegmentOf(CaldavSyncStorage.canonicalHref(href));
    if (!StringUtils.startsWithIgnoreCase(segment, CALENDAR_PREFIX)) {
      return null;
    }
    String container = segment.substring(CALENDAR_PREFIX.length());
    if (StringUtils.startsWithIgnoreCase(container, DEFAULT_MARKER)) {
      String ownerUid = container.substring(DEFAULT_MARKER.length());
      return StringUtils.isBlank(ownerUid) || ownerUid.contains(":") ? null : ownerUid;
    }
    if (StringUtils.startsWithIgnoreCase(container, USER_CREATED_MARKER)) {
      String rest = container.substring(USER_CREATED_MARKER.length());
      String ownerUid = StringUtils.substringBefore(rest, ":");
      if (!rest.contains(":") || StringUtils.isBlank(ownerUid) || StringUtils.isBlank(StringUtils.substringAfter(rest, ":"))) {
        return null;
      }
      return ownerUid;
    }
    return null;
  }

  /**
   * The same container as it is listed under another uid's home (EXO-90331).
   *
   * <p>
   * BlueMind lists one container under every subscriber's home by the same
   * container uid ({@code DavStore#getCalendarDavResource}), so the owner's
   * own path is the subscriber's with the home's uid replaced by the owner's
   * — which is the path the owner's pair records. Built from the listed path
   * rather than from a constant, so a server mounted under another root keeps
   * it. The uid is spelled as given: the container spells the owner's uid as
   * BlueMind minted it, which is how it spells the owner's home too.
   *
   * @param href a collection path of BlueMind's home shape, raw or canonical
   * @param uid the uid of the home to rebuild the path under
   * @return the canonical path of the container in that home, or null when
   *         the given path is not of the home shape
   */
  public static String hrefInHomeOf(String href, String uid) {
    if (homeUidOf(href) == null || StringUtils.isBlank(uid)) {
      return null;
    }
    String collectionPath = CaldavSyncStorage.canonicalHref(href);
    String home = StringUtils.substringBeforeLast(collectionPath, "/");
    return StringUtils.substringBeforeLast(home, "/") + "/" + uid + "/" + lastSegmentOf(collectionPath);
  }

  /**
   * The uid a BlueMind principal path carries, or null for a principal of
   * another shape (EXO-90331).
   *
   * <p>
   * BlueMind spells every principal {@code …/principals/__uids__/<uid>/}
   * ({@code CurrentUserPrincipal#fetch}); the uid is what a container's name
   * is compared against to say whether the user connected as that principal
   * is the person the container names.
   *
   * @param principal the principal path, raw or canonical, may be null
   * @return its uid, or null when the path is blank or its parent segment is
   *         not {@code __uids__}
   */
  public static String principalUidOf(String principal) {
    if (StringUtils.isBlank(principal)) {
      return null;
    }
    String principalPath = CalendarCollection.principalPathOf(principal);
    String uid = lastSegmentOf(principalPath);
    if (StringUtils.isBlank(uid) || !principalPath.contains("/")
        || !UIDS_SEGMENT.equals(lastSegmentOf(StringUtils.substringBeforeLast(principalPath, "/")))) {
      return null;
    }
    return uid;
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
