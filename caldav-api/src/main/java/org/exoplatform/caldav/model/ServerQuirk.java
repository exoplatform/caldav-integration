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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The behaviours a CalDAV server can be excused for, named in code.
 *
 * <p>
 * <b>Why the list is code and not data.</b> Each entry decides what
 * "unchanged" means for the servers it is ticked on, so adding one is a
 * decision about the mirror's guarantees rather than a configuration value.
 * Written here, it is reviewed like any other change to the comparison; typed
 * into a text field it would not be. That is also why the administrator never
 * types a property name: the drawer offers what the sweep has actually seen,
 * and each offer carries the sentence written here beside it.
 *
 * <p>
 * <b>Nothing is blocked by the list being incomplete.</b> A divergence no entry
 * matches is still offered, described generically by the property name the
 * sweep saw. A deployment meeting a server nobody here has seen can excuse its
 * quirk on the day it meets it; the entry that gives that quirk a sentence
 * follows later.
 *
 * <p>
 * <b>The patterns are the unit of storage.</b> Ticking an entry writes exactly
 * the patterns below into the server's list, so what was ticked can be read
 * back from the row and the excusal covers the whole family the sentence names
 * — every {@code X-MOZ-} marker, not only the one this deployment happened to
 * see first. A pattern ending in {@code *} matches by prefix; anything else is
 * an exact, case-insensitive property name. That is the same matcher the
 * comparison uses against the stored list, so what the drawer says is excused
 * and what the sweep excuses cannot drift apart.
 *
 * <p>
 * <b>Two kinds of entry, and the difference is declared.</b> Most entries only
 * change what eXo <i>notices</i>; one changes what eXo <i>writes</i> into a
 * copy on somebody's calendar. {@link ServerQuirkEffect} carries that, it
 * decides which stored list a tick is written into, and the drawer renders it —
 * a payload-changing box that looks identical to a tolerance one is the kind of
 * thing that reads fine today and surprises somebody in a year.
 *
 * <p>
 * <b>The URL duplication is deliberately absent.</b> BlueMind appends every URI
 * in a description a second time; eXo recognises and normalises that for every
 * server (EXO-89756). It is a correctness fix, not a preference, and an entry
 * here would only offer a way to switch working code off.
 */
public enum ServerQuirk {

  /**
   * The server does not keep video-conference links. BlueMind stores no
   * {@code CONFERENCE} at all: eXo writes the link on every push and the copy
   * comes back without it, which kept five copies of a live account in a
   * permanent repair loop.
   */
  DROPS_CONFERENCE("dropsConference", ServerQuirkDirection.DROPPED, ServerQuirkEffect.TOLERATE, null, "CONFERENCE"),

  /**
   * The server stamps the compatibility markers Outlook and Thunderbird read —
   * hidden fields that say nothing about the meeting. eXo writes none of them,
   * so they arrive as unrecognised properties on every copy.
   */
  ADDS_COMPATIBILITY_MARKERS("addsCompatibilityMarkers",
                             ServerQuirkDirection.ADDED,
                             ServerQuirkEffect.TOLERATE,
                             null,
                             "X-MICROSOFT-*",
                             "X-MOZ-*"),

  /**
   * The server adds a rich-text duplicate of the invitation text. It is a
   * second rendering of the same words, not a second statement about the
   * meeting, and eXo goes on comparing the plain one.
   */
  ADDS_FORMATTED_DESCRIPTION("addsFormattedDescription",
                             ServerQuirkDirection.ADDED,
                             ServerQuirkEffect.TOLERATE,
                             null,
                             "X-ALT-DESC"),

  /**
   * The server rewrites the invitation text itself.
   *
   * <p>
   * <b>The blunt one, and it must stay blunt.</b> Excusing it stops the text of
   * every copy on that server being compared at all — including the tokenised
   * answer links the description carries since EXO-89753, which EXO-89752
   * bounded in time precisely so that a rewritten one would be noticed. Ticked,
   * nobody notices a rewritten answer link again on that server. It is the one
   * entry whose direction is {@link ServerQuirkDirection#REWRITTEN}, which is
   * what makes the comparison tolerate a substituted value and not only an
   * absence, and it is the reason that tolerance is opt-in per entry rather
   * than granted to every excused property.
   */
  REWRITES_DESCRIPTION("rewritesDescription",
                       ServerQuirkDirection.REWRITTEN,
                       ServerQuirkEffect.TOLERATE,
                       null,
                       "DESCRIPTION"),

