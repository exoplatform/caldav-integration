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

import java.util.List;
import java.util.concurrent.locks.Lock;

import org.exoplatform.caldav.model.CalendarShares.ShareUser;

/**
 * What the host lends a {@link CalendarShareChannel} for one operation: the
 * lock serialising edits of a collection's access, who eXo knows behind a
 * principal, and the subscription follow-up of a confirmed change. Every
 * method answers from eXo's own records or the owner's own endpoint, never
 * with an administrator's key.
 */
public interface ShareHost {

  /**
   * The lock serialising this node's edits of one collection's access.
   *
   * @param calendar the calendar being shared
   * @return the lock, not yet held
   */
  Lock lockOf(SharedCalendar calendar);

  /**
   * The principal eXo recorded for the caller's connection to the server.
   *
   * @param calendar the calendar being shared
   * @return the canonical principal, or null when none is recorded
   */
  String recordedPrincipal(SharedCalendar calendar);

  /**
   * The caller's principal for a read: the recorded one, the server being
   * asked only when nothing is recorded.
   *
   * @param calendar the calendar being read
   * @return the canonical principal, or null when neither says
   */
  String ownerPrincipalForRead(SharedCalendar calendar);

  /**
   * The eXo users connected to the server as one principal, the caller left
   * out; a lookup that fails names nobody.
   *
   * @param calendar the calendar being shared
   * @param principal the canonical principal
   * @return the users, possibly empty
   */
  List<ShareUser> usersConnectedAs(SharedCalendar calendar, String principal);

  /**
   * What a principal no eXo user is connected as calls itself.
   *
   * @param calendar the calendar being shared
   * @param href the principal href as written
   * @param canonical the canonical principal
   * @return the name, never blank
   */
  String displayNameOf(SharedCalendar calendar, String href, String canonical);

  /**
   * Makes the colleague's account follow a change just confirmed on the
   * access list: subscribed after a grant, unsubscribed after a revoke. Never
   * fails the owner's action: what does not land is recorded and retried.
   *
   * @param calendar the calendar
   * @param sharee the colleague
   * @param shareeUid the uid the server addresses the colleague by
   * @param containerUid the calendar's container uid on the server
   * @param subscribe true after a grant, false after a revoke
   */
  void followSubscription(SharedCalendar calendar, ShareRecipient sharee, String shareeUid, String containerUid, boolean subscribe);

}
