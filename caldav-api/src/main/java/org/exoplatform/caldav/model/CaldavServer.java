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
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A CalDAV server registration, as an administrator declared it: where the
 * server lives and how it is presented to users. Deliberately credential-free
 * — per-user secrets stay in the per-user settings storage — so this object
 * can travel through any REST response without leaking anything.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CaldavServer {

  /**
   * Technical identifier of the registration row.
   */
  private long    id;

  /**
   * Name of the agenda remote provider this registration is bridged to:
   * {@code agenda.caldavCalendar} for the seed registration,
   * {@code agenda.caldavCalendar.<id>} for the others. System-derived, never
   * user-typed, and unique.
   */
  private String  providerName;

  /**
   * Display name of the server, as users see it in the connectors list.
   */
  private String  name;

  /**
   * Optional description shown beside the name.
   */
  private String  description;

  /**
   * Base URL of the CalDAV server, optionally holding a {username}
   * placeholder. Not a secret: the browser itself needs it to speak WebDAV.
   */
  private String  serverUrl;

  /**
   * Whether users may connect to this server. Deactivation propagates to the
   * agenda remote provider, which is what actually hides the connector.
   */
  private boolean active;

  /**
   * Font-icon class chosen from the icon picker; the fallback identity when
   * no image was uploaded.
   */
  private String  icon;

  /**
   * Identifier of the uploaded image in FileService, when one exists. Sending
   * it back null (or 0) on an update removes the stored image.
   */
  private Long    imageFileId;

  /**
   * Transient, inbound only: identifier of a fresh browser upload the storage
   * turns into a FileService file. Never persisted, never served back.
   */
  private String  imageUploadId;

  /**
   * Transient, outbound only: the URL the browser fetches the stored image
   * from, versioned by its last modification. Null when no image exists.
   */
  private String  imageUrl;

  /**
   * Whether eXo writes its Accept / Decline / Tentative links into the
   * description of every meeting copy pushed to this server.
   *
   * <p>
   * Named after what eXo does, never after what the server can do: whether a
   * native RSVP control appears is the <i>client's</i> decision — BlueMind's
   * web UI gates it on the default calendar while Thunderbird against the same
   * account decides for itself — so it can never be derived and stays
   * explicit. Default true, because the failure modes are asymmetric:
   * redundant links are a mild annoyance, missing links leave a user unable to
   * answer at all.
   *
   * <p>
   * Declared LAST on purpose — the model is built positionally through its
   * all-args constructor, and appending keeps every existing argument on its
   * own field. The initialiser is what makes the default a real one: it runs
   * before every constructor, so a row built through the no-args constructor
   * (a JSON body that never mentioned the field, most plausibly) carries the
   * links rather than silently dropping them.
   */
  private boolean answerLinksInCopy = true;

  /**
   * The property-name patterns this server is excused for <b>adding</b> to the
   * copies it stores — proprietary hints eXo never writes and does not need to
   * understand.
   *
   * <p>
   * <b>Null and empty say different things, and the difference is the whole
   * upgrade story.</b> Null means the row has never been asked, so the
   * deployment-wide {@code exo.agenda.caldav.mirror.ignoredProperties} still
   * decides — which is why upgrading changes nothing. An empty string means an
   * administrator opened the drawer and excused nothing, and it must not fall
   * back to the global list, or unticking the last box would be a no-op.
   *
   * <p>
   * Written by ticking, never typed: the drawer offers what the sweep has seen
   * and writes the patterns {@link ServerQuirk} declares for it, so no
   * mistyped name can reach here from the UI.
   */
  private String  ignoredProperties;

  /**
   * The property-name patterns this server is excused for <b>not keeping</b>
   * faithfully — a property eXo writes that the copy comes back without, and,
   * for the one catalogue entry that declares it, a property whose value the
   * server rewrites.
   *
   * <p>
   * Same null-versus-empty rule as {@link #ignoredProperties}, falling back to
   * {@code exo.agenda.caldav.mirror.droppedProperties}.
   */
  private String  droppedProperties;

  /**
   * What eXo <b>leaves out</b> of the copies it writes to this server.
   *
   * <p>
   * The third list, and the one that is not like the other two. Those change
   * what eXo <i>notices</i>; this one changes what eXo <i>writes into somebody's
   * calendar</i>, so it is stored apart from them rather than folded in — a
   * reader of the row, like an administrator reading the drawer, must be able to
   * see which decision is which.
   *
   * <p>
   * It carries cases rather than property names — {@code SOLO-ORGANIZER}, the
   * organizer of an event with no other participants — precisely so that it can
   * never be read as an excusal by the comparison, whose lists only accept a
   * property eXo actually writes.
   *
   * <p>
   * Same null-versus-empty rule as the two above, except that nothing global
   * ever stood here: there is no deployment-wide property to fall back to, so
   * null and empty both mean "eXo writes everything it writes".
   */
  private String  omittedProperties;

  /**
   * Transient, outbound only: what this server has actually been seen doing,
   * with how often and whether it is excused today. Never accepted on a write
   * — it is the sweep's observation, not an administrator's input — and never
   * persisted from here; the storage keeps its own rolling summary.
   */
  private List<ObservedQuirk> observedQuirks;

  /**
   * When a setting of this registration that governs the <i>copies</i> last
   * changed — the stamp a mirror compares its own against to know it owes the
   * copies already on the server one full comparison (EXO-89759).
   *
   * <p>
   * <b>Null means nothing to apply</b>, and that is what makes the mechanism
   * behaviour-neutral on an upgrade: every registration that already exists
   * starts unstamped, no mirror finds itself behind, and nothing happens until
   * an administrator actually changes one of the settings concerned.
   *
   * <p>
   * <b>Never accepted from a caller.</b> The write path recomputes it from the
   * row it is about to overwrite, so a JSON body that carries a stamp — stale,
   * invented, or copied from another server — cannot make every mirror in the
   * deployment re-compare, nor stop one that should. Which settings move it is
   * {@link org.exoplatform.caldav.utils.CopySettingsFingerprint}'s single
   * decision.
   *
   * <p>
   * Declared LAST on purpose, for the same reason as every field appended
   * before it: the model is built positionally through its all-args
   * constructor, and appending keeps every existing argument on its own field.
   */
  private Date    copySettingsUpdated;
}
