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
package org.exoplatform.caldav.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

import org.exoplatform.caldav.model.CaldavServer;

/**
 * Which changes to a server registration have to reach the copies eXo has
 * already written, and which do not (EXO-89759).
 *
 * <p>
 * <b>The problem.</b> The mirror converges towards what eXo would render now
 * only when something moves the remote ETag. Change a setting that alters what
 * eXo writes into a copy and nothing moves: every copy already on the server
 * keeps whatever it was given, indefinitely. The remedy is a stamp on the
 * registration and a matching one on each mirror pair, and this class is the
 * one place that decides <i>when the first of them moves</i>.
 *
 * <p>
 * <b>The rule, and it is a rule rather than a list.</b> A registration's
 * settings are copy-affecting <i>by default</i>. A field is excused only when it
 * belongs to one of four categories that describe the row rather than the
 * copies:
 * <ul>
 * <li><b>identity</b> — which row this is ({@code id}, {@code providerName});</li>
 * <li><b>address</b> — where the server lives ({@code serverUrl}). Repointing a
 * registration does not change what eXo would render; it changes which server
 * the account talks to, and the copies on the old one are not this mechanism's
 * business;</li>
 * <li><b>availability</b> — whether users may connect at all ({@code active}).
 * A deactivated connector has nothing to write;</li>
 * <li><b>presentation</b> — how the row is shown in a list ({@code name},
 * {@code description}, {@code icon}, and the three image fields). None of it
 * ever reaches a calendar object.</li>
 * </ul>
 * Everything else — what eXo writes into a copy, and what eXo is prepared to
 * accept as still being its own writing — moves the stamp. So the answer-links
 * switch of EXO-89757 moves it, and so will the per-server excusal lists,
 * without either of them naming itself here.
 *
 * <p>
 * <b>Why by default, and why reflection.</b> Enumerating today's copy-affecting
 * fields would mean the <i>next</i> setting silently does not propagate, and
 * that failure is invisible: nothing breaks, the copies simply never catch up,
 * which is the exact bug this task exists to remove. Reading the model's own
 * fields and excusing four named categories inverts it — a setting added later
 * is covered the moment it is declared, and excusing one is a deliberate,
 * reviewable edit to {@link #EXCUSED} with a reason.
 *
 * <p>
 * <b>The asymmetry is on purpose.</b> Over-including costs one comparison round
 * per mirror, which fetches and compares and — finding nothing different —
 * writes nothing at all. Under-including costs copies that never converge and
 * nobody able to see why. So when a new field is ambiguous, leaving it in is the
 * cheap mistake.
 *
 * <p>
 * <b>Never trusted from a caller.</b> The stamp is recomputed on the write path
 * from the row about to be overwritten, so a JSON body carrying one — stale,
 * invented, or copied from another registration — can neither set every mirror
 * in the deployment re-comparing nor stop one that should.
 */
public final class CopySettingsFingerprint {

  /**
   * The field names excused from the fingerprint, by the four categories the
   * class Javadoc sets out. Everything not named here is copy-affecting.
   *
   * <p>
   * {@code copySettingsUpdated} is the stamp itself and could only ever compare
   * unequal with itself. {@code observedQuirks} is not an administrator's
   * setting at all — it is what the sweep has been seen to observe, carried
   * outbound only and never accepted on a write, so it is present on a row read
   * from storage and absent from the body that comes back; comparing it would
   * move the stamp on every save and set every mirror re-comparing for ever. It
   * is named here ahead of the branch that introduces it (EXO-89771) precisely
   * so that landing that branch needs no edit in this file: a name that no field
   * carries is simply never met.
   */
  static final Set<String>  EXCUSED = Set.of(
                                             // identity
                                             "id",
                                             "providerName",
                                             // address
                                             "serverUrl",
                                             // availability
                                             "active",
                                             // presentation
                                             "name",
                                             "description",
                                             "icon",
                                             "imageFileId",
                                             "imageUploadId",
                                             "imageUrl",
                                             // the stamp itself, and the sweep's own observations
                                             "copySettingsUpdated",
                                             "observedQuirks");

  /**
   * Not instantiable: the rule is a decision, not an object.
   */
  private CopySettingsFingerprint() {
  }

  /**
   * The stamp an update should carry: the one the stored row already holds when
   * nothing copy-affecting changed, and the moment of the change when something
   * did.
   *
   * <p>
   * Carrying the stored value forward is as important as moving it. A write that
   * left the stamp null — an activation toggle, a rename — would tell every
   * mirror that there is nothing to apply, and quietly cancel a change an
   * administrator made a minute earlier that no sweep has reached yet.
   *
   * @param stored the registration as it stands now, or null when it could not
   *          be read — in which case nothing can be compared and the stamp is
   *          left where the incoming row has it
   * @param incoming the registration as the caller wants it
   * @param changedAt the moment to stamp when something copy-affecting changed
   * @return the stamp to persist, possibly null
   */
  public static Date stampFor(CaldavServer stored, CaldavServer incoming, Date changedAt) {
    if (stored == null || incoming == null) {
      return incoming == null ? null : incoming.getCopySettingsUpdated();
    }
    if (Objects.equals(of(stored), of(incoming))) {
      return stored.getCopySettingsUpdated();
    }
    return changedAt;
  }

  /**
   * Everything about a registration that governs the copies, as one comparable
   * string.
   *
   * <p>
   * Fields are read in declaration order, which is stable for a class and is the
   * order the model itself documents its appending discipline in; each is
   * rendered as {@code name=value} so that two fields swapping values cannot
   * produce the same fingerprint. Nothing here is persisted or shown — it exists
   * only to be compared with another one taken the same way.
   *
   * @param server the registration to fingerprint
   * @return the comparable state, never null
   */
  public static String of(CaldavServer server) {
    if (server == null) {
      return "";
    }
    StringJoiner state = new StringJoiner("|");
    for (Field field : copyAffectingFields()) {
      state.add(field.getName() + "=" + read(field, server));
    }
    return state.toString();
  }

  /**
   * The model's own fields, minus the excused ones — the rule, applied.
   *
   * @return the fields that make up the fingerprint
   */
  static List<Field> copyAffectingFields() {
    return Arrays.stream(CaldavServer.class.getDeclaredFields())
                 .filter(field -> !field.isSynthetic())
                 .filter(field -> !Modifier.isStatic(field.getModifiers()))
                 .filter(field -> !EXCUSED.contains(field.getName()))
                 .toList();
  }

  /**
   * One field's value, rendered for comparison.
   *
   * <p>
   * A field this code cannot read is rendered as a constant rather than allowed
   * to throw: a fingerprint that fails takes an administrator's save down with
   * it, and the worst a constant can do is leave that one field out of the
   * comparison.
   *
   * @param field the field to read
   * @param server the registration to read it from
   * @return the value as text, never null
   */
  private static String read(Field field, CaldavServer server) {
    try {
      field.setAccessible(true); // NOSONAR - a model in this add-on's own API, read to compare its own settings
      return String.valueOf(field.get(server));
    } catch (ReflectiveOperationException | RuntimeException e) {
      return "(unreadable)";
    }
  }
}