  /**
   * The server drops the organizer from an event with no other participants.
   *
   * <p>
   * BlueMind stores neither {@code ORGANIZER} nor {@code ATTENDEE} on a copy of
   * an event whose only participant is its creator. eXo writes an organizer,
   * reads back a copy with none, judges it altered and rewrites it — on every
   * sweep, for ever (EXO-89775).
   *
   * <p>
   * <b>The one entry that changes what eXo writes rather than what it
   * tolerates.</b> Ticking it stops eXo naming an organizer on those copies for
   * this server; the copy and the render then say the same thing, so there is
   * nothing left to excuse and no tolerance entry beside it. That is why its
   * pattern, {@link #SOLO_ORGANIZER}, is deliberately not a property name eXo
   * writes: it can never be read as an excusal by the comparison, whose lists
   * are restricted to the properties {@code IcsWriter} emits.
   *
   * <p>
   * <b>Per server, and it has to be.</b> The obvious global fix — never write an
   * organizer when nobody else is invited — was rejected in EXO-89768 and again
   * here: the golden corpus holds organizer-only events that a real server
   * stored <i>with</i> their organizer, so this is one server's behaviour and
   * not CalDAV's. Made global it would strip information from copies on servers
   * that keep it happily, to buy a clean sweep on one.
   */
  OMITS_SOLO_ORGANIZER("omitsSoloOrganizer",
                       ServerQuirkDirection.DROPPED,
                       ServerQuirkEffect.OMIT,
                       Patterns.ORGANIZER,
                       Patterns.SOLO_ORGANIZER);

  /**
   * The token standing for "the organizer of an event with no other
   * participants".
   *
   * <p>
   * Deliberately not {@code ORGANIZER}. It names a <b>case</b> and not a
   * property, so it can never be mistaken for one: the comparison's excusal
   * lists only ever accept a property {@code IcsWriter} emits, which means no
   * setting of this can make a missing organizer on an ordinary meeting stop
   * being reported. An organizer disappearing from a copy on a server that
   * normally keeps it is a real change and stays one.
   */
  public static final String         SOLO_ORGANIZER = Patterns.SOLO_ORGANIZER;

  /** Suffix marking a pattern that matches by prefix rather than exactly. */
  private static final String        WILDCARD       = "*";

  /** Stable identifier, carried to the browser and resolving its wording. */
  private final String               id;

  /** Which way the divergence this entry describes points. */
  private final ServerQuirkDirection direction;

  /** Whether ticking it changes what eXo notices or what eXo writes. */
  private final ServerQuirkEffect    effect;

  /**
   * The property whose own record this entry replaces once it is being
   * observed, or null when it replaces nothing.
   *
   * <p>
   * <b>Why an entry declares this itself.</b> When the comparison learns to
   * report a situation as a case rather than as a bare property, every record
   * already stored under that property describes the same behaviour in the
   * older, broader way — and a live BlueMind account showed both at once, the
   * older one offering an excusal that would have covered far more than the
   * administrator intended. Declared here, beside the patterns, it is the one
   * place somebody adding a case would think to look; declared in a table of
   * its own it would be the one place they would not.
   */
  private final String               supersedes;

  /** The property-name patterns ticking this entry writes into the server's list. */
  private final List<String>         patterns;

  /**
   * Declares one catalogue entry.
   *
   * <p>
   * One constructor rather than two, so every entry states in as many words
   * whether it replaces an older record. A default would make the interesting
   * case the invisible one.
   *
   * @param entryId stable identifier, also the suffix of the entry's wording keys
   * @param entryDirection which way the divergence it describes points
   * @param entryEffect whether ticking it changes what eXo notices or what eXo
   *          writes
   * @param entrySupersedes the property whose own record this entry replaces
   * @param entryPatterns the property-name patterns the entry covers
   */
  ServerQuirk(String entryId,
              ServerQuirkDirection entryDirection,
              ServerQuirkEffect entryEffect,
              String entrySupersedes,
              String... entryPatterns) {
    this.id = entryId;
    this.direction = entryDirection;
    this.effect = entryEffect;
    this.supersedes = entrySupersedes;
    this.patterns = List.of(entryPatterns);
  }

  /**
   * @return whether ticking this entry changes what eXo notices or what eXo
   *         writes into copies on the server
   */
  public ServerQuirkEffect getEffect() {
    return effect;
  }

  /**
   * The property whose stored record is replaced once a behaviour is observed.
   *
   * <p>
   * Asked of what a pass actually saw, so nothing is forgotten on the strength
   * of the catalogue alone: a record only goes when the case that replaces it is
   * being observed on that very server.
   *
   * @param property the property or case name a pass reported
   * @return the property whose record it replaces, or empty when it replaces
   *         none
   */
  public static Optional<String> superseding(String property) {
    return Arrays.stream(values())
                 .filter(quirk -> quirk.supersedes != null && quirk.matches(property))
                 .map(quirk -> quirk.supersedes)
                 .findFirst();
  }

  /**
   * @return the stable identifier the browser resolves this entry's wording by
   */
  public String getId() {
    return id;
  }

