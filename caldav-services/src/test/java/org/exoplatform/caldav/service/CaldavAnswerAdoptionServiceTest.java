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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.caldav.ics.IcsEventMapper;
import org.exoplatform.caldav.ics.IcsParser;
import org.exoplatform.caldav.service.CaldavAnswerAdoptionService.Outcome;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The one field that flows back from a user's calendar into eXo: their own
 * PARTSTAT, on their own copy (EXO-89681).
 *
 * <p>
 * What matters as much as the adoption is everything this refuses to adopt —
 * another attendee's line, a token agenda has no word for, an un-answering —
 * because every refusal is a line the mirror does not cross on its way to
 * becoming a two-way calendar, which it must not become.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavAnswerAdoptionServiceTest {

  private static final long           USER    = 42L;

  private static final long           EVENT   = 5L;

  private static final String         EMAIL   = "john@example.test";

  @Mock
  private IdentityManager             identityManager;

  /**
   * Holds the address the user's account answers to. Left answering null in
   * most cases: a copy written before that address was used names its owner
   * by their eXo profile address, and both spellings must keep working.
   */
  @Mock
  private CaldavConnectorStorage      caldavConnectorStorage;

  @Mock
  private AgendaEventService          agendaEventService;

  @Mock
  private AgendaEventAttendeeService  agendaEventAttendeeService;

  @Spy
  private IcsParser                   icsParser;

  @Spy
  private IcsEventMapper              icsEventMapper;

  @InjectMocks
  private CaldavAnswerAdoptionService service;

  @BeforeEach
  public void aUserWithAVisibleAddressAndAKnownEvent() {
    givenOwnEmail(EMAIL);
    lenient().when(agendaEventService.getEventById(EVENT)).thenReturn(event(EVENT, 0));
  }

  @Test
  public void anAnswerTheClientWroteIsRecordedInAgenda() throws Exception {
    when(agendaEventAttendeeService.getEventResponse(EVENT, null, USER)).thenReturn(EventAttendeeResponse.NEEDS_ACTION);

    Outcome outcome = service.adoptAnswer(USER, EVENT, object("ATTENDEE;PARTSTAT=ACCEPTED:mailto:" + EMAIL));

    assertEquals(Outcome.ADOPTED, outcome);
    verify(agendaEventAttendeeService).sendEventResponse(EVENT, USER, EventAttendeeResponse.ACCEPTED);
  }

  @Test
  public void anAnswerAgendaAlreadyHoldsChangesNothing() throws Exception {
    // Idempotence is what lets the caller re-read the same object safely: an
    // answer already recorded is not an event, however many passes see it.
    when(agendaEventAttendeeService.getEventResponse(EVENT, null, USER)).thenReturn(EventAttendeeResponse.ACCEPTED);

    Outcome outcome = service.adoptAnswer(USER, EVENT, object("ATTENDEE;PARTSTAT=ACCEPTED:mailto:" + EMAIL));

    assertEquals(Outcome.NOTHING, outcome);
    verify(agendaEventAttendeeService, never()).sendEventResponse(anyLong(), anyLong(), any());
  }

  @Test
  public void unAnsweringIsNotAnAnswer() {
    // A client resetting a copy to NEEDS-ACTION is not the user saying
    // something; adopting it would erase a recorded answer on the strength of
    // a state most clients never write on purpose.
    Outcome outcome = service.adoptAnswer(USER, EVENT, object("ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:" + EMAIL));

    assertEquals(Outcome.NOTHING, outcome);
  }

  @Test
  public void aTokenAgendaHasNoWordForIsLeftAlone() {
    // DELEGATED is a real RFC 5545 answer and not one agenda can record;
    // guessing the nearest one would put words in the user's mouth.
    Outcome outcome = service.adoptAnswer(USER, EVENT, object("ATTENDEE;PARTSTAT=DELEGATED:mailto:" + EMAIL));

    assertEquals(Outcome.NOTHING, outcome);
  }

  /**
   * The server's own spelling of the scheme is still the owner's line.
   */
  @Test
  public void anAnswerWrittenBackWithAnUppercaseSchemeIsStillTheOwnersOwn() throws Exception {
    // Taken verbatim from the live copy that lost an answer on the rig: eXo
    // writes "mailto:" and BlueMind hands the same line back as "MAILTO:".
    // A URI scheme is case-insensitive (RFC 3986), so this is one address in
    // two spellings — but an adoption comparing it as text finds the answer,
    // fails to attribute it, and reports the one outcome nobody investigates:
    // NOTHING, on an object that plainly carries an answer.
    when(agendaEventAttendeeService.getEventResponse(EVENT, null, USER)).thenReturn(EventAttendeeResponse.NEEDS_ACTION);

    Outcome outcome = service.adoptAnswer(USER,
                                          EVENT,
                                          object("ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED;CN=FRANCOIS;"
                                              + "DIR=\"bm://19d43481671.internal/users/751E6D1A\":MAILTO:" + EMAIL));

    assertEquals(Outcome.ADOPTED, outcome);
    verify(agendaEventAttendeeService).sendEventResponse(EVENT, USER, EventAttendeeResponse.ACCEPTED);
  }

  /**
   * A server may also return the address itself in another case.
   */
  @Test
  public void anAnswerWrittenBackInAnotherCaseIsStillTheOwnersOwn() throws Exception {
    // The scheme is not the only half a server may re-case. The local part of
    // an address is formally case-sensitive, but no mail system treats it so,
    // and a copy eXo wrote for john@ that comes back naming John@ is the same
    // person's line — the alternative is an answer silently attributed to
    // nobody, which is exactly the failure this pins.
    lenient().when(agendaEventAttendeeService.getEventResponse(EVENT, null, USER))
             .thenReturn(EventAttendeeResponse.NEEDS_ACTION);

    Outcome outcome = service.adoptAnswer(USER,
                                          EVENT,
                                          object("ATTENDEE;PARTSTAT=DECLINED:MAILTO:" + EMAIL.toUpperCase()));

    assertEquals(Outcome.ADOPTED, outcome);
  }

  @Test
  public void anotherAttendeesLineIsNeverRead() {
    // The narrow boundary itself: one line, matched on the account's own
    // address. Another attendee's PARTSTAT is content, and content must not
    // act on a platform user's behalf.
    Outcome outcome = service.adoptAnswer(USER, EVENT, object("ATTENDEE;PARTSTAT=ACCEPTED:mailto:someone.else@example.test"));

    assertEquals(Outcome.NOTHING, outcome);
  }

  @Test
  public void aUserWithNoVisibleAddressHasNoLineToAnswerOn() {
    // The write side leaves an address-less owner off the roster, so nothing
    // on the object is theirs — symmetric refusals on the two directions.
    givenOwnEmail(null);

    Outcome outcome = service.adoptAnswer(USER, EVENT, object("ATTENDEE;PARTSTAT=ACCEPTED:mailto:" + EMAIL));

    assertEquals(Outcome.NOTHING, outcome);
  }

  @Test
  public void anAnswerOnAVanishedEventHasNowhereToGo() {
    when(agendaEventService.getEventById(EVENT)).thenReturn(null);

    Outcome outcome = service.adoptAnswer(USER, EVENT, object("ATTENDEE;PARTSTAT=ACCEPTED:mailto:" + EMAIL));

    assertEquals(Outcome.NOTHING, outcome);
  }

  @Test
  public void anAnswerThatCannotBeRecordedSaysSoRatherThanVanishing() throws Exception {
    // FAILED is what stops the caller repairing the object: the copy still
    // holds the only record of the user's answer, and overwriting it on a
    // transient agenda failure would lose it for good.
    when(agendaEventAttendeeService.getEventResponse(EVENT, null, USER)).thenReturn(EventAttendeeResponse.NEEDS_ACTION);
    org.mockito.Mockito.doThrow(new ObjectNotFoundException("gone"))
                       .when(agendaEventAttendeeService)
                       .sendEventResponse(EVENT, USER, EventAttendeeResponse.ACCEPTED);

    Outcome outcome = service.adoptAnswer(USER, EVENT, object("ATTENDEE;PARTSTAT=ACCEPTED:mailto:" + EMAIL));

    assertEquals(Outcome.FAILED, outcome);
  }

  @Test
  public void aSingleOccurrenceAnswerBecomesAPerOccurrenceResponse() throws Exception {
    // A client accepting one instance of a series writes an override carrying
    // RECURRENCE-ID. It maps to agenda's own per-occurrence shape — the
    // exceptional occurrence is created when the answer is the first thing to
    // distinguish that instance, exactly as agenda's REST answers one.
    when(agendaEventAttendeeService.getEventResponse(eq(EVENT), any(ZonedDateTime.class), eq(USER)))
                                                                                                   .thenReturn(EventAttendeeResponse.NEEDS_ACTION);
    when(agendaEventService.getExceptionalOccurrenceEvent(eq(EVENT), any(ZonedDateTime.class))).thenReturn(null);
    when(agendaEventService.saveEventExceptionalOccurrence(eq(EVENT), any(ZonedDateTime.class))).thenReturn(event(9L, EVENT));

    Outcome outcome = service.adoptAnswer(USER,
                                          EVENT,
                                          series("ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:" + EMAIL,
                                                 "20260902T100000Z",
                                                 "ATTENDEE;PARTSTAT=DECLINED:mailto:" + EMAIL));

    assertEquals(Outcome.ADOPTED, outcome);
    verify(agendaEventAttendeeService).sendEventResponse(9L, USER, EventAttendeeResponse.DECLINED);
  }

  @Test
  public void anOccurrenceAlreadyAnsweringThatWayIsLeftUnmaterialised() throws Exception {
    // Creating an exceptional occurrence records a distinction; when the
    // series already answers as the override does, there is none to record.
    lenient().when(agendaEventAttendeeService.getEventResponse(eq(EVENT), any(ZonedDateTime.class), eq(USER)))
             .thenReturn(EventAttendeeResponse.DECLINED);
    lenient().when(agendaEventAttendeeService.getEventResponse(eq(EVENT), isNull(), eq(USER)))
             .thenReturn(EventAttendeeResponse.NEEDS_ACTION);
    when(agendaEventService.getExceptionalOccurrenceEvent(eq(EVENT), any(ZonedDateTime.class))).thenReturn(null);

    Outcome outcome = service.adoptAnswer(USER,
                                          EVENT,
                                          series("ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:" + EMAIL,
                                                 "20260902T100000Z",
                                                 "ATTENDEE;PARTSTAT=DECLINED:mailto:" + EMAIL));

    assertEquals(Outcome.NOTHING, outcome);
  }

  @Test
  public void theSeriesAnswerIsRecordedBeforeTheOccurrenceOnes() throws Exception {
    // Recording a series response resets its exceptional occurrences, so the
    // other order would erase the per-occurrence answer just adopted.
    when(agendaEventAttendeeService.getEventResponse(eq(EVENT), isNull(), eq(USER)))
                                                                                   .thenReturn(EventAttendeeResponse.NEEDS_ACTION);
    when(agendaEventAttendeeService.getEventResponse(eq(EVENT), any(ZonedDateTime.class), eq(USER)))
                                                                                                   .thenReturn(EventAttendeeResponse.NEEDS_ACTION);
    when(agendaEventService.getExceptionalOccurrenceEvent(eq(EVENT), any(ZonedDateTime.class))).thenReturn(null);
    when(agendaEventService.saveEventExceptionalOccurrence(eq(EVENT), any(ZonedDateTime.class))).thenReturn(event(9L, EVENT));

    service.adoptAnswer(USER,
                        EVENT,
                        series("ATTENDEE;PARTSTAT=ACCEPTED:mailto:" + EMAIL,
                               "20260902T100000Z",
                               "ATTENDEE;PARTSTAT=DECLINED:mailto:" + EMAIL));

    InOrder order = inOrder(agendaEventAttendeeService);
    order.verify(agendaEventAttendeeService).sendEventResponse(EVENT, USER, EventAttendeeResponse.ACCEPTED);
    order.verify(agendaEventAttendeeService).sendEventResponse(9L, USER, EventAttendeeResponse.DECLINED);
  }

  @Test
  public void aMappingRowNamingAnOccurrenceStillAnswersTheSeries() throws Exception {
    // A mapping row may name an exceptional occurrence rather than the
    // master; the master component's answer still belongs to the series.
    when(agendaEventService.getEventById(77L)).thenReturn(event(77L, EVENT));
    when(agendaEventAttendeeService.getEventResponse(EVENT, null, USER)).thenReturn(EventAttendeeResponse.NEEDS_ACTION);

    Outcome outcome = service.adoptAnswer(USER, 77L, object("ATTENDEE;PARTSTAT=TENTATIVE:mailto:" + EMAIL));

    assertEquals(Outcome.ADOPTED, outcome);
    verify(agendaEventAttendeeService).sendEventResponse(EVENT, USER, EventAttendeeResponse.TENTATIVE);
  }

  /**
   * @param attendeeLine the ATTENDEE content line of the master component
   * @return a one-master calendar object carrying it
   */
  private String object(String attendeeLine) {
    return series(attendeeLine, null, null);
  }

  /**
   * @param masterAttendeeLine the master component's ATTENDEE content line
   * @param recurrenceId the instance an override amends, or null for none
   * @param overrideAttendeeLine the override's ATTENDEE content line
   * @return the calendar object, master first as a server stores it
   */
  private String series(String masterAttendeeLine, String recurrenceId, String overrideAttendeeLine) {
    StringBuilder ics = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n");
    ics.append("BEGIN:VEVENT\r\nUID:uid-1\r\nDTSTART:20260901T100000Z\r\nDTEND:20260901T110000Z\r\n")
       .append("RRULE:FREQ=DAILY\r\n")
       .append(masterAttendeeLine)
       .append("\r\nEND:VEVENT\r\n");
    if (recurrenceId != null) {
      ics.append("BEGIN:VEVENT\r\nUID:uid-1\r\nRECURRENCE-ID:").append(recurrenceId)
         .append("\r\nDTSTART:").append(recurrenceId)
         .append("\r\nDTEND:20260902T110000Z\r\n")
         .append(overrideAttendeeLine)
         .append("\r\nEND:VEVENT\r\n");
    }
    return ics.append("END:VCALENDAR\r\n").toString();
  }

  /**
   * @param id the event identifier
   * @param parentId its parent, 0 for a master
   * @return the agenda event
   */
  private Event event(long id, long parentId) {
    Event event = new Event();
    event.setId(id);
    event.setParentId(parentId);
    return event;
  }

  /**
   * @param email the address the profile exposes, or null for none
   */
  private void givenOwnEmail(String email) {
    Identity identity = new Identity(String.valueOf(USER));
    Profile profile = new Profile(identity);
    if (email != null) {
      profile.setProperty(Profile.EMAIL, email);
    }
    identity.setProfile(profile);
    lenient().when(identityManager.getIdentity(USER)).thenReturn(identity);
  }
}
