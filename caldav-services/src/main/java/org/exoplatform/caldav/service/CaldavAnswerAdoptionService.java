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

import java.time.ZonedDateTime;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.caldav.ics.IcsEventMapper;
import org.exoplatform.caldav.ics.IcsParser;
import org.exoplatform.caldav.ics.IcsText;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.IcsPerson;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Reads the one field a user may answer through their own calendar — their own
 * PARTSTAT on a copy eXo pushed — and records it in agenda (EXO-89681).
 *
 * <p>
 * This is deliberately the narrowest possible inbound mapping. The mirror is
 * eXo's projection and stays one; the single thing that flows back is the
 * owner's participation, read from the one ATTENDEE line whose address is the
 * account owner's own — the same identity the write side names for them. No
 * server-supplied address ever resolves to any other eXo user: one line, on an
 * object eXo wrote, in a collection eXo created, on an account the user holds.
 * That is the narrow version of the ATTENDEE mapping
 * {@link CaldavInboundService} defers as a trust-boundary question, and it
 * sidesteps that question entirely.
 *
 * <p>
 * The caller decides <i>when</i> to ask and what to do with the object
 * afterwards. This service only turns "what the client wrote" into "what agenda
 * records", and says whether it changed anything.
 *
 * <p>
 * <b>Two callers ask, on two different signals, and that is deliberate.</b>
 * {@link CaldavMirrorVerificationService} asks when a copy's ETag has moved
 * away from the one eXo recorded — the server's own statement that a client
 * wrote after eXo did, which is what tells a phone's answer from an eXo answer
 * not yet pushed. {@link CaldavInboundService} asks when the collection's sync
 * report names one of eXo's own copies as changed (EXO-89807), because a server
 * can record an answer without moving the value the first signal reads, and did:
 * on BlueMind the answer sat on the copy for hours while its ETag never moved.
 * Neither signal supersedes the other; between them they cover the servers that
 * publish a version and the servers that publish one but do not move it.
 * Whichever asks, the answers below are the same, and asking twice about one
 * copy costs nothing — an answer agenda already holds is not adopted again.
 */
@Service
public class CaldavAnswerAdoptionService {

  private static final Log           LOG = ExoLogger.getLogger(CaldavAnswerAdoptionService.class);

  @Autowired
  private IdentityManager            identityManager;

  @Autowired
  private AgendaEventService         agendaEventService;

  @Autowired
  private AgendaEventAttendeeService agendaEventAttendeeService;

  @Autowired
  private IcsParser                  icsParser;

  /** Holds the account whose address a copy names its owner by. */
  @Autowired
  private CaldavConnectorStorage     caldavConnectorStorage;

  @Autowired
  private IcsEventMapper             icsEventMapper;

  /** What one adoption pass over one object did. */
  public enum Outcome {
    /** The object carries no answer eXo does not already hold. */
    NOTHING,
    /** At least one answer was read off the object and recorded in agenda. */
    ADOPTED,
    /** An answer was found and recording it failed; nothing may overwrite it. */
    FAILED
  }

