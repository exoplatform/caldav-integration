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

import org.exoplatform.services.connector.credentials.PersonalCredentialsProvider;

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
  @SequenceGenerator(name = "SEQ_CALDAV_SERVER_ID", sequenceName = "SEQ_CALDAV_SERVER_ID", allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_CALDAV_SERVER_ID")
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

  /**
   * When a setting of this row that governs the <i>copies</i> last changed
   * (EXO-89759). Nullable with no default, and null means nothing to apply: an
   * upgraded deployment finds every registration unstamped and no mirror behind,
   * so the mechanism ships doing nothing until an administrator acts.
   *
   * <p>
   * Declared LAST, for the same reason as every field appended before it — the
   * entity is built positionally through its all-args constructor.
   */
  @Temporal(TemporalType.TIMESTAMP)
  @Column(name = "COPY_SETTINGS_UPDATED")
  private Date    copySettingsUpdated;

  /**
   * Where the meeting copies pushed to this server are written, as the name of
   * a {@code MirrorTargetKind}.
   *
   * <p>
   * <b>A String rather than an {@code @Enumerated} field, on purpose.</b> A
   * mapped enum throws on a value the running code does not know, and it throws
   * on the <i>read</i> — so a row written by a later version, or edited by hand,
   * would not degrade a setting, it would make the registration unreadable and
   * take every account resolving through it down with it. Read as text and
   * mapped in the storage, an unknown value resolves to the behaviour every
   * deployment already had.
   *
   * <p>
   * NOT NULL with a DEFAULT, unlike every nullable column above it: those
   * distinguish "never asked" from "answered nothing", because something else
   * — a deployment-wide property, or a pair's own stamp — decides for a row
   * nobody has touched. Nothing stands behind this one, so null would mean
   * nothing that empty does not, and the column's own DEFAULT is what backfills
   * every row an existing deployment already holds with the behaviour it
   * already had. The initialiser says the same thing, so a row this code builds
   * and one the database backfilled agree.
   *
   * <p>
   * Declared LAST, after {@link #copySettingsUpdated}: the entity is built
   * positionally through its all-args constructor, and this field is the final
   * argument. The next column appended goes after it.
   */
  @Column(name = "MIRROR_TARGET", nullable = false)
  private String  mirrorTarget = "DEDICATED_CALENDAR";

  /**
   * Name of the {@link org.exoplatform.services.connector.credentials.ConnectorCredentialsProvider}
   * this server is configured to use (e.g. "personal", "bluemind-sudo"). Not
   * to be confused with {@link #providerName} above, which names the remote
   * server product (Stalwart, BlueMind...), not how credentials are obtained
   * for it. Defaults to Personal, the only mode that requires no
   * administrator action beyond this server's own URL - every server
   * declared before this field existed backfills to the same value via the
   * migration's column default. Declared LAST, after the quirk columns, for the
   * positional-constructor reason they explain above.
   */
  @Column(name = "AUTH_PROVIDER_NAME")
  private String  authProviderName = PersonalCredentialsProvider.NAME;
}
