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
 * One write eXo owes to one calendar copy and has not managed to make.
 *
 * <p>
 * <b>Why this exists at all.</b> A push that fails used to leave nothing
 * behind. The log said the verification pass would retry, and the verification
 * pass never could: its first gate compares the version the server publishes
 * against the version eXo recorded, and an edit that never reached the server
 * does not move the server's version. The copy was therefore judged untouched
 * before anything was fetched or compared, and stayed wrong for ever. A failed
 * removal was worse still — a destroyed event renders to nothing, and no pass
 * concludes anything from an empty render.
 *
 * <p>
 * So eXo records the obligation on its own side. Nothing here is a claim about
 * the server: it is a claim about what this connector has and has not done,
 * which is the only thing it can know without asking.
 *
 * <p>
 * Flat, for the reason {@link CalendarSync} explains.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PendingPush {

  /** Technical identifier of the record; null before it is first persisted. */
  private Long            id;

  /** The mapping row whose copy is behind. */
  private long            objectSyncId;

  /**
   * Whose calendar the copy sits in. Carried here rather than reached through
   * the mapping row and its pair, because the retry is driven one account at a
   * time and a two-table join to find the owner of an obligation that is
   * normally absent is a join paid for nothing.
   */
  private long            userIdentityId;

  /** Whether the copy has to be written again or removed. */
  private PendingPushKind kind;

  /** The eXo event to render, or null when the obligation is a removal. */
  private Long            localEventId;

  /**
   * The iCalendar identity of the object, which is how a removal addresses it
   * — agenda no longer holds the event a destroyed meeting would be found by.
   */
  private String          icsUid;

  /**
   * How many times the write has been attempted and refused. The bound that
   * stops a permanently refusing server being argued with for ever.
   */
  private int             attempts;

  /** When the obligation was first recorded. */
  private Date            since;

}
