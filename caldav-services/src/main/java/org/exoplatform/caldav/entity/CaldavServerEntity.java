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

import io.meeds.common.persistence.PortableSequence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A CalDAV server declared by an administrator. Holds only what every user is
 * allowed to see — name, description, URL, activation — and never a
 * credential: per-user secrets live in the per-user settings storage, so a
 * read of this table can be served to any authenticated user.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "CaldavServerEntity")
@Table(name = "CALDAV_SERVER")
public class CaldavServerEntity {

  @Id
  @PortableSequence(name = "SEQ_CALDAV_SERVER_ID")
  @Column(name = "ID")
  private Long    id;

  @Column(name = "PROVIDER_NAME")
  private String  providerName;

  @Column(name = "NAME")
  private String  name;

  @Column(name = "DESCRIPTION")
  private String  description;

  @Column(name = "SERVER_URL")
  private String  serverUrl;

  @Column(name = "ACTIVE")
  private boolean active;

  /**
   * Font-icon class chosen from the icon picker; the fallback identity when
   * no image was uploaded.
   */
  @Column(name = "ICON")
  private String  icon;

  /**
   * Identifier of the uploaded image in FileService, when an administrator
   * uploaded one; null otherwise.
   */
  @Column(name = "IMAGE_FILE_ID")
  private Long    imageFileId;

  /**
   * Whether eXo writes its Accept / Decline / Tentative links into the
   * description of every meeting copy pushed to this server.
   *
   * <p>
   * Declared LAST on purpose: the entity is built positionally through its
   * all-args constructor, and appending keeps every existing argument on its
   * own field. The initialiser mirrors the column's own DEFAULT TRUE, so a row
   * this code builds and one the database backfilled say the same thing.
   */
  @Column(name = "ANSWER_LINKS_IN_COPY", nullable = false)
  private boolean answerLinksInCopy = true;

  /**
   * Property-name patterns this server is excused for adding to the copies it
   * stores.
   *
   * <p>
   * <b>Nullable on purpose.</b> Null is "never asked", and it is what makes the
   * deployment-wide property go on deciding for every row an upgrade finds; an
   * empty string is an administrator's own answer of "nothing". A NOT NULL
   * DEFAULT '' would have collapsed the two and silenced the global lever on
   * every existing deployment the moment this shipped.
   */
  @Column(name = "IGNORED_PROPERTIES")
  private String  ignoredProperties;

  /**
   * Property-name patterns this server is excused for not keeping faithfully.
   * Same nullable-means-never-asked rule as {@link #ignoredProperties}.
   */
  @Column(name = "DROPPED_PROPERTIES")
  private String  droppedProperties;

  /**
   * The cases eXo leaves out of the copies it writes to this server.
   *
   * <p>
   * Its own column rather than a third meaning stacked onto
   * {@link #droppedProperties}: those two say what eXo tolerates, this one says
   * what eXo writes, and a single column holding both kinds of decision is a
   * column nobody can read.
   */
  @Column(name = "OMITTED_PROPERTIES")
  private String  omittedProperties;

  /**
   * The rolling summary of what this server has been seen doing, as
   * {@code DIRECTION:PROPERTY=COUNT} entries separated by {@code ;}.
   *
   * <p>
   * <b>Why a column and not a table.</b> It has to survive a restart — it is
   * the evidence the administrator ticks from, and an empty drawer after a
   * restart is an empty drawer exactly when somebody is investigating. It also
   * has to stay cheap: one bounded row per server, not one row per copy per
   * divergence, which is the second mapping table this deliberately is not. It
   * is read with the registration the drawer already fetches, so it costs no
   * extra query at all.
   *
   * <p>
   * Approximate by construction: counts are accumulated in memory and flushed
   * at most once per sweep per server, so a crash loses at most one pass. The
   * number answers "does this server always do this, or did it happen once",
   * and nothing finer.
   */
  @Column(name = "OBSERVED_QUIRKS")
  private String  observedQuirks;
}
