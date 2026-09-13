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
package org.exoplatform.caldav.client;

import java.net.URI;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/**
 * One calendar collection as a PROPFIND listed it: everything the sync
 * engine binds a pair on, and nothing it does not need. Hrefs are answered
 * as server-absolute raw paths — exactly the shape they travel back out in,
 * so a round trip never re-encodes them.
 *
 * @param href the collection's server-absolute raw path, trailing slash as
 *          the server sent it
 * @param displayName the collection's display name, or null — data, never
 *          identity: nothing may key on it
 * @param ctag the CalendarServer ctag, or null when the server has none
 * @param syncToken the RFC 6578 sync token, or null when unsupported
 * @param color the Apple calendar-color, or null
 * @param writable whether the current user's privilege set carries write —
 *          false as well when the server answered no privilege set at all,
 *          which {@code privilegesAnswered} is there to tell apart
 * @param components the component types the collection declares, empty when
 *          the server did not say
 * @param owner the {@code DAV:owner} of the collection as a server-absolute
 *          raw path, or null when the server answered none — the principal
 *          the collection belongs to, which is not always the user listing
 *          it (EXO-90235)
 * @param privilegesAnswered whether the server answered a
 *          {@code current-user-privilege-set} at all; false is the Google
 *          shape, where {@code writable} says nothing because nothing was
 *          said
 */
