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

import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;

import io.meeds.common.persistence.PortableSequence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.DynamicUpdate;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
/**
 * The binding between one eXo calendar and one remote collection, for one user
 * on one declared server. This is the row the engine reconciles against: it
 * carries no calendar content, only the identity of the pair and the state of
 * their last exchange.
 *
 * <p>
 * Deliberately in the CalDAV add-on's own schema rather than as columns on
 * agenda's tables. Agenda owns calendars and events; how one of them happens to
 * be mirrored on a DAV server is this add-on's concern, and an add-on that can
 * be uninstalled must not have left columns behind in another domain's tables.
 *
 * <p>
 * <b>{@code @DynamicUpdate}, and it is not decoration.</b> This row carries no
 * version column and has several writers doing read-modify-save over the whole
 * of it: the sync pass writes the token, the ctag and the two timestamps, the
 * failure counter is written on its own, and since EXO-89759 the mirror
 * verification stamps {@link #copySettingsApplied} at the end of a round that
 * can take minutes. Without this annotation every one of those writes flushes an
 * UPDATE over every column, from the snapshot the writer read when it started —
 * so a stamp written by a long round would silently erase whatever a sync that
 * finished in the meantime had committed, and the other way round. With it, only
 * the columns that actually changed are in the statement, and the two writers
 * stop overwriting each other's work.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@DynamicUpdate
@Entity(name = "CaldavCalendarSyncEntity")
@Table(name = "CALDAV_CALENDAR_SYNC")
public class CaldavCalendarSyncEntity {

  @Id
  @PortableSequence(name = "SEQ_CALDAV_CALENDAR_SYNC_ID")
  @Column(name = "ID")
  private Long               id;

  /** The user the pair belongs to; pairs are never shared between users. */
  @Column(name = "USER_IDENTITY_ID")
  private long               userIdentityId;

  /** The declared server registration the remote side lives on. */
  @Column(name = "SERVER_ID")
  private long               serverId;

  /**
   * Agenda's own immutable per-calendar anchor, not the calendar's technical
   * id. The id is a database identity that a restore or a migration may
   * renumber; the sync uid is minted once when the calendar is created and
   * never changes, which is what a binding meant to outlive both sides needs.
   *
   * <p>
   * Null for the MIRROR pair alone — it has no local calendar to anchor to.
   */
  @Column(name = "LOCAL_CALENDAR_SYNC_UID")
  private String             localCalendarSyncUid;

  /**
   * The collection's path on the server, stored canonical: percent-decoded, no
   * trailing slash. Two spellings of one collection are the same collection,
   * and a binding that compares them as raw strings loses its pair the first
   * time a server answers a differently-escaped href.
   */
  @Column(name = "REMOTE_HREF")
  private String             remoteHref;

  /** Which side created the collection; see {@link SyncOrigin}. */
  @Enumerated(EnumType.STRING)
  @Column(name = "ORIGIN")
  private SyncOrigin         origin;

  /**
   * The RFC 6578 synchronisation token from the last successful sync-collection
   * REPORT, when the server supports one. Null falls back to the ctag and ETag
   * comparison tier.
   */
  @Column(name = "SYNC_TOKEN")
  private String             syncToken;

  /**
   * The collection change tag from the last read. Cheap short-circuit: an
   * unchanged ctag means nothing in the collection moved, and the expensive
   * listing can be skipped entirely.
   */
  @Column(name = "CTAG")
  private String             ctag;

  /** The pair's lifecycle state; see {@link CalendarSyncStatus}. */
  @Enumerated(EnumType.STRING)
  @Column(name = "STATUS")
  private CalendarSyncStatus status;

  /**
   * When the last synchronisation started. Written before the exchange, so a
   * run that never finished is visible as a start without an end rather than
   * as no run at all.
   */
  @Column(name = "LAST_SYNC_START")
  private Date               lastSyncStart;

  /** When the last synchronisation completed, successfully or not. */
  @Column(name = "LAST_SYNC_END")
  private Date               lastSyncEnd;

  /**
   * How many synchronisations have failed in a row. Reset on success; it is
   * what turns a transient failure into a deliberate pause instead of an
   * endless retry against a server that is not answering.
   */
  @Column(name = "CONSECUTIVE_FAILURES")
  private int                consecutiveFailures;

  /**
   * The value of the server's copy-settings stamp this pair has already carried
   * through one full comparison of its copies (EXO-89759).
   *
   * <p>
   * Nullable with no default, and null means the pair has applied nothing —
   * which, paired with a server whose own stamp is also null, is exactly the
   * state every upgraded deployment starts in and the reason it does nothing.
   *
   * <p>
   * Declared LAST, because the entity is built positionally through its all-args
   * constructor and appending keeps every existing argument on its own field.
   */
  @Temporal(TemporalType.TIMESTAMP)
  @Column(name = "COPY_SETTINGS_APPLIED")
  private Date               copySettingsApplied;
}
