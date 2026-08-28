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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.exoplatform.caldav.model.CaldavServer;

/**
 * Which changes to a server registration have to reach the copies eXo has
 * already written.
 *
 * <p>
 * The failure this guards against is invisible by construction: a setting that
 * does not move the stamp breaks nothing, throws nothing and logs nothing — the
 * copies simply never catch up, and the only symptom is an administrator saying
 * "I turned that off and my calendar still shows it". So the rule is pinned from
 * both ends: what it includes, what it excuses, and — the one that matters for
 * the setting nobody has written yet — that a field added to the model tomorrow
 * lands on one side or the other and cannot fall between them.
 */
public class CopySettingsFingerprintTest {

  private static final Date CHANGED_AT = new Date(1_800_000_000_000L);

  private static final Date EARLIER    = new Date(1_700_000_000_000L);

  @Test
  public void turningOffTheAnswerLinksMovesTheStamp() {
    // The setting EXO-89757 shipped, and the reason this mechanism exists: it
    // changes what eXo renders into the description of every copy, and nothing
    // about that change moves a single ETag on the server.
    CaldavServer stored = server();
    CaldavServer incoming = server();
    incoming.setAnswerLinksInCopy(false);

    assertEquals(CHANGED_AT, CopySettingsFingerprint.stampFor(stored, incoming, CHANGED_AT));
  }

  @Test
  public void renamingTheServerDoesNotMoveTheStamp() {
    // Presentation. It reaches a list in the connectors screen and nothing
    // else — certainly not a calendar object — so making every mirror in the
    // deployment fetch and compare every copy it holds would be pure cost.
    CaldavServer stored = server();
    stored.setCopySettingsUpdated(EARLIER);
    CaldavServer incoming = server();
    incoming.setCopySettingsUpdated(EARLIER);
    incoming.setName("BlueMind (production)");
    incoming.setDescription("The one in Lille");
    incoming.setIcon("fas fa-calendar");

    assertEquals(EARLIER, CopySettingsFingerprint.stampFor(stored, incoming, CHANGED_AT));
  }

  @Test
  public void deactivatingOrRepointingTheServerDoesNotMoveTheStamp() {
    // Availability and address. A deactivated connector has nothing to write,
    // and a repointed one is a different server — the copies on the old one are
    // not what this mechanism is for.
    CaldavServer stored = server();
    stored.setCopySettingsUpdated(EARLIER);
    CaldavServer incoming = server();
    incoming.setCopySettingsUpdated(EARLIER);
    incoming.setActive(false);
    incoming.setServerUrl("https://elsewhere.example.invalid/dav/");

    assertEquals(EARLIER, CopySettingsFingerprint.stampFor(stored, incoming, CHANGED_AT));
  }

  @Test
  public void aWriteThatChangesNothingCarriesTheExistingStampForward() {
    // The quiet failure this closes. A rename or an activation toggle that let
    // the stamp come out null would tell every mirror there is nothing to
    // apply, and silently cancel a change an administrator made a minute
    // earlier that no sweep has reached yet.
    CaldavServer stored = server();
    stored.setCopySettingsUpdated(EARLIER);
    CaldavServer incoming = server();
    incoming.setName("Renamed");

    assertEquals(EARLIER, CopySettingsFingerprint.stampFor(stored, incoming, CHANGED_AT));
  }

  @Test
  public void aStampSentByTheCallerIsNeverTakenAsTrue() {
    // The registration has never been stamped and nothing copy-affecting is
    // changing, so the answer is null however loudly the body says otherwise.
    // Trusted, an invented timestamp would set every mirror in the deployment
    // re-comparing its copies — or, echoed back stale, stop one that owes a
    // round.
    CaldavServer stored = server();
    CaldavServer incoming = server();
    incoming.setCopySettingsUpdated(new Date());

    assertNull(CopySettingsFingerprint.stampFor(stored, incoming, CHANGED_AT));
  }

  @Test
  public void aRowThatCannotBeReadLeavesTheStampWhereItIs() {
    // Deleted between the read and the write, most plausibly. There is nothing
    // to compare against, and the write that follows answers the disappearance
    // with the not-found it already declares.
    CaldavServer incoming = server();
    incoming.setCopySettingsUpdated(EARLIER);

    assertEquals(EARLIER, CopySettingsFingerprint.stampFor(null, incoming, CHANGED_AT));
  }