  /**
   * Adopts the owner's answer from a copy a client rewrote, series and single
   * occurrences alike.
   *
   * <p>
   * The master component carries the answer to the whole meeting; an override
   * component carrying a RECURRENCE-ID is a client answering a <b>single
   * occurrence</b>, and it is mapped to agenda's own per-occurrence shape: the
   * exceptional occurrence is looked up — created when the answer is the first
   * thing to distinguish that instance — and the response recorded on it,
   * exactly as agenda's own REST endpoint answers one occurrence. The master
   * is adopted first, deliberately: recording a series response resets the
   * responses of its exceptional occurrences, so the other order would erase
   * the per-occurrence answers just adopted.
   *
   * @param userIdentityId identity of the account's owner
   * @param localEventId the agenda event the object's mapping row names
   * @param remoteIcs the object as the client left it on the server
   * @return whether anything was adopted, and whether adopting it failed
   */
  public Outcome adoptAnswer(long userIdentityId, long localEventId, String remoteIcs) {
    String email = ownEmail(userIdentityId);
    // The address the user's CalDAV account answers to. Copies name their
    // owner by it, so that a client recognises the event as an invitation to
    // itself; older copies name them by their eXo profile address, and both
    // are accepted.
    CaldavUserSetting account = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    String accountAddress = account == null ? null : StringUtils.trimToNull(account.getUsername());
    if (StringUtils.isBlank(email) && StringUtils.isBlank(accountAddress)) {
      // Without a visible address there is no line to read: the write side
      // leaves an address-less owner off the roster, so there is nothing on
      // the object that is theirs to answer on.
      LOG.debug("User {} exposes no email address; no answer can be read off their copies", userIdentityId);
      return Outcome.NOTHING;
    }
    List<IcsEvent> parsed = icsParser.parse(remoteIcs);
    if (parsed.isEmpty()) {
      return Outcome.NOTHING;
    }
    long seriesId = seriesIdOf(localEventId);
    if (seriesId <= 0) {
      return Outcome.NOTHING;
    }
    boolean adopted = false;
    try {
      for (IcsEvent component : parsed) {
        EventAttendeeResponse answer = answerOf(component, email, accountAddress);
        if (answer == null) {
          continue;
        }
        if (StringUtils.isBlank(component.getOccurrenceId())) {
          adopted |= adoptOnSeries(userIdentityId, seriesId, answer);
        } else {
          adopted |= adoptOnOccurrence(userIdentityId, seriesId, component, answer);
        }
      }
    } catch (Exception e) { // NOSONAR agenda declares several checked exceptions on this path
      // An answer was found and could not be recorded. Saying FAILED is what
      // stops the caller repairing the object — a repair here would overwrite
      // the one record of the user's answer. The next pass reads it again.
      LOG.warn("An answer on the copy of event {} could not be recorded for user {}; the copy is left untouched",
               localEventId,
               userIdentityId,
               e);
      return Outcome.FAILED;
    }
    return adopted ? Outcome.ADOPTED : Outcome.NOTHING;
  }

  /**
   * Whether a copy carries an answer of the owner's that agenda does not hold
   * (EXO-89814) — asked, and answered, without recording anything.
   *
   * <p>
   * <b>What it is for.</b> The caller is about to <i>overwrite</i> the copy —
   * a settings round repairing a copy whose content differs while its version
   * never moved — and needs to know whether doing so would destroy the only
   * record of an answer. That is a different question from
   * {@link #adoptAnswer(long, long, String)}: adopting needs the direction
   * proven and this does not, because refusing to write is safe whichever way
   * the difference runs.
   *
   * <p>
   * The same three refusals apply and are the whole of the answer's meaning:
   * a line that is not the owner's is not theirs to answer on, NEEDS-ACTION is
   * not an answer, and an answer agenda already holds is not one at risk. So a
   * copy eXo has just written — every one of which carries NEEDS-ACTION until
   * somebody answers — reads as holding nothing, which is what keeps a settings
   * round doing its work on the copies that have nothing to lose.
   *
   * <p>
   * Occurrences are read as the series is: an override carrying a
   * RECURRENCE-ID states an answer to one instance, and losing that is the same
   * loss. It is compared against the response agenda gives for that occurrence,
   * exceptional or not, which is the same reading {@link #adoptOnOccurrence}
   * does before deciding there is nothing to record.
   *
   * <p>
   * Reads only. Nothing here creates an exceptional occurrence or sends a
   * response — a question asked on the way to refusing a write must not itself
   * change what agenda holds.
   *
   * @param userIdentityId identity of the account's owner
   * @param localEventId the agenda event the copy's mapping row names
   * @param remoteIcs the copy as the server holds it
   * @return true when the copy states an answer of the owner's that agenda does
   *         not already hold
   */
  public boolean holdsUnrecordedAnswer(long userIdentityId, long localEventId, String remoteIcs) {
    String email = ownEmail(userIdentityId);
    CaldavUserSetting account = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    String accountAddress = account == null ? null : StringUtils.trimToNull(account.getUsername());
    if (StringUtils.isBlank(email) && StringUtils.isBlank(accountAddress)) {
      return false;
    }
    List<IcsEvent> parsed = icsParser.parse(remoteIcs);
    if (parsed.isEmpty()) {
      return false;
    }
    long seriesId = seriesIdOf(localEventId);
    if (seriesId <= 0) {
      return false;
    }
    try {
      for (IcsEvent component : parsed) {
        EventAttendeeResponse answer = answerOf(component, email, accountAddress);
        if (answer != null && answer != recordedAnswer(userIdentityId, seriesId, component)) {
          return true;
        }
      }
    } catch (Exception e) { // NOSONAR agenda declares several checked exceptions on this path
      // Agenda could not say what it holds, so nothing can be concluded about
      // what would be lost. Refusing the write is the safe reading of a
      // question that could not be answered: an unrepaired copy costs a
      // setting, a repaired one can cost an answer.
      LOG.debug("What agenda holds for event {} could not be read; the copy is treated as carrying an answer",
                localEventId,
                e);
      return true;
    }
    return false;
  }

