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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Event;
import org.exoplatform.caldav.client.SharingMechanism;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Whether an invitee's copy of a meeting is delivered by the organizer's own
 * calendar server rather than by eXo (EXO-90247).
 *
 * <h2>The situation this exists for</h2>
 *
 * <p>
 * eXo writes one copy of a meeting per invited user, each under a UID of its
 * own. When the organizer's copy lands in an account on a server that performs
 * its own scheduling, that server reads the {@code ORGANIZER} and
 * {@code ATTENDEE} lines and delivers a second invitation to every attendee it
 * can resolve in its own directory — keeping the organizer's UID. An invitee
 * with a mailbox on that server then holds two objects for one meeting: eXo's
 * copy and the server's. eXo's inbound pass, seeing the second under a UID no
 * pair of theirs maps, imports it as a personal event, and the meeting is
 * doubled on both sides.
 *
 * <h2>eXo asks for that scheduling itself, and that is what makes this
 * answerable</h2>
 *
 * <p>
 * {@code IcsWriter.addPeople} writes {@code SCHEDULE-AGENT=CLIENT} on every
 * {@code ATTENDEE}, and on {@code ORGANIZER} only when the pushing user is
 * <i>not</i> the organizer. So on the organizer's own copy the
 * {@code ORGANIZER} line carries no {@code SCHEDULE-AGENT} at all, and RFC 6638
 * section 7.1 makes the absent value {@code SERVER}: a scheduling-aware server
 * is being told, by omission, to do the scheduling. The trigger is therefore
 * not a server quirk to be guessed at but a property of eXo's own render, and
 * the question this class answers reduces to a checkable one — <i>is an
 * organizer-bearing object of this event landing in an account of a natively
 * scheduling server, with this invitee on it as an attendee that server can
 * resolve?</i>
 *
 * <h2>Six clauses, and why every one of them is load-bearing</h2>
 *
 * <p>
 * The failure modes are not symmetrical. A false negative leaves today's
 * duplicate, which is what this delivery already improves on elsewhere. A false
 * positive takes a copy away from somebody who needed it — a meeting missing
 * from a phone, with nothing in the calendar to suggest why. So the answer is
 * yes only when every clause below holds, and each one names the failure it is
 * there to prevent:
 * <ol>
 * <li><b>The invitee is not the organizer.</b> The organizer's own copy is the
 * object that makes the server schedule; skipping it would remove the
 * invitation from everybody.</li>
 * <li><b>Both are connected to the same declared server.</b> An organizer on
 * Stalwart, or on no CalDAV account at all, puts no organizer-bearing object
 * on the invitee's server, so nothing is delivered natively and eXo's copy is
 * the only one there will be.</li>
 * <li><b>The organizer consents to copies.</b> Same reason, read from the
 * per-account switch rather than from the presence of a copy: the organizer's
 * own copy is pushed by their browser, which races the creation fan-out, so at
 * fan-out time the copy is routinely not there yet. See
 * {@link CaldavCopyConsent}.</li>
 * <li><b>Both accounts have BlueMind's principal shape, and are different
 * accounts.</b> BlueMind is the one server this add-on has met that schedules
 * from a stored object; Stalwart, which advertises RFC 6638's
 * {@code calendar-auto-schedule} just as BlueMind does, was observed storing
 * back exactly what eXo writes, which is why the capability class is not the
 * witness and the account spelling is. The inequality is the EXO-90190 clause:
 * two eXo users sharing one BlueMind account write into one calendar home, a
 * server delivers nothing to an account from itself, and that shape is already
 * handled inbound by the account-scoped ownership question.</li>
 * <li><b>The invitee will really be an {@code ATTENDEE} on that object.</b>
 * {@code IcsWriter.addPeople} omits {@code ORGANIZER} and every
 * {@code ATTENDEE} when the organizer has no visible address, and
 * {@code IcsWriter.guests} leaves the organizer off the attendee list. An
 * invitee who is not written as an attendee is never invited by the server.
 * This also covers the solo event, which carries no organizer at all
 * ({@code AgendaEventIcsMapper.organizerOf}).</li>
 * <li><b>That attendee address is the invitee's own mailbox on that
 * server.</b> Approximated by the address equalling their CalDAV login there,
 * because eXo cannot ask the server's directory without a call this decision
 * cannot afford. Deliberately the strict direction: an invitee whose profile
 * address is an alias their login does not spell is a miss — today's duplicate,
 * unchanged — never a copy wrongly taken away.</li>
 * </ol>
 *
 * <h2>What it does not decide</h2>
 *
 * <p>
 * Only whether a copy that does not exist yet is created. Its caller asks it
 * only when no UID has been recorded for this (series, user), so a copy already
 * written — before this rule existed, or by an older deployment — goes on being
 * rewritten, repaired and answered exactly as before. Nothing here retires
 * anything.
 */
@Service
public class CaldavNativeSchedulingService {

  private static final Log LOG = ExoLogger.getLogger(CaldavNativeSchedulingService.class);

  /**
   * How many (event, invitee) pairs the "said once" set remembers.
   *
   * <p>
   * The bound matters because the decision is re-reached rather than recorded:
   * the seeding pass looks at every upcoming meeting a user holds no copy of,
   * every sweep, and these meetings never acquire one — so an undeduplicated
   * line would repeat every few minutes for ever, and an unbounded set would
   * grow by one entry per such meeting for the life of the process, never
   * shrinking as meetings leave the window. Past the bound the line drops to
   * DEBUG rather than the set growing: a deployment that has skipped five
   * thousand distinct copies has been told what is happening.
   */
  static final int SAID_MAX = 5000;