  @Test
  public void everyFieldOfTheModelIsEitherExcusedOrCompared() {
    // The by-construction pin, and the whole point of writing this as a rule
    // rather than a list. A setting added to the model next year is included in
    // the comparison the moment it is declared; excusing it is a deliberate edit
    // to EXCUSED with a reason beside it. What must never happen is a field that
    // is in neither — which is exactly what an enumeration of today's fields
    // would produce, silently.
    List<String> compared = CopySettingsFingerprint.copyAffectingFields().stream().map(Field::getName).toList();

    for (Field field : CaldavServer.class.getDeclaredFields()) {
      if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      assertTrue(compared.contains(field.getName()) || CopySettingsFingerprint.EXCUSED.contains(field.getName()),
                 field.getName() + " is neither compared nor excused: decide which, in CopySettingsFingerprint");
    }
  }

  @Test
  public void theExcusedFieldsAreTheFourCategoriesAndNothingElse() {
    // Named one by one, so that widening the excusal is a visible edit here as
    // well as there. Over-including costs one comparison round that writes
    // nothing; under-including costs copies that never converge, and nobody
    // able to see why.
    List<String> compared = CopySettingsFingerprint.copyAffectingFields().stream().map(Field::getName).toList();

    for (String excused : List.of("id", "providerName", "serverUrl", "active", "name", "description", "icon",
                                  "imageFileId", "imageUploadId", "imageUrl", "copySettingsUpdated",
                                  "observedQuirks")) {
      assertFalse(compared.contains(excused), excused + " must not be part of the fingerprint");
    }
    assertTrue(compared.contains("answerLinksInCopy"), "the answer-links switch changes what eXo writes into a copy");
    // EXO-89771's per-server excusal lists arrived after this rule was written
    // and are covered by it without naming themselves — which is the whole
    // point of a rule rather than a list. The one that plainly changes what eXo
    // writes into somebody's calendar is pinned here so a later edit cannot
    // quietly excuse it.
    assertTrue(compared.contains("omittedProperties"), "what eXo leaves out of a copy changes the copy");
  }

  @Test
  public void twoFieldsSwappingValuesAreNotTheSameSettings() {
    // The fingerprint names each value rather than concatenating them, so that
    // a change nobody thought about cannot cancel itself out against another.
    assertTrue(CopySettingsFingerprint.of(server()).contains("answerLinksInCopy="),
               "each value must be named by its field");
  }

  @Test
  public void nothingAtAllFingerprintsToNothing() {
    assertEquals("", CopySettingsFingerprint.of(null));
  }

  @Test
  public void theFingerprintOfOneRowIsStable() {
    // Read twice from the same values it must answer the same, or every save
    // would look like a change and every mirror would re-compare on every edit.
    assertEquals(CopySettingsFingerprint.of(server()), CopySettingsFingerprint.of(server()));
    CaldavServer flipped = server();
    flipped.setAnswerLinksInCopy(false);
    assertNotEquals(CopySettingsFingerprint.of(server()), CopySettingsFingerprint.of(flipped));
  }

  @Test
  public void anUnchangedSaveReturnsTheStoredStampItselfAndNotACopyOfNow() {
    // Same instance, so that a save cannot advance a stamp by a millisecond and
    // send every mirror round again for nothing.
    CaldavServer stored = server();
    stored.setCopySettingsUpdated(EARLIER);

    assertSame(EARLIER, CopySettingsFingerprint.stampFor(stored, server(), CHANGED_AT));
  }

  @Test
  public void theModelDeclaresFieldsToCompare() {
    // A guard on the guard: if the model ever stopped declaring fields, every
    // fingerprint would be empty, every comparison equal, and the tests above
    // would all pass while the mechanism did nothing at all.
    assertFalse(CopySettingsFingerprint.copyAffectingFields().isEmpty(),
                "the rule must compare something, or it is not a rule");
    assertTrue(Arrays.stream(CaldavServer.class.getDeclaredFields()).anyMatch(field -> !field.isSynthetic()),
               "the model must declare fields");
  }

  /**
   * A registration as an administrator declared it, with every field set so
   * that a comparison has something on both sides to differ about.
   *
   * @return the registration
   */
  private CaldavServer server() {
    CaldavServer server = new CaldavServer();
    server.setId(7L);
    server.setProviderName("agenda.caldavCalendar.7");
    server.setName("Bluemind");
    server.setDescription("The shared one");
    server.setServerUrl("https://caldav.example.invalid/dav/");
    server.setActive(true);
    server.setIcon("fas fa-server");
    server.setImageFileId(11L);
    server.setAnswerLinksInCopy(true);
    return server;
  }
}
