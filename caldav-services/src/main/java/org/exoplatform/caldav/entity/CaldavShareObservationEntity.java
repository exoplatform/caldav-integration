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
 * <li>a colleague's calendar already bound as an ordinary calendar of the
 * sharee's is not seen as a share, so it is in no row. Two things leave one
 * bound that way, and neither is reachable by the sweep as it stands: a pass
 * from before the outbound prefix was skipped at all (the first inbound sync,
 * EXO-89530, whose {@code isAlreadyOurs} asked only this user's own pairs and
 * so materialised a colleague's {@code exo-cal-} collection on a shared
 * account), and a collection whose slug a server rewrote until it carries no
 * anchor eXo minted (EXO-89590) — the second being a permanent limit of
 * addressing a calendar by the anchor in its slug, not a migration. Adopting
 * either here would count a calendar the sharee holds a writable local copy
 * of, which is a different fact from the one this table records; cleaning
 * them up is the migration {@code CaldavSyncService#skipShare} leaves to a
 * human.</li>
 * </ul>
 * The count this table answers is therefore a floor on the exposure <em>as
 * far as the homes still being read can see it</em>, and the Share drawer —
 * which reads the server live — stays the place that answers <em>who</em>.
 *
 * <p>
 * <b>The one direction it can overstate</b>, stated here once so the other
 * readers of this table can point at it rather than each keep their own list.
 * A sighting stands until a <em>listing</em> contradicts it, and three things
 * leave one uncontradicted:
 * <ol>
 * <li>a sharee whose credentials the server has begun refusing — their
 * bindings are paused ({@code CaldavSyncService#pauseAll}) and they stop
 * listing anything;</li>
 * <li>a sharee removed as an eXo user, who disconnects nothing on the way
 * out;</li>
 * <li>a home that is still read but whose listing comes back empty — it is
 * deliberately not treated as a listing at all
 * ({@code CaldavSyncService#materialiseRemoteCalendars}), because an empty
 * answer is far likelier to be a server having a bad minute than every share
 * being revoked at once.</li>
 * </ol>
 * In the first two the last sighting survives until the account is
 * disconnected ({@code CaldavConnectorServiceImpl}); in the third it survives
 * until a listing that holds something contradicts it. In each the owner may
 * be told a colleague sees a calendar whose access is already gone.
 *
 * <p>
 * All three are deliberate rather than overlooked, and they are the same
 * decision made three times: the share is still live on the server in the
 * common case — a refused password revokes nothing, and an empty listing
 * asserts nothing — so removing on any of these signals would manufacture the
 * false negative this design exists to avoid. For a signal about who can see
 * your calendar, overstating is the safer of the two errors.
 *
 * <p>
 * Keyed by the calendar's <b>anchor</b>, agenda's {@code syncUid}, not by an
 * agenda calendar id: the pair table records the anchor and nothing else, the
 * anchor is what the collection's slug carries, and a calendar id is a number
 * a restore may renumber. The owner's own session resolves anchor to calendar
 * id from their own calendars, which is a local read they already make.
 *
 * <p>
 * {@code @DynamicUpdate}, carried by convention rather than for an effect that
 * can be observed today. One writer touches this row —
 * {@code CaldavShareObservationStorage#reconcile}, which rewrites
 * {@code OWNER_IDENTITY_ID} and {@code OBSERVED} together inside one
 * transaction while the other three columns are immutable for the row's life —
 * so two nodes racing reach the same last-write-wins state with or without it,
 * and no column-set test could tell the two apart. It is kept because the
 * norm asks it of a multi-writer row with no version, and the day a second
 * writer touches a strict subset of this row the annotation must already be
 * there rather than be remembered.
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
