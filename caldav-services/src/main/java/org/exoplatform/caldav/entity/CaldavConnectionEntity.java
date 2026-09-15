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

import org.hibernate.annotations.DynamicUpdate;

import io.meeds.common.persistence.PortableSequence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Who one eXo user is on the CalDAV server their account is connected to: the
 * {@code current-user-principal} the server named when the account was last
 * discovered (EXO-90243).
 *
 * <p>
 * The account's identity, which nothing stored before. The engine knew an
 * account only as the parent path of a calendar's href, and that path cannot
 * tell two eXo users on one login (EXO-90190) from a colleague's calendar
 * listed in the user's own home (EXO-90235) — both are pairs under one home.
 * The principal can: the same principal is the same account.
 *
 * <p>
 * The credentials of the connection stay where they are, in the per-user
 * settings; this row holds no secret and nothing a user typed, only what the
 * server answered them. It is written by the discovery that every connection
 * and every synchronisation pass already makes, removed when the account is
 * connected again or disconnected, and read by the shared-account warning and
 * by the owner lookup of a shared calendar.
 *
 * <p>
 * {@code @DynamicUpdate}: several nodes may record the same user, each by a
 * read-modify-save, and the statement should carry the columns that changed
 * and nothing else — the norm for a row with several writers and no version.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@DynamicUpdate
@Entity(name = "CaldavConnectionEntity")
@Table(name = "CALDAV_CONNECTION")
public class CaldavConnectionEntity {

  /**
   * The longest principal the column holds. A longer one is not recorded at
   * all rather than truncated: a truncated principal could equal another
   * account's.
   */
  public static final int PRINCIPAL_MAX_LENGTH = 250;

  @Id
  @PortableSequence(name = "SEQ_CALDAV_CONNECTION_ID")
  @Column(name = "ID")
  private Long            id;

  /** The eXo user whose connected account this is; unique, one per user. */
  @Column(name = "USER_IDENTITY_ID")
  private long            userIdentityId;

  /**
   * The declared server registration the account is connected to, keyed the
   * way the calendar pairs are keyed: zero for an account attached before
   * registrations existed.
   */
  @Column(name = "SERVER_ID")
  private long            serverId;

  /**
   * The principal path the server named, canonical: percent-decoding undone,
   * trailing slash dropped — the form a collection's {@code DAV:owner} is
   * compared in.
   */
  @Column(name = "PRINCIPAL")
  private String          principal;
}
