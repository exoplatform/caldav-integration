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
package org.exoplatform.caldav.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * What separates a state of the user from a copy that failed (EXO-89798).
 *
 * <p>
 * A user who never connected a CalDAV account is an ordinary state of that
 * user, not an incident, and it used to be recorded as one: a WARN and eleven
 * frames, per attendee, per meeting created. In a twenty-member space where
 * most people have never connected, one meeting printed a stack trace each —
 * and the copies that genuinely failed drowned under states nobody was going
 * to change.
 *
 * <p>
 * The classification is a pure function of the code, which is why it is tested
 * here and not through an appender: the question "is this a state or a
 * failure?" is ordinary logic, and asserting on log output to check it is how
 * a guard ends up untested.
 *
 * <p>
 * <b>The test that matters most is the default.</b> An unrecognised code has to
 * classify as a failure — the moment the default flips, this stops being a
 * filter and becomes a silencer for every code the next author forgets.
 */
public class CaldavPushCodeClassificationTest {

  /**
   * The constants of the vocabulary that are not failure codes at all, and so
   * are not classified.
   *
   * <p>
   * Named one by one rather than matched by shape, because there is no shape
   * to match: {@code CREATION_REFUSED} is a code and reads
   * {@code "calendarCreationRefused"}, while {@code CONNECTOR_NAME} is not one
   * and reads {@code "agenda.caldavCalendar"}. Adding a constant to this class
   * therefore fails {@link #everyCodeInTheVocabularyIsClassified} until its
   * author says which of the two it is — which is the whole point of the list.
   */
  private static final Set<String> NOT_CODES = Set.of("MIRROR_COLLECTION_SLUG", "MIRROR_DISPLAY_NAME", "CONNECTOR_NAME");

  /**
   * The state behind the incident in the report: no account, nothing to write
   * to, and nobody but the user can change it.
   */
  @Test
  public void aUserWithNoConnectedAccountIsAState() {
    assertTrue(CaldavPushService.isKnownState(CaldavPushService.NOT_CONNECTED));
  }

  /**
   * The two other states of the same kind: a destination the user has not
   * named, and an account naming a default calendar it does not hold. Retrying
   * either is pointless and warning about either recurs for ever.
   */
  @Test
  public void theOtherPersistentStatesAreStatesToo() {
    assertTrue(CaldavPushService.isKnownState(CaldavPushService.MAIN_CALENDAR_UNKNOWN));
  }

  /**
   * A refused save is a failure: a meeting that never reaches a user's calendar
   * is invisible to them and, at debug, to everyone else too.
   */
  @Test
  public void aRefusedSaveIsAFailure() {
    assertFalse(CaldavPushService.isKnownState(CaldavPushService.SAVE));
  }

  /**
   * The rest of the failures, so that no future edit quietly moves one of them
   * out of the log.
   */
  @Test
  public void theOtherFailuresAreFailures() {
    assertFalse(CaldavPushService.isKnownState(CaldavPushService.CONFLICT));
    assertFalse(CaldavPushService.isKnownState(CaldavPushService.CREDENTIALS));
    assertFalse(CaldavPushService.isKnownState(CaldavPushService.CREATION_REFUSED));
  }

  /**
   * The default, and the reason this file exists.
   *
   * <p>
   * A code nobody classified is exactly the thing worth hearing about, so it
   * has to reach the warn branch with its trace. Defaulting the other way would
   * turn every unclassified code silent — the failure mode this whole change is
   * one step away from.
   */
  @Test
  public void anUnrecognisedCodeIsAFailure() {
    assertFalse(CaldavPushService.isKnownState("caldav.error.somethingNobodyHasWrittenYet"));
    assertFalse(CaldavPushService.isKnownState(""));
    assertFalse(CaldavPushService.isKnownState(null));
  }

  /**
   * A code owned by another service reaches the default, and the default is
   * right for it: a collection that would not delete is a failure.
   */
  @Test
  public void aCodeOwnedElsewhereReachesTheDefault() {
    assertFalse(CaldavPushService.isKnownState(CaldavDeletionService.NOTHING_DELETED));
    assertFalse(CaldavPushService.isClassified(CaldavDeletionService.NOTHING_DELETED));
  }

  /**
   * The guard rail: every code the vocabulary declares was put in one category
   * or the other on purpose.
   *
   * <p>
   * Without this, {@link CaldavPushService#isKnownState} answers "failure" to a
   * code that was classified as one and to a code nobody thought about, and the
   * two are indistinguishable. This walks the class the codes live in and holds
   * each one to an explicit answer, so that adding a code without deciding is a
   * red test rather than a silent default.
   *
   * @throws IllegalAccessException never — the constants read are public
   */
  @Test
  public void everyCodeInTheVocabularyIsClassified() throws IllegalAccessException {
    for (Field field : CaldavPushService.class.getDeclaredFields()) {
      if (!isPublicStaticFinalString(field) || NOT_CODES.contains(field.getName())) {
        continue;
      }
      String code = (String) field.get(null);
      assertTrue(CaldavPushService.isClassified(code),
                 "The code " + field.getName() + " (\"" + code + "\") is in neither category. Say whether it is a"
                     + " persistent state of the subject, which only a person can clear, or a failure of the attempt"
                     + " — and add it to KNOWN_STATE_CODES or FAILURE_CODES accordingly. A code left out is logged as"
                     + " a failure, which is the safe answer but not a decision.");
    }
  }

  /**
   * Whether a declared field is one of the vocabulary's string constants.
   *
   * @param field a field declared by the service
   * @return true when it is a public static final String
   */
  private boolean isPublicStaticFinalString(Field field) {
    int modifiers = field.getModifiers();
    return field.getType() == String.class
        && Modifier.isPublic(modifiers)
        && Modifier.isStatic(modifiers)
        && Modifier.isFinal(modifiers);
  }
}
