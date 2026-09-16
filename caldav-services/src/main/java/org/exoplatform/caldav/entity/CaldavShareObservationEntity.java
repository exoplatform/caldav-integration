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
 * One sighting of one eXo user's calendar inside <em>another</em> eXo user's
 * CalDAV home (EXO-90331): the server listed, in the sharee's own account, a
 * collection this deployment minted for the owner's calendar.
 *
 * <p>
 * The fact the owner's row needs, observed from the other end. Sharing is
 * never recorded when it is granted — the grant is made on the server and
 * {@code CaldavCalendarShareService} reads the access list back live, storing
 * nothing — so eXo held no answer to "which of my calendars are exposed" that
 * did not cost one remote ACL read per calendar per panel refresh. What eXo
 * does already have is the other direction: every sharee's synchronisation
 * pass lists their whole calendar home, classifies each collection, and
 * recognises a colleague's eXo calendar by the anchor in its slug
 * ({@code CollectionOwnership#COLLEAGUES_EXO_CALENDAR}). That classification
 * used to be spent on one log line and a process-lifetime memory set. Written
 * down here, the owner's mark becomes a local indexed query and costs the
 * server nothing.
 *
 * <p>
 * <b>A row is an observation, not a grant.</b> It says "on this pass, this
 * sharee's home listed that owner's calendar", which is the server's word
 * rather than eXo's belief — so a share made or removed directly in the
 * server's own web client is seen too, and an eXo grant the server did not
 * apply is not counted. The set is reconciled against each listing
 * ({@code CaldavShareObservationService#observed}), so the same pass repeated
 * writes nothing and a share that stops being listed is removed.
 *
 * <p>
 * <b>What this cannot see</b>, and what a reader of this table must not
 * conclude from its silence:
 * <ul>
 * <li>a sharee who is not an eXo user with a connected CalDAV account never
 * runs a pass, so their share is in no row — the population EXO-90277 already
 * documents as out of scope for sharing from eXo at all;</li>
 * <li>a share granted, or revoked, since the sharee's last pass is not in the
 * table yet: the mark lags by up to one synchronisation period (five minutes
 * by default), which is why it is drawn as a state and never as a
 * confirmation that a grant succeeded;</li>
 * <li>a colleague's calendar that a pass materialised into their eXo before
 * EXO-90234 taught the sweep to skip it is bound as an ordinary calendar of
 * theirs, not seen as a share, and so is in no row. Those bindings are the
 * migration {@code CaldavSyncService#skipShare} records as left to a human;
 * until one is cleaned up its owner sees no mark for that sharee.</li>
 * </ul>
 * The count this table answers is therefore a floor on the exposure, never a
 * ceiling, and the Share drawer — which reads the server live — stays the
 * place that answers <em>who</em>.
 *
 * <p>
 * Keyed by the calendar's <b>anchor</b>, agenda's {@code syncUid}, not by an
 * agenda calendar id: the pair table records the anchor and nothing else, the
 * anchor is what the collection's slug carries, and a calendar id is a number
 * a restore may renumber. The owner's own session resolves anchor to calendar
 * id from their own calendars, which is a local read they already make.
 *
 * <p>
 * {@code @DynamicUpdate}: two nodes may reconcile the same sharee's listing at
 * once, each by a read-modify-save of {@code OBSERVED}, and the statement
 * should carry the column that changed and nothing else — the norm for a row
 * with several writers and no version.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@DynamicUpdate
@Entity(name = "CaldavShareObservationEntity")
@Table(name = "CALDAV_SHARE_OBSERVATION")
public class CaldavShareObservationEntity {

  /**
   * The longest anchor the column holds. Agenda mints a UUID, 36 characters;
   * the column is sized as every other text column of this changelog is, and
   * an anchor it cannot hold is not recorded at all rather than truncated —
   * a truncated anchor could equal another calendar's.
   */
  public static final int ANCHOR_MAX_LENGTH = 250;

  @Id
  @PortableSequence(name = "SEQ_CALDAV_SHARE_OBSERVATION_ID")
  @Column(name = "ID")
  private Long            id;

  /**
   * The eXo user whose calendar it is — the one whose row carries the mark.
   * Resolved from the pair standing behind the collection
   * ({@code CaldavOutboundService#exportingUserOf}).
   */
  @Column(name = "OWNER_IDENTITY_ID")
  private long            ownerIdentityId;

  /**
   * The eXo user whose own CalDAV home listed it — the colleague the calendar
   * is shared with, as far as the server is concerned.
   */
  @Column(name = "SHAREE_IDENTITY_ID")
  private long            shareeIdentityId;

  /**
   * The declared server registration, keyed the way the pairs are keyed: zero
   * for an account attached before registrations existed. Two users seeing
   * each other on two different servers are two observations, and a
   * reconciliation of one server never touches the other's rows.
   */
  @Column(name = "SERVER_ID")
  private long            serverId;

  /**
   * The owner's calendar anchor, agenda's {@code syncUid}, as the collection's
   * slug carried it.
   */
  @Column(name = "CALENDAR_SYNC_UID")
  private String          calendarSyncUid;

  /**
   * When the sighting was last confirmed. Not read by the count — a row exists
   * only while the last listing held it — and kept for the administrator who
   * has to explain a mark, and for the operator reading the table directly.
   */
  @Column(name = "OBSERVED")
  private Date            observed;
}