  /**
   * The answer agenda holds for the meeting one component stands for.
   *
   * @param userIdentityId identity of the account's owner
   * @param seriesId the agenda event the master component stands for
   * @param component the parsed component, master or override
   * @return the recorded response, or null when there is none to read
   * @throws Exception when agenda refuses the read
   */
  private EventAttendeeResponse recordedAnswer(long userIdentityId,
                                               long seriesId,
                                               IcsEvent component) throws Exception { // NOSONAR
    if (StringUtils.isBlank(component.getOccurrenceId())) {
      return agendaEventAttendeeService.getEventResponse(seriesId, null, userIdentityId);
    }
    ZonedDateTime occurrenceId = icsEventMapper.occurrenceOf(component);
    if (occurrenceId == null) {
      // An override whose instance cannot be identified states an answer to
      // nothing this can compare. Read as the series' own so it is not counted
      // as a loss on its own.
      return agendaEventAttendeeService.getEventResponse(seriesId, null, userIdentityId);
    }
    Event occurrence = agendaEventService.getExceptionalOccurrenceEvent(seriesId, occurrenceId);
    return occurrence == null ? agendaEventAttendeeService.getEventResponse(seriesId, occurrenceId, userIdentityId)
                              : agendaEventAttendeeService.getEventResponse(occurrence.getId(), null, userIdentityId);
  }

  /**
   * Records an answer against the series — or the single event, which agenda
   * treats the same way — when it differs from the one agenda already holds.
   *
   * @param userIdentityId identity of the account's owner
   * @param seriesId the agenda event the master component stands for
   * @param answer the answer the client wrote
   * @return true when agenda's record changed
   * @throws Exception when agenda refuses the read or the write
   */
  private boolean adoptOnSeries(long userIdentityId, long seriesId, EventAttendeeResponse answer) throws Exception { // NOSONAR
    EventAttendeeResponse current = agendaEventAttendeeService.getEventResponse(seriesId, null, userIdentityId);
    if (current == answer) {
      return false;
    }
    agendaEventAttendeeService.sendEventResponse(seriesId, userIdentityId, answer);
    LOG.info("User {} answered event {} as {} from their own calendar; the answer is recorded",
             userIdentityId,
             seriesId,
             answer);
    return true;
  }

  /**
   * Records an answer against one occurrence of a series, creating the
   * exceptional occurrence when the answer is the first thing to set that
   * instance apart.
   *
   * @param userIdentityId identity of the account's owner
   * @param seriesId the agenda series the override belongs to
   * @param override the parsed component carrying the RECURRENCE-ID
   * @param answer the answer the client wrote
   * @return true when agenda's record changed
   * @throws Exception when agenda refuses the read or the write
   */
  private boolean adoptOnOccurrence(long userIdentityId,
                                    long seriesId,
                                    IcsEvent override,
                                    EventAttendeeResponse answer) throws Exception { // NOSONAR
    ZonedDateTime occurrenceId = icsEventMapper.occurrenceOf(override);
    if (occurrenceId == null) {
      LOG.debug("An override of event {} carries a recurrence identifier that cannot be read; its answer is not adopted",
                seriesId);
      return false;
    }
    Event occurrence = agendaEventService.getExceptionalOccurrenceEvent(seriesId, occurrenceId);
    if (occurrence == null) {
      if (agendaEventAttendeeService.getEventResponse(seriesId, occurrenceId, userIdentityId) == answer) {
        // The occurrence answers as the series does, and that is already what
        // the client wrote: materialising an exceptional occurrence would
        // record a distinction that does not exist.
        return false;
      }
      occurrence = agendaEventService.saveEventExceptionalOccurrence(seriesId, occurrenceId);
    } else if (agendaEventAttendeeService.getEventResponse(occurrence.getId(), null, userIdentityId) == answer) {
      return false;
    }
    agendaEventAttendeeService.sendEventResponse(occurrence.getId(), userIdentityId, answer);
    LOG.info("User {} answered occurrence {} of event {} as {} from their own calendar; the answer is recorded",
             userIdentityId,
             occurrenceId,
             seriesId,
             answer);
    return true;
  }

