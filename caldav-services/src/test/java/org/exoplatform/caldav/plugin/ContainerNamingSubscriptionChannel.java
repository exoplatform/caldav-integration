/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.caldav.plugin;

import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * A subscription channel as a server-specific add-on contributes one, for the
 * host's tests: the server lays out principals and calendars under
 * {@code __uids__}, and a calendar the account subscribes to is listed in the
 * account's home under a container name that carries its owner's uid —
 * {@code calendar:Default:<owner uid>} or
 * {@code calendar:UserCreated:<owner uid>:<seed>} for a person's,
 * {@code calendar:<uid>} for a resource's. That is the layout of the recorded transcripts these tests
 * replay. The sessions and the owner listing are the delegate's, so a test
 * states them.
 */
public class ContainerNamingSubscriptionChannel implements CalendarSubscriptionChannel {

  /** The channel's identifier. */
  public static final String                CHANNEL_ID      = "container-naming";

  /** The segment every principal and calendar home sits under. */
  private static final String               UIDS_SEGMENT    = "__uids__";

  /** The prefix of every calendar container name. */
  private static final String               CALENDAR_PREFIX = "calendar:";

  /** The marker of a person's default calendar. */
  private static final String               DEFAULT_MARKER  = "Default:";

  /** The marker of a calendar a person created. */
  private static final String               USER_CREATED_MARKER = "UserCreated:";

  /** Who opens the sessions and lists the owners, may be null. */
  private final CalendarSubscriptionChannel sessions;

  /**
   * The channel over a delegate for its sessions and owner listing.
   *
   * @param sessions the delegate, typically a mock; null where only the
   *          naming is used
   */
  public ContainerNamingSubscriptionChannel(CalendarSubscriptionChannel sessions) {
    this.sessions = sessions;
  }

  /**
   * The delegate the sessions and the owner listing go to.
   *
   * @return the delegate, may be null
   */
  public CalendarSubscriptionChannel sessions() {
    return sessions;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String id() {
    return CHANNEL_ID;
  }

  /**
   * The uid of a principal under {@code /principals/__uids__/}.
   *
   * @param principal a principal path, may be null
   * @return the uid, or null for any other shape
   */
  @Override
  public String userUidOf(String principal) {
    if (StringUtils.isBlank(principal)) {
      return null;
    }
    String canonical = CalendarCollection.principalPathOf(principal);
    String parent = StringUtils.substringBeforeLast(StringUtils.stripEnd(canonical, "/"), "/");
    String uid = lastSegmentOf(canonical);
    return StringUtils.isBlank(uid) || !UIDS_SEGMENT.equals(lastSegmentOf(parent)) || !parent.contains("/principals/") ? null : uid;
  }

  /**
   * The subscription a container name in the account's own home reveals.
   *
   * @param href the collection's path
   * @param principal the account's principal path, may be null
   * @return the subscription, or null for the account's own calendar or any
   *         other shape
   */
  @Override
  public CalendarSubscription subscriptionOf(String href, String principal) {
    String accountUid = userUidOf(principal);
    String collection = CaldavSyncStorage.canonicalHref(href);
    String container = lastSegmentOf(collection);
    String home = StringUtils.substringBeforeLast(StringUtils.stripEnd(collection, "/"), "/");
    if (accountUid == null || !StringUtils.startsWithIgnoreCase(container, CALENDAR_PREFIX)
        || !StringUtils.equalsIgnoreCase(lastSegmentOf(home), accountUid)
        || !UIDS_SEGMENT.equals(lastSegmentOf(StringUtils.substringBeforeLast(home, "/")))) {
      return null;
    }
    String name = container.substring(CALENDAR_PREFIX.length());
    boolean person = true;
    String ownerUid;
    if (StringUtils.startsWithIgnoreCase(name, DEFAULT_MARKER)) {
      ownerUid = name.substring(DEFAULT_MARKER.length());
    } else if (StringUtils.startsWithIgnoreCase(name, USER_CREATED_MARKER)) {
      String rest = name.substring(USER_CREATED_MARKER.length());
      ownerUid = StringUtils.isBlank(StringUtils.substringAfter(rest, ":")) ? null : StringUtils.substringBefore(rest, ":");
    } else {
      person = false;
      ownerUid = name;
    }
    if (StringUtils.isBlank(ownerUid) || ownerUid.contains(":") || StringUtils.equalsIgnoreCase(ownerUid, accountUid)) {
      return null;
    }
    String canonicalPrincipal = CalendarCollection.principalPathOf(principal);
    return new CalendarSubscription(ownerUid, !person, StringUtils.substringBeforeLast(canonicalPrincipal, "/") + "/" + ownerUid + "/");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> T asSubscriber(CalDavEndpoint endpoint, String userUid, Function<SubscriptionEdits, T> job) {
    return sessions.asSubscriber(endpoint, userUid, job);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CalendarOwners ownersOf(CalDavEndpoint endpoint) {
    return sessions.ownersOf(endpoint);
  }

  /**
   * The last segment of a path.
   *
   * @param path the path, may be null
   * @return its last segment, or null for a blank path
   */
  private static String lastSegmentOf(String path) {
    if (StringUtils.isBlank(path)) {
      return null;
    }
    String stripped = StringUtils.stripEnd(path, "/");
    return stripped.contains("/") ? StringUtils.substringAfterLast(stripped, "/") : stripped;
  }
}
