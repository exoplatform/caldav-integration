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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.plugin.RemoteEventCopyPlugin;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Tells agenda whether this add-on is going to write a user's copy of a meeting
 * into their connected calendar account (EXO-90247).
 *
 * <h2>Why agenda has to ask rather than work it out</h2>
 *
 * <p>
 * Agenda leaves the {@code event.ics} file out of an invitation mail when the
 * recipient is going to hold a synced copy of the same meeting, because the two
 * carry different UIDs and no client can reconcile them. Until this plugin
 * existed, agenda answered that from the recipient's own settings — a guess at
 * a decision this add-on owns, and the two drifted apart in both directions at
 * once:
 * <ul>
 * <li>{@link CaldavPendingInvitationService} seeds copies server-side behind
 * {@link CaldavCopyConsent}, which reads the <i>account's</i> push switch and
 * not agenda's {@code automaticPushEvents}. A user who had turned that global
 * switch off was predicted to hold no copy, so the file was attached — and the
 * seeding pass wrote the copy anyway. The meeting twice, which is the very
 * symptom this task was opened for.</li>
 * <li>Conversely, when this add-on gained a reason of its own to decline a copy
 * for a particular invitee, agenda knew nothing of it and still suppressed the
 * file. The meeting <b>nowhere</b>: no copy, no attachment, no calendar entry
 * anywhere. That is what the first attempt at this task shipped, and what the
 * rig measured.</li>
 * </ul>
 *
 * <p>
 * Aligning the two predicates by hand would have closed today's gap and left
 * the mechanism that produced it — two repositories each modelling the other —
 * intact for the next rule either side adds. So the answer moves to the only
 * party that can give it, and agenda stops modelling this add-on at all.
 *
 * <h2>What it reads, and what it deliberately does not</h2>
 *
 * <p>
 * The same two questions the write paths ask, through the same beans, so there
 * is one reading and not a third: does this user take copies at all
 * ({@link CaldavCopyConsent}), and may this meeting be copied at all
 * ({@link CaldavCopyPolicy}).
 *
 * <p>
 * It does <b>not</b> go on to ask whether the account names a usable
 * destination collection, or whether the server is reachable. Those need a
 * round trip or a load this answer cannot afford on the notification path, and
 * the cost of not asking is bounded and already accepted: a user whose account
 * is connected but whose server has been unreachable loses the attachment, as
 * they did before this plugin existed — and they are already receiving none of
 * their meetings, which is a different fault with a different fix.
 */
@Service
public class CaldavRemoteEventCopyPlugin implements RemoteEventCopyPlugin {

  private static final Log LOG = ExoLogger.getLogger(CaldavRemoteEventCopyPlugin.class);

  @Autowired
  private CaldavCopyConsent caldavCopyConsent;

  @Autowired
  private CaldavCopyPolicy  caldavCopyPolicy;

  /**
   * Whether a copy of this meeting is going to be written into this
   * recipient's connected CalDAV account.
   *
   * <p>
   * Answers false on anything it cannot establish, which is the direction the
   * interface requires: a wrong "no" costs the recipient a redundant file,
   * a wrong "yes" costs them the invitation altogether.
   *
   * @param event the meeting, as agenda holds it
   * @param recipientIdentityId organization identity id of the recipient, 0 for
   *          a guest
   * @return true only when this add-on will write that recipient a copy
   */
  @Override
  public boolean writesCopyOf(Event event, long recipientIdentityId) {
    if (event == null || recipientIdentityId <= 0) {
      return false;
    }
    try {
      return caldavCopyPolicy.mayHoldCopy(event) && caldavCopyConsent.copiesEnabled(recipientIdentityId);
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("Whether a copy of event {} is written for user {} could not be told; it is answered as no copy",
                event.getId(),
                recipientIdentityId,
                e);
      return false;
    }
  }
}