  /**
   * The answer one component carries for the account's owner, when it is one
   * agenda can record.
   *
   * <p>
   * NEEDS-ACTION is deliberately not an answer: the tokens adopted are the
   * three that say something. A client resetting an answered meeting back to
   * "needs action" is un-answering, and adopting that would erase a recorded
   * answer on the strength of a state most clients never write on purpose.
   *
   * @param component the parsed component
   * @param email the owner's own address, as the write side spells it
   * @param accountAddress the address the owner's CalDAV account answers to,
   *          which is how copies spell their own line now. Both spellings are
   *          accepted here, for the reason
   *          {@link #isOwner(String, String, String)} records.
   * @return the response to record, or null when the component carries none
   */
  private EventAttendeeResponse answerOf(IcsEvent component, String email, String accountAddress) {
    if (component.getAttendees() == null) {
      return null;
    }
    for (IcsPerson attendee : component.getAttendees()) {
      if (attendee == null || !isOwner(attendee.getEmail(), email, accountAddress)) {
        continue;
      }
      String response = IcsText.agendaResponse(attendee.getResponse());
      if (response == null || "NEEDS_ACTION".equals(response)) {
        return null;
      }
      return EventAttendeeResponse.valueOf(response);
    }
    return null;
  }

  /**
   * The agenda event answers attach to: the series master when the mapping
   * row happens to name one of its occurrences, the event itself otherwise.
   *
   * @param localEventId the agenda event the mapping row names
   * @return the series identifier, or 0 when the event no longer exists
   */
  private long seriesIdOf(long localEventId) {
    Event event = agendaEventService.getEventById(localEventId);
    if (event == null) {
      return 0;
    }
    return event.getParentId() > 0 ? event.getParentId() : event.getId();
  }

  /**
   * Whether an attendee line is the owner's own.
   *
   * <p>
   * Two spellings are accepted because two exist. Copies are written with the
   * address the user's CalDAV account answers to, so that a client recognises
   * the event as an invitation to itself; copies written before that carry
   * the eXo profile address. Refusing the older spelling would make every
   * answer on an existing copy unreadable.
   *
   * <p>
   * <b>Three spellings, really, and the third is the server's.</b> The address
   * arrives here with its {@code mailto:} scheme already off — stripped
   * case-insensitively by {@link org.exoplatform.caldav.ics.IcsParser}, which
   * matters because a server may hand back what eXo wrote in a different case
   * from the one eXo wrote it in: BlueMind returns {@code MAILTO:} for the
   * lowercase {@code mailto:} eXo sends (EXO-89807), and a URI scheme is
   * case-insensitive per RFC 3986. The address itself is compared the same way
   * for the same reason. Both are pinned by tests using the live payload's own
   * spelling, because this is the shape that makes an answer arrive, be seen,
   * and be attributed to nobody.
   *
   * @param candidate the address on the attendee line
   * @param profileEmail the owner's eXo profile address
   * @param accountAddress the address the owner's CalDAV account answers to
   * @return true when the line belongs to the owner
   */
  private boolean isOwner(String candidate, String profileEmail, String accountAddress) {
    String address = StringUtils.trimToNull(candidate);
    if (address == null) {
      return false;
    }
    return StringUtils.equalsIgnoreCase(address, profileEmail)
        || StringUtils.equalsIgnoreCase(address, accountAddress);
  }

  /**
   * The account owner's own address — the same one the write side puts on
   * their ATTENDEE line, which is what makes the match unambiguous.
   *
   * @param userIdentityId identity of the account's owner
   * @return the address, or null when their profile exposes none
   */
  private String ownEmail(long userIdentityId) {
    Identity identity = identityManager.getIdentity(userIdentityId);
    if (identity == null || identity.getProfile() == null) {
      return null;
    }
    return StringUtils.trimToNull(identity.getProfile().getEmail());
  }
}
