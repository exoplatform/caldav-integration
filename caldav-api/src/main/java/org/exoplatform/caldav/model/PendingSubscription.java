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

import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A subscription change eXo owes a colleague's BlueMind account and has not
 * managed to make (EXO-90277).
 *
 * <p>
 * Recorded the moment the change fails at grant or revoke time, and struck
 * off only when BlueMind accepts it. The colleague holds no synchronised
 * pair of their own by construction — they merely received a share — so the
 * row names them directly, by identity and server, and belongs to no other
 * table.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PendingSubscription {

  /** The row's own identifier. */
  private Long                    id;

  /** The colleague whose BlueMind account owes the change: the sharee. */
  private long                    userIdentityId;

  /** The registered server the colleague's account is on. */
  private long                    serverId;

  /** The BlueMind container uid of the calendar shared with them. */
  private String                  containerUid;

  /** Whether they are to be subscribed to it or unsubscribed from it. */
  private PendingSubscriptionKind kind;

  /** How many times the change has been attempted and refused since it was recorded. */
  private int                     attempts;

  /** When the change was first found owed. */
  private Date                    since;

}