  /**
   * @return which way the divergence this entry describes points
   */
  public ServerQuirkDirection getDirection() {
    return direction;
  }

  /**
   * @return the property-name patterns ticking this entry writes into the
   *         server's list
   */
  public List<String> getPatterns() {
    return patterns;
  }

  /**
   * The catalogue entry describing a divergence, when one does.
   *
   * @param property the property name the sweep saw diverge
   * @param direction which way that divergence pointed
   * @return the entry, or empty when nothing in the catalogue describes it
   */
  public static Optional<ServerQuirk> describing(String property, ServerQuirkDirection direction) {
    return Arrays.stream(values()).filter(quirk -> quirk.covers(property, direction)).findFirst();
  }

  /**
   * Whether excusing a property's absence on a server also excuses the server
   * substituting its own value for it.
   *
   * <p>
   * True only for an entry that declares {@link ServerQuirkDirection#REWRITTEN}
   * — one deliberate entry today. Every other excused property, and every
   * property no entry names, keeps the narrow meaning the operator lever has
   * always had: a statement eXo makes and the copy does not carry. So a client
   * that rewrites a conference link is still caught on a server excused for
   * dropping one, which is what the entry's own wording promises.
   *
   * @param property the property name
   * @return true when a rewritten value of it is excused along with its absence
   */
  public static boolean rewriteExcusable(String property) {
    return Arrays.stream(values())
                 .anyMatch(quirk -> quirk.direction == ServerQuirkDirection.REWRITTEN && quirk.matches(property));
  }

  /**
   * Whether a stored list of patterns asks eXo to leave something out of the
   * copies it writes to a server.
   *
   * @param patterns the server's stored omission list, may be null or blank
   * @param property the case or property name to test
   * @return true when one of the patterns names it
   */
  public static boolean listMatches(String patterns, String property) {
    if (patterns == null || patterns.isBlank()) {
      return false;
    }
    return Arrays.stream(patterns.split(",")).anyMatch(pattern -> patternMatches(pattern, property));
  }

  /**
   * Whether a stored pattern names a property.
   *
   * <p>
   * The one matcher, used by the catalogue, by the drawer's ticking and by the
   * comparison's excusal, so a family written as {@code X-MOZ-*} means the same
   * thing everywhere it is read.
   *
   * @param pattern an exact property name, or a prefix followed by {@code *}
   * @param property the property name to test
   * @return true when the pattern names the property
   */
  public static boolean patternMatches(String pattern, String property) {
    if (pattern == null || property == null) {
      return false;
    }
    String trimmed = pattern.trim().toUpperCase(Locale.ROOT);
    String candidate = property.trim().toUpperCase(Locale.ROOT);
    if (trimmed.isEmpty()) {
      return false;
    }
    if (trimmed.endsWith(WILDCARD)) {
      String prefix = trimmed.substring(0, trimmed.length() - WILDCARD.length());
      // An empty prefix would make "*" excuse everything on the server, which
      // is not an excusal but a way to switch the comparison off.
      return !prefix.isEmpty() && candidate.startsWith(prefix);
    }
    return trimmed.equals(candidate);
  }

  /**
   * The pattern literals, in a holder so an entry above can name one.
   *
   * <p>
   * An enum constant may not read a field of its own enum declared after it, and
   * declaring the literal before the constants would put a pattern above the
   * catalogue it belongs to. A nested holder is the ordinary way out, and it
   * keeps one spelling of the token: {@link ServerQuirk#SOLO_ORGANIZER} is this
   * field, so the entry and every reader agree by construction.
   */
  private static final class Patterns {

    /** The organizer of an event with no other participants. */
    private static final String SOLO_ORGANIZER = "SOLO-ORGANIZER";

    /** The property the case above replaces the record of. */
    private static final String ORGANIZER      = "ORGANIZER";

    /**
     * Not instantiated: a holder for one literal.
     */
    private Patterns() {
    }
  }

  /**
   * Whether this entry describes a divergence on a property, in a direction.
   *
   * @param property the property name
   * @param divergenceDirection which way the divergence pointed
   * @return true when this entry is the one to show for it
   */
  private boolean covers(String property, ServerQuirkDirection divergenceDirection) {
    if (!matches(property)) {
      return false;
    }
    if (direction == ServerQuirkDirection.REWRITTEN) {
      // A server that rewrites a value can also simply not return it — the same
      // fact about the same server, seen on a copy that had nothing to rewrite.
      return divergenceDirection != ServerQuirkDirection.ADDED;
    }
    return direction == divergenceDirection;
  }

  /**
   * Whether any of this entry's patterns names a property.
   *
   * @param property the property name
   * @return true when the entry covers it
   */
  private boolean matches(String property) {
    return patterns.stream().anyMatch(pattern -> patternMatches(pattern, property));
  }
}