public record CalendarCollection(String href,
                                 String displayName,
                                 String ctag,
                                 String syncToken,
                                 String color,
                                 boolean writable,
                                 Set<String> components,
                                 String owner,
                                 boolean privilegesAnswered) {

  /**
   * A collection whose server declared no component set.
   * <p>
   * The six-argument form every caller used before the set was read: an
   * undeclared set is an empty one, which {@link #holdsEvents()} reads as
   * "the server did not say" — the RFC 4791 default of supporting everything.
   * It names no owner and answers no privilege set either, so what it
   * describes is a collection nothing has been said about — never a share
   * (see {@link #isSharedWith(String)}).
   *
   * @param href the collection path
   * @param displayName the name the server gives it
   * @param ctag the collection change tag
   * @param syncToken the RFC 6578 sync token
   * @param color the colour the server carries
   * @param writable whether the caller may write to it
   */
  public CalendarCollection(String href,
                            String displayName,
                            String ctag,
                            String syncToken,
                            String color,
                            boolean writable) {
    this(href, displayName, ctag, syncToken, color, writable, Set.of());
  }

  /**
   * A collection whose server named no owner and answered no privilege set.
   * <p>
   * The seven-argument form every caller used before ownership was read; it
   * keeps meaning what it meant, a collection about which nothing beyond its
   * own properties is known, so a record built by hand never classifies as a
   * share by accident.
   *
   * @param href the collection path
   * @param displayName the name the server gives it
   * @param ctag the collection change tag
   * @param syncToken the RFC 6578 sync token
   * @param color the colour the server carries
   * @param writable whether the caller may write to it
   * @param components the component types it declares
   */
  public CalendarCollection(String href,
                            String displayName,
                            String ctag,
                            String syncToken,
                            String color,
                            boolean writable,
                            Set<String> components) {
    this(href, displayName, ctag, syncToken, color, writable, components, null, false);
  }

  /**
   * Whether this collection holds events at all.
   * <p>
   * RFC 4791 §5.2.3 makes {@code supported-calendar-component-set} optional,
   * and a collection that omits it supports every component. An empty set is
   * therefore a server that did not say, not a server that said "nothing" —
   * both answer true. Only an explicit set without VEVENT answers false, which
   * is what keeps a task list from becoming a calendar.
   *
   * @return true unless the server explicitly excluded events
   */
  public boolean holdsEvents() {
    return components == null || components.isEmpty() || components.contains("VEVENT");
  }

  /**
   * Whether the server says this collection is somebody else's, granted to
   * the user rather than owned by them.
   *
   * <p>
   * Two signals, either of which is enough, both read from what the server
   * answered and neither guessed from the path. The collection names an
   * <b>owner</b> that is not the user's own principal; or the server answered
   * a <b>privilege set</b> that grants no write. Observed live on Stalwart
   * 0.16 (EXO-90235): after a colleague granted the user {@code DAV:read} on
   * her calendar, the Depth-1 listing of the user's own home returned her
   * collection at her path, with {@code DAV:owner} naming her principal and
   * {@code read, read-current-user-privilege-set} as the whole privilege set
   * — both signals at once. The engine had read neither, and turned her
   * calendar into the user's own, editable one.
   *
   * <p>
   * <b>Silence is not a signal.</b> A server that names no owner cannot be
   * compared, and one that answers no privilege set has not said the user may
   * not write — Google answers none, and reading its silence as read-only
   * would turn every Google calendar into a share. So an unknown owner, an
   * unknown principal and an unanswered privilege set each leave their signal
   * off, and a collection nothing was said about is the user's own, exactly
   * as it was before ownership was read. Only what the server stated counts
   * against it.
   *
   * <p>
   * Compared as paths, not as strings: Stalwart spells the principal segment
   * with the login percent-encoded ({@code /dav/pal/alice%40stalwart.local/})
   * and nothing forbids a server from answering the owner encoded and the
   * principal not, or one with a trailing slash and the other without. A
   * comparison that failed on either would silently classify the user's own
   * calendars as shares, which is the reverse of this method's defect.
   *
   * <p>
   * What this does <b>not</b> tell apart is a share from a subscribed copy on
   * a server that misreports one: BlueMind lists a calendar the user
   * subscribed to under their own home, names <em>them</em> as its owner and
   * grants them the full privilege set, so neither signal fires there. The
   * signal that does hold there is not the server's but this deployment's —
   * the slug of a colleague's <em>eXo</em> calendar carries the anchor eXo
   * exported it under — and it is read beside this method, not inside it, by
   * {@code CaldavOutboundService#ownershipOf} (EXO-90234), which the sweep
   * and the read-through both classify with. This method deliberately keeps
   * the two server rules above as the whole of what it reads.
   *
   * @param currentUserPrincipal the {@code current-user-principal} of the
   *          account listing this collection, as a server-absolute path;
   *          null or blank when the server named none, which switches the
   *          owner signal off
   * @return true when the server said the collection is not the user's own
   *         to write into
   */
  public boolean isSharedWith(String currentUserPrincipal) {
    return isOwnedByAnother(currentUserPrincipal) || (privilegesAnswered && !writable);
  }

  /**
   * Whether the server names an owner other than the user listing it.
   *
   * @param currentUserPrincipal the listing account's principal path, may be
   *          null or blank
   * @return true only when both sides are known and differ as paths
   */
  private boolean isOwnedByAnother(String currentUserPrincipal) {
    if (StringUtils.isBlank(owner) || StringUtils.isBlank(currentUserPrincipal)) {
      return false;
    }
    return !comparablePath(owner).equals(comparablePath(currentUserPrincipal));
  }

  /**
   * A principal path in the one shape two answers of the same server can be
   * compared in: percent-decoding undone, trailing slash dropped.
   *
   * <p>
   * Decoded through {@link URI} rather than {@code URLDecoder}, which would
   * also turn a {@code +} into a space — a transformation meant for form
   * bodies, not paths. A value {@link URI} cannot parse is compared as it
   * came, trimmed of its slash: a malformed owner href is the server's to
   * explain, and treating it as "another owner" would hide a calendar over a
   * spelling.
   *
   * @param path a server-absolute raw path
   * @return the comparable form
   */
  private static String comparablePath(String path) {
    String trimmed = path.trim();
    try {
      String decoded = URI.create(trimmed).getPath();
      if (StringUtils.isNotBlank(decoded)) {
        trimmed = decoded;
      }
    } catch (IllegalArgumentException e) {
      // Compared raw, below.
    }
    return StringUtils.stripEnd(trimmed, "/");
  }
}