  @Autowired
  private CaldavConnectorStorage         caldavConnectorStorage;

  @Autowired
  private CaldavConnectionIdentityService caldavConnectionIdentityService;

  @Autowired
  private AgendaEventIcsMapper           agendaEventIcsMapper;

  @Autowired
  private CaldavCopyConsent              caldavCopyConsent;

  /** The (event, invitee) pairs whose skip has already been announced. */
  private final Set<String>              said = ConcurrentHashMap.newKeySet();

  /**
   * Whether this invitee's invitation to this meeting is delivered by the
   * organizer's own server, so eXo should write no copy of its own.
   *
   * <p>
   * Answers false on anything it cannot establish, including any runtime
   * failure while establishing it: not knowing is not a reason to withhold a
   * copy.
   *
   * @param event the meeting, as agenda holds it; null answers false
   * @param inviteeIdentityId the user a copy would be written for
   * @return true only when all six clauses of this class hold
   */
  public boolean deliveredNatively(Event event, long inviteeIdentityId) {
    try {
      return decide(event, inviteeIdentityId);
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("Whether event {} reaches user {} through their own server could not be told; a copy is written as usual",
                event == null ? null : event.getId(),
                inviteeIdentityId,
                e);
      return false;
    }
  }

  /**
   * Forgets what has already been announced for a user's meetings, so a
   * reconnection says it again.
   *
   * <p>
   * Called from nowhere today and offered for the same reason the sweep's other
   * "said once" sets offer it: the statement is about a connection, and a
   * connection that changes makes the statement worth repeating. Kept package
   * private until something needs it, so it cannot be mistaken for API.
   */
  void forgetWhatWasSaid() {
    said.clear();
  }

  /**
   * The six clauses, in the order that reads cheapest first.
   *
   * @param event the meeting, may be null
   * @param inviteeIdentityId the user a copy would be written for
   * @return true when the server delivers this invitation itself
   */
  private boolean decide(Event event, long inviteeIdentityId) {
    if (event == null || inviteeIdentityId <= 0) {
      return false;
    }
    long organizerIdentityId = event.getCreatorId();
    // Clause 1.
    if (organizerIdentityId <= 0 || organizerIdentityId == inviteeIdentityId) {
      return false;
    }
    // Clause 2. The server identifier is read from the invitee's own account,
    // and the organizer is required onto the same one; a zero identifier is the
    // legacy shape of "no declared server" and names nothing to compare.
    CaldavUserSetting invitee = caldavConnectorStorage.getCaldavSetting(inviteeIdentityId);
    long serverId = invitee == null || invitee.getServerId() == null ? 0L : invitee.getServerId();
    if (serverId <= 0) {
      return false;
    }
    CaldavUserSetting organizer = caldavConnectorStorage.getCaldavSetting(organizerIdentityId);
    long organizerServerId = organizer == null || organizer.getServerId() == null ? 0L : organizer.getServerId();
    if (organizerServerId != serverId) {
      return false;
    }
    // Clause 3.
    if (!caldavCopyConsent.copiesEnabled(organizerIdentityId)) {
      return false;
    }
    // Clause 4. principalOf answers null unless the user is recorded on that
    // server AND still connected to it, so this is the connectivity check as
    // well as the product one.
    String organizerPrincipal = caldavConnectionIdentityService.principalOf(organizerIdentityId, serverId);
    String inviteePrincipal = caldavConnectionIdentityService.principalOf(inviteeIdentityId, serverId);
    if (!SharingMechanism.isBlueMindPrincipal(organizerPrincipal) || !SharingMechanism.isBlueMindPrincipal(inviteePrincipal)
        || StringUtils.equalsIgnoreCase(organizerPrincipal, inviteePrincipal)) {
      return false;
    }
    // Clause 5.
    String organizerAddress = StringUtils.trimToNull(agendaEventIcsMapper.addressOf(organizerIdentityId));
    String inviteeAddress = StringUtils.trimToNull(agendaEventIcsMapper.addressOf(inviteeIdentityId));
    if (organizerAddress == null || inviteeAddress == null || StringUtils.equalsIgnoreCase(organizerAddress, inviteeAddress)) {
      return false;
    }
    // Clause 6.
    if (!StringUtils.equalsIgnoreCase(inviteeAddress, StringUtils.trimToNull(invitee.getUsername()))) {
      return false;
    }
    announce(event.getId(), inviteeIdentityId, organizerIdentityId, serverId);
    return true;
  }

  /**
   * Says once, per (event, invitee), that a copy was not written and why.
   *
   * @param eventId the meeting no copy is written for
   * @param inviteeIdentityId the user it is not written for
   * @param organizerIdentityId whose copy the server schedules from
   * @param serverId the declared server doing the scheduling
   */
  private void announce(long eventId, long inviteeIdentityId, long organizerIdentityId, long serverId) {
    String key = eventId + ":" + inviteeIdentityId;
    if (said.size() < SAID_MAX && said.add(key)) {
      LOG.info("No copy of event {} is written for user {}: their account and organizer {}'s are two accounts of"
          + " server {}, which schedules the meeting itself from the organizer's copy and delivers the invitation"
          + " natively. eXo reads their answer off the copy the server delivers",
               eventId,
               inviteeIdentityId,
               organizerIdentityId,
               serverId);
    } else {
      LOG.debug("No copy of event {} is written for user {}: server {} delivers it natively", eventId, inviteeIdentityId, serverId);
    }
  }
}
