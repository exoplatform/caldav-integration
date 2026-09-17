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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/**
 * One access control entry of a collection's {@code DAV:acl}, as RFC 3744
 * §5.5 defines it: who it applies to, whether it grants or denies, which
 * privileges, and whether the server lets it be changed (EXO-90253).
 *
 * <p>
 * The client parses every entry into this shape <em>entirely</em> or not at
 * all ({@link CollectionAcl#understood()}): an {@code ACL} request replaces
 * every entry that is neither protected nor inherited (§8.1), so an entry this
 * record could not represent would be deleted by writing the list back. The
 * shape is therefore the whole grammar of §5.5 and no less.
 *
 * <p>
 * Privileges are held as Clark names, {@code {namespace}local} —
 * {@code {DAV:}read}, {@code {urn:ietf:params:xml:ns:caldav}read-free-busy} —
 * so a privilege of any namespace survives the round trip.
 *
 * @param principal whom the entry applies to
 * @param inverted whether it applies to everyone <em>except</em> that
 *          principal ({@code DAV:invert})
 * @param deny whether it denies rather than grants
 * @param privileges the privileges granted or denied, as Clark names, never
 *          empty for a parsed entry
 * @param protectedEntry whether the server marks it {@code DAV:protected}:
 *          it cannot be changed, and it is never sent back
 * @param inheritedFrom the resource it is inherited from
 *          ({@code DAV:inherited}), null when it is this collection's own;
 *          an inherited entry is never sent back either
 */
