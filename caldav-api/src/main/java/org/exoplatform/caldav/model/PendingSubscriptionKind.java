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

/**
 * What eXo still owes a colleague's BlueMind account about one shared
 * calendar (EXO-90277).
 *
 * <p>
 * Two instructions, because the second cannot be inferred from the first. A
 * share granted from eXo is followed by a subscription on the colleague's
 * account, which is what makes the calendar appear in their views; a share
 * revoked is followed by the subscription's removal, without which BlueMind
 * leaves the subscription dangling — it auto-unsubscribes on an access
 * removal for mailboxes only ({@code MailboxAutoSubscribeAclHook.java}) —
 * and the colleague keeps a dead calendar listed. One row per colleague,
 * server and container, so the latest instruction is the one that stands: a
 * revoke recorded before a pending subscribe was drained turns it into a
 * removal, which is harmless when nothing was ever subscribed.
 */
public enum PendingSubscriptionKind {

  /**
   * The colleague has to be subscribed to the container: the owner shared it
   * from eXo and the subscription did not land at grant time.
   */
  SUBSCRIBE,

  /**
   * The colleague has to be unsubscribed from the container: the owner
   * stopped sharing it from eXo and the removal did not land at revoke time.
   */
  UNSUBSCRIBE

}
