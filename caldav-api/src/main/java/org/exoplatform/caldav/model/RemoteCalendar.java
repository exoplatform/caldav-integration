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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A calendar of the connected account, in the shape agenda expects from any
 * connector.
 *
 * <p>
 * The identity is the collection href and never the display name: a user
 * renaming a calendar in their own client must not detach whatever eXo has
 * associated with it, and nothing stops two collections sharing a name.
 *
 * <p>
 * Since EXO-90237 a calendar also says whether it is <b>shared</b> with the
 * user rather than theirs, and by whom when that can be told. {@code shared}
 * is distinct from {@code readOnly}: a calendar of the user's own can be
 * read-only because the server grants no write, and a share is read-only
 * because it is somebody else's — agenda groups on the first and locks on
 * the second. The owner travels in three fields, all null when unknown and
 * <b>always null when {@code shared} is false</b> — the user's own calendar
 * names nobody, read-only or not. For
 * a colleague's eXo calendar the deployment knows the owner as one of its
 * users, and names them by identity, login and full name; for a share the
 * server alone reported, the owner is named the same way when exactly one
 * user of this deployment is connected to that server as the owner principal
 * (EXO-90243), and otherwise only a display name is known — the owner
 * principal's {@code DAV:displayname}, or the last segment of its path —
 * and the identity fields stay null. Naming the owner to the viewer is
 * acceptable because the viewer already holds read access the owner
 * granted; what is never exposed is more than the server itself told the
 * viewer, which is why a DAV login is shown only as the server spelled it
 * in the principal path.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RemoteCalendar {

  /** The collection href, which is the calendar's identity. */
  private String  id;

  /** What the user called it. */
  private String  name;

  /** A colour that is always usable, published or derived. */
  private String  color;

  /** Whether events may be written into it. */
  private boolean readOnly;

  /**
   * Whether the calendar is somebody else's, granted to the user rather
   * than owned by them — by the server's word or by this deployment's
   * (EXO-90237). False for the user's own, read-only or not.
   */
  private boolean shared;

  /**
   * The social identity of the eXo user the calendar belongs to, when the
   * owner is a user of this deployment — a colleague whose eXo calendar
   * reached the server through this connector, or the one user connected as
   * the owner principal of a share the server reported (EXO-90243); null
   * otherwise, and always null when {@code shared} is false.
   */
  private Long    ownerIdentityId;

  /**
   * That user's eXo login, under the same condition as
   * {@code ownerIdentityId}; null otherwise, and always null when
   * {@code shared} is false.
   */
  private String  ownerUsername;

  /**
   * How to name the owner to the viewer: the eXo user's full name when the
   * owner is a user of this deployment; for a share the server alone
   * reported and no single user of this deployment is connected as that
   * principal, the owner principal's display name, else the decoded last
   * segment of the principal path; null when nobody can be named, and
   * always null when {@code shared} is false.
   */
  private String  ownerDisplayName;

  /**
   * Whether the owner is a person or a resource (EXO-90275): the list draws
   * a resource with a resource glyph and names it "Resource: …" rather than
   * showing an avatar. {@code RESOURCE} for a BlueMind resource the user
   * subscribed to, whose {@code ownerDisplayName} is then the resource's
   * name and whose identity fields are null; {@code PERSON} for every other
   * share; always null when {@code shared} is false.
   */
  private CalendarOwnerKind ownerKind;

  /**
   * A calendar with its owner named and its kind derived: a share belongs to
   * a person unless said otherwise — the shape every caller built before the
   * kind was carried (EXO-90275).
   *
   * @param id the collection href
   * @param name its display name
   * @param color a usable colour
   * @param readOnly whether events may be written into it
   * @param shared whether it is somebody else's
   * @param ownerIdentityId the owner's identity, when a user of this deployment
   * @param ownerUsername the owner's login, under the same condition
   * @param ownerDisplayName how to name the owner
   */
  public RemoteCalendar(String id,
                        String name,
                        String color,
                        boolean readOnly,
                        boolean shared,
                        Long ownerIdentityId,
                        String ownerUsername,
                        String ownerDisplayName) {
    this(id,
         name,
         color,
         readOnly,
         shared,
         ownerIdentityId,
         ownerUsername,
         ownerDisplayName,
         shared ? CalendarOwnerKind.PERSON : null);
  }

  /**
   * A calendar nothing is said about beyond its own properties: not shared,
   * no owner named — the shape every caller built before ownership was
   * carried (EXO-90237), kept so that a calendar built by hand never
   * classifies as a share by accident.
   *
   * @param id the collection href
   * @param name its display name
   * @param color a usable colour
   * @param readOnly whether events may be written into it
   */
  public RemoteCalendar(String id, String name, String color, boolean readOnly) {
    this(id, name, color, readOnly, false, null, null, null);
  }

}
