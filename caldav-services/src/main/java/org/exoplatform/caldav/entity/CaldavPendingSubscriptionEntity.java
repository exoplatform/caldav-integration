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
package org.exoplatform.caldav.entity;

import java.util.Date;

import io.meeds.common.persistence.PortableSequence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.exoplatform.caldav.model.PendingSubscriptionKind;

/**
 * One subscription change eXo owes a colleague's BlueMind account and has not
 * managed to make (EXO-90277): the table {@code CALDAV_PENDING_SUBSCRIPTION}.
 *
 * <p>
 * Modelled on {@link CaldavPendingPushEntity}, with one difference that is
 * the whole point: <b>no foreign key</b>. A pending push belongs to a mapping
 * row; a pending subscription belongs to the sharee, who holds no pair of
 * their own for the shared calendar — they merely received a share — and who
 * is therefore never visited by the sweep's per-account passes. The row names
 * them by identity and server so that a table-driven drain can reach them.
 *
 * <p>
 * One row per colleague, server and container (the unique index): the latest
 * instruction is the one that stands, so a revoke recorded before a pending
 * subscribe was drained replaces it with a removal rather than queueing
 * behind it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "CaldavPendingSubscriptionEntity")
@Table(name = "CALDAV_PENDING_SUBSCRIPTION")
public class CaldavPendingSubscriptionEntity {

  @Id
  @PortableSequence(name = "SEQ_CALDAV_PENDING_SUBSCRIPTION_ID")
  @Column(name = "ID")
  private Long                    id;

  /** The colleague the change is owed to: the sharee's social identity. */
  @Column(name = "USER_IDENTITY_ID")
  private long                    userIdentityId;

  /** The registered server their account is on, the key the pairs use too. */
  @Column(name = "SERVER_ID")
  private long                    serverId;

  /** The BlueMind container uid of the shared calendar. */
  @Column(name = "CONTAINER_UID")
  private String                  containerUid;

  /** Whether they are to be subscribed or unsubscribed. */
  @Enumerated(EnumType.STRING)
  @Column(name = "KIND")
  private PendingSubscriptionKind kind;

  /** How many attempts were refused since the row was written or renewed. */
  @Column(name = "ATTEMPTS")
  private int                     attempts;

  /** When the change was first found owed. */
  @Temporal(TemporalType.TIMESTAMP)
  @Column(name = "SINCE")
  private Date                    since;
}
