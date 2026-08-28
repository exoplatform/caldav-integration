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
}