public record AccessControlEntry(AcePrincipal principal,
                                 boolean inverted,
                                 boolean deny,
                                 Set<String> privileges,
                                 boolean protectedEntry,
                                 String inheritedFrom) {

  /** The namespace of WebDAV's own elements. */
  public static final String      DAV_NS                    = "DAV:";

  /** The namespace of CalDAV's elements. */
  public static final String      CALDAV_NS                 = "urn:ietf:params:xml:ns:caldav";

  /** {@code DAV:read}, the privilege a "can view" share grants. */
  public static final String      READ                      = clark(DAV_NS, "read");

  /**
   * {@code DAV:write}, the privilege a "can edit" share adds (EXO-90378).
   * RFC 3744 §3.12 aggregates {@code write-properties}, {@code write-content},
   * {@code bind} and {@code unbind} under it — changing an event, adding one
   * and removing one, which is exactly what an edit share is for.
   */
  public static final String      WRITE                     = clark(DAV_NS, "write");

  /**
   * The privileges that let a principal see a calendar and nothing more: what
   * "can view" means when an entry is read back. {@code DAV:read} itself;
   * {@code read-current-user-privilege-set}, which Stalwart reports beside it
   * on a read share; and {@code CALDAV:read-free-busy}, which RFC 4791 §6.1.1
   * aggregates under {@code DAV:read}.
   */
  public static final Set<String> READ_ONLY_PRIVILEGES      = Set.of(READ,
                                                                     clark(DAV_NS, "read-current-user-privilege-set"),
                                                                     clark(CALDAV_NS, "read-free-busy"));

  /**
   * The privileges an edit share may hold when it is read back (EXO-90378):
   * the read-only set plus {@code DAV:write}. The grant eXo <b>writes</b> is
   * narrower still — {@code DAV:read} and {@code DAV:write}, see
   * {@link #editGrantTo(String)} — but a server reports the read-ish
   * privileges it aggregates beside them, exactly as it does for a read share.
   */
  public static final Set<String> EDIT_PRIVILEGES           = Set.of(READ,
                                                                     WRITE,
                                                                     clark(DAV_NS, "read-current-user-privilege-set"),
                                                                     clark(CALDAV_NS, "read-free-busy"));

  /**
   * The characters left as they are when a principal path is spelled into an
   * href: RFC 3986 §2.3's unreserved set. Everything else in a segment is
   * percent-encoded, which is how Stalwart spells its own principals
   * ({@code /dav/pal/alice%40stalwart.local/}).
   */
  private static final String     UNRESERVED                = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

  /**
   * The entry eXo writes to share a calendar: grant {@code DAV:read} to one
   * principal, nothing else.
   *
   * @param principalHref the principal's server-absolute href
   * @return the entry
   */
  public static AccessControlEntry readGrantTo(String principalHref) {
    return new AccessControlEntry(AcePrincipal.href(principalHref), false, false, Set.of(READ), false, null);
  }

  /**
   * The entry eXo writes to share a calendar for editing (EXO-90378): grant
   * {@code DAV:read} and {@code DAV:write} to one principal, nothing else.
   * <p>
   * {@code DAV:read} is written even though a colleague who may write must be
   * able to read: it is what makes the entry <b>recognisable</b>. A grant
   * carrying {@code DAV:write} and no read is what Stalwart reports for a
   * right given through JMAP — "may delete", "may write all" — and eXo never
   * writes that shape, so it can keep refusing to carry one back
   * ({@link #grantsEditOnly()} demands both).
   *
   * @param principalHref the principal's server-absolute href
   * @return the entry
   */
  public static AccessControlEntry editGrantTo(String principalHref) {
    return new AccessControlEntry(AcePrincipal.href(principalHref), false, false, Set.of(READ, WRITE), false, null);
  }

  /**
   * Whether an {@code ACL} request may carry this entry: neither protected nor
   * inherited (RFC 3744 §8.1 leaves both where they are, and sending one is a
   * conflict the server refuses).
   *
   * @return true for an entry of this collection's own that may be rewritten
   */
  public boolean isModifiable() {
    return !protectedEntry && inheritedFrom == null;
  }

  /**
   * Whether this entry lets a principal read the calendar: a grant, not
   * inverted, naming {@code DAV:read} or {@code DAV:all}.
   *
   * @return true when it grants read
   */
  public boolean grantsRead() {
    return !deny && !inverted && (privileges.contains(READ) || privileges.contains(clark(DAV_NS, "all")));
  }

  /**
   * Whether this entry is a plain grant carrying nothing beyond seeing the
   * calendar.
   *
   * @return true when every privilege is a read-only one
   */
  public boolean grantsReadOnly() {
    return !deny && !inverted && READ_ONLY_PRIVILEGES.containsAll(privileges);
  }

  /**
   * Whether this entry is a plain grant carrying exactly what an eXo edit
   * share grants (EXO-90378): reading and writing the calendar, and nothing
   * beyond {@link #EDIT_PRIVILEGES}.
   * <p>
   * <b>Both {@code DAV:read} and {@code DAV:write} are required.</b> That is
   * what separates a grant eXo itself wrote from the one shape it must keep
   * refusing: a bare {@code DAV:write}, which is how Stalwart reports a right
   * given through JMAP, is <b>not</b> edit-only here, so writing the list back
   * still stops rather than widening that right to full write.
   *
   * @return true when every privilege is an edit-share one and both read and
   *         write are among them
   */
  public boolean grantsEditOnly() {
    return !deny && !inverted && privileges.contains(READ) && privileges.contains(WRITE)
        && EDIT_PRIVILEGES.containsAll(privileges);
  }

  /**
   * Whether this entry is of a shape eXo itself writes — a read-only grant or
   * an edit grant (EXO-90378) — and may therefore be carried back unchanged in
   * an {@code ACL} request without widening anybody's rights.
   *
   * @return true for a grant eXo could have written
   */
  public boolean grantsExoShape() {
    return grantsReadOnly() || grantsEditOnly();
  }

  /**
   * Whether this entry names exactly one principal path, compared in the
   * canonical form a recorded principal is held in.
   *
   * @param canonicalPrincipal a principal path, canonical, not null
   * @return true when the entry's principal is an href naming it
   */
  public boolean appliesTo(String canonicalPrincipal) {
    return !inverted && principal.kind() == AcePrincipal.Kind.HREF
        && canonicalPrincipal.equals(CalendarCollection.principalPathOf(principal.href()));
  }

  /**
   * The href a principal path is written as: every segment percent-encoded
   * outside the unreserved set, and a trailing slash, since a principal is a
   * collection. A canonical path decodes {@code %40} to {@code @}; this puts
   * it back, so the server reads the spelling it answers itself.
   *
   * @param canonicalPrincipal a server-absolute principal path, decoded, with
   *          or without a trailing slash
   * @return the href
   */
  public static String principalHrefOf(String canonicalPrincipal) {
    String path = StringUtils.strip(StringUtils.trimToEmpty(canonicalPrincipal), "/");
    List<String> segments = new ArrayList<>();
    for (String segment : path.split("/")) {
      if (!segment.isEmpty()) {
        segments.add(encodeSegment(segment));
      }
    }
    return "/" + String.join("/", segments) + "/";
  }

  /**
   * A Clark name: {@code {namespace}local}.
   *
   * @param namespace the element's namespace
   * @param localName the element's local name
   * @return the Clark name
   */
  public static String clark(String namespace, String localName) {
    return "{" + StringUtils.defaultString(namespace) + "}" + localName;
  }

  /**
   * One path segment, percent-encoded outside the unreserved set, byte by
   * byte of its UTF-8 form.
   *
   * @param segment the decoded segment
   * @return the encoded segment
   */
  private static String encodeSegment(String segment) {
    StringBuilder encoded = new StringBuilder();
    for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xFF);
      if (b >= 0 && UNRESERVED.indexOf(c) >= 0) {
        encoded.append(c);
      } else {
        encoded.append('%').append(String.format("%02X", b & 0xFF));
      }
    }
    return encoded.toString();
  }

  /**
   * Whom an entry applies to: one of the six forms RFC 3744 §5.5.1 allows.
   *
   * @param kind which form
   * @param href the principal href, for {@link Kind#HREF} only
   * @param propertyNamespace the property's namespace, for {@link Kind#PROPERTY}
   *          only
   * @param propertyName the property's local name, for {@link Kind#PROPERTY}
   *          only
   */
  public record AcePrincipal(Kind kind, String href, String propertyNamespace, String propertyName) {

    /** The forms of {@code DAV:principal}. */
    public enum Kind {
      /** One principal, named by its URL. */
      HREF,
      /** Every user, authenticated or not. */
      ALL,
      /** Every authenticated user. */
      AUTHENTICATED,
      /** Every unauthenticated user. */
      UNAUTHENTICATED,
      /** The principal a property of the resource names, {@code DAV:owner} typically. */
      PROPERTY,
      /** The principal the resource itself is, for a principal resource. */
      SELF
    }

    /**
     * A principal named by its href.
     *
     * @param href the principal's href
     * @return the principal
     */
    public static AcePrincipal href(String href) {
      return new AcePrincipal(Kind.HREF, href, null, null);
    }

    /**
     * A principal of a form that carries nothing but its kind.
     *
     * @param kind {@link Kind#ALL}, {@link Kind#AUTHENTICATED},
     *          {@link Kind#UNAUTHENTICATED} or {@link Kind#SELF}
     * @return the principal
     */
    public static AcePrincipal of(Kind kind) {
      return new AcePrincipal(kind, null, null, null);
    }

    /**
     * The principal a property of the resource names.
     *
     * @param namespace the property's namespace
     * @param name the property's local name
     * @return the principal
     */
    public static AcePrincipal property(String namespace, String name) {
      return new AcePrincipal(Kind.PROPERTY, null, namespace, name);
    }
  }
}
