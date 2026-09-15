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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.model.Event;

/**
 * The answer agenda now asks this add-on for instead of predicting: will a copy
 * of this meeting be written into this recipient's account (EXO-90247)?
 *
 * <p>
 * What is pinned here is that the answer is read from the <b>same beans the
 * write paths read</b> — {@link CaldavCopyConsent} and {@link CaldavCopyPolicy}
 * — and that it errs towards "no copy", because "no copy" makes agenda attach
 * the {@code event.ics} file and a wrong "yes" leaves the recipient with
 * nothing at all.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavRemoteEventCopyPluginTest {

  /** The recipient being asked about. */
  private static final long           RECIPIENT = 42L;

  @Mock
  private CaldavCopyConsent           caldavCopyConsent;

  @Mock
  private CaldavCopyPolicy            caldavCopyPolicy;

  @InjectMocks
  private CaldavRemoteEventCopyPlugin plugin;

  /**
   * The ordinary invitation: the recipient's account takes copies and the
   * meeting may hold one, so a copy is coming and agenda leaves the file out.
   */
  @Test
  public void aConnectedRecipientOfACopyableMeetingHoldsACopy() {
    when(caldavCopyPolicy.mayHoldCopy(any())).thenReturn(true);
    when(caldavCopyConsent.copiesEnabled(RECIPIENT)).thenReturn(true);

    assertTrue(plugin.writesCopyOf(confirmed(), RECIPIENT));
  }

  /**
   * The direction that had been drifting the other way, and the reason this
   * plugin exists at all: consent is read through the <b>account's</b> push
   * switch, the one the server-side seeding pass reads, and not through
   * agenda's global {@code automaticPushEvents}. A user who turned that global
   * switch off was predicted by agenda to hold no copy, so the file was
   * attached — while the seeding pass wrote the copy anyway, and the meeting
   * arrived twice.
   */
  @Test
  public void consentIsTheSeedingPassesOwnQuestion() {
    when(caldavCopyPolicy.mayHoldCopy(any())).thenReturn(true);
    when(caldavCopyConsent.copiesEnabled(RECIPIENT)).thenReturn(true);

    assertTrue(plugin.writesCopyOf(confirmed(), RECIPIENT),
               "the answer follows CaldavCopyConsent, which agenda's settings prediction could not see");

    verify(caldavCopyConsent).copiesEnabled(RECIPIENT);
  }

  /**
   * A recipient whose account does not take copies gets no copy, so agenda must
   * attach the file.
   */
  @Test
  public void aRecipientWhoRefusesCopiesHoldsNone() {
    when(caldavCopyPolicy.mayHoldCopy(any())).thenReturn(true);
    when(caldavCopyConsent.copiesEnabled(RECIPIENT)).thenReturn(false);

    assertFalse(plugin.writesCopyOf(confirmed(), RECIPIENT));
  }

  /**
   * A meeting no calendar may hold a copy of — a date poll — is one nobody gets
   * a copy of, however their account is set. Agenda used to suppress the file
   * for such a recipient on the strength of their settings alone, leaving them
   * with neither.
   */
  @Test
  public void aMeetingNoCopyMayBeWrittenForHoldsNone() {
    when(caldavCopyPolicy.mayHoldCopy(any())).thenReturn(false);

    assertFalse(plugin.writesCopyOf(poll(), RECIPIENT));
    verify(caldavCopyConsent, never()).copiesEnabled(anyLong());
  }

  /**
   * A guest reaches this with an identity of 0: no account, so no copy, and the
   * file is the only way they get the meeting.
   */
  @Test
  public void aGuestHoldsNoCopy() {
    lenient().when(caldavCopyPolicy.mayHoldCopy(any())).thenReturn(true);

    assertFalse(plugin.writesCopyOf(confirmed(), 0L));
  }

  /**
   * No meeting in hand, no copy claimed.
   */
  @Test
  public void aMissingMeetingHoldsNoCopy() {
    assertFalse(plugin.writesCopyOf(null, RECIPIENT));
  }

  /**
   * The asymmetry, pinned: a failure to establish the answer is answered "no
   * copy", never "a copy is coming". The first costs a redundant file, the
   * second costs the invitation.
   */
  @Test
  public void aFailureAnswersNoCopy() {
    when(caldavCopyPolicy.mayHoldCopy(any())).thenThrow(new IllegalStateException("agenda is down"));

    assertFalse(plugin.writesCopyOf(confirmed(), RECIPIENT), "not knowing must never withhold the file");
  }

  /**
   * A confirmed meeting.
   *
   * @return the event
   */
  private Event confirmed() {
    return eventWith(EventStatus.CONFIRMED);
  }

  /**
   * A date poll, which is spelled {@code TENTATIVE}.
   *
   * @return the event
   */
  private Event poll() {
    return eventWith(EventStatus.TENTATIVE);
  }

  /**
   * An event carrying the given status.
   *
   * @param status the status to set
   * @return the event
   */
  private Event eventWith(EventStatus status) {
    Event event = new Event();
    event.setId(4242L);
    event.setStatus(status);
    return event;
  }
}
