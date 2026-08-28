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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.exoplatform.caldav.model.PendingPushKind;

/**
 * One write eXo owes a calendar copy: recorded before it is attempted, deleted
 * when it lands, retried by the sweep until it does or until it has been
 * refused often enough to stop.
 *
 * <p>
 * <b>A table of its own rather than two columns on {@code CALDAV_OBJECT_SYNC}.</b>
 * The mapping row was the obvious place — it is already eXo's record of what it
 * believes it wrote — and it is the wrong one, for two reasons that both bite.
 *
 * <p>
 * The first is that the mapping row has several writers, and every one of them
 * saves it whole. {@code CaldavPushService.writeInto}, the deletion path and
 * the verification pass all read a row into a detached DTO, change one field
 * and save it back, which rewrites every column from the snapshot they read.
 * A marker set between one of those reads and its save would be erased by it,
 * silently — the obligation cleared by a pass that knew nothing about it. That
 * is the read-modify-save hazard the org's backend norms describe, and the
 * clean answer to it is not to put a second writer's state on somebody else's
 * row.
 *
 * <p>
 * The second is cost, and it is the constraint EXO-89716 and EXO-89756 left
 * behind: a converged account must cost nothing. This table is <b>empty</b>
 * when everything has landed, so the retry pass is one index lookup that
 * answers no rows. A marker column on the mapping table would instead have the
 * pass read every copy the user holds, on every sweep, to discover that none of
 * them is owed anything.
 *
 * <p>
 * The row is bound to the mapping by a foreign key that cascades: a copy whose
 * mapping is gone is a copy nothing can be owed to, and an obligation that
 * outlived its subject would be retried against an identifier that no longer
 * resolves.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "CaldavPendingPushEntity")
@Table(name = "CALDAV_PENDING_PUSH")
public class CaldavPendingPushEntity {

  @Id
  @SequenceGenerator(name = "SEQ_CALDAV_PENDING_PUSH_ID", sequenceName = "SEQ_CALDAV_PENDING_PUSH_ID", allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_CALDAV_PENDING_PUSH_ID")
  @Column(name = "ID")
  private Long            id;

  /** The mapping row whose copy is behind; unique, one obligation per copy. */
  @Column(name = "OBJECT_SYNC_ID")
  private long            objectSyncId;

  /** Whose calendar holds the copy: the retry is driven one account at a time. */
  @Column(name = "USER_IDENTITY_ID")
  private long            userIdentityId;

  /** Whether the copy has to be written again or removed. */
  @Enumerated(EnumType.STRING)
  @Column(name = "KIND")
  private PendingPushKind kind;

  /** The eXo event to render, null for a removal whose event no longer exists. */
  @Column(name = "LOCAL_EVENT_ID")
  private Long            localEventId;

  /** The iCalendar identity a removal addresses the object by. */
  @Column(name = "ICS_UID")
  private String          icsUid;

  /** How many times the write has been attempted and refused. */
  @Column(name = "ATTEMPTS")
  private int             attempts;

  /** When the obligation was first recorded. */
  @Temporal(TemporalType.TIMESTAMP)
  @Column(name = "SINCE")
  private Date            since;
}
